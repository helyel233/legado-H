package io.legado.app.uikit.v5.contract

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.token.DensitySpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.MotionSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.uikit.v5.token.TypeSpec

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
 * 这里只有"用第几档材质 / 用哪种语义色 / 取哪类形状名字"的接口，
 * **没有 VisualSpec 本身**，也没有任何"自己画顶栏 / 底栏 / 背景 / 自定义圆角 /
 * 自定义动效"的入口。页面想自作主张也做不到 —— 这不是约定，是 API 层面的不存在。
 *
 * 它是接口而非具体类：实现类 [ContentScopeImpl] 在 uikit 内且 `internal`，
 * 页面拿到的永远只是这个窄接口，[io.legado.app.uikit.v5.token.VisualSpec]
 * （形状族、玻璃五件套、密度系数等）对页面不可见。
 */
interface ContentScope {

    /** 按容器种类取形状。页面只能说"我要卡片形状"，不能说"我要 13dp 圆角"。 */
    fun shape(kind: ShapeKind): Shape

    /** 字阶（已套用界面包的字重与中文字距） */
    val type: TypeSpec

    /** 间距（已套用密度） */
    val density: DensitySpec

    /** 动效规格：页面只取规格，不自己写时长数字 */
    val motion: MotionSpec

    /** 语义色：只有名字，没有十六进制 */
    val color: AppSemanticColors

    /**
     * 内容容器。页面说"这里用浮起档"，宿主决定它长什么样。
     * 这是页面唯一能拿到的"带背景的盒子"，且外观参数不由页面提供。
     */
    @Composable
    fun Surface(
        level: MaterialLevel = MaterialLevel.RAISED,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    )

    /**
     * 导航请求：跳到注册表里的另一个目的地。
     * 导航是路由语义，不是外观 —— 所以这是页面唯一能触发的"骨架动作"，
     * 且只能按 id 跳注册表里存在的页面，不能携带任何外观指令。
     */
    fun navigate(destinationId: String)
}

/** 宿主专用工厂。页面拿不到 [io.legado.app.uikit.v5.token.VisualSpec]，只能拿到窄接口。 */
object HostScope {
    fun create(
        visual: io.legado.app.uikit.v5.token.VisualSpec,
        colors: AppSemanticColors,
        navigate: (String) -> Unit = {},
    ): ContentScope = ContentScopeImpl(visual, colors, navigate)
}

internal class ContentScopeImpl(
    private val visual: io.legado.app.uikit.v5.token.VisualSpec,
    override val color: AppSemanticColors,
    private val nav: (String) -> Unit,
) : ContentScope {

    override fun shape(kind: ShapeKind): Shape = visual.shape(kind)

    override val type: TypeSpec get() = visual.type

    override val density: DensitySpec get() = visual.density

    override val motion: MotionSpec get() = visual.motion

    @Composable
    override fun Surface(
        level: MaterialLevel,
        modifier: Modifier,
        content: @Composable () -> Unit,
    ) = AppSurface(level = level, modifier = modifier, content = content)

    override fun navigate(destinationId: String) = nav(destinationId)
}

/**
 * 语义色表：页面只认名字不认值。
 * 具体数值由 ThemeResolver 从主题包推导，Provider 缺失时用中性默认。
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
