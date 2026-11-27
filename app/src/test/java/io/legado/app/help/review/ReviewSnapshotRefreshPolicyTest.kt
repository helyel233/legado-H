package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSnapshotRefreshPolicyTest {

    @Test
    fun isCommentPageRejectsEmptyAndErrorPagesAndAcceptsCommentCards() {
        assertFalse(ReviewSnapshotRefreshPolicy.isCommentPage(null))
        assertFalse(ReviewSnapshotRefreshPolicy.isCommentPage(""))
        assertFalse(ReviewSnapshotRefreshPolicy.isCommentPage("   "))
        assertFalse(ReviewSnapshotRefreshPolicy.isCommentPage("<html><body>error</body></html>"))
        assertTrue(
            ReviewSnapshotRefreshPolicy.isCommentPage(
                "<html><body><div class=\"comment-card\">hello</div></body></html>"
            )
        )
        assertTrue(
            ReviewSnapshotRefreshPolicy.isCommentPage(
                "<html><body><article data-comment-id=\"123\">world</article></body></html>"
            )
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
        assertEquals(2, noChange.currentCardCount)
        assertEquals(2, noChange.refreshedCardCount)

        val truncated = ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(existing, truncatedFirstPage)
        assertEquals(ReviewSnapshotRefreshPolicy.RefreshDecision.CONTENT_CHANGED, truncated.decision)
        assertEquals(2, truncated.currentCardCount)
        assertEquals(1, truncated.refreshedCardCount)
        assertFalse(ReviewSnapshotRefreshPolicy.shouldOverwriteSnapshot(existing, truncated.refreshedCardCount))

        val expanded = ReviewSnapshotRefreshPolicy.evaluateRefreshDetail(existing, expandedNewComment)
        assertEquals(ReviewSnapshotRefreshPolicy.RefreshDecision.CONTENT_CHANGED, expanded.decision)
        assertEquals(3, expanded.refreshedCardCount)
        assertTrue(ReviewSnapshotRefreshPolicy.shouldOverwriteSnapshot(existing, expanded.refreshedCardCount))
    }
}
