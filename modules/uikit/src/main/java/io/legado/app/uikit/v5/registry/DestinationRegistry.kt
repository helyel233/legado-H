package io.legado.app.uikit.v5.registry

import io.legado.app.uikit.v5.contract.AppDestination
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.SideGroup

/**
 * P0-3 页面注册表（docs/ui-v5-skeleton-first.md 2.3）。
 *
 * **Tab 只有一个真相。** 宿主的一切导航产物都从这张表生成：
 * 底栏菜单、侧栏菜单、顶栏、内容路由、二级入口 —— 生成逻辑只有一份。
 *
 * 于是"加一个 Tab / 换顺序 / 精简成 3 Tab / Tab 变侧栏项"全部变成改表上的一行，
 * 不会再出现现状那种三处真相（MainBottomNavConfig + MainActivity 手写映射 + 侧栏镜像）。
 */
class DestinationRegistry(
    destinations: List<AppDestination>,
    /** 可见性与顺序的持久化；P0 用内存实现，P1 由 app 侧接 SP。 */
    private val store: DestOrderStore = InMemoryDestOrderStore(),
) {
    private val byIdMap: Map<String, AppDestination> = destinations.associateBy { it.id }

    /** 全部注册项（按 order 稳定排序） */
    val all: List<AppDestination> = destinations.sortedWith(compareBy({ it.order }, { it.id }))

    init {
        // 首次运行：以注册时声明的 defaultVisible 初始化可见性
        // （对应现状"6 项规格、默认只显示 3 项"），之后完全听用户的。
        if (!store.isInitialized()) {
            destinations.forEach { store.setVisible(it.id, it.defaultVisible) }
            store.markInitialized()
        }
    }

    fun byId(id: String): AppDestination? = byIdMap[id]

    fun content(id: String): AppContent? = byId(id)?.factory?.invoke()

    /** 可作为主界面 Tab 的项 */
    val topLevel: List<AppDestination> get() = all.filter { it.topLevel }

    /** 当前可见的 Tab（锁定项强制可见） */
    fun visibleTabs(): List<AppDestination> {
        val hidden = store.hiddenIds()
        return topLevel.filter { it.lockedVisible || it.id !in hidden }
            .let { list -> store.orderIds().let { order -> reorder(list, order) } }
    }

    /** 侧栏分组（分组内按 order） */
    fun sideItems(): Map<SideGroup, List<AppDestination>> =
        all.filter { it.sideGroup != SideGroup.NONE }
            .groupBy { it.sideGroup }
            .mapValues { (_, v) -> v.sortedBy { it.order } }

    /** 被隐藏的 Tab 的二级入口（现在"发现/订阅/统计"这类收敛后的去处） */
    fun hiddenTabs(): List<AppDestination> {
        val hidden = store.hiddenIds()
        return topLevel.filter { !it.lockedVisible && it.id in hidden }
    }

    fun setVisible(id: String, visible: Boolean) {
        val d = byId(id) ?: return
        if (d.lockedVisible && !visible) return
        store.setVisible(id, visible)
    }

    /** 把 id 移动到 targetId 之前；targetId 为空表示移到末尾。 */
    fun moveBefore(id: String, targetId: String?) {
        store.setOrder(visibleTabs().map { it.id }.toMutableList().apply {
            remove(id)
            val at = if (targetId == null) size else indexOf(targetId).coerceAtLeast(0)
            add(at, id)
        })
    }

    /** 搜索（设置搜索与全局搜索共用） */
    fun search(query: String): List<AppDestination> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.filter { d ->
            d.keywords.any { it.lowercase().contains(q) } || d.id.lowercase().contains(q)
        }
    }

    private fun reorder(list: List<AppDestination>, order: List<String>): List<AppDestination> {
        if (order.isEmpty()) return list
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return list.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.order }))
    }
}

/** 顺序与可见性的持久化抽象。 */
interface DestOrderStore {
    /** 是否已经写入过初始状态；false 时由注册表按 defaultVisible 播种。 */
    fun isInitialized(): Boolean
    fun markInitialized()
    fun hiddenIds(): Set<String>
    fun setVisible(id: String, visible: Boolean)
    fun orderIds(): List<String>
    fun setOrder(ids: List<String>)
}

/** P0 默认：内存实现（不持久化，重启回到默认）。P1 由 app 侧换成 SP 实现。 */
class InMemoryDestOrderStore : DestOrderStore {
    private val hidden = mutableSetOf<String>()
    private var order: List<String> = emptyList()
    private var initialized = false

    override fun isInitialized(): Boolean = initialized
    override fun markInitialized() {
        initialized = true
    }
    override fun hiddenIds(): Set<String> = hidden.toSet()
    override fun setVisible(id: String, visible: Boolean) {
        if (visible) hidden.remove(id) else hidden.add(id)
    }
    override fun orderIds(): List<String> = order
    override fun setOrder(ids: List<String>) {
        order = ids.toList()
    }
}
