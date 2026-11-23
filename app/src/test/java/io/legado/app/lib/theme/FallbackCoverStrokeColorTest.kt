package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FallbackCoverStrokeColorTest {

    @Test
    fun `transparent background falls back to opaque surface color when wallpaper is active`() {
        val resolved = resolveFallbackCoverStrokeColor(
            backgroundColor = 0x00000000,
            surfaceFallbackColor = 0xFFF4ECD8.toInt(),
            isNight = false
        )

        assertEquals(0xFFF4ECD8.toInt(), resolved)
        assertEquals(0xFF, resolved ushr 24)
        assertNotEquals(0, resolved ushr 24)
    }

    @Test
    fun `transparent background and transparent surface fallback never return transparent`() {
        val dayResolved = resolveFallbackCoverStrokeColor(
            backgroundColor = 0x00000000,
            surfaceFallbackColor = 0x00000000,
            isNight = false
        )
        val nightResolved = resolveFallbackCoverStrokeColor(
            backgroundColor = 0x00000000,
            surfaceFallbackColor = 0x00112233,
            isNight = true
        )

        assertEquals(0xFFFFFFFF.toInt(), dayResolved)
        assertEquals(0xFF121212.toInt(), nightResolved)
        assertEquals(0xFF, dayResolved ushr 24)
        assertEquals(0xFF, nightResolved ushr 24)
    }

    @Test
    fun `translucent surface or background is forced to full alpha`() {
        val fromTranslucentBg = resolveFallbackCoverStrokeColor(
            backgroundColor = 0x80223344.toInt(),
            surfaceFallbackColor = 0xFFFFFFFF.toInt(),
            isNight = false
        )
        val fromTranslucentSurface = resolveFallbackCoverStrokeColor(
            backgroundColor = 0x00000000,
            surfaceFallbackColor = 0x70223344,
            isNight = true
        )

        assertEquals(0xFF223344.toInt(), fromTranslucentBg)
        assertEquals(0xFF223344.toInt(), fromTranslucentSurface)
    }

    @Test
    fun `opaque background color is preserved verbatim`() {
        val bg = 0xFF1E293B.toInt()
        val resolved = resolveFallbackCoverStrokeColor(
            backgroundColor = bg,
            surfaceFallbackColor = 0xFF0F172A.toInt(),
            isNight = true
        )

        assertEquals(bg, resolved)
    }
}
