package io.legado.app.ui.widget

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 顶栏毛玻璃参数换算纯函数的确定性单测。
 *
 * 顶栏接入「全局毛玻璃效果」预设后，glass/frosted 档的注入口径必须与
 * [GlassMaterialPreset] 逐值 1:1（不重定义任何公式），solid 档必须不激活玻璃层
 * （返回 null）。口径错（漏乘 density、误乘等效系数、tint/dispersion 被二次缩放）
 * 不会导致编译失败，只会让顶栏观感悄悄偏离底栏与发现页，故用确定性断言锁住。
 */
class TopBarGlassSpecTest {

    /** solid 档不激活玻璃层：返回 null，由顶栏实底分支兜底。 */
    @Test
    fun solidModeReturnsNull() {
        val params = GlassMaterialPreset.params(GlassMaterialPreset.SOLID, 0.3f)
        assertNull(topBarGlassSpec(params, density = 3f))
    }

    /** glass 档五参数与预设 1:1：dp 值乘密度，无量纲值直传。 */
    @Test
    fun glassParamsMapOneToOneWithDensityConversion() {
        val density = 2.5f
        val params = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)
        val spec = topBarGlassSpec(params, density)!!
        assertEquals(params.blurDp * density, spec.blurPx, 0.0001f)
        assertEquals(params.tintAlpha, spec.tintAlpha, 0.000001f)
        assertEquals(params.dispersion, spec.dispersion, 0.000001f)
        assertEquals(params.refractionHeightDp * density, spec.refractionHeightPx, 0.0001f)
        assertEquals(params.refractionOffsetDp * density, spec.refractionOffsetPx, 0.0001f)
        // 具体数值锁口径（与 ExploreGlassHardwareBlurTest 同一预设公式）：
        // blur 8 + 0.34*10 = 11.4dp、折射高 22 + 0.34*12 = 26.08dp、折射偏移 42 + 0.34*24 = 50.16dp
        assertEquals(11.4f * density, spec.blurPx, 0.001f)
        assertEquals(0.037f, spec.tintAlpha, 0.001f)
        assertEquals(0.2816f, spec.dispersion, 0.001f)
        assertEquals(26.08f * density, spec.refractionHeightPx, 0.001f)
        assertEquals(50.16f * density, spec.refractionOffsetPx, 0.001f)
    }

    /** frosted 档同样 1:1，且两档差异只来自预设自身。 */
    @Test
    fun frostedParamsMapOneToOneAndStayDistinctFromGlass() {
        val density = 3f
        val params = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)
        val spec = topBarGlassSpec(params, density)!!
        assertEquals(params.blurDp * density, spec.blurPx, 0.0001f)
        assertEquals(params.tintAlpha, spec.tintAlpha, 0.000001f)
        assertEquals(params.dispersion, spec.dispersion, 0.000001f)
        assertEquals(params.refractionHeightDp * density, spec.refractionHeightPx, 0.0001f)
        assertEquals(params.refractionOffsetDp * density, spec.refractionOffsetPx, 0.0001f)
        // 具体数值锁口径：blur 12 + 0.35*18 = 18.3dp、tint 0.05 + 0.35*0.10 = 0.085
        assertEquals(18.3f * density, spec.blurPx, 0.001f)
        assertEquals(0.085f, spec.tintAlpha, 0.001f)
        assertEquals(0.142f, spec.dispersion, 0.001f)

        val glass = topBarGlassSpec(
            GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.35f), density
        )!!
        // 两档差异只来自预设自身（tint/blur/dispersion/折射各档独立），换算层不做档位缩放
        org.junit.Assert.assertTrue(spec.tintAlpha > glass.tintAlpha)
        org.junit.Assert.assertTrue(spec.blurPx > glass.blurPx)
        org.junit.Assert.assertTrue(spec.dispersion < glass.dispersion)
        org.junit.Assert.assertTrue(spec.refractionHeightPx < glass.refractionHeightPx)
    }

    /** 密度换算必须线性且不过度限幅：density=1 时像素值等于 dp 值。 */
    @Test
    fun unitDensityKeepsPixelValuesEqualToDp() {
        val params = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.2f)
        val spec = topBarGlassSpec(params, 1f)!!
        assertEquals(params.blurDp, spec.blurPx, 0.0001f)
        assertEquals(params.refractionHeightDp, spec.refractionHeightPx, 0.0001f)
        assertEquals(params.refractionOffsetDp, spec.refractionOffsetPx, 0.0001f)
    }
}
