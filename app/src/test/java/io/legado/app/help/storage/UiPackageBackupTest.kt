package io.legado.app.help.storage

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
            listOf("themePackages", "navigationBarPackages", "topBarPackages", "coverCollections"),
            UiPackageBackup.packageDirNames
        )
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
}
