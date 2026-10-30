package com.dexxp.desktop.bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.dexxp.desktop.model.DesktopEnvironmentMode
import com.dexxp.desktop.model.PointerState
import com.dexxp.desktop.model.SnapPreviewState
import com.dexxp.desktop.state.DesktopSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Top-most hardware-accelerated secondary display HUD (`Layer 26 / 29`) combining three
 * zero-touch-interference surfaces in a single `WindowManager` layer:
 * 1. External Monitor Luminance Dimmer (enabling real brightness dimming over HDMI/DP monitors);
 * 2. Semi-transparent Window Snap Preview box (rendered above third-party Freeform windows);
 * 3. `36dp x 36dp` GPU `RenderNode` Mouse Cursor sprite (`translationX/Y` matrix updates only).
 */
class CursorOverlayController(
    private val appContext: Context,
    private val pointerFocusBridge: PointerFocusBridge,
    private val sessionRepository: DesktopSessionRepository
) {
    private var overlayWindowManager: WindowManager? = null
    private var containerView: FrameLayout? = null
    private var dimmerView: View? = null
    private var snapHudView: SnapZoneHudView? = null
    private var scope: CoroutineScope? = null

    val isAttached: Boolean
        get() = containerView?.isAttachedToWindow == true

    fun attachToDisplay(display: Display): Boolean {
        detach()
        if (!display.isValid) return false

        val a11yService = DesktopPointerAccessibilityService.instance
        val windowType: Int
        val windowContext: Context

        if (a11yService != null) {
            val ctx = runCatching {
                a11yService.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    null
                )
            }.getOrNull()
            if (ctx != null) {
                windowContext = ctx
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Settings.canDrawOverlays(appContext)) {
                val appOverlayCtx = runCatching {
                    appContext.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }.getOrNull() ?: return false
                windowContext = appOverlayCtx
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                return false
            }
        } else if (Settings.canDrawOverlays(appContext)) {
            val appOverlayCtx = runCatching {
                appContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }.getOrNull() ?: return false
            windowContext = appOverlayCtx
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            return false
        }

        val wm = windowContext.getSystemService(WindowManager::class.java) ?: return false
        val dimmer = View(windowContext).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
        }
        val snapHud = SnapZoneHudView(windowContext).apply {
            visibility = View.GONE
        }

        val root = FrameLayout(windowContext).apply {
            clipChildren = false
            clipToPadding = false
            addView(
                dimmer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                snapHud,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DexXPCursorAndSnapHud"
        }

        val added = runCatching {
            wm.addView(root, params)
            true
        }.onFailure { err ->
            Log.w(TAG, "Unable to attach top-level HUD overlay on display ${display.displayId}", err)
        }.getOrDefault(false)

        if (!added) return false

        overlayWindowManager = wm
        containerView = root
        dimmerView = dimmer
        snapHudView = snapHud

        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = coroutineScope

        coroutineScope.launch {
            sessionRepository.snapPreview.collect { updateSnapPreview(it) }
        }
        coroutineScope.launch {
            sessionRepository.externalBrightnessFraction.collect { updateBrightnessDimmer(it) }
        }
        return true
    }

    private fun updateSnapPreview(preview: SnapPreviewState?) {
        val hud = snapHudView ?: return
        if (preview == null) {
            hud.visibility = View.GONE
        } else {
            hud.visibility = View.VISIBLE
            hud.setPreviewState(preview)
        }
    }

    private fun updateBrightnessDimmer(brightnessFraction: Float) {
        val dimmer = dimmerView ?: return
        val dimAlpha = ((1f - brightnessFraction) * 0.78f).coerceIn(0f, 0.75f)
        if (dimAlpha <= 0.01f) {
            dimmer.visibility = View.GONE
        } else {
            dimmer.alpha = dimAlpha
            dimmer.visibility = View.VISIBLE
        }
    }

    fun detach() {
        scope?.cancel()
        scope = null

        val root = containerView
        val wm = overlayWindowManager
        if (root != null && wm != null) {
            runCatching { wm.removeViewImmediate(root) }
        }
        containerView = null
        dimmerView = null
        snapHudView = null
        overlayWindowManager = null
    }

    private class SnapZoneHudView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val drawRect = RectF()
        private val pillRect = RectF()
        private var labelText: String = ""

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(54, 138, 180, 248)
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * d
            color = Color.argb(220, 138, 180, 248)
        }

        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(235, 30, 38, 54)
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * d
            textAlign = Paint.Align.CENTER
        }

        fun setPreviewState(state: SnapPreviewState) {
            drawRect.set(state.previewBounds)
            labelText = state.snapMode.label
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (drawRect.isEmpty) return
            val radius = 18f * d
            canvas.drawRoundRect(drawRect, radius, radius, fillPaint)
            canvas.drawRoundRect(drawRect, radius, radius, borderPaint)

            if (labelText.isNotEmpty()) {
                val cx = drawRect.centerX()
                val cy = drawRect.centerY()
                val textW = textPaint.measureText(labelText)
                pillRect.set(
                    cx - textW / 2f - 16f * d,
                    cy - 16f * d,
                    cx + textW / 2f + 16f * d,
                    cy + 16f * d
                )
                canvas.drawRoundRect(pillRect, 16f * d, 16f * d, pillPaint)
                canvas.drawText(labelText, cx, cy + 4.5f * d, textPaint)
            }
        }
    }

    companion object {
        private const val TAG = "CursorOverlayController"
    }
}
