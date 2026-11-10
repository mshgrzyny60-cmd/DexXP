package com.dexxp.desktop

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.model.ShellSurfaceMode
import com.dexxp.desktop.service.DisplayRouterService
import com.dexxp.desktop.ui.desktop.DesktopWorkspaceRoot
import com.dexxp.desktop.ui.theme.DexXPTheme
import kotlinx.coroutines.launch

/**
 * Layer-2 `CATEGORY_SECONDARY_HOME` Activity hosted at the root of an external display's
 * `TaskDisplayArea` when `force_desktop_mode_on_external_displays=1`.
 *
 * Self-terminates cleanly via [DisplayManager.DisplayListener] if the HDMI / USB-C DP cable
 * is pulled mid-session so it never leaks or migrates onto `Display.DEFAULT_DISPLAY`.
 */
class SecondaryDesktopActivity : ComponentActivity() {

    private var hostedDisplayId: Int = Display.INVALID_DISPLAY
    private var displayManager: DisplayManager? = null
    private var wasStoppedBehindExternalApp: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val confirmExternalCloseRunnable = Runnable {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            DesktopPointerAccessibilityService.instance == null
        ) {
            (application as DexXpApplication).sessionRepository.onSecondaryHomeResumedAfterExternalClose()
        }
    }

    private val cableDisconnectListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == hostedDisplayId) {
                val active = displayManager?.getDisplay(displayId)
                if (active == null || !active.isValid) {
                    if (!isFinishing) finish()
                }
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == hostedDisplayId && !isFinishing) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (currentDisplayId == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        hostedDisplayId = currentDisplayId
        displayManager = getSystemService(DisplayManager::class.java)?.also { dm ->
            dm.registerDisplayListener(cableDisconnectListener, Handler(Looper.getMainLooper()))
        }

        DisplayRouterService.start(this)

        applyStickyImmersiveSystemBars()

        val app = application as DexXpApplication
        lifecycleScope.launch {
            app.sessionRepository.shellSurfaceMode.collect { mode ->
                if (mode == ShellSurfaceMode.DOCKED_TASKBAR && !isFinishing && !isDestroyed) {
                    wasStoppedBehindExternalApp = true
                    runCatching { moveTaskToBack(true) }
                }
            }
        }

        setContent {
            DexXPTheme(allowDynamicColorOnPhone = false) {
                DesktopWorkspaceRoot(
                    sessionRepository = app.sessionRepository,
                    appRepository = app.appRepository,
                    appLauncher = app.appLauncher,
                    pointerFocusBridge = app.pointerFocusBridge,
                    audioRouteManager = app.audioRouteManager,
                    desktopFileManager = app.desktopFileManager,
                    phoneScreenPowerController = app.phoneScreenPowerController,
                    clipboardManager = app.clipboardManager,
                    virtualWorkspaceManager = app.virtualWorkspaceManager,
                    desktopBrowserLauncher = app.desktopBrowserLauncher,
                    presetManager = app.presetManager,
                    terminalLauncher = app.terminalLauncher,
                    commandPaletteController = app.commandPaletteController,
                    missionControlController = app.missionControlController,
                    phoneHubController = app.phoneHubController,
                    keyboardManager = app.keyboardManager,
                    renderInlineCursorFallback = false,
                    isBackdropOnly = true
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as DexXpApplication
        if (app.keyboardManager.onHardwareKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        applyStickyImmersiveSystemBars()
    }

    override fun onStart() {
        super.onStart()
        applyStickyImmersiveSystemBars()
        val app = application as DexXpApplication
        app.pointerFocusBridge.registerBackdropWindow(window) {
            val decor = window.peekDecorView()
            Rect(0, 0, decor?.width ?: 1920, decor?.height ?: 1080)
        }
    }

    override fun onResume() {
        super.onResume()
        applyStickyImmersiveSystemBars()
        val app = application as DexXpApplication
        if (app.sessionRepository.shellSurfaceMode.value == ShellSurfaceMode.DOCKED_TASKBAR &&
            app.sessionRepository.isWithinLaunchGracePeriod()
        ) {
            wasStoppedBehindExternalApp = true
            runCatching { moveTaskToBack(true) }
            return
        }
        if (wasStoppedBehindExternalApp) {
            wasStoppedBehindExternalApp = false
            // When Accessibility is disabled, perform two consecutive RESUMED checks separated by 250ms
            if (DesktopPointerAccessibilityService.instance == null) {
                app.sessionRepository.onSecondaryHomeResumedAfterExternalClose()
                mainHandler.removeCallbacks(confirmExternalCloseRunnable)
                mainHandler.postDelayed(confirmExternalCloseRunnable, 250L)
            }
        }
        app.pointerFocusBridge.bringShellToFront(requestFocus = false)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(confirmExternalCloseRunnable)
        super.onPause()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(confirmExternalCloseRunnable)
        val app = application as DexXpApplication
        if (!app.sessionRepository.isShowDesktopActive.value &&
            app.sessionRepository.runningWindows.value.any { !it.isMinimized }
        ) {
            wasStoppedBehindExternalApp = true
        }
        app.pointerFocusBridge.unregisterBackdropWindow(window)
        super.onStop()
    }

    private fun applyStickyImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(confirmExternalCloseRunnable)
        displayManager?.unregisterDisplayListener(cableDisconnectListener)
        displayManager = null
        super.onDestroy()
    }
}
