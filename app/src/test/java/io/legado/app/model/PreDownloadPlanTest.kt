package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PreDownloadPlanTest {

    private fun plan(
        durChapterIndex: Int,
        chapterSize: Int,
        preDownloadNum: Int,
        downloaded: Set<Int> = emptySet(),
        failures: Map<Int, Int> = emptyMap()
    ): List<Int> {
        return PreDownloadPlan.of(
            durChapterIndex = durChapterIndex,
            chapterSize = chapterSize,
            preDownloadNum = preDownloadNum,
            isDownloaded = { it in downloaded },
            failCount = { failures[it] ?: 0 }
        )
    }

    @Test
    fun fetchesTheChaptersAheadAndBehind() {
        // 向后 2 章（第 5、6 章），向前 2 章（第 1、0 章），紧邻的第 3、4 章不预下载。
        assertEquals(
            listOf(5, 6, 1, 0),
            plan(durChapterIndex = 3, chapterSize = 100, preDownloadNum = 3)
        )
    }

    @Test
    fun neverNamesAChapterOutsideTheBook() {
        // 原来前向用 min(dur + num, chapterSize) 作闭区间上界，会把 chapterSize 这个
        // 不存在的章节也放进去；后向也没有下限，会放进负索引。两者都必须夹掉。
        // 当前是第 9 章（最后一章）：向前没有可预下载的，只剩往后的 4 章。
        assertEquals(
            listOf(7, 6, 5, 4),
            plan(durChapterIndex = 9, chapterSize = 10, preDownloadNum = 5)
        )
        // 当前是第 1 章：往后 4 章，向前已经没有章节了（不会出现负索引）。
        assertEquals(
            listOf(3, 4, 5, 6),
            plan(durChapterIndex = 1, chapterSize = 10, preDownloadNum = 5)
        )
    }

    @Test
    fun skipsChaptersThatAreAlreadyDownloaded() {
        val indexes = plan(
            durChapterIndex = 3,
            chapterSize = 100,
            preDownloadNum = 3,
            downloaded = setOf(5)
        )
        assertEquals(listOf(6, 1, 0), indexes)
    }

    @Test
    fun skipsChaptersThatKeepFailing() {
        val indexes = plan(
            durChapterIndex = 3,
            chapterSize = 100,
            preDownloadNum = 3,
            failures = mapOf(6 to 3)
        )
        // 失败满 3 次不再重试；失败 2 次仍然会试。
        assertEquals(listOf(5, 1, 0), indexes)

        val stillTrying = plan(
            durChapterIndex = 3,
            chapterSize = 100,
            preDownloadNum = 3,
            failures = mapOf(6 to 2)
        )
        assertEquals(listOf(5, 6, 1, 0), stillTrying)
    }

    @Test
    fun aBookWithoutChaptersHasNothingToFetch() {
        assertEquals(
            emptyList<Int>(),
            plan(durChapterIndex = 0, chapterSize = 0, preDownloadNum = 5)
        )
    }

    @Test
    fun theForwardWindowStopsAtTheLastChapter() {
        // 向前只剩一章可预下载（第 9 章）时只放这一章，不会越界放到第 10 章。
        assertEquals(
            listOf(9, 5, 4, 3, 2),
            plan(durChapterIndex = 7, chapterSize = 10, preDownloadNum = 5)
        )
    }
}
