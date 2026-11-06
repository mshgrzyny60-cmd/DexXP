package com.dexxp.desktop.ui.desktop

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.audio.AudioRouteManager
import com.dexxp.desktop.browser.DesktopBrowserLauncher
import com.dexxp.desktop.clipboard.ClipboardHistoryFlyout
import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.command.CommandPaletteModal
import com.dexxp.desktop.continuity.IncomingCallFloatingBanner
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.continuity.SmartOtpTaskbarBanner
import com.dexxp.desktop.keyboard.AltTabSwitcherOverlay
import com.dexxp.desktop.keyboard.DesktopKeyboardManager
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.layout.WorkspaceLayoutPresetManager
import com.dexxp.desktop.model.AltTabOverlayState
import com.dexxp.desktop.model.AppWindowLaunchPolicy
import com.dexxp.desktop.model.AudioOutputRoute
import com.dexxp.desktop.model.ClipboardHistoryEntry
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.DesktopFileItem
import com.dexxp.desktop.model.DesktopNotificationItem
import com.dexxp.desktop.model.DetectedOtpCode
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.IncomingCallState
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.VirtualWorkspace
import com.dexxp.desktop.model.WallpaperPreset
import com.dexxp.desktop.model.WorkspaceLayoutPreset
import com.dexxp.desktop.notification.DesktopNotificationListenerService
import com.dexxp.desktop.notification.NotificationCenterFlyout
import com.dexxp.desktop.notification.QuickSettingsFlyout
import com.dexxp.desktop.overview.MissionControlOverviewController
import com.dexxp.desktop.overview.MissionControlOverviewScreen
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import com.dexxp.desktop.terminal.DesktopTerminalWindow
import com.dexxp.desktop.workspace.VirtualWorkspaceManager
import com.dexxp.desktop.workspace.WorkspaceSwitcherFlyout

/**
 * Hosts all shell flyouts, modal overlays, and continuity banners anchored above the Taskbar:
 * Start Menu, Virtual Workspace Switcher, Clipboard History, Notification Center, Quick Settings
 * Tray, Developer Terminal, Mission Control Overview, Command Palette, Alt+Tab Switcher, Incoming
 * Call Banner, and Smart OTP Banner.
 */
@Composable
internal fun DesktopFlyoutsLayer(
    context: Context,
    apps: List<DesktopAppItem>,
    desktopFiles: List<DesktopFileItem>,
    displayInfo: ExternalDisplayInfo?,
    dpiScale: DesktopDpiScale,
    wallpaperPreset: WallpaperPreset,
    brightnessFraction: Float,
    audioRoutes: List<AudioOutputRoute>,
    readiness: SystemReadinessStatus,
    runningWindows: List<RunningWindowRecord>,
    notifications: List<DesktopNotificationItem>,
    clipboardEntries: List<ClipboardHistoryEntry>,
    workspaces: List<VirtualWorkspace>,
    activeWorkspaceIndex: Int,
    layoutPresets: List<WorkspaceLayoutPreset>,
    pinnedPackages: Set<String>,
    desktopShortcutPackages: Set<String>,
    appWindowPolicies: Map<String, AppWindowLaunchPolicy>,
    isStartMenuOpen: Boolean,
    isWorkspaceSwitcherOpen: Boolean,
    isClipboardHistoryOpen: Boolean,
    isNotificationCenterOpen: Boolean,
    isSystemTrayOpen: Boolean,
    isTerminalWindowOpen: Boolean,
    isMissionControlOpen: Boolean,
    isCommandPaletteOpen: Boolean,
    altTabState: AltTabOverlayState,
    incomingCallState: IncomingCallState?,
    detectedOtp: DetectedOtpCode?,
    sessionRepository: DesktopSessionRepository,
    appRepository: AppRepository,
    appLauncher: AppLauncher,
    desktopBrowserLauncher: DesktopBrowserLauncher,
    virtualWorkspaceManager: VirtualWorkspaceManager,
    presetManager: WorkspaceLayoutPresetManager,
    clipboardManager: DesktopClipboardManager,
    keyboardManager: DesktopKeyboardManager,
    audioRouteManager: AudioRouteManager,
    terminalLauncher: DesktopTerminalLauncher,
    missionControlController: MissionControlOverviewController,
    commandPaletteController: CommandPaletteController,
    phoneHubController: PhoneHubController
) {
    // Start Menu Panel anchored bottom-start with M3 spring entrance/exit
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 14.dp,
                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
            ),
        contentAlignment = Alignment.BottomStart
    ) {
        AnimatedVisibility(
            visible = isStartMenuOpen,
            enter = fadeIn(tween(140)) + scaleIn(
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(0f, 1f),
                animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(95)) + scaleOut(
                targetScale = 0.96f,
                transformOrigin = TransformOrigin(0f, 1f),
                animationSpec = tween(95)
            )
        ) {
            StartMenuPanel(
                apps = apps,
                webApps = desktopBrowserLauncher.builtInWebApps,
                pinnedPackages = pinnedPackages,
                desktopShortcutPackages = desktopShortcutPackages,
                appWindowPolicies = appWindowPolicies,
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
                onChangeWindowPolicy = sessionRepository::setAppWindowPolicy,
                onOpenAppInfo = { pkg ->
                    sessionRepository.dismissFlyouts()
                    appLauncher.launchAppDetailsSettings(pkg, context)
                },
                onDismiss = sessionRepository::dismissFlyouts
            )
        }
    }

    // Virtual Workspaces & Saved Snap Groups Flyout (`Super + Ctrl + Arrows`)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 140.dp,
                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
            ),
        contentAlignment = Alignment.BottomStart
    ) {
        AnimatedVisibility(
            visible = isWorkspaceSwitcherOpen,
            enter = fadeIn(tween(130)) + scaleIn(
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(0.2f, 1f),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(90)) + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(0.2f, 1f))
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                end = 78.dp,
                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
            ),
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedVisibility(
            visible = isClipboardHistoryOpen,
            enter = fadeIn(tween(130)) + scaleIn(
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(0.85f, 1f),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(90)) + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(0.85f, 1f))
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                end = 14.dp,
                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
            ),
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedVisibility(
            visible = isNotificationCenterOpen,
            enter = fadeIn(tween(135)) + scaleIn(
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(1f, 1f),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(90)) + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 1f))
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                end = 14.dp,
                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 10).dp
            ),
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedVisibility(
            visible = isSystemTrayOpen,
            enter = fadeIn(tween(135)) + scaleIn(
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(1f, 1f),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(90)) + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 1f))
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
}
