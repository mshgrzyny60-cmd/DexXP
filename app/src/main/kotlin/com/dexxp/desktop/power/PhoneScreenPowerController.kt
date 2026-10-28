package com.dexxp.desktop.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AMOLED Blackout & Thermal/Battery-Saver Engine for the primary phone screen.
 *
 * - Automatically detects physical USB HID / Bluetooth HID mice via
 *   [InputManager.registerInputDeviceListener] and triggers AMOLED Blackout when an external
 *   monitor is active.
 * - Supports manual "Sleep Phone Screen" toggling from both the Desktop Taskbar and Phone Controller.
 * - Drives phone window `screenBrightness = 0.0f` with `FLAG_KEEP_SCREEN_ON` so OLED subpixels emit
 *   zero light while keeping the DisplayRouterService / Touchpad / IME session awake.
 */
class PhoneScreenPowerController(
    context: Context,
    private val sessionRepository: DesktopSessionRepository
) {
    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(InputManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isBlackoutActive = MutableStateFlow(false)
    val isBlackoutActive: StateFlow<Boolean> = _isBlackoutActive.asStateFlow()

    private val _externalMouseName = MutableStateFlow<String?>(null)
    val externalMouseName: StateFlow<String?> = _externalMouseName.asStateFlow()

    @Volatile
    private var userWokeManuallyDuringSession = false

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val wasConnected = _externalMouseName.value != null
            val currentName = scanPhysicalMouseName()
            _externalMouseName.value = currentName
            if (!wasConnected && currentName != null) {
                userWokeManuallyManuallyReset()
                maybeTriggerAutoBlackout()
            }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            val currentName = scanPhysicalMouseName()
            _externalMouseName.value = currentName
            if (currentName == null) {
                userWokeManuallyManuallyReset()
            }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            _externalMouseName.value = scanPhysicalMouseName()
        }
    }

    init {
        _externalMouseName.value = scanPhysicalMouseName()
        inputManager?.registerInputDeviceListener(inputDeviceListener, mainHandler)
    }

    fun onExternalDisplayStateChanged(attached: Boolean) {
        if (!attached) {
            userWokeManuallyManuallyReset()
            _isBlackoutActive.value = false
        } else {
            _externalMouseName.value = scanPhysicalMouseName()
            maybeTriggerAutoBlackout()
        }
    }

    fun enableBlackout() {
        _isBlackoutActive.value = true
    }

    fun wakePhoneScreen() {
        if (_isBlackoutActive.value) {
            if (_externalMouseName.value != null) {
                userWokeManuallyDuringSession = true
            }
            _isBlackoutActive.value = false
        }
    }

    fun toggleBlackout() {
        if (_isBlackoutActive.value) {
            wakePhoneScreen()
        } else {
            enableBlackout()
        }
    }

    private fun maybeTriggerAutoBlackout() {
        if (sessionRepository.activeDisplay.value != null &&
            _externalMouseName.value != null &&
            !userWokeManuallyDuringSession
        ) {
            _isBlackoutActive.value = true
        }
    }

    private fun userWokeManuallyManuallyReset() {
        userWokeManuallyDuringSession = false
    }

    /**
     * Scans `InputManager` for non-virtual external USB or Bluetooth HID mice.
     */
    private fun scanPhysicalMouseName(): String? {
        val im = inputManager ?: return null
        val ids = im.inputDeviceIds
        for (id in ids) {
            if (id <= 0) continue
            val dev = im.getInputDevice(id) ?: continue
            if (dev.isVirtual) continue
            val sources = dev.sources
            val hasMouse = (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            val isTouchscreen = (sources and InputDevice.SOURCE_TOUCHSCREEN) != 0
            if (hasMouse && !isTouchscreen) {
                return dev.name?.trim()?.takeIf { it.isNotEmpty() } ?: "External Mouse"
            }
        }
        return null
    }

    /**
     * Applies `screenBrightness = 0.0f` + `FLAG_KEEP_SCREEN_ON` + hidden system bars during
     * AMOLED Blackout, restoring standard brightness and system bars instantly upon wake.
     */
    fun applyWindowPowerPolicy(window: Window, isBlackout: Boolean) {
        val hasExternalDisplay = sessionRepository.activeDisplay.value != null
        if (isBlackout || hasExternalDisplay) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val lp = window.attributes
        val targetBrightness = if (isBlackout) {
            0.0f // WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (lp.screenBrightness != targetBrightness) {
            lp.screenBrightness = targetBrightness
            window.attributes = lp
        }

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        if (isBlackout) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun shutdown() {
        runCatching { inputManager?.unregisterInputDeviceListener(inputDeviceListener) }
    }
}

/**
 * True `#000000` AMOLED Blackout overlay with minute-aligned pixel-shift burn-in protection.
 * Wakes immediately on any single tap or swipe gesture.
 */
@Composable
fun AmoledBlackoutHud(
    activeDisplay: ExternalDisplayInfo?,
    externalMouseName: String?,
    onWake: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var clockText by remember { mutableStateOf(timeFormatter.format(Date())) }
    var shiftStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            clockText = timeFormatter.format(Date(nowMs))
            shiftStep = ((nowMs / 60_000L) % 4L).toInt()
            val sleepMs = (60_000L - (nowMs % 60_000L)).coerceIn(500L, 60_000L)
            delay(sleepMs)
        }
    }

    // Subtle OLED pixel-shift offsets preventing static subpixel wear over multi-hour sessions
    val shiftX = when (shiftStep) {
        0 -> -8
        1 -> 8
        2 -> 6
        else -> -6
    }
    val shiftY = when (shiftStep) {
        0 -> -10
        1 -> -6
        2 -> 10
        else -> 6
    }

    val dimText = Color.White.copy(alpha = 0.34f)
    val subtleText = Color.White.copy(alpha = 0.20f)
    val dimBorder = Color.White.copy(alpha = 0.10f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onWake()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(shiftX.dp.roundToPx(), shiftY.dp.roundToPx()) }
                .border(1.dp, dimBorder, RoundedCornerShape(28.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = clockText,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Light,
                color = dimText
            )

            Spacer(Modifier.height(8.dp))

            BlackoutBatteryReadout(context = context, dimText = dimText)

            if (activeDisplay != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Monitor,
                        contentDescription = null,
                        tint = subtleText,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${activeDisplay.name} (${activeDisplay.resolutionSummary})",
                        style = MaterialTheme.typography.labelSmall,
                        color = subtleText
                    )
                }
            }

            if (externalMouseName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Mouse Active: $externalMouseName",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtleText
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = DesktopIcons.MoonSleep,
                    contentDescription = null,
                    tint = subtleText,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "AMOLED Power Saver • Tap or swipe anywhere to wake",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtleText
                )
            }
        }
    }
}

@Composable
private fun BlackoutBatteryReadout(context: Context, dimText: Color) {
    val batteryTelemetry = rememberBlackoutBattery(context)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = DesktopIcons.Battery,
            contentDescription = null,
            tint = dimText,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = buildString {
                append("${batteryTelemetry.first}%")
                if (batteryTelemetry.second) append(" • Charging")
            },
            style = MaterialTheme.typography.labelLarge,
            color = dimText
        )
    }
}

@Composable
private fun rememberBlackoutBattery(context: Context): Pair<Int, Boolean> {
    var state by remember { mutableStateOf(100 to false) }
    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val parse: (Intent?) -> Unit = { intent ->
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                state = ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100) to charging
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = parse(intent)
        }
        parse(context.registerReceiver(receiver, filter))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}
