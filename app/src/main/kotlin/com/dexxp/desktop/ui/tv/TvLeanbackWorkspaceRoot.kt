package com.dexxp.desktop.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.audio.AudioRouteManager
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.model.AppCategory
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.DisplayPerformanceProfile
import com.dexxp.desktop.model.TvNavigationAction
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 10-Foot Leanback TV Interface designed for couch & remote control navigation on televisions.
 * Renders large high-contrast responsive cards with luminous focus borders and launches
 * all apps in native 100% fullscreen (zero floating window overlays).
 */
@Composable
fun TvLeanbackWorkspaceRoot(
    sessionRepository: DesktopSessionRepository,
    appRepository: AppRepository,
    appLauncher: AppLauncher,
    audioRouteManager: AudioRouteManager
) {
    val installedApps by appRepository.apps.collectAsState()
    val pinnedPackages by sessionRepository.pinnedPackages.collectAsState()
    val activeDisplay by sessionRepository.activeDisplay.collectAsState()
    val perfProfile by sessionRepository.performanceProfile.collectAsState()
    val audioRoutes by audioRouteManager.routes.collectAsState()
    val activeAudioRoute = audioRoutes.firstOrNull { it.isSelected }

    val context = LocalContext.current
    var focusedAppIndex by remember { mutableIntStateOf(0) }
    var isCategoryRailFocused by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val numColumns = 5

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = remember {
        listOf("🌟 Favorites & Recents", "🎬 Media & Streaming", "🎮 Games & Tools", "📱 All Applications")
    }

    // Filter apps based on category with abundant apps to browse
    val displayedApps by remember(installedApps, pinnedPackages, selectedCategoryIndex) {
        derivedStateOf {
            when (selectedCategoryIndex) {
                0 -> {
                    val pinned = installedApps.filter { it.packageName in pinnedPackages }
                    val others = installedApps.filterNot { it.packageName in pinnedPackages }
                    (pinned + others.take(25)).ifEmpty { installedApps.take(25) }
                }
                1 -> {
                    val media = installedApps.filter { it.category == AppCategory.MEDIA }
                    if (media.isNotEmpty()) media else installedApps.filter { app ->
                        app.packageName.contains("youtube", ignoreCase = true) ||
                            app.packageName.contains("netflix", ignoreCase = true) ||
                            app.packageName.contains("spotify", ignoreCase = true) ||
                            app.packageName.contains("vlc", ignoreCase = true) ||
                            app.packageName.contains("media", ignoreCase = true)
                    }.ifEmpty { installedApps.take(25) }
                }
                2 -> {
                    val tools = installedApps.filter {
                        it.category == AppCategory.GAMES ||
                            it.category == AppCategory.SYSTEM_UTILITIES
                    }
                    if (tools.isNotEmpty()) tools else installedApps.take(25)
                }
                else -> installedApps
            }
        }
    }

    LaunchedEffect(displayedApps) {
        if (focusedAppIndex >= displayedApps.size) {
            focusedAppIndex = (displayedApps.size - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(Unit) {
        sessionRepository.tvNavigationEvents.collect { action ->
            val appCount = displayedApps.size
            if (isCategoryRailFocused) {
                when (action) {
                    TvNavigationAction.LEFT -> {
                        selectedCategoryIndex = (selectedCategoryIndex - 1).coerceAtLeast(0)
                        focusedAppIndex = 0
                        gridState.animateScrollToItem(0)
                    }
                    TvNavigationAction.RIGHT -> {
                        selectedCategoryIndex = (selectedCategoryIndex + 1).coerceAtMost(categories.size - 1)
                        focusedAppIndex = 0
                        gridState.animateScrollToItem(0)
                    }
                    TvNavigationAction.DOWN -> {
                        isCategoryRailFocused = false
                        focusedAppIndex = 0
                    }
                    TvNavigationAction.SELECT -> {
                        isCategoryRailFocused = false
                        focusedAppIndex = 0
                    }
                    TvNavigationAction.UP, TvNavigationAction.BACK -> {
                        // Keep focus in category rail
                    }
                }
            } else {
                if (appCount == 0) {
                    isCategoryRailFocused = true
                    return@collect
                }
                when (action) {
                    TvNavigationAction.LEFT -> {
                        if (focusedAppIndex > 0) {
                            focusedAppIndex--
                            gridState.animateScrollToItem(focusedAppIndex)
                        }
                    }
                    TvNavigationAction.RIGHT -> {
                        if (focusedAppIndex < appCount - 1) {
                            focusedAppIndex++
                            gridState.animateScrollToItem(focusedAppIndex)
                        }
                    }
                    TvNavigationAction.UP -> {
                        if (focusedAppIndex < numColumns) {
                            isCategoryRailFocused = true
                        } else {
                            focusedAppIndex = (focusedAppIndex - numColumns).coerceAtLeast(0)
                            gridState.animateScrollToItem(focusedAppIndex)
                        }
                    }
                    TvNavigationAction.DOWN -> {
                        if (focusedAppIndex + numColumns < appCount) {
                            focusedAppIndex += numColumns
                            gridState.animateScrollToItem(focusedAppIndex)
                        } else if (focusedAppIndex < appCount - 1) {
                            focusedAppIndex = appCount - 1
                            gridState.animateScrollToItem(focusedAppIndex)
                        }
                    }
                    TvNavigationAction.SELECT -> {
                        if (focusedAppIndex in displayedApps.indices) {
                            appLauncher.launchApp(displayedApps[focusedAppIndex], launchContext = context)
                        }
                    }
                    TvNavigationAction.BACK -> {
                        isCategoryRailFocused = true
                    }
                }
            }
        }
    }

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
            .padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // TV Top Bar
            TvTopHeaderBar(
                activeDisplayName = activeDisplay?.name ?: "Television",
                perfProfile = perfProfile,
                audioRouteName = activeAudioRoute?.name ?: "Audio",
                onSwitchToDesktop = {
                    sessionRepository.setEnvironmentMode(DesktopEnvironmentMode.DESKTOP_FREEFORM)
                },
                onCyclePerfProfile = {
                    val nextProfile = when (perfProfile) {
                        DisplayPerformanceProfile.ULTRA_FAST_1080P -> DisplayPerformanceProfile.SMOOTH_720P
                        DisplayPerformanceProfile.SMOOTH_720P -> DisplayPerformanceProfile.NATIVE_MAX
                        DisplayPerformanceProfile.NATIVE_MAX -> DisplayPerformanceProfile.ULTRA_FAST_1080P
                    }
                    sessionRepository.setPerformanceProfile(nextProfile)
                },
                onCycleAudioRoute = {
                    audioRouteManager.cycleNextOutputRoute()
                }
            )

            // Category Filter Rail
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(categories.size) { index ->
                    val isSelected = selectedCategoryIndex == index
                    val isRailActive = isCategoryRailFocused && isSelected
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()

                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedCategoryIndex = index
                            isCategoryRailFocused = false
                            focusedAppIndex = 0
                        },
                        label = {
                            Text(
                                text = categories[index],
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                            )
                        },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                        ),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .scale(if (isRailActive || isFocused) 1.08f else 1f)
                            .border(
                                width = if (isRailActive) 3.dp else if (isFocused) 2.dp else 0.dp,
                                color = if (isRailActive || isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
            }

            // Big 10-Foot App Grid
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(numColumns),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(
                    count = displayedApps.size,
                    key = { idx -> displayedApps[idx].uniqueKey }
                ) { idx ->
                    val app = displayedApps[idx]
                    TvAppCard(
                        app = app,
                        isExternalFocused = (!isCategoryRailFocused && idx == focusedAppIndex),
                        onLaunch = {
                            isCategoryRailFocused = false
                            focusedAppIndex = idx
                            appLauncher.launchApp(app, launchContext = context)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Modern 10-foot TV App Card featuring luminous focus borders and spring scale animations
 * for couch/D-Pad navigation.
 */
@Composable
private fun TvAppCard(
    app: DesktopAppItem,
    isExternalFocused: Boolean = false,
    onLaunch: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isHighlighted = isFocused || isHovered || isExternalFocused

    val scale by animateFloatAsState(
        targetValue = if (isHighlighted) 1.08f else 1.0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "tvCardScale"
    )

    Surface(
        onClick = onLaunch,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(20.dp),
        color = if (isHighlighted) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.75f)
        },
        tonalElevation = if (isHighlighted) 10.dp else 1.dp,
        modifier = Modifier
            .scale(scale)
            .height(160.dp)
            .focusable(interactionSource = interactionSource)
            .border(
                width = if (isHighlighted) 3.dp else 1.dp,
                color = if (isHighlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                },
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (app.iconBitmap != null) {
                Image(
                    bitmap = app.iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier.size(64.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                color = if (isHighlighted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Top bar in TV mode with branding, clock, audio routing, performance badge, and mode toggle.
 */
@Composable
private fun TvTopHeaderBar(
    activeDisplayName: String,
    perfProfile: DisplayPerformanceProfile,
    audioRouteName: String,
    onSwitchToDesktop: () -> Unit,
    onCyclePerfProfile: () -> Unit,
    onCycleAudioRoute: () -> Unit
) {
    val currentTime = remember {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date())
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand & TV Mode Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = DesktopIcons.Tv,
                        contentDescription = "DexXP TV Mode",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "DexXP TV",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "LEANBACK",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = activeDisplayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Action Controls & Status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Performance Profile Toggle Chip
            Surface(
                onClick = onCyclePerfProfile,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Sliders,
                        contentDescription = "Performance Profile",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = perfProfile.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Audio Route Switcher Chip
            Surface(
                onClick = onCycleAudioRoute,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.VolumeUp,
                        contentDescription = "Audio Output Route",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = audioRouteName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Switch to Desktop Mode (Windows) Button
            Surface(
                onClick = onSwitchToDesktop,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.DesktopView,
                        contentDescription = "Desktop Mode",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Switch to Desktop Mode",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // TV Clock
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = currentTime,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}
