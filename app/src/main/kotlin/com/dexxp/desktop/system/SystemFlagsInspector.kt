package com.dexxp.desktop.system

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.model.SystemReadinessStatus

/**
 * Inspects low-level AOSP `Settings.Global` flags and non-root OS capabilities required
 * for Freeform Multi-Window orchestration, cross-display input, and notification mirroring.
 */
object SystemFlagsInspector {

    const val KEY_ENABLE_FREEFORM_SUPPORT = "enable_freeform_support"
    const val KEY_FORCE_RESIZABLE_ACTIVITIES = "force_resizable_activities"
    const val KEY_FORCE_DESKTOP_MODE_EXTERNAL = "force_desktop_mode_on_external_displays"
    const val KEY_ENABLE_NON_RESIZABLE_MULTI_WINDOW = "enable_non_resizable_multi_window"

    fun inspect(context: Context): SystemReadinessStatus {
        val resolver = context.contentResolver
        val hasFreeformFeature = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
        )

        val freeformGlobal = readGlobalInt(resolver, KEY_ENABLE_FREEFORM_SUPPORT) == 1
        val forceResizable = readGlobalInt(resolver, KEY_FORCE_RESIZABLE_ACTIVITIES) == 1
        val forceDesktopExternal = readGlobalInt(resolver, KEY_FORCE_DESKTOP_MODE_EXTERNAL) == 1
        val nonResizableMultiWindow = readGlobalInt(resolver, KEY_ENABLE_NON_RESIZABLE_MULTI_WINDOW) == 1

        val overlayGranted = Settings.canDrawOverlays(context)
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
        val notificationListenerGranted = isNotificationListenerEnabled(context)
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return SystemReadinessStatus(
            freeformSupportEnabled = hasFreeformFeature || freeformGlobal,
            forceResizableEnabled = forceResizable,
            desktopModeExternalEnabled = forceDesktopExternal,
            nonResizableMultiWindowEnabled = nonResizableMultiWindow,
            overlayPermissionGranted = overlayGranted,
            accessibilityPointerEnabled = accessibilityEnabled,
            notificationListenerEnabled = notificationListenerGranted,
            notificationPermissionGranted = notificationsGranted
        )
    }

    private fun readGlobalInt(
        resolver: android.content.ContentResolver,
        key: String
    ): Int = runCatching {
        Settings.Global.getInt(resolver, key, 0)
    }.getOrDefault(0)

    fun isNotificationListenerEnabled(context: Context): Boolean {
        return runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val expectedComponent = ComponentName(context, DesktopPointerAccessibilityService::class.java)
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        if (enabledServices.any { info ->
                val resolveInfo = info.resolveInfo?.serviceInfo
                resolveInfo != null &&
                    resolveInfo.packageName == expectedComponent.packageName &&
                    resolveInfo.name == expectedComponent.className
            }
        ) {
            return true
        }
        val flatSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flatSetting.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expectedComponent
        }
    }

    fun buildAdbSetupCommands(packageName: String, displayId: Int = 2): String = buildString {
        appendLine("# 1. Enable AOSP Freeform Window Management & Resizable Activities")
        appendLine("adb shell settings put global enable_freeform_support 1")
        appendLine("adb shell settings put global force_resizable_activities 1")
        appendLine("adb shell settings put global enable_non_resizable_multi_window 1")
        appendLine()
        appendLine("# 2. Activate Secondary Display Desktop Mode")
        appendLine("adb shell settings put global force_desktop_mode_on_external_displays 1")
        appendLine()
        appendLine("# 3. Enforce Full Desktop/Tablet Layout in Apps (CapCut, Chrome, YouTube)")
        appendLine("adb shell wm density 180 -d $displayId")
        appendLine()
        appendLine("# 4. Grant Overlay, Accessibility Bridge & Notification Listener")
        appendLine("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
        appendLine(
            "adb shell cmd notification allow_listener " +
                "$packageName/com.dexxp.desktop.notification.DesktopNotificationListenerService"
        )
        appendLine(
            "adb shell settings put secure enabled_accessibility_services " +
                "$packageName/com.dexxp.desktop.bridge.DesktopPointerAccessibilityService"
        )
        appendLine("adb shell settings put secure accessibility_enabled 1")
        appendLine()
        appendLine("# 5. Apply WindowManagerService changes (reboot once)")
        appendLine("adb reboot")
    }
}
