package io.legado.app.ui.main.bookshelf.style1

import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.BookGroup

data class BookshelfTabItem(
    val itemId: Long,
    val group: BookGroup
)

/**
 * Maps `(structureVersion, groupId)` pairs to unique positive `Long` adapter IDs.
 * Negative built-in [BookGroup] IDs (`-1L..-6L`, `-11L`, `-100L`) and arbitrary
 * 64-bit bitmask custom IDs are mapped without collisions, while bumping
 * `structureVersion` yields fresh IDs so layout-mode changes recreate tabs cleanly.
 */
class BookshelfTabRegistry {

    private var nextId = 1L
    private val itemIds = HashMap<Pair<Int, Long>, Long>()

    fun itemIdFor(structureVersion: Int, groupId: Long): Long {
        return itemIds.getOrPut(structureVersion to groupId) {
            nextId++
        }
    }

    fun buildItems(structureVersion: Int, groups: List<BookGroup>): List<BookshelfTabItem> {
        val activeKeys = HashSet<Pair<Int, Long>>(groups.size)
        val items = groups.map { group ->
            activeKeys.add(structureVersion to group.groupId)
            BookshelfTabItem(
                itemId = itemIdFor(structureVersion, group.groupId),
                group = group
            )
        }
        itemIds.keys.retainAll(activeKeys)
        return items
    }

    internal fun trackedCount(): Int = itemIds.size
}

class BookshelfTabDiffCallback(
    private val oldItems: List<BookshelfTabItem>,
    private val newItems: List<BookshelfTabItem>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldItems.size

    override fun getNewListSize(): Int = newItems.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldItems[oldItemPosition].itemId == newItems[newItemPosition].itemId
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldItems[oldItemPosition].group == newItems[newItemPosition].group
    }

    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any = Unit
}
