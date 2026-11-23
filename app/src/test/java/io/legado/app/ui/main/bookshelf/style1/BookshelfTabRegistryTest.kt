package io.legado.app.ui.main.bookshelf.style1

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.main.MainTabDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfTabRegistryTest {

    private class RecordingUpdateCallback : ListUpdateCallback {
        val inserted = mutableListOf<Pair<Int, Int>>()
        val removed = mutableListOf<Pair<Int, Int>>()
        val moved = mutableListOf<Pair<Int, Int>>()
        val changed = mutableListOf<Pair<Int, Int>>()

        override fun onInserted(position: Int, count: Int) {
            inserted.add(position to count)
        }

        override fun onRemoved(position: Int, count: Int) {
            removed.add(position to count)
        }

        override fun onMoved(fromPosition: Int, toPosition: Int) {
            moved.add(fromPosition to toPosition)
        }

        override fun onChanged(position: Int, count: Int, payload: Any?) {
            changed.add(position to count)
        }
    }

    private fun group(id: Long, name: String = "G$id", order: Int = 0): BookGroup {
        return BookGroup(groupId = id, groupName = name, order = order)
    }

    @Test
    fun builtInNegativeAndPositiveCustomIdsProduceDistinctPositiveItemIds() {
        val registry = BookshelfTabRegistry()
        val groupIds = listOf(
            BookGroup.IdRoot,
            BookGroup.IdError,
            BookGroup.IdVideo,
            BookGroup.IdLocalNone,
            BookGroup.IdNetNone,
            BookGroup.IdAudio,
            BookGroup.IdLocal,
            BookGroup.IdAll,
            0L,
            1L,
            2L,
            4L,
            1L shl 62,
            Long.MAX_VALUE,
            Long.MIN_VALUE
        )
        val items = registry.buildItems(
            structureVersion = 0,
            groups = groupIds.map { group(it) }
        )

        val itemIds = items.map { it.itemId }
        assertTrue(itemIds.all { it > 0L })
        assertEquals(groupIds.size, itemIds.toSet().size)
    }

    @Test
    fun upGroupReorderingInsertingAndRemovingPreservesExistingItemIds() {
        val registry = BookshelfTabRegistry()
        val gAll = group(BookGroup.IdAll, "All", order = 0)
        val gLocal = group(BookGroup.IdLocal, "Local", order = 1)
        val gAudio = group(BookGroup.IdAudio, "Audio", order = 2)
        val gCustom1 = group(1L, "Fantasy", order = 3)
        val gCustom2 = group(2L, "SciFi", order = 4)

        val initial = registry.buildItems(0, listOf(gAll, gLocal, gAudio, gCustom1))
        val initialIdByGroup = initial.associate { it.group.groupId to it.itemId }

        // Remove gLocal, reorder gCustom1 before gAudio, insert gCustom2
        val updated = registry.buildItems(0, listOf(gAll, gCustom1, gAudio, gCustom2))

        assertEquals(initialIdByGroup[BookGroup.IdAll], updated[0].itemId)
        assertEquals(initialIdByGroup[1L], updated[1].itemId)
        assertEquals(initialIdByGroup[BookGroup.IdAudio], updated[2].itemId)
        assertTrue(updated[3].itemId > 0L)
        assertTrue(updated[3].itemId !in initialIdByGroup.values)

        val callback = RecordingUpdateCallback()
        DiffUtil.calculateDiff(BookshelfTabDiffCallback(initial, updated))
            .dispatchUpdatesTo(callback)

        // gLocal removed (1), gCustom2 inserted (1), gCustom1/gAudio reordered (moved), no full change/reset
        assertEquals(1, callback.removed.sumOf { it.second })
        assertEquals(1, callback.inserted.sumOf { it.second })
        assertTrue(callback.moved.isNotEmpty())
        assertTrue(callback.changed.isEmpty())
    }

    @Test
    fun upGroupMetadataChangeReportsItemChangeWithSameStableItemId() {
        val registry = BookshelfTabRegistry()
        val initial = registry.buildItems(0, listOf(group(BookGroup.IdAll, "All"), group(1L, "OldName")))
        val renamed = registry.buildItems(0, listOf(group(BookGroup.IdAll, "All"), group(1L, "NewName")))

        assertEquals(initial[0].itemId, renamed[0].itemId)
        assertEquals(initial[1].itemId, renamed[1].itemId)

        val callback = RecordingUpdateCallback()
        DiffUtil.calculateDiff(BookshelfTabDiffCallback(initial, renamed))
            .dispatchUpdatesTo(callback)

        assertTrue(callback.inserted.isEmpty())
        assertTrue(callback.removed.isEmpty())
        assertEquals(listOf(1 to 1), callback.changed)
    }

    @Test
    fun bumpingStructureVersionGeneratesFreshItemIdsForAllGroups() {
        val registry = BookshelfTabRegistry()
        val groups = listOf(
            group(BookGroup.IdAll, "All"),
            group(BookGroup.IdLocal, "Local"),
            group(1L, "Custom")
        )

        val v0 = registry.buildItems(0, groups)
        val v1 = registry.buildItems(1, groups)

        assertEquals(v0.size, v1.size)
        for (i in groups.indices) {
            assertNotEquals(v0[i].itemId, v1[i].itemId)
            assertTrue(v1[i].itemId > 0L)
        }
        assertEquals(6, (v0 + v1).map { it.itemId }.toSet().size)
    }

    @Test
    fun buildItemsPrunesStaleKeysAcrossStructureVersionAndGroupRemovals() {
        val registry = BookshelfTabRegistry()
        val gAll = group(BookGroup.IdAll, "All")
        val gLocal = group(BookGroup.IdLocal, "Local")
        val gCustom = group(1L, "Custom")

        registry.buildItems(0, listOf(gAll, gLocal, gCustom))
        assertEquals(3, registry.trackedCount())

        registry.buildItems(0, listOf(gAll, gCustom))
        assertEquals(2, registry.trackedCount())

        registry.buildItems(1, listOf(gAll))
        assertEquals(1, registry.trackedCount())

        registry.buildItems(1, emptyList())
        assertEquals(0, registry.trackedCount())
    }

    @Test
    fun mainTabDiffPreservesUnchangedTabsAcrossMenuUpdatesAndBookshelfStyleSwitch() {
        val idHomepage = 5
        val idBookshelf1 = 11
        val idBookshelf2 = 12
        val idExplore = 1
        val idRss = 2
        val idMy = 4

        // Hide Homepage and switch Bookshelf from style 1 to style 2
        val oldTabs = listOf(idHomepage, idBookshelf1, idExplore, idRss, idMy)
        val newTabs = listOf(idBookshelf2, idExplore, idRss, idMy)

        val callback = RecordingUpdateCallback()
        MainTabDiff.calculateDiff(oldTabs, newTabs).dispatchUpdatesTo(callback)

        // Homepage + idBookshelf1 removed (2), idBookshelf2 inserted (1); Explore/Rss/My untouched
        assertEquals(2, callback.removed.sumOf { it.second })
        assertEquals(1, callback.inserted.sumOf { it.second })
        assertTrue(callback.changed.isEmpty())
    }
}
