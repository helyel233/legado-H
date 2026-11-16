package io.legado.app.ui.book.read.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读设置面板的磨砂准入真值表（[readerPanelFrostedAllowed]）。
 *
 * 第四轮根因：前几轮在「该不该磨砂」上叠了 `ThemeConfig.hasUsableBgImage`（要求用户配置过
 * App 背景图偏好且文件可读）。但本面板磨的是**当前正文**，与背景图无关；绝大多数用户没有
 * 那个偏好 → 判定恒否 → 面板在第一步就回退实底，加在后面的磨砂 / 描边永远走不到。
 * 本表锁定修复后的判定：只看玻璃档（`wallpaperGlassEnabled` 折叠成的 [glassEnabled]）。
 */
class ReaderPanelGlassTest {

    /** 玻璃档开：铺正文磨砂，即使（尤其）用户从未配置背景图偏好。 */
    @Test
    fun frostedWhenGlassEnabled() {
        assertTrue(readerPanelFrostedAllowed(glassEnabled = true))
    }

    /** solid 档 / 墨水屏 / 低内存：逐值回退实底，保持既有降级矩阵。 */
    @Test
    fun opaqueWhenGlassDisabled() {
        assertFalse(readerPanelFrostedAllowed(glassEnabled = false))
    }
}
