package io.legado.app.uikit.v5.contract

/**
 * P0-2 页面注册表中的一条（docs/ui-v5-skeleton-first.md 2.3）。
 *
 * 现状问题：Tab 清单有三处真相（MainBottomNavConfig、MainActivity 手写 id→Fragment 映射、
 * 侧栏又是底栏菜单的镜像）。新骨架改为**一张表**，宿主从它生成：
 * 底栏菜单 / 侧栏菜单 / 顶栏 / 内容路由 / 二级入口 —— 生成逻辑只有一份。
 */
data class AppDestination(
    /** 稳定 id，用于路由与顺序持久化 */
    val id: String,
    /** 显示名 */
    val title: DestTitle,
    /** 导航图标（侧栏、底栏共用） */
    val icon: DestIcon? = null,
    /** 内容工厂 */
    val factory: () -> AppContent,
    /** 能否作为主界面 Tab */
    val topLevel: Boolean = false,
    /** 侧栏归属分组；NONE 表示不进侧栏 */
    val sideGroup: SideGroup = SideGroup.NONE,
    /** Tab 默认是否可见（对应现在的 6 项默认只显示 3 项） */
    val defaultVisible: Boolean = true,
    /** 是否锁定可见（不允许用户隐藏） */
    val lockedVisible: Boolean = false,
    /** 排序权重，小者在前 */
    val order: Int = 0,
    /** 设置搜索关键词 */
    val keywords: List<String> = emptyList(),
)

enum class SideGroup(val id: String, val displayName: String) {
    NONE("none", "不进侧栏"),
    MAIN("main", "主界面"),
    CONTENT("content", "内容与书源"),
    SETTINGS("settings", "设置"),
    ;
}

/** 标题：直接文本或字符串资源（项目已有大量 string res，两种都要支持）。 */
sealed interface DestTitle {
    data class Text(val value: String) : DestTitle
    data class Res(val id: Int, val args: List<Any> = emptyList()) : DestTitle
}
