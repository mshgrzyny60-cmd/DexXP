package com.dexxp.desktop.ui.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppShortcutHelper
import com.dexxp.desktop.launcher.DesktopAppShortcut
import com.dexxp.desktop.model.CallPhase
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.DetectedOtpCode
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.IncomingCallState
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.VirtualWorkspace
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.StatusGreen
import com.dexxp.desktop.workspace.TaskbarWorkspaceChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopTaskbar(
    apps: List<DesktopAppItem>,
    pinnedPackages: Set<String>,
    runningWindows: List<RunningWindowRecord>,
    activeWorkspace: VirtualWorkspace,
    notificationCount: Int,
    packagesWithNotifications: Set<String> = emptySet(),
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
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(AppLauncher.TASKBAR_HEIGHT_DP.dp),
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
    onTogglePin: (String) -> Unit,
    onLaunchShortcut: (DesktopAppShortcut) -> Unit = {},
    onOpenAppInfo: (String) -> Unit
) {
    val context = LocalContext.current

    val appsByPkg = remember(apps) {
        HashMap<String, DesktopAppItem>(apps.size).apply {
            apps.forEach { putIfAbsent(it.packageName, it) }
        }
    }

    val dockEntries by remember(
        appsByPkg,
        pinnedPackages,
        runningWindows,
        isShowDesktopActive,
        packagesWithNotifications
    ) {
        derivedStateOf {
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
                        isFocused = !isShowDesktopActive && running?.isFocused == true && !running.isMinimized,
                        isMinimized = running?.isMinimized == true,
                        isPinned = pkg in pinnedPackages,
                        hasPendingNotification = hasPendingNotificationForDockPackage(
                            packageName = pkg,
                            activeNotificationPackages = packagesWithNotifications
                        )
                    )
                }
            }
        }
    }

    Surface(
        modifier = modifier,
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
                val startHover = remember { MutableInteractionSource() }
                val isStartHovered by startHover.collectIsHoveredAsState()
                val startScale by animateFloatAsState(if (isStartHovered) 1.06f else 1f, label = "startScale")
                Surface(
                    onClick = onToggleStartMenu,
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isStartMenuOpen -> MaterialTheme.colorScheme.primary
                        isStartHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = startScale
                            scaleY = startScale
                        }
                        .hoverable(startHover)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.AppsGrid,
                            contentDescription = "Start Menu",
                            tint = if (isStartMenuOpen || isStartHovered) {
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
                val searchHover = remember { MutableInteractionSource() }
                val isSearchHovered by searchHover.collectIsHoveredAsState()
                Surface(
                    onClick = onOpenCommandPalette,
                    shape = RoundedCornerShape(50),
                    color = when {
                        isCommandPaletteOpen -> MaterialTheme.colorScheme.primaryContainer
                        isSearchHovered -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier
                        .height(38.dp)
                        .hoverable(searchHover)
                        .pointerHoverIcon(PointerIcon.Hand)
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
                TaskbarUtilityIconButton(
                    icon = DesktopIcons.OverviewGrid,
                    contentDescription = "Mission Control Overview (Super + Tab)",
                    isActive = isMissionControlOpen,
                    onClick = onToggleMissionControl
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Virtual Workspaces & Snap Groups Indicator Chip (`Super + Ctrl + Arrows`)
                TaskbarWorkspaceChip(
                    activeWorkspace = activeWorkspace,
                    isSwitcherOpen = isWorkspaceSwitcherOpen,
                    onClick = onToggleWorkspaceSwitcher
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Show Desktop (`Super + D`) Toggle
                TaskbarUtilityIconButton(
                    icon = DesktopIcons.DesktopView,
                    contentDescription = "Show Desktop",
                    isActive = isShowDesktopActive && runningWindows.isNotEmpty(),
                    onClick = onToggleShowDesktop
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Integrated Local Developer Terminal (`Ctrl + Alt + T` / `Super + T`)
                TaskbarUtilityIconButton(
                    icon = DesktopIcons.Terminal,
                    contentDescription = "Developer Terminal (Ctrl + Alt + T)",
                    isActive = isTerminalOpen,
                    onClick = onToggleTerminal
                )

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
                            onLaunchShortcut = onLaunchShortcut,
                            onMinimize = { onMinimizePackage(entry.packageName) },
                            onTogglePin = { onTogglePin(entry.packageName) },
                            onOpenAppInfo = { onOpenAppInfo(entry.packageName) },
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
                TaskbarUtilityIconButton(
                    icon = DesktopIcons.MoonSleep,
                    contentDescription = "Sleep Phone Screen (AMOLED Blackout)",
                    isActive = isPhoneBlackoutActive,
                    onClick = onTogglePhoneBlackout
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Clipboard History (`Win + V`) Trigger
                TaskbarUtilityIconButton(
                    icon = DesktopIcons.Clipboard,
                    contentDescription = "Clipboard History (Win + V)",
                    isActive = isClipboardHistoryOpen,
                    onClick = onToggleClipboardHistory
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Phone Soft Keyboard Relay Button
                TaskbarUtilityIconButton(
                    icon = DesktopIcons.Keyboard,
                    contentDescription = "Phone Soft Keyboard Relay",
                    isActive = false,
                    onClick = onToggleSoftKeyboard
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Notification Center Trigger Pill with Live Badge
                val notifHover = remember { MutableInteractionSource() }
                val isNotifHovered by notifHover.collectIsHoveredAsState()
                Surface(
                    onClick = onToggleNotificationCenter,
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        isNotificationCenterOpen -> MaterialTheme.colorScheme.primaryContainer
                        isNotifHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .height(38.dp)
                        .hoverable(notifHover)
                        .pointerHoverIcon(PointerIcon.Hand)
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
                val trayHover = remember { MutableInteractionSource() }
                val isTrayHovered by trayHover.collectIsHoveredAsState()
                Surface(
                    onClick = onToggleSystemTray,
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isSystemTrayOpen -> MaterialTheme.colorScheme.secondaryContainer
                        isTrayHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .height(40.dp)
                        .hoverable(trayHover)
                        .pointerHoverIcon(PointerIcon.Hand)
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
                            contentDescription = "Network & Quick Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(15.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onToggleSystemTray
                                )
                        )
                        Icon(
                            imageVector = DesktopIcons.VolumeUp,
                            contentDescription = "Audio Volume & Route Quick Settings",
                            tint = if (isSystemTrayOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .size(15.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onToggleSystemTray
                                )
                        )
                        // Leaf-isolated battery & clock readouts so DesktopTaskbar never recomposes on ticks
                        TaskbarBatteryReadout(context = context, onClick = onToggleSystemTray)
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
private fun TaskbarBatteryReadout(context: Context, onClick: () -> Unit = {}) {
    val batteryPercent = rememberBatteryPercentage(context)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Icon(
            imageVector = DesktopIcons.Battery,
            contentDescription = "Battery & Quick Settings",
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

@Composable
private fun TaskbarUtilityIconButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.06f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "utilityIconScale"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "utilityIconBg"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hoverable(hoverSource)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

internal fun hasPendingNotificationForDockPackage(
    packageName: String,
    activeNotificationPackages: Set<String>
): Boolean {
    if (activeNotificationPackages.isEmpty()) return false
    if (packageName in activeNotificationPackages) return true
    return when (packageName) {
        "dexxp.webapp.whatsapp_web" ->
            "com.whatsapp" in activeNotificationPackages || "com.whatsapp.w4b" in activeNotificationPackages
        "dexxp.webapp.gmail_web" -> "com.google.android.gm" in activeNotificationPackages
        "dexxp.webapp.youtube_desktop" -> "com.google.android.youtube" in activeNotificationPackages
        else -> false
    }
}

private data class DockAppEntry(
    val packageName: String,
    val label: String,
    val iconBitmap: ImageBitmap?,
    val isRunning: Boolean,
    val isFocused: Boolean,
    val isMinimized: Boolean,
    val isPinned: Boolean,
    val hasPendingNotification: Boolean = false
)

private val TaskbarPillShape = RoundedCornerShape(12.dp)
private val TaskbarIconClipShape = RoundedCornerShape(6.dp)
private val TaskbarCloseHoverRed = Color(0xFFD93025)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskbarAppButton(
    entry: DockAppEntry,
    onClick: () -> Unit,
    onSnap: (WindowSnapMode?) -> Unit,
    onLaunchShortcut: (DesktopAppShortcut) -> Unit,
    onMinimize: () -> Unit,
    onTogglePin: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onCloseRecord: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var appShortcuts by remember(entry.packageName) { mutableStateOf<List<DesktopAppShortcut>>(emptyList()) }

    LaunchedEffect(menuOpen, entry.packageName) {
        if (menuOpen) {
            appShortcuts = withContext(Dispatchers.IO) {
                AppShortcutHelper.getShortcutsForPackage(context, entry.packageName)
            }
        }
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isHovered -> 1.05f
            entry.isMinimized -> 0.96f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "taskbarBtnScale"
    )

    val iconOffsetY by animateDpAsState(
        targetValue = if (entry.isMinimized) 2.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "taskbarIconOffsetY"
    )

    val indicatorWidth by animateDpAsState(
        targetValue = when {
            entry.isFocused -> 22.dp
            entry.isRunning && !entry.isMinimized -> 10.dp
            entry.isMinimized -> 5.dp
            else -> 0.dp
        },
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "taskbarIndicatorW"
    )

    val targetBg = when {
        entry.isFocused -> MaterialTheme.colorScheme.secondaryContainer
        isHovered || menuOpen -> MaterialTheme.colorScheme.surfaceContainerHighest
        entry.isRunning && !entry.isMinimized -> MaterialTheme.colorScheme.surfaceContainerHigh
        entry.isMinimized -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.48f)
        else -> Color.Transparent
    }
    val animatedBg by animateColorAsState(targetBg, label = "taskbarBtnBg")

    val iconAlpha by animateFloatAsState(
        targetValue = if (entry.isMinimized && !isHovered) 0.58f else 1.0f,
        label = "taskbarIconAlpha"
    )

    Box {
        Column(
            modifier = Modifier
                .height(42.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(TaskbarPillShape)
                .hoverable(interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .background(animatedBg)
                .desktopSecondaryClick { menuOpen = true }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                )
                .padding(start = 10.dp, end = if (entry.isRunning) 6.dp else 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.graphicsLayer {
                    translationY = iconOffsetY.toPx()
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (entry.iconBitmap != null) {
                        Image(
                            bitmap = entry.iconBitmap,
                            contentDescription = entry.label,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { alpha = iconAlpha }
                                .clip(TaskbarIconClipShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { alpha = iconAlpha }
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

                    if (entry.hasPendingNotification) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(1.5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                if (entry.isRunning) {
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (entry.isFocused) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (entry.isMinimized) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    // Inline 1-Click Close [X] Button directly on Running Taskbar Items
                    val closeHover = remember { MutableInteractionSource() }
                    val isCloseHovered by closeHover.collectIsHoveredAsState()
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .hoverable(closeHover)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                when {
                                    isCloseHovered -> TaskbarCloseHoverRed
                                    isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    else -> Color.Transparent
                                }
                            )
                            .clickable(
                                interactionSource = closeHover,
                                indication = null,
                                onClick = onCloseRecord
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Close,
                            contentDescription = "Close ${entry.label}",
                            tint = when {
                                isCloseHovered -> Color.White
                                isHovered || entry.isFocused -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            if (entry.isRunning) {
                Box(
                    modifier = Modifier
                        .width(indicatorWidth)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            when {
                                entry.isFocused -> MaterialTheme.colorScheme.primary
                                entry.isMinimized -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            }
                        )
                )
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            when {
                !entry.isRunning -> {
                    DropdownMenuItem(
                        text = { Text("Open", fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(DesktopIcons.AppsGrid, null, Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onSnap(null) }
                    )
                }
                entry.isMinimized -> {
                    DropdownMenuItem(
                        text = { Text("Restore Window", fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(DesktopIcons.RestoreWindow, null, Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onClick() }
                    )
                }
                else -> {
                    DropdownMenuItem(
                        text = { Text("Minimize to Taskbar") },
                        leadingIcon = { Icon(DesktopIcons.Minimize, null, Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onMinimize() }
                    )
                }
            }
            if (appShortcuts.isNotEmpty()) {
                HorizontalDivider()
                appShortcuts.forEach { shortcut ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = shortcut.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = DesktopIcons.ArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onLaunchShortcut(shortcut)
                        }
                    )
                }
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(if (entry.isPinned) "Unpin from Taskbar" else "Pin to Taskbar") },
                leadingIcon = { Icon(DesktopIcons.Pin, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onTogglePin() }
            )
            DropdownMenuItem(
                text = { Text("App Info") },
                leadingIcon = { Icon(DesktopIcons.Info, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onOpenAppInfo() }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Portrait Window (9:19)") },
                leadingIcon = { Icon(DesktopIcons.PhonePortrait, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.PORTRAIT_CONTAINER) }
            )
            DropdownMenuItem(
                text = { Text("Landscape Container (16:10)") },
                leadingIcon = { Icon(DesktopIcons.AspectLandscape, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.LANDSCAPE_CONTAINER) }
            )
            DropdownMenuItem(
                text = { Text("Maximize Widescreen") },
                leadingIcon = { Icon(DesktopIcons.Maximize, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.MAXIMIZED) }
            )
            DropdownMenuItem(
                text = { Text("Snap Left Half (50%)") },
                leadingIcon = { Icon(DesktopIcons.SplitLeft, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.LEFT_HALF) }
            )
            DropdownMenuItem(
                text = { Text("Snap Right Half (50%)") },
                leadingIcon = { Icon(DesktopIcons.SplitRight, null, Modifier.size(18.dp)) },
                onClick = { menuOpen = false; onSnap(WindowSnapMode.RIGHT_HALF) }
            )
            if (entry.isRunning) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Close App") },
                    leadingIcon = { Icon(DesktopIcons.Close, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onCloseRecord() }
                )
            }
        }
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
