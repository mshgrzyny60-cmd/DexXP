package com.dexxp.desktop.ui.phone

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dexxp.desktop.DexXpApplication
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.input.VirtualTouchpadView
import com.dexxp.desktop.keyboard.DesktopKeyboardManager
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.ExternalTextInputState
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.TvNavigationAction
import com.dexxp.desktop.overview.MissionControlOverviewController
import com.dexxp.desktop.power.AmoledBlackoutHud
import com.dexxp.desktop.power.PhoneScreenPowerController
import com.dexxp.desktop.service.DisplayRouterService
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.system.SystemFlagsInspector
import com.dexxp.desktop.ui.desktop.DesktopWorkspaceRoot
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.StatusAmber
import com.dexxp.desktop.ui.theme.StatusGreen

/**
 * Primary phone display Material 3 controller hosting [VirtualTouchpadView], AMOLED Blackout
 * battery-saver mode ([AmoledBlackoutHud]), auto-deploying Soft Keyboard IME Relay,
 * SAF Desktop Folder picker, and non-root ADB diagnostics.
 */
@Composable
fun PhoneControllerScreen(
    sessionRepository: DesktopSessionRepository,
    pointerFocusBridge: PointerFocusBridge,
    phoneScreenPowerController: PhoneScreenPowerController,
    keyboardManager: DesktopKeyboardManager,
    missionControlController: MissionControlOverviewController,
    phoneHubController: PhoneHubController
) {
    val context = LocalContext.current
    val activeDisplay by sessionRepository.activeDisplay.collectAsState()
    val readiness by sessionRepository.systemReadiness.collectAsState()
    val sensitivity by sessionRepository.touchpadSensitivity.collectAsState()
    val externalInputState by sessionRepository.externalTextInputState.collectAsState()
    val manualKeyboardRelayOpen by sessionRepository.manualKeyboardRelayOpen.collectAsState()
    val customDesktopUri by sessionRepository.customDesktopTreeUri.collectAsState()
    val isBlackoutActive by phoneScreenPowerController.isBlackoutActive.collectAsState()
    val externalMouseName by phoneScreenPowerController.externalMouseName.collectAsState()
    val environmentMode by sessionRepository.environmentMode.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var tvControllerSubTab by remember { mutableIntStateOf(0) }

    val safFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            sessionRepository.setCustomDesktopTreeUri(treeUri)
        }
    }

    val isKeyboardRelayVisible = manualKeyboardRelayOpen || externalInputState.isExternalFieldFocused

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedTab == 2) {
            Box(modifier = Modifier.fillMaxSize()) {
                val app = context.applicationContext as DexXpApplication
                DesktopWorkspaceRoot(
                    sessionRepository = sessionRepository,
                    appRepository = app.appRepository,
                    appLauncher = app.appLauncher,
                    pointerFocusBridge = pointerFocusBridge,
                    audioRouteManager = app.audioRouteManager,
                    desktopFileManager = app.desktopFileManager,
                    phoneScreenPowerController = phoneScreenPowerController,
                    clipboardManager = app.clipboardManager,
                    virtualWorkspaceManager = app.virtualWorkspaceManager,
                    desktopBrowserLauncher = app.desktopBrowserLauncher,
                    presetManager = app.presetManager,
                    terminalLauncher = app.terminalLauncher,
                    commandPaletteController = app.commandPaletteController,
                    missionControlController = app.missionControlController,
                    phoneHubController = app.phoneHubController,
                    keyboardManager = keyboardManager,
                    renderInlineCursorFallback = false,
                    isBackdropOnly = false
                )

                Surface(
                    onClick = { selectedTab = 0 },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f),
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 10.dp, start = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Close,
                            contentDescription = "Exit to Trackpad",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Exit Desktop View",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PhoneDisplayStatusHeader(
                        activeDisplay = activeDisplay,
                        externalMouseName = externalMouseName,
                        readiness = readiness,
                        onRescan = { DisplayRouterService.start(context) },
                        onStopEngine = { DisplayRouterService.stop(context) }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = environmentMode == DesktopEnvironmentMode.TV_LEANBACK,
                            onClick = {
                                val next = if (environmentMode == DesktopEnvironmentMode.TV_LEANBACK) {
                                    DesktopEnvironmentMode.DESKTOP_FREEFORM
                                } else {
                                    DesktopEnvironmentMode.TV_LEANBACK
                                }
                                sessionRepository.setEnvironmentMode(next)
                            },
                            leadingIcon = { Icon(DesktopIcons.Tv, null, Modifier.size(16.dp)) },
                            label = { Text(if (environmentMode == DesktopEnvironmentMode.TV_LEANBACK) "TV Mode (Active)" else "TV Mode") },
                            shape = RoundedCornerShape(50)
                        )
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            leadingIcon = { Icon(DesktopIcons.Touchpad, null, Modifier.size(16.dp)) },
                            label = { Text("Controller") },
                            shape = RoundedCornerShape(50)
                        )
                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = {
                                sessionRepository.refreshSystemReadiness()
                                selectedTab = 1
                            },
                            leadingIcon = { Icon(DesktopIcons.Terminal, null, Modifier.size(16.dp)) },
                            label = { Text("Setup (${readiness.readyScore}/${readiness.totalChecks})") },
                            shape = RoundedCornerShape(50)
                        )
                        FilterChip(
                            selected = false,
                            onClick = { selectedTab = 2 },
                            leadingIcon = { Icon(DesktopIcons.DesktopView, null, Modifier.size(16.dp)) },
                            label = { Text("Desktop View (Fullscreen)") },
                            shape = RoundedCornerShape(50)
                        )
                        FilterChip(
                            selected = isBlackoutActive,
                            onClick = phoneScreenPowerController::toggleBlackout,
                            leadingIcon = { Icon(DesktopIcons.MoonSleep, null, Modifier.size(15.dp)) },
                            label = { Text("Sleep Screen") },
                            shape = RoundedCornerShape(50)
                        )
                        IconButton(
                            onClick = sessionRepository::toggleSoftKeyboardRelay
                        ) {
                            Icon(
                                imageVector = DesktopIcons.Keyboard,
                                contentDescription = "Toggle Soft Keyboard Relay",
                                tint = if (isKeyboardRelayVisible) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

            if (isKeyboardRelayVisible) {
                PhoneKeyboardRelayBar(
                    externalInputState = externalInputState,
                    onTextChanged = keyboardManager::streamRelayTextToActiveWindow,
                    onSendEnter = keyboardManager::sendRelayEnterAction,
                    onDismiss = {
                        sessionRepository.requestSoftKeyboardRelay(false)
                        sessionRepository.updateExternalTextInputState(ExternalTextInputState())
                    }
                )
            }

            if (selectedTab == 0) {
                if (environmentMode == DesktopEnvironmentMode.TV_LEANBACK) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        FilterChip(
                            selected = tvControllerSubTab == 0,
                            onClick = { tvControllerSubTab = 0 },
                            leadingIcon = { Icon(DesktopIcons.Dpad, null, Modifier.size(15.dp)) },
                            label = { Text("TV Remote (D-Pad)") },
                            shape = RoundedCornerShape(50)
                        )
                        Spacer(Modifier.width(10.dp))
                        FilterChip(
                            selected = tvControllerSubTab == 1,
                            onClick = { tvControllerSubTab = 1 },
                            leadingIcon = { Icon(DesktopIcons.Touchpad, null, Modifier.size(15.dp)) },
                            label = { Text("Mouse Trackpad") },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }

                if (environmentMode == DesktopEnvironmentMode.TV_LEANBACK && tvControllerSubTab == 0) {
                    PhoneTvRemoteDpad(
                        sessionRepository = sessionRepository,
                        pointerFocusBridge = pointerFocusBridge,
                        onBack = {
                            sessionRepository.sendTvNavigation(TvNavigationAction.BACK)
                            val handled = DesktopPointerAccessibilityService.instance?.performGlobalAction(
                                AccessibilityService.GLOBAL_ACTION_BACK
                            ) ?: false
                            if (!handled) {
                                pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_BACK)
                            }
                        },
                        onHome = {
                            val handled = DesktopPointerAccessibilityService.instance?.performGlobalAction(
                                AccessibilityService.GLOBAL_ACTION_HOME
                            ) ?: false
                            if (!handled) {
                                pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_HOME)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(24.dp)
                            )
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                VirtualTouchpadView(ctx).apply {
                                    this.pointerFocusBridge = pointerFocusBridge
                                    this.onThreeFingerSwipeUp = {
                                        missionControlController.toggleOverview()
                                    }
                                    this.onThreeFingerSwipeDown = {
                                        sessionRepository.toggleShowDesktop()
                                    }
                                }
                            },
                            update = { view ->
                                view.pointerFocusBridge = pointerFocusBridge
                                view.onThreeFingerSwipeUp = {
                                    missionControlController.toggleOverview()
                                }
                                view.onThreeFingerSwipeDown = {
                                    sessionRepository.toggleShowDesktop()
                                }
                            }
                        )

                        Text(
                            text = "1F Move/Tap • Double-Tap Drag (Edge/Corner Snaps) • 2F Scroll/Right-Click • 3F Up: Mission Control",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp)
                        )
                    }
                }

                // Physical Mouse & Window Switcher Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxSize()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(18.dp)
                            )
                            .pointerInput(pointerFocusBridge) {
                                detectTapGestures(
                                    onPress = {
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            if (pointerFocusBridge.pointerState.value.isDragging) {
                                                pointerFocusBridge.endDrag()
                                            }
                                        }
                                    },
                                    onTap = { pointerFocusBridge.performPrimaryClick() },
                                    onLongPress = { pointerFocusBridge.beginDrag() }
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Left Click",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = sessionRepository::toggleStartMenu,
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Icon(DesktopIcons.AppsGrid, contentDescription = "Start", Modifier.size(18.dp))
                    }

                    FilledTonalButton(
                        onClick = { keyboardManager.cycleAltTab(reverse = false) },
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Icon(DesktopIcons.Layers, contentDescription = "Alt+Tab", Modifier.size(18.dp))
                    }

                    Surface(
                        onClick = pointerFocusBridge::performSecondaryClick,
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxSize()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(18.dp)
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Right Click",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pointer Speed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Slider(
                        value = sensitivity,
                        onValueChange = sessionRepository::setTouchpadSensitivity,
                        valueRange = 0.5f..3.0f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = String.format("%.2fx", sensitivity),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                SystemAndAdbSetupPane(
                    readiness = readiness,
                    customDesktopUri = customDesktopUri,
                    phoneHubController = phoneHubController,
                    onPickDesktopFolder = { safFolderPicker.launch(customDesktopUri) },
                    onResetDesktopFolder = { sessionRepository.setCustomDesktopTreeUri(null) },
                    onRefresh = sessionRepository::refreshSystemReadiness,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

        if (isBlackoutActive) {
            AmoledBlackoutHud(
                activeDisplay = activeDisplay,
                externalMouseName = externalMouseName,
                onWake = phoneScreenPowerController::wakePhoneScreen
            )
        }
    }
}

