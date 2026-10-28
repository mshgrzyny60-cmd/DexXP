package com.dexxp.desktop.keyboard

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.model.AltTabOverlayState
import com.dexxp.desktop.model.RunningWindowRecord
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.overview.MissionControlOverviewController
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import com.dexxp.desktop.workspace.VirtualWorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hardware Keyboard Shortcut Interceptor, `Alt + Tab` M3 Window Switcher, and Soft IME Relay.
 *
 * Intercepts global & shell key events:
 * - `Alt + Space` / `Super + Space`: Toggles Universal Command Palette & Spotlight (`CommandPaletteController`).
 * - `Super + Tab`: Toggles Mission Control / Exposé Window Overview (`MissionControlOverviewController`).
 * - `Ctrl + Alt + T` / `Super + T`: Toggles Integrated Local Developer Terminal (`DesktopTerminalLauncher`).
 * - `Alt + Tab` / `Alt + Shift + Tab`: Opens & cycles M3 Task Switcher, committing on `Alt` release.
 * - `Super / Meta / Windows` solo press: Toggles Start Menu open/closed.
 * - `Super + V` (`Win + V`): Toggles Clipboard History Flyout (`DesktopClipboardManager`).
 * - `Super + Ctrl + Left / Right Arrow`: Switches previous/next Virtual Workspace (`VirtualWorkspaceManager`).
 * - `Super + D`: Minimizes/restores all active Freeform windows.
 * - `Super + Left / Right / Up / Down`: Instant window snap (Left 50%, Right 50%, Maximize, Freeform).
 * - `Escape`: Dismisses active Task Switcher or shell flyouts.
 */
