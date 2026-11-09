package io.legado.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全局玻璃表面口径的纯 JVM 单测：[GlassMaterialPreset.scaled]。
 *
 * 这是「同参数 = 同观感」的根契约：底栏/顶栏不经过缩放假定为 100，发现页把四个
 * 倍率滑杆乘进来。若 100% 不是恒等、或缩放误伤了色散/折射几何，同一档参数在
 * 不同宿主上就会悄悄分叉——编译与冒烟测试都不会报错，只有真机能看出来。
 */
class GlassSurfaceScaleTest {

    private val glass = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)
    private val frosted = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)

    /** 100% 必须是恒等：底栏/顶栏不显式传滑杆时取到的就是预设原值。 */
    @Test
    fun defaultPercentIsIdentity() {
        listOf(glass, frosted).forEach { material ->
            val scaled = GlassMaterialPreset.scaled(material)
            assertEquals(material.blurDp, scaled.blurDp, 0.000001f)
            assertEquals(material.tintAlpha, scaled.tintAlpha, 0.000001f)
            assertEquals(material.dispersion, scaled.dispersion, 0.000001f)
            assertEquals(material.refractionHeightDp, scaled.refractionHeightDp, 0.000001f)
            assertEquals(material.refractionOffsetDp, scaled.refractionOffsetDp, 0.000001f)
            assertEquals(material.shell.start, scaled.shell.start, 0.000001f)
            assertEquals(material.shell.center, scaled.shell.center, 0.000001f)
            assertEquals(material.shell.end, scaled.shell.end, 0.000001f)
            assertEquals(material.shell.stroke, scaled.shell.stroke, 0.000001f)
            assertEquals(material.mode, scaled.mode)
            assertEquals(material.glassLevel, scaled.glassLevel, 0.000001f)
        }
    }

    /** 每个滑杆只缩放自己的维度；色散与折射几何永远取预设原值。 */
    @Test
    fun eachPercentScalesOnlyItsOwnDimension() {
        val scaled = GlassMaterialPreset.scaled(
            glass,
            blurPercent = 50,
            tintPercent = 200,
            shellPercent = 150,
            strokePercent = 25
        )
        assertEquals(glass.blurDp * 0.5f, scaled.blurDp, 0.000001f)
        assertEquals(glass.tintAlpha * 2f, scaled.tintAlpha, 0.000001f)
        assertEquals(glass.shell.start * 1.5f, scaled.shell.start, 0.000001f)
        assertEquals(glass.shell.center * 1.5f, scaled.shell.center, 0.000001f)
        assertEquals(glass.shell.end * 1.5f, scaled.shell.end, 0.000001f)
        assertEquals(glass.shell.stroke * 0.25f, scaled.shell.stroke, 0.000001f)
        // 材质身份量不参与滑杆
        assertEquals(glass.dispersion, scaled.dispersion, 0.000001f)
        assertEquals(glass.refractionHeightDp, scaled.refractionHeightDp, 0.000001f)
        assertEquals(glass.refractionOffsetDp, scaled.refractionOffsetDp, 0.000001f)
    }

    /** 滑杆 0 关闭该维度（不会出现负数）。 */
    @Test
    fun zeroPercentDisablesDimension() {
        val scaled = GlassMaterialPreset.scaled(
            glass,
            blurPercent = 0,
            tintPercent = 0,
            shellPercent = 0,
            strokePercent = 0
        )
        assertEquals(0f, scaled.blurDp, 0.000001f)
        assertEquals(0f, scaled.tintAlpha, 0.000001f)
        assertEquals(0f, scaled.shell.start, 0.000001f)
        assertEquals(0f, scaled.shell.center, 0.000001f)
        assertEquals(0f, scaled.shell.end, 0.000001f)
        assertEquals(0f, scaled.shell.stroke, 0.000001f)
    }

    /** 越界滑杆先钳到 [0, 200]，备份恢复写入脏值时不会产生负 alpha 或无限放大。 */
    @Test
    fun outOfRangePercentIsClamped() {
        val scaled = GlassMaterialPreset.scaled(
            glass,
            blurPercent = -50,
            tintPercent = 999,
            shellPercent = 999,
            strokePercent = -1
        )
        assertEquals(0f, scaled.blurDp, 0.000001f)
        assertEquals(glass.tintAlpha * 2f, scaled.tintAlpha, 0.000001f)
        assertEquals(glass.shell.start * 2f, scaled.shell.start, 0.000001f)
        assertEquals(0f, scaled.shell.stroke, 0.000001f)
    }

    /** scalePercent 本身：100 恒等、0 归零、越界钳制。 */
    @Test
    fun scalePercentContract() {
        assertEquals(1.6f, GlassMaterialPreset.scalePercent(1.6f, 100), 0.000001f)
        assertEquals(0f, GlassMaterialPreset.scalePercent(1.6f, 0), 0.000001f)
        assertEquals(3.2f, GlassMaterialPreset.scalePercent(1.6f, 200), 0.000001f)
        assertEquals(3.2f, GlassMaterialPreset.scalePercent(1.6f, 500), 0.000001f)
        assertEquals(0f, GlassMaterialPreset.scalePercent(1.6f, -100), 0.000001f)
    }
}
