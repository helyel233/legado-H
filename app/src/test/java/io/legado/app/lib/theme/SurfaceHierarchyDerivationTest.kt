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
}
