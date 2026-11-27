package io.legado.app.help.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CacheSubmission(
    val sessionId: String,
    val taskId: String,
)

/** User-facing port. Worker leases and unit mutation are intentionally absent. */
interface CacheUiPort {
    val snapshot: StateFlow<CacheSnapshot>
    val progress: StateFlow<CacheProgressSnapshot>

    fun submit(request: CacheRequest): CacheSubmission
    fun pause(submission: CacheSubmission): Boolean
    fun resume(submission: CacheSubmission): Boolean
    fun cancel(submission: CacheSubmission): Boolean
}

/** Internal worker-facing port. UI code must not depend on this interface. */
internal interface CacheWorkerPort {
    fun acquire(submission: CacheSubmission): CacheWorkerLease?
    fun reclaim(submission: CacheSubmission): CacheWorkerLease?
    fun failQueued(submission: CacheSubmission, error: String): Boolean
    fun confirmPaused(submission: CacheSubmission): Boolean
    fun commitIfLeaseActive(lease: CacheWorkerLease, action: () -> Unit): Boolean
    fun updateUnit(
        lease: CacheWorkerLease,
        key: CacheUnitKey,
        status: CacheUnitStatus,
        error: String? = null,
    ): Boolean
    fun updateProgress(
        lease: CacheWorkerLease,
        unitKey: CacheUnitKey?,
        mode: CacheProgressMode,
        current: Long,
        total: Long? = null,
        failed: Long = 0L,
    ): Boolean
    fun finish(
        lease: CacheWorkerLease,
        result: CacheResult,
        error: String? = null,
    ): Boolean
    fun skip(
        lease: CacheWorkerLease,
        reason: CacheTaskSkipReason,
        detail: String,
    ): Boolean
    fun confirmCancelled(submission: CacheSubmission): Boolean
}

/**
 * The only public submission/command boundary for offline cache work.
 * Execution workers are hosted behind [workerPort]; they cannot mutate UI state.
 */
object CacheCoordinator : CacheUiPort {

    private val store = CacheTaskStore(onPublished = CacheNotificationBridge::render)
    override val snapshot: StateFlow<CacheSnapshot> = store.snapshot
    override val progress: StateFlow<CacheProgressSnapshot> = store.progress

    internal val workerPort: CacheWorkerPort = object : CacheWorkerPort {
        override fun acquire(submission: CacheSubmission): CacheWorkerLease? {
            return store.acquireWorker(submission.sessionId, submission.taskId)
        }

        override fun reclaim(submission: CacheSubmission): CacheWorkerLease? {
            return store.reclaimWorker(submission.sessionId, submission.taskId)
        }

        override fun failQueued(submission: CacheSubmission, error: String): Boolean {
            return acceptTerminalTransition(
                store.failQueuedTask(submission.sessionId, submission.taskId, error),
            )
        }

        override fun confirmPaused(submission: CacheSubmission): Boolean {
            return store.confirmPaused(submission.sessionId, submission.taskId)
        }

        override fun commitIfLeaseActive(lease: CacheWorkerLease, action: () -> Unit): Boolean {
            return store.commitIfLeaseActive(lease, action)
        }

        override fun updateUnit(
            lease: CacheWorkerLease,
            key: CacheUnitKey,
            status: CacheUnitStatus,
            error: String?,
        ): Boolean {
            return store.updateUnit(lease, key, status, error)
        }

        override fun updateProgress(
            lease: CacheWorkerLease,
            unitKey: CacheUnitKey?,
            mode: CacheProgressMode,
            current: Long,
            total: Long?,
            failed: Long,
        ): Boolean {
            return store.updateProgress(lease, unitKey, mode, current, total, failed)
        }

        override fun finish(
            lease: CacheWorkerLease,
            result: CacheResult,
            error: String?,
        ): Boolean {
            return acceptTerminalTransition(
                store.finishTask(lease, result, error),
            )
        }

        override fun skip(
            lease: CacheWorkerLease,
            reason: CacheTaskSkipReason,
            detail: String,
        ): Boolean {
            return acceptTerminalTransition(
                store.skipTask(lease, reason, detail),
            )
        }

        override fun confirmCancelled(submission: CacheSubmission): Boolean {
            return acceptTerminalTransition(
                store.confirmCancelled(submission.sessionId, submission.taskId),
            )
        }
    }

