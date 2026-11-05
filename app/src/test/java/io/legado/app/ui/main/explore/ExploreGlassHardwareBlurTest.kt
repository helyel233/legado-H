package io.legado.app.ui.main.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬件管线高斯半径的口径单测。
 *
 * 半径是本次治本的核心参数：口径错（误乘软件管线的 1.4 等效系数、漏乘 density、
 * 限幅失效）都不会导致编译失败，只会让观感或低端 GPU 开销悄悄偏离，故抽成纯函数后
 * 用确定性断言锁住，不依赖真机与运行时推断。
 */
class ExploreGlassHardwareBlurTest {

    @Test
    fun frostedBlurFollowsDensityLinearly() {
        // glassLevel=0（关闭档）→ blurDp=12；density=2 → 24px
        assertEquals(24f, exploreHardwareBlurPx(0f, 2f), 0.001f)
        // glassLevel=0.5（设置滑杆 100 档）→ blurDp=12+9=21；density=2 → 42px
        assertEquals(42f, exploreHardwareBlurPx(0.5f, 2f), 0.001f)
        // glassLevel=0.3（默认 60 档）→ blurDp=17.4；density=3 → 52.2px
        assertEquals(52.2f, exploreHardwareBlurPx(0.3f, 3f), 0.001f)
    }

    @Test
    fun doesNotApplySoftwareStackBlurFactor() {
        // 若误乘软件管线的 1.4 等效系数，12dp@3x 会变成 50.4px 而非 36px
        assertEquals(36f, exploreHardwareBlurPx(0f, 3f), 0.001f)
        assertEquals(63f, exploreHardwareBlurPx(0.5f, 3f), 0.001f)
    }

    @Test
    fun radiusClampsToSupportedRange() {
        // density 为 0 / 强度为负 → 仍守住下限 1px，不把 0 半径交给 RenderEffect
        assertEquals(1f, exploreHardwareBlurPx(0f, 0f), 0.001f)
        assertEquals(1f, exploreHardwareBlurPx(-1f, 0.01f), 0.001f)
        // 超大密度 / 超量程强度 → 仍守住上限 80px，避免低端 GPU 开销陡增
        assertEquals(80f, exploreHardwareBlurPx(0.5f, 100f), 0.001f)
        assertEquals(80f, exploreHardwareBlurPx(10f, 3f), 0.001f)
    }

    @Test
    fun radiusIsMonotonicInGlassLevel() {
        var previous = 0f
        var level = 0f
        while (level <= 0.5f) {
            val current = exploreHardwareBlurPx(level, 2f)
            assertTrue(current >= previous)
            previous = current
            level += 0.05f
        }
    }
}
