package io.legado.app.help.config.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPackageCodecTest {

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
}
