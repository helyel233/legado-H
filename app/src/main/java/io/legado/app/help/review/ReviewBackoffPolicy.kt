package io.legado.app.help.review

/**
 * 段评预热失败退避策略（纯函数）。
 *
 * 退避表是「这一章抓失败了，先别再试」，键必须能区分同一本书的不同章节；
 * 之前用章节序号做键，换源后同一个序号指向另一章，会被旧章的退避白白跳过。
 *
 * 退避时长按失败次数指数增长：源规则坏掉、页面结构改版这类失败是持续性的，
 * 固定 10 分钟一条意味着一天要白白重抓上百轮（每轮都要起 WebView 跑 click/js）。
 * 首次仍从 [BASE_DELAY_MS] 起，避免把偶发的网络抖动放大成长期放弃。
 */
internal object ReviewBackoffPolicy {

    /** 首次失败的退避时长：10 分钟，与顺序翻章的节奏相当 */
    const val BASE_DELAY_MS = 10 * 60 * 1000L

    /** 退避上限：2 小时。再长会让「源临时挂了」这类可恢复失败迟迟等不到重试 */
    const val MAX_DELAY_MS = 2 * 60 * 60 * 1000L

    /** 失败次数上限：只参与算退避时长，超过后一律按上限计 */
    const val MAX_ATTEMPTS = 6

    /** 一条退避记录：连续失败次数 + 退避截止时间戳 */
    data class Failure(val attempts: Int, val until: Long)

    /** 退避表键：bookUrl + 章节 URL（换源后 URL 会变，因此不会误伤） */
    fun key(bookUrl: String, chapterUrl: String): String {
        return "$bookUrl|$chapterUrl"
    }

    /** 是否已过退避截止时间 */
    fun isInBackoff(failure: Failure?, now: Long): Boolean {
        return failure != null && now < failure.until
    }

    /**
     * 记一次失败后的退避状态：次数累加，截止时间按 [delayFor] 指数后推。
     * 没有上一次记录时视为首次失败。
     */
    fun onFailure(previous: Failure?, now: Long): Failure {
        val attempts = ((previous?.attempts ?: 0) + 1).coerceAtMost(MAX_ATTEMPTS)
        return Failure(attempts, now + delayFor(attempts))
    }

    /**
     * 第 [attempts] 次连续失败对应的退避时长：10min、20min、40min……直到上限。
     * 用移位而不是 Math.pow，避免浮点误差与溢出。
     */
    fun delayFor(attempts: Int): Long {
        if (attempts <= 0) return 0L
        val shift = (attempts - 1).coerceAtMost(MAX_ATTEMPTS - 1)
        // 先截断再相乘：MAX_ATTEMPTS = 6 时最大 32 倍，不会溢出 Long
        return (BASE_DELAY_MS * (1L shl shift)).coerceAtMost(MAX_DELAY_MS)
    }

    /** 退避表超过上限时才需要清理 */
    fun shouldTrim(size: Int, maxEntries: Int): Boolean {
        return size > maxEntries
    }

    /**
     * 已经过期（可以清掉）的退避条目。
     *
     * 边界与 [isInBackoff] 对齐：until == now 时已经不在退避中，同样可以清掉。
     */
    fun expiredKeys(entries: Map<String, Failure>, now: Long): List<String> {
        return entries.filterValues { it.until <= now }.keys.toList()
    }

    /**
     * 仍需保留的条目：先丢过期，超过 [maxEntries] 时优先丢「最早解禁」的。
     * 越晚解禁说明失败越顽固，越该留在表里继续压着不抓。
     */
    fun retained(entries: Map<String, Failure>, now: Long, maxEntries: Int): Map<String, Failure> {
        val alive = entries.filterValues { it.until > now }
        if (alive.size <= maxEntries) return alive
        return alive.entries
            .sortedByDescending { it.value.until }
            .take(maxEntries)
            .associate { it.key to it.value }
    }
}
