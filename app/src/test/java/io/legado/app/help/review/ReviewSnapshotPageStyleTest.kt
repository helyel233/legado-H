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

    @Test
    fun refreshPolicySkipsReloadWhenSnapshotContentIsEquivalent() {
        val snapshotHtml = """
            <!doctype html>
            <html data-theme="light">
            <head><style id="legado-snapshot-deferral">img{content-visibility:auto}</style></head>
            <body>
              <div id="contentArea">
                <article class="comment-card" data-comment-id="c1">
                  <div class="comment-header">
                    <img class="avatar" src="review-resource://0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef">
                    <span class="comment-author">甲</span>
                    <span class="comment-time">3分钟前</span>
                  </div>
                  <p class="comment-body">这段不错</p>
                </article>
              </div>
              <div id="bottomBar"><button id="commentTrigger">写段评</button></div>
            </body>
            </html>
        """.trimIndent()

        val refreshedEquivalentHtml = """
            <!doctype html>
            <html data-theme="dark">
            <head><style>body{color:#fff}</style></head>
            <body>
              <div id="contentArea">
                <article class="comment-card" data-comment-id="c1">
                  <div class="comment-header">
                    <img class="avatar" src="https://cdn.example.com/u/1.png">
                    <span class="comment-author">甲</span>
                    <span class="comment-time">8分钟前</span>
                  </div>
                  <p class="comment-body">这段不错</p>
                </article>
              </div>
            </body>
            </html>
        """.trimIndent()

        assertEquals(
            ReviewSnapshotRefreshPolicy.RefreshDecision.NO_CHANGE,
            ReviewSnapshotRefreshPolicy.evaluateRefresh(snapshotHtml, snapshotHtml)
        )
        assertEquals(
            ReviewSnapshotRefreshPolicy.RefreshDecision.NO_CHANGE,
            ReviewSnapshotRefreshPolicy.evaluateRefresh(snapshotHtml, refreshedEquivalentHtml)
        )
    }

    @Test
    fun refreshPolicyTriggersUpdateWhenCommentsOrRepliesChange() {
        val baseHtml = """
            <html><body><div id="contentArea">
              <article class="comment-card" data-comment-id="c1">
                <p class="comment-body">第一条</p>
                <div class="reply-list"><article class="reply-item">回复A</article></div>
              </article>
            </div></body></html>
        """.trimIndent()

        val newCommentHtml = """
            <html><body><div id="contentArea">
              <article class="comment-card" data-comment-id="c2">
                <p class="comment-body">新增评论</p>
              </article>
              <article class="comment-card" data-comment-id="c1">
                <p class="comment-body">第一条</p>
                <div class="reply-list"><article class="reply-item">回复A</article></div>
              </article>
            </div></body></html>
        """.trimIndent()

        val newReplyHtml = """
            <html><body><div id="contentArea">
              <article class="comment-card" data-comment-id="c1">
                <p class="comment-body">第一条</p>
                <div class="reply-list">
                  <article class="reply-item">回复A</article>
                  <article class="reply-item">回复B</article>
                </div>
              </article>
            </div></body></html>
        """.trimIndent()

        val ajaxShellHtml = """
            <html><head><script>loadParaFromApi();</script></head>
            <body><div id="contentArea"></div></body></html>
        """.trimIndent()

        assertEquals(
            ReviewSnapshotRefreshPolicy.RefreshDecision.CONTENT_CHANGED,
            ReviewSnapshotRefreshPolicy.evaluateRefresh(baseHtml, newCommentHtml)
        )
        assertEquals(
            ReviewSnapshotRefreshPolicy.RefreshDecision.CONTENT_CHANGED,
            ReviewSnapshotRefreshPolicy.evaluateRefresh(baseHtml, newReplyHtml)
        )
        assertEquals(
            ReviewSnapshotRefreshPolicy.RefreshDecision.CONTENT_CHANGED,
            ReviewSnapshotRefreshPolicy.evaluateRefresh(baseHtml, ajaxShellHtml)
        )
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
