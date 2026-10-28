package com.dexxp.desktop.overview

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.layout.WorkspaceLayoutPresetManager
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.VirtualWorkspace
import com.dexxp.desktop.model.WallpaperPreset
import com.dexxp.desktop.model.WorkspaceLayoutPreset
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.workspace.VirtualWorkspaceManager
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Mission Control / Exposé Bird's-Eye Window Overview Controller (`Super + Tab` / 3-Finger Swipe Up).
 *
 * Scales down and tiles all active Freeform windows across the secondary display into a proportional
 * overview grid, allowing 1-click foreground activation, window closing, and drag-and-drop transfer
 * across the 4 Virtual Workspaces in the top bar.
 */
class MissionControlOverviewController(
    private val sessionRepository: DesktopSessionRepository,
    private val appLauncher: AppLauncher,
    private val virtualWorkspaceManager: VirtualWorkspaceManager
) {
    val isOverviewOpen: StateFlow<Boolean> = sessionRepository.isMissionControlOpen

    fun toggleOverview() {
        sessionRepository.toggleMissionControl()
    }

    fun openOverview() {
        sessionRepository.setMissionControlOpen(true)
    }

    fun closeOverview() {
        sessionRepository.setMissionControlOpen(false)
    }

    fun focusWindowFromOverview(window: RunningWindowRecord, launchContext: Context? = null) {
        sessionRepository.setMissionControlOpen(false)
        sessionRepository.setWindowMinimized(window.packageName, minimized = false)
        appLauncher.launchOrFocusPackage(window.packageName, window.snapMode, launchContext)
    }

    fun closeWindowFromOverview(packageName: String, launchContext: Context? = null) {
        appLauncher.closeWindow(packageName, launchContext)
    }

    fun moveWindowToWorkspace(
        packageName: String,
        targetWorkspaceIndex: Int,
        launchContext: Context? = null
    ): Boolean {
        return virtualWorkspaceManager.moveWindowPackageToWorkspace(
            packageName = packageName,
            targetIndex = targetWorkspaceIndex,
            switchAfterMove = false,
            launchContext = launchContext
        )
    }
}

/**
 * Full-screen Material 3 Mission Control / Exposé Overview UI with a top Virtual Workspaces
 * drag-and-drop target bar and proportional Freeform window preview tiles.
 */