class DesktopKeyboardManager(
    private val sessionRepository: DesktopSessionRepository,
    private val appLauncher: AppLauncher,
    private val pointerFocusBridge: PointerFocusBridge,
    private val virtualWorkspaceManager: VirtualWorkspaceManager,
    private val clipboardManager: DesktopClipboardManager,
    private val commandPaletteController: CommandPaletteController,
    private val missionControlController: MissionControlOverviewController,
    private val terminalLauncher: DesktopTerminalLauncher
) {
    private val _altTabState = MutableStateFlow(AltTabOverlayState())
    val altTabState: StateFlow<AltTabOverlayState> = _altTabState.asStateFlow()

    @Volatile
    private var metaPressedSoloCandidate = false

    @Volatile
    private var metaChordConsumed = false

    /**
     * Processes hardware [KeyEvent]s from `DesktopPointerAccessibilityService.onKeyEvent`,
     * `DesktopWindowPresentation`, `SecondaryDesktopActivity`, and `MainActivity`.
     *
     * @return `true` if the shortcut was consumed by the desktop window manager.
     */
    fun onHardwareKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // 1. Track solo Super / Meta / Windows key tap vs Meta+Key chord
        if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                metaPressedSoloCandidate = true
                metaChordConsumed = false
            } else if (action == KeyEvent.ACTION_UP) {
                if (metaPressedSoloCandidate && !metaChordConsumed) {
                    sessionRepository.toggleStartMenu()
                    metaPressedSoloCandidate = false
                    return true
                }
                metaPressedSoloCandidate = false
                metaChordConsumed = false
            }
            return false
        }

        // 1a. Global Command Palette (`Alt + Space` / `Super + Space`) & Terminal (`Ctrl + Alt + T`)
        if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (keyCode == KeyEvent.KEYCODE_SPACE && (event.isAltPressed || event.isMetaPressed)) {
                if (event.isMetaPressed) {
                    metaPressedSoloCandidate = false
                    metaChordConsumed = true
                }
                dismissAltTab()
                commandPaletteController.togglePalette()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_T && event.isCtrlPressed && event.isAltPressed) {
                dismissAltTab()
                terminalLauncher.toggleTerminal()
                return true
            }
        }

        // 1b. Super + Ctrl + Left / Right Arrow -> Virtual Workspace Switch
        if (event.isMetaPressed && event.isCtrlPressed && action == KeyEvent.ACTION_DOWN) {
            metaPressedSoloCandidate = false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    metaChordConsumed = true
                    virtualWorkspaceManager.switchPreviousWorkspace()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    metaChordConsumed = true
                    virtualWorkspaceManager.switchNextWorkspace()
                    return true
                }
            }
        }

        if (event.isMetaPressed && action == KeyEvent.ACTION_DOWN) {
            metaPressedSoloCandidate = false
            when (keyCode) {
                KeyEvent.KEYCODE_TAB -> {
                    metaChordConsumed = true
                    dismissAltTab()
                    missionControlController.toggleOverview()
                    return true
                }
                KeyEvent.KEYCODE_T -> {
                    metaChordConsumed = true
                    dismissAltTab()
                    terminalLauncher.toggleTerminal()
                    return true
                }
                KeyEvent.KEYCODE_V -> {
                    metaChordConsumed = true
                    clipboardManager.syncCurrentPrimaryClip()
                    sessionRepository.toggleClipboardHistory()
                    return true
                }
                KeyEvent.KEYCODE_D -> {
                    metaChordConsumed = true
                    appLauncher.toggleMinimizeAllOrRestore()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    metaChordConsumed = true
                    return appLauncher.snapFocusedWindow(WindowSnapMode.LEFT_HALF)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    metaChordConsumed = true
                    return appLauncher.snapFocusedWindow(WindowSnapMode.RIGHT_HALF)
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    metaChordConsumed = true
                    return appLauncher.snapFocusedWindow(WindowSnapMode.MAXIMIZED)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    metaChordConsumed = true
                    return appLauncher.snapFocusedWindow(WindowSnapMode.FREEFORM_CASCADED)
                }
            }
        }

        // 2. Alt + Tab Window Switcher cycle & commit on Alt release
        if (keyCode == KeyEvent.KEYCODE_TAB && event.isAltPressed) {
            if (action == KeyEvent.ACTION_DOWN) {
                cycleAltTab(reverse = event.isShiftPressed)
            }
            return true
        }

        if ((keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) &&
            action == KeyEvent.ACTION_UP &&
            _altTabState.value.isVisible
        ) {
            commitAltTabSelection()
            return true
        }

        // 3. Escape closes Alt+Tab or open shell flyouts
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && action == KeyEvent.ACTION_DOWN) {
            if (_altTabState.value.isVisible) {
                dismissAltTab()
                return true
            }
            if (sessionRepository.anyFlyoutOpen.value) {
                sessionRepository.dismissFlyouts()
                return true
            }
        }

        return false
    }

    fun cycleAltTab(reverse: Boolean = false) {
        val ordered = sessionRepository.runningWindows.value
            .sortedByDescending { it.lastActiveEpochMs }
        if (ordered.isEmpty()) return

        _altTabState.update { current ->
            if (!current.isVisible) {
                val initialIdx = if (ordered.size > 1) 1 else 0
                AltTabOverlayState(
                    isVisible = true,
                    windows = ordered,
                    selectedIndex = initialIdx
                )
            } else {
                val count = current.windows.size.coerceAtLeast(1)
                val step = if (reverse) -1 else 1
                val nextIdx = ((current.selectedIndex + step) % count + count) % count
                current.copy(selectedIndex = nextIdx)
            }
        }
    }

    fun selectAndCommitWindow(window: RunningWindowRecord) {
        _altTabState.value = AltTabOverlayState(isVisible = false)
        appLauncher.launchOrFocusPackage(window.packageName, window.snapMode)
    }

    fun commitAltTabSelection() {
        val current = _altTabState.value
        _altTabState.value = AltTabOverlayState(isVisible = false)
        if (!current.isVisible || current.windows.isEmpty()) return
        val chosen = current.windows.getOrNull(current.selectedIndex) ?: current.windows.first()
        appLauncher.launchOrFocusPackage(chosen.packageName, chosen.snapMode)
    }

    fun dismissAltTab() {
        _altTabState.value = AltTabOverlayState(isVisible = false)
    }

    /**
     * Streams live soft-IME text from the Phone Controller to whichever editable surface
     * is active on the secondary display (Desktop Shell search/reply or third-party Freeform app).
     */
    fun streamRelayTextToActiveWindow(newText: String, previousText: String) {
        if (sessionRepository.needsShellKeyboardFocus.value) {
            dispatchStringDeltaToShellWindow(previousText, newText)
            return
        }

        // External Freeform app editable node injection via Accessibility ACTION_SET_TEXT
        DesktopPointerAccessibilityService.instance?.setTextOnFocusedExternalNode(newText)
    }

    fun sendRelayEnterAction() {
        if (sessionRepository.needsShellKeyboardFocus.value) {
            pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_ENTER)
        } else {
            val handled = DesktopPointerAccessibilityService.instance
                ?.performImeEnterOnFocusedExternalNode() == true
            if (!handled) {
                pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    fun sendRelayBackspace() {
        pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_DEL)
    }

    private fun dispatchStringDeltaToShellWindow(oldText: String, newText: String) {
        if (newText.length < oldText.length && oldText.startsWith(newText)) {
            val deleteCount = oldText.length - newText.length
            repeat(deleteCount) {
                pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_DEL)
            }
            return
        }

        val appended = if (newText.startsWith(oldText)) {
            newText.substring(oldText.length)
        } else {
            newText
        }
        if (appended.isEmpty()) return

        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(appended.toCharArray())
        if (events != null) {
            for (ev in events) {
                pointerFocusBridge.forwardRawKeyEventToShell(ev)
            }
        } else {
            val now = SystemClock.uptimeMillis()
            val actionMultiple = KeyEvent(now, appended, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
            pointerFocusBridge.forwardRawKeyEventToShell(actionMultiple)
        }
    }
}

/**
 * Centered Material 3 `Alt + Tab` Task Switcher Overlay displaying live icons and window titles.
 */
@Composable
fun AltTabSwitcherOverlay(
    state: AltTabOverlayState,
    appRepository: AppRepository,
    onSelectWindow: (RunningWindowRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible || state.windows.isEmpty()) return

    Surface(
        modifier = modifier
            .widthIn(min = 320.dp, max = 760.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                shape = RoundedCornerShape(26.dp)
            ),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 12.dp,
        shadowElevation = 24.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Switch Active Window (Alt + Tab)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(14.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                itemsIndexed(
                    items = state.windows,
                    key = { _, win -> win.packageName }
                ) { index, win ->
                    val isSelected = index == state.selectedIndex
                    val iconBitmap = remember(win.packageName) {
                        appRepository.findByPackage(win.packageName)?.iconBitmap
                    }

                    Column(
                        modifier = Modifier
                            .width(126.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { onSelectWindow(win) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = win.label,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = win.label.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = win.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = win.snapMode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
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
