package com.dexxp.desktop.ui.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.browser.DesktopWebAppsSection
import com.dexxp.desktop.launcher.AppShortcutHelper
import com.dexxp.desktop.launcher.DesktopAppShortcut
import com.dexxp.desktop.model.AppCategory
import com.dexxp.desktop.model.AppWindowLaunchPolicy
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopWebAppDefinition
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.ui.icons.DesktopIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Material 3 Desktop Start Menu featuring a top pill search bar, categorical M3 [FilterChip]s,
 * Desktop Web Apps section (WhatsApp Web, YouTube Desktop, Gmail Web, CapCut Studio),
 * 2D hardware keyboard arrow navigation, mouse hover elevation, and Freeform / Portrait Container controls.
 */
@Composable
fun StartMenuPanel(
    apps: List<DesktopAppItem>,
    webApps: List<DesktopWebAppDefinition>,
    pinnedPackages: Set<String>,
    desktopShortcutPackages: Set<String>,
    appWindowPolicies: Map<String, AppWindowLaunchPolicy> = emptyMap(),
    onSearchFocusChanged: (Boolean) -> Unit,
    onLaunchApp: (DesktopAppItem, WindowSnapMode?, Boolean) -> Unit,
    onLaunchWebApp: (DesktopWebAppDefinition) -> Unit,
    onLaunchDesktopBrowser: () -> Unit,
    onTogglePinTaskbar: (String) -> Unit,
    onToggleDesktopShortcut: (String) -> Unit,
    onChangeWindowPolicy: (String, AppWindowLaunchPolicy) -> Unit = { _, _ -> },
    onLaunchShortcut: (DesktopAppShortcut) -> Unit = {},
    onOpenAppInfo: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(AppCategory.ALL) }
    var highlightedIndex by remember { mutableIntStateOf(0) }
    var isGridNavigationActive by remember { mutableStateOf(false) }
    var gridColumnCount by remember { mutableIntStateOf(5) }
    var contextMenuTargetKey by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        onSearchFocusChanged(true)
        for (attempt in 1..4) {
            kotlinx.coroutines.delay(40L * attempt)
            val focused = runCatching {
                focusRequester.requestFocus()
                true
            }.getOrDefault(false)
            if (focused) break
        }
    }

    DisposableEffect(Unit) {
        onDispose { onSearchFocusChanged(false) }
    }

    val filteredApps by remember(apps, searchQuery, selectedCategory) {
        derivedStateOf {
            val trimmed = searchQuery.trim().lowercase(Locale.getDefault())
            apps.filter { app ->
                val matchesCat = selectedCategory == AppCategory.ALL || app.category == selectedCategory
                val matchesQuery = trimmed.isEmpty() ||
                    app.label.lowercase(Locale.getDefault()).contains(trimmed) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(trimmed)
                matchesCat && matchesQuery
            }
        }
    }

    LaunchedEffect(searchQuery, selectedCategory, filteredApps.size) {
        highlightedIndex = if (filteredApps.isEmpty()) 0 else highlightedIndex.coerceIn(0, filteredApps.lastIndex)
    }

    LaunchedEffect(highlightedIndex) {
        if (filteredApps.isEmpty()) return@LaunchedEffect
        val safeIdx = highlightedIndex.coerceIn(0, filteredApps.lastIndex)
        val visibleItems = gridState.layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return@LaunchedEffect
        val firstVisible = visibleItems.first().index
        val lastVisible = visibleItems.last().index
        if (safeIdx < firstVisible || safeIdx > lastVisible) {
            gridState.animateScrollToItem(safeIdx)
        }
    }

    Surface(
        modifier = modifier
            .width(568.dp)
            .height(548.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            )
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        if (contextMenuTargetKey != null) {
                            contextMenuTargetKey = null
                        } else {
                            onDismiss()
                        }
                        true
                    }

                    Key.Tab -> {
                        val categories = AppCategory.entries
                        val currentIdx = categories.indexOf(selectedCategory).coerceAtLeast(0)
                        val delta = if (event.isShiftPressed) -1 else 1
                        val nextIdx = (currentIdx + delta + categories.size) % categories.size
                        selectedCategory = categories[nextIdx]
                        highlightedIndex = 0
                        true
                    }

                    Key.DirectionDown -> {
                        if (filteredApps.isNotEmpty()) {
                            val cols = gridColumnCount.coerceAtLeast(1)
                            highlightedIndex = if (!isGridNavigationActive && highlightedIndex == 0 && filteredApps.size > cols) {
                                cols.coerceAtMost(filteredApps.lastIndex)
                            } else if (!isGridNavigationActive) {
                                (highlightedIndex + 1).coerceAtMost(filteredApps.lastIndex)
                            } else {
                                (highlightedIndex + cols).coerceAtMost(filteredApps.lastIndex)
                            }
                            isGridNavigationActive = true
                        }
                        true
                    }

                    Key.DirectionUp -> {
                        if (filteredApps.isNotEmpty()) {
                            val cols = gridColumnCount.coerceAtLeast(1)
                            if (highlightedIndex >= cols) {
                                highlightedIndex = (highlightedIndex - cols).coerceAtLeast(0)
                                isGridNavigationActive = true
                            } else {
                                highlightedIndex = 0
                                isGridNavigationActive = false
                                runCatching { focusRequester.requestFocus() }
                            }
                        }
                        true
                    }

                    Key.DirectionRight -> {
                        if (filteredApps.isNotEmpty() && (isGridNavigationActive || searchQuery.isEmpty())) {
                            highlightedIndex = (highlightedIndex + 1).coerceAtMost(filteredApps.lastIndex)
                            isGridNavigationActive = true
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionLeft -> {
                        if (filteredApps.isNotEmpty() && (isGridNavigationActive || searchQuery.isEmpty())) {
                            highlightedIndex = (highlightedIndex - 1).coerceAtLeast(0)
                            isGridNavigationActive = true
                            true
                        } else {
                            false
                        }
                    }

                    Key.Enter,
                    Key.NumPadEnter -> {
                        val target = filteredApps.getOrNull(highlightedIndex)
                        if (target != null) {
                            onLaunchApp(target, null, false)
                            true
                        } else {
                            false
                        }
                    }

                    Key.Menu -> {
                        filteredApps.getOrNull(highlightedIndex)?.let { target ->
                            contextMenuTargetKey = target.uniqueKey
                        }
                        true
                    }

                    else -> false
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shadowElevation = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            // Top Material 3 Pill Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        width = 1.dp,
                        color = if (!isGridNavigationActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(50)
                    )
                    .pointerHoverIcon(PointerIcon.Text)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isGridNavigationActive = false
                        focusRequester.requestFocus()
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DesktopIcons.Search,
                    contentDescription = "Search applications",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isGridNavigationActive = false
                            focusRequester.requestFocus()
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search apps, packages, tools (Arrows to navigate, Enter to open)...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { next ->
                            searchQuery = next
                            highlightedIndex = 0
                            isGridNavigationActive = false
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                filteredApps.getOrNull(highlightedIndex)?.let { selected ->
                                    onLaunchApp(selected, null, false)
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                onSearchFocusChanged(focusState.isFocused)
                            }
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            searchQuery = ""
                            highlightedIndex = 0
                            isGridNavigationActive = false
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Categorical M3 FilterChip Row (`Tab` cycles categories)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(AppCategory.entries, key = { it.name }) { category ->
                    val selected = category == selectedCategory
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedCategory = category
                            highlightedIndex = 0
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        label = {
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Desktop Web Apps Section (WhatsApp Web, YouTube Desktop, Gmail Web, CapCut Studio + Desktop Browser)
            if (searchQuery.isBlank() &&
                (selectedCategory == AppCategory.ALL || selectedCategory == AppCategory.INTERNET_SOCIAL)
            ) {
                DesktopWebAppsSection(
                    webApps = webApps,
                    onLaunchWebApp = onLaunchWebApp,
                    onLaunchDesktopBrowser = onLaunchDesktopBrowser
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            // High-Performance Installed Apps Grid with 2D Arrow-Key Highlighting
            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matching applications found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 94.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { intSize ->
                            val minCellPx = with(density) { 94.dp.toPx() }
                            val spacingPx = with(density) { 8.dp.toPx() }
                            val cols = ((intSize.width + spacingPx) / (minCellPx + spacingPx))
                                .toInt()
                                .coerceAtLeast(1)
                            if (cols != gridColumnCount) {
                                gridColumnCount = cols
                            }
                        },
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filteredApps, key = { _, item -> item.uniqueKey }) { index, app ->
                        val policy = appWindowPolicies[app.packageName] ?: AppWindowLaunchPolicy.AUTO
                        val opensInPortrait = policy == AppWindowLaunchPolicy.PORTRAIT_WINDOW ||
                            (policy == AppWindowLaunchPolicy.AUTO && app.isPortraitLocked)

                        StartMenuAppTile(
                            app = app,
                            isKeyboardSelected = index == highlightedIndex &&
                                (isGridNavigationActive || searchQuery.isNotEmpty()),
                            isPinnedTaskbar = app.packageName in pinnedPackages,
                            isOnDesktop = app.packageName in desktopShortcutPackages,
                            opensInPortraitWindow = opensInPortrait,
                            currentPolicy = policy,
                            forceMenuOpen = contextMenuTargetKey == app.uniqueKey,
                            onMenuOpenChanged = { open ->
                                contextMenuTargetKey = if (open) app.uniqueKey else null
                            },
                            onHovered = {
                                if (highlightedIndex != index) {
                                    highlightedIndex = index
                                }
                            },
                            onLaunch = { mode, multi -> onLaunchApp(app, mode, multi) },
                            onTogglePinTaskbar = { onTogglePinTaskbar(app.packageName) },
                            onToggleDesktopShortcut = { onToggleDesktopShortcut(app.packageName) },
                            onChangeWindowPolicy = { nextPolicy ->
                                onChangeWindowPolicy(app.packageName, nextPolicy)
                            },
                            onLaunchShortcut = onLaunchShortcut,
                            onOpenAppInfo = { onOpenAppInfo(app.packageName) }
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )

            // Footer Metadata & Hints
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filteredApps.size} apps • Arrows + Enter to launch • Right-click for window mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val closeInteraction = remember { MutableInteractionSource() }
                val closeHovered by closeInteraction.collectIsHoveredAsState()
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    color = if (closeHovered) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier
                        .hoverable(closeInteraction)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Close,
                            contentDescription = "Close Start Menu",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Close",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StartMenuAppTile(
    app: DesktopAppItem,
    isKeyboardSelected: Boolean,
    isPinnedTaskbar: Boolean,
    isOnDesktop: Boolean,
    opensInPortraitWindow: Boolean,
    currentPolicy: AppWindowLaunchPolicy,
    forceMenuOpen: Boolean,
    onMenuOpenChanged: (Boolean) -> Unit,
    onHovered: () -> Unit,
    onLaunch: (WindowSnapMode?, Boolean) -> Unit,
    onTogglePinTaskbar: () -> Unit,
    onToggleDesktopShortcut: () -> Unit,
    onChangeWindowPolicy: (AppWindowLaunchPolicy) -> Unit,
    onLaunchShortcut: (DesktopAppShortcut) -> Unit,
    onOpenAppInfo: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var localMenuExpanded by remember { mutableStateOf(false) }
    val menuExpanded = localMenuExpanded || forceMenuOpen

    LaunchedEffect(isHovered) {
        if (isHovered) onHovered()
    }

    val tileScale by animateFloatAsState(
        targetValue = if (isHovered || isKeyboardSelected) 1.04f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "startTileScale"
    )

    val containerBg by animateColorAsState(
        targetValue = when {
            menuExpanded || isKeyboardSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.45f)
        },
        label = "startTileBg"
    )

    val borderColor = when {
        isKeyboardSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        isHovered || menuExpanded -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(tileScale)
                .clip(RoundedCornerShape(14.dp))
                .hoverable(interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .background(containerBg)
                .border(
                    width = if (isKeyboardSelected || isHovered || menuExpanded) 1.dp else 0.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(14.dp)
                )
                .desktopSecondaryClick {
                    localMenuExpanded = true
                    onMenuOpenChanged(true)
                }
                .combinedClickable(
                    onClick = { onLaunch(null, false) },
                    onLongClick = {
                        localMenuExpanded = true
                        onMenuOpenChanged(true)
                    }
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.label.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (opensInPortraitWindow) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = DesktopIcons.PhonePortrait,
                            contentDescription = "Opens in Floating Portrait Window",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = app.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isKeyboardSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        AppLaunchContextMenu(
            expanded = menuExpanded,
            packageName = app.packageName,
            isPinnedTaskbar = isPinnedTaskbar,
            isOnDesktop = isOnDesktop,
            currentPolicy = currentPolicy,
            onDismiss = {
                localMenuExpanded = false
                onMenuOpenChanged(false)
            },
            onSelectSnapMode = { snap, multi ->
                localMenuExpanded = false
                onMenuOpenChanged(false)
                onLaunch(snap, multi)
            },
            onLaunchShortcut = { shortcut ->
                localMenuExpanded = false
                onMenuOpenChanged(false)
                onLaunchShortcut(shortcut)
            },
            onTogglePinTaskbar = {
                localMenuExpanded = false
                onMenuOpenChanged(false)
                onTogglePinTaskbar()
            },
            onToggleDesktopShortcut = {
                localMenuExpanded = false
                onMenuOpenChanged(false)
                onToggleDesktopShortcut()
            },
            onChangeWindowPolicy = { nextPolicy ->
                onChangeWindowPolicy(nextPolicy)
            },
            onOpenAppInfo = {
                localMenuExpanded = false
                onMenuOpenChanged(false)
                onOpenAppInfo()
            }
        )
    }
}

@Composable
fun AppLaunchContextMenu(
    expanded: Boolean,
    packageName: String? = null,
    isPinnedTaskbar: Boolean,
    isOnDesktop: Boolean,
    currentPolicy: AppWindowLaunchPolicy = AppWindowLaunchPolicy.AUTO,
    onDismiss: () -> Unit,
    onSelectSnapMode: (WindowSnapMode?, Boolean) -> Unit,
    onLaunchShortcut: ((DesktopAppShortcut) -> Unit)? = null,
    onTogglePinTaskbar: () -> Unit,
    onToggleDesktopShortcut: () -> Unit,
    onChangeWindowPolicy: ((AppWindowLaunchPolicy) -> Unit)? = null,
    onOpenAppInfo: () -> Unit
) {
    val context = LocalContext.current
    var appShortcuts by remember(packageName) { mutableStateOf<List<DesktopAppShortcut>>(emptyList()) }

    LaunchedEffect(expanded, packageName) {
        if (expanded && !packageName.isNullOrBlank()) {
            appShortcuts = withContext(Dispatchers.IO) {
                AppShortcutHelper.getShortcutsForPackage(context, packageName)
            }
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Open", fontWeight = FontWeight.SemiBold) },
            leadingIcon = {
                Icon(DesktopIcons.AppsGrid, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(null, false) }
        )
        if (appShortcuts.isNotEmpty() && onLaunchShortcut != null) {
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
                    onClick = { onLaunchShortcut(shortcut) }
                )
            }
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(if (isPinnedTaskbar) "Unpin from Taskbar" else "Pin to Taskbar") },
            leadingIcon = {
                Icon(DesktopIcons.Pin, null, modifier = Modifier.size(18.dp))
            },
            onClick = onTogglePinTaskbar
        )
        DropdownMenuItem(
            text = { Text("App Info") },
            leadingIcon = {
                Icon(DesktopIcons.Info, null, modifier = Modifier.size(18.dp))
            },
            onClick = onOpenAppInfo
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Open in Portrait Window (9:19)") },
            leadingIcon = {
                Icon(DesktopIcons.PhonePortrait, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.PORTRAIT_CONTAINER, false) }
        )
        DropdownMenuItem(
            text = { Text("Open in Landscape Container (16:10)") },
            leadingIcon = {
                Icon(DesktopIcons.AspectLandscape, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.LANDSCAPE_CONTAINER, false) }
        )
        DropdownMenuItem(
            text = { Text("Open Resizable Freeform") },
            leadingIcon = {
                Icon(DesktopIcons.WindowFreeform, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.FREEFORM_CASCADED, false) }
        )
        DropdownMenuItem(
            text = { Text("Maximize Widescreen") },
            leadingIcon = {
                Icon(DesktopIcons.Maximize, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.MAXIMIZED, false) }
        )
        DropdownMenuItem(
            text = { Text("Snap Left Half (50%)") },
            leadingIcon = {
                Icon(DesktopIcons.SplitLeft, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.LEFT_HALF, false) }
        )
        DropdownMenuItem(
            text = { Text("Snap Right Half (50%)") },
            leadingIcon = {
                Icon(DesktopIcons.SplitRight, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.RIGHT_HALF, false) }
        )
        DropdownMenuItem(
            text = { Text("Open New Multi-Instance") },
            leadingIcon = {
                Icon(DesktopIcons.Layers, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.FREEFORM_CASCADED, true) }
        )
        HorizontalDivider()
        if (onChangeWindowPolicy != null) {
            DropdownMenuItem(
                text = { Text("Default Mode: ${currentPolicy.label}") },
                leadingIcon = {
                    Icon(DesktopIcons.Sliders, null, modifier = Modifier.size(18.dp))
                },
                onClick = {
                    val entries = AppWindowLaunchPolicy.entries
                    val next = entries[(entries.indexOf(currentPolicy) + 1) % entries.size]
                    onChangeWindowPolicy(next)
                }
            )
        }
        DropdownMenuItem(
            text = { Text(if (isOnDesktop) "Remove from Desktop" else "Add to Desktop Grid") },
            leadingIcon = {
                Icon(DesktopIcons.DesktopView, null, modifier = Modifier.size(18.dp))
            },
            onClick = onToggleDesktopShortcut
        )
    }
}

/**
 * Intercepts hardware mouse right-click and [com.dexxp.desktop.bridge.PointerFocusBridge]
 * synthetic `BUTTON_SECONDARY` presses before primary click handlers fire.
 */
fun Modifier.desktopSecondaryClick(onSecondaryClick: () -> Unit): Modifier =
    this.pointerInput(onSecondaryClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    onSecondaryClick()
                }
            }
        }
    }
