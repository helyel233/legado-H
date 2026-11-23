package io.legado.app.ui.book.read

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.book.addType
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookSessionLoadCoordinator
import io.legado.app.help.book.ChangeSourceTextAnchor
import io.legado.app.help.book.ChangeSourceTextAnchorHelper
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.ReaderTextPositionStore
import io.legado.app.help.book.SourceSwitchQualityPolicy
import io.legado.app.help.book.TocSessionResult
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.usesDirectReader
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadBookChapterIndexPolicy
import io.legado.app.model.inheritNotShelfStateFrom
import io.legado.app.model.resolveStoredBookshelfState
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.TocIntegrity
import io.legado.app.model.webBook.TocLoadState
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.utils.DocumentUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.postEvent
import io.legado.app.utils.runOnUI
import io.legado.app.utils.toStringArray
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import splitties.init.appCtx

/**
 * 阅读界面数据处理
 */
class ReadBookViewModel(application: Application) : BaseViewModel(application) {
    data class PreviousSourceSnapshot(
        val previousBook: Book,
        val previousToc: List<BookChapter>,
        val switchedToBookUrl: String
    )

    data class SourceSwitchRecoveryState(
        val bookUrl: String,
        val chapterIndex: Int,
        val issue: SourceSwitchQualityPolicy.ChapterIssue,
        val effectiveCharCount: Int,
        val previousOriginName: String
    )

    val permissionDenialLiveData = MutableLiveData<Int>()
    val sourceSwitchRecoveryLiveData = MutableLiveData<SourceSwitchRecoveryState?>(null)
    var isInitFinish = false
    var searchContentQuery = ""
    var searchResultList: List<SearchResult>? = null
    var searchResultIndex: Int = 0
    private var changeSourceCoroutine: Coroutine<*>? = null
    @Volatile
    private var lastSourceSnapshot: PreviousSourceSnapshot? = null
    @Volatile
    private var pendingSourceValidation: Pair<String, Int>? = null

    private val chapterQualityListener: (ReadBook.ChapterQualityReport) -> Unit = { report ->
        val currentBookUrl = ReadBook.book?.bookUrl
        if (report.bookUrl != null && report.bookUrl == currentBookUrl) {
            val pending = pendingSourceValidation
            if (pending != null && pending.first == report.bookUrl && pending.second == report.chapterIndex) {
                pendingSourceValidation = null
                val snapshot = lastSourceSnapshot
                if (report.issue != SourceSwitchQualityPolicy.ChapterIssue.NONE && snapshot != null) {
                    sourceSwitchRecoveryLiveData.postValue(
                        SourceSwitchRecoveryState(
                            bookUrl = report.bookUrl,
                            chapterIndex = report.chapterIndex,
                            issue = report.issue,
                            effectiveCharCount = report.effectiveCharCount,
                            previousOriginName = snapshot.previousBook.originName
                        )
                    )
                } else if (report.issue == SourceSwitchQualityPolicy.ChapterIssue.NONE) {
                    sourceSwitchRecoveryLiveData.postValue(null)
                }
            } else if (report.issue == SourceSwitchQualityPolicy.ChapterIssue.NONE &&
                sourceSwitchRecoveryLiveData.value != null
            ) {
                sourceSwitchRecoveryLiveData.postValue(null)
            }
        }
    }

    init {
        AppConfig.detectClickArea()
        ReadBook.addChapterQualityListener(chapterQualityListener)
    }

    fun canRevertSourceChange(): Boolean = lastSourceSnapshot != null

    fun dismissSourceSwitchRecovery() {
        sourceSwitchRecoveryLiveData.postValue(null)
    }

