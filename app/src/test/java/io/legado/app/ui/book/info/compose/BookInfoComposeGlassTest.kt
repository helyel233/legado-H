package io.legado.app.ui.book.info.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compose 详情页玻璃准入与 alpha 真值表。
 *
 * 第四轮：Compose 详情页（`BookInfoComposeActivity`）从未接过玻璃；用户报告的详情页问题
 * 无法从代码排除该路径，故按与 CLASSIC / 阅读弹窗同口径接入——只认玻璃档，不叠背景图门。
 */
class BookInfoComposeGlassTest {

    @Test
    fun allowedOnlyByGlassTier() {
        assertTrue(bookInfoComposeGlassAllowed(glassEnabled = true))
        assertFalse(bookInfoComposeGlassAllowed(glassEnabled = false))
    }

    /** 玻璃档：实底卡 0.97 让到上限 0.80，透出背后封面渐变。 */
    @Test
    fun opaqueCardYieldsAlphaUnderGlass() {
        assertEquals(
            BOOK_INFO_COMPOSE_GLASS_ALPHA_CAP,
            bookInfoComposeGlassAlpha(true, 0.97f),
            0f
        )
        assertEquals(0.80f, bookInfoComposeGlassAlpha(true, 0.96f), 0f)
    }

    /** 玻璃档：本就半透明的卡（简介卡 0.28）取较小值后不变，不会比原来更淡。 */
    @Test
    fun alreadyTranslucentCardIsNotDiluted() {
        assertEquals(0.28f, bookInfoComposeGlassAlpha(true, 0.28f), 0f)
        assertEquals(0.72f, bookInfoComposeGlassAlpha(true, 0.72f), 0f)
    }

    /** 非玻璃档：所有 alpha 逐值不变，逐值等价接入前。 */
    @Test
    fun opaqueWhenGlassDisabledKeepsEveryAlpha() {
        for (base in listOf(0.28f, 0.72f, 0.96f, 0.97f, 1.0f)) {
            assertEquals(base, bookInfoComposeGlassAlpha(false, base), 0f)
        }
    }

    /** 描边只在玻璃档出现；非玻璃档 false 对应调用方画 Modifier（无描边）。 */
    @Test
    fun strokeOnlyUnderGlass() {
        assertTrue(bookInfoComposeGlassStroke(true))
        assertFalse(bookInfoComposeGlassStroke(false))
    }

    /** 上限恒在 (0,1)，保证半透明而非全透明 / 全实底。 */
    @Test
    fun alphaCapIsSane() {
        assertTrue(BOOK_INFO_COMPOSE_GLASS_ALPHA_CAP > 0f)
        assertTrue(BOOK_INFO_COMPOSE_GLASS_ALPHA_CAP < 1f)
    }

    /** CLEAR 档与 CLASSIC 路径同口径走 0.86f + 描边，不压成玻璃档的 0.80f。 */
    @Test
    fun clearModeUsesClearSurfaceAlphaAndStroke() {
        assertEquals(0.86f, bookInfoComposeCardAlpha(glassEnabled = false, clearEnabled = true, opaqueAlpha = 0.97f), 0f)
        assertEquals(0.28f, bookInfoComposeCardAlpha(glassEnabled = false, clearEnabled = true, opaqueAlpha = 0.28f), 0f)
        assertEquals(0.80f, bookInfoComposeCardAlpha(glassEnabled = true, clearEnabled = false, opaqueAlpha = 0.97f), 0f)
        assertEquals(0.97f, bookInfoComposeCardAlpha(glassEnabled = false, clearEnabled = false, opaqueAlpha = 0.97f), 0f)

        assertTrue(bookInfoComposeCardStroke(glassEnabled = false, clearEnabled = true))
        assertTrue(bookInfoComposeCardStroke(glassEnabled = true, clearEnabled = false))
        assertFalse(bookInfoComposeCardStroke(glassEnabled = false, clearEnabled = false))
    }
}
