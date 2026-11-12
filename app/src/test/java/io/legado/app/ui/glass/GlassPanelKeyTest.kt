package io.legado.app.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GlassPanel` 组合期可用性判定的回归锁（QA 第二轮 P3）。
 *
 * 缺陷形态：`available` 的 remember key 与 `GlassPanelHost.bindKey` 都**不含**「壁纸此刻
 * 是否可用」，而 `GlassSurface.configSignature` 只含档位 / 强度 / 描边 / 夜间，不含背景图
 * 开关。于是「开背景图 + 玻璃 → 进设置关背景图 → 返回」时页根背景已正确变实底，但卡片
 * 停在玻璃上；反向开背景图则玻璃不出现。
 *
 * 这里锁两条纯逻辑（真实组合期无法在 JVM / Robolectric 下稳定跑 Compose，故把判定与
 * key 计算抽成顶层函数，生产代码直接调用同一份实现）：
 *
 * - [glassPanelAvailable]：壁纸门槛只在调用点声明「需要壁纸宿主」时才生效；
 * - [glassPanelBindKey]：壁纸可用性必须参与 key，否则宿主复用时会留着旧玻璃。
 *
 * 另外「壁纸可用性变化能触发重组」由 [rememberGlassAvailability] 的偏好监听承担
 * （Compose State），属真机 / 截图验证项，见测试报告。
 */
class GlassPanelKeyTest {

    @Test
    fun `bind key changes when wallpaper availability changes`() {
        val withWallpaper = glassPanelBindKey(
            signature = "liquid|1.0",
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = 16f,
            screenAligned = false,
            wallpaperGlassAllowed = true
        )
        val withoutWallpaper = glassPanelBindKey(
            signature = "liquid|1.0",
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = 16f,
            screenAligned = false,
            wallpaperGlassAllowed = false
        )

        assertNotEquals(
            "关背景图后签名不变、壁纸可用性变了，bind key 必须不同，否则宿主留在旧玻璃上",
            withWallpaper,
            withoutWallpaper
        )
    }

    @Test
    fun `bind key is stable for identical inputs`() {
        // 每次重组都会调 bind：同样的输入必须得到同一个 key，否则会白重建 drawable
        assertEquals(
            glassPanelBindKey("s", GlassSamplingDomain.WALLPAPER, 12f, true, true),
            glassPanelBindKey("s", GlassSamplingDomain.WALLPAPER, 12f, true, true)
        )
    }

    @Test
    fun `bind key still separates the appearance parameters`() {
        val base = glassPanelBindKey("s", GlassSamplingDomain.WALLPAPER, 12f, false, true)

        assertNotEquals(
            "签名（档位 / 强度 / 描边 / 夜间）变化必须重绑",
            base,
            glassPanelBindKey("s2", GlassSamplingDomain.WALLPAPER, 12f, false, true)
        )
        assertNotEquals(
            "采样域变化必须重绑",
            base,
            glassPanelBindKey("s", GlassSamplingDomain.PANEL, 12f, false, true)
        )
        assertNotEquals(
            "圆角变化必须重绑",
            base,
            glassPanelBindKey("s", GlassSamplingDomain.WALLPAPER, 8f, false, true)
        )
        assertNotEquals(
            "对话框的屏幕对齐变化必须重绑",
            base,
            glassPanelBindKey("s", GlassSamplingDomain.WALLPAPER, 12f, true, true)
        )
    }

    @Test
    fun `callers that do not require a wallpaper host never read the wallpaper flag`() {
        // 底栏 / 顶栏 / 对话框：只看采样域与设备。壁纸位即便传 false 也不能影响结论，
        // 否则无背景图的普通主题下这些位置会整块消失。
        assertTrue(
            glassPanelAvailable(
                requireWallpaperHost = false,
                pageWallpaperAllowed = false,
                domainAvailable = true
            )
        )
        assertFalse(
            glassPanelAvailable(
                requireWallpaperHost = false,
                pageWallpaperAllowed = true,
                domainAvailable = false
            )
        )
    }

    @Test
    fun `callers that require a wallpaper host follow the wallpaper flag only`() {
        assertTrue(
            glassPanelAvailable(
                requireWallpaperHost = true,
                pageWallpaperAllowed = true,
                domainAvailable = false
            )
        )
        assertFalse(
            "页面不露壁纸（关背景图 / solid 档 / 阅读页）时必须走 fallback",
            glassPanelAvailable(
                requireWallpaperHost = true,
                pageWallpaperAllowed = false,
                domainAvailable = true
            )
        )
    }

    @Test
    fun `wallpaper gated host and device gated host agree when both are allowed`() {
        assertTrue(
            glassPanelAvailable(
                requireWallpaperHost = true,
                pageWallpaperAllowed = true,
                domainAvailable = true
            )
        )
        assertTrue(
            glassPanelAvailable(
                requireWallpaperHost = false,
                pageWallpaperAllowed = true,
                domainAvailable = true
            )
        )
    }
}
