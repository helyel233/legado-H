package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadProgressSyncPolicyTest {

    private fun progress(chapterIndex: Int, chapterPos: Int): BookProgress {
        val book = Book(
            name = "书",
            author = "作者",
            bookUrl = "https://example.com/book/$chapterIndex-$chapterPos"
        )
        book.durChapterIndex = chapterIndex
        book.durChapterPos = chapterPos
        return BookProgress(book)
    }

    @Test
    fun theSamePositionIsInSync() {
        assertEquals(
            ProgressSyncDecision.IN_SYNC,
            ReadProgressSyncPolicy.decide(progress(3, 100), progress(3, 100))
        )
    }

    @Test
    fun anEarlierChapterIsAlwaysBehindEvenWithALargerOffset() {
        // 章节号小的一定更旧，哪怕它的字符位置更大——不同章节的长度不可比。
        assertEquals(
            ProgressSyncDecision.APPLY_SERVER,
            ReadProgressSyncPolicy.decide(progress(4, 10), progress(3, 900))
        )
        assertEquals(
            ProgressSyncDecision.UPLOAD,
            ReadProgressSyncPolicy.decide(progress(3, 900), progress(4, 10))
        )
    }

    @Test
    fun theSameChapterComparesTheOffset() {
        assertEquals(
            ProgressSyncDecision.UPLOAD,
            ReadProgressSyncPolicy.decide(progress(3, 100), progress(3, 200))
        )
        assertEquals(
            ProgressSyncDecision.APPLY_SERVER,
            ReadProgressSyncPolicy.decide(progress(3, 200), progress(3, 100))
        )
    }
}
