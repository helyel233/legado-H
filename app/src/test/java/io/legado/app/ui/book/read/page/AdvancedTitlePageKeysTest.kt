package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTitlePageKeysTest {

    @Test
    fun pageKeySeparatesBooksChaptersAndPages() {
        val bookA = AdvancedTitlePageKeys.of("bookA", 0, 0)
        val bookB = AdvancedTitlePageKeys.of("bookB", 0, 0)
        val chapterTwo = AdvancedTitlePageKeys.of("bookA", 1, 0)
        val pageTwo = AdvancedTitlePageKeys.of("bookA", 0, 1)

        assertFalse(bookA == bookB)
        assertFalse(bookA == chapterTwo)
        assertFalse(bookA == pageTwo)
        assertEquals(bookA, AdvancedTitlePageKeys.of("bookA", 0, 0))
    }

    @Test
    fun pageKeyKeepsChapterNamesApart() {
        // 章节名会重复（"序"/"第一章"/"番外"），位置必须以章节号+页号区分，
        // 否则两个不相干的位置会撞成同一条记录，一个章节显示另一个章节的组合。
        val prologue = AdvancedTitlePageKeys.of("book", 0, 0)
        val firstChapter = AdvancedTitlePageKeys.of("book", 1, 0)
        val extra = AdvancedTitlePageKeys.of("book", 5, 0)

        assertEquals(3, setOf(prologue, firstChapter, extra).size)
    }

    @Test
    fun pageKeyCacheEvictsLeastRecentlyUsedEntry() {
        val cache = AdvancedTitlePageKeyCache(2)

        cache.put("page1", "key1")
        cache.put("page2", "key2")
        assertEquals(2, cache.size())

        // 读取也算一次使用：page1 应当比 page2 更"新"，淘汰时先掉 page2。
        assertEquals("key1", cache["page1"])
        cache.put("page3", "key3")

        assertEquals("key1", cache["page1"])
        assertNull(cache["page2"])
        assertEquals("key3", cache["page3"])
        assertEquals(2, cache.size())
    }

    @Test
    fun pageKeyCacheDropsOldestEntryWhenFull() {
        val cache = AdvancedTitlePageKeyCache(24)

        for (index in 0 until 24) {
            cache.put("page$index", "key$index")
        }
        assertEquals(24, cache.size())

        cache.put("page24", "key24")

        assertEquals(24, cache.size())
        assertNull(cache["page0"])
        assertEquals("key23", cache["page23"])
        assertEquals("key24", cache["page24"])
    }

    @Test
    fun pageKeyCacheReadingAnEntryKeepsItAlive() {
        val cache = AdvancedTitlePageKeyCache(3)

        cache.put("page1", "key1")
        cache.put("page2", "key2")
        cache.put("page3", "key3")
        // 读一次就算用过：翻回来看一眼的这一页不该被淘汰掉。
        assertEquals("key1", cache["page1"])

        cache.put("page4", "key4")

        assertEquals("key1", cache["page1"])
        assertNull(cache["page2"])
        assertEquals("key3", cache["page3"])
        assertEquals("key4", cache["page4"])
    }

    @Test
    fun pageKeyCacheRewritingAKeyKeepsASingleEntry() {
        val cache = AdvancedTitlePageKeyCache(4)

        cache.put("page1", "key1")
        cache.put("page1", "key2")

        assertEquals(1, cache.size())
        assertEquals("key2", cache["page1"])
    }

    @Test
    fun committedTitleCacheIsBoundedByEntryCount() {
        // 已提交的组合最多留 6 份，多了就按最久未用淘汰，避免 Lottie 组合常驻内存。
        val cache = AccessOrderedLruCache<String, String>(6)

        for (index in 0 until 6) {
            cache.put("key$index", "value$index")
        }
        assertEquals(6, cache.size())

        cache.put("key6", "value6")

        assertEquals(6, cache.size())
        assertNull(cache["key0"])
        assertEquals("value6", cache["key6"])
    }

    @Test
    fun committedTitleCacheReuseCountsAsUse() {
        val cache = AccessOrderedLruCache<String, String>(2)

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        assertEquals("value1", cache["key1"])

        cache.put("key3", "value3")

        assertEquals("value1", cache["key1"])
        assertNull(cache["key2"])
        assertEquals("value3", cache["key3"])
    }

    @Test
    fun compositionKeyOnlyMatchesItsOwnViewSize() {
        val key = AdvancedTitleLottieKeys.composition("json", 900, 717)

        assertTrue(AdvancedTitleLottieKeys.matchesViewSize(key, 900, 717))
        assertFalse(AdvancedTitleLottieKeys.matchesViewSize(key, 900, 718))
        assertFalse(AdvancedTitleLottieKeys.matchesViewSize(key, 901, 717))
    }

    @Test
    fun rawCompositionKeyMatchesItsOwnViewSize() {
        val key = AdvancedTitleLottieKeys.rawComposition(900, 717)

        assertTrue(AdvancedTitleLottieKeys.matchesViewSize(key, 900, 717))
        assertFalse(AdvancedTitleLottieKeys.matchesViewSize(key, 900, 718))
    }
}
