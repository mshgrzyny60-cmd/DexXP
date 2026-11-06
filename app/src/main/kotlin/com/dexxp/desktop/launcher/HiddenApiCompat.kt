package com.dexxp.desktop.launcher

import android.app.ActivityOptions
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import java.lang.reflect.Method

/**
 * Single isolation boundary for all hidden / `@SystemApi` reflective calls in DexXP.
 *
 * Design guarantees:
 * 1. Every reflective lookup and invocation is wrapped in [runCatching] so ART hidden-API
 *    restrictions on Android 12–15 never crash or stall app launches.
 * 2. Never writes raw hidden keys (such as `"android.activity.windowingMode"`) directly into an
 *    [ActivityOptions] [Bundle], avoiding OEM `ActivityTaskManager` composition stalls.
 * 3. Every helper has a deterministic public-API fallback when reflection is unavailable.
 */
object HiddenApiCompat {

    private const val WINDOWING_MODE_FREEFORM = 5

    /**
     * Cached reflective handle to `ActivityOptions.setLaunchWindowingMode(int)`, or `null` when
     * blocked by Android 12–15 hidden-API enforcement on non-system builds.
     */
    private val setLaunchWindowingModeMethod: Method? by lazy {
        runCatching {
            ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    /**
     * Builds an [ActivityOptions] [Bundle] targeting [targetDisplayId] with optional Freeform
     * [launchBounds].
     *
     * Fallback behavior when hidden API is unavailable:
     * - For `MAXIMIZED` / fullscreen launches (`useFreeformWindowing == false`), no reflective call
     *   is attempted at all; `ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplayId)` lets
     *   the secondary `TaskDisplayArea` compose widescreen apps natively.
     * - For Freeform container launches (`useFreeformWindowing == true` with non-empty bounds), if
     *   reflective `setLaunchWindowingMode(WINDOWING_MODE_FREEFORM)` is blocked or fails, [runCatching]
     *   absorbs the error and falls back cleanly to plain public
     *   `ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplayId).setLaunchBounds(launchBounds)`.
     * - Raw hidden keys like `"android.activity.windowingMode"` are NEVER inserted into the bundle.
     */
    fun createLaunchActivityOptionsBundle(
        targetDisplayId: Int,
        isTvMode: Boolean = false,
        launchBounds: Rect? = null,
        useFreeformWindowing: Boolean = false
    ): Bundle {
        val effectiveFreeform = !isTvMode &&
            useFreeformWindowing &&
            launchBounds != null &&
            !launchBounds.isEmpty

        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(targetDisplayId)
            if (effectiveFreeform) {
                setLaunchBounds(launchBounds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }

        if (effectiveFreeform) {
            runCatching {
                setLaunchWindowingModeMethod?.invoke(options, WINDOWING_MODE_FREEFORM)
            }
        }

        return options.toBundle()
    }

    /**
     * Attempts to route media playback to [deviceInfo] via the `@SystemApi`
     * `AudioManager.setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)`.
     *
     * Fallback behavior when hidden API is unavailable:
     * - Returns `false` whenever `MODIFY_AUDIO_ROUTING` or reflective `@SystemApi` access is denied,
     *   allowing the caller (`AudioRouteManager.selectRoute`) to fall back to public
     *   `AudioManager.setCommunicationDevice(...)` or the SystemUI Media Output Switcher.
     */
    @Suppress("PrivateApi")
    fun trySetPreferredDeviceForMediaStrategy(
        audioManager: AudioManager,
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
        setPreferredMethod.invoke(audioManager, mediaStrategy, deviceAttributes)
        true
    }.getOrDefault(false)
}
