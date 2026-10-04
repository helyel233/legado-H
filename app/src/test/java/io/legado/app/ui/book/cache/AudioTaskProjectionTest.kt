package io.legado.app.ui.book.cache

import io.legado.app.help.cache.CacheKind
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CachePhase
import io.legado.app.help.cache.CacheProgressMode
import io.legado.app.help.cache.CacheProgressSnapshot
import io.legado.app.help.cache.CacheProgressState
import io.legado.app.help.cache.CacheRequestSource
import io.legado.app.help.cache.CacheSessionState
import io.legado.app.help.cache.CacheSnapshot
import io.legado.app.help.cache.CacheTaskState
import io.legado.app.help.cache.CacheUnitKey
import io.legado.app.help.cache.CacheUnitState
import io.legado.app.help.cache.CacheUnitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTaskProjectionTest {

    @Test
    fun lifecycleStatusMapping() {
        val expectations = mapOf(
            CacheLifecycle.QUEUED to CacheTaskStatus.PENDING,
            CacheLifecycle.PAUSING to CacheTaskStatus.PAUSED,
            CacheLifecycle.PAUSED to CacheTaskStatus.PAUSED,
            CacheLifecycle.INTERRUPTED to CacheTaskStatus.PAUSED,
            CacheLifecycle.CANCELLING to CacheTaskStatus.CANCELLED,
            CacheLifecycle.CANCELLED to CacheTaskStatus.CANCELLED,
            CacheLifecycle.COMPLETED to CacheTaskStatus.COMPLETED,
            CacheLifecycle.FAILED to CacheTaskStatus.FAILED,
        )
        expectations.forEach { (lifecycle, expected) ->
            val task = task(status = lifecycle)
            val projected = projectSingle(task)
            assertEquals(lifecycle.name, expected, projected.status)
        }
    }

    @Test
    fun runningStatusDependsOnProgressMode() {
        val task = task(status = CacheLifecycle.RUNNING)
        assertEquals(CacheTaskStatus.CACHING, projectSingle(task).status)
        assertEquals(
            CacheTaskStatus.RESOLVING,
            projectSingle(task, progressOf(task, CacheProgressMode.INDETERMINATE)).status,
        )
        assertEquals(
            CacheTaskStatus.CACHING,
            projectSingle(task, progressOf(task, CacheProgressMode.BYTES, 10L, 100L)).status,
        )
        assertEquals(
            CacheTaskStatus.CACHING,
            projectSingle(task, progressOf(task, CacheProgressMode.CHAPTERS, 1L, 5L)).status,
        )
    }

    @Test
    fun activeFlagExcludesPausedAndTerminal() {
        assertTrue(projectSingle(task(status = CacheLifecycle.QUEUED)).active)
        assertTrue(projectSingle(task(status = CacheLifecycle.RUNNING)).active)
        assertFalse(projectSingle(task(status = CacheLifecycle.PAUSED)).active)
        assertFalse(projectSingle(task(status = CacheLifecycle.COMPLETED)).active)
        assertFalse(projectSingle(task(status = CacheLifecycle.FAILED)).active)
        assertFalse(projectSingle(task(status = CacheLifecycle.CANCELLED)).active)
    }

    @Test
    fun activeMediaWinsOverActiveReview() {
        val media = task(phase = CachePhase.MEDIA, status = CacheLifecycle.RUNNING)
        val review = task(phase = CachePhase.REVIEW, status = CacheLifecycle.RUNNING)
        val projected = projectAll(media, review).getValue(media.bookUrl)

        assertEquals(CacheTaskStatus.CACHING, projected.status)
        assertEquals(media.units.size, projected.totalChapters)
    }

    @Test
    fun activeReviewWinsOverTerminalMedia() {
        val media = task(
            phase = CachePhase.MEDIA,
            status = CacheLifecycle.COMPLETED,
            updatedAt = 100L,
            units = listOf(unit("book", 0), unit("book", 1)),
        )
        val review = task(
            phase = CachePhase.REVIEW,
            status = CacheLifecycle.RUNNING,
            updatedAt = 10L,
            units = listOf(unit("book", 0)),
        )
        val projected = projectAll(media, review).getValue("book")

        assertEquals(CacheTaskStatus.CACHING, projected.status)
        assertEquals(1, projected.totalChapters)
    }

    @Test
    fun latestTerminalTaskSelectedWhenNoActiveTasks() {
        val older = task(
            phase = CachePhase.REVIEW,
            status = CacheLifecycle.COMPLETED,
            updatedAt = 50L,
        )
        val newer = task(
            phase = CachePhase.MEDIA,
            status = CacheLifecycle.FAILED,
            updatedAt = 100L,
        )
        val projected = projectAll(older, newer).getValue("book")

        assertEquals(CacheTaskStatus.FAILED, projected.status)
        assertFalse(projected.active)
    }

    @Test
    fun completedChapterCountUsesSucceededUnits() {
        val task = task(
            status = CacheLifecycle.RUNNING,
            units = listOf(
                unit("book", 0, CacheUnitStatus.SUCCEEDED),
                unit("book", 1, CacheUnitStatus.SUCCEEDED),
                unit("book", 2, CacheUnitStatus.FAILED),
                unit("book", 3, CacheUnitStatus.PENDING),
            ),
        )
        val projected = projectSingle(task)

        assertEquals(4, projected.totalChapters)
        assertEquals(2, projected.completedChapters)
        assertEquals(3, projected.currentChapterIndex)
    }

    @Test
    fun byteProgressFeedsStateAndMessageFacts() {
        val task = task(status = CacheLifecycle.RUNNING)
        val progress = progressOf(task, CacheProgressMode.BYTES, current = 512L, total = 1024L)
        val facts = mutableListOf<AudioTaskMessageFacts>()

        val projected = AudioTaskProjection
            .project(snapshotOf(task), progress) { received ->
                facts += received
                "message"
            }
            .getValue("book")

        assertEquals(512L, projected.downloadedBytes)
        assertEquals(1024L, projected.totalBytes)
        assertEquals("message", projected.message)
        assertEquals(1, facts.size)
        assertEquals(512L, facts.single().downloadedBytes)
        assertEquals(1024L, facts.single().totalBytes)
        assertEquals(CacheTaskStatus.CACHING, facts.single().status)
    }

    @Test
    fun chapterProgressIsNotTreatedAsByteProgress() {
        val task = task(status = CacheLifecycle.RUNNING)
        val projected = projectSingle(
            task,
            progressOf(task, CacheProgressMode.CHAPTERS, current = 2L, total = 8L),
        )

        assertEquals(0L, projected.downloadedBytes)
        assertNull(projected.totalBytes)
    }

    @Test
    fun failureErrorReachesMessageFacts() {
        val task = task(status = CacheLifecycle.FAILED, error = "boom")
        val facts = mutableListOf<AudioTaskMessageFacts>()

        AudioTaskProjection.project(snapshotOf(task), CacheProgressSnapshot()) { received ->
            facts += received
            ""
        }

        assertEquals("boom", facts.single().error)
        assertEquals(CacheTaskStatus.FAILED, facts.single().status)
    }

    @Test
    fun onlyAudioDomainTasksAreProjected() {
        val textBody = task(kind = CacheKind.TEXT, phase = CachePhase.BODY)
        val textReview = task(kind = CacheKind.TEXT, phase = CachePhase.REVIEW)
        val video = task(kind = CacheKind.VIDEO, phase = CachePhase.MEDIA, bookUrl = "video")

        val result = projectAll(textBody, textReview, video)

        assertEquals(setOf("video"), result.keys)
    }

    @Test
    fun booksAreAggregatedIndependently() {
        val first = task(bookUrl = "book-a", status = CacheLifecycle.RUNNING)
        val second = task(bookUrl = "book-b", status = CacheLifecycle.PAUSED)

        val result = projectAll(first, second)

        assertEquals(setOf("book-a", "book-b"), result.keys)
        assertEquals(CacheTaskStatus.CACHING, result.getValue("book-a").status)
        assertEquals(CacheTaskStatus.PAUSED, result.getValue("book-b").status)
    }

    @Test
    fun snapshotQueriesReportOnlyActiveTasks() {
        val active = task(bookUrl = "book-a", kind = CacheKind.TEXT, phase = CachePhase.BODY)
        val finished = task(bookUrl = "book-a", status = CacheLifecycle.COMPLETED)
        val paused = task(bookUrl = "book-b", status = CacheLifecycle.PAUSED)
        val snapshot = snapshotOf(active, finished, paused)

        assertEquals(listOf(active), snapshot.activeTasksFor("book-a"))
        assertEquals(listOf(paused), snapshot.activeTasksFor("book-b"))
        assertTrue(snapshot.hasActiveTasks())

        val terminalOnly = snapshotOf(finished)
        assertTrue(terminalOnly.activeTasksFor("book-a").isEmpty())
        assertFalse(terminalOnly.hasActiveTasks())
    }

    private fun projectSingle(
        task: CacheTaskState,
        progress: CacheProgressSnapshot = CacheProgressSnapshot(),
    ): AudioCacheTaskState {
        return AudioTaskProjection.project(snapshotOf(task), progress).getValue(task.bookUrl)
    }

    private fun projectAll(vararg tasks: CacheTaskState): Map<String, AudioCacheTaskState> {
        return AudioTaskProjection.project(snapshotOf(*tasks), CacheProgressSnapshot())
    }

    private fun snapshotOf(vararg tasks: CacheTaskState): CacheSnapshot {
        return CacheSnapshot(
            sessions = listOf(
                CacheSessionState(
                    sessionId = SESSION_ID,
                    title = "session",
                    tasks = tasks.toList(),
                ),
            ),
        )
    }

    private fun progressOf(
        task: CacheTaskState,
        mode: CacheProgressMode,
        current: Long = 0L,
        total: Long? = null,
    ): CacheProgressSnapshot {
        return CacheProgressSnapshot(
            states = listOf(
                CacheProgressState(
                    sessionId = task.sessionId,
                    taskId = task.taskId,
                    generation = task.generation,
                    mode = mode,
                    current = current,
                    total = total,
                ),
            ),
        )
    }

    private fun task(
        bookUrl: String = "book",
        kind: CacheKind = CacheKind.AUDIO,
        phase: CachePhase = CachePhase.MEDIA,
        status: CacheLifecycle = CacheLifecycle.QUEUED,
        units: List<CacheUnitState> = listOf(unit(bookUrl, 0)),
        updatedAt: Long = 0L,
        error: String? = null,
    ): CacheTaskState {
        return CacheTaskState(
            taskId = "task-$bookUrl-$kind-$phase-$status-$updatedAt",
            sessionId = SESSION_ID,
            source = CacheRequestSource.CACHE_MANAGE,
            kind = kind,
            phase = phase,
            bookUrl = bookUrl,
            bookName = "《$bookUrl》",
            units = units,
            status = status,
            error = error,
            updatedAt = updatedAt,
        )
    }

    private fun unit(
        bookUrl: String,
        index: Int,
        status: CacheUnitStatus = CacheUnitStatus.PENDING,
    ): CacheUnitState {
        return CacheUnitState(
            key = CacheUnitKey(bookUrl, index),
            status = status,
        )
    }

    private companion object {
        const val SESSION_ID = "session"
    }
}
