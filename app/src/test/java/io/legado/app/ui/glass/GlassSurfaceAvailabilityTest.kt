package io.legado.app.ui.glass

import androidx.compose.ui.graphics.Color
import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compose 桥组合期判定（[glassSurfaceAvailable]）与页面背景策略
 * （[glassPageBackgroundColor]）的纯 JVM 单测。
 *
 * 这两个函数决定「Compose 画玻璃层还是回退原背景」，必须与 View 侧
 * `ExploreGlassBackdrop.glassDrawable()` 的早退分支逐条同源：判错可用会让卡片
 * 先按玻璃布局、再拿到 null 背景，表现为整块透明；判错不可用则白白丢掉玻璃效果。
 */
class GlassSurfaceAvailabilityTest {

    /** 壁纸域 + 玻璃档 + 正常设备：可用。 */
    @Test
    fun wallpaperDomainOnHealthyDeviceIsAvailable() {
        assertTrue(
            glassSurfaceAvailable(
                domain = GlassSamplingDomain.WALLPAPER,
                mode = GlassMaterialPreset.GLASS,
                isEInk = false,
                isLowRam = false
            )
        )
        assertTrue(
            glassSurfaceAvailable(
                domain = GlassSamplingDomain.WALLPAPER,
                mode = GlassMaterialPreset.FROSTED,
                isEInk = false,
                isLowRam = false
            )
        )
    }

    /** 墨水屏 / 低内存：壁纸采样降级为 null，Compose 必须走回退背景。 */
    @Test
    fun wallpaperDomainDegradesOnWeakDevice() {
        assertFalse(
            glassSurfaceAvailable(
                domain = GlassSamplingDomain.WALLPAPER,
                mode = GlassMaterialPreset.GLASS,
                isEInk = true,
                isLowRam = false
            )
        )
        assertFalse(
            glassSurfaceAvailable(
                domain = GlassSamplingDomain.WALLPAPER,
                mode = GlassMaterialPreset.FROSTED,
                isEInk = false,
                isLowRam = true
            )
        )
    }

    /**
     * 无玻璃档是纯实底壳，没有模糊 / 位图采样开销，因此排在设备降级之前判定：
     * 墨水屏 + 无玻璃档仍然可用（与 `glassDrawable()` 的实际产物一致）。
     */
    @Test
    fun solidModeStaysAvailableOnWeakDevice() {
        assertTrue(
            glassSurfaceAvailable(
                domain = GlassSamplingDomain.WALLPAPER,
                mode = GlassMaterialPreset.SOLID,
                isEInk = true,
                isLowRam = true
            )
        )
    }

    /** 面板域 / 内容域：壁纸采样本身就是错的，任何设备、任何档位都不可用。 */
    @Test
    fun nonWallpaperDomainIsNeverAvailable() {
        for (mode in listOf(GlassMaterialPreset.SOLID, GlassMaterialPreset.GLASS)) {
            for (domain in listOf(GlassSamplingDomain.PANEL, GlassSamplingDomain.CONTENT)) {
                assertFalse(
                    "domain=$domain mode=$mode",
                    glassSurfaceAvailable(domain, mode, isEInk = false, isLowRam = false)
                )
            }
        }
    }

    /** 有背景图：页面根背景透明，壁纸可见，玻璃采样源真实。 */
    @Test
    fun pageBackgroundIsTransparentWhenWallpaperVisible() {
        val page = Color(0xFFF5F5F5)
        assertEquals(Color.Transparent, glassPageBackgroundColor(page, hasBgImage = true))
    }

    /** 无背景图：保留主题页色，未开背景图时的观感不变。 */
    @Test
    fun pageBackgroundKeepsThemeColorWithoutWallpaper() {
        val page = Color(0xFFF5F5F5)
        assertEquals(page, glassPageBackgroundColor(page, hasBgImage = false))
    }
}
