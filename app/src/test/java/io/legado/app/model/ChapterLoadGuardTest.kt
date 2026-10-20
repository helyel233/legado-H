package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterLoadGuardTest {

    @Test
    fun chapterWindowKeepsTheNeighbouringChapters() {
        assertTrue(ChapterLoadGuard.isInChapterWindow(chapterIndex = 5, currentChapterIndex = 5))
        assertTrue(ChapterLoadGuard.isInChapterWindow(chapterIndex = 4, currentChapterIndex = 5))
        assertTrue(ChapterLoadGuard.isInChapterWindow(chapterIndex = 6, currentChapterIndex = 5))
        assertFalse(ChapterLoadGuard.isInChapterWindow(chapterIndex = 3, currentChapterIndex = 5))
        assertFalse(ChapterLoadGuard.isInChapterWindow(chapterIndex = 7, currentChapterIndex = 5))
    }

    @Test
    fun tokenIsCurrentWhenEverythingMatches() {
        assertTrue(
            ChapterLoadGuard.isTokenCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout"
            )
        )
    }

    @Test
    fun tokenOfAnotherBookIsNotCurrent() {
        assertFalse(
            ChapterLoadGuard.isTokenCurrent(
                activeBookUrl = "bookB",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout"
            )
        )
    }

    @Test
    fun tokenOfASecondLoadIsNotCurrent() {
        // 同一章被重新加载过一次：先发的那次的结果不能再用。
        assertFalse(
            ChapterLoadGuard.isTokenCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = 8L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout"
            )
        )
    }

    @Test
    fun tokenOfAnotherLayoutIsNotCurrent() {
        // 字号/行距变了，章节要重排：旧排版下加载的结果不能装进新排版。
        assertFalse(
            ChapterLoadGuard.isTokenCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout-old",
                requestLayoutKey = "layout-new"
            )
        )
    }

    @Test
    fun tokenOfAChapterOutsideTheWindowIsNotCurrent() {
        assertFalse(
            ChapterLoadGuard.isTokenCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 9,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout"
            )
        )
    }

    @Test
    fun missingRecordedStateIsNotCurrent() {
        // 没有记录（世代号/排版键缺失）时不能当成当前请求。
        assertFalse(
            ChapterLoadGuard.isTokenCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = null,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout"
            )
        )
        assertFalse(
            ChapterLoadGuard.isTokenCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = null,
                requestLayoutKey = "layout"
            )
        )
    }

    @Test
    fun requestNeedsTheSameCurrentLayout() {
        assertTrue(
            ChapterLoadGuard.isRequestCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout",
                currentLayoutKey = "layout"
            )
        )
        assertFalse(
            ChapterLoadGuard.isRequestCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = 5,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout",
                currentLayoutKey = "layout-changed"
            )
        )
    }

    @Test
    fun slotNeedsTheRightOffset() {
        fun isSlotCurrent(chapterIndex: Int, expectedOffset: Int, slotMatches: Boolean): Boolean {
            return ChapterLoadGuard.isSlotCurrent(
                activeBookUrl = "bookA",
                requestBookUrl = "bookA",
                chapterIndex = chapterIndex,
                currentChapterIndex = 5,
                recordedGeneration = 7L,
                requestGeneration = 7L,
                recordedLayoutKey = "layout",
                requestLayoutKey = "layout",
                expectedOffset = expectedOffset,
                slotMatches = slotMatches
            )
        }

        // 当前章 → 偏移 0；上一章 → 偏移 -1；下一章 → 偏移 1。
        assertTrue(isSlotCurrent(chapterIndex = 5, expectedOffset = 0, slotMatches = true))
        assertTrue(isSlotCurrent(chapterIndex = 4, expectedOffset = -1, slotMatches = true))
        assertTrue(isSlotCurrent(chapterIndex = 6, expectedOffset = 1, slotMatches = true))

        assertFalse(isSlotCurrent(chapterIndex = 5, expectedOffset = -1, slotMatches = true))
        assertFalse(isSlotCurrent(chapterIndex = 5, expectedOffset = 0, slotMatches = false))
    }
}
