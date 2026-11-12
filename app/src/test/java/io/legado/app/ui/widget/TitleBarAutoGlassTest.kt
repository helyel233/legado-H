package io.legado.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏「自动 opt-in」判定的真值表锁。
 *
 * 与 [TitleBarGlassTest] 的关系：那边锁的是顶栏**自身**能不能接玻璃（opaque /
 * 透明导航栏 / 墨水屏 / 底图），这边锁的是 `BaseActivity` / `BaseFragment` 在什么情况下
 * 才对该顶栏**发起**自动 opt-in。多出来的两条前置条件是本次反馈的直接根因：
 *
 * - 布局显式退出（`app:wallpaperGlass="false"`）的内容域页面（相机预览、WebView 预览）
 *   顶栏背后是内容而不是壁纸，套上玻璃等于画出用户看不见的背景；
 * - 页面自己不露壁纸（`showsWallpaperBehindContent = false`：书籍详情 / 视频 / 音频）
 *   同理。
 *
 * 反面同样重要：这两个条件**不能**顺手写成「默认关闭」。此前 17 个页面手写
 * `applyWallpaperGlass()`、剩下约 20 个二级页顶栏一直实底，就是「默认关 + 人工清单」
 * 守不住的结构性缺口。
 */
class TitleBarAutoGlassTest {

    @Test
    fun defaultLayoutWithWallpaperOptsIn() {
        assertTrue(autoEnabled())
    }

    @Test
    fun layoutOptOutWinsEvenWhenEverythingElseIsReady() {
        assertFalse(autoEnabled(layoutEnables = false))
    }

    @Test
    fun pageThatHidesWallpaperNeverOptsIn() {
        assertFalse(autoEnabled(pageShowsWallpaper = false))
        // 内容域页面同时还是 opaque 顶栏的真实组合：两条都要能独立否决
        assertFalse(autoEnabled(pageShowsWallpaper = false, opaque = true))
    }

    @Test
    fun opaqueTitleBarStaysSolidUnderAutoOptIn() {
        assertFalse(autoEnabled(opaque = true))
    }

    @Test
    fun nonTransparentNavBarStaysSolidUnderAutoOptIn() {
        assertFalse(autoEnabled(transparentNavBar = false))
    }

    @Test
    fun eInkStaysSolidUnderAutoOptIn() {
        assertFalse(autoEnabled(isEInk = true))
    }

    @Test
    fun noUsableBgImageStaysSolidUnderAutoOptIn() {
        assertFalse(autoEnabled(hasUsableBgImage = false))
    }

    @Test
    fun autoOptInAgreesWithManualOptInWhenLayoutAllows() {
        // 布局允许且页面露壁纸时，自动入口与手动入口必须同结论（逐值一致），
        // 否则「手写过 applyWallpaperGlass 的页面」和「靠自动 opt-in 的页面」会不一致
        for (transparentNavBar in booleanValues()) {
            for (isEInk in booleanValues()) {
                for (hasUsableBgImage in booleanValues()) {
                    for (opaque in booleanValues()) {
                        assertTrue(
                            "auto 必须不弱于 manual: opaque=$opaque nav=$transparentNavBar " +
                                "eink=$isEInk bg=$hasUsableBgImage",
                            !titleBarAutoWallpaperGlassEnabled(
                                layoutEnables = true,
                                pageShowsWallpaper = true,
                                opaque = opaque,
                                transparentNavBar = transparentNavBar,
                                isEInk = isEInk,
                                hasUsableBgImage = hasUsableBgImage
                            ) || titleBarWantsWallpaperGlass(
                                opaque = opaque,
                                transparentNavBar = transparentNavBar,
                                isEInk = isEInk,
                                hasUsableBgImage = hasUsableBgImage
                            )
                        )
                    }
                }
            }
        }
    }

    private fun autoEnabled(
        layoutEnables: Boolean = true,
        pageShowsWallpaper: Boolean = true,
        opaque: Boolean = false,
        transparentNavBar: Boolean = true,
        isEInk: Boolean = false,
        hasUsableBgImage: Boolean = true
    ): Boolean = titleBarAutoWallpaperGlassEnabled(
        layoutEnables = layoutEnables,
        pageShowsWallpaper = pageShowsWallpaper,
        opaque = opaque,
        transparentNavBar = transparentNavBar,
        isEInk = isEInk,
        hasUsableBgImage = hasUsableBgImage
    )

    private fun booleanValues(): List<Boolean> = listOf(true, false)
}
