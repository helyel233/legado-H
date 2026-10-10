package io.legado.app.uikit.v5.contract

import io.legado.app.uikit.v5.token.MaterialLevel

/**
 * P0-2 页面对外框的**声明**（docs/ui-v5-skeleton-first.md 2.4）。
 *
 * 关键：声明的是"意图"，不是"外观"。
 * 页面说"我要大标题折叠顶栏"，宿主决定顶栏多高、什么材质、标题什么字号、怎么折叠。
 * 这份数据里没有任何尺寸、颜色、圆角 —— 页面想指定也指定不了。
 */
data class ScreenChrome(
    /** 顶栏模式 */
    val topBar: TopBarMode = TopBarMode.NONE,
    /** 顶栏标题（大标题折叠态与收起态共用） */
    val title: DestTitle = DestTitle.Text(""),
    /** 顶栏副标题（大标题模式下显示在标题下方） */
    val subtitle: DestTitle? = null,
    /** 顶栏右上角操作（宿主决定形状、尺寸、材质、点击反馈） */
    val actions: List<ChromeAction> = emptyList(),
    /** 左上角是否显示返回（非 Tab 页通常为 true） */
    val showBack: Boolean = false,
    /** 内容区材质档（页面说"我的卡片用浮起档"，不指定具体颜色与圆角） */
    val contentLevel: MaterialLevel = MaterialLevel.FLAT,
    /** 进入动画 */
    val enterTransition: EnterTransition = EnterTransition.NONE,
    /** 该页是否参与导航栏的滚动隐藏 */
    val scrollAffectsNav: Boolean = true,
    /** 该页是否需要底部安全区留白（有底栏时为 true） */
    val reserveNavSpace: Boolean = true,
)

enum class TopBarMode {
    /** 无顶栏（内容自己处理顶部空间，宿主仍会留出状态栏高度） */
    NONE,

    /** 小标题顶栏 */
    TITLE,

    /** 大标题 + 滚动折叠成小标题 */
    LARGE_TITLE,

    /** 搜索框顶栏 */
    SEARCH,
}

enum class EnterTransition {
    /** Tab 切换：不做容器变换，淡入 */
    NONE,

    /** 从列表进入详情：容器变换（宿主做，页面只声明） */
    CONTAINER_TRANSFORM,

    /** 弹层式页面：缩放淡入 */
    POP,
}

/**
 * 顶栏操作项。只给"语义 id + 图标 + 回调"，外观全部由宿主决定。
 */
data class ChromeAction(
    val id: String,
    val icon: DestIcon,
    val contentDescription: DestTitle = DestTitle.Text(""),
    val onClick: () -> Unit,
)

/** 图标：P0 阶段允许直接给 ImageVector，也支持资源 id。 */
sealed interface DestIcon {
    data class Res(val id: Int) : DestIcon
    class Vector(val imageVector: androidx.compose.ui.graphics.vector.ImageVector) : DestIcon
}
