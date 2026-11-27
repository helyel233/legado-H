package io.legado.app.help.review

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 评论快照滑动窗口预取器：仅在阅读页串行预取当前章 + 后 N 章（N 受 [WINDOW_LOOKAHEAD_CAP] 上限约束），
 * 通过 [Channel.CONFLATED] 常驻消费者合并最新窗口，不打断正在进行的单个按钮抓拍，
 * 并承接点击即缓存与过期刷新。去重走单飞，失败只做纯内存短冷却。
 */
object ReviewWindowPrefetcher {

    private const val TAG = "ReviewWindowPrefetcher"
    internal const val WINDOW_LOOKAHEAD_CAP = 3
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val CLICK_CAPTURE_DELAY_MS = 3_000L
    private const val BUTTON_TIMEOUT_MS = 90_000L
    private const val COOLDOWN_MS = 5 * 60 * 1000L
    private const val MAX_COOLDOWN_ENTRIES = 256

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Unit>>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val consumerLock = Any()

    private var prefetchChannel: Channel<Book>? = null
    private var consumerJob: Job? = null

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var refreshJob: Job? = null

    internal fun windowRange(
        centerIndex: Int,
        preDownloadNum: Int,
        chapterSize: Int,
    ): IntRange {
        if (chapterSize <= 0 || centerIndex !in 0 until chapterSize) return IntRange.EMPTY
        val lookahead = preDownloadNum.coerceIn(0, WINDOW_LOOKAHEAD_CAP)
        val endIndex = minOf(centerIndex + lookahead, chapterSize - 1)
        return centerIndex..endIndex
    }

    internal fun isStale(savedAt: Long, now: Long, ttlDays: Int): Boolean {
        if (ttlDays <= 0) return false
        if (savedAt <= 0L) return true
        return now - savedAt > ttlDays * ONE_DAY_MS
    }

    fun prefetch(book: Book, @Suppress("UNUSED_PARAMETER") centerIndex: Int) {
        if (!canRun(book)) return
        val channel = synchronized(consumerLock) {
            if (consumerJob?.isActive != true || prefetchChannel == null) {
                val created = Channel<Book>(Channel.CONFLATED)
                prefetchChannel = created
                consumerJob = scope.launch {
                    for (triggerBook in created) {
                        runPrefetchWindow(triggerBook)
                    }
                }
            }
            prefetchChannel
        }
        channel?.trySend(book)
    }

    private suspend fun runPrefetchWindow(book: Book) {
        if (!canRun(book)) return
        if (ReadBook.book?.bookUrl != book.bookUrl) return
        val chapterSize = ReadBook.chapterSize
        val centerIndex = ReadBook.durChapterIndex
        val range = windowRange(centerIndex, AppConfig.preDownloadNum, chapterSize)
        if (range.isEmpty()) return
        val windowChapterCount = range.last - range.first + 1
        val chapters = withContext(Dispatchers.IO) {
            range.mapNotNull { index ->
                appDb.bookChapterDao.getChapter(book.bookUrl, index)
            }
        }
        for (chapter in chapters) {
            currentCoroutineContext().ensureActive()
            if (ReadBook.book?.bookUrl != book.bookUrl || ReadBook.durChapterIndex != centerIndex) {
                return
            }
            runCatching { prefetchChapter(book, chapter, centerIndex, windowChapterCount) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    AppLog.put("$TAG 章节预取异常\n${error.localizedMessage}", error)
                }
        }
    }

    fun captureFromClick(
        book: Book,
        chapter: BookChapter,
        buttonSrc: String,
        url: String,
        html: String?,
        preloadJs: String?,
        sourceKey: String? = null,
    ) {
        if (!canRun(book) || url.isBlank()) return
        captureJob?.cancel()
        captureJob = scope.launch {
            delay(CLICK_CAPTURE_DELAY_MS)
            val key = flightKey(book, chapter, buttonSrc)
            singleFlight(key) {
                runCapture(key, "点击即缓存") {
                    if (ReviewSnapshotStore.hasComplete(book, chapter, buttonSrc)) return@runCapture true
                    val captureSource = ParagraphRuleProcessor.resolveSource(sourceKey)
                        ?: appDb.bookSourceDao.getBookSource(book.origin)
                        ?: return@runCapture false
                    val capture = withTimeout(BUTTON_TIMEOUT_MS) {
                        ReviewSnapshotCapture.capture(
                            captureSource, book, chapter, buttonSrc, url, html, preloadJs
                        )
                    }
                    ReviewSnapshotStore.put(book, capture.snapshot)
                    AppLog.putDebug("$TAG 点击即缓存成功：${chapter.title} / ${buttonSrc.take(60)}")
                    !capture.snapshot.partial
                }
            }
        }
    }

