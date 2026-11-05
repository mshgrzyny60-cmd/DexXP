package com.dexxp.desktop.windowing

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.ExternalWindowFrame
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.DexXPTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Universal Top Window Control Bar (`28dp` Material 3 Surface) for secondary displays:
 *
 * - **Floating / Container Windows (`9:19` Portrait, `16:10` Landscape, Freeform, Split)**:
 *   Renders a full-width floating titlebar directly above the window frame with drag-to-move,
 *   drag-to-resize, container orientation toggle, Minimize `[—]`, Maximize `[□]`, and Close `[X]`.
 * - **Maximized / Fullscreen Applications**:
 *   Remains accessible as a sleek, pinned top-center Window Control Pill (`336dp x 28dp`) so users
 *   always have 1-click access to Minimize `[—]`, Restore Container `[□]`, and Close `[X]` without
 *   occluding top-left navigation menus or top-right action buttons.
 * - **System-Overlay-Only Attachment**:
 *   Attaches strictly via `TYPE_ACCESSIBILITY_OVERLAY` or `TYPE_APPLICATION_OVERLAY` (NEVER a second
 *   `Presentation` dialog, which would occlude `APPLICATION_LAYER` Activities). When overlay
 *   permissions are not yet granted, gracefully returns `false` so the docked Taskbar's direct
 *   1-click `[X]` and Minimize/Restore controls govern active apps without blocking their surfaces.
 */
