package io.legado.app.uikit.v5

import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.AppDestination
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.SideGroup
import io.legado.app.uikit.v5.registry.DestinationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3 注册表自检（手册 P0-3：Tab 只有一个真相）。
 */
class DestinationRegistryTest {

    private fun dest(
        id: String,
        order: Int = 0,
        topLevel: Boolean = true,
        locked: Boolean = false,
        visible: Boolean = true,
        side: SideGroup = SideGroup.NONE,
        words: List<String> = emptyList(),
    ) = AppDestination(
        id = id,
        title = DestTitle.Text(id),
        factory = { StubContent },
        topLevel = topLevel,
        lockedVisible = locked,
        defaultVisible = visible,
        sideGroup = side,
        order = order,
        keywords = words,
    )

    private val six = listOf(
        dest("home", order = 5, visible = false),
        dest("shelf", order = 0, locked = true),
        dest("explore", order = 1),
        dest("rss", order = 2, visible = false),
        dest("stats", order = 3, visible = false),
        dest("my", order = 4, locked = true),
    )

    /** 现状：6 项规格、默认可见 3 项（书架/发现/我的 + 两个锁定项）。 */
    private fun registryLikeToday() = DestinationRegistry(six).apply {
        setVisible("explore", true)
    }

    @Test
    fun `all six are registered but only visible ones show as tabs`() {
        val r = registryLikeToday()
        assertEquals(6, r.topLevel.size)
        val visible = r.visibleTabs().map { it.id }
        assertTrue(visible.contains("shelf"))
        assertTrue(visible.contains("my"))
        assertTrue(visible.contains("explore"))
        assertTrue(!visible.contains("home"))
        assertTrue(!visible.contains("rss"))
        assertTrue(!visible.contains("stats"))
    }

    @Test
    fun `locked tabs cannot be hidden`() {
        val r = registryLikeToday()
        r.setVisible("shelf", false)
        r.setVisible("my", false)
        assertTrue(r.visibleTabs().map { it.id }.containsAll(listOf("shelf", "my")))
    }

    @Test
    fun `hidden tabs remain reachable as secondary entries`() {
        val r = registryLikeToday()
        val hidden = r.hiddenTabs().map { it.id }
        assertTrue(hidden.containsAll(listOf("home", "rss", "stats")))
    }

    @Test
    fun `reordering moves the tab and keeps the rest`() {
        val r = registryLikeToday()
        r.moveBefore("explore", "shelf")
        val order = r.visibleTabs().map { it.id }
        assertEquals(listOf("explore", "shelf", "my"), order)
    }

    @Test
    fun `hiding a tab removes it from the tab list but not from the registry`() {
        val r = registryLikeToday()
        r.setVisible("explore", false)
        assertTrue(!r.visibleTabs().map { it.id }.contains("explore"))
        assertEquals(6, r.all.size)
    }

    @Test
    fun `side bar grouping only includes items that opted in`() {
        val r = DestinationRegistry(
            listOf(
                dest("shelf", side = SideGroup.MAIN),
                dest("sources", topLevel = false, side = SideGroup.CONTENT, order = 2),
                dest("backup", topLevel = false, side = SideGroup.SETTINGS, order = 1),
                dest("noside"),
            )
        )
        assertEquals(listOf("shelf"), r.sideItems()[SideGroup.MAIN]!!.map { it.id })
        assertEquals(listOf("sources"), r.sideItems()[SideGroup.CONTENT]!!.map { it.id })
        assertEquals(listOf("backup"), r.sideItems()[SideGroup.SETTINGS]!!.map { it.id })
        assertTrue(r.sideItems()[SideGroup.NONE] == null)
    }

    @Test
    fun `search matches keywords and ids`() {
        val r = DestinationRegistry(
            listOf(dest("glass", words = listOf("玻璃", "透明度", "glass")), dest("shelf", words = listOf("书架")))
        )
        assertEquals(listOf("glass"), r.search("透明").map { it.id })
        assertEquals(listOf("glass"), r.search("GLASS").map { it.id })
        assertEquals(listOf("shelf"), r.search("书架").map { it.id })
        assertTrue(r.search("不存在").isEmpty())
    }

    @Test
    fun `unknown id resolves to null instead of crashing`() {
        val r = registryLikeToday()
        assertTrue(r.byId("nope") == null)
        assertTrue(r.content("nope") == null)
    }

    private object StubContent : AppContent {
        override fun chrome(): ScreenChrome = ScreenChrome()

        @androidx.compose.runtime.Composable
        override fun Content(scope: io.legado.app.uikit.v5.contract.ContentScope) = Unit
    }
}
