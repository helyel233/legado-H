package io.legado.app.help.config.pkg

import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.TopBarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPackageMigratorTest {

    @Test
    fun disassembleLegacyBundle_separatesSensoryAssetsFromNumericParams() {
        val dayTheme = ThemeConfig.Config(
            themeName = "Jiangnan",
            isNightTheme = false,
            primaryColor = "#2E7D32",
            accentColor = "#00897B",
            backgroundColor = "#F1F8E9",
            bottomBackground = "#DCEDC8",
            transparentNavBar = true,
            backgroundImgPath = "/tmp/day_bg.jpg",
            backgroundImgBlur = 12,
            panelBackgroundImgPath = "/tmp/panel.png",
            uiCornerScale = 1.4f,
            uiLayoutAlpha = 85,
            dialogAlpha = 92,
            fontScale = 2,
            uiFontPath = "/tmp/font.ttf"
        )
        val topBar = TopBarConfig.Config(
            name = "Jiangnan",
            isNightMode = false,
            wallpaperPath = "/tmp/topbar.jpg",
            wallpaperAlpha = 75
        )

        val bundle = LegacyPackageMigrator.disassembleLegacyBundle(
            dayTheme = dayTheme,
            dayTopBar = topBar,
            dayIconSlotId = "ink_icons",
            dayCoverSlotId = "ink_covers"
        )

        // Art gets colors, wallpapers (including topBar), fonts, and mounts
        assertEquals("#2E7D32", bundle.art.dayPalette?.primaryColor)
        assertEquals("/tmp/day_bg.jpg", bundle.art.dayWallpapers?.main)
        assertEquals("/tmp/topbar.jpg", bundle.art.dayWallpapers?.topBar)
        assertEquals(75, bundle.art.dayWallpapers?.topBarAlpha)
        assertEquals("/tmp/panel.png", bundle.art.dayWallpapers?.panel)
        assertEquals("/tmp/font.ttf", bundle.art.dayTypography?.uiFontPath)
        assertEquals("ink_icons", bundle.art.mounts.dayIconSlotId)
        assertEquals("ink_covers", bundle.art.mounts.dayCoverSlotId)

        // Param gets numeric corner/alpha/blur/fontScale
        assertNotNull(bundle.param)
        assertEquals(1.4f, bundle.param!!.surfaceGlobal?.cornerBaseScale ?: 0f, 0.001f)
        assertEquals(85, bundle.param!!.surfaceGlobal?.surfaceAlpha)
        assertEquals(92, bundle.param!!.surfaceGlobal?.dialogAlpha)
        assertEquals(12, bundle.param!!.surfaceGlobal?.wallpaperBlurDay)
        assertEquals(2, bundle.param!!.layout?.fontScale)
        assertNull(bundle.param!!.layout?.navigationPreset)
    }
}
