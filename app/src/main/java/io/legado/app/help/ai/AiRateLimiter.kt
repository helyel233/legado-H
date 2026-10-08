package io.legado.app.help.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 全局 AI 请求限流（令牌桶）+ 指数退避计算。
 *
 * 所有走 AiChatService 的模型请求共享一个桶，避免并发场景（agent 多轮 + 朗读多角色 + 总结）
 * 同时打满上游触发 429。默认突发 6 个、每秒补充 1.5 个。
 */
object AiRateLimiter {

    private const val BURST = 6.0
    private const val REFILL_PER_SECOND = 1.5
    private const val MAX_WAIT_MILLIS = 30_000L

    private val mutex = Mutex()
    private var tokens = BURST
    private var lastRefillAt = System.nanoTime()

    /** 获取令牌，不足时挂起等待，不做无限自旋 */
    suspend fun acquire(permits: Double = 1.0) {
        var waitMillis: Long
        while (true) {
            waitMillis = mutex.withLock {
                refill()
                if (tokens >= permits) {
                    tokens -= permits
                    0L
                } else {
                    val needed = permits - tokens
                    ((needed / REFILL_PER_SECOND) * 1000).toLong()
                        .coerceIn(50L, MAX_WAIT_MILLIS)
                }
            }
            if (waitMillis <= 0L) return
            delay(waitMillis)
        }
    }

    /**
     * 计算第 attempt 次重试（从 0 开始）的退避时长：指数增长 + 最多 30% 的随机抖动。
     */
    fun backoffDelayMillis(
        attempt: Int,
        baseMillis: Long = 1_000L,
        maxMillis: Long = 20_000L
    ): Long {
        val safeAttempt = attempt.coerceIn(0, 10)
        val exponential = baseMillis * (1L shl safeAttempt)
        val jitter = (Random.nextDouble() * 0.3 * exponential).toLong()
        return (exponential + jitter).coerceAtMost(maxMillis)
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillAt) / 1_000_000_000.0
        if (elapsedSeconds > 0) {
            tokens = (tokens + elapsedSeconds * REFILL_PER_SECOND).coerceAtMost(BURST)
            lastRefillAt = now
        }
    }
}
