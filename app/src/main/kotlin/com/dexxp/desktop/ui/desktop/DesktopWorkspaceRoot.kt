package com.dexxp.desktop.ui.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.audio.AudioRouteManager
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.browser.DesktopBrowserLauncher
import com.dexxp.desktop.clipboard.ClipboardHistoryFlyout
import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.command.CommandPaletteModal
import com.dexxp.desktop.continuity.IncomingCallFloatingBanner
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.continuity.SmartOtpTaskbarBanner
import com.dexxp.desktop.files.DesktopFileManager
import com.dexxp.desktop.files.DesktopFileTile
import com.dexxp.desktop.keyboard.AltTabSwitcherOverlay
import com.dexxp.desktop.keyboard.DesktopKeyboardManager
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.layout.WorkspaceLayoutPresetManager
import com.dexxp.desktop.model.CallPhase
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.DesktopFileItem
import com.dexxp.desktop.model.DetectedOtpCode
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.IncomingCallState
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.ShellSurfaceMode
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.VirtualWorkspace
import com.dexxp.desktop.model.WallpaperPreset
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.notification.DesktopNotificationListenerService
import com.dexxp.desktop.notification.NotificationCenterFlyout
import com.dexxp.desktop.notification.QuickSettingsFlyout
import com.dexxp.desktop.overview.MissionControlOverviewController
import com.dexxp.desktop.overview.MissionControlOverviewScreen
import com.dexxp.desktop.power.PhoneScreenPowerController
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import com.dexxp.desktop.terminal.DesktopTerminalWindow
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.StatusAmber
import com.dexxp.desktop.ui.theme.StatusGreen
import com.dexxp.desktop.windowing.SnapPreviewOverlay
import com.dexxp.desktop.ui.tv.TvLeanbackWorkspaceRoot
import com.dexxp.desktop.workspace.TaskbarWorkspaceChip
import com.dexxp.desktop.workspace.VirtualWorkspaceManager
import com.dexxp.desktop.workspace.WorkspaceSwitcherFlyout
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Primary Jetpack Compose Desktop Environment root rendered on the external display.
 *
 * Performance optimizations:
 * - High-frequency 120Hz `pointerState` reads are isolated inside leaf [InlineDesktopCursorOverlay],
 *   preventing parent workspace recompositions during cursor movement.
 * - Taskbar clock & battery telemetry are isolated inside leaf [TaskbarClockReadout] and
 *   [TaskbarBatteryReadout], preventing parent Taskbar `LazyRow` recompositions on background ticks.
 */
