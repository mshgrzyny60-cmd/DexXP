package com.dexxp.desktop.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.model.AudioEndpointCategory
import com.dexxp.desktop.model.AudioOutputRoute
import com.dexxp.desktop.ui.icons.DesktopIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 12–15 (`API 31–35`) Audio Output Routing Engine.
 *
 * - Monitors real-time sink hotplugs (HDMI/DisplayPort Alt-Mode audio, Phone Stereo Speakers,
 *   Bluetooth A2DP / LE Audio, USB DACs) via [AudioManager.registerAudioDeviceCallback].
 * - Switches active output routes using:
 *   1. Reflective AOSP `AudioManager.setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)`
 *      for `STREAM_MUSIC` media routing when system/privileged access is available;
 *   2. Public Android 12+ [AudioManager.setCommunicationDevice] / [AudioManager.clearCommunicationDevice];
 *   3. Optional SystemUI Media Output Switcher panel (`com.android.settings.panel.action.MEDIA_OUTPUT`).
 */
class AudioRouteManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _routes = MutableStateFlow<List<AudioOutputRoute>>(emptyList())
    val routes: StateFlow<List<AudioOutputRoute>> = _routes.asStateFlow()

    @Volatile
    private var selectedDeviceId: Int = -1

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes()
        }
    }

    init {
        audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refreshRoutes()
    }

    fun refreshRoutes() {
        val am = audioManager ?: return
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val commDeviceId = runCatching { am.communicationDevice?.id }.getOrNull()

        // Deduplicate by endpoint category + product name so multi-channel sinks appear cleanly once
        val deduped = LinkedHashMap<String, AudioOutputRoute>()

        for (device in outputs) {
            if (!device.isSink) continue
            val category = classifySink(device.type) ?: continue
            val product = device.productName?.toString()?.trim().orEmpty()
            val readableName = buildReadableSinkName(category, product, device.type)
            val subtitle = when (category) {
                AudioEndpointCategory.HDMI_DISPLAYPORT -> "External Monitor Digital Audio"
                AudioEndpointCategory.PHONE_SPEAKER -> "Internal Device Stereo"
                AudioEndpointCategory.BLUETOOTH -> "Wireless Bluetooth A2DP / LE Audio"
                AudioEndpointCategory.USB_OR_WIRED -> "USB-C / Wired Audio"
            }

            val key = "${category.name}:$readableName"
            if (!deduped.containsKey(key)) {
                deduped[key] = AudioOutputRoute(
                    deviceId = device.id,
                    name = readableName,
                    subtitle = subtitle,
                    category = category,
                    androidDeviceType = device.type,
                    isSelected = false
                )
            }
        }

        val rawList = deduped.values.sortedBy { routePriority(it.category) }
        val activeId = when {
            rawList.any { it.deviceId == selectedDeviceId } -> selectedDeviceId
            commDeviceId != null && rawList.any { it.deviceId == commDeviceId } -> commDeviceId
            else -> defaultActiveRouteId(rawList)
        }
        selectedDeviceId = activeId

        _routes.value = rawList.map { it.copy(isSelected = it.deviceId == activeId) }
    }

    /**
     * Switches audio playback toward [route] using Android 12+ `setPreferredDeviceForStrategy`
     * (reflective `@SystemApi` path) paired with public `setCommunicationDevice`.
     */
    fun selectRoute(route: AudioOutputRoute): Boolean {
        val am = audioManager ?: return false
        val targetInfo = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == route.deviceId } ?: return false

        // 1. Attempt AOSP AudioProductStrategy media routing (`setPreferredDeviceForStrategy`)
        val strategyRouted = applyPreferredDeviceForMediaStrategy(am, targetInfo)

        // 2. Apply public Android 12+ communication device routing
        val commRouted = runCatching {
            val commCandidates = am.availableCommunicationDevices
            val matchingComm = commCandidates.firstOrNull {
                it.id == targetInfo.id || it.type == targetInfo.type
            }
            if (matchingComm != null) {
                am.setCommunicationDevice(matchingComm)
            } else if (route.category == AudioEndpointCategory.HDMI_DISPLAYPORT) {
                am.clearCommunicationDevice()
                true
            } else {
                false
            }
        }.getOrDefault(false)

        selectedDeviceId = route.deviceId
        refreshRoutes()
        return strategyRouted || commRouted
    }

    /**
     * Opens the native Android SystemUI Media Output Switcher panel for non-privileged
     * `STREAM_MUSIC` A2DP/Speaker switching on strict OEM builds.
     */
    fun openSystemMediaOutputPanel(launchContext: Context? = null) {
        val ctx = launchContext ?: appContext
        val panelIntent = Intent(ACTION_SYSTEM_MEDIA_OUTPUT).apply {
            putExtra(EXTRA_PACKAGE_NAME, appContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            ctx.startActivity(panelIntent)
            true
        }.getOrDefault(false)

        if (!launched) {
            val fallback = Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(fallback) }
        }
    }

    @Suppress("PrivateApi")
    private fun applyPreferredDeviceForMediaStrategy(
        am: AudioManager,
        deviceInfo: AudioDeviceInfo
    ): Boolean = runCatching {
        val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        val deviceAttrClass = Class.forName("android.media.AudioDeviceAttributes")

        val getStrategiesMethod = strategyClass.getMethod("getAudioProductStrategies")
        val strategies = getStrategiesMethod.invoke(null) as? List<*> ?: return false

        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val supportsMethod = strategyClass.getMethod(
            "supportsAudioAttributes",
            AudioAttributes::class.java
        )
        val mediaStrategy = strategies.firstOrNull { strategy ->
            strategy != null && (supportsMethod.invoke(strategy, mediaAttrs) as? Boolean == true)
        } ?: strategies.firstOrNull() ?: return false

        val attrCtor = deviceAttrClass.getConstructor(AudioDeviceInfo::class.java)
        val deviceAttributes = attrCtor.newInstance(deviceInfo)

        val setPreferredMethod = AudioManager::class.java.getMethod(
            "setPreferredDeviceForStrategy",
            strategyClass,
            deviceAttrClass
        )
        setPreferredMethod.invoke(am, mediaStrategy, deviceAttributes)
        true
    }.getOrDefault(false)

    private fun classifySink(type: Int): AudioEndpointCategory? = when (type) {
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE -> AudioEndpointCategory.HDMI_DISPLAYPORT

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioEndpointCategory.PHONE_SPEAKER

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        TYPE_BLE_BROADCAST_COMPAT -> AudioEndpointCategory.BLUETOOTH

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioEndpointCategory.USB_OR_WIRED

        else -> null
    }

    private fun buildReadableSinkName(
        category: AudioEndpointCategory,
        product: String,
        type: Int
    ): String {
        return when (category) {
            AudioEndpointCategory.HDMI_DISPLAYPORT ->
                if (product.isNotEmpty() && !product.equals("Android", ignoreCase = true)) {
                    "HDMI / DP • $product"
                } else {
                    "External Monitor (HDMI / DP)"
                }

            AudioEndpointCategory.PHONE_SPEAKER -> "Phone Stereo Speakers"

            AudioEndpointCategory.BLUETOOTH ->
                if (product.isNotEmpty()) product else "Bluetooth Headphones"

            AudioEndpointCategory.USB_OR_WIRED ->
                if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    if (product.isNotEmpty()) "USB Audio • $product" else "USB-C Audio Output"
                } else {
                    "Wired Headphones"
                }
        }
    }

    private fun routePriority(category: AudioEndpointCategory): Int = when (category) {
        AudioEndpointCategory.HDMI_DISPLAYPORT -> 0
        AudioEndpointCategory.BLUETOOTH -> 1
        AudioEndpointCategory.USB_OR_WIRED -> 2
        AudioEndpointCategory.PHONE_SPEAKER -> 3
    }

    private fun defaultActiveRouteId(routes: List<AudioOutputRoute>): Int {
        return (routes.firstOrNull { it.category == AudioEndpointCategory.BLUETOOTH }
            ?: routes.firstOrNull { it.category == AudioEndpointCategory.HDMI_DISPLAYPORT }
            ?: routes.firstOrNull())?.deviceId ?: -1
    }

    fun shutdown() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
    }

    companion object {
        private const val TYPE_BLE_BROADCAST_COMPAT = 30
        private const val ACTION_SYSTEM_MEDIA_OUTPUT =
            "com.android.settings.panel.action.MEDIA_OUTPUT"
        private const val EXTRA_PACKAGE_NAME = "com.android.settings.panel.extra.PACKAGE_NAME"
    }
}

/**
 * Material 3 Audio Output Switcher UI embedded in the System Tray Quick Settings flyout.
 */
@Composable
fun AudioRouteSwitcherSection(
    routes: List<AudioOutputRoute>,
    onSelectRoute: (AudioOutputRoute) -> Unit,
    onOpenSystemOutputPanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Audio Output Routing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                onClick = onOpenSystemOutputPanel,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = "OS Mixer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }

        routes.forEach { route ->
            val icon = when (route.category) {
                AudioEndpointCategory.HDMI_DISPLAYPORT -> DesktopIcons.Monitor
                AudioEndpointCategory.PHONE_SPEAKER -> DesktopIcons.Speaker
                AudioEndpointCategory.BLUETOOTH -> DesktopIcons.Bluetooth
                AudioEndpointCategory.USB_OR_WIRED -> DesktopIcons.Headphones
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (route.isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (route.isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectRoute(route) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = route.name,
                    tint = if (route.isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = route.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (route.isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1
                    )
                    Text(
                        text = route.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (route.isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
                if (route.isSelected) {
                    Icon(
                        imageVector = DesktopIcons.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
