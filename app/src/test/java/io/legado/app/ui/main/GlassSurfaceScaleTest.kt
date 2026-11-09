package io.legado.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

/**
 * 三档预设的固定滑杆坐标（[GlassMaterialPreset.presetPoint] / `matchesPreset`）。
 *
 * 契约：选一档 = 把整组数写回偏好；任一滑杆偏离即「自定义」。
 * 这些判定错都不会编译失败，只会让设置页把自定义状态继续显示成预设名，
 * 或者把「切档残留的另一个强度键」误判成自定义。
 */
class GlassPresetPointTest {

    private val presetScales = GlassMaterialPreset.Scales()

    /** 磨砂档读磨砂强度键，其余档读液态强度键——坐标必须与该口径一致。 */
    @Test
    fun presetPointUsesTheSameLevelKeyAsRendering() {
        val frosted = GlassMaterialPreset.presetPoint(GlassMaterialPreset.FROSTED)
        assertEquals(GlassMaterialPreset.DEFAULT_FROSTED_LEVEL, frosted.level)
        assertEquals(70, frosted.level)

        listOf(GlassMaterialPreset.GLASS, GlassMaterialPreset.SOLID).forEach { mode ->
            val point = GlassMaterialPreset.presetPoint(mode)
            assertEquals(GlassMaterialPreset.DEFAULT_LIQUID_LEVEL, point.level)
            assertEquals(68, point.level)
        }
    }

    /** 预设坐标的四个倍率就是滑杆默认值（100 = 预设原值），否则一选中就立刻是「自定义」。 */
    @Test
    fun presetScalesAreSliderDefaults() {
        listOf(
            GlassMaterialPreset.SOLID,
            GlassMaterialPreset.GLASS,
            GlassMaterialPreset.FROSTED
        ).forEach { mode ->
            assertEquals(presetScales, GlassMaterialPreset.presetPoint(mode).scales)
            assertEquals(100, presetScales.blur)
            assertEquals(100, presetScales.tint)
            assertEquals(100, presetScales.shell)
            assertEquals(100, presetScales.stroke)
        }
    }

    /** 选中一档后立刻匹配；四个倍率任一被拖动都不再匹配（→ 自定义）。 */
    @Test
    fun movingAnyScaleLeavesThePreset() {
        val point = GlassMaterialPreset.presetPoint(GlassMaterialPreset.FROSTED)
        assertTrue(
            GlassMaterialPreset.matchesPreset(
                GlassMaterialPreset.FROSTED, point.level, point.level, point.scales
            )
        )
        listOf(
            point.scales.copy(blur = 120),
            point.scales.copy(tint = 0),
            point.scales.copy(shell = 200),
            point.scales.copy(stroke = 99)
        ).forEach { moved ->
            assertFalse(
                "倍率 $moved 已偏离预设坐标",
                GlassMaterialPreset.matchesPreset(
                    GlassMaterialPreset.FROSTED, point.level, point.level, moved
                )
            )
        }
    }

    /** 强度滑杆（该档实际读取的那个键）偏离即自定义。 */
    @Test
    fun movingStrengthLeavesThePreset() {
        assertFalse(
            GlassMaterialPreset.matchesPreset(
                GlassMaterialPreset.GLASS, 68, 69, presetScales
            )
        )
        assertTrue(
            GlassMaterialPreset.matchesPreset(
                GlassMaterialPreset.GLASS, 999, 68, presetScales
            )
        )
    }

    /** 另一个强度键在当前档不生效，残留值不得把预设误判成自定义。 */
    @Test
    fun inactiveLevelKeyDoesNotAffectMatching() {
        // 磨砂档读 frosted，liquid 残留 12 不影响判定
        assertTrue(
            GlassMaterialPreset.matchesPreset(
                GlassMaterialPreset.FROSTED,
                GlassMaterialPreset.DEFAULT_FROSTED_LEVEL,
                12,
                presetScales
            )
        )
        // 玻璃档读 liquid，frosted 残留 12 不影响判定
        assertTrue(
            GlassMaterialPreset.matchesPreset(
                GlassMaterialPreset.GLASS,
                12,
                GlassMaterialPreset.DEFAULT_LIQUID_LEVEL,
                presetScales
            )
        )
    }
}
