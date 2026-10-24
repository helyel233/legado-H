package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段评预热带纯策略回归：预热窗口、保鲜期、失败退避。
 * 这三个判定决定「哪些章该抓 / 哪些快照该重抓 / 失败后是否还值得再试」，
 * 全部是无环境访问的纯函数，单独钉住边界。
 */
class ReviewPreheatCoordinatorPolicyTest {

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun preheatWindowCoversPreviousCurrentAndNext() {
        assertEquals(listOf(4, 5, 6), ReviewPreheatCoordinator.preheatWindow(5, 100))
    }

    @Test
    fun preheatWindowClampsAtBothEnds() {
        assertEquals(listOf(0, 1), ReviewPreheatCoordinator.preheatWindow(0, 100))
        assertEquals(listOf(98, 99), ReviewPreheatCoordinator.preheatWindow(99, 100))
    }

    @Test
    fun preheatWindowHandlesTinyAndEmptyBooks() {
        assertEquals(listOf(0), ReviewPreheatCoordinator.preheatWindow(0, 1))
        assertEquals(emptyList<Int>(), ReviewPreheatCoordinator.preheatWindow(0, 0))
        assertEquals(emptyList<Int>(), ReviewPreheatCoordinator.preheatWindow(-1, 10))
    }

    @Test
    fun staleDetectionUsesTtlBoundary() {
        val now = 10 * day
        assertFalse(ReviewPreheatCoordinator.isStale(now - 2 * day, now, ttlDays = 3))
        // 刚好等于保鲜期不算过期（严格大于才过期）
        assertFalse(ReviewPreheatCoordinator.isStale(now - 3 * day, now, ttlDays = 3))
        assertTrue(ReviewPreheatCoordinator.isStale(now - 3 * day - 1, now, ttlDays = 3))
    }

    @Test
    fun staleDetectionSpecialCases() {
        val now = 10 * day
        // ttl <= 0 = 不保鲜，永不过期
        assertFalse(ReviewPreheatCoordinator.isStale(now - 100 * day, now, ttlDays = 0))
        // savedAt 缺失（0/负）视为必需抓取
        assertTrue(ReviewPreheatCoordinator.isStale(0L, now, ttlDays = 3))
        assertTrue(ReviewPreheatCoordinator.isStale(-1L, now, ttlDays = 3))
        // 时钟跳变（快照时间戳在未来）不过期
        assertFalse(ReviewPreheatCoordinator.isStale(now + day, now, ttlDays = 3))
    }

    @Test
    fun backoffWindowSkipsRecentFailuresOnly() {
        val now = 1_000L
        assertFalse(ReviewPreheatCoordinator.isInBackoff(null, now))
        assertFalse(ReviewPreheatCoordinator.isInBackoff(now - 1, now))
        assertFalse(ReviewPreheatCoordinator.isInBackoff(now, now))
        assertTrue(ReviewPreheatCoordinator.isInBackoff(now + 1, now))
    }
}
