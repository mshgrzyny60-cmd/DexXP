package com.dexxp.desktop.model

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt

/**
 * Physical or logical transport mechanism backing an external [android.view.Display].
 */
enum class DisplayTransportType(val label: String) {
    HDMI_DP_ALT_MODE("USB-C DP Alt / HDMI"),
    WIRELESS_MIRACAST("Wireless Display"),
    VIRTUAL_OR_OVERLAY("Virtual / Simulated")
}

/**
 * User-selectable desktop density scale factors applied atop the baseline monitor DPI.
 */
enum class DesktopDpiScale(val multiplier: Float, val percentLabel: String) {
    SCALE_85(0.85f, "85%"),
    SCALE_100(1.00f, "100%"),
    SCALE_125(1.25f, "125%"),
    SCALE_150(1.50f, "150%");

    fun computeTargetDpi(widthPx: Int, heightPx: Int): Int {
        val longEdge = maxOf(widthPx, heightPx)
        val baseDesktopDpi = when {
            longEdge >= 3800 -> 260
            longEdge >= 2500 -> 192
            longEdge >= 1900 -> 160
            else -> 140
        }
        return (baseDesktopDpi * multiplier).roundToInt().coerceIn(120, 480)
    }
}

/**
 * Snapshot of an active external secondary display managed by [com.dexxp.desktop.service.DisplayRouterService].
 */
@Immutable
data class ExternalDisplayInfo(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Int,
    val nativeDensityDpi: Int,
    val scaledDensityDpi: Int,
    val transportType: DisplayTransportType,
    val isHdrSupported: Boolean
) {
    val resolutionSummary: String
        get() = "${widthPx}x${heightPx} @ ${refreshRateHz}Hz"
}

/**
 * Categorical taxonomy for desktop application organization in StartMenu.
 */
enum class AppCategory(val title: String) {
    ALL("All Apps"),
    PRODUCTIVITY("Productivity"),
    INTERNET_SOCIAL("Web & Social"),
    MEDIA("Media & Studio"),
    GAMES("Games"),
    SYSTEM_UTILITIES("System & Tools")
}

/**
 * Immutable metadata + cached [ImageBitmap] icon for an installed launchable application.
 */
@Immutable
data class DesktopAppItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val category: AppCategory,
    val iconBitmap: ImageBitmap?,
    val isSystemApp: Boolean
) {
    val componentName: ComponentName
        get() = ComponentName(packageName, activityName)

    val uniqueKey: String
        get() = "$packageName/$activityName"
}

/**
 * Window placement & tiling presets for Freeform Multi-Window launching and edge/corner snapping.
 */
enum class WindowSnapMode(val label: String) {
    FREEFORM_CASCADED("Freeform Window"),
    LEFT_HALF("Snap Left 50%"),
    RIGHT_HALF("Snap Right 50%"),
    MAXIMIZED("Maximize Work Area"),
    TOP_LEFT_QUADRANT("Top-Left 25%"),
    TOP_RIGHT_QUADRANT("Top-Right 25%"),
    BOTTOM_LEFT_QUADRANT("Bottom-Left 25%"),
    BOTTOM_RIGHT_QUADRANT("Bottom-Right 25%"),
    COMPACT_UTILITY("Compact Utility")
}

/**
 * Visual preview state emitted while dragging a window/pointer into an edge or corner snap zone.
 */
@Immutable
data class SnapPreviewState(
    val snapMode: WindowSnapMode,
    val previewBounds: Rect,
    val targetPackageName: String?
)

/**
 * Represents a launched or detected Freeform application window on the secondary display.
 */
@Immutable
data class RunningWindowRecord(
    val packageName: String,
    val activityName: String,
    val label: String,
    val bounds: Rect,
    val snapMode: WindowSnapMode,
    val isFocused: Boolean,
    val lastActiveEpochMs: Long,
    val displayId: Int,
    val isMinimized: Boolean = false
)

/**
 * Live external window geometry inspected via `DesktopPointerAccessibilityService.getWindowsOnAllDisplays()`.
 */
@Immutable
data class ExternalWindowFrame(
    val windowId: Int,
    val packageName: String,
    val title: String?,
    val bounds: Rect,
    val isFocused: Boolean,
    val isActive: Boolean
)

