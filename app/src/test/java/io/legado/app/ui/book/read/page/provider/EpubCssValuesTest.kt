package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
