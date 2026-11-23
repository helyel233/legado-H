package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 锁住「备份端导出的 UI 美术资产包目录 == 恢复端读取的目录」这一对称性。
 *
 * 恢复端 Restore.restoreThemePackages / restoreNavigationIcons / restoreTopBarPackages /
 * restoreCoverCollections 与备份端共用 UiPackageBackup 的目录名常量；本测试固定这些常量的
 * 字面值，防止任一改版把常量改错导致备份写 A 目录、恢复读 B 目录而静默丢数据。
 */
class UiPackageBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun packageDirNamesMatchRestoreContract() {
        assertEquals(
            listOf(
                "themePackages",
                "navigationBarPackages",
                "topBarPackages",
                "coverCollections",
                "appThemeKits",
                "unifiedPackages"
            ),
            UiPackageBackup.packageDirNames
        )
    }

    @Test
    fun copyUnifiedPackages_copiesArtAndParamAndSkipsStagingOrBuiltin() {
        val source = temporary.newFolder("unified_src")
        val target = File(temporary.root, "unified_dst")

        File(source, "art/ink_art/manifest.json").apply {
            parentFile?.mkdirs()
            writeText("""{"kind":"art","id":"ink_art","name":"水墨"}""")
        }
        File(source, "art/.staging-123/manifest.json").apply {
            parentFile?.mkdirs()
            writeText("staging")
        }
        File(source, "param/glass_param/manifest.json").apply {
            parentFile?.mkdirs()
            writeText("""{"kind":"param","id":"glass_param","name":"液态参数"}""")
        }

        UiPackageBackup.copyUnifiedPackages(source, target)

        assertTrue(File(target, "art/ink_art/manifest.json").isFile)
        assertFalse(File(target, "art/.staging-123").exists())
        assertTrue(File(target, "param/glass_param/manifest.json").isFile)
    }

    @Test
    fun visibleKeepsUserPackagesAndDropsCacheBuiltinAndHidden() {
        // 用户创建/导入的包：保留
        assertTrue(UiPackageBackup.dirVisible("my-theme"))
        assertTrue(UiPackageBackup.dirVisible("红读主题"))
        assertTrue(UiPackageBackup.dirVisible("theme_2026"))

        // 临时/缓存/内置/隐藏：排除
        assertFalse(UiPackageBackup.dirVisible("temp"))
        assertFalse(UiPackageBackup.dirVisible("remote_cache"))
        assertFalse(UiPackageBackup.dirVisible("appearanceKitTemp"))
        assertFalse(UiPackageBackup.dirVisible("builtin_day"))
        assertFalse(UiPackageBackup.dirVisible("builtin_night"))
        assertFalse(UiPackageBackup.dirVisible("default"))
        assertFalse(UiPackageBackup.dirVisible(".staging-abc"))
        assertFalse(UiPackageBackup.dirVisible(""))
        assertFalse(UiPackageBackup.dirVisible("   "))
    }

    @Test
    fun copyVisiblePackagesExportsUserPackagesWithNestedFiles() {
        val source = temporary.newFolder("themePackages")
        val pkg = File(source, "my-theme").apply { mkdirs() }
        File(pkg, "theme.json").writeText("{\"name\":\"my-theme\"}")
        File(pkg, "assets").mkdirs()
        File(pkg, "assets/bg.png").writeBytes(byteArrayOf(1, 2, 3))
        val tempDir = File(source, "temp").apply { mkdirs() }
        File(tempDir, "junk").writeText("x")
        val cacheDir = File(source, "remote_cache").apply { mkdirs() }
        File(cacheDir, "cache").writeText("x")
        val builtinDir = File(source, "builtin_day").apply { mkdirs() }
        File(builtinDir, "builtin").writeText("x")
        val target = File(temporary.root, "backup/themePackages")

        UiPackageBackup.copyVisiblePackages(source, target)

        assertEquals("{\"name\":\"my-theme\"}", File(target, "my-theme/theme.json").readText())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(File(target, "my-theme/assets/bg.png").readBytes()))
        assertEquals(setOf("my-theme"), target.list()!!.toSet())
    }

    @Test
    fun copyVisiblePackagesWithMissingSourceIsNoOp() {
        val absent = File(temporary.root, "absent")
        val target = File(temporary.root, "backup/absent")
        UiPackageBackup.copyVisiblePackages(absent, target)
        assertFalse(target.exists())
    }

    @Test
    fun copyVisiblePackagesWithNoVisibleChildCreatesNothing() {
        val source = temporary.newFolder("topBarPackages")
        File(source, "temp").mkdirs()
        File(source, "remote_cache").mkdirs()
        val target = File(temporary.root, "backup/topBarPackages")
        UiPackageBackup.copyVisiblePackages(source, target)
        assertFalse(target.exists())
    }

    @Test
    fun copyVisiblePackagesAndFilesIncludesKitsJsonAndSkipsTemp() {
        val source = temporary.newFolder("appThemeKits")
        File(source, "kits.json").writeText("[{\"id\":\"k1\"}]")
        File(source, "appearanceKitTemp").apply { mkdirs() }.let {
            File(it, "tmp.zip").writeText("tmp")
        }
        val target = File(temporary.root, "backup/appThemeKits")

        UiPackageBackup.copyVisiblePackagesAndFiles(source, target)

        assertEquals("[{\"id\":\"k1\"}]", File(target, "kits.json").readText())
        assertFalse(File(target, "appearanceKitTemp").exists())
    }

    @Test
    fun copyUnifiedPackages_includesCoverSlotAndSkipsBuiltinAndStaging() {
        val source = temporary.newFolder("unified_cover_src")
        val target = File(temporary.root, "unified_cover_dst")

        File(source, "cover_slot/my_covers/manifest.json").apply {
            parentFile?.mkdirs()
            writeText("""{"kind":"cover_slot","id":"cover_slot_custom","name":"自定义书架封面"}""")
        }
        File(source, "cover_slot/my_covers/assets/cover_01.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        File(source, "cover_slot/builtin_default/manifest.json").apply {
            parentFile?.mkdirs()
            writeText("""{"kind":"cover_slot","id":"cover_slot_builtin_default","name":"原生书架封面"}""")
        }
        File(source, "cover_slot/.staging-tmp/manifest.json").apply {
            parentFile?.mkdirs()
            writeText("staging")
        }

        UiPackageBackup.copyUnifiedPackages(source, target)

        assertTrue(File(target, "cover_slot/my_covers/manifest.json").isFile)
        assertTrue(File(target, "cover_slot/my_covers/assets/cover_01.jpg").isFile)
        assertFalse(File(target, "cover_slot/builtin_default").exists())
        assertFalse(File(target, "cover_slot/.staging-tmp").exists())
    }

    @Test
    fun unifiedActivePrefs_includeCoverSlotKeysAndRoundTrip() {
        assertTrue(PreferKey.activeDayCoverSlotPackageId in UiPackageBackup.unifiedActivePrefKeys)
        assertTrue(PreferKey.activeNightCoverSlotPackageId in UiPackageBackup.unifiedActivePrefKeys)
        assertTrue(PreferKey.currentCoverSlotPackageId in UiPackageBackup.unifiedActivePrefKeys)
        assertTrue(PreferKey.coverSlotLocked in UiPackageBackup.unifiedActivePrefKeys)

        val backupDir = temporary.newFolder("unified_prefs_backup")
        val sourceStrings = mapOf(
            PreferKey.activeDayArtPackageId to "art_day_1",
            PreferKey.activeNightArtPackageId to "art_night_1",
            PreferKey.activeDayCoverSlotPackageId to "cover_slot_day_1",
            PreferKey.activeNightCoverSlotPackageId to "cover_slot_night_1",
            PreferKey.currentCoverSlotPackageId to "cover_slot_day_1"
        )
        val sourceBools = mapOf(
            PreferKey.coverSlotLocked to true
        )

        UiPackageBackup.exportUnifiedActivePrefs(
            targetRoot = backupDir,
            readString = { sourceStrings[it] },
            readBoolean = { sourceBools[it] }
        )

        assertTrue(File(backupDir, UiPackageBackup.UNIFIED_ACTIVE_PREFS_FILE).isFile)

        val restoredStrings = mutableMapOf<String, String>()
        val restoredBools = mutableMapOf<String, Boolean>()
        val ok = UiPackageBackup.importUnifiedActivePrefs(
            sourceRoot = backupDir,
            writeString = { k, v -> restoredStrings[k] = v },
            writeBoolean = { k, v -> restoredBools[k] = v }
        )

        assertTrue(ok)
        assertEquals(sourceStrings, restoredStrings)
        assertEquals(sourceBools, restoredBools)
    }
}
