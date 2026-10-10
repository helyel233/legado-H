package io.legado.app.uikit.theme

import io.legado.app.theme.palette.PaletteRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2-4b self-check: two-layer resolve (factory defaults <- active package,
 * package override wins over derivation), frozen reader roles.
 */
class ApplicatorTest {

    private fun reset() {
        Applicator.applyTheme(BuiltinThemes.sky)
        Applicator.applyWallpaperSeed(null)
        Applicator.applyDark(false)
    }

    @Test
    fun `default theme is sky and roles resolve`() {
        reset()
        val bg = Applicator.resolveColor(PaletteRole.BACKGROUND)
        assertTrue("background should be light in day mode: " + bg, (bg shr 16) and 0xFF > 0xC0)
    }

    @Test
    fun `package override wins over derivation`() {
        reset()
        val before = Applicator.resolveColor(PaletteRole.SURFACE)
        val tweaked = BuiltinThemes.sky.copy(
            id = "u_test", name = "test", basedOn = "sky",
            override = mapOf("color.surface" to "#FFFF00FF"),
        )
        Applicator.applyTheme(tweaked)
        assertEquals(0xFFFF00FF.toInt(), Applicator.resolveColor(PaletteRole.SURFACE))
        Applicator.applyTheme(BuiltinThemes.sky)
        assertEquals(before, Applicator.resolveColor(PaletteRole.SURFACE))
    }

    @Test
    fun `switching packages changes derived values`() {
        reset()
        val skyPrimary = Applicator.resolveColor(PaletteRole.PRIMARY)
        Applicator.applyTheme(BuiltinThemes.paper)
        assertTrue(skyPrimary != Applicator.resolveColor(PaletteRole.PRIMARY))
    }

    @Test
    fun `reader roles are frozen against themes and overrides`() {
        reset()
        val readerText = Applicator.resolveScheme().readerText
        val readerBg = Applicator.resolveScheme().readerBackground
        Applicator.applyTheme(BuiltinThemes.amoled)
        Applicator.applyTheme(
            BuiltinThemes.sky.copy(
                id = "u_test", name = "t", basedOn = "sky",
                override = mapOf("color.background" to "#00FF00"),
            ),
        )
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
        assertTrue("night bg should be darker: " + dayBg + " -> " + nightBg, nightBg < dayBg)
    }

    @Test
    fun `wallpaper seed drives dynamic package`() {
        reset()
        Applicator.applyTheme(BuiltinThemes.dynamic)
        val fallback = Applicator.resolveColor(PaletteRole.PRIMARY)
        Applicator.applyWallpaperSeed(0xFF112233.toInt())
        val seeded = io.legado.app.theme.palette.SeedPalette.generate(0xFF112233.toInt(), dark = false)
        assertEquals(seeded.getValue(PaletteRole.PRIMARY), Applicator.resolveColor(PaletteRole.PRIMARY))
        assertTrue(fallback != Applicator.resolveColor(PaletteRole.PRIMARY))
    }
}
