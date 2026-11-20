package io.legado.app.ui.config

import io.legado.app.help.config.ThemeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeEditDraftTest {

    @Test
    fun normalizeHex6_handlesShortAndArgbForms() {
        assertEquals("#AABBCC", ThemeEditDraft.normalizeHex6("#abc"))
        assertEquals("#112233", ThemeEditDraft.normalizeHex6("0xF123".replace("F123", "8123").let { "#8123" }).let {
            ThemeEditDraft.normalizeHex6("#F123")
        })
        assertEquals("#0A84FF", ThemeEditDraft.normalizeHex6("0A84FF"))
        assertEquals("#123456", ThemeEditDraft.normalizeHex6("#80123456"))
        assertNull(ThemeEditDraft.normalizeHex6("not_a_color"))
        assertNull(ThemeEditDraft.normalizeHex6("#12345"))
    }

    @Test
    fun jsonRoundTrip_preservesAllFieldsAndClampsOutOfRangeValues() {
        val original = ThemeEditDraft(
            themeName = "Slate Studio",
            isNightTheme = true,
            editingConfigNight = true,
            editingEntryDirName = "slate_studio",
            editingEntryNight = true,
            nameEditable = true,
            canDelete = true,
            activeGroupIndex = 99,
            advancedColorsExpanded = true,
            primaryColorHex = "#101216",
            accentColorHex = "#0A84FF",
            backgroundColorHex = "#121212",
            bottomBackgroundHex = "#1A1C20",
            blur = 42,
            mainBackgroundPath = "/tmp/bg.png",
            mainBackgroundCrop = "0,0,100,100",
            bookInfoBackgroundPath = "/tmp/book.png",
            panelBackgroundPath = "/tmp/panel.png",
            panelBackgroundScaleType = ThemeConfig.PANEL_BG_FIT,
            panelBorderColor = "#AABBCC",
            panelBorderAlpha = 150,
            uiCornerScale = 4.5f,
            uiLayoutAlpha = -10,
            dialogAlpha = 120,
            cardColor = "#202226",
            mutedColor = "#2B2D32",
            searchFieldBackgroundColor = null,
            tabBackgroundColor = "#303238",
            shelfColor = null,
            cardShadow = 30,
            cardBackgroundBlur = 28f,
            fontScale = 20,
            uiCornerSearchFollow = true,
            uiCornerReplyFollow = false,
            uiFontPath = "/fonts/ui.ttf",
            titleFontPath = "/fonts/title.ttf",
            uiFontColor = "#EAEAEA",
            titleFontColor = "#FFFFFF",
            fontTarget = ThemeEditFontTarget.TITLE
        )

        val restored = ThemeEditDraft.fromJson(original.toJson())
        assertNotNull(restored)
        restored!!
        assertEquals("Slate Studio", restored.themeName)
        assertEquals(3, restored.activeGroupIndex)
        assertEquals(25, restored.blur)
        assertEquals(100, restored.panelBorderAlpha)
        assertEquals(3f, restored.uiCornerScale, 0.001f)
        assertEquals(0, restored.uiLayoutAlpha)
        assertEquals(100, restored.dialogAlpha)
        assertEquals(24, restored.cardShadow)
        assertEquals(25f, restored.cardBackgroundBlur!!, 0.001f)
        assertEquals(16, restored.fontScale)
        assertEquals(3, restored.overriddenAdvancedColorCount())
        assertEquals(ThemeEditFontTarget.TITLE, restored.fontTarget)
        assertEquals(true, restored.uiCornerSearchFollow)
        assertEquals(false, restored.uiCornerReplyFollow)
    }

    @Test
    fun toThemeConfig_appliesBlankNameFallbackAndNormalizesColors() {
        val draft = ThemeEditDraft(
            themeName = "   ",
            isNightTheme = false,
            primaryColorHex = "#abc",
            accentColorHex = "invalid",
            backgroundColorHex = "#f6f7f9",
            bottomBackgroundHex = "#ffffff",
            panelBorderColor = "80334455",
            uiCornerScale = 1.5f,
            uiLayoutAlpha = 85,
            dialogAlpha = 90
        )
        val base = ThemeConfig.Config(
            themeName = "Old",
            isNightTheme = false,
            primaryColor = "#000000",
            accentColor = "#000000",
            backgroundColor = "#000000",
            bottomBackground = "#000000",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
        val out = draft.toThemeConfig(
            baseConfig = base,
            fallbackName = "Day",
            fallbackPrimaryHex = "#111111",
            fallbackAccentHex = "#222222",
            fallbackBackgroundHex = "#333333",
            fallbackBottomHex = "#444444"
        )
        assertEquals("Day", out.themeName)
        assertEquals("#AABBCC", out.primaryColor)
        assertEquals("#222222", out.accentColor)
        assertEquals("#F6F7F9", out.backgroundColor)
        assertEquals("#FFFFFF", out.bottomBackground)
        assertEquals("#334455", out.panelBorderColor)
        assertEquals(1.5f, out.uiCornerScale!!, 0.001f)
        assertEquals(85, out.uiLayoutAlpha)
        assertEquals(90, out.dialogAlpha)
        assertEquals(true, out.transparentNavBar)
    }
}