@Composable
fun DesktopWorkspaceRoot(
    sessionRepository: DesktopSessionRepository,
    appRepository: AppRepository,
    appLauncher: AppLauncher,
    pointerFocusBridge: PointerFocusBridge,
    audioRouteManager: AudioRouteManager,
    desktopFileManager: DesktopFileManager,
    phoneScreenPowerController: PhoneScreenPowerController,
    clipboardManager: DesktopClipboardManager,
    virtualWorkspaceManager: VirtualWorkspaceManager,
    desktopBrowserLauncher: DesktopBrowserLauncher,
    presetManager: WorkspaceLayoutPresetManager,
    terminalLauncher: DesktopTerminalLauncher,
    commandPaletteController: CommandPaletteController,
    missionControlController: MissionControlOverviewController,
    phoneHubController: PhoneHubController,
    keyboardManager: DesktopKeyboardManager,
    renderInlineCursorFallback: Boolean,
    isBackdropOnly: Boolean = false
) {
    val environmentMode by sessionRepository.environmentMode.collectAsState()

    if (environmentMode == DesktopEnvironmentMode.TV_LEANBACK && !isBackdropOnly) {
        TvLeanbackWorkspaceRoot(
            sessionRepository = sessionRepository,
            appRepository = appRepository,
            appLauncher = appLauncher,
            audioRouteManager = audioRouteManager
        )
        return
    }

    val context = LocalContext.current
    val apps by appRepository.apps.collectAsState()
    val desktopFiles by desktopFileManager.files.collectAsState()
    val displayInfo by sessionRepository.activeDisplay.collectAsState()
    val dpiScale by sessionRepository.dpiScale.collectAsState()
    val wallpaperPreset by sessionRepository.wallpaperPreset.collectAsState()
    val isStartMenuOpen by sessionRepository.isStartMenuOpen.collectAsState()
    val isSystemTrayOpen by sessionRepository.isSystemTrayOpen.collectAsState()
    val isNotificationCenterOpen by sessionRepository.isNotificationCenterOpen.collectAsState()
    val isClipboardHistoryOpen by sessionRepository.isClipboardHistoryOpen.collectAsState()
    val isWorkspaceSwitcherOpen by sessionRepository.isWorkspaceSwitcherOpen.collectAsState()
    val isCommandPaletteOpen by sessionRepository.isCommandPaletteOpen.collectAsState()
    val isMissionControlOpen by sessionRepository.isMissionControlOpen.collectAsState()
    val isTerminalWindowOpen by sessionRepository.isTerminalWindowOpen.collectAsState()
    val shellMode by sessionRepository.shellSurfaceMode.collectAsState()
    val runningWindows by sessionRepository.runningWindows.collectAsState()
    val notifications by sessionRepository.activeNotifications.collectAsState()
    val clipboardEntries by clipboardManager.history.collectAsState()
    val workspaces by virtualWorkspaceManager.workspaces.collectAsState()
    val activeWorkspaceIndex by virtualWorkspaceManager.activeWorkspaceIndex.collectAsState()
    val layoutPresets by presetManager.presets.collectAsState()
    val incomingCallState by phoneHubController.incomingCallState.collectAsState()
    val detectedOtp by phoneHubController.detectedOtp.collectAsState()
    val isPhoneBlackoutActive by phoneScreenPowerController.isBlackoutActive.collectAsState()
    val audioRoutes by audioRouteManager.routes.collectAsState()
    val brightnessFraction by sessionRepository.externalBrightnessFraction.collectAsState()
    val snapPreview by sessionRepository.snapPreview.collectAsState()
    val altTabState by keyboardManager.altTabState.collectAsState()
    val pinnedPackages by sessionRepository.pinnedPackages.collectAsState()
    val desktopShortcutPackages by sessionRepository.desktopShortcutPackages.collectAsState()
    val readiness by sessionRepository.systemReadiness.collectAsState()

    val activeWorkspace = workspaces.getOrElse(activeWorkspaceIndex) { workspaces.first() }

    val desktopApps by remember(apps, desktopShortcutPackages) {
        derivedStateOf {
            val matched = apps.filter { it.packageName in desktopShortcutPackages }
            matched.ifEmpty { apps.take(8) }
        }
    }

    if (isBackdropOnly) {
        Box(modifier = Modifier.fillMaxSize()) {
            DesktopWallpaperCanvas(
                preset = wallpaperPreset,
                modifier = Modifier.fillMaxSize()
            )
            DesktopShortcutAndFileGrid(
                shortcuts = desktopApps,
                files = desktopFiles,
                fileManager = desktopFileManager,
                pinnedPackages = pinnedPackages,
                desktopShortcutPackages = desktopShortcutPackages,
                onLaunchApp = { item, snap, multi ->
                    appLauncher.launchApp(item, snap, multi, context)
                },
                onOpenFile = { file ->
                    appLauncher.launchContentUriInFreeform(file.uri, file.mimeType, context)
                },
                onShareFile = { file ->
                    desktopFileManager.shareFileExternally(file, context)
                },
                onTogglePinTaskbar = sessionRepository::toggleTaskbarPin,
                onToggleDesktopShortcut = sessionRepository::toggleDesktopShortcut,
                onOpenAppInfo = { pkg -> appLauncher.launchAppDetailsSettings(pkg, context) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = AppLauncher.TASKBAR_HEIGHT_DP.dp)
            )
        }
        return
    }

    val effectiveMode = if (altTabState.isVisible) {
        ShellSurfaceMode.FLYOUT_ACTIVE
    } else {
        shellMode
    }

    when (effectiveMode) {
        ShellSurfaceMode.DOCKED_TASKBAR -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                DesktopTaskbar(
                    apps = apps,
                    pinnedPackages = pinnedPackages,
                    runningWindows = runningWindows,
                    activeWorkspace = activeWorkspace,
                    notificationCount = notifications.size,
                    incomingCall = incomingCallState,
                    detectedOtp = detectedOtp,
                    isStartMenuOpen = false,
                    isCommandPaletteOpen = false,
                    isMissionControlOpen = false,
                    isWorkspaceSwitcherOpen = false,
                    isTerminalOpen = false,
                    isClipboardHistoryOpen = false,
                    isPhoneBlackoutActive = isPhoneBlackoutActive,
                    isSystemTrayOpen = false,
                    isNotificationCenterOpen = false,
                    isShowDesktopActive = false,
                    displayInfo = displayInfo,
                    dpiScale = dpiScale,
                    onToggleStartMenu = sessionRepository::toggleStartMenu,
                    onOpenCommandPalette = commandPaletteController::togglePalette,
                    onToggleMissionControl = missionControlController::toggleOverview,
                    onToggleTerminal = terminalLauncher::toggleTerminal,
                    onToggleShowDesktop = { appLauncher.toggleMinimizeAllOrRestore(context) },
                    onToggleWorkspaceSwitcher = sessionRepository::toggleWorkspaceSwitcher,
                    onToggleClipboardHistory = {
                        clipboardManager.syncCurrentPrimaryClip()
                        sessionRepository.toggleClipboardHistory()
                    },
                    onTogglePhoneBlackout = phoneScreenPowerController::toggleBlackout,
                    onToggleSoftKeyboard = sessionRepository::toggleSoftKeyboardRelay,
                    onToggleNotificationCenter = sessionRepository::toggleNotificationCenter,
                    onToggleSystemTray = sessionRepository::toggleSystemTray,
                    onAnswerCall = { phoneHubController.answerIncomingCall() },
                    onDeclineCall = { phoneHubController.declineOrHangupCall() },
                    onToggleCallSpeaker = { phoneHubController.toggleCallSpeaker() },
                    onCopyOtp = phoneHubController::copyAndInjectOtp,
                    onDismissOtp = phoneHubController::dismissOtpBanner,
                    onActivatePackage = { pkg, snap ->
                        appLauncher.launchOrFocusPackage(pkg, snap, context)
                    },
                    onMinimizePackage = { pkg ->
                        appLauncher.toggleMinimizeWindow(pkg, context)
                    },
                    onCloseWindow = { pkg ->
                        appLauncher.closeWindow(pkg, context)
                    },
                    onTogglePin = sessionRepository::toggleTaskbarPin
                )
            }
        }

        ShellSurfaceMode.FULL_DESKTOP,
        ShellSurfaceMode.FLYOUT_ACTIVE -> {
            Box(modifier = Modifier.fillMaxSize()) {
                if (effectiveMode == ShellSurfaceMode.FULL_DESKTOP) {
                    DesktopWallpaperCanvas(
                        preset = wallpaperPreset,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                sessionRepository.dismissFlyouts()
                            }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = AppLauncher.TASKBAR_HEIGHT_DP.dp)
                    ) {
                        if (!readiness.freeformSupportEnabled) {
                            FreeformSetupNoticeBanner(
                                readiness = readiness,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                            )
                        }

                        DesktopShortcutAndFileGrid(
                            shortcuts = desktopApps,
                            files = desktopFiles,
                            fileManager = desktopFileManager,
                            pinnedPackages = pinnedPackages,
                            desktopShortcutPackages = desktopShortcutPackages,
                            onLaunchApp = { item, snap, multi ->
                                appLauncher.launchApp(item, snap, multi, context)
                            },
                            onOpenFile = { file ->
                                appLauncher.launchContentUriInFreeform(file.uri, file.mimeType, context)
                            },
                            onShareFile = { file ->
                                desktopFileManager.shareFileExternally(file, context)
                            },
                            onTogglePinTaskbar = sessionRepository::toggleTaskbarPin,
                            onToggleDesktopShortcut = sessionRepository::toggleDesktopShortcut,
                            onOpenAppInfo = { pkg -> appLauncher.launchAppDetailsSettings(pkg, context) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.22f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                keyboardManager.dismissAltTab()
                                sessionRepository.dismissFlyouts()
                            }
                    )
                }

                // Inline Snap Preview fallback when top-level HUD overlay isn't attached
                if (renderInlineCursorFallback && snapPreview != null) {
                    snapPreview?.let { preview ->
                        SnapPreviewOverlay(previewState = preview)
                    }
                }

                // Start Menu Panel anchored bottom-start
                if (isStartMenuOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 14.dp,
                                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
                            ),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        StartMenuPanel(
                            apps = apps,
                            webApps = desktopBrowserLauncher.builtInWebApps,
                            pinnedPackages = pinnedPackages,
                            desktopShortcutPackages = desktopShortcutPackages,
                            onSearchFocusChanged = sessionRepository::setSearchFieldFocused,
                            onLaunchApp = { item, snap, multi ->
                                appLauncher.launchApp(item, snap, multi, context)
                            },
                            onLaunchWebApp = { webApp ->
                                desktopBrowserLauncher.launchWebApp(webApp, launchContext = context)
                            },
                            onLaunchDesktopBrowser = {
                                desktopBrowserLauncher.launchDesktopBrowserSession(launchContext = context)
                            },
                            onTogglePinTaskbar = sessionRepository::toggleTaskbarPin,
                            onToggleDesktopShortcut = sessionRepository::toggleDesktopShortcut,
                            onOpenAppInfo = { pkg ->
                                sessionRepository.dismissFlyouts()
                                appLauncher.launchAppDetailsSettings(pkg, context)
                            },
                            onDismiss = sessionRepository::dismissFlyouts
                        )
                    }
                }

                // Virtual Workspaces & Saved Snap Groups Flyout (`Super + Ctrl + Arrows`)
                if (isWorkspaceSwitcherOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 140.dp,
                                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
                            ),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        WorkspaceSwitcherFlyout(
                            workspaces = workspaces,
                            activeIndex = activeWorkspaceIndex,
                            hasActiveWindowToMove = runningWindows.any { !it.isMinimized },
                            presets = layoutPresets,
                            onSelectWorkspace = { idx ->
                                virtualWorkspaceManager.switchToWorkspace(idx, context)
                            },
                            onMoveActiveWindowTo = { idx ->
                                virtualWorkspaceManager.moveFocusedWindowToWorkspace(idx, context)
                            },
                            onLaunchPreset = { preset ->
                                presetManager.launchPreset(preset, context)
                            },
                            onSaveCurrentPreset = { name ->
                                presetManager.saveCurrentArrangementAsPreset(name)
                            },
                            onDeletePreset = presetManager::deletePreset,
                            onPresetInputFocusChanged = sessionRepository::setSearchFieldFocused
                        )
                    }
                }

                // Clipboard History (`Win + V`) Flyout anchored bottom-end
                if (isClipboardHistoryOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                end = 78.dp,
                                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
                            ),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        ClipboardHistoryFlyout(
                            entries = clipboardEntries,
                            onSyncClipboard = clipboardManager::syncCurrentPrimaryClip,
                            onPasteEntry = { entry ->
                                clipboardManager.pasteEntryIntoActiveWindow(entry, keyboardManager)
                            },
                            onTogglePin = clipboardManager::togglePin,
                            onDeleteEntry = clipboardManager::removeEntry,
                            onClearUnpinned = clipboardManager::clearUnpinned,
                            onDismiss = sessionRepository::dismissFlyouts
                        )
                    }
                }

                // Notification Center Flyout anchored bottom-end
                if (isNotificationCenterOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                end = 14.dp,
                                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
                            ),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        NotificationCenterFlyout(
                            notifications = notifications,
                            isListenerEnabled = readiness.notificationListenerEnabled,
                            appRepository = appRepository,
                            onReplyFocusChanged = sessionRepository::setNotificationReplyFocused,
                            onOpenNotification = { item ->
                                sessionRepository.dismissFlyouts()
                                DesktopNotificationListenerService.openNotificationOnExternalDisplay(
                                    context = context,
                                    item = item,
                                    appLauncher = appLauncher,
                                    displayInfo = displayInfo
                                )
                            },
                            onDismissNotification = { key ->
                                DesktopNotificationListenerService.instance?.dismissByKey(key)
                            },
                            onClearAll = {
                                DesktopNotificationListenerService.instance?.dismissAllClearable()
                            },
                            onOpenListenerSettings = {
                                sessionRepository.dismissFlyouts()
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        )
                    }
                }

                // System Tray Quick Settings Flyout anchored bottom-end
                if (isSystemTrayOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                end = 14.dp,
                                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
                            ),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        QuickSettingsFlyout(
                            displayInfo = displayInfo,
                            currentDpiScale = dpiScale,
                            currentWallpaper = wallpaperPreset,
                            externalBrightnessFraction = brightnessFraction,
                            audioRoutes = audioRoutes,
                            readiness = readiness,
                            runningCount = runningWindows.size,
                            onChangeBrightness = sessionRepository::setExternalBrightness,
                            onSelectAudioRoute = audioRouteManager::selectRoute,
                            onOpenSystemAudioPanel = {
                                audioRouteManager.openSystemMediaOutputPanel(context)
                            },
                            onSelectDpiScale = sessionRepository::setDpiScale,
                            onSelectWallpaper = sessionRepository::setWallpaperPreset,
                            onTileSideBySide = {
                                sessionRepository.dismissFlyouts()
                                appLauncher.tileTopTwoWindowsSideBySide(context)
                            },
                            onShowDesktop = { appLauncher.toggleMinimizeAllOrRestore(context) },
                            onClearWindows = sessionRepository::clearAllWindowRecords
                        )
                    }
                }

                // Integrated Draggable Local Developer Terminal Console (`Super + T` / `Ctrl + Alt + T`)
                if (isTerminalWindowOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 8).dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DesktopTerminalWindow(
                            terminalLauncher = terminalLauncher,
                            onInputFocusChanged = sessionRepository::setTerminalInputFocused,
                            onClose = terminalLauncher::closeTerminal
                        )
                    }
                }

                // Mission Control / Exposé Bird's-Eye Window Overview (`Super + Tab` / 3-Finger Swipe Up)
                if (isMissionControlOpen) {
                    MissionControlOverviewScreen(
                        controller = missionControlController,
                        runningWindows = runningWindows,
                        workspaces = workspaces,
                        activeWorkspaceIndex = activeWorkspaceIndex,
                        presets = layoutPresets,
                        displayInfo = displayInfo,
                        appRepository = appRepository,
                        presetManager = presetManager,
                        onSwitchWorkspace = { idx ->
                            virtualWorkspaceManager.switchToWorkspace(idx, context)
                        },
                        onDismiss = missionControlController::closeOverview
                    )
                }

                // Universal Command Palette & Spotlight Modal (`Alt + Space` / `Super + Space`)
                if (isCommandPaletteOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 84.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CommandPaletteModal(
                            controller = commandPaletteController,
                            apps = apps,
                            files = desktopFiles,
                            presets = layoutPresets,
                            pinnedPackages = pinnedPackages,
                            onInputFocusChanged = sessionRepository::setCommandPaletteInputFocused,
                            onDismiss = commandPaletteController::closePalette
                        )
                    }
                }

                // Centered Alt + Tab Task Switcher Overlay
                if (altTabState.isVisible) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AltTabSwitcherOverlay(
                            state = altTabState,
                            appRepository = appRepository,
                            onSelectWindow = keyboardManager::selectAndCommitWindow
                        )
                    }
                }

                // Floating Incoming Call Banner (Mobile Continuity) anchored top-end
                incomingCallState?.let { activeCall ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 18.dp, end = 18.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        IncomingCallFloatingBanner(
                            callState = activeCall,
                            onAnswer = { phoneHubController.answerIncomingCall() },
                            onDeclineOrHangup = { phoneHubController.declineOrHangupCall() },
                            onToggleSpeaker = { phoneHubController.toggleCallSpeaker() }
                        )
                    }
                }

                // Smart SMS OTP 1-Click Copy Banner anchored above bottom-end Taskbar notification tray
                val otpCandidate = detectedOtp
                if (otpCandidate != null && !isNotificationCenterOpen && !isSystemTrayOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                end = 14.dp,
                                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
                            ),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        SmartOtpTaskbarBanner(
                            otp = otpCandidate,
                            onCopyCode = phoneHubController::copyAndInjectOtp,
                            onDismiss = phoneHubController::dismissOtpBanner
                        )
                    }
                }

                // Bottom M3 Desktop Taskbar
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    DesktopTaskbar(
                        apps = apps,
                        pinnedPackages = pinnedPackages,
                        runningWindows = runningWindows,
                        activeWorkspace = activeWorkspace,
                        notificationCount = notifications.size,
                        incomingCall = null,
                        detectedOtp = null,
                        isStartMenuOpen = isStartMenuOpen,
                        isCommandPaletteOpen = isCommandPaletteOpen,
                        isMissionControlOpen = isMissionControlOpen,
                        isWorkspaceSwitcherOpen = isWorkspaceSwitcherOpen,
                        isTerminalOpen = isTerminalWindowOpen,
                        isClipboardHistoryOpen = isClipboardHistoryOpen,
                        isPhoneBlackoutActive = isPhoneBlackoutActive,
                        isSystemTrayOpen = isSystemTrayOpen,
                        isNotificationCenterOpen = isNotificationCenterOpen,
                        isShowDesktopActive = effectiveMode == ShellSurfaceMode.FULL_DESKTOP,
                        displayInfo = displayInfo,
                        dpiScale = dpiScale,
                        onToggleStartMenu = sessionRepository::toggleStartMenu,
                        onOpenCommandPalette = commandPaletteController::togglePalette,
                        onToggleMissionControl = missionControlController::toggleOverview,
                        onToggleTerminal = terminalLauncher::toggleTerminal,
                        onToggleShowDesktop = { appLauncher.toggleMinimizeAllOrRestore(context) },
                        onToggleWorkspaceSwitcher = sessionRepository::toggleWorkspaceSwitcher,
                        onToggleClipboardHistory = {
                            clipboardManager.syncCurrentPrimaryClip()
                            sessionRepository.toggleClipboardHistory()
                        },
                        onTogglePhoneBlackout = phoneScreenPowerController::toggleBlackout,
                        onToggleSoftKeyboard = sessionRepository::toggleSoftKeyboardRelay,
                        onToggleNotificationCenter = sessionRepository::toggleNotificationCenter,
                        onToggleSystemTray = sessionRepository::toggleSystemTray,
                        onAnswerCall = { phoneHubController.answerIncomingCall() },
                        onDeclineCall = { phoneHubController.declineOrHangupCall() },
                        onToggleCallSpeaker = { phoneHubController.toggleCallSpeaker() },
                        onCopyOtp = phoneHubController::copyAndInjectOtp,
                        onDismissOtp = phoneHubController::dismissOtpBanner,
                        onActivatePackage = { pkg, snap ->
                            appLauncher.launchOrFocusPackage(pkg, snap, context)
                        },
                        onMinimizePackage = { pkg ->
                            appLauncher.toggleMinimizeWindow(pkg, context)
                        },
                        onCloseWindow = { pkg ->
                            appLauncher.closeWindow(pkg, context)
                        },
                        onTogglePin = sessionRepository::toggleTaskbarPin
                    )
                }

                // Fallback external monitor luminance dimmer when HUD overlay isn't attached
                if (renderInlineCursorFallback && brightnessFraction < 0.99f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(
                                    alpha = ((1f - brightnessFraction) * 0.78f).coerceIn(0f, 0.75f)
                                )
                            )
                    )
                }

                // Leaf-isolated zero-recomposition cursor fallback
                if (renderInlineCursorFallback) {
                    InlineDesktopCursorOverlay(pointerFocusBridge = pointerFocusBridge)
                }
            }
        }
    }
}

