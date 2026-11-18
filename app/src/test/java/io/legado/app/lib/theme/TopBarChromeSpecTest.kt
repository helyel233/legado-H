package io.legado.app.lib.theme

import io.legado.app.help.config.TopBarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TopBarChromeSpec] 纯 JVM 真值表测试：锁定「半径只来自 cornerScale、悬浮态只来自
 * floatingChrome、二者互不反推」两条 R4 铁律，以及与旧 cornerRadius 公式的等价性。
 *
 * 不依赖 Robolectric：所有像素参数（baseRadiusPx / topBarHeightPx）显式传入，
 * 因此测试可脱离 Android 运行时直接跑。
 */
class TopBarChromeSpecTest {

    companion object {
        private const val DELTA = 0.0001f
        private const val BASE = 10f
        private const val HEIGHT = 48f
    }

    /** 构造顶栏包配置；name/isNightMode 无默认值，必须显式传。 */
    private fun config(scale: Float?, floating: Boolean = false): TopBarConfig.Config =
        TopBarConfig.Config(
            name = "t",
            isNightMode = false,
            cornerScale = scale,
            floatingChrome = floating,
        )

    // ------------------------------------------------------- 半径真值表

    /** cornerScale 缺失 → resolveCornerScale 归一为 1 → 半径等于基础值。 */
    @Test
    fun nullScaleNormalizesToOne() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = null), HEIGHT)
        assertEquals(BASE, chrome.radiusPx, DELTA)
        assertFalse(chrome.floating)
    }

    /** cornerScale=0 是用户主动选择的直角档，半径必须为 0。 */
    @Test
    fun zeroScaleKeepsSquareCorner() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 0f), HEIGHT)
        assertEquals(0f, chrome.radiusPx, DELTA)
    }

    /** cornerScale=1 保持基础半径。 */
    @Test
    fun oneScaleKeepsBaseRadius() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 1f), HEIGHT)
        assertEquals(BASE, chrome.radiusPx, DELTA)
    }

    /** cornerScale=3、已知高度 48 → raw=30 被钳制到半高 24，避免变胶囊。 */
    @Test
    fun largeScaleClampedToHalfHeight() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 3f), HEIGHT)
        assertEquals(24f, chrome.radiusPx, DELTA)
    }

    /** 高度未知（<=0）时不钳制，raw=30 原样返回。 */
    @Test
    fun unknownHeightDoesNotClamp() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 3f), 0f)
        assertEquals(30f, chrome.radiusPx, DELTA)
        // 负高度同样视为未知
        val negativeHeight = TopBarChromeSpec.resolve(BASE, config(scale = 3f), -1f)
        assertEquals(30f, negativeHeight.radiusPx, DELTA)
    }

    /** cornerScale=5 越界 → coerce 到 3 → 配合高度 48 得 24。 */
    @Test
    fun scaleAboveMaxIsCoercedThenClamped() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 5f), HEIGHT)
        assertEquals(24f, chrome.radiusPx, DELTA)
    }

    /** cornerScale=-1 越界 → coerce 到 0 → 半径 0。 */
    @Test
    fun negativeScaleIsCoercedToZero() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = -1f), HEIGHT)
        assertEquals(0f, chrome.radiusPx, DELTA)
    }

    // ------------------------------------------------------- R4：悬浮态与半径互不反推

    /** 关键：悬浮开启 + 直角（scale=0）→ 半径 0 但 floating=true，禁止由半径反推悬浮态。 */
    @Test
    fun floatingIsIndependentOfZeroRadius() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 0f, floating = true), HEIGHT)
        assertEquals(0f, chrome.radiusPx, DELTA)
        assertTrue(chrome.floating)
    }

    /** 反向同样独立：大半径不代表悬浮，floating 始终取自字段本身。 */
    @Test
    fun nonFloatingIsIndependentOfLargeRadius() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 3f, floating = false), HEIGHT)
        assertEquals(24f, chrome.radiusPx, DELTA)
        assertFalse(chrome.floating)
    }

    /**
     * 存量包 JSON 缺字段时 floatingChrome 走默认 false：用全默认构造验证向后兼容
     * （不传 floatingChrome 即等价于缺字段场景，视觉零变化）。
     */
    @Test
    fun legacyConfigDefaultsToNonFloating() {
        val legacy = TopBarConfig.Config(name = "t", isNightMode = false, cornerScale = 1f)
        assertFalse(legacy.floatingChrome)
        val chrome = TopBarChromeSpec.resolve(BASE, legacy, HEIGHT)
        assertFalse(chrome.floating)
    }

    // ------------------------------------------------------- 悬浮卡片内边距（由半径纯推导，不新增 dimens）

    /** floating=true、scale=1、半径 10 → 左右/底部 0.6×=6，顶部 0.5×=5。 */
    @Test
    fun floatingInsetsDerivedFromRadius() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 1f, floating = true), HEIGHT)
        assertEquals(10f, chrome.radiusPx, DELTA)
        assertEquals(6, chrome.hInsetPx)
        assertEquals(5, chrome.topInsetPx)
        assertEquals(6, chrome.bottomInsetPx)
    }

    /** floating=false 时三个 inset 必须全 0，存量 padding 逐字节不变（回滚安全底线）。 */
    @Test
    fun nonFloatingInsetsAreAllZero() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 3f, floating = false), HEIGHT)
        assertEquals(0, chrome.hInsetPx)
        assertEquals(0, chrome.topInsetPx)
        assertEquals(0, chrome.bottomInsetPx)
    }

    /** floating=true 但半径 0（直角档）→ 无内边距，禁止把悬浮态与圆角互相反推。 */
    @Test
    fun floatingWithZeroRadiusHasZeroInsets() {
        val chrome = TopBarChromeSpec.resolve(BASE, config(scale = 0f, floating = true), HEIGHT)
        assertTrue(chrome.floating)
        assertEquals(0f, chrome.radiusPx, DELTA)
        assertEquals(0, chrome.hInsetPx)
        assertEquals(0, chrome.topInsetPx)
        assertEquals(0, chrome.bottomInsetPx)
    }

    // ------------------------------------------------------- 与旧 cornerRadius 公式等价

    /** 抽出的纯 helper 必须与旧 `base * scale.coerceIn(0f,3f)` 逐档等价。 */
    @Test
    fun resolveCornerRadiusPxMatchesLegacyFormula() {
        assertEquals(0f, TopBarConfig.resolveCornerRadiusPx(BASE, config(scale = 0f)), DELTA)
        assertEquals(10f, TopBarConfig.resolveCornerRadiusPx(BASE, config(scale = 1f)), DELTA)
        assertEquals(30f, TopBarConfig.resolveCornerRadiusPx(BASE, config(scale = 3f)), DELTA)
        assertEquals(10f, TopBarConfig.resolveCornerRadiusPx(BASE, config(scale = null)), DELTA)
        // 越界档同样由 helper 内部钳制，不依赖 normalizeConfig 预先处理
        assertEquals(30f, TopBarConfig.resolveCornerRadiusPx(BASE, config(scale = 5f)), DELTA)
        assertEquals(0f, TopBarConfig.resolveCornerRadiusPx(BASE, config(scale = -1f)), DELTA)
    }
}
