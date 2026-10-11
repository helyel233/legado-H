package io.legado.app.uikit.v5.pack

import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.MotionLevel
import io.legado.app.uikit.v5.token.NavPosition
import io.legado.app.uikit.v5.token.NavVisibility

/**
 * P3-2 内置 5 个官方界面包（docs/ui-v5-skeleton-first.md 4.4）。
 *
 * 档位 = 参数组的推荐组合；选包即一键写入，包内仍可逐项微调（另存模型）。
 * **默认包 = 悬浮玻璃** —— 本轮视觉现代化的第一眼。
 */
object BuiltinLayoutPackages {

    /** 经典：干净耐看，接近传统但精致很多（无玻璃）。 */
    val classic = LayoutPackageSpecV2(
        id = "classic", name = "经典", author = "legado-H",
        navPosition = NavPosition.BOTTOM.id,
        navVisibility = NavVisibility.ALWAYS.id,
        shapeFamily = "soft", shapeScale = 1f,
        glassEnabled = false,
        densityFactor = 1f,
        motionLevel = MotionLevel.STANDARD.id,
        topBarLevel = MaterialLevel.RAISED.id,
        bottomBarLevel = MaterialLevel.RAISED.id,
    )

    /** 悬浮玻璃：默认推荐，最能体现现代化。 */
    val floatGlass = LayoutPackageSpecV2(
        id = "float", name = "悬浮玻璃", author = "legado-H",
        navPosition = NavPosition.FLOAT.id,
        navVisibility = NavVisibility.SCROLL_HIDE.id,
        shapeFamily = "round", shapeScale = 1.2f,
        glassEnabled = true, glassAlpha = 0.42f, glassBlur = 22f,
        densityFactor = 1f,
        motionLevel = MotionLevel.EXPRESSIVE.id,
        topBarLevel = MaterialLevel.RAISED.id,
        bottomBarLevel = MaterialLevel.FLOATING.id,
    )

    /** 侧栏沉浸：大屏 / 横屏友好。 */
    val side = LayoutPackageSpecV2(
        id = "side", name = "侧栏沉浸", author = "legado-H",
        navPosition = NavPosition.SIDE.id,
        navVisibility = NavVisibility.ALWAYS.id,
        shapeFamily = "soft", shapeScale = 1.1f,
        glassEnabled = true, glassAlpha = 0.36f, glassBlur = 26f,
        densityFactor = 1.1f,
        motionLevel = MotionLevel.EXPRESSIVE.id,
        topBarLevel = MaterialLevel.RAISED.id,
        bottomBarLevel = MaterialLevel.FLOATING.id,
    )

    /** 极简：效率至上，紧凑 + 方 + 无玻璃 + 克制动效。 */
    val minimal = LayoutPackageSpecV2(
        id = "minimal", name = "极简", author = "legado-H",
        navPosition = NavPosition.BOTTOM.id,
        navVisibility = NavVisibility.ALWAYS.id,
        shapeFamily = "sharp", shapeScale = 0.6f,
        glassEnabled = false,
        densityFactor = 0.85f,
        motionLevel = MotionLevel.CALM.id,
        topBarLevel = MaterialLevel.EMBEDDED.id,
        bottomBarLevel = MaterialLevel.EMBEDDED.id,
        topBarCollapse = false,
    )

    /** 花瓣：个性年轻，不对称圆角。 */
    val petal = LayoutPackageSpecV2(
        id = "petal", name = "花瓣", author = "legado-H",
        navPosition = NavPosition.FLOAT.id,
        navVisibility = NavVisibility.SCROLL_HIDE.id,
        shapeFamily = "petal", shapeScale = 1.3f,
        glassEnabled = true, glassAlpha = 0.52f, glassBlur = 16f,
        densityFactor = 1.15f,
        motionLevel = MotionLevel.EXPRESSIVE.id,
        topBarLevel = MaterialLevel.RAISED.id,
        bottomBarLevel = MaterialLevel.FLOATING.id,
    )

    val all: List<LayoutPackageSpecV2> =
        listOf(classic, floatGlass, side, minimal, petal)

    fun byId(id: String): LayoutPackageSpecV2? = all.firstOrNull { it.id == id }

    /** 出厂默认：悬浮玻璃。 */
    val default: LayoutPackageSpecV2 = floatGlass
}
