package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论抓取分页预算的回归网
 */
class ReviewPaginationPolicyTest {

    @Test
    fun `page count within the limit is allowed`() {
        assertFalse(ReviewPaginationPolicy.isOverLimit(1))
        assertFalse(ReviewPaginationPolicy.isOverLimit(ReviewPaginationPolicy.MAX_PAGES))
    }

    @Test
    fun `page count beyond the limit is rejected`() {
        assertTrue(ReviewPaginationPolicy.isOverLimit(ReviewPaginationPolicy.MAX_PAGES + 1))
        assertTrue(ReviewPaginationPolicy.isOverLimit(10_000))
    }

    @Test
    fun `custom limit is respected`() {
        assertTrue(ReviewPaginationPolicy.isOverLimit(pageCount = 11, maxPages = 10))
        assertFalse(ReviewPaginationPolicy.isOverLimit(pageCount = 10, maxPages = 10))
    }

    @Test
    fun `default limit is the shared constant`() {
        assertEquals(200, ReviewPaginationPolicy.MAX_PAGES)
    }
}
