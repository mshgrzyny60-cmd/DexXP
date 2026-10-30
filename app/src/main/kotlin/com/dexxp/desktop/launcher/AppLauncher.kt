package com.dexxp.desktop.launcher

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.dexxp.desktop.SecondaryDesktopActivity
import com.dexxp.desktop.StandaloneDesktopActivity
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.windowing.WindowSnappingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Orchestrates Freeform Multi-Window application launches, snapping, quadrant tiling,
 * single-window minimize/maximize/close (`WindowTitlebarOverlayController`), and
 * `Super + D` desktop minimize/restore onto secondary external displays.
 */
class AppLauncher(
    context: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appRepository: AppRepository,
    private val snappingController: WindowSnappingController
) {
    private val appContext = context.applicationContext
    private val cascadeCounter = AtomicInteger(0)
    private val rememberedBounds = ConcurrentHashMap<String, Rect>()

    private val setLaunchWindowingModeMethod: Method? by lazy {
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    fun launchApp(
        app: DesktopAppItem,
        snapMode: WindowSnapMode = WindowSnapMode.MAXIMIZED,
        newInstance: Boolean = false,
        launchContext: Context? = null
    ): Boolean {
        val isTv = sessionRepository.environmentMode.value == DesktopEnvironmentMode.TV_LEANBACK
        if (isTv) {
            // Keep TV presentation visible with loading indicator during cold launch
            sessionRepository.setLaunchingPackage(app.packageName)
        }

        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY

        val baseIntent = appContext.packageManager.getLaunchIntentForPackage(app.packageName)
        val intent = if (baseIntent != null) {
            baseIntent.apply {
                component = app.componentName
                if (newInstance) {
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        } else {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = app.componentName
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                if (newInstance) {
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
            }
        }

        val bounds = Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        val optionsBundle = buildLaunchActivityOptions(
            targetDisplayId = targetDisplayId,
            isTvMode = isTv,
            launchBounds = bounds
        )

        val dispatcherContext = launchContext ?: appContext

        fun recordLaunch() {
            sessionRepository.setShowDesktop(false)
            sessionRepository.recordWindowLaunched(
                packageName = app.packageName,
                activityName = app.activityName,
                label = app.label,
                bounds = bounds,
                snapMode = WindowSnapMode.MAXIMIZED,
                displayId = targetDisplayId
            )
        }

        val launched = dispatchActivityIntent(
            context = dispatcherContext,
            intent = intent,
            optionsBundle = optionsBundle,
            targetDisplayId = targetDisplayId,
            targetPackage = app.packageName
        )

        if (launched) {
            recordLaunch()
            if (isTv) {
                // Yield the TV presentation to the app after a smooth 750ms grace period
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    delay(750)
                    sessionRepository.setLaunchingPackage(null)
                }
            }
        } else {
            if (isTv) {
                sessionRepository.setLaunchingPackage(null)
            }
        }
        return launched
    }

    fun launchCustomIntentInFreeform(
        intent: Intent,
        recordPackageKey: String,
        label: String,
        customBounds: Rect?,
        snapMode: WindowSnapMode = WindowSnapMode.MAXIMIZED,
        launchContext: Context? = null
    ): Boolean {
        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
        val effectiveBounds = customBounds ?: Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        val optionsBundle = buildLaunchActivityOptions(
            targetDisplayId = targetDisplayId,
            isTvMode = false,
            launchBounds = effectiveBounds
        )

        val dispatcher = launchContext ?: appContext
        val launched = dispatchActivityIntent(
            context = dispatcher,
            intent = intent,
            optionsBundle = optionsBundle,
            targetDisplayId = targetDisplayId,
            targetPackage = recordPackageKey
        )
        if (launched) {
            rememberedBounds[recordPackageKey] = Rect(effectiveBounds)
            sessionRepository.recordWindowLaunched(
                packageName = recordPackageKey,
                activityName = intent.component?.className.orEmpty(),
                label = label,
                bounds = effectiveBounds,
                snapMode = snapMode,
                displayId = targetDisplayId
            )
        }
        return launched
    }

    fun launchOrFocusPackage(
        packageName: String,
        snapMode: WindowSnapMode? = null,
        launchContext: Context? = null
    ): Boolean {
        val item = appRepository.findByPackage(packageName)
        if (item != null) {
            val effectiveSnap = snapMode
                ?: sessionRepository.runningWindows.value
                    .firstOrNull { it.packageName == packageName }
                    ?.snapMode
                ?: WindowSnapMode.MAXIMIZED
            return launchApp(item, effectiveSnap, newInstance = false, launchContext = launchContext)
        }

        val fallbackIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
        val effectiveSnap = snapMode ?: WindowSnapMode.MAXIMIZED
        val bounds = Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        val bundle = buildLaunchActivityOptions(
            targetDisplayId = targetDisplayId,
            launchBounds = bounds
        )
        val dispatcher = launchContext ?: appContext
        val launched = dispatchActivityIntent(
            context = dispatcher,
            intent = fallbackIntent,
            optionsBundle = bundle,
            targetDisplayId = targetDisplayId,
            targetPackage = packageName
        )
        if (launched) {
            sessionRepository.recordWindowLaunched(
                packageName = packageName,
                activityName = fallbackIntent.component?.className ?: "",
                label = packageName.substringAfterLast('.'),
                bounds = bounds,
                snapMode = effectiveSnap,
                displayId = targetDisplayId
            )
        }
        return launched
    }

    /**
     * Launches [packageName] into caller-specified exact [exactBounds] on the secondary display,
     * used by [com.dexxp.desktop.layout.WorkspaceLayoutPresetManager] for custom Snap Groups
     * (e.g., 70% / 30% Editing Suite or saved multi-window layouts).
     */
    fun launchPackageWithExactBounds(
        packageName: String,
        exactBounds: Rect,
        snapMode: WindowSnapMode = WindowSnapMode.FREEFORM_CASCADED,
        launchContext: Context? = null
    ): Boolean {
        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY

        val catalogItem = appRepository.findByPackage(packageName)
        val launchIntent = if (catalogItem != null) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = catalogItem.componentName
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
        } else {
            appContext.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            } ?: return false
        }

        val resolvedLabel = catalogItem?.label ?: packageName.substringAfterLast('.')
        val resolvedActivity = catalogItem?.activityName ?: launchIntent.component?.className.orEmpty()
        val safeBounds = Rect(exactBounds)

        val optionsBundle = buildFreeformActivityOptions(
            targetDisplayId = targetDisplayId,
            launchBounds = safeBounds
        )

        val dispatcher = launchContext ?: appContext
        val launched = dispatchActivityIntent(
            context = dispatcher,
            intent = launchIntent,
            optionsBundle = optionsBundle,
            targetDisplayId = targetDisplayId,
            targetPackage = packageName
        )
        if (launched) {
            rememberedBounds[packageName] = Rect(safeBounds)
            sessionRepository.recordWindowLaunched(
                packageName = packageName,
                activityName = resolvedActivity,
                label = resolvedLabel,
                bounds = safeBounds,
                snapMode = snapMode,
                displayId = targetDisplayId
            )
        }
        return launched
    }

    /**
     * Stashes a single Freeform window to the Taskbar (`WindowTitlebarOverlayController` Minimize
     * or Taskbar click on an already focused window).
     */
    fun toggleMinimizeWindow(packageName: String, launchContext: Context? = null) {
        val record = sessionRepository.runningWindows.value.firstOrNull {
            it.packageName == packageName
        } ?: return

        if (record.isMinimized) {
            sessionRepository.setWindowMinimized(packageName, minimized = false)
            launchOrFocusPackage(packageName, record.snapMode, launchContext)
            return
        }

        sessionRepository.setWindowMinimized(packageName, minimized = true)
        val remainingVisible = sessionRepository.runningWindows.value
            .filterNot { it.isMinimized || it.packageName == packageName }
            .sortedByDescending { it.lastActiveEpochMs }

        if (remainingVisible.isEmpty()) {
            sendSecondaryHomeToFront(launchContext)
            sessionRepository.setShowDesktop(true)
        } else {
            val nextTop = remainingVisible.first()
            sessionRepository.setWindowMinimized(nextTop.packageName, minimized = false)
            launchOrFocusPackage(nextTop.packageName, nextTop.snapMode, launchContext)
        }
    }

    /**
     * Toggles [packageName] between `MAXIMIZED` full work-area bounds and restored `FREEFORM_CASCADED` bounds.
     */
    fun toggleMaximizeOrRestoreWindow(packageName: String, launchContext: Context? = null): Boolean {
        val current = sessionRepository.runningWindows.value.firstOrNull {
            it.packageName == packageName
        }
        val nextSnap = if (current?.snapMode == WindowSnapMode.MAXIMIZED) {
            WindowSnapMode.FREEFORM_CASCADED
        } else {
            WindowSnapMode.MAXIMIZED
        }
        return launchOrFocusPackage(packageName, nextSnap, launchContext)
    }

    /**
     * Closes the target window by backgrounding behind the desktop shell and terminating processes.
     */
    fun closeWindow(packageName: String, launchContext: Context? = null) {
        DesktopPointerAccessibilityService.instance?.dispatchGlobalBackForClose()
        sessionRepository.removeWindowRecord(packageName)

        val remainingVisible = sessionRepository.runningWindows.value
            .filterNot { it.isMinimized || it.packageName == packageName }
            .sortedByDescending { it.lastActiveEpochMs }

        if (remainingVisible.isEmpty()) {
            sendSecondaryHomeToFront(launchContext)
            sessionRepository.setShowDesktop(true)
        } else {
            val nextTop = remainingVisible.first()
            sessionRepository.setWindowMinimized(nextTop.packageName, minimized = false)
            launchOrFocusPackage(nextTop.packageName, nextTop.snapMode, launchContext)
        }

        runCatching {
            val am = appContext.getSystemService(ActivityManager::class.java)
            am?.killBackgroundProcesses(packageName)
        }
    }

    fun sendSecondaryHomeToFront(launchContext: Context? = null) {
        val displayId = sessionRepository.activeDisplay.value?.displayId ?: Display.DEFAULT_DISPLAY
        if (displayId != Display.DEFAULT_DISPLAY) {
            val homeIntent = Intent(appContext, SecondaryDesktopActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            val optsBundle = buildLaunchActivityOptions(displayId)
            dispatchActivityIntent(launchContext ?: appContext, homeIntent, optsBundle, displayId, appContext.packageName)
        } else {
            val standaloneIntent = Intent(appContext, StandaloneDesktopActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            runCatching { (launchContext ?: appContext).startActivity(standaloneIntent) }
        }
    }

    fun restoreWorkspaceWindows(windows: List<RunningWindowRecord>, launchContext: Context? = null) {
        val orderedVisible = windows
            .filterNot { it.isMinimized }
            .sortedBy { it.lastActiveEpochMs }

        sendSecondaryHomeToFront(launchContext)
        for (win in orderedVisible) {
            if (win.snapMode == WindowSnapMode.FREEFORM_CASCADED && !win.bounds.isEmpty) {
                rememberedBounds[win.packageName] = Rect(win.bounds)
            }
            launchOrFocusPackage(win.packageName, win.snapMode, launchContext)
        }
    }

    fun snapFocusedWindow(snapMode: WindowSnapMode, launchContext: Context? = null): Boolean {
        val target = sessionRepository.focusedOrMostRecentWindow() ?: return false
        return launchOrFocusPackage(target.packageName, snapMode, launchContext)
    }

    fun toggleMinimizeAllOrRestore(launchContext: Context? = null) {
        val currentlyShowingDesktop = sessionRepository.isShowDesktopActive.value
        val windows = sessionRepository.runningWindows.value
        if (!currentlyShowingDesktop && windows.isNotEmpty()) {
            sessionRepository.setShowDesktop(true)
            sessionRepository.dismissFlyouts()
            sendSecondaryHomeToFront(launchContext)
        } else if (windows.isNotEmpty()) {
            sessionRepository.setShowDesktop(false)
            val top = sessionRepository.focusedOrMostRecentWindow(includeMinimized = true)
            if (top != null) {
                sessionRepository.setWindowMinimized(top.packageName, minimized = false)
                launchOrFocusPackage(top.packageName, top.snapMode, launchContext)
            }
        } else {
            sessionRepository.toggleShowDesktop()
        }
    }

    fun launchAppDetailsSettings(packageName: String, launchContext: Context? = null) {
        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val bounds = computeWindowBounds("com.android.settings", displayInfo, WindowSnapMode.COMPACT_UTILITY)
        val bundle = buildFreeformActivityOptions(
            targetDisplayId = targetDisplayId,
            launchBounds = bounds
        )
        runCatching { (launchContext ?: appContext).startActivity(intent, bundle) }
    }

    fun launchContentUriInFreeform(uri: Uri, mimeType: String, launchContext: Context? = null): Boolean {
        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val bounds = computeWindowBounds("viewer.$mimeType", displayInfo, WindowSnapMode.FREEFORM_CASCADED)
        val bundle = buildFreeformActivityOptions(
            targetDisplayId = targetDisplayId,
            launchBounds = bounds
        )
        return runCatching {
            (launchContext ?: appContext).startActivity(viewIntent, bundle)
            sessionRepository.setShowDesktop(false)
            true
        }.getOrDefault(false)
    }

    fun tileTopTwoWindowsSideBySide(launchContext: Context? = null) {
        val active = sessionRepository.runningWindows.value
            .filterNot { it.isMinimized }
            .sortedByDescending { it.lastActiveEpochMs }
            .take(2)
        if (active.isEmpty()) return
        launchOrFocusPackage(active[0].packageName, WindowSnapMode.LEFT_HALF, launchContext)
        if (active.size > 1) {
            launchOrFocusPackage(active[1].packageName, WindowSnapMode.RIGHT_HALF, launchContext)
        }
    }

    fun computeWindowBounds(
        packageName: String,
        displayInfo: ExternalDisplayInfo?,
        snapMode: WindowSnapMode
    ): Rect {
        val width = displayInfo?.widthPx ?: 1920
        val height = displayInfo?.heightPx ?: 1080
        val dpi = displayInfo?.scaledDensityDpi ?: 160

        if (snapMode != WindowSnapMode.FREEFORM_CASCADED) {
            return snappingController.computeSnapBounds(snapMode, width, height, dpi)
        }

        val density = dpi / 160f
        val taskbarPx = (TASKBAR_HEIGHT_DP * density).roundToInt()
        val marginPx = (WindowSnappingController.SNAP_MARGIN_DP * density).roundToInt()
        val titlebarAllowancePx = (32 * density).roundToInt()
        val usableHeight = (height - taskbarPx).coerceAtLeast(360)

        rememberedBounds[packageName]?.let { saved ->
            if (saved.width() in 320..width && saved.bottom <= usableHeight) {
                return Rect(saved)
            }
        }

        val winW = (width * 0.66f).roundToInt().coerceIn(640.coerceAtMost(width), width - marginPx * 2)
        val winH = (usableHeight * 0.74f).roundToInt().coerceIn(420.coerceAtMost(usableHeight), usableHeight - titlebarAllowancePx - marginPx)

        val step = cascadeCounter.getAndIncrement() % CASCADE_MODULO
        val stepOffsetPx = (CASCADE_STEP_DP * density).roundToInt()
        val baseLeft = ((width - winW) * 0.22f).roundToInt()
        val baseTop = ((usableHeight - winH) * 0.18f).roundToInt().coerceAtLeast(titlebarAllowancePx)

        val left = (baseLeft + step * stepOffsetPx).coerceIn(marginPx, (width - winW - marginPx).coerceAtLeast(marginPx))
        val top = (baseTop + step * stepOffsetPx).coerceIn(titlebarAllowancePx, (usableHeight - winH - marginPx).coerceAtLeast(titlebarAllowancePx))
        return Rect(left, top, left + winW, top + winH)
    }

    fun dispatchActivityIntent(
        context: Context,
        intent: Intent,
        optionsBundle: Bundle,
        targetDisplayId: Int,
        targetPackage: String? = null
    ): Boolean {
        // 1. Try launching through context directly
        val directSuccess = runCatching {
            context.startActivity(intent, optionsBundle)
            true
        }.getOrDefault(false)

        if (directSuccess) return true

        // 2. Try launching through DesktopPointerAccessibilityService (privileged background launcher)
        val a11y = DesktopPointerAccessibilityService.instance
        if (a11y != null) {
            val a11ySuccess = runCatching {
                a11y.startActivity(intent, optionsBundle)
                true
            }.getOrDefault(false)
            if (a11ySuccess) return true
        }

        // 3. Try launching through PendingIntent with MODE_BACKGROUND_ACTIVITY_START_ALLOWED (Android 14+ BAL compliant)
        val requestCode = (targetPackage?.hashCode() ?: intent.filterHashCode()) and 0x7FFFFFFF
        val pendingSuccess = runCatching {
            val pi = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                optionsBundle
            )
            pi.send(context, 0, null, null, null, null, optionsBundle)
            true
        }.getOrDefault(false)

        if (pendingSuccess) return true

        // 4. Fallback to basic display options without bounds
        val basicOptions = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(targetDisplayId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }.toBundle()

        return runCatching {
            context.startActivity(intent, basicOptions)
            true
        }.recoverCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun buildFreeformOptionsBundleForDisplay(
        targetDisplayId: Int,
        launchBounds: Rect? = null
    ): Bundle = buildLaunchActivityOptions(targetDisplayId, isTvMode = false, launchBounds = launchBounds)

    fun buildLaunchActivityOptions(
        targetDisplayId: Int,
        isTvMode: Boolean = false,
        launchBounds: Rect? = null
    ): Bundle {
        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(targetDisplayId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }

        // Always enforce FULLSCREEN windowing mode on secondary displays (both TV and Desktop/scrcpy modes).
        // On Xiaomi MIUI/HyperOS, passing WINDOWING_MODE_FREEFORM (5) triggers Xiaomi's proprietary
        // phone-shaped floating window container with vertical constraints and camera notches.
        // FULLSCREEN mode gives apps (Chrome, CapCut, YouTube, etc.) the full 16:9 widescreen canvas (>960dp width),
        // allowing them to switch automatically to their tablet/desktop UI with full tabs and timelines.
        val targetWindowingMode = WINDOWING_MODE_FULLSCREEN
        runCatching {
            setLaunchWindowingModeMethod?.invoke(options, targetWindowingMode)
        }

        return options.toBundle().apply {
            putInt(KEY_LAUNCH_WINDOWING_MODE, targetWindowingMode)
        }
    }

    private fun buildFreeformActivityOptions(
        targetDisplayId: Int,
        launchBounds: Rect?
    ): Bundle = buildLaunchActivityOptions(targetDisplayId, isTvMode = false, launchBounds = launchBounds)

    companion object {
        private const val TAG = "AppLauncher"
        const val TASKBAR_HEIGHT_DP = 56
        private const val CASCADE_STEP_DP = 32
        private const val CASCADE_MODULO = 6
        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_FREEFORM = 5
        private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"
    }
}
