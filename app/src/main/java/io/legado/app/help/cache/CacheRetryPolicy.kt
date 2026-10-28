package io.legado.app.help.cache

/**
 * 缓存终态副作用的重试预算（纯函数）。
 *
 * 终态副作用失败会按退避重试，但没有上限时会变成常驻协程每 15 秒空转一次。
 */
internal object CacheRetryPolicy {

    /** 最大重试次数 */
    const val MAX_TERMINAL_EFFECT_RETRY = 20

    /** 是否应该放弃（attempt 从 1 开始计数） */
    fun shouldStop(attempt: Int, maxAttempts: Int = MAX_TERMINAL_EFFECT_RETRY): Boolean {
        return attempt > maxAttempts
    }
}
