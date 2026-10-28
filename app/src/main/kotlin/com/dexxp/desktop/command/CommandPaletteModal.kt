package com.dexxp.desktop.command

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.model.CommandPaletteItem
import com.dexxp.desktop.model.CommandResultKind
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopFileItem
import com.dexxp.desktop.model.WorkspaceLayoutPreset
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.StatusGreen

/**
 * Floating Material 3 Universal Command Palette & Spotlight Modal (`Alt + Space` / `Cmd + Space`).
 *
 * Supports:
 * - Instant 2-keystroke fuzzy app & recent file launching;
 * - Live inline calculator (`450 * 1.17`, `sqrt(144)`) with 1-click Copy;
 * - Saved Snap Group launching & built-in desktop commands;
 * - Direct Web Search dispatch to the default desktop browser on `Enter`.
 */
@Composable
fun CommandPaletteModal(
    controller: CommandPaletteController,
    apps: List<DesktopAppItem>,
    files: List<DesktopFileItem>,
    presets: List<WorkspaceLayoutPreset>,
    pinnedPackages: Set<String>,
    onInputFocusChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var copiedMathValue by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { onInputFocusChanged(false) }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val results by remember(query, apps, files, presets, pinnedPackages) {
        derivedStateOf {
            controller.buildRankedResults(
                rawQuery = query,
                apps = apps,
                files = files,
                presets = presets,
                pinnedPackages = pinnedPackages
            )
        }
    }

    LaunchedEffect(results.size, query) {
        selectedIndex = 0
        if (copiedMathValue != null && results.firstOrNull()?.kind != CommandResultKind.CALCULATOR) {
            copiedMathValue = null
        }
    }

    fun triggerSelectedOrEnter(forceWebSearch: Boolean = false) {
        if (forceWebSearch && query.isNotBlank()) {
            controller.dispatchWebSearch(query, context)
            return
        }
        val activeItem = results.getOrNull(selectedIndex) ?: results.firstOrNull()
        if (activeItem?.kind == CommandResultKind.CALCULATOR) {
            activeItem.mathValue?.let { valStr ->
                controller.copyTextToClipboard(valStr)
                copiedMathValue = valStr
            }
        } else {
            controller.executeItem(activeItem, query, context)
        }
    }

    Surface(
        modifier = modifier
            .width(636.dp)
            .heightIn(min = 140.dp, max = 510.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                shape = RoundedCornerShape(26.dp)
            )
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.DirectionDown -> {
                        if (results.isNotEmpty()) {
                            selectedIndex = (selectedIndex + 1) % results.size
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (results.isNotEmpty()) {
                            selectedIndex = (selectedIndex - 1 + results.size) % results.size
                        }
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        triggerSelectedOrEnter(forceWebSearch = ev.isCtrlPressed)
                        true
                    }
                    else -> false
                }
            },
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.98f),
        tonalElevation = 14.dp,
        shadowElevation = 28.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top Spotlight Pill Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DesktopIcons.Search,
                    contentDescription = "Command Palette Search",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Type app, recent file, math (450 * 1.17, sqrt(144)), or web query...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { triggerSelectedOrEnter(forceWebSearch = false) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { onInputFocusChanged(it.isFocused) }
                    )
                }

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = "" },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Close,
                            contentDescription = "Clear Query",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            text = "Alt + Space",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Ranked Results Stream
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                itemsIndexed(results, key = { _, item -> item.id }) { index, item ->
                    CommandPaletteResultRow(
                        item = item,
                        isSelected = index == selectedIndex,
                        copiedMathValue = copiedMathValue,
                        onSelect = {
                            selectedIndex = index
                            if (item.kind == CommandResultKind.CALCULATOR) {
                                item.mathValue?.let { v ->
                                    controller.copyTextToClipboard(v)
                                    copiedMathValue = v
                                }
                            } else {
                                controller.executeItem(item, query, context)
                            }
                        }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Up/Down Navigate • Enter Launch / Copy • Ctrl+Enter Web Search • Esc Close",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (query.isNotBlank()) {
                    Surface(
                        onClick = { controller.dispatchWebSearch(query, context) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = DesktopIcons.Globe,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "Web Search",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandPaletteResultRow(
    item: CommandPaletteItem,
    isSelected: Boolean,
    copiedMathValue: String?,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = isSelected || isHovered

    val isMath = item.kind == CommandResultKind.CALCULATOR
    val mathWasCopied = isMath && copiedMathValue != null && copiedMathValue == item.mathValue

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .hoverable(interactionSource)
            .background(
                when {
                    isMath -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                    highlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f)
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f)
                }
            )
            .border(
                width = if (highlighted || isMath) 1.dp else 0.dp,
                color = if (isMath || isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading Icon or App Bitmap
        val appBitmap = item.appItem?.iconBitmap
        if (appBitmap != null) {
            Image(
                bitmap = appBitmap,
                contentDescription = item.title,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
        } else {
            val icon = when (item.kind) {
                CommandResultKind.CALCULATOR -> DesktopIcons.Calculate
                CommandResultKind.APP -> DesktopIcons.AppsGrid
                CommandResultKind.LAYOUT_PRESET -> DesktopIcons.LayoutPreset
                CommandResultKind.SYSTEM_ACTION -> DesktopIcons.Terminal
                CommandResultKind.FILE -> {
                    if (item.fileItem?.isImage == true) DesktopIcons.ImageFile else DesktopIcons.FileGeneric
                }
                CommandResultKind.WEB_SEARCH -> DesktopIcons.Globe
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (isMath) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isMath) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = if (isMath) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontFamily = if (isMath) FontFamily.Monospace else null,
                fontWeight = FontWeight.SemiBold,
                color = if (isMath) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMath) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(10.dp))

        if (isMath) {
            Surface(
                onClick = onSelect,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (mathWasCopied) DesktopIcons.CheckCircle else DesktopIcons.Copy,
                        contentDescription = "Copy Result",
                        tint = if (mathWasCopied) StatusGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (mathWasCopied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (mathWasCopied) StatusGreen else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = item.kind.badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
        }
    }
}
