package com.dexxp.desktop.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dexxp.desktop.DexXpApplication
import com.dexxp.desktop.model.ExternalTextInputState
import com.dexxp.desktop.model.ExternalWindowFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Non-root multi-display Accessibility Bridge (Android 12 – Android 15):
 *
 * 1. Injects virtual touchpad clicks, context-menu long-presses, drags, and scroll wheels onto
 *    external displays via `GestureDescription.Builder().setDisplayId(displayId)`.
 * 2. Intercepts global hardware keyboard shortcuts (`Alt + Tab`, `Super`, `Super + V`, `Super + D`,
 *    `Super + Arrows`, `Super + Ctrl + Arrows`) via [onKeyEvent].
 * 3. Streams live external Freeform window coordinates (`getWindowsOnAllDisplays()`) to
 *    [com.dexxp.desktop.windowing.WindowTitlebarOverlayController] and handles `ACTION_PASTE`
 *    (`Win + V`), `ACTION_SET_TEXT` (IME Relay), and `GLOBAL_ACTION_BACK` (Window Close).
 */
class DesktopPointerAccessibilityService : AccessibilityService() {

    private var pendingRefreshJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastObservedWindowFingerprint: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val app = applicationContext as? DexXpApplication
        app?.sessionRepository?.refreshSystemReadiness()
        refreshExternalDisplayWindows()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val app = applicationContext as? DexXpApplication ?: return false
        if (app.sessionRepository.activeDisplay.value == null) return false
        return app.keyboardManager.onHardwareKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleThrottledRefresh(checkEditor = false)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                scheduleThrottledRefresh(checkEditor = true)
            }
            else -> Unit
        }
    }

    private fun scheduleThrottledRefresh(checkEditor: Boolean) {
        if (pendingRefreshJob?.isActive == true) return
        pendingRefreshJob = serviceScope.launch {
            delay(150)
            refreshExternalDisplayWindows()
            if (checkEditor) {
                inspectFocusedExternalEditor()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingRefreshJob?.cancel()
        serviceScope.cancel()
        if (instance === this) {
            instance = null
        }
        val app = applicationContext as? DexXpApplication
        app?.pointerFocusBridge?.updateDetectedExternalWindows(emptyList(), emptyList(), null)
        app?.sessionRepository?.updateExternalTextInputState(ExternalTextInputState())
        app?.sessionRepository?.refreshSystemReadiness()
        super.onDestroy()
    }

    fun refreshExternalDisplayWindows() {
        val app = applicationContext as? DexXpApplication ?: return
        val activeDisplay = app.sessionRepository.activeDisplay.value
        if (activeDisplay == null) {
            app.pointerFocusBridge.updateDetectedExternalWindows(emptyList(), emptyList(), null)
            return
        }
        val targetDisplayId = activeDisplay.displayId
        if (targetDisplayId == Display.DEFAULT_DISPLAY) return

        val windowsByDisplay = runCatching { windowsOnAllDisplays }.getOrNull() ?: return
        val displayWindows: List<AccessibilityWindowInfo> = windowsByDisplay.get(targetDisplayId)
            ?: emptyList()

        val selfPkg = packageName
        val detectedRects = ArrayList<Rect>(displayWindows.size)
        val frames = ArrayList<ExternalWindowFrame>(displayWindows.size)
        var focusedExternalPkg: String? = null

        for (win in displayWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val rootPkg = runCatching { win.root?.packageName?.toString() }.getOrNull() ?: continue
            if (rootPkg == "com.android.systemui") continue
            if (rootPkg == selfPkg) {
                val hasRunningWebApp = app.sessionRepository.runningWindows.value.any {
                    it.packageName.startsWith("dexxp.webapp") && !it.isMinimized
                }
                if (!hasRunningWebApp) continue
            }

            val rect = Rect()
            win.getBoundsInScreen(rect)
            if (rect.isEmpty) continue

            detectedRects.add(rect)
            frames.add(
                ExternalWindowFrame(
                    windowId = win.id,
                    packageName = rootPkg,
                    title = runCatching { win.title?.toString() }.getOrNull(),
                    bounds = rect,
                    isFocused = win.isFocused,
                    isActive = win.isActive
                )
            )
            if (win.isFocused) {
                focusedExternalPkg = rootPkg
            }
        }

        val fingerprint = (focusedExternalPkg?.hashCode() ?: 0) * 31 + frames.sumOf { it.bounds.hashCode() } + frames.size
        if (fingerprint == lastObservedWindowFingerprint && frames.isNotEmpty()) {
            return
        }
        lastObservedWindowFingerprint = fingerprint

        app.pointerFocusBridge.updateDetectedExternalWindows(
            bounds = detectedRects,
            frames = frames,
            focusedPackage = focusedExternalPkg
        )
    }

    private fun inspectFocusedExternalEditor() {
        val app = applicationContext as? DexXpApplication ?: return
        val editable = findFocusedExternalEditableNode()
        if (editable != null) {
            val pkg = editable.packageName?.toString()
            val currentText = if (!editable.isPassword) {
                editable.text?.toString().orEmpty()
            } else {
                ""
            }
            app.sessionRepository.updateExternalTextInputState(
                ExternalTextInputState(
                    isExternalFieldFocused = true,
                    targetPackageName = pkg,
                    currentTextHint = currentText
                )
            )
        } else {
            app.sessionRepository.updateExternalTextInputState(ExternalTextInputState())
        }
    }

    private fun findFocusedExternalEditableNode(): AccessibilityNodeInfo? {
        val app = applicationContext as? DexXpApplication ?: return null
        val targetDisplayId = app.sessionRepository.activeDisplay.value?.displayId
            ?: return null
        if (targetDisplayId == Display.DEFAULT_DISPLAY) return null

        val windows = runCatching { windowsOnAllDisplays.get(targetDisplayId) }.getOrNull()
            ?: return null
        val selfPkg = packageName

        for (win in windows) {
            if (!win.isFocused && !win.isActive) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == selfPkg) continue
            val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedInput != null && focusedInput.isEditable) {
                return focusedInput
            }
        }
        return null
    }

    /**
     * Injects the active clipboard item into the focused external Freeform window via
     * [AccessibilityNodeInfo.ACTION_PASTE], falling back to [ACTION_SET_TEXT] concatenation.
     */
    fun pasteIntoFocusedExternalNode(fallbackText: String?): Boolean {
        val node = findFocusedExternalEditableNode() ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return true
        }
        if (!fallbackText.isNullOrEmpty()) {
            val existing = if (!node.isPassword) node.text?.toString().orEmpty() else ""
            val merged = existing + fallbackText
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    merged
                )
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Dispatches `GLOBAL_ACTION_BACK` when closing an active third-party Freeform window.
     */
    fun dispatchGlobalBackForClose(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun setTextOnFocusedExternalNode(text: String): Boolean {
        val node = findFocusedExternalEditableNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performImeEnterOnFocusedExternalNode(): Boolean {
        val node = findFocusedExternalEditableNode() ?: return false
        return node.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        )
    }

    fun dispatchTapOnDisplay(
        displayId: Int,
        x: Float,
        y: Float,
        durationMs: Long = 48L
    ): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(16L, 1000L)
        )
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchDragOnDisplay(
        displayId: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 220L
    ): Boolean {
        if (hypot(endX - startX, endY - startY) < 2f) return false
        val path = Path().apply {
            moveTo(startX.coerceAtLeast(1f), startY.coerceAtLeast(1f))
            lineTo(endX.coerceAtLeast(1f), endY.coerceAtLeast(1f))
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(50L, 1500L)
        )
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchScrollSwipeOnDisplay(
        displayId: Int,
        centerX: Float,
        centerY: Float,
        scrollDeltaX: Float,
        scrollDeltaY: Float
    ): Boolean {
        val app = applicationContext as? DexXpApplication ?: return false
        val display = app.sessionRepository.activeDisplay.value
        val maxW = ((display?.widthPx ?: 1920) - 8).toFloat()
        val maxH = ((display?.heightPx ?: 1080) - 8).toFloat()

        val sx = centerX.coerceIn(8f, maxW)
        val sy = centerY.coerceIn(8f, maxH)
        val ex = (sx + scrollDeltaX).coerceIn(8f, maxW)
        val ey = (sy + scrollDeltaY).coerceIn(8f, maxH)
        if (hypot(ex - sx, ey - sy) < 4f) return false

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 95L)
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: DesktopPointerAccessibilityService? = null
            private set
    }
}
