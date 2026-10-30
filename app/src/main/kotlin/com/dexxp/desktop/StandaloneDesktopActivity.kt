package com.dexxp.desktop

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dexxp.desktop.model.DisplayTransportType
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.service.DisplayRouterService
import com.dexxp.desktop.ui.desktop.DesktopWorkspaceRoot
import com.dexxp.desktop.ui.theme.DexXPTheme

/**
 * Standalone Immersive Desktop Activity for Direct PC / scrcpy usage, physical monitor fallback,
 * and edge-to-edge desktop mode on any display (including Display.DEFAULT_DISPLAY).
 *
 * Enforces:
 * - 100% immersive fullscreen with zero status bar or navigation bar.
 * - Hardware mouse & keyboard event bridge routing for scrcpy UHID / USB mouse.
 * - Hardware cursor rendering directly on the desktop canvas.
 * - Clean exit on Back button/gesture.
 */
class StandaloneDesktopActivity : ComponentActivity() {

    private var didRegisterStandaloneDisplay: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make window 100% immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Prevent soft keyboard from resizing desktop canvas
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )

        // Start background display router service to manage audio and apps
        DisplayRouterService.start(this)

        val app = application as DexXpApplication

        // Configure display metrics
        val decor = window.decorView
        decor.post {
            val width = decor.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val height = decor.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            app.pointerFocusBridge.configureDisplayDimensions(width, height)

            if (app.sessionRepository.activeDisplay.value == null) {
                didRegisterStandaloneDisplay = true
                val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                app.sessionRepository.updateActiveDisplay(
                    ExternalDisplayInfo(
                        displayId = currentDisplay?.displayId ?: Display.DEFAULT_DISPLAY,
                        name = "Standalone Desktop (PC / scrcpy)",
                        widthPx = width,
                        heightPx = height,
                        refreshRateHz = (currentDisplay?.refreshRate ?: 60f).toInt(),
                        nativeDensityDpi = resources.displayMetrics.densityDpi,
                        scaledDensityDpi = resources.displayMetrics.densityDpi,
                        transportType = DisplayTransportType.SCRCPY_VIRTUAL_DISPLAY,
                        isHdrSupported = false
                    )
                )
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
                    renderInlineCursorFallback = true,
                    isBackdropOnly = false
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as DexXpApplication
        app.pointerFocusBridge.registerBackdropWindow(window) {
            val decor = window.peekDecorView()
            Rect(
                0,
                0,
                decor?.width ?: resources.displayMetrics.widthPixels,
                decor?.height ?: resources.displayMetrics.heightPixels
            )
        }
    }

    override fun onStop() {
        val app = application as DexXpApplication
        app.pointerFocusBridge.unregisterBackdropWindow(window)
        super.onStop()
    }

    override fun onDestroy() {
        if (didRegisterStandaloneDisplay) {
            val app = application as DexXpApplication
            if (app.sessionRepository.activeDisplay.value?.transportType == DisplayTransportType.SCRCPY_VIRTUAL_DISPLAY) {
                app.sessionRepository.updateActiveDisplay(null)
            }
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as DexXpApplication
        if (app.keyboardManager.onHardwareKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val app = application as DexXpApplication
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    app.pointerFocusBridge.setPointerPosition(event.x, event.y, isDown = false)
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val app = application as DexXpApplication
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    app.pointerFocusBridge.setPointerPosition(event.x, event.y, isDown = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    app.pointerFocusBridge.setPointerPosition(event.x, event.y, isDown = true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    app.pointerFocusBridge.setPointerPosition(event.x, event.y, isDown = false)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
