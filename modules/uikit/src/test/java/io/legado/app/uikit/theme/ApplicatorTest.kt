package io.legado.app.uikit.theme

import io.legado.app.theme.palette.PaletteRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1-4 self-check: sandwich precedence, tweak marks, frozen reader roles.
 */
class ApplicatorTest {

    private fun reset() {
        Applicator.applyTheme(BuiltinThemes.sky)
        Applicator.applyWallpaperSeed(null)
        Applicator.applyDark(false)
        Applicator.clearAllTweaks()
    }

    @Test
    fun `default theme is sky and roles resolve`() {
        reset()
        val bg = Applicator.resolveColor(PaletteRole.BACKGROUND)
        assertTrue("background should be light in day mode: $bg", (bg shr 16) and 0xFF > 0xC0)
    }

    @Test
    fun `tweak overrides package and marks the role`() {
        reset()
        val before = Applicator.resolveColor(PaletteRole.SURFACE)
        assertFalse(Applicator.isTweaked(PaletteRole.SURFACE))
        Applicator.setTweak(PaletteRole.SURFACE, 0xFFFF00FF.toInt())
        assertEquals(0xFFFF00FF.toInt(), Applicator.resolveColor(PaletteRole.SURFACE))
        assertTrue(Applicator.isTweaked(PaletteRole.SURFACE))
        assertNotEquals(before, Applicator.resolveColor(PaletteRole.SURFACE))
        // single restore
        Applicator.clearTweak(PaletteRole.SURFACE)
        assertEquals(before, Applicator.resolveColor(PaletteRole.SURFACE))
        assertFalse(Applicator.isTweaked(PaletteRole.SURFACE))
    }

    @Test
    fun `tweak survives theme switch but clearAll restores package values`() {
        reset()
        Applicator.setTweak(PaletteRole.PRIMARY, 0xFFFF0000.toInt())
        Applicator.applyTheme(BuiltinThemes.paper)
        assertEquals(0xFFFF0000.toInt(), Applicator.resolveColor(PaletteRole.PRIMARY))
        Applicator.clearAllTweaks()
        val paperPrimary = Applicator.resolveColor(PaletteRole.PRIMARY)
        val expected = io.legado.app.theme.palette.SeedPalette.generate(0xFFA08A5B.toInt(), dark = false)
        assertEquals(expected.getValue(PaletteRole.PRIMARY), paperPrimary)
    }

    @Test
    fun `reader roles are frozen against themes and tweaks`() {
        reset()
        val readerText = Applicator.resolveScheme().readerText
        val readerBg = Applicator.resolveScheme().readerBackground
        Applicator.applyTheme(BuiltinThemes.amoled)
        Applicator.setTweak(PaletteRole.PRIMARY, 0xFFFF0000.toInt())
        Applicator.setTweak(PaletteRole.BACKGROUND, 0xFF00FF00.toInt())
        assertEquals(readerText, Applicator.resolveScheme().readerText)
        assertEquals(readerBg, Applicator.resolveScheme().readerBackground)
    }

    @Test
    fun `night mode flips scheme flag and darkens background`() {
        reset()
        val dayBg = Applicator.resolveColor(PaletteRole.BACKGROUND)
        Applicator.applyDark(true)
        val nightBg = Applicator.resolveColor(PaletteRole.BACKGROUND)
        assertTrue(Applicator.resolveScheme().isDark)
        assertTrue("night bg should be darker: $dayBg -> $nightBg", nightBg < dayBg)
    }

    @Test
    fun `tweaks snapshot round-trips through load`() {
        reset()
        Applicator.setTweak(PaletteRole.OUTLINE, 0xFF123456.toInt())
        val snap = Applicator.tweaksSnapshot()
        Applicator.clearAllTweaks()
        assertTrue(Applicator.tweaksSnapshot().isEmpty())
        Applicator.loadTweaks(snap)
        assertEquals(0xFF123456.toInt(), Applicator.resolveColor(PaletteRole.OUTLINE))
        // invalid saved entries are dropped, not crashed on
        Applicator.loadTweaks(mapOf("color.primary" to "#ABC", "nonsense" to "#FFF"))
        assertTrue(!Applicator.isTweaked(PaletteRole.PRIMARY))
    }
}
