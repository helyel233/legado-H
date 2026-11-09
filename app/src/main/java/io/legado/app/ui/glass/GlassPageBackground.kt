package io.legado.app.ui.glass

import androidx.compose.ui.graphics.Color

/**
 * Compose 页面根背景的壁纸模式策略。
 *
 * `BaseActivity` / `BaseFragment` 在背景图可用时会把宿主背景置空（`applyRootBackgroundPolicy`），
 * 透出壁纸层；Compose 页面若还在根节点铺主题页色，壁纸就被页面自己盖住。此时玻璃采样的是
 * 壁纸位图，卡片里会出现「用户根本看不见的背景」，属于错误管线（见方案文档 2.3）。
 *
 * 因此：背景图可用 = 页面透明（壁纸可见，玻璃采样源真实）；否则用主题页色兜底，
 * 未开背景图时的观感逐值不变。
 */
fun glassPageBackgroundColor(pageColor: Color, hasBgImage: Boolean): Color =
    if (hasBgImage) Color.Transparent else pageColor
