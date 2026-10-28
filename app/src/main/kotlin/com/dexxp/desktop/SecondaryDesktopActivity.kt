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
import com.dexxp.desktop.service.DisplayRouterService
import com.dexxp.desktop.ui.desktop.DesktopWorkspaceRoot
import com.dexxp.desktop.ui.theme.DexXPTheme

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

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val app = application as DexXpApplication
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

    override fun onStart() {
        super.onStart()
        val app = application as DexXpApplication
        app.pointerFocusBridge.registerBackdropWindow(window) {
            val decor = window.peekDecorView()
            Rect(0, 0, decor?.width ?: 1920, decor?.height ?: 1080)
        }
    }

    override fun onStop() {
        val app = application as DexXpApplication
        app.pointerFocusBridge.unregisterBackdropWindow(window)
        super.onStop()
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(cableDisconnectListener)
        displayManager = null
        super.onDestroy()
    }
}
