package com.dexxp.desktop.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dexxp.desktop.DexXpApplication
import com.dexxp.desktop.MainActivity
import com.dexxp.desktop.R
import com.dexxp.desktop.SecondaryDesktopActivity
import com.dexxp.desktop.bridge.CursorOverlayController
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.DisplayTransportType
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.presentation.DesktopWindowPresentation
import com.dexxp.desktop.windowing.WindowTitlebarOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground orchestration service monitoring `DisplayManager` for HDMI, USB-C DisplayPort
 * Alternate Mode, and Wireless Miracast displays.
 */
class DisplayRouterService : Service() {

    inner class LocalBinder : Binder() {
        val service: DisplayRouterService
            get() = this@DisplayRouterService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var displayManager: DisplayManager
    private lateinit var cursorOverlayController: CursorOverlayController
    private lateinit var titlebarOverlayController: WindowTitlebarOverlayController

    private var activePresentation: DesktopWindowPresentation? = null
    private var attachedDisplayId: Int = Display.INVALID_DISPLAY

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) return
            Log.i(TAG, "External display connected: id=$displayId")
            dexApp.audioRouteManager.refreshRoutes()
            evaluateAndBindExternalDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == attachedDisplayId) {
                val display = displayManager.getDisplay(displayId)
                if (display == null || !display.isValid) {
                    Log.w(TAG, "Active external display invalidated mid-session: id=$displayId")
                    teardownActivePresentation()
                    dexApp.audioRouteManager.refreshRoutes()
                    evaluateAndBindExternalDisplays()
                    return
                }
                if (display.state == Display.STATE_OFF) return
                val currentInfo = dexApp.sessionRepository.activeDisplay.value
                val metrics = readRealDisplayMetrics(display)
                if (currentInfo != null &&
                    (currentInfo.widthPx != metrics.widthPixels ||
                        currentInfo.heightPx != metrics.heightPixels)
                ) {
                    bindPresentationToDisplay(display)
                }
            } else if (attachedDisplayId == Display.INVALID_DISPLAY &&
                displayId != Display.DEFAULT_DISPLAY
            ) {
                evaluateAndBindExternalDisplays()
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == attachedDisplayId) {
                Log.i(TAG, "External display removed: id=$displayId")
                teardownActivePresentation()
                dexApp.audioRouteManager.refreshRoutes()
                evaluateAndBindExternalDisplays()
            }
        }
    }

    private val dexApp: DexXpApplication
        get() = application as DexXpApplication

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        cursorOverlayController = CursorOverlayController(
            appContext = applicationContext,
            pointerFocusBridge = dexApp.pointerFocusBridge,
            sessionRepository = dexApp.sessionRepository
        )
        titlebarOverlayController = WindowTitlebarOverlayController(
            appContext = applicationContext,
            sessionRepository = dexApp.sessionRepository,
            appRepository = dexApp.appRepository,
            appLauncher = dexApp.appLauncher,
            pointerFocusBridge = dexApp.pointerFocusBridge
        )

        ensureNotificationChannel()
        promoteToForeground(statusText = "Waiting for HDMI / USB-C DP or Wireless display")

        displayManager.registerDisplayListener(displayListener, mainHandler)
        evaluateAndBindExternalDisplays()

        serviceScope.launch {
            dexApp.sessionRepository.dpiScale
                .drop(1)
                .collect {
                    if (attachedDisplayId != Display.INVALID_DISPLAY) {
                        displayManager.getDisplay(attachedDisplayId)?.let { display ->
                            bindPresentationToDisplay(display)
                        }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_DESKTOP_ENGINE -> {
                teardownActivePresentation()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESCAN_DISPLAYS -> evaluateAndBindExternalDisplays()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        teardownActivePresentation()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun evaluateAndBindExternalDisplays() {
        val candidate = findBestExternalDisplay()
        if (candidate == null) {
            if (attachedDisplayId != Display.INVALID_DISPLAY) {
                teardownActivePresentation()
            }
            promoteToForeground("Ready • Plug in USB-C DisplayPort, HDMI, or Wireless Display")
            return
        }

        if (candidate.displayId != attachedDisplayId || activePresentation?.isShowing != true) {
            bindPresentationToDisplay(candidate)
        }
    }

    private fun findBestExternalDisplay(): Display? {
        val presentationDisplays = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { isEligibleExternalDisplay(it) }

        if (presentationDisplays.isNotEmpty()) {
            return presentationDisplays.maxByOrNull { d ->
                val m = readRealDisplayMetrics(d)
                m.widthPixels.toLong() * m.heightPixels.toLong()
            }
        }

        return displayManager.displays
            .filter { isEligibleExternalDisplay(it) }
            .maxByOrNull { d ->
                val m = readRealDisplayMetrics(d)
                m.widthPixels.toLong() * m.heightPixels.toLong()
            }
    }

    private fun isEligibleExternalDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY || !display.isValid) return false
        val flags = display.flags
        val isPrivateInaccessible = (flags and Display.FLAG_PRIVATE) != 0 &&
            (flags and Display.FLAG_PRESENTATION) == 0
        return !isPrivateInaccessible
    }

    private fun bindPresentationToDisplay(display: Display) {
        if (!display.isValid) return
        teardownActivePresentation(keepPowerSession = true)

        val metrics = readRealDisplayMetrics(display)
        val widthPx = metrics.widthPixels.coerceAtLeast(640)
        val heightPx = metrics.heightPixels.coerceAtLeast(480)
        val scale: DesktopDpiScale = dexApp.sessionRepository.dpiScale.value
        val targetDpi = scale.computeTargetDpi(widthPx, heightPx)

        val baseDisplayContext = runCatching { createDisplayContext(display) }.getOrNull() ?: return
        val overrideConfig = Configuration(baseDisplayContext.resources.configuration).apply {
            densityDpi = targetDpi
            fontScale = 1.0f
            screenWidthDp = (widthPx * 160f / targetDpi).roundToInt()
            screenHeightDp = (heightPx * 160f / targetDpi).roundToInt()
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
        }
        val scaledDisplayContext = baseDisplayContext.createConfigurationContext(overrideConfig)

        val bestMode = runCatching {
            display.supportedModes.maxWithOrNull(
                compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
                    .thenBy { it.refreshRate }
            )
        }.getOrNull()
        val refreshHz = (bestMode?.refreshRate ?: display.refreshRate).roundToInt().coerceAtLeast(30)
        val isHdr = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                display.isHdr
            } else {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
            }
        }.getOrDefault(false)

        val info = ExternalDisplayInfo(
            displayId = display.displayId,
            name = display.name ?: "External Monitor #${display.displayId}",
            widthPx = widthPx,
            heightPx = heightPx,
            refreshRateHz = refreshHz,
            nativeDensityDpi = metrics.densityDpi,
            scaledDensityDpi = targetDpi,
            transportType = classifyDisplayTransport(display),
            isHdrSupported = isHdr
        )

        dexApp.pointerFocusBridge.configureDisplayDimensions(widthPx, heightPx)
        dexApp.sessionRepository.updateActiveDisplay(info)
        dexApp.sessionRepository.refreshSystemReadiness()
        dexApp.audioRouteManager.refreshRoutes()
        dexApp.desktopFileManager.reloadFiles()
        dexApp.phoneScreenPowerController.onExternalDisplayStateChanged(attached = true)

        launchSecondaryBackdropActivity(display.displayId)

        val presentation = DesktopWindowPresentation(
            scaledDisplayContext = scaledDisplayContext,
            display = display,
            displayWidthPx = widthPx,
            displayHeightPx = heightPx,
            scaledDensityDpi = targetDpi,
            sessionRepository = dexApp.sessionRepository,
            appRepository = dexApp.appRepository,
            appLauncher = dexApp.appLauncher,
            pointerFocusBridge = dexApp.pointerFocusBridge,
            audioRouteManager = dexApp.audioRouteManager,
            desktopFileManager = dexApp.desktopFileManager,
            phoneScreenPowerController = dexApp.phoneScreenPowerController,
            clipboardManager = dexApp.clipboardManager,
            virtualWorkspaceManager = dexApp.virtualWorkspaceManager,
            desktopBrowserLauncher = dexApp.desktopBrowserLauncher,
            presetManager = dexApp.presetManager,
            terminalLauncher = dexApp.terminalLauncher,
            commandPaletteController = dexApp.commandPaletteController,
            missionControlController = dexApp.missionControlController,
            phoneHubController = dexApp.phoneHubController,
            keyboardManager = dexApp.keyboardManager,
            hasHardwareCursorOverlay = { cursorOverlayController.isAttached }
        )

        runCatching {
            presentation.show()
            activePresentation = presentation
            attachedDisplayId = display.displayId
            // Attach 28dp floating window titlebar first, then cursor HUD above it for top Z-order
            titlebarOverlayController.attachToDisplay(display, scaledDisplayContext)
            cursorOverlayController.attachToDisplay(display)
            promoteToForeground(
                statusText = "${info.name} • ${info.resolutionSummary} (${targetDpi}dpi)"
            )
        }.onFailure { err ->
            Log.e(TAG, "Failed showing DesktopWindowPresentation on display ${display.displayId}", err)
            teardownActivePresentation()
        }
    }

    private fun launchSecondaryBackdropActivity(targetDisplayId: Int) {
        val intent = Intent(this, SecondaryDesktopActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(targetDisplayId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }
        runCatching { startActivity(intent, options.toBundle()) }
    }

    private fun teardownActivePresentation(keepPowerSession: Boolean = false) {
        cursorOverlayController.detach()
        titlebarOverlayController.detach()
        activePresentation?.let { pres ->
            runCatching { pres.dismiss() }
        }
        activePresentation = null
        attachedDisplayId = Display.INVALID_DISPLAY
        dexApp.keyboardManager.dismissAltTab()
        dexApp.sessionRepository.dismissFlyouts()
        dexApp.pointerFocusBridge.onExternalDisplayDisconnected()
        dexApp.sessionRepository.updateActiveDisplay(null)
        if (!keepPowerSession) {
            dexApp.phoneScreenPowerController.onExternalDisplayStateChanged(attached = false)
        }
    }

    private fun readRealDisplayMetrics(display: Display): DisplayMetrics {
        val out = DisplayMetrics()
        runCatching {
            @Suppress("DEPRECATION")
            display.getRealMetrics(out)
        }
        return out
    }

    private fun classifyDisplayTransport(display: Display): DisplayTransportType {
        val lowerName = (display.name ?: "").lowercase()
        return when {
            lowerName.contains("wifi") ||
                lowerName.contains("miracast") ||
                lowerName.contains("cast") ||
                lowerName.contains("wireless") -> DisplayTransportType.WIRELESS_MIRACAST

            lowerName.contains("overlay") ||
                lowerName.contains("simulated") ||
                lowerName.contains("virtual") -> DisplayTransportType.VIRTUAL_OR_OVERLAY

            else -> DisplayTransportType.HDMI_DP_ALT_MODE
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun promoteToForeground(statusText: String) {
        val notification = buildForegroundNotification(statusText)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        }.onFailure {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openPhoneController = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopEngineIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, DisplayRouterService::class.java).apply {
                action = ACTION_STOP_DESKTOP_ENGINE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPhoneController)
            .addAction(0, "Trackpad", openPhoneController)
            .addAction(0, "Disconnect", stopEngineIntent)
            .build()
    }

    companion object {
        private const val TAG = "DisplayRouterService"
        private const val CHANNEL_ID = "dexxp_display_router"
        private const val NOTIFICATION_ID = 4102

        const val ACTION_STOP_DESKTOP_ENGINE = "com.dexxp.desktop.action.STOP_ENGINE"
        const val ACTION_RESCAN_DISPLAYS = "com.dexxp.desktop.action.RESCAN_DISPLAYS"

        fun start(context: Context) {
            val intent = Intent(context, DisplayRouterService::class.java).apply {
                action = ACTION_RESCAN_DISPLAYS
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DisplayRouterService::class.java).apply {
                action = ACTION_STOP_DESKTOP_ENGINE
            }
            context.startService(intent)
        }
    }
}
