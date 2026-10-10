package io.legado.app.uikit.v5

import io.legado.app.uikit.v5.token.CornerSpec
import io.legado.app.uikit.v5.token.DensityLevel
import io.legado.app.uikit.v5.token.DensitySpec
import io.legado.app.uikit.v5.token.GlassSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.MotionLevel
import io.legado.app.uikit.v5.token.MotionSpec
import io.legado.app.uikit.v5.token.SHAPE_CUT
import io.legado.app.uikit.v5.token.SHAPE_PETAL
import io.legado.app.uikit.v5.token.SHAPE_ROUND
import io.legado.app.uikit.v5.token.SHAPE_SHARP
import io.legado.app.uikit.v5.token.SHAPE_SOFT
import io.legado.app.uikit.v5.token.SHAPE_SCALE_RANGE
import io.legado.app.uikit.v5.token.ShapeFamily
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.uikit.v5.token.TitleWeight
import io.legado.app.uikit.v5.token.TypeSpec
import io.legado.app.uikit.v5.token.VisualSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 视觉语言规格自检（手册 P0-1：规格里有的，必须有测试）。
 */
class VisualSpecTest {

    // ------------------------------------------------------------ 形状族

    @Test
    fun `five shape families are registered and lookup never fails`() {
        listOf("soft", "round", "sharp", "petal", "cut").forEach { id ->
            assertEquals(id, ShapeFamily.byId(id).id)
        }
        // 未知族回落默认，绝不崩
        assertEquals("soft", ShapeFamily.byId("no-such-family").id)
    }

    @Test
    fun `petal family is asymmetric while others are not`() {
        val cardPetal = SHAPE_PETAL.specFor(ShapeKind.CARD)
        assertTrue("花瓣族应是不对称圆角", cardPetal is CornerSpec.Radius && cardPetal.minorRatio < 1f)

        val cardSoft = SHAPE_SOFT.specFor(ShapeKind.CARD)
        assertTrue(cardSoft is CornerSpec.Radius && cardSoft.minorRatio == 1f)
    }

    @Test
    fun `cut family uses cut corners`() {
        assertTrue(SHAPE_CUT.specFor(ShapeKind.CARD) is CornerSpec.Cut)
        assertTrue(SHAPE_CUT.specFor(ShapeKind.TAG) is CornerSpec.Cut)
    }

    @Test
    fun `pill families keep buttons and bars pill shaped`() {
        assertSame(CornerSpec.Pill, SHAPE_ROUND.specFor(ShapeKind.BUTTON))
        assertSame(CornerSpec.Pill, SHAPE_ROUND.specFor(ShapeKind.BAR))
        assertSame(CornerSpec.Pill, SHAPE_SOFT.specFor(ShapeKind.BUTTON))
        // 利落族刻意不用胶囊
        assertTrue(SHAPE_SHARP.specFor(ShapeKind.BUTTON) is CornerSpec.Radius)
    }

    @Test
    fun `shape scale out of range never throws`() {
        SHAPE_SOFT.of(ShapeKind.CARD, -5f)
        SHAPE_SOFT.of(ShapeKind.CARD, 99f)
        SHAPE_PETAL.of(ShapeKind.BAR, 0f)
        SHAPE_CUT.of(ShapeKind.FIELD, 2f)
        // 不抛异常即通过（越界值已在内部 clamp）
        assertTrue(SHAPE_SCALE_RANGE.contains(0f) && SHAPE_SCALE_RANGE.contains(2f))
    }

    @Test
    fun `sharp family is tighter than round family`() {
        val sharp = SHAPE_SHARP.specFor(ShapeKind.CARD) as CornerSpec.Radius
        val round = SHAPE_ROUND.specFor(ShapeKind.CARD) as CornerSpec.Radius
        assertTrue(sharp.dp.value < round.dp.value)
    }

    // ------------------------------------------------------------ 材质与玻璃

    @Test
    fun `only raised and floating consume glass`() {
        assertTrue(MaterialLevel.RAISED.usesGlass)
        assertTrue(MaterialLevel.FLOATING.usesGlass)
        assertTrue(!MaterialLevel.FLAT.usesGlass)
        assertTrue(!MaterialLevel.EMBEDDED.usesGlass)
    }

    @Test
    fun `glass off disables blur and noise entirely`() {
        val off = GlassSpec(enabled = false, alpha = 0.42f, blurDp = 22f, noise = 0.08f)
        assertEquals(0f, off.noiseAlpha(), 0.0001f)
        assertEquals(0f, off.effectiveBlur(), 0.0001f)
        assertEquals(1f, off.effectiveAlpha(), 0.0001f) // 退回实色
        assertEquals(0f, off.effectiveVibrancy(), 0.0001f)
    }

