package io.legado.app.uikit.v5.token

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P0-1 材质分档（docs/ui-v5-skeleton-first.md 2.2 / 3.2）。
 *
 * 代替旧的"阴影 elevation"：层次关系由材质强度表达，不靠一层黑影。
 * 只有 E2 / E3 吃玻璃参数；关掉玻璃时它们退回实色 + 细描边，依然好看。
 */
enum class MaterialLevel(
    val id: String,
    /** 是否消费玻璃五件套 */
    val usesGlass: Boolean,
    /** 是否有描边（玻璃态为高光描边，实色态为细分隔描边） */
    val hasStroke: Boolean,
    /** 基础投影高度 dp，0 = 无阴影 */
    val shadowDp: Dp,
) {
    /** E0 平铺：无容器，内容直接落在背景上 */
    FLAT("E0", usesGlass = false, hasStroke = false, shadowDp = 0.dp),

    /** E1 嵌入：比背景略深/略浅，无描边无阴影 */
    EMBEDDED("E1", usesGlass = false, hasStroke = false, shadowDp = 0.dp),

    /** E2 浮起：卡片、面板、菜单 */
    RAISED("E2", usesGlass = true, hasStroke = true, shadowDp = 2.dp),

    /** E3 悬浮：弹窗、侧栏、悬浮底栏、抽屉 */
    FLOATING("E3", usesGlass = true, hasStroke = true, shadowDp = 16.dp),
    ;

    companion object {
        /** 顶栏/底栏可配置的材质档（只允许 E0-E3 中的这几档）。 */
        val BAR_LEVELS = listOf(FLAT, EMBEDDED, RAISED, FLOATING)

        fun byId(id: String): MaterialLevel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: RAISED
    }
}

/**
 * P0-1 玻璃五件套（docs/ui-v5-skeleton-first.md 3.2）。
 *
 * 不是"调个透明度"，而是五个值共同构成真玻璃：
 * 模糊 + 基底透明度 + 高光描边 + 噪声 + 饱和度提升。
 */
data class GlassSpec(
    /** 关掉时容器退回实色 + 细描边（低端机与"不喜欢玻璃"的兜底） */
    val enabled: Boolean = false,
    /** 基底透明度 0..1 */
    val alpha: Float = DEFAULT_ALPHA,
    /** 背景模糊半径 dp 0..30 */
    val blurDp: Float = DEFAULT_BLUR,
    /** 高光描边：顶边一条亮线，模拟玻璃边缘反光 */
    val stroke: Boolean = true,
    /** 噪声强度 0..0.3：极细颗粒，消除色带、让材质有实体感 */
    val noise: Float = DEFAULT_NOISE,
    /** 饱和度提升 0..1：透出的颜色更鲜艳（vibrancy） */
    val vibrancy: Float = DEFAULT_VIBRANCY,
) {
    /** 描边颜色（高光），由调用方按当前明暗决定基底色。 */
    fun strokeColor(onSurface: Color): Color = onSurface.copy(alpha = if (stroke) 0.18f else 0f)

    /** 噪声叠层的最终不透明度；关玻璃时不画噪声。 */
    fun noiseAlpha(): Float = if (enabled) noise.coerceIn(NOISE_RANGE) else 0f

    /** 生效的模糊半径。宿主直接读这个，不必自己判断 [enabled]。 */
    fun effectiveBlur(): Float = if (enabled) blurDp.coerceIn(BLUR_RANGE) else 0f

    /** 生效的基底不透明度：关玻璃时为 1（实色容器）。 */
    fun effectiveAlpha(): Float = if (enabled) alpha.coerceIn(ALPHA_RANGE) else 1f

    /** 生效的饱和度提升；关玻璃时为 0。 */
    fun effectiveVibrancy(): Float = if (enabled) vibrancy.coerceIn(VIBRANCY_RANGE) else 0f

    companion object {
        const val DEFAULT_ALPHA = 0.42f
        const val DEFAULT_BLUR = 22f
        const val DEFAULT_NOISE = 0.05f
        const val DEFAULT_VIBRANCY = 0.3f

        val ALPHA_RANGE = 0f..1f
        val BLUR_RANGE = 0f..30f
        val NOISE_RANGE = 0f..0.3f
        val VIBRANCY_RANGE = 0f..1f

        /** 关玻璃时的实色容器（alpha 拉满、模糊清零），保证两套观感都成立。 */
        val SOLID = GlassSpec(enabled = false, alpha = 1f, blurDp = 0f, stroke = true, noise = 0f, vibrancy = 0f)
    }
}
