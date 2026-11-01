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
        assertTrue(ReviewBackoffPolicy.isInBackoff(ReviewBackoffPolicy.Failure(1, 200L), 100L))
        assertFalse(ReviewBackoffPolicy.isInBackoff(ReviewBackoffPolicy.Failure(1, 100L), 100L))
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
            "a" to ReviewBackoffPolicy.Failure(1, 50L),
            "b" to ReviewBackoffPolicy.Failure(1, 500L),
            "c" to ReviewBackoffPolicy.Failure(2, 100L)
        )
        assertEquals(
            listOf("a", "c"),
            ReviewBackoffPolicy.expiredKeys(entries, now = 100L).sorted()
        )
    }

    @Test
    fun `backoff grows exponentially and stops at the cap`() {
        val base = ReviewBackoffPolicy.BASE_DELAY_MS
        assertEquals(base, ReviewBackoffPolicy.delayFor(1))
        assertEquals(base * 2, ReviewBackoffPolicy.delayFor(2))
        assertEquals(base * 4, ReviewBackoffPolicy.delayFor(3))
        // 次数再多也不会超过上限：坏源不该被无限期放弃
        assertEquals(ReviewBackoffPolicy.MAX_DELAY_MS, ReviewBackoffPolicy.delayFor(99))
        assertEquals(0L, ReviewBackoffPolicy.delayFor(0))
    }

    @Test
    fun `each failure pushes the deadline further out`() {
        val now = 1_000L
        val first = ReviewBackoffPolicy.onFailure(null, now)
        assertEquals(1, first.attempts)
        assertEquals(now + ReviewBackoffPolicy.BASE_DELAY_MS, first.until)

        val second = ReviewBackoffPolicy.onFailure(first, now)
        assertEquals(2, second.attempts)
        assertTrue(second.until > first.until)
    }

    @Test
    fun `attempt count is capped so the deadline never overflows`() {
        var failure = ReviewBackoffPolicy.Failure(ReviewBackoffPolicy.MAX_ATTEMPTS, 0L)
        repeat(10) {
            failure = ReviewBackoffPolicy.onFailure(failure, 1_000L)
            assertEquals(ReviewBackoffPolicy.MAX_ATTEMPTS, failure.attempts)
            assertEquals(1_000L + ReviewBackoffPolicy.MAX_DELAY_MS, failure.until)
        }
    }

    @Test
    fun `retained drops expired entries and keeps the stubborn ones`() {
        val now = 100L
        val entries = mapOf(
            "expired" to ReviewBackoffPolicy.Failure(1, 50L),
            "soon" to ReviewBackoffPolicy.Failure(1, 200L),
            "later" to ReviewBackoffPolicy.Failure(3, 900L),
        )
        assertFalse(ReviewBackoffPolicy.retained(entries, now, 10).containsKey("expired"))
        // 上限收紧时优先丢掉「最早解禁」的：越晚解禁说明失败越顽固
        val trimmed = ReviewBackoffPolicy.retained(entries, now, 1)
        assertEquals(setOf("later"), trimmed.keys)
    }
}