@Composable
private fun PhoneDisplayStatusHeader(
    activeDisplay: ExternalDisplayInfo?,
    externalMouseName: String?,
    readiness: SystemReadinessStatus,
    onRescan: () -> Unit,
    onStopEngine: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (activeDisplay != null) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = DesktopIcons.Monitor,
                        contentDescription = null,
                        tint = if (activeDisplay != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = activeDisplay?.name ?: "DexXP Desktop Standby",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = buildString {
                            if (activeDisplay != null) {
                                append("${activeDisplay.resolutionSummary} • ${activeDisplay.scaledDensityDpi}dpi • ${activeDisplay.transportType.label}")
                            } else {
                                append("Connect USB-C DP, HDMI, or Wireless • ${readiness.readyScore}/${readiness.totalChecks} active")
                            }
                            if (externalMouseName != null) {
                                append(" • Mouse: $externalMouseName")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeDisplay != null) StatusGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (activeDisplay != null) {
                IconButton(onClick = onStopEngine) {
                    Icon(
                        imageVector = DesktopIcons.Power,
                        contentDescription = "Disconnect Desktop Engine",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onRescan,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Scan", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Active Soft Keyboard IME Relay streaming live text deltas & Enter actions to the active window.
 */
@Composable
private fun PhoneKeyboardRelayBar(
    externalInputState: ExternalTextInputState,
    onTextChanged: (newText: String, previousText: String) -> Unit,
    onSendEnter: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var buffer by remember(externalInputState.targetPackageName) {
        mutableStateOf(externalInputState.currentTextHint)
    }

    LaunchedEffect(Unit) {
        runCatching {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(50))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                DesktopIcons.Keyboard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (buffer.isEmpty()) {
                    val targetLabel = externalInputState.targetPackageName
                        ?.substringAfterLast('.')
                        ?.let { "Streaming to $it..." }
                        ?: "Live IME Relay • Type to stream to active window..."
                    Text(
                        text = targetLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = buffer,
                    onValueChange = { updated ->
                        val prev = buffer
                        buffer = updated
                        onTextChanged(updated, prev)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            onSendEnter()
                            buffer = ""
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            IconButton(onClick = onSendEnter, modifier = Modifier.size(28.dp)) {
                Icon(
                    DesktopIcons.Send,
                    contentDescription = "Enter",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(DesktopIcons.Close, contentDescription = "Close", Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SystemAndAdbSetupPane(
    readiness: SystemReadinessStatus,
    customDesktopUri: Uri?,
    phoneHubController: PhoneHubController,
    onPickDesktopFolder: () -> Unit,
    onResetDesktopFolder: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var phoneCallPermsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val phoneCallPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        phoneCallPermsGranted = grants[Manifest.permission.READ_PHONE_STATE] == true &&
            grants[Manifest.permission.ANSWER_PHONE_CALLS] == true
        phoneHubController.refreshTelephonyCallbackRegistration()
        onRefresh()
    }
    val adbScript = remember(context.packageName) {
        SystemFlagsInspector.buildAdbSetupCommands(context.packageName)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Desktop Files SAF Folder Configuration Card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        DesktopIcons.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Interactive Desktop Files & Drag-and-Drop",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = if (customDesktopUri != null) {
                        "Custom SAF Folder linked: ${customDesktopUri.lastPathSegment ?: customDesktopUri}"
                    } else {
                        "Showing recent Downloads & workspace Desktop files. Select a custom folder to pin directly to the Desktop Grid."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onPickDesktopFolder,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Choose Desktop Folder (SAF)", style = MaterialTheme.typography.labelMedium)
                    }
                    if (customDesktopUri != null) {
                        OutlinedButton(
                            onClick = onResetDesktopFolder,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text("Reset", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // OS Readiness Diagnostics Card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Non-Root OS Capability Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedButton(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Refresh", style = MaterialTheme.typography.labelSmall)
                    }
                }

                ReadinessCheckRow(
                    title = "enable_freeform_support",
                    subtitle = "Enables AOSP multi-window Freeform stacks on external displays",
                    passed = readiness.freeformSupportEnabled
                )
                ReadinessCheckRow(
                    title = "force_resizable_activities",
                    subtitle = "Forces portrait-locked apps into resizable desktop windows",
                    passed = readiness.forceResizableEnabled
                )
                ReadinessCheckRow(
                    title = "force_desktop_mode_on_external_displays",
                    subtitle = "Activates secondary TaskDisplayArea & SECONDARY_HOME root",
                    passed = readiness.desktopModeExternalEnabled
                )
                ReadinessCheckRow(
                    title = "SYSTEM_ALERT_WINDOW (Cursor, Snap & Dimmer HUD)",
                    subtitle = "Renders 120Hz cursor & snap previews above Freeform apps",
                    passed = readiness.overlayPermissionGranted,
                    actionLabel = if (!readiness.overlayPermissionGranted) "Grant" else null,
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                )
                ReadinessCheckRow(
                    title = "Accessibility Pointer, Alt+Tab & IME Relay Bridge",
                    subtitle = "Global shortcuts, external clicks & live text field relay",
                    passed = readiness.accessibilityPointerEnabled,
                    actionLabel = if (!readiness.accessibilityPointerEnabled) "Enable" else null,
                    onAction = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                )
                ReadinessCheckRow(
                    title = "Notification Listener (Desktop Notification Center & Smart OTP)",
                    subtitle = "Streams live notifications, 1-click SMS OTP codes & inline replies",
                    passed = readiness.notificationListenerEnabled,
                    actionLabel = if (!readiness.notificationListenerEnabled) "Allow" else null,
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    }
                )
                ReadinessCheckRow(
                    title = "Phone Hub Continuity (Floating Incoming Call Banner)",
                    subtitle = "Answers, declines & toggles speakerphone directly from monitor",
                    passed = phoneCallPermsGranted,
                    actionLabel = if (!phoneCallPermsGranted) "Grant" else null,
                    onAction = {
                        phoneCallPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ANSWER_PHONE_CALLS
                            )
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(DesktopIcons.Sliders, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open Android Developer Options")
                }
            }
        }

        // One-Time ADB Provisioning Card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "One-Time ADB Provisioning Script",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = { copyToClipboard(context, adbScript) },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(DesktopIcons.Copy, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy ADB", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(12.dp)
                ) {
                    Text(
                        text = adbScript,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessCheckRow(
    title: String,
    subtitle: String,
    passed: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (passed) DesktopIcons.CheckCircle else DesktopIcons.Warning,
                contentDescription = null,
                tint = if (passed) StatusGreen else StatusAmber,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun copyToClipboard(context: Context, script: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DexXP ADB Setup", script))
    Toast.makeText(context, "ADB setup commands copied to clipboard", Toast.LENGTH_SHORT).show()
}

/**
 * Clean 10-foot TV Remote Control D-Pad interface for controlling television navigation from the phone.
 */
@Composable
private fun PhoneTvRemoteDpad(
    sessionRepository: DesktopSessionRepository,
    pointerFocusBridge: PointerFocusBridge,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Quick Action Bar: Back, Home, Volume Down, Volume Up
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                },
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(DesktopIcons.Close, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }

            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHome()
                },
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(DesktopIcons.DesktopView, contentDescription = "Home", modifier = Modifier.size(20.dp))
            }

            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_VOLUME_DOWN)
                },
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(DesktopIcons.VolumeUp, contentDescription = "Vol -", modifier = Modifier.size(18.dp))
            }

            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_VOLUME_UP)
                },
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(DesktopIcons.VolumeUp, contentDescription = "Vol +", modifier = Modifier.size(22.dp))
            }
        }

        // Circular D-Pad
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // UP Button
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sessionRepository.sendTvNavigation(TvNavigationAction.UP)
                    pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_DPAD_UP)
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .size(56.dp)
            ) {
                Icon(DesktopIcons.ArrowUp, contentDescription = "D-Pad Up", modifier = Modifier.size(32.dp))
            }

            // DOWN Button
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sessionRepository.sendTvNavigation(TvNavigationAction.DOWN)
                    pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_DPAD_DOWN)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .size(56.dp)
            ) {
                Icon(DesktopIcons.ArrowDown, contentDescription = "D-Pad Down", modifier = Modifier.size(32.dp))
            }

            // LEFT Button
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sessionRepository.sendTvNavigation(TvNavigationAction.LEFT)
                    pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_DPAD_LEFT)
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(56.dp)
            ) {
                Icon(DesktopIcons.ArrowLeft, contentDescription = "D-Pad Left", modifier = Modifier.size(32.dp))
            }

            // RIGHT Button
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sessionRepository.sendTvNavigation(TvNavigationAction.RIGHT)
                    pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_DPAD_RIGHT)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(56.dp)
            ) {
                Icon(DesktopIcons.ArrowRight, contentDescription = "D-Pad Right", modifier = Modifier.size(32.dp))
            }

            // CENTER OK Button
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sessionRepository.sendTvNavigation(TvNavigationAction.SELECT)
                    pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_DPAD_CENTER)
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 6.dp,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Text(
            text = "D-Pad Remote • Controls TV Screen & Leanback Navigation",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}
