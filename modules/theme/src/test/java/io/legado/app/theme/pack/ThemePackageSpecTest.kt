package io.legado.app.theme.pack

import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.palette.PaletteStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1-2 self-check: fault-tolerant parsing per plan rule "缺什么补什么".
 */
class ThemePackageSpecTest {

    private val full = """
    {
      "formatVersion": 1,
      "id": "hazel",
      "name": "榛子棕",
      "author": "zego",
      "seed": "#8D6E63",
      "darkStrategy": "amoled",
      "fonts": { "ui": "assets/ui.ttf", "title": "assets/title.ttf" },
      "images": { "background": "assets/bg.webp" },
      "override": { "color.primary": "#112233" },
      "someFutureField": { "whatever": 1 }
    }
    """.trimIndent()

    @Test
    fun `parse full spec`() {
        val r = ThemePackageSpec.parse(full)
        assertTrue(r is SpecParseResult.Ok)
        val spec = (r as SpecParseResult.Ok).spec
        assertEquals("hazel", spec.id)
        assertEquals("榛子棕", spec.name)
        assertEquals(0xFF8D6E63.toInt(), spec.seedArgb())
        assertEquals(PaletteStrategy.AMOLED, spec.strategy)
        assertEquals("assets/ui.ttf", spec.fonts?.ui)
        assertEquals(mapOf("color.primary" to "#112233"), spec.`override`)
        assertTrue(spec.isValid())
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val r = ThemePackageSpec.parse("""{"id":"x","name":"X","seed":"#5B6ABF"}""")
        val spec = (r as SpecParseResult.Ok).spec
        assertEquals(1, spec.formatVersion)
        assertEquals(PaletteStrategy.TONAL, spec.strategy)
        assertNull(spec.fonts)
        assertNull(spec.images)
        assertNull(spec.`override`)
    }

    @Test
    fun `unknown strategy falls back to tonal`() {
        val r = ThemePackageSpec.parse("""{"id":"x","name":"X","seed":"#5B6ABF","darkStrategy":"turbo"}""")
        assertEquals(PaletteStrategy.TONAL, (r as SpecParseResult.Ok).spec.strategy)
    }

    @Test
    fun `future formatVersion is rejected with friendly message`() {
        val r = ThemePackageSpec.parse("""{"formatVersion":2,"id":"x","name":"X","seed":"#5B6ABF"}""")
        assertTrue(r is SpecParseResult.Error)
        assertTrue((r as SpecParseResult.Error).message.contains("过新"))
    }

    @Test
    fun `broken seed is rejected`() {
        val r = ThemePackageSpec.parse("""{"id":"x","name":"X","seed":"#GGG"}""")
        assertTrue(r is SpecParseResult.Error)
    }

    @Test
    fun `malformed json is an error not a crash`() {
        assertTrue(ThemePackageSpec.parse("not json") is SpecParseResult.Error)
        assertTrue(ThemePackageSpec.parse("[1,2]") is SpecParseResult.Error)
    }

    @Test
    fun `color parser handles 6 and 8 digit hex`() {
        assertEquals(0xFF5B6ABF.toInt(), ThemePackageSpec.parseColor("#5B6ABF"))
        assertEquals(0x805B6ABF.toInt(), ThemePackageSpec.parseColor("#805B6ABF"))
        assertNull(ThemePackageSpec.parseColor(null))
        assertNull(ThemePackageSpec.parseColor("#12345"))
    }

    @Test
    fun `package override applies valid roles and skips bad entries`() {
        val r = ThemePackageSpec.parse(
            """
            {"id":"x","name":"X","seed":"#5B6ABF",
             "override":{"color.primary":"#112233","color.nonsense":"#FFFFFF","color.background":"bad","on_background":"#00FF00"}}
            """.trimIndent(),
        )
        val spec = (r as SpecParseResult.Ok).spec
        val p = spec.resolvePalette(dark = false)
        assertEquals(0xFF112233.toInt(), p[PaletteRole.PRIMARY])
        assertEquals(0xFF00FF00.toInt(), p[PaletteRole.ON_BACKGROUND])
        val plain = io.legado.app.theme.palette.SeedPalette.generate(0xFF5B6ABF.toInt(), dark = false)
        // invalid entries skipped → keep derived values
        assertEquals(plain[PaletteRole.BACKGROUND], p[PaletteRole.BACKGROUND])
    }

    @Test
    fun `wallpaper seed is accepted and falls back to sky when unresolved`() {
        val r = ThemePackageSpec.parse("""{"id":"dynamic","name":"动态取色","seed":"dynamic"}""")
        val spec = (r as SpecParseResult.Ok).spec
        assertTrue(spec.isValid())
        assertTrue(spec.isWallpaperSeed)
        // unresolved wallpaper seed → factory Sky fallback
        val p = spec.resolvePalette(dark = false)
        val sky = ThemePackageSpec.parse("""{"id":"sky","name":"晴空白","seed":"#5B6ABF"}""")
        assertEquals(
            (sky as SpecParseResult.Ok).spec.resolvePalette(dark = false)[PaletteRole.PRIMARY],
            p[PaletteRole.PRIMARY],
        )
        // resolved wallpaper seed is used as-is
        val p2 = spec.resolvePalette(dark = false, wallpaperSeed = 0xFF112233.toInt())
        val direct = io.legado.app.theme.palette.SeedPalette.generate(0xFF112233.toInt(), dark = false)
        assertEquals(direct[PaletteRole.PRIMARY], p2[PaletteRole.PRIMARY])
    }
}
