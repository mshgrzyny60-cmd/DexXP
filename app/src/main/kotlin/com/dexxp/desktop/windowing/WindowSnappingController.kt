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
     * Computes exact Freeform `Rect` coordinates for all tiling, quadrant, and maximize modes.
     */
    fun computeSnapBounds(
        snapMode: WindowSnapMode,
        displayWidthPx: Int,
        displayHeightPx: Int,
        densityDpi: Int
    ): Rect {
        val density = densityDpi / 160f
        val taskbarPx = (AppLauncher.TASKBAR_HEIGHT_DP * density).roundToInt()
        val marginPx = (SNAP_MARGIN_DP * density).roundToInt()
        val halfGap = (marginPx / 2).coerceAtLeast(2)

        val usableHeight = (displayHeightPx - taskbarPx).coerceAtLeast(320)
        val midX = displayWidthPx / 2
        val midY = usableHeight / 2

        return when (snapMode) {
            WindowSnapMode.MAXIMIZED -> Rect(
                0,
                0,
                displayWidthPx,
                usableHeight
            )

            WindowSnapMode.LEFT_HALF -> Rect(
                marginPx,
                marginPx,
                midX - halfGap,
                usableHeight - marginPx
            )

            WindowSnapMode.RIGHT_HALF -> Rect(
                midX + halfGap,
                marginPx,
                displayWidthPx - marginPx,
                usableHeight - marginPx
            )

            WindowSnapMode.TOP_LEFT_QUADRANT -> Rect(
                marginPx,
                marginPx,
                midX - halfGap,
                midY - halfGap
            )

            WindowSnapMode.TOP_RIGHT_QUADRANT -> Rect(
                midX + halfGap,
                marginPx,
                displayWidthPx - marginPx,
                midY - halfGap
            )

            WindowSnapMode.BOTTOM_LEFT_QUADRANT -> Rect(
                marginPx,
                midY + halfGap,
                midX - halfGap,
                usableHeight - marginPx
            )

            WindowSnapMode.BOTTOM_RIGHT_QUADRANT -> Rect(
                midX + halfGap,
                midY + halfGap,
                displayWidthPx - marginPx,
                usableHeight - marginPx
            )

            WindowSnapMode.COMPACT_UTILITY -> {
                val winW = (displayWidthPx * 0.34f).roundToInt()
                    .coerceIn(400, (displayWidthPx - marginPx * 2).coerceAtLeast(400))
                val winH = (usableHeight * 0.72f).roundToInt()
                    .coerceIn(380, (usableHeight - marginPx * 2).coerceAtLeast(380))
                val left = (displayWidthPx - winW - marginPx * 2).coerceAtLeast(marginPx)
                val top = (marginPx * 2).coerceAtMost((usableHeight - winH).coerceAtLeast(0))
                Rect(left, top, left + winW, top + winH)
            }

            WindowSnapMode.FREEFORM_CASCADED -> {
                val winW = (displayWidthPx * 0.66f).roundToInt().coerceAtLeast(480)
                val winH = (usableHeight * 0.76f).roundToInt().coerceAtLeast(360)
                val left = ((displayWidthPx - winW) / 2).coerceAtLeast(marginPx)
                val top = ((usableHeight - winH) / 2).coerceAtLeast(marginPx)
                Rect(left, top, left + winW, top + winH)
            }
        }
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

    companion object {
        private const val EDGE_SNAP_TRIGGER_DP = 24f
        private const val CORNER_SPAN_DP = 116f
        const val SNAP_MARGIN_DP = 8
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
