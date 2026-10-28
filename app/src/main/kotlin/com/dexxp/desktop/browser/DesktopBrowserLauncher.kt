package com.dexxp.desktop.browser

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.model.DesktopWebAppDefinition
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.windowing.WindowSnappingController
import kotlin.math.roundToInt

/**
 * Desktop Browser Mode Enforcer & Standalone Borderless Web App Launcher.
 *
 * Prevents mobile browsers (Chrome, Brave, Edge, Vivaldi, Firefox) from rendering condensed
 * phone layouts on secondary monitors by:
 * 1. Injecting Desktop User-Agent Client Hints (`Sec-CH-UA-Mobile: ?0`, `Sec-CH-UA-Platform: "Linux"`)
 *    via `Browser.EXTRA_HEADERS`;
 * 2. Enforcing wide `16:10` desktop viewport Freeform bounds (`>= 1280px` width on 1080p+ displays);
 * 3. Launching borderless, isolated multi-instance Custom Tab / WebApp tasks (`FLAG_ACTIVITY_MULTIPLE_TASK`)
 *    for **WhatsApp Web**, **YouTube Desktop**, and **Gmail Web**.
 */
class DesktopBrowserLauncher(
    context: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appLauncher: AppLauncher,
    private val snappingController: WindowSnappingController
) {
    private val appContext = context.applicationContext

    val builtInWebApps: List<DesktopWebAppDefinition> = listOf(
        DesktopWebAppDefinition(
            id = "whatsapp_web",
            title = "WhatsApp Web",
            subtitle = "Desktop Messenger",
            desktopUrl = "https://web.whatsapp.com/",
            accentColorHex = 0xFF25D366
        ),
        DesktopWebAppDefinition(
            id = "youtube_desktop",
            title = "YouTube Desktop",
            subtitle = "Full Theater Studio",
            desktopUrl = "https://www.youtube.com/?app=desktop&persist_app=1",
            accentColorHex = 0xFFFF3D00
        ),
        DesktopWebAppDefinition(
            id = "gmail_web",
            title = "Gmail Web",
            subtitle = "Split-Pane Inbox",
            desktopUrl = "https://mail.google.com/mail/u/0/#inbox",
            accentColorHex = 0xFF8AB4F8
        )
    )

    /**
     * Launches a standalone borderless Freeform Desktop Web App instance on the secondary display.
     */
    fun launchWebApp(
        webApp: DesktopWebAppDefinition,
        snapMode: WindowSnapMode = WindowSnapMode.FREEFORM_CASCADED,
        launchContext: Context? = null
    ): Boolean {
        val displayInfo = sessionRepository.activeDisplay.value
        val browserPkg = resolveInstalledDesktopBrowserPackage()
        val intent = buildDesktopWebIntent(
            url = webApp.desktopUrl,
            appSessionId = "dexxp.webapp.${webApp.id}",
            browserPackage = browserPkg,
            borderlessWebAppMode = true
        )
        val bounds = computeDesktopViewportBounds(displayInfo, snapMode)
        return appLauncher.launchCustomIntentInFreeform(
            intent = intent,
            recordPackageKey = browserPkg ?: "webapp.${webApp.id}",
            label = webApp.title,
            customBounds = bounds,
            snapMode = snapMode,
            launchContext = launchContext
        )
    }

    /**
     * Launches the user's primary browser in enforced Desktop Viewport mode.
     */
    fun launchDesktopBrowserSession(
        url: String = DEFAULT_DESKTOP_START_URL,
        snapMode: WindowSnapMode = WindowSnapMode.FREEFORM_CASCADED,
        launchContext: Context? = null
    ): Boolean {
        val displayInfo = sessionRepository.activeDisplay.value
        val browserPkg = resolveInstalledDesktopBrowserPackage()
        val intent = buildDesktopWebIntent(
            url = url,
            appSessionId = "dexxp.browser.${System.currentTimeMillis()}",
            browserPackage = browserPkg,
            borderlessWebAppMode = false
        )
        val bounds = computeDesktopViewportBounds(displayInfo, snapMode)
        return appLauncher.launchCustomIntentInFreeform(
            intent = intent,
            recordPackageKey = browserPkg ?: "com.android.chrome",
            label = "Desktop Browser",
            customBounds = bounds,
            snapMode = snapMode,
            launchContext = launchContext
        )
    }

    fun buildDesktopWebIntent(
        url: String,
        appSessionId: String,
        browserPackage: String?,
        borderlessWebAppMode: Boolean
    ): Intent {
        val headers = Bundle().apply {
            putString("Sec-CH-UA-Mobile", "?0")
            putString("Sec-CH-UA-Platform", "\"Linux\"")
            putString("Upgrade-Insecure-Requests", "1")
        }

        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (browserPackage != null) {
                setPackage(browserPackage)
            }
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT

            putExtra(Browser.EXTRA_APPLICATION_ID, appSessionId)
            putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
            putExtra(Browser.EXTRA_HEADERS, headers)

            if (borderlessWebAppMode) {
                // Custom Tabs / Standalone WebApp minimal-chrome flags
                val sessionBundle = Bundle().apply {
                    putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
                }
                putExtras(sessionBundle)
                putExtra(EXTRA_CUSTOM_TABS_TITLE_VISIBILITY, 0)
                putExtra(EXTRA_CUSTOM_TABS_URLBAR_HIDING, true)
                putExtra(EXTRA_CUSTOM_TABS_COLOR_SCHEME, 2)
                putExtra(EXTRA_CUSTOM_TABS_TOOLBAR_COLOR, 0xFF0E131E.toInt())

                val standaloneDisplayMode = Bundle().apply {
                    putInt(KEY_TWA_DISPLAY_MODE_ID, 1) // Standalone borderless mode
                }
                putExtra(EXTRA_TWA_DISPLAY_MODE, standaloneDisplayMode)
            }
        }
    }

    /**
     * Computes wide desktop viewport bounds (`>= 84%` width on Freeform) so responsive web apps
     * trigger full multi-column desktop CSS breakpoints instead of condensed mobile drawers.
     */
    fun computeDesktopViewportBounds(
        displayInfo: ExternalDisplayInfo?,
        snapMode: WindowSnapMode
    ): Rect {
        val width = displayInfo?.widthPx ?: 1920
        val height = displayInfo?.heightPx ?: 1080
        val dpi = displayInfo?.scaledDensityDpi ?: 160

        if (snapMode != WindowSnapMode.FREEFORM_CASCADED) {
            return snappingController.computeSnapBounds(snapMode, width, height, dpi)
        }

        val density = dpi / 160f
        val taskbarPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()
        val titlebarAllowancePx = (32 * density).roundToInt()
        val marginPx = (WindowSnappingController.SNAP_MARGIN_DP * density).roundToInt()
        val usableHeight = (height - taskbarPx).coerceAtLeast(480)

        val targetWidth = (width * 0.84f).roundToInt()
            .coerceAtLeast(1280.coerceAtMost(width - marginPx * 2))
            .coerceAtMost(width - marginPx * 2)
        val targetHeight = (usableHeight * 0.84f).roundToInt()
            .coerceAtMost(usableHeight - titlebarAllowancePx - marginPx)

        val left = ((width - targetWidth) / 2).coerceAtLeast(marginPx)
        val top = ((usableHeight - targetHeight) / 2).coerceAtLeast(titlebarAllowancePx)
        return Rect(left, top, left + targetWidth, top + targetHeight)
    }

    private fun resolveInstalledDesktopBrowserPackage(): String? {
        val pm = appContext.packageManager
        for (candidate in PREFERRED_BROWSER_PACKAGES) {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                setPackage(candidate)
            }
            if (probe.resolveActivity(pm) != null) {
                return candidate
            }
        }
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        return generic.resolveActivity(pm)?.packageName
    }

    companion object {
        private const val DEFAULT_DESKTOP_START_URL = "https://www.google.com/?igu=1"

        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_CUSTOM_TABS_TITLE_VISIBILITY =
            "android.support.customtabs.extra.TITLE_VISIBILITY"
        private const val EXTRA_CUSTOM_TABS_URLBAR_HIDING =
            "android.support.customtabs.extra.ENABLE_URLBAR_HIDING"
        private const val EXTRA_CUSTOM_TABS_COLOR_SCHEME =
            "android.support.customtabs.extra.COLOR_SCHEME"
        private const val EXTRA_CUSTOM_TABS_TOOLBAR_COLOR =
            "android.support.customtabs.extra.TOOLBAR_COLOR"
        private const val EXTRA_TWA_DISPLAY_MODE =
            "androidx.browser.trusted.extra.DISPLAY_MODE"
        private const val KEY_TWA_DISPLAY_MODE_ID =
            "androidx.browser.trusted.displaymode.KEY_ID"

        private val PREFERRED_BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.vivaldi.browser",
            "org.cromite.cromite",
            "org.mozilla.firefox"
        )
    }
}

/**
 * Material 3 "Desktop Web Apps" launcher strip rendered inside [com.dexxp.desktop.ui.desktop.StartMenuPanel].
 */
@Composable
fun DesktopWebAppsSection(
    webApps: List<DesktopWebAppDefinition>,
    onLaunchWebApp: (DesktopWebAppDefinition) -> Unit,
    onLaunchDesktopBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = DesktopIcons.Globe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Desktop Web Apps (Borderless Freeform)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                onClick = onLaunchDesktopBrowser,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "New Desktop Browser",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(webApps, key = { it.id }) { app ->
                val accent = Color(app.accentColorHex)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onLaunchWebApp(app) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Globe,
                            contentDescription = app.title,
                            tint = accent,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(
                            text = app.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
