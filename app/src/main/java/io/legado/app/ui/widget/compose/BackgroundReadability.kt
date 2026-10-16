package io.legado.app.ui.widget.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.rememberThemeUiPalette

/**
 * 复杂背景图下的文字可读性辅助。
 *
 * 背景图模式（[ThemeConfig.hasUsableBgImage]）下页面背景透明，主题文字色直接压图难分辨：
 * - 独立标题/提示文本：用 [imageBgTextShadow] 加自适应投影（浅色文字配黑影、深色文字配白影）；
 * - 列表行/卡片文字区：铺 [imageBgScrimColor] 半透明衬底（跟随主题卡色与界面不透明度）。
 * 未开背景图时两者均返回无效果值，普通主题观感不变。
 */
@Composable
fun rememberHasUsableBgImage(): Boolean {
    val context = LocalContext.current
    val signature = rememberThemeUiPalette().signature
    return remember(context, signature) {
        ThemeConfig.hasUsableBgImage(context)
    }
}

/**
 * 背景图模式下的自适应文字投影；未开背景图时返回 null（无投影）。
 */
@Composable
fun imageBgTextShadow(textColor: Color): Shadow? {
    if (!rememberHasUsableBgImage()) return null
    return adaptiveTextShadow(textColor)
}

private fun adaptiveTextShadow(textColor: Color): Shadow =
    if (textColor.luminance() > 0.5f) {
        Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(1f, 1f), blurRadius = 4f)
    } else {
        Shadow(color = Color.White.copy(alpha = 0.75f), offset = Offset(1f, 1f), blurRadius = 4f)
    }

/**
 * 背景图模式下的行/卡半透明衬底色（未开背景图时返回 [Color.Transparent]）。
 * [baseColor] 传入主题行色（如 palette.row / settings.row，已经 UiCorner.surfaceColor 调透明度）。
 */
fun imageBgScrimColor(hasBgImage: Boolean, baseColor: Color): Color =
    if (hasBgImage) baseColor else Color.Transparent
