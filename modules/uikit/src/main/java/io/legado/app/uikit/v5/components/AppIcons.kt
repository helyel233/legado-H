package io.legado.app.uikit.v5.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * P2 组件库内部图标。
 *
 * 自绘 path，不引 material-icons-extended。颜色由使用处 Icon(tint=) 决定。
 * 线宽基准 1.8，最终显示层线宽由界面包 icon.strokeDp 统一施加（显示层缩放）。
 */
private fun icon(vararg paths: String): ImageVector {
    var b = Builder(
        name = "v5comp",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    paths.forEach { data ->
        b = b.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object AppIcons {
    val Search = icon("M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "m21 21-4.3-4.3")
    val Close = icon("M18 6 6 18", "m6 6 12 12")
    val Back = icon("m15 18-6-6 6-6")
    val Chevron = icon("m9 18 6-6-6-6")
    val Check = icon("M20 6 9 17l-5-5")
    val Refresh = icon("M21 12a9 9 0 1 1-2.6-6.4", "M21 3v6h-6")
    val Cloud = icon("M18 10h-1.3A5 5 0 1 0 7 12H6a4 4 0 0 0 0 8h12a4 4 0 0 0 0-8z")
    val Error = icon("M12 8v5", "M12 16.5 L12.01 16.5", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z")
    val Inbox = icon("M22 12h-6l-2 3h-4l-2-3H2", "M5.4 5 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.4-7z")
    val Plus = icon("M12 5v14", "M5 12h14")
    val Star = icon("m12 3 2.7 5.6 6.3.9-4.5 4.4 1 6.1-5.5-2.9-5.5 2.9 1-6.1L3 9.5l6.3-.9z")
}
