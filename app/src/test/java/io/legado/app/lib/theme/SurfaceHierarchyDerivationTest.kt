package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-M1 纯 JVM 单测：锁住 [SurfaceHierarchyDerivation] 的 L0→L1→L2 同色相派生、
 * 墨水屏旁路、柔和同系描边与同心圆角退让公式。
 */
class SurfaceHierarchyDerivationTest {

    @Test
    fun deriveCardAndSlot_dayWarmCanvas_preservesWarmHueAndCreatesStep() {
        val warmCanvas = 0xFFF4ECD8.toInt()
        val accent = 0xFF8B5A2B.toInt()

        val card = SurfaceHierarchyDerivation.deriveCardColor(
            backgroundArgb = warmCanvas,
            isNight = false,
            isEInk = false
        )
        val slot = SurfaceHierarchyDerivation.deriveSlotColor(
            cardArgb = card,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )

        // 卡片向纯白混合 72%，保留暖色相（R > B）且不是死白
        val cardR = (card ushr 16) and 0xFF
        val cardB = card and 0xFF
        assertTrue(cardR > cardB)
        assertNotEquals(SurfaceHierarchyDerivation.ARGB_WHITE, card)

        // 内槽比卡片略暗，且有明显色差
        val slotR = (slot ushr 16) and 0xFF
        assertTrue(slotR < cardR)
        assertNotEquals(card, slot)
    }

    @Test
    fun deriveCardAndSlot_dayPureWhiteCanvas_returnsWhiteCardAndSoftSlot() {
        val card = SurfaceHierarchyDerivation.deriveCardColor(
            backgroundArgb = 0xFFFFFFFF.toInt(),
            isNight = false,
            isEInk = false
        )
        val slot = SurfaceHierarchyDerivation.deriveSlotColor(
            cardArgb = card,
            accentArgb = 0xFF1E88E5.toInt(),
            isNight = false,
            isEInk = false
        )

        assertEquals(SurfaceHierarchyDerivation.ARGB_WHITE, card)
        val slotR = (slot ushr 16) and 0xFF
        assertTrue(slotR in 235..246)
    }

    @Test
    fun deriveCardAndSlot_nightDarkBlueCanvas_preservesHueAndStepsUp() {
        val darkBlue = 0xFF0F172A.toInt()
        val accent = 0xFF38BDF8.toInt()

        val card = SurfaceHierarchyDerivation.deriveCardColor(
            backgroundArgb = darkBlue,
            isNight = true,
            isEInk = false
        )
        val slot = SurfaceHierarchyDerivation.deriveSlotColor(
            cardArgb = card,
            accentArgb = accent,
            isNight = true,
            isEInk = false
        )

        val bgB = darkBlue and 0xFF
        val cardB = card and 0xFF
        val slotB = slot and 0xFF
        assertTrue(cardB > bgB)
        assertTrue(slotB > cardB)
        // 保留深蓝冷色相（B > R）
        val cardR = (card ushr 16) and 0xFF
        assertTrue(cardB > cardR)
    }

    @Test
    fun deriveAll_eInkMode_bypassesBlendingWithMonoContrast() {
        val card = SurfaceHierarchyDerivation.deriveCardColor(
            backgroundArgb = 0xFFF4ECD8.toInt(),
            isNight = false,
            isEInk = true
        )
        val slot = SurfaceHierarchyDerivation.deriveSlotColor(
            cardArgb = card,
            accentArgb = 0xFF1E88E5.toInt(),
            isNight = false,
            isEInk = true
        )
        val border = SurfaceHierarchyDerivation.deriveDefaultBorderLineColor(
            surfaceArgb = card,
            accentArgb = 0xFF1E88E5.toInt(),
            isNight = false,
            isEInk = true
        )

        assertEquals(SurfaceHierarchyDerivation.ARGB_WHITE, card)
        assertEquals(SurfaceHierarchyDerivation.ARGB_EINK_MUTED, slot)
        assertEquals(SurfaceHierarchyDerivation.ARGB_BLACK, border)
    }

