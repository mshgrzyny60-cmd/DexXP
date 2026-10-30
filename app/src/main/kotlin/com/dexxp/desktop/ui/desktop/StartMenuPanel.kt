package com.dexxp.desktop.ui.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.browser.DesktopWebAppsSection
import com.dexxp.desktop.model.AppCategory
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopWebAppDefinition
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.ui.icons.DesktopIcons
import java.util.Locale

/**
 * Material 3 Desktop Start Menu featuring a top pill search bar, categorical M3 [FilterChip]s,
 * Desktop Web Apps section (WhatsApp Web, YouTube Desktop, Gmail Web), mouse-hoverable adaptive
 * app grid, keyboard navigation, and Freeform window-snap actions.
 */
@Composable
fun StartMenuPanel(
    apps: List<DesktopAppItem>,
    webApps: List<DesktopWebAppDefinition>,
    pinnedPackages: Set<String>,
    desktopShortcutPackages: Set<String>,
    onSearchFocusChanged: (Boolean) -> Unit,
    onLaunchApp: (DesktopAppItem, WindowSnapMode, Boolean) -> Unit,
    onLaunchWebApp: (DesktopWebAppDefinition) -> Unit,
    onLaunchDesktopBrowser: () -> Unit,
    onTogglePinTaskbar: (String) -> Unit,
    onToggleDesktopShortcut: (String) -> Unit,
    onOpenAppInfo: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(AppCategory.ALL) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
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
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
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
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(50)
                    )
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
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search installed apps, packages, tools...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                filteredApps.firstOrNull()?.let { top ->
                                    onLaunchApp(top, WindowSnapMode.FREEFORM_CASCADED, false)
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
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(28.dp)
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

            // Categorical M3 FilterChip Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(AppCategory.entries, key = { it.name }) { category ->
                    val selected = category == selectedCategory
                    FilterChip(
                        selected = selected,
                        onClick = { selectedCategory = category },
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

            // Desktop Web Apps Section (WhatsApp Web, YouTube Desktop, Gmail Web + Desktop Browser)
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

            // High-Performance Installed Apps Grid
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
                    columns = GridCells.Adaptive(minSize = 94.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.uniqueKey }) { app ->
                        StartMenuAppTile(
                            app = app,
                            isPinnedTaskbar = app.packageName in pinnedPackages,
                            isOnDesktop = app.packageName in desktopShortcutPackages,
                            onLaunch = { mode, multi -> onLaunchApp(app, mode, multi) },
                            onTogglePinTaskbar = { onTogglePinTaskbar(app.packageName) },
                            onToggleDesktopShortcut = { onToggleDesktopShortcut(app.packageName) },
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
                    text = "${filteredApps.size} applications • Long-press or right-click for window snap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
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
    isPinnedTaskbar: Boolean,
    isOnDesktop: Boolean,
    onLaunch: (WindowSnapMode, Boolean) -> Unit,
    onTogglePinTaskbar: () -> Unit,
    onToggleDesktopShortcut: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .hoverable(interactionSource)
                .background(
                    if (isHovered || menuExpanded) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.45f)
                    }
                )
                .desktopSecondaryClick { menuExpanded = true }
                .combinedClickable(
                    onClick = { onLaunch(WindowSnapMode.FREEFORM_CASCADED, false) },
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = app.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        AppLaunchContextMenu(
            expanded = menuExpanded,
            isPinnedTaskbar = isPinnedTaskbar,
            isOnDesktop = isOnDesktop,
            onDismiss = { menuExpanded = false },
            onSelectSnapMode = { snap, multi ->
                menuExpanded = false
                onLaunch(snap, multi)
            },
            onTogglePinTaskbar = {
                menuExpanded = false
                onTogglePinTaskbar()
            },
            onToggleDesktopShortcut = {
                menuExpanded = false
                onToggleDesktopShortcut()
            },
            onOpenAppInfo = {
                menuExpanded = false
                onOpenAppInfo()
            }
        )
    }
}

@Composable
fun AppLaunchContextMenu(
    expanded: Boolean,
    isPinnedTaskbar: Boolean,
    isOnDesktop: Boolean,
    onDismiss: () -> Unit,
    onSelectSnapMode: (WindowSnapMode, Boolean) -> Unit,
    onTogglePinTaskbar: () -> Unit,
    onToggleDesktopShortcut: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Open Freeform Window") },
            leadingIcon = {
                Icon(DesktopIcons.WindowFreeform, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.FREEFORM_CASCADED, false) }
        )
        DropdownMenuItem(
            text = { Text("Snap Left Half") },
            leadingIcon = {
                Icon(DesktopIcons.SplitLeft, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.LEFT_HALF, false) }
        )
        DropdownMenuItem(
            text = { Text("Snap Right Half") },
            leadingIcon = {
                Icon(DesktopIcons.SplitRight, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.RIGHT_HALF, false) }
        )
        DropdownMenuItem(
            text = { Text("Maximize Work Area") },
            leadingIcon = {
                Icon(DesktopIcons.Maximize, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.MAXIMIZED, false) }
        )
        DropdownMenuItem(
            text = { Text("Quadrant Top-Left (25%)") },
            leadingIcon = {
                Icon(DesktopIcons.WindowFreeform, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.TOP_LEFT_QUADRANT, false) }
        )
        DropdownMenuItem(
            text = { Text("Quadrant Top-Right (25%)") },
            leadingIcon = {
                Icon(DesktopIcons.WindowFreeform, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.TOP_RIGHT_QUADRANT, false) }
        )
        DropdownMenuItem(
            text = { Text("Quadrant Bottom-Left (25%)") },
            leadingIcon = {
                Icon(DesktopIcons.WindowFreeform, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.BOTTOM_LEFT_QUADRANT, false) }
        )
        DropdownMenuItem(
            text = { Text("Quadrant Bottom-Right (25%)") },
            leadingIcon = {
                Icon(DesktopIcons.WindowFreeform, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.BOTTOM_RIGHT_QUADRANT, false) }
        )
        DropdownMenuItem(
            text = { Text("Open New Multi-Instance") },
            leadingIcon = {
                Icon(DesktopIcons.Layers, null, modifier = Modifier.size(18.dp))
            },
            onClick = { onSelectSnapMode(WindowSnapMode.FREEFORM_CASCADED, true) }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (isPinnedTaskbar) "Unpin from Taskbar" else "Pin to Taskbar") },
            leadingIcon = {
                Icon(DesktopIcons.Pin, null, modifier = Modifier.size(18.dp))
            },
            onClick = onTogglePinTaskbar
        )
        DropdownMenuItem(
            text = { Text(if (isOnDesktop) "Remove from Desktop" else "Add to Desktop Grid") },
            leadingIcon = {
                Icon(DesktopIcons.DesktopView, null, modifier = Modifier.size(18.dp))
            },
            onClick = onToggleDesktopShortcut
        )
        DropdownMenuItem(
            text = { Text("Application Info") },
            leadingIcon = {
                Icon(DesktopIcons.Sliders, null, modifier = Modifier.size(18.dp))
            },
            onClick = onOpenAppInfo
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
