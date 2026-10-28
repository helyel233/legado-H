package io.legado.app.help

/**
 * 缓存有效期判定（纯函数）。
 *
 * 缓存有磁盘与内存两层：磁盘层按 deadline 判定，内存层此前完全不看 deadline，
 * 导致「设了 TTL 的缓存」在内存命中时把过期数据一直返回（TTL 形同虚设）。
 * 这里把两层的判定统一成一份可单测的规则。
 *
 * 约定：deadline <= 0 表示永不过期（与磁盘 Cache.deadline 的语义一致）。
 */
internal object CacheExpiryPolicy {

    /** 缓存条目的判定结果 */
    enum class Decision {
        /** 内存命中且未过期，直接返回 */
        MEMORY_HIT,

        /** 内存条目已过期，需要清掉并回落到磁盘 */
        MEMORY_EXPIRED,

        /** 内存没有条目，磁盘可用 */
        DISK_HIT,

        /** 内存与磁盘都没有可用数据 */
        MISS
    }

    /** 是否已过期：deadline <= 0 视为永不过期 */
    fun isExpired(deadline: Long, now: Long): Boolean {
        return deadline > 0L && deadline <= now
    }

    /** 内存层判定：没有条目算 MISS，过期算 MEMORY_EXPIRED */
    fun memoryDecision(hasEntry: Boolean, deadline: Long, now: Long): Decision {
        if (!hasEntry) return Decision.MISS
        return if (isExpired(deadline, now)) Decision.MEMORY_EXPIRED else Decision.MEMORY_HIT
    }

    /** 磁盘层判定：没有条目或已过期都算 MISS */
    fun diskDecision(hasEntry: Boolean, deadline: Long, now: Long): Decision {
        if (!hasEntry) return Decision.MISS
        return if (isExpired(deadline, now)) Decision.MISS else Decision.DISK_HIT
    }

    /**
     * 综合内存与磁盘判定缓存可否使用。
     *
     * @param hasMemoryEntry 内存里是否有该 key 的条目
     * @param memoryDeadline 内存条目的过期时间，0/缺省表示永不过期
     * @param hasDiskEntry 磁盘里是否有该 key 的条目
     * @param diskDeadline 磁盘条目的过期时间，0/缺省表示永不过期
     */
    fun decide(
        hasMemoryEntry: Boolean,
        memoryDeadline: Long,
        hasDiskEntry: Boolean,
        diskDeadline: Long,
        now: Long
    ): Decision {
        val memory = memoryDecision(hasMemoryEntry, memoryDeadline, now)
        return when (memory) {
            Decision.MEMORY_HIT -> Decision.MEMORY_HIT
            else -> diskDecision(hasDiskEntry, diskDeadline, now)
        }
    }
}
