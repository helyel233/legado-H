package io.legado.app.theme.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1-1 self-check (docs/ui-rewrite-plan-v4-impl.md A1-1):
 * pure function, deterministic, WCAG AA contrast on body text roles,
 * AMOLED = pure black background.
 */
class SeedPaletteTest {

    private val seed = 0xFF5B6ABF.toInt()

    @Test
    fun `generate covers every role`() {
        for (strategy in PaletteStrategy.entries) {
            for (dark in booleanArrayOf(false, true)) {
                val palette = SeedPalette.generate(seed, strategy, dark)
                assertEquals(
                    "missing roles for $strategy dark=$dark",
                    PaletteRole.entries.toSet(),
                    palette.keys,
                )
            }
        }
    }

    @Test
    fun `body text contrast meets WCAG AA in both modes`() {
        for (strategy in PaletteStrategy.entries) {
            for (dark in booleanArrayOf(false, true)) {
                val p = SeedPalette.generate(seed, strategy, dark)
                val bgText = SeedPalette.contrastRatio(p.getValue(PaletteRole.ON_BACKGROUND), p.getValue(PaletteRole.BACKGROUND))
                val surfaceText = SeedPalette.contrastRatio(p.getValue(PaletteRole.ON_SURFACE), p.getValue(PaletteRole.SURFACE))
                assertTrue("$strategy dark=$dark background contrast $bgText", bgText >= 4.5)
                assertTrue("$strategy dark=$dark surface contrast $surfaceText", surfaceText >= 4.5)
            }
        }
    }

    @Test
    fun `amoled dark background is pure black`() {
        val p = SeedPalette.generate(seed, PaletteStrategy.AMOLED, dark = true)
        assertEquals(0xFF000000.toInt(), p.getValue(PaletteRole.BACKGROUND))
        assertEquals(0xFF000000.toInt(), p.getValue(PaletteRole.SURFACE))
    }

    @Test
    fun `mono strategy is grayscale`() {
        val p = SeedPalette.generate(seed, PaletteStrategy.MONO, dark = false)
        fun isGray(argb: Int): Boolean {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return maxOf(r, g, b) - minOf(r, g, b) <= 2
        }
        assertTrue(isGray(p.getValue(PaletteRole.PRIMARY)))
        assertTrue(isGray(p.getValue(PaletteRole.BACKGROUND)))
    }

    @Test
    fun `pure function is deterministic`() {
        val a = SeedPalette.generate(seed, PaletteStrategy.TONAL, dark = true)
        val b = SeedPalette.generate(seed, PaletteStrategy.TONAL, dark = true)
        assertEquals(a, b)
    }

    @Test
    fun `different seeds diverge`() {
        val a = SeedPalette.generate(0xFF5B6ABF.toInt(), dark = false)
        val b = SeedPalette.generate(0xFF8D6E63.toInt(), dark = false)
        assertTrue(a.getValue(PaletteRole.PRIMARY) != b.getValue(PaletteRole.PRIMARY))
    }
}
