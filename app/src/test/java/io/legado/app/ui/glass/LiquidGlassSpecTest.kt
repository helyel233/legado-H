package io.legado.app.ui.glass

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 液态玻璃参数换算与内容域启用判定的确定性单测。
 *
 * 顶栏与阅读页菜单共用 [liquidGlassSpec]：glass/frosted 档必须与
 * [GlassMaterialPreset] 逐值 1:1（不重定义任何公式），solid 档必须返回 null
 * （宿主保持实底）。口径错（漏乘 density、tint/dispersion 被二次缩放）不会
 * 导致编译失败，只会让观感悄悄偏离底栏，故用确定性断言锁住。
 */
class LiquidGlassSpecTest {

    /** solid 档不激活玻璃层：返回 null，由宿主实底表面兜底。 */
    @Test
    fun solidModeReturnsNull() {
        val params = GlassMaterialPreset.params(GlassMaterialPreset.SOLID, 0.3f)
        assertNull(liquidGlassSpec(params, density = 3f))
    }

    /** glass 档五参数与预设 1:1：dp 值乘密度，无量纲值直传。 */
    @Test
    fun glassParamsMapOneToOneWithDensityConversion() {
        val density = 2.5f
        val params = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)
        val spec = liquidGlassSpec(params, density)!!
        assertEquals(params.blurDp * density, spec.blurPx, 0.0001f)
        assertEquals(params.tintAlpha, spec.tintAlpha, 0.000001f)
        assertEquals(params.dispersion, spec.dispersion, 0.000001f)
        assertEquals(params.refractionHeightDp * density, spec.refractionHeightPx, 0.0001f)
        assertEquals(params.refractionOffsetDp * density, spec.refractionOffsetPx, 0.0001f)
        // 具体数值锁口径：blur 3 + 0.34*6 = 5.04dp、折射高 24 + 0.34*12 = 28.08dp、
        // 折射偏移 46 + 0.34*26 = 54.84dp
        assertEquals(5.04f * density, spec.blurPx, 0.001f)
        assertEquals(0.0108f, spec.tintAlpha, 0.001f)
        assertEquals(0.3216f, spec.dispersion, 0.001f)
        assertEquals(28.08f * density, spec.refractionHeightPx, 0.001f)
        assertEquals(54.84f * density, spec.refractionOffsetPx, 0.001f)
    }

    /** frosted 档同样 1:1，且两档差异只来自预设自身。 */
    @Test
    fun frostedParamsMapOneToOneAndStayDistinctFromGlass() {
        val density = 3f
        val params = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)
        val spec = liquidGlassSpec(params, density)!!
        assertEquals(params.blurDp * density, spec.blurPx, 0.0001f)
        assertEquals(params.tintAlpha, spec.tintAlpha, 0.000001f)
        assertEquals(params.dispersion, spec.dispersion, 0.000001f)
        assertEquals(params.refractionHeightDp * density, spec.refractionHeightPx, 0.0001f)
        assertEquals(params.refractionOffsetDp * density, spec.refractionOffsetPx, 0.0001f)
        // 具体数值锁口径：blur 20 + 0.35*24 = 28.4dp、tint 0.18 + 0.35*0.20 = 0.25
        assertEquals(28.4f * density, spec.blurPx, 0.001f)
        assertEquals(0.25f, spec.tintAlpha, 0.001f)
        assertEquals(0.068f, spec.dispersion, 0.001f)

        val glass = liquidGlassSpec(
            GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.35f), density
        )!!
        // 两档差异只来自预设自身（tint/blur/dispersion/折射各档独立），换算层不做档位缩放
        assertTrue(spec.tintAlpha > glass.tintAlpha)
        assertTrue(spec.blurPx > glass.blurPx)
        assertTrue(spec.dispersion < glass.dispersion)
        assertTrue(spec.refractionHeightPx < glass.refractionHeightPx)
    }

    /** 密度换算必须线性且不过度限幅：density=1 时像素值等于 dp 值。 */
    @Test
    fun unitDensityKeepsPixelValuesEqualToDp() {
        val params = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.2f)
        val spec = liquidGlassSpec(params, 1f)!!
        assertEquals(params.blurDp, spec.blurPx, 0.0001f)
        assertEquals(params.refractionHeightDp, spec.refractionHeightPx, 0.0001f)
        assertEquals(params.refractionOffsetDp, spec.refractionOffsetPx, 0.0001f)
    }

    // region 内容域启用判定

    private val glassParams = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)

    private fun contentSpec(
        params: GlassMaterialPreset.Params = glassParams,
        sdkInt: Int = LIQUID_GLASS_MIN_SDK,
        sourceAvailable: Boolean = true,
        isLowRam: Boolean = false,
        isEInk: Boolean = false
    ) = contentLiquidGlassSpec(
        params = params,
        density = 2f,
        sdkInt = sdkInt,
        sourceAvailable = sourceAvailable,
        isLowRam = isLowRam,
        isEInk = isEInk
    )

    /** 健康设备 + 合法采样源 + 玻璃档：激活，且参数与共享换算逐值一致。 */
    @Test
    fun contentDomainActivatesOnHealthyDevice() {
        val spec = contentSpec()
        assertNotNull(spec)
        assertEquals(liquidGlassSpec(glassParams, 2f), spec)
    }

    /** 没有可采样的兄弟子树：不创建玻璃层，宿主保持原表面。 */
    @Test
    fun contentDomainRequiresSamplingSource() {
        assertNull(contentSpec(sourceAvailable = false))
    }

    /** API < 33 没有 RuntimeShader：该入口不可用（不是设备降级）。 */
    @Test
    fun contentDomainRejectsLowSdk() {
        assertNull(contentSpec(sdkInt = LIQUID_GLASS_MIN_SDK - 1))
        assertNotNull(contentSpec(sdkInt = LIQUID_GLASS_MIN_SDK))
    }

    /** 每帧整树录制对低内存 / 墨水屏是阅读性能红线：保持静态表面。 */
    @Test
    fun contentDomainRejectsLowRamAndEInk() {
        assertNull(contentSpec(isLowRam = true))
        assertNull(contentSpec(isEInk = true))
    }

    /** solid 档：即使设备与采样源都合格也不激活。 */
    @Test
    fun contentDomainKeepsSolidModeOff() {
        val solid = GlassMaterialPreset.params(GlassMaterialPreset.SOLID, 0.34f)
        assertNull(contentSpec(params = solid))
    }

    // endregion

    // region 玻璃矩形几何

    /** 底边向下延伸一个圆角：下方两角被父容器裁掉，只保留面板上方圆角。 */
    @Test
    fun panelRectExtendsBottomByCornerRadius() {
        val rect = glassPanelRect(
            panelLeft = 0f,
            panelTop = 1800f,
            panelWidth = 1080f,
            panelHeight = 300f,
            cornerRadiusPx = 28f
        )!!
        assertEquals(0, rect.left)
        assertEquals(1800, rect.top)
        assertEquals(1080, rect.right)
        assertEquals(2128, rect.bottom)
    }

    /** 小数坐标向外取整，避免玻璃比面板小一条像素缝。 */
    @Test
    fun panelRectRoundsOutwards() {
        val rect = glassPanelRect(
            panelLeft = 10.4f,
            panelTop = 200.6f,
            panelWidth = 100.2f,
            panelHeight = 50.3f,
            cornerRadiusPx = 0f
        )!!
        assertEquals(10, rect.left)
        assertEquals(200, rect.top)
        assertEquals(111, rect.right) // ceil(110.6)
        assertEquals(251, rect.bottom) // ceil(250.9)
    }

    /** 面板未测量完成 / 已隐藏：返回 null，调用方跳过定位。 */
    @Test
    fun panelRectRequiresMeasuredPanel() {
        assertNull(glassPanelRect(0f, 0f, 0f, 100f, 10f))
        assertNull(glassPanelRect(0f, 0f, 100f, 0f, 10f))
        assertNull(glassPanelRect(0f, 0f, -1f, 100f, 10f))
    }

    /** 负圆角按 0 处理，不缩小玻璃高度。 */
    @Test
    fun panelRectClampsNegativeRadius() {
        val rect = glassPanelRect(0f, 0f, 100f, 100f, -20f)!!
        assertEquals(100, rect.bottom)
    }

    /**
     * 阅读页顶部菜单是贴边直角条：半径传 0 时玻璃矩形与面板逐值重合，
     * 不做底部菜单那种「底边下推一个圆角」的延伸（那是为了藏住屏幕底边的两个圆角）。
     */
    @Test
    fun topBarGlassRectHasNoBottomExtension() {
        val rect = glassPanelRect(0f, 0f, 1080f, 176f, 0f)!!
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1080, rect.right)
        assertEquals(176, rect.bottom)
    }

    // endregion

    // region 面板让出底色判定

    /** 玻璃激活且已完成定位：面板让出底色，透出玻璃层。 */
    @Test
    fun fillYieldedOnlyWhenActiveAndGeometried() {
        assertTrue(glassPanelFillYielded(active = true, hasGeometry = true))
    }

    /**
     * 回归锁：玻璃已激活但还没拿到面板矩形时必须保持实底。
     * 此时让出底色会得到一块透明空面板——真机表现为阅读页底部菜单背景整体消失。
     */
    @Test
    fun fillNotYieldedBeforeGeometry() {
        assertEquals(false, glassPanelFillYielded(active = true, hasGeometry = false))
    }

    /** 玻璃未激活（solid 档 / 设备降级 / 无采样源）：保持实底。 */
    @Test
    fun fillNotYieldedWhenInactive() {
        assertEquals(false, glassPanelFillYielded(active = false, hasGeometry = true))
        assertEquals(false, glassPanelFillYielded(active = false, hasGeometry = false))
    }

    // endregion

    // region 侧边面板让出底色判定

    /** 玻璃激活且玻璃层已测量：面板让出底色。 */
    @Test
    fun sidebarFillYieldedAfterGlassMeasured() {
        assertTrue(sidebarGlassFillYielded(active = true, glassWidth = 720, glassHeight = 2400))
    }

    /**
     * 回归锁：玻璃层刚由 GONE 转 VISIBLE 时还没测量，此时必须保持实底，
     * 否则会露出一帧透明空面板（与阅读页底部菜单同一个失败形态）。
     */
    @Test
    fun sidebarFillNotYieldedBeforeGlassMeasured() {
        assertEquals(false, sidebarGlassFillYielded(active = true, glassWidth = 0, glassHeight = 0))
        assertEquals(false, sidebarGlassFillYielded(active = true, glassWidth = 720, glassHeight = 0))
    }

    /** 玻璃未激活（solid 档 / 设备降级 / 自定义背景图）：保持实底。 */
    @Test
    fun sidebarFillNotYieldedWhenInactive() {
        assertEquals(
            false,
            sidebarGlassFillYielded(active = false, glassWidth = 720, glassHeight = 2400)
        )
    }

    // endregion

    // region 侧边导航采样源资格

    /** 无自定义侧边背景图 + 有采样源：允许接玻璃。 */
    @Test
    fun sidebarSourceAllowedWithoutCustomBackground() {
        assertTrue(sidebarGlassSourceAllowed(hasCustomBackground = false, sourceAvailable = true))
    }

    /** 面板配了自定义侧边背景图：不接玻璃——那张图就是面板最终外观。 */
    @Test
    fun sidebarSourceRejectedWithCustomBackground() {
        assertEquals(
            false,
            sidebarGlassSourceAllowed(hasCustomBackground = true, sourceAvailable = true)
        )
    }

    /** 没有可用采样源：无论是否有自定义背景图都不接。 */
    @Test
    fun sidebarSourceRejectedWithoutSource() {
        assertEquals(
            false,
            sidebarGlassSourceAllowed(hasCustomBackground = false, sourceAvailable = false)
        )
        assertEquals(
            false,
            sidebarGlassSourceAllowed(hasCustomBackground = true, sourceAvailable = false)
        )
    }

    // endregion

    // region 覆盖式弹层让出底色判定

    /** 玻璃可用且面板已测量：弹层让出底色，露出背后玻璃层。 */
    @Test
    fun overlayFillYieldedAfterPanelMeasured() {
        assertTrue(
            overlayGlassFillYielded(glassAvailable = true, panelWidthPx = 520, panelHeightPx = 860)
        )
    }

    /**
     * 回归锁：玻璃层是面板测量回填后才挂上的，首帧尺寸为 0 时必须保持实底。
     * 这里让出底色就是弹出「透明空面板」，与阅读页菜单背景消失同一失败形态。
     */
    @Test
    fun overlayFillNotYieldedBeforePanelMeasured() {
        assertEquals(
            false,
            overlayGlassFillYielded(glassAvailable = true, panelWidthPx = 0, panelHeightPx = 0)
        )
        assertEquals(
            false,
            overlayGlassFillYielded(glassAvailable = true, panelWidthPx = 520, panelHeightPx = 0)
        )
    }

    /** 玻璃不可用（solid 档 / API 低于 33 / 低内存 / 墨水屏 / 无采样源）：始终实底。 */
    @Test
    fun overlayFillNotYieldedWithoutGlass() {
        assertEquals(
            false,
            overlayGlassFillYielded(glassAvailable = false, panelWidthPx = 520, panelHeightPx = 860)
        )
    }

    // endregion
}
