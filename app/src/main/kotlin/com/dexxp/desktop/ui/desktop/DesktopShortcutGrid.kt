package com.dexxp.desktop.ui.desktop

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.files.DesktopFileManager
import com.dexxp.desktop.files.DesktopFileTile
import com.dexxp.desktop.launcher.DesktopAppShortcut
import com.dexxp.desktop.model.AppWindowLaunchPolicy
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopFileItem
import com.dexxp.desktop.model.SystemReadinessStatus
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.StatusAmber

/**
 * Unified Desktop Grid rendering both pinned app shortcuts and cross-window draggable user files.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopShortcutAndFileGrid(
    shortcuts: List<DesktopAppItem>,
    files: List<DesktopFileItem>,
    fileManager: DesktopFileManager,
    pinnedPackages: Set<String>,
    desktopShortcutPackages: Set<String>,
    appWindowPolicies: Map<String, AppWindowLaunchPolicy> = emptyMap(),
    onLaunchApp: (DesktopAppItem, WindowSnapMode?, Boolean) -> Unit,
    onOpenFile: (DesktopFileItem) -> Unit,
    onShareFile: (DesktopFileItem) -> Unit,
    onTogglePinTaskbar: (String) -> Unit,
    onToggleDesktopShortcut: (String) -> Unit,
    onChangeWindowPolicy: (String, AppWindowLaunchPolicy) -> Unit = { _, _ -> },
    onLaunchShortcut: (DesktopAppShortcut) -> Unit = {},
    onOpenAppInfo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedKey by remember { mutableStateOf<String?>(null) }

    LazyHorizontalGrid(
        rows = GridCells.Adaptive(minSize = 98.dp),
        modifier = modifier.padding(18.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(shortcuts, key = { "app:${it.uniqueKey}" }) { app ->
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()
            val isSelected = selectedKey == app.uniqueKey
            var contextMenuOpen by remember { mutableStateOf(false) }
            val tileScale by animateFloatAsState(
                targetValue = if (isHovered) 1.04f else 1.0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "desktopShortcutScale"
            )
            val policy = appWindowPolicies[app.packageName] ?: AppWindowLaunchPolicy.AUTO

            Box {
                Column(
                    modifier = Modifier
                        .width(92.dp)
                        .scale(tileScale)
                        .clip(RoundedCornerShape(14.dp))
                        .hoverable(interactionSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .background(
                            when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                isHovered -> Color.White.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            width = if (isSelected || isHovered) 1.dp else 0.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            } else if (isHovered) {
                                Color.White.copy(alpha = 0.25f)
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(14.dp)
                        )
                        .desktopSecondaryClick { contextMenuOpen = true }
                        .combinedClickable(
                            onClick = {
                                selectedKey = app.uniqueKey
                                onLaunchApp(app, null, false)
                            },
                            onLongClick = { contextMenuOpen = true }
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (app.iconBitmap != null) {
                        Image(
                            bitmap = app.iconBitmap,
                            contentDescription = app.label,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(11.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
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
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                AppLaunchContextMenu(
                    expanded = contextMenuOpen,
                    packageName = app.packageName,
                    isPinnedTaskbar = app.packageName in pinnedPackages,
                    isOnDesktop = app.packageName in desktopShortcutPackages,
                    currentPolicy = policy,
                    onDismiss = { contextMenuOpen = false },
                    onSelectSnapMode = { snap, multi ->
                        contextMenuOpen = false
                        onLaunchApp(app, snap, multi)
                    },
                    onLaunchShortcut = { shortcut ->
                        contextMenuOpen = false
                        onLaunchShortcut(shortcut)
                    },
                    onTogglePinTaskbar = {
                        contextMenuOpen = false
                        onTogglePinTaskbar(app.packageName)
                    },
                    onToggleDesktopShortcut = {
                        contextMenuOpen = false
                        onToggleDesktopShortcut(app.packageName)
                    },
                    onChangeWindowPolicy = { nextPolicy ->
                        onChangeWindowPolicy(app.packageName, nextPolicy)
                    },
                    onOpenAppInfo = {
                        contextMenuOpen = false
                        onOpenAppInfo(app.packageName)
                    }
                )
            }
        }

        items(files, key = { "file:${it.uri}" }) { fileItem ->
            DesktopFileTile(
                file = fileItem,
                fileManager = fileManager,
                onOpenFile = onOpenFile,
                onShareFile = onShareFile
            )
        }
    }
}

@Composable
internal fun FreeformSetupNoticeBanner(
    readiness: SystemReadinessStatus,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = DesktopIcons.Warning,
                contentDescription = null,
                tint = StatusAmber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Freeform multi-window flag is currently off (${readiness.readyScore}/${readiness.totalChecks} checks active). " +
                    "Open DexXP on your phone to copy the one-time ADB command or enable Developer Options -> Enable freeform windows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
