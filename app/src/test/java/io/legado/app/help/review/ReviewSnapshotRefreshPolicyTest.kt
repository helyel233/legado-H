package io.legado.app.help.review

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSnapshotRefreshPolicyTest {

    @Test
    fun evaluateRefreshDetailRejectsEmptyAndErrorPagesAndAcceptsCommentCards() {
        val current = "<html><body><div class=\"comment-card\">old</div></body></html>"

        assertFalse(ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(current, null).refreshedIsCommentPage)
        assertFalse(ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(current, "").refreshedIsCommentPage)
        assertFalse(ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(current, "   ").refreshedIsCommentPage)
        assertFalse(
            ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(
                current,
                "<html><body>error</body></html>",
            ).refreshedIsCommentPage
        )
        assertTrue(
            ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(
                current,
                "<html><body><div class=\"comment-card\">hello</div></body></html>",
            ).refreshedIsCommentPage
        )
        assertTrue(
            ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(
                current,
                "<html><body><article data-comment-id=\"123\">world</article></body></html>",
            ).refreshedIsCommentPage
        )
    }

    @Test
    fun evaluateRefreshDetailReportsDecisionAndCardCountsInSinglePass() {
        val existing = """
            <html><body>
              <div class="comment-card" data-comment-id="1">first</div>
              <div class="comment-card" data-comment-id="2">second</div>
            </body></html>
        """.trimIndent()
        val sameWithRelativeTime = """
            <html><body>
              <div class="comment-card" data-comment-id="1">first <span class="comment-time">2分钟前</span></div>
              <div class="comment-card" data-comment-id="2">second <time>刚刚</time></div>
            </body></html>
        """.trimIndent()
        val truncatedFirstPage = """
            <html><body>
              <div class="comment-card" data-comment-id="1">first</div>
            </body></html>
        """.trimIndent()
        val expandedNewComment = """
            <html><body>
              <div class="comment-card" data-comment-id="1">first</div>
              <div class="comment-card" data-comment-id="2">second</div>
              <div class="comment-card" data-comment-id="3">third</div>
            </body></html>
        """.trimIndent()

        val noChange = ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(existing, sameWithRelativeTime)
        assertEquals(ReviewSnapshotRefreshPolicy.RefreshDecision.NO_CHANGE, noChange.decision)
        assertTrue(noChange.refreshedIsCommentPage)
        assertEquals(2, noChange.refreshedCardCount)

        val truncated = ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(existing, truncatedFirstPage)
        assertEquals(ReviewSnapshotRefreshPolicy.RefreshDecision.CONTENT_CHANGED, truncated.decision)
        assertEquals(1, truncated.refreshedCardCount)
        assertFalse(ReviewSnapshotRefreshPolicy.shouldOverwriteSnapshot(existing, truncated.refreshedCardCount))

        val expanded = ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(existing, expandedNewComment)
        assertEquals(ReviewSnapshotRefreshPolicy.RefreshDecision.CONTENT_CHANGED, expanded.decision)
        assertEquals(3, expanded.refreshedCardCount)
        assertTrue(ReviewSnapshotRefreshPolicy.shouldOverwriteSnapshot(existing, expanded.refreshedCardCount))
    }

    @Test
    fun sanitizeForSnapshotRemovesScriptsResolvesRelativeImagesAndOptionallyStripsReplies() {
        val doc = Jsoup.parse("<html><head></head><body></body></html>")
        doc.head().appendElement("script").text("void 0;")
        doc.head().appendElement("link").attr("rel", "stylesheet").attr("href", "/static/app.css")
        doc.head().appendElement("link").attr("rel", "preload").attr("as", "font").attr("href", "/fonts/a.woff2")
        val card = doc.body().appendElement("div").addClass("comment-card").attr("data-comment-id", "1")
        card.appendElement("img").attr("src", "/img/pic.png")
        card.appendElement("img").attr("src", "#anchor")
        card.appendElement("div").addClass("reply-section").text("reply body")
        val rawHtml = doc.outerHtml()

        val cleanedWithReplies = ReviewSnapshotRefreshPolicy.sanitizeForSnapshot(
            html = rawHtml,
            baseUrl = "https://reader.local/book/1/review",
            cacheReplies = true,
        )
        val parsedWithReplies = Jsoup.parse(cleanedWithReplies)
        assertTrue(parsedWithReplies.select("script").isEmpty())
        assertTrue(parsedWithReplies.select("link").isEmpty())
        val imgSrcs = parsedWithReplies.select("img").map { it.attr("src") }
        assertTrue(imgSrcs.contains("https://reader.local/img/pic.png"))
        assertTrue(imgSrcs.contains("#anchor"))
        assertEquals(1, parsedWithReplies.select(".reply-section").size)

        val cleanedNoReplies = ReviewSnapshotRefreshPolicy.sanitizeForSnapshot(
            html = rawHtml,
            baseUrl = "https://reader.local/book/1/review",
            cacheReplies = false,
        )
        val parsedNoReplies = Jsoup.parse(cleanedNoReplies)
        assertTrue(parsedNoReplies.select(".reply-section").isEmpty())
    }
}
