# DexXP Desktop Environment (Android 12 – Android 15)

Commercial-grade, ultra-lightweight AOSP Desktop Mode & Samsung DeX alternative for standard unrooted Android 12–15 (`API 31`–`API 35`) devices over USB-C DisplayPort Alternate Mode, HDMI, and Wireless Miracast displays.

## Core, Phase 2, Phase 3 & Phase 4 Subsystems

- **`CommandPaletteController` + `CommandPaletteModal` (`Alt + Space` / `Cmd + Space`)**: Universal Command Palette & Spotlight modal featuring 2-keystroke fuzzy app/file/preset/action search, a recursive-descent inline calculator (`450 * 1.17`, `sqrt(144)`, `2^10`) with 1-click Copy, and direct browser web search dispatch on `Enter`.
- **`MissionControlOverviewController` (`Super + Tab` / 3-Finger Swipe Up)**: Exposé / Task View bird's-eye overview scaling active Freeform windows proportionally into an interactive grid with click-to-foreground focus and drag-and-drop reassignment across Virtual Workspaces 1–4.
- **`PhoneHubController` (Mobile Continuity: Calls & Smart OTP)**: Android 12+ `TelephonyCallback` + `CATEGORY_CALL` inspector projecting a non-intrusive floating Incoming/Active Call Banner (Answer, Decline/End, Speaker toggle) and a Smart SMS OTP 4–8 digit parser (`G-123456`, `123-456`, contextual verification codes) with 1-click Copy & Auto-Paste chips in the Taskbar.
- **`WorkspaceLayoutPresetManager` (Saved Snap Groups & Layout Presets)**: Saves current multi-window arrangements as display-independent `[0..1]` monitor fractions alongside built-in presets (**Editing Suite** 70%/30% and **Research** 50%/50%) for 1-click exact-bounds multi-app launching.
- **`DesktopTerminalLauncher` (`Ctrl + Alt + T` / `Super + T`)**: Built-in draggable dark Material 3 Developer Shell Console executing non-blocking `/system/bin/sh` commands (`ps`, `top`, `ping`, `ls`, `df -h`, `uname -a`, `getprop`, `cd`, `pwd`, `clear`, `help`) with a 6-second watchdog and 1-click **Termux** Freeform integration.
- **`DisplayRouterService`**: Foreground `connectedDevice|specialUse` service listening to `DisplayManager`, selecting optimal EDID resolution/refresh-rate modes, applying isolated Desktop DPI `Configuration` overrides (`140–260 dpi`), and orchestrating secondary display lifecycles with instant cable-disconnect teardown.
- **`DesktopWindowPresentation` + `SecondaryDesktopActivity`**: Dual-layer windowing shell solving AOSP's `WindowManagerPolicy` Z-order separation between `TYPE_BASE_APPLICATION` (`Layer 2` Freeform activities) and `TYPE_PRESENTATION` (`Layer 23` desktop chrome).
- **`PhoneScreenPowerController` (AMOLED Blackout & Battery-Saver)**: Auto-detects physical USB/Bluetooth mice via `InputManager.registerInputDeviceListener`, drives `screenBrightness = 0.0f` (`#000000` zero-emission OLED HUD with pixel-shift burn-in protection + `FLAG_KEEP_SCREEN_ON`), and wakes the trackpad instantaneously on any single tap or swipe.
- **`DesktopClipboardManager` + `ClipboardHistoryFlyout` (`Win + V`)**: Secure 20-clip volatile LRU clipboard manager honoring Android 13+ `EXTRA_IS_SENSITIVE`, featuring 1-click auto-paste into active Freeform windows via `AccessibilityNodeInfo.ACTION_PASTE` or Soft IME Relay.
- **`WindowTitlebarOverlayController`**: Projects a `28dp` semi-transparent M3 floating titlebar (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`) directly above the active third-party Freeform window (`getWindowsOnAllDisplays()`) with Minimize, Maximize/Restore, and Close controls, auto-hiding when maximized.
- **`VirtualWorkspaceManager` (`Win + Ctrl + Arrows`)**: 4 isolated virtual desktops (`Work`, `Media`, `Dev & Web`, `Focus`), each retaining its own Freeform window stack and desktop wallpaper configuration.
- **`DesktopBrowserLauncher`**: Enforces desktop `Sec-CH-UA` headers and wide `>= 1280px` Freeform viewports, with 1-click borderless Web App launchers in the Start Menu for **WhatsApp Web**, **YouTube Desktop**, and **Gmail Web**.
- **`DesktopNotificationListenerService` + Notification & Quick Settings Flyouts**: Real-time phone notification mirroring, single/bulk dismiss, inline `RemoteInput` replies, Freeform `PendingIntent` launching, live battery/Wi-Fi tiles, external display brightness slider, and media volume control.
- **`DesktopKeyboardManager` (`Alt + Tab` & Soft IME Relay)**: Global hardware keyboard interceptor plus auto-deploying phone Soft IME Relay streaming keystrokes to active external text fields.
- **`WindowSnappingController`**: Edge and corner snapping engine (Left 50%, Right 50%, Top Maximize, and 4-corner 25% Quadrant tiling) with live semi-transparent snap preview overlays before drop.
- **`AudioRouteManager`**: Android 12+ real-time audio sink switcher (`AudioDeviceCallback`, `setPreferredDeviceForStrategy`, `setCommunicationDevice`) across HDMI/DisplayPort monitors, phone stereo speakers, and Bluetooth A2DP / LE Audio headphones.
- **`DesktopFileManager`**: Queries `Downloads` (`MediaStore`), custom `Desktop` folders via SAF (`DocumentsContract`), and workspace `FileProvider` files, enabling cross-window Compose Drag-and-Drop (`DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`) directly into active Freeform apps.

## Hardware Keyboard Shortcuts & Trackpad Gestures

| Shortcut / Gesture | Action |
| :--- | :--- |
| `Alt + Space` / `Super + Space` | Open Universal Command Palette & Spotlight (Apps, Files, Math, Web) |
| `Super + Tab` / `3-Finger Swipe Up` | Toggle Mission Control / Exposé Window Overview & Workspace Drag Bar |
| `Ctrl + Alt + T` / `Super + T` | Toggle Integrated Local Developer Terminal Console |
| `Alt + Tab` / `Alt + Shift + Tab` | Open M3 Window Switcher & cycle active Freeform apps |
| `Super / Meta / Windows` | Toggle Start Menu open / closed |
| `Super + V` (`Win + V`) | Open M3 Clipboard History & 1-click paste |
| `Super + Ctrl + Left / Right Arrow` | Switch to previous / next Virtual Workspace (Desktops 1–4) |
| `Super + D` | Minimize all Freeform windows / Restore Desktop |
| `Super + Left / Right Arrow` | Snap focused Freeform window to Left 50% / Right 50% |
| `Super + Up / Down Arrow` | Maximize focused window / Restore cascaded Freeform |
| `Escape` | Dismiss active Command Palette, Mission Control, Task Switcher, or flyouts |

## Footprint & Performance Optimizations

- **Zero Icon-Pack Bloat**: Custom Material 3 vector symbols (`DesktopIcons.kt`, ~4 KB) eliminate `material-icons-extended` (~28 MB saved).
- **Aggressive R8 Full Mode + 5-Pass Optimization**: `android.enableR8.fullMode=true`, `-optimizationpasses 5`, `-allowaccessmodification`, `-repackageclasses 'd'`, `-Xno-param/call/receiver-assertions`, `isShrinkResources=true`, `localeFilters = ["en", "iw", "he"]`, stripped `DebugProbesKt.bin` / `*.kotlin_builtins` / `.version` metadata, and stripped release `Log.v`/`Log.d`/`Log.i`.
- **Zero-Allocation 120–240Hz Input Path**: Primitive ring buffers (`FloatArray`/`LongArray`) and coalesced sub-frame historical motion dispatch in `VirtualTouchpadView`, pre-allocated `PointerProperties`/`PointerCoords` and `Rect` buffers in `PointerFocusBridge`, and hardware `RenderNode` `translationX/Y` cursor sprite (`CursorOverlayController`).
- **Leaf-Scoped Recomposition Pruning**: Clock (`TaskbarClockReadout`) and battery/Wi-Fi telemetry (`TaskbarBatteryReadout`, `QuickSettingsConnectivityTiles`, `BlackoutBatteryReadout`) are isolated in leaf `@Composable`s so periodic ticks never recompose the desktop grid, taskbar app shelf, or active modals.
- **44% Lower Icon Cache RAM & O(1) Lookups**: `72x72px` byte-bounded `LruCache` (4 MB cap) + `O(1)` package hash index in `AppRepository` and `DesktopTaskbar`.

## Non-Root One-Time ADB Setup

```bash
# 1. Enable AOSP Freeform Window Management & Resizable Activities
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb shell settings put global enable_non_resizable_multi_window 1

# 2. Activate Secondary Display TaskDisplayArea Desktop Mode
adb shell settings put global force_desktop_mode_on_external_displays 1

# 3. Grant Overlay, Notification Listener & Accessibility Pointer/Shortcut Bridge
adb shell appops set com.dexxp.desktop SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener com.dexxp.desktop/com.dexxp.desktop.notification.DesktopNotificationListenerService
adb shell settings put secure enabled_accessibility_services com.dexxp.desktop/com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 4. (Optional) Pre-grant Phone Hub Call Continuity permissions via ADB
adb shell pm grant com.dexxp.desktop android.permission.READ_PHONE_STATE
adb shell pm grant com.dexxp.desktop android.permission.ANSWER_PHONE_CALLS

# 5. Reboot once so WindowManagerService latches Freeform flags
adb reboot
```

## Build & Verify

```powershell
.\gradlew.bat assembleRelease assembleDebug testDebugUnitTest
```
