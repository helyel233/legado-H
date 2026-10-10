package io.legado.app.uikit.v5.contract

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import io.legado.app.uikit.v5.token.DensitySpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.uikit.v5.token.TypeSpec
import io.legado.app.uikit.v5.token.VisualSpec

/**
 * P0-2 页面内容接口（docs/ui-v5-skeleton-first.md 2.1 铁律：页面不画外框）。
 *
 * 页面实现两件事：`chrome()` 声明外框需求，`Content(scope)` 画自己的内容。
 * 其余（背景、顶栏、底栏、侧栏、弹层、动效）全部由宿主渲染。
 */
interface AppContent {

    /** 宿主在渲染前调用。返回的是意图声明，不是外观。 */
    fun chrome(): ScreenChrome = ScreenChrome()

    @Composable
    fun Content(scope: ContentScope)
}

/**
 * 页面绘制作用域 —— **机制保证**的核心。
 *
 * 这里只提供"用第几档材质 / 用哪种语义色 / 取哪类形状名字"的接口，
 * 完全没有"自己画顶栏/底栏/背景/自定义圆角/自定义动效"的入口。
 * 页面想自作主张也做不到 —— 这不是约定，是 API 层面的不存在。
 *
 * 构造函数 internal：只有宿主（`ui/host`）能创建实例。
 */
class ContentScope internal constructor(
    internal val visual: VisualSpec,
    internal val colors: AppSemanticColors,
) {
    /** 按容器种类取形状。页面只能说"我要卡片形状"，不能说"我要 13dp 圆角"。 */
    fun shape(kind: ShapeKind): Shape = visual.shape(kind)

    /** 字阶（已套用界面包的字重与中文字距） */
    val type: TypeSpec get() = visual.type

    /** 间距（已套用密度） */
    val density: DensitySpec get() = visual.density

    /** 动效规格：页面只取规格，不自己写时长数字 */
    val motion get() = visual.motion

    /** 语义色：只有名字，没有十六进制 */
    val color: AppSemanticColors get() = colors

    /**
     * 内容容器。页面说"这里用浮起档"，宿主决定它长什么样。
     * 这是页面唯一能拿到的"带背景的盒子"，且外观参数不由页面提供。
     */
    @Composable
    fun Surface(
        level: MaterialLevel = MaterialLevel.RAISED,
        modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
        content: @Composable () -> Unit,
    ) = io.legado.app.uikit.v5.runtime.AppSurface(level = level, modifier = modifier, content = content)
}

/**
 * 语义色表：页面只认名字不认值。
 * 具体数值由 ThemeResolver 从主题包推导，P0 阶段给出中性默认实现。
 */
data class AppSemanticColors(
    val background: androidx.compose.ui.graphics.Color,
    val surface: androidx.compose.ui.graphics.Color,
    val surfaceVariant: androidx.compose.ui.graphics.Color,
    val primary: androidx.compose.ui.graphics.Color,
    val onPrimary: androidx.compose.ui.graphics.Color,
    val secondary: androidx.compose.ui.graphics.Color,
    val onSurface: androidx.compose.ui.graphics.Color,
    val onSurfaceVariant: androidx.compose.ui.graphics.Color,
    val outline: androidx.compose.ui.graphics.Color,
    val success: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val error: androidx.compose.ui.graphics.Color,
)
