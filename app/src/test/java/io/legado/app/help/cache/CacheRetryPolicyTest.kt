package io.legado.app.help.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存终态副作用重试预算的回归网
 */
class CacheRetryPolicyTest {

    @Test
    fun `retrying continues within the budget`() {
        assertFalse(CacheRetryPolicy.shouldStop(1))
        assertFalse(CacheRetryPolicy.shouldStop(10))
        assertFalse(CacheRetryPolicy.shouldStop(CacheRetryPolicy.MAX_TERMINAL_EFFECT_RETRY))
    }

    @Test
    fun `retrying stops after the budget is exhausted`() {
        assertTrue(CacheRetryPolicy.shouldStop(CacheRetryPolicy.MAX_TERMINAL_EFFECT_RETRY + 1))
        assertTrue(CacheRetryPolicy.shouldStop(1000))
    }

    @Test
    fun `custom budget is respected`() {
        assertFalse(CacheRetryPolicy.shouldStop(attempt = 3, maxAttempts = 3))
        assertTrue(CacheRetryPolicy.shouldStop(attempt = 4, maxAttempts = 3))
    }

    @Test
    fun `default budget is the shared constant`() {
        assertEquals(20, CacheRetryPolicy.MAX_TERMINAL_EFFECT_RETRY)
    }
}
