package com.dexxp.desktop.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DesktopDarkColorScheme = darkColorScheme(
    primary = SapphirePrimary,
    onPrimary = SapphireOnPrimary,
    primaryContainer = SapphirePrimaryContainer,
    onPrimaryContainer = SapphireOnPrimaryContainer,
    secondary = SlateSecondary,
    onSecondary = SlateOnSecondary,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = SlateOnSecondaryContainer,
    tertiary = CyanTertiary,
    onTertiary = CyanOnTertiary,
    tertiaryContainer = CyanTertiaryContainer,
    onTertiaryContainer = CyanOnTertiaryContainer,
    background = DesktopSurfaceDim,
    onBackground = DesktopOnSurface,
    surface = DesktopSurface,
    onSurface = DesktopOnSurface,
    surfaceVariant = DesktopSurfaceContainerHigh,
    onSurfaceVariant = DesktopOnSurfaceVariant,
    surfaceContainerLowest = DesktopSurfaceDim,
    surfaceContainerLow = DesktopSurfaceContainerLow,
    surfaceContainer = DesktopSurfaceContainer,
    surfaceContainerHigh = DesktopSurfaceContainerHigh,
    surfaceContainerHighest = DesktopSurfaceContainerHighest,
    outline = DesktopOutline,
    outlineVariant = DesktopOutlineVariant
)

/**
 * Material 3 Desktop & Phone Controller Theme.
 * Secondary displays use the calibrated high-contrast [DesktopDarkColorScheme] so external monitor
 * color profiles (sRGB / Rec.709 / HDR) maintain crisp contrast on taskbars and menus.
 */
@Composable
fun DexXPTheme(
    allowDynamicColorOnPhone: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (allowDynamicColorOnPhone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        DesktopDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
