package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypographyJustificationTest {

    private fun assertColumnInvariants(starts: FloatArray, ends: FloatArray) {
        assertEquals(starts.size, ends.size)
        for (i in starts.indices) {
            assertTrue("Column $i has negative width: ${starts[i]}..${ends[i]}", ends[i] >= starts[i])
            if (i < starts.lastIndex) {
                assertTrue(
                    "Column $i overlaps column ${i + 1}: end=${ends[i]}, nextStart=${starts[i + 1]}",
                    ends[i] <= starts[i + 1] + 1e-4f
                )
            }
        }
    }

    @Test
    fun `mixed CJK and Latin caps space expansion and conserves end coordinate`() {
        val words = listOf("测", "试", " ", "H", "e", "l", "l", "o", " ", "W", "o", "r", "l", "d")
        val cnCharWidth = 40f
        val textSize = 40f
        val widths = listOf(40f, 40f, 12f, 24f, 20f, 10f, 10f, 20f, 12f, 26f, 20f, 14f, 10f, 20f)
        val desiredWidth = widths.sum()
        val residualWidth = 100f
        val startX = 16f

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = startX,
            residualWidth = residualWidth,
            textSize = textSize,
            cnCharWidth = cnCharWidth
        )

        val expectedMaxExtraSpace = maxOf(cnCharWidth * 0.55f, textSize * 0.45f)
        assertEquals(expectedMaxExtraSpace, result.wordSpacing, 1e-3f)
        assertTrue(result.extraLetterSpacing > 0f)
        assertEquals(-result.extraLetterSpacing * textSize / 2f, result.extraLetterSpacingOffsetX, 1e-3f)
        assertFalse(result.hasCustomColumnLayout)
        assertEquals(startX + desiredWidth + residualWidth, result.columnEnds.last(), 1e-2f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `clean English line below maxExtraSpace expands only spaces and ignores trailing space`() {
        val words = listOf("H", "i", " ", "a", "l", "l", " ", "y", "o", "u", " ")
        val widths = listOf(22f, 10f, 10f, 18f, 10f, 10f, 10f, 18f, 18f, 18f, 10f)
        val residualWidth = 20f
        val cnCharWidth = 40f
        val textSize = 40f

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = 0f,
            residualWidth = residualWidth,
            textSize = textSize,
            cnCharWidth = cnCharWidth
        )

        // Only 2 effective spaces (indices 2 and 6); trailing space at index 10 is ignored.
        assertEquals(10f, result.wordSpacing, 1e-3f)
        assertEquals(0f, result.extraLetterSpacing, 1e-6f)
        assertEquals(0f, result.extraLetterSpacingOffsetX, 1e-6f)
        assertFalse(result.hasCustomColumnLayout)
        assertEquals(widths.sum() + residualWidth, result.columnEnds.last(), 1e-2f)
        assertEquals(widths.last(), result.columnEnds.last() - result.columnStarts.last(), 1e-4f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `negative residual from CPS punctuation squeeze compresses punctuation without shrinking spaces`() {
        val words = listOf("你", "好", " ", "w", "o", "r", "l", "d", "。", "”")
        val cnCharWidth = 40f
        val widths = listOf(40f, 40f, 12f, 24f, 20f, 14f, 10f, 20f, 40f, 40f)
        val residualWidth = -18f

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = 10f,
            residualWidth = residualWidth,
            textSize = 40f,
            cnCharWidth = cnCharWidth
        )

        assertTrue(result.hasCustomColumnLayout)
        assertEquals(0f, result.wordSpacing, 1e-6f)
        assertEquals(0f, result.extraLetterSpacing, 1e-6f)
        // Space at index 2 must retain exact natural width.
        assertEquals(widths[2], result.columnEnds[2] - result.columnStarts[2], 1e-4f)
        // Punctuation slots compressed within 35% cap and >= 0.55 * cnCharWidth.
        val periodWidth = result.columnEnds[8] - result.columnStarts[8]
        val quoteWidth = result.columnEnds[9] - result.columnStarts[9]
        assertTrue(periodWidth < widths[8])
        assertTrue(quoteWidth < widths[9])
        assertTrue(periodWidth >= cnCharWidth * 0.55f - 1e-3f)
        assertTrue(quoteWidth >= cnCharWidth * 0.55f - 1e-3f)
        assertEquals(10f + widths.sum() + residualWidth, result.columnEnds.last(), 1e-2f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `negative residual overflow beyond punctuation capacity preserves normal widths and spaces`() {
        val words = listOf("字", " ", "A", "字", "字", "字", "，", "”")
        val cnCharWidth = 40f
        val widths = listOf(40f, 12f, 24f, 40f, 40f, 40f, 40f, 40f)
        val residualWidth = -40f

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = 0f,
            residualWidth = residualWidth,
            textSize = 40f,
            cnCharWidth = cnCharWidth
        )

        assertTrue(result.hasCustomColumnLayout)
        // Normal Han/Latin columns and spaces keep exact natural widths without overlapping fallback.
        for (i in 0..5) {
            assertEquals("Column $i width changed", widths[i], result.columnEnds[i] - result.columnStarts[i], 1e-4f)
        }
        // Only punctuation slots compress up to budget (min(40 * 0.35, 40 - 22) = 14f each -> 26f).
        assertEquals(26f, result.columnEnds[6] - result.columnStarts[6], 1e-3f)
        assertEquals(26f, result.columnEnds[7] - result.columnStarts[7], 1e-3f)
        // Remaining 12f overflow stays unabsorbed so exceed() can flag it cleanly.
        assertEquals(widths.sum() - 28f, result.columnEnds.last(), 1e-2f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `negative residual without compressible punctuation leaves all columns at natural width`() {
        val words = listOf("测", "试", " ", "A", "B")
        val widths = listOf(40f, 40f, 12f, 24f, 24f)

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = 5f,
            residualWidth = -15f,
            textSize = 40f,
            cnCharWidth = 40f
        )

        assertFalse(result.hasCustomColumnLayout)
        for (i in words.indices) {
            assertEquals(widths[i], result.columnEnds[i] - result.columnStarts[i], 1e-4f)
        }
        assertEquals(5f + widths.sum(), result.columnEnds.last(), 1e-4f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `pure CJK justification distributes dChar and sets negative half offset`() {
        val words = listOf("天", "地", "玄", "黄", "宇", "宙", "洪", "荒")
        val widths = List(8) { 40f }
        val residualWidth = 28f
        val textSize = 40f

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = 20f,
            residualWidth = residualWidth,
            textSize = textSize,
            cnCharWidth = 40f
        )

        val expectedDChar = residualWidth / 7f
        assertEquals(0f, result.wordSpacing, 1e-6f)
        assertEquals(expectedDChar / textSize, result.extraLetterSpacing, 1e-5f)
        assertEquals(-expectedDChar / 2f, result.extraLetterSpacingOffsetX, 1e-4f)
        assertFalse(result.hasCustomColumnLayout)
        assertEquals(20f + widths.sum() + residualWidth, result.columnEnds.last(), 1e-2f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `near-zero residual uses sequential natural placement`() {
        val words = listOf("春", "眠", "不", "觉", "晓")
        val widths = listOf(40f, 40f, 40f, 40f, 40f)

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = 5f,
            residualWidth = 0.05f,
            textSize = 40f,
            cnCharWidth = 40f
        )

        assertEquals(0f, result.wordSpacing, 1e-6f)
        assertEquals(0f, result.extraLetterSpacing, 1e-6f)
        assertEquals(0f, result.extraLetterSpacingOffsetX, 1e-6f)
        assertFalse(result.hasCustomColumnLayout)
        assertEquals(205f, result.columnEnds.last(), 1e-4f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `justifyHtmlSpans preserves HiddenReviewColumn zero-width and ImageColumn width without rivers`() {
        // [A, " ", B, HiddenReview, Image, " ", C]
        val starts = floatArrayOf(10f, 50f, 62f, 102f, 102f, 152f, 164f)
        val ends = floatArrayOf(50f, 62f, 102f, 102f, 152f, 164f, 204f)
        val isSpace = booleanArrayOf(false, true, false, false, false, true, false)
        val isZeroWidthAnchor = booleanArrayOf(false, false, false, true, false, false, false)
        val isFixedWidth = booleanArrayOf(false, false, false, false, true, false, false)
        val lineWidth = 280
        val textSize = 40f
        val cnCharWidth = 40f

        val result = TypographyJustification.justifyHtmlSpans(
            starts = starts,
            ends = ends,
            isSpace = isSpace,
            isZeroWidthAnchor = isZeroWidthAnchor,
            isFixedWidth = isFixedWidth,
            lineWidth = lineWidth,
            textSize = textSize,
            cnCharWidth = cnCharWidth
        )

        // HiddenReviewColumn at index 3 must keep start == end.
        assertEquals(result.starts[3], result.ends[3], 1e-5f)
        // ImageColumn at index 4 must keep exact 50f width.
        assertEquals(50f, result.ends[4] - result.starts[4], 1e-4f)
        // Spaces capped at maxExtraSpace.
        val maxExtraSpace = maxOf(cnCharWidth * 0.55f, textSize * 0.45f)
        assertEquals(maxExtraSpace, result.wordSpacing, 1e-3f)
        assertTrue(result.extraLetterSpacing > 0f)
        // Conserves line width: first start (10f) + lineWidth (280f) = 290f.
        assertEquals(10f + lineWidth, result.ends.last(), 1e-2f)
        assertColumnInvariants(result.starts, result.ends)
    }

    @Test
    fun `justifyHtmlSpans with trailing HiddenReviewColumn aligns last visible column to lineWidth`() {
        val starts = floatArrayOf(0f, 40f, 80f, 120f)
        val ends = floatArrayOf(40f, 80f, 120f, 120f)
        val isSpace = booleanArrayOf(false, false, false, false)
        val isZeroWidthAnchor = booleanArrayOf(false, false, false, true)

        val result = TypographyJustification.justifyHtmlSpans(
            starts = starts,
            ends = ends,
            isSpace = isSpace,
            isZeroWidthAnchor = isZeroWidthAnchor,
            lineWidth = 160,
            textSize = 40f,
            cnCharWidth = 40f
        )

        assertEquals(result.starts[3], result.ends[3], 1e-5f)
        assertEquals(160f, result.ends[2], 1e-2f)
        assertEquals(160f, result.ends[3], 1e-2f)
        assertColumnInvariants(result.starts, result.ends)
    }

    @Test
    fun `justifyLine ending in hidden review char places last visible char at line end and keeps marker zero-width`() {
        val words = listOf("测", "试", "文", "字", "\u2060")
        val widths = listOf(40f, 40f, 40f, 40f, 0f)
        val startX = 10f
        val residualWidth = 30f
        val textSize = 40f

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = startX,
            residualWidth = residualWidth,
            textSize = textSize,
            cnCharWidth = 40f
        )

        val expectedDChar = residualWidth / 3f
        val expectedLineEnd = startX + widths.sum() + residualWidth
        assertEquals(expectedDChar / textSize, result.extraLetterSpacing, 1e-5f)
        assertEquals(-expectedDChar / 2f, result.extraLetterSpacingOffsetX, 1e-4f)
        assertEquals(expectedLineEnd, result.columnEnds[3], 1e-2f)
        assertEquals(expectedLineEnd, result.columnStarts[4], 1e-2f)
        assertEquals(result.columnStarts[4], result.columnEnds[4], 1e-5f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `justifyLine with middle hidden review char does not steal gap from visible chars`() {
        val words = listOf("天", "\u2060", "地", "人")
        val widths = listOf(40f, 0f, 40f, 40f)
        val startX = 0f
        val residualWidth = 20f
        val textSize = 40f

        val result = TypographyJustification.justifyLine(
            words = words,
            widths = widths,
            startX = startX,
            residualWidth = residualWidth,
            textSize = textSize,
            cnCharWidth = 40f
        )

        // Only 2 visible gaps ("天" -> "地", "地" -> "人").
        val expectedDChar = 10f
        assertEquals(expectedDChar / textSize, result.extraLetterSpacing, 1e-5f)
        assertEquals(50f, result.columnEnds[0], 1e-3f)
        assertEquals(50f, result.columnStarts[1], 1e-3f)
        assertEquals(result.columnStarts[1], result.columnEnds[1], 1e-5f)
        assertEquals(50f, result.columnStarts[2], 1e-3f)
        assertEquals(140f, result.columnEnds[3], 1e-2f)
        assertColumnInvariants(result.columnStarts, result.columnEnds)
    }

    @Test
    fun `isHtmlJustificationSpace matches ASCII and non-breaking spaces while excluding ideographic space`() {
        assertTrue(TypographyJustification.isHtmlJustificationSpace(" "))
        assertTrue(TypographyJustification.isHtmlJustificationSpace("\u00A0"))
        assertFalse(TypographyJustification.isHtmlJustificationSpace("\u3000"))
        assertFalse(TypographyJustification.isHtmlJustificationSpace("字"))
        assertFalse(TypographyJustification.isHtmlJustificationSpace(null))
    }

    @Test
    fun `justifyHtmlSpans expands non-breaking spaces when flagged as spaces`() {
        val starts = floatArrayOf(0f, 24f, 36f, 60f, 72f)
        val ends = floatArrayOf(24f, 36f, 60f, 72f, 96f)
        val chars = listOf("A", "\u00A0", "B", "\u00A0", "C")
        val isSpace = BooleanArray(chars.size) { TypographyJustification.isHtmlJustificationSpace(chars[it]) }
        val isZeroWidthAnchor = BooleanArray(chars.size)

        val result = TypographyJustification.justifyHtmlSpans(
            starts = starts,
            ends = ends,
            isSpace = isSpace,
            isZeroWidthAnchor = isZeroWidthAnchor,
            lineWidth = 120,
            textSize = 40f,
            cnCharWidth = 40f
        )

        assertEquals(12f, result.wordSpacing, 1e-3f)
        assertEquals(0f, result.extraLetterSpacing, 1e-6f)
        assertEquals(120f, result.ends.last(), 1e-2f)
        assertColumnInvariants(result.starts, result.ends)
    }
}
