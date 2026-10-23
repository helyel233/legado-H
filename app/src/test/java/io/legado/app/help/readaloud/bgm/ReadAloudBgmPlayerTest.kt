package io.legado.app.help.readaloud.bgm

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudBgmPlayerTest {

    @Test
    fun buildCueKey_formatsAsPipeDelimitedTriplet() {
        assertEquals("bookA|3|7", buildCueKey("bookA", 3, 7))
    }

    @Test
    fun buildCueKey_preservesBookUrlWithSlashes() {
        val url = "https://example.com/book/42"
        assertEquals("$url|0|0", buildCueKey(url, 0, 0))
    }

    @Test
    fun buildCueKey_negativeIndicesStillFormatted() {
        assertEquals("b|-1|-2", buildCueKey("b", -1, -2))
    }

    @Test
    fun computeVolume_fullScaleIsOne() {
        assertEquals(1f, computeVolume(1f, 1f, 1f), 1e-6f)
    }

    @Test
    fun computeVolume_halfAssignmentHalvesResult() {
        assertEquals(0.5f, computeVolume(0.5f, 1f, 1f), 1e-6f)
    }

    @Test
    fun computeVolume_scaleHalvesResult() {
        assertEquals(0.5f, computeVolume(1f, 1f, 0.5f), 1e-6f)
    }

    @Test
    fun computeVolume_trackAndAssignmentMultiply() {
        assertEquals(0.25f, computeVolume(0.5f, 0.5f, 1f), 1e-6f)
    }

    @Test
    fun computeVolume_clampsAboveOne() {
        assertEquals(1f, computeVolume(2f, 1f, 1f), 1e-6f)
        assertEquals(1f, computeVolume(1.5f, 0.8f, 1f), 1e-6f)
    }

    @Test
    fun computeVolume_clampsNegativeToZero() {
        assertEquals(0f, computeVolume(-1f, 1f, 1f), 1e-6f)
        assertEquals(0f, computeVolume(1f, -0.5f, 1f), 1e-6f)
    }

    @Test
    fun computeVolume_zeroScaleGivesZero() {
        assertEquals(0f, computeVolume(1f, 1f, 0f), 1e-6f)
    }
}
