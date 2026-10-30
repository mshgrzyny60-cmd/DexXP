package com.dexxp.desktop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dexxp.desktop.service.DisplayRouterService
import com.dexxp.desktop.ui.phone.PhoneControllerScreen
import com.dexxp.desktop.ui.theme.DexXPTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Primary phone display host Activity (`Display.DEFAULT_DISPLAY`).
 * Routes hardware keyboard shortcuts (`Alt+Tab`, `Super`, `Win+V`, `Super+Ctrl+Arrows`, `Super+Arrows`),
 * enforces AMOLED Blackout power policy (`screenBrightness = 0.0f` + `FLAG_KEEP_SCREEN_ON`),
 * and hosts [PhoneControllerScreen].
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        (application as DexXpApplication).sessionRepository.refreshSystemReadiness()
        DisplayRouterService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensurePermissionsAndStartEngine()

        val app = application as DexXpApplication

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    app.phoneScreenPowerController.isBlackoutActive,
                    app.sessionRepository.activeDisplay
                ) { isBlackout, _ ->
                    isBlackout
                }.collect { isBlackout ->
                    app.phoneScreenPowerController.applyWindowPowerPolicy(window, isBlackout)
                }
            }
        }

        setContent {
            DexXPTheme(allowDynamicColorOnPhone = true) {
                PhoneControllerScreen(
                    sessionRepository = app.sessionRepository,
                    pointerFocusBridge = app.pointerFocusBridge,
                    phoneScreenPowerController = app.phoneScreenPowerController,
                    keyboardManager = app.keyboardManager,
                    missionControlController = app.missionControlController,
                    phoneHubController = app.phoneHubController
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as DexXpApplication
        app.sessionRepository.refreshSystemReadiness()
        app.clipboardManager.syncCurrentPrimaryClip()
        app.phoneHubController.refreshTelephonyCallbackRegistration()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            (application as DexXpApplication).clipboardManager.syncCurrentPrimaryClip()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as DexXpApplication
        if (app.sessionRepository.activeDisplay.value != null &&
            app.keyboardManager.onHardwareKeyEvent(event)
        ) {
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
                    app.pointerFocusBridge.setPointerPosition(event.x, event.y, isDown = false, sourceDisplayId = display?.displayId)
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val app = application as DexXpApplication
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    app.pointerFocusBridge.setPointerPosition(event.x, event.y, isDown = true, sourceDisplayId = display?.displayId)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    app.pointerFocusBridge.setPointerPosition(event.x, event.y, isDown = false, sourceDisplayId = display?.displayId)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun ensurePermissionsAndStartEngine() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DisplayRouterService.start(this)
        }
    }
}
