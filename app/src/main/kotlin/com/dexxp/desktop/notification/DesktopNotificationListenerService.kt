package com.dexxp.desktop.notification

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.DexXpApplication
import com.dexxp.desktop.audio.AudioRouteSwitcherSection
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.model.AudioOutputRoute
import com.dexxp.desktop.model.DesktopDpiScale
import com.dexxp.desktop.model.DesktopNotificationItem
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.WallpaperPreset
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.StatusAmber
import com.dexxp.desktop.ui.theme.StatusGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Real-time Desktop Notification Mirroring & Inline Reply service.
 *
 * Streams active `StatusBarNotification` entries from the phone into [com.dexxp.desktop.state.DesktopSessionRepository],
 * supports per-item & bulk dismissal, dispatches inline `RemoteInput` replies directly from the
 * secondary display, and launches notification `PendingIntent`s into Freeform windows.
 */
class DesktopNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        (applicationContext as? DexXpApplication)?.sessionRepository?.refreshSystemReadiness()
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        val app = applicationContext as? DexXpApplication
        app?.sessionRepository?.updateActiveNotifications(emptyList())
        app?.phoneHubController?.onRawNotificationsSynced(emptyArray())
        app?.sessionRepository?.refreshSystemReadiness()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        syncActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        syncActiveNotifications()
    }

    fun syncActiveNotifications() {
        val app = applicationContext as? DexXpApplication ?: return
        val rawList = runCatching { activeNotifications }.getOrNull() ?: return
        val selfPkg = packageName

        val mapped = ArrayList<DesktopNotificationItem>(rawList.size.coerceAtMost(MAX_MIRRORED))
        for (sbn in rawList) {
            if (sbn.packageName == selfPkg) continue
            val notification = sbn.notification ?: continue
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue

            val extras = notification.extras ?: Bundle.EMPTY
            val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE))
                ?.toString()?.trim().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()

            if (title.isEmpty() && body.isEmpty()) continue

            val appName = app.appRepository.findByPackage(sbn.packageName)?.label
                ?: resolvePackageLabelFallback(sbn.packageName)

            var replyPendingIntent: android.app.PendingIntent? = null
            var replyRemoteInputs: List<RemoteInput> = emptyList()
            var replyActionTitle: String? = null

            val actions = notification.actions
            if (actions != null) {
                for (action in actions) {
                    val inputs = action.remoteInputs
                    if (!inputs.isNullOrEmpty() && action.actionIntent != null) {
                        replyPendingIntent = action.actionIntent
                        replyRemoteInputs = inputs.toList()
                        replyActionTitle = action.title?.toString()
                        break
                    }
                }
            }

            mapped.add(
                DesktopNotificationItem(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = appName,
                    title = title.ifEmpty { appName },
                    text = body,
                    postTimeMs = sbn.postTime,
                    isClearable = sbn.isClearable,
                    category = notification.category,
                    contentIntent = notification.contentIntent,
                    replyActionIntent = replyPendingIntent,
                    remoteInputs = replyRemoteInputs,
                    replyLabel = replyActionTitle
                )
            )
            if (mapped.size >= MAX_MIRRORED) break
        }

        mapped.sortByDescending { it.postTimeMs }
        app.sessionRepository.updateActiveNotifications(mapped)
        app.phoneHubController.onRawNotificationsSynced(rawList)
    }

    fun dismissByKey(key: String) {
        runCatching { cancelNotification(key) }
    }

    fun dismissAllClearable() {
        runCatching { cancelAllNotifications() }
    }

    fun sendInlineReply(item: DesktopNotificationItem, replyText: String): Boolean {
        val actionIntent = item.replyActionIntent ?: return false
        val trimmed = replyText.trim()
        if (trimmed.isEmpty() || item.remoteInputs.isEmpty()) return false

        val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val resultsBundle = Bundle().apply {
            for (ri in item.remoteInputs) {
                putCharSequence(ri.resultKey, trimmed)
            }
        }
        RemoteInput.addResultsToIntent(
            item.remoteInputs.toTypedArray(),
            fillInIntent,
            resultsBundle
        )

        val optionsBundle = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }.toBundle()

        return runCatching {
            actionIntent.send(applicationContext, 0, fillInIntent, null, null, null, optionsBundle)
            true
        }.getOrDefault(false)
    }

    private fun resolvePackageLabelFallback(pkg: String): String = runCatching {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    companion object {
        private const val MAX_MIRRORED = 35

        @Volatile
        var instance: DesktopNotificationListenerService? = null
            private set

        fun openNotificationOnExternalDisplay(
            context: Context,
            item: DesktopNotificationItem,
            appLauncher: AppLauncher,
            displayInfo: ExternalDisplayInfo?
        ): Boolean {
            val contentIntent = item.contentIntent
            if (contentIntent == null) {
                return appLauncher.launchOrFocusPackage(item.packageName, null, context)
            }
            val displayId = displayInfo?.displayId ?: Display.DEFAULT_DISPLAY
            val bounds = if (displayId != Display.DEFAULT_DISPLAY) {
                appLauncher.computeWindowBounds(
                    item.packageName,
                    displayInfo,
                    WindowSnapMode.FREEFORM_CASCADED
                )
            } else {
                null
            }
            val options = appLauncher.buildFreeformOptionsBundleForDisplay(displayId, bounds)
            return runCatching {
                contentIntent.send(context, 0, null, null, null, null, options)
                true
            }.getOrElse {
                appLauncher.launchOrFocusPackage(item.packageName, null, context)
            }
        }
    }
}

