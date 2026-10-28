package com.dexxp.desktop.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.model.TerminalLineKind
import com.dexxp.desktop.model.TerminalTranscriptLine
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Integrated Local Developer Shell Console & Termux Bridge (`Ctrl + Alt + T` / `Super + T`).
 *
 * - Executes local non-root Android shell commands (`ps`, `top`, `ping`, `ls`, `df -h`, `uname -a`,
 *   `getprop`, `cat`, `mkdir`, `cp`, `rm`, `cd`, `pwd`) safely via `/system/bin/sh` with a strict
 *   6-second process watchdog and a bounded 300-line ring buffer.
 * - Normalizes interactive TTY commands (`top` -> `top -b -n 1 | head -n 24`, `ping <host>` ->
 *   `ping -c 4 <host>`) so sessions never block waiting for a pseudo-terminal.
 * - Includes 1-click Freeform handoff to **Termux** (`com.termux`) when installed.
 */
class DesktopTerminalLauncher(
    context: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appRepository: AppRepository,
    private val appLauncher: AppLauncher
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lineIdGenerator = AtomicLong(1L)
    private val transcriptLock = Any()
    private val transcriptRing = ArrayDeque<TerminalTranscriptLine>(MAX_TRANSCRIPT_LINES)

    private val initialDir: File = (appContext.getExternalFilesDir("Desktop") ?: appContext.filesDir)
        .apply { if (!exists()) mkdirs() }

    private val _workingDirectory = MutableStateFlow(initialDir.absolutePath)
    val workingDirectory: StateFlow<String> = _workingDirectory.asStateFlow()

    private val _transcript = MutableStateFlow<List<TerminalTranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TerminalTranscriptLine>> = _transcript.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    @Volatile
    private var activeProcess: Process? = null
    private var activeCommandJob: Job? = null

    init {
        appendLine(
            "DexXP Local Developer Console • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
            TerminalLineKind.SYSTEM
        )
        appendLine(
            "Type 'help' for built-in commands, or run ps, top, ping, ls -la, df -h, uname -a, getprop.",
            TerminalLineKind.SYSTEM
        )
    }

    fun toggleTerminal() {
        sessionRepository.toggleTerminalWindow()
    }

    fun openTerminal() {
        sessionRepository.setTerminalWindowOpen(true)
    }

    fun closeTerminal() {
        sessionRepository.setTerminalWindowOpen(false)
    }

    fun isTermuxInstalled(): Boolean {
        return appRepository.findByPackage(TERMUX_PACKAGE) != null ||
            appContext.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) != null
    }

    fun launchTermuxInFreeform(launchContext: Context? = null): Boolean {
        sessionRepository.setTerminalWindowOpen(false)
        return appLauncher.launchOrFocusPackage(
            packageName = TERMUX_PACKAGE,
            snapMode = WindowSnapMode.FREEFORM_CASCADED,
            launchContext = launchContext
        )
    }

    fun clearTranscript() {
        synchronized(transcriptLock) {
            transcriptRing.clear()
            _transcript.value = emptyList()
        }
    }

    fun cancelActiveCommand() {
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
        activeCommandJob?.cancel()
        activeCommandJob = null
        _isExecuting.value = false
        appendLine("^C Process terminated", TerminalLineKind.STDERR)
    }

    fun executeCommand(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        _commandHistory.value = (listOf(trimmed) + _commandHistory.value.filterNot { it == trimmed })
            .take(MAX_HISTORY_ENTRIES)

        val shortCwd = shortenPath(_workingDirectory.value)
        appendLine("$shortCwd $ $trimmed", TerminalLineKind.PROMPT)

        when {
            trimmed.equals("clear", ignoreCase = true) || trimmed.equals("cls", ignoreCase = true) -> {
                clearTranscript()
                return
            }

            trimmed.equals("help", ignoreCase = true) -> {
                appendLine("Available Console Commands:", TerminalLineKind.SYSTEM)
                appendLine("  ps, top, df -h, uname -a, id, uptime, date, getprop <key>", TerminalLineKind.STDOUT)
                appendLine("  ls [-la], pwd, cd <dir>, cat <file>, mkdir, cp, mv, rm", TerminalLineKind.STDOUT)
                appendLine("  ping <host>, ip addr, pm list packages | head -n 25", TerminalLineKind.STDOUT)
                appendLine("  termux (launch full Termux Freeform window), clear, exit", TerminalLineKind.STDOUT)
                return
            }

            trimmed.equals("exit", ignoreCase = true) -> {
                closeTerminal()
                return
            }

            trimmed.equals("termux", ignoreCase = true) -> {
                if (!launchTermuxInFreeform()) {
                    appendLine("Termux (com.termux) is not installed on this device.", TerminalLineKind.STDERR)
                }
                return
            }

            trimmed.equals("pwd", ignoreCase = true) -> {
                appendLine(_workingDirectory.value, TerminalLineKind.STDOUT)
                return
            }

            trimmed == "cd" || trimmed.startsWith("cd ") -> {
                handleCdCommand(trimmed.removePrefix("cd").trim())
                return
            }
        }

        if (_isExecuting.value) {
            appendLine("Busy: wait for active command or press Stop.", TerminalLineKind.STDERR)
            return
        }

        val normalizedCommand = normalizeNonInteractiveCommand(trimmed)
        _isExecuting.value = true
        activeCommandJob = scope.launch {
            runShellProcess(normalizedCommand)
        }
    }

    private fun handleCdCommand(arg: String) {
        val targetDir = when {
            arg.isEmpty() || arg == "~" -> initialDir
            arg.startsWith("/") -> File(arg)
            else -> File(_workingDirectory.value, arg)
        }
        val canonical = runCatching { targetDir.canonicalFile }.getOrElse { targetDir.absoluteFile }
        if (canonical.exists() && canonical.isDirectory) {
            _workingDirectory.value = canonical.absolutePath
            appendLine(canonical.absolutePath, TerminalLineKind.SYSTEM)
        } else {
            appendLine("cd: no such directory: $arg", TerminalLineKind.STDERR)
        }
    }

    private suspend fun runShellProcess(command: String) = withContext(Dispatchers.IO) {
        var proc: Process? = null
        try {
            val cwdFile = File(_workingDirectory.value).takeIf { it.exists() && it.isDirectory }
                ?: initialDir
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(cwdFile)
            pb.environment()["HOME"] = initialDir.absolutePath
            pb.environment()["TERM"] = "dumb"

            proc = pb.start()
            activeProcess = proc

            val stdoutJob = launch {
                proc.inputStream.bufferedReader().useLines { seq ->
                    var count = 0
                    for (line in seq) {
                        if (count < MAX_LINES_PER_COMMAND) {
                            appendLine(line, TerminalLineKind.STDOUT)
                        } else if (count == MAX_LINES_PER_COMMAND) {
                            appendLine("... [output truncated at $MAX_LINES_PER_COMMAND lines]", TerminalLineKind.SYSTEM)
                        }
                        count++
                    }
                }
            }

            val stderrJob = launch {
                proc.errorStream.bufferedReader().useLines { seq ->
                    var count = 0
                    for (line in seq) {
                        if (count < 80) {
                            appendLine(line, TerminalLineKind.STDERR)
                        }
                        count++
                    }
                }
            }

            val finishedInTime = proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finishedInTime) {
                proc.destroyForcibly()
                appendLine(
                    "Timeout: command exceeded ${COMMAND_TIMEOUT_SECONDS}s limit and was terminated.",
                    TerminalLineKind.STDERR
                )
            }
            stdoutJob.join()
            stderrJob.join()
        } catch (err: Throwable) {
            appendLine("Error: ${err.message ?: err::class.java.simpleName}", TerminalLineKind.STDERR)
        } finally {
            runCatching { proc?.destroy() }
            if (activeProcess === proc) {
                activeProcess = null
            }
            _isExecuting.value = false
        }
    }

    private fun appendLine(text: String, kind: TerminalLineKind) {
        val entry = TerminalTranscriptLine(
            id = lineIdGenerator.getAndIncrement(),
            text = text,
            kind = kind
        )
        synchronized(transcriptLock) {
            while (transcriptRing.size >= MAX_TRANSCRIPT_LINES) {
                transcriptRing.removeFirst()
            }
            transcriptRing.addLast(entry)
            _transcript.value = transcriptRing.toList()
        }
    }

    private fun shortenPath(fullPath: String): String {
        val base = initialDir.absolutePath
        return if (fullPath == base) {
            "~/Desktop"
        } else if (fullPath.startsWith("$base/")) {
            "~/Desktop/${fullPath.removePrefix("$base/")}"
        } else {
            fullPath.takeLast(32)
        }
    }

    fun copyTranscriptToClipboard() {
        val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val fullText = _transcript.value.joinToString("\n") { it.text }
        if (fullText.isNotEmpty()) {
            cm.setPrimaryClip(ClipData.newPlainText("DexXP Terminal Output", fullText))
        }
    }

    fun shutdown() {
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
        scope.cancel()
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        private const val COMMAND_TIMEOUT_SECONDS = 6L
        private const val MAX_TRANSCRIPT_LINES = 300
        private const val MAX_LINES_PER_COMMAND = 140
        private const val MAX_HISTORY_ENTRIES = 30

        val QUICK_COMMANDS = listOf(
            "ps -A | head -n 20",
            "top -b -n 1 | head -n 20",
            "df -h",
            "uname -a",
            "ls -la",
            "ping -c 3 1.1.1.1"
        )

        /**
         * Ensures bare `top` or `ping <host>` runs in bounded batch mode when invoked without a PTY.
         */
        fun normalizeNonInteractiveCommand(command: String): String {
            val tokens = command.trim().split(Regex("\\s+"))
            val head = tokens.firstOrNull()?.lowercase() ?: return command
            return when {
                head == "top" && !command.contains("-n") && !command.contains("-b") -> {
                    "top -b -n 1 | head -n 24"
                }
                head == "ping" && !command.contains("-c") -> {
                    val rest = tokens.drop(1).joinToString(" ")
                    if (rest.isNotEmpty()) "ping -c 4 $rest" else command
                }
                else -> command
            }
        }
    }
}

