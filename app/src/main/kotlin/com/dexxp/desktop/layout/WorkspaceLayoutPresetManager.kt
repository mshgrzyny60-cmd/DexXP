package com.dexxp.desktop.layout

import android.content.Context
import android.graphics.Rect
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.model.AppCategory
import com.dexxp.desktop.model.DesktopAppItem
import com.dexxp.desktop.model.SavedWindowSlot
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.model.WorkspaceLayoutPreset
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.windowing.WindowSnappingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Saved Workspace Snap Groups & Multi-Window Layout Preset Engine.
 *
 * - Provides smart built-in multi-window Snap Groups (e.g., `"Editing Suite"` = Creative/Media 70% +
 *   Files 30%; `"Research Split"` = Browser 50% + Docs/Notes 50%) resolved dynamically against
 *   installed packages on the device.
 * - Allows saving live Freeform window arrangements as named custom presets stored with normalized
 *   `[0..1]` monitor fractions so saved layouts adapt cleanly across 1080p, 1440p, and 4K displays.
 * - Executes 1-Click Launch via [AppLauncher.launchPackageWithExactBounds].
 */
class WorkspaceLayoutPresetManager(
    context: Context,
    private val sessionRepository: DesktopSessionRepository,
    private val appRepository: AppRepository,
    private val appLauncher: AppLauncher
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var customPresets: List<WorkspaceLayoutPreset> = loadCustomPresetsFromPrefs()

    private val _presets = MutableStateFlow<List<WorkspaceLayoutPreset>>(emptyList())
    val presets: StateFlow<List<WorkspaceLayoutPreset>> = _presets.asStateFlow()

    init {
        scope.launch {
            appRepository.apps.collect { catalog ->
                rebuildCombinedPresets(catalog)
            }
        }
    }

    /**
     * Captures all currently visible Freeform windows on the external display as a named preset.
     */
    fun saveCurrentArrangementAsPreset(rawName: String): WorkspaceLayoutPreset? {
        val visibleWindows = sessionRepository.runningWindows.value.filterNot { it.isMinimized }
        if (visibleWindows.isEmpty()) return null

        val displayInfo = sessionRepository.activeDisplay.value
        val widthPx = (displayInfo?.widthPx ?: 1920).coerceAtLeast(320)
        val heightPx = (displayInfo?.heightPx ?: 1080).coerceAtLeast(240)
        val density = (displayInfo?.scaledDensityDpi ?: 160) / 160f
        val taskbarPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()
        val usableHeight = (heightPx - taskbarPx).coerceAtLeast(240)

        val slots = visibleWindows.sortedBy { it.lastActiveEpochMs }.map { win ->
            val b = win.bounds
            val leftF = (b.left.toFloat() / widthPx.toFloat()).coerceIn(0f, 0.92f)
            val topF = (b.top.toFloat() / usableHeight.toFloat()).coerceIn(0f, 0.90f)
            val rightF = (b.right.toFloat() / widthPx.toFloat()).coerceIn(leftF + 0.15f, 1f)
            val bottomF = (b.bottom.toFloat() / usableHeight.toFloat()).coerceIn(topF + 0.15f, 1f)
            SavedWindowSlot(
                packageName = win.packageName,
                label = win.label,
                leftFraction = leftF,
                topFraction = topF,
                rightFraction = rightF,
                bottomFraction = bottomF,
                snapMode = win.snapMode
            )
        }

        val cleanName = rawName.trim().ifEmpty {
            "Snap Group (${slots.joinToString(" + ") { it.label.take(10) }})"
        }
        val subtitle = slots.joinToString(" + ") { slot ->
            val pct = ((slot.rightFraction - slot.leftFraction) * 100f).roundToInt().coerceIn(10, 100)
            "${slot.label} $pct%"
        }

        val created = WorkspaceLayoutPreset(
            id = "custom_${UUID.randomUUID()}",
            name = cleanName,
            subtitle = subtitle,
            slots = slots,
            isBuiltIn = false
        )

        customPresets = listOf(created) + customPresets.take(MAX_CUSTOM_PRESETS - 1)
        persistCustomPresets()
        rebuildCombinedPresets(appRepository.apps.value)
        return created
    }

    fun deleteCustomPreset(presetId: String) {
        val updated = customPresets.filterNot { it.id == presetId }
        if (updated.size != customPresets.size) {
            customPresets = updated
            persistCustomPresets()
            rebuildCombinedPresets(appRepository.apps.value)
        }
    }

    fun deletePreset(presetId: String) = deleteCustomPreset(presetId)

    /**
     * Opens and positions all apps in [preset] into their exact saved normalized `Rect` bounds.
     */
    fun launchPreset(preset: WorkspaceLayoutPreset, launchContext: Context? = null): Int {
        if (preset.slots.isEmpty()) return 0

        val displayInfo = sessionRepository.activeDisplay.value
        val widthPx = (displayInfo?.widthPx ?: 1920).coerceAtLeast(640)
        val heightPx = (displayInfo?.heightPx ?: 1080).coerceAtLeast(480)
        val dpi = displayInfo?.scaledDensityDpi ?: 160
        val density = dpi / 160f
        val taskbarPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()
        val usableHeight = (heightPx - taskbarPx).coerceAtLeast(320)
        val marginPx = (WindowSnappingController.SNAP_MARGIN_DP * density).roundToInt()

        sessionRepository.dismissFlyouts()
        appLauncher.sendSecondaryHomeToFront(launchContext)

        var launchedCount = 0
        for (slot in preset.slots) {
            val rect = denormalizeSlotBounds(
                slot = slot,
                displayWidthPx = widthPx,
                usableHeightPx = usableHeight,
                marginPx = marginPx
            )
            if (appLauncher.launchPackageWithExactBounds(
                    packageName = slot.packageName,
                    exactBounds = rect,
                    snapMode = slot.snapMode,
                    launchContext = launchContext
                )
            ) {
                launchedCount++
            }
        }
        return launchedCount
    }

    private fun rebuildCombinedPresets(catalog: List<DesktopAppItem>) {
        val builtIns = buildSmartDefaultPresets(catalog)
        _presets.value = customPresets + builtIns
    }

    private fun buildSmartDefaultPresets(catalog: List<DesktopAppItem>): List<WorkspaceLayoutPreset> {
        if (catalog.isEmpty()) return emptyList()
        val byPkg = catalog.associateBy { it.packageName }

        fun pickFirstInstalled(candidates: List<String>, fallbackCategory: AppCategory? = null): DesktopAppItem? {
            for (pkg in candidates) {
                byPkg[pkg]?.let { return it }
            }
            if (fallbackCategory != null) {
                catalog.firstOrNull { it.category == fallbackCategory }?.let { return it }
            }
            return catalog.firstOrNull()
        }

        val out = ArrayList<WorkspaceLayoutPreset>(3)

        // 1. Editing Suite: Creative / Studio App 70% Left + File Manager 30% Right
        val studioApp = pickFirstInstalled(CREATIVE_STUDIO_PACKAGES, AppCategory.MEDIA)
        val filesApp = pickFirstInstalled(FILE_MANAGER_PACKAGES, AppCategory.SYSTEM_UTILITIES)
            ?.takeIf { it.packageName != studioApp?.packageName }
            ?: catalog.firstOrNull { it.packageName != studioApp?.packageName }

        if (studioApp != null && filesApp != null) {
            out.add(
                WorkspaceLayoutPreset(
                    id = "builtin_editing_suite",
                    name = "Editing Suite",
                    subtitle = "${studioApp.label} 70% + ${filesApp.label} 30%",
                    slots = listOf(
                        SavedWindowSlot(
                            packageName = studioApp.packageName,
                            label = studioApp.label,
                            leftFraction = 0.005f,
                            topFraction = 0.010f,
                            rightFraction = 0.697f,
                            bottomFraction = 0.990f,
                            snapMode = WindowSnapMode.FREEFORM_CASCADED
                        ),
                        SavedWindowSlot(
                            packageName = filesApp.packageName,
                            label = filesApp.label,
                            leftFraction = 0.703f,
                            topFraction = 0.010f,
                            rightFraction = 0.995f,
                            bottomFraction = 0.990f,
                            snapMode = WindowSnapMode.FREEFORM_CASCADED
                        )
                    ),
                    isBuiltIn = true
                )
            )
        }

        // 2. Research Split: Browser 50% Left + Docs / Notes / Mail 50% Right
        val browserApp = pickFirstInstalled(BROWSER_PACKAGES, AppCategory.INTERNET_SOCIAL)
        val docsApp = pickFirstInstalled(DOCS_NOTES_PACKAGES, AppCategory.PRODUCTIVITY)
            ?.takeIf { it.packageName != browserApp?.packageName }
            ?: catalog.firstOrNull { it.packageName != browserApp?.packageName }

        if (browserApp != null && docsApp != null) {
            out.add(
                WorkspaceLayoutPreset(
                    id = "builtin_research_split",
                    name = "Research",
                    subtitle = "${browserApp.label} 50% + ${docsApp.label} 50%",
                    slots = listOf(
                        SavedWindowSlot(
                            packageName = browserApp.packageName,
                            label = browserApp.label,
                            leftFraction = 0.005f,
                            topFraction = 0.010f,
                            rightFraction = 0.497f,
                            bottomFraction = 0.990f,
                            snapMode = WindowSnapMode.LEFT_HALF
                        ),
                        SavedWindowSlot(
                            packageName = docsApp.packageName,
                            label = docsApp.label,
                            leftFraction = 0.503f,
                            topFraction = 0.010f,
                            rightFraction = 0.995f,
                            bottomFraction = 0.990f,
                            snapMode = WindowSnapMode.RIGHT_HALF
                        )
                    ),
                    isBuiltIn = true
                )
            )
        }

        return out
    }

    private fun persistCustomPresets() {
        val array = JSONArray()
        for (preset in customPresets) {
            val slotsJson = JSONArray()
            for (slot in preset.slots) {
                slotsJson.put(
                    JSONObject().apply {
                        put("pkg", slot.packageName)
                        put("label", slot.label)
                        put("l", slot.leftFraction.toDouble())
                        put("t", slot.topFraction.toDouble())
                        put("r", slot.rightFraction.toDouble())
                        put("b", slot.bottomFraction.toDouble())
                        put("snap", slot.snapMode.name)
                    }
                )
            }
            array.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("subtitle", preset.subtitle)
                    put("slots", slotsJson)
                }
            )
        }
        prefs.edit().putString(KEY_SAVED_PRESETS_JSON, array.toString()).apply()
    }

    private fun loadCustomPresetsFromPrefs(): List<WorkspaceLayoutPreset> = runCatching {
        val raw = prefs.getString(KEY_SAVED_PRESETS_JSON, null) ?: return emptyList()
        val array = JSONArray(raw)
        val out = ArrayList<WorkspaceLayoutPreset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val slotsArr = obj.getJSONArray("slots")
            val slots = ArrayList<SavedWindowSlot>(slotsArr.length())
            for (j in 0 until slotsArr.length()) {
                val s = slotsArr.getJSONObject(j)
                val snapName = s.optString("snap", WindowSnapMode.FREEFORM_CASCADED.name)
                val snapMode = WindowSnapMode.entries.firstOrNull { it.name == snapName }
                    ?: WindowSnapMode.FREEFORM_CASCADED
                slots.add(
                    SavedWindowSlot(
                        packageName = s.getString("pkg"),
                        label = s.optString("label", s.getString("pkg").substringAfterLast('.')),
                        leftFraction = s.getDouble("l").toFloat().coerceIn(0f, 0.95f),
                        topFraction = s.getDouble("t").toFloat().coerceIn(0f, 0.95f),
                        rightFraction = s.getDouble("r").toFloat().coerceIn(0.05f, 1f),
                        bottomFraction = s.getDouble("b").toFloat().coerceIn(0.05f, 1f),
                        snapMode = snapMode
                    )
                )
            }
            if (slots.isNotEmpty()) {
                out.add(
                    WorkspaceLayoutPreset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        subtitle = obj.optString("subtitle", "${slots.size} apps"),
                        slots = slots,
                        isBuiltIn = false
                    )
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val PREFS_NAME = "dexxp_layout_presets"
        private const val KEY_SAVED_PRESETS_JSON = "saved_presets_v1"
        private const val MAX_CUSTOM_PRESETS = 10

        private val CREATIVE_STUDIO_PACKAGES = listOf(
            "com.lemon.lvoverseas",
            "com.capcut.editor",
            "com.adobe.lrmobile",
            "com.nexstreaming.app.kinemasterfree",
            "com.camerasideas.instashot",
            "com.google.android.apps.photos",
            "org.videolan.vlc",
            "com.sec.android.gallery3d"
        )

        private val FILE_MANAGER_PACKAGES = listOf(
            "com.google.android.apps.nbu.files",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "pl.solidexplorer2",
            "com.mi.android.globalFileexplorer",
            "com.alphainventor.filemanager"
        )

        private val BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "com.brave.browser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.vivaldi.browser"
        )

        private val DOCS_NOTES_PACKAGES = listOf(
            "com.google.android.apps.docs.editors.docs",
            "com.google.android.keep",
            "md.obsidian",
            "notion.id",
            "com.microsoft.office.word",
            "com.google.android.gm"
        )

        fun denormalizeSlotBounds(
            slot: SavedWindowSlot,
            displayWidthPx: Int,
            usableHeightPx: Int,
            marginPx: Int
        ): Rect {
            val left = (slot.leftFraction * displayWidthPx).roundToInt()
                .coerceIn(marginPx, (displayWidthPx - 240).coerceAtLeast(marginPx))
            val top = (slot.topFraction * usableHeightPx).roundToInt()
                .coerceIn(marginPx, (usableHeightPx - 200).coerceAtLeast(marginPx))
            val right = (slot.rightFraction * displayWidthPx).roundToInt()
                .coerceIn((left + 220).coerceAtMost(displayWidthPx - marginPx), displayWidthPx - marginPx)
            val bottom = (slot.bottomFraction * usableHeightPx).roundToInt()
                .coerceIn((top + 180).coerceAtMost(usableHeightPx - marginPx), usableHeightPx - marginPx)
            return Rect(left, top, right, bottom)
        }
    }
}

