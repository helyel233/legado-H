package io.legado.app.uikit.v5.token

import androidx.compose.ui.graphics.Shape

/**
 * P0-1 视觉规格聚合 —— 一个界面包解析后的完整输出。
 *
 * 这是 LayoutResolver 的产出物，也是宿主渲染外框时唯一的数据来源。
 * 全 App 所有窗口读同一份实例，任何一处不得自行覆盖（方案验收第 5 条的机制保证）。
 */
data class VisualSpec(
    // ---- 导航 ----
    val navPosition: NavPosition = NavPosition.FLOAT,
    val navVisibility: NavVisibility = NavVisibility.ALWAYS,

    // ---- 形状 ----
    val shapeFamily: ShapeFamily = SHAPE_SOFT,
    val shapeScale: Float = 1f,

    // ---- 材质与玻璃 ----
    val glass: GlassSpec = GlassSpec(),
    val topBarLevel: MaterialLevel = MaterialLevel.RAISED,
    val bottomBarLevel: MaterialLevel = MaterialLevel.FLOATING,
    /** 顶栏大标题滚动折叠 */
    val topBarCollapse: Boolean = true,

    // ---- 其余旋钮 ----
    val density: DensitySpec = DensitySpec.DEFAULT,
    val motion: MotionSpec = MotionSpec.DEFAULT,
    val icon: IconSpec = IconSpec.DEFAULT,
    val type: TypeSpec = TypeSpec.DEFAULT,
) {
    /** 取某类容器的形状（已套用族 + 缩放）。 */
    fun shape(kind: ShapeKind): Shape = shapeFamily.of(kind, shapeScale)

    /**
     * 某档材质最终是否表现为空容器。
     * 关玻璃时 E2/E3 退回实色卡片，而不是消失 —— 两套观感都要成立。
     */
    fun levelIsVisible(level: MaterialLevel): Boolean = level != MaterialLevel.FLAT

    companion object {
        /** 出厂默认：悬浮玻璃（本轮视觉现代化的第一眼）。 */
        val DEFAULT = VisualSpec(
            navPosition = NavPosition.FLOAT,
            shapeFamily = SHAPE_ROUND,
            shapeScale = 1.2f,
            glass = GlassSpec(enabled = true, alpha = 0.42f, blurDp = 22f, stroke = true, noise = 0.05f, vibrancy = 0.3f),
            topBarLevel = MaterialLevel.RAISED,
            bottomBarLevel = MaterialLevel.FLOATING,
            topBarCollapse = true,
            density = DensitySpec(1.0f),
            motion = MotionSpec(MotionLevel.EXPRESSIVE),
            icon = IconSpec.DEFAULT,
            type = TypeSpec.DEFAULT,
        )
    }
}
