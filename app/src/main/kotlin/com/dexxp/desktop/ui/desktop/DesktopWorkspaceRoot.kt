package com.dexxp.desktop.ui.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.audio.AudioRouteManager
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.browser.DesktopBrowserLauncher
import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.files.DesktopFileManager
import com.dexxp.desktop.keyboard.DesktopKeyboardManager
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.layout.WorkspaceLayoutPresetManager
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.ShellSurfaceMode
import com.dexxp.desktop.model.WallpaperPreset
import com.dexxp.desktop.overview.MissionControlOverviewController
import com.dexxp.desktop.power.PhoneScreenPowerController
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import com.dexxp.desktop.ui.tv.TvLeanbackWorkspaceRoot
import com.dexxp.desktop.windowing.SnapPreviewOverlay
import com.dexxp.desktop.workspace.VirtualWorkspaceManager

/**
 * Primary Jetpack Compose Desktop Environment root rendered on the external display.
 *
 * Thin composition root delegating to:
 * - [DesktopShortcutAndFileGrid] & [FreeformSetupNoticeBanner] (`DesktopShortcutGrid.kt`)
 * - [DesktopFlyoutsLayer] (`DesktopFlyouts.kt`)
 * - [DesktopTaskbar] (`DesktopTaskbar.kt`)
 * - Leaf-isolated [InlineDesktopCursorOverlay] so 120Hz cursor movement never recomposes parent trees.
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

    if (environmentMode == DesktopEnvironmentMode.TV_LEANBACK) {
        if (!isBackdropOnly) {
            TvLeanbackWorkspaceRoot(
                sessionRepository = sessionRepository,
                appRepository = appRepository,
                appLauncher = appLauncher,
                audioRouteManager = audioRouteManager
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0F141C),
                                Color(0xFF080B10)
                            )
                        )
                    )
            )
        }
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
    val appWindowPolicies by sessionRepository.appWindowPolicies.collectAsState()
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
                appWindowPolicies = appWindowPolicies,
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
                onChangeWindowPolicy = sessionRepository::setAppWindowPolicy,
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
            Box(modifier = Modifier.fillMaxSize()) {
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
                    modifier = Modifier.fillMaxSize(),
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
                        appLauncher.minimizeWindow(pkg, context)
                    },
                    onCloseWindow = { pkg ->
                        appLauncher.closeWindow(pkg, context)
                    },
                    onTogglePin = sessionRepository::toggleTaskbarPin,
                    onOpenAppInfo = { pkg ->
                        appLauncher.launchAppDetailsSettings(pkg, context)
                    }
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
                            appWindowPolicies = appWindowPolicies,
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
                            onChangeWindowPolicy = sessionRepository::setAppWindowPolicy,
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

                DesktopFlyoutsLayer(
                    context = context,
                    apps = apps,
                    desktopFiles = desktopFiles,
                    displayInfo = displayInfo,
                    dpiScale = dpiScale,
                    wallpaperPreset = wallpaperPreset,
                    brightnessFraction = brightnessFraction,
                    audioRoutes = audioRoutes,
                    readiness = readiness,
                    runningWindows = runningWindows,
                    notifications = notifications,
                    clipboardEntries = clipboardEntries,
                    workspaces = workspaces,
                    activeWorkspaceIndex = activeWorkspaceIndex,
                    layoutPresets = layoutPresets,
                    pinnedPackages = pinnedPackages,
                    desktopShortcutPackages = desktopShortcutPackages,
                    appWindowPolicies = appWindowPolicies,
                    isStartMenuOpen = isStartMenuOpen,
                    isWorkspaceSwitcherOpen = isWorkspaceSwitcherOpen,
                    isClipboardHistoryOpen = isClipboardHistoryOpen,
                    isNotificationCenterOpen = isNotificationCenterOpen,
                    isSystemTrayOpen = isSystemTrayOpen,
                    isTerminalWindowOpen = isTerminalWindowOpen,
                    isMissionControlOpen = isMissionControlOpen,
                    isCommandPaletteOpen = isCommandPaletteOpen,
                    altTabState = altTabState,
                    incomingCallState = incomingCallState,
                    detectedOtp = detectedOtp,
                    sessionRepository = sessionRepository,
                    appRepository = appRepository,
                    appLauncher = appLauncher,
                    desktopBrowserLauncher = desktopBrowserLauncher,
                    virtualWorkspaceManager = virtualWorkspaceManager,
                    presetManager = presetManager,
                    clipboardManager = clipboardManager,
                    keyboardManager = keyboardManager,
                    audioRouteManager = audioRouteManager,
                    terminalLauncher = terminalLauncher,
                    missionControlController = missionControlController,
                    commandPaletteController = commandPaletteController,
                    phoneHubController = phoneHubController
                )

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
                            appLauncher.minimizeWindow(pkg, context)
                        },
                        onCloseWindow = { pkg ->
                            appLauncher.closeWindow(pkg, context)
                        },
                        onTogglePin = sessionRepository::toggleTaskbarPin,
                        onOpenAppInfo = { pkg ->
                            sessionRepository.dismissFlyouts()
                            appLauncher.launchAppDetailsSettings(pkg, context)
                        }
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

                // Leaf-isolated zero-recomposition hardware-accelerated cursor
                InlineDesktopCursorOverlay(pointerFocusBridge = pointerFocusBridge)
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
 * Leaf-isolated cursor canvas so 120Hz `pointerState` updates never recompose parent desktop trees.
 *
 * The state is read only inside the draw lambda, so pointer movement invalidates the draw
 * phase alone (no recomposition / relayout). The sprite is hidden while a real hardware or
 * scrcpy mouse is driving the pointer, because the system already draws its own arrow there.
 */
@Composable
private fun InlineDesktopCursorOverlay(pointerFocusBridge: PointerFocusBridge) {
    val pointerStateHolder = pointerFocusBridge.pointerState.collectAsState()
    val cursorPath = remember { Path() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pointerState = pointerStateHolder.value
        if (!pointerState.visible || pointerState.isHardwarePointer) return@Canvas
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
