package io.legado.app.ui.glass

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃「壳层 + 折射边缘」规格的纯 JVM 真值表。
 *
 * 第五轮根因：前四轮只把面 alpha 让出来 / 只做软件模糊，用户看到「有透明、没有磨砂/折射」。
 * 纯色底上模糊像素与着色叠完近似纯色，唯一能给出玻璃证据的是边缘。这张表锁定
 * 「边缘强度确实来自档位参数」且「两档在边缘维度上也有可见差异」，防止后续再把
 * 折射层悄悄删掉后又回到「只有透明」。
 */
class GlassFrostSpecTest {

    @Test
    fun shellAlphasFollowTheSingleMaterialSource() {
        val params = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)
        val spec = glassFrostSpec(params)
        assertEquals(params.shell.start, spec.shellStartAlpha, 1e-6f)
        assertEquals(params.shell.center, spec.shellCenterAlpha, 1e-6f)
        assertEquals(params.shell.end, spec.shellEndAlpha, 1e-6f)
    }

    @Test
    fun shellGradientIsTopStrongerThanBottom() {
        val spec = glassFrostSpecFor(GlassMaterialPreset.FROSTED, 0.35f)
        assertTrue(spec.shellStartAlpha > spec.shellCenterAlpha)
        assertTrue(spec.shellCenterAlpha > spec.shellEndAlpha)
    }

    /** 透明玻璃档的折射边缘必须明显强于磨砂档，否则两档只剩深浅差。 */
    @Test
    fun edgeHighlightDistinguishesLiquidFromFrosted() {
        val liquid = glassFrostSpecFor(GlassMaterialPreset.GLASS, 0.34f)
        val frosted = glassFrostSpecFor(GlassMaterialPreset.FROSTED, 0.35f)
        assertTrue("透明玻璃 edge=${liquid.edgeAlpha} 应强于磨砂 ${frosted.edgeAlpha}",
            liquid.edgeAlpha > frosted.edgeAlpha)
        assertTrue("透明玻璃色散=${liquid.dispersionAlpha} 应强于磨砂 ${frosted.dispersionAlpha}",
            liquid.dispersionAlpha > frosted.dispersionAlpha)
    }

    /** 折射边缘在任何档位都不得为 0——0 就是「纯色面 + 一圈描边」(= 用户说的没玻璃)。 */
    @Test
    fun edgeHighlightNeverZero() {
        for (mode in listOf(GlassMaterialPreset.GLASS, GlassMaterialPreset.FROSTED)) {
            for (level in listOf(0f, 0.25f, 0.5f, 1f)) {
                val spec = glassFrostSpecFor(mode, level)
                assertTrue("$mode/$level edgeAlpha 必须 > 0", spec.edgeAlpha > 0f)
            }
        }
    }

    /** 滑杆把模糊关到 0 时，玻璃边缘仍在；与全 App「模糊=0 时玻璃变平」不冲突。 */
    @Test
    fun blurScaleZeroKeepsGlassShell() {
        val base = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)
        val zeroBlur = GlassMaterialPreset.scaled(base, blurPercent = 0)
        assertEquals(0f, zeroBlur.blurDp, 1e-6f)
        assertNotEquals(0f, glassFrostSpec(zeroBlur).edgeAlpha)
    }

    @Test
    fun allSpecValuesStayInUnitRange() {
        for (mode in listOf(GlassMaterialPreset.GLASS, GlassMaterialPreset.FROSTED)) {
            for (level in listOf(-1f, 0f, 0.34f, 1f, 2f)) {
                val spec = glassFrostSpecFor(mode, level)
                listOf(
                    spec.shellStartAlpha,
                    spec.shellCenterAlpha,
                    spec.shellEndAlpha,
                    spec.edgeAlpha,
                    spec.dispersionAlpha
                ).forEach { assertTrue("$mode/$level: $it 越界", it in 0f..1f) }
            }
        }
    }

    @Test
    fun surfaceRgbFollowsBaseLightness() {
        assertEquals(0xFFFFFF, glassFrostSurfaceRgb(lightBase = true))
        assertEquals(0x16181C, glassFrostSurfaceRgb(lightBase = false))
    }

    @Test
    fun edgeGeometryStaysVisibleForSmallButtons() {
        val density = 3f
        // 24dp 圆角的小按钮：内缩与线宽都至少 1dp，不能退化成 0 或点。
        val inset = glassFrostEdgeInsetPx(24f, density)
        val width = glassFrostEdgeWidthPx(24f, density)
        assertTrue(inset >= density)
        assertTrue(width >= density)
        assertTrue(width <= density * 1.5f)
    }
}
