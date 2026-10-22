package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCssValuesTest {

    // --- parseColor: hex ------------------------------------------------------

    @Test
    fun sixDigitHex() {
        assertEquals(0xFFFF0000.toInt(), EpubCssValues.parseColor("#FF0000"))
    }

    @Test
    fun threeDigitHexExpandsEachChannel() {
        assertEquals(0xFFAABBCC.toInt(), EpubCssValues.parseColor("#abc"))
    }

    @Test
    fun fourDigitHexIsArgb() {
        // #abcd -> AA=aa RR=bb GG=cc BB=dd
        assertEquals(0xAABBCCDD.toInt(), EpubCssValues.parseColor("#abcd"))
    }

    @Test
    fun eightDigitHexIsArgb() {
        assertEquals(0x00000000, EpubCssValues.parseColor("#00000000"))
    }

    @Test
    fun transparentNamedColorIsFullyClear() {
        assertEquals(0x00000000, EpubCssValues.parseColor("transparent"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(0xFF00FF00.toInt(), EpubCssValues.parseColor("  #00FF00  "))
    }

    @Test
    fun matchingQuotesAreStripped() {
        assertEquals(0xFFFF0000.toInt(), EpubCssValues.parseColor("'#FF0000'"))
        assertEquals(0xFF00FF00.toInt(), EpubCssValues.parseColor("\"#00FF00\""))
    }

    @Test
    fun garbageHexReturnsNull() {
        assertNull(EpubCssValues.parseColor("#zzz"))
    }

    @Test
    fun nonColorReturnsNull() {
        assertNull(EpubCssValues.parseColor("not-a-color"))
    }

    // --- parseColor: rgb / rgba -----------------------------------------------

    @Test
    fun rgbFunction() {
        assertEquals(0xFFFF0000.toInt(), EpubCssValues.parseColor("rgb(255,0,0)"))
    }

    @Test
    fun rgbFunctionWithSpacesAndSlashes() {
        assertEquals(0xFF008000.toInt(), EpubCssValues.parseColor("rgb(0, 128 / 0)"))
    }

    @Test
    fun rgbaAlphaFraction() {
        // 0.5 * 255 = 127，绿/蓝通道为 0
        assertEquals(0x7FFF0000.toInt(), EpubCssValues.parseColor("rgba(255,0,0,0.5)"))
    }

    @Test
    fun rgbPercentChannel() {
        // 50% -> 127
        assertEquals(0xFF7F0000.toInt(), EpubCssValues.parseColor("rgb(50%,0,0)"))
    }

    @Test
    fun rgbaAlphaPercent() {
        // 50% alpha -> 127
        assertEquals(0x7F0000FF.toInt(), EpubCssValues.parseColor("rgba(0,0,255,50%)"))
    }

    // --- parseColor: named ----------------------------------------------------

    @Test
    fun namedColors() {
        assertEquals(0xFFFF0000.toInt(), EpubCssValues.parseColor("red"))
        assertEquals(0xFF0000FF.toInt(), EpubCssValues.parseColor("blue"))
        assertEquals(0xFFFFFFFF.toInt(), EpubCssValues.parseColor("white"))
        assertEquals(0xFF808080.toInt(), EpubCssValues.parseColor("gray"))
        assertEquals(0xFF808080.toInt(), EpubCssValues.parseColor("grey")) // 别名
        assertEquals(0xFF00FFFF.toInt(), EpubCssValues.parseColor("aqua")) // 别名
    }

    @Test
    fun namedColorIsCaseInsensitive() {
        assertEquals(0xFFFF0000.toInt(), EpubCssValues.parseColor("ReD"))
    }

    // --- parseTagColor --------------------------------------------------------

    @Test
    fun tagColorSixDigits() {
        assertEquals(0xFFFF0000.toInt(), EpubCssValues.parseTagColor("FF0000"))
    }

    @Test
    fun tagColorEightDigits() {
        assertEquals(0xAABBCCDD.toInt(), EpubCssValues.parseTagColor("AABBCCDD"))
    }

    @Test
    fun tagColorRejectsWrongLength() {
        assertNull(EpubCssValues.parseTagColor("FFF"))
        assertNull(EpubCssValues.parseTagColor("FFFF"))
    }

    // --- extractColor ---------------------------------------------------------

    @Test
    fun extractColorPullsHexFromShorthand() {
        assertEquals("#FF0000", EpubCssValues.extractColor("background #FF0000 solid"))
    }

    @Test
    fun extractColorPullsNamedFromShorthand() {
        assertEquals("red", EpubCssValues.extractColor("border red"))
    }

    @Test
    fun extractColorReturnsNullWhenNoColor() {
        assertNull(EpubCssValues.extractColor("border solid"))
    }

    // --- extractLength --------------------------------------------------------

    @Test
    fun extractLengthFindsPx() {
        assertEquals("12px", EpubCssValues.extractLength("padding 12px auto"))
    }

    @Test
    fun extractLengthFindsEmAndRem() {
        assertEquals("3em", EpubCssValues.extractLength("3em"))
        assertEquals("1.5rem", EpubCssValues.extractLength("border 1.5rem"))
    }

    @Test
    fun extractLengthFindsBareNumber() {
        assertEquals("12", EpubCssValues.extractLength("12"))
    }

    @Test
    fun extractLengthReturnsNullForNonLength() {
        assertNull(EpubCssValues.extractLength("auto"))
    }

    // --- toBoxLengths ---------------------------------------------------------

    @Test
    fun boxOneValueRepeatsFourTimes() {
        assertEquals(listOf("1px", "1px", "1px", "1px"), EpubCssValues.toBoxLengths("1px"))
    }

    @Test
    fun boxTwoValues() {
        assertEquals(listOf("1px", "2px", "1px", "2px"), EpubCssValues.toBoxLengths("1px 2px"))
    }

    @Test
    fun boxThreeValues() {
        assertEquals(listOf("1px", "2px", "3px", "2px"), EpubCssValues.toBoxLengths("1px 2px 3px"))
    }

    @Test
    fun boxFourOrMoreTakesFirstFour() {
        assertEquals(
            listOf("1px", "2px", "3px", "4px"),
            EpubCssValues.toBoxLengths("1px 2px 3px 4px 5px")
        )
    }

    @Test
    fun boxEmptyString() {
        assertEquals(emptyList<String>(), EpubCssValues.toBoxLengths("   "))
    }

    // --- box side resolution (margin/padding shorthand -> a single side) -------

    @Test
    fun oneValueAppliesToAllSides() {
        val values = listOf("1px")
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "top"))
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "right"))
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "bottom"))
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "left"))
    }

    @Test
    fun twoValuesAreTopBottomAndLeftRight() {
        val values = listOf("1px", "2px")
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "top"))
        assertEquals("2px", EpubCssValues.resolveBoxSide(values, "right"))
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "bottom"))
        assertEquals("2px", EpubCssValues.resolveBoxSide(values, "left"))
    }

    @Test
    fun threeValuesLeaveLeftMirroringRight() {
        val values = listOf("1px", "2px", "3px")
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "top"))
        assertEquals("2px", EpubCssValues.resolveBoxSide(values, "right"))
        assertEquals("3px", EpubCssValues.resolveBoxSide(values, "bottom"))
        assertEquals("2px", EpubCssValues.resolveBoxSide(values, "left"))
    }

    @Test
    fun fourValuesAreTakenAsGiven() {
        val values = listOf("1px", "2px", "3px", "4px")
        assertEquals("1px", EpubCssValues.resolveBoxSide(values, "top"))
        assertEquals("2px", EpubCssValues.resolveBoxSide(values, "right"))
        assertEquals("3px", EpubCssValues.resolveBoxSide(values, "bottom"))
        assertEquals("4px", EpubCssValues.resolveBoxSide(values, "left"))
    }

    @Test
    fun noValuesOrUnknownSideYieldsEmpty() {
        val values = emptyList<String>()
        assertEquals("", EpubCssValues.resolveBoxSide(values, "top"))
        assertEquals("", EpubCssValues.resolveBoxSide(listOf("1px"), "center"))
    }

    // --- break decisions -----------------------------------------------------

    @Test
    fun breakKeywordsForceABreak() {
        assertTrue(EpubCssValues.isAlwaysBreak("always"))
        assertTrue(EpubCssValues.isAlwaysBreak("page"))
        assertTrue(EpubCssValues.isAlwaysBreak("left"))
        assertTrue(EpubCssValues.isAlwaysBreak("right"))
    }

    @Test
    fun avoidAndAutoDoNotBreak() {
        assertFalse(EpubCssValues.isAlwaysBreak("avoid"))
        assertFalse(EpubCssValues.isAlwaysBreak("auto"))
        assertFalse(EpubCssValues.isAlwaysBreak(""))
    }

    @Test
    fun breakCheckIsCaseInsensitive() {
        assertTrue(EpubCssValues.isAlwaysBreak("ALWAYS"))
    }

    // --- spacing decisions ---------------------------------------------------

    @Test
    fun largeSpacingThresholds() {
        assertTrue(EpubCssValues.isLargeSpacing("1em"))
        assertTrue(EpubCssValues.isLargeSpacing("1rem"))
        assertTrue(EpubCssValues.isLargeSpacing("8%"))
        assertTrue(EpubCssValues.isLargeSpacing("16px"))
        assertTrue(EpubCssValues.isLargeSpacing("16"))
    }

    @Test
    fun smallSpacingIsNotLarge() {
        assertFalse(EpubCssValues.isLargeSpacing("0.5em"))
        assertFalse(EpubCssValues.isLargeSpacing("7%"))
        assertFalse(EpubCssValues.isLargeSpacing("15px"))
        assertFalse(EpubCssValues.isLargeSpacing("15"))
    }

    @Test
    fun zeroAndBlankAreNotLargeSpacing() {
        assertFalse(EpubCssValues.isLargeSpacing("0"))
        assertFalse(EpubCssValues.isLargeSpacing("  "))
        assertFalse(EpubCssValues.isLargeSpacing("auto")) // 无单位 -> 0 -> 小于 16
    }

    // --- px conversion -------------------------------------------------------

    private val width = 1000f
    private val emBase = 20f

    private fun assertPx(expected: Float, actual: Float?, what: String) {
        assertNotNull("$what 不应为 null", actual)
        assertEquals(what, expected, actual!!, 0.001f)
    }

    @Test
    fun spacingConvertsPercentageOffTheVisibleWidth() {
        assertPx(500f, EpubCssValues.toSpacingPx("50%", width, emBase), "50% 间距")
    }

    @Test
    fun spacingConvertsEmAndRemOffTheTextHeight() {
        assertPx(40f, EpubCssValues.toSpacingPx("2em", width, emBase), "2em 间距")
        assertPx(40f, EpubCssValues.toSpacingPx("2rem", width, emBase), "2rem 间距")
    }

    /**
     * 回归锁定：`rem` 必须排在 `em` 之前判定——"2rem" 同样 endsWith("em")，
     * 若顺序颠倒 rem 分支会永远不可达，Epub 里用 rem 写的 margin/padding 会静默失效。
     */
    @Test
    fun remMustBeCheckedBeforeEm() {
        assertPx(40f, EpubCssValues.toSpacingPx("2rem", width, emBase), "2rem 间距")
        assertTrue(EpubCssValues.isLargeSpacing("2rem"))
        // em 仍然正常
        assertPx(40f, EpubCssValues.toSpacingPx("2em", width, emBase), "2em 间距")
    }

    @Test
    fun spacingPassesPxAndBareNumbersThrough() {
        assertPx(12f, EpubCssValues.toSpacingPx("12px", width, emBase), "12px 间距")
        assertPx(12f, EpubCssValues.toSpacingPx("12", width, emBase), "裸数间距")
    }

    @Test
    fun zeroAndBlankCollapseToZero() {
        assertPx(0f, EpubCssValues.toSpacingPx("0", width, emBase), "0 间距")
        assertPx(0f, EpubCssValues.toSpacingPx("  ", width, emBase), "空白间距")
        assertPx(0f, EpubCssValues.toHorizontalPx("0", width, emBase), "0 水平")
    }

    /**
     * 两个转换函数唯一的行为差异：`auto` 在水平方向视为 0（宽度自适应），
     * 在垂直间距上不是有效值（返回 null）。后续若被误统一，Epub 排版会错。
     */
    @Test
    fun autoIsZeroHorizontallyButInvalidVertically() {
        assertEquals(0f, EpubCssValues.toHorizontalPx("auto", width, emBase))
        assertNull(EpubCssValues.toSpacingPx("auto", width, emBase))
    }

    @Test
    fun unparsableValuesReturnNull() {
        assertNull(EpubCssValues.toSpacingPx("abc", width, emBase))
        assertNull(EpubCssValues.toHorizontalPx("abc", width, emBase))
    }
}
