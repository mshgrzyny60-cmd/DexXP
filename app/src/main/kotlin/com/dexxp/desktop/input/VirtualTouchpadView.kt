package com.dexxp.desktop.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.dexxp.desktop.bridge.PointerFocusBridge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Multi-touch primary-screen trackpad controller translating raw finger gestures on the phone
 * (`Display.DEFAULT_DISPLAY`) into ballistic cursor motion, clicks, drags, scroll wheels, and
 * workspace gestures on the secondary desktop display.
 *
 * Supported gesture matrix:
 * - **1-Finger Move**: Ballistic velocity-accelerated pointer delta via `MotionEvent` historical batching.
 * - **1-Finger Tap**: Left click (`BUTTON_PRIMARY`).
 * - **Double-Tap & Hold/Move**: Drag-lock (`ACTION_DOWN` -> `ACTION_MOVE` -> `ACTION_UP`).
 * - **2-Finger Tap**: Right click (`BUTTON_SECONDARY`).
 * - **2-Finger Drag** (or right-edge strip): Vertical & horizontal `ACTION_SCROLL` wheel.
 * - **3-Finger Swipe Up / Down**: Toggle Mission Control Overview / Show Desktop.
 */
class VirtualTouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pointerFocusBridge: PointerFocusBridge? = null
    var onThreeFingerSwipeUp: (() -> Unit)? = null
    var onThreeFingerSwipeDown: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var velocityTracker: VelocityTracker? = null

    private var maxPointersInGesture = 0
    private var gestureStartTimeMs = 0L
    private var initialX = 0f
    private var initialY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var focalX = 0f
    private var focalY = 0f
    private var hasMovedBeyondSlop = false

    private var lastSingleTapUpTimeMs = 0L
    private var lastSingleTapX = 0f
    private var lastSingleTapY = 0f
    private var isDragLocked = false
    private var isEdgeScrollGesture = false
    private var threeFingerTriggered = false

    private var scrollAccumulatorX = 0f
    private var scrollAccumulatorY = 0f

    // Zero-allocation primitive ring buffer for 120-240Hz touch trail feedback
    private val trailX = FloatArray(TRAIL_CAPACITY)
    private val trailY = FloatArray(TRAIL_CAPACITY)
    private val trailTimeMs = LongArray(TRAIL_CAPACITY)
    private var trailHead = 0
    private var trailCount = 0
    private var activeTouchX = -1f
    private var activeTouchY = -1f
    private var isTouching = false

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(18, 255, 255, 255)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val scrollZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 138, 180, 248)
        style = Paint.Style.FILL
    }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 138, 180, 248)
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_RING_DEFAULT
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_RING_DEFAULT
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }

    private val scrollZoneRect = RectF()

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bridge = pointerFocusBridge ?: return super.onTouchEvent(event)
        val now = SystemClock.uptimeMillis()

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                maxPointersInGesture = 1
                gestureStartTimeMs = now
                initialX = event.x
                initialY = event.y
                lastX = event.x
                lastY = event.y
                hasMovedBeyondSlop = false
                threeFingerTriggered = false
                scrollAccumulatorX = 0f
                scrollAccumulatorY = 0f

                val edgeStripWidth = 26f * density
                isEdgeScrollGesture = width > 0 && event.x >= (width - edgeStripWidth)

                val sinceLastTap = now - lastSingleTapUpTimeMs
                val nearLastTap = hypot(event.x - lastSingleTapX, event.y - lastSingleTapY) < touchSlop * 2.5f
                if (!isEdgeScrollGesture && sinceLastTap in 1..DOUBLE_TAP_DRAG_WINDOW_MS && nearLastTap) {
                    isDragLocked = true
                    bridge.beginDrag()
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } else {
                    isDragLocked = false
                }

                isTouching = true
                activeTouchX = event.x
                activeTouchY = event.y
                recordTrail(event.x, event.y, now)
                postInvalidateOnAnimation()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersInGesture = maxOf(maxPointersInGesture, event.pointerCount)
                if (isDragLocked && maxPointersInGesture > 1) {
                    bridge.endDrag()
                    isDragLocked = false
                }
                updateFocalPoint(event)
                initialX = focalX
                initialY = focalY
                lastX = focalX
                lastY = focalY
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerCount = event.pointerCount
                maxPointersInGesture = maxOf(maxPointersInGesture, pointerCount)

                if (pointerCount == 1 && maxPointersInGesture == 1) {
                    // Coalesce sub-frame historical samples into a single zero-allocation frame dispatch
                    var accDx = 0f
                    var accDy = 0f
                    val historySize = event.historySize
                    for (h in 0 until historySize) {
                        val hx = event.getHistoricalX(0, h)
                        val hy = event.getHistoricalY(0, h)
                        val stepDx = hx - lastX
                        val stepDy = hy - lastY
                        lastX = hx
                        lastY = hy
                        if (!hasMovedBeyondSlop && hypot(hx - initialX, hy - initialY) > touchSlop * 0.65f) {
                            hasMovedBeyondSlop = true
                        }
                        if (hasMovedBeyondSlop) {
                            accDx += stepDx
                            accDy += stepDy
                        }
                    }

                    val stepDx = event.x - lastX
                    val stepDy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (!hasMovedBeyondSlop && hypot(event.x - initialX, event.y - initialY) > touchSlop * 0.65f) {
                        hasMovedBeyondSlop = true
                    }
                    if (hasMovedBeyondSlop) {
                        accDx += stepDx
                        accDy += stepDy
                    }

                    if (hasMovedBeyondSlop && (accDx != 0f || accDy != 0f)) {
                        if (isEdgeScrollGesture) {
                            dispatchTwoFingerScroll(bridge, 0f, accDy)
                        } else {
                            velocityTracker?.computeCurrentVelocity(1000)
                            val vxDp = abs(velocityTracker?.xVelocity ?: 0f) / density
                            val vyDp = abs(velocityTracker?.yVelocity ?: 0f) / density
                            val speedDpPerSec = hypot(vxDp, vyDp)
                            val gain = 1.0f + 0.85f * min(speedDpPerSec / 920f, 1.82f)
                            bridge.movePointerBy(accDx * gain, accDy * gain)
                        }
                    }

                    activeTouchX = event.x
                    activeTouchY = event.y
                    recordTrail(event.x, event.y, now)
                } else if (pointerCount == 2 && maxPointersInGesture == 2) {
                    updateFocalPoint(event)
                    val dx = focalX - lastX
                    val dy = focalY - lastY
                    if (!hasMovedBeyondSlop && hypot(focalX - initialX, focalY - initialY) > touchSlop) {
                        hasMovedBeyondSlop = true
                    }
                    if (hasMovedBeyondSlop) {
                        dispatchTwoFingerScroll(bridge, dx, dy)
                    }
                    lastX = focalX
                    lastY = focalY
                    activeTouchX = focalX
                    activeTouchY = focalY
                } else if (pointerCount >= 3 && !threeFingerTriggered) {
                    updateFocalPoint(event)
                    val totalDy = focalY - initialY
                    val threshold = 44f * density
                    if (abs(totalDy) >= threshold) {
                        threeFingerTriggered = true
                        hasMovedBeyondSlop = true
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        if (totalDy < 0f) {
                            onThreeFingerSwipeUp?.invoke()
                        } else {
                            onThreeFingerSwipeDown?.invoke()
                        }
                    }
                }
                postInvalidateOnAnimation()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                var sumX = 0f
                var sumY = 0f
                var count = 0
                for (i in 0 until event.pointerCount) {
                    if (i == upIndex) continue
                    sumX += event.getX(i)
                    sumY += event.getY(i)
                    count++
                }
                if (count > 0) {
                    lastX = sumX / count
                    lastY = sumY / count
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = now - gestureStartTimeMs
                if (isDragLocked) {
                    bridge.endDrag()
                    isDragLocked = false
                } else if (!hasMovedBeyondSlop && duration <= TAP_TIMEOUT_MS) {
                    when (maxPointersInGesture) {
                        1 -> {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            bridge.performPrimaryClick()
                            lastSingleTapUpTimeMs = now
                            lastSingleTapX = event.x
                            lastSingleTapY = event.y
                            performClick()
                        }
                        2 -> {
                            performHapticFeedback(
                                HapticFeedbackConstants.CONTEXT_CLICK
                            )
                            bridge.performSecondaryClick()
                        }
                    }
                }

                isTouching = false
                isEdgeScrollGesture = false
                recycleVelocityTracker()
                postInvalidateOnAnimation()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragLocked) {
                    bridge.endDrag()
                    isDragLocked = false
                }
                isTouching = false
                isEdgeScrollGesture = false
                recycleVelocityTracker()
                postInvalidateOnAnimation()
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        if (isDragLocked) {
            pointerFocusBridge?.endDrag()
            isDragLocked = false
        }
        isTouching = false
        isEdgeScrollGesture = false
        trailCount = 0
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    private fun dispatchTwoFingerScroll(bridge: PointerFocusBridge, dx: Float, dy: Float) {
        scrollAccumulatorX += dx
        scrollAccumulatorY += dy
        val stepPx = 18f * density

        var vScroll = 0f
        var hScroll = 0f
        if (abs(scrollAccumulatorY) >= stepPx) {
            vScroll = scrollAccumulatorY / stepPx
            scrollAccumulatorY = 0f
        }
        if (abs(scrollAccumulatorX) >= stepPx) {
            hScroll = scrollAccumulatorX / stepPx
            scrollAccumulatorX = 0f
        }
        if (vScroll != 0f || hScroll != 0f) {
            bridge.performScroll(verticalDelta = vScroll, horizontalDelta = hScroll)
        }
    }

    private fun updateFocalPoint(event: MotionEvent) {
        var sumX = 0f
        var sumY = 0f
        val count = event.pointerCount.coerceAtLeast(1)
        for (i in 0 until count) {
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        focalX = sumX / count
        focalY = sumY / count
    }

    private fun recordTrail(x: Float, y: Float, timeMs: Long) {
        if (trailCount < TRAIL_CAPACITY) {
            val writeIdx = (trailHead + trailCount) % TRAIL_CAPACITY
            trailX[writeIdx] = x
            trailY[writeIdx] = y
            trailTimeMs[writeIdx] = timeMs
            trailCount++
        } else {
            trailX[trailHead] = x
            trailY[trailHead] = y
            trailTimeMs[trailHead] = timeMs
            trailHead = (trailHead + 1) % TRAIL_CAPACITY
        }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Precision dot/line matrix
        val gridSpacing = 44f * density
        var gx = gridSpacing
        while (gx < w - 26f * density) {
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
            gx += gridSpacing
        }
        var gy = gridSpacing
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += gridSpacing
        }

        // Right-edge dedicated scroll track highlight
        val stripW = 24f * density
        scrollZoneRect.set(w - stripW, 12f * density, w - 6f * density, h - 12f * density)
        canvas.drawRoundRect(scrollZoneRect, 9f * density, 9f * density, scrollZonePaint)

        // Corner calibration brackets
        val pad = 16f * density
        val arm = 18f * density
        canvas.drawLine(pad, pad, pad + arm, pad, reticlePaint)
        canvas.drawLine(pad, pad, pad, pad + arm, reticlePaint)
        canvas.drawLine(w - stripW - pad, pad, w - stripW - pad - arm, pad, reticlePaint)
        canvas.drawLine(w - stripW - pad, pad, w - stripW - pad, pad + arm, reticlePaint)
        canvas.drawLine(pad, h - pad, pad + arm, h - pad, reticlePaint)
        canvas.drawLine(pad, h - pad, pad, h - pad - arm, reticlePaint)

        // Zero-allocation decaying touch trail over primitive ring buffer
        val now = SystemClock.uptimeMillis()
        while (trailCount > 0) {
            val oldestAge = now - trailTimeMs[trailHead]
            if (oldestAge > TRAIL_LIFETIME_MS) {
                trailHead = (trailHead + 1) % TRAIL_CAPACITY
                trailCount--
            } else {
                break
            }
        }
        for (i in 0 until trailCount) {
            val idx = (trailHead + i) % TRAIL_CAPACITY
            val age = now - trailTimeMs[idx]
            if (age in 0..TRAIL_LIFETIME_MS) {
                val fraction = 1f - (age.toFloat() / TRAIL_LIFETIME_MS.toFloat())
                trailPaint.alpha = (fraction * 130f).toInt().coerceIn(0, 255)
                canvas.drawCircle(trailX[idx], trailY[idx], (3.5f + 5f * fraction) * density, trailPaint)
            }
        }

        if (isTouching && activeTouchX >= 0f) {
            ringPaint.color = if (isDragLocked) COLOR_RING_DRAG else COLOR_RING_DEFAULT
            ringPaint.alpha = 200
            canvas.drawCircle(activeTouchX, activeTouchY, 18f * density, ringPaint)
        }

        if (trailCount > 0 || isTouching) {
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val TAP_TIMEOUT_MS = 230L
        private const val DOUBLE_TAP_DRAG_WINDOW_MS = 270L
        private const val TRAIL_LIFETIME_MS = 180L
        private const val TRAIL_CAPACITY = 24
        private const val COLOR_RING_DEFAULT = 0xFF8AB4F8.toInt()
        private const val COLOR_RING_DRAG = 0xFF6DD58C.toInt()
    }
}
