package io.legado.app.ui.main.homepage

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.HomepageModuleType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomepageCacheAndTabPolicyTest {

    @Test
    fun `tab policy returns null in mixed layout mode`() {
        val result = HomepageTabSelectionPolicy.resolveLoadSetIds(
            layoutMode = 0,
            preloadMode = 0,
            tabSelection = TabSelectionState(0, listOf("src_a", "src_b")),
            fallbackSetUrls = listOf("src_a"),
        )
        assertNull(result)
    }

    @Test
    fun `tab policy uses fallback urls before ui callback arrives and strips custom prefix`() {
        val result = HomepageTabSelectionPolicy.resolveLoadSetIds(
            layoutMode = 1,
            preloadMode = 0,
            tabSelection = TabSelectionState(index = 0, setUrls = emptyList()),
            fallbackSetUrls = listOf("custom://cs_1", "src_a"),
        )
        assertEquals(setOf("cs_1"), result)
    }

    @Test
    fun `tab policy includes adjacent tabs when preload is enabled`() {
        val result = HomepageTabSelectionPolicy.resolveLoadSetIds(
            layoutMode = 1,
            preloadMode = 1,
            tabSelection = TabSelectionState(
                index = 1,
                setUrls = listOf("src_a", "custom://cs_2", "rss_c", "src_d"),
            ),
            fallbackSetUrls = emptyList(),
        )
        assertEquals(setOf("src_a", "cs_2", "rss_c"), result)
    }

    @Test
    fun `cache policy checks freshness window and compacts oversized fields`() {
        val now = 1_000_000L
        assertTrue(HomepageCachePolicy.isFresh(now - 60_000L, now))
        assertFalse(HomepageCachePolicy.isFresh(now - HomepageCachePolicy.FRESH_TTL_MS - 1L, now))
        assertFalse(HomepageCachePolicy.isFresh(0L, now))

        val hugeIntro = "a".repeat(10_000)
        val compacted = HomepageCachePolicy.compact(
            SearchBook(
                bookUrl = "https://book.example/1",
                origin = "https://src.example",
                name = "Test Book",
                intro = hugeIntro,
            )
        )
        assertNotNull(compacted)
        assertTrue(compacted!!.intro!!.length < hugeIntro.length)
        assertNotNull(HomepageCachePolicy.toBoundedJson(HomepageBooksSnapshot(listOf(compacted), true, now)))
    }

    @Test
    fun `starter builder skips error explore kinds and builds book modules`() = runBlocking {
        val s1 = BookSource(bookSourceUrl = "https://s1.test", bookSourceName = "书源一")
        val s2 = BookSource(bookSourceUrl = "https://s2.test", bookSourceName = "书源二")
        val (sets, modules) = HomepageModuleBuilder.buildStarterModules(
            bookSources = listOf(s1, s2),
            rssSources = emptyList(),
            resolveExploreKinds = { src ->
                if (src.bookSourceUrl == "https://s1.test") {
                    listOf(ExploreKind("ERROR:boom", "bad"))
                } else {
                    listOf(
                        ExploreKind("玄幻", "https://s2.test/xh"),
                        ExploreKind("都市", "https://s2.test/ds"),
                    )
                }
            },
        )
        assertEquals(1, sets.size)
        assertEquals("src_https://s2.test", sets.first().id)
        assertEquals(1, modules.size)
        assertEquals(HomepageModuleType.Ranking.key, modules.first().type)
        val categories = HomepageModuleBuilder.parseCategories(modules.first().args)
        assertEquals(listOf("玄幻" to "https://s2.test/xh", "都市" to "https://s2.test/ds"), categories)
    }

    @Test
    fun `starter builder falls back to enabled rss sources when book sources yield nothing`() = runBlocking {
        val rss = RssSource(sourceUrl = "https://rss.test", sourceName = "测试订阅")
        val (sets, modules) = HomepageModuleBuilder.buildStarterModules(
            bookSources = emptyList(),
            rssSources = listOf(rss),
            resolveRssKinds = { listOf("最新" to "https://rss.test/latest") },
        )
        assertEquals(1, sets.size)
        assertEquals("rss_https://rss.test", sets.first().id)
        assertEquals(1, modules.size)
        assertEquals(HomepageModuleType.Card.key, modules.first().type)
    }

    @Test
    fun `cache policy updates LRU index and evicts oldest keys beyond limit`() {
        val (retained1, evicted1) = HomepageCachePolicy.updateLruIndex(
            existingKeys = listOf("k1", "k2", "k3"),
            newKey = "k2",
            maxEntries = 3,
        )
        assertEquals(listOf("k1", "k3", "k2"), retained1)
        assertEquals(emptyList<String>(), evicted1)

        val (retained2, evicted2) = HomepageCachePolicy.updateLruIndex(
            existingKeys = retained1,
            newKey = "k4",
            maxEntries = 3,
        )
        assertEquals(listOf("k3", "k2", "k4"), retained2)
        assertEquals(listOf("k1"), evicted2)
    }

    @Test
    fun `starter builder isolates slow source with per-source timeout`() = runBlocking {
        val slow = BookSource(bookSourceUrl = "https://slow.test", bookSourceName = "慢源")
        val fast = BookSource(bookSourceUrl = "https://fast.test", bookSourceName = "快源")
        val (sets, modules) = HomepageModuleBuilder.buildStarterModules(
            bookSources = listOf(slow, fast),
            rssSources = emptyList(),
            perSourceTimeoutMs = 80L,
            resolveExploreKinds = { src ->
                if (src.bookSourceUrl == "https://slow.test") {
                    delay(5_000L)
                    listOf(ExploreKind("不会到达", "https://slow.test/a"))
                } else {
                    listOf(ExploreKind("热门", "https://fast.test/hot"))
                }
            },
        )
        assertEquals(1, sets.size)
        assertEquals("src_https://fast.test", sets.first().id)
        assertEquals(1, modules.size)
    }

    @Test
    fun `buildExploreModuleDef omits args and url when category url is blank`() {
        val def = HomepageModuleBuilder.buildExploreModuleDef(
            sourceUrl = "https://src.test",
            typeKey = HomepageModuleType.Grid.key,
            kinds = listOf(ExploreKind("全部", "")),
        )
        assertNull(def.args)
        assertNull(def.url)
        assertEquals("全部", def.title)
    }
}