/**
 * Controls the WindowManager geometry and focus flags of [com.dexxp.desktop.presentation.DesktopWindowPresentation]
 * so Layer-23 Presentation chrome never occludes Layer-2 third-party Freeform Activity windows.
 */
enum class ShellSurfaceMode {
    FULL_DESKTOP,
    FLYOUT_ACTIVE,
    DOCKED_TASKBAR
}

/**
 * Real-time virtual pointer coordinates & button state on the secondary display.
 */
@Immutable
data class PointerState(
    val x: Float = 960f,
    val y: Float = 540f,
    val displayWidth: Int = 1920,
    val displayHeight: Int = 1080,
    val isPrimaryDown: Boolean = false,
    val isSecondaryDown: Boolean = false,
    val isDragging: Boolean = false,
    val visible: Boolean = false,
    val lastInteractionMs: Long = 0L
)

/**
 * Procedural M3 desktop wallpaper themes rendered on the secondary monitor.
 */
enum class WallpaperPreset(val title: String) {
    OBSIDIAN_AURORA("Obsidian Aurora"),
    SAPPHIRE_HORIZON("Sapphire Horizon"),
    EMERALD_MATRIX("Emerald Slate"),
    CARBON_MINIMAL("Carbon Studio")
}

/**
 * Active phone notification mirrored into the Desktop Notification Center.
 */
@Immutable
data class DesktopNotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val isClearable: Boolean,
    val contentIntent: PendingIntent?,
    val replyActionIntent: PendingIntent?,
    val remoteInputs: List<RemoteInput>,
    val replyLabel: String?,
    val category: String? = null
) {
    val canReplyInline: Boolean
        get() = replyActionIntent != null && remoteInputs.isNotEmpty()
}

/**
 * Audio output endpoint category for [com.dexxp.desktop.audio.AudioRouteManager].
 */
enum class AudioEndpointCategory {
    HDMI_DISPLAYPORT,
    PHONE_SPEAKER,
    BLUETOOTH,
    USB_OR_WIRED
}

/**
 * Selectable audio sink surfaced in the System Tray audio switcher.
 */
@Immutable
data class AudioOutputRoute(
    val deviceId: Int,
    val name: String,
    val subtitle: String,
    val category: AudioEndpointCategory,
    val androidDeviceType: Int,
    val isSelected: Boolean
)

/**
 * Desktop file item queried via MediaStore Downloads, SAF, or workspace FileProvider.
 */
@Immutable
data class DesktopFileItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isImage: Boolean
)

/**
 * State of the `Alt + Tab` M3 Window Switcher overlay.
 */
@Immutable
data class AltTabOverlayState(
    val isVisible: Boolean = false,
    val windows: List<RunningWindowRecord> = emptyList(),
    val selectedIndex: Int = 0
)

/**
 * Tracks whether an editable text field is currently focused on the external display
 * so the Phone Controller can automatically deploy the Soft Keyboard IME Relay.
 */
@Immutable
data class ExternalTextInputState(
    val isExternalFieldFocused: Boolean = false,
    val targetPackageName: String? = null,
    val currentTextHint: String = ""
)

/**
 * Content classification for `Win + V` Clipboard History items.
 */
enum class ClipboardItemKind(val badgeLabel: String) {
    TEXT("Text"),
    LINK("URL"),
    IMAGE_URI("Image")
}

/**
 * Immutable entry stored in the 20-item secure in-memory LRU cache of [com.dexxp.desktop.clipboard.DesktopClipboardManager].
 */
@Immutable
data class ClipboardHistoryEntry(
    val id: String,
    val kind: ClipboardItemKind,
    val previewText: String,
    val textValue: String?,
    val contentUri: Uri?,
    val mimeType: String,
    val copiedAtEpochMs: Long,
    val isPinned: Boolean = false
)

/**
 * Isolated virtual desktop state managed by [com.dexxp.desktop.workspace.VirtualWorkspaceManager].
 */
@Immutable
data class VirtualWorkspace(
    val index: Int,
    val name: String,
    val shortLabel: String,
    val wallpaper: WallpaperPreset,
    val windows: List<RunningWindowRecord> = emptyList()
) {
    val activeWindowCount: Int
        get() = windows.size
}

