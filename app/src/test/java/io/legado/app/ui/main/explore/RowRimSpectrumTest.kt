package io.legado.app.ui.main.explore

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API < 33 拟液态叠加层（方案 A1）纯函数的确定性单测。
 *
 * A1 是低版本设备上**唯一的**体积感/色散观感来源，而且它的参数不来自着色器、
 * 没有真机截图能自动比对，所以口径必须靠断言锁住：
 * - 叠加强度必须随 `dispersion` 单调（否则三档看起来一样，用户以为设置没生效）；
 * - 高光带/内阴影不得吃满整行（否则行变成一条白色/黑色横条）；
 * - 色散彩边错位必须 ≤ 1px（超过就是重影，不是色散）；
 * - 颜色推导不得把通道加溢出（否则浅色主题下一圈纯红纯蓝）。
 */
class RowRimSpectrumTest {

    private val density = 2.75f
    private val rowHeightPx = 36f * density // 99px，与真机一致

    private val glass = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f)
    private val frosted = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)

    private fun spectrumOf(params: GlassMaterialPreset.Params) = rowRimSpectrum(
        dispersion = params.dispersion,
        refractionHeightDp = params.refractionHeightDp,
        rowHeightPx = rowHeightPx,
        density = density
    )

    // ------------------------------------------------------------ 单调性 / 档位差异

    /** 四个量都必须随 dispersion 单调不减（dispersion 是唯一的档位差来源）。 */
    @Test
    fun everyTermGrowsMonotonicallyWithDispersion() {
        val samples = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 1f).map {
            rowRimSpectrum(it, 25.6f, rowHeightPx, density)
        }
        samples.zipWithNext { low, high ->
            assertTrue(low.topBandAlpha <= high.topBandAlpha)
            assertTrue(low.innerShadowAlpha <= high.innerShadowAlpha)
            assertTrue(low.chromaOffsetPx <= high.chromaOffsetPx)
            assertTrue(low.chromaAlpha <= high.chromaAlpha)
        }
    }

    /** 透明玻璃档的每一项都必须明显强于磨砂档（真机上「肉眼可分」的量化门槛）。 */
    @Test
    fun glassPresetIsVisiblyStrongerThanFrosted() {
        val g = spectrumOf(glass)
        val f = spectrumOf(frosted)
        assertTrue(g.topBandAlpha > f.topBandAlpha)
        assertTrue(g.innerShadowAlpha > f.innerShadowAlpha)
        assertTrue(g.chromaOffsetPx > f.chromaOffsetPx)
        assertTrue(g.chromaAlpha > f.chromaAlpha)
        // 「明显」至少要达到 3% alpha 以上的差距，否则人眼在 36dp 高的行上看不出来
        assertTrue(g.topBandAlpha - f.topBandAlpha > 0.03f)
        assertTrue(g.chromaAlpha - f.chromaAlpha > 0.02f)
    }

    /** 逐值锁 glass 档结果（口径回归时立即失败）。 */
    @Test
    fun glassSpectrumValuesAreLocked() {
        val s = spectrumOf(glass) // dispersion = 0.272
        assertEquals(0.10f + 0.272f * 0.55f, s.topBandAlpha, 0.0001f)
        assertEquals(0.2496f, s.topBandAlpha, 0.001f)
        assertEquals(0.05f + 0.272f * 0.30f, s.innerShadowAlpha, 0.0001f)
        assertEquals(0.1316f, s.innerShadowAlpha, 0.001f)
        assertEquals(0.272f * 0.55f, s.chromaAlpha, 0.0001f)
        assertEquals(0.1496f, s.chromaAlpha, 0.001f)
        // 0.2 + 0.272*1.6 = 0.6352（亚像素）
        assertEquals(0.6352f, s.chromaOffsetPx, 0.0001f)
    }

    // ------------------------------------------------------------ 上限与退化

    /** 上限必须硬生效：极端 dispersion 也不得越界（否则行会过曝/重影）。 */
    @Test
    fun capsAreEnforcedAtExtremeDispersion() {
        val s = rowRimSpectrum(10f, 25.6f, rowHeightPx, density)
        assertTrue(s.topBandAlpha <= ROW_RIM_MAX_TOP_ALPHA)
        assertTrue(s.innerShadowAlpha <= ROW_RIM_MAX_INNER_SHADOW_ALPHA)
        assertTrue(s.chromaAlpha <= ROW_RIM_MAX_CHROMA_ALPHA)
        assertTrue(s.chromaOffsetPx <= ROW_RIM_CHROMA_MAX_OFFSET_PX)
        // 负 dispersion（脏数据）不得产出负 alpha
        val negative = rowRimSpectrum(-1f, 25.6f, rowHeightPx, density)
        assertTrue(negative.topBandAlpha >= 0f)
        assertTrue(negative.chromaAlpha >= 0f)
        assertTrue(negative.chromaOffsetPx >= 0f)
    }

    /** 彩边错位量恒 ≤ 1px：这是「色散」与「重影」的分界线。 */
    @Test
    fun chromaOffsetNeverExceedsOnePixel() {
        assertEquals(1f, ROW_RIM_CHROMA_MAX_OFFSET_PX, 0.000001f)
        listOf(0f, 0.2f, 0.5f, 0.8f, 1f, 5f).forEach { d ->
            val s = rowRimSpectrum(d, 25.6f, rowHeightPx, density)
            assertTrue("d=$d 越界", s.chromaOffsetPx <= 1f)
        }
    }

    /** 高光带比例落在 [0.12, 0.45]：太窄看不见，太宽就成了整行渐变填充。 */
    @Test
    fun topBandFractionStaysInReadableRange() {
        listOf(0f, 20f, 99f, 400f).forEach { height ->
            val s = rowRimSpectrum(0.272f, 25.6f, height, density)
            assertTrue("height=$height", s.topBandFraction >= ROW_RIM_TOP_BAND_MIN_FRACTION)
            assertTrue("height=$height", s.topBandFraction <= ROW_RIM_TOP_BAND_MAX_FRACTION)
        }
        // 行高未知（0）时也不得为 0，否则除零
        val zero = rowRimSpectrum(0.272f, 25.6f, 0f, density)
        assertEquals(ROW_RIM_TOP_BAND_MIN_FRACTION, zero.topBandFraction, 0.0001f)
    }

    /** 高光带与内阴影带不得在矮行上重叠成一条糊线：阴影带高度 ≤ 行高一半。 */
    @Test
    fun innerShadowBandNeverExceedsHalfRow() {
        listOf(10f, 40f, 99f, 400f).forEach { height ->
            val s = rowRimSpectrum(0.272f, 25.6f, height, density)
            val band = rowInnerShadowHeightPx(height, s)
            assertTrue("height=$height", band >= 0f)
            assertTrue("height=$height", band <= height / 2f + 0.0001f)
        }
        assertEquals(0f, rowInnerShadowHeightPx(0f, spectrumOf(glass)), 0.000001f)
    }

    // ------------------------------------------------------------ 彩边颜色

    /** 暖侧抬红、冷侧抬蓝，且都不越界；alpha 与传入值一致。 */
    @Test
    fun chromaEdgeColorsShiftCorrectChannelsWithinBounds() {
        val base = 0xFF808080.toInt()
        val edges = rowChromaEdgeColors(base, dispersion = 0.272f, alpha = 0.15f)
        val warmR = (edges.warmColor ushr 16) and 0xFF
        val coolB = edges.coolColor and 0xFF
        assertEquals(0x80 + (0.272f * 96f).toInt(), warmR)
        assertEquals(0x80 + (0.272f * 96f).toInt(), coolB)
        assertEquals((0.15f * 255f).toInt(), (edges.warmColor ushr 24) and 0xFF)
        assertEquals((0.15f * 255f).toInt(), (edges.coolColor ushr 24) and 0xFF)
        // 暖侧蓝通道被压低、冷侧红通道被压低（形成色散方向感）
        assertTrue(((edges.warmColor) and 0xFF) < 0x80)
        assertTrue((((edges.coolColor ushr 16) and 0xFF)) < 0x80)
    }

    /** 极端输入不得让通道溢出/下溢成非法 ARGB（浅色主题近白 + 强色散是最坏组合）。 */
    @Test
    fun chromaEdgeColorsNeverOverflow() {
        listOf(0xFFFFFFFF.toInt(), 0x00000000, 0xFF000000.toInt(), 0xFFFEFEFE.toInt()).forEach { base ->
            listOf(0f, 0.5f, 1f, 3f).forEach { dispersion ->
                val edges = rowChromaEdgeColors(base, dispersion, alpha = 0.2f)
                listOf(edges.warmColor, edges.coolColor).forEach { color ->
                    listOf(0, 8, 16, 24).forEach { shift ->
                        val channel = (color ushr shift) and 0xFF
                        assertTrue("channel 越界 base=$base d=$dispersion", channel in 0..255)
                    }
                }
            }
        }
    }

    /** 基准色相同时，dispersion 越大彩边偏移越明显（单调）。 */
    @Test
    fun chromaEdgeOffsetGrowsWithDispersion() {
        val base = 0xFF404040.toInt()
        val low = rowChromaEdgeColors(base, 0.1f, 0.2f)
        val high = rowChromaEdgeColors(base, 0.5f, 0.2f)
        val lowRed = (low.warmColor ushr 16) and 0xFF
        val highRed = (high.warmColor ushr 16) and 0xFF
        assertTrue(highRed > lowRed)
    }
}
