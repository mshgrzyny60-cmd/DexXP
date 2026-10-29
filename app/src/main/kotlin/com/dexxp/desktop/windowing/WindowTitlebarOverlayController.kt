package com.dexxp.desktop.windowing

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Projects a sleek, semi-transparent `28dp` Material 3 Floating Titlebar directly above the active
 * third-party Freeform window on the secondary display.
 *
 * - Tracks live top-boundary coordinates from `DesktopPointerAccessibilityService.getWindowsOnAllDisplays()`
 *   (via [PointerFocusBridge.detectedExternalFrames]) reconciled with [DesktopSessionRepository.runningWindows].
 * - Uses `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` so clicking Minimize, Maximize/Restore, or Close
 *   never steals input focus from the active Freeform app or blocks outside touches.
 * - Automatically hides when the active window is maximized, minimized, or covered by shell flyouts.
 */
class WindowTitlebarOverlayController(
    private val appContext: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appRepository: AppRepository,
    private val appLauncher: AppLauncher,
    private val pointerFocusBridge: PointerFocusBridge
) {
    data class TitlebarRenderModel(
        val packageName: String,
        val label: String,
        val iconBitmap: ImageBitmap?,
        val isMaximized: Boolean,
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
    private var hostLifecycle: OverlayHostLifecycle? = null
    private var scope: CoroutineScope? = null

    private val renderModelFlow = MutableStateFlow<TitlebarRenderModel?>(null)

    @Volatile
    private var currentVisibleBounds: Rect? = null

    fun attachToDisplay(display: Display, scaledDisplayContext: Context): Boolean {
        detach()
        if (!display.isValid) return false

        val a11yService = DesktopPointerAccessibilityService.instance
        val windowType: Int
        val baseOverlayCtx: Context

        if (a11yService != null) {
            val a11yCtx = runCatching {
                a11yService.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    null
                )
            }.getOrNull()
            if (a11yCtx != null) {
                baseOverlayCtx = a11yCtx
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Settings.canDrawOverlays(appContext)) {
                val appCtx = runCatching {
                    scaledDisplayContext.createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null
                    )
                }.getOrNull() ?: return false
                baseOverlayCtx = appCtx
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                return false
            }
        } else if (Settings.canDrawOverlays(appContext)) {
            val appCtx = runCatching {
                scaledDisplayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
            }.getOrNull() ?: return false
            baseOverlayCtx = appCtx
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            return false
        }

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
                            onMinimize = {
                                appLauncher.toggleMinimizeWindow(activeModel.packageName)
                            },
                            onMaximizeOrRestore = {
                                appLauncher.toggleMaximizeOrRestoreWindow(activeModel.packageName)
                            },
                            onClose = {
                                appLauncher.closeWindow(activeModel.packageName)
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
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
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
            Log.w(TAG, "Unable to attach floating window titlebar on display ${display.displayId}", err)
        }.getOrDefault(false)

        if (!added) {
            owner.onDetach()
            return false
        }

        windowManager = wm
        composeView = view
        layoutParams = lp
        hostLifecycle = owner

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
                resolveActiveTitlebarModel(
                    liveFrames = liveFrames,
                    records = records,
                    showDesktop = showDesktop,
                    flyoutOpen = flyoutOpen,
                    displayInfo = displayInfo,
                    barHeightPx = barHeightPx
                )
            }.collect { nextModel ->
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

        val targetPkg = liveFocused?.packageName ?: topRecord?.packageName ?: return null
        val matchedRecord = visibleRecords.firstOrNull { it.packageName == targetPkg }
        if (records.any { it.packageName == targetPkg && it.isMinimized }) return null

        val rawWinBounds = liveFocused?.bounds ?: matchedRecord?.bounds ?: return null
        if (rawWinBounds.width() < 180 || rawWinBounds.height() < 120) return null

        val isMaximizedSnap = matchedRecord?.snapMode == WindowSnapMode.MAXIMIZED
        val fillsWorkArea = rawWinBounds.width() >= (displayInfo.widthPx * 0.95f).roundToInt() &&
            rawWinBounds.top <= (10 * (displayInfo.scaledDensityDpi / 160f)).roundToInt()

        // Titlebar hides seamlessly when the window is maximized
        if (isMaximizedSnap || fillsWorkArea) {
            return null
        }

        val appItem = appRepository.findByPackage(targetPkg)
        val resolvedTitle = liveFocused?.title?.takeIf { it.isNotBlank() }
            ?: matchedRecord?.label
            ?: appItem?.label
            ?: targetPkg.substringAfterLast('.')

        val barWidth = rawWinBounds.width().coerceIn(220, displayInfo.widthPx)
        val barLeft = rawWinBounds.left.coerceIn(0, (displayInfo.widthPx - barWidth).coerceAtLeast(0))
        val barTop = (rawWinBounds.top - barHeightPx - 2).coerceAtLeast(2)
        val barBounds = Rect(barLeft, barTop, barLeft + barWidth, barTop + barHeightPx)

        return TitlebarRenderModel(
            packageName = targetPkg,
            label = resolvedTitle,
            iconBitmap = appItem?.iconBitmap,
            isMaximized = false,
            barScreenBounds = barBounds
        )
    }

    private fun applyTitlebarLayout(model: TitlebarRenderModel?) {
        val view = composeView ?: return
        val wm = windowManager ?: return
        val lp = layoutParams ?: return

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
        val geometryChanged = lp.x != b.left ||
            lp.y != b.top ||
            lp.width != b.width() ||
            lp.height != b.height()

        currentVisibleBounds = b
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        if (geometryChanged && view.isAttachedToWindow) {
            lp.x = b.left
            lp.y = b.top
            lp.width = b.width()
            lp.height = b.height()
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        currentVisibleBounds = null
        renderModelFlow.value = null

        val view = composeView
        val wm = windowManager
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
    }
}

@Composable
private fun FloatingWindowTitlebarBar(
    model: WindowTitlebarOverlayController.TitlebarRenderModel,
    onMinimize: () -> Unit,
    onMaximizeOrRestore: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
            )
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model.iconBitmap != null) {
            Image(
                bitmap = model.iconBitmap,
                contentDescription = model.label,
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
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
            modifier = Modifier.weight(1f)
        )

        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TitlebarControlButton(
                icon = DesktopIcons.Minimize,
                contentDescription = "Minimize to Taskbar",
                hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                iconTint = MaterialTheme.colorScheme.onSurface,
                onClick = onMinimize
            )
            TitlebarControlButton(
                icon = if (model.isMaximized) DesktopIcons.RestoreWindow else DesktopIcons.Maximize,
                contentDescription = "Maximize / Restore",
                hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                iconTint = MaterialTheme.colorScheme.onSurface,
                onClick = onMaximizeOrRestore
            )
            TitlebarControlButton(
                icon = DesktopIcons.Close,
                contentDescription = "Close Window",
                hoverColor = Color(0xFFD93025),
                iconTint = MaterialTheme.colorScheme.onSurface,
                hoverIconTint = Color.White,
                onClick = onClose
            )
        }
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
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interactionSource)
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
