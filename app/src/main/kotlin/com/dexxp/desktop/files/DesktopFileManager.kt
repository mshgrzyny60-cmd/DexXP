package com.dexxp.desktop.files

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dexxp.desktop.model.DesktopFileItem
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.desktop.desktopSecondaryClick
import com.dexxp.desktop.ui.icons.DesktopIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Interactive Desktop File Provider & Cross-Window Drag-and-Drop Engine.
 *
 * Aggregates user files without requiring `MANAGE_EXTERNAL_STORAGE`:
 * 1. Custom SAF `Desktop` folder (`DocumentsContract` tree URI) if configured by the user;
 * 2. Live `MediaStore.Downloads.EXTERNAL_CONTENT_URI` items observed via [ContentObserver];
 * 3. App-scoped `Desktop` workspace folder shared via [FileProvider].
 *
 * Files are rendered on the Desktop Grid with Compose `Modifier.dragAndDropSource` configured
 * with `View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ` so dragging files directly into
 * active Freeform apps (WhatsApp, Gmail, Chrome, Docs) works seamlessly across windows.
 */
class DesktopFileManager(
    context: Context,
    private val sessionRepository: DesktopSessionRepository
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _files = MutableStateFlow<List<DesktopFileItem>>(emptyList())
    val files: StateFlow<List<DesktopFileItem>> = _files.asStateFlow()

    private val downloadsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            reloadFiles()
        }
    }

    init {
        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                true,
                downloadsObserver
            )
        }
        scope.launch {
            sessionRepository.customDesktopTreeUri.collect {
                reloadFilesInternal()
            }
        }
    }

    fun reloadFiles() {
        scope.launch { reloadFilesInternal() }
    }

    private suspend fun reloadFilesInternal() {
        val loaded = withContext(Dispatchers.IO) {
            val result = ArrayList<DesktopFileItem>(MAX_DESKTOP_FILES)

            // 1. Custom SAF Desktop folder (if selected by user)
            sessionRepository.customDesktopTreeUri.value?.let { treeUri ->
                result.addAll(querySafTreeFolder(treeUri))
            }

            // 2. MediaStore Downloads recent items
            if (result.size < MAX_DESKTOP_FILES) {
                result.addAll(queryMediaStoreDownloads(MAX_DESKTOP_FILES - result.size))
            }

            // 3. App Desktop workspace folder (ensuring at least 1 ready-to-drag reference file)
            if (result.size < MAX_DESKTOP_FILES) {
                result.addAll(queryWorkspaceDesktopFolder(MAX_DESKTOP_FILES - result.size))
            }

            result.take(MAX_DESKTOP_FILES)
        }
        _files.value = loaded
    }

    private fun querySafTreeFolder(treeUri: Uri): List<DesktopFileItem> = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<DesktopFileItem>()
        appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext() && out.size < MAX_DESKTOP_FILES) {
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val childDocId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                val itemUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                out.add(
                    DesktopFileItem(
                        uri = itemUri,
                        displayName = name,
                        mimeType = mime,
                        sizeBytes = cursor.getLong(sizeCol),
                        lastModifiedMs = cursor.getLong(modCol),
                        isImage = mime.startsWith("image/")
                    )
                )
            }
        }
        out.sortedByDescending { it.lastModifiedMs }
    }.getOrDefault(emptyList())

    private fun queryMediaStoreDownloads(limit: Int): List<DesktopFileItem> = runCatching {
        if (limit <= 0) return emptyList()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        val out = ArrayList<DesktopFileItem>(limit)

        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)

            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    id
                )
                out.add(
                    DesktopFileItem(
                        uri = contentUri,
                        displayName = name,
                        mimeType = mime,
                        sizeBytes = cursor.getLong(sizeCol),
                        lastModifiedMs = cursor.getLong(modCol) * 1000L,
                        isImage = mime.startsWith("image/")
                    )
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun queryWorkspaceDesktopFolder(limit: Int): List<DesktopFileItem> = runCatching {
        if (limit <= 0) return emptyList()
        val dir = appContext.getExternalFilesDir("Desktop") ?: return emptyList()
        if (!dir.exists()) dir.mkdirs()

        val existing = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (existing.isEmpty()) {
            val welcome = File(dir, "DexXP_Shortcuts_Guide.txt")
            welcome.writeText(
                buildString {
                    appendLine("DexXP Desktop Environment — Quick Shortcuts")
                    appendLine("• Alt + Space      : Universal Command Palette, Fuzzy Search & Calculator")
                    appendLine("• Super + Tab      : Mission Control / Bird's-Eye Window Overview")
                    appendLine("• Ctrl + Alt + T   : Integrated Local Developer Terminal Console")
                    appendLine("• Alt + Tab        : Cycle running Freeform windows")
                    appendLine("• Super / Meta     : Toggle Start Menu")
                    appendLine("• Super + V        : Clipboard History & 1-Click Paste")
                    appendLine("• Super + D        : Minimize / Restore all windows")
                    appendLine("• Super + Arrows   : Snap Left 50%, Right 50%, or Maximize")
                    appendLine("• Drag Desktop File: Drop directly into Gmail, Chrome, or WhatsApp")
                }
            )
        }

        val authority = "${appContext.packageName}.fileprovider"
        (dir.listFiles()?.filter { it.isFile } ?: emptyList())
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull { file ->
                val uri = runCatching {
                    FileProvider.getUriForFile(appContext, authority, file)
                }.getOrNull() ?: return@mapNotNull null
                val mime = guessMimeFromExtension(file.extension)
                DesktopFileItem(
                    uri = uri,
                    displayName = file.name,
                    mimeType = mime,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    isImage = mime.startsWith("image/")
                )
            }
    }.getOrDefault(emptyList())

    fun buildDragClipData(file: DesktopFileItem): ClipData {
        val description = ClipDescription(
            file.displayName,
            arrayOf(file.mimeType.ifBlank { "*/*" })
        )
        return ClipData(description, ClipData.Item(file.uri))
    }

    fun shareFileExternally(file: DesktopFileItem, launchContext: Context? = null) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, file.displayName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { (launchContext ?: appContext).startActivity(chooser) }
    }

    private fun guessMimeFromExtension(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun shutdown() {
        scope.cancel()
        runCatching { appContext.contentResolver.unregisterContentObserver(downloadsObserver) }
    }

    companion object {
        private const val MAX_DESKTOP_FILES = 14
    }
}

