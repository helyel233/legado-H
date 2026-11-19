package io.legado.app.help.config.appearance

/**
 * 「外观中心」管理的 6 大全局视觉槽位。
 *
 * 导航骨架、底部 Tab 显隐、应用图标、状态栏、页面布局、发现套件与书籍详情
 * 属于结构/系统偏好（「界面布局与系统栏」），不在此槽位集合内。
 */
enum class AppearanceSlot {
    THEME,
    TOP_BAR,
    NAVIGATION_BAR,
    COVER,
    GLASS,
    WELCOME
}