/**
 * Expandable Material 3 Notification Center Flyout anchored to the bottom-right of the Taskbar.
 * Supports notification click-to-open in Freeform, single/bulk dismiss, and inline `RemoteInput` replies.
 */
@Composable
fun NotificationCenterFlyout(
    notifications: List<DesktopNotificationItem>,
    isListenerEnabled: Boolean,
    appRepository: AppRepository,
    onReplyFocusChanged: (Boolean) -> Unit,
    onOpenNotification: (DesktopNotificationItem) -> Unit,
    onDismissNotification: (String) -> Unit,
    onClearAll: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onDispose { onReplyFocusChanged(false) }
    }

    Surface(
        modifier = modifier
            .width(412.dp)
            .heightIn(min = 240.dp, max = 520.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shadowElevation = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DesktopIcons.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Notification Center",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (notifications.any { it.isClearable }) {
                    Surface(
                        onClick = onClearAll,
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = "Clear all",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (!isListenerEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Warning,
                        contentDescription = null,
                        tint = StatusAmber,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = "Notification mirroring permission is not yet active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        onClick = onOpenListenerSettings,
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Enable Notification Access",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            } else if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "All caught up • No active notifications",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(notifications, key = { it.key }) { item ->
                        NotificationCard(
                            item = item,
                            appRepository = appRepository,
                            onReplyFocusChanged = onReplyFocusChanged,
                            onOpen = { onOpenNotification(item) },
                            onDismiss = { onDismissNotification(item.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    item: DesktopNotificationItem,
    appRepository: AppRepository,
    onReplyFocusChanged: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val iconBitmap = remember(item.packageName) {
        appRepository.findByPackage(item.packageName)?.iconBitmap
    }
    var showReplyInput by remember { mutableStateOf(false) }
    var replyDraft by remember { mutableStateOf("") }
    var sentFeedback by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onOpen() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = item.appName,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.appName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.appName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = timeFormatter.format(Date(item.postTimeMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.isClearable) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (item.text.isNotEmpty()) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (item.canReplyInline) {
            if (!showReplyInput) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            showReplyInput = true
                            sentFeedback = false
                        },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = item.replyLabel ?: "Reply",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    if (sentFeedback) {
                        Text(
                            text = "Reply sent",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusGreen
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (replyDraft.isEmpty()) {
                            Text(
                                text = "Write inline reply...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = replyDraft,
                            onValueChange = { replyDraft = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val ok = DesktopNotificationListenerService.instance
                                        ?.sendInlineReply(item, replyDraft) == true
                                    if (ok) {
                                        replyDraft = ""
                                        showReplyInput = false
                                        sentFeedback = true
                                        onReplyFocusChanged(false)
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { onReplyFocusChanged(it.isFocused) }
                        )
                    }
                    IconButton(
                        onClick = {
                            val ok = DesktopNotificationListenerService.instance
                                ?.sendInlineReply(item, replyDraft) == true
                            if (ok) {
                                replyDraft = ""
                                showReplyInput = false
                                sentFeedback = true
                                onReplyFocusChanged(false)
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Send,
                            contentDescription = "Send Reply",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Material 3 Quick Settings Flyout featuring:
 * - Live Battery + Charging status & Wi-Fi state toggle tile
 * - Display Brightness slider (syncing external monitor luminance + `Settings.System.SCREEN_BRIGHTNESS`)
 * - Media Volume slider (`AudioManager.STREAM_MUSIC`)
 * - 1-Click Audio Output Switcher (HDMI/DP, Phone Speakers, Bluetooth)
 * - Desktop DPI Scaling & Workspace Theme controls
 */
@Composable
fun QuickSettingsFlyout(
    displayInfo: ExternalDisplayInfo?,
    currentDpiScale: DesktopDpiScale,
    currentWallpaper: WallpaperPreset,
    externalBrightnessFraction: Float,
    audioRoutes: List<AudioOutputRoute>,
    readiness: SystemReadinessStatus,
    runningCount: Int,
    onChangeBrightness: (Float) -> Unit,
    onSelectAudioRoute: (AudioOutputRoute) -> Unit,
    onOpenSystemAudioPanel: () -> Unit,
    onSelectDpiScale: (DesktopDpiScale) -> Unit,
    onSelectWallpaper: (WallpaperPreset) -> Unit,
    onTileSideBySide: () -> Unit,
    onShowDesktop: () -> Unit,
    onClearWindows: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumeFraction by remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
        )
    }

    Surface(
        modifier = modifier
            .width(396.dp)
            .heightIn(max = 540.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shadowElevation = 18.dp
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Monitor Summary Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = DesktopIcons.Monitor,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = displayInfo?.name ?: "External Display",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = displayInfo?.let {
                            "${it.resolutionSummary} • ${it.scaledDensityDpi} dpi"
                        } ?: "Active Desktop Session",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Leaf-isolated Wi-Fi & Battery tiles (prevents QuickSettings parent recomposition on background ticks)
            QuickSettingsConnectivityTiles(context = context)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Display Brightness Slider (External Luminance + System Brightness if granted)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = DesktopIcons.Brightness,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Display Brightness",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${(externalBrightnessFraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = externalBrightnessFraction,
                    valueRange = 0.20f..1.0f,
                    onValueChange = { value ->
                        onChangeBrightness(value)
                        applySystemBrightnessIfPermitted(context, value)
                    }
                )
            }

            // Media Volume Control Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = DesktopIcons.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Media Volume",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${(volumeFraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = volumeFraction,
                    onValueChange = { fraction ->
                        volumeFraction = fraction
                        val target = (fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                        runCatching {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        }
                    }
                )
            }

            // Audio Route Switcher (HDMI/DP, Phone Speakers, Bluetooth)
            AudioRouteSwitcherSection(
                routes = audioRoutes,
                onSelectRoute = onSelectAudioRoute,
                onOpenSystemOutputPanel = onOpenSystemAudioPanel
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Desktop DPI Scaling Chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Desktop DPI Scaling",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DesktopDpiScale.entries.forEach { scale ->
                        FilterChip(
                            selected = scale == currentDpiScale,
                            onClick = { onSelectDpiScale(scale) },
                            label = { Text(scale.percentLabel, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }
            }

            // Workspace Theme
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Workspace Theme",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(WallpaperPreset.entries, key = { it.name }) { preset ->
                        FilterChip(
                            selected = preset == currentWallpaper,
                            onClick = { onSelectWallpaper(preset) },
                            label = { Text(preset.title, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }
            }

            // Window Tiling Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = onTileSideBySide,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(DesktopIcons.SplitLeft, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tile 50/50", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Surface(
                    onClick = if (runningCount > 0) onShowDesktop else onClearWindows,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(DesktopIcons.DesktopView, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Show Desktop", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OS Capabilities Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${readiness.readyScore}/${readiness.totalChecks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (readiness.isFullyConfigured) StatusGreen else StatusAmber
                )
            }
        }
    }
}

@Composable
private fun QuickSettingsConnectivityTiles(context: Context) {
    val batteryStatus = rememberLiveBatteryTelemetry(context)
    val wifiConnected = rememberLiveWifiState(context)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            onClick = { toggleOrOpenWifiPanel(context, wifiConnected) },
            shape = RoundedCornerShape(16.dp),
            color = if (wifiConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DesktopIcons.Wifi,
                    contentDescription = "Wi-Fi",
                    tint = if (wifiConnected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Wi-Fi",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (wifiConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = if (wifiConnected) "Connected" else "Off / Tap",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (wifiConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DesktopIcons.Battery,
                    contentDescription = "Battery",
                    tint = if (batteryStatus.second) StatusGreen else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "${batteryStatus.first}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (batteryStatus.second) "Charging" else "On Battery",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun toggleOrOpenWifiPanel(context: Context, currentlyConnected: Boolean) {
    val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    val toggledDirectly = runCatching {
        wifiManager?.setWifiEnabled(!currentlyConnected) == true
    }.getOrDefault(false)

    if (!toggledDirectly) {
        val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(panelIntent) }
    }
}

private fun applySystemBrightnessIfPermitted(context: Context, fraction: Float) {
    if (Settings.System.canWrite(context)) {
        val brightnessInt = (fraction * 255f).roundToInt().coerceIn(25, 255)
        runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessInt
            )
        }
    }
}

@Composable
private fun rememberLiveBatteryTelemetry(context: Context): Pair<Int, Boolean> {
    var telemetry by remember { mutableStateOf(100 to false) }
    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val parse: (Intent?) -> Unit = { intent ->
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                telemetry = ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100) to charging
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = parse(intent)
        }
        parse(context.registerReceiver(receiver, filter))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return telemetry
}

@Composable
private fun rememberLiveWifiState(context: Context): Boolean {
    val cm = remember(context) {
        context.getSystemService(ConnectivityManager::class.java)
    }
    var isWifi by remember {
        val active = cm?.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        mutableStateOf(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
    }
    DisposableEffect(cm) {
        if (cm == null) return@DisposableEffect onDispose {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    isWifi = true
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }

            override fun onLost(network: Network) {
                val active = cm.activeNetwork
                val caps = if (active != null) cm.getNetworkCapabilities(active) else null
                isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return isWifi
}
