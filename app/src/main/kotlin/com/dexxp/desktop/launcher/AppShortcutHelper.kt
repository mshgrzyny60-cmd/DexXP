package com.dexxp.desktop.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a dynamic or manifest application jump-list shortcut (e.g., Chrome "New tab",
 * YouTube "Search", Gmail "Compose") surfaced in right-click context menus.
 */
data class DesktopAppShortcut(
    val packageName: String,
    val shortcutId: String,
    val label: String,
    val shortcutInfo: ShortcutInfo? = null,
    val fallbackIntent: Intent? = null
)

/**
 * Queries up to [MAX_SHORTCUTS_PER_APP] (3) jump-list shortcuts for a package on API 31+.
 *
 * Primary path: [LauncherApps.getShortcuts] (`FLAG_MATCH_DYNAMIC` | `FLAG_MATCH_MANIFEST`).
 * Fallback path: When DexXP is not the phone's default home launcher (`hasShortcutHostPermission()`
 * is `false`), parses exported `<shortcut>` declarations from `android.app.shortcuts` manifest XML
 * so 1-click jump-list actions still work without root or default-launcher role.
 */
object AppShortcutHelper {

    const val MAX_SHORTCUTS_PER_APP = 3
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val META_DATA_SHORTCUTS = "android.app.shortcuts"

    private val shortcutCache = ConcurrentHashMap<String, List<DesktopAppShortcut>>()

    fun getShortcutsForPackage(
        context: Context,
        packageName: String,
        maxCount: Int = MAX_SHORTCUTS_PER_APP
    ): List<DesktopAppShortcut> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        if (packageName.isBlank() || packageName.startsWith("dexxp.webapp.")) return emptyList()

        shortcutCache[packageName]?.let { cached ->
            return cached.take(maxCount)
        }

        val resolved = queryLauncherAppsShortcuts(context, packageName, maxCount)
            .ifEmpty { parseStaticManifestShortcuts(context, packageName, maxCount) }

        shortcutCache[packageName] = resolved
        return resolved
    }

    fun invalidatePackage(packageName: String) {
        shortcutCache.remove(packageName)
    }

    private fun queryLauncherAppsShortcuts(
        context: Context,
        packageName: String,
        maxCount: Int
    ): List<DesktopAppShortcut> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        return runCatching {
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                )
            }
            launcherApps.getShortcuts(query, Process.myUserHandle())
                .orEmpty()
                .asSequence()
                .filter { it.isEnabled }
                .sortedWith(compareBy<ShortcutInfo>({ !it.isDynamic }, { it.rank }))
                .mapNotNull { info ->
                    val label = (info.shortLabel ?: info.longLabel)?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) {
                        null
                    } else {
                        DesktopAppShortcut(
                            packageName = packageName,
                            shortcutId = info.id,
                            label = label,
                            shortcutInfo = info
                        )
                    }
                }
                .distinctBy { it.label.lowercase(Locale.ROOT) }
                .take(maxCount)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun parseStaticManifestShortcuts(
        context: Context,
        packageName: String,
        maxCount: Int
    ): List<DesktopAppShortcut> {
        return runCatching {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            @Suppress("DEPRECATION")
            val activities = pm.queryIntentActivities(launcherIntent, PackageManager.GET_META_DATA)
            val shortcutsResId = activities
                .firstOrNull { (it.activityInfo?.metaData?.getInt(META_DATA_SHORTCUTS, 0) ?: 0) != 0 }
                ?.activityInfo
                ?.metaData
                ?.getInt(META_DATA_SHORTCUTS, 0)
                ?: 0
            if (shortcutsResId == 0) return emptyList()

            val appResources = pm.getResourcesForApplication(packageName)
            val results = ArrayList<DesktopAppShortcut>(maxCount)

            appResources.getXml(shortcutsResId).use { parser ->
                var inShortcut = false
                var shortcutEnabled = true
                var shortcutId = ""
                var shortcutLabel = ""
                var primaryIntent: Intent? = null
                var inPrimaryIntent = false

                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT && results.size < maxCount * 2) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "shortcut" -> {
                                    inShortcut = true
                                    shortcutEnabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                                    shortcutId = parser.getAttributeValue(ANDROID_NS, "shortcutId").orEmpty()
                                    val shortRes = parser.getAttributeResourceValue(ANDROID_NS, "shortcutShortLabel", 0)
                                    val longRes = parser.getAttributeResourceValue(ANDROID_NS, "shortcutLongLabel", 0)
                                    shortcutLabel = when {
                                        shortRes != 0 -> runCatching { appResources.getString(shortRes) }.getOrDefault("")
                                        longRes != 0 -> runCatching { appResources.getString(longRes) }.getOrDefault("")
                                        else -> parser.getAttributeValue(ANDROID_NS, "shortcutShortLabel")
                                            ?: parser.getAttributeValue(ANDROID_NS, "shortcutLongLabel")
                                            ?: ""
                                    }.trim()
                                    primaryIntent = null
                                    inPrimaryIntent = false
                                }

                                "intent" -> {
                                    if (inShortcut && shortcutEnabled && primaryIntent == null) {
                                        val action = parser.getAttributeValue(ANDROID_NS, "action")
                                            ?.takeIf { it.isNotBlank() }
                                            ?: Intent.ACTION_VIEW
                                        val targetPkg = parser.getAttributeValue(ANDROID_NS, "targetPackage")
                                            ?.takeIf { it.isNotBlank() }
                                            ?: packageName
                                        val targetClass = parser.getAttributeValue(ANDROID_NS, "targetClass")
                                            ?.takeIf { it.isNotBlank() }
                                        val dataRaw = parser.getAttributeValue(ANDROID_NS, "data")
                                            ?.takeIf { it.isNotBlank() }

                                        primaryIntent = Intent(action).apply {
                                            if (targetClass != null) {
                                                val fullClass = if (targetClass.startsWith(".")) {
                                                    targetPkg + targetClass
                                                } else {
                                                    targetClass
                                                }
                                                setClassName(targetPkg, fullClass)
                                            } else {
                                                setPackage(targetPkg)
                                            }
                                            if (dataRaw != null) {
                                                data = Uri.parse(dataRaw)
                                            }
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        inPrimaryIntent = true
                                    }
                                }

                                "extra" -> {
                                    if (inPrimaryIntent && primaryIntent != null) {
                                        val name = parser.getAttributeValue(ANDROID_NS, "name")
                                        val value = parser.getAttributeValue(ANDROID_NS, "value")
                                        if (!name.isNullOrBlank() && value != null) {
                                            primaryIntent.putExtra(name, value)
                                        }
                                    }
                                }
                            }
                        }

                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                "intent" -> inPrimaryIntent = false
                                "shortcut" -> {
                                    val candidate = primaryIntent
                                    if (inShortcut &&
                                        shortcutEnabled &&
                                        shortcutId.isNotBlank() &&
                                        shortcutLabel.isNotBlank() &&
                                        candidate != null
                                    ) {
                                        results.add(
                                            DesktopAppShortcut(
                                                packageName = packageName,
                                                shortcutId = shortcutId,
                                                label = shortcutLabel,
                                                shortcutInfo = null,
                                                fallbackIntent = candidate
                                            )
                                        )
                                    }
                                    inShortcut = false
                                    primaryIntent = null
                                    inPrimaryIntent = false
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }

            results
                .distinctBy { it.label.lowercase(Locale.ROOT) }
                .take(maxCount)
        }.getOrDefault(emptyList())
    }
}
