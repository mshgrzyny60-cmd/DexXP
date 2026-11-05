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
import com.dexxp.desktop.browser.DesktopWebActivity
import com.dexxp.desktop.model.AppWindowLaunchPolicy
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

    fun launchApp(
        app: DesktopAppItem,
        snapMode: WindowSnapMode? = null,
        newInstance: Boolean = false,
        launchContext: Context? = null
    ): Boolean {
        if (app.packageName == "com.lemon.lvoverseas" || app.packageName == "com.capcut.editor") {
            if (snapMode == WindowSnapMode.LANDSCAPE_CONTAINER || snapMode == WindowSnapMode.PORTRAIT_CONTAINER) {
                return launchAppInternal(app, snapMode, newInstance, launchContext)
            }
            return launchCapCutDesktopStudio(snapMode ?: WindowSnapMode.MAXIMIZED, launchContext)
        }
        return launchAppInternal(app, snapMode, newInstance, launchContext)
    }

    fun resolveEffectiveSnapMode(
        app: DesktopAppItem?,
        packageName: String,
        requestedSnapMode: WindowSnapMode?
    ): WindowSnapMode {
        if (sessionRepository.environmentMode.value == DesktopEnvironmentMode.TV_LEANBACK) {
            return WindowSnapMode.MAXIMIZED
        }
        if (requestedSnapMode != null) {
            return requestedSnapMode
        }
        val policy = sessionRepository.getAppWindowPolicy(packageName)
        return resolvePolicySnapMode(
            policy = policy,
            isPortraitLocked = app?.isPortraitLocked == true
        )
    }

    fun launchCapCutDesktopStudio(
        snapMode: WindowSnapMode = WindowSnapMode.MAXIMIZED,
        launchContext: Context? = null
    ): Boolean {
        sessionRepository.removeWindowRecord("com.lemon.lvoverseas")
        sessionRepository.removeWindowRecord("com.capcut.editor")
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses("com.lemon.lvoverseas")
            am?.killBackgroundProcesses("com.capcut.editor")
        }
        val displayInfo = sessionRepository.activeDisplay.value
        val recordKey = "dexxp.webapp.capcut_web"
        val intent = Intent(appContext, DesktopWebActivity::class.java).apply {
            putExtra(DesktopWebActivity.EXTRA_URL, "https://www.capcut.com/editor")
            putExtra(DesktopWebActivity.EXTRA_TITLE, "CapCut Desktop Studio")
            putExtra(DesktopWebActivity.EXTRA_RECORD_KEY, recordKey)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val bounds = if (snapMode.usesFreeformContainer) {
            computeWindowBounds(recordKey, displayInfo, snapMode)
        } else {
            Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        }
        return launchCustomIntentInFreeform(
            intent = intent,
            recordPackageKey = recordKey,
            label = "CapCut Desktop Studio",
            customBounds = bounds,
            snapMode = snapMode,
            launchContext = launchContext
        )
    }

    /**
     * High-performance native fallback from CapCut Desktop Studio (`DesktopWebActivity`):
     * launches the installed native CapCut Android editor (`com.lemon.lvoverseas` / `com.capcut.editor`)
     * directly inside a `16:10` `WindowSnapMode.LANDSCAPE_CONTAINER` on the secondary display.
     */
    fun launchNativeCapCutInLandscapeContainer(launchContext: Context? = null): Boolean {
        val catalogItem = appRepository.findByPackage("com.lemon.lvoverseas")
            ?: appRepository.findByPackage("com.capcut.editor")
        if (catalogItem != null) {
            return launchAppInternal(
                app = catalogItem,
                snapMode = WindowSnapMode.LANDSCAPE_CONTAINER,
                newInstance = false,
                launchContext = launchContext
            )
        }

        val pm = appContext.packageManager
        val (targetPkg, launchIntent) = pm.getLaunchIntentForPackage("com.lemon.lvoverseas")
            ?.let { "com.lemon.lvoverseas" to it }
            ?: pm.getLaunchIntentForPackage("com.capcut.editor")
                ?.let { "com.capcut.editor" to it }
            ?: return false

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
        val bounds = computeWindowBounds(targetPkg, displayInfo, WindowSnapMode.LANDSCAPE_CONTAINER)
        val optionsBundle = buildLaunchActivityOptions(
            targetDisplayId = targetDisplayId,
            isTvMode = false,
            launchBounds = bounds,
            useFreeformWindowing = true
        )
        val launched = dispatchActivityIntent(
            context = launchContext ?: appContext,
            intent = launchIntent,
            optionsBundle = optionsBundle,
            targetDisplayId = targetDisplayId,
            targetPackage = targetPkg
        )
        if (launched) {
            sessionRepository.setShowDesktop(false)
            sessionRepository.recordWindowLaunched(
                packageName = targetPkg,
                activityName = launchIntent.component?.className.orEmpty(),
                label = "CapCut",
                bounds = bounds,
                snapMode = WindowSnapMode.LANDSCAPE_CONTAINER,
                displayId = targetDisplayId
            )
        }
        return launched
    }

    private fun launchAppInternal(
        app: DesktopAppItem,
        snapMode: WindowSnapMode? = null,
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
        val effectiveSnap = resolveEffectiveSnapMode(app, app.packageName, snapMode)
        val useFreeform = !isTv && effectiveSnap.usesFreeformContainer

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

        val bounds = if (useFreeform) {
            computeWindowBounds(app.packageName, displayInfo, effectiveSnap)
        } else {
            Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        }
        val optionsBundle = buildLaunchActivityOptions(
            targetDisplayId = targetDisplayId,
            isTvMode = isTv,
            launchBounds = if (useFreeform) bounds else null,
            useFreeformWindowing = useFreeform
        )

        val dispatcherContext = launchContext ?: appContext

        fun recordLaunch() {
            if (useFreeform && effectiveSnap == WindowSnapMode.FREEFORM_CASCADED) {
                rememberedBounds[app.packageName] = Rect(bounds)
            }
            sessionRepository.setShowDesktop(false)
            sessionRepository.recordWindowLaunched(
                packageName = app.packageName,
                activityName = app.activityName,
                label = app.label,
                bounds = bounds,
                snapMode = if (isTv) WindowSnapMode.MAXIMIZED else effectiveSnap,
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
        val useFreeform = snapMode.usesFreeformContainer
        val effectiveBounds = customBounds ?: if (useFreeform) {
            computeWindowBounds(recordPackageKey, displayInfo, snapMode)
        } else {
            Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        }
        val optionsBundle = buildLaunchActivityOptions(
            targetDisplayId = targetDisplayId,
            isTvMode = false,
            launchBounds = if (useFreeform) effectiveBounds else null,
            useFreeformWindowing = useFreeform
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

    private fun buildDesktopWebIntentForRecordKey(
        recordKey: String,
        fallbackLabel: String?
    ): Pair<Intent, String> {
        val (url, defaultTitle) = when (recordKey) {
            "dexxp.webapp.capcut_web" -> "https://www.capcut.com/editor" to "CapCut Desktop Studio"
            "dexxp.webapp.whatsapp_web" -> "https://web.whatsapp.com/" to "WhatsApp Web"
            "dexxp.webapp.youtube_desktop" -> "https://www.youtube.com" to "YouTube Desktop"
            "dexxp.webapp.gmail_web" -> "https://mail.google.com/mail/u/0/#inbox" to "Gmail Desktop"
            else -> "https://www.google.com" to "Desktop Web"
        }
        val title = fallbackLabel?.takeIf { it.isNotBlank() } ?: defaultTitle
        val intent = Intent(appContext, DesktopWebActivity::class.java).apply {
            putExtra(DesktopWebActivity.EXTRA_URL, url)
            putExtra(DesktopWebActivity.EXTRA_TITLE, title)
            putExtra(DesktopWebActivity.EXTRA_RECORD_KEY, recordKey)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        return intent to title
    }

    private fun launchWebRecordByKey(
        recordKey: String,
        fallbackLabel: String?,
        snapMode: WindowSnapMode,
        launchContext: Context?
    ): Boolean {
        val (intent, title) = buildDesktopWebIntentForRecordKey(recordKey, fallbackLabel)
        val displayInfo = sessionRepository.activeDisplay.value
        val bounds = if (snapMode.usesFreeformContainer) {
            computeWindowBounds(recordKey, displayInfo, snapMode)
        } else {
            Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        }
        return launchCustomIntentInFreeform(
            intent = intent,
            recordPackageKey = recordKey,
            label = title,
            customBounds = bounds,
            snapMode = snapMode,
            launchContext = launchContext
        )
    }

    fun launchOrFocusPackage(
        packageName: String,
        snapMode: WindowSnapMode? = null,
        launchContext: Context? = null
    ): Boolean {
        if (packageName.startsWith("dexxp.webapp.")) {
            val existing = sessionRepository.runningWindows.value.firstOrNull {
                it.packageName == packageName
            }
            val targetSnap = snapMode ?: existing?.snapMode ?: WindowSnapMode.MAXIMIZED
            if (existing != null && (snapMode == null || snapMode == existing.snapMode)) {
                val displayInfo = sessionRepository.activeDisplay.value
                val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
                val useFreeform = targetSnap.usesFreeformContainer
                val bounds = if (useFreeform) {
                    existing.bounds.takeIf { !it.isEmpty } ?: computeWindowBounds(packageName, displayInfo, targetSnap)
                } else {
                    Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
                }
                val options = buildLaunchActivityOptions(
                    targetDisplayId = targetDisplayId,
                    launchBounds = if (useFreeform) bounds else null,
                    useFreeformWindowing = useFreeform
                )
                if (DesktopWebActivity.bringToFrontByRecordKey(packageName, options)) {
                    sessionRepository.setShowDesktop(false)
                    sessionRepository.setWindowMinimized(packageName, minimized = false)
                    sessionRepository.markWindowFocused(packageName)
                    return true
                }
            }
            DesktopWebActivity.finishByRecordKey(packageName)
            return launchWebRecordByKey(packageName, existing?.label, targetSnap, launchContext)
        }

        if (packageName == "com.lemon.lvoverseas" || packageName == "com.capcut.editor") {
            val nativeRecord = sessionRepository.runningWindows.value.firstOrNull {
                it.packageName == packageName
            }
            if (nativeRecord == null &&
                snapMode != WindowSnapMode.LANDSCAPE_CONTAINER &&
                snapMode != WindowSnapMode.PORTRAIT_CONTAINER
            ) {
                return launchOrFocusPackage("dexxp.webapp.capcut_web", snapMode, launchContext)
            }
        }

        val item = appRepository.findByPackage(packageName)
        if (item != null) {
            val effectiveSnap = snapMode
                ?: sessionRepository.runningWindows.value
                    .firstOrNull { it.packageName == packageName }
                    ?.snapMode
            if ((packageName == "com.lemon.lvoverseas" || packageName == "com.capcut.editor") &&
                sessionRepository.runningWindows.value.any { it.packageName == packageName }
            ) {
                return launchAppInternal(item, effectiveSnap, newInstance = false, launchContext = launchContext)
            }
            return launchApp(item, effectiveSnap, newInstance = false, launchContext = launchContext)
        }

        val fallbackIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val displayInfo = sessionRepository.activeDisplay.value
        val targetDisplayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
        val effectiveSnap = snapMode ?: resolveEffectiveSnapMode(null, packageName, null)
        val useFreeform = effectiveSnap.usesFreeformContainer
        val bounds = if (useFreeform) {
            computeWindowBounds(packageName, displayInfo, effectiveSnap)
        } else {
            Rect(0, 0, displayInfo?.widthPx ?: 1920, displayInfo?.heightPx ?: 1080)
        }
        val bundle = buildLaunchActivityOptions(
            targetDisplayId = targetDisplayId,
            launchBounds = if (useFreeform) bounds else null,
            useFreeformWindowing = useFreeform
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
     * (e.g., 70% / 30% Editing Suite or saved multi-window layouts) and interactive resize/move.
     */
    fun launchPackageWithExactBounds(
        packageName: String,
        exactBounds: Rect,
        snapMode: WindowSnapMode = WindowSnapMode.FREEFORM_CASCADED,
        launchContext: Context? = null
    ): Boolean {
        if (packageName.startsWith("dexxp.webapp.")) {
            val existingLabel = sessionRepository.runningWindows.value
                .firstOrNull { it.packageName == packageName }
                ?.label
            DesktopWebActivity.finishByRecordKey(packageName)
            val (intent, title) = buildDesktopWebIntentForRecordKey(packageName, existingLabel)
            return launchCustomIntentInFreeform(
                intent = intent,
                recordPackageKey = packageName,
                label = title,
                customBounds = Rect(exactBounds),
                snapMode = snapMode,
                launchContext = launchContext
            )
        }

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
     * Explicitly minimizes [packageName] to the Taskbar and focuses the next visible window
     * (or brings the secondary desktop shell to the front when no visible windows remain).
     */
    fun minimizeWindow(packageName: String, launchContext: Context? = null) {
        sessionRepository.setWindowMinimized(packageName, minimized = true)
        if (packageName.startsWith("dexxp.webapp.")) {
            DesktopWebActivity.minimizeByRecordKey(packageName)
        }

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
     * Toggles single-window minimize/restore from Taskbar clicks or keyboard shortcuts.
     */
    fun toggleMinimizeWindow(packageName: String, launchContext: Context? = null) {
        val record = sessionRepository.runningWindows.value.firstOrNull {
            it.packageName == packageName
        } ?: return

        if (record.isMinimized || sessionRepository.isShowDesktopActive.value) {
            sessionRepository.setShowDesktop(false)
            sessionRepository.setWindowMinimized(packageName, minimized = false)
            launchOrFocusPackage(packageName, record.snapMode, launchContext)
            return
        }

        minimizeWindow(packageName, launchContext)
    }

    /**
     * Toggles [packageName] between `MAXIMIZED` full work-area bounds and its smart floating container.
     */
    fun toggleMaximizeOrRestoreWindow(packageName: String, launchContext: Context? = null): Boolean {
        val current = sessionRepository.runningWindows.value.firstOrNull {
            it.packageName == packageName
        }
        val nextSnap = if (current?.snapMode == WindowSnapMode.MAXIMIZED) {
            val app = appRepository.findByPackage(packageName)
            val policy = sessionRepository.getAppWindowPolicy(packageName)
            when {
                policy == AppWindowLaunchPolicy.PORTRAIT_WINDOW -> WindowSnapMode.PORTRAIT_CONTAINER
                policy == AppWindowLaunchPolicy.LANDSCAPE_WINDOW -> WindowSnapMode.LANDSCAPE_CONTAINER
                app?.isPortraitLocked == true -> WindowSnapMode.PORTRAIT_CONTAINER
                else -> WindowSnapMode.FREEFORM_CASCADED
            }
        } else {
            WindowSnapMode.MAXIMIZED
        }
        return launchOrFocusPackage(packageName, nextSnap, launchContext)
    }

    /**
     * Switches a floating window seamlessly between `9:19` Portrait Window and `16:10` Landscape Container.
     */
    fun toggleContainerOrientation(packageName: String, launchContext: Context? = null): Boolean {
        val current = sessionRepository.runningWindows.value.firstOrNull {
            it.packageName == packageName
        }
        val nextSnap = if (current?.snapMode == WindowSnapMode.PORTRAIT_CONTAINER) {
            WindowSnapMode.LANDSCAPE_CONTAINER
        } else {
            WindowSnapMode.PORTRAIT_CONTAINER
        }
        return launchOrFocusPackage(packageName, nextSnap, launchContext)
    }

    /**
     * Commits interactive drag-to-move or drag-to-resize bounds for a floating window on the secondary display.
     */
    fun repositionWindowBounds(
        packageName: String,
        newBounds: Rect,
        snapMode: WindowSnapMode = WindowSnapMode.FREEFORM_CASCADED,
        launchContext: Context? = null
    ): Boolean {
        val displayInfo = sessionRepository.activeDisplay.value
        val displayW = displayInfo?.widthPx ?: 1920
        val displayH = displayInfo?.heightPx ?: 1080
        val density = (displayInfo?.scaledDensityDpi ?: 160) / 160f
        val taskbarPx = (TASKBAR_HEIGHT_DP * density).roundToInt()
        val marginPx = (WindowSnappingController.SNAP_MARGIN_DP * density).roundToInt()
        val topHeadroomPx = maxOf(marginPx, (WindowSnappingController.FLOATING_TOP_HEADROOM_DP * density).roundToInt())
        val usableH = (displayH - taskbarPx).coerceAtLeast(360)

        val clampedW = newBounds.width().coerceIn(320, (displayW - marginPx * 2).coerceAtLeast(320))
        val clampedH = newBounds.height().coerceIn(280, (usableH - topHeadroomPx - marginPx).coerceAtLeast(280))
        val clampedLeft = newBounds.left.coerceIn(marginPx, (displayW - clampedW - marginPx).coerceAtLeast(marginPx))
        val clampedTop = newBounds.top.coerceIn(topHeadroomPx, (usableH - clampedH - marginPx).coerceAtLeast(topHeadroomPx))
        val clamped = Rect(clampedLeft, clampedTop, clampedLeft + clampedW, clampedTop + clampedH)

        rememberedBounds[packageName] = Rect(clamped)
        sessionRepository.updateWindowBoundsAndSnap(packageName, clamped, snapMode)
        return launchPackageWithExactBounds(packageName, clamped, snapMode, launchContext)
    }

    /**
     * Closes the target window cleanly, removing its task/record, shifting focus to the next visible
     * window (or returning to the desktop wallpaper), and reclaiming background app processes.
     */
    fun closeWindow(packageName: String, launchContext: Context? = null) {
        val wasFocused = !sessionRepository.isShowDesktopActive.value &&
            sessionRepository.focusedOrMostRecentWindow()?.packageName == packageName

        sessionRepository.removeWindowRecord(packageName)

        if (packageName.startsWith("dexxp.webapp.")) {
            DesktopWebActivity.finishByRecordKey(packageName)
        } else if (wasFocused) {
            DesktopPointerAccessibilityService.instance?.dispatchGlobalBackForClose()
        }

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

        if (!packageName.startsWith("dexxp.webapp.")) {
            val am = appContext.getSystemService(ActivityManager::class.java)
            runCatching { am?.killBackgroundProcesses(packageName) }
            CoroutineScope(Dispatchers.Default).launch {
                delay(180)
                runCatching { am?.killBackgroundProcesses(packageName) }
            }
        }
    }

    fun sendSecondaryHomeToFront(launchContext: Context? = null) {
        val displayId = sessionRepository.activeDisplay.value?.displayId ?: Display.DEFAULT_DISPLAY
        if (displayId != Display.DEFAULT_DISPLAY) {
            sessionRepository.setShowDesktop(true)
            sessionRepository.dismissFlyouts()
            val homeIntent = Intent(appContext, SecondaryDesktopActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            val options = buildLaunchActivityOptions(
                targetDisplayId = displayId,
                isTvMode = false,
                launchBounds = null,
                useFreeformWindowing = false
            )
            dispatchActivityIntent(
                context = launchContext ?: appContext,
                intent = homeIntent,
                optionsBundle = options,
                targetDisplayId = displayId,
                targetPackage = appContext.packageName
            )
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

        if (snapMode == WindowSnapMode.PORTRAIT_CONTAINER) {
            val base = snappingController.computeSnapBounds(snapMode, width, height, dpi)
            val otherPortraits = sessionRepository.runningWindows.value.count {
                !it.isMinimized && it.snapMode == WindowSnapMode.PORTRAIT_CONTAINER && it.packageName != packageName
            }
            if (otherPortraits > 0) {
                val density = dpi / 160f
                val marginPx = (WindowSnappingController.SNAP_MARGIN_DP * density).roundToInt()
                val winW = base.width()
                val shift = ((otherPortraits % 3) * (winW * 0.42f).roundToInt())
                val shiftedLeft = (base.left + shift).coerceIn(
                    marginPx,
                    (width - winW - marginPx).coerceAtLeast(marginPx)
                )
                return Rect(shiftedLeft, base.top, shiftedLeft + winW, base.bottom)
            }
            return base
        }

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
    ): Bundle = buildLaunchActivityOptions(
        targetDisplayId = targetDisplayId,
        isTvMode = false,
        launchBounds = launchBounds,
        useFreeformWindowing = launchBounds != null
    )

    fun buildLaunchActivityOptions(
        targetDisplayId: Int,
        isTvMode: Boolean = false,
        launchBounds: Rect? = null,
        useFreeformWindowing: Boolean = false
    ): Bundle {
        val effectiveFreeform = !isTvMode && useFreeformWindowing && launchBounds != null && !launchBounds.isEmpty
        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(targetDisplayId)
            if (effectiveFreeform) {
                setLaunchBounds(launchBounds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }
        return options.toBundle()
    }

    private fun buildFreeformActivityOptions(
        targetDisplayId: Int,
        launchBounds: Rect?
    ): Bundle = buildLaunchActivityOptions(
        targetDisplayId = targetDisplayId,
        isTvMode = false,
        launchBounds = launchBounds,
        useFreeformWindowing = launchBounds != null
    )

    companion object {
        private const val TAG = "AppLauncher"
        const val TASKBAR_HEIGHT_DP = 56
        private const val CASCADE_STEP_DP = 32
        private const val CASCADE_MODULO = 6

        fun resolvePolicySnapMode(
            policy: AppWindowLaunchPolicy,
            isPortraitLocked: Boolean
        ): WindowSnapMode = when (policy) {
            AppWindowLaunchPolicy.PORTRAIT_WINDOW -> WindowSnapMode.PORTRAIT_CONTAINER
            AppWindowLaunchPolicy.LANDSCAPE_WINDOW -> WindowSnapMode.LANDSCAPE_CONTAINER
            AppWindowLaunchPolicy.FREEFORM_WINDOW -> WindowSnapMode.FREEFORM_CASCADED
            AppWindowLaunchPolicy.MAXIMIZED_WIDESCREEN -> WindowSnapMode.MAXIMIZED
            AppWindowLaunchPolicy.AUTO -> if (isPortraitLocked) {
                WindowSnapMode.PORTRAIT_CONTAINER
            } else {
                WindowSnapMode.MAXIMIZED
            }
        }
    }
}
