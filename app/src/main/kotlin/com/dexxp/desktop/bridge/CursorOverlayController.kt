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
import com.dexxp.desktop.model.PointerState
import com.dexxp.desktop.model.SnapPreviewState
import com.dexxp.desktop.state.DesktopSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Top-most hardware-accelerated secondary display HUD (`TYPE_ACCESSIBILITY_OVERLAY` /
 * `TYPE_APPLICATION_OVERLAY`) combining three zero-touch-interference surfaces in a single
 * `WindowManager` layer above all third-party apps (Chrome, CapCut, video players) and the Taskbar:
 * 1. External Monitor Luminance Dimmer (enabling real brightness dimming over HDMI/DP monitors);
 * 2. Semi-transparent Window Snap Preview box (rendered above third-party Freeform windows);
 * 3. `28dp x 28dp` GPU `RenderNode` Mouse Cursor sprite (`translationX/Y` matrix updates only).
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
    private var cursorSpriteView: CursorSpriteView? = null
    private var scope: CoroutineScope? = null

    private val _isAttachedFlow = MutableStateFlow(false)
    val isAttachedFlow: StateFlow<Boolean> = _isAttachedFlow.asStateFlow()

    @Volatile
    private var attachedWithAccessibilityOverlay: Boolean = false

    val isAttached: Boolean
        get() = containerView?.isAttachedToWindow == true

    val isAccessibilityOverlay: Boolean
        get() = attachedWithAccessibilityOverlay && isAttached

    fun attachToDisplay(display: Display, scaledDisplayContext: Context? = null): Boolean {
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
        val metricsDensity = (scaledDisplayContext ?: windowContext).resources.displayMetrics.density
            .coerceAtLeast(0.75f)
        val cursorSizePx = (CURSOR_SIZE_DP * metricsDensity).roundToInt().coerceAtLeast(24)

        val dimmer = View(windowContext).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
        }
        val snapHud = SnapZoneHudView(windowContext, metricsDensity).apply {
            visibility = View.GONE
        }
        val cursorSprite = CursorSpriteView(windowContext, metricsDensity).apply {
            // Keep untrusted TYPE_APPLICATION_OVERLAY opacity strictly below Android 12+
            // InputManager.maximumObscuringOpacityForTouch (0.80f) so touches beneath never drop;
            // TYPE_ACCESSIBILITY_OVERLAY is a trusted system overlay and renders at full 1.0f.
            alpha = if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                1.0f
            } else {
                0.79f
            }
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
            addView(
                cursorSprite,
                FrameLayout.LayoutParams(
                    cursorSizePx,
                    cursorSizePx,
                    Gravity.TOP or Gravity.START
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
        cursorSpriteView = cursorSprite
        attachedWithAccessibilityOverlay = windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        _isAttachedFlow.value = true

        // Apply initial cursor position/visibility immediately
        updateCursorSprite(pointerFocusBridge.pointerState.value)

        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = coroutineScope

        coroutineScope.launch {
            pointerFocusBridge.pointerState.collect { state ->
                updateCursorSprite(state)
            }
        }
        coroutineScope.launch {
            sessionRepository.snapPreview.collect { updateSnapPreview(it) }
        }
        coroutineScope.launch {
            sessionRepository.externalBrightnessFraction.collect { updateBrightnessDimmer(it) }
        }
        return true
    }

    private fun updateCursorSprite(state: PointerState) {
        val sprite = cursorSpriteView ?: return
        if (shouldShowVirtualCursorSprite(state)) {
            if (sprite.visibility != View.VISIBLE) {
                sprite.visibility = View.VISIBLE
            }
            // Pure RenderNode matrix translation without layout or invalidation for 120Hz/240Hz zero-alloc motion
            sprite.translationX = state.x
            sprite.translationY = state.y
            sprite.setPressedHighlight(state.isPrimaryDown || state.isSecondaryDown)
        } else {
            if (sprite.visibility != View.GONE) {
                sprite.visibility = View.GONE
            }
        }
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
        cursorSpriteView = null
        overlayWindowManager = null
        attachedWithAccessibilityOverlay = false
        _isAttachedFlow.value = false
    }

    /**
     * Hardware-cached `28dp x 28dp` vector pointer sprite.
     * Pre-builds the arrow [Path] once; movement uses only `translationX`/`translationY` on the
     * View's GPU `RenderNode`, avoiding `onDraw` and heap allocations on 120Hz/240Hz frames.
     */
    private class CursorSpriteView(
        context: Context,
        private val d: Float
    ) : View(context) {

        private var isPressedHighlight: Boolean = false

        private val arrowPath = Path().apply {
            // Crisp desktop pointer arrow anchored with hotspot at (1dp, 1dp) inside 28dp x 28dp box
            val ox = 1.0f * d
            val oy = 1.0f * d
            moveTo(ox, oy)
            lineTo(ox, oy + 20.5f * d)
            lineTo(ox + 5.4f * d, oy + 15.6f * d)
            lineTo(ox + 9.2f * d, oy + 23.4f * d)
            lineTo(ox + 12.3f * d, oy + 21.9f * d)
            lineTo(ox + 8.5f * d, oy + 14.2f * d)
            lineTo(ox + 15.6f * d, oy + 14.2f * d)
            close()
        }

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3.0f * d
            color = Color.argb(95, 0, 0, 0)
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(16, 21, 32)
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.7f * d
            color = Color.WHITE
        }

        private val clickHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(115, 138, 180, 248)
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun setPressedHighlight(pressed: Boolean) {
            if (isPressedHighlight != pressed) {
                isPressedHighlight = pressed
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isPressedHighlight) {
                canvas.drawCircle(4.5f * d, 4.5f * d, 9.5f * d, clickHaloPaint)
            }
            canvas.save()
            canvas.translate(0.6f * d, 0.9f * d)
            canvas.drawPath(arrowPath, shadowPaint)
            canvas.restore()

            canvas.drawPath(arrowPath, fillPaint)
            canvas.drawPath(arrowPath, borderPaint)
        }
    }

    private class SnapZoneHudView(
        context: Context,
        private val d: Float
    ) : View(context) {
        private val drawRect = RectF()
        private val insetRect = RectF()
        private val pillRect = RectF()
        private var labelText: String = ""

        // Material 3 dark-theme primary (#A8C7FA) & primaryContainer (#1F477A) magnetic snap assist styling
        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6.5f * d
            color = Color.argb(46, 168, 199, 250)
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(56, 138, 180, 248)
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * d
            color = Color.argb(235, 168, 199, 250)
        }

        private val innerHairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.0f * d
            color = Color.argb(72, 214, 227, 255)
        }

        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(238, 35, 43, 58)
        }

        private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * d
            color = Color.argb(145, 168, 199, 250)
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(234, 241, 255)
            textSize = 13f * d
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        fun setPreviewState(state: SnapPreviewState) {
            drawRect.set(state.previewBounds)
            val inset = 2.6f * d
            insetRect.set(
                drawRect.left + inset,
                drawRect.top + inset,
                drawRect.right - inset,
                drawRect.bottom - inset
            )
            labelText = state.snapMode.label
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (drawRect.isEmpty) return
            val radius = 18f * d
            canvas.drawRoundRect(drawRect, radius, radius, outerGlowPaint)
            canvas.drawRoundRect(drawRect, radius, radius, fillPaint)
            canvas.drawRoundRect(drawRect, radius, radius, borderPaint)
            if (!insetRect.isEmpty) {
                val innerRadius = (radius - 2.6f * d).coerceAtLeast(4f * d)
                canvas.drawRoundRect(insetRect, innerRadius, innerRadius, innerHairlinePaint)
            }

            if (labelText.isNotEmpty()) {
                val cx = drawRect.centerX()
                val cy = drawRect.centerY()
                val textW = textPaint.measureText(labelText)
                pillRect.set(
                    cx - textW / 2f - 18f * d,
                    cy - 17f * d,
                    cx + textW / 2f + 18f * d,
                    cy + 17f * d
                )
                canvas.drawRoundRect(pillRect, 17f * d, 17f * d, pillPaint)
                canvas.drawRoundRect(pillRect, 17f * d, 17f * d, pillBorderPaint)
                canvas.drawText(labelText, cx, cy + 4.5f * d, textPaint)
            }
        }
    }

    companion object {
        private const val TAG = "CursorOverlayController"
        const val CURSOR_SIZE_DP = 28

        fun shouldShowVirtualCursorSprite(state: PointerState): Boolean {
            return state.visible && !state.isHardwarePointer
        }
    }
}
