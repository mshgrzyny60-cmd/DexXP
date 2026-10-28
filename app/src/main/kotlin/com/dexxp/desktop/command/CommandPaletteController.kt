package com.dexxp.desktop.command

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.dexxp.desktop.browser.DesktopBrowserLauncher
import com.dexxp.desktop.files.DesktopFileManager
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.layout.WorkspaceLayoutPresetManager
import com.dexxp.desktop.model.CommandPaletteItem
import com.dexxp.desktop.model.CommandResultKind
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.DesktopFileItem
import com.dexxp.desktop.model.MathEvalResult
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.model.WorkspaceLayoutPreset
import com.dexxp.desktop.power.PhoneScreenPowerController
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Universal Command Palette & Spotlight Engine (`Alt + Space` / `Cmd + Space`).
 *
 * Unifies:
 * 1. Zero-dependency recursive-descent Inline Calculator (`450 * 1.17`, `sqrt(144)`, `2^10`);
 * 2. 2-keystroke Fuzzy Scorer across installed apps, recent desktop files, saved Snap Groups,
 *    and built-in system commands;
 * 3. Direct Desktop Browser Web Search dispatch on non-math queries.
 */
class CommandPaletteController(
    context: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appRepository: AppRepository,
    private val desktopFileManager: DesktopFileManager,
    private val presetManager: WorkspaceLayoutPresetManager,
    private val appLauncher: AppLauncher,
    private val browserLauncher: DesktopBrowserLauncher,
    private val terminalLauncher: DesktopTerminalLauncher,
    private val phoneScreenPowerController: PhoneScreenPowerController
) {
    private val appContext = context.applicationContext

    val isOpen: StateFlow<Boolean> = sessionRepository.isCommandPaletteOpen

    private data class BuiltInCommand(
        val id: String,
        val title: String,
        val subtitle: String,
        val keywords: String
    )

    private val systemCommands = listOf(
        BuiltInCommand(
            id = ACTION_OPEN_TERMINAL,
            title = "Open Developer Terminal Console",
            subtitle = "Run local shell commands (ps, top, ping, ls) or launch Termux",
            keywords = "terminal shell bash console sh adb termux cmd"
        ),
        BuiltInCommand(
            id = ACTION_MISSION_CONTROL,
            title = "Mission Control • Window Overview",
            subtitle = "Bird's-eye Exposé grid of active Freeform windows & Virtual Workspaces",
            keywords = "mission control expose overview windows task view"
        ),
        BuiltInCommand(
            id = ACTION_SLEEP_PHONE,
            title = "Toggle Sleep Phone Screen (AMOLED Blackout)",
            subtitle = "Zero-emission OLED battery & thermal saver on primary phone display",
            keywords = "sleep blackout amoled battery screen off power"
        ),
        BuiltInCommand(
            id = ACTION_CLIPBOARD_HISTORY,
            title = "Clipboard History (Win + V)",
            subtitle = "View recent copied snippets & 1-click paste",
            keywords = "clipboard paste history copy win+v"
        ),
        BuiltInCommand(
            id = ACTION_TILE_SIDE_BY_SIDE,
            title = "Tile Top Two Windows 50% / 50%",
            subtitle = "Snap the two most recent Freeform apps side-by-side",
            keywords = "tile split 50/50 side by side snap"
        )
    )

    fun togglePalette() {
        sessionRepository.toggleCommandPalette()
    }

    fun openPalette() {
        sessionRepository.setCommandPaletteOpen(true)
    }

    fun closePalette() {
        sessionRepository.setCommandPaletteOpen(false)
    }

    /**
     * Ranks inline math, apps, files, layout presets, system actions, and direct web search
     * in sub-millisecond time for instant 2-keystroke launching.
     */
    fun buildRankedResults(
        rawQuery: String,
        apps: List<DesktopAppItem> = appRepository.apps.value,
        files: List<DesktopFileItem> = desktopFileManager.files.value,
        presets: List<WorkspaceLayoutPreset> = presetManager.presets.value,
        pinnedPackages: Set<String> = sessionRepository.pinnedPackages.value
    ): List<CommandPaletteItem> {
        val query = rawQuery.trim()
        val results = ArrayList<CommandPaletteItem>(16)

        if (query.isEmpty()) {
            // Zero-keystroke default Spotlight suggestions: Presets + Key Commands + Pinned Apps + Recent Files
            presets.take(2).forEach { preset ->
                results.add(
                    CommandPaletteItem(
                        id = "preset:${preset.id}",
                        kind = CommandResultKind.LAYOUT_PRESET,
                        title = "Launch Snap Group: ${preset.name}",
                        subtitle = preset.subtitle,
                        score = 920,
                        presetId = preset.id
                    )
                )
            }
            systemCommands.take(2).forEach { cmd ->
                results.add(
                    CommandPaletteItem(
                        id = "cmd:${cmd.id}",
                        kind = CommandResultKind.SYSTEM_ACTION,
                        title = cmd.title,
                        subtitle = cmd.subtitle,
                        score = 900,
                        actionId = cmd.id
                    )
                )
            }
            val starterApps = apps.filter { it.packageName in pinnedPackages }.ifEmpty { apps.take(5) }
            starterApps.take(5).forEach { app ->
                results.add(
                    CommandPaletteItem(
                        id = "app:${app.uniqueKey}",
                        kind = CommandResultKind.APP,
                        title = app.label,
                        subtitle = "${app.category.title} • ${app.packageName}",
                        score = 850,
                        appItem = app
                    )
                )
            }
            files.take(3).forEach { file ->
                results.add(
                    CommandPaletteItem(
                        id = "file:${file.uri}",
                        kind = CommandResultKind.FILE,
                        title = file.displayName,
                        subtitle = "Recent File • ${formatFileSize(file.sizeBytes)}",
                        score = 800,
                        fileItem = file
                    )
                )
            }
            return results
        }

        // 1. Inline Math Calculator Evaluation
        val mathResult = evaluateMathExpression(query)
        if (mathResult != null) {
            results.add(
                CommandPaletteItem(
                    id = "math:$query",
                    kind = CommandResultKind.CALCULATOR,
                    title = "= ${mathResult.formattedValue}",
                    subtitle = "${mathResult.expression} • Press Enter or click Copy",
                    score = 2_000,
                    mathValue = mathResult.formattedValue
                )
            )
        }

        val candidates = ArrayList<CommandPaletteItem>(24)

        // 2. Fuzzy App Scoring
        for (app in apps) {
            val score = scoreFuzzyMatch(
                query = query,
                primary = app.label,
                secondary = app.packageName.substringAfterLast('.')
            )
            if (score > 0) {
                val pinnedBonus = if (app.packageName in pinnedPackages) 20 else 0
                candidates.add(
                    CommandPaletteItem(
                        id = "app:${app.uniqueKey}",
                        kind = CommandResultKind.APP,
                        title = app.label,
                        subtitle = "${app.category.title} • Freeform Window",
                        score = score + pinnedBonus,
                        appItem = app
                    )
                )
            }
        }

        // 3. Fuzzy Layout Presets Scoring
        for (preset in presets) {
            val score = scoreFuzzyMatch(
                query = query,
                primary = preset.name,
                secondary = preset.subtitle
            )
            if (score > 0) {
                candidates.add(
                    CommandPaletteItem(
                        id = "preset:${preset.id}",
                        kind = CommandResultKind.LAYOUT_PRESET,
                        title = preset.name,
                        subtitle = "Snap Group • ${preset.subtitle}",
                        score = score + 10,
                        presetId = preset.id
                    )
                )
            }
        }

        // 4. Fuzzy Built-In System Commands
        for (cmd in systemCommands) {
            val score = scoreFuzzyMatch(
                query = query,
                primary = cmd.title,
                secondary = cmd.keywords
            )
            if (score > 0) {
                candidates.add(
                    CommandPaletteItem(
                        id = "cmd:${cmd.id}",
                        kind = CommandResultKind.SYSTEM_ACTION,
                        title = cmd.title,
                        subtitle = cmd.subtitle,
                        score = score,
                        actionId = cmd.id
                    )
                )
            }
        }

        // 5. Fuzzy Recent Desktop Files
        for (file in files) {
            val score = scoreFuzzyMatch(
                query = query,
                primary = file.displayName,
                secondary = file.mimeType
            )
            if (score > 0) {
                candidates.add(
                    CommandPaletteItem(
                        id = "file:${file.uri}",
                        kind = CommandResultKind.FILE,
                        title = file.displayName,
                        subtitle = "Desktop File • ${formatFileSize(file.sizeBytes)}",
                        score = score - 15,
                        fileItem = file
                    )
                )
            }
        }

        candidates.sortByDescending { it.score }
        results.addAll(candidates.take(MAX_SEARCH_HITS))

        // 6. Direct Web Search Dispatch (auto-selected at index 0 when no math/app/file matched)
        if (mathResult == null) {
            results.add(
                CommandPaletteItem(
                    id = "web:$query",
                    kind = CommandResultKind.WEB_SEARCH,
                    title = "Search Web for \"$query\"",
                    subtitle = "Open directly in Desktop Browser",
                    score = 100,
                    webQuery = query
                )
            )
        }

        return results
    }

    /**
     * Executes the chosen [CommandPaletteItem] or dispatches a direct web query when [rawQuery]
     * is non-math and no explicit item matched.
     */
    fun executeItem(item: CommandPaletteItem?, rawQuery: String, launchContext: Context? = null): Boolean {
        if (item == null) {
            val trimmed = rawQuery.trim()
            if (trimmed.isEmpty()) return false
            val math = evaluateMathExpression(trimmed)
            return if (math != null) {
                copyTextToClipboard(math.formattedValue)
                true
            } else {
                dispatchWebSearch(trimmed, launchContext)
            }
        }

        return when (item.kind) {
            CommandResultKind.CALCULATOR -> {
                val value = item.mathValue ?: return false
                copyTextToClipboard(value)
                true
            }

            CommandResultKind.APP -> {
                val app = item.appItem ?: return false
                closePalette()
                appLauncher.launchApp(app, WindowSnapMode.FREEFORM_CASCADED, false, launchContext)
            }

            CommandResultKind.FILE -> {
                val file = item.fileItem ?: return false
                closePalette()
                appLauncher.launchContentUriInFreeform(file.uri, file.mimeType, launchContext)
            }

            CommandResultKind.LAYOUT_PRESET -> {
                val preset = presetManager.presets.value.firstOrNull { it.id == item.presetId }
                    ?: return false
                closePalette()
                presetManager.launchPreset(preset, launchContext) > 0
            }

            CommandResultKind.SYSTEM_ACTION -> {
                executeSystemAction(item.actionId, launchContext)
            }

            CommandResultKind.WEB_SEARCH -> {
                val q = item.webQuery ?: rawQuery.trim()
                dispatchWebSearch(q, launchContext)
            }
        }
    }

    fun dispatchWebSearch(query: String, launchContext: Context? = null): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        closePalette()
        val targetUrl = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            (trimmed.contains('.') && !trimmed.contains(' '))
        ) {
            if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
        } else {
            "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        return browserLauncher.launchDesktopBrowserSession(url = targetUrl, launchContext = launchContext)
    }

    fun copyTextToClipboard(value: String) {
        val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("DexXP Calculator", value))
    }

    private fun executeSystemAction(actionId: String?, launchContext: Context?): Boolean {
        return when (actionId) {
            ACTION_OPEN_TERMINAL -> {
                terminalLauncher.openTerminal()
                true
            }
            ACTION_MISSION_CONTROL -> {
                sessionRepository.setMissionControlOpen(true)
                true
            }
            ACTION_SLEEP_PHONE -> {
                closePalette()
                phoneScreenPowerController.toggleBlackout()
                true
            }
            ACTION_CLIPBOARD_HISTORY -> {
                sessionRepository.setClipboardHistoryOpen(true)
                true
            }
            ACTION_TILE_SIDE_BY_SIDE -> {
                closePalette()
                appLauncher.tileTopTwoWindowsSideBySide(launchContext)
                true
            }
            else -> false
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    companion object {
        private const val MAX_SEARCH_HITS = 9
        const val ACTION_OPEN_TERMINAL = "open_terminal"
        const val ACTION_MISSION_CONTROL = "mission_control"
        const val ACTION_SLEEP_PHONE = "sleep_phone"
        const val ACTION_CLIPBOARD_HISTORY = "clipboard_history"
        const val ACTION_TILE_SIDE_BY_SIDE = "tile_side_by_side"

        /**
         * Fast 2-keystroke fuzzy scorer supporting prefix, word-boundary acronym, substring,
         * and ordered subsequence matching without heap-heavy regexes.
         */
        fun scoreFuzzyMatch(query: String, primary: String, secondary: String = ""): Int {
            val q = query.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) return 0
            val p = primary.lowercase(Locale.ROOT)
            val s = secondary.lowercase(Locale.ROOT)

            if (p == q) return 1_000
            if (p.startsWith(q)) return (900 - p.length.coerceAtMost(40))

            // Word-boundary acronym & token prefix check (e.g. "ch" -> Chrome, "es" -> Editing Suite)
            val words = buildWordInitialsAndStarts(p)
            if (words.acronym.startsWith(q) && q.length >= 2) {
                return 845
            }
            for (word in words.tokens) {
                if (word.startsWith(q)) return 810
            }

            val subIdx = p.indexOf(q)
            if (subIdx >= 0) {
                return (680 - subIdx * 4).coerceAtLeast(520)
            }

            // Ordered subsequence match on primary label
            val subseqScore = scoreSubsequence(q, p)
            if (subseqScore > 0) return subseqScore

            // Fallback check against secondary package/keywords
            if (s.isNotEmpty()) {
                if (s.startsWith(q)) return 560
                if (s.contains(q)) return 460
            }
            return 0
        }

        private class WordTokens(val tokens: List<String>, val acronym: String)

        private fun buildWordInitialsAndStarts(lowerText: String): WordTokens {
            val parts = lowerText.split(' ', '-', '_', '.', '•').filter { it.isNotEmpty() }
            val initials = StringBuilder(parts.size)
            for (part in parts) {
                initials.append(part[0])
            }
            return WordTokens(parts, initials.toString())
        }

        private fun scoreSubsequence(query: String, target: String): Int {
            if (query.length > target.length) return 0
            var qi = 0
            var firstMatch = -1
            var lastMatch = -1
            var consecutiveBonus = 0

            for (ti in target.indices) {
                if (qi < query.length && target[ti] == query[qi]) {
                    if (firstMatch == -1) firstMatch = ti
                    if (lastMatch == ti - 1) consecutiveBonus += 18
                    lastMatch = ti
                    qi++
                }
            }
            if (qi != query.length) return 0
            val spanPenalty = (lastMatch - firstMatch - query.length).coerceAtLeast(0) * 6
            return (480 + consecutiveBonus - spanPenalty).coerceAtLeast(220)
        }

        /**
         * Recursive-descent mathematical expression evaluator supporting `+`, `-`, `*`, `/`, `%`,
         * `^`, parentheses, `sqrt`, `sin`, `cos`, `tan`, `abs`, `round`, `floor`, `ceil`, `ln`,
         * `log`, `pi`, and `e`.
         *
         * Returns `null` if [raw] is plain text or a bare single literal without any math operator/function.
         */
        fun evaluateMathExpression(raw: String): MathEvalResult? {
            val normalized = raw.trim()
                .replace('×', '*')
                .replace('÷', '/')
                .replace(',', '.')
            if (normalized.length < 2) return null

            // Require at least one operator or math function/constant so plain app names or single numbers don't trigger
            val lower = normalized.lowercase(Locale.US)
            val hasOperator = normalized.any { it in "+-*/^%()" }
            val hasFunction = MATH_FUNCTIONS.any { lower.contains(it) }
            if (!hasOperator && !hasFunction) return null

            val parser = RecursiveMathParser(lower)
            val value = runCatching { parser.parseFullExpression() }.getOrNull() ?: return null
            if (!value.isFinite()) return null

            // Reject bare signed literal like "-42" or "+10" with only 1 token
            if (parser.operatorOrFunctionCount == 0) return null

            val formatted = formatCleanDecimal(value)
            return MathEvalResult(
                expression = raw.trim(),
                formattedValue = formatted,
                rawValue = value
            )
        }

        private fun formatCleanDecimal(value: Double): String {
            if (abs(value) < 1e-12) return "0"
            val roundedLong = value.toLong()
            if (abs(value - roundedLong.toDouble()) < 1e-9 && abs(value) < 1e15) {
                return roundedLong.toString()
            }
            return runCatching {
                BigDecimal.valueOf(value)
                    .setScale(8, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
            }.getOrElse { value.toString() }
        }

        private val MATH_FUNCTIONS = listOf(
            "sqrt", "sin", "cos", "tan", "abs", "round", "floor", "ceil", "ln", "log", "pi"
        )

        private class RecursiveMathParser(private val s: String) {
            private var pos = 0
            var operatorOrFunctionCount = 0
                private set

            fun parseFullExpression(): Double? {
                val v = parseAddSub() ?: return null
                skipSpaces()
                if (pos != s.length) return null
                return v
            }

            private fun skipSpaces() {
                while (pos < s.length && s[pos].isWhitespace()) pos++
            }

            private fun parseAddSub(): Double? {
                var left = parseMulDiv() ?: return null
                while (true) {
                    skipSpaces()
                    if (pos >= s.length) break
                    val op = s[pos]
                    if (op != '+' && op != '-') break
                    pos++
                    operatorOrFunctionCount++
                    val right = parseMulDiv() ?: return null
                    left = if (op == '+') left + right else left - right
                }
                return left
            }

            private fun parseMulDiv(): Double? {
                var left = parsePower() ?: return null
                while (true) {
                    skipSpaces()
                    if (pos >= s.length) break
                    val op = s[pos]
                    if (op != '*' && op != '/' && op != '%') break
                    pos++
                    operatorOrFunctionCount++
                    val right = parsePower() ?: return null
                    left = when (op) {
                        '*' -> left * right
                        '/' -> if (right == 0.0) return null else left / right
                        else -> if (right == 0.0) return null else left % right
                    }
                }
                return left
            }

            private fun parsePower(): Double? {
                val base = parseUnary() ?: return null
                skipSpaces()
                if (pos < s.length && s[pos] == '^') {
                    pos++
                    operatorOrFunctionCount++
                    val exp = parsePower() ?: return null
                    return base.pow(exp)
                }
                return base
            }

            private fun parseUnary(): Double? {
                skipSpaces()
                if (pos >= s.length) return null
                return when (s[pos]) {
                    '+' -> {
                        pos++
                        parseUnary()
                    }
                    '-' -> {
                        pos++
                        val v = parseUnary() ?: return null
                        -v
                    }
                    else -> parsePostfixPercent()
                }
            }

            private fun parsePostfixPercent(): Double? {
                var v = parsePrimary() ?: return null
                skipSpaces()
                // Support e.g. "25%" -> 0.25 when not followed by a number (modulo is handled in parseMulDiv when followed by operand)
                if (pos < s.length && s[pos] == '%') {
                    val savedPos = pos
                    pos++
                    skipSpaces()
                    val nextStartsNumberOrIdent = pos < s.length &&
                        (s[pos].isDigit() || s[pos].isLetter() || s[pos] == '(' || s[pos] == '.')
                    if (nextStartsNumberOrIdent) {
                        pos = savedPos
                    } else {
                        operatorOrFunctionCount++
                        v /= 100.0
                    }
                }
                return v
            }

            private fun parsePrimary(): Double? {
                skipSpaces()
                if (pos >= s.length) return null

                if (s[pos] == '(') {
                    pos++
                    val inner = parseAddSub() ?: return null
                    skipSpaces()
                    if (pos >= s.length || s[pos] != ')') return null
                    pos++
                    return inner
                }

                if (s[pos].isLetter()) {
                    val start = pos
                    while (pos < s.length && s[pos].isLetter()) pos++
                    val ident = s.substring(start, pos)
                    if (ident == "pi") {
                        operatorOrFunctionCount++
                        return Math.PI
                    }
                    if (ident == "e") {
                        operatorOrFunctionCount++
                        return Math.E
                    }
                    skipSpaces()
                    if (pos >= s.length || s[pos] != '(') return null
                    pos++
                    val arg = parseAddSub() ?: return null
                    skipSpaces()
                    if (pos >= s.length || s[pos] != ')') return null
                    pos++
                    operatorOrFunctionCount++
                    return when (ident) {
                        "sqrt" -> if (arg < 0.0) null else sqrt(arg)
                        "sin" -> sin(arg)
                        "cos" -> cos(arg)
                        "tan" -> tan(arg)
                        "abs" -> abs(arg)
                        "round" -> round(arg)
                        "floor" -> floor(arg)
                        "ceil" -> ceil(arg)
                        "ln" -> if (arg <= 0.0) null else ln(arg)
                        "log" -> if (arg <= 0.0) null else log10(arg)
                        else -> null
                    }
                }

                val start = pos
                var hasDot = false
                while (pos < s.length && (s[pos].isDigit() || (!hasDot && s[pos] == '.'))) {
                    if (s[pos] == '.') hasDot = true
                    pos++
                }
                if (pos == start) return null
                return s.substring(start, pos).toDoubleOrNull()
            }
        }
    }
}
