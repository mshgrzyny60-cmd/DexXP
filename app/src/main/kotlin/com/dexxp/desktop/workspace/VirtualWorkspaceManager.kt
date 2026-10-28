package com.dexxp.desktop.workspace

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.layout.WorkspaceLayoutPresetsSection
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.VirtualWorkspace
import com.dexxp.desktop.model.WallpaperPreset
import com.dexxp.desktop.model.WorkspaceLayoutPreset
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Multi-Desktop / Virtual Workspaces State Machine (`Win + Ctrl + Left / Right`).
 *
 * Manages 4 isolated desktop workspaces (`Work`, `Media`, `Dev & Web`, `Focus`), each retaining
 * its own active Freeform window stack and desktop wallpaper preset. Switching workspaces stashes
 * inactive windows behind `SecondaryDesktopActivity` and restores the incoming workspace's windows.
 */
class VirtualWorkspaceManager(
    context: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appLauncher: AppLauncher
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _workspaces = MutableStateFlow(buildInitialWorkspaces())
    val workspaces: StateFlow<List<VirtualWorkspace>> = _workspaces.asStateFlow()

    private val _activeWorkspaceIndex = MutableStateFlow(0)
    val activeWorkspaceIndex: StateFlow<Int> = _activeWorkspaceIndex.asStateFlow()

    @Volatile
    private var isPerformingWorkspaceSwitch = false

    init {
        // Keep current workspace's window stack & wallpaper continuously mirrored
        scope.launch {
            sessionRepository.runningWindows.collect { liveWindows ->
                if (!isPerformingWorkspaceSwitch) {
                    val idx = _activeWorkspaceIndex.value
                    _workspaces.update { list ->
                        list.map { ws ->
                            if (ws.index == idx) ws.copy(windows = liveWindows) else ws
                        }
                    }
                }
            }
        }
        scope.launch {
            sessionRepository.wallpaperPreset.collect { liveWallpaper ->
                if (!isPerformingWorkspaceSwitch) {
                    val idx = _activeWorkspaceIndex.value
                    prefs.edit().putString("$KEY_WS_WALLPAPER_PREFIX$idx", liveWallpaper.name).apply()
                    _workspaces.update { list ->
                        list.map { ws ->
                            if (ws.index == idx) ws.copy(wallpaper = liveWallpaper) else ws
                        }
                    }
                }
            }
        }
    }

    fun switchPreviousWorkspace(launchContext: Context? = null) {
        val next = computeWrappedWorkspaceIndex(_activeWorkspaceIndex.value, delta = -1)
        switchToWorkspace(next, launchContext)
    }

    fun switchNextWorkspace(launchContext: Context? = null) {
        val next = computeWrappedWorkspaceIndex(_activeWorkspaceIndex.value, delta = 1)
        switchToWorkspace(next, launchContext)
    }

    fun switchToWorkspace(targetIndex: Int, launchContext: Context? = null) {
        val clamped = targetIndex.coerceIn(0, WORKSPACE_COUNT - 1)
        val currentIdx = _activeWorkspaceIndex.value
        if (clamped == currentIdx) {
            sessionRepository.dismissFlyouts()
            return
        }

        isPerformingWorkspaceSwitch = true
        try {
            // 1. Snapshot outgoing workspace state
            val outgoingWindows = sessionRepository.runningWindows.value
            val outgoingWallpaper = sessionRepository.wallpaperPreset.value
            _workspaces.update { list ->
                list.map { ws ->
                    if (ws.index == currentIdx) {
                        ws.copy(windows = outgoingWindows, wallpaper = outgoingWallpaper)
                    } else {
                        ws
                    }
                }
            }

            // 2. Activate target workspace state
            val incoming = _workspaces.value[clamped]
            _activeWorkspaceIndex.value = clamped
            sessionRepository.dismissFlyouts()
            sessionRepository.setWallpaperPreset(incoming.wallpaper)
            sessionRepository.setRunningWindowsSnapshot(incoming.windows)

            // 3. Hide inactive windows & restore incoming workspace Freeform stack
            appLauncher.restoreWorkspaceWindows(incoming.windows, launchContext)
        } finally {
            isPerformingWorkspaceSwitch = false
        }
    }

    /**
     * Moves the currently focused Freeform window from the active workspace into [targetIndex]
     * and switches to that workspace.
     */
    fun moveFocusedWindowToWorkspace(targetIndex: Int, launchContext: Context? = null): Boolean {
        val focused: RunningWindowRecord = sessionRepository.focusedOrMostRecentWindow() ?: return false
        return moveWindowPackageToWorkspace(
            packageName = focused.packageName,
            targetIndex = targetIndex,
            switchAfterMove = true,
            launchContext = launchContext
        )
    }

    /**
     * Moves any active window matching [packageName] from the current workspace into [targetIndex].
     * Used by Mission Control / Exposé top-bar drag-and-drop as well as the Workspace Switcher.
     */
    fun moveWindowPackageToWorkspace(
        packageName: String,
        targetIndex: Int,
        switchAfterMove: Boolean = false,
        launchContext: Context? = null
    ): Boolean {
        val clamped = targetIndex.coerceIn(0, WORKSPACE_COUNT - 1)
        val currentIdx = _activeWorkspaceIndex.value
        if (clamped == currentIdx) return false

        val record = sessionRepository.runningWindows.value.firstOrNull {
            it.packageName == packageName
        } ?: return false

        sessionRepository.removeWindowRecord(record.packageName)

        _workspaces.update { list ->
            list.map { ws ->
                when (ws.index) {
                    currentIdx -> ws.copy(windows = ws.windows.filterNot { it.packageName == record.packageName })
                    clamped -> ws.copy(
                        windows = ws.windows.filterNot { it.packageName == record.packageName } +
                            record.copy(isFocused = true, isMinimized = false)
                    )
                    else -> ws
                }
            }
        }

        if (switchAfterMove) {
            switchToWorkspace(clamped, launchContext)
        } else {
            val remaining = sessionRepository.runningWindows.value
            appLauncher.restoreWorkspaceWindows(remaining, launchContext)
        }
        return true
    }

    private fun buildInitialWorkspaces(): List<VirtualWorkspace> {
        val defaults = listOf(
            Triple("Desktop 1 • Work", "1 • Work", sessionRepository.wallpaperPreset.value),
            Triple("Desktop 2 • Media", "2 • Media", WallpaperPreset.SAPPHIRE_HORIZON),
            Triple("Desktop 3 • Dev & Web", "3 • Dev", WallpaperPreset.EMERALD_MATRIX),
            Triple("Desktop 4 • Focus", "4 • Focus", WallpaperPreset.CARBON_MINIMAL)
        )
        return defaults.mapIndexed { idx, (fullName, shortName, fallbackWall) ->
            val savedWallName = prefs.getString("$KEY_WS_WALLPAPER_PREFIX$idx", null)
            val wall = WallpaperPreset.entries.firstOrNull { it.name == savedWallName } ?: fallbackWall
            VirtualWorkspace(
                index = idx,
                name = fullName,
                shortLabel = shortName,
                wallpaper = wall,
                windows = emptyList()
            )
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        const val WORKSPACE_COUNT = 4
        private const val PREFS_NAME = "dexxp_virtual_workspaces"
        private const val KEY_WS_WALLPAPER_PREFIX = "ws_wallpaper_"

        fun computeWrappedWorkspaceIndex(current: Int, delta: Int, total: Int = WORKSPACE_COUNT): Int {
            val safeTotal = total.coerceAtLeast(1)
            return ((current + delta) % safeTotal + safeTotal) % safeTotal
        }
    }
}

/**
 * Compact Taskbar Workspace Indicator Chip displaying the active virtual desktop (`1 • Work`, etc.).
 */
@Composable
fun TaskbarWorkspaceChip(
    activeWorkspace: VirtualWorkspace,
    isSwitcherOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSwitcherOpen) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.height(38.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = DesktopIcons.Workspaces,
                contentDescription = "Virtual Workspaces",
                tint = if (isSwitcherOpen) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = activeWorkspace.shortLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSwitcherOpen) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/**
 * Material 3 Virtual Workspaces & Saved Snap Groups Flyout allowing 1-click desktop switching
 * (`Super + Ctrl + Arrows`), moving active Freeform windows across workspaces, and saving/launching
 * multi-window layout presets.
 */
@Composable
fun WorkspaceSwitcherFlyout(
    workspaces: List<VirtualWorkspace>,
    activeIndex: Int,
    hasActiveWindowToMove: Boolean,
    presets: List<WorkspaceLayoutPreset>,
    onSelectWorkspace: (Int) -> Unit,
    onMoveActiveWindowTo: (Int) -> Unit,
    onLaunchPreset: (WorkspaceLayoutPreset) -> Unit,
    onSaveCurrentPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onPresetInputFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(468.dp)
            .heightIn(max = 560.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 10.dp,
        shadowElevation = 20.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DesktopIcons.Workspaces,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Virtual Workspaces",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Shortcut: Super + Ctrl + Left / Right Arrow",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                workspaces.forEach { ws ->
                    val isSelected = ws.index == activeIndex
                    val gradientColors = wallpaperPreviewGradient(ws.wallpaper)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onSelectWorkspace(ws.index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 48.dp, height = 32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(gradientColors))
                                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${ws.index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ws.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = "${ws.activeWindowCount} window(s) • ${ws.wallpaper.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        if (!isSelected && hasActiveWindowToMove) {
                            Surface(
                                onClick = { onMoveActiveWindowTo(ws.index) },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "Move App Here",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        } else if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            WorkspaceLayoutPresetsSection(
                presets = presets,
                canSaveCurrentLayout = hasActiveWindowToMove,
                onLaunchPreset = onLaunchPreset,
                onSaveCurrentPreset = onSaveCurrentPreset,
                onDeletePreset = onDeletePreset,
                onInputFocusChanged = onPresetInputFocusChanged
            )
        }
    }
}

private fun wallpaperPreviewGradient(preset: WallpaperPreset): List<Color> = when (preset) {
    WallpaperPreset.OBSIDIAN_AURORA -> listOf(Color(0xFF0A0F1D), Color(0xFF1D4ED8))
    WallpaperPreset.SAPPHIRE_HORIZON -> listOf(Color(0xFF091326), Color(0xFF2563EB))
    WallpaperPreset.EMERALD_MATRIX -> listOf(Color(0xFF081715), Color(0xFF0D9488))
    WallpaperPreset.CARBON_MINIMAL -> listOf(Color(0xFF0E1015), Color(0xFF334155))
}
