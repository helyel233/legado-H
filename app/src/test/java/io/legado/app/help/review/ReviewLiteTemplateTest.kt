package io.legado.app.help.review

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLiteTemplateTest {

    @Test
    fun blankHtmlReturnedAsIs() {
        assertEquals("", ReviewLiteTemplate.apply("", isNight = false))
        assertEquals("   \n\t  ", ReviewLiteTemplate.apply("   \n\t  ", isNight = true, offlineMode = true))
    }

    @Test
    fun fragmentWithoutHtmlIsWrappedWithThemeViewportAndLiteStyle() {
        val output = ReviewLiteTemplate.apply("<p>精彩段评</p>", isNight = true)
        val doc = Jsoup.parse(output)

        assertTrue(output.startsWith("<!DOCTYPE html>"))
        assertEquals("dark", doc.selectFirst("html")?.attr("data-theme"))
        assertFalse(doc.selectFirst("html")?.hasAttr("data-offline") ?: true)
        assertNotNull(doc.selectFirst("""meta[name="viewport"]"""))
        assertNotNull(doc.selectFirst("style#legado-review-lite-style"))
        assertNull(doc.selectFirst("style#legado-review-offline-img"))
        assertEquals("精彩段评", doc.selectFirst("body p")?.text())
    }

    @Test
    fun dataThemeIsSynchronizedAndOverwritten() {
        val nightSource = """<html lang="zh" data-theme="dark"><head></head><body><p>hi</p></body></html>"""
        val dayResult = ReviewLiteTemplate.apply(nightSource, isNight = false)
        assertEquals("light", Jsoup.parse(dayResult).selectFirst("html")?.attr("data-theme"))

        val backToNight = ReviewLiteTemplate.apply(dayResult, isNight = true)
        assertEquals("dark", Jsoup.parse(backToNight).selectFirst("html")?.attr("data-theme"))

        val noThemeSource = """<html lang="zh"><head></head><body><p>hi</p></body></html>"""
        val addedTheme = ReviewLiteTemplate.apply(noThemeSource, isNight = true)
        assertEquals("dark", Jsoup.parse(addedTheme).selectFirst("html")?.attr("data-theme"))
        assertEquals("zh", Jsoup.parse(addedTheme).selectFirst("html")?.attr("lang"))
    }

    @Test
    fun viewportMetaIsInsertedOnlyWhenMissing() {
        val missingViewport = """<html><head><meta charset="utf-8"></head><body><p>a</p></body></html>"""
        val withViewport = ReviewLiteTemplate.apply(missingViewport, isNight = false)
        assertEquals(1, Jsoup.parse(withViewport).select("""meta[name="viewport"]""").size)

        val existingViewport =
            """<html><head><meta name="viewport" content="width=device-width,initial-scale=2"></head><body><p>a</p></body></html>"""
        val kept = ReviewLiteTemplate.apply(existingViewport, isNight = false)
        val metas = Jsoup.parse(kept).select("""meta[name="viewport"]""")
        assertEquals(1, metas.size)
        assertEquals("width=device-width,initial-scale=2", metas.single().attr("content"))
    }

    @Test
    fun applyIsIdempotentForOnlineAndOfflineModes() {
        val raw = """<html><head><title>t</title></head><body><p>text</p><img src="https://example.com/a.png"></body></html>"""

        val onlineOnce = ReviewLiteTemplate.apply(raw, isNight = false, accentHex = "#0A84FF", offlineMode = false)
        val onlineTwice = ReviewLiteTemplate.apply(onlineOnce, isNight = false, accentHex = "#0A84FF", offlineMode = false)
        assertEquals(onlineOnce, onlineTwice)

        val offlineOnce = ReviewLiteTemplate.apply(raw, isNight = true, accentHex = "#0A84FF", offlineMode = true)
        val offlineTwice = ReviewLiteTemplate.apply(offlineOnce, isNight = true, accentHex = "#0A84FF", offlineMode = true)
        assertEquals(offlineOnce, offlineTwice)
    }

    @Test
    fun dataTemplateSkipsGenericLiteStyleButSyncsTheme() {
        val dataHtml = """
            <html data-theme="light">
            <head>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style id="${ReviewLiteTemplate.DATA_TEMPLATE_MARKER}">body{margin:0;}</style>
            </head>
            <body><div id="contentArea">ok</div></body>
            </html>
        """.trimIndent()

        val applied = ReviewLiteTemplate.apply(dataHtml, isNight = true, offlineMode = false)
        val doc = Jsoup.parse(applied)

        assertEquals("dark", doc.selectFirst("html")?.attr("data-theme"))
        assertNotNull(doc.selectFirst("style#${ReviewLiteTemplate.DATA_TEMPLATE_MARKER}"))
        assertNull(doc.selectFirst("style#legado-review-lite-style"))
        assertNull(doc.selectFirst("style#legado-review-offline-img"))
    }

    @Test
    fun invalidAccentHexFallsBackToDefault() {
        val raw = """<html><head></head><body><p>a</p></body></html>"""

        val valid = ReviewLiteTemplate.apply(raw, isNight = false, accentHex = " #0A84FF ")
        assertTrue(valid.contains("--rv-accent:#0A84FF;"))

        listOf("", "red", "#12345", "#1234567", "#GGGGGG", "0A84FF").forEach { bad ->
            val applied = ReviewLiteTemplate.apply(raw, isNight = false, accentHex = bad)
            assertTrue("Expected fallback accent for '$bad'", applied.contains("--rv-accent:#3B82F6;"))
        }
    }

    @Test
    fun offlineModeInjectsDataOfflineAndHideExternalImageRuleForGenericAndDataTemplates() {
        val genericRaw = """<html data-offline="0"><head></head><body><img src="https://example.com/a.jpg"><img src="data:image/png;base64,AA=="></body></html>"""
        val genericOffline = ReviewLiteTemplate.apply(genericRaw, isNight = false, offlineMode = true)
        val genericDoc = Jsoup.parse(genericOffline)

        assertEquals("1", genericDoc.selectFirst("html")?.attr("data-offline"))
        val genericOfflineStyle = genericDoc.selectFirst("style#legado-review-offline-img")
        assertNotNull(genericOfflineStyle)
        assertEquals(
            """html[data-offline="1"] img[src^="http"]{display:none!important;}""",
            genericOfflineStyle!!.data()
        )
        assertNotNull(genericDoc.selectFirst("style#legado-review-lite-style"))

        val dataRaw = """
            <html data-theme="light">
            <head>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style id="${ReviewLiteTemplate.DATA_TEMPLATE_MARKER}">body{margin:0;}</style>
            </head>
            <body><img src="http://example.com/b.jpg"></body>
            </html>
        """.trimIndent()
        val dataOffline = ReviewLiteTemplate.apply(dataRaw, isNight = true, offlineMode = true)
        val dataDoc = Jsoup.parse(dataOffline)

        assertEquals("dark", dataDoc.selectFirst("html")?.attr("data-theme"))
        assertEquals("1", dataDoc.selectFirst("html")?.attr("data-offline"))
        assertNull(dataDoc.selectFirst("style#legado-review-lite-style"))
        val dataOfflineStyle = dataDoc.selectFirst("style#legado-review-offline-img")
        assertNotNull(dataOfflineStyle)
        assertEquals(
            """html[data-offline="1"] img[src^="http"]{display:none!important;}""",
            dataOfflineStyle!!.data()
        )
    }

    @Test
    fun onlineModeDoesNotHideExternalImagesAndStripsStaleDataOffline() {
        val raw = """<html><head></head><body><img src="https://example.com/a.jpg"></body></html>"""
        val online = ReviewLiteTemplate.apply(raw, isNight = false, offlineMode = false)
        val onlineDoc = Jsoup.parse(online)

        assertFalse(onlineDoc.selectFirst("html")?.hasAttr("data-offline") ?: true)
        assertNull(onlineDoc.selectFirst("style#legado-review-offline-img"))

        val previouslyOffline = ReviewLiteTemplate.apply(raw, isNight = false, offlineMode = true)
        val backOnline = ReviewLiteTemplate.apply(previouslyOffline, isNight = false, offlineMode = false)
        assertFalse(Jsoup.parse(backOnline).selectFirst("html")?.hasAttr("data-offline") ?: true)
    }
}
