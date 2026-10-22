package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.usesDirectReader
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * What the content loader needs from the reader.
 *
 * Loading a chapter touches almost every piece of reader state, but it only ever *reads*
 * the book and its position, asks for the current layout key, and hands the loaded
 * content back. Everything that decides what the reader sees stays with the reader.
 */
internal interface ReadBookContentHost {

    val book: Book?

    val bookSource: BookSource?

    val chapterSize: Int

    val durChapterIndex: Int

    val callBack: ReadBook.CallBack?

    val contentProcessor: ContentProcessor?

    val chapterLoads: ChapterLoadRegistry

    /** Layout key of the chapter the reader is on right now. */
    fun currentChapterLayoutKey(): String

    fun textChapter(chapterOnDur: Int = 0): TextChapter?

    fun invalidateDirectTextContent(bookUrl: String)

    fun refreshParagraphRuleLayoutKey()

    fun finishChapterLoad(index: Int, generation: Long)

    fun cancelChapterLoading(index: Int, expectedGeneration: Long? = null)

    fun showCurrentChapterLoadError(index: Int, message: String, throwable: Throwable? = null)

    /** Scope the loads run in, so leaving the book cancels them. */
    val coroutineScope: CoroutineScope

    /** The three chapters kept around the current one, in load order. */
    var prevTextChapter: TextChapter?
    var curTextChapter: TextChapter?
    var nextTextChapter: TextChapter?

    val durPageIndex: Int

    val durChapterPos: Int

    val isScroll: Boolean

    val simulatedChapterSize: Int

    fun onPageChanged(pageChanged: Boolean = false, fromReadAloud: Boolean = false)
}

/**
 * Loads chapter content (ReadBook): cache first, then the network, then install it.
 *
 * A chapter is either already cached, fetched from the book source, or reported as
 * failed. Every step has to prove it still belongs to the chapter the reader is on — the
 * book may have been switched, the chapter may have moved, the layout may have changed —
 * so each load carries a generation and the layout key it started with, and a result
 * that no longer matches is dropped instead of installed. See [ChapterLoadGuard] for the
 * rules and [ChapterLoadRegistry] for the values they read.
 */