    fun initReadBookConfig(intent: Intent) {
        // DB 查询与配置绑定挪到 IO：原在 onPostCreate 主线程同步查库，
        // 书多/磁盘忙时拖慢进书速度；效果仅是异步发 UP_CONFIG 事件，晚到无时序风险
        execute {
            val bookUrl = intent.getStringExtra("bookUrl")
            val book = when {
                bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
                else -> appDb.bookDao.getBook(bookUrl)
                    ?: BookSessionLoadCoordinator.default.peekStagedBook(bookUrl)
            } ?: return@execute
            ReadBook.upReadBookConfig(book)
        }
    }

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            ReadBook.chapterChanged = intent.getBooleanExtra("chapterChanged", false)
            val bookUrl = intent.getStringExtra("bookUrl")
            val storedByUrl = when {
                bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
                else -> appDb.bookDao.getBook(bookUrl)
            }
            val stagedBook = if (storedByUrl == null && !bookUrl.isNullOrEmpty()) {
                BookSessionLoadCoordinator.default.peekStagedBook(bookUrl)
            } else {
                null
            }
            // A source can derive an unstable bookUrl, so the row written when this book was
            // opened is not always found by the url now in hand. Recover it by identity
            // instead of falling back to the in-memory copy, whose type never carried the
            // temporary flag and would erase it on the next whole-row write.
            val stored = storedByUrl ?: stagedBook ?: ReadBook.book?.let { candidate ->
                candidate.takeIf { it.name.isNotBlank() }
                    ?.let { appDb.bookDao.getBook(it.name, it.author) }
            }
            val book = stored ?: ReadBook.book
            if (book != null && lastSourceSnapshot?.switchedToBookUrl != book.bookUrl) {
                lastSourceSnapshot = null
                pendingSourceValidation = null
                sourceSwitchRecoveryLiveData.postValue(null)
            }
            // Trust whichever side says "not on the shelf": the detail page knows what the
            // reader chose, the stored row knows what was persisted, and treating a sampled
            // book as shelved is the failure that leaves books behind.
            val storedSaysShelved = resolveStoredBookshelfState(stored)
            val intentSaysShelved = intent.getBooleanExtra("inBookshelf", storedSaysShelved)
            ReadBook.inBookshelf = storedSaysShelved && intentSaysShelved
            when {
                book != null -> {
                    ReadBook.markRecentRead(book)
                    initBook(book)
                }
                else -> {
                    ReadBook.upMsg(context.getString(R.string.no_book))
                    AppLog.put("未找到书籍\nbookUrl:$bookUrl")
                }
            }
            val index = intent.getIntExtra("index", -1)
            val chapterPos = intent.getIntExtra("chapterPos", -1)
            if (index >= 0 && chapterPos >= 0) { //从书签打开的正文，有进度传递
                ReadBook.saveCurrentBookProgress() //启用恢复进度提示
                openChapter(index, chapterPos)
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            val msg = "初始化数据失败\n${it.localizedMessage}"
            ReadBook.upMsg(msg)
            AppLog.put(msg, it)
        }.onFinally {
            ReadBook.saveRead()
        }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = ReadBook.book?.bookUrl == book.bookUrl
        val startup = ReadBookStartupPolicy.decide(
            isEpub = book.isEpub,
            useEpubCore = AppConfig.useEpubCore,
            directText = !book.isEpub && book.usesDirectReader
        )
        AppLog.put(
            "read-init: start, sameBook=$isSameBook, book=${book.name}, " +
                "bookUrl=${book.bookUrl}, origin=${book.origin}, tocUrl=${book.tocUrl}, " +
                "durChapterIndex=${book.durChapterIndex}, totalChapterNum=${book.totalChapterNum}, " +
                "local=${book.isLocal}"
        )
        if (isSameBook) {
            ReadBook.upData(book)
        } else {
            ReadBook.resetData(book, notifyContent = startup.notifyContentDuringReset)
        }
        AppLog.put(
            "read-init: after data, chapterSize=${ReadBook.chapterSize}, " +
                "simulatedChapterSize=${ReadBook.simulatedChapterSize}, " +
                "bookSource=${ReadBook.bookSource?.bookSourceUrl}"
        )
        isInitFinish = true
        val stagedChapters = BookSessionLoadCoordinator.default.consumeStagedToc(book.bookUrl)
        if (!stagedChapters.isNullOrEmpty() && !book.isLocal) {
            ReadBook.chapterSize = stagedChapters.size
            ReadBook.simulatedChapterSize = if (book.readSimulating()) {
                book.simulatedTotalChapterNum()
            } else {
                stagedChapters.size
            }
            ReadBook.durChapterIndex = ReadBookChapterIndexPolicy.clamp(
                ReadBook.durChapterIndex,
                ReadBook.simulatedChapterSize
            )
            AppLog.put(
                "read-init: fastMemoryToc, chapterSize=${ReadBook.chapterSize}, " +
                    "bookUrl=${book.bookUrl}"
            )
        }
        notifyNoSourceIfNeeded(book)
        if (stagedChapters.isNullOrEmpty()) {
            if (!book.isLocal && book.tocUrl.isEmpty() && !loadBookInfo(book)) {
                AppLog.put("read-init: stop, reason=book_info_failed, bookUrl=${book.bookUrl}")
                return
            }
            if (book.isLocal && !checkLocalBookFileExist(book)) {
                AppLog.put("read-init: stop, reason=local_file_missing, bookUrl=${book.bookUrl}")
                return
            }
            if ((ReadBook.chapterSize == 0 || book.isLocalModified()) &&
                !loadChapterListAwait(book, loadContentAfterUpdate = false)
            ) {
                AppLog.put(
                    "read-init: stop, reason=toc_failed, chapterSize=${ReadBook.chapterSize}, " +
                        "bookUrl=${book.bookUrl}, origin=${book.origin}"
                )
                return
            }
        }
        ReadBook.upMsg(null)
        if (startup.contentLoader == ReadBookStartupPolicy.ContentLoader.DIRECT_EPUB) {
            AppLog.put(
                "read-init: directContent, mode=${if (isSameBook) "same" else "reset"}, " +
                    "index=${ReadBook.durChapterIndex}, chapterSize=${ReadBook.chapterSize}"
            )
            ReadBook.callBack?.upContent(resetPageOffset = false) {
                ReadBook.bookSource?.let {
                    SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, ReadBook.curTextChapter?.chapter)
                }
            } ?: AppLog.put(
                "read-init: stop, reason=reader_callback_missing, mode=direct, " +
                    "bookUrl=${book.bookUrl}"
            )
        } else if (!isSameBook) {
            AppLog.put("read-init: loadContent, mode=reset, index=${ReadBook.durChapterIndex}")
            ReadBook.loadContent(resetPageOffset = true) {
                ReadBook.bookSource?.let {
                    SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, ReadBook.curTextChapter?.chapter)
                }
            }
        } else {
            AppLog.put("read-init: loadContent, mode=same, index=${ReadBook.durChapterIndex}")
            ReadBook.loadOrUpContent {
                ReadBook.bookSource?.let {
                    SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, ReadBook.curTextChapter?.chapter)
                }
            }
        }
        if (ReadBook.chapterChanged) {
            // 有章节跳转不同步阅读进度
            ReadBook.chapterChanged = false
        } else if (!(isSameBook && BaseReadAloudService.isRun) && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress({ progress -> ReadBook.callBack?.sureNewProgress(progress) })
            } else {
                syncBookProgress(book)
            }
        }
        if (!book.isLocal && ReadBook.bookSource == null) {
            autoChangeSource(book.name, book.author)
            return
        }
    }

    private fun notifyNoSourceIfNeeded(book: Book) {
        if (!book.isLocal && ReadBook.bookSource == null) {
            context.toastOnUi(R.string.error_no_source)
        }
    }

    private fun checkLocalBookFileExist(book: Book): Boolean {
        try {
            LocalBook.getBookInputStream(book)
            return true
        } catch (e: Throwable) {
            ReadBook.upMsg(appCtx.getString(R.string.ui_book_failed_open_local_book, e.localizedMessage))
            if (e is SecurityException || e is FileNotFoundException) {
                permissionDenialLiveData.postValue(0)
            }
            return false
        }
    }

    /**
     * 加载详情页
     */
    private suspend fun loadBookInfo(book: Book): Boolean {
        val source = ReadBook.bookSource ?: return true
        try {
            BookSessionLoadCoordinator.default.fetchOrJoinBookInfo(
                book = book,
                consumeHandoff = true,
                preserveNameAuthorOnApply = true
            ) { workingBook ->
                WebBook.getBookInfoAwait(source, workingBook, canReName = false)
            }
            return true
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            ReadBook.upMsg(appCtx.getString(R.string.ui_book_detail_page_error, e.localizedMessage))
            return false
        }
    }

    /**
     * 加载目录
     */
    fun loadChapterList(book: Book) {
        execute {
            if (loadChapterListAwait(book, forceRefresh = true)) {
                ReadBook.upMsg(null)
            }
        }
    }

    private suspend fun loadChapterListAwait(
        book: Book,
        loadContentAfterUpdate: Boolean = true,
        forceRefresh: Boolean = false
    ): Boolean {
        if (book.isLocal) {
            kotlin.runCatching {
                LocalBook.getChapterList(
                    book,
                    onProgress = { percent ->
                        //每 1% 都 upMsg 会触发一次正文视图更新，解析大文件时会明显重排，
                        //这里按 5% 节流（最多约 20 次）
                        if (percent % 5 == 0 || percent >= 99) {
                            ReadBook.upMsg(appCtx.getString(R.string.ui_book_parsing_contents, percent))
                        }
                    },
                    onCharsetSuspect = {
                        context.toastOnUi(appCtx.getString(R.string.ui_book_file_encoding_may_be_wrong))
                    }
                ).let {
                    appDb.runInTransaction {
                        appDb.bookChapterDao.delByBook(book.bookUrl)
                        appDb.bookChapterDao.insert(*it.toTypedArray())
                        appDb.bookDao.update(book)
                    }
                    ReadBook.onChapterListUpdated(book, loadContentAfterUpdate)
                }
                return true
            }.onFailure {
                when (it) {
                    is SecurityException, is FileNotFoundException -> {
                        permissionDenialLiveData.postValue(1)
                    }

                    else -> {
                        AppLog.put("LoadTocError:${it.localizedMessage}", it)
                        ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                    }
                }
                return false
            }
        } else {
            ReadBook.bookSource?.let { source ->
                val oldBook = book.copy()
                AppLog.put(
                    "read-toc: start, book=${book.name}, bookUrl=${book.bookUrl}, " +
                        "origin=${book.origin}, source=${source.bookSourceUrl}"
                )
                val progressCallback: (Int, Int) -> Unit = { loaded, total ->
                    // 目录分页较多时给出进度反馈，避免长时间没有任何动静
                    val progressText = if (total > 0) {
                        context.getString(R.string.toc_loading_pages, loaded, total)
                    } else {
                        context.getString(R.string.toc_loading_chapters, loaded)
                    }
                    runOnUI { ReadBook.upMsg(progressText) }
                }
                kotlin.runCatching {
                    if (forceRefresh) {
                        BookSessionLoadCoordinator.default.invalidateSession(book.bookUrl, book.origin)
                    }
                    BookSessionLoadCoordinator.default.fetchOrJoinChapterList(
                        book = book,
                        forceRefresh = forceRefresh,
                        consumeHandoff = !forceRefresh,
                        onProgress = progressCallback
                    ) { workingBook, onProgress ->
                        val state = TocLoadState()
                        val chapters = WebBook.getChapterListAwait(
                            bookSource = source,
                            book = workingBook,
                            runPerJs = true,
                            loadState = state,
                            earlyDispatchFirstChapter = true,
                            onProgress = onProgress
                        ).getOrThrow()
                        TocSessionResult(workingBook, chapters, state.failedPages)
                    }
                }
                    .onSuccess { sessionResult ->
                        val cList = sessionResult.chapters
                        val failedPages = sessionResult.failedPages
                        AppLog.put(
                            "read-toc: success, size=${cList.size}, oldBookUrl=${oldBook.bookUrl}, " +
                                "newBookUrl=${book.bookUrl}, first=${cList.firstOrNull()?.title}, " +
                                "last=${cList.lastOrNull()?.title}"
                        )
                        val oldChapterList = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
                        // 有分页失败时按旧章 url 覆盖率判定：源新增尾部章节会让
                        // newSize >= oldSize，仅比长度会漏掉「更长但缺中间页」的残缺目录
                        val missingOldUrls = if (failedPages > 0 && oldChapterList.isNotEmpty()) {
                            val newUrls = cList.mapTo(hashSetOf()) { it.url }
                            oldChapterList.count { it.url !in newUrls }
                        } else {
                            0
                        }
                        // 目录分页抓取不完整（部分页失败）且库里已有更完整的目录时，
                        // 沿用旧目录：既避免把完整目录覆盖成残缺目录，
                        // 也避免已缓存正文失去章节索引
                        if (TocIntegrity.shouldKeepOldToc(
                                failedPages,
                                cList.size,
                                oldChapterList.size,
                                missingOldUrls
                            )
                        ) {
                            AppLog.put(
                                "read-toc: keep old toc, new=${cList.size}, old=${oldChapterList.size}, " +
                                    "failedPages=${failedPages}"
                            )
                            // 恢复被目录解析改写的统计字段，保持内存与库内目录一致
                            book.totalChapterNum = oldChapterList.size
                            book.durChapterTitle = oldBook.durChapterTitle
                            book.latestChapterTitle = oldBook.latestChapterTitle
                            book.lastCheckTime = oldBook.lastCheckTime
                            book.lastCheckCount = oldBook.lastCheckCount
                            ReadBook.onChapterListUpdated(book, loadContentAfterUpdate)
                            return true
                        }
                        BookHelp.remapContentCache(oldBook, oldChapterList, cList)
                        // Loading the table of contents must not promote a sampled book onto
                        // the shelf. The url may have been re-derived while fetching, so the
                        // row about to be written can be a fresh one that never carried the
                        // flag; take it from what the reader actually chose.
                        if (!ReadBook.inBookshelf) {
                            book.addType(BookType.notShelf)
                        }
                        if (oldBook.bookUrl == book.bookUrl) {
                            appDb.bookDao.update(book)
                        } else {
                            appDb.bookDao.replace(oldBook, book)
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.runInTransaction {
                            appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                            appDb.bookChapterDao.insert(*cList.toTypedArray())
                        }
                        ReadBook.onChapterListUpdated(book, loadContentAfterUpdate)
                        return true
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        // 目录拉取失败：先沿用已有章节，再从缓存文件反推，
                        // 保证断网或源失效时仍能读已缓存的正文。
                        // url 被改写时缓存目录还没迁移，这种情况无从恢复，照常报错。
                        if (oldBook.bookUrl == book.bookUrl &&
                            tryRecoverFromCache(book)
                        ) {
                            ReadBook.upMsg(context.getString(R.string.toc_cache_recovered))
                            return true
                        }
                        ReadBook.upMsg(context.getString(R.string.error_load_toc))
                        AppLog.put(
                            "read-toc: failed, book=${book.name}, bookUrl=${book.bookUrl}, " +
                                "origin=${book.origin}\n${it.localizedMessage}",
                            it
                        )
                        return false
                    }
            }
        }
        // 没有书源（被删除或禁用）时目录无从拉取，用缓存目录兜底，仍能读已缓存的正文
        if (ReadBook.bookSource == null &&
            appDb.bookChapterDao.getChapterList(book.bookUrl).isEmpty() &&
            tryRecoverFromCache(book)
        ) {
            ReadBook.upMsg(context.getString(R.string.toc_cache_recovered))
        }
        return true
    }

    /**
     * 目录加载失败或无书源时的兜底：优先沿用已有的章节记录，没有再从缓存文件反推一份。
     *
     * @return 是否拿到了可用的章节列表
     */
    private suspend fun tryRecoverFromCache(
        book: Book,
        loadContentAfterUpdate: Boolean = true
    ): Boolean {
        if (appDb.bookChapterDao.getChapterList(book.bookUrl).isNotEmpty()) {
            ReadBook.onChapterListUpdated(book, loadContentAfterUpdate)
            return true
        }
        val recovered = BookHelp.recoverChaptersFromCache(book)
        if (recovered.isEmpty()) return false
        appDb.runInTransaction {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*recovered.toTypedArray())
        }
        book.totalChapterNum = recovered.size
        appDb.bookDao.update(book)
        ReadBook.onChapterListUpdated(book, loadContentAfterUpdate)
        AppLog.put("目录加载失败，已从缓存恢复 ${recovered.size} 章")
        return true
    }

    /**
     * 同步进度
     */
    fun syncBookProgress(
        book: Book,
        alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        execute {
            AppCloudStorage.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
        }.onSuccess { progress ->
            progress ?: return@onSuccess
            if (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos == book.durChapterPos) {
                return@onSuccess
            }
            if (progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                alertSync?.invoke(progress)
            } else if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                ReadBook.setProgress(progress)
                AppLog.put("自动同步阅读进度成功《${book.name}》 ${progress.durChapterTitle}")
                context.toastOnUi(appCtx.getString(R.string.ui_book_reading_progress_synced))
            }
        }
    }

    /**
     * 换源
     */
    fun changeTo(book: Book, toc: List<BookChapter>) {
        changeSourceCoroutine?.cancel()
        val oldBook = ReadBook.book
        val oldTextChapter = ReadBook.curTextChapter
        val oldPageIndex = ReadBook.durPageIndex
        val oldChapterPos = ReadBook.durChapterPos
        val changeSourceAnchor = captureChangeSourceAnchor(oldTextChapter, oldPageIndex, oldChapterPos)
        val oldBookSnapshot = oldBook?.let { BookSessionLoadCoordinator.copyBookSnapshot(it) }
        BookSessionLoadCoordinator.default.cancelEntryPersistence(oldBook?.bookUrl)
        changeSourceCoroutine = execute {
            val oldToc = oldBookSnapshot?.let {
                appDb.bookChapterDao.getChapterList(it.bookUrl)
            }.orEmpty()
            ReadBook.releaseAndCancel()
            ReadBook.upMsg(context.getString(R.string.loading), isError = false)
            oldBook?.migrateTo(book, toc)
            book.inheritNotShelfStateFrom(oldBook)
            book.removeType(BookType.updateError)
            if (oldBookSnapshot != null) {
                lastSourceSnapshot = PreviousSourceSnapshot(
                    previousBook = oldBookSnapshot,
                    previousToc = oldToc,
                    switchedToBookUrl = book.bookUrl
                )
            }
            if (changeSourceAnchor != null) {
                ReaderTextPositionStore.saveChangeSourceAnchor(
                    bookUrl = book.bookUrl,
                    chapterIndex = book.durChapterIndex,
                    anchor = changeSourceAnchor
                )
            }
            pendingSourceValidation = book.bookUrl to book.durChapterIndex
            sourceSwitchRecoveryLiveData.postValue(null)
            appDb.runInTransaction {
                oldBook?.delete()
                appDb.bookDao.insert(book)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
            if (oldBook != null &&
                oldBook.bookUrl != book.bookUrl &&
                oldBook.getFolderNameNoCache() != book.getFolderNameNoCache()
            ) {
                runCatching {
                    BookHelp.clearCache(oldBook)
                }.onFailure {
                    AppLog.put("换源清理旧缓存失败: ${oldBook.name}\n${it.localizedMessage}", it)
                }
            }
            ReadBook.resetData(book, cancelInFlight = false)
            ReadBook.upMsg(null)
            ReadBook.loadContent(resetPageOffset = true)
        }.onError {
            AppLog.put("换源失败\n$it", it, true)
            ReadBook.upMsg(appCtx.getString(R.string.misc_failed_load_content_n, it.localizedMessage ?: ""), isError = true)
            lastSourceSnapshot?.let { snapshot ->
                sourceSwitchRecoveryLiveData.postValue(
                    SourceSwitchRecoveryState(
                        bookUrl = book.bookUrl,
                        chapterIndex = book.durChapterIndex,
                        issue = SourceSwitchQualityPolicy.ChapterIssue.LOAD_FAILED,
                        effectiveCharCount = 0,
                        previousOriginName = snapshot.previousBook.originName
                    )
                )
            }
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun revertLastSourceChange() {
        val snapshot = lastSourceSnapshot ?: return
        changeSourceCoroutine?.cancel()
        lastSourceSnapshot = null
        pendingSourceValidation = null
        sourceSwitchRecoveryLiveData.postValue(null)
        changeSourceCoroutine = execute {
            val currentBook = ReadBook.book
            ReadBook.releaseAndCancel()
            ReadBook.upMsg(context.getString(R.string.loading), isError = false)
            ReaderTextPositionStore.clearChangeSourceAnchors(currentBook?.bookUrl)
            val restoredBook = BookSessionLoadCoordinator.copyBookSnapshot(snapshot.previousBook)
            restoredBook.inheritNotShelfStateFrom(currentBook)
            appDb.runInTransaction {
                currentBook?.takeIf { it.bookUrl != restoredBook.bookUrl }?.delete()
                appDb.bookDao.insert(restoredBook)
                if (snapshot.previousToc.isNotEmpty()) {
                    appDb.bookChapterDao.replaceByBook(restoredBook.bookUrl, snapshot.previousToc)
                }
            }
            if (currentBook != null &&
                currentBook.bookUrl != restoredBook.bookUrl &&
                currentBook.getFolderNameNoCache() != restoredBook.getFolderNameNoCache()
            ) {
                runCatching {
                    BookHelp.clearCache(currentBook)
                }.onFailure {
                    AppLog.put("回退书源清理缓存失败: ${currentBook.name}\n${it.localizedMessage}", it)
                }
            }
            ReadBook.resetData(restoredBook, cancelInFlight = false)
            if (snapshot.previousToc.isEmpty() && !restoredBook.isLocal) {
                loadChapterListAwait(restoredBook, loadContentAfterUpdate = false, forceRefresh = true)
            }
            ReadBook.upMsg(null)
            ReadBook.loadContent(resetPageOffset = true)
        }.onError {
            AppLog.put("回退原书源失败\n$it", it, true)
            ReadBook.upMsg(appCtx.getString(R.string.misc_failed_load_content_n, it.localizedMessage ?: ""), isError = true)
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, snapshot.previousBook.bookUrl)
        }
    }

    private fun captureChangeSourceAnchor(
        curTextChapter: TextChapter?,
        durPageIndex: Int,
        durChapterPos: Int
    ): ChangeSourceTextAnchor? {
        if (curTextChapter == null) return null
        val chapterContentLength = curTextChapter.getContent().length
        val page = curTextChapter.getPage(durPageIndex)
            ?: curTextChapter.getPageByReadPos(durChapterPos)
        val pageText = page?.text.orEmpty()
        return ChangeSourceTextAnchorHelper.extractAnchor(
            pageText = pageText,
            chapterContentLength = chapterContentLength,
            durChapterPos = durChapterPos,
            chapterTitle = curTextChapter.title,
            isFirstPage = (page?.index ?: durPageIndex) == 0
        )
    }

    /**
     * 自动换源
     */
    private fun autoChangeSource(name: String, author: String) {
        if (!AppConfig.autoChangeSource) return
        execute {
            val sources = appDb.bookSourceDao.allTextEnabledPart
            flow {
                for (source in sources) {
                    source.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                ReadBook.upMsg(context.getString(R.string.source_auto_changing))
            }.mapParallelSafe(AppConfig.threadCount) { source ->
                val book = WebBook.preciseSearchAwait(source, name, author).getOrThrow()
                if (book.tocUrl.isEmpty()) {
                    WebBook.getBookInfoAwait(source, book)
                }
                val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                if (toc.isEmpty()) {
                    throw NoStackTraceException("目录为空")
                }
                val oldBook = ReadBook.book
                val targetIndex = if (oldBook != null) {
                    BookHelp.getDurChapter(oldBook, toc)
                } else {
                    book.durChapterIndex.coerceIn(0, maxOf(0, toc.lastIndex))
                }
                val chapter = toc.getOrElse(targetIndex) {
                    toc.last()
                }
                val nextChapter = toc.getOrNull(chapter.index + 1)
                WebBook.getContentAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    nextChapterUrl = nextChapter?.url
                )
                book to toc
            }.take(1).onEach { (book, toc) ->
                changeTo(book, toc)
            }.onEmpty {
                throw NoStackTraceException("没有合适书源")
            }.onCompletion {
                ReadBook.upMsg(null)
            }.catch {
                AppLog.put("自动换源失败\n${it.localizedMessage}", it)
                context.toastOnUi(appCtx.getString(R.string.ui_book_auto_source_switch_failed_n, it.localizedMessage))
            }.collect()
        }
    }

    fun openChapter(index: Int, durChapterPos: Int = 0, success: (() -> Unit)? = null) {
        ReadBook.openChapter(index, durChapterPos, success = success)
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        val book = ReadBook.book
        BookSessionLoadCoordinator.default.cancelEntryPersistence(book?.bookUrl)
        Coroutine.async(Coroutine.defaultScope) {
            if (book != null) {
                val byUrl = appDb.bookDao.deleteIfNotShelf(book.bookUrl)
                // The row saved when the book was opened is not always the row this
                // bookUrl points at now: a source can derive an unstable url, so the
                // delete above may miss and leave a book the reader never added. Clear
                // the temporary rows for this identity within the same source too.
                val byIdentity = if (book.name.isNotBlank() && book.origin.isNotBlank()) {
                    appDb.bookDao.deleteTempByIdentity(book.name, book.author, book.origin)
                } else {
                    0
                }
                if (byUrl == 0 && byIdentity == 0) {
                    AppLog.put(
                        "跳过删除正式书架书: ${book.name}, bookUrl=${book.bookUrl}, " +
                            "inBookshelf=${ReadBook.inBookshelf}"
                    )
                }
            }
        }.onError {
            AppLog.put("删除临时书失败: bookUrl=${book?.bookUrl}", it)
        }.onFinally {
            success?.invoke()
        }
    }

    fun upBookSource(success: (() -> Unit)?) {
        execute {
            ReadBook.book?.let { book ->
                ReadBook.bookSource = appDb.bookSourceDao.getBookSource(book.origin)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun refreshContentDur(book: Book) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.delContent(book, chapter)
                    ReadBook.reloadCurrentContent("refresh-current-content")
                }
        }
    }

    fun refreshContentAfter(book: Book) {
        execute {
            appDb.bookChapterDao.getChapterList(
                book.bookUrl,
                ReadBook.durChapterIndex,
                book.totalChapterNum
            ).forEach { chapter ->
                BookHelp.delContent(book, chapter)
            }
            ReadBook.reloadCurrentContent("refresh-content-after")
        }
    }

    fun refreshContentAll(book: Book) {
        execute {
            BookHelp.clearCache(book)
            ReadBook.reloadCurrentContent("refresh-content-all")
        }
    }

    /**
     * 保存内容
     */
    fun saveContent(book: Book, content: String) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.saveText(book, chapter, content)
                    ReadBook.reloadCurrentContent("save-content")
                }
        }
    }

    /**
     * 反转内容
     */
    fun reverseContent(book: Book) {
        execute {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@execute
            val content = BookHelp.getContent(book, chapter) ?: return@execute
            val stringBuilder = StringBuilder()
            content.toStringArray().forEach {
                stringBuilder.insert(0, it)
            }
            BookHelp.saveText(book, chapter, stringBuilder.toString())
            ReadBook.reloadCurrentContent("reverse-content")
        }
    }

    /**
     * 内容搜索跳转
     */
    fun searchResultPositions(
        textChapter: TextChapter,
        searchResult: SearchResult
    ): Array<Int> {
        // calculate search result's pageIndex
        val pages = textChapter.pages
        val content = textChapter.getContent()
        var queryLength = searchContentQuery.length

        var index: Int
        if (searchResult.isRegex) {
            val regex = Regex(searchContentQuery)
            val matches = regex.findAll(content)
            val match = matches.elementAtOrNull(searchResult.resultCountWithinChapter)
            queryLength = match?.value?.length ?: 0
            index = match?.range?.first ?: -1
        } else {
            var count = 0
            index = content.indexOf(searchContentQuery)
            while (count != searchResult.resultCountWithinChapter) {
                index = content.indexOf(searchContentQuery, index + queryLength)
                count += 1
            }
        }
        val contentPosition = index
        var pageIndex = 0
        var length = pages[pageIndex].text.length
        while (length < contentPosition && pageIndex + 1 < pages.size) {
            pageIndex += 1
            length += pages[pageIndex].text.length
        }

        // calculate search result's lineIndex
        val currentPage = pages[pageIndex]
        val curTextLines = currentPage.lines
        var lineIndex = 0
        var curLine = curTextLines[lineIndex]
        length = length - currentPage.text.length + curLine.text.length
        if (curLine.isParagraphEnd) length++
        while (length <= contentPosition && lineIndex + 1 < curTextLines.size) {
            lineIndex += 1
            curLine = curTextLines[lineIndex]
            length += curLine.text.length
            if (curLine.isParagraphEnd) length++
        }

        // charIndex
        val currentLine = currentPage.lines[lineIndex]
        var curLineLength = currentLine.text.length
        if (currentLine.isParagraphEnd) curLineLength++
        length -= curLineLength

        val charIndex = contentPosition - length
        var addLine = 0
        var charIndex2 = 0
        // change line
        if ((charIndex + queryLength) > curLineLength) {
            addLine = 1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        // changePage
        if ((lineIndex + addLine + 1) > currentPage.lines.size) {
            addLine = -1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        return arrayOf(pageIndex, lineIndex, charIndex, addLine, charIndex2, queryLength)
    }

    /**
     * 翻转删除重复标题
     */
    fun reverseRemoveSameTitle() {
        execute {
            val book = ReadBook.book ?: return@execute
            val textChapter = ReadBook.curTextChapter ?: return@execute
            BookHelp.setRemoveSameTitle(
                book, textChapter.chapter, !textChapter.sameTitleRemoved
            )
            ReadBook.reloadCurrentContent("same-title")
        }
    }

    /**
     * 刷新图片
     */
    fun refreshImage(src: String) {
        execute {
            ReadBook.book?.let { book ->
                val vFile = BookHelp.getImage(book, src)
                ImageProvider.bitmapLruCache.remove(vFile.absolutePath)
                vFile.delete()
            }
        }.onFinally {
            ReadBook.loadContent(false)
        }
    }

    /**
     * 保存图片
     */
    fun saveImage(src: String?, uri: Uri) {
        src ?: return
        val book = ReadBook.book ?: return
        execute {
            val image = BookHelp.getImage(book, src)
            FileInputStream(image).use { input ->
                if (uri.isContentScheme()) {
                    DocumentFile.fromTreeUri(context, uri)?.let { doc ->
                        val imageDoc = DocumentUtils.createFileIfNotExist(doc, image.name)!!
                        context.contentResolver.openOutputStream(imageDoc.uri)!!.use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    val dir = File(uri.path ?: uri.toString())
                    val file = FileUtils.createFileIfNotExist(dir, image.name)
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }.onError {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            context.toastOnUi(appCtx.getString(R.string.ui_book_failed_save_image_n, it.localizedMessage))
        }
    }

    /**
     * 替换规则变化
     */
    fun replaceRuleChanged() {
        execute {
            ReadBook.book?.let {
                ContentProcessor.get(it.name, it.origin).upReplaceRules()
                ReadBook.reloadCurrentContent("replace-rule")
            }
        }
    }

    fun disableSource() {
        execute {
            ReadBook.bookSource?.let {
                it.enabled = false
                appDb.bookSourceDao.update(it)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ReadBook.removeChapterQualityListener(chapterQualityListener)
        if (BaseReadAloudService.isRun && BaseReadAloudService.pause) {
            ReadAloud.stop(context)
        }
    }

}