@Composable
fun DesktopWallpaperCanvas(
    preset: WallpaperPreset,
    modifier: Modifier = Modifier
) {
    val colors = when (preset) {
        WallpaperPreset.OBSIDIAN_AURORA -> listOf(
            Color(0xFF0A0F1D),
            Color(0xFF102544),
            Color(0xFF0C1829)
        )
        WallpaperPreset.SAPPHIRE_HORIZON -> listOf(
            Color(0xFF091326),
            Color(0xFF183B73),
            Color(0xFF0F213D)
        )
        WallpaperPreset.EMERALD_MATRIX -> listOf(
            Color(0xFF081715),
            Color(0xFF113832),
            Color(0xFF0B2226)
        )
        WallpaperPreset.CARBON_MINIMAL -> listOf(
            Color(0xFF0E1015),
            Color(0xFF1A1F2B),
            Color(0xFF11141C)
        )
    }

    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = colors,
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF8AB4F8).copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.78f, size.height * 0.24f),
                radius = size.minDimension * 0.55f
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * 0.78f, size.height * 0.24f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF7FD0E6).copy(alpha = 0.11f), Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.76f),
                radius = size.minDimension * 0.48f
            ),
            radius = size.minDimension * 0.48f,
            center = Offset(size.width * 0.22f, size.height * 0.76f)
        )

        val step = 64.dp.toPx()
        val gridColor = Color.White.copy(alpha = 0.025f)
        var x = step
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