/**
 * Material 3 Saved Snap Groups & Layout Presets panel embedded in the Workspace Switcher Flyout.
 */
@Composable
fun WorkspaceLayoutPresetsSection(
    presets: List<WorkspaceLayoutPreset>,
    canSaveCurrentLayout: Boolean,
    onLaunchPreset: (WorkspaceLayoutPreset) -> Unit,
    onSaveCurrentPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showNameField by remember { mutableStateOf(false) }
    var presetNameDraft by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { onInputFocusChanged(false) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = DesktopIcons.LayoutPreset,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Snap Groups & Layout Presets",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (canSaveCurrentLayout && !showNameField) {
                Surface(
                    onClick = { showNameField = true },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "+ Save Current Layout",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (showNameField) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (presetNameDraft.isEmpty()) {
                        Text(
                            text = "Preset name (e.g., Studio 70/30)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = presetNameDraft,
                        onValueChange = { presetNameDraft = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                onSaveCurrentPreset(presetNameDraft)
                                presetNameDraft = ""
                                showNameField = false
                                onInputFocusChanged(false)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { onInputFocusChanged(it.isFocused) }
                    )
                }
                Surface(
                    onClick = {
                        onSaveCurrentPreset(presetNameDraft)
                        presetNameDraft = ""
                        showNameField = false
                        onInputFocusChanged(false)
                    },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "Save",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        showNameField = false
                        presetNameDraft = ""
                        onInputFocusChanged(false)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = DesktopIcons.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        presets.take(5).forEach { preset ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onLaunchPreset(preset) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DesktopIcons.LayoutPreset,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = preset.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    onClick = { onLaunchPreset(preset) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Launch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (!preset.isBuiltIn) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { onDeletePreset(preset.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = DesktopIcons.Close,
                            contentDescription = "Delete Preset",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
