package com.dexxp.desktop.bridge

import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
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
    private var dragOriginX: Float = 0f

    @Volatile
    private var dragOriginY: Float = 0f

    // Pre-allocated reusable buffers eliminating 120-240Hz pointer hover/drag GC churn
    private val dispatchLock = Any()
    private val reusableTitlebarBounds = Rect()
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
        focusedPackage: String?
    ) {
        externalWindowBounds = bounds
        _detectedExternalFrames.value = frames
        sessionRepository.updateExternalWindowVisibility(
            hasVisibleExternalApp = bounds.isNotEmpty(),
            focusedPackage = focusedPackage,
            detectedPackages = frames.map { it.packageName }
        )
    }

    /**
     * Purges all display-scoped window/overlay references and resets active drag/button states
     * immediately when an HDMI / USB-C DP monitor disconnects mid-session.
     */
    fun onExternalDisplayDisconnected() {
        presentationTarget = null
        backdropTarget = null
        titlebarOverlayTarget = null
        externalWindowBounds = emptyList()
        _detectedExternalFrames.value = emptyList()
        activeDragTargetIsShell = false
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
        needsKeyboardFocus: Boolean,
        displayWidthPx: Int,
        displayHeightPx: Int,
        densityDpi: Int
    ) {
        val decorView = window.peekDecorView() ?: return
        val lp = window.attributes ?: return
        val density = densityDpi / 160f
        val taskbarHeightPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()

        val isTvMode = sessionRepository.environmentMode.value == DesktopEnvironmentMode.TV_LEANBACK
        val isLaunching = sessionRepository.launchingPackage.value != null
        val hasActiveApp = sessionRepository.runningWindows.value.any { !it.isMinimized } &&
            !sessionRepository.isShowDesktopActive.value &&
            !isLaunching

        val (targetWidth, targetHeight, targetGravity) = when {
            isTvMode && hasActiveApp -> {
                // When an app is active on TV (and not mid-launch), hide presentation dialog so the app takes 100% of the screen
                Triple(0, 0, Gravity.TOP or Gravity.START)
            }
            mode == ShellSurfaceMode.DOCKED_TASKBAR -> {
                Triple(WindowManager.LayoutParams.MATCH_PARENT, taskbarHeightPx, Gravity.BOTTOM or Gravity.START)
            }
            else -> {
                Triple(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.START)
            }
        }

        // Capture prior attributes BEFORE in-place mutation (`window.attributes` returns live mWindowAttributes)
        val prevWidth = lp.width
        val prevHeight = lp.height
        val prevGravity = lp.gravity
        val prevFlags = lp.flags

        var flags = prevFlags
        flags = flags or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()

        if (mode == ShellSurfaceMode.DOCKED_TASKBAR) {
            flags = flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
            flags = flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        } else {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        val shouldBeFocusable = when {
            isTvMode && hasActiveApp -> false
            isTvMode -> true
            mode == ShellSurfaceMode.DOCKED_TASKBAR -> false
            mode == ShellSurfaceMode.FLYOUT_ACTIVE -> true
            else -> needsKeyboardFocus
        }
        flags = if (shouldBeFocusable) {
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        if (isTvMode && hasActiveApp) {
            decorView.visibility = View.GONE
        } else {
            decorView.visibility = View.VISIBLE
        }

        val layoutChanged = prevWidth != targetWidth ||
            prevHeight != targetHeight ||
            prevGravity != targetGravity ||
            prevFlags != flags ||
            lp.x != 0 ||
            lp.y != 0

        if (layoutChanged) {
            lp.width = targetWidth
            lp.height = targetHeight
            lp.gravity = targetGravity
            lp.x = 0
            lp.y = 0
            lp.flags = flags
            window.attributes = lp
            if (decorView.isAttachedToWindow) {
                runCatching { window.windowManager.updateViewLayout(decorView, lp) }
            }
        }

        if (shouldBeFocusable) {
            decorView.post { decorView.requestFocus() }
        } else if (decorView.hasFocus()) {
            decorView.clearFocus()
        }
    }

    fun onOutsideWindowTouch() {
        if (sessionRepository.anyFlyoutOpen.value) {
            sessionRepository.dismissFlyouts()
        }
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
        val now = SystemClock.uptimeMillis()
        _pointerState.update { current ->
            val maxX = (current.displayWidth - 1).coerceAtLeast(1).toFloat()
            val maxY = (current.displayHeight - 1).coerceAtLeast(1).toFloat()
            val nx = screenX.coerceIn(0f, maxX)
            val ny = screenY.coerceIn(0f, maxY)
            if (nx == current.x && ny == current.y && current.isPrimaryDown == isDown &&
                current.visible && current.isHardwarePointer
            ) {
                current
            } else {
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
    }

    fun movePointerBy(deltaX: Float, deltaY: Float) {
        if (deltaX == 0f && deltaY == 0f) return
        val sensitivity = sessionRepository.touchpadSensitivity.value
        val now = SystemClock.uptimeMillis()

        var nextState: PointerState? = null
        _pointerState.update { current ->
            val maxX = (current.displayWidth - 1).coerceAtLeast(1).toFloat()
            val maxY = (current.displayHeight - 1).coerceAtLeast(1).toFloat()
            val clampedX = (current.x + deltaX * sensitivity).coerceIn(0f, maxX)
            val clampedY = (current.y + deltaY * sensitivity).coerceIn(0f, maxY)
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
        if (state.isDragging && !activeDragTargetIsShell) {
            snappingController.onPointerDragMoved(
                x = state.x,
                y = state.y,
                displayInfo = sessionRepository.activeDisplay.value
            )
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
        val state = _pointerState.value
        val now = SystemClock.uptimeMillis()
        _pointerState.update {
            it.copy(visible = true, isPrimaryDown = true, lastInteractionMs = now, isHardwarePointer = false)
        }

        val titlebarHit = resolveTitlebarOverlayAt(state.x, state.y, reusableTitlebarBounds)
        if (titlebarHit != null) {
            dispatchMouseMotionToOverlayView(
                titlebarHit,
                reusableTitlebarBounds,
                MotionEvent.ACTION_DOWN,
                state.x,
                state.y,
                MotionEvent.BUTTON_PRIMARY,
                now
            )
            dispatchMouseMotionToOverlayView(
                titlebarHit,
                reusableTitlebarBounds,
                MotionEvent.ACTION_UP,
                state.x,
                state.y,
                0,
                now
            )
            _pointerState.update { it.copy(isPrimaryDown = false) }
            return
        }

        val shellTarget = resolveShellTargetAt(state.x, state.y)
        if (shellTarget != null) {
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_DOWN, state.x, state.y, MotionEvent.BUTTON_PRIMARY, now)
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_UP, state.x, state.y, 0, now)
        } else {
            val displayId = sessionRepository.activeDisplay.value?.displayId
            if (displayId != null) {
                DesktopPointerAccessibilityService.instance?.dispatchTapOnDisplay(
                    displayId = displayId,
                    x = state.x,
                    y = state.y,
                    durationMs = 48L
                )
            }
        }

        _pointerState.update { it.copy(isPrimaryDown = false) }
    }

    fun performSecondaryClick() {
        val state = _pointerState.value
        val now = SystemClock.uptimeMillis()
        _pointerState.update {
            it.copy(visible = true, isSecondaryDown = true, lastInteractionMs = now, isHardwarePointer = false)
        }

        val shellTarget = resolveShellTargetAt(state.x, state.y)
        if (shellTarget != null) {
            _rightClickEvents.tryEmit(SecondaryRightClickEvent(state.x, state.y, now))
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_DOWN, state.x, state.y, MotionEvent.BUTTON_SECONDARY, now)
            dispatchMouseMotionToShell(shellTarget, MotionEvent.ACTION_UP, state.x, state.y, 0, now)
        } else {
            val displayId = sessionRepository.activeDisplay.value?.displayId
            if (displayId != null) {
                DesktopPointerAccessibilityService.instance?.dispatchTapOnDisplay(
                    displayId = displayId,
                    x = state.x,
                    y = state.y,
                    durationMs = 520L
                )
            }
        }

        _pointerState.update { it.copy(isSecondaryDown = false) }
    }

    fun beginDrag() {
        val state = _pointerState.value
        val now = SystemClock.uptimeMillis()
        activeDownTimeMs = now
        dragOriginX = state.x
        dragOriginY = state.y

        val shellTarget = resolveShellTargetAt(state.x, state.y)
        activeDragTargetIsShell = shellTarget != null

        _pointerState.update {
            it.copy(
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
                screenX = state.x,
                screenY = state.y,
                buttonState = MotionEvent.BUTTON_PRIMARY,
                downTime = now
            )
        }
    }

    fun endDrag() {
        val state = _pointerState.value
        if (!state.isDragging) return

        if (activeDragTargetIsShell) {
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
                if (displayId != null && distance >= 4f) {
                    DesktopPointerAccessibilityService.instance?.dispatchDragOnDisplay(
                        displayId = displayId,
                        startX = dragOriginX,
                        startY = dragOriginY,
                        endX = state.x,
                        endY = state.y,
                        durationMs = 220L
                    )
                }
            }
        }

        activeDragTargetIsShell = false
        _pointerState.update {
            it.copy(
                isPrimaryDown = false,
                isDragging = false,
                lastInteractionMs = SystemClock.uptimeMillis()
            )
        }
    }

    fun performScroll(verticalDelta: Float, horizontalDelta: Float) {
        val state = _pointerState.value
        val now = SystemClock.uptimeMillis()
        _pointerState.update { it.copy(visible = true, lastInteractionMs = now) }

        val shellTarget = resolveShellTargetAt(state.x, state.y)
        if (shellTarget != null) {
            dispatchMouseScrollToShell(shellTarget, state.x, state.y, verticalDelta, horizontalDelta)
        } else {
            val displayId = sessionRepository.activeDisplay.value?.displayId
            if (displayId != null) {
                DesktopPointerAccessibilityService.instance?.dispatchScrollSwipeOnDisplay(
                    displayId = displayId,
                    centerX = state.x,
                    centerY = state.y,
                    scrollDeltaX = horizontalDelta * 110f,
                    scrollDeltaY = verticalDelta * 110f
                )
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
            if (pres.screenBoundsProvider().contains(px, py)) return pres
        }

        val coveredByExternalWindow = externalWindowBounds.any { it.contains(px, py) } ||
            (externalWindowBounds.isEmpty() && sessionRepository.runningWindows.value.any {
                !it.isMinimized && it.bounds.contains(px, py)
            })
        if (coveredByExternalWindow) return null

        backdropTarget?.let { backdrop ->
            if (backdrop.screenBoundsProvider().contains(px, py)) return backdrop
        }
        return null
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
    ) = synchronized(dispatchLock) {
        val winBounds = registration.screenBoundsProvider()
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
                registration.window.superDispatchGenericMotionEvent(event)
            } else {
                registration.window.superDispatchTouchEvent(event)
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
    ) = synchronized(dispatchLock) {
        val winBounds = registration.screenBoundsProvider()
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
            registration.window.superDispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }
}
