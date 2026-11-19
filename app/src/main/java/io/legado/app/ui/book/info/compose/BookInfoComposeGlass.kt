package io.legado.app.ui.book.info.compose

/**
 * Compose 详情页（`BookInfoComposeActivity` / [BookInfoComposeRoute]）的玻璃准入与参数。
 *
 * 纯 JVM 文件（不引用 Android / Compose 类型），便于单测锁定真值表。
 *
 * ## 为什么新增这一处（第四轮根因）
 *
 * 详情页有两条渲染路径，由 `BookInfoNavigator.targetClass()` 按 `bookInfoPageStyle` 分流：
 * - CLASSIC → `BookInfoActivity`（XML），前几轮在这里接了 `BookInfoBackdropSurface`；
 * - IMMERSIVE_COMPOSE → `BookInfoComposeActivity`，走本文件的 [BookInfoComposeRoute]，
 *   **前几轮完全没接过**——它的卡片 / 顶栏 / 底部按钮一直是实底渐变，玻璃档开也毫无变化。
 *
 * 用户报告的「卡片 / 顶栏 / 底部按钮玻璃无变化」无法从代码排除任一条路径
 * （`bookInfoPageStyle` 是用户设置，默认 CLASSIC 但可被改），因此两条都必须接。
 *
 * 口径与 CLASSIC 路径、阅读设置弹窗统一：**只认玻璃档，不叠「有背景图」门**。背后是封面
 * 渐变与模糊封面，半透明卡面透出它们即磨砂观感，无背景图偏好时也不会退化成死板实底。
 */
internal const val BOOK_INFO_COMPOSE_GLASS_ALPHA_CAP = 0.80f

/** Compose 详情页玻璃准入：只认玻璃档（solid / 墨水屏 / 低内存 已折叠为 [glassEnabled]）。 */
internal fun bookInfoComposeGlassAllowed(glassEnabled: Boolean): Boolean = glassEnabled

/**
 * 统一计算 Compose 详情页卡面 alpha（与 CLASSIC 路径 `BookInfoBackdropSurface` 同口径）：
 * - 玻璃档（glass/frosted）：压到 [cap]（0.80f）；
 * - 透明无玻璃档（clear）：压到 [clearAlpha]（`GlassMaterialPreset.CLEAR_SURFACE_ALPHA` = 0.86f）；
 * - 实底 / 降级：逐值保留 [opaqueAlpha]；本就半透明的卡（如简介卡 0.28f）取较小值后不变。
 */
internal fun bookInfoComposeCardAlpha(
    glassEnabled: Boolean,
    clearEnabled: Boolean,
    opaqueAlpha: Float,
    clearAlpha: Float = 0.86f,
    cap: Float = BOOK_INFO_COMPOSE_GLASS_ALPHA_CAP
): Float = when {
    glassEnabled -> minOf(opaqueAlpha, cap)
    clearEnabled -> minOf(opaqueAlpha, clearAlpha)
    else -> opaqueAlpha
}

/**
 * 是否给卡面补玻璃描边。玻璃档或 CLEAR 档为真时由调用方画 1dp `glass_stroke`，与全 App 同源；
 * 非玻璃档返回 false（逐值等价接入前：无描边）。
 */
internal fun bookInfoComposeCardStroke(
    glassEnabled: Boolean,
    clearEnabled: Boolean
): Boolean = glassEnabled || clearEnabled