/**
 * Standalone desktop web application definition launched via [com.dexxp.desktop.browser.DesktopBrowserLauncher].
 */
@Immutable
data class DesktopWebAppDefinition(
    val id: String,
    val title: String,
    val subtitle: String,
    val desktopUrl: String,
    val accentColorHex: Long
)

/**
 * Non-root system flags & runtime permission state required for full AOSP Freeform Desktop Mode.
 */
@Immutable
data class SystemReadinessStatus(
    val freeformSupportEnabled: Boolean = false,
    val forceResizableEnabled: Boolean = false,
    val desktopModeExternalEnabled: Boolean = false,
    val nonResizableMultiWindowEnabled: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val accessibilityPointerEnabled: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val notificationPermissionGranted: Boolean = true
) {
    val readyScore: Int
        get() = listOf(
            freeformSupportEnabled,
            forceResizableEnabled,
            desktopModeExternalEnabled,
            overlayPermissionGranted,
            accessibilityPointerEnabled,
            notificationListenerEnabled
        ).count { it }

    val totalChecks: Int
        get() = 6

    val isFullyConfigured: Boolean
        get() = freeformSupportEnabled && forceResizableEnabled && overlayPermissionGranted
}

/**
 * Inline real-time mathematical evaluation output for [com.dexxp.desktop.command.CommandPaletteController].
 */
@Immutable
data class MathEvalResult(
    val expression: String,
    val formattedValue: String,
    val rawValue: Double
)

/**
 * Result classification surfaced inside the `Alt + Space` Universal Command Palette.
 */
enum class CommandResultKind(val badgeLabel: String) {
    CALCULATOR("Math"),
    APP("App"),
    LAYOUT_PRESET("Snap Group"),
    SYSTEM_ACTION("Command"),
    FILE("File"),
    WEB_SEARCH("Web")
}

/**
 * Ranked result entry rendered inside `CommandPaletteModal`.
 */
@Immutable
data class CommandPaletteItem(
    val id: String,
    val kind: CommandResultKind,
    val title: String,
    val subtitle: String,
    val score: Int,
    val appItem: DesktopAppItem? = null,
    val fileItem: DesktopFileItem? = null,
    val presetId: String? = null,
    val actionId: String? = null,
    val mathValue: String? = null,
    val webQuery: String? = null
)

/**
 * Active call lifecycle phase tracked by [com.dexxp.desktop.continuity.PhoneHubController].
 */
enum class CallPhase {
    RINGING,
    ACTIVE
}

/**
 * Non-intrusive desktop call banner state emitted during incoming or ongoing phone calls.
 */
@Immutable
data class IncomingCallState(
    val callerTitle: String,
    val callerSubtitle: String,
    val phase: CallPhase,
    val isSpeakerEnabled: Boolean = false,
    val answerIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
    val notificationKey: String? = null
)

/**
 * Parsed 4–8 digit SMS / messaging verification code surfaced for 1-click desktop copy & paste.
 */
@Immutable
data class DetectedOtpCode(
    val code: String,
    val senderTitle: String,
    val appName: String,
    val detectedAtMs: Long,
    val notificationKey: String
)

/**
 * Normalized `[0.0 .. 1.0]` work-area slot allowing saved Snap Group layouts to scale cleanly
 * across 1080p, 1440p, and 4K external monitors.
 */
@Immutable
data class SavedWindowSlot(
    val packageName: String,
    val label: String,
    val leftFraction: Float,
    val topFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
    val snapMode: WindowSnapMode = WindowSnapMode.FREEFORM_CASCADED
)

/**
 * Named multi-window arrangement managed by [com.dexxp.desktop.layout.WorkspaceLayoutPresetManager].
 */
@Immutable
data class WorkspaceLayoutPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val slots: List<SavedWindowSlot>,
    val isBuiltIn: Boolean
)

/**
 * Output stream classification for lines rendered in [com.dexxp.desktop.terminal.DesktopTerminalLauncher].
 */
enum class TerminalLineKind {
    PROMPT,
    STDOUT,
    STDERR,
    SYSTEM
}

/**
 * Immutable line entry inside the built-in Developer Shell Console ring buffer.
 */
@Immutable
data class TerminalTranscriptLine(
    val id: Long,
    val text: String,
    val kind: TerminalLineKind
)
