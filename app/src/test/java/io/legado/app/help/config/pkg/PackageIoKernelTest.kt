package io.legado.app.help.config.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageIoKernelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun atomicWriteAndBackupRecovery_restoresFromBackupWhenTargetMissing() {
        val dir = tempFolder.newFolder("atomic")
        val target = File(dir, "manifest.json")
        PackageIoKernel.writeTextAtomic(target, """{"v":1}""")
        assertEquals("""{"v":1}""", PackageIoKernel.readTextWithRecovery(target, 4096L))

        // Simulate crash mid-replace: backup exists, target deleted, staging leftover
        val backup = File(dir, ".manifest.json.backup")
        val staging = File(dir, ".manifest.json.staging")
        assertTrue(target.renameTo(backup))
        staging.writeText("corrupted")

        val recovered = PackageIoKernel.readTextWithRecovery(target, 4096L)
        assertEquals("""{"v":1}""", recovered)
        assertTrue(target.exists())
        assertFalse(backup.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun atomicWriteFailure_preservesPreviousCommittedFile() {
        val dir = tempFolder.newFolder("rollback")
        val target = File(dir, "manifest.json")
        PackageIoKernel.writeTextAtomic(target, "good-v1")

        var threw = false
        try {
            PackageIoKernel.writeTextAtomic(target, "bad-v2") { false }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals("good-v1", target.readText())
    }

    @Test
    fun zipDirectoryAtomicAndExtractSafe_roundTripsFiles() {
        val src = tempFolder.newFolder("src")
        File(src, "manifest.json").writeText("""{"kind":"art"}""")
        File(src, "sub").mkdirs()
        File(src, "sub/color.txt").writeText("#FF0000")

        val zip = File(tempFolder.root, "out/archive.zip")
        PackageIoKernel.zipDirectoryAtomic(src, zip)
        assertTrue(zip.isFile && zip.length() > 0L)

        val dst = tempFolder.newFolder("dst")
        val extracted = PackageIoKernel.extractZipSafe(zip, dst)
        assertEquals(2, extracted.size)
        assertEquals("""{"kind":"art"}""", File(dst, "manifest.json").readText())
        assertEquals("#FF0000", File(dst, "sub/color.txt").readText())
    }

    @Test
    fun uniqueDirNameAndSafeDelete_respectsRootBoundary() {
        val root = tempFolder.newFolder("pkgRoot")
        val outside = tempFolder.newFolder("outside")
        File(root, "ink").mkdirs()

        val allocated = PackageIoKernel.uniqueDirName(root, "ink")
        assertEquals("ink_1", allocated)

        val matched = PackageIoKernel.uniqueDirName(root, "ink") { it.name == "ink" }
        assertEquals("ink", matched)

        // Refuses to delete root itself or outside directories
        assertFalse(PackageIoKernel.deletePackageDirSafely(root, root))
        assertFalse(PackageIoKernel.deletePackageDirSafely(outside, root))
        assertTrue(outside.exists())

        assertTrue(PackageIoKernel.deletePackageDirSafely(File(root, "ink"), root))
        assertFalse(File(root, "ink").exists())
    }

    @Test
    fun remoteCacheRoundTrip_handlesMissingAndCorruptFilesGracefully() {
        val cache = File(tempFolder.root, "remote_cache/day.json")
        assertTrue(PackageIoKernel.readRemoteCache(cache) { listOf(it) }.isEmpty())

        PackageIoKernel.writeRemoteCache(cache, """["a","b"]""")
        val read = PackageIoKernel.readRemoteCache(cache) { text ->
            assertEquals("""["a","b"]""", text)
            listOf("a", "b")
        }
        assertEquals(listOf("a", "b"), read)

        PackageIoKernel.deleteAtomicText(cache)
        assertNull(PackageIoKernel.readTextWithRecovery(cache, 1024L))
    }
}
