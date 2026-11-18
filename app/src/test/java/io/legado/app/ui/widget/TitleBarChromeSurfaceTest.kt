package io.legado.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * B系 [TitleBar] 悬浮 chrome 表面决策的真值表回归网。
 *
 * 全部为纯 JVM 判定（[titleBarChromeSurface] / [titleBarChromeSurfaceSignature]），
 * 不需要 Material 宿主。锁定三条纪律：
 * 1. 「是否悬浮」只来自顶栏包 floatingChrome 布尔，禁止由半径>0 反推（R4）；
 * 2. 半径必须进复合签名——View 侧 ledger 不含 radius 的旧缺陷不许回归（红队既定项）；
 * 3. 非悬浮恒为 LEGACY，存量页面逐字节 dormant。
 */
class TitleBarChromeSurfaceTest {

    @Test
    fun `悬浮加玻璃判定通过 = 玻璃卡片`() {
        assertEquals(TitleBarChromeSurface.GLASS, titleBarChromeSurface(glassEnabled = true, floating = true))
    }

    @Test
    fun `悬浮但玻璃判定不过 = 包实底卡片`() {
        assertEquals(TitleBarChromeSurface.PACKAGE, titleBarChromeSurface(glassEnabled = false, floating = true))
    }

    @Test
    fun `非悬浮恒为 LEGACY 与玻璃判定无关`() {
        assertEquals(TitleBarChromeSurface.LEGACY, titleBarChromeSurface(glassEnabled = true, floating = false))
        assertEquals(TitleBarChromeSurface.LEGACY, titleBarChromeSurface(glassEnabled = false, floating = false))
    }

    @Test
    fun `签名包含半径 半径变化必须重铺`() {
        val base = titleBarChromeSurfaceSignature("pkg", "glass", 10f, floating = true)
        assertNotEquals(base, titleBarChromeSurfaceSignature("pkg", "glass", 30f, floating = true))
        assertNotEquals(base, titleBarChromeSurfaceSignature("pkg", "glass", 0f, floating = true))
    }

    @Test
    fun `签名包含悬浮态 悬浮翻转必须重铺`() {
        val base = titleBarChromeSurfaceSignature("pkg", "glass", 10f, floating = true)
        assertNotEquals(base, titleBarChromeSurfaceSignature("pkg", "glass", 10f, floating = false))
    }

    @Test
    fun `签名包含包身份与玻璃档位 改包或换档必须重铺`() {
        val base = titleBarChromeSurfaceSignature("pkg", "glass", 10f, floating = true)
        assertNotEquals(base, titleBarChromeSurfaceSignature("pkg2", "glass", 10f, floating = true))
        assertNotEquals(base, titleBarChromeSurfaceSignature("pkg", "glass2", 10f, floating = true))
    }

    @Test
    fun `同参数签名幂等 重复调用零重建`() {
        val a = titleBarChromeSurfaceSignature("pkg", "glass", 10f, floating = true)
        val b = titleBarChromeSurfaceSignature("pkg", "glass", 10f, floating = true)
        assertEquals(a, b)
    }
}