class WindowTitlebarOverlayController(
    private val appContext: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appRepository: AppRepository,
    private val appLauncher: AppLauncher,
    private val pointerFocusBridge: PointerFocusBridge,
    private val snappingController: WindowSnappingController
) {
    data class TitlebarRenderModel(
        val packageName: String,
        val label: String,
        val iconBitmap: ImageBitmap?,
        val snapMode: WindowSnapMode,
        val isPortraitContainer: Boolean,
        val isMaximized: Boolean,
        val clientWindowBounds: Rect,
        val barScreenBounds: Rect
    )

    private class OverlayHostLifecycle :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val vmStore = ViewModelStore()
        private val savedController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = vmStore
        override val savedStateRegistry: SavedStateRegistry get() = savedController.savedStateRegistry

        fun onAttach() {
            savedController.performAttach()
            savedController.performRestore(Bundle.EMPTY)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDetach() {
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
                if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                }
            }
            vmStore.clear()
        }
    }

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bottomEdgeHoverView: View? = null
    private var hostLifecycle: OverlayHostLifecycle? = null
    private var scope: CoroutineScope? = null

    var isAttached: Boolean = false
        private set

    private val renderModelFlow = MutableStateFlow<TitlebarRenderModel?>(null)

    @Volatile
    private var currentVisibleBounds: Rect? = null

    // Reusable Rect avoiding allocations during 120-240Hz interactive window drag/resize loops
    private val reusableDragBounds = Rect()

    @Volatile
    private var isInteractivelyManipulating: Boolean = false
    private var dragOriginBarLeft: Int = 0
    private var dragOriginBarTop: Int = 0
    private var dragOriginBarWidth: Int = 320
    private var dragAccumDx: Float = 0f
    private var dragAccumDy: Float = 0f

    fun attachToDisplay(display: Display, scaledDisplayContext: Context): Boolean {
        detach()
        if (!display.isValid) return false

        val a11yService = DesktopPointerAccessibilityService.instance
        var resolvedOverlayCtx: Context? = null
        var resolvedWindowType: Int = 0

        if (a11yService != null) {
            val a11yCtx = runCatching {
                a11yService.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    null
                )
            }.getOrNull()
            if (a11yCtx != null) {
                resolvedOverlayCtx = a11yCtx
                resolvedWindowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
        }

        if (resolvedOverlayCtx == null && Settings.canDrawOverlays(appContext)) {
            val appOverlayCtx = runCatching {
                appContext.createDisplayContext(display).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
            }.getOrNull()
            if (appOverlayCtx != null) {
                resolvedOverlayCtx = appOverlayCtx
                resolvedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
        }

        // NEVER instantiate a second Presentation dialog on the display (would black out APPLICATION_LAYER).
        val baseOverlayCtx = resolvedOverlayCtx ?: return false
        val wm = baseOverlayCtx.getSystemService(WindowManager::class.java) ?: return false

        val uiCtx = baseOverlayCtx.createConfigurationContext(
            scaledDisplayContext.resources.configuration
        )
        val density = uiCtx.resources.displayMetrics.density
        val barHeightPx = (TITLEBAR_HEIGHT_DP * density).roundToInt().coerceAtLeast(28)

        val owner = OverlayHostLifecycle().apply { onAttach() }
        val view = ComposeView(uiCtx).apply {
            visibility = View.GONE
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val model by renderModelFlow.collectAsState()
                val activeModel = model
                if (activeModel != null) {
                    DexXPTheme(allowDynamicColorOnPhone = false) {
                        FloatingWindowTitlebarBar(
                            model = activeModel,
                            onDragMoveStart = {
                                beginInteractiveMove(activeModel)
                            },
                            onDragMoveDelta = { dx, dy, localPointerX, localPointerY ->
                                stepInteractiveMove(dx, dy, localPointerX, localPointerY)
                            },
                            onDragMoveEnd = {
                                finishInteractiveMove(activeModel)
                            },
                            onDragMoveCancel = {
                                cancelInteractiveManipulation()
                            },
                            onResizeStart = {
                                beginInteractiveResize(activeModel)
                            },
                            onResizeDelta = { dx, dy ->
                                stepInteractiveResize(dx, dy)
                            },
                            onResizeEnd = {
                                finishInteractiveResize(activeModel)
                            },
                            onResizeCancel = {
                                cancelInteractiveManipulation()
                            },
                            onToggleContainerOrientation = {
                                post {
                                    appLauncher.toggleContainerOrientation(activeModel.packageName)
                                }
                            },
                            onMinimize = {
                                post {
                                    appLauncher.minimizeWindow(activeModel.packageName)
                                }
                            },
                            onMaximizeOrRestore = {
                                post {
                                    appLauncher.toggleMaximizeOrRestoreWindow(activeModel.packageName)
                                }
                            },
                            onClose = {
                                post {
                                    appLauncher.closeWindow(activeModel.packageName)
                                }
                            }
                        )
                    }
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            320,
            barHeightPx,
            0,
            0,
            resolvedWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DexXPFloatingWindowTitlebar"
        }

        val added = runCatching {
            wm.addView(view, lp)
            true
        }.onFailure { err ->
            Log.w(TAG, "Unable to attach system overlay window titlebar on display ${display.displayId}", err)
        }.getOrDefault(false)

        if (!added) {
            owner.onDetach()
            return false
        }

        windowManager = wm
        composeView = view
        layoutParams = lp
        hostLifecycle = owner
        isAttached = true

        // 6px transparent bottom-edge hover sensor strip: ensures moving the mouse to the bottom edge
        // reveals the Taskbar even when an unadapted fullscreen app or TV Leanback mode covers the screen.
        val edgeSensor = object : View(uiCtx) {
            override fun onHoverEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                    event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
                ) {
                    val h = sessionRepository.activeDisplay.value?.heightPx ?: 1080
                    pointerFocusBridge.setPointerPosition(
                        screenX = event.rawX,
                        screenY = (h - 1).coerceAtLeast(1).toFloat(),
                        isDown = false,
                        sourceDisplayId = display.displayId
                    )
                    pointerFocusBridge.onBottomEdgeHoverReveal()
                }
                return super.onHoverEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    pointerFocusBridge.onBottomEdgeHoverReveal()
                }
                return false
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.GONE
        }
        val edgeLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            PointerFocusBridge.BOTTOM_EDGE_REVEAL_THRESHOLD_PX,
            0,
            0,
            resolvedWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            title = "DexXPBottomEdgeHoverSensor"
        }
        if (runCatching { wm.addView(edgeSensor, edgeLp); true }.getOrDefault(false)) {
            bottomEdgeHoverView = edgeSensor
        }

        pointerFocusBridge.registerTitlebarOverlayView(view) { currentVisibleBounds }

        val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = overlayScope
        overlayScope.launch {
            combine(
                pointerFocusBridge.detectedExternalFrames,
                sessionRepository.runningWindows,
                sessionRepository.isShowDesktopActive,
                sessionRepository.anyFlyoutOpen,
                sessionRepository.activeDisplay
            ) { liveFrames, records, showDesktop, flyoutOpen, displayInfo ->
                val shouldEnableBottomSensor = !showDesktop &&
                    !flyoutOpen &&
                    displayInfo != null &&
                    records.any { !it.isMinimized }
                bottomEdgeHoverView?.let { sensor ->
                    val desiredVis = if (shouldEnableBottomSensor) View.VISIBLE else View.GONE
                    if (sensor.visibility != desiredVis) {
                        sensor.visibility = desiredVis
                    }
                }
                resolveActiveTitlebarModel(
                    liveFrames = liveFrames,
                    records = records,
                    showDesktop = showDesktop,
                    flyoutOpen = flyoutOpen,
                    displayInfo = displayInfo,
                    barHeightPx = barHeightPx
                )
            }
                .distinctUntilChanged()
                .collect { nextModel ->
                    applyTitlebarLayout(nextModel)
                }
        }
        return true
    }

    private fun resolveActiveTitlebarModel(
        liveFrames: List<ExternalWindowFrame>,
        records: List<RunningWindowRecord>,
        showDesktop: Boolean,
        flyoutOpen: Boolean,
        displayInfo: ExternalDisplayInfo?,
        barHeightPx: Int
    ): TitlebarRenderModel? {
        if (showDesktop || flyoutOpen || displayInfo == null ||
            sessionRepository.environmentMode.value == DesktopEnvironmentMode.TV_LEANBACK
        ) return null

        val visibleRecords = records.filterNot { it.isMinimized }
        val topRecord = visibleRecords.firstOrNull { it.isFocused }
            ?: visibleRecords.maxByOrNull { it.lastActiveEpochMs }

        val liveFocused = liveFrames.firstOrNull { it.isFocused }
            ?: liveFrames.firstOrNull { it.isActive }
            ?: topRecord?.let { rec -> liveFrames.firstOrNull { it.packageName == rec.packageName } }

        // Reconcile DexXP's own package name (`DesktopWebActivity` vs `SecondaryDesktopActivity`)
        val targetPkg: String = when {
            liveFocused != null && liveFocused.packageName != appContext.packageName -> {
                liveFocused.packageName
            }
            liveFocused?.packageName == appContext.packageName -> {
                val activeWeb = visibleRecords.firstOrNull {
                    it.packageName.startsWith("dexxp.webapp.") && it.isFocused
                } ?: visibleRecords
                    .filter { it.packageName.startsWith("dexxp.webapp.") }
                    .maxByOrNull { it.lastActiveEpochMs }
                activeWeb?.packageName ?: topRecord?.packageName ?: return null
            }
            else -> topRecord?.packageName ?: return null
        }

        if (targetPkg == appContext.packageName) return null
        if (records.any { it.packageName == targetPkg && it.isMinimized }) return null

        val matchedRecord = visibleRecords.firstOrNull { it.packageName == targetPkg }
        val rawWinBounds = when {
            liveFocused != null && (
                liveFocused.packageName == targetPkg ||
                    (liveFocused.packageName == appContext.packageName && targetPkg.startsWith("dexxp.webapp."))
                ) -> liveFocused.bounds
            matchedRecord != null -> matchedRecord.bounds
            else -> return null
        }
        if (rawWinBounds.width() < 180 || rawWinBounds.height() < 120) return null

        val density = (displayInfo.scaledDensityDpi / 160f).coerceAtLeast(0.75f)
        val isMaximizedSnap = matchedRecord?.snapMode == WindowSnapMode.MAXIMIZED
        val fillsWorkArea = rawWinBounds.width() >= (displayInfo.widthPx * 0.94f).roundToInt() &&
            rawWinBounds.top <= (12 * density).roundToInt()
        val isMaximized = isMaximizedSnap || fillsWorkArea

        // DesktopWebActivity already renders its own dedicated 44dp top bar with [—] [□] [X] when maximized
        if (isMaximized && targetPkg.startsWith("dexxp.webapp.")) {
            return null
        }

        val appItem = appRepository.findByPackage(targetPkg)
        val resolvedTitle = liveFocused?.title?.takeIf {
            it.isNotBlank() && liveFocused.packageName == targetPkg
        }
            ?: matchedRecord?.label
            ?: appItem?.label
            ?: targetPkg.substringAfterLast('.')

        val currentSnap = when {
            isMaximized -> WindowSnapMode.MAXIMIZED
            matchedRecord != null -> matchedRecord.snapMode
            rawWinBounds.width() < rawWinBounds.height() -> WindowSnapMode.PORTRAIT_CONTAINER
            else -> WindowSnapMode.FREEFORM_CASCADED
        }

        val barBounds = if (isMaximized) {
            val pillWidthPx = (MAXIMIZED_PILL_WIDTH_DP * density).roundToInt()
                .coerceIn(240, (displayInfo.widthPx - 24).coerceAtLeast(240))
            val pillLeft = ((displayInfo.widthPx - pillWidthPx) / 2).coerceAtLeast(0)
            Rect(pillLeft, 0, pillLeft + pillWidthPx, barHeightPx)
        } else {
            val barWidth = rawWinBounds.width().coerceIn(220, displayInfo.widthPx)
            val barLeft = rawWinBounds.left.coerceIn(0, (displayInfo.widthPx - barWidth).coerceAtLeast(0))
            val barTop = (rawWinBounds.top - barHeightPx - 2).coerceAtLeast(0)
            Rect(barLeft, barTop, barLeft + barWidth, barTop + barHeightPx)
        }

        return TitlebarRenderModel(
            packageName = targetPkg,
            label = resolvedTitle,
            iconBitmap = appItem?.iconBitmap,
            snapMode = currentSnap,
            isPortraitContainer = !isMaximized && (
                currentSnap == WindowSnapMode.PORTRAIT_CONTAINER ||
                    rawWinBounds.width() < (rawWinBounds.height() * 0.72f).roundToInt()
                ),
            isMaximized = isMaximized,
            clientWindowBounds = Rect(rawWinBounds),
            barScreenBounds = barBounds
        )
    }

    private fun updateHostWindowLayout(view: View, x: Int, y: Int, width: Int, height: Int) {
        val wm = windowManager ?: return
        val lp = layoutParams ?: return
        if (lp.x != x || lp.y != y || lp.width != width || lp.height != height) {
            lp.x = x
            lp.y = y
            lp.width = width
            lp.height = height
            if (view.isAttachedToWindow) {
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
    }

    private fun beginInteractiveMove(model: TitlebarRenderModel) {
        val lp = layoutParams ?: return
        isInteractivelyManipulating = true
        dragOriginBarLeft = lp.x
        dragOriginBarTop = lp.y
        dragOriginBarWidth = lp.width.coerceAtLeast(model.barScreenBounds.width())
        dragAccumDx = 0f
        dragAccumDy = 0f
    }

    private fun stepInteractiveMove(dx: Float, dy: Float, localPointerX: Float, localPointerY: Float) {
        val view = composeView ?: return
        val lp = layoutParams ?: return
        val displayInfo = sessionRepository.activeDisplay.value ?: return

        dragAccumDx += dx
        dragAccumDy += dy

        val curW = lp.width.coerceAtLeast(240)
        val curH = lp.height.coerceAtLeast(28)
        val nextLeft = (dragOriginBarLeft + dragAccumDx.roundToInt())
            .coerceIn(0, (displayInfo.widthPx - curW).coerceAtLeast(0))
        val nextTop = (dragOriginBarTop + dragAccumDy.roundToInt())
            .coerceIn(0, (displayInfo.heightPx - curH * 3).coerceAtLeast(0))

        if (lp.x != nextLeft || lp.y != nextTop) {
            reusableDragBounds.set(nextLeft, nextTop, nextLeft + curW, nextTop + curH)
            currentVisibleBounds = reusableDragBounds
            updateHostWindowLayout(view, nextLeft, nextTop, curW, curH)
        }

        val pointerScreenX = (nextLeft + localPointerX).coerceIn(0f, (displayInfo.widthPx - 1).toFloat())
        val pointerScreenY = (nextTop + localPointerY).coerceIn(0f, (displayInfo.heightPx - 1).toFloat())
        snappingController.onPointerDragMoved(pointerScreenX, pointerScreenY, displayInfo)
    }

    private fun finishInteractiveMove(model: TitlebarRenderModel) {
        isInteractivelyManipulating = false
        if (snappingController.commitSnapOnDrop(appLauncher)) {
            return
        }
        val movedDx = dragAccumDx.roundToInt()
        val movedDy = dragAccumDy.roundToInt()
        if (abs(movedDx) < 3 && abs(movedDy) < 3) {
            return
        }
        // Tearing off a maximized top pill downward restores the app into its floating container
        if (model.isMaximized) {
            if (movedDy > 24) {
                appLauncher.toggleMaximizeOrRestoreWindow(model.packageName)
            } else {
                applyTitlebarLayout(renderModelFlow.value)
            }
            return
        }
        val movedRect = Rect(model.clientWindowBounds).apply {
            offset(movedDx, movedDy)
        }
        val preservedSnap = when (model.snapMode) {
            WindowSnapMode.PORTRAIT_CONTAINER,
            WindowSnapMode.LANDSCAPE_CONTAINER -> model.snapMode
            else -> WindowSnapMode.FREEFORM_CASCADED
        }
        appLauncher.repositionWindowBounds(
            packageName = model.packageName,
            newBounds = movedRect,
            snapMode = preservedSnap
        )
    }

    private fun beginInteractiveResize(model: TitlebarRenderModel) {
        if (model.isMaximized) return
        val lp = layoutParams ?: return
        isInteractivelyManipulating = true
        dragOriginBarLeft = lp.x
        dragOriginBarTop = lp.y
        dragOriginBarWidth = lp.width.coerceAtLeast(model.barScreenBounds.width())
        dragAccumDx = 0f
        dragAccumDy = 0f
    }

    private fun stepInteractiveResize(dx: Float, dy: Float) {
        val view = composeView ?: return
        val lp = layoutParams ?: return
        val displayInfo = sessionRepository.activeDisplay.value ?: return

        dragAccumDx += dx
        dragAccumDy += dy

        val maxW = (displayInfo.widthPx - lp.x - 8).coerceAtLeast(260)
        val previewW = (dragOriginBarWidth + dragAccumDx.roundToInt()).coerceIn(260, maxW)
        val curH = lp.height.coerceAtLeast(28)
        if (lp.width != previewW) {
            reusableDragBounds.set(lp.x, lp.y, lp.x + previewW, lp.y + curH)
            currentVisibleBounds = reusableDragBounds
            updateHostWindowLayout(view, lp.x, lp.y, previewW, curH)
        }
    }

    private fun finishInteractiveResize(model: TitlebarRenderModel) {
        isInteractivelyManipulating = false
        if (model.isMaximized) return
        val dW = dragAccumDx.roundToInt()
        val dH = dragAccumDy.roundToInt()
        if (abs(dW) < 4 && abs(dH) < 4) {
            return
        }
        val base = model.clientWindowBounds
        val resized = Rect(
            base.left,
            base.top,
            base.left + (base.width() + dW).coerceAtLeast(320),
            base.top + (base.height() + dH).coerceAtLeast(280)
        )
        appLauncher.repositionWindowBounds(
            packageName = model.packageName,
            newBounds = resized,
            snapMode = WindowSnapMode.FREEFORM_CASCADED
        )
    }

    private fun cancelInteractiveManipulation() {
        isInteractivelyManipulating = false
        snappingController.clearPreview()
        applyTitlebarLayout(renderModelFlow.value)
    }

    private fun applyTitlebarLayout(model: TitlebarRenderModel?) {
        if (isInteractivelyManipulating && model != null) {
            return
        }
        val view = composeView ?: return

        if (model == null) {
            currentVisibleBounds = null
            renderModelFlow.value = null
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
            return
        }

        renderModelFlow.value = model
        val b = model.barScreenBounds
        currentVisibleBounds = b
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        updateHostWindowLayout(view, b.left, b.top, b.width(), b.height())
    }

    fun detach() {
        scope?.cancel()
        scope = null
        isInteractivelyManipulating = false
        isAttached = false
        snappingController.clearPreview()
        currentVisibleBounds = null
        renderModelFlow.value = null

        val edgeView = bottomEdgeHoverView
        val view = composeView
        val wm = windowManager
        if (edgeView != null && wm != null) {
            runCatching { wm.removeViewImmediate(edgeView) }
        }
        bottomEdgeHoverView = null
        if (view != null) {
            pointerFocusBridge.unregisterTitlebarOverlayView(view)
            if (wm != null) {
                runCatching { wm.removeViewImmediate(view) }
            }
        }
        hostLifecycle?.onDetach()
        hostLifecycle = null
        composeView = null
        layoutParams = null
        windowManager = null
    }

    companion object {
        private const val TAG = "WindowTitlebarOverlay"
        const val TITLEBAR_HEIGHT_DP = 28
        const val MAXIMIZED_PILL_WIDTH_DP = 336
    }
}

private val FloatingTitlebarShape = RoundedCornerShape(
    topStart = 12.dp,
    topEnd = 12.dp,
    bottomStart = 6.dp,
    bottomEnd = 6.dp
)

private val MaximizedTopPillShape = RoundedCornerShape(
    topStart = 0.dp,
    topEnd = 0.dp,
    bottomStart = 12.dp,
    bottomEnd = 12.dp
)

private val ControlButtonShape = RoundedCornerShape(6.dp)
private val AppIconClipShape = RoundedCornerShape(4.dp)
private val CloseRedColor = Color(0xFFD93025)

@Composable
private fun FloatingWindowTitlebarBar(
    model: WindowTitlebarOverlayController.TitlebarRenderModel,
    onDragMoveStart: () -> Unit,
    onDragMoveDelta: (dx: Float, dy: Float, pointerX: Float, pointerY: Float) -> Unit,
    onDragMoveEnd: () -> Unit,
    onDragMoveCancel: () -> Unit,
    onResizeStart: () -> Unit,
    onResizeDelta: (dx: Float, dy: Float) -> Unit,
    onResizeEnd: () -> Unit,
    onResizeCancel: () -> Unit,
    onToggleContainerOrientation: () -> Unit,
    onMinimize: () -> Unit,
    onMaximizeOrRestore: () -> Unit,
    onClose: () -> Unit
) {
    val barShape = if (model.isMaximized) MaximizedTopPillShape else FloatingTitlebarShape

    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(barShape)
            .background(
                MaterialTheme.colorScheme.surfaceContainer.copy(
                    alpha = if (model.isMaximized) 0.94f else 0.92f
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                shape = barShape
            )
            // Consume all inside-bar pointer changes at Final pass so clicks never leak into background apps
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            if (!change.isConsumed) {
                                change.consume()
                            }
                        }
                    }
                }
            }
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Draggable Title Area (Drag to Move / Tear-Off / Snap to Edges, Double-Click to Maximize/Restore)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(model.packageName, model.isMaximized) {
                    detectTapGestures(
                        onDoubleTap = { onMaximizeOrRestore() }
                    )
                }
                .pointerInput(model.packageName, model.isMaximized, "dragMove") {
                    detectDragGestures(
                        onDragStart = { onDragMoveStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragMoveDelta(
                                dragAmount.x,
                                dragAmount.y,
                                change.position.x,
                                change.position.y
                            )
                        },
                        onDragEnd = { onDragMoveEnd() },
                        onDragCancel = { onDragMoveCancel() }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.iconBitmap != null) {
                Image(
                    bitmap = model.iconBitmap,
                    contentDescription = model.label,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(AppIconClipShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = model.label.take(1).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = model.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (!model.isMaximized) {
                // Interactive Drag-to-Resize Handle (Floating Windows)
                TitlebarResizeGrip(
                    packageName = model.packageName,
                    onResizeStart = onResizeStart,
                    onResizeDelta = onResizeDelta,
                    onResizeEnd = onResizeEnd,
                    onResizeCancel = onResizeCancel
                )
            }
            // 1-Click Portrait (9:19) <-> Landscape (16:10) Container Toggle
            TitlebarControlButton(
                icon = if (model.isPortraitContainer) DesktopIcons.AspectLandscape else DesktopIcons.PhonePortrait,
                contentDescription = if (model.isPortraitContainer) {
                    "Switch to Landscape Container (16:10)"
                } else {
                    "Switch to Portrait Window (9:19)"
                },
                hoverColor = MaterialTheme.colorScheme.secondaryContainer,
                iconTint = MaterialTheme.colorScheme.primary,
                hoverIconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onToggleContainerOrientation
            )
            // Minimize [—]
            TitlebarControlButton(
                icon = DesktopIcons.Minimize,
                contentDescription = "Minimize to Taskbar",
                hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                iconTint = MaterialTheme.colorScheme.onSurface,
                onClick = onMinimize
            )
            // Maximize / Container Toggle [□]
            TitlebarControlButton(
                icon = if (model.isMaximized) DesktopIcons.RestoreWindow else DesktopIcons.Maximize,
                contentDescription = "Maximize / Restore",
                hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                iconTint = MaterialTheme.colorScheme.onSurface,
                onClick = onMaximizeOrRestore
            )
            // Close [X] (Red Hover)
            TitlebarControlButton(
                icon = DesktopIcons.Close,
                contentDescription = "Close Window",
                hoverColor = CloseRedColor,
                iconTint = MaterialTheme.colorScheme.onSurface,
                hoverIconTint = Color.White,
                onClick = onClose
            )
        }
    }
}

@Composable
private fun TitlebarResizeGrip(
    packageName: String,
    onResizeStart: () -> Unit,
    onResizeDelta: (dx: Float, dy: Float) -> Unit,
    onResizeEnd: () -> Unit,
    onResizeCancel: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 22.dp)
            .clip(ControlButtonShape)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(
                if (isHovered) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
            )
            .pointerInput(packageName, "resizeGrip") {
                detectDragGestures(
                    onDragStart = { onResizeStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onResizeDelta(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = { onResizeEnd() },
                    onDragCancel = { onResizeCancel() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = DesktopIcons.ResizeHandle,
            contentDescription = "Drag to Resize Window",
            tint = if (isHovered) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun TitlebarControlButton(
    icon: ImageVector,
    contentDescription: String,
    hoverColor: Color,
    iconTint: Color,
    hoverIconTint: Color = iconTint,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 22.dp)
            .clip(ControlButtonShape)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (isHovered) hoverColor else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isHovered) hoverIconTint else iconTint,
            modifier = Modifier.size(13.dp)
        )
    }
}
