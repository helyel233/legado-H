package io.legado.app.help.book

import android.app.Application
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BookHelpGetDurChapterTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    private fun chapters(count: Int, titleOf: (Int) -> String = { "第${it + 1}章" }): List<BookChapter> {
        return List(count) { index ->
            BookChapter(
                url = "https://example.com/c/$index",
                title = titleOf(index),
                bookUrl = "https://example.com/book",
                index = index
            )
        }
    }

    @Test
    fun `proportional mapping when old size is smaller than new size`() {
        val newChapters = chapters(2000) { idx ->
            when (idx) {
                1000 -> "第1001章 决战巅峰"
                else -> "第${idx + 1}章 普通章节$idx"
            }
        }

        val resolved = BookHelp.getDurChapter(
            oldDurChapterIndex = 500,
            oldDurChapterName = "第1001章 决战巅峰",
            newChapterList = newChapters,
            oldChapterListSize = 1000
        )

        assertEquals(1000, resolved)
    }

    @Test
    fun `proportional mapping when old size is larger than new size`() {
        val newChapters = chapters(1000) { idx ->
            when (idx) {
                500 -> "第501章 转折点"
                else -> "第${idx + 1}章 章节$idx"
            }
        }

        val resolved = BookHelp.getDurChapter(
            oldDurChapterIndex = 1000,
            oldDurChapterName = "第501章 转折点",
            newChapterList = newChapters,
            oldChapterListSize = 2000
        )

        assertEquals(500, resolved)
    }

    @Test
    fun `large chapter counts do not overflow int multiplication`() {
        val oldIndex = 60_000
        val oldSize = 100_000
        val newSize = 120_000
        // 60_000 * 120_000 = 7_200_000_000 (> Int.MAX_VALUE)，必须用 Long 计算比例
        val newChapters = chapters(newSize) { idx ->
            if (idx == 72_000) "第72001章 终局之战" else "第${idx + 1}章"
        }

        val resolved = BookHelp.getDurChapter(
            oldDurChapterIndex = oldIndex,
            oldDurChapterName = "第72001章 终局之战",
            newChapterList = newChapters,
            oldChapterListSize = oldSize
        )

        assertEquals(72_000, resolved)
    }

    @Test
    fun `oldBook overload uses totalChapterNum and matches pure chapter title`() {
        val oldBook = Book(
            bookUrl = "https://example.com/old",
            name = "测试书",
            author = "作者",
            durChapterIndex = 200,
            durChapterTitle = "第401章 云起龙骧",
            totalChapterNum = 500
        )
        val newChapters = chapters(1000) { idx ->
            if (idx == 400) "401、云起龙骧" else "第${idx + 1}章 占位"
        }

        val resolved = BookHelp.getDurChapter(oldBook, newChapters)

        assertEquals(400, resolved)
    }

    @Test
    fun `edge cases with zero or oversized index clamp safely`() {
        val newChapters = chapters(10) { "第${it + 1}章" }

        assertEquals(0, BookHelp.getDurChapter(0, "第1章", newChapters, 100))
        assertEquals(0, BookHelp.getDurChapter(-1, "第1章", newChapters, 100))
        assertEquals(
            9,
            BookHelp.getDurChapter(
                oldDurChapterIndex = 50,
                oldDurChapterName = "不存在的标题",
                newChapterList = newChapters,
                oldChapterListSize = 0
            )
        )
        assertEquals(5, BookHelp.getDurChapter(5, "第6章", emptyList(), 10))
    }
}
