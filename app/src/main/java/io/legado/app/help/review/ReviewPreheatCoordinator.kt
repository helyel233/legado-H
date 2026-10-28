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
import io.legado.app.ui.widget.dialog.CommentWebViewSession
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.mapAsyncIndexed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 段评预热带（阅读页提前抓取评论快照）。
 *
 * 目标：用户点段评图标时本地已有快照 → 0 秒可见（快照优先/仅快照模式）。
 *
 * 与既有「缓存评论」任务的关系：
 * - 复用同一套抓取执行体（[ReviewSnapshotManager.resolveReviewPageUrl] 解析真实
 *   评论页 + [ReviewSnapshotCapture] 抓取 + [ReviewSnapshotStore] 落盘）；
 * - 不参与 CacheCoordinator 的租约体系，只补「正在读的三章」这个窗口，章节缓存
 *   任务继续覆盖整本缓存场景；两边都以 hasComplete 跳过，重复抓取也只是幂等重写；
 * - 失败退避与保鲜期刷新都在本对象内闭环。
 *
 * 触发时机（ReadBookActivity.upContent，relativePosition == 0）：上一章 + 本章 + 下一章。
 */
object ReviewPreheatCoordinator {

    private const val TAG = "ReviewPreheat"

    /** 预热窗口：当前章前后各一章 */
    internal const val PREHEAT_WINDOW = 1

    /** 单章抓取失败后的退避时长：避免快速翻章时每章都浪费一轮 */
    private const val FAIL_BACKOFF_MS = 10 * 60 * 1000L

    /** 点击即缓存的启动延迟：避开评论弹窗创建高峰，错开 WebView 使用 */
    private const val CLICK_CAPTURE_DELAY_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 章级失败退避表：key = bookUrl|chapterUrl → 退避截止时间戳。
     * 用 chapterUrl 而不是 chapterIndex：换源后同一个 index 指向的是另一章，
     * 用 index 会让新章被旧章的退避白白跳过。
     */
    private val failedUntil = ConcurrentHashMap<String, Long>()

    /** 退避表条目上限，超过后顺带清掉已过期的条目，防止随阅读过的书无限增长 */
    private const val MAX_BACKOFF_ENTRIES = 512

    @Volatile
    private var preheatJob: Job? = null

    /** 点击即缓存 / 快照刷新的任务句柄：离开阅读页时一并取消 */
    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var refreshJob: Job? = null

