package com.dexxp.desktop.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 / Google Material Symbols vector iconography (24dp viewport).
 * Zero-emoji, lightweight resolution-independent vectors (~3 KB total footprint).
 */
object DesktopIcons {

    private inline fun symbol(
        name: String,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

    val AppsGrid: ImageVector by lazy {
        symbol("AppsGrid") {
            val fill = SolidColor(Color.White)
            for (row in 0..2) {
                for (col in 0..2) {
                    val cx = 5f + col * 7f
                    val cy = 5f + row * 7f
                    path(fill = fill) {
                        moveTo(cx - 1.8f, cy - 1.8f)
                        lineTo(cx + 1.8f, cy - 1.8f)
                        lineTo(cx + 1.8f, cy + 1.8f)
                        lineTo(cx - 1.8f, cy + 1.8f)
                        close()
                    }
                }
            }
        }
    }

    val Search: ImageVector by lazy {
        symbol("Search") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(15.5f, 15.5f)
                lineTo(20f, 20f)
                moveTo(17f, 10.5f)
                arcTo(6.5f, 6.5f, 0f, true, true, 4f, 10.5f)
                arcTo(6.5f, 6.5f, 0f, true, true, 17f, 10.5f)
            }
        }
    }

    val Monitor: ImageVector by lazy {
        symbol("Monitor") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 16f)
                lineTo(3f, 16f)
                close()
                moveTo(8f, 20f)
                lineTo(16f, 20f)
                moveTo(12f, 16f)
                lineTo(12f, 20f)
            }
        }
    }

    val Touchpad: ImageVector by lazy {
        symbol("Touchpad") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
                moveTo(3f, 14.5f)
                lineTo(21f, 14.5f)
                moveTo(12f, 14.5f)
                lineTo(12f, 19f)
            }
        }
    }

    val WindowFreeform: ImageVector by lazy {
        symbol("WindowFreeform") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 6f)
                lineTo(20f, 6f)
                lineTo(20f, 18f)
                lineTo(4f, 18f)
                close()
                moveTo(4f, 9.5f)
                lineTo(20f, 9.5f)
            }
        }
    }

    val SplitLeft: ImageVector by lazy {
        symbol("SplitLeft") {
            path(fill = SolidColor(Color.White)) {
                moveTo(4f, 5f); lineTo(11f, 5f); lineTo(11f, 19f); lineTo(4f, 19f); close()
            }
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(13f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(13f, 19f); close()
            }
        }
    }

    val SplitRight: ImageVector by lazy {
        symbol("SplitRight") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 5f); lineTo(11f, 5f); lineTo(11f, 19f); lineTo(4f, 19f); close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(13f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(13f, 19f); close()
            }
        }
    }

    val Maximize: ImageVector by lazy {
        symbol("Maximize") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
                moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
                moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
                moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
            }
        }
    }

    val DesktopView: ImageVector by lazy {
        symbol("DesktopView") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
                moveTo(3f, 14.5f); lineTo(21f, 14.5f)
            }
        }
    }

    val Sliders: ImageVector by lazy {
        symbol("Sliders") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17f); lineTo(20f, 17f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(9f, 5f); lineTo(11.5f, 5f); lineTo(11.5f, 9f); lineTo(9f, 9f); close()
                moveTo(14f, 10f); lineTo(16.5f, 10f); lineTo(16.5f, 14f); lineTo(14f, 14f); close()
                moveTo(7f, 15f); lineTo(9.5f, 15f); lineTo(9.5f, 19f); lineTo(7f, 19f); close()
            }
        }
    }

    val Wifi: ImageVector by lazy {
        symbol("Wifi") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(2.5f, 8.5f); quadTo(12f, 1.5f, 21.5f, 8.5f)
                moveTo(5.8f, 12.2f); quadTo(12f, 7.2f, 18.2f, 12.2f)
                moveTo(9.2f, 15.8f); quadTo(12f, 13.5f, 14.8f, 15.8f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 18.2f); arcTo(1.4f, 1.4f, 0f, true, true, 11.99f, 18.2f); close()
            }
        }
    }

    val Battery: ImageVector by lazy {
        symbol("Battery") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 7f); lineTo(18.5f, 7f); lineTo(18.5f, 17f); lineTo(3f, 17f); close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(19.5f, 9.8f); lineTo(21.5f, 9.8f); lineTo(21.5f, 14.2f); lineTo(19.5f, 14.2f); close()
                moveTo(5.2f, 9.2f); lineTo(15.5f, 9.2f); lineTo(15.5f, 14.8f); lineTo(5.2f, 14.8f); close()
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        symbol("VolumeUp") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(3f, 9.5f); lineTo(7f, 9.5f); lineTo(12f, 5f)
                lineTo(12f, 19f); lineTo(7f, 14.5f); lineTo(3f, 14.5f); close()
            }
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(15.5f, 8.8f); quadTo(18.2f, 12f, 15.5f, 15.2f)
                moveTo(18f, 6f); quadTo(22.2f, 12f, 18f, 18f)
            }
        }
    }

    val Notifications: ImageVector by lazy {
        symbol("Notifications") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 16f)
                lineTo(6f, 16f)
                lineTo(7.5f, 14f)
                lineTo(7.5f, 10f)
                arcTo(4.5f, 4.5f, 0f, false, true, 16.5f, 10f)
                lineTo(16.5f, 14f)
                close()
                moveTo(10f, 19f)
                quadTo(12f, 21f, 14f, 19f)
            }
        }
    }

    val Brightness: ImageVector by lazy {
        symbol("Brightness") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(16f, 12f)
                arcTo(4f, 4f, 0f, true, true, 8f, 12f)
                arcTo(4f, 4f, 0f, true, true, 16f, 12f)
                moveTo(12f, 3f); lineTo(12f, 5f)
                moveTo(12f, 19f); lineTo(12f, 21f)
                moveTo(3f, 12f); lineTo(5f, 12f)
                moveTo(19f, 12f); lineTo(21f, 12f)
                moveTo(5.6f, 5.6f); lineTo(7.1f, 7.1f)
                moveTo(16.9f, 16.9f); lineTo(18.4f, 18.4f)
                moveTo(18.4f, 5.6f); lineTo(16.9f, 7.1f)
                moveTo(7.1f, 16.9f); lineTo(5.6f, 18.4f)
            }
        }
    }

    val Bluetooth: ImageVector by lazy {
        symbol("Bluetooth") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7f, 7f)
                lineTo(17f, 17f)
                lineTo(12f, 21f)
                lineTo(12f, 3f)
                lineTo(17f, 7f)
                lineTo(7f, 17f)
            }
        }
    }

    val Headphones: ImageVector by lazy {
        symbol("Headphones") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 13f)
                arcTo(8f, 8f, 0f, false, true, 20f, 13f)
                lineTo(20f, 18f)
                lineTo(17f, 18f)
                lineTo(17f, 13f)
                lineTo(20f, 13f)
                moveTo(4f, 13f)
                lineTo(7f, 13f)
                lineTo(7f, 18f)
                lineTo(4f, 18f)
                close()
            }
        }
    }

    val Speaker: ImageVector by lazy {
        symbol("Speaker") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(6f, 21f); close()
                moveTo(15f, 14.5f)
                arcTo(3f, 3f, 0f, true, true, 9f, 14.5f)
                arcTo(3f, 3f, 0f, true, true, 15f, 14.5f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 7.5f); arcTo(1.2f, 1.2f, 0f, true, true, 11.99f, 7.5f); close()
            }
        }
    }

    val FileGeneric: ImageVector by lazy {
        symbol("FileGeneric") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 3f)
                lineTo(14f, 3f)
                lineTo(19f, 8f)
                lineTo(19f, 21f)
                lineTo(6f, 21f)
                close()
                moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
                moveTo(9f, 13f); lineTo(16f, 13f)
                moveTo(9f, 16.5f); lineTo(14f, 16.5f)
            }
        }
    }

    val ImageFile: ImageVector by lazy {
        symbol("ImageFile") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
                moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(16f, 12f); lineTo(20f, 16f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(8.5f, 8.5f); arcTo(1.5f, 1.5f, 0f, true, true, 8.49f, 8.5f); close()
            }
        }
    }

    val FolderOpen: ImageVector by lazy {
        symbol("FolderOpen") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 6f)
                lineTo(9.5f, 6f)
                lineTo(11.5f, 8.5f)
                lineTo(21f, 8.5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
            }
        }
    }

    val Send: ImageVector by lazy {
        symbol("Send") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 20f)
                lineTo(21f, 12f)
                lineTo(3f, 4f)
                lineTo(6.5f, 12f)
                close()
                moveTo(6.5f, 12f); lineTo(14f, 12f)
            }
        }
    }

    val Terminal: ImageVector by lazy {
        symbol("Terminal") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
                moveTo(7f, 9.5f); lineTo(10.5f, 12f); lineTo(7f, 14.5f)
                moveTo(12.5f, 15f); lineTo(17f, 15f)
            }
        }
    }

    val CheckCircle: ImageVector by lazy {
        symbol("CheckCircle") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, true, true, 3f, 12f)
                arcTo(9f, 9f, 0f, true, true, 21f, 12f)
                moveTo(8.5f, 12.2f); lineTo(11f, 14.8f); lineTo(15.8f, 9.5f)
            }
        }
    }

    val Warning: ImageVector by lazy {
        symbol("Warning") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
                moveTo(12f, 10f); lineTo(12f, 14f)
                moveTo(12f, 17f); lineTo(12.01f, 17f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        symbol("Copy") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 8f); lineTo(19f, 8f); lineTo(19f, 20f); lineTo(9f, 20f); close()
                moveTo(15f, 8f); lineTo(15f, 4f); lineTo(5f, 4f); lineTo(5f, 16f); lineTo(9f, 16f)
            }
        }
    }

    val Close: ImageVector by lazy {
        symbol("Close") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
            }
        }
    }

    val Pin: ImageVector by lazy {
        symbol("Pin") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 4f); lineTo(16f, 4f); lineTo(15f, 10f)
                lineTo(18f, 13f); lineTo(6f, 13f); lineTo(9f, 10f); close()
                moveTo(12f, 13f); lineTo(12f, 20f)
            }
        }
    }

    val Keyboard: ImageVector by lazy {
        symbol("Keyboard") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2.5f, 6f); lineTo(21.5f, 6f); lineTo(21.5f, 18f); lineTo(2.5f, 18f); close()
                moveTo(6f, 10f); lineTo(7.5f, 10f)
                moveTo(10f, 10f); lineTo(11.5f, 10f)
                moveTo(14f, 10f); lineTo(15.5f, 10f)
                moveTo(17.5f, 10f); lineTo(18.5f, 10f)
                moveTo(7.5f, 14.5f); lineTo(16.5f, 14.5f)
            }
        }
    }

    val Power: ImageVector by lazy {
        symbol("Power") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.1f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 3.5f); lineTo(12f, 11.5f)
                moveTo(7.2f, 6.5f); arcTo(7.2f, 7.2f, 0f, true, false, 16.8f, 6.5f)
            }
        }
    }

    val Layers: ImageVector by lazy {
        symbol("Layers") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3.5f); lineTo(21f, 8.5f); lineTo(12f, 13.5f); lineTo(3f, 8.5f); close()
                moveTo(3f, 13f); lineTo(12f, 18f); lineTo(21f, 13f)
            }
        }
    }

    val MoonSleep: ImageVector by lazy {
        symbol("MoonSleep") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20.5f, 14.5f)
                arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 3.5f)
                arcTo(6.8f, 6.8f, 0f, false, false, 20.5f, 14.5f)
                close()
            }
        }
    }

    val Clipboard: ImageVector by lazy {
        symbol("Clipboard") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.5f, 5f); lineTo(6f, 5f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 5f); lineTo(15.5f, 5f)
                moveTo(9f, 3.5f); lineTo(15f, 3.5f); lineTo(15f, 6.5f); lineTo(9f, 6.5f); close()
                moveTo(9f, 11f); lineTo(15f, 11f)
                moveTo(9f, 15f); lineTo(13.5f, 15f)
            }
        }
    }

    val Minimize: ImageVector by lazy {
        symbol("Minimize") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(6f, 16.5f); lineTo(18f, 16.5f)
            }
        }
    }

    val RestoreWindow: ImageVector by lazy {
        symbol("RestoreWindow") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 9f); lineTo(15f, 9f); lineTo(15f, 19f); lineTo(5f, 19f); close()
                moveTo(9f, 9f); lineTo(9f, 5f); lineTo(19f, 5f); lineTo(19f, 15f); lineTo(15f, 15f)
            }
        }
    }

    val Workspaces: ImageVector by lazy {
        symbol("Workspaces") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.5f, 4.5f); lineTo(10.5f, 4.5f); lineTo(10.5f, 10.5f); lineTo(3.5f, 10.5f); close()
                moveTo(13.5f, 4.5f); lineTo(20.5f, 4.5f); lineTo(20.5f, 10.5f); lineTo(13.5f, 10.5f); close()
                moveTo(3.5f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 19.5f); lineTo(3.5f, 19.5f); close()
                moveTo(13.5f, 13.5f); lineTo(20.5f, 13.5f); lineTo(20.5f, 19.5f); lineTo(13.5f, 19.5f); close()
            }
        }
    }

    val Globe: ImageVector by lazy {
        symbol("Globe") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, true, true, 3f, 12f)
                arcTo(9f, 9f, 0f, true, true, 21f, 12f)
                moveTo(3.5f, 12f); lineTo(20.5f, 12f)
                moveTo(12f, 3f)
                quadTo(16.5f, 12f, 12f, 21f)
                moveTo(12f, 3f)
                quadTo(7.5f, 12f, 12f, 21f)
            }
        }
    }

    val Link: ImageVector by lazy {
        symbol("Link") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(10f, 13f)
                arcTo(3.5f, 3.5f, 0f, false, true, 10f, 7f)
                lineTo(13f, 7f)
                arcTo(3.5f, 3.5f, 0f, false, true, 16.5f, 10.5f)
                moveTo(14f, 11f)
                arcTo(3.5f, 3.5f, 0f, false, true, 14f, 17f)
                lineTo(11f, 17f)
                arcTo(3.5f, 3.5f, 0f, false, true, 7.5f, 13.5f)
                moveTo(9f, 12f); lineTo(15f, 12f)
            }
        }
    }

    val OverviewGrid: ImageVector by lazy {
        symbol("OverviewGrid") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 4.5f); lineTo(13f, 4.5f); lineTo(13f, 11f); lineTo(3f, 11f); close()
                moveTo(15.5f, 4.5f); lineTo(21f, 4.5f); lineTo(21f, 11f); lineTo(15.5f, 11f); close()
                moveTo(3f, 13.5f); lineTo(9.5f, 13.5f); lineTo(9.5f, 19.5f); lineTo(3f, 19.5f); close()
                moveTo(12f, 13.5f); lineTo(21f, 13.5f); lineTo(21f, 19.5f); lineTo(12f, 19.5f); close()
            }
        }
    }

    val Calculate: ImageVector by lazy {
        symbol("Calculate") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
                moveTo(7f, 8.5f); lineTo(10.5f, 8.5f)
                moveTo(8.75f, 6.75f); lineTo(8.75f, 10.25f)
                moveTo(13.5f, 8.5f); lineTo(17f, 8.5f)
                moveTo(7.2f, 14f); lineTo(10.2f, 17f)
                moveTo(10.2f, 14f); lineTo(7.2f, 17f)
                moveTo(13.5f, 14.2f); lineTo(17f, 14.2f)
                moveTo(13.5f, 16.8f); lineTo(17f, 16.8f)
            }
        }
    }

    val PhoneCall: ImageVector by lazy {
        symbol("PhoneCall") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6.5f, 4.2f)
                lineTo(9.5f, 4.2f)
                lineTo(11f, 8.2f)
                lineTo(8.8f, 10f)
                quadTo(10.8f, 13.8f, 14f, 15.2f)
                lineTo(15.8f, 13f)
                lineTo(19.8f, 14.5f)
                lineTo(19.8f, 17.5f)
                quadTo(19.8f, 20f, 17f, 20f)
                quadTo(5f, 19f, 4f, 7f)
                quadTo(4f, 4.2f, 6.5f, 4.2f)
                close()
            }
        }
    }

    val PhoneHangup: ImageVector by lazy {
        symbol("PhoneHangup") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 14.5f)
                quadTo(12f, 7.5f, 21f, 14.5f)
                lineTo(19.2f, 17.5f)
                lineTo(15.5f, 16f)
                lineTo(15f, 13.2f)
                quadTo(12f, 12f, 9f, 13.2f)
                lineTo(8.5f, 16f)
                lineTo(4.8f, 17.5f)
                close()
            }
        }
    }

    val KeyOtp: ImageVector by lazy {
        symbol("KeyOtp") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(11f, 12f)
                arcTo(4f, 4f, 0f, true, true, 3f, 12f)
                arcTo(4f, 4f, 0f, true, true, 11f, 12f)
                moveTo(11f, 12f); lineTo(21f, 12f)
                moveTo(17.5f, 12f); lineTo(17.5f, 15.5f)
                moveTo(20.5f, 12f); lineTo(20.5f, 14.8f)
            }
        }
    }

    val LayoutPreset: ImageVector by lazy {
        symbol("LayoutPreset") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.5f, 5f); lineTo(14.5f, 5f); lineTo(14.5f, 19f); lineTo(3.5f, 19f); close()
                moveTo(16.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(16.5f, 19f); close()
            }
        }
    }

    val Tv: ImageVector by lazy {
        symbol("Tv") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 7f); lineTo(21f, 7f); lineTo(21f, 18f); lineTo(3f, 18f); close()
                moveTo(8.5f, 21f); lineTo(15.5f, 21f)
                moveTo(12f, 18f); lineTo(12f, 21f)
                moveTo(8.5f, 3.5f); lineTo(12f, 7f); lineTo(15.5f, 3.5f)
            }
        }
    }

    val Dpad: ImageVector by lazy {
        symbol("Dpad") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 9f); lineTo(21f, 9f)
                lineTo(21f, 15f); lineTo(15f, 15f); lineTo(15f, 21f); lineTo(9f, 21f)
                lineTo(9f, 15f); lineTo(3f, 15f); lineTo(3f, 9f); lineTo(9f, 9f); close()
            }
        }
    }
}
