package io.legado.app.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样域判定表的纯 JVM 单测（[glassBackdropSource] / [glassSurfaceDrawableAllowed]）。
 *
 * 这张表是「防止错误复用」的架构约束本身：面板域若被允许走壁纸采样，玻璃会画出
 * 被面板挡住的壁纸（观感断裂），而这种错误编译不报、冒烟不报，只有真机能看出来。
 */
class GlassSamplingDomainTest {

    /** 壁纸域：唯一允许 drawable 入口出图的采样域。 */
    @Test
    fun wallpaperDomainUsesWallpaperBitmap() {
        assertEquals(
            GlassBackdropSource.WALLPAPER_BITMAP,
            glassBackdropSource(GlassSamplingDomain.WALLPAPER)
        )
        assertTrue(glassSurfaceDrawableAllowed(GlassSamplingDomain.WALLPAPER))
    }

    /** 面板域：纸面采样必然错位，只能保留宿主原有的不透明底。 */
    @Test
    fun panelDomainNeverSamplesWallpaper() {
        assertEquals(
            GlassBackdropSource.OPAQUE_SHELL,
            glassBackdropSource(GlassSamplingDomain.PANEL)
        )
        assertFalse(glassSurfaceDrawableAllowed(GlassSamplingDomain.PANEL))
    }

    /** 内容域：壁纸不可见，必须改用兄弟子树采样；drawable 入口不得出图。 */
    @Test
    fun contentDomainRequiresSiblingSubtreeSampling() {
        assertEquals(
            GlassBackdropSource.SIBLING_SUBTREE,
            glassBackdropSource(GlassSamplingDomain.CONTENT)
        )
        assertFalse(glassSurfaceDrawableAllowed(GlassSamplingDomain.CONTENT))
    }

    /** 每个采样域都有确定来源，且只有壁纸域能出 drawable（锁死全表，新增枚举值必须显式过表）。 */
    @Test
    fun onlyWallpaperDomainIsDrawable() {
        val allowed = GlassSamplingDomain.entries.filter(::glassSurfaceDrawableAllowed)
        assertEquals(listOf(GlassSamplingDomain.WALLPAPER), allowed)
    }
}
