package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照页首屏让位样式与内容指纹的纯函数测试。
 */
class ReviewSnapshotPageStyleTest {

    @Test
    fun insertsStyleRightAfterHeadOpenTag() {
        val html = "<html><head><meta charset=\"utf-8\"></head><body>x</body></html>"
        val result = injectSnapshotImageDeferralStyle(html)
        val headEnd = result.indexOf('>', result.indexOf("<head"))
        val styleStart = result.indexOf("<style id=\"$SNAPSHOT_IMAGE_DEFERRAL_STYLE_TAG\"")
        assertEquals(headEnd + 1, styleStart)
        assertTrue(result.contains("content-visibility:auto"))
        assertTrue(result.contains("contain-intrinsic-size:auto 160px"))
        // 原有内容结构不被破坏
        assertTrue(result.contains("<meta charset=\"utf-8\">"))
        assertTrue(result.endsWith("<body>x</body></html>"))
    }

    @Test
    fun handlesHeadWithAttributes() {
        val html = "<HTML><HEAD lang=\"zh\"><title>t</title></HEAD><BODY></BODY></HTML>"
        val result = injectSnapshotImageDeferralStyle(html)
        val headEnd = result.indexOf('>', result.indexOf("<HEAD"))
        assertEquals(headEnd + 1, result.indexOf("<style id=\"$SNAPSHOT_IMAGE_DEFERRAL_STYLE_TAG\""))
    }

    @Test
    fun isIdempotent() {
        val html = "<html><head></head><body></body></html>"
        val once = injectSnapshotImageDeferralStyle(html)
        val twice = injectSnapshotImageDeferralStyle(once)
        assertEquals(once, twice)
        assertEquals(1, countOccurrences(once, SNAPSHOT_IMAGE_DEFERRAL_STYLE_TAG))
    }

    @Test
    fun prependsWhenNoHeadTag() {
        val html = "<div>裸片段</div>"
        val result = injectSnapshotImageDeferralStyle(html)
        assertTrue(result.endsWith(html))
        assertTrue(result.startsWith("<style id=\"$SNAPSHOT_IMAGE_DEFERRAL_STYLE_TAG\""))
    }

    @Test
    fun leavesEmptyHtmlUntouched() {
        assertEquals("", injectSnapshotImageDeferralStyle(""))
    }

    @Test
    fun identityIsStableForSameContentAndConditions() {
        val html = "<html><head></head><body>a</body></html>"
        assertEquals(
            reviewPageIdentity(html, false, 100),
            reviewPageIdentity(html, false, 100)
        )
    }

    @Test
    fun identityChangesWithContent() {
        assertNotEquals(
            reviewPageIdentity("<body>a</body>", false, 100),
            reviewPageIdentity("<body>b</body>", false, 100)
        )
    }

    @Test
    fun identityChangesWithNightMode() {
        val html = "<body>a</body>"
        assertNotEquals(
            reviewPageIdentity(html, false, 100),
            reviewPageIdentity(html, true, 100)
        )
    }

    @Test
    fun identityChangesWithTextZoom() {
        val html = "<body>a</body>"
        assertNotEquals(
            reviewPageIdentity(html, false, 100),
            reviewPageIdentity(html, false, 130)
        )
    }

    @Test
    fun identityEncodesLengthHashAndFlags() {
        val html = "<body>abc</body>"
        val identity = reviewPageIdentity(html, true, 120)
        assertEquals("${html.length}:${html.hashCode()}:N:120", identity)
        assertFalse(identity.contains("D:"))
    }

    private fun countOccurrences(source: String, needle: String): Int {
        var count = 0
        var index = source.indexOf(needle)
        while (index >= 0) {
            count++
            index = source.indexOf(needle, index + needle.length)
        }
        return count
    }
}
