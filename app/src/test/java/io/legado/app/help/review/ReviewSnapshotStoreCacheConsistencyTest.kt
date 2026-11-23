package io.legado.app.help.review

import android.app.Application
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReviewSnapshotStoreCacheConsistencyTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    private fun testBook(id: String) = Book(
        bookUrl = "https://example.com/book/$id",
        name = "TestBook-$id",
        author = "Tester"
    )

    private fun testChapter(book: Book, index: Int = 0) = BookChapter(
        url = "${book.bookUrl}/ch/$index",
        title = "第${index + 1}章",
        bookUrl = book.bookUrl,
        index = index
    )

    private fun testSnapshot(book: Book, chapter: BookChapter, buttonSrc: String) = ReviewSnapshot(
        bookUrl = book.bookUrl,
        chapterUrl = chapter.url,
        chapterIndex = chapter.index,
        chapterTitle = chapter.title,
        buttonSrc = buttonSrc,
        url = "${chapter.url}#review",
        title = "评论",
        html = "<html><body><div class=\"comment\">hello</div></body></html>",
        resourceKeys = emptyList(),
        partial = false,
        savedAt = 1_700_000_000_000L
    )

    @Test
    fun `hasSnapshot verifies underlying file existence and evicts stale memory cache entry`() {
        val book = testBook("consistency")
        BookHelp.clearCache(book)
        val chapter = testChapter(book)
        val buttonSrc = "dp:5,{pclick:101}"
        try {
            ReviewSnapshotResourceStore.prepareForCapture(book)
            ReviewSnapshotStore.put(book, testSnapshot(book, chapter, buttonSrc))

            assertTrue(ReviewSnapshotStore.hasSnapshot(book, chapter.url, buttonSrc))
            assertNotNull(ReviewSnapshotStore.getCached(book, chapter, buttonSrc))

            val snapshotFile = File(
                ReviewSnapshotStore.reviewsDir(book),
                ReviewSnapshotStore.fileName(chapter.url, buttonSrc)
            )
            assertTrue(snapshotFile.delete())

            assertFalse(ReviewSnapshotStore.hasSnapshot(book, chapter.url, buttonSrc))
            assertNull(ReviewSnapshotStore.getCached(book, chapter, buttonSrc))
        } finally {
            BookHelp.clearCache(book)
        }
    }

    @Test
    fun `BookHelp clearCache clears snapshotCache, cachedReviewChaptersMap and resource databaseCache for target book only`() {
        val bookA = testBook("clear-a")
        val bookB = testBook("clear-b")
        BookHelp.clearCache(bookA)
        BookHelp.clearCache(bookB)
        val chapterA = testChapter(bookA)
        val chapterB = testChapter(bookB)
        val buttonA = "dp:3,{pclick:11}"
        val buttonB = "dp:3,{pclick:22}"

        try {
            ReviewSnapshotResourceStore.prepareForCapture(bookA)
            ReviewSnapshotResourceStore.prepareForCapture(bookB)
            val blobA = File.createTempFile("blob-a-", ".png").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val entryA = try {
                ReviewSnapshotResourceStore.put(bookA, "https://example.com/a.png", "image/png", blobA)
            } finally {
                blobA.delete()
            }
            ReviewSnapshotStore.put(bookA, testSnapshot(bookA, chapterA, buttonA))
            ReviewSnapshotStore.put(bookB, testSnapshot(bookB, chapterB, buttonB))

            // 预热资源索引内存缓存与章节计数内存缓存
            assertNotNull(ReviewSnapshotResourceStore.open(bookA, entryA.key)?.use { it.mimeType })
            assertEquals(1, ReviewSnapshotManager.cachedReviewChapterCount(bookA))
            assertEquals(1, ReviewSnapshotManager.cachedReviewChapterCount(bookB))

            // 仅清理 bookA
            BookHelp.clearCache(bookA)

            assertFalse(ReviewSnapshotStore.reviewsDir(bookA).exists())
            assertFalse(ReviewSnapshotStore.hasSnapshot(bookA, chapterA.url, buttonA))
            assertNull(ReviewSnapshotStore.getCached(bookA, chapterA, buttonA))
            assertEquals(0, ReviewSnapshotManager.cachedReviewChapterCount(bookA))
            assertNull(ReviewSnapshotResourceStore.open(bookA, entryA.key))

            // bookB 完整保留不受影响
            assertTrue(ReviewSnapshotStore.hasSnapshot(bookB, chapterB.url, buttonB))
            assertNotNull(ReviewSnapshotStore.getCached(bookB, chapterB, buttonB))
            assertEquals(1, ReviewSnapshotManager.cachedReviewChapterCount(bookB))
        } finally {
            BookHelp.clearCache(bookA)
            BookHelp.clearCache(bookB)
        }
    }

    @Test
    fun `resolveExistingSnapshotPolicy decouples force and incremental`() {
        assertEquals(
            ReviewSnapshotManager.ExistingSnapshotPolicy.INCREMENTAL,
            ReviewSnapshotManager.resolveExistingSnapshotPolicy(
                hasCompleteSnapshot = true,
                force = true,
                incremental = true
            )
        )
        assertEquals(
            ReviewSnapshotManager.ExistingSnapshotPolicy.INCREMENTAL,
            ReviewSnapshotManager.resolveExistingSnapshotPolicy(
                hasCompleteSnapshot = true,
                force = false,
                incremental = true
            )
        )
        assertEquals(
            ReviewSnapshotManager.ExistingSnapshotPolicy.FULL,
            ReviewSnapshotManager.resolveExistingSnapshotPolicy(
                hasCompleteSnapshot = true,
                force = true,
                incremental = false
            )
        )
        assertEquals(
            ReviewSnapshotManager.ExistingSnapshotPolicy.SKIP,
            ReviewSnapshotManager.resolveExistingSnapshotPolicy(
                hasCompleteSnapshot = true,
                force = false,
                incremental = false
            )
        )
        assertEquals(
            ReviewSnapshotManager.ExistingSnapshotPolicy.FULL,
            ReviewSnapshotManager.resolveExistingSnapshotPolicy(
                hasCompleteSnapshot = false,
                force = false,
                incremental = true
            )
        )
    }

    @Test
    fun `getCachedButtonSrcSet returns batch set for chapter and filters missing files`() {
        val book = testBook("batch-set")
        BookHelp.clearCache(book)
        val ch1 = testChapter(book, index = 0)
        val ch2 = testChapter(book, index = 1)
        val btn1 = "dp:5,{pclick:101}"
        val btn2 = "dp:8,{pclick:202}"
        val btnOtherChapter = "dp:2,{pclick:303}"
        try {
            ReviewSnapshotResourceStore.prepareForCapture(book)
            ReviewSnapshotStore.put(book, testSnapshot(book, ch1, btn1))
            ReviewSnapshotStore.put(book, testSnapshot(book, ch1, btn2))
            ReviewSnapshotStore.put(book, testSnapshot(book, ch2, btnOtherChapter))
            ReviewSnapshotStore.put(book, testSnapshot(book, ch1, ReviewSnapshotStore.CHAPTER_TAB_SRC))

            assertEquals(
                setOf(btn1, btn2),
                ReviewSnapshotStore.getCachedButtonSrcSet(book, ch1.url)
            )
            assertEquals(
                setOf(btnOtherChapter),
                ReviewSnapshotStore.getCachedButtonSrcSet(book, ch2.url)
            )

            // 删除其中一个快照文件后，批量集合必须立即过滤缺失项
            val file1 = File(
                ReviewSnapshotStore.reviewsDir(book),
                ReviewSnapshotStore.fileName(ch1.url, btn1)
            )
            assertTrue(file1.delete())

            assertEquals(
                setOf(btn2),
                ReviewSnapshotStore.getCachedButtonSrcSet(book, ch1.url)
            )

            // 清空全书缓存后应返回空集合
            BookHelp.clearCache(book)
            assertEquals(
                emptySet<String>(),
                ReviewSnapshotStore.getCachedButtonSrcSet(book, ch1.url)
            )
        } finally {
            BookHelp.clearCache(book)
        }
    }
}