    private val workerDispatcher: CacheWorkerDispatcher =
        CacheWorkerDispatcherImpl(workerPort)
    private val automaticSubmitLock = Any()
    private val terminalEffectsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminalEffectsInProgress = ConcurrentHashMap.newKeySet<String>()
    private val terminalEffectsRetryAttempts = ConcurrentHashMap<String, Int>()
    private val terminalEffectsRetryScheduled = ConcurrentHashMap.newKeySet<String>()

    init {
        recoveryScope.launch {
            replayPendingTerminalEffects()
            workerDispatcher.recover(snapshot.value)
        }
    }

    override fun submit(request: CacheRequest): CacheSubmission {
        validateUiRequest(request)
        validateReaderTtsPrerequisite(request)
        val session = store.createSession(request.bookName)
        val task = store.addTask(session.sessionId, request)
        record(
            CacheLogEventType.REQUEST_ACCEPTED,
            session.sessionId,
            task.taskId,
            "source=${request.source} kind=${request.kind} phase=${request.phase}",
        )
        return CacheSubmission(session.sessionId, task.taskId).also(workerDispatcher::start)
    }

    /**
     * The only explicit-download entry point for book pages.
     *
     * The book type, rather than the screen that initiated the action, decides the worker.
     * Audio and video books must never be sent to the text body worker.
     */
    fun submitBookDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
    ): CacheSubmission {
        return if (book.isAudio || book.isVideo) {
            submitMediaDownload(book, chapterIndexes, source)
        } else {
            submitTextDownload(book, chapterIndexes, source)
        }
    }

    /** Shared BODY submission used by text-book download actions and automatic refresh. */
    fun submitTextDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
        ttsEnabled: Boolean = false,
    ): CacheSubmission {
        require(!book.isAudio && !book.isVideo) {
            "text download is invalid for media book: ${book.bookUrl}"
        }
        val indexes = chapterIndexes.distinct().sorted()
        require(indexes.isNotEmpty()) { "text download has no chapters" }
        return submit(
            CacheRequest(
                source = source,
                kind = CacheKind.TEXT,
                phase = CachePhase.BODY,
                bookUrl = book.bookUrl,
                bookName = book.name,
                units = indexes.map { CacheUnitKey(book.bookUrl, it) },
                ttsEnabled = ttsEnabled,
            )
        )
    }

    /** Shared MEDIA submission used by every audio/video-book download action. */
    fun submitMediaDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
    ): CacheSubmission {
        require(book.isAudio || book.isVideo) { "media download requires an audio or video book" }
        val indexes = chapterIndexes.distinct().sorted()
        require(indexes.isNotEmpty()) { "media download has no chapters" }
        return submit(
            CacheRequest(
                source = source,
                kind = if (book.isVideo) CacheKind.VIDEO else CacheKind.AUDIO,
                phase = CachePhase.MEDIA,
                bookUrl = book.bookUrl,
                bookName = book.name,
                units = indexes.map { CacheUnitKey(book.bookUrl, it) },
            )
        )
    }

    /** Current chapter counts as the first chapter in the configured predownload window. */
    fun automaticChapterIndexes(
        startIndex: Int,
        chapterCount: Int,
        preDownloadCount: Int,
    ): List<Int> {
        if (chapterCount <= 0 || startIndex !in 0 until chapterCount) return emptyList()
        val count = preDownloadCount.coerceAtLeast(1)
        val endIndex = (startIndex.toLong() + count - 1L)
            .coerceAtMost(chapterCount - 1L)
            .toInt()
        return (startIndex..endIndex).toList()
    }

    /**
     * Automatic book download never creates a competing same-domain session for the book.
     */
    fun submitAutomaticBookDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
    ): CacheSubmission? = synchronized(automaticSubmitLock) {
        require(!book.isVideo) { "automatic book download is invalid for video book" }
        val kind = if (book.isAudio) CacheKind.AUDIO else CacheKind.TEXT
        if (hasActiveDownload(book.bookUrl, kind)) return@synchronized null
        if (book.isAudio) {
            submitMediaDownload(book, chapterIndexes, source)
        } else {
            submitTextDownload(book, chapterIndexes, source)
        }
    }

    private fun hasActiveDownload(bookUrl: String, kind: CacheKind): Boolean {
        return snapshot.value.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .any { task ->
                task.bookUrl == bookUrl &&
                    task.kind == kind &&
                    !CacheLifecycleRules.isTerminal(task.status)
            }
    }

    override fun pause(submission: CacheSubmission): Boolean {
        return store.pauseTask(submission.sessionId, submission.taskId).also {
            if (it) workerDispatcher.pause(submission)
        }
    }

    override fun resume(submission: CacheSubmission): Boolean {
        val lease = store.resumeTask(submission.sessionId, submission.taskId) ?: return false
        workerDispatcher.resume(submission, lease)
        return true
    }

    override fun cancel(submission: CacheSubmission): Boolean {
        return store.beginCancel(submission.sessionId, submission.taskId).also {
            if (it) workerDispatcher.cancel(submission)
        }
    }

    /** Summary notification commands intentionally operate on all active tasks. */
    fun pauseAll(): Int = commandAll { pause(it) }

    fun resumeAll(): Int = commandAll { resume(it) }

    fun cancelAll(): Int = commandAll { cancel(it) }

    private fun commandAll(command: (CacheSubmission) -> Boolean): Int {
        val submissions = snapshot.value.sessions
            .flatMap { it.tasks }
            .filter { !CacheLifecycleRules.isTerminal(it.status) }
            .map { CacheSubmission(it.sessionId, it.taskId) }
        return submissions.count(command)
    }

    internal fun currentTask(submission: CacheSubmission): CacheTaskState? {
        return store.currentTask(submission.sessionId, submission.taskId)
    }

    private fun acceptTerminalTransition(accepted: Boolean): Boolean {
        if (accepted) replayPendingTerminalEffects()
        return accepted
    }

    private fun replayPendingTerminalEffects() {
        store.pendingTerminalEffects().forEach { task ->
            processTerminalEffects(CacheSubmission(task.sessionId, task.taskId))
        }
    }

    private fun processTerminalEffects(submission: CacheSubmission) {
        val effectKey = "${submission.sessionId}/${submission.taskId}"
        if (!terminalEffectsInProgress.add(effectKey)) return
        val task = currentTask(submission)
            ?.takeIf { CacheLifecycleRules.isTerminal(it.status) && it.terminalEffectsPending }
            ?: run {
                terminalEffectsInProgress.remove(effectKey)
                return
            }
        try {
            val finalTask = currentTask(submission)
                ?: error("terminal task disappeared: ${submission.sessionId}/${submission.taskId}")
            CacheNotificationBridge.finished(
                snapshot = snapshot.value,
                progress = progress.value,
                task = finalTask,
                result = requireNotNull(finalTask.result) { "terminal task has no result" },
                error = finalTask.error,
            )
            check(store.completeTerminalEffects(submission.sessionId, submission.taskId)) {
                "terminal effects were not acknowledged: ${submission.sessionId}/${submission.taskId}"
            }
            terminalEffectsRetryAttempts.remove(effectKey)
        } catch (error: Throwable) {
            AppLogCacheLogSink.record(
                CacheLogEvent(
                    type = CacheLogEventType.TERMINAL_EFFECT_FAILED,
                    sessionId = submission.sessionId,
                    taskId = submission.taskId,
                    detail = "${error::class.simpleName}: ${error.localizedMessage}",
                )
            )
            scheduleTerminalEffectsRetry(submission, effectKey)
        } finally {
            terminalEffectsInProgress.remove(effectKey)
        }
    }

    /** Retry a failed terminal outbox effect without waiting for process restart or another task. */
    private fun scheduleTerminalEffectsRetry(
        submission: CacheSubmission,
        effectKey: String,
    ) {
        if (!terminalEffectsRetryScheduled.add(effectKey)) return
        terminalEffectsScope.launch {
            try {
                while (true) {
                    val attempt = terminalEffectsRetryAttempts
                        .merge(effectKey, 1, Int::plus) ?: 1
                    if (CacheRetryPolicy.shouldStop(attempt)) {
                        // 持续失败时不再无限重试：否则这个协程会常驻并每 15s 唤醒一次
                        AppLog.put("缓存终态副作用重试超过上限，放弃：$effectKey")
                        terminalEffectsRetryAttempts.remove(effectKey)
                        return@launch
                    }
                    val delaySeconds = when (attempt) {
                        1 -> 1L
                        2 -> 2L
                        3 -> 5L
                        else -> 15L
                    }
                    kotlinx.coroutines.delay(TimeUnit.SECONDS.toMillis(delaySeconds))
                    processTerminalEffects(submission)
                    val pending = store.currentTask(submission.sessionId, submission.taskId)
                        ?.let { CacheLifecycleRules.isTerminal(it.status) && it.terminalEffectsPending }
                        ?: false
                    if (!pending) {
                        terminalEffectsRetryAttempts.remove(effectKey)
                        return@launch
                    }
                }
            } finally {
                terminalEffectsRetryScheduled.remove(effectKey)
            }
        }
    }

    private fun validateUiRequest(request: CacheRequest) {
        when (request.phase) {
            CachePhase.TTS -> {
                require(request.kind.ttsPrerequisitePhase() != null) {
                    "${request.kind} requests cannot use TTS phase"
                }
                require(request.source == CacheRequestSource.READER) {
                    "TTS tasks must be appended by the coordinator or submitted by READER"
                }
                // legado-H 未移植 TTS 缓存 Worker（依赖 TTS-V2 引擎体系），TTS 任务直接拒绝
                error("TTS cache worker is not available in this build")
            }
            else -> {
                require(request.kind != CacheKind.TEXT || request.phase == CachePhase.BODY) {
                    "text UI requests must use BODY phase"
                }
            }
        }
        validateCommon(request)
    }

    /** READER may submit TTS only when the same chapter's body text is already available. */
    private fun validateReaderTtsPrerequisite(request: CacheRequest) {
        if (request.phase != CachePhase.TTS || request.source != CacheRequestSource.READER) return
        val book = appDb.bookDao.getBook(request.bookUrl)
            ?: error("reader tts prerequisite book not found: ${request.bookUrl}")
        require(request.kind == CacheKind.TEXT) {
            "reader tts kind ${request.kind} is not TEXT"
        }
        require(!book.isAudio && !book.isVideo) {
            "tts artifacts require a text book: ${book.bookUrl}"
        }
        val chaptersByIndex = request.units.associate { unit ->
            unit.chapterIndex to appDb.bookChapterDao.getChapter(request.bookUrl, unit.chapterIndex)
        }
        require(chaptersByIndex.values.all { it != null }) {
            "reader tts prerequisite chapter is missing: ${request.bookUrl}"
        }
        val incomplete = request.units.mapNotNull { unit ->
            val chapter = requireNotNull(chaptersByIndex[unit.chapterIndex])
            unit.takeUnless { BookHelp.hasContent(book, chapter) }
        }
        require(incomplete.isEmpty()) {
            "reader tts prerequisite body text unavailable: " +
                incomplete.joinToString { it.chapterIndex.toString() }
        }
    }

    private fun validateCommon(request: CacheRequest) {
        require(request.bookUrl.isNotBlank()) { "cache request bookUrl is blank" }
        require(request.bookName.isNotBlank()) { "cache request bookName is blank" }
        require(request.units.isNotEmpty()) { "cache request has no units" }
        require(request.units.all { it.bookUrl == request.bookUrl }) {
            "cache request contains units from another book"
        }
        require(request.kind.supports(request.phase)) {
            "unsupported cache artifact ${request.kind}/${request.phase}"
        }
        if (request.ttsEnabled) {
            require(
                request.phase == CachePhase.TTS ||
                    request.kind.ttsPrerequisitePhase() == request.phase
            ) {
                "${request.kind}/${request.phase} cannot enable tts caching"
            }
        }
    }

    private fun record(
        type: CacheLogEventType,
        sessionId: String,
        taskId: String,
        detail: String,
    ) {
        AppLogCacheLogSink.record(CacheLogEvent(type, sessionId, taskId, detail))
    }
}
