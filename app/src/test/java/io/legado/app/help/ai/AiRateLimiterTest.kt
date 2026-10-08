package io.legado.app.help.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class AiRateLimiterTest {

    @Test
    fun `backoff delay grows with attempt and respects cap`() {
        val first = AiRateLimiter.backoffDelayMillis(0)
        val second = AiRateLimiter.backoffDelayMillis(1)
        val big = AiRateLimiter.backoffDelayMillis(8)
        assertTrue("first=$first", first >= 1_000L && first <= 1_300L)
        assertTrue("second=$second", second >= 2_000L && second <= 2_600L)
        assertTrue("big=$big", big == 20_000L)
    }

    @Test
    fun `custom base and cap are respected`() {
        val delay = AiRateLimiter.backoffDelayMillis(3, baseMillis = 500L, maxMillis = 2_000L)
        assertTrue("delay=$delay", delay == 2_000L)
        val small = AiRateLimiter.backoffDelayMillis(0, baseMillis = 500L, maxMillis = 5_000L)
        assertTrue("small=$small", small >= 500L && small <= 650L)
    }

    @Test
    fun `negative attempt is clamped`() {
        val delay = AiRateLimiter.backoffDelayMillis(-3)
        assertTrue("delay=$delay", delay >= 1_000L && delay <= 1_300L)
    }
}
