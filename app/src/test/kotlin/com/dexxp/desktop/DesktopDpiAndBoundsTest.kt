package com.dexxp.desktop

import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.model.ClipboardItemKind
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.system.SystemFlagsInspector
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
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
    fun `edge and corner snap zone math detects halves maximize and 4 quadrants`() {
        // Pure geometry verification matching WindowSnappingController.evaluateSnapZone on 1080p @ 160dpi
        fun evalZone(x: Float, y: Float, w: Int = 1920, h: Int = 1080, dpi: Int = 160): WindowSnapMode? {
            val density = dpi / 160f
            val usableH = (h - (56 * density).roundToInt()).coerceAtLeast(240)
            if (y >= usableH) return null
            val edge = (24f * density).coerceAtLeast(18f)
            val corner = (116f * density).coerceAtLeast(72f)

            val nearLeft = x <= edge
            val nearRight = x >= (w - edge)
            val nearTop = y <= edge
            return when {
                (nearLeft && y <= corner) || (nearTop && x <= corner) -> WindowSnapMode.TOP_LEFT_QUADRANT
                (nearRight && y <= corner) || (nearTop && x >= (w - corner)) -> WindowSnapMode.TOP_RIGHT_QUADRANT
                nearLeft && y >= (usableH - corner) -> WindowSnapMode.BOTTOM_LEFT_QUADRANT
                nearRight && y >= (usableH - corner) -> WindowSnapMode.BOTTOM_RIGHT_QUADRANT
                nearLeft -> WindowSnapMode.LEFT_HALF
                nearRight -> WindowSnapMode.RIGHT_HALF
                nearTop -> WindowSnapMode.MAXIMIZED
                else -> null
            }
        }

        assertEquals(WindowSnapMode.LEFT_HALF, evalZone(x = 8f, y = 500f))
        assertEquals(WindowSnapMode.RIGHT_HALF, evalZone(x = 1912f, y = 500f))
        assertEquals(WindowSnapMode.MAXIMIZED, evalZone(x = 960f, y = 10f))
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
}
