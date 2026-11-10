package com.dexxp.desktop

import android.content.pm.ActivityInfo
import android.view.MotionEvent
import com.dexxp.desktop.bridge.CursorOverlayController
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.browser.DesktopWebActivity
import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.launcher.AppShortcutHelper
import com.dexxp.desktop.model.AppWindowLaunchPolicy
import com.dexxp.desktop.model.ClipboardItemKind
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.PointerState
import com.dexxp.desktop.model.ShellSurfaceMode
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.power.PhoneScreenPowerController
import com.dexxp.desktop.service.DisplayRouterService
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.system.SystemFlagsInspector
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import com.dexxp.desktop.ui.desktop.hasPendingNotificationForDockPackage
import com.dexxp.desktop.windowing.WindowSnappingController
import com.dexxp.desktop.windowing.WindowTitlebarOverlayController
import com.dexxp.desktop.workspace.VirtualWorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class DesktopDpiAndBoundsTest {

    @Test
    fun `computeTargetDpi calibrates 1080p external monitor to 160dpi at 100 percent`() {
        assertEquals(160, DesktopDpiScale.SCALE_100.computeTargetDpi(1920, 1080))
        assertEquals(200, DesktopDpiScale.SCALE_125.computeTargetDpi(1920, 1080))
        assertEquals(240, DesktopDpiScale.SCALE_150.computeTargetDpi(1920, 1080))
    }

    @Test
    fun `computeTargetDpi scales 1440p and 4K UHD monitors ergonomically`() {
        assertEquals(192, DesktopDpiScale.SCALE_100.computeTargetDpi(2560, 1440))
        assertEquals(260, DesktopDpiScale.SCALE_100.computeTargetDpi(3840, 2160))
    }

    @Test
    fun `system readiness score tracks all 6 freeform overlay and notification checks`() {
        val unconfigured = SystemReadinessStatus()
        assertEquals(0, unconfigured.readyScore)
        assertEquals(6, unconfigured.totalChecks)
        assertFalse(unconfigured.isFullyConfigured)

        val configured = SystemReadinessStatus(
            freeformSupportEnabled = true,
            forceResizableEnabled = true,
            desktopModeExternalEnabled = true,
            overlayPermissionGranted = true,
            accessibilityPointerEnabled = true,
            notificationListenerEnabled = true
        )
        assertEquals(6, configured.readyScore)
        assertTrue(configured.isFullyConfigured)
    }

    @Test
    fun `buildAdbSetupCommands includes freeform accessibility and notification listener flags`() {
        val script = SystemFlagsInspector.buildAdbSetupCommands("com.dexxp.desktop")
        assertTrue(script.contains("enable_freeform_support 1"))
        assertTrue(script.contains("force_resizable_activities 1"))
        assertTrue(script.contains("force_desktop_mode_on_external_displays 1"))
        assertTrue(script.contains("SYSTEM_ALERT_WINDOW allow"))
        assertTrue(script.contains("cmd notification allow_listener"))
    }

    @Test
    fun `edge and corner snap zone math detects halves maximize and 4 quadrants within 16dp threshold`() {
        assertEquals(16f, WindowSnappingController.EDGE_SNAP_TRIGGER_DP, 0.001f)
        fun evalZone(x: Float, y: Float, w: Int = 1920, h: Int = 1080, dpi: Int = 160): WindowSnapMode? =
            WindowSnappingController.evaluateSnapZoneStatic(x, y, w, h, dpi)

        assertEquals(WindowSnapMode.LEFT_HALF, evalZone(x = 16f, y = 500f))
        assertNull(evalZone(x = 17f, y = 500f))
        assertEquals(WindowSnapMode.RIGHT_HALF, evalZone(x = 1904f, y = 500f))
        assertNull(evalZone(x = 1903f, y = 500f))
        assertEquals(WindowSnapMode.MAXIMIZED, evalZone(x = 960f, y = 16f))
        assertNull(evalZone(x = 960f, y = 17f))
        assertEquals(WindowSnapMode.TOP_LEFT_QUADRANT, evalZone(x = 8f, y = 12f))
        assertEquals(WindowSnapMode.TOP_RIGHT_QUADRANT, evalZone(x = 1914f, y = 12f))
        assertEquals(WindowSnapMode.BOTTOM_LEFT_QUADRANT, evalZone(x = 6f, y = 980f))
        assertEquals(WindowSnapMode.BOTTOM_RIGHT_QUADRANT, evalZone(x = 1915f, y = 980f))
        assertNull(evalZone(x = 960f, y = 540f))
        assertNull(evalZone(x = 10f, y = 1060f)) // Inside bottom 56dp taskbar
    }

    @Test
    fun `virtual workspace wrap-around math cycles cleanly across 4 desktops`() {
        assertEquals(3, VirtualWorkspaceManager.computeWrappedWorkspaceIndex(current = 0, delta = -1))
        assertEquals(1, VirtualWorkspaceManager.computeWrappedWorkspaceIndex(current = 0, delta = 1))
        assertEquals(0, VirtualWorkspaceManager.computeWrappedWorkspaceIndex(current = 3, delta = 1))
        assertEquals(2, VirtualWorkspaceManager.computeWrappedWorkspaceIndex(current = 3, delta = -1))
    }

    @Test
    fun `clipboard text classifier distinguishes URLs from plain snippets`() {
        assertEquals(
            ClipboardItemKind.LINK,
            DesktopClipboardManager.classifyTextKind("https://web.whatsapp.com/")
        )
        assertEquals(
            ClipboardItemKind.TEXT,
            DesktopClipboardManager.classifyTextKind("Copied paragraph with https://example.com inside")
        )
    }

    @Test
    fun `command palette inline calculator evaluates arithmetic functions and rejects non-math`() {
        val vat = CommandPaletteController.evaluateMathExpression("450 * 1.17")
        assertNotNull(vat)
        assertEquals("526.5", vat?.formattedValue)

        val root = CommandPaletteController.evaluateMathExpression("sqrt(144)")
        assertNotNull(root)
        assertEquals("12", root?.formattedValue)

        val power = CommandPaletteController.evaluateMathExpression("(20 + 4) / 3 + 2^3")
        assertNotNull(power)
        assertEquals("16", power?.formattedValue)

        assertNull(CommandPaletteController.evaluateMathExpression("chrome"))
        assertNull(CommandPaletteController.evaluateMathExpression("-42"))
    }

    @Test
    fun `command palette 2-keystroke fuzzy scorer matches prefixes acronyms and subsequences`() {
        val chromePrefix = CommandPaletteController.scoreFuzzyMatch("ch", "Chrome", "com.android.chrome")
        val acronymMatch = CommandPaletteController.scoreFuzzyMatch("es", "Editing Suite", "Preset")
        val noMatch = CommandPaletteController.scoreFuzzyMatch("zx", "Chrome", "com.android.chrome")

        assertTrue(chromePrefix >= 850)
        assertTrue(acronymMatch >= 840)
        assertEquals(0, noMatch)
    }

    @Test
    fun `phone hub smart OTP parser extracts 4 to 8 digit codes and ignores plain dates`() {
        assertEquals(
            "482910",
            PhoneHubController.extractSmartOtpCode("Messages", "Your verification code is 482910. Do not share.")
        )
        assertEquals(
            "739201",
            PhoneHubController.extractSmartOtpCode("Google", "G-739201 is your Google verification code.")
        )
        assertEquals(
            "123456",
            PhoneHubController.extractSmartOtpCode("Bank", "Use login code 123-456 to continue.")
        )
        assertNull(
            PhoneHubController.extractSmartOtpCode("Calendar", "Team sync scheduled for 2026 in room 1042")
        )
    }

    @Test
    fun `terminal command normalizer bounds interactive top and ping invocations`() {
        assertEquals(
            "top -b -n 1 | head -n 24",
            DesktopTerminalLauncher.normalizeNonInteractiveCommand("top")
        )
        assertEquals(
            "ping -c 4 1.1.1.1",
            DesktopTerminalLauncher.normalizeNonInteractiveCommand("ping 1.1.1.1")
        )
        assertEquals(
            "ls -la",
            DesktopTerminalLauncher.normalizeNonInteractiveCommand("ls -la")
        )
    }

    @Test
    fun `portrait and landscape containers compute ergonomic aspect ratios with titlebar headroom`() {
        val portrait = WindowSnappingController.computeSnapIntBounds(
            snapMode = WindowSnapMode.PORTRAIT_CONTAINER,
            displayWidthPx = 1920,
            displayHeightPx = 1080,
            densityDpi = 160
        )
        // Portrait Window must remain narrower than tall (~9:19), preserve >= 34px top headroom
        // for the 28dp floating titlebar, and stay above the 56px taskbar (1024px usable height).
        assertTrue(portrait.width < portrait.height)
        assertTrue(portrait.top >= WindowSnappingController.FLOATING_TOP_HEADROOM_DP)
        assertTrue(portrait.bottom <= 1080 - AppLauncher.TASKBAR_HEIGHT_DP)
        val portraitAspect = portrait.width.toFloat() / portrait.height.toFloat()
        assertTrue(portraitAspect in 0.42f..0.56f)

        val landscape = WindowSnappingController.computeSnapIntBounds(
            snapMode = WindowSnapMode.LANDSCAPE_CONTAINER,
            displayWidthPx = 1920,
            displayHeightPx = 1080,
            densityDpi = 160
        )
        assertTrue(landscape.width > landscape.height)
        assertTrue(landscape.top >= WindowSnappingController.FLOATING_TOP_HEADROOM_DP)
        assertTrue(landscape.bottom <= 1080 - AppLauncher.TASKBAR_HEIGHT_DP)
        val landscapeAspect = landscape.width.toFloat() / landscape.height.toFloat()
        assertTrue(landscapeAspect in 1.50f..1.70f)

        val maximized = WindowSnappingController.computeSnapIntBounds(
            snapMode = WindowSnapMode.MAXIMIZED,
            displayWidthPx = 1920,
            displayHeightPx = 1080,
            densityDpi = 160
        )
        assertEquals(0, maximized.left)
        assertEquals(0, maximized.top)
        assertEquals(1920, maximized.right)
        assertEquals(1024, maximized.bottom)
        assertFalse(WindowSnapMode.MAXIMIZED.usesFreeformContainer)
        assertTrue(WindowSnapMode.PORTRAIT_CONTAINER.usesFreeformContainer)
        assertTrue(WindowSnapMode.LANDSCAPE_CONTAINER.usesFreeformContainer)
    }

    @Test
    fun `smart launch policy routes portrait-locked apps to portrait container and responsive apps to widescreen`() {
        assertEquals(
            WindowSnapMode.PORTRAIT_CONTAINER,
            AppLauncher.resolvePolicySnapMode(AppWindowLaunchPolicy.AUTO, isPortraitLocked = true)
        )
        assertEquals(
            WindowSnapMode.MAXIMIZED,
            AppLauncher.resolvePolicySnapMode(AppWindowLaunchPolicy.AUTO, isPortraitLocked = false)
        )
        assertEquals(
            WindowSnapMode.LANDSCAPE_CONTAINER,
            AppLauncher.resolvePolicySnapMode(AppWindowLaunchPolicy.LANDSCAPE_WINDOW, isPortraitLocked = true)
        )
        assertEquals(
            WindowSnapMode.PORTRAIT_CONTAINER,
            AppLauncher.resolvePolicySnapMode(AppWindowLaunchPolicy.PORTRAIT_WINDOW, isPortraitLocked = false)
        )
        assertEquals(
            WindowSnapMode.FREEFORM_CASCADED,
            AppLauncher.resolvePolicySnapMode(AppWindowLaunchPolicy.FREEFORM_WINDOW, isPortraitLocked = false)
        )
        assertEquals(
            WindowSnapMode.MAXIMIZED,
            AppLauncher.resolvePolicySnapMode(AppWindowLaunchPolicy.MAXIMIZED_WIDESCREEN, isPortraitLocked = true)
        )
    }

    @Test
    fun `orientation classifier detects portrait-locked activities accurately`() {
        assertTrue(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
        assertTrue(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT))
        assertTrue(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT))
        assertTrue(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT))

        assertFalse(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
        assertFalse(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
        assertFalse(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER))
        assertFalse(AppRepository.isPortraitLockedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR))
    }

    @Test
    fun `desktop web studio user agent client hints and JS spoof emulate 64-bit Windows 11 Chrome 130`() {
        val ua = DesktopWebActivity.DESKTOP_USER_AGENT
        assertTrue(ua.contains("Windows NT 10.0; Win64; x64"))
        assertTrue(ua.contains("Chrome/130.0.0.0"))
        assertFalse(ua.contains("Linux", ignoreCase = true))
        assertFalse(ua.contains("Android", ignoreCase = true))

        assertEquals("\"Windows\"", DesktopWebActivity.DESKTOP_CH_HEADERS["Sec-CH-UA-Platform"])
        assertEquals("?0", DesktopWebActivity.DESKTOP_CH_HEADERS["Sec-CH-UA-Mobile"])
        assertEquals("\"64\"", DesktopWebActivity.DESKTOP_CH_HEADERS["Sec-CH-UA-Bitness"])

        val js = DesktopWebActivity.PLATFORM_SPOOF_JS
        assertTrue(js.contains("'Win32'"))
        assertTrue(js.contains("maxTouchPoints"))
        assertTrue(js.contains("'Windows'"))
        assertTrue(js.contains("getHighEntropyValues"))

        assertEquals("dexxp.webapp.capcut_web", DesktopWebActivity.inferRecordKey("https://www.capcut.com/editor"))
        assertEquals("dexxp.webapp.whatsapp_web", DesktopWebActivity.inferRecordKey("https://web.whatsapp.com/"))
        assertEquals("dexxp.webapp.youtube_desktop", DesktopWebActivity.inferRecordKey("https://www.youtube.com/watch?v=1"))
        assertEquals(28, WindowTitlebarOverlayController.TITLEBAR_HEIGHT_DP)
        assertEquals(336, WindowTitlebarOverlayController.MAXIMIZED_PILL_WIDTH_DP)
    }

    @Test
    fun `bottom edge hover reveal triggers strictly within bottom 6 pixels`() {
        assertEquals(6, PointerFocusBridge.BOTTOM_EDGE_REVEAL_THRESHOLD_PX)
        assertFalse(PointerFocusBridge.isInBottomEdgeRevealZone(screenY = 1073f, displayHeightPx = 1080))
        assertTrue(PointerFocusBridge.isInBottomEdgeRevealZone(screenY = 1074f, displayHeightPx = 1080))
        assertTrue(PointerFocusBridge.isInBottomEdgeRevealZone(screenY = 1079f, displayHeightPx = 1080))
        assertFalse(PointerFocusBridge.isInBottomEdgeRevealZone(screenY = 0f, displayHeightPx = 0))
    }

    @Test
    fun `launch grace period protects cold app startup and expires cleanly for watchdog reconciliation`() {
        val t0 = 1_000_000L
        assertTrue(DesktopSessionRepository.isTimestampWithinGracePeriod(t0, nowEpochMs = t0))
        assertTrue(DesktopSessionRepository.isTimestampWithinGracePeriod(t0, nowEpochMs = t0 + 2499L))
        assertFalse(DesktopSessionRepository.isTimestampWithinGracePeriod(t0, nowEpochMs = t0 + 2500L))
        assertFalse(DesktopSessionRepository.isTimestampWithinGracePeriod(0L, nowEpochMs = t0))
    }

    @Test
    fun `watchdog requires two consecutive not-visible checks and resets counter when observed visible`() {
        val tracker = DesktopSessionRepository.WindowLivenessTracker()
        val pkg = "com.android.chrome"
        val launchTime = 10_000L
        tracker.onWindowLaunchedOrRestored(pkg, nowEpochMs = launchTime)

        val afterGrace = launchTime + DesktopSessionRepository.LAUNCH_GRACE_PERIOD_MS + 100L

        // 1st "not visible" check after grace period: increments miss count to 1, does NOT remove
        assertFalse(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = afterGrace))
        assertEquals(1, tracker.getConsecutiveMissCount(pkg))
        assertTrue(tracker.hasPendingMisses())

        // Window becomes visible before 2nd check: resets consecutive miss count back to 0
        assertFalse(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = true, nowEpochMs = afterGrace + 100L))
        assertEquals(0, tracker.getConsecutiveMissCount(pkg))
        assertFalse(tracker.hasPendingMisses())
        assertTrue(tracker.isConfirmedVisible(pkg))

        // Next single miss again stays at 1 (never removed on a single transient miss)
        assertFalse(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = afterGrace + 300L))
        assertEquals(1, tracker.getConsecutiveMissCount(pkg))

        // 2nd consecutive miss removes the window record and clears internal state
        assertTrue(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = afterGrace + 540L))
        assertEquals(0, tracker.getConsecutiveMissCount(pkg))
        assertFalse(tracker.isConfirmedVisible(pkg))
        assertFalse(tracker.hasPendingMisses())
    }

    @Test
    fun `launch grace period never removes or accumulates misses during slow cold start`() {
        val tracker = DesktopSessionRepository.WindowLivenessTracker()
        val pkg = "com.whatsapp"
        val launchTime = 50_000L
        tracker.onWindowLaunchedOrRestored(pkg, nowEpochMs = launchTime)

        // Repeated "not visible" checks throughout [launchTime, launchTime + 2500ms) never remove or count misses
        assertFalse(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = launchTime + 100L))
        assertFalse(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = launchTime + 1200L))
        assertFalse(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = launchTime + 2499L))
        assertEquals(0, tracker.getConsecutiveMissCount(pkg))

        // Even immediately after grace expires at +2500ms, it STILL requires 2 full consecutive misses
        val postGrace1 = launchTime + DesktopSessionRepository.LAUNCH_GRACE_PERIOD_MS
        assertFalse(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = postGrace1))
        assertEquals(1, tracker.getConsecutiveMissCount(pkg))
        assertTrue(tracker.recordVisibilityCheck(pkg, isCurrentlyVisible = false, nowEpochMs = postGrace1 + 250L))
    }

    @Test
    fun `fallback 5s watchdog runs only when external display and non-minimized windows exist`() {
        assertEquals(5_000L, DisplayRouterService.FALLBACK_WATCHDOG_INTERVAL_MS)
        assertFalse(DisplayRouterService.shouldRunFallbackWatchdog(hasActiveExternalDisplay = false, nonMinimizedWindowCount = 0))
        assertFalse(DisplayRouterService.shouldRunFallbackWatchdog(hasActiveExternalDisplay = false, nonMinimizedWindowCount = 2))
        assertFalse(DisplayRouterService.shouldRunFallbackWatchdog(hasActiveExternalDisplay = true, nonMinimizedWindowCount = 0))
        assertTrue(DisplayRouterService.shouldRunFallbackWatchdog(hasActiveExternalDisplay = true, nonMinimizedWindowCount = 1))
    }

    @Test
    fun `ensureShellRecovered state transitions guarantee taskbar and desktop never stay hidden with zero visible windows`() {
        // 1. Stuck docked state (showDesktop = false) with 0 visible windows -> recovers to full desktop + emits recovery event
        val recoveredFromStuck = DesktopSessionRepository.computeEnsureShellRecoveredTransition(
            wasShowDesktopActive = false,
            wasExternalAppVisible = true,
            hasVisibleWindows = false,
            isLaunching = false,
            forceShowDesktop = false
        )
        assertTrue(recoveredFromStuck.showDesktop)
        assertFalse(recoveredFromStuck.externalAppVisible)
        assertTrue(recoveredFromStuck.shouldDismissFlyouts)
        assertTrue(recoveredFromStuck.shouldEmitRecoveryEvent)

        // 2. Already on full desktop with 0 visible windows -> stays on desktop WITHOUT dismissing active Start Menu search
        val steadyOnDesktop = DesktopSessionRepository.computeEnsureShellRecoveredTransition(
            wasShowDesktopActive = true,
            wasExternalAppVisible = false,
            hasVisibleWindows = false,
            isLaunching = false,
            forceShowDesktop = false
        )
        assertTrue(steadyOnDesktop.showDesktop)
        assertFalse(steadyOnDesktop.externalAppVisible)
        assertFalse(steadyOnDesktop.shouldDismissFlyouts)
        assertFalse(steadyOnDesktop.shouldEmitRecoveryEvent)

        // 3. Visible window still running and forceShowDesktop == false -> preserves docked app mode
        val activeAppRunning = DesktopSessionRepository.computeEnsureShellRecoveredTransition(
            wasShowDesktopActive = false,
            wasExternalAppVisible = true,
            hasVisibleWindows = true,
            isLaunching = false,
            forceShowDesktop = false
        )
        assertFalse(activeAppRunning.showDesktop)
        assertTrue(activeAppRunning.externalAppVisible)
        assertFalse(activeAppRunning.shouldDismissFlyouts)
        assertFalse(activeAppRunning.shouldEmitRecoveryEvent)

        // 4. Explicit Show Desktop / Minimize All (forceShowDesktop = true) -> forces full desktop recovery
        val forcedShowDesktop = DesktopSessionRepository.computeEnsureShellRecoveredTransition(
            wasShowDesktopActive = false,
            wasExternalAppVisible = true,
            hasVisibleWindows = true,
            isLaunching = false,
            forceShowDesktop = true
        )
        assertTrue(forcedShowDesktop.showDesktop)
        assertFalse(forcedShowDesktop.externalAppVisible)
        assertTrue(forcedShowDesktop.shouldDismissFlyouts)
        assertTrue(forcedShowDesktop.shouldEmitRecoveryEvent)

        // 5. Guard against hiding desktop when zero visible windows exist
        assertFalse(
            DesktopSessionRepository.canExitShowDesktop(
                hasVisibleWindows = false,
                hasExternalAppVisible = false,
                isLaunching = false
            )
        )
        assertTrue(
            DesktopSessionRepository.canExitShowDesktop(
                hasVisibleWindows = true,
                hasExternalAppVisible = false,
                isLaunching = false
            )
        )
    }

    @Test
    fun `top-level cursor overlay sprite shows for virtual touchpad and hides for physical hardware mouse`() {
        assertEquals(28, CursorOverlayController.CURSOR_SIZE_DP)
        assertTrue(
            CursorOverlayController.shouldShowVirtualCursorSprite(
                PointerState(x = 640f, y = 360f, visible = true, isHardwarePointer = false)
            )
        )
        assertFalse(
            CursorOverlayController.shouldShowVirtualCursorSprite(
                PointerState(x = 640f, y = 360f, visible = true, isHardwarePointer = true)
            )
        )
        assertFalse(
            CursorOverlayController.shouldShowVirtualCursorSprite(
                PointerState(x = 640f, y = 360f, visible = false, isHardwarePointer = false)
            )
        )
    }

    @Test
    fun `backdrop hit-testing is disabled in docked taskbar mode so touchpad clicks reach running apps`() {
        assertFalse(
            PointerFocusBridge.shouldAllowBackdropHitTesting(
                shellSurfaceMode = ShellSurfaceMode.DOCKED_TASKBAR,
                isShowDesktopActive = false,
                hasUnminimizedWindows = true,
                hasExternalAppVisible = true
            )
        )
        assertTrue(
            PointerFocusBridge.shouldAllowBackdropHitTesting(
                shellSurfaceMode = ShellSurfaceMode.FULL_DESKTOP,
                isShowDesktopActive = true,
                hasUnminimizedWindows = false,
                hasExternalAppVisible = false
            )
        )
    }

    @Test
    fun `persisted per-app window bounds round-trip and validate against 56dp taskbar and top headroom`() {
        val chromeBounds = WindowSnappingController.IntBounds(120, 48, 1420, 960)
        val termuxBounds = WindowSnappingController.IntBounds(80, 60, 1080, 820)
        val undersized = WindowSnappingController.IntBounds(10, 40, 200, 200)

        val encoded = DesktopSessionRepository.encodePersistedWindowBounds(
            mapOf(
                "com.android.chrome" to chromeBounds,
                "com.termux" to termuxBounds,
                "com.tiny.invalid" to undersized
            )
        )
        assertEquals(2, encoded.size)

        val decoded = DesktopSessionRepository.decodePersistedWindowBounds(encoded)
        assertEquals(chromeBounds, decoded["com.android.chrome"])
        assertEquals(termuxBounds, decoded["com.termux"])
        assertNull(decoded["com.tiny.invalid"])

        // Fits cleanly within 1920x1080 @ 160dpi (56px taskbar -> usableH=1024, topHeadroom=34)
        assertEquals(
            chromeBounds,
            DesktopSessionRepository.fitPersistedBoundsToDisplay(
                saved = chromeBounds,
                displayWidthPx = 1920,
                displayHeightPx = 1080,
                taskbarHeightPx = 56,
                topHeadroomPx = 34
            )
        )

        // Overlaps bottom 56dp taskbar (bottom = 1040 > 1024) -> rejected so cascade fallback runs
        assertNull(
            DesktopSessionRepository.fitPersistedBoundsToDisplay(
                saved = WindowSnappingController.IntBounds(120, 48, 1200, 1040),
                displayWidthPx = 1920,
                displayHeightPx = 1080,
                taskbarHeightPx = 56,
                topHeadroomPx = 34
            )
        )

        // Intrudes into floating titlebar top headroom (top = 12 < 34) -> rejected
        assertNull(
            DesktopSessionRepository.fitPersistedBoundsToDisplay(
                saved = WindowSnappingController.IntBounds(120, 12, 1200, 900),
                displayWidthPx = 1920,
                displayHeightPx = 1080,
                taskbarHeightPx = 56,
                topHeadroomPx = 34
            )
        )

        // Exceeds narrower 1280x720 external monitor -> rejected
        assertNull(
            DesktopSessionRepository.fitPersistedBoundsToDisplay(
                saved = chromeBounds,
                displayWidthPx = 1280,
                displayHeightPx = 720,
                taskbarHeightPx = 56,
                topHeadroomPx = 34
            )
        )
    }

    @Test
    fun `taskbar notification badge matcher connects active notifications to native and web dock icons`() {
        assertEquals(3, AppShortcutHelper.MAX_SHORTCUTS_PER_APP)
        val pendingPkgs = setOf("com.android.chrome", "com.whatsapp", "com.google.android.gm")

        assertTrue(hasPendingNotificationForDockPackage("com.android.chrome", pendingPkgs))
        assertTrue(hasPendingNotificationForDockPackage("dexxp.webapp.whatsapp_web", pendingPkgs))
        assertTrue(hasPendingNotificationForDockPackage("dexxp.webapp.gmail_web", pendingPkgs))
        assertFalse(hasPendingNotificationForDockPackage("com.termux", pendingPkgs))
        assertFalse(hasPendingNotificationForDockPackage("com.android.chrome", emptySet()))
    }

    @Test
    fun `hardware mouse thermal guard engages after 15s phone touch inactivity and wakes on first touch`() {
        assertEquals(15_000L, PhoneScreenPowerController.HARDWARE_POINTER_IDLE_BLACKOUT_MS)

        // <= 15s since last phone touch -> stays awake
        assertFalse(
            PhoneScreenPowerController.shouldAutoActivateThermalBlackout(
                hasActiveExternalDisplay = true,
                isAlreadyBlackout = false,
                isHardwarePointerActive = true,
                isSoftKeyboardRelayOpen = false,
                manualToggleSuppressed = false,
                lastPhoneTouchElapsedMs = 10_000L,
                nowElapsedMs = 25_000L
            )
        )

        // > 15s since last phone touch while working with PC/hardware mouse -> activates AMOLED blackout
        assertTrue(
            PhoneScreenPowerController.shouldAutoActivateThermalBlackout(
                hasActiveExternalDisplay = true,
                isAlreadyBlackout = false,
                isHardwarePointerActive = true,
                isSoftKeyboardRelayOpen = false,
                manualToggleSuppressed = false,
                lastPhoneTouchElapsedMs = 10_000L,
                nowElapsedMs = 25_001L
            )
        )

        // Virtual touchpad mode (isHardwarePointerActive = false) -> never auto-blacks out
        assertFalse(
            PhoneScreenPowerController.shouldAutoActivateThermalBlackout(
                hasActiveExternalDisplay = true,
                isAlreadyBlackout = false,
                isHardwarePointerActive = false,
                isSoftKeyboardRelayOpen = false,
                manualToggleSuppressed = false,
                lastPhoneTouchElapsedMs = 10_000L,
                nowElapsedMs = 40_000L
            )
        )

        // First physical touch contact (ACTION_DOWN / ACTION_POINTER_DOWN) wakes screen immediately
        assertTrue(PhoneScreenPowerController.isFirstTouchContactAction(MotionEvent.ACTION_DOWN))
        assertTrue(PhoneScreenPowerController.isFirstTouchContactAction(MotionEvent.ACTION_POINTER_DOWN))
        assertFalse(PhoneScreenPowerController.isFirstTouchContactAction(MotionEvent.ACTION_MOVE))
        assertFalse(PhoneScreenPowerController.isFirstTouchContactAction(MotionEvent.ACTION_UP))
    }
}