@Composable
fun MissionControlOverviewScreen(
    controller: MissionControlOverviewController,
    runningWindows: List<RunningWindowRecord>,
    workspaces: List<VirtualWorkspace>,
    activeWorkspaceIndex: Int,
    presets: List<WorkspaceLayoutPreset>,
    displayInfo: ExternalDisplayInfo?,
    appRepository: AppRepository,
    presetManager: WorkspaceLayoutPresetManager,
    onSwitchWorkspace: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val workspaceDropZones = remember { mutableStateMapOf<Int, Rect>() }

    var draggingWindow by remember { mutableStateOf<RunningWindowRecord?>(null) }
    var dragPointerRootPos by remember { mutableStateOf(Offset.Zero) }

    val hoveredWorkspaceDropIndex = remember(draggingWindow, dragPointerRootPos) {
        if (draggingWindow == null) {
            null
        } else {
            workspaceDropZones.entries.firstOrNull { (idx, rect) ->
                idx != activeWorkspaceIndex && rect.contains(dragPointerRootPos)
            }?.key
        }
    }

    val monitorW = (displayInfo?.widthPx ?: 1920).coerceAtLeast(640)
    val monitorH = (displayInfo?.heightPx ?: 1080).coerceAtLeast(480)
    val monitorAspect = (monitorW.toFloat() / monitorH.toFloat()).coerceIn(1.3f, 2.4f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B14).copy(alpha = 0.88f))
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismiss()
            }
            .padding(
                start = 28.dp,
                end = 28.dp,
                top = 20.dp,
                bottom = (AppLauncher.TASKBAR_HEIGHT_DP + 16).dp
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Top Virtual Workspaces Bar (Supports 1-Click Switch + Drag-and-Drop Window Transfer)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(22.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Consume inside top bar */ }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = DesktopIcons.OverviewGrid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Mission Control • Drag any window onto a Virtual Desktop above",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            presets.take(2).forEach { preset ->
                                Surface(
                                    onClick = { presetManager.launchPreset(preset, context) },
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = DesktopIcons.LayoutPreset,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

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
                                        contentDescription = "Exit Mission Control",
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Esc",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        workspaces.forEach { ws ->
                            val isActive = ws.index == activeWorkspaceIndex
                            val isDropHovered = hoveredWorkspaceDropIndex == ws.index
                            val gradient = overviewWallpaperColors(ws.wallpaper)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .onGloballyPositioned { coords ->
                                        workspaceDropZones[ws.index] = coords.boundsInRoot()
                                    }
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Brush.linearGradient(gradient))
                                    .border(
                                        width = when {
                                            isDropHovered -> 2.5.dp
                                            isActive -> 2.dp
                                            else -> 1.dp
                                        },
                                        color = when {
                                            isDropHovered -> Color(0xFF6DD58C)
                                            isActive -> MaterialTheme.colorScheme.primary
                                            else -> Color.White.copy(alpha = 0.22f)
                                        },
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable { onSwitchWorkspace(ws.index) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = ws.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isActive) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isDropHovered) {
                                            "Release to move window here"
                                        } else {
                                            "${ws.activeWindowCount} window(s) • ${ws.wallpaper.title}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isDropHovered) {
                                            Color(0xFF6DD58C)
                                        } else {
                                            Color.White.copy(alpha = 0.78f)
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Scaled Bird's-Eye Freeform Windows Grid
            if (runningWindows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = DesktopIcons.WindowFreeform,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = "No Freeform Windows Open on This Workspace",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Press Alt + Space to launch an app or select a Snap Group preset above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val sortedWindows = remember(runningWindows) {
                    runningWindows.sortedByDescending { it.lastActiveEpochMs }
                }
                val columns = when {
                    sortedWindows.size <= 2 -> 2
                    sortedWindows.size <= 6 -> 3
                    else -> 4
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(sortedWindows, key = { it.packageName }) { win ->
                        MissionControlWindowCard(
                            window = win,
                            monitorWidthPx = monitorW,
                            monitorHeightPx = monitorH,
                            monitorAspect = monitorAspect,
                            appRepository = appRepository,
                            isBeingDragged = draggingWindow?.packageName == win.packageName,
                            onActivate = { controller.focusWindowFromOverview(win, context) },
                            onClose = { controller.closeWindowFromOverview(win.packageName, context) },
                            onDragStartAtRoot = { startRoot ->
                                draggingWindow = win
                                dragPointerRootPos = startRoot
                            },
                            onDragMoveBy = { delta ->
                                dragPointerRootPos += delta
                            },
                            onDragEnd = {
                                val targetIdx = hoveredWorkspaceDropIndex
                                if (targetIdx != null) {
                                    controller.moveWindowToWorkspace(win.packageName, targetIdx, context)
                                }
                                draggingWindow = null
                            }
                        )
                    }
                }
            }
        }

        // Floating Drag Ghost Badge following pointer during workspace drag-and-drop
        val dragged = draggingWindow
        if (dragged != null) {
            Surface(
                modifier = Modifier.offset {
                    IntOffset(
                        (dragPointerRootPos.x - 80f).roundToInt(),
                        (dragPointerRootPos.y - 36f).roundToInt()
                    )
                },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 18.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = DesktopIcons.Workspaces,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Move ${dragged.label}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun MissionControlWindowCard(
    window: RunningWindowRecord,
    monitorWidthPx: Int,
    monitorHeightPx: Int,
    monitorAspect: Float,
    appRepository: AppRepository,
    isBeingDragged: Boolean,
    onActivate: () -> Unit,
    onClose: () -> Unit,
    onDragStartAtRoot: (Offset) -> Unit,
    onDragMoveBy: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val iconBitmap = remember(window.packageName) {
        appRepository.findByPackage(window.packageName)?.iconBitmap
    }
    var cardRootOrigin by remember { mutableStateOf(Offset.Zero) }

    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                cardRootOrigin = coords.boundsInRoot().topLeft
            }
            .hoverable(interactionSource)
            .border(
                width = if (isHovered || window.isFocused) 2.dp else 1.dp,
                color = when {
                    isBeingDragged -> Color(0xFF6DD58C)
                    isHovered || window.isFocused -> primaryColor
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(20.dp)
            )
            .pointerInput(window.packageName) {
                detectDragGestures(
                    onDragStart = { localOffset ->
                        onDragStartAtRoot(cardRootOrigin + localOffset)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragMoveBy(dragAmount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                )
            }
            .clickable { onActivate() },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
            alpha = if (isBeingDragged) 0.50f else 0.95f
        ),
        tonalElevation = 8.dp,
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Card Header: App Icon, Title & Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = window.label,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = window.label.take(1).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.width(9.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = window.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (window.isMinimized) {
                            "Minimized • ${window.snapMode.label}"
                        } else {
                            window.snapMode.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Close,
                        contentDescription = "Close Window",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Proportional Scaled Monitor Viewport Preview showing exact window spatial placement
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(monitorAspect)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0E1525))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.12f),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val b = window.bounds
                    val leftFrac = (b.left.toFloat() / monitorWidthPx).coerceIn(0.02f, 0.85f)
                    val topFrac = (b.top.toFloat() / monitorHeightPx).coerceIn(0.02f, 0.82f)
                    val wFrac = (b.width().toFloat() / monitorWidthPx).coerceIn(0.18f, 0.96f)
                    val hFrac = (b.height().toFloat() / monitorHeightPx).coerceIn(0.18f, 0.92f)

                    val rectLeft = size.width * leftFrac
                    val rectTop = size.height * topFrac
                    val rectW = (size.width * wFrac).coerceAtMost(size.width - rectLeft - 4f)
                    val rectH = (size.height * hFrac).coerceAtMost(size.height - rectTop - 8f)

                    // Simulated bottom taskbar line
                    drawLine(
                        color = Color.White.copy(alpha = 0.12f),
                        start = Offset(0f, size.height * 0.93f),
                        end = Offset(size.width, size.height * 0.93f),
                        strokeWidth = 2f
                    )

                    drawRoundRect(
                        color = primaryColor.copy(alpha = if (window.isMinimized) 0.14f else 0.30f),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW.coerceAtLeast(24f), rectH.coerceAtLeast(18f)),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.88f),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW.coerceAtLeast(24f), rectH.coerceAtLeast(18f)),
                        cornerRadius = CornerRadius(10f, 10f),
                        style = Stroke(width = 2.2f)
                    )
                }

                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(11.dp))
                    )
                }
            }
        }
    }
}

private fun overviewWallpaperColors(preset: WallpaperPreset): List<Color> = when (preset) {
    WallpaperPreset.OBSIDIAN_AURORA -> listOf(Color(0xFF0F172A), Color(0xFF1E3A8A))
    WallpaperPreset.SAPPHIRE_HORIZON -> listOf(Color(0xFF0C1933), Color(0xFF2563EB))
    WallpaperPreset.EMERALD_MATRIX -> listOf(Color(0xFF06241F), Color(0xFF0D9488))
    WallpaperPreset.CARBON_MINIMAL -> listOf(Color(0xFF111827), Color(0xFF334155))
}
