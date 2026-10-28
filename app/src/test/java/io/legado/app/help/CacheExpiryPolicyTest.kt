package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存有效期判定的回归网
 */
class CacheExpiryPolicyTest {

    @Test
    fun `zero deadline never expires`() {
        assertFalse(CacheExpiryPolicy.isExpired(0L, 1000L))
        assertFalse(CacheExpiryPolicy.isExpired(-1L, 1000L))
    }

    @Test
    fun `deadline in the future is not expired`() {
        assertFalse(CacheExpiryPolicy.isExpired(2000L, 1000L))
    }

    @Test
    fun `deadline reached or passed is expired`() {
        assertTrue(CacheExpiryPolicy.isExpired(1000L, 1000L))
        assertTrue(CacheExpiryPolicy.isExpired(500L, 1000L))
    }

    @Test
    fun `memory decision distinguishes missing expired and valid`() {
        assertEquals(
            CacheExpiryPolicy.Decision.MISS,
            CacheExpiryPolicy.memoryDecision(hasEntry = false, deadline = 0L, now = 100L)
        )
        assertEquals(
            CacheExpiryPolicy.Decision.MEMORY_EXPIRED,
            CacheExpiryPolicy.memoryDecision(hasEntry = true, deadline = 50L, now = 100L)
        )
        assertEquals(
            CacheExpiryPolicy.Decision.MEMORY_HIT,
            CacheExpiryPolicy.memoryDecision(hasEntry = true, deadline = 500L, now = 100L)
        )
        assertEquals(
            CacheExpiryPolicy.Decision.MEMORY_HIT,
            CacheExpiryPolicy.memoryDecision(hasEntry = true, deadline = 0L, now = 100L)
        )
    }

    @Test
    fun `disk decision treats expired as missing`() {
        assertEquals(
            CacheExpiryPolicy.Decision.MISS,
            CacheExpiryPolicy.diskDecision(hasEntry = false, deadline = 0L, now = 100L)
        )
        assertEquals(
            CacheExpiryPolicy.Decision.MISS,
            CacheExpiryPolicy.diskDecision(hasEntry = true, deadline = 50L, now = 100L)
        )
        assertEquals(
            CacheExpiryPolicy.Decision.DISK_HIT,
            CacheExpiryPolicy.diskDecision(hasEntry = true, deadline = 500L, now = 100L)
        )
    }

    @Test
    fun `expired memory falls back to disk`() {
        assertEquals(
            CacheExpiryPolicy.Decision.DISK_HIT,
            CacheExpiryPolicy.decide(
                hasMemoryEntry = true,
                memoryDeadline = 50L,
                hasDiskEntry = true,
                diskDeadline = 500L,
                now = 100L
            )
        )
    }

    @Test
    fun `valid memory wins over disk`() {
        assertEquals(
            CacheExpiryPolicy.Decision.MEMORY_HIT,
            CacheExpiryPolicy.decide(
                hasMemoryEntry = true,
                memoryDeadline = 500L,
                hasDiskEntry = true,
                diskDeadline = 0L,
                now = 100L
            )
        )
    }

    @Test
    fun `expired memory and expired disk means miss`() {
        assertEquals(
            CacheExpiryPolicy.Decision.MISS,
            CacheExpiryPolicy.decide(
                hasMemoryEntry = true,
                memoryDeadline = 50L,
                hasDiskEntry = true,
                diskDeadline = 50L,
                now = 100L
            )
        )
    }

    @Test
    fun `no entry anywhere means miss`() {
        assertEquals(
            CacheExpiryPolicy.Decision.MISS,
            CacheExpiryPolicy.decide(
                hasMemoryEntry = false,
                memoryDeadline = 0L,
                hasDiskEntry = false,
                diskDeadline = 0L,
                now = 100L
            )
        )
    }
}