    /** 取消在途预热（离开阅读页/换书时调用）；点击即缓存是短任务，不在此列 */
    fun cancelPreheat() {
        preheatJob?.cancel()
        preheatJob = null
        // 点击即缓存与快照刷新同样会抓 WebView（可达数十秒），离开阅读页时一并停掉，
        // 否则会一直持有书籍/章节与快照 HTML
        captureJob?.cancel()
        captureJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * 预热窗口章序号：中心章前后各 [PREHEAT_WINDOW] 章，夹到合法范围。
     * 纯函数（供单测），不含任何环境访问。
     */
    internal fun preheatWindow(centerIndex: Int, chapterSize: Int): List<Int> {
        if (chapterSize <= 0 || centerIndex < 0) return emptyList()
        val from = (centerIndex - PREHEAT_WINDOW).coerceAtLeast(0)
        val to = (centerIndex + PREHEAT_WINDOW).coerceAtMost(chapterSize - 1)
        if (from > to) return emptyList()
        return (from..to).toList()
    }

    /**
     * 快照是否已过保鲜期（需要重抓）。
     * 过期不删除：点击时仍先用旧快照保证 0 秒可见，由刷新覆盖。
     * 纯函数（供单测）：ttlDays <= 0 表示不保鲜；savedAt <= 0 视为必需抓取。
     */
    internal fun isStale(savedAt: Long, now: Long, ttlDays: Int): Boolean {
        if (ttlDays <= 0) return false
        if (savedAt <= 0L) return true
        return now - savedAt > ttlDays * ONE_DAY_MS
    }

    /**
     * 是否处于失败退避窗口内。纯函数（供单测）。
     */
    internal fun isInBackoff(failedUntil: Long?, now: Long): Boolean {
        return ReviewBackoffPolicy.isInBackoff(failedUntil, now)
    }

    /** 退避表超过上限时清掉已过期条目 */
    private fun trimBackoff() {
        if (!ReviewBackoffPolicy.shouldTrim(failedUntil.size, MAX_BACKOFF_ENTRIES)) return
        val expired = ReviewBackoffPolicy.expiredKeys(failedUntil, System.currentTimeMillis())
        expired.forEach { failedUntil.remove(it) }
    }

    /**
     * 预热中心章 [centerIndex] 及其前后一章。重复调用会取消在途任务
     * （快速翻章时旧任务作废），因此同时最多只有一轮三章预热在跑。
     */
    fun preheat(book: Book, centerIndex: Int) {
        if (!AppConfig.syncCacheReview) return
        if (book.isVideo) return
        if (AppConfig.reviewPreheatWifiOnly && !NetworkUtils.isWifi()) return
        preheatJob?.cancel()
        preheatJob = scope.launch {
            val chapters = withContext(Dispatchers.IO) {
                preheatWindow(centerIndex, ReadBook.chapterSize).mapNotNull { index ->
                    appDb.bookChapterDao.getChapter(book.bookUrl, index)
                }
            }
            for (chapter in chapters) {
                currentCoroutineContext().ensureActive()
                runCatching { preheatChapter(book, chapter) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        AppLog.put("$TAG 章节预热异常\n${error.localizedMessage}", error)
                    }
            }
        }
    }

    /**
     * 点击即缓存：用户点击段评时，点击 JS 已解析出真实评论页（url + 渲染 HTML），
     * 顺手抓成快照，下次同章再点即可 0 秒打开（网络优先模式下同样生效，
     * 为切换到快照优先攒料）。延迟起步避开弹窗创建高峰。
     *
     * 与预热同一套门控（syncCacheReview / 仅 WiFi）：点击本身已花的流量之外，
     * 不再额外花后台流量。
     */
    fun captureFromClick(
        book: Book,
        chapter: BookChapter,
        buttonSrc: String,
        url: String,
        html: String?,
        preloadJs: String?,
    ) {
        if (!AppConfig.syncCacheReview) return
        if (book.isVideo) return
        if (url.isBlank()) return
        if (AppConfig.reviewPreheatWifiOnly && !NetworkUtils.isWifi()) return
        captureJob = scope.launch {
            delay(CLICK_CAPTURE_DELAY_MS)
            runCatching {
                if (ReviewSnapshotStore.hasComplete(book, chapter, buttonSrc)) return@launch
                val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return@launch
                // put 前置：资源库不存在时 requireDatabase 必抛（同缓存管线 prepareForCapture）
                ReviewSnapshotResourceStore.prepareForCapture(book)
                val capture = ReviewSnapshotCapture.capture(
                    bookSource, book, chapter, buttonSrc, url, html, preloadJs
                )
                ReviewSnapshotStore.put(book, capture.snapshot)
                AppLog.putDebug("$TAG 点击即缓存成功：${chapter.title} / ${buttonSrc.take(60)}")
            }.onFailure { error ->
                // 解析/抓取超时按失败记日志，不当协程取消静默吞掉
                if (error is TimeoutCancellationException) {
                    AppLog.put("$TAG 点击即缓存失败：抓取超时", error)
                    return@onFailure
                }
                if (error is CancellationException) throw error
                AppLog.put("$TAG 点击即缓存失败\n${error.localizedMessage}", error)
            }
        }
    }

    /**
     * 刷新单个按钮的快照（快照过期时点击路径后台触发）。
     * 复用预热管线的 resolve + capture + put，失败只记日志，旧快照保留。
     * 与 [preheat]/[captureFromClick] 共用同一套门控（缓存总闸 + 仅 WiFi）。
     */
    fun refreshButton(book: Book, chapter: BookChapter, buttonSrc: String) {
        if (!AppConfig.syncCacheReview) return
        if (book.isVideo) return
        if (AppConfig.reviewPreheatWifiOnly && !NetworkUtils.isWifi()) return
        refreshJob = scope.launch {
            runCatching {
                val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return@launch
                // put 前置：资源库不存在时 requireDatabase 必抛（同缓存管线 prepareForCapture）
                ReviewSnapshotResourceStore.prepareForCapture(book)
                val processed = preheatContent(book, chapter)?.let {
                    ParagraphRuleProcessor.process(book, chapter, it)
                } ?: return@launch
                val button = ReviewSnapshotManager.extractReviewButtons(processed)
                    .firstOrNull { it.src.trim() == buttonSrc.trim() && it.hasAction }
                    ?: return@launch
                val page = ReviewSnapshotManager.resolveReviewPageUrl(
                    book, bookSource, chapter, button
                )
                val url = page.url?.takeIf { it.isNotBlank() } ?: return@launch
                val capture = ReviewSnapshotCapture.capture(
                    bookSource, book, chapter, buttonSrc, url, page.html, page.preloadJs
                )
                ReviewSnapshotStore.put(book, capture.snapshot)
            }.onFailure { error ->
                // 解析/抓取超时按失败记日志，不当协程取消静默吞掉
                if (error is TimeoutCancellationException) {
                    AppLog.put("$TAG 快照刷新失败：解析或抓取超时", error)
                    return@onFailure
                }
                if (error is CancellationException) throw error
                AppLog.put("$TAG 快照刷新失败\n${error.localizedMessage}", error)
            }
        }
    }

    private suspend fun preheatChapter(book: Book, chapter: BookChapter) {
        val key = ReviewBackoffPolicy.key(book.bookUrl, chapter.url)
        if (isInBackoff(failedUntil[key], System.currentTimeMillis())) return
        val content = preheatContent(book, chapter) ?: return
        // 段落规则处理后提按钮：与用户点击看到的泡完全一致（含 pclick 包装），
        // 无段落规则时 process 原样返回，旧源 click/js 泡同样覆盖。
        val processed = ParagraphRuleProcessor.process(book, chapter, content)
        val buttons = ReviewSnapshotManager.extractReviewButtonsWithStats(processed).buttons
            .filter { it.hasAction }
        if (buttons.isEmpty()) return
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        // 与缓存管线同前置（ReviewSnapshotManager 抓取前的 prepareForCapture）：
        // 资源库不存在时 put 会 requireDatabase 抛异常，预热对此类书将永远失败
        ReviewSnapshotResourceStore.prepareForCapture(book)
        val ttlDays = AppConfig.reviewSnapshotTtlDays
        var anyFailure = false
        // 当前章的预渲染候选：按钮并发抓取，用「按钮序号 → 快照」收集后取文档序第一个
        val candidates = ConcurrentHashMap<Int, ReviewSnapshot>()
        val concurrency = maxOf(
            ReviewDownloadConfig.Number.DATA.value,
            ReviewDownloadConfig.Number.PAGES.value
        ).coerceAtMost(buttons.size.coerceAtLeast(1))
        buttons.asFlow()
            .mapAsyncIndexed(concurrency) { index, button ->
                runCatching {
                    val existing = ReviewSnapshotStore.get(book, chapter, button.src)
                    if (existing != null && !existing.partial &&
                        !isStale(existing.savedAt, System.currentTimeMillis(), ttlDays)
                    ) {
                        // 已有未过期完整快照：跳过（但仍可作为预渲染候选）
                        collectPrerenderCandidate(chapter, index, existing, candidates)
                        return@runCatching
                    }
                    val page = ReviewSnapshotManager.resolveReviewPageUrl(
                        book, bookSource, chapter, button
                    )
                    val url = page.url?.takeIf { it.isNotBlank() } ?: return@runCatching
                    val capture = ReviewSnapshotCapture.capture(
                        bookSource, book, chapter, button.src, url, page.html, page.preloadJs
                    )
                    ReviewSnapshotStore.put(book, capture.snapshot)
                    collectPrerenderCandidate(chapter, index, capture.snapshot, candidates)
                }.onFailure { error ->
                    // 单按钮解析/抓取超时（withTimeout 的 TCE）按失败计入退避，
                    // 绝不能当协程取消重抛——否则一个慢按钮会中止整轮预热，
                    // 本章剩余按钮与后续章节永远预热不到。
                    if (error is TimeoutCancellationException) {
                        anyFailure = true
                        return@onFailure
                    }
                    if (error is CancellationException) throw error
                    anyFailure = true
                }
            }
            .toList()
        if (anyFailure) {
            failedUntil[key] = System.currentTimeMillis() + FAIL_BACKOFF_MS
            trimBackoff()
        } else {
            failedUntil.remove(key)
        }
        prerenderFirstCandidate(book, chapter, candidates)
    }

    /**
     * 收集当前章的预渲染候选。只收「正在读的那一章」——预渲染槽位只有一份，
     * 上一章/下一章的成品页在用户翻过去之前就会过期，没有收益。
     */
    private fun collectPrerenderCandidate(
        chapter: BookChapter,
        buttonIndex: Int,
        snapshot: ReviewSnapshot,
        candidates: MutableMap<Int, ReviewSnapshot>,
    ) {
        if (chapter.index != ReadBook.durChapterIndex) return
        if (snapshot.partial || snapshot.html.isEmpty()) return
        candidates[buttonIndex] = snapshot
    }

    /**
     * 把当前章文档序第一个快照预渲染进评论 WebView 池：用户点该段评时
     * 弹窗直接接管成品页，连 HTML 解析与首帧布局都省掉。
     *
     * 池里没有空闲实例时由 [CommentWebViewSession] 自行挂起，等阅读页的
     * WebView 预热建出实例后接着渲染，因此这里不需要关心两者时序。
     */
    private fun prerenderFirstCandidate(
        book: Book,
        chapter: BookChapter,
        candidates: Map<Int, ReviewSnapshot>,
    ) {
        if (chapter.index != ReadBook.durChapterIndex) return
        val snapshot = candidates.minByOrNull { it.key }?.value ?: return
        runCatching {
            CommentWebViewSession.shared.prerender(
                injectSnapshotImageDeferralStyle(snapshot.html),
                snapshot.url.takeIf { it.isNotBlank() },
                book,
            )
        }.onFailure { error ->
            AppLog.put("$TAG 预渲染评论页失败\n${error.localizedMessage}", error)
        }
    }

    /** 与 [ReviewSnapshotManager] 取评论载体同策略：文本读正文，音频读原始 lyric */
    private suspend fun preheatContent(book: Book, chapter: BookChapter): String? {
        if (book.isVideo) return null
        return if (book.isAudio) {
            chapter.getVariable("lyric").takeIf { it.isNotBlank() }
        } else {
            BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() }
        }
    }

    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
}
