package io.legado.app.ui.rss

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssTabIdRegistryTest {

    @Test
    fun `distinct keys with identical hashCode receive distinct monotonic itemIds`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val registry = RssTabIdRegistry<String>()

        val idAa = registry.idFor("Aa")
        val idBb = registry.idFor("BB")
        val idOther = registry.idFor("CC")

        assertTrue(idAa > 0L)
        assertTrue(idBb > idAa)
        assertTrue(idOther > idBb)
        assertEquals(idAa, registry.idFor("Aa"))
        assertEquals(idBb, registry.idFor("BB"))

        val sortRegistry = RssTabIdRegistry<RssSortTabKey>()
        val sortAa = RssSortTabKey(sortName = "Aa", sortUrl = "https://example.com/1", searchKey = null)
        val sortBb = RssSortTabKey(sortName = "BB", sortUrl = "https://example.com/1", searchKey = null)
        assertEquals(sortAa.hashCode(), sortBb.hashCode())

        val sortIdAa = sortRegistry.idFor(sortAa)
        val sortIdBb = sortRegistry.idFor(sortBb)
        assertTrue(sortIdBb > sortIdAa)
    }

    @Test
    fun `unchanged keys retain exact same itemId across submitTabs and DiffUtil`() {
        val registry = RssTabIdRegistry<RssSortTabKey>()
        val initialTabs = buildRssSortTabItems(
            sorts = listOf(
                "头条" to "https://example.com/top",
                "科技" to "https://example.com/tech",
                "体育" to "https://example.com/sports"
            ),
            searchKey = null,
            registry = registry
        )

        val updatedTabs = buildRssSortTabItems(
            sorts = listOf(
                "体育" to "https://example.com/sports",
                "财经" to "https://example.com/finance",
                "头条" to "https://example.com/top"
            ),
            searchKey = null,
            registry = registry
        )

        val initialByUrl = initialTabs.associateBy { it.sortUrl }
        val updatedByUrl = updatedTabs.associateBy { it.sortUrl }

        assertEquals(
            initialByUrl.getValue("https://example.com/top").itemId,
            updatedByUrl.getValue("https://example.com/top").itemId
        )
        assertEquals(
            initialByUrl.getValue("https://example.com/sports").itemId,
            updatedByUrl.getValue("https://example.com/sports").itemId
        )
        assertTrue(
            updatedByUrl.getValue("https://example.com/finance").itemId >
                initialTabs.maxOf { it.itemId }
        )

        val events = mutableListOf<String>()
        val diff = DiffUtil.calculateDiff(RssTabDiffCallback(initialTabs, updatedTabs) { it.itemId })
        diff.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) {
                events.add("insert:$position:$count")
            }

            override fun onRemoved(position: Int, count: Int) {
                events.add("remove:$position:$count")
            }

            override fun onMoved(fromPosition: Int, toPosition: Int) {
                events.add("move:$fromPosition->$toPosition")
            }

            override fun onChanged(position: Int, count: Int, payload: Any?) {
                events.add("change:$position:$count")
            }
        })

        // 体育 moved, 科技 removed, 财经 inserted, 头条 unchanged in identity
        assertTrue(events.any { it.startsWith("remove:") })
        assertTrue(events.any { it.startsWith("insert:") })
        assertTrue(events.none { it.startsWith("change:") })
    }

    @Test
    fun `changing searchKey for RssSortTabKey produces a new itemId`() {
        val registry = RssTabIdRegistry<RssSortTabKey>()
        val searchSorts = listOf("搜索" to "https://example.com/search")

        val firstSearchTabs = buildRssSortTabItems(searchSorts, searchKey = "kotlin", registry = registry)
        val secondSearchTabs = buildRssSortTabItems(searchSorts, searchKey = "android", registry = registry)

        val firstId = firstSearchTabs.single().itemId
        val secondId = secondSearchTabs.single().itemId

        assertNotEquals(firstId, secondId)
        assertTrue(secondId > firstId)

        // Switching back to a previously removed searchKey must still yield a fresh monotonic ID.
        val thirdSearchTabs = buildRssSortTabItems(searchSorts, searchKey = "kotlin", registry = registry)
        assertTrue(thirdSearchTabs.single().itemId > secondId)
    }

    @Test
    fun `invalidateAll forces fresh monotonic itemIds without resetting sequence`() {
        val registry = RssTabIdRegistry<RssSortTabKey>()
        val sorts = listOf("默认" to "https://example.com/default")

        val before = buildRssSortTabItems(sorts, searchKey = null, registry = registry).single().itemId
        registry.invalidateAll()
        val after = buildRssSortTabItems(sorts, searchKey = null, registry = registry).single().itemId

        assertTrue(after > before)
    }
}
