package com.dexxp.desktop.state

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.DesktopNotificationItem
import com.dexxp.desktop.model.DisplayPerformanceProfile
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.ExternalTextInputState
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.ShellSurfaceMode
import com.dexxp.desktop.model.SnapPreviewState
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.TvNavigationAction
import com.dexxp.desktop.model.WallpaperPreset
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.system.SystemFlagsInspector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Single source of truth coordinating DisplayRouterService, DesktopWindowPresentation,
 * Notification Center, Clipboard History (`Win+V`), Virtual Workspaces, Window Snapping,
 * and PhoneControllerScreen.
 */
class DesktopSessionRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _activeDisplay = MutableStateFlow<ExternalDisplayInfo?>(null)
    val activeDisplay: StateFlow<ExternalDisplayInfo?> = _activeDisplay.asStateFlow()

    private val _dpiScale = MutableStateFlow(loadSavedDpiScale())
    val dpiScale: StateFlow<DesktopDpiScale> = _dpiScale.asStateFlow()

    private val _environmentMode = MutableStateFlow(loadSavedEnvironmentMode())
    val environmentMode: StateFlow<DesktopEnvironmentMode> = _environmentMode.asStateFlow()

    private val _performanceProfile = MutableStateFlow(loadSavedPerformanceProfile())
    val performanceProfile: StateFlow<DisplayPerformanceProfile> = _performanceProfile.asStateFlow()

    private val _wallpaperPreset = MutableStateFlow(loadSavedWallpaper())
    val wallpaperPreset: StateFlow<WallpaperPreset> = _wallpaperPreset.asStateFlow()

    private val _isStartMenuOpen = MutableStateFlow(false)
    val isStartMenuOpen: StateFlow<Boolean> = _isStartMenuOpen.asStateFlow()

    private val _isSystemTrayOpen = MutableStateFlow(false)
    val isSystemTrayOpen: StateFlow<Boolean> = _isSystemTrayOpen.asStateFlow()

    private val _isNotificationCenterOpen = MutableStateFlow(false)
    val isNotificationCenterOpen: StateFlow<Boolean> = _isNotificationCenterOpen.asStateFlow()

    private val _isClipboardHistoryOpen = MutableStateFlow(false)
    val isClipboardHistoryOpen: StateFlow<Boolean> = _isClipboardHistoryOpen.asStateFlow()

    private val _isWorkspaceSwitcherOpen = MutableStateFlow(false)
    val isWorkspaceSwitcherOpen: StateFlow<Boolean> = _isWorkspaceSwitcherOpen.asStateFlow()

    private val _isCommandPaletteOpen = MutableStateFlow(false)
    val isCommandPaletteOpen: StateFlow<Boolean> = _isCommandPaletteOpen.asStateFlow()

    private val _isMissionControlOpen = MutableStateFlow(false)
    val isMissionControlOpen: StateFlow<Boolean> = _isMissionControlOpen.asStateFlow()

    private val _isTerminalWindowOpen = MutableStateFlow(false)
    val isTerminalWindowOpen: StateFlow<Boolean> = _isTerminalWindowOpen.asStateFlow()

    private val _tvNavigationEvents = MutableSharedFlow<TvNavigationAction>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tvNavigationEvents: SharedFlow<TvNavigationAction> = _tvNavigationEvents.asSharedFlow()

    fun sendTvNavigation(action: TvNavigationAction) {
        _tvNavigationEvents.tryEmit(action)
    }

    private val _isShowDesktopActive = MutableStateFlow(true)
    val isShowDesktopActive: StateFlow<Boolean> = _isShowDesktopActive.asStateFlow()

    private val _isSearchFieldFocused = MutableStateFlow(false)
    val isSearchFieldFocused: StateFlow<Boolean> = _isSearchFieldFocused.asStateFlow()

    private val _isNotificationReplyFocused = MutableStateFlow(false)
    val isNotificationReplyFocused: StateFlow<Boolean> = _isNotificationReplyFocused.asStateFlow()

    private val _isCommandPaletteInputFocused = MutableStateFlow(false)
    val isCommandPaletteInputFocused: StateFlow<Boolean> = _isCommandPaletteInputFocused.asStateFlow()

    private val _isTerminalInputFocused = MutableStateFlow(false)
    val isTerminalInputFocused: StateFlow<Boolean> = _isTerminalInputFocused.asStateFlow()

    val needsShellKeyboardFocus: StateFlow<Boolean> = combine(
        _isSearchFieldFocused,
        _isNotificationReplyFocused,
        _isCommandPaletteInputFocused,
        _isTerminalInputFocused
    ) { searchFocused, replyFocused, paletteFocused, terminalFocused ->
        searchFocused || replyFocused || paletteFocused || terminalFocused
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _runningWindows = MutableStateFlow<List<RunningWindowRecord>>(emptyList())
    val runningWindows: StateFlow<List<RunningWindowRecord>> = _runningWindows.asStateFlow()

    private val _externalAppVisibleOnDisplay = MutableStateFlow(false)
    val externalAppVisibleOnDisplay: StateFlow<Boolean> = _externalAppVisibleOnDisplay.asStateFlow()

    private val _activeNotifications = MutableStateFlow<List<DesktopNotificationItem>>(emptyList())
    val activeNotifications: StateFlow<List<DesktopNotificationItem>> = _activeNotifications.asStateFlow()

    private val _snapPreview = MutableStateFlow<SnapPreviewState?>(null)
    val snapPreview: StateFlow<SnapPreviewState?> = _snapPreview.asStateFlow()

    private val _externalBrightnessFraction = MutableStateFlow(
        prefs.getFloat(KEY_EXTERNAL_BRIGHTNESS, 1.0f).coerceIn(0.20f, 1.0f)
    )
    val externalBrightnessFraction: StateFlow<Float> = _externalBrightnessFraction.asStateFlow()

    private val _externalTextInputState = MutableStateFlow(ExternalTextInputState())
    val externalTextInputState: StateFlow<ExternalTextInputState> = _externalTextInputState.asStateFlow()

    private val _manualKeyboardRelayOpen = MutableStateFlow(false)
    val manualKeyboardRelayOpen: StateFlow<Boolean> = _manualKeyboardRelayOpen.asStateFlow()

    private val _customDesktopTreeUri = MutableStateFlow(
        prefs.getString(KEY_CUSTOM_DESKTOP_URI, null)?.let(Uri::parse)
    )
    val customDesktopTreeUri: StateFlow<Uri?> = _customDesktopTreeUri.asStateFlow()

    private val _pinnedPackages = MutableStateFlow(loadStringSet(KEY_PINNED_PACKAGES, DEFAULT_PINNED))
    val pinnedPackages: StateFlow<Set<String>> = _pinnedPackages.asStateFlow()

    private val _desktopShortcutPackages = MutableStateFlow(
        loadStringSet(KEY_DESKTOP_SHORTCUTS, DEFAULT_DESKTOP_SHORTCUTS)
    )
    val desktopShortcutPackages: StateFlow<Set<String>> = _desktopShortcutPackages.asStateFlow()

    private val _systemReadiness = MutableStateFlow(SystemFlagsInspector.inspect(appContext))
    val systemReadiness: StateFlow<SystemReadinessStatus> = _systemReadiness.asStateFlow()

    private val _touchpadSensitivity = MutableStateFlow(
        prefs.getFloat(KEY_TOUCHPAD_SENSITIVITY, 1.35f)
    )
    val touchpadSensitivity: StateFlow<Float> = _touchpadSensitivity.asStateFlow()

    val anyFlyoutOpen: StateFlow<Boolean> = combine(
        listOf(
            _isStartMenuOpen,
            _isSystemTrayOpen,
            _isNotificationCenterOpen,
            _isClipboardHistoryOpen,
            _isWorkspaceSwitcherOpen,
            _isCommandPaletteOpen,
            _isMissionControlOpen,
            _isTerminalWindowOpen
        )
    ) { states ->
        states.any { it }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _launchingPackage = MutableStateFlow<String?>(null)
    val launchingPackage: StateFlow<String?> = _launchingPackage.asStateFlow()

    fun setLaunchingPackage(packageName: String?) {
        _launchingPackage.value = packageName
    }

    val shellSurfaceMode: StateFlow<ShellSurfaceMode> = combine(
        anyFlyoutOpen,
        _isShowDesktopActive,
        _runningWindows,
        _externalAppVisibleOnDisplay,
        _launchingPackage
    ) { flyoutOpen, showDesktop, windows, externalVisible, launching ->
        val hasUnminimizedWindows = windows.any { !it.isMinimized }
        when {
            flyoutOpen -> ShellSurfaceMode.FLYOUT_ACTIVE
            launching != null -> ShellSurfaceMode.FULL_DESKTOP
            !showDesktop && (hasUnminimizedWindows || externalVisible) -> ShellSurfaceMode.DOCKED_TASKBAR
            else -> ShellSurfaceMode.FULL_DESKTOP
        }
    }.stateIn(scope, SharingStarted.Eagerly, ShellSurfaceMode.FULL_DESKTOP)

    fun refreshSystemReadiness() {
        _systemReadiness.value = SystemFlagsInspector.inspect(appContext)
    }

    fun updateActiveDisplay(info: ExternalDisplayInfo?) {
        _activeDisplay.value = info
        if (info == null) {
            dismissFlyouts()
            _snapPreview.value = null
            _externalAppVisibleOnDisplay.value = false
        }
    }

    fun setDpiScale(scale: DesktopDpiScale) {
        _dpiScale.value = scale
        prefs.edit().putString(KEY_DPI_SCALE, scale.name).apply()
    }

    fun setWallpaperPreset(preset: WallpaperPreset) {
        _wallpaperPreset.value = preset
        prefs.edit().putString(KEY_WALLPAPER, preset.name).apply()
    }

    fun setExternalBrightness(fraction: Float) {
        val clamped = fraction.coerceIn(0.20f, 1.0f)
        _externalBrightnessFraction.value = clamped
        prefs.edit().putFloat(KEY_EXTERNAL_BRIGHTNESS, clamped).apply()
    }

    fun setTouchpadSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.5f, 3.0f)
        _touchpadSensitivity.value = clamped
        prefs.edit().putFloat(KEY_TOUCHPAD_SENSITIVITY, clamped).apply()
    }

    fun setCustomDesktopTreeUri(uri: Uri?) {
        _customDesktopTreeUri.value = uri
        prefs.edit().apply {
            if (uri != null) putString(KEY_CUSTOM_DESKTOP_URI, uri.toString())
            else remove(KEY_CUSTOM_DESKTOP_URI)
        }.apply()
    }

    fun toggleStartMenu() {
        val next = !_isStartMenuOpen.value
        dismissFlyouts()
        _isStartMenuOpen.value = next
    }

    fun setStartMenuOpen(open: Boolean) {
        if (open) {
            dismissFlyouts()
            _isStartMenuOpen.value = true
        } else {
            _isStartMenuOpen.value = false
            _isSearchFieldFocused.value = false
        }
    }

    fun toggleSystemTray() {
        val next = !_isSystemTrayOpen.value
        dismissFlyouts()
        _isSystemTrayOpen.value = next
    }

    fun toggleNotificationCenter() {
        val next = !_isNotificationCenterOpen.value
        dismissFlyouts()
        _isNotificationCenterOpen.value = next
    }

    fun toggleClipboardHistory() {
        val next = !_isClipboardHistoryOpen.value
        dismissFlyouts()
        _isClipboardHistoryOpen.value = next
    }

    fun setClipboardHistoryOpen(open: Boolean) {
        if (open) {
            dismissFlyouts()
            _isClipboardHistoryOpen.value = true
        } else {
            _isClipboardHistoryOpen.value = false
        }
    }

    fun toggleWorkspaceSwitcher() {
        val next = !_isWorkspaceSwitcherOpen.value
        dismissFlyouts()
        _isWorkspaceSwitcherOpen.value = next
    }

    fun toggleCommandPalette() {
        val next = !_isCommandPaletteOpen.value
        dismissFlyouts()
        _isCommandPaletteOpen.value = next
        _isCommandPaletteInputFocused.value = next
    }

    fun setCommandPaletteOpen(open: Boolean) {
        if (open) {
            dismissFlyouts()
            _isCommandPaletteOpen.value = true
            _isCommandPaletteInputFocused.value = true
        } else {
            _isCommandPaletteOpen.value = false
            _isCommandPaletteInputFocused.value = false
        }
    }

    fun toggleMissionControl() {
        val next = !_isMissionControlOpen.value
        dismissFlyouts()
        _isMissionControlOpen.value = next
    }

    fun setMissionControlOpen(open: Boolean) {
        if (open) {
            dismissFlyouts()
            _isMissionControlOpen.value = true
        } else {
            _isMissionControlOpen.value = false
        }
    }

    fun toggleTerminalWindow() {
        val next = !_isTerminalWindowOpen.value
        if (next) {
            dismissTransientFlyoutsOnly()
            _isTerminalWindowOpen.value = true
            _isTerminalInputFocused.value = true
        } else {
            _isTerminalWindowOpen.value = false
            _isTerminalInputFocused.value = false
        }
    }

    fun setTerminalWindowOpen(open: Boolean) {
        if (open) {
            dismissTransientFlyoutsOnly()
            _isTerminalWindowOpen.value = true
            _isTerminalInputFocused.value = true
        } else {
            _isTerminalWindowOpen.value = false
            _isTerminalInputFocused.value = false
        }
    }

    private fun dismissTransientFlyoutsOnly() {
        _isStartMenuOpen.value = false
        _isSystemTrayOpen.value = false
        _isNotificationCenterOpen.value = false
        _isClipboardHistoryOpen.value = false
        _isWorkspaceSwitcherOpen.value = false
        _isCommandPaletteOpen.value = false
        _isMissionControlOpen.value = false
        _isSearchFieldFocused.value = false
        _isNotificationReplyFocused.value = false
        _isCommandPaletteInputFocused.value = false
    }

    fun dismissFlyouts() {
        dismissTransientFlyoutsOnly()
        _isTerminalWindowOpen.value = false
        _isTerminalInputFocused.value = false
    }

    fun setSearchFieldFocused(focused: Boolean) {
        _isSearchFieldFocused.value = focused
    }

    fun setNotificationReplyFocused(focused: Boolean) {
        _isNotificationReplyFocused.value = focused
    }

    fun setCommandPaletteInputFocused(focused: Boolean) {
        _isCommandPaletteInputFocused.value = focused
    }

    fun setTerminalInputFocused(focused: Boolean) {
        _isTerminalInputFocused.value = focused
    }

    fun setSnapPreview(preview: SnapPreviewState?) {
        _snapPreview.value = preview
    }

    fun updateActiveNotifications(items: List<DesktopNotificationItem>) {
        _activeNotifications.value = items
    }

    fun updateExternalTextInputState(state: ExternalTextInputState) {
        _externalTextInputState.value = state
    }

    fun requestSoftKeyboardRelay(open: Boolean) {
        _manualKeyboardRelayOpen.value = open
    }

    fun toggleSoftKeyboardRelay() {
        _manualKeyboardRelayOpen.value = !_manualKeyboardRelayOpen.value
    }

    fun toggleShowDesktop() {
        val next = !_isShowDesktopActive.value
        _isShowDesktopActive.value = next
        dismissFlyouts()
    }

    fun setShowDesktop(show: Boolean) {
        _isShowDesktopActive.value = show
    }

    fun minimizeAllWindows() {
        _runningWindows.update { list ->
            list.map { it.copy(isMinimized = true, isFocused = false) }
        }
        _isShowDesktopActive.value = true
        _externalAppVisibleOnDisplay.value = false
        _launchingPackage.value = null
    }

    fun recordWindowLaunched(
        packageName: String,
        activityName: String,
        label: String,
        bounds: Rect,
        snapMode: WindowSnapMode,
        displayId: Int
    ) {
        val now = System.currentTimeMillis()
        _runningWindows.update { existing ->
            val updated = existing
                .filterNot { it.packageName == packageName }
                .map { it.copy(isFocused = false) }
            updated + RunningWindowRecord(
                packageName = packageName,
                activityName = activityName,
                label = label,
                bounds = Rect(bounds),
                snapMode = snapMode,
                isFocused = true,
                lastActiveEpochMs = now,
                displayId = displayId,
                isMinimized = false
            )
        }
        _externalAppVisibleOnDisplay.value = true
        _isShowDesktopActive.value = false
        dismissFlyouts()
    }

    fun setWindowMinimized(packageName: String, minimized: Boolean) {
        _runningWindows.update { list ->
            val next = list.map { rec ->
                if (rec.packageName == packageName) {
                    rec.copy(
                        isMinimized = minimized,
                        isFocused = if (minimized) false else true,
                        lastActiveEpochMs = if (!minimized) System.currentTimeMillis() else rec.lastActiveEpochMs
                    )
                } else if (!minimized) {
                    rec.copy(isFocused = false)
                } else {
                    rec
                }
            }
            val anyVisible = next.any { !it.isMinimized }
            _externalAppVisibleOnDisplay.value = anyVisible
            if (!anyVisible) {
                _isShowDesktopActive.value = true
            }
            next
        }
    }

    fun setRunningWindowsSnapshot(windows: List<RunningWindowRecord>) {
        _runningWindows.value = windows
        val hasVisible = windows.any { !it.isMinimized }
        _externalAppVisibleOnDisplay.value = hasVisible
        _isShowDesktopActive.value = !hasVisible
    }

    fun markWindowFocused(packageName: String?) {
        if (_isShowDesktopActive.value) {
            // NEVER auto-unminimize or steal focus from background apps when Desktop or TV home is active!
            return
        }
        _runningWindows.update { list ->
            list.map { item ->
                val matches = item.packageName == packageName
                item.copy(
                    isFocused = matches,
                    isMinimized = if (matches) false else item.isMinimized,
                    lastActiveEpochMs = if (matches) {
                        System.currentTimeMillis()
                    } else {
                        item.lastActiveEpochMs
                    }
                )
            }
        }
    }

    fun focusedOrMostRecentWindow(includeMinimized: Boolean = false): RunningWindowRecord? {
        val candidates = if (includeMinimized) {
            _runningWindows.value
        } else {
            _runningWindows.value.filterNot { it.isMinimized }
        }
        return candidates.firstOrNull { it.isFocused }
            ?: candidates.maxByOrNull { it.lastActiveEpochMs }
    }

    fun updateExternalWindowVisibility(
        hasVisibleExternalApp: Boolean,
        focusedPackage: String?,
        detectedPackages: List<String> = emptyList()
    ) {
        _externalAppVisibleOnDisplay.value = hasVisibleExternalApp
        val env = _environmentMode.value

        if (!hasVisibleExternalApp) {
            if (env == DesktopEnvironmentMode.TV_LEANBACK) {
                // When an active TV app finishes or exits with Back, immediately return to TV home!
                _isShowDesktopActive.value = true
                _runningWindows.update { list ->
                    list.map { it.copy(isMinimized = true, isFocused = false) }
                }
            }
        } else {
            val launching = _launchingPackage.value
            if (launching != null && (focusedPackage == launching || detectedPackages.contains(launching))) {
                _launchingPackage.value = null
            }
            if (!_isShowDesktopActive.value && focusedPackage != null && focusedPackage != appContext.packageName) {
                markWindowFocused(focusedPackage)
            }
        }
    }

    fun removeWindowRecord(packageName: String) {
        _runningWindows.update { list ->
            val remaining = list.filterNot { it.packageName == packageName }
            val hasVisible = remaining.any { !it.isMinimized }
            if (!hasVisible) {
                _externalAppVisibleOnDisplay.value = false
                _isShowDesktopActive.value = true
            }
            remaining
        }
    }

    fun clearAllWindowRecords() {
        _runningWindows.value = emptyList()
        _externalAppVisibleOnDisplay.value = false
        _isShowDesktopActive.value = true
    }

    fun toggleTaskbarPin(packageName: String) {
        _pinnedPackages.update { current ->
            val next = if (packageName in current) current - packageName else current + packageName
            prefs.edit().putStringSet(KEY_PINNED_PACKAGES, next).apply()
            next
        }
    }

    fun toggleDesktopShortcut(packageName: String) {
        _desktopShortcutPackages.update { current ->
            val next = if (packageName in current) current - packageName else current + packageName
            prefs.edit().putStringSet(KEY_DESKTOP_SHORTCUTS, next).apply()
            next
        }
    }

    fun setEnvironmentMode(mode: DesktopEnvironmentMode) {
        _environmentMode.value = mode
        prefs.edit().putString(KEY_ENVIRONMENT_MODE, mode.name).apply()
    }

    fun setPerformanceProfile(profile: DisplayPerformanceProfile) {
        _performanceProfile.value = profile
        prefs.edit().putString(KEY_PERFORMANCE_PROFILE, profile.name).apply()
    }

    private fun loadSavedEnvironmentMode(): DesktopEnvironmentMode {
        val raw = prefs.getString(KEY_ENVIRONMENT_MODE, DesktopEnvironmentMode.DESKTOP_FREEFORM.name)
        return DesktopEnvironmentMode.entries.firstOrNull { it.name == raw }
            ?: DesktopEnvironmentMode.DESKTOP_FREEFORM
    }

    private fun loadSavedPerformanceProfile(): DisplayPerformanceProfile {
        val raw = prefs.getString(KEY_PERFORMANCE_PROFILE, DisplayPerformanceProfile.ULTRA_FAST_1080P.name)
        return DisplayPerformanceProfile.entries.firstOrNull { it.name == raw }
            ?: DisplayPerformanceProfile.ULTRA_FAST_1080P
    }

    private fun loadSavedDpiScale(): DesktopDpiScale {
        val raw = prefs.getString(KEY_DPI_SCALE, DesktopDpiScale.SCALE_100.name)
        return DesktopDpiScale.entries.firstOrNull { it.name == raw } ?: DesktopDpiScale.SCALE_100
    }

    private fun loadSavedWallpaper(): WallpaperPreset {
        val raw = prefs.getString(KEY_WALLPAPER, WallpaperPreset.OBSIDIAN_AURORA.name)
        return WallpaperPreset.entries.firstOrNull { it.name == raw }
            ?: WallpaperPreset.OBSIDIAN_AURORA
    }

    private fun loadStringSet(key: String, defaults: Set<String>): Set<String> {
        return prefs.getStringSet(key, null)?.toSet() ?: defaults
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val PREFS_NAME = "dexxp_desktop_prefs"
        private const val KEY_ENVIRONMENT_MODE = "environment_mode"
        private const val KEY_PERFORMANCE_PROFILE = "performance_profile"
        private const val KEY_DPI_SCALE = "dpi_scale"
        private const val KEY_WALLPAPER = "wallpaper_preset"
        private const val KEY_PINNED_PACKAGES = "pinned_packages"
        private const val KEY_DESKTOP_SHORTCUTS = "desktop_shortcuts"
        private const val KEY_TOUCHPAD_SENSITIVITY = "touchpad_sensitivity"
        private const val KEY_EXTERNAL_BRIGHTNESS = "external_brightness"
        private const val KEY_CUSTOM_DESKTOP_URI = "custom_desktop_tree_uri"

        private val DEFAULT_PINNED = setOf(
            "com.android.chrome",
            "com.google.android.apps.nbu.files",
            "com.android.documentsui",
            "com.android.settings",
            "com.google.android.gm",
            "com.termux"
        )

        private val DEFAULT_DESKTOP_SHORTCUTS = setOf(
            "com.android.chrome",
            "com.android.documentsui",
            "com.google.android.apps.nbu.files",
            "com.android.settings",
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.google.android.calendar",
            "com.termux"
        )
    }
}