/**
 * Unified Desktop Grid rendering both pinned app shortcuts and cross-window draggable user files.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopShortcutAndFileGrid(
    shortcuts: List<DesktopAppItem>,
    files: List<DesktopFileItem>,
    fileManager: DesktopFileManager,
    pinnedPackages: Set<String>,
    desktopShortcutPackages: Set<String>,
    onLaunchApp: (DesktopAppItem, WindowSnapMode, Boolean) -> Unit,
    onOpenFile: (DesktopFileItem) -> Unit,
    onShareFile: (DesktopFileItem) -> Unit,
    onTogglePinTaskbar: (String) -> Unit,
    onToggleDesktopShortcut: (String) -> Unit,
    onOpenAppInfo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedKey by remember { mutableStateOf<String?>(null) }

    LazyHorizontalGrid(
        rows = GridCells.Adaptive(minSize = 98.dp),
        modifier = modifier.padding(18.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(shortcuts, key = { "app:${it.uniqueKey}" }) { app ->
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()
            val isSelected = selectedKey == app.uniqueKey
            var contextMenuOpen by remember { mutableStateOf(false) }

            Box {
                Column(
                    modifier = Modifier
                        .width(92.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .hoverable(interactionSource)
                        .background(
                            when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                isHovered -> Color.White.copy(alpha = 0.10f)
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            width = if (isSelected || isHovered) 1.dp else 0.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            } else if (isHovered) {
                                Color.White.copy(alpha = 0.22f)
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(14.dp)
                        )
                        .desktopSecondaryClick { contextMenuOpen = true }
                        .combinedClickable(
                            onClick = {
                                selectedKey = app.uniqueKey
                                onLaunchApp(app, WindowSnapMode.FREEFORM_CASCADED, false)
                            },
                            onLongClick = { contextMenuOpen = true }
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (app.iconBitmap != null) {
                        Image(
                            bitmap = app.iconBitmap,
                            contentDescription = app.label,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(11.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = app.label.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                AppLaunchContextMenu(
                    expanded = contextMenuOpen,
                    isPinnedTaskbar = app.packageName in pinnedPackages,
                    isOnDesktop = app.packageName in desktopShortcutPackages,
                    onDismiss = { contextMenuOpen = false },
                    onSelectSnapMode = { snap, multi ->
                        contextMenuOpen = false
                        onLaunchApp(app, snap, multi)
                    },
                    onTogglePinTaskbar = {
                        contextMenuOpen = false
                        onTogglePinTaskbar(app.packageName)
                    },
                    onToggleDesktopShortcut = {
                        contextMenuOpen = false
                        onToggleDesktopShortcut(app.packageName)
                    },
                    onOpenAppInfo = {
                        contextMenuOpen = false
                        onOpenAppInfo(app.packageName)
                    }
                )
            }
        }

        items(files, key = { "file:${it.uri}" }) { fileItem ->
            DesktopFileTile(
                file = fileItem,
                fileManager = fileManager,
                onOpenFile = onOpenFile,
                onShareFile = onShareFile
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopTaskbar(
    apps: List<DesktopAppItem>,
    pinnedPackages: Set<String>,
    runningWindows: List<RunningWindowRecord>,
    activeWorkspace: VirtualWorkspace,
    notificationCount: Int,
    incomingCall: IncomingCallState?,
    detectedOtp: DetectedOtpCode?,
    isStartMenuOpen: Boolean,
    isCommandPaletteOpen: Boolean,
    isMissionControlOpen: Boolean,
    isWorkspaceSwitcherOpen: Boolean,
    isTerminalOpen: Boolean,
    isClipboardHistoryOpen: Boolean,
    isPhoneBlackoutActive: Boolean,
    isSystemTrayOpen: Boolean,
    isNotificationCenterOpen: Boolean,
    isShowDesktopActive: Boolean,
    displayInfo: ExternalDisplayInfo?,
    dpiScale: DesktopDpiScale,
    onToggleStartMenu: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onToggleMissionControl: () -> Unit,
    onToggleTerminal: () -> Unit,
    onToggleShowDesktop: () -> Unit,
    onToggleWorkspaceSwitcher: () -> Unit,
    onToggleClipboardHistory: () -> Unit,
    onTogglePhoneBlackout: () -> Unit,
    onToggleSoftKeyboard: () -> Unit,
    onToggleNotificationCenter: () -> Unit,
    onToggleSystemTray: () -> Unit,
    onAnswerCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onToggleCallSpeaker: () -> Unit,
    onCopyOtp: (DetectedOtpCode) -> Unit,
    onDismissOtp: (DetectedOtpCode) -> Unit,
    onActivatePackage: (String, WindowSnapMode?) -> Unit,
    onMinimizePackage: (String) -> Unit,
    onCloseWindow: (String) -> Unit,
    onTogglePin: (String) -> Unit
) {
    val context = LocalContext.current

    val dockEntries by remember(apps, pinnedPackages, runningWindows) {
        derivedStateOf {
            val appsByPkg = HashMap<String, DesktopAppItem>(apps.size).apply {
                apps.forEach { putIfAbsent(it.packageName, it) }
            }
            val runningByPkg = runningWindows.associateBy { it.packageName }
            val orderedPackages = LinkedHashSet<String>().apply {
                pinnedPackages.forEach { add(it) }
                runningWindows.forEach { add(it.packageName) }
            }
            orderedPackages.mapNotNull { pkg ->
                val app = appsByPkg[pkg]
                val running = runningByPkg[pkg]
                if (app == null && running == null) {
                    null
                } else {
                    DockAppEntry(
                        packageName = pkg,
                        label = app?.label ?: running?.label ?: pkg.substringAfterLast('.'),
                        iconBitmap = app?.iconBitmap,
                        isRunning = running != null,
                        isFocused = running?.isFocused == true && !running.isMinimized,
                        isMinimized = running?.isMinimized == true,
                        isPinned = pkg in pinnedPackages
                    )
                }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppLauncher.TASKBAR_HEIGHT_DP.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Start Launcher Button
                Surface(
                    onClick = onToggleStartMenu,
                    shape = RoundedCornerShape(14.dp),
                    color = if (isStartMenuOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.AppsGrid,
                            contentDescription = "Start Menu",
                            tint = if (isStartMenuOpen) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Pill Spotlight / Command Palette Trigger (`Alt + Space`)
                Surface(
                    onClick = onOpenCommandPalette,
                    shape = RoundedCornerShape(50),
                    color = if (isCommandPaletteOpen) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Search,
                            contentDescription = "Command Palette (Alt + Space)",
                            tint = if (isCommandPaletteOpen) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = "Search",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCommandPaletteOpen) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Mission Control / Exposé Window Overview (`Super + Tab`)
                Surface(
                    onClick = onToggleMissionControl,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isMissionControlOpen) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.OverviewGrid,
                            contentDescription = "Mission Control Overview (Super + Tab)",
                            tint = if (isMissionControlOpen) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Virtual Workspaces & Snap Groups Indicator Chip (`Super + Ctrl + Arrows`)
                TaskbarWorkspaceChip(
                    activeWorkspace = activeWorkspace,
                    isSwitcherOpen = isWorkspaceSwitcherOpen,
                    onClick = onToggleWorkspaceSwitcher
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Show Desktop (`Super + D`) Toggle
                Surface(
                    onClick = onToggleShowDesktop,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isShowDesktopActive && runningWindows.isNotEmpty()) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.DesktopView,
                            contentDescription = "Show Desktop",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Integrated Local Developer Terminal (`Ctrl + Alt + T` / `Super + T`)
                Surface(
                    onClick = onToggleTerminal,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isTerminalOpen) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.Terminal,
                            contentDescription = "Developer Terminal (Ctrl + Alt + T)",
                            tint = if (isTerminalOpen) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Pinned & Running Freeform Windows Strip
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(dockEntries, key = { it.packageName }) { entry ->
                        TaskbarAppButton(
                            entry = entry,
                            onClick = {
                                if (entry.isFocused && !entry.isMinimized) {
                                    onMinimizePackage(entry.packageName)
                                } else {
                                    onActivatePackage(entry.packageName, null)
                                }
                            },
                            onSnap = { snap -> onActivatePackage(entry.packageName, snap) },
                            onMinimize = { onMinimizePackage(entry.packageName) },
                            onTogglePin = { onTogglePin(entry.packageName) },
                            onCloseRecord = { onCloseWindow(entry.packageName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Docked Taskbar Mobile Continuity Chips (1-Click Call Controls & Smart SMS OTP while in Freeform apps)
                if (incomingCall != null) {
                    TaskbarCompactCallChip(
                        callState = incomingCall,
                        onAnswer = onAnswerCall,
                        onDecline = onDeclineCall,
                        onToggleSpeaker = onToggleCallSpeaker
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else if (detectedOtp != null) {
                    TaskbarCompactOtpChip(
                        otp = detectedOtp,
                        onCopyCode = onCopyOtp,
                        onDismiss = onDismissOtp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Sleep Phone Screen (AMOLED Blackout) Toggle
                Surface(
                    onClick = onTogglePhoneBlackout,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isPhoneBlackoutActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.MoonSleep,
                            contentDescription = "Sleep Phone Screen (AMOLED Blackout)",
                            tint = if (isPhoneBlackoutActive) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Clipboard History (`Win + V`) Trigger
                Surface(
                    onClick = onToggleClipboardHistory,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isClipboardHistoryOpen) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.Clipboard,
                            contentDescription = "Clipboard History (Win + V)",
                            tint = if (isClipboardHistoryOpen) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Phone Soft Keyboard Relay Button
                Surface(
                    onClick = onToggleSoftKeyboard,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.Keyboard,
                            contentDescription = "Phone Soft Keyboard Relay",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Notification Center Trigger Pill with Live Badge
                Surface(
                    onClick = onToggleNotificationCenter,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isNotificationCenterOpen) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Notifications,
                            contentDescription = "Notifications",
                            tint = if (isNotificationCenterOpen) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(17.dp)
                        )
                        if (notificationCount > 0) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = notificationCount.coerceAtMost(99).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // System Tray Quick Settings Pill
                Surface(
                    onClick = onToggleSystemTray,
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSystemTrayOpen) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        if (displayInfo != null) {
                            Text(
                                text = "${displayInfo.heightPx}p • ${dpiScale.percentLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(
                            imageVector = DesktopIcons.Wifi,
                            contentDescription = "Network",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        Icon(
                            imageVector = DesktopIcons.VolumeUp,
                            contentDescription = "Audio",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        // Leaf-isolated battery & clock readouts so DesktopTaskbar never recomposes on ticks
                        TaskbarBatteryReadout(context = context)
                        TaskbarClockReadout()
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskbarCompactCallChip(
    callState: IncomingCallState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .height(38.dp)
            .border(1.dp, StatusGreen.copy(alpha = 0.7f), RoundedCornerShape(50))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = DesktopIcons.PhoneCall,
                contentDescription = null,
                tint = StatusGreen,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = callState.callerTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Surface(
                onClick = onToggleSpeaker,
                shape = CircleShape,
                color = if (callState.isSpeakerEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(DesktopIcons.VolumeUp, "Speaker", Modifier.size(13.dp))
                }
            }
            if (callState.phase == CallPhase.RINGING) {
                Surface(
                    onClick = onAnswer,
                    shape = CircleShape,
                    color = Color(0xFF1E8E3E),
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(DesktopIcons.PhoneCall, "Answer", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
            }
            Surface(
                onClick = onDecline,
                shape = CircleShape,
                color = Color(0xFFD93025),
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(DesktopIcons.PhoneHangup, "Decline", tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun TaskbarCompactOtpChip(
    otp: DetectedOtpCode,
    onCopyCode: (DetectedOtpCode) -> Unit,
    onDismiss: (DetectedOtpCode) -> Unit
) {
    Surface(
        onClick = { onCopyCode(otp) },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = DesktopIcons.KeyOtp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Copy Code ${otp.code}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss(otp) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = DesktopIcons.Close,
                    contentDescription = "Dismiss OTP",
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun TaskbarBatteryReadout(context: Context) {
    val batteryPercent = rememberBatteryPercentage(context)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = DesktopIcons.Battery,
            contentDescription = "Battery",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "$batteryPercent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TaskbarClockReadout() {
    val clockState = rememberMinuteAlignedClock()
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = clockState.first,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = clockState.second,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class DockAppEntry(
    val packageName: String,
    val label: String,
    val iconBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    val isRunning: Boolean,
    val isFocused: Boolean,
    val isMinimized: Boolean,
    val isPinned: Boolean
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskbarAppButton(
    entry: DockAppEntry,
    onClick: () -> Unit,
    onSnap: (WindowSnapMode) -> Unit,
    onMinimize: () -> Unit,
    onTogglePin: () -> Unit,
    onCloseRecord: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .hoverable(interactionSource)
                .background(
                    when {
                        entry.isFocused -> MaterialTheme.colorScheme.secondaryContainer
                        isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                        entry.isRunning -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .desktopSecondaryClick { menuOpen = true }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                )
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.iconBitmap != null) {
                    Image(
                        bitmap = entry.iconBitmap,
                        contentDescription = entry.label,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = entry.label.take(1).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                if (entry.isRunning) {
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.isMinimized) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            if (entry.isRunning) {
                Box(
                    modifier = Modifier
                        .width(if (entry.isFocused) 18.dp else 7.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            when {
                                entry.isFocused -> MaterialTheme.colorScheme.primary
                                entry.isMinimized -> MaterialTheme.colorScheme.outlineVariant
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                )
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text("Focus / Restore Freeform") },
                leadingIcon = { Icon(DesktopIcons.WindowFreeform, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.FREEFORM_CASCADED) }
            )
            if (entry.isRunning && !entry.isMinimized) {
                DropdownMenuItem(
                    text = { Text("Minimize to Taskbar") },
                    leadingIcon = { Icon(DesktopIcons.Minimize, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onMinimize() }
                )
            }
            DropdownMenuItem(
                text = { Text("Snap Left 50%") },
                leadingIcon = { Icon(DesktopIcons.SplitLeft, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.LEFT_HALF) }
            )
            DropdownMenuItem(
                text = { Text("Snap Right 50%") },
                leadingIcon = { Icon(DesktopIcons.SplitRight, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.RIGHT_HALF) }
            )
            DropdownMenuItem(
                text = { Text("Maximize") },
                leadingIcon = { Icon(DesktopIcons.Maximize, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.MAXIMIZED) }
            )
            DropdownMenuItem(
                text = { Text("Top-Left Quadrant (25%)") },
                leadingIcon = { Icon(DesktopIcons.WindowFreeform, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.TOP_LEFT_QUADRANT) }
            )
            DropdownMenuItem(
                text = { Text("Bottom-Right Quadrant (25%)") },
                leadingIcon = { Icon(DesktopIcons.WindowFreeform, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.BOTTOM_RIGHT_QUADRANT) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (entry.isPinned) "Unpin from Taskbar" else "Pin to Taskbar") },
                leadingIcon = { Icon(DesktopIcons.Pin, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onTogglePin() }
            )
            if (entry.isRunning) {
                DropdownMenuItem(
                    text = { Text("Close Window") },
                    leadingIcon = { Icon(DesktopIcons.Close, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onCloseRecord() }
                )
            }
        }
    }
}

@Composable
private fun FreeformSetupNoticeBanner(
    readiness: SystemReadinessStatus,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = DesktopIcons.Warning,
                contentDescription = null,
                tint = StatusAmber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Freeform multi-window flag is currently off (${readiness.readyScore}/${readiness.totalChecks} checks active). " +
                    "Open DexXP on your phone to copy the one-time ADB command or enable Developer Options -> Enable freeform windows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Leaf-isolated cursor canvas so 120Hz `pointerState` updates never recompose parent desktop trees.
 */
