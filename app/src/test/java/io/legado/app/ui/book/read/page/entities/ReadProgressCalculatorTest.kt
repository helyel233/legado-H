package io.legado.app.ui.book.read.page.entities

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class ReadProgressCalculatorTest {

    private val formatter = DecimalFormat("0.0%", DecimalFormatSymbols(Locale.US))

    private fun format(
        chapterSize: Int,
        pageSize: Int,
        chapterIndex: Int,
        pageIndex: Int
    ): String {
        return ReadProgressCalculator.format(
            chapterSize = chapterSize,
            pageSize = pageSize,
            chapterIndex = chapterIndex,
            pageIndex = pageIndex,
            formatter = formatter
        )
    }

    @Test
    fun anEmptyBookHasNoProgress() {
        assertEquals("0.0%", format(chapterSize = 0, pageSize = 0, chapterIndex = 0, pageIndex = 0))
    }

    @Test
    fun aChapterWithoutPagesCountsByChapter() {
        // 没有分页信息时只按章节算：第 3 章 / 共 10 章 = 30%。
        assertEquals("30.0%", format(chapterSize = 10, pageSize = 0, chapterIndex = 2, pageIndex = 0))
    }

    @Test
    fun progressBlendsChapterAndPage() {
        // 第 1 章（共 2 章）的第 5 页（共 10 页）：(0 + 1/2*5/10) = 25%。
        assertEquals("25.0%", format(chapterSize = 2, pageSize = 10, chapterIndex = 0, pageIndex = 4))
    }

    @Test
    fun theLastPageOfTheLastChapterIsOneHundredPercent() {
        assertEquals(
            "100.0%",
            format(chapterSize = 2, pageSize = 10, chapterIndex = 1, pageIndex = 9)
        )
    }

    @Test
    fun aBookThatIsNotFinishedNeverShowsOneHundredPercent() {
        // 最后一章还有 2 页，才读了第 1 页：四舍五入会得到 100%，必须压回 99.9%。
        assertEquals(
            "99.9%",
            format(chapterSize = 1000, pageSize = 2, chapterIndex = 999, pageIndex = 0)
        )
    }

    @Test
    fun aChapterThatStillHasMorePagesNeverShowsOneHundredPercent() {
        // 第 2 章（共 3 章）的第 1 页（共 2 页）：1/3 + 1/3*1/2 = 50%。
        assertEquals(
            "50.0%",
            format(chapterSize = 3, pageSize = 2, chapterIndex = 1, pageIndex = 0)
        )
    }
}
