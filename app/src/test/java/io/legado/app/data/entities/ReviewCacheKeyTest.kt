package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ReviewCacheKeyTest {

    @Test
    fun cacheKeyReturns64CharLowercaseHexSha256() {
        val key = ReviewCache.cacheKey(
            bookUrl = "https://book.example.com/123",
            chapterUrl = "https://book.example.com/123/ch1",
            buttonSrc = "btn-para-1",
        )

        assertEquals(64, key.length)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(key))

        val expected = MessageDigest.getInstance("SHA-256")
            .digest("https://book.example.com/123\nhttps://book.example.com/123/ch1\nbtn-para-1".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, key)
    }

    @Test
    fun cacheKeyTrimsChapterUrlAndButtonSrcWhitespace() {
        val canonical = ReviewCache.cacheKey(
            bookUrl = "https://book.example.com/1",
            chapterUrl = "https://book.example.com/1/c1",
            buttonSrc = "review:42",
        )
        val padded = ReviewCache.cacheKey(
            bookUrl = "https://book.example.com/1",
            chapterUrl = "  \thttps://book.example.com/1/c1\n ",
            buttonSrc = "\n  review:42\t ",
        )

        assertEquals(canonical, padded)
    }

    @Test
    fun cacheKeyIsolatesEachField() {
        val base = ReviewCache.cacheKey("book-1", "ch-1", "btn-1")

        assertNotEquals(base, ReviewCache.cacheKey("book-2", "ch-1", "btn-1"))
        assertNotEquals(base, ReviewCache.cacheKey("book-1", "ch-2", "btn-1"))
        assertNotEquals(base, ReviewCache.cacheKey("book-1", "ch-1", "btn-2"))
    }

    @Test
    fun newlineSeparatorPreventsConcatenationCollisions() {
        val k1 = ReviewCache.cacheKey("a", "bc", "d")
        val k2 = ReviewCache.cacheKey("ab", "c", "d")
        val k3 = ReviewCache.cacheKey("a", "b", "cd")

        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertNotEquals(k2, k3)
    }
}
