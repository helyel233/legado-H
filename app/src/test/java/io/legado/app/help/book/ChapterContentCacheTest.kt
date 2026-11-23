package io.legado.app.help.book

import android.app.Application
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ChapterContentCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        ChapterContentCache.clear()
    }

    @After
    fun tearDown() {
        ChapterContentCache.clear()
    }

    @Test
    fun `remove by file evicts only target chapter even after file deletion`() {
        val bookADir = tempFolder.newFolder("bookA")
        val bookBDir = tempFolder.newFolder("bookB")

        val ch1File = File(bookADir, "c-chapter1.nb").apply { writeText("Book A Chapter 1") }
        val ch2File = File(bookADir, "c-chapter2.nb").apply { writeText("Book A Chapter 2") }
        val otherBookFile = File(bookBDir, "c-chapter1.nb").apply { writeText("Book B Chapter 1") }

        val ch1Key = ChapterContentCache.keyOf(ch1File)
        val ch2Key = ChapterContentCache.keyOf(ch2File)
        val otherBookKey = ChapterContentCache.keyOf(otherBookFile)

        ChapterContentCache.put(ch1Key, "Book A Chapter 1")
        ChapterContentCache.put(ch2Key, "Book A Chapter 2")
        ChapterContentCache.put(otherBookKey, "Book B Chapter 1")

        // Delete target chapter file first (simulating BookHelp.delContent order)
        ch1File.delete()
        ChapterContentCache.remove(ch1File)

        assertNull(ChapterContentCache.get(ch1Key))
        assertEquals("Book A Chapter 2", ChapterContentCache.get(ch2Key))
        assertEquals("Book B Chapter 1", ChapterContentCache.get(otherBookKey))
    }

    @Test
    fun `removeAll evicts both primary and legacy keys for target chapter while preserving others`() {
        val bookDir = tempFolder.newFolder("bookA")
        val primaryFile = File(bookDir, "c-target.nb").apply { writeText("Primary Content") }
        val legacyFile = File(bookDir, "00001-target.nb").apply { writeText("Legacy Content") }
        val siblingFile = File(bookDir, "c-sibling.nb").apply { writeText("Sibling Content") }

        val primaryKey = ChapterContentCache.keyOf(primaryFile)
        val legacyKey = ChapterContentCache.keyOf(legacyFile)
        val siblingKey = ChapterContentCache.keyOf(siblingFile)

        ChapterContentCache.put(primaryKey, "Primary Content")
        ChapterContentCache.put(legacyKey, "Legacy Content")
        ChapterContentCache.put(siblingKey, "Sibling Content")

        primaryFile.delete()
        legacyFile.delete()
        ChapterContentCache.removeAll(listOf(primaryFile, legacyFile))

        assertNull(ChapterContentCache.get(primaryKey))
        assertNull(ChapterContentCache.get(legacyKey))
        assertEquals("Sibling Content", ChapterContentCache.get(siblingKey))
    }

    @Test
    fun `BookHelp delContent evicts only deleted chapter from ChapterContentCache`() {
        val bookA = Book(
            bookUrl = "https://example.com/bookA",
            name = "Book A",
            author = "Author A"
        )
        val bookB = Book(
            bookUrl = "https://example.com/bookB",
            name = "Book B",
            author = "Author B"
        )
        val chapterA1 = BookChapter(
            url = "https://example.com/bookA/1",
            title = "Chapter 1",
            bookUrl = bookA.bookUrl,
            index = 0
        )
        val chapterA2 = BookChapter(
            url = "https://example.com/bookA/2",
            title = "Chapter 2",
            bookUrl = bookA.bookUrl,
            index = 1
        )
        val chapterB1 = BookChapter(
            url = "https://example.com/bookB/1",
            title = "Chapter 1",
            bookUrl = bookB.bookUrl,
            index = 0
        )

        val dirA = BookHelp.getCacheDir(bookA).apply { mkdirs() }
        val dirB = BookHelp.getCacheDir(bookB).apply { mkdirs() }

        val fileA1 = File(dirA, chapterA1.contentCacheFileName("nb")).apply { writeText("A1 text") }
        val legacyA1 = File(dirA, chapterA1.getFileName("nb")).apply { writeText("A1 legacy") }
        val fileA2 = File(dirA, chapterA2.contentCacheFileName("nb")).apply { writeText("A2 text") }
        val fileB1 = File(dirB, chapterB1.contentCacheFileName("nb")).apply { writeText("B1 text") }

        val keyA1 = ChapterContentCache.keyOf(fileA1)
        val keyLegacyA1 = ChapterContentCache.keyOf(legacyA1)
        val keyA2 = ChapterContentCache.keyOf(fileA2)
        val keyB1 = ChapterContentCache.keyOf(fileB1)

        ChapterContentCache.put(keyA1, "A1 text")
        ChapterContentCache.put(keyLegacyA1, "A1 legacy")
        ChapterContentCache.put(keyA2, "A2 text")
        ChapterContentCache.put(keyB1, "B1 text")

        BookHelp.delContent(bookA, chapterA1)

        assertFalse(fileA1.exists())
        assertFalse(legacyA1.exists())
        assertNull(ChapterContentCache.get(keyA1))
        assertNull(ChapterContentCache.get(keyLegacyA1))
        assertEquals("A2 text", ChapterContentCache.get(keyA2))
        assertEquals("B1 text", ChapterContentCache.get(keyB1))
    }

    @Test
    fun `secondary index tracks overwrites in O(1) and cleans up on remove and clear`() {
        val dir = tempFolder.newFolder("bookIdx")
        val targetFile = File(dir, "c-chapter.nb").apply { writeText("v1") }
        val otherFile = File(dir, "c-other.nb").apply { writeText("other") }

        val keyV1 = ChapterContentCache.keyOf(targetFile)
        val otherKey = ChapterContentCache.keyOf(otherFile)
        ChapterContentCache.put(keyV1, "v1")
        ChapterContentCache.put(otherKey, "other")
        assertEquals(2, ChapterContentCache.indexSizeForTest())

        // Rewrite file with different size/mtime so key changes
        targetFile.writeText("v2-updated-content")
        targetFile.setLastModified(System.currentTimeMillis() + 2000L)
        val keyV2 = ChapterContentCache.keyOf(targetFile)
        ChapterContentCache.put(keyV2, "v2-updated-content")

        assertNull("Old stale version for same path should be evicted", ChapterContentCache.get(keyV1))
        assertEquals("v2-updated-content", ChapterContentCache.get(keyV2))
        assertEquals(2, ChapterContentCache.indexSizeForTest())

        targetFile.delete()
        ChapterContentCache.remove(targetFile)
        assertNull(ChapterContentCache.get(keyV2))
        assertEquals("other", ChapterContentCache.get(otherKey))
        assertEquals(1, ChapterContentCache.indexSizeForTest())

        ChapterContentCache.clear()
        assertEquals(0, ChapterContentCache.indexSizeForTest())
    }
}
