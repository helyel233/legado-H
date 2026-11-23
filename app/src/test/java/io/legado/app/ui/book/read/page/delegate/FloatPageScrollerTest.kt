package io.legado.app.ui.book.read.page.delegate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class FloatPageScrollerTest {

    @Test
    fun `120Hz increments produce strictly monotonic sub-pixel currX without integer stalls`() {
        var nowMs = 1000L
        val scroller = FloatPageScroller(
            progressCurve = { t -> 1f - (1f - t) * (1f - t) },
            timeSourceMs = { nowMs }
        )

        // Small distance over 300ms at 120Hz (8.33ms per frame) would stall repeatedly on integer Scroller.
        scroller.startScroll(startX = 10.5f, startY = 0f, dx = 120f, dy = 0f, duration = 300)
        assertFalse(scroller.isFinished)

        val sampledX = mutableListOf<Float>()
        var frame = 1
        while (true) {
            nowMs = 1000L + (frame * (1000.0 / 120.0)).roundToLong()
            if (!scroller.computeScrollOffset()) break
            sampledX += scroller.currX
            frame++
        }

        assertTrue("Expected ~36 frames at 120Hz for 300ms, got ${sampledX.size}", sampledX.size >= 35)
        for (i in 1 until sampledX.size) {
            assertTrue(
                "Expected strictly increasing currX at frame $i: prev=${sampledX[i - 1]}, curr=${sampledX[i]}",
                sampledX[i] > sampledX[i - 1]
            )
        }
        assertTrue(
            "Expected sub-pixel fractional currX values during 120Hz animation",
            sampledX.any { it != it.toInt().toFloat() }
        )
        assertEquals(130.5f, sampledX.last(), 0.0001f)
        assertTrue(scroller.isFinished)
    }

    @Test
    fun `120Hz negative dx produces strictly decreasing sub-pixel currX`() {
        var nowMs = 500L
        val scroller = FloatPageScroller(timeSourceMs = { nowMs })
        scroller.startScroll(startX = 900.25f, startY = 0f, dx = -900.25f, dy = 0f, duration = 300)

        var prevX = Float.POSITIVE_INFINITY
        var frame = 1
        while (true) {
            nowMs = 500L + (frame * (1000.0 / 120.0)).roundToLong()
            if (!scroller.computeScrollOffset()) break
            assertTrue(scroller.currX < prevX)
            prevX = scroller.currX
            frame++
        }
        assertEquals(0f, scroller.currX, 0.0001f)
        assertTrue(scroller.isFinished)
    }

    @Test
    fun `custom curve easing shapes intermediate progress`() {
        var nowMs = 0L
        val easeOutCurve: (Float) -> Float = { t -> 1f - (1f - t) * (1f - t) }
        val scroller = FloatPageScroller(progressCurve = easeOutCurve, timeSourceMs = { nowMs })

        scroller.startScroll(startX = 0f, startY = 0f, dx = 1000f, dy = 200f, duration = 400)

        nowMs = 200L // t = 0.5 -> easeOut = 0.75
        assertTrue(scroller.computeScrollOffset())
        assertEquals(750f, scroller.currX, 0.01f)
        assertEquals(150f, scroller.currY, 0.01f)
        assertFalse(scroller.isFinished)

        nowMs = 400L
        assertTrue(scroller.computeScrollOffset())
        assertEquals(1000f, scroller.currX, 0.0001f)
        assertEquals(200f, scroller.currY, 0.0001f)
        assertTrue(scroller.isFinished)
        assertFalse(scroller.computeScrollOffset())
    }

    @Test
    fun `abortAnimation snaps to final coordinates and marks finished`() {
        var nowMs = 100L
        val scroller = FloatPageScroller(timeSourceMs = { nowMs })
        scroller.startScroll(startX = 120f, startY = 40f, dx = 500f, dy = -20f, duration = 300)

        nowMs = 150L
        assertTrue(scroller.computeScrollOffset())
        assertFalse(scroller.isFinished)

        scroller.abortAnimation()
        assertTrue(scroller.isFinished)
        assertEquals(620f, scroller.currX, 0.0001f)
        assertEquals(20f, scroller.currY, 0.0001f)
        assertFalse(scroller.computeScrollOffset())
    }

    @Test
    fun `zero delta completes immediately without running frames`() {
        var nowMs = 0L
        val scroller = FloatPageScroller(timeSourceMs = { nowMs })
        scroller.startScroll(startX = 333.5f, startY = 44.25f, dx = 0f, dy = 0f, duration = 300)

        assertTrue(scroller.isFinished)
        assertEquals(333.5f, scroller.currX, 0.0001f)
        assertEquals(44.25f, scroller.currY, 0.0001f)
        assertFalse(scroller.computeScrollOffset())
    }
}
