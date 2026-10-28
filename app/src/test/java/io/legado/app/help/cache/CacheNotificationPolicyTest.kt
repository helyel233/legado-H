package io.legado.app.help.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论缓存通知可见性规则的回归网
 *
 * 需求：评论缓存的各种行为不进手机通知栏。
 * 这里锁住"哪些任务允许出现在通知里"的判定，避免以后又被改回去。
 */
class CacheNotificationPolicyTest {

    private fun task(
        kind: CacheKind,
        phase: CachePhase,
        status: CacheLifecycle = CacheLifecycle.RUNNING,
    ) = CacheTaskState(
        taskId = "$kind-$phase",
        sessionId = "session",
        source = CacheRequestSource.CACHE_ACTIVITY,
        kind = kind,
        phase = phase,
        bookUrl = "bookUrl",
        bookName = "book",
        units = emptyList(),
        status = status,
    )

    @Test
    fun `review task is detected for text and audio`() {
        assertTrue(CacheNotificationPolicy.isReviewTask(CacheKind.TEXT, CachePhase.REVIEW))
        assertTrue(CacheNotificationPolicy.isReviewTask(CacheKind.AUDIO, CachePhase.REVIEW))
    }

    @Test
    fun `non review phases are not review tasks`() {
        assertFalse(CacheNotificationPolicy.isReviewTask(CacheKind.TEXT, CachePhase.BODY))
        assertFalse(CacheNotificationPolicy.isReviewTask(CacheKind.AUDIO, CachePhase.MEDIA))
        assertFalse(CacheNotificationPolicy.isReviewTask(CacheKind.TEXT, CachePhase.TTS))
    }

    @Test
    fun `video has no review prerequisite so review phase is not a review task`() {
        assertFalse(CacheNotificationPolicy.isReviewTask(CacheKind.VIDEO, CachePhase.REVIEW))
    }

    @Test
    fun `silenced notifications hide review tasks`() {
        val tasks = listOf(
            task(CacheKind.TEXT, CachePhase.BODY),
            task(CacheKind.TEXT, CachePhase.REVIEW),
        )
        val visible = CacheNotificationPolicy.visibleTasks(tasks, silenced = true)
        assertEquals(1, visible.size)
        assertEquals(CachePhase.BODY, visible.first().phase)
    }

    @Test
    fun `non silenced notifications keep review tasks`() {
        val tasks = listOf(
            task(CacheKind.TEXT, CachePhase.BODY),
            task(CacheKind.TEXT, CachePhase.REVIEW),
        )
        assertEquals(2, CacheNotificationPolicy.visibleTasks(tasks, silenced = false).size)
    }

    @Test
    fun `only review tasks left means nothing is visible when silenced`() {
        val tasks = listOf(task(CacheKind.TEXT, CachePhase.REVIEW))
        assertTrue(CacheNotificationPolicy.visibleTasks(tasks, silenced = true).isEmpty())
    }

    @Test
    fun `visible active task ignores terminal tasks`() {
        val tasks = listOf(
            task(CacheKind.TEXT, CachePhase.BODY, status = CacheLifecycle.COMPLETED),
            task(CacheKind.TEXT, CachePhase.REVIEW, status = CacheLifecycle.RUNNING),
        )
        assertFalse(
            CacheNotificationPolicy.hasVisibleActiveTask(
                tasks,
                silenced = true,
                isTerminal = { it.status == CacheLifecycle.COMPLETED },
            )
        )
    }

    @Test
    fun `visible active task exists when a body task is still running`() {
        val tasks = listOf(
            task(CacheKind.TEXT, CachePhase.BODY, status = CacheLifecycle.RUNNING),
            task(CacheKind.TEXT, CachePhase.REVIEW, status = CacheLifecycle.RUNNING),
        )
        assertTrue(
            CacheNotificationPolicy.hasVisibleActiveTask(
                tasks,
                silenced = true,
                isTerminal = { it.status == CacheLifecycle.COMPLETED },
            )
        )
    }

    @Test
    fun `terminal notice for a review task is blocked when silenced`() {
        val review = task(CacheKind.TEXT, CachePhase.REVIEW, status = CacheLifecycle.COMPLETED)
        assertFalse(CacheNotificationPolicy.allowsTerminalNotice(review, silenced = true))
        assertTrue(CacheNotificationPolicy.allowsTerminalNotice(review, silenced = false))
    }

    @Test
    fun `terminal notice for a body task survives silencing`() {
        val body = task(CacheKind.TEXT, CachePhase.BODY, status = CacheLifecycle.COMPLETED)
        assertTrue(CacheNotificationPolicy.allowsTerminalNotice(body, silenced = true))
    }

    @Test
    fun `missing task never produces a terminal notice`() {
        assertFalse(CacheNotificationPolicy.allowsTerminalNotice(null, silenced = false))
        assertFalse(CacheNotificationPolicy.allowsTerminalNotice(null, silenced = true))
    }
}
