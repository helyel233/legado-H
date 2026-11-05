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

// 深浅色自适应投影/衬底的统一参数：浅色文字配黑影、深色文字配白影；
// 衬底与投影取反向色，保证任意壁纸明暗下文字都有可读的边界。全站引用这一份口径。
private const val SHADOW_OFFSET_X = 1f
private const val SHADOW_OFFSET_Y = 1f
private const val SHADOW_BLUR_RADIUS = 4f
private const val LIGHT_TEXT_SHADOW_ALPHA = 0.7f
private const val DARK_TEXT_SHADOW_ALPHA = 0.75f
private const val LIGHT_TEXT_SCRIM_ALPHA = 0.45f
private const val DARK_TEXT_SCRIM_ALPHA = 0.55f

private fun adaptiveTextShadow(textColor: Color): Shadow =
    if (textColor.luminance() > 0.5f) {
        Shadow(
            color = Color.Black.copy(alpha = LIGHT_TEXT_SHADOW_ALPHA),
            offset = Offset(SHADOW_OFFSET_X, SHADOW_OFFSET_Y),
            blurRadius = SHADOW_BLUR_RADIUS
        )
    } else {
        Shadow(
            color = Color.White.copy(alpha = DARK_TEXT_SHADOW_ALPHA),
            offset = Offset(SHADOW_OFFSET_X, SHADOW_OFFSET_Y),
            blurRadius = SHADOW_BLUR_RADIUS
        )
    }

/**
 * 背景图模式下、按文字明暗自适应的半透明衬底：浅字配深衬底、深字配浅衬底。
 * 与 [adaptiveTextShadow] 共用同一份深浅自适应口径；未开背景图时返回 [Color.Transparent]。
 */
@Composable
fun imageBgAdaptiveScrim(textColor: Color): Color {
    if (!rememberHasUsableBgImage()) return Color.Transparent
    return if (textColor.luminance() > 0.5f) {
        Color.Black.copy(alpha = LIGHT_TEXT_SCRIM_ALPHA)
    } else {
        Color.White.copy(alpha = DARK_TEXT_SCRIM_ALPHA)
    }
}

/**
 * 背景图模式下的行/卡半透明衬底色（未开背景图时返回 [Color.Transparent]）。
 * [baseColor] 传入主题行色（如 palette.row / settings.row，已经 UiCorner.surfaceColor 调透明度）。
 * 需要按文字色自适应的场景改用 [imageBgAdaptiveScrim]。
 */
fun imageBgScrimColor(hasBgImage: Boolean, baseColor: Color): Color =
    if (hasBgImage) baseColor else Color.Transparent
