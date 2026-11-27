package io.legado.app.help.review

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
}
