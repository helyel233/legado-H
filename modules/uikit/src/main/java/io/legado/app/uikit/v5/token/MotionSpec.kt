package io.legado.app.uikit.v5.token

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec

/**
 * P0-1 动效（docs/ui-v5-skeleton-first.md 3.3）。
 *
 * 现代化最容易被感知、也是旧版最缺的部分。三档强度：
 * 克制 / 标准 / 生动，外加一个尊重系统设置的"减少动效"开关（无障碍）。
 */
enum class MotionLevel(
    val id: String,
    val displayName: String,
    /** 短：按压反馈、图标形变 */
    val shortMs: Int,
    /** 中：页面切换、顶栏折叠 */
    val mediumMs: Int,
    /** 长：容器变换、抽屉 */
    val longMs: Int,
    /** 过渡曲线 */
    val easing: Easing,
) {
    CALM("calm", "克制", shortMs = 120, mediumMs = 200, longMs = 260,
        easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),

    STANDARD("standard", "标准", shortMs = 160, mediumMs = 300, longMs = 380,
        easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),

    /** 生动：弹性（overshoot）曲线，果冻感 */
    EXPRESSIVE("expressive", "生动", shortMs = 220, mediumMs = 380, longMs = 460,
        easing = CubicBezierEasing(0.34f, 1.4f, 0.5f, 1f)),
    ;

    companion object {
        fun byId(id: String): MotionLevel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STANDARD
    }
}

data class MotionSpec(
    val level: MotionLevel = MotionLevel.STANDARD,
    /** 跟随系统"减少动效"：打开后所有过渡退化为极短线性，不做位移与形变 */
    val reduce: Boolean = false,
) {
    private val short: Int get() = if (reduce) 80 else level.shortMs
    private val medium: Int get() = if (reduce) 100 else level.mediumMs
    private val long: Int get() = if (reduce) 120 else level.longMs
    private val ease: Easing get() = if (reduce) Easing { it } else level.easing

    fun <T> shortSpec(): TweenSpec<T> = TweenSpec(durationMillis = short, easing = ease)
    fun <T> mediumSpec(): TweenSpec<T> = TweenSpec(durationMillis = medium, easing = ease)
    fun <T> longSpec(): TweenSpec<T> = TweenSpec(durationMillis = long, easing = ease)

    /** 图标选中形变、按压回弹用弹簧；减少动效时退化为瞬时。 */
    fun <T> springSpec(): SpringSpec<T> = SpringSpec(
        dampingRatio = if (reduce) Spring.DampingRatioNoBouncy else Spring.DampingRatioMediumBouncy,
        stiffness = if (reduce) Spring.StiffnessHigh else Spring.StiffnessMediumLow,
    )

    /** 是否允许位移类动效（容器变换、共享元素）。减少动效时只做淡入。 */
    val allowMovement: Boolean get() = !reduce

    companion object {
        val DEFAULT = MotionSpec()
    }
}