    @Test
    fun deriveDefaultBorderLineColor_dayAndNight_usesSubtleLowAlphaInsteadOfOpaqueAccent() {
        val dayBorder = SurfaceHierarchyDerivation.deriveDefaultBorderLineColor(
            surfaceArgb = 0xFFFFFFFF.toInt(),
            accentArgb = 0xFF1E88E5.toInt(),
            isNight = false,
            isEInk = false
        )
        val nightBorder = SurfaceHierarchyDerivation.deriveDefaultBorderLineColor(
            surfaceArgb = 0xFF1C1C1E.toInt(),
            accentArgb = 0xFF1E88E5.toInt(),
            isNight = true,
            isEInk = false
        )

        assertEquals(22, (dayBorder ushr 24) and 0xFF)
        assertEquals(32, (nightBorder ushr 24) and 0xFF)
    }

    @Test
    fun computeActionRadiusPx_maintainsConcentricInsetAcrossAllScales() {
        val density = 3f
        val panelBasePx = 10f * density

        // s = 0 -> 0dp
        assertEquals(0f, SurfaceHierarchyDerivation.computeActionRadiusPx(panelBasePx, 0f, density), 0.01f)

        // s = 0.5 -> panel 5dp, action = max(5 - 3, 2) = 2dp
        assertEquals(2f * density, SurfaceHierarchyDerivation.computeActionRadiusPx(panelBasePx, 0.5f, density), 0.01f)

        // s = 1.0 -> panel 10dp, action = max(10 - 3, 4) = 7dp
        assertEquals(7f * density, SurfaceHierarchyDerivation.computeActionRadiusPx(panelBasePx, 1.0f, density), 0.01f)

        // s = 2.0 -> panel 20dp, action = max(20 - 3, 8) = 17dp (严格保持 3dp 同心差)
        assertEquals(17f * density, SurfaceHierarchyDerivation.computeActionRadiusPx(panelBasePx, 2.0f, density), 0.01f)

        // s = 3.0 -> panel 30dp, action = max(30 - 3, 12) = 27dp
        assertEquals(27f * density, SurfaceHierarchyDerivation.computeActionRadiusPx(panelBasePx, 3.0f, density), 0.01f)
    }

    @Test
    fun deriveCardColor_transparentInput_fallsBackToOpaqueCanvasInsteadOfGrayAlpha() {
        val dayCard = SurfaceHierarchyDerivation.deriveCardColor(
            backgroundArgb = 0x00000000,
            isNight = false,
            isEInk = false
        )
        val nightCard = SurfaceHierarchyDerivation.deriveCardColor(
            backgroundArgb = 0x00000000,
            isNight = true,
            isEInk = false
        )

        // 绝不出现 alpha != 255 或 #B8B8B8B8 / #12121212
        assertEquals(0xFF, (dayCard ushr 24) and 0xFF)
        assertEquals(0xFF, (nightCard ushr 24) and 0xFF)
        assertEquals(SurfaceHierarchyDerivation.ARGB_WHITE, dayCard)
        assertEquals(0xFF232323.toInt(), nightCard)
    }

    @Test
    fun deriveL3ControlColor_dayWarmAndNearWhite_createsDirectionalStep() {
        val accent = 0xFF8B5A2B.toInt()
        val warmSlot = 0xFFF2ECE4.toInt()
        val warmL3 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = warmSlot,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )
        assertNotEquals(warmSlot, warmL3)
        assertEquals(0xFF, (warmL3 ushr 24) and 0xFF)
        assertTrue(((warmL3 ushr 16) and 0xFF) > ((warmSlot ushr 16) and 0xFF))
        assertTrue(((warmL3 ushr 8) and 0xFF) > ((warmSlot ushr 8) and 0xFF))
        assertTrue((warmL3 and 0xFF) > (warmSlot and 0xFF))

