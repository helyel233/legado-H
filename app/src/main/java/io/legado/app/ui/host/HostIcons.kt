package io.legado.app.ui.host

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * P1-2 占位图标集。
 *
 * 自绘 path，不依赖 material-icons-extended（那会带进几百 KB 用不到的图标）。
 * 线宽由界面包的 `icon.strokeDp` 在**显示层**统一施加，这里只是形状来源。
 */
internal fun v5Icon(vararg paths: String): ImageVector {
    var builder = Builder(
        name = "v5icon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    paths.forEach { data ->
        builder = builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

/** 占位 Tab 与宿主用到的全部图标。 */
object HostIcons {
    val Shelf = v5Icon(
        "M4 19.5A2.5 2.5 0 0 1 6.5 17H20",
        "M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z",
    )
    val Home = v5Icon(
        "m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        "M9 22V12h6v10",
    )
    val Explore = v5Icon(
        "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z",
        "m15.5 8.5-2.2 5.3-5.3 2.2 2.2-5.3z",
    )
    val Rss = v5Icon(
        "M4 11a9 9 0 0 1 9 9",
        "M4 4a16 16 0 0 1 16 16",
        "M5.5 19.5 L5.51 19.5",
    )
    val Stats = v5Icon(
        "M3 3v18h18",
        "m7 15 4-5 3 3 5-7",
    )
    val My = v5Icon(
        "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2",
        "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
    )
    val Search = v5Icon(
        "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z",
        "m21 21-4.3-4.3",
    )
    val Menu = v5Icon("M4 6h16", "M4 12h16", "M4 18h16")
    val Back = v5Icon("m15 18-6-6 6-6")
    val Settings = v5Icon(
        "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.2-.5 1.7 1.7 0 0 0-1.7 1.7v.2a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 7 19.4l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.2A1.7 1.7 0 0 0 4.6 8.4l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9.4A1.7 1.7 0 0 0 10 4.6V4a2 2 0 1 1 4 0v.2a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9v.1a1.7 1.7 0 0 0 1.5 1H22a2 2 0 1 1 0 4h-.2a1.7 1.7 0 0 0-1.5 1z",
    )
    val Check = v5Icon("M20 6 9 17l-5-5")
    val Chevron = v5Icon("m9 18 6-6-6-6")
    val Plus = v5Icon("M12 5v14", "M5 12h14")
}
