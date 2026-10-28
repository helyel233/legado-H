package io.legado.app.help

/**
 * 封面缩略图目录回收策略（纯函数）。
 *
 * 缩略图目录原来只增不减也没有保留期，长期使用会无限膨胀；
 * 这里把「删哪些」拆成纯函数，方便单测锁定顺序与边界。
 */
internal object CoverThumbnailPolicy {

    /** 目录中的一个文件（只取回收需要的三个属性，避免在策略里碰 File） */
    data class Entry(
        val name: String,
        val lastModified: Long,
        val length: Long
    )

    /**
     * 选出需要删除的文件。
     *
     * 先删超过保留期的；仍超上限时按最旧优先删到上限内。
     *
     * @return 要删除的文件名，按删除顺序
     */
    fun selectForRemoval(
        entries: List<Entry>,
        now: Long,
        maxAgeMs: Long,
        maxBytes: Long
    ): List<String> {
        val expired = entries.filter { now - it.lastModified > maxAgeMs }
        val alive = entries.filter { now - it.lastModified <= maxAgeMs }
        var total = alive.sumOf { it.length }
        if (total <= maxBytes) return expired.map { it.name }
        val removable = ArrayList<String>()
        alive.sortedBy { it.lastModified }.forEach { entry ->
            if (total <= maxBytes) return@forEach
            removable.add(entry.name)
            total -= entry.length
        }
        return expired.map { it.name } + removable
    }
}
