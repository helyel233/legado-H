package io.legado.app.help.config.pkg

import io.legado.app.utils.compress.ZipUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class UnifiedPackageCodecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun roundTrip_preservesUnknownFieldsAtEveryNestedLevel() {
        val v2Json = """
            {
              "schemaVersion": 2,
              "kind": "param",
              "id": "param_future",
              "name": "Future Preset",
              "updatedAt": 1763731200000,
              "futureTopFlag": true,
              "param": {
                "futureParamSection": {"x": 42},
                "surfaceGlobal": {
                  "materialMode": "liquid",
                  "cornerBaseScale": 1.25,
                  "surfaceAlpha": 88,
                  "futureRefractionCurve": "cubic"
                },
                "surfaceScopes": {
                  "dialog": {
                    "cornerScale": 1.5,
                    "alpha": 95,
                    "futureSpringDamping": 0.72
                  },
                  "futureReaderBar": {
                    "alpha": 70
                  }
                },
                "layout": {
                  "navigationPreset": "floating",
                  "densityScale": 1.15,
                  "futureCompactHeader": true
                }
              }
            }
        """.trimIndent()

        val decoded = UnifiedPackageCodec.decode(v2Json)
        assertEquals(2, decoded.schemaVersion)
        assertEquals(PackageKind.PARAM, decoded.kind)
        assertEquals("param_future", decoded.id)
        assertTrue(decoded.unknownFields.containsKey("futureTopFlag"))
        assertNotNull(decoded.param)
        assertTrue(decoded.param!!.unknownFields.containsKey("futureParamSection"))
        assertTrue(decoded.param!!.surfaceGlobal!!.unknownFields.containsKey("futureRefractionCurve"))
        assertTrue(decoded.param!!.surfaceScopes!!.dialog!!.unknownFields.containsKey("futureSpringDamping"))
        assertTrue(decoded.param!!.surfaceScopes!!.unknownFields.containsKey("futureReaderBar"))
        assertTrue(decoded.param!!.layout!!.unknownFields.containsKey("futureCompactHeader"))

        // Modify a known field on an older client and re-encode
        val modified = decoded.copy(
            name = "Edited on V1",
            param = decoded.param!!.copy(
                surfaceGlobal = decoded.param!!.surfaceGlobal!!.copy(surfaceAlpha = 90)
            )
        )
        val reEncoded = UnifiedPackageCodec.encode(modified)
        val reDecoded = UnifiedPackageCodec.decode(reEncoded)

        assertEquals("Edited on V1", reDecoded.name)
        assertEquals(90, reDecoded.param!!.surfaceGlobal!!.surfaceAlpha)
        assertEquals(true, reDecoded.unknownFields["futureTopFlag"]?.asBoolean)
        assertEquals(42, reDecoded.param!!.unknownFields["futureParamSection"]?.asJsonObject?.get("x")?.asInt)
        assertEquals(
            "cubic",
            reDecoded.param!!.surfaceGlobal!!.unknownFields["futureRefractionCurve"]?.asString
        )
        assertEquals(
            0.72,
            reDecoded.param!!.surfaceScopes!!.dialog!!.unknownFields["futureSpringDamping"]?.asDouble ?: 0.0,
            0.001
        )
        assertEquals(
            70,
            reDecoded.param!!.surfaceScopes!!.unknownFields["futureReaderBar"]?.asJsonObject?.get("alpha")?.asInt
        )
        assertEquals(
            true,
            reDecoded.param!!.layout!!.unknownFields["futureCompactHeader"]?.asBoolean
        )
    }

    @Test
    fun artKindRoundTrip_preservesPalettesWallpapersTypographyMountsAndUnknowns() {
        val json = """
            {
              "schemaVersion": 1,
              "kind": "art",
              "id": "art_ink",
              "name": "水墨",
              "updatedAt": 1000,
              "art": {
                "palettes": {
                  "day": {
                    "primaryColor": "#795548",
                    "accentColor": "#E53935",
                    "futureToken": "#123456"
                  }
                },
                "wallpapers": {
                  "day": {
                    "main": "assets/day_main.jpg",
                    "topBar": "assets/day_topbar.png",
                    "topBarAlpha": 85
                  }
                },
                "typography": {
                  "day": {
                    "uiFontPath": "assets/ui.ttf"
                  }
                },
                "mounts": {
                  "dayIconSlotId": "ink_icons",
                  "dayCoverSlotId": "ink_covers"
                }
              }
            }
        """.trimIndent()

        val decoded = UnifiedPackageCodec.decode(json)
        assertEquals(PackageKind.ART, decoded.kind)
        assertEquals("#795548", decoded.art?.dayPalette?.primaryColor)
        assertEquals("#123456", decoded.art?.dayPalette?.unknownFields?.get("futureToken")?.asString)
        assertEquals("assets/day_topbar.png", decoded.art?.dayWallpapers?.topBar)
        assertEquals(85, decoded.art?.dayWallpapers?.topBarAlpha)
        assertEquals("ink_icons", decoded.art?.mounts?.dayIconSlotId)
        assertEquals("ink_covers", decoded.art?.mounts?.dayCoverSlotId)

        val roundTripped = UnifiedPackageCodec.decode(UnifiedPackageCodec.encode(decoded))
        assertEquals(decoded, roundTripped)
    }

    @Test
    fun coverSlotRoundTrip_preservesAllFieldsAndUnknownFields() {
        val json = """
            {
              "schemaVersion": 1,
              "kind": "cover_slot",
              "id": "cover_slot_ink",
              "name": "水墨书架封面",
              "updatedAt": 2000,
              "coverSlot": {
                "covers": ["assets/cover_01.jpg", "assets/cover_02.png"],
                "applyMode": "MIXED",
                "dayDefaultCoverPath": "assets/day_default.jpg",
                "nightDefaultCoverPath": "assets/night_default.jpg",
                "groupCoverPath": "assets/group_default.png",
                "futureOverlayStyle": "linen"
              }
            }
        """.trimIndent()

        val decoded = UnifiedPackageCodec.decode(json)
        assertEquals(PackageKind.COVER_SLOT, decoded.kind)
        assertNotNull(decoded.coverSlot)
        val slot = decoded.coverSlot!!
        assertEquals(listOf("assets/cover_01.jpg", "assets/cover_02.png"), slot.covers)
        assertEquals("MIXED", slot.applyMode)
        assertEquals("assets/day_default.jpg", slot.dayDefaultCoverPath)
        assertEquals("assets/night_default.jpg", slot.nightDefaultCoverPath)
        assertEquals("assets/group_default.png", slot.groupCoverPath)
        assertEquals("linen", slot.unknownFields["futureOverlayStyle"]?.asString)

        val roundTripped = UnifiedPackageCodec.decode(UnifiedPackageCodec.encode(decoded))
        assertEquals(decoded, roundTripped)

        val collected = UnifiedPackageCodec.collectAssetPaths(decoded)
        assertEquals(
            listOf(
                "assets/cover_01.jpg",
                "assets/cover_02.png",
                "assets/day_default.jpg",
                "assets/night_default.jpg",
                "assets/group_default.png"
            ),
            collected
        )
    }

    @Test
    fun coverSlotContentHashAndValidation_detectBinaryChangesAndPathIssues() {
        val dirA = tempFolder.newFolder("cover_a")
        val dirB = tempFolder.newFolder("cover_b")
        File(dirA, "assets/cover_01.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        File(dirA, "assets/group.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5, 6, 7))
        }
        File(dirB, "assets/cover_01.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9, 9))
        }
        File(dirB, "assets/group.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5, 6, 7))
        }

        val m1 = UnifiedPackageManifest(
            kind = PackageKind.COVER_SLOT,
            id = "cover_1",
            name = "包1",
            updatedAt = 100L,
            coverSlot = CoverSlotPackagePayload(
                covers = listOf("assets/cover_01.jpg"),
                applyMode = CoverSlotApplyMode.RANDOM,
                groupCoverPath = "assets/group.png"
            )
        )
        val m2SameContent = m1.copy(
            id = "cover_999",
            name = "不同名包",
            updatedAt = 99999L,
            basePackageId = "cover_base"
        )

        val hashA = UnifiedPackageCodec.computeContentHash(m1, dirA)
        val hashSame = UnifiedPackageCodec.computeContentHash(m2SameContent, dirA)
        val hashB = UnifiedPackageCodec.computeContentHash(m1, dirB)

        assertTrue(UnifiedPackageCodec.isCurrentContentHash(hashA))
        assertEquals(hashA, hashSame)
        assertNotEquals(hashA, hashB)

        assertTrue(UnifiedPackageCodec.validateCoverSlotPackage(m1, dirA).isEmpty())

        val broken = m1.copy(
            coverSlot = m1.coverSlot!!.copy(covers = listOf("../escape.jpg", "assets/missing.jpg"))
        )
        val errors = UnifiedPackageCodec.validateCoverSlotPackage(broken, dirA)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("Unsafe asset path") })
        assertTrue(errors.any { it.contains("Missing cover asset") })
    }

    @Test
    fun legacyCoverCollectionZipAndRed_convertLosslesslyToCoverSlot() {
        val legacySrc = tempFolder.newFolder("legacy_collection_src")
        File(legacySrc, "images/c1.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(10, 20, 30))
        }
        File(legacySrc, "images/c2.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(40, 50, 60))
        }
        File(legacySrc, "collection.json").writeText(
            """
            {
              "id": "old-col-1",
              "name": "复古藏书阁",
              "dirName": "retro",
              "isNight": false,
              "mode": "sequence",
              "images": ["images/c1.jpg", "images/c2.png"],
              "updatedAt": 1700000000000
            }
            """.trimIndent()
        )

        val legacyZip = File(tempFolder.root, "retro_covers.zip")
        assertTrue(ZipUtils.zipFile(legacySrc, legacyZip))

        val scratchTemp = tempFolder.newFolder("codec_tmp")
        assertEquals(
            PackageKind.COVER_SLOT,
            UnifiedPackageCodec.detectArchivePackageKind(legacyZip, scratchTemp)
        )

        val convertedZipDir = tempFolder.newFolder("converted_from_zip")
        val convertedFromZip = UnifiedPackageCodec.convertLegacyCoverCollectionArchive(
            archiveFile = legacyZip,
            targetDir = convertedZipDir,
            tempDir = scratchTemp
        )
        assertEquals(PackageKind.COVER_SLOT, convertedFromZip.kind)
        assertEquals("复古藏书阁", convertedFromZip.name)
        assertEquals(CoverSlotApplyMode.SEQUENCE, convertedFromZip.coverSlot?.applyMode)
        assertEquals(listOf("assets/c1.jpg", "assets/c2.png"), convertedFromZip.coverSlot?.covers)
        assertTrue(File(convertedZipDir, "manifest.json").isFile)
        assertTrue(File(convertedZipDir, "assets/c1.jpg").isFile)
        assertTrue(File(convertedZipDir, "assets/c2.png").isFile)
        assertTrue(UnifiedPackageCodec.isCurrentContentHash(convertedFromZip.contentHash))

        // Wrap the zip bytes with RED\x01 header (`.red` package format)
        val redFile = File(tempFolder.root, "retro_covers.red")
        FileOutputStream(redFile).use { out ->
            out.write(byteArrayOf('R'.code.toByte(), 'E'.code.toByte(), 'D'.code.toByte(), 1))
            out.write(legacyZip.readBytes())
        }
        assertEquals(
            PackageKind.COVER_SLOT,
            UnifiedPackageCodec.detectArchivePackageKind(redFile, scratchTemp)
        )
        val convertedRedDir = tempFolder.newFolder("converted_from_red")
        val convertedFromRed = UnifiedPackageCodec.convertLegacyCoverCollectionArchive(
            archiveFile = redFile,
            targetDir = convertedRedDir,
            tempDir = scratchTemp
        )
        assertEquals(convertedFromZip.contentHash, convertedFromRed.contentHash)
        assertEquals(convertedFromZip.coverSlot?.covers, convertedFromRed.coverSlot?.covers)
        assertFalse(UnifiedPackageCodec.validateCoverSlotPackage(convertedFromRed, convertedRedDir).isNotEmpty())
    }
}
