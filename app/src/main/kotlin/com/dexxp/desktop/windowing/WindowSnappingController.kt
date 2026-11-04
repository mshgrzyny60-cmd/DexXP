package com.dexxp.desktop.windowing

import android.content.Context
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.model.ExternalDisplayInfo
import com.dexxp.desktop.model.SnapPreviewState
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.ui.icons.DesktopIcons
import kotlin.math.roundToInt

/**
 * Edge & corner window-snapping engine supporting:
 * - Left Edge -> 50% Left Half ([WindowSnapMode.LEFT_HALF])
 * - Right Edge -> 50% Right Half ([WindowSnapMode.RIGHT_HALF])
 * - Top Edge -> 100% Work-Area Maximize ([WindowSnapMode.MAXIMIZED])
 * - 4 Screen Corners -> 25% Quadrant Tiling (`TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`)
 */
class WindowSnappingController(
    private val sessionRepository: DesktopSessionRepository
) {

    /**
     * Evaluates whether cursor `(x, y)` during a window/pointer drag falls inside an active
     * edge or corner snap trigger zone on the secondary display.
     */
    fun evaluateSnapZone(
        x: Float,
        y: Float,
        displayWidthPx: Int,
        displayHeightPx: Int,
        densityDpi: Int
    ): WindowSnapMode? {
        if (displayWidthPx <= 0 || displayHeightPx <= 0) return null
        val density = densityDpi / 160f
        val taskbarPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()
        val usableHeight = (displayHeightPx - taskbarPx).coerceAtLeast(240)

        // Ignore drags inside the bottom Taskbar strip itself
        if (y >= usableHeight) return null

        val edgeThreshold = (EDGE_SNAP_TRIGGER_DP * density).coerceAtLeast(18f)
        val cornerSpan = (CORNER_SPAN_DP * density).coerceAtLeast(72f)

        val nearLeft = x <= edgeThreshold
        val nearRight = x >= (displayWidthPx - edgeThreshold)
        val nearTop = y <= edgeThreshold
        val inTopCornerBand = y <= cornerSpan
        val inBottomCornerBand = y >= (usableHeight - cornerSpan)
        val inLeftCornerBand = x <= cornerSpan
        val inRightCornerBand = x >= (displayWidthPx - cornerSpan)

        return when {
            (nearLeft && inTopCornerBand) || (nearTop && inLeftCornerBand) ->
                WindowSnapMode.TOP_LEFT_QUADRANT

            (nearRight && inTopCornerBand) || (nearTop && inRightCornerBand) ->
                WindowSnapMode.TOP_RIGHT_QUADRANT

            nearLeft && inBottomCornerBand ->
                WindowSnapMode.BOTTOM_LEFT_QUADRANT

            nearRight && inBottomCornerBand ->
                WindowSnapMode.BOTTOM_RIGHT_QUADRANT

            nearLeft ->
                WindowSnapMode.LEFT_HALF

            nearRight ->
                WindowSnapMode.RIGHT_HALF

            nearTop ->
                WindowSnapMode.MAXIMIZED

            else -> null
        }
    }

    /**
     * Computes exact Freeform `Rect` coordinates for all tiling, quadrant, container, and maximize modes.
     */
    fun computeSnapBounds(
        snapMode: WindowSnapMode,
        displayWidthPx: Int,
        displayHeightPx: Int,
        densityDpi: Int
    ): Rect {
        val b = computeSnapIntBounds(snapMode, displayWidthPx, displayHeightPx, densityDpi)
        return Rect(b.left, b.top, b.right, b.bottom)
    }

    /**
     * Updates the live semi-transparent snap preview overlay during an active drag gesture.
     */
    fun onPointerDragMoved(x: Float, y: Float, displayInfo: ExternalDisplayInfo?) {
        val targetPkg = sessionRepository.focusedOrMostRecentWindow()?.packageName
        if (displayInfo == null || targetPkg == null) {
            if (sessionRepository.snapPreview.value != null) {
                sessionRepository.setSnapPreview(null)
            }
            return
        }

        val zone = evaluateSnapZone(
            x = x,
            y = y,
            displayWidthPx = displayInfo.widthPx,
            displayHeightPx = displayInfo.heightPx,
            densityDpi = displayInfo.scaledDensityDpi
        )

        if (zone == null) {
            if (sessionRepository.snapPreview.value != null) {
                sessionRepository.setSnapPreview(null)
            }
            return
        }

        val current = sessionRepository.snapPreview.value
        if (current?.snapMode == zone && current.targetPackageName == targetPkg) {
            return
        }

        val previewRect = computeSnapBounds(
            snapMode = zone,
            displayWidthPx = displayInfo.widthPx,
            displayHeightPx = displayInfo.heightPx,
            densityDpi = displayInfo.scaledDensityDpi
        )
        sessionRepository.setSnapPreview(
            SnapPreviewState(
                snapMode = zone,
                previewBounds = previewRect,
                targetPackageName = targetPkg
            )
        )
    }

    /**
     * Commits the pending snap preview upon drag release, resizing & docking the target Freeform window.
     */
    fun commitSnapOnDrop(appLauncher: AppLauncher, launchContext: Context? = null): Boolean {
        val preview = sessionRepository.snapPreview.value ?: return false
        sessionRepository.setSnapPreview(null)
        val pkg = preview.targetPackageName ?: return false
        return appLauncher.launchOrFocusPackage(
            packageName = pkg,
            snapMode = preview.snapMode,
            launchContext = launchContext
        )
    }

    fun clearPreview() {
        if (sessionRepository.snapPreview.value != null) {
            sessionRepository.setSnapPreview(null)
        }
    }

    data class IntBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    companion object {
        private const val EDGE_SNAP_TRIGGER_DP = 24f
        private const val CORNER_SPAN_DP = 116f
        const val SNAP_MARGIN_DP = 8
        const val FLOATING_TOP_HEADROOM_DP = 34

        /**
         * Pure integer geometry solver for all desktop container & tiling modes.
         * Leaves `34dp` top headroom on floating/tiled modes so the `28dp` floating titlebar
         * sits cleanly above the client window with zero surface overlap.
         */
        fun computeSnapIntBounds(
            snapMode: WindowSnapMode,
            displayWidthPx: Int,
            displayHeightPx: Int,
            densityDpi: Int
        ): IntBounds {
            val density = densityDpi / 160f
            val taskbarPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()
            val marginPx = (SNAP_MARGIN_DP * density).roundToInt()
            val topHeadroomPx = maxOf(marginPx, (FLOATING_TOP_HEADROOM_DP * density).roundToInt())
            val halfGap = (marginPx / 2).coerceAtLeast(2)

            val usableHeight = (displayHeightPx - taskbarPx).coerceAtLeast(320)
            val midX = displayWidthPx / 2
            val midY = (topHeadroomPx + usableHeight - marginPx) / 2

            return when (snapMode) {
                WindowSnapMode.MAXIMIZED -> IntBounds(
                    left = 0,
                    top = 0,
                    right = displayWidthPx,
                    bottom = usableHeight
                )

                WindowSnapMode.PORTRAIT_CONTAINER -> {
                    val maxH = (usableHeight - topHeadroomPx - marginPx).coerceAtLeast(340)
                    val winH = (usableHeight * 0.86f).roundToInt().coerceIn(340.coerceAtMost(maxH), maxH)
                    val maxW = (displayWidthPx - marginPx * 2).coerceAtLeast(300)
                    val idealPortraitW = (winH * 9f / 19f).roundToInt()
                    val winW = idealPortraitW.coerceIn(360.coerceAtMost(maxW), maxW)
                    val left = ((displayWidthPx - winW) / 2).coerceAtLeast(marginPx)
                    val top = (topHeadroomPx + (maxH - winH) / 2).coerceAtLeast(topHeadroomPx)
                    IntBounds(left, top, left + winW, top + winH)
                }

                WindowSnapMode.LANDSCAPE_CONTAINER -> {
                    val maxH = (usableHeight - topHeadroomPx - marginPx).coerceAtLeast(340)
                    val winH = (usableHeight * 0.80f).roundToInt().coerceIn(400.coerceAtMost(maxH), maxH)
                    val maxW = (displayWidthPx - marginPx * 2).coerceAtLeast(480)
                    val idealLandscapeW = (winH * 1.60f).roundToInt()
                    val winW = idealLandscapeW.coerceIn(640.coerceAtMost(maxW), maxW)
                    val left = ((displayWidthPx - winW) / 2).coerceAtLeast(marginPx)
                    val top = (topHeadroomPx + (maxH - winH) / 2).coerceAtLeast(topHeadroomPx)
                    IntBounds(left, top, left + winW, top + winH)
                }

                WindowSnapMode.LEFT_HALF -> IntBounds(
                    left = marginPx,
                    top = topHeadroomPx,
                    right = midX - halfGap,
                    bottom = usableHeight - marginPx
                )

                WindowSnapMode.RIGHT_HALF -> IntBounds(
                    left = midX + halfGap,
                    top = topHeadroomPx,
                    right = displayWidthPx - marginPx,
                    bottom = usableHeight - marginPx
                )

                WindowSnapMode.TOP_LEFT_QUADRANT -> IntBounds(
                    left = marginPx,
                    top = topHeadroomPx,
                    right = midX - halfGap,
                    bottom = midY - halfGap
                )

                WindowSnapMode.TOP_RIGHT_QUADRANT -> IntBounds(
                    left = midX + halfGap,
                    top = topHeadroomPx,
                    right = displayWidthPx - marginPx,
                    bottom = midY - halfGap
                )

                WindowSnapMode.BOTTOM_LEFT_QUADRANT -> IntBounds(
                    left = marginPx,
                    top = midY + halfGap,
                    right = midX - halfGap,
                    bottom = usableHeight - marginPx
                )

                WindowSnapMode.BOTTOM_RIGHT_QUADRANT -> IntBounds(
                    left = midX + halfGap,
                    top = midY + halfGap,
                    right = displayWidthPx - marginPx,
                    bottom = usableHeight - marginPx
                )

                WindowSnapMode.COMPACT_UTILITY -> {
                    val winW = (displayWidthPx * 0.34f).roundToInt()
                        .coerceIn(400, (displayWidthPx - marginPx * 2).coerceAtLeast(400))
                    val maxH = (usableHeight - topHeadroomPx - marginPx).coerceAtLeast(360)
                    val winH = (usableHeight * 0.72f).roundToInt()
                        .coerceIn(360.coerceAtMost(maxH), maxH)
                    val left = (displayWidthPx - winW - marginPx * 2).coerceAtLeast(marginPx)
                    val top = topHeadroomPx.coerceAtMost((usableHeight - winH).coerceAtLeast(0))
                    IntBounds(left, top, left + winW, top + winH)
                }

                WindowSnapMode.FREEFORM_CASCADED -> {
                    val winW = (displayWidthPx * 0.66f).roundToInt().coerceAtLeast(480)
                    val maxH = (usableHeight - topHeadroomPx - marginPx).coerceAtLeast(360)
                    val winH = (usableHeight * 0.76f).roundToInt().coerceIn(360.coerceAtMost(maxH), maxH)
                    val left = ((displayWidthPx - winW) / 2).coerceAtLeast(marginPx)
                    val top = ((usableHeight - winH) / 2).coerceAtLeast(topHeadroomPx)
                    IntBounds(left, top, left + winW, top + winH)
                }
            }
        }
    }
}

/**
 * Semi-transparent Material 3 glass snap-zone preview rendered before window drop.
 */
@Composable
fun SnapPreviewOverlay(
    previewState: SnapPreviewState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val bounds = previewState.previewBounds
    val widthDp = with(density) { bounds.width().coerceAtLeast(1).toDp() }
    val heightDp = with(density) { bounds.height().coerceAtLeast(1).toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset { IntOffset(bounds.left, bounds.top) }
                .size(width = widthDp, height = heightDp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    shape = RoundedCornerShape(18.dp)
                )
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = DesktopIcons.WindowFreeform,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = previewState.snapMode.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
