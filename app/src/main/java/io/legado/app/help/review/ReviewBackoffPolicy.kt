package io.legado.app.help.review

/**
 * 段评预热失败退避策略（纯函数）。
 *
 * 退避表是「这一章抓失败了，10 分钟内别再试」，键必须能区分同一本书的不同章节；
 * 之前用章节序号做键，换源后同一个序号指向另一章，会被旧章的退避白白跳过。
 */
internal object ReviewBackoffPolicy {

    /** 退避表键：bookUrl + 章节 URL（换源后 URL 会变，因此不会误伤） */
    fun key(bookUrl: String, chapterUrl: String): String {
        return "$bookUrl|$chapterUrl"
    }

    /** 是否已过退避截止时间 */
    fun isInBackoff(deadline: Long?, now: Long): Boolean {
        return deadline != null && now < deadline
    }

    /** 退避表超过上限时才需要清理 */
    fun shouldTrim(size: Int, maxEntries: Int): Boolean {
        return size > maxEntries
    }

    /**
     * 已经过期（可以清掉）的退避条目。
     *
     * 边界与 [isInBackoff] 对齐：deadline == now 时已经不在退避中，同样可以清掉。
     */
    fun expiredKeys(entries: Map<String, Long>, now: Long): List<String> {
        return entries.filterValues { it <= now }.keys.toList()
    }
}