/**
 * Interactive Desktop File Tile supporting native cross-window Drag-and-Drop (`ClipData` +
 * `DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`) into external Freeform apps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
fun DesktopFileTile(
    file: DesktopFileItem,
    fileManager: DesktopFileManager,
    onOpenFile: (DesktopFileItem) -> Unit,
    onShareFile: (DesktopFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(92.dp)
                .clip(RoundedCornerShape(14.dp))
                .hoverable(interactionSource)
                .background(
                    if (isHovered || menuOpen) Color.White.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .border(
                    width = if (isHovered || menuOpen) 1.dp else 0.dp,
                    color = if (isHovered || menuOpen) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                    shape = RoundedCornerShape(14.dp)
                )
                .dragAndDropSource(
                    transferData = { _ ->
                        DragAndDropTransferData(
                            clipData = fileManager.buildDragClipData(file),
                            flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                        )
                    }
                )
                .desktopSecondaryClick { menuOpen = true }
                .combinedClickable(
                    onClick = { onOpenFile(file) },
                    onLongClick = { menuOpen = true }
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (file.isImage) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.88f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (file.isImage) DesktopIcons.ImageFile else DesktopIcons.FileGeneric,
                    contentDescription = file.displayName,
                    tint = if (file.isImage) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = file.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text("Open in Freeform Window") },
                leadingIcon = { Icon(DesktopIcons.WindowFreeform, null, Modifier.size(18.dp)) },
                onClick = {
                    menuOpen = false
                    onOpenFile(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Share / Send to App") },
                leadingIcon = { Icon(DesktopIcons.Send, null, Modifier.size(18.dp)) },
                onClick = {
                    menuOpen = false
                    onShareFile(file)
                }
            )
        }
    }
}
