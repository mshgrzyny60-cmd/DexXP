package com.dexxp.desktop.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.keyboard.DesktopKeyboardManager
import com.dexxp.desktop.model.ClipboardHistoryEntry
import com.dexxp.desktop.model.ClipboardItemKind
import com.dexxp.desktop.state.DesktopSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Secure In-Memory LRU Clipboard History Engine (`Win + V`).
 *
 * - Listens to [ClipboardManager.OnPrimaryClipChangedListener] and maintains up to 20 recent
 *   text snippets, web links, and image `content://` URIs purely in volatile RAM.
 * - Honors Android 13+ [ClipDescription.EXTRA_IS_SENSITIVE] so password-manager copies are never
 *   captured or exposed on an external display.
 * - Supports 1-click paste directly into focused third-party Freeform windows via
 *   `AccessibilityNodeInfo.ACTION_PASTE` or Soft IME / `KEYCODE_PASTE` injection.
 */
class DesktopClipboardManager(
    context: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val pointerFocusBridge: PointerFocusBridge
) {
    private val appContext = context.applicationContext
    private val clipboardManager = appContext.getSystemService(ClipboardManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _history = MutableStateFlow<List<ClipboardHistoryEntry>>(emptyList())
    val history: StateFlow<List<ClipboardHistoryEntry>> = _history.asStateFlow()

    @Volatile
    private var suppressNextClipCallback = false

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (suppressNextClipCallback) {
            suppressNextClipCallback = false
            return@OnPrimaryClipChangedListener
        }
        syncCurrentPrimaryClip()
    }

    init {
        clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        syncCurrentPrimaryClip()
    }

    /**
     * Reads the active system primary clip when allowed by Android 12–15 window-focus policy
     * and inserts/promotes it at the head of the 20-item MRU history list.
     */
    fun syncCurrentPrimaryClip() {
        val cm = clipboardManager ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        if (clip.itemCount <= 0) return

        val description = clip.description
        if (isSensitiveClip(description)) return

        val item = clip.getItemAt(0) ?: return
        val coercedText = runCatching { item.text?.toString()?.trim() }.getOrNull()
        val uri: Uri? = item.uri
        val primaryMime = if (description != null && description.mimeTypeCount > 0) {
            description.getMimeType(0) ?: "text/plain"
        } else {
            "text/plain"
        }

        val entry = buildEntryFromClip(
            text = coercedText,
            uri = uri,
            mimeType = primaryMime
        ) ?: return

        insertOrPromoteEntry(entry)
    }

    private fun buildEntryFromClip(
        text: String?,
        uri: Uri?,
        mimeType: String
    ): ClipboardHistoryEntry? {
        val now = System.currentTimeMillis()
        if (uri != null && (mimeType.startsWith("image/") || text.isNullOrEmpty())) {
            val isImg = mimeType.startsWith("image/") ||
                uri.toString().lowercase().let {
                    it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp")
                }
            val kind = if (isImg) ClipboardItemKind.IMAGE_URI else ClipboardItemKind.LINK
            val label = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.toString()
            return ClipboardHistoryEntry(
                id = UUID.randomUUID().toString(),
                kind = kind,
                previewText = label,
                textValue = text ?: uri.toString(),
                contentUri = uri,
                mimeType = mimeType,
                copiedAtEpochMs = now
            )
        }

        val cleanText = text?.takeIf { it.isNotEmpty() } ?: return null
        val kind = classifyTextKind(cleanText)
        return ClipboardHistoryEntry(
            id = UUID.randomUUID().toString(),
            kind = kind,
            previewText = cleanText.replace('\n', ' ').take(180),
            textValue = cleanText,
            contentUri = if (kind == ClipboardItemKind.LINK) runCatching { Uri.parse(cleanText) }.getOrNull() else null,
            mimeType = "text/plain",
            copiedAtEpochMs = now
        )
    }

    private fun insertOrPromoteEntry(incoming: ClipboardHistoryEntry) {
        _history.update { current ->
            val duplicate = current.firstOrNull { existing ->
                (incoming.textValue != null && existing.textValue == incoming.textValue) ||
                    (incoming.contentUri != null && existing.contentUri == incoming.contentUri)
            }
            val promoted = if (duplicate != null) {
                duplicate.copy(copiedAtEpochMs = incoming.copiedAtEpochMs)
            } else {
                incoming
            }
            val remaining = current.filterNot { it.id == promoted.id }
            val combined = listOf(promoted) + remaining
            if (combined.size <= MAX_CLIP_HISTORY) {
                combined
            } else {
                val pinned = combined.filter { it.isPinned }
                val unpinned = combined.filterNot { it.isPinned }
                (pinned + unpinned).take(MAX_CLIP_HISTORY)
            }
        }
    }

    /**
     * Copies [entry] to the active system clipboard, dismisses the `Win + V` flyout so window
     * focus returns to the target surface, and auto-injects via `ACTION_PASTE` or IME Relay.
     */
    fun pasteEntryIntoActiveWindow(
        entry: ClipboardHistoryEntry,
        keyboardManager: DesktopKeyboardManager
    ) {
        val cm = clipboardManager ?: return
        val clip = if (entry.kind == ClipboardItemKind.IMAGE_URI && entry.contentUri != null) {
            ClipData(
                ClipDescription("DexXP Image Clip", arrayOf(entry.mimeType.ifBlank { "image/*" })),
                ClipData.Item(entry.contentUri)
            )
        } else {
            ClipData.newPlainText("DexXP Clip", entry.textValue.orEmpty())
        }

        suppressNextClipCallback = true
        runCatching { cm.setPrimaryClip(clip) }
        insertOrPromoteEntry(entry.copy(copiedAtEpochMs = System.currentTimeMillis()))

        val shellWasFocused = sessionRepository.needsShellKeyboardFocus.value
        sessionRepository.setClipboardHistoryOpen(false)

        scope.launch {
            delay(FOCUS_SETTLE_DELAY_MS)
            if (shellWasFocused) {
                val handled = pointerFocusBridge.forwardKeyCodeToShell(KeyEvent.KEYCODE_PASTE)
                if (!handled && !entry.textValue.isNullOrEmpty()) {
                    keyboardManager.streamRelayTextToActiveWindow(entry.textValue, "")
                }
            } else {
                val pastedExternally = DesktopPointerAccessibilityService.instance
                    ?.pasteIntoFocusedExternalNode(entry.textValue) == true
                if (!pastedExternally && !entry.textValue.isNullOrEmpty()) {
                    keyboardManager.streamRelayTextToActiveWindow(entry.textValue, "")
                }
            }
        }
    }

    fun togglePin(entryId: String) {
        _history.update { list ->
            list.map { if (it.id == entryId) it.copy(isPinned = !it.isPinned) else it }
        }
    }

    fun removeEntry(entryId: String) {
        _history.update { list -> list.filterNot { it.id == entryId } }
    }

    fun clearUnpinned() {
        _history.update { list -> list.filter { it.isPinned } }
    }

    private fun isSensitiveClip(description: ClipDescription?): Boolean {
        val extras = description?.extras ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
        } else {
            extras.getBoolean(EXTRA_IS_SENSITIVE_COMPAT, false)
        }
    }

    fun shutdown() {
        runCatching { clipboardManager?.removePrimaryClipChangedListener(clipChangedListener) }
        scope.cancel()
    }

    companion object {
        const val MAX_CLIP_HISTORY = 20
        private const val FOCUS_SETTLE_DELAY_MS = 65L
        private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"

        fun classifyTextKind(text: String): ClipboardItemKind {
            val trimmed = text.trim()
            return if (
                !trimmed.contains(' ') &&
                (trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("http://", ignoreCase = true))
            ) {
                ClipboardItemKind.LINK
            } else {
                ClipboardItemKind.TEXT
            }
        }
    }
}
