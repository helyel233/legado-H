package io.legado.app.ui.rss

import androidx.recyclerview.widget.DiffUtil

/**
 * 订阅源栏目页签的业务键。
 *
 * 将栏目名称 [sortName]、栏目地址 [sortUrl] 与搜索词 [searchKey] 一起纳入标识，
 * 以便搜索词变化时生成全新 `itemId` 重建搜索结果 Fragment，而普通栏目顺序调整或增删时
 * 未变更的栏目保持同一 `itemId` 与 Fragment 实例存活。
 * [occurrence] 仅在异常源规则输出重复 `(sortName, sortUrl)` 时区分同名同址页签，
 * 避免 `FragmentStateAdapter` 出现重复稳定 ID。
 */
data class RssSortTabKey(
    val sortName: String,
    val sortUrl: String,
    val searchKey: String?,
    val occurrence: Int = 0
)

/**
 * `RssSortActivity` 的页签项快照，绑定业务键 [key] 与稳定 [itemId]。
 */
data class RssTabItem(
    val key: RssSortTabKey,
    val itemId: Long
) {
    val sortName: String get() = key.sortName
    val sortUrl: String get() = key.sortUrl
    val searchKey: String? get() = key.searchKey
}

/**
 * 纯 JVM 无碰撞稳定 ID 注册表。
 *
 * 使用 `HashMap<K, Long>` 基于键的 `equals` 精确映射，不受 `hashCode()` 碰撞影响；
 * [nextId] 从 `1L` 起单调递增（避开 `RecyclerView.NO_ID = -1L`），确保已移除或失效的页签
 * 不会与后续新页签复用同一 `itemId`。
 */
class RssTabIdRegistry<K : Any>(
    initialNextId: Long = 1L
) {
    private var nextId: Long = initialNextId.coerceAtLeast(1L)
    private val keyToId = HashMap<K, Long>()

    /**
     * 获取 [key] 对应的稳定 `itemId`；若尚未注册则分配下一个单调递增 ID。
     */
    fun idFor(key: K): Long = keyToId.getOrPut(key) { nextId++ }

    /**
     * 查询当前已注册的 `itemId`，未注册时返回 `null`。
     */
    fun findId(key: K): Long? = keyToId[key]

    /**
     * 仅保留当前活跃集合 [activeKeys] 的映射，回收已移除页签占用的键。
     * 不回退 [nextId]，避免旧页签移除后再次出现时与尚未提交的销毁事务冲突。
     */
    fun retainAll(activeKeys: Collection<K>) {
        if (activeKeys.isEmpty()) {
            keyToId.clear()
            return
        }
        val activeSet = activeKeys as? Set<K> ?: activeKeys.toHashSet()
        keyToId.keys.retainAll(activeSet)
    }

    /**
     * 使当前所有映射失效（例如切换文章排版样式时需要强制重建全部 Fragment）。
     * 保留单调递增的 [nextId]，确保新分配的 ID 永不与旧 ID 重叠。
     */
    fun invalidateAll() {
        keyToId.clear()
    }
}

/**
 * 根据栏目列表 [sorts] 与当前 [searchKey] 生成带稳定 `itemId` 的 [RssTabItem] 列表，
 * 并同步清理已移除页签在 [registry] 中的旧映射。
 */
fun buildRssSortTabItems(
    sorts: List<Pair<String, String>>,
    searchKey: String?,
    registry: RssTabIdRegistry<RssSortTabKey>
): List<RssTabItem> {
    if (sorts.isEmpty()) {
        registry.retainAll(emptyList())
        return emptyList()
    }
    val seenCounts = HashMap<Pair<String, String>, Int>(sorts.size)
    val keys = ArrayList<RssSortTabKey>(sorts.size)
    for (sort in sorts) {
        val occurrence = seenCounts[sort] ?: 0
        seenCounts[sort] = occurrence + 1
        keys.add(
            RssSortTabKey(
                sortName = sort.first,
                sortUrl = sort.second,
                searchKey = searchKey,
                occurrence = occurrence
            )
        )
    }
    registry.retainAll(keys)
    return keys.map { key ->
        RssTabItem(key = key, itemId = registry.idFor(key))
    }
}

/**
 * 通用页签 `DiffUtil.Callback`，以稳定 `itemId` 判定是否为同一页签。
 */
class RssTabDiffCallback<T>(
    private val oldItems: List<T>,
    private val newItems: List<T>,
    private val itemIdOf: (T) -> Long
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldItems.size

    override fun getNewListSize(): Int = newItems.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        itemIdOf(oldItems[oldItemPosition]) == itemIdOf(newItems[newItemPosition])

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        oldItems[oldItemPosition] == newItems[newItemPosition]
}
