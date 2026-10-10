package io.legado.app.uikit.v5.token

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * P0-1 形状族（docs/ui-v5-skeleton-first.md 3.1）。
 *
 * 现代化第一抓手：旧体系只有"圆角大小"一个维度，做不出风格；
 * 新体系用「族」定义一组容器的性格，全 App 统一，再叠一个缩放系数定强弱。
 *
 * 族是代码常量，界面包只存 `shape.family`（族 id）+ `shape.scale`（系数）。
 */

/** 容器种类。全 App 只有这六种容器形状，业务代码不得另立。 */
enum class ShapeKind { CARD, BAR, BUTTON, FIELD, TAG, COVER }

/**
 * 角定义。
 * - [Radius]：普通圆角，[minorRatio] < 1 时对角落小 → 不对称"花瓣"效果
 * - [Pill]：胶囊（百分比 50%），与尺寸无关
 * - [Cut]：斜切角
 */
sealed interface CornerSpec {
    data class Radius(val dp: Dp, val minorRatio: Float = 1f) : CornerSpec
    data object Pill : CornerSpec
    data class Cut(val dp: Dp) : CornerSpec
}

/** 缩放系数合法区间：0 = 全方，1 = 基准，2 = 超圆。 */
val SHAPE_SCALE_RANGE = 0f..2f

data class ShapeFamily(
    val id: String,
    val displayName: String,
    val card: CornerSpec,
    val bar: CornerSpec,
    val button: CornerSpec,
    val field: CornerSpec,
    val tag: CornerSpec,
    val cover: CornerSpec,
) {
    /** 取某类容器的实际形状。scale 已 clamp 到 [SHAPE_SCALE_RANGE]。 */
    fun of(kind: ShapeKind, scale: Float = 1f): Shape {
        val s = scale.coerceIn(SHAPE_SCALE_RANGE)
        return when (val c = specFor(kind)) {
            is CornerSpec.Pill -> RoundedCornerShape(50)
            is CornerSpec.Cut -> CutCornerShape((c.dp.value * s).roundToInt())
            is CornerSpec.Radius -> radius(c, s)
        }
    }

    fun specFor(kind: ShapeKind): CornerSpec = when (kind) {
        ShapeKind.CARD -> card
        ShapeKind.BAR -> bar
        ShapeKind.BUTTON -> button
        ShapeKind.FIELD -> field
        ShapeKind.TAG -> tag
        ShapeKind.COVER -> cover
    }

    private fun radius(c: CornerSpec.Radius, scale: Float): Shape {
        val major = c.dp * scale
        if (c.minorRatio >= 1f) return RoundedCornerShape(major)
        // 不对称：左上/右下大，右上/左下小 —— M3 Expressive 的花瓣感
        val minor = major * c.minorRatio
        return RoundedCornerShape(
            topStart = major, topEnd = minor, bottomEnd = major, bottomStart = minor,
        )
    }

    companion object {
        fun byId(id: String): ShapeFamily = ALL[id] ?: SHAPE_SOFT
    }
}

// ---------------------------------------------------------------- 五个内置族

/** 柔和：连续圆角风格，耐看的默认。 */
val SHAPE_SOFT = ShapeFamily(
    id = "soft", displayName = "柔和",
    card = CornerSpec.Radius(20.dp),
    bar = CornerSpec.Radius(26.dp),
    button = CornerSpec.Pill,
    field = CornerSpec.Radius(14.dp),
    tag = CornerSpec.Radius(8.dp),
    cover = CornerSpec.Radius(10.dp),
)

/** 圆润：大圆角 + 胶囊栏，活泼亲和。 */
val SHAPE_ROUND = ShapeFamily(
    id = "round", displayName = "圆润",
    card = CornerSpec.Radius(28.dp),
    bar = CornerSpec.Pill,
    button = CornerSpec.Pill,
    field = CornerSpec.Radius(22.dp),
    tag = CornerSpec.Radius(13.dp),
    cover = CornerSpec.Radius(16.dp),
)

/** 利落：小圆角，信息密度高，效率优先。 */
val SHAPE_SHARP = ShapeFamily(
    id = "sharp", displayName = "利落",
    card = CornerSpec.Radius(9.dp),
    bar = CornerSpec.Radius(11.dp),
    button = CornerSpec.Radius(7.dp),
    field = CornerSpec.Radius(7.dp),
    tag = CornerSpec.Radius(4.dp),
    cover = CornerSpec.Radius(5.dp),
)

/** 花瓣：不对称圆角（对角大、对角小），有设计感。 */
val SHAPE_PETAL = ShapeFamily(
    id = "petal", displayName = "花瓣",
    card = CornerSpec.Radius(28.dp, minorRatio = 0.32f),
    bar = CornerSpec.Radius(32.dp, minorRatio = 0.34f),
    button = CornerSpec.Pill,
    field = CornerSpec.Radius(22.dp, minorRatio = 0.32f),
    tag = CornerSpec.Radius(11.dp, minorRatio = 0.45f),
    cover = CornerSpec.Radius(18.dp, minorRatio = 0.38f),
)

/** 切角：斜切，硬朗极客风。 */
val SHAPE_CUT = ShapeFamily(
    id = "cut", displayName = "切角",
    card = CornerSpec.Cut(10.dp),
    bar = CornerSpec.Cut(12.dp),
    button = CornerSpec.Cut(7.dp),
    field = CornerSpec.Cut(8.dp),
    tag = CornerSpec.Cut(5.dp),
    cover = CornerSpec.Cut(8.dp),
)

val ALL_SHAPE_FAMILIES: List<ShapeFamily> =
    listOf(SHAPE_SOFT, SHAPE_ROUND, SHAPE_SHARP, SHAPE_PETAL, SHAPE_CUT)

private val ALL: Map<String, ShapeFamily> = ALL_SHAPE_FAMILIES.associateBy { it.id }
