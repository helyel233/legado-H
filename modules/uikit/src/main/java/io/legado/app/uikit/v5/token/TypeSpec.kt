package io.legado.app.uikit.v5.token

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * P0-1 排版（docs/ui-v5-skeleton-first.md 3.4）。
 *
 * 沿用 6 级结构，但重新定值：更大、层级更清晰，一眼能分清标题/内容/辅助信息。
 * 字重与中文字距是界面包参数；字体族（P3-1）来自主题包，标题类用 title 字体、
 * 正文类用 ui 字体，缺省回落系统字体。
 */
data class TypeSpec(
    /** 标题字重 */
    val titleWeight: TitleWeight = TitleWeight.BOLD,
    /** 中文标题字距（中文没有词间空格，加一点字距立刻不一样） */
    val cjkSpacing: Boolean = true,
    /** 全局字号缩放，1 = 基准 */
    val scale: Float = 1f,
    /** 界面字体（主题包 fonts.ui），null = 系统默认 */
    val uiFont: FontFamily? = null,
    /** 标题字体（主题包 fonts.title），null = 跟随 uiFont */
    val titleFont: FontFamily? = null,
) {
    private val f = scale.coerceIn(SCALE_RANGE)

    /** 中文字距实际值；非中文环境下也可保留，不影响拉丁文本观感。 */
    private val cjk: TextUnit get() = if (cjkSpacing) 0.3.sp else 0.sp
    private val titleFamily: FontFamily? get() = titleFont ?: uiFont

    val display: TextStyle get() = TextStyle(
        fontSize = (30f * f).sp, lineHeight = (38f * f).sp,
        fontWeight = titleWeight.value, letterSpacing = cjk,
        fontFamily = titleFamily,
    )

    val headline: TextStyle get() = TextStyle(
        fontSize = (22f * f).sp, lineHeight = (29f * f).sp,
        fontWeight = titleWeight.value, letterSpacing = cjk,
        fontFamily = titleFamily,
    )

    val title: TextStyle get() = TextStyle(
        fontSize = (17f * f).sp, lineHeight = (24f * f).sp,
        fontWeight = titleWeight.value, letterSpacing = cjk,
        fontFamily = titleFamily,
    )

    val body: TextStyle get() = TextStyle(
        fontSize = (15f * f).sp, lineHeight = (23f * f).sp,
        fontWeight = FontWeight.Normal,
        fontFamily = uiFont,
    )

    val label: TextStyle get() = TextStyle(
        fontSize = (14f * f).sp, lineHeight = (20f * f).sp,
        fontWeight = FontWeight.Medium,
        fontFamily = uiFont,
    )

    val caption: TextStyle get() = TextStyle(
        fontSize = (12f * f).sp, lineHeight = (17f * f).sp,
        fontWeight = FontWeight.Normal,
        fontFamily = uiFont,
    )

    companion object {
        val SCALE_RANGE = 0.85f..1.6f
        val DEFAULT = TypeSpec()
    }
}

enum class TitleWeight(val id: String, val displayName: String, val value: FontWeight) {
    MEDIUM("medium", "中等", FontWeight.Medium),
    SEMI_BOLD("semiBold", "中粗", FontWeight.SemiBold),
    BOLD("bold", "粗", FontWeight.Bold),
    ;

    companion object {
        fun byId(id: String): TitleWeight =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BOLD
    }
}
