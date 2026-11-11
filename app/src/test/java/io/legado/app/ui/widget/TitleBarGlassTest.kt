package io.legado.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏壁纸域玻璃启用判定的确定性单测。
 *
 * 四个条件缺一不可；任一不满足都必须保持原背景，否则会采到被顶栏自己
 * （或页面不透明根容器）盖住的壁纸。
 */
class TitleBarGlassTest {

    @Test
    fun allConditionsMetEnablesGlass() {
        assertTrue(
            titleBarWantsWallpaperGlass(
                opaque = false,
                transparentNavBar = true,
                isEInk = false,
                hasUsableBgImage = true
            )
        )
    }

    @Test
    fun opaqueTitleBarNeverUsesGlass() {
        assertFalse(
            titleBarWantsWallpaperGlass(
                opaque = true,
                transparentNavBar = true,
                isEInk = false,
                hasUsableBgImage = true
            )
        )
    }

    @Test
    fun nonTransparentNavBarKeepsOriginalBackground() {
        assertFalse(
            titleBarWantsWallpaperGlass(
                opaque = false,
                transparentNavBar = false,
                isEInk = false,
                hasUsableBgImage = true
            )
        )
    }

    @Test
    fun eInkKeepsOriginalBackground() {
        assertFalse(
            titleBarWantsWallpaperGlass(
                opaque = false,
                transparentNavBar = true,
                isEInk = true,
                hasUsableBgImage = true
            )
        )
    }

    @Test
    fun noUsableBgImageKeepsOriginalBackground() {
        assertFalse(
            titleBarWantsWallpaperGlass(
                opaque = false,
                transparentNavBar = true,
                isEInk = false,
                hasUsableBgImage = false
            )
        )
    }
}
