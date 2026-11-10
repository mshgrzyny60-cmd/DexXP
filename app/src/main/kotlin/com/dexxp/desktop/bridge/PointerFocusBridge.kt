package com.dexxp.desktop.bridge

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dexxp.desktop.browser.DesktopWebActivity
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.ExternalWindowFrame
import com.dexxp.desktop.model.PointerState
import com.dexxp.desktop.model.ShellSurfaceMode
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.windowing.WindowSnappingController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Secondary-display window focus arbiter, zero-allocation `SOURCE_MOUSE` event synthesizer,
 * floating titlebar click/hover bridge, and edge/quadrant drag-snap coordinator.
 */
class PointerFocusBridge(
    private val sessionRepository: DesktopSessionRepository,
    private val snappingController: WindowSnappingController
) {
    data class ShellWindowRegistration(
        val window: Window,
        val screenBoundsProvider: () -> Rect
    )

    data class OverlayViewRegistration(
        val rootView: View,
        val screenBoundsProvider: () -> Rect?
    )

    data class SecondaryRightClickEvent(
        val screenX: Float,
        val screenY: Float,
        val timestampMs: Long = SystemClock.uptimeMillis()
    )

    private val _pointerState = MutableStateFlow(PointerState())
    val pointerState: StateFlow<PointerState> = _pointerState.asStateFlow()

    private val _detectedExternalFrames = MutableStateFlow<List<ExternalWindowFrame>>(emptyList())
    val detectedExternalFrames: StateFlow<List<ExternalWindowFrame>> = _detectedExternalFrames.asStateFlow()

    private val _rightClickEvents = MutableSharedFlow<SecondaryRightClickEvent>(extraBufferCapacity = 8)
    val rightClickEvents: SharedFlow<SecondaryRightClickEvent> = _rightClickEvents.asSharedFlow()

    @Volatile
    var appLauncherRef: AppLauncher? = null

    @Volatile
    var ensurePresentationShowingCallback: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastBottomEdgeRevealMs: Long = 0L

    @Volatile
    private var forceTaskbarRevealUntilMs: Long = 0L

    @Volatile
    private var presentationTarget: ShellWindowRegistration? = null

    @Volatile
    private var backdropTarget: ShellWindowRegistration? = null

    @Volatile
    private var titlebarOverlayTarget: OverlayViewRegistration? = null

    @Volatile
    private var externalWindowBounds: List<Rect> = emptyList()

    @Volatile
    private var activeDownTimeMs: Long = 0L

    @Volatile
    private var activeDragTargetIsShell: Boolean = false

    @Volatile
    private var activeDragTargetIsTitlebar: Boolean = false

    @Volatile
    private var dragOriginX: Float = 0f

    @Volatile
    private var dragOriginY: Float = 0f

    @Volatile
    private var lastHardwareX: Float = 960f

    @Volatile
    private var lastHardwareY: Float = 540f

    @Volatile
    var lastHardwarePointerActivityElapsedMs: Long = 0L
        private set

    // Pre-allocated reusable buffers eliminating 120-240Hz pointer hover/drag GC churn
    private val dispatchLock = Any()
    private val reusableTitlebarBounds = Rect()
    private val reusableWebAppBounds = Rect()
    private val releasePrimaryButtonRunnable = Runnable {
        _pointerState.update { current ->
            if (!current.isDragging && current.isPrimaryDown) {
                current.copy(isPrimaryDown = false)
            } else {
                current
            }
        }
    }
    private val releaseSecondaryButtonRunnable = Runnable {
        _pointerState.update { current ->
            if (current.isSecondaryDown) current.copy(isSecondaryDown = false) else current
        }
    }
    private val reusablePointerProps = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
    )
    private val reusablePointerCoords = arrayOf(MotionEvent.PointerCoords())

    fun configureDisplayDimensions(widthPx: Int, heightPx: Int) {
        val safeW = widthPx.coerceAtLeast(320)
        val safeH = heightPx.coerceAtLeast(240)
        lastHardwareX = if (_pointerState.value.lastInteractionMs == 0L) safeW / 2f else lastHardwareX.coerceIn(0f, safeW - 1f)
        lastHardwareY = if (_pointerState.value.lastInteractionMs == 0L) safeH / 2f else lastHardwareY.coerceIn(0f, safeH - 1f)
        _pointerState.update { current ->
            current.copy(
                x = if (current.lastInteractionMs == 0L) safeW / 2f else current.x.coerceIn(0f, safeW - 1f),
                y = if (current.lastInteractionMs == 0L) safeH / 2f else current.y.coerceIn(0f, safeH - 1f),
                displayWidth = safeW,
                displayHeight = safeH
            )
        }
    }

    fun registerPresentationWindow(window: Window, boundsProvider: () -> Rect) {
        presentationTarget = ShellWindowRegistration(window, boundsProvider)
    }

    fun unregisterPresentationWindow(window: Window) {
        if (presentationTarget?.window === window) {
            presentationTarget = null
        }
    }

    fun registerBackdropWindow(window: Window, boundsProvider: () -> Rect) {
        backdropTarget = ShellWindowRegistration(window, boundsProvider)
    }

    fun unregisterBackdropWindow(window: Window) {
        if (backdropTarget?.window === window) {
            backdropTarget = null
        }
    }

    fun registerTitlebarOverlayView(rootView: View, boundsProvider: () -> Rect?) {
        titlebarOverlayTarget = OverlayViewRegistration(rootView, boundsProvider)
    }

    fun unregisterTitlebarOverlayView(rootView: View) {
        if (titlebarOverlayTarget?.rootView === rootView) {
            titlebarOverlayTarget = null
        }
    }

    fun updateDetectedExternalWindows(
        bounds: List<Rect>,
        frames: List<ExternalWindowFrame>,
        focusedPackage: String?,
        authoritative: Boolean = false
    ) {
        externalWindowBounds = bounds
        _detectedExternalFrames.value = frames
        sessionRepository.updateExternalWindowVisibility(
            hasVisibleExternalApp = bounds.isNotEmpty(),
            focusedPackage = focusedPackage,
            detectedPackages = frames.map { it.packageName },
            authoritative = authoritative
        )
    }

    /**
     * Purges all display-scoped window/overlay references and resets active drag/button states
     * immediately when an HDMI / USB-C DP monitor disconnects mid-session.
     */
    fun onExternalDisplayDisconnected() {
        mainHandler.removeCallbacks(releasePrimaryButtonRunnable)
        mainHandler.removeCallbacks(releaseSecondaryButtonRunnable)
        presentationTarget = null
        backdropTarget = null
        titlebarOverlayTarget = null
        externalWindowBounds = emptyList()
        _detectedExternalFrames.value = emptyList()
        activeDragTargetIsShell = false
        activeDragTargetIsTitlebar = false
        snappingController.clearPreview()
        sessionRepository.updateExternalWindowVisibility(
            hasVisibleExternalApp = false,
            focusedPackage = null
        )
        _pointerState.update {
            it.copy(
                isPrimaryDown = false,
                isSecondaryDown = false,
                isDragging = false
            )
        }
    }

    fun applyPresentationWindowPolicy(
        window: Window,
        mode: ShellSurfaceMode,
        displayWidthPx: Int,
        displayHeightPx: Int,
        densityDpi: Int,
        needsKeyboardFocus: Boolean = true
    ) {
        val decorView = window.peekDecorView() ?: return
        val lp = window.attributes ?: return
        val density = densityDpi / 160f
        val taskbarHeightPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()

        val isTvMode = sessionRepository.environmentMode.value == DesktopEnvironmentMode.TV_LEANBACK
        val isLaunching = sessionRepository.launchingPackage.value != null
        val isBottomEdgeForceRevealed = SystemClock.uptimeMillis() < forceTaskbarRevealUntilMs
        val hasActiveApp = sessionRepository.runningWindows.value.any { !it.isMinimized } &&
            !sessionRepository.isShowDesktopActive.value &&
            !isLaunching

        val (targetWidth, targetHeight, targetGravity) = when {
            isTvMode && hasActiveApp && !isBottomEdgeForceRevealed -> {
                // When an app is active on TV (and not mid-launch or bottom-edge revealed), hide presentation dialog so the app takes 100% of the screen
                Triple(0, 0, Gravity.TOP or Gravity.START)
            }
            mode == ShellSurfaceMode.DOCKED_TASKBAR -> {
                Triple(WindowManager.LayoutParams.MATCH_PARENT, taskbarHeightPx, Gravity.BOTTOM)
            }
            else -> {
                Triple(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.BOTTOM)
            }
        }

        // Capture prior attributes BEFORE in-place mutation (`window.attributes` returns live mWindowAttributes)
        val prevWidth = lp.width
        val prevHeight = lp.height
        val prevGravity = lp.gravity
        val prevFlags = lp.flags
        val prevFormat = lp.format

        var flags = prevFlags
        flags = flags or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()

        if (mode == ShellSurfaceMode.DOCKED_TASKBAR) {
            flags = flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        } else {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        // On Android 13/14 secondary displays, TYPE_PRIVATE_PRESENTATION belongs to APPLICATION_LAYER.
        // If FLAG_NOT_FOCUSABLE is set while an external app is running, InputDispatcher directs all
        // mouse pointer events to the focused external app behind it.
        // Therefore, we MUST clear FLAG_NOT_FOCUSABLE so the presentation window reliably receives mouse clicks!
        // We do NOT steal active keyboard focus because decorView.requestFocus() is ONLY called when
        // a text entry surface (Flyout search, Terminal) explicitly requests it.
        val shouldBeFocusable = when {
            isTvMode && hasActiveApp && !isBottomEdgeForceRevealed -> false
            mode == ShellSurfaceMode.DOCKED_TASKBAR -> true
            mode == ShellSurfaceMode.FLYOUT_ACTIVE -> true
            else -> true
        }
        flags = if (shouldBeFocusable) {
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        window.setBackgroundDrawableResource(android.R.color.transparent)
        decorView.setBackgroundColor(Color.TRANSPARENT)

        if (isTvMode && hasActiveApp && !isBottomEdgeForceRevealed) {
            decorView.visibility = View.GONE
        } else {
            decorView.visibility = View.VISIBLE
        }

        val layoutChanged = prevWidth != targetWidth ||
            prevHeight != targetHeight ||
            prevGravity != targetGravity ||
            prevFlags != flags ||
            prevFormat != PixelFormat.TRANSLUCENT ||
            lp.dimAmount != 0f ||
            lp.x != 0 ||
            lp.y != 0

        if (layoutChanged) {
            lp.width = targetWidth
            lp.height = targetHeight
            lp.gravity = targetGravity
            lp.x = 0
            lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.dimAmount = 0f
            lp.flags = flags
            window.attributes = lp
            if (decorView.isAttachedToWindow) {
                runCatching { window.windowManager.updateViewLayout(decorView, lp) }
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            if (mode == ShellSurfaceMode.FLYOUT_ACTIVE && needsKeyboardFocus) {
                decorView.post { decorView.requestFocus() }
            } else if (decorView.hasFocus() && mode == ShellSurfaceMode.DOCKED_TASKBAR) {
                decorView.clearFocus()
            }
        }
    }

    fun onOutsideWindowTouch() {
        if (sessionRepository.anyFlyoutOpen.value) {
            sessionRepository.dismissFlyouts()
        }
    }

    /**
     * Forces the DexXP Presentation (Taskbar / Start Menu / Full Desktop) back to the front of
     * the secondary display's `APPLICATION_LAYER` z-order and re-asserts sticky immersive bars.
     */
    fun bringShellToFront(requestFocus: Boolean = false) {
        val action = Runnable {
            ensurePresentationShowingCallback?.invoke()
            val reg = presentationTarget ?: return@Runnable
            val win = reg.window
            val decor = win.peekDecorView() ?: return@Runnable
            val displayInfo = sessionRepository.activeDisplay.value
            val mode = sessionRepository.shellSurfaceMode.value
            val needsKeyboard = requestFocus ||
                sessionRepository.needsShellKeyboardFocus.value ||
                mode == ShellSurfaceMode.FLYOUT_ACTIVE

            applyPresentationWindowPolicy(
                window = win,
                mode = mode,
                displayWidthPx = displayInfo?.widthPx ?: _pointerState.value.displayWidth,
                displayHeightPx = displayInfo?.heightPx ?: _pointerState.value.displayHeight,
                densityDpi = displayInfo?.scaledDensityDpi ?: 160,
                needsKeyboardFocus = needsKeyboard
            )

            decor.visibility = View.VISIBLE
            WindowCompat.setDecorFitsSystemWindows(win, false)
            WindowInsetsControllerCompat(win, decor).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            decor.bringToFront()
            if (decor.isAttachedToWindow) {
                runCatching { win.windowManager.updateViewLayout(decor, win.attributes) }
            }
            if (requestFocus && mode != ShellSurfaceMode.DOCKED_TASKBAR) {
                decor.post { decor.requestFocus() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Reveals the docked Taskbar immediately when the mouse cursor reaches the bottom 6 pixels
     * (`screenY >= displayHeight - 6`) of an active secondary display.
     */
    fun onBottomEdgeHoverReveal() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBottomEdgeRevealMs < BOTTOM_EDGE_REVEAL_DEBOUNCE_MS) return
        lastBottomEdgeRevealMs = now
        forceTaskbarRevealUntilMs = now + 4500L
        bringShellToFront(requestFocus = false)
    }

    /**
     * Syncs the shared pointer position with a real hardware / scrcpy mouse event.
     *
     * Events originating from a different display than the active desktop display are ignored,
     * so phone-screen mouse movement can never teleport (or pin to a corner) the desktop pointer.
     */
    fun setPointerPosition(
        screenX: Float,
        screenY: Float,
        isDown: Boolean = false,
        sourceDisplayId: Int? = null
    ) {
        if (sourceDisplayId != null) {
            val activeId = sessionRepository.activeDisplay.value?.displayId
            if (activeId != null && activeId != sourceDisplayId) return
        }
        val snapshot = _pointerState.value
        val maxX = (snapshot.displayWidth - 1).coerceAtLeast(1).toFloat()
        val maxY = (snapshot.displayHeight - 1).coerceAtLeast(1).toFloat()
        val nx = screenX.coerceIn(0f, maxX)
        val ny = screenY.coerceIn(0f, maxY)
        lastHardwareX = nx
        lastHardwareY = ny
        lastHardwarePointerActivityElapsedMs = SystemClock.elapsedRealtime()

        if (!isDown && isInBottomEdgeRevealZone(ny, snapshot.displayHeight)) {
            onBottomEdgeHoverReveal()
        }

        // Fast-path: when a physical/scrcpy mouse is hovering without button transitions,
        // skip StateFlow copy/emission completely to eliminate 120-240Hz main-thread snapshot churn.
        if (snapshot.isHardwarePointer && snapshot.visible &&
            snapshot.isPrimaryDown == isDown && !snapshot.isDragging
        ) {
            return
        }

        val now = SystemClock.uptimeMillis()
        _pointerState.update { current ->
            current.copy(
                x = nx,
                y = ny,
                visible = true,
                isPrimaryDown = isDown,
                lastInteractionMs = now,
                isHardwarePointer = true
            )
        }
    }

    private fun currentEffectiveCoordinates(state: PointerState): Pair<Float, Float> {
        return if (state.isHardwarePointer) {
            lastHardwareX to lastHardwareY
        } else {
            state.x to state.y
        }
    }

    fun movePointerBy(deltaX: Float, deltaY: Float) {
        if (deltaX == 0f && deltaY == 0f) return
        val sensitivity = sessionRepository.touchpadSensitivity.value
        val now = SystemClock.uptimeMillis()

        var nextState: PointerState? = null
        _pointerState.update { current ->
            val maxX = (current.displayWidth - 1).coerceAtLeast(1).toFloat()
            val maxY = (current.displayHeight - 1).coerceAtLeast(1).toFloat()
            val baseX = if (current.isHardwarePointer) lastHardwareX else current.x
            val baseY = if (current.isHardwarePointer) lastHardwareY else current.y
            val clampedX = (baseX + deltaX * sensitivity).coerceIn(0f, maxX)
            val clampedY = (baseY + deltaY * sensitivity).coerceIn(0f, maxY)
            if (clampedX == current.x && clampedY == current.y && current.visible &&
                !current.isDragging && !current.isHardwarePointer
            ) {
                nextState = null
                current
            } else {
                val updated = current.copy(
                    x = clampedX,
                    y = clampedY,
                    visible = true,
                    lastInteractionMs = now,
                    isHardwarePointer = false
                )
                nextState = updated
                updated
            }
        }

        val state = nextState ?: return
        if (!state.isPrimaryDown && isInBottomEdgeRevealZone(state.y, state.displayHeight)) {
            onBottomEdgeHoverReveal()
        }
        if (state.isDragging && activeDragTargetIsTitlebar) {
            val reg = titlebarOverlayTarget
            val bounds = reg?.screenBoundsProvider()
            if (reg != null && bounds != null) {
                reusableTitlebarBounds.set(bounds)
                dispatchMouseMotionToOverlayView(
                    registration = reg,
                    bounds = reusableTitlebarBounds,
                    action = MotionEvent.ACTION_MOVE,
                    screenX = state.x,
                    screenY = state.y,
                    buttonState = MotionEvent.BUTTON_PRIMARY,
                    downTime = activeDownTimeMs
                )
                return
            }
        }

        if (state.isDragging && !activeDragTargetIsShell && !activeDragTargetIsTitlebar) {
            snappingController.onPointerDragMoved(
                x = state.x,
                y = state.y,
                displayInfo = sessionRepository.activeDisplay.value
            )
            if (DesktopPointerAccessibilityService.instance == null) {
                dispatchFallbackWebAppDragMove(state.x, state.y)
            }
        }

        val titlebarHit = resolveTitlebarOverlayAt(state.x, state.y, reusableTitlebarBounds)
        if (!state.isPrimaryDown && titlebarHit != null) {
            dispatchMouseMotionToOverlayView(
                registration = titlebarHit,
                bounds = reusableTitlebarBounds,
                action = MotionEvent.ACTION_HOVER_MOVE,
                screenX = state.x,
                screenY = state.y,
                buttonState = 0,
                downTime = now,
                isGenericMotion = true
            )
            return
        }

        val target = resolveShellTargetAt(state.x, state.y)
        if (state.isDragging && activeDragTargetIsShell && target != null) {
            dispatchMouseMotionToShell(
                registration = target,
                action = MotionEvent.ACTION_MOVE,
                screenX = state.x,
                screenY = state.y,
                buttonState = MotionEvent.BUTTON_PRIMARY,
                downTime = activeDownTimeMs
            )
        } else if (!state.isPrimaryDown && target != null) {
            dispatchMouseMotionToShell(
                registration = target,
                action = MotionEvent.ACTION_HOVER_MOVE,
                screenX = state.x,
                screenY = state.y,
                buttonState = 0,
                downTime = now,
                isGenericMotion = true
            )
        }
    }

    fun performPrimaryClick() {
        mainHandler.removeCallbacks(releasePrimaryButtonRunnable)
        val (px, py) = currentEffectiveCoordinates(_pointerState.value)
        val now = SystemClock.uptimeMillis()
        _pointerState.update {
            it.copy(x = px, y = py, visible = true, isPrimaryDown = true, lastInteractionMs = now, isHardwarePointer = false)
        }

        val titlebarHit = resolveTitlebarOverlayAt(px, py, reusableTitlebarBounds)
        if (titlebarHit != null) {
            dispatchMouseMotionToOverlayView(
                titlebarHit,
                reusableTitlebarBounds,
                MotionEvent.ACTION_DOWN,
                px,
                py,
                MotionEvent.BUTTON_PRIMARY,
                now
            )
            dispatchMouseMotionToOverlayView(
                titlebarHit,
                reusableTitlebarBounds,
                MotionEvent.ACTION_UP,
                px,
                py,
                0,
                now
            )
            mainHandler.postDelayed(releasePrimaryButtonRunnable, 40L)
            return
        }

        val shellTarget = resolveShellTargetAt(px, py)
        if (shellTarget != null) {
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_DOWN, px, py, MotionEvent.BUTTON_PRIMARY, now)
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_UP, px, py, 0, now)
        } else {
            val displayId = sessionRepository.activeDisplay.value?.displayId
            val dispatched = if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
                DesktopPointerAccessibilityService.instance?.dispatchTapOnDisplay(
                    displayId = displayId,
                    x = px,
                    y = py,
                    durationMs = 40L
                ) == true
            } else {
                false
            }
            if (!dispatched) {
                dispatchFallbackWebAppClick(px, py, MotionEvent.BUTTON_PRIMARY, now)
            }
        }

        mainHandler.postDelayed(releasePrimaryButtonRunnable, 40L)
    }

    fun performSecondaryClick() {
        mainHandler.removeCallbacks(releaseSecondaryButtonRunnable)
        val (px, py) = currentEffectiveCoordinates(_pointerState.value)
        val now = SystemClock.uptimeMillis()
        _pointerState.update {
            it.copy(x = px, y = py, visible = true, isSecondaryDown = true, lastInteractionMs = now, isHardwarePointer = false)
        }

        val titlebarHit = resolveTitlebarOverlayAt(px, py, reusableTitlebarBounds)
        if (titlebarHit != null) {
            dispatchMouseMotionToOverlayView(
                titlebarHit,
                reusableTitlebarBounds,
                MotionEvent.ACTION_DOWN,
                px,
                py,
                MotionEvent.BUTTON_SECONDARY,
                now
            )
            dispatchMouseMotionToOverlayView(
                titlebarHit,
                reusableTitlebarBounds,
                MotionEvent.ACTION_UP,
                px,
                py,
                0,
                now
            )
            mainHandler.postDelayed(releaseSecondaryButtonRunnable, 60L)
            return
        }

        val shellTarget = resolveShellTargetAt(px, py)
        if (shellTarget != null) {
            _rightClickEvents.tryEmit(SecondaryRightClickEvent(px, py, now))
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_DOWN, px, py, MotionEvent.BUTTON_SECONDARY, now)
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_UP, px, py, 0, now)
        } else {
            val displayId = sessionRepository.activeDisplay.value?.displayId
            val dispatched = if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
                DesktopPointerAccessibilityService.instance?.dispatchTapOnDisplay(
                    displayId = displayId,
                    x = px,
                    y = py,
                    durationMs = 520L
                ) == true
            } else {
                false
            }
            if (!dispatched) {
                dispatchFallbackWebAppClick(px, py, MotionEvent.BUTTON_SECONDARY, now)
            }
        }

        mainHandler.postDelayed(releaseSecondaryButtonRunnable, 60L)
    }

    fun beginDrag() {
        mainHandler.removeCallbacks(releasePrimaryButtonRunnable)
        val (px, py) = currentEffectiveCoordinates(_pointerState.value)
        val now = SystemClock.uptimeMillis()
        activeDownTimeMs = now
        dragOriginX = px
        dragOriginY = py

        val titlebarHit = resolveTitlebarOverlayAt(px, py, reusableTitlebarBounds)
        if (titlebarHit != null) {
            activeDragTargetIsTitlebar = true
            activeDragTargetIsShell = false
            _pointerState.update {
                it.copy(
                    x = px,
                    y = py,
                    isPrimaryDown = true,
                    isDragging = true,
                    visible = true,
                    lastInteractionMs = now,
                    isHardwarePointer = false
                )
            }
            dispatchMouseMotionToOverlayView(
                registration = titlebarHit,
                bounds = reusableTitlebarBounds,
                action = MotionEvent.ACTION_DOWN,
                screenX = px,
                screenY = py,
                buttonState = MotionEvent.BUTTON_PRIMARY,
                downTime = now
            )
            return
        }

        activeDragTargetIsTitlebar = false
        val shellTarget = resolveShellTargetAt(px, py)
        activeDragTargetIsShell = shellTarget != null

        _pointerState.update {
            it.copy(
                x = px,
                y = py,
                isPrimaryDown = true,
                isDragging = true,
                visible = true,
                lastInteractionMs = now,
                isHardwarePointer = false
            )
        }

        if (shellTarget != null) {
            dispatchMouseMotionToShell(
                registration = shellTarget,
                action = MotionEvent.ACTION_DOWN,
                screenX = px,
                screenY = py,
                buttonState = MotionEvent.BUTTON_PRIMARY,
                downTime = now
            )
        } else if (DesktopPointerAccessibilityService.instance == null) {
            resolveFocusedWebAppWindow()?.let { (win, bounds) ->
                dispatchMouseMotionToWindow(
                    window = win,
                    winBounds = bounds,
                    action = MotionEvent.ACTION_DOWN,
                    screenX = px,
                    screenY = py,
                    buttonState = MotionEvent.BUTTON_PRIMARY,
                    downTime = now
                )
            }
        }
    }

    fun endDrag() {
        val state = _pointerState.value
        if (!state.isDragging) return

        if (activeDragTargetIsTitlebar) {
            val reg = titlebarOverlayTarget
            val bounds = reg?.screenBoundsProvider()
            if (reg != null && bounds != null) {
                reusableTitlebarBounds.set(bounds)
                dispatchMouseMotionToOverlayView(
                    registration = reg,
                    bounds = reusableTitlebarBounds,
                    action = MotionEvent.ACTION_UP,
                    screenX = state.x,
                    screenY = state.y,
                    buttonState = 0,
                    downTime = activeDownTimeMs
                )
            }
            activeDragTargetIsTitlebar = false
            snappingController.clearPreview()
        } else if (activeDragTargetIsShell) {
            resolveShellTargetAt(state.x, state.y)?.let { target ->
                dispatchMouseMotionToShell(
                    registration = target,
                    action = MotionEvent.ACTION_UP,
                    screenX = state.x,
                    screenY = state.y,
                    buttonState = 0,
                    downTime = activeDownTimeMs
                )
            }
            snappingController.clearPreview()
        } else {
            val launcher = appLauncherRef
            val snapped = if (launcher != null) {
                snappingController.commitSnapOnDrop(launcher)
            } else {
                snappingController.clearPreview()
                false
            }

            if (!snapped) {
                val displayId = sessionRepository.activeDisplay.value?.displayId
                val distance = hypot(state.x - dragOriginX, state.y - dragOriginY)
                val dispatched = if (displayId != null && displayId != Display.DEFAULT_DISPLAY && distance >= 4f) {
                    DesktopPointerAccessibilityService.instance?.dispatchDragOnDisplay(
                        displayId = displayId,
                        startX = dragOriginX,
                        startY = dragOriginY,
                        endX = state.x,
                        endY = state.y,
                        durationMs = 220L
                    ) == true
                } else {
                    false
                }
                if (!dispatched && DesktopPointerAccessibilityService.instance == null) {
                    resolveFocusedWebAppWindow()?.let { (win, bounds) ->
                        dispatchMouseMotionToWindow(
                            window = win,
                            winBounds = bounds,
                            action = MotionEvent.ACTION_UP,
                            screenX = state.x,
                            screenY = state.y,
                            buttonState = 0,
                            downTime = activeDownTimeMs
                        )
                    }
                }
            }
        }

        activeDragTargetIsShell = false
        activeDragTargetIsTitlebar = false
        _pointerState.update {
            it.copy(
                isPrimaryDown = false,
                isDragging = false,
                lastInteractionMs = SystemClock.uptimeMillis()
            )
        }
    }

    fun performScroll(verticalDelta: Float, horizontalDelta: Float) {
        val (px, py) = currentEffectiveCoordinates(_pointerState.value)
        val now = SystemClock.uptimeMillis()
        _pointerState.update {
            it.copy(x = px, y = py, visible = true, lastInteractionMs = now, isHardwarePointer = false)
        }

        val shellTarget = resolveShellTargetAt(px, py)
        if (shellTarget != null) {
            dispatchMouseScrollToShell(shellTarget, px, py, verticalDelta, horizontalDelta)
        } else {
            val displayId = sessionRepository.activeDisplay.value?.displayId
            val dispatched = if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
                DesktopPointerAccessibilityService.instance?.dispatchScrollSwipeOnDisplay(
                    displayId = displayId,
                    centerX = px,
                    centerY = py,
                    scrollDeltaX = horizontalDelta * 110f,
                    scrollDeltaY = verticalDelta * 110f
                ) == true
            } else {
                false
            }
            if (!dispatched && DesktopPointerAccessibilityService.instance == null) {
                resolveFocusedWebAppWindow()?.let { (win, bounds) ->
                    dispatchMouseScrollToWindow(win, bounds, px, py, verticalDelta, horizontalDelta)
                }
            }
        }
    }

    fun forwardKeyCodeToShell(keyCode: Int): Boolean {
        val reg = presentationTarget ?: return false
        val win = reg.window
        val targetView = win.currentFocus ?: win.decorView
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        val handledDown = targetView.dispatchKeyEvent(down)
        val handledUp = targetView.dispatchKeyEvent(up)
        return handledDown || handledUp || win.superDispatchKeyEvent(down) || win.superDispatchKeyEvent(up)
    }

    fun forwardRawKeyEventToShell(event: KeyEvent): Boolean {
        val reg = presentationTarget ?: return false
        return reg.window.superDispatchKeyEvent(event)
    }

    private fun resolveTitlebarOverlayAt(
        screenX: Float,
        screenY: Float,
        outBounds: Rect
    ): OverlayViewRegistration? {
        if (sessionRepository.anyFlyoutOpen.value) return null
        val reg = titlebarOverlayTarget ?: return null
        val bounds = reg.screenBoundsProvider() ?: return null
        if (bounds.contains(screenX.roundToInt(), screenY.roundToInt())) {
            outBounds.set(bounds)
            return reg
        }
        return null
    }

    private fun resolveShellTargetAt(screenX: Float, screenY: Float): ShellWindowRegistration? {
        val px = screenX.roundToInt()
        val py = screenY.roundToInt()

        presentationTarget?.let { pres ->
            val decor = pres.window.peekDecorView()
            if (decor?.visibility == View.VISIBLE && pres.screenBoundsProvider().contains(px, py)) {
                return pres
            }
        }

        // In DOCKED_TASKBAR mode an external/running app owns the workspace above the 56dp taskbar
        // and SecondaryDesktopActivity has moved to back—never route touchpad taps to backdropTarget!
        if (!shouldAllowBackdropHitTesting(
                shellSurfaceMode = sessionRepository.shellSurfaceMode.value,
                isShowDesktopActive = sessionRepository.isShowDesktopActive.value,
                hasUnminimizedWindows = sessionRepository.runningWindows.value.any { !it.isMinimized },
                hasExternalAppVisible = sessionRepository.externalAppVisibleOnDisplay.value
            )
        ) {
            return null
        }

        val coveredByExternalWindow = externalWindowBounds.any { it.contains(px, py) } ||
            (externalWindowBounds.isEmpty() && sessionRepository.runningWindows.value.any {
                !it.isMinimized && it.bounds.contains(px, py)
            })
        if (coveredByExternalWindow) return null

        backdropTarget?.let { backdrop ->
            val decor = backdrop.window.peekDecorView()
            if (decor?.visibility == View.VISIBLE && backdrop.screenBoundsProvider().contains(px, py)) {
                return backdrop
            }
        }
        return null
    }

    private fun resolveFocusedWebAppWindow(): Pair<Window, Rect>? {
        val top = sessionRepository.focusedOrMostRecentWindow() ?: return null
        if (!top.packageName.startsWith("dexxp.webapp.")) return null
        val win = DesktopWebActivity.resolveActiveWindowForPointer(top.packageName, reusableWebAppBounds)
            ?: return null
        return win to reusableWebAppBounds
    }

    private fun dispatchFallbackWebAppClick(
        screenX: Float,
        screenY: Float,
        buttonState: Int,
        downTime: Long
    ) {
        val (win, bounds) = resolveFocusedWebAppWindow() ?: return
        if (!bounds.contains(screenX.roundToInt(), screenY.roundToInt())) return
        dispatchMouseMotionToWindow(win, bounds, MotionEvent.ACTION_DOWN, screenX, screenY, buttonState, downTime)
        dispatchMouseMotionToWindow(win, bounds, MotionEvent.ACTION_UP, screenX, screenY, 0, downTime)
    }

    private fun dispatchFallbackWebAppDragMove(screenX: Float, screenY: Float) {
        val (win, bounds) = resolveFocusedWebAppWindow() ?: return
        dispatchMouseMotionToWindow(
            window = win,
            winBounds = bounds,
            action = MotionEvent.ACTION_MOVE,
            screenX = screenX,
            screenY = screenY,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            downTime = activeDownTimeMs
        )
    }

    private fun dispatchMouseMotionToOverlayView(
        registration: OverlayViewRegistration,
        bounds: Rect,
        action: Int,
        screenX: Float,
        screenY: Float,
        buttonState: Int,
        downTime: Long,
        isGenericMotion: Boolean = false
    ) = synchronized(dispatchLock) {
        val localX = (screenX - bounds.left).coerceAtLeast(0f)
        val localY = (screenY - bounds.top).coerceAtLeast(0f)
        val eventTime = SystemClock.uptimeMillis()

        val coords = reusablePointerCoords[0].apply {
            clear()
            x = localX
            y = localY
            pressure = if (action == MotionEvent.ACTION_UP || isGenericMotion) 0f else 1f
            size = 1f
        }
        reusablePointerCoords[0] = coords

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            reusablePointerProps,
            reusablePointerCoords,
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )

        try {
            if (isGenericMotion) {
                registration.rootView.dispatchGenericMotionEvent(event)
            } else {
                registration.rootView.dispatchTouchEvent(event)
            }
        } finally {
            event.recycle()
        }
    }

    private fun dispatchMouseMotionToShell(
        registration: ShellWindowRegistration,
        action: Int,
        screenX: Float,
        screenY: Float,
        buttonState: Int,
        downTime: Long,
        isGenericMotion: Boolean = false
    ) {
        dispatchMouseMotionToWindow(
            window = registration.window,
            winBounds = registration.screenBoundsProvider(),
            action = action,
            screenX = screenX,
            screenY = screenY,
            buttonState = buttonState,
            downTime = downTime,
            isGenericMotion = isGenericMotion
        )
    }

    private fun dispatchMouseMotionToWindow(
        window: Window,
        winBounds: Rect,
        action: Int,
        screenX: Float,
        screenY: Float,
        buttonState: Int,
        downTime: Long,
        isGenericMotion: Boolean = false
    ) = synchronized(dispatchLock) {
        val localX = (screenX - winBounds.left).coerceAtLeast(0f)
        val localY = (screenY - winBounds.top).coerceAtLeast(0f)
        val eventTime = SystemClock.uptimeMillis()

        val coords = reusablePointerCoords[0].apply {
            clear()
            x = localX
            y = localY
            pressure = if (action == MotionEvent.ACTION_UP || isGenericMotion) 0f else 1f
            size = 1f
        }
        reusablePointerCoords[0] = coords

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            reusablePointerProps,
            reusablePointerCoords,
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )

        try {
            if (isGenericMotion) {
                window.superDispatchGenericMotionEvent(event)
            } else {
                window.superDispatchTouchEvent(event)
            }
        } finally {
            event.recycle()
        }
    }

    private fun dispatchMouseScrollToShell(
        registration: ShellWindowRegistration,
        screenX: Float,
        screenY: Float,
        vScroll: Float,
        hScroll: Float
    ) {
        dispatchMouseScrollToWindow(
            window = registration.window,
            winBounds = registration.screenBoundsProvider(),
            screenX = screenX,
            screenY = screenY,
            vScroll = vScroll,
            hScroll = hScroll
        )
    }

    private fun dispatchMouseScrollToWindow(
        window: Window,
        winBounds: Rect,
        screenX: Float,
        screenY: Float,
        vScroll: Float,
        hScroll: Float
    ) = synchronized(dispatchLock) {
        val now = SystemClock.uptimeMillis()

        reusablePointerCoords[0].apply {
            clear()
            x = (screenX - winBounds.left).coerceAtLeast(0f)
            y = (screenY - winBounds.top).coerceAtLeast(0f)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        }

        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_SCROLL,
            1,
            reusablePointerProps,
            reusablePointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )

        try {
            window.superDispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    companion object {
        const val BOTTOM_EDGE_REVEAL_THRESHOLD_PX = 6
        private const val BOTTOM_EDGE_REVEAL_DEBOUNCE_MS = 250L

        fun isInBottomEdgeRevealZone(screenY: Float, displayHeightPx: Int): Boolean {
            return displayHeightPx > 0 && screenY >= (displayHeightPx - BOTTOM_EDGE_REVEAL_THRESHOLD_PX)
        }

        fun shouldAllowBackdropHitTesting(
            shellSurfaceMode: ShellSurfaceMode,
            isShowDesktopActive: Boolean,
            hasUnminimizedWindows: Boolean,
            hasExternalAppVisible: Boolean
        ): Boolean {
            if (shellSurfaceMode == ShellSurfaceMode.DOCKED_TASKBAR) return false
            if (!isShowDesktopActive && (hasUnminimizedWindows || hasExternalAppVisible)) return false
            return true
        }
    }
}
