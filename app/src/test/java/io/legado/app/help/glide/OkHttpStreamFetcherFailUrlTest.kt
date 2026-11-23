package io.legado.app.help.glide

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpStreamFetcherFailUrlTest {

    @Before
    fun setUp() {
        OkHttpStreamFetcher.failUrl.clear()
    }

    @After
    fun tearDown() {
        OkHttpStreamFetcher.failUrl.clear()
    }

    @Test
    fun `failed url expires automatically after ttl`() {
        var now = 10_000L
        val cache = FailedUrlCache(
            maxEntries = 16,
            ttlMs = 60_000L,
            timeProvider = { now }
        )

        cache.add("https://example.com/cover1.jpg")
        assertTrue(cache.contains("https://example.com/cover1.jpg"))

        now += 59_999L
        assertTrue("URL should still be cached before TTL elapses", cache.contains("https://example.com/cover1.jpg"))

        now += 1L
        assertFalse("URL should auto-expire once TTL elapses", cache.contains("https://example.com/cover1.jpg"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `capacity is bounded and evicts least recently used url`() {
        var now = 1_000L
        val cache = FailedUrlCache(
            maxEntries = 3,
            ttlMs = 60_000L,
            timeProvider = { now }
        )

        cache.add("https://example.com/1.jpg")
        cache.add("https://example.com/2.jpg")
        cache.add("https://example.com/3.jpg")
        assertEquals(3, cache.size())

        // Touch 1.jpg so 2.jpg becomes least recently used
        assertTrue(cache.contains("https://example.com/1.jpg"))

        cache.add("https://example.com/4.jpg")
        assertEquals(3, cache.size())
        assertFalse("Least recently used 2.jpg should be evicted", cache.contains("https://example.com/2.jpg"))
        assertTrue(cache.contains("https://example.com/1.jpg"))
        assertTrue(cache.contains("https://example.com/3.jpg"))
        assertTrue(cache.contains("https://example.com/4.jpg"))
    }

    @Test
    fun `successful load removes url from failure cache immediately`() {
        var now = 5_000L
        val cache = FailedUrlCache(
            maxEntries = 16,
            ttlMs = 60_000L,
            timeProvider = { now }
        )

        val url = "https://example.com/transient.jpg"
        cache.add(url)
        assertTrue(cache.contains(url))

        cache.remove(url)
        assertFalse(cache.contains(url))
        assertEquals(0, cache.size())
    }

    @Test
    fun `fetcher companion failUrl cache enforces bounded capacity`() {
        OkHttpStreamFetcher.failUrl.clear()
        try {
            for (i in 1..(FailedUrlCache.DEFAULT_MAX_FAIL_URLS + 50)) {
                OkHttpStreamFetcher.failUrl.add("https://example.com/img-$i.jpg")
            }
            assertEquals(FailedUrlCache.DEFAULT_MAX_FAIL_URLS, OkHttpStreamFetcher.failUrl.size())
            assertFalse(OkHttpStreamFetcher.failUrl.contains("https://example.com/img-1.jpg"))
            assertTrue(OkHttpStreamFetcher.failUrl.contains("https://example.com/img-${FailedUrlCache.DEFAULT_MAX_FAIL_URLS + 50}.jpg"))
        } finally {
            OkHttpStreamFetcher.failUrl.clear()
        }
    }
}