@Composable
private fun InlineDesktopCursorOverlay(pointerFocusBridge: PointerFocusBridge) {
    val pointerState by pointerFocusBridge.pointerState.collectAsState()
    if (!pointerState.visible) return

    val cursorPath = remember { Path() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val x = pointerState.x
        val y = pointerState.y
        cursorPath.reset()
        cursorPath.moveTo(x, y)
        cursorPath.lineTo(x, y + 20.dp.toPx())
        cursorPath.lineTo(x + 5.2.dp.toPx(), y + 15.2.dp.toPx())
        cursorPath.lineTo(x + 9.0.dp.toPx(), y + 22.8.dp.toPx())
        cursorPath.lineTo(x + 12.0.dp.toPx(), y + 21.4.dp.toPx())
        cursorPath.lineTo(x + 8.2.dp.toPx(), y + 13.8.dp.toPx())
        cursorPath.lineTo(x + 15.2.dp.toPx(), y + 13.8.dp.toPx())
        cursorPath.close()

        if (pointerState.isPrimaryDown || pointerState.isSecondaryDown) {
            drawCircle(
                color = Color(0xFF8AB4F8).copy(alpha = 0.45f),
                radius = 12.dp.toPx(),
                center = Offset(x + 3.dp.toPx(), y + 3.dp.toPx())
            )
        }
        drawPath(path = cursorPath, color = Color(0xFF101520))
        drawPath(path = cursorPath, color = Color.White, style = Stroke(width = 1.6.dp.toPx()))
    }
}

@Composable
private fun rememberBatteryPercentage(context: Context): Int {
    var level by remember { mutableIntStateOf(100) }
    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (rawLevel >= 0 && scale > 0) {
                    level = ((rawLevel * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
                }
            }
        }
        val sticky = context.registerReceiver(receiver, filter)
        if (sticky != null) {
            val rawLevel = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (rawLevel >= 0 && scale > 0) {
                level = ((rawLevel * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
            }
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level
}

@Composable
private fun rememberMinuteAlignedClock(): Pair<String, String> {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    var current by remember {
        val now = Date()
        mutableStateOf(timeFormat.format(now) to dateFormat.format(now))
    }
    LaunchedEffect(Unit) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            val now = Date(nowMs)
            current = timeFormat.format(now) to dateFormat.format(now)
            val sleepMs = (60_000L - (nowMs % 60_000L)).coerceIn(500L, 60_000L)
            delay(sleepMs)
        }
    }
    return current
}
