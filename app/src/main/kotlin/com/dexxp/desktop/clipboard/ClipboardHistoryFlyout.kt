package com.dexxp.desktop.clipboard

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.model.ClipboardHistoryEntry
import com.dexxp.desktop.model.ClipboardItemKind
import com.dexxp.desktop.ui.icons.DesktopIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Material 3 `Win + V` Clipboard History Popup Overlay displaying up to 20 recent clips
 * (text snippets, links, and image URIs) with timestamps, pinning, and 1-click auto-paste.
 */
@Composable
fun ClipboardHistoryFlyout(
    entries: List<ClipboardHistoryEntry>,
    onSyncClipboard: () -> Unit,
    onPasteEntry: (ClipboardHistoryEntry) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearUnpinned: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf<ClipboardItemKind?>(null) }

    LaunchedEffect(Unit) {
        onSyncClipboard()
    }

    val filteredEntries by remember(entries, selectedFilter) {
        derivedStateOf {
            if (selectedFilter == null) entries else entries.filter { it.kind == selectedFilter }
        }
    }

    Surface(
        modifier = modifier
            .width(420.dp)
            .heightIn(min = 260.dp, max = 510.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            )
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 10.dp,
        shadowElevation = 20.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DesktopIcons.Clipboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Clipboard History",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Win + V • Click any item for 1-click paste",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (entries.any { !it.isPinned }) {
                    Surface(
                        onClick = onClearUnpinned,
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = "Clear all",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("All (${entries.size})", style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(50)
                )
                ClipboardItemKind.entries.forEach { kind ->
                    FilterChip(
                        selected = selectedFilter == kind,
                        onClick = { selectedFilter = if (selectedFilter == kind) null else kind },
                        label = { Text(kind.badgeLabel, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No copied snippets yet • Copy text, links, or images to populate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        ClipboardSnippetCard(
                            entry = entry,
                            onPaste = { onPasteEntry(entry) },
                            onTogglePin = { onTogglePin(entry.id) },
                            onDelete = { onDeleteEntry(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardSnippetCard(
    entry: ClipboardHistoryEntry,
    onPaste: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val kindIcon = when (entry.kind) {
        ClipboardItemKind.TEXT -> DesktopIcons.Clipboard
        ClipboardItemKind.LINK -> DesktopIcons.Link
        ClipboardItemKind.IMAGE_URI -> DesktopIcons.ImageFile
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = if (entry.isPinned) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                },
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onPaste() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = kindIcon,
                contentDescription = entry.kind.badgeLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.kind.badgeLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = timeFormatter.format(Date(entry.copiedAtEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = DesktopIcons.Pin,
                    contentDescription = if (entry.isPinned) "Unpin" else "Pin",
                    tint = if (entry.isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(13.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = DesktopIcons.Close,
                    contentDescription = "Remove clip",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Text(
            text = entry.previewText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