internal class ReadBookContentLoader(private val host: ReadBookContentHost) :
    CoroutineScope by host.coroutineScope {

    /** Coroutines that fetch chapter bodies from the network. */
    val downloadScope = CoroutineScope(SupervisorJob() + IO)

    /** Serialises paragraph-rule processing: it is CPU heavy and shared across chapters. */
    private val paragraphRuleProcessingSemaphore = Semaphore(1)

    /** One lock per slot, so loading the next chapter never waits for the current one. */
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()

    /**
     * Chapter the reader asked for while read-aloud was navigating, or -1.
     *
     * Read-aloud turns pages on its own; a load it triggered must not be mistaken for a
     * turn the reader made.
     */
    private var readAloudPendingLoadChapterIndex = -1

    /** Records that the next load of this chapter comes from read-aloud navigation. */
    fun markReadAloudPendingLoad(index: Int) {
        readAloudPendingLoadChapterIndex = index
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        forceReload: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        if (!ReadBookChapterIndexPolicy.canLoad(index, host.chapterSize)) {
            if (index == host.durChapterIndex) {
                logContentLoadSkip("chapter_index_out_of_range", index)
                host.showCurrentChapterLoadError(
                    index,
                    "Current chapter index is outside the chapter list"
                )
            }
            return
        }
        var requestGeneration: Long? = null
        if (forceReload) {
            host.book?.let { host.invalidateDirectTextContent(it.bookUrl) }
            host.refreshParagraphRuleLayoutKey()
            host.cancelChapterLoading(index)
            host.chapterLoads.removeChapterLayoutKey(index)
        }
        val layoutKey = host.currentChapterLayoutKey()
        val cached = host.textChapter(index - host.durChapterIndex)
        if (!forceReload && cached?.isCompleted == true &&
            host.chapterLoads.chapterLayoutKey(index) == layoutKey
        ) {
            if (upContent) {
                host.callBack?.upContent(index - host.durChapterIndex, resetPageOffset)
            }
            success?.invoke()
            return
        }
        Coroutine.async(Coroutine.defaultScope) {
            val book = host.book
            if (book == null) {
                logContentLoadSkip("book_null", index)
                host.showCurrentChapterLoadError(index, "书籍数据为空")
                return@async
            }
            host.refreshParagraphRuleLayoutKey()
            val requestedLayoutKey = host.currentChapterLayoutKey()
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
            if (chapter == null) {
                logContentLoadSkip("chapter_null", index, book)
                host.showCurrentChapterLoadError(index, "未找到当前章节，可能目录未加载成功")
                return@async
            }
            val generation = host.chapterLoads.begin(index, requestedLayoutKey)
            if (generation != null) {
                requestGeneration = generation
                BookHelp.getContent(book, chapter)?.let {
                    contentLoadFinish(
                        book,
                        chapter,
                        it,
                        upContent,
                        resetPageOffset,
                        requestGeneration = generation,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    book,
                    chapter,
                    resetPageOffset,
                    requestGeneration = generation,
                    upContent = upContent,
                    success = success
                )
            } else {
                logContentLoadSkip("already_loading", index, book)
            }
        }.onError {
            requestGeneration?.let { generation -> host.finishChapterLoad(index, generation) }
            val message = it.localizedMessage ?: it.toString()
            host.showCurrentChapterLoadError(index, message, it)
            if (index != host.durChapterIndex) {
                AppLog.put("Load content error\n$message", it)
            }
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = withContext(IO) {
        if (!ReadBookChapterIndexPolicy.canLoad(index, host.chapterSize)) {
            if (index == host.durChapterIndex) {
                logContentLoadSkip("chapter_index_out_of_range_await", index)
                host.showCurrentChapterLoadError(
                    index,
                    "Current chapter index is outside the chapter list"
                )
            }
            return@withContext
        }
        val requestedLayoutKey = host.currentChapterLayoutKey()
        val initialGeneration = host.chapterLoads.begin(index, requestedLayoutKey)
        if (initialGeneration != null) {
            try {
                val book = host.book
                if (book == null) {
                    logContentLoadSkip("book_null_await", index)
                    host.showCurrentChapterLoadError(index, "Book is null")
                    return@withContext
                }
                host.refreshParagraphRuleLayoutKey()
                val refreshedLayoutKey = host.currentChapterLayoutKey()
                val generation = if (refreshedLayoutKey == requestedLayoutKey) {
                    initialGeneration
                } else {
                    host.chapterLoads.updateLayoutKey(index, initialGeneration, refreshedLayoutKey)
                        ?: return@withContext
                }
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
                if (chapter == null) {
                    logContentLoadSkip("chapter_null_await", index, book)
                    host.showCurrentChapterLoadError(index, "Current chapter not found")
                    return@withContext
                }
                val content = BookHelp.getContent(book, chapter)
                    ?: downloadAwait(book, chapter)
                val applied = contentLoadFinishAwait(
                    book,
                    chapter,
                    content,
                    upContent,
                    resetPageOffset,
                    requestGeneration = generation
                )
                if (applied) success?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.toString()
                host.showCurrentChapterLoadError(index, message, e)
                if (index != host.durChapterIndex) {
                    AppLog.put("Load content error\n$message", e)
                }
            } finally {
                host.finishChapterLoad(index, initialGeneration)
            }
        } else {
            logContentLoadSkip("already_loading_await", index)
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        expectedBook: Book,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: kotlinx.coroutines.sync.Semaphore? = null,
        requestGeneration: Long? = null,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        val book = expectedBook
        if (host.book?.bookUrl != book.bookUrl || chapter.bookUrl != book.bookUrl) {
            logContentLoadSkip("book_changed_download", chapter.index, book)
            requestGeneration?.let { host.finishChapterLoad(chapter.index, it) }
            return
        }
        val bookSource = host.bookSource?.takeIf { it.bookSourceUrl == book.origin }
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(
                scope,
                chapter,
                semaphore,
                resetPageOffset,
                requestGeneration,
                upContent,
                success
            )
        } else {
            logContentLoadSkip("book_source_null", chapter.index, book)
            val msg = if (book.isLocal) "无内容" else "没有书源"
            contentLoadFinish(
                book,
                chapter,
                "加载正文失败\n$msg",
                upContent = upContent,
                resetPageOffset = resetPageOffset,
                requestGeneration = requestGeneration,
                success = success
            )
        }
    }

    private suspend fun downloadAwait(expectedBook: Book, chapter: BookChapter): String {
        val book = expectedBook
        if (host.book?.bookUrl != book.bookUrl || chapter.bookUrl != book.bookUrl) {
            logContentLoadSkip("book_changed_download_await", chapter.index, book)
            return "Load content canceled\nbook changed"
        }
        val bookSource = host.bookSource?.takeIf { it.bookSourceUrl == book.origin }
        if (bookSource != null) {
            return CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
        } else {
            logContentLoadSkip("book_source_null_await", chapter.index, book)
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    private fun logContentLoadSkip(reason: String, index: Int, activeBook: Book? = host.book) {
        val dbChapterCount = activeBook?.let {
            runCatching { appDb.bookChapterDao.getChapterCount(it.bookUrl) }.getOrNull()
        }
        AppLog.put(
            "read-load skip: reason=$reason, index=$index, " +
                "durChapterIndex=${host.durChapterIndex}, chapterSize=${host.chapterSize}, " +
                "loading=${host.chapterLoads.isLoading(index)}, dbChapterCount=$dbChapterCount, " +
                "bookUrl=${activeBook?.bookUrl}, origin=${activeBook?.origin}, " +
                "bookSource=${host.bookSource?.bookSourceUrl}"
        )
    }

    /** Cancels the loads of chapters the reader can no longer return to. */
    fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        for ((index, job) in host.chapterLoads.allJobs()) {
            if (clearAll || index !in host.durChapterIndex - 1..host.durChapterIndex + 1) {
                if (host.chapterLoads.removeJob(index, job)) {
                    job.cancel()
                }
            }
        }
        for ((index, generation) in host.chapterLoads.allGenerations()) {
            if (clearAll || index !in host.durChapterIndex - 1..host.durChapterIndex + 1) {
                host.cancelChapterLoading(index, generation)
            }
        }
    }

    /** Cancels every fetch: the reader is leaving this book. */
    fun release() {
        downloadScope.coroutineContext.cancelChildren()
    }

    // ---- 安装：把加载到的正文装进槽位（自 ReadBook 搬入）----

    private fun isChapterLoadRequestCurrent(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long
    ): Boolean {
        return ChapterLoadGuard.isRequestCurrent(
            activeBookUrl = host.book?.bookUrl,
            requestBookUrl = bookUrl,
            chapterIndex = chapterIndex,
            currentChapterIndex = host.durChapterIndex,
            recordedGeneration = host.chapterLoads.generationOf(chapterIndex),
            requestGeneration = generation,
            recordedLayoutKey = host.chapterLoads.layoutKeyOf(chapterIndex),
            requestLayoutKey = layoutKey,
            currentLayoutKey = host.currentChapterLayoutKey()
        )
    }


    private fun isChapterLoadTokenCurrent(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long
    ): Boolean {
        return ChapterLoadGuard.isTokenCurrent(
            activeBookUrl = host.book?.bookUrl,
            requestBookUrl = bookUrl,
            chapterIndex = chapterIndex,
            currentChapterIndex = host.durChapterIndex,
            recordedGeneration = host.chapterLoads.generationOf(chapterIndex),
            requestGeneration = generation,
            recordedLayoutKey = host.chapterLoads.layoutKeyOf(chapterIndex),
            requestLayoutKey = layoutKey
        )
    }


    private fun isChapterLoadSlotCurrent(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long,
        expectedOffset: Int,
        expectedChapter: TextChapter? = null
    ): Boolean {
        return ChapterLoadGuard.isSlotCurrent(
            activeBookUrl = host.book?.bookUrl,
            requestBookUrl = bookUrl,
            chapterIndex = chapterIndex,
            currentChapterIndex = host.durChapterIndex,
            recordedGeneration = host.chapterLoads.generationOf(chapterIndex),
            requestGeneration = generation,
            recordedLayoutKey = host.chapterLoads.layoutKeyOf(chapterIndex),
            requestLayoutKey = layoutKey,
            expectedOffset = expectedOffset,
            slotMatches = expectedChapter == null || host.textChapter(expectedOffset) === expectedChapter
        )
    }


    @Synchronized
    private fun installChapterSlot(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long,
        expectedOffset: Int,
        textChapter: TextChapter
    ): Boolean {
        if (!isChapterLoadTokenCurrent(bookUrl, chapterIndex, layoutKey, generation) ||
            chapterIndex - host.durChapterIndex != expectedOffset
        ) return false
        when (expectedOffset) {
            -1 -> {
                host.prevTextChapter?.takeIf { it !== textChapter }?.cancelLayout()
                host.prevTextChapter = textChapter
            }
            0 -> {
                host.curTextChapter?.takeIf { it !== textChapter }?.cancelLayout()
                host.curTextChapter = textChapter
            }
            1 -> {
                host.nextTextChapter?.takeIf { it !== textChapter }?.cancelLayout()
                host.nextTextChapter = textChapter
            }
            else -> return false
        }
        host.chapterLoads.setChapterLayoutKey(chapterIndex, layoutKey)
        return true
    }


    @Synchronized
    fun clearIncompleteChapterSlot(chapterIndex: Int, expectedChapter: TextChapter?) {
        if (expectedChapter == null || expectedChapter.isCompleted) return
        var cleared = false
        if (host.prevTextChapter === expectedChapter) {
            host.prevTextChapter = null
            cleared = true
        }
        if (host.curTextChapter === expectedChapter) {
            host.curTextChapter = null
            cleared = true
        }
        if (host.nextTextChapter === expectedChapter) {
            host.nextTextChapter = null
            cleared = true
        }
        if (cleared) {
            expectedChapter.cancelLayout()
            host.chapterLoads.removeChapterLayoutKey(chapterIndex)
        }
    }


    /**
     * 内容加载完成
     */
    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        requestGeneration: Long? = null,
        success: (() -> Unit)? = null
    ) {
        val generation = requestGeneration ?: return
        val requestedLayoutKey = host.chapterLoads.layoutKeyOf(chapter.index)
        if (requestedLayoutKey == null || chapter.bookUrl != book.bookUrl || canceled ||
            !isChapterLoadRequestCurrent(
                book.bookUrl,
                chapter.index,
                requestedLayoutKey,
                generation
            )
        ) {
            host.finishChapterLoad(chapter.index, generation)
            return
        }
        if (host.chapterLoads.jobOf(chapter.index)?.isActive == true) {
            return
        }
        var installedTextChapter: TextChapter? = null
        lateinit var job: Coroutine<Boolean>
        job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            if (!isChapterLoadRequestCurrent(
                    book.bookUrl,
                    chapter.index,
                    requestedLayoutKey,
                    generation
                )
            ) {
                return@async false
            }
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val fallbackDisplayTitle = if (book.usesDirectReader) chapter.title.trim() else chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule() && !book.usesDirectReader,
                replaceBook = book.toReplaceBook()
            )
            val contents = paragraphRuleProcessingSemaphore.withPermit {
                ParagraphRuleProcessor.process(
                    book,
                    chapter,
                    contentProcessor.getContent(book, chapter, content, includeTitle = false, checkActive = { ensureActive() })
                )
            }
            val displayTitle = contents.displayTitle ?: fallbackDisplayTitle
            ensureActive()
            if (!isChapterLoadRequestCurrent(
                    book.bookUrl,
                    chapter.index,
                    requestedLayoutKey,
                    generation
                )
            ) {
                return@async false
            }
            val textChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, host.simulatedChapterSize
            )
            installedTextChapter = textChapter
            when (val offset = chapter.index - host.durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    }
                    if (!installed) return@withLock false
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    ) return@withLock false
                    host.callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                generation,
                                offset,
                                textChapter
                            )
                        ) {
                            textChapter.cancelLayout()
                            return@withLock false
                        }
                        val index = page.index
                        if (!available && page.containPos(host.durChapterPos)) {
                            if (upContent) {
                                host.callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && host.isScroll && available &&
                            index > host.durPageIndex && index <= host.durPageIndex + 2
                        ) {
                            host.callBack?.upContent(offset, false)
                        }
                        host.callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    ) return@withLock false
                    if (upContent) host.callBack?.upContent(offset, !available && resetPageOffset)
                    val fromReadAloud = readAloudPendingLoadChapterIndex == chapter.index
                    if (fromReadAloud) {
                        readAloudPendingLoadChapterIndex = -1
                    }
                    host.onPageChanged(fromReadAloud = fromReadAloud)
                    host.callBack?.contentLoadFinish()
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        generation,
                        offset,
                        textChapter
                    )
                }

                -1 -> prevChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in textChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                generation,
                                offset,
                                textChapter
                            )
                        ) {
                            textChapter.cancelLayout()
                            return@withLock false
                        }
                    }
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    ) return@withLock false
                    if (upContent) host.callBack?.upContent(offset, resetPageOffset)
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        generation,
                        offset,
                        textChapter
                    )
                }

                1 -> nextChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in textChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                generation,
                                offset,
                                textChapter
                            )
                        ) {
                            textChapter.cancelLayout()
                            return@withLock false
                        }
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) host.callBack?.upContent(offset, resetPageOffset)
                    }
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        generation,
                        offset,
                        textChapter
                    )
                }

                else -> false
            }
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            if (chapter.index == host.durChapterIndex) {
                host.showCurrentChapterLoadError(
                    chapter.index,
                    "Layout failed: ${it.localizedMessage ?: it::class.java.simpleName}",
                    it
                )
                return@onError
            }
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess { applied ->
            if (applied) success?.invoke()
        }
        host.chapterLoads.setJob(chapter.index, job)
        job.invokeOnCompletion {
            if (host.chapterLoads.removeJob(chapter.index, job)) {
                clearIncompleteChapterSlot(chapter.index, installedTextChapter)
                host.finishChapterLoad(chapter.index, generation)
            }
        }
        job.start()
    }


    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        requestGeneration: Long
    ): Boolean {
        if (chapter.bookUrl != book.bookUrl) return false
        val requestedLayoutKey = host.chapterLoads.layoutKeyOf(chapter.index) ?: return false
        if (!isChapterLoadRequestCurrent(
                book.bookUrl,
                chapter.index,
                requestedLayoutKey,
                requestGeneration
            )
        ) return false
        var textChapter: TextChapter? = null
        var applied = false
        try {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val fallbackDisplayTitle = if (book.usesDirectReader) chapter.title.trim() else chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule() && !book.usesDirectReader,
                replaceBook = book.toReplaceBook()
            )
            val contents = paragraphRuleProcessingSemaphore.withPermit {
                ParagraphRuleProcessor.process(
                    book,
                    chapter,
                    contentProcessor.getContent(book, chapter, content, includeTitle = false, checkActive = { ensureActive() })
                )
            }
            val displayTitle = contents.displayTitle ?: fallbackDisplayTitle
            ensureActive()
            if (!isChapterLoadRequestCurrent(
                    book.bookUrl,
                    chapter.index,
                    requestedLayoutKey,
                    requestGeneration
                )
            ) return false
            val createdChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, host.simulatedChapterSize
            )
            textChapter = createdChapter
            applied = when (val offset = chapter.index - host.durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    }
                    if (!installed) return@withLock false
                    host.callBack?.upMenuView()
                    var available = false
                    for (page in createdChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                requestGeneration,
                                offset,
                                createdChapter
                            )
                        ) {
                            createdChapter.cancelLayout()
                            return@withLock false
                        }
                        val index = page.index
                        if (!available && page.containPos(host.durChapterPos)) {
                            if (upContent) {
                                host.callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && host.isScroll && available &&
                            index > host.durPageIndex && index <= host.durPageIndex + 2
                        ) {
                            host.callBack?.upContent(offset, false)
                        }
                        host.callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    ) return@withLock false
                    if (upContent) host.callBack?.upContent(offset, !available && resetPageOffset)
                    val fromReadAloud = readAloudPendingLoadChapterIndex == chapter.index
                    if (fromReadAloud) {
                        readAloudPendingLoadChapterIndex = -1
                    }
                    host.onPageChanged(fromReadAloud = fromReadAloud)
                    host.callBack?.contentLoadFinish()
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        requestGeneration,
                        offset,
                        createdChapter
                    )
                }

                -1 -> prevChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in createdChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                requestGeneration,
                                offset,
                                createdChapter
                            )
                        ) {
                            createdChapter.cancelLayout()
                            return@withLock false
                        }
                    }
                    if (upContent) host.callBack?.upContent(offset, resetPageOffset)
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        requestGeneration,
                        offset,
                        createdChapter
                    )
                }

                1 -> nextChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in createdChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                requestGeneration,
                                offset,
                                createdChapter
                            )
                        ) {
                            createdChapter.cancelLayout()
                            return@withLock false
                        }
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) host.callBack?.upContent(offset, resetPageOffset)
                    }
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        requestGeneration,
                        offset,
                        createdChapter
                    )
                }

                else -> false
            }
            return applied
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("ChapterProvider ERROR", e)
            if (chapter.index == host.durChapterIndex) {
                host.showCurrentChapterLoadError(
                    chapter.index,
                    "Layout failed: ${e.localizedMessage ?: e::class.java.simpleName}",
                    e
                )
            } else {
                appCtx.toastOnUi("ChapterProvider ERROR:\n${e.stackTraceStr}")
            }
            return false
        } finally {
            if (!applied) clearIncompleteChapterSlot(chapter.index, textChapter)
        }
    }

}
