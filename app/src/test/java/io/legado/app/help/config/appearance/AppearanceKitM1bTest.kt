package io.legado.app.help.config.appearance

import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.MainLayoutPresetConfig
import io.legado.app.help.config.TopBarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceKitM1bTest {

    @Test
    fun normalizeBuiltinKitId_mapsAllFourLegacyIdsAndBlankToBuiltinDefault() {
        val legacyIds = listOf(
            null,
            "",
            "   ",
            AppearanceKitManager.KIT_FLOATING,
            AppearanceKitManager.KIT_FLOATING_NO_SEARCH,
            AppearanceKitManager.KIT_REGULAR,
            AppearanceKitManager.KIT_SIDEBAR,
            BuiltinAppearanceDefaults.BUILTIN_DEFAULT_KIT_ID
        )
        legacyIds.forEach { raw ->
            assertEquals(
                BuiltinAppearanceDefaults.BUILTIN_DEFAULT_KIT_ID,
                AppearanceKitManager.normalizeBuiltinKitId(raw)
            )
        }
        assertEquals("custom_kit_1", AppearanceKitManager.normalizeBuiltinKitId("custom_kit_1"))
    }

    @Test
    fun isTopBarStyleMismatched_detectsCrossLayoutMismatch() {
        assertFalse(
            MainLayoutPresetConfig.isTopBarStyleMismatched(
                MainLayoutPresetConfig.PRESET_DEFAULT,
                TopBarConfig.STYLE_DEFAULT
            )
        )
        assertTrue(
            MainLayoutPresetConfig.isTopBarStyleMismatched(
                MainLayoutPresetConfig.PRESET_DEFAULT,
                TopBarConfig.STYLE_REGULAR
            )
        )
        assertTrue(
            MainLayoutPresetConfig.isTopBarStyleMismatched(
                MainLayoutPresetConfig.PRESET_REGULAR,
                TopBarConfig.STYLE_DEFAULT
            )
        )
        assertFalse(
            MainLayoutPresetConfig.isTopBarStyleMismatched(
                MainLayoutPresetConfig.PRESET_REGULAR,
                TopBarConfig.STYLE_REGULAR
            )
        )
    }
}
