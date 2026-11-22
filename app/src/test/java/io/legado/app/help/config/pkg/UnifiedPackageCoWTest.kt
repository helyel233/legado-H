package io.legado.app.help.config.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnifiedPackageCoWTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun packageNameUniqueness_isCaseInsensitiveAndSuggestsNonCollidingCopyNames() {
        val existing = listOf("江南烟雨", "Pure Ink", "江南烟雨 副本")

        assertFalse(UnifiedPackageRepository.isPackageNameUnique(existing, "江南烟雨"))
        assertFalse(UnifiedPackageRepository.isPackageNameUnique(existing, "  pure   ink "))
        assertTrue(UnifiedPackageRepository.isPackageNameUnique(existing, "江南烟雨 2"))

        val suggested = UnifiedPackageRepository.suggestUniquePackageName(existing, "江南烟雨")
        assertEquals("江南烟雨 副本 2", suggested)
        assertTrue(UnifiedPackageRepository.isPackageNameUnique(existing, suggested))
    }

    @Test
    fun cowAssetResolutionAndMaterialization_prefersOverlayAndFallsBackToBase() {
        val baseDir = tempFolder.newFolder("base_art")
        val overlayDir = tempFolder.newFolder("clone_art")
        val exportDir = tempFolder.newFolder("export_art")

        File(baseDir, "assets/main.jpg").apply {
            parentFile?.mkdirs()
            writeText("base_wallpaper_bytes")
        }
        File(baseDir, "icons/nav_book.png").apply {
            parentFile?.mkdirs()
            writeText("base_icon_bytes")
        }
        // Cloned overlay only replaces nav_book.png, leaving main.jpg in baseDir (0 duplicate bytes)
        File(overlayDir, "icons/nav_book.png").apply {
            parentFile?.mkdirs()
            writeText("custom_overlay_icon_bytes")
        }

        val resolvedMain = UnifiedPackageRepository.resolveCoWAssetFile(overlayDir, baseDir, "assets/main.jpg")
        val resolvedIcon = UnifiedPackageRepository.resolveCoWAssetFile(overlayDir, baseDir, "icons/nav_book.png")

        assertNotNull(resolvedMain)
        assertEquals("base_wallpaper_bytes", resolvedMain!!.readText())
        assertNotNull(resolvedIcon)
        assertEquals("custom_overlay_icon_bytes", resolvedIcon!!.readText())

        // Materialization combines base + overlay into a 100% self-contained directory
        UnifiedPackageRepository.materializeCoWDirectory(overlayDir, baseDir, exportDir)
        assertEquals("base_wallpaper_bytes", File(exportDir, "assets/main.jpg").readText())
        assertEquals("custom_overlay_icon_bytes", File(exportDir, "icons/nav_book.png").readText())
    }

    @Test
    fun computeContentHash_ignoresVolatileMetadataAndDetectsSameContent() {
        val m1 = UnifiedPackageManifest(
            kind = PackageKind.PARAM,
            id = "param_1",
            name = "包A",
            updatedAt = 100L,
            param = ParamPackagePayload(
                surfaceGlobal = SurfaceGlobalParams(materialMode = "clear", cornerBaseScale = 1.2f)
            )
        )
        val m2 = m1.copy(
            id = "param_999",
            name = "别人分享的同名或不同名包",
            updatedAt = 999999L,
            basePackageId = "param_base"
        )
        val m3 = m1.copy(
            param = ParamPackagePayload(
                surfaceGlobal = SurfaceGlobalParams(materialMode = "frosted", cornerBaseScale = 1.2f)
            )
        )

        assertEquals(
            UnifiedPackageCodec.computeContentHash(m1),
            UnifiedPackageCodec.computeContentHash(m2)
        )
        assertNotEquals(
            UnifiedPackageCodec.computeContentHash(m1),
            UnifiedPackageCodec.computeContentHash(m3)
        )
    }

    @Test
    fun computeContentHash_includesBinaryAssetBytesAndRejectsUnconfinedAbsolutePaths() {
        val dirA = tempFolder.newFolder("art_a")
        val dirB = tempFolder.newFolder("art_b")
        val outsideSecret = tempFolder.newFile("outside_secret.txt").apply {
            writeText("sensitive_host_file")
        }
        val manifest = UnifiedPackageManifest(
            kind = PackageKind.ART,
            id = "art_a",
            name = "美术包",
            art = ArtPackagePayload(
                dayWallpapers = ArtWallpapersSpec(main = "assets/day_main.png")
            )
        )
        File(dirA, "assets/day_main.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        File(dirB, "assets/day_main.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 8, 7, 6))
        }

        // Same manifest JSON path string, different image binary bytes -> different v2 SHA-256 contentHash
        val hashA = UnifiedPackageCodec.computeContentHash(manifest, dirA)
        val hashB = UnifiedPackageCodec.computeContentHash(manifest, dirB)
        assertTrue(UnifiedPackageCodec.isCurrentContentHash(hashA))
        assertFalse(UnifiedPackageCodec.isCurrentContentHash("legacy_unversioned_hex"))
        assertNotEquals(hashA, hashB)

        // Path traversal / unconfined absolute paths are rejected by default
        assertNull(UnifiedPackageRepository.resolveCoWAssetFile(dirA, null, "../outside_secret.txt"))
        assertNull(UnifiedPackageRepository.resolveCoWAssetFile(dirA, null, outsideSecret.absolutePath))
        // Explicit allowedExternalRoots permits legitimate app storage files
        assertNotNull(
            UnifiedPackageRepository.resolveCoWAssetFile(
                dirA,
                null,
                outsideSecret.absolutePath,
                allowedExternalRoots = listOf(outsideSecret.parentFile!!)
            )
        )
    }
}
