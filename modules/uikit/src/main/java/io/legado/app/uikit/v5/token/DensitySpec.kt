package io.legado.app.uikit.v5.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P0-1 密度（docs/ui-v5-skeleton-first.md 3.5）。
 *
 * 8dp 网格，三档联动：屏幕边距 / 区块间距 / 列表行高 / 图标尺寸一起变。
 * 关键改动：标准档屏幕边距从旧的 12dp 提到 16dp —— 单独这一条就能让观感"呼吸"起来。
 */
enum class DensityLevel(val id: String, val displayName: String, val factor: Float) {
    COMPACT("compact", "紧凑", 0.85f),
    STANDARD("standard", "标准", 1.00f),
    COMFORTABLE("comfortable", "舒展", 1.20f),
    ;

    companion object {
        val FACTOR_RANGE = 0.7f..1.4f
        fun byId(id: String): DensityLevel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STANDARD
    }
}

/**
 * 由密度系数派生出的一组实际间距。全部是只读派生值，业务代码不得另写数字。
 */
data class DensitySpec(
    /** 密度系数，允许自由微调（不局限于三档） */
    val factor: Float = DensityLevel.STANDARD.factor,
) {
    private val f = factor.coerceIn(DensityLevel.FACTOR_RANGE)

    /** 实际生效的系数（已 clamp）。宿主与测试用它，[factor] 保留为原始输入。 */
    val clamped: Float get() = f

    /** 屏幕左右边距 */
    val screenPadding: Dp get() = (16f * f).dp

    /** 卡片 / 区块之间的间距 */
    val blockGap: Dp get() = (12f * f).dp

    /** 卡片内边距 */
    val cardPadding: Dp get() = (13f * f).dp

    /** 列表项最小高度 */
    val rowHeight: Dp get() = (56f * f).dp

    /** 列表项水平内边距 */
    val rowPaddingH: Dp get() = (12f * f).dp

    /** 列表项垂直内边距 */
    val rowPaddingV: Dp get() = (11f * f).dp

    /** 栅格间距 */
    val gridGap: Dp get() = (10f * f).dp

    /** 标准图标按钮尺寸 */
    val iconButtonSize: Dp get() = (38f * f).dp

    /** 内容区图标尺寸 */
    val iconSize: Dp get() = (22f * f).dp

    /** 顶栏高度（不含状态栏） */
    val topBarHeight: Dp get() = (56f * f).dp

    companion object {
        val DEFAULT = DensitySpec()
    }
}