    @Test
    fun `glass values are clamped when enabled`() {
        val wild = GlassSpec(enabled = true, alpha = -3f, blurDp = 999f, noise = 5f, vibrancy = 7f)
        assertEquals(0f, wild.effectiveAlpha(), 0.0001f)
        assertEquals(GlassSpec.BLUR_RANGE.endInclusive, wild.effectiveBlur(), 0.0001f)
        assertEquals(0.3f, wild.noiseAlpha(), 0.0001f) // 噪声上限 0.3
        assertEquals(1f, wild.effectiveVibrancy(), 0.0001f)
    }

    @Test
    fun `solid preset is the off state`() {
        assertTrue(!GlassSpec.SOLID.enabled)
        assertEquals(1f, GlassSpec.SOLID.effectiveAlpha(), 0f)
    }

    // ------------------------------------------------------------ 密度

    @Test
    fun `density scales every spacing together`() {
        val compact = DensitySpec(DensityLevel.COMPACT.factor)
        val standard = DensitySpec(DensityLevel.STANDARD.factor)
        val roomy = DensitySpec(DensityLevel.COMFORTABLE.factor)

        assertTrue(compact.screenPadding.value < standard.screenPadding.value)
        assertTrue(standard.screenPadding.value < roomy.screenPadding.value)
        assertTrue(compact.rowHeight.value < roomy.rowHeight.value)
        // 标准档屏幕边距应为 16dp（方案 3.5：从旧的 12dp 提上来）
        assertEquals(16f, standard.screenPadding.value, 0.001f)
    }

    @Test
    fun `density factor is clamped`() {
        val wild = DensitySpec(9f)
        assertEquals(DensityLevel.FACTOR_RANGE.endInclusive, wild.clamped, 0.0001f)
        val tiny = DensitySpec(0.01f)
        assertEquals(DensityLevel.FACTOR_RANGE.start, tiny.clamped, 0.0001f)
    }

    // ------------------------------------------------------------ 动效

    @Test
    fun `expressive is slower and bouncier than calm`() {
        assertTrue(MotionLevel.EXPRESSIVE.longMs > MotionLevel.CALM.longMs)
        assertTrue(MotionSpec(MotionLevel.EXPRESSIVE).allowMovement)
    }

    @Test
    fun `reduce motion collapses durations and blocks movement`() {
        val reduced = MotionSpec(MotionLevel.EXPRESSIVE, reduce = true)
        assertTrue(reduced.longSpec<androidx.compose.ui.unit.Dp>().durationMillis < 200)
        assertTrue(!reduced.allowMovement)
    }

    // ------------------------------------------------------------ 排版

    @Test
    fun `title weight is a package parameter`() {
        val light = TypeSpec(titleWeight = TitleWeight.MEDIUM)
        val heavy = TypeSpec(titleWeight = TitleWeight.BOLD)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, light.title.fontWeight)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, heavy.title.fontWeight)
    }

    @Test
    fun `cjk spacing can be turned off`() {
        assertTrue(TypeSpec(cjkSpacing = true).title.letterSpacing.value > 0f)
        assertEquals(0f, TypeSpec(cjkSpacing = false).title.letterSpacing.value, 0f)
    }

    @Test
    fun `type tiers are ordered by size`() {
        val t = TypeSpec.DEFAULT
        assertTrue(t.caption.fontSize.value < t.body.fontSize.value)
        assertTrue(t.body.fontSize.value < t.title.fontSize.value)
        assertTrue(t.title.fontSize.value < t.headline.fontSize.value)
        assertTrue(t.headline.fontSize.value < t.display.fontSize.value)
    }

    // ------------------------------------------------------------ 聚合

    @Test
    fun `factory default is the float glass package`() {
        val d = VisualSpec.DEFAULT
        assertEquals(io.legado.app.uikit.v5.token.NavPosition.FLOAT, d.navPosition)
        assertTrue(d.glass.enabled)
        assertEquals(SHAPE_ROUND.id, d.shapeFamily.id)
        assertTrue(d.shapeScale > 1f) // 圆润档
    }

    @Test
    fun `every material level stays visible except flat`() {
        val v = VisualSpec.DEFAULT
        assertTrue(v.levelIsVisible(MaterialLevel.RAISED))
        assertTrue(v.levelIsVisible(MaterialLevel.FLOATING))
        assertTrue(!v.levelIsVisible(MaterialLevel.FLAT))
    }
}
