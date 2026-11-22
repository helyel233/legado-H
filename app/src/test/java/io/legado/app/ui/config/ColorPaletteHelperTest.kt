package io.legado.app.ui.config

import io.legado.app.help.config.pkg.ArtPackagePayload
import io.legado.app.help.config.pkg.ArtPaletteSpec
import io.legado.app.help.config.pkg.ArtTypographySpec
import io.legado.app.help.config.pkg.ArtWallpapersSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPaletteHelperTest {

    @Test
    fun parseColorInput_handlesHexVariantsAndMatchesThemeEditDraftOpaqueNormalization() {
        assertEquals(0xFFFF0000.toInt(), ColorPaletteHelper.parseColorInput("#f00"))
        // 4-digit & 8-digit hex default to opaque RGB matching ThemeEditDraft.normalizeHex6
        assertEquals(0xFFFF0000.toInt(), ColorPaletteHelper.parseColorInput("#8f00"))
        assertEquals(0x88FF0000.toInt(), ColorPaletteHelper.parseColorInput("#8f00", preserveAlpha = true))
        assertEquals(0xFF1E88E5.toInt(), ColorPaletteHelper.parseColorInput(" #1e88e5 "))
        assertEquals(0xFF1E88E5.toInt(), ColorPaletteHelper.parseColorInput("#801E88E5"))
        assertEquals(0x801E88E5.toInt(), ColorPaletteHelper.parseColorInput("#801E88E5", preserveAlpha = true))
        assertEquals(0xFF1E88E5.toInt(), ColorPaletteHelper.parseColorInput("1E88E5"))

        assertNull(ColorPaletteHelper.parseColorInput(null))
        assertNull(ColorPaletteHelper.parseColorInput(""))
        assertNull(ColorPaletteHelper.parseColorInput("   "))
        assertNull(ColorPaletteHelper.parseColorInput("#12"))
        assertNull(ColorPaletteHelper.parseColorInput("#GGGGGG"))
        assertNull(ColorPaletteHelper.parseColorInput("-16777216", allowDecimalArgb = false))
    }

    @Test
    fun parseColorInput_handlesDecimalArgbWhenEnabled() {
        val opaqueBlack = 0xFF000000.toInt()
        assertEquals(opaqueBlack, ColorPaletteHelper.parseColorInput(opaqueBlack.toString(), allowDecimalArgb = true))
        assertEquals(0x00FF0000, ColorPaletteHelper.parseColorInput("16711680", allowDecimalArgb = true))
        assertEquals("#000000", ColorPaletteHelper.formatHex6(opaqueBlack))
    }

    @Test
    fun rgbAndHsv_roundTripMatchesOriginalRgbChannels() {
        val samples = listOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFFFF00FF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF808080.toInt(),
            0xFF1E88E5.toInt(),
            0xFF795548.toInt()
        )
        samples.forEach { argb ->
            val (h, s, v) = ColorPaletteHelper.rgbToHsv(argb)
            val roundTripped = ColorPaletteHelper.hsvToArgb(h, s, v)
            assertEquals("Mismatch for ${ColorPaletteHelper.formatHex6(argb)}", argb, roundTripped)
        }
    }

    @Test
    fun curatedPaletteAndContrast_handleFamiliesLowSaturationAndContrast() {
        assertEquals(18, ColorPaletteHelper.materialFamilies.size)
        assertEquals(10, ColorPaletteHelper.neutralSwatches.size)
        ColorPaletteHelper.materialFamilies.forEach { family ->
            assertEquals(10, family.shades.size)
            assertTrue(family.primaryArgb in family.shades)
        }
        val blueIdx = ColorPaletteHelper.findNearestFamilyIndex(0xFF1E88E5.toInt())
        assertEquals("蓝色", ColorPaletteHelper.materialFamilies[blueIdx].name)

        // Low-saturation neutrals fall back to Blue Grey ("蓝灰")
        assertEquals("蓝灰", ColorPaletteHelper.materialFamilies[ColorPaletteHelper.findNearestFamilyIndex(0xFFFFFFFF.toInt())].name)
        assertEquals("蓝灰", ColorPaletteHelper.materialFamilies[ColorPaletteHelper.findNearestFamilyIndex(0xFF808080.toInt())].name)
        assertEquals("蓝灰", ColorPaletteHelper.materialFamilies[ColorPaletteHelper.findNearestFamilyIndex(0xFF000000.toInt())].name)

        // Contrast helper picks black on light surfaces and white on dark surfaces
        assertEquals(0xFF000000.toInt(), ColorPaletteHelper.contrastOnColor(0xFFFFFFFF.toInt()))
        assertEquals(0xFF000000.toInt(), ColorPaletteHelper.contrastOnColor(0xFFFFEB3B.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorPaletteHelper.contrastOnColor(0xFF000000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorPaletteHelper.contrastOnColor(0xFF1A237E.toInt()))
    }

    @Test
    fun artSlotEdit_loadsAndSavesDayOrNightIndependently() {
        val day = ArtPaletteSpec(primaryColor = "#795548", panelBorderColor = "#D7CCC8")
        val night = ArtPaletteSpec(primaryColor = "#37474F", panelBorderColor = "#546E7A")
        val art = ArtPackagePayload(
            dayPalette = day,
            nightPalette = night,
            dayWallpapers = ArtWallpapersSpec(main = "assets/day.jpg"),
            nightWallpapers = ArtWallpapersSpec(main = "assets/night.jpg"),
            dayTypography = ArtTypographySpec(uiFontPath = "assets/day.ttf"),
            nightTypography = ArtTypographySpec(uiFontPath = "assets/night.ttf")
        )

        val nightSeed = ColorPaletteHelper.resolveArtSlotForEdit(art, preferNightSlot = true)
        assertEquals("#37474F", nightSeed.palette.primaryColor)
        assertEquals("#546E7A", nightSeed.palette.panelBorderColor)
        assertEquals("assets/night.jpg", nightSeed.wallpapers.main)

        val savedNight = ColorPaletteHelper.applyArtSlotEdit(
            original = art,
            preferNightSlot = true,
            updatedPalette = nightSeed.palette.copy(panelBorderColor = "#FF5722"),
            updatedWallpapers = nightSeed.wallpapers,
            updatedTypography = nightSeed.typography,
            defaultCoverPath = ""
        )
        assertNotNull(savedNight.dayPalette)
        assertEquals("#D7CCC8", savedNight.dayPalette?.panelBorderColor)
        assertEquals("#FF5722", savedNight.nightPalette?.panelBorderColor)
        assertNull(savedNight.defaultCoverPath)

        val daySeed = ColorPaletteHelper.resolveArtSlotForEdit(art, preferNightSlot = false)
        assertEquals("#795548", daySeed.palette.primaryColor)
        val savedDay = ColorPaletteHelper.applyArtSlotEdit(
            original = art,
            preferNightSlot = false,
            updatedPalette = daySeed.palette.copy(panelBorderColor = "#4CAF50"),
            updatedWallpapers = daySeed.wallpapers,
            updatedTypography = daySeed.typography,
            defaultCoverPath = null
        )
        assertEquals("#4CAF50", savedDay.dayPalette?.panelBorderColor)
        assertEquals("#546E7A", savedDay.nightPalette?.panelBorderColor)
        assertEquals("assets/night.jpg", savedDay.nightWallpapers?.main)
    }

    @Test
    fun artSlotEdit_canSplitPreviouslyIdenticalSlotsOrSyncWhenRequested() {
        val shared = ArtPaletteSpec(primaryColor = "#2E7D32", panelBorderColor = "#A5D6A7")
        val art = ArtPackagePayload(
            dayPalette = shared,
            nightPalette = shared,
            dayWallpapers = ArtWallpapersSpec(main = "assets/shared.jpg"),
            nightWallpapers = ArtWallpapersSpec(main = "assets/shared.jpg")
        )

        val seed = ColorPaletteHelper.resolveArtSlotForEdit(art, preferNightSlot = true)
        // Default syncBothSlots = false splits the previously identical night slot without mutating day slot
        val splitSaved = ColorPaletteHelper.applyArtSlotEdit(
            original = art,
            preferNightSlot = true,
            updatedPalette = seed.palette.copy(panelBorderColor = "#4CAF50"),
            updatedWallpapers = seed.wallpapers,
            updatedTypography = seed.typography,
            defaultCoverPath = null,
            syncBothSlots = false
        )
        assertEquals("#A5D6A7", splitSaved.dayPalette?.panelBorderColor)
        assertEquals("#4CAF50", splitSaved.nightPalette?.panelBorderColor)

        // Explicit syncBothSlots = true updates both slots together
        val syncedSaved = ColorPaletteHelper.applyArtSlotEdit(
            original = art,
            preferNightSlot = true,
            updatedPalette = seed.palette.copy(panelBorderColor = "#FF9800"),
            updatedWallpapers = seed.wallpapers,
            updatedTypography = seed.typography,
            defaultCoverPath = null,
            syncBothSlots = true
        )
        assertEquals("#FF9800", syncedSaved.dayPalette?.panelBorderColor)
        assertEquals("#FF9800", syncedSaved.nightPalette?.panelBorderColor)
    }
}