/**
 * Draggable dark Material 3 Developer Shell Console window rendered inside the desktop shell.
 */
@Composable
fun DesktopTerminalWindow(
    terminalLauncher: DesktopTerminalLauncher,
    onInputFocusChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val transcript by terminalLauncher.transcript.collectAsState()
    val workingDir by terminalLauncher.workingDirectory.collectAsState()
    val isExecuting by terminalLauncher.isExecuting.collectAsState()
    val history by terminalLauncher.commandHistory.collectAsState()

    var inputCommand by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    var isExpandedSize by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val termuxAvailable = remember { terminalLauncher.isTermuxInstalled() }

    DisposableEffect(Unit) {
        onDispose { onInputFocusChanged(false) }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.scrollToItem(transcript.lastIndex)
        }
    }

    val winWidth = if (isExpandedSize) 860.dp else 680.dp
    val winHeight = if (isExpandedSize) 520.dp else 410.dp

    Surface(
        modifier = modifier
            .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
            .size(width = winWidth, height = winHeight)
            .border(
                width = 1.dp,
                color = Color(0xFF334155),
                shape = RoundedCornerShape(20.dp)
            )
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Escape -> {
                        onClose()
                        true
                    }
                    Key.DirectionUp -> {
                        if (history.isNotEmpty()) {
                            val nextIdx = (historyCursor + 1).coerceAtMost(history.lastIndex)
                            historyCursor = nextIdx
                            inputCommand = history[nextIdx]
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (historyCursor > 0) {
                            historyCursor -= 1
                            inputCommand = history[historyCursor]
                        } else if (historyCursor == 0) {
                            historyCursor = -1
                            inputCommand = ""
                        }
                        true
                    }
                    else -> false
                }
            },
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF0B0F19).copy(alpha = 0.97f),
        tonalElevation = 12.dp,
        shadowElevation = 26.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Draggable Dark M3 Terminal Titlebar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color(0xFF131B2E))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragOffsetX = (dragOffsetX + dragAmount.x).coerceIn(-520f, 520f)
                            dragOffsetY = (dragOffsetY + dragAmount.y).coerceIn(-300f, 300f)
                        }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DesktopIcons.Terminal,
                    contentDescription = null,
                    tint = Color(0xFF6DD58C),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Developer Shell Console • ${workingDir.takeLast(36)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE2E8F0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (termuxAvailable) {
                    Surface(
                        onClick = { terminalLauncher.launchTermuxInFreeform(context) },
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = "Open Termux",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8AB4F8),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                IconButton(
                    onClick = terminalLauncher::copyTranscriptToClipboard,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Copy,
                        contentDescription = "Copy Output",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(
                    onClick = { isExpandedSize = !isExpandedSize },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isExpandedSize) DesktopIcons.RestoreWindow else DesktopIcons.Maximize,
                        contentDescription = "Toggle Terminal Size",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Close,
                        contentDescription = "Close Terminal",
                        tint = Color(0xFFF87171),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Quick Diagnostic Command Strip
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F1626))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(DesktopTerminalLauncher.QUICK_COMMANDS, key = { it }) { cmd ->
                    Surface(
                        onClick = { terminalLauncher.executeCommand(cmd) },
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = cmd,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                item(key = "clear_chip") {
                    Surface(
                        onClick = terminalLauncher::clearTranscript,
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = "clear",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // Monospace Output Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(transcript, key = { it.id }) { line ->
                    val textColor = when (line.kind) {
                        TerminalLineKind.PROMPT -> Color(0xFF6DD58C)
                        TerminalLineKind.STDOUT -> Color(0xFFE2E8F0)
                        TerminalLineKind.STDERR -> Color(0xFFFCA5A5)
                        TerminalLineKind.SYSTEM -> Color(0xFF8AB4F8)
                    }
                    Text(
                        text = line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (line.kind == TerminalLineKind.PROMPT) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // Interactive Shell Prompt Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Color(0xFF111827))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExecuting) "..." else "$",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isExecuting) Color(0xFFFBBF24) else Color(0xFF6DD58C),
                    fontSize = 14.sp
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (inputCommand.isEmpty()) {
                        Text(
                            text = "Enter shell command (ps, top, ping, ls, df -h, getprop)...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                    BasicTextField(
                        value = inputCommand,
                        onValueChange = {
                            inputCommand = it
                            historyCursor = -1
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color(0xFFF8FAFC)
                        ),
                        cursorBrush = SolidColor(Color(0xFF6DD58C)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val cmd = inputCommand
                                inputCommand = ""
                                historyCursor = -1
                                terminalLauncher.executeCommand(cmd)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { onInputFocusChanged(it.isFocused) }
                    )
                }

                if (isExecuting) {
                    Surface(
                        onClick = terminalLauncher::cancelActiveCommand,
                        shape = CircleShape,
                        color = Color(0xFF7F1D1D)
                    ) {
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val cmd = inputCommand
                            inputCommand = ""
                            historyCursor = -1
                            terminalLauncher.executeCommand(cmd)
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Send,
                            contentDescription = "Run Command",
                            tint = Color(0xFF6DD58C),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}
