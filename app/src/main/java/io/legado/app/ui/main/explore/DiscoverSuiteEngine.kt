package io.legado.app.ui.main.explore

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.webBook.WebBook
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.constant.AppPattern
import io.legado.app.help.CoverThumbnailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import kotlin.random.Random
import java.util.concurrent.TimeUnit
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions

/**
 * 发现套件引擎（台账 #29-B13 第二步：加载/预取/分页下沉）。
 *
 * 从 ExploreFragment 搬来，供旧壳与将来的 V5 套件发现页共用：
 * - 控件取书（普通/随机/横排/瀑布/排行榜）
 * - 随机牌堆填充与后台补牌
 * - 横排/排行榜分页状态
 * - 封面预取（只用 applicationContext，不持有 Fragment/Activity）
 *
 * 设计约束：
 * 1. **按屏实例化**（信号量是实例字段）—— 做成单例会让旧壳与新页互相抢并发许可。
 * 2. **接收外部 [scope]**（旧壳传 viewLifecycleOwner.lifecycleScope，V5 传
 *    rememberCoroutineScope/viewModelScope），并暴露 [cancelAll]，保证离开页面能取消。
 * 3. [isActive] 由调用方注入（对应旧壳的 isCurrentSuiteWidget 语义：
 *    isAdded && usingSuiteDiscovery && 签名未变），避免切套件后旧请求写回新套件。
 */
