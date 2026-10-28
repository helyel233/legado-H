package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段评预热失败退避策略的回归网
 */
class ReviewBackoffPolicyTest {

    @Test
    fun `backoff key is built from book url and chapter url`() {
        assertEquals(
            "bookUrl|chapterUrl",
            ReviewBackoffPolicy.key("bookUrl", "chapterUrl")
        )
    }

    @Test
    fun `different chapters of the same book get different keys`() {
        val first = ReviewBackoffPolicy.key("book", "chapter-1")
        val second = ReviewBackoffPolicy.key("book", "chapter-2")
        assertTrue(first != second)
    }

    @Test
    fun `the same chapter always maps to the same key`() {
        assertEquals(
            ReviewBackoffPolicy.key("book", "chapter-1"),
            ReviewBackoffPolicy.key("book", "chapter-1")
        )
    }

    @Test
    fun `a chapter url change produces a different key`() {
        // 换源后章节 URL 会变，新章不应被旧章的退避跳过
        assertTrue(
            ReviewBackoffPolicy.key("book", "old-url") !=
                ReviewBackoffPolicy.key("book", "new-url")
        )
    }

    @Test
    fun `in backoff only while the deadline is in the future`() {
        assertTrue(ReviewBackoffPolicy.isInBackoff(200L, 100L))
        assertFalse(ReviewBackoffPolicy.isInBackoff(100L, 100L))
        assertFalse(ReviewBackoffPolicy.isInBackoff(null, 100L))
    }

    @Test
    fun `trim is only needed when the table exceeds the limit`() {
        assertFalse(ReviewBackoffPolicy.shouldTrim(size = 10, maxEntries = 512))
        assertFalse(ReviewBackoffPolicy.shouldTrim(size = 512, maxEntries = 512))
        assertTrue(ReviewBackoffPolicy.shouldTrim(size = 513, maxEntries = 512))
    }

    @Test
    fun `expired keys are the ones whose deadline has passed`() {
        val entries = mapOf(
            "a" to 50L,
            "b" to 500L,
            "c" to 100L
        )
        assertEquals(
            listOf("a", "c"),
            ReviewBackoffPolicy.expiredKeys(entries, now = 100L).sorted()
        )
    }
}
