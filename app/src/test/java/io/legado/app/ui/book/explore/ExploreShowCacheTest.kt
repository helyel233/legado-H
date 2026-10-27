package io.legado.app.ui.book.explore

import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.main.explore.DiscoveryCachePolicy
import io.legado.app.utils.stableSearchBookKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreShowCacheTest {

    private fun book(
        name: String = "书名",
        bookUrl: String = "https://example.com/book/1",
        origin: String = "https://example.com/source"
    ): SearchBook {
        return SearchBook(
            name = name,
            author = "作者",
            bookUrl = bookUrl,
            origin = origin,
            originName = "示例源",
            coverUrl = "https://example.com/cover.jpg",
            intro = "简介"
        )
    }

    private fun snapshot(
        page: Int = 2,
        books: List<SearchBook> = listOf(book())
    ): ExploreShowCache.Snapshot {
        return ExploreShowCache.Snapshot(page, books)
    }

    @Test
    fun roundTripKeepsPageAndBooks() {
        val source = snapshot(
            page = 3,
            books = listOf(
                book(name = "书一", bookUrl = "https://example.com/1"),
                book(name = "书二", bookUrl = "https://example.com/2")
            )
        )
        val json = requireNotNull(ExploreShowCache.toJson(source))
        val restored = requireNotNull(ExploreShowCache.fromJson(json, "https://example.com/source"))

        assertEquals(3, restored.page)
        assertEquals(2, restored.books.size)
        assertEquals("书一", restored.books[0].name)
        assertEquals("https://example.com/2", restored.books[1].bookUrl)
    }

    @Test
    fun roundTripPreservesListKeyOfEveryBook() {
        val books = listOf(
            book(name = "甲", bookUrl = "https://example.com/a"),
            book(name = "乙", bookUrl = "https://example.com/b")
        )
        val json = requireNotNull(ExploreShowCache.toJson(snapshot(books = books)))
        val restored = requireNotNull(ExploreShowCache.fromJson(json, "https://example.com/source"))

        books.forEachIndexed { index, original ->
            assertEquals(
                original.stableSearchBookKey(),
                restored.books[index].stableSearchBookKey()
            )
        }
    }

    @Test
    fun keyChangesWhenSourceOrExploreUrlChanges() {
        val base = ExploreShowCache.cacheKey("https://s.com", "https://s.com/cat1")
        assertEquals(base, ExploreShowCache.cacheKey("https://s.com", "https://s.com/cat1"))
        assertNotEquals(base, ExploreShowCache.cacheKey("https://s.com", "https://s.com/cat2"))
        assertNotEquals(base, ExploreShowCache.cacheKey("https://other.com", "https://s.com/cat1"))
    }

    @Test
    fun rejectsInvalidPage() {
        assertNull(ExploreShowCache.toJson(snapshot(page = 0)))
        assertNull(ExploreShowCache.toJson(snapshot(page = -1)))
        assertNull(ExploreShowCache.fromJson("""{"page":0,"books":[]}""", "https://s.com"))
    }

    @Test
    fun rejectsBlankJson() {
        assertNull(ExploreShowCache.fromJson("", "https://s.com"))
        assertNull(ExploreShowCache.fromJson("   ", "https://s.com"))
        assertNull(ExploreShowCache.fromJson("not json", "https://s.com"))
    }

    @Test
    fun dropsBooksFromOtherSources() {
        val json = requireNotNull(
            ExploreShowCache.toJson(
                snapshot(books = listOf(book(origin = "https://example.com/source")))
            )
        )
        assertNull(ExploreShowCache.fromJson(json, "https://another-source.com"))
    }

    @Test
    fun dropsBooksWithoutIdentity() {
        val json = requireNotNull(
            ExploreShowCache.toJson(
                snapshot(
                    books = listOf(
                        book(bookUrl = ""),
                        book(name = "完整体")
                    )
                )
            )
        )
        val restored = requireNotNull(ExploreShowCache.fromJson(json, "https://example.com/source"))
        assertEquals(1, restored.books.size)
        assertEquals("完整体", restored.books[0].name)
    }

    @Test
    fun dropsBooksThatCannotSurviveCompaction() {
        val json = requireNotNull(
            ExploreShowCache.toJson(
                snapshot(
                    books = listOf(
                        book().copy(variable = "v".repeat(20_000)),
                        book(name = "可保留")
                    )
                )
            )
        )
        val restored = requireNotNull(ExploreShowCache.fromJson(json, "https://example.com/source"))
        assertEquals(1, restored.books.size)
        assertEquals("可保留", restored.books[0].name)
    }

    @Test
    fun truncatesSnapshotToMaxBooks() {
        val books = (1..ExploreShowCache.MAX_SNAPSHOT_BOOKS + 30).map {
            book(name = "书$it", bookUrl = "https://example.com/$it")
        }
        val json = requireNotNull(ExploreShowCache.toJson(snapshot(page = 9, books = books)))
        val restored = requireNotNull(ExploreShowCache.fromJson(json, "https://example.com/source"))

        assertEquals(ExploreShowCache.MAX_SNAPSHOT_BOOKS, restored.books.size)
        // 截断保留最新的条目：快照尾部必须与 page（下一待拉页）连续，
        // 否则恢复后「最旧几页 + 最新页续拉」会出现页码断层
        assertEquals("书31", restored.books.first().name)
        assertEquals("书${ExploreShowCache.MAX_SNAPSHOT_BOOKS + 30}", restored.books.last().name)
        assertEquals(9, restored.page)
    }

    @Test
    fun compactedBooksStayWellUnderCacheBudget() {
        val books = (1..ExploreShowCache.MAX_SNAPSHOT_BOOKS).map {
            book(name = "书$it", bookUrl = "https://example.com/$it")
                .copy(intro = "很长的简介".repeat(200))
        }
        val json = requireNotNull(ExploreShowCache.toJson(snapshot(books = books)))
        // 缓存上限按 UTF-8 字节计，字符数比较单位不一致（中文 3 字节/字符）
        assertTrue(
            DiscoveryCachePolicy.utf8ByteCount(json) < DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES
        )
    }
}