class DiscoverSuiteEngine(
    private val scope: CoroutineScope,
    private val isActive: () -> Boolean,
) {

    private val targetLoadSemaphore = Semaphore(SUITE_TARGET_LOAD_PARALLELISM)
    private val coverPreloadSemaphore = Semaphore(SUITE_COVER_PRELOAD_PARALLELISM)

    private val randomDecks = hashMapOf<String, SuiteRandomDeck>()
    private val preparedBatches = hashMapOf<String, SuitePreparedBatch>()
    private val randomPrepareJobs = hashMapOf<String, kotlinx.coroutines.Job>()
    private val horizontalPagingStates = hashMapOf<String, SuiteHorizontalPagingState>()
    private val rankedPagingStates = hashMapOf<String, SuiteRankedPagingState>()

    // ---------------------------------------------------------------- 取书

    /** 普通/随机/瀑布控件取书（横排走 [loadHorizontalWidgetBooks]）。 */
    suspend fun loadWidgetBooks(widget: DiscoverySuiteWidget): List<SearchBook> {
        if (widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value) {
            return loadHorizontalWidgetBooks(widget)
        }
        val bookCount = when (widget.type) {
            DiscoverySuiteWidgetType.WaterfallBooks.value -> WATERFALL_SUITE_BOOK_COUNT
            else -> RANDOM_SUITE_BOOK_COUNT
        }
        val deck = deckFor(widget)
        val books = deck.mutex.withLock {
            if (deck.queue.size < bookCount) {
                fillRandomDeckLocked(
                    widget = widget,
                    deck = deck,
                    minSize = maxOf(bookCount, RANDOM_SUITE_PREFETCH_COUNT)
                )
            }
            val out = ArrayList<SearchBook>(bookCount)
            while (out.size < bookCount && deck.queue.isNotEmpty()) {
                out.add(deck.queue.removeFirst())
            }
            if (out.size < bookCount) {
                fillRandomDeckLocked(widget, deck, bookCount)
                while (out.size < bookCount && deck.queue.isNotEmpty()) {
                    out.add(deck.queue.removeFirst())
                }
            }
            out
        }
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        return books
    }

    suspend fun loadHorizontalWidgetBooks(widget: DiscoverySuiteWidget): List<SearchBook> {
        val state = horizontalPagingFor(widget)
        state.nextPage = 2
        state.exhausted = false
        val target = widget.validRandomTargets().firstOrNull() ?: return emptyList()
        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: return emptyList()
        val books = loadTargetPage(source, target.tagUrl, 1)
            .distinctBy { it.suiteDeckKey() }
            .take(HORIZONTAL_SUITE_PAGE_BOOK_LIMIT)
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        state.exhausted = books.isEmpty()
        return books
    }

    suspend fun loadRankedWidgetBooks(
        widget: DiscoverySuiteWidget
    ): Map<String, List<SearchBook>> {
        val result = linkedMapOf<String, List<SearchBook>>()
        val entries = coroutineScope {
            widget.validRandomTargets()
                .take(RANKED_SUITE_TARGET_LIMIT)
                .map { target ->
                    async {
                        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl)
                        val books = if (source == null) {
                            emptyList()
                        } else {
                            loadTargetPage(source, target.tagUrl, 1)
                                .distinctBy { it.suiteDeckKey() }
                        }
                        target to books
                    }
                }
                .awaitAll()
        }
        entries.forEach { (target, books) ->
            val state = rankedPagingFor(widget, target)
            state.nextPage = 2
            state.exhausted = books.isEmpty()
            result[target.deckKey()] = books
        }
        entries
            .flatMap { it.second }
            .takeIf { it.isNotEmpty() }
            ?.let { merged -> appDb.searchBookDao.insert(*merged.toTypedArray()) }
        return result
    }

    suspend fun loadHorizontalPage(widget: DiscoverySuiteWidget, page: Int): List<SearchBook> {
        val target = widget.validRandomTargets().firstOrNull() ?: return emptyList()
        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: return emptyList()
        val books = loadTargetPage(source, target.tagUrl, page)
            .distinctBy { it.suiteDeckKey() }
            .take(HORIZONTAL_SUITE_PAGE_BOOK_LIMIT)
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        return books
    }

    suspend fun loadRankedPage(
        target: DiscoverySuiteWidgetTarget,
        page: Int
    ): List<SearchBook> {
        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: return emptyList()
        val books = loadTargetPage(source, target.tagUrl, page)
            .distinctBy { it.suiteDeckKey() }
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        return books
    }

    /** 唯一网络出口：分类页拉取，受并发信号量限制。 */
    suspend fun loadTargetPage(
        source: BookSource,
        tagUrl: String,
        page: Int
    ): List<SearchBook> {
        return targetLoadSemaphore.withPermit {
            try {
                WebBook.exploreBookAwait(
                    source,
                    tagUrl,
                    page,
                    io.legado.app.help.webView.WebViewPool.Scope.DISCOVERY
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现加载分类失败", e)
                emptyList()
            }
        }
    }
    // ---------------------------------------------------------------- 随机牌堆 / 预取

    fun deckFor(widget: DiscoverySuiteWidget): SuiteRandomDeck {
        val signature = widget.deckSignature()
        return synchronized(randomDecks) {
            val current = randomDecks[widget.id]
            if (current != null && current.signature == signature) {
                return@synchronized current
            }
            val targets = widget.validRandomTargets()
            SuiteRandomDeck(
                signature = signature,
                targetIndex = if (targets.isEmpty()) {
                    0
                } else {
                    Random(System.nanoTime()).nextInt(targets.size)
                }
            ).also { randomDecks[widget.id] = it }
        }
    }

    /** 后台补牌 + 封面预取（仅随机型控件；横排/排行/瀑布不预取）。 */
    fun prefetchDeck(widget: DiscoverySuiteWidget) {
        if (!isActive() ||
            widget.isSuiteButtonOnlyWidget() ||
            widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value ||
            widget.type == DiscoverySuiteWidgetType.RankedList.value ||
            widget.type == DiscoverySuiteWidgetType.WaterfallBooks.value
        ) return
        val deck = deckFor(widget)
        if (deck.prefetching || deck.queue.size >= RANDOM_SUITE_BOOK_COUNT) return
        scope.launch(Dispatchers.IO) {
            deck.prefetching = true
            try {
                val queuedBooks = deck.mutex.withLock {
                    fillRandomDeckLocked(widget, deck, RANDOM_SUITE_PREFETCH_COUNT)
                    deck.queue.take(RANDOM_SUITE_COVER_PREFETCH_COUNT)
                }
                withContext(Dispatchers.Main) {
                    prefetchCovers(queuedBooks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现控件预加载失败", e)
            } finally {
                deck.prefetching = false
            }
        }
    }

    /** 预生成下一批随机书（刷新时可直接使用）。 */
    fun prepareNextRandomBatch(widget: DiscoverySuiteWidget) {
        if (!isActive() || widget.isSuiteButtonOnlyWidget()) return
        if (widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value ||
            widget.type == DiscoverySuiteWidgetType.RankedList.value ||
            widget.type == DiscoverySuiteWidgetType.WaterfallBooks.value
        ) return
        val signature = widget.deckSignature()
        if (preparedBatches[widget.id]?.signature == signature) return
        val runningJob = randomPrepareJobs[widget.id]
        if (runningJob?.isActive == true) return
        randomPrepareJobs[widget.id] = scope.launch {
            try {
                val books = try {
                    withContext(Dispatchers.IO) {
                        loadWidgetBooks(widget).also {
                            preloadVisibleCovers(appCtx, widget, it)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("套件发现控件预加载失败", e)
                    emptyList()
                }
                if (!isActive()) return@launch
                if (books.isNotEmpty() && widget.deckSignature() == signature) {
                    preparedBatches[widget.id] = SuitePreparedBatch(signature, books)
                    prefetchCovers(books)
                }
                prefetchDeck(widget)
            } finally {
                randomPrepareJobs.remove(widget.id)
            }
        }
    }

    /** 取走预生成的批次（签名匹配才有效）。 */
    fun takePreparedBatch(widget: DiscoverySuiteWidget): List<SearchBook>? {
        val signature = widget.deckSignature()
        return preparedBatches.remove(widget.id)?.takeIf { it.signature == signature }?.books
    }

    private suspend fun fillRandomDeckLocked(
        widget: DiscoverySuiteWidget,
        deck: SuiteRandomDeck,
        minSize: Int
    ) {
        val targets = widget.validRandomTargets()
        if (targets.isEmpty()) return
        var attempts = 0
        var resetSeen = false
        while (deck.queue.size < minSize && attempts < RANDOM_SUITE_MAX_PREFETCH_ATTEMPTS) {
            val requests = mutableListOf<SuiteDeckPageRequest>()
            while (
                requests.size < SUITE_RANDOM_BATCH_PARALLELISM &&
                attempts < RANDOM_SUITE_MAX_PREFETCH_ATTEMPTS
            ) {
                attempts++
                val target = targets[deck.targetIndex % targets.size]
                deck.targetIndex += 1
                val targetKey = target.deckKey()
                val page = deck.nextPageByTarget[targetKey] ?: 1
                deck.nextPageByTarget[targetKey] =
                    if (page >= RANDOM_SUITE_MAX_PAGE) 1 else page + 1
                val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: continue
                val seed = "${widget.id}|$targetKey|$page|${deck.seenKeys.size}".hashCode()
                requests += SuiteDeckPageRequest(
                    tagUrl = target.tagUrl,
                    page = page,
                    source = source,
                    seed = seed
                )
            }
            if (requests.isEmpty()) continue
            val loadedPages = coroutineScope {
                requests.map { request ->
                    async {
                        request to loadTargetPage(request.source, request.tagUrl, request.page)
                            .ifEmpty {
                                if (request.page == 1) {
                                    emptyList()
                                } else {
                                    loadTargetPage(request.source, request.tagUrl, 1)
                                }
                            }
                    }
                }.awaitAll()
            }
            var added = 0
            loadedPages.forEach { (request, pageBooks) ->
                pageBooks.shuffled(Random(request.seed)).forEach { book ->
                    if (deck.queue.size >= minSize) return@forEach
                    val key = book.suiteDeckKey()
                    if (deck.seenKeys.add(key)) {
                        deck.queue.addLast(book)
                        added += 1
                    }
                }
            }
            if (added == 0 && !resetSeen && deck.queue.isEmpty() && attempts >= targets.size) {
                deck.seenKeys.clear()
                resetSeen = true
            }
        }
    }
    // ---------------------------------------------------------------- 分页状态

    fun horizontalPagingFor(widget: DiscoverySuiteWidget): SuiteHorizontalPagingState {
        val signature = widget.horizontalPagingSignature()
        return synchronized(horizontalPagingStates) {
            horizontalPagingStates[widget.id]
                ?.takeIf { it.signature == signature }
                ?: SuiteHorizontalPagingState(signature = signature).also {
                    horizontalPagingStates[widget.id] = it
                }
        }
    }

    fun rankedPagingFor(
        widget: DiscoverySuiteWidget,
        target: DiscoverySuiteWidgetTarget
    ): SuiteRankedPagingState {
        val key = widget.rankedPagingKey(target)
        val signature = widget.rankedPagingSignature(target)
        return synchronized(rankedPagingStates) {
            rankedPagingStates[key]
                ?.takeIf { it.signature == signature }
                ?: SuiteRankedPagingState(signature = signature).also {
                    rankedPagingStates[key] = it
                }
        }
    }

    // ---------------------------------------------------------------- 封面

    /** 异步低优先级预取（Glide preload），只用 applicationContext。 */
    fun prefetchCovers(books: List<SearchBook>) {
        if (books.isEmpty()) return
        val context = appCtx
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .disallowHardwareConfig()
            .set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
        books.asSequence()
            .distinctBy { "${it.origin}|${it.coverUrl.orEmpty()}|${it.name}|${it.author}" }
            .take(RANDOM_SUITE_COVER_PREFETCH_COUNT)
            .forEach { book ->
                val display = CoverDisplayResolver.resolve(book)
                if (AppConfig.useDefaultCover && !display.forcePath) return@forEach
                val path = display.path?.takeIf { it.isNotBlank() } ?: return@forEach
                val requestOptions = options.clone()
                display.sourceOrigin?.let { origin ->
                    requestOptions.set(OkHttpModelLoader.sourceOriginOption, origin)
                }
                ImageLoader.load(context, path)
                    .apply(requestOptions)
                    .priority(Priority.LOW)
                    .override(SUITE_COVER_THUMB_WIDTH, SUITE_COVER_THUMB_HEIGHT)
                    .centerCrop()
                    .preload(SUITE_COVER_THUMB_WIDTH, SUITE_COVER_THUMB_HEIGHT)
            }
    }

    /** 同步预取可见封面（带超时，不阻塞控件刷新）。 */
    suspend fun preloadVisibleCovers(
        context: android.content.Context,
        widget: DiscoverySuiteWidget,
        books: List<SearchBook>
    ) {
        if (books.isEmpty()) return
        val visibleCount = when (widget.type) {
            DiscoverySuiteWidgetType.HorizontalBooks.value -> HORIZONTAL_SUITE_VISIBLE_COVER_COUNT
            DiscoverySuiteWidgetType.RankedList.value -> RANKED_SUITE_VISIBLE_COVER_COUNT
            DiscoverySuiteWidgetType.WaterfallBooks.value -> WATERFALL_SUITE_VISIBLE_COVER_COUNT
            else -> RANDOM_SUITE_BOOK_COUNT
        }
        val visibleBooks = books.take(visibleCount)
        withTimeoutOrNull(SUITE_VISIBLE_COVER_PRELOAD_TIMEOUT_MS) {
            coroutineScope {
                visibleBooks.map { book ->
                    async(Dispatchers.IO) {
                        coverPreloadSemaphore.withPermit {
                            preloadCoverBlocking(context, book)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun preloadCoverBlocking(context: android.content.Context, book: SearchBook) {
        val display = CoverDisplayResolver.resolve(book)
        if (AppConfig.useDefaultCover && !display.forcePath) return
        val path = display.path?.takeIf { it.isNotBlank() } ?: return
        val cleanName = display.name?.replace(AppPattern.bdRegex, "")?.trim()
        val cleanAuthor = display.author?.replace(AppPattern.bdRegex, "")?.trim()
        val thumbKey = "${display.sourceOrigin}|$path|$cleanName|$cleanAuthor"
        val thumbFile = CoverThumbnailCache.existing(context, thumbKey)
        var options = RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .disallowHardwareConfig()
            .set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
        display.sourceOrigin?.let { origin ->
            options = options.set(OkHttpModelLoader.sourceOriginOption, origin)
        }
        val request = if (thumbFile != null) {
            ImageLoader.load(context, thumbFile)
        } else {
            ImageLoader.load(context, path)
        }
        val target = request
            .apply(options)
            .priority(Priority.HIGH)
            .override(SUITE_COVER_THUMB_WIDTH, SUITE_COVER_THUMB_HEIGHT)
            .centerCrop()
            .submit(SUITE_COVER_THUMB_WIDTH, SUITE_COVER_THUMB_HEIGHT)
        try {
            val drawable = target.get(SUITE_SINGLE_COVER_PRELOAD_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            if (thumbFile == null) {
                CoverThumbnailCache.saveBlocking(context, thumbKey, drawable)
            }
        } catch (_: Throwable) {
            // 封面失败不应阻塞控件刷新
        } finally {
            runCatching { Glide.with(context).clear(target) }
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 退出套件模式/离开页面：取消在途预取与补牌任务。 */
    fun cancelAll() {
        randomPrepareJobs.values.forEach { it.cancel() }
        randomPrepareJobs.clear()
        randomDecks.values.forEach { it.prefetching = false }
    }

    /** 清空运行时状态（牌堆/批次/分页），配置仍由 DiscoverySuiteStore 持有。 */
    fun clearRuntimeState() {
        randomDecks.clear()
        preparedBatches.clear()
        horizontalPagingStates.clear()
        rankedPagingStates.clear()
    }

    /**
     * 切换/刷新套件时的选择性淘汰：只保留当前套件还在用的控件，
     * 其余牌堆/批次/预取任务/分页态一并清掉（预取任务要取消）。
     * 排行榜分页态的键形如 `widgetId\ntargetKey`，按前缀判断归属。
     */
    fun retainWidgets(widgetIds: Set<String>) {
        synchronized(randomDecks) {
            randomDecks.keys.filterNot { it in widgetIds }
                .forEach { randomDecks.remove(it) }
        }
        synchronized(preparedBatches) {
            preparedBatches.keys.filterNot { it in widgetIds }
                .forEach { preparedBatches.remove(it) }
        }
        synchronized(randomPrepareJobs) {
            randomPrepareJobs.keys.filterNot { it in widgetIds }
                .forEach { randomPrepareJobs.remove(it)?.cancel() }
        }
        synchronized(horizontalPagingStates) {
            horizontalPagingStates.keys.filterNot { it in widgetIds }
                .forEach { horizontalPagingStates.remove(it) }
        }
        synchronized(rankedPagingStates) {
            rankedPagingStates.keys.filterNot { it.substringBefore('\n') in widgetIds }
                .forEach { rankedPagingStates.remove(it) }
        }
    }
}