        val whiteSlot = SurfaceHierarchyDerivation.ARGB_WHITE
        val whiteL3 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = whiteSlot,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )
        assertNotEquals(whiteSlot, whiteL3)
        assertEquals(0xFF, (whiteL3 ushr 24) and 0xFF)
        assertTrue(((whiteL3 ushr 8) and 0xFF) < 0xFF)
    }

    @Test
    fun deriveL3ControlColor_dayModeDarkCustomSlot_liftsSubtlyWithoutExploding() {
        val accent = 0xFF4F7CAC.toInt()
        val darkSlot = 0xFF313131.toInt()
        val darkL3 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = darkSlot,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )
        val slotRed = (darkSlot ushr 16) and 0xFF
        val l3Red = (darkL3 ushr 16) and 0xFF
        assertEquals(0xFF, (darkL3 ushr 24) and 0xFF)
        assertTrue(l3Red > slotRed)
        assertTrue(l3Red < 0x55)
    }

    @Test
    fun deriveL3ControlColor_nightSlot_liftsAndPreservesAlpha() {
        val accent = 0xFF4F7CAC.toInt()
        val nightSlot = 0xFF232323.toInt()
        val nightL3 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = nightSlot,
            accentArgb = accent,
            isNight = true,
            isEInk = false
        )
        assertNotEquals(nightSlot, nightL3)
        assertEquals(0xFF, (nightL3 ushr 24) and 0xFF)
        val slotRed = (nightSlot ushr 16) and 0xFF
        val l3Red = (nightL3 ushr 16) and 0xFF
        assertTrue(l3Red > slotRed)
    }

    @Test
    fun deriveL3ControlColor_eInkMode_returnsPureWhiteBypass() {
        val resultDay = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = 0xFFEEEEEE.toInt(),
            accentArgb = 0xFF000000.toInt(),
            isNight = false,
            isEInk = true
        )
        val resultNight = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = 0xFF222222.toInt(),
            accentArgb = 0xFFFFFFFF.toInt(),
            isNight = true,
            isEInk = true
        )
        assertEquals(SurfaceHierarchyDerivation.ARGB_WHITE, resultDay)
        assertEquals(SurfaceHierarchyDerivation.ARGB_WHITE, resultNight)
    }

    @Test
    fun deriveL3ControlColor_glassAndHighOpacityBoundaries_monotonicAlphaWithoutInversion() {
        val accent = 0xFF4F7CAC.toInt()
        val baseRgb = 0x00EAEAEA
        val glassAlpha128 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = (128 shl 24) or baseRgb,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )
        val opacity73Alpha186 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = (186 shl 24) or baseRgb,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )
        val opacity99Alpha252 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = (252 shl 24) or baseRgb,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )
        val opacity100Alpha255 = SurfaceHierarchyDerivation.deriveL3ControlColor(
            slotArgb = (255 shl 24) or baseRgb,
            accentArgb = accent,
            isNight = false,
            isEInk = false
        )

        val a128 = (glassAlpha128 ushr 24) and 0xFF
        val a186 = (opacity73Alpha186 ushr 24) and 0xFF
        val a252 = (opacity99Alpha252 ushr 24) and 0xFF
        val a255 = (opacity100Alpha255 ushr 24) and 0xFF

        assertEquals(148, a128)
        assertEquals(206, a186)
        assertEquals(255, a252)
        assertEquals(255, a255)
        assertTrue(a128 < a186 && a186 <= a252 && a252 <= a255)
    }

    @Test
    fun resolveConcentricCardPaddingDp_matchesContractAcrossRadii() {
        assertEquals(14f, SurfaceHierarchyDerivation.resolveConcentricCardPaddingDp(0f), 0.001f)
        assertEquals(14f, SurfaceHierarchyDerivation.resolveConcentricCardPaddingDp(14f), 0.001f)
        assertEquals(15.5f, SurfaceHierarchyDerivation.resolveConcentricCardPaddingDp(20f), 0.001f)
        assertEquals(18f, SurfaceHierarchyDerivation.resolveConcentricCardPaddingDp(30f), 0.001f)
        assertEquals(18f, SurfaceHierarchyDerivation.resolveConcentricCardPaddingDp(40f), 0.001f)
    }
}
