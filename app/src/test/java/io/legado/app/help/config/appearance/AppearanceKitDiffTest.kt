package io.legado.app.help.config.appearance

import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.ComponentRef
import io.legado.app.help.config.KitBinding
import io.legado.app.help.config.KitGlassBinding
import io.legado.app.help.config.KitWelcomeBinding
import io.legado.app.help.config.StoredAppearanceKit
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceKitDiffTest {

    @Test
    fun identicalSnapshots_produceNoDirtySlots() {
        val base = AppearanceKitSnapshot()
        assertTrue(AppearanceKitDiff.computeDirtySlots(base, base).isEmpty())
    }

    @Test
    fun changingOnlyThemeAccentColor_marksThemeDirty() {
        val base = AppearanceKitSnapshot(
            dayTheme = ThemeSlotSnapshot(themeName = "MyTheme", dirName = "my_theme", accent = 0xFF112233.toInt())
        )
        val current = base.copy(
            dayTheme = base.dayTheme.copy(accent = 0xFF445566.toInt())
        )
        assertEquals(setOf(AppearanceSlot.THEME), AppearanceKitDiff.computeDirtySlots(current, base))
    }

    @Test
    fun changingCoverModeOrWelcomeShowTimeOrGlassPresetMatched_marksCorrespondingSlotsDirty() {
        val base = AppearanceKitSnapshot()
        val current = base.copy(
            dayCoverMode = "sequential",
            glass = base.glass.copy(presetMatched = false),
            welcome = base.welcome.copy(showTime = 500)
        )
        assertEquals(
            setOf(AppearanceSlot.COVER, AppearanceSlot.GLASS, AppearanceSlot.WELCOME),
            AppearanceKitDiff.computeDirtySlots(current, base)
        )
    }

    @Test
    fun changingHardwareBlurOrAnimatedSolid_marksGlassDirty() {
        val base = AppearanceKitSnapshot()
        val onlyHwBlur = base.copy(glass = base.glass.copy(hardwareBlur = false))
        val onlyAnimatedSolid = base.copy(glass = base.glass.copy(animatedSolid = false))

        assertEquals(setOf(AppearanceSlot.GLASS), AppearanceKitDiff.computeDirtySlots(onlyHwBlur, base))
        assertEquals(setOf(AppearanceSlot.GLASS), AppearanceKitDiff.computeDirtySlots(onlyAnimatedSolid, base))
    }

    @Test
    fun validateManifestVersion_acceptsV1AndV2AndRejectsUnknownVersions() {
        assertFalse(AppearanceKitManager.validateManifestVersion(0))
        assertTrue(AppearanceKitManager.validateManifestVersion(1))
        assertTrue(AppearanceKitManager.validateManifestVersion(2))
        assertFalse(AppearanceKitManager.validateManifestVersion(3))
    }

    @Test
    fun v1KitBindingJson_deserializesWithNullGlassAndWelcome() {
        val v1Json = """
            {
              "preset": "default",
              "dayTheme": {"dirName": "t1", "name": "Theme1"}
            }
        """.trimIndent()
        val binding = GSON.fromJson(v1Json, KitBinding::class.java)
        assertEquals("t1", binding.dayTheme?.dirName)
        assertNull(binding.glass)
        assertNull(binding.welcome)
    }

    @Test
    fun v2KitBindingMergeImported_preservesGlassAndWelcome() {
        val base = KitBinding(
            glass = KitGlassBinding(mode = "glass", frostedLevel = 60),
            welcome = KitWelcomeBinding(showTime = 300)
        )
        val imported = KitBinding(
            dayTheme = ComponentRef("t2", "Theme2")
        )
        val merged = base.mergeImported(imported)
        assertEquals("t2", merged.dayTheme?.dirName)
        assertEquals(60, merged.glass?.frostedLevel)
        assertEquals(300, merged.welcome?.showTime)
    }

    @Test
    fun reconcileStoredKits_prunesMissingComponentsAndDeadKits() {
        val aliveKit = StoredAppearanceKit(
            id = "k1",
            name = "Alive",
            binding = KitBinding(
                dayTheme = ComponentRef("theme_ok", "OK"),
                dayTopBar = ComponentRef("topbar_missing", "Missing")
            )
        )
        val deadKit = StoredAppearanceKit(
            id = "k2",
            name = "Dead",
            binding = KitBinding(
                nightTheme = ComponentRef("theme_missing", "Gone")
            )
        )
        val result = AppearanceKitManager.reconcileStoredKits(
            kits = listOf(aliveKit, deadKit),
            dayThemeDirs = setOf("theme_ok"),
            nightThemeDirs = emptySet(),
            dayTopBarDirs = setOf("default"),
            nightTopBarDirs = setOf("default"),
            dayNavBarDirs = setOf("default"),
            nightNavBarDirs = setOf("default"),
            dayCoverIds = emptySet(),
            nightCoverIds = emptySet()
        )
        assertEquals(1, result.size)
        assertEquals("k1", result[0].id)
        assertEquals("theme_ok", result[0].binding.dayTheme?.dirName)
        assertNull(result[0].binding.dayTopBar)
    }
}
