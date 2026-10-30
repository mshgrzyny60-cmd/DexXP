package com.dexxp.desktop.presentation

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.dexxp.desktop.R
import com.dexxp.desktop.audio.AudioRouteManager
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.browser.DesktopBrowserLauncher
import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.files.DesktopFileManager
import com.dexxp.desktop.keyboard.DesktopKeyboardManager
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.layout.WorkspaceLayoutPresetManager
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.DisplayPerformanceProfile
import com.dexxp.desktop.model.ShellSurfaceMode
import com.dexxp.desktop.overview.MissionControlOverviewController
import com.dexxp.desktop.power.PhoneScreenPowerController
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import com.dexxp.desktop.ui.desktop.DesktopWorkspaceRoot
import com.dexxp.desktop.ui.theme.DexXPTheme
import com.dexxp.desktop.workspace.VirtualWorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Secondary-display `Presentation` hosting the Material 3 Jetpack Compose Desktop Root,
 * Notification Center, Clipboard History (`Win+V`), Virtual Workspaces, Quick Settings,
 * Alt+Tab Switcher, and Hardware Shortcut Interceptor.
 */
class DesktopWindowPresentation(
    scaledDisplayContext: Context,
    display: Display,
    private val displayWidthPx: Int,
    private val displayHeightPx: Int,
    private val scaledDensityDpi: Int,
    private val sessionRepository: DesktopSessionRepository,
    private val appRepository: AppRepository,
    private val appLauncher: AppLauncher,
    private val pointerFocusBridge: PointerFocusBridge,
    private val audioRouteManager: AudioRouteManager,
    private val desktopFileManager: DesktopFileManager,
    private val phoneScreenPowerController: PhoneScreenPowerController,
    private val clipboardManager: DesktopClipboardManager,
    private val virtualWorkspaceManager: VirtualWorkspaceManager,
    private val desktopBrowserLauncher: DesktopBrowserLauncher,
    private val presetManager: WorkspaceLayoutPresetManager,
    private val terminalLauncher: DesktopTerminalLauncher,
    private val commandPaletteController: CommandPaletteController,
    private val missionControlController: MissionControlOverviewController,
    private val phoneHubController: PhoneHubController,
    private val keyboardManager: DesktopKeyboardManager,
    private val hasHardwareCursorOverlay: () -> Boolean
) : Presentation(scaledDisplayContext, display, R.style.Theme_DexXP_Presentation),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var presentationScope: CoroutineScope? = null

    @Volatile
    private var currentSurfaceMode: ShellSurfaceMode = ShellSurfaceMode.FULL_DESKTOP

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val win = window ?: return
        configureExternalWindow(win)

        val decorView = win.decorView
        decorView.setViewTreeLifecycleOwner(this)
        decorView.setViewTreeViewModelStoreOwner(this)
        decorView.setViewTreeSavedStateRegistryOwner(this)

        val composeView = ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@DesktopWindowPresentation)
            setViewTreeViewModelStoreOwner(this@DesktopWindowPresentation)
            setViewTreeSavedStateRegistryOwner(this@DesktopWindowPresentation)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                DexXPTheme(allowDynamicColorOnPhone = false) {
                    DesktopWorkspaceRoot(
                        sessionRepository = sessionRepository,
                        appRepository = appRepository,
                        appLauncher = appLauncher,
                        pointerFocusBridge = pointerFocusBridge,
                        audioRouteManager = audioRouteManager,
                        desktopFileManager = desktopFileManager,
                        phoneScreenPowerController = phoneScreenPowerController,
                        clipboardManager = clipboardManager,
                        virtualWorkspaceManager = virtualWorkspaceManager,
                        desktopBrowserLauncher = desktopBrowserLauncher,
                        presetManager = presetManager,
                        terminalLauncher = terminalLauncher,
                        commandPaletteController = commandPaletteController,
                        missionControlController = missionControlController,
                        phoneHubController = phoneHubController,
                        keyboardManager = keyboardManager,
                        renderInlineCursorFallback = !hasHardwareCursorOverlay(),
                        isBackdropOnly = false
                    )
                }
            }
        }

        setContentView(composeView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            clipboardManager.syncCurrentPrimaryClip()
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val win = window ?: return
        pointerFocusBridge.registerPresentationWindow(win) {
            computeCurrentScreenBounds()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        presentationScope = scope
        scope.launch {
            combine(
                sessionRepository.shellSurfaceMode,
                sessionRepository.needsShellKeyboardFocus,
                sessionRepository.environmentMode,
                keyboardManager.altTabState
            ) { mode, needsKeyboard, envMode, altTab ->
                val isTv = envMode == DesktopEnvironmentMode.TV_LEANBACK
                val effectiveMode = if (altTab.isVisible) {
                    ShellSurfaceMode.FLYOUT_ACTIVE
                } else if (isTv) {
                    ShellSurfaceMode.FULL_DESKTOP
                } else {
                    mode
                }
                Triple(effectiveMode, needsKeyboard, isTv)
            }.collect { (mode, needsKeyboard, isTv) ->
                currentSurfaceMode = mode
                pointerFocusBridge.applyPresentationWindowPolicy(
                    window = win,
                    mode = mode,
                    needsKeyboardFocus = needsKeyboard || isTv,
                    displayWidthPx = displayWidthPx,
                    displayHeightPx = displayHeightPx,
                    densityDpi = scaledDensityDpi
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyboardManager.onHardwareKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            pointerFocusBridge.onOutsideWindowTouch()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            val isDown = (ev.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
            pointerFocusBridge.setPointerPosition(ev.x, ev.y, isDown, sourceDisplayId = display.displayId)
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onStop() {
        presentationScope?.cancel()
        presentationScope = null
        window?.let { pointerFocusBridge.unregisterPresentationWindow(it) }

        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
        store.clear()
        super.onStop()
    }

    private fun computeCurrentScreenBounds(): Rect {
        return if (currentSurfaceMode == ShellSurfaceMode.DOCKED_TASKBAR) {
            val density = scaledDensityDpi / 160f
            val taskbarPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()
            Rect(0, (displayHeightPx - taskbarPx).coerceAtLeast(0), displayWidthPx, displayHeightPx)
        } else {
            Rect(0, 0, displayWidthPx, displayHeightPx)
        }
    }

    private fun configureExternalWindow(win: Window) {
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        win.addFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        val bestMode = selectOptimalDisplayMode(display)
        if (bestMode != null) {
            val attrs = win.attributes
            attrs.preferredDisplayModeId = bestMode.modeId
            win.attributes = attrs
        }

        WindowCompat.setDecorFitsSystemWindows(win, false)
        WindowInsetsControllerCompat(win, win.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun selectOptimalDisplayMode(targetDisplay: Display): Display.Mode? = runCatching {
        if (!targetDisplay.isValid) return null
        val modes = targetDisplay.supportedModes ?: return null
        if (modes.isEmpty()) return null

        val profile = sessionRepository.performanceProfile.value
        val isWireless = (targetDisplay.flags and Display.FLAG_PRESENTATION) != 0 &&
            (targetDisplay.name?.contains("wifi", ignoreCase = true) == true ||
                targetDisplay.name?.contains("cast", ignoreCase = true) == true ||
                targetDisplay.name?.contains("wireless", ignoreCase = true) == true)

        when {
            (isWireless && profile != DisplayPerformanceProfile.NATIVE_MAX) ||
                profile == DisplayPerformanceProfile.ULTRA_FAST_1080P -> {
                modes.filter { it.physicalWidth <= 1920 && it.physicalHeight <= 1080 }
                    .maxWithOrNull(compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight.toLong() }.thenBy { it.refreshRate })
                    ?: modes.minByOrNull { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
            }
            profile == DisplayPerformanceProfile.SMOOTH_720P -> {
                modes.filter { it.physicalWidth <= 1280 && it.physicalHeight <= 720 }
                    .maxWithOrNull(compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight.toLong() }.thenBy { it.refreshRate })
                    ?: modes.minByOrNull { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
            }
            else -> {
                modes.maxWithOrNull(
                    compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
                        .thenBy { it.refreshRate }
                )
            }
        }
    }.getOrNull()
}