    fun refreshButton(book: Book, chapter: BookChapter, buttonSrc: String) {
        if (!canRun(book)) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val key = flightKey(book, chapter, buttonSrc)
            singleFlight(key) {
                runCapture(key, "快照刷新") {
                    val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return@runCapture false
                    val content = readChapterContent(book, chapter) ?: return@runCapture false
                    val processed = ParagraphRuleProcessor.process(book, chapter, content)
                    val button = ReviewSnapshotManager.extractReviewButtons(processed)
                        .firstOrNull { it.src.trim() == buttonSrc.trim() && it.hasAction }
                        ?: return@runCapture false
                    val page = ReviewSnapshotManager.resolveReviewPageUrl(book, bookSource, chapter, button)
                    val url = page.url?.takeIf { it.isNotBlank() } ?: return@runCapture false
                    val captureSource = ReviewSnapshotManager.resolveCaptureSource(bookSource, page.sourceKey)
                    val capture = withTimeout(BUTTON_TIMEOUT_MS) {
                        ReviewSnapshotCapture.capture(
                            captureSource, book, chapter, buttonSrc, url, page.html, page.preloadJs
                        )
                    }
                    ReviewSnapshotStore.put(book, capture.snapshot)
                    !capture.snapshot.partial
                }
            }
        }
    }

    fun cancel() {
        synchronized(consumerLock) {
            prefetchChannel?.close()
            prefetchChannel = null
            consumerJob?.cancel()
            consumerJob = null
        }
        captureJob?.cancel()
        captureJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    fun clearCooldown(bookUrl: String) {
        if (bookUrl.isBlank()) return
        val prefix = "$bookUrl|"
        val iterator = failedUntil.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) {
                iterator.remove()
            }
        }
    }

    fun clearAllCooldown() {
        failedUntil.clear()
    }

    private fun canRun(book: Book): Boolean {
        if (!NetworkUtils.isAvailable()) return false
        if (!AppConfig.syncCacheReview) return false
        if (book.isVideo) return false
        if (AppConfig.reviewPreheatWifiOnly && !NetworkUtils.isWifi()) return false
        return true
    }

    private suspend fun prefetchChapter(
        book: Book,
        chapter: BookChapter,
        expectedCenterIndex: Int,
        windowChapterCount: Int,
    ) {
        val content = readChapterContent(book, chapter) ?: return
        val processed = ParagraphRuleProcessor.process(book, chapter, content)
        val buttons = ReviewSnapshotManager.extractReviewButtonsWithStats(processed).buttons
            .filter { it.hasAction }
        if (buttons.isEmpty()) return
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        val ttlDays = AppConfig.reviewSnapshotTtlDays
        val chapterStartMs = System.currentTimeMillis()
        for (button in buttons) {
            currentCoroutineContext().ensureActive()
            if (ReadBook.book?.bookUrl != book.bookUrl || ReadBook.durChapterIndex != expectedCenterIndex) {
                return
            }
            val key = flightKey(book, chapter, button.src)
            val now = System.currentTimeMillis()
            if (isInCooldown(key, now)) continue
            val existing = ReviewSnapshotStore.get(book, chapter, button.src)
            if (existing != null && !existing.partial && !isStale(existing.savedAt, now, ttlDays)) {
                continue
            }
            singleFlight(key) {
                runCapture(key, "窗口预取") {
                    val page = ReviewSnapshotManager.resolveReviewPageUrl(book, bookSource, chapter, button)
                    val url = page.url?.takeIf { it.isNotBlank() } ?: return@runCapture false
                    val captureSource = ReviewSnapshotManager.resolveCaptureSource(bookSource, page.sourceKey)
                    val capture = withTimeout(BUTTON_TIMEOUT_MS) {
                        ReviewSnapshotCapture.capture(
                            captureSource, book, chapter, button.src, url, page.html, page.preloadJs
                        )
                    }
                    ReviewSnapshotStore.put(book, capture.snapshot)
                    !capture.snapshot.partial
                }
            }
        }
        val elapsedMs = System.currentTimeMillis() - chapterStartMs
        AppLog.putDebug(
            "$TAG 窗口预取统计：窗口=${windowChapterCount}章，章=${chapter.title}，按钮=${buttons.size}个，耗时=${elapsedMs}ms"
        )
    }

    private suspend fun readChapterContent(book: Book, chapter: BookChapter): String? {
        if (book.isVideo) return null
        return if (book.isAudio) {
            chapter.getVariable("lyric").takeIf { it.isNotBlank() }
        } else {
            BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() }
        }
    }

    private fun flightKey(book: Book, chapter: BookChapter, buttonSrc: String): String {
        return "${book.bookUrl}|${chapter.url}|${buttonSrc.trim()}"
    }

    internal suspend fun singleFlight(key: String, block: suspend () -> Unit) {
        inFlight[key]?.let { existing ->
            existing.await()
            return
        }
        lateinit var created: Deferred<Unit>
        created = scope.async(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                inFlight.remove(key, created)
            }
        }
        val existing = inFlight.putIfAbsent(key, created)
        if (existing != null) {
            created.cancel()
            existing.await()
            return
        }
        created.start()
        created.await()
    }

    private suspend fun runCapture(key: String, label: String, block: suspend () -> Boolean) {
        try {
            if (block()) {
                failedUntil.remove(key)
            } else {
                recordCooldown(key)
            }
        } catch (error: TimeoutCancellationException) {
            recordCooldown(key)
            AppLog.put("$TAG ${label}失败：抓取超时", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            recordCooldown(key)
            AppLog.put("$TAG ${label}失败\n${error.localizedMessage}", error)
        }
    }

    internal fun isInCooldown(key: String, now: Long): Boolean {
        val until = failedUntil[key] ?: return false
        if (until <= now) {
            failedUntil.remove(key, until)
            return false
        }
        return true
    }

    internal fun recordCooldown(key: String, now: Long = System.currentTimeMillis()) {
        failedUntil[key] = now + COOLDOWN_MS
        if (failedUntil.size <= MAX_COOLDOWN_ENTRIES) return
        val iterator = failedUntil.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) {
                iterator.remove()
            }
        }
        while (failedUntil.size > MAX_COOLDOWN_ENTRIES) {
            val earliest = failedUntil.entries.minByOrNull { it.value } ?: break
            failedUntil.remove(earliest.key, earliest.value)
        }
    }
}
