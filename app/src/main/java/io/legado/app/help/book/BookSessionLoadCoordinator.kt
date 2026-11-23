package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ReadBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class BookSessionKey(
    val bookUrl: String,
    val origin: String
)

data class TocSessionResult(
    val book: Book,
    val chapters: List<BookChapter>,
    val failedPages: Int = 0
)

/**
 * Coordinates in-flight book-info / chapter-list loads between [io.legado.app.ui.book.info.BookInfoViewModel]
 * and [io.legado.app.ui.book.read.ReadBookViewModel], plus sampled-book (`!inBookshelf`) in-memory TOC
 * handoff with asynchronous Room persistence.
 */
class BookSessionLoadCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val handoffTtlMs: Long = DEFAULT_HANDOFF_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val persistSampledBook: suspend (Book, List<BookChapter>, () -> Boolean) -> Unit = { book, chapters, isCanceled ->
        defaultPersistSampledBook(book, chapters, isCanceled)
    }
) {
    private class InFlightBookInfoEntry(
        val deferred: Deferred<Book>,
        val waiters: AtomicInteger = AtomicInteger(0)
    )

    private class InFlightTocEntry(
        val deferred: Deferred<TocSessionResult>,
        val waiters: AtomicInteger = AtomicInteger(0),
        val progressListeners: CopyOnWriteArrayList<(Int, Int) -> Unit> = CopyOnWriteArrayList()
    )

    private data class RecentBookInfoHandoff(
        val book: Book,
        val createdAtMs: Long
    )

    private data class RecentTocHandoff(
        val result: TocSessionResult,
        val createdAtMs: Long
    )

    private class StagedSampledEntry(
        val book: Book,
        val chapters: List<BookChapter>,
        val createdAtMs: Long,
        @Volatile var tocConsumed: Boolean = false,
        @Volatile var persisted: Boolean = false,
        @Volatile var canceled: Boolean = false,
        @Volatile var persistJob: Job? = null
    )

    private val lock = Any()
    private val inFlightBookInfo = ConcurrentHashMap<BookSessionKey, InFlightBookInfoEntry>()
    private val inFlightToc = ConcurrentHashMap<BookSessionKey, InFlightTocEntry>()
    private val recentBookInfo = ConcurrentHashMap<BookSessionKey, RecentBookInfoHandoff>()
    private val recentToc = ConcurrentHashMap<BookSessionKey, RecentTocHandoff>()
    private val stagedEntries = ConcurrentHashMap<String, StagedSampledEntry>()

    suspend fun fetchOrJoinBookInfo(
        book: Book,
        forceRefresh: Boolean = false,
        rememberHandoff: Boolean = false,
        consumeHandoff: Boolean = false,
        preserveNameAuthorOnApply: Boolean = false,
        fetcher: suspend (Book) -> Book
    ): Book {
        currentCoroutineContext().ensureActive()
        val key = BookSessionKey(book.bookUrl, book.origin)
        if (forceRefresh) {
            recentBookInfo.remove(key)
        } else if (consumeHandoff) {
            consumeFreshBookInfoHandoff(key)?.let { cached ->
                applyBookInfoSnapshot(book, cached, preserveNameAuthor = preserveNameAuthorOnApply)
                return book
            }
        }
        var shouldStart = false
        val entry = synchronized(lock) {
            val existing = inFlightBookInfo[key]
            if (existing != null && !existing.deferred.isCompleted && !existing.deferred.isCancelled) {
                existing.waiters.incrementAndGet()
                existing
            } else {
                if (existing != null) {
                    inFlightBookInfo.remove(key, existing)
                }
                val workingBook = copyBookSnapshot(book)
                var completedSnapshot: Book? = null
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    val fetched = fetcher(workingBook)
                    copyBookSnapshot(fetched).also { completedSnapshot = it }
                }
                val created = InFlightBookInfoEntry(deferred, AtomicInteger(1))
                inFlightBookInfo[key] = created
                deferred.invokeOnCompletion { throwable ->
                    synchronized(lock) {
                        inFlightBookInfo.remove(key, created)
                    }
                    if (throwable == null && rememberHandoff) {
                        completedSnapshot?.let { completed ->
                            recentBookInfo[key] = RecentBookInfoHandoff(completed, clockMs())
                        }
                    }
                }
                shouldStart = true
                created
            }
        }
        if (shouldStart) {
            entry.deferred.start()
        }
        var released = false
        val result = try {
            entry.deferred.await()
        } catch (e: CancellationException) {
            releaseBookInfoWaiter(key, entry, cancelIfLast = true)
            released = true
            throw e
        } finally {
            if (!released) {
                releaseBookInfoWaiter(key, entry, cancelIfLast = false)
            }
        }
        if (consumeHandoff) {
            recentBookInfo.remove(key)
        }
        applyBookInfoSnapshot(book, result, preserveNameAuthor = preserveNameAuthorOnApply)
        return book
    }

    suspend fun fetchOrJoinChapterList(
        book: Book,
        forceRefresh: Boolean = false,
        rememberHandoff: Boolean = false,
        consumeHandoff: Boolean = false,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
        fetcher: suspend (workingBook: Book, onProgress: (Int, Int) -> Unit) -> TocSessionResult
    ): TocSessionResult {
        currentCoroutineContext().ensureActive()
        val key = BookSessionKey(book.bookUrl, book.origin)
        if (forceRefresh) {
            recentToc.remove(key)
        } else if (consumeHandoff) {
            consumeFreshTocHandoff(key)?.let { cached ->
                applyTocBookSnapshot(book, cached.book)
                return TocSessionResult(book, cached.chapters, cached.failedPages)
            }
        }
        var shouldStart = false
        val entry = synchronized(lock) {
            val existing = inFlightToc[key]
            if (existing != null && !existing.deferred.isCompleted && !existing.deferred.isCancelled) {
                existing.waiters.incrementAndGet()
                if (onProgress != null) {
                    existing.progressListeners.add(onProgress)
                }
                existing
            } else {
                if (existing != null) {
                    inFlightToc.remove(key, existing)
                }
                val listeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
                if (onProgress != null) {
                    listeners.add(onProgress)
                }
                val workingBook = copyBookSnapshot(book)
                var completedSnapshot: TocSessionResult? = null
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    val res = fetcher(workingBook) { loaded, total ->
                        listeners.forEach { listener ->
                            runCatching { listener(loaded, total) }
                        }
                    }
                    TocSessionResult(
                        book = copyBookSnapshot(res.book),
                        chapters = res.chapters,
                        failedPages = res.failedPages
                    ).also { completedSnapshot = it }
                }
                val created = InFlightTocEntry(deferred, AtomicInteger(1), listeners)
                inFlightToc[key] = created
                deferred.invokeOnCompletion { throwable ->
                    synchronized(lock) {
                        inFlightToc.remove(key, created)
                    }
                    if (throwable == null && rememberHandoff) {
                        completedSnapshot?.let { completed ->
                            recentToc[key] = RecentTocHandoff(completed, clockMs())
                        }
                    }
                }
                shouldStart = true
                created
            }
        }
        if (shouldStart) {
            entry.deferred.start()
        }
        var released = false
        val result = try {
            entry.deferred.await()
        } catch (e: CancellationException) {
            releaseTocWaiter(key, entry, onProgress, cancelIfLast = true)
            released = true
            throw e
        } finally {
            if (!released) {
                releaseTocWaiter(key, entry, onProgress, cancelIfLast = false)
            }
        }
        if (consumeHandoff) {
            recentToc.remove(key)
        }
        applyTocBookSnapshot(book, result.book)
        return TocSessionResult(book, result.chapters, result.failedPages)
    }

    /**
     * Stages a sampled (`!inBookshelf`) book + in-memory chapter list for immediate reader entry,
     * while launching a re-entrancy-safe background IO job to persist them into SQLite.
     */
    fun stageSampledBookEntry(
        book: Book,
        chapters: List<BookChapter>
    ) {
        if (book.bookUrl.isBlank() || chapters.isEmpty()) return
        val bookSnapshot = copyBookSnapshot(book)
        val chapterSnapshot = chapters.toList()
        var jobToStart: Job? = null
        synchronized(lock) {
            val existing = stagedEntries[book.bookUrl]
            if (existing != null && !existing.canceled) {
                val activeJob = existing.persistJob
                if (activeJob != null && activeJob.isActive &&
                    existing.chapters.size == chapterSnapshot.size &&
                    existing.book.origin == bookSnapshot.origin
                ) {
                    // Re-entrant tap while identical persistence is already running.
                    return
                }
                existing.canceled = true
                activeJob?.cancel()
            }
            val entry = StagedSampledEntry(
                book = bookSnapshot,
                chapters = chapterSnapshot,
                createdAtMs = clockMs()
            )
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (entry.canceled) return@launch
                    persistSampledBook(copyBookSnapshot(entry.book), entry.chapters) { entry.canceled }
                    synchronized(lock) {
                        if (stagedEntries[book.bookUrl] === entry) {
                            entry.persisted = true
                            if (entry.tocConsumed) {
                                stagedEntries.remove(book.bookUrl, entry)
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // Expected when user leaves or switches source before DB write finishes.
                } catch (e: Throwable) {
                    AppLog.put("试读书后台目录写库失败: ${entry.book.name}\n${e.localizedMessage}", e)
                }
            }
            entry.persistJob = job
            stagedEntries[book.bookUrl] = entry
            jobToStart = job
        }
        jobToStart?.start()
    }

    fun peekStagedBook(bookUrl: String?): Book? {
        if (bookUrl.isNullOrBlank()) return null
        val entry = stagedEntries[bookUrl] ?: return null
        if (entry.canceled) return null
        if (isExpired(entry.createdAtMs)) {
            if (entry.persisted) {
                stagedEntries.remove(bookUrl, entry)
            }
            return null
        }
        return copyBookSnapshot(entry.book)
    }

    fun consumeStagedToc(bookUrl: String?): List<BookChapter>? {
        if (bookUrl.isNullOrBlank()) return null
        synchronized(lock) {
            val entry = stagedEntries[bookUrl] ?: return null
            if (entry.canceled || entry.tocConsumed) return null
            if (isExpired(entry.createdAtMs)) {
                if (entry.persisted) {
                    stagedEntries.remove(bookUrl, entry)
                }
                return null
            }
            entry.tocConsumed = true
            val chapters = entry.chapters
            if (entry.persisted) {
                stagedEntries.remove(bookUrl, entry)
            }
            return chapters
        }
    }

    fun findStagedChapter(bookUrl: String?, index: Int): BookChapter? {
        if (bookUrl.isNullOrBlank() || index < 0) return null
        val entry = stagedEntries[bookUrl] ?: return null
        if (entry.canceled) return null
        return entry.chapters.getOrNull(index)?.takeIf { it.index == index }
            ?: entry.chapters.firstOrNull { it.index == index }
    }

    fun findStagedChapterCount(bookUrl: String?): Int? {
        if (bookUrl.isNullOrBlank()) return null
        val entry = stagedEntries[bookUrl] ?: return null
        if (entry.canceled) return null
        return entry.chapters.size
    }

    suspend fun awaitPendingPersist(bookUrl: String?) {
        if (bookUrl.isNullOrBlank()) return
        val job = synchronized(lock) {
            stagedEntries[bookUrl]?.takeIf { !it.canceled }?.persistJob
        } ?: return
        runCatching { job.join() }
    }

    fun cancelEntryPersistence(bookUrl: String?) {
        if (bookUrl.isNullOrBlank()) return
        synchronized(lock) {
            val entry = stagedEntries.remove(bookUrl)
            if (entry != null) {
                entry.canceled = true
                entry.persistJob?.cancel()
            }
        }
    }

    fun hasPendingPersist(bookUrl: String?): Boolean {
        if (bookUrl.isNullOrBlank()) return false
        val entry = stagedEntries[bookUrl] ?: return false
        return !entry.canceled && entry.persistJob?.isActive == true
    }

    fun stagedEntryCount(): Int = stagedEntries.size

    fun invalidateSession(bookUrl: String, origin: String) {
        val key = BookSessionKey(bookUrl, origin)
        recentBookInfo.remove(key)
        recentToc.remove(key)
    }

    fun isBookInfoInFlight(bookUrl: String, origin: String): Boolean = synchronized(lock) {
        val entry = inFlightBookInfo[BookSessionKey(bookUrl, origin)] ?: return@synchronized false
        !entry.deferred.isCompleted && !entry.deferred.isCancelled
    }

    fun isChapterListInFlight(bookUrl: String, origin: String): Boolean = synchronized(lock) {
        val entry = inFlightToc[BookSessionKey(bookUrl, origin)] ?: return@synchronized false
        !entry.deferred.isCompleted && !entry.deferred.isCancelled
    }

    fun inFlightBookInfoCount(): Int = synchronized(lock) { inFlightBookInfo.size }

    fun inFlightChapterListCount(): Int = synchronized(lock) { inFlightToc.size }

    fun bookInfoWaiterCount(bookUrl: String, origin: String): Int = synchronized(lock) {
        inFlightBookInfo[BookSessionKey(bookUrl, origin)]?.waiters?.get() ?: 0
    }

    fun chapterListWaiterCount(bookUrl: String, origin: String): Int = synchronized(lock) {
        inFlightToc[BookSessionKey(bookUrl, origin)]?.waiters?.get() ?: 0
    }

    private fun consumeFreshBookInfoHandoff(key: BookSessionKey): Book? {
        val handoff = recentBookInfo.remove(key) ?: return null
        if (isExpired(handoff.createdAtMs)) return null
        return handoff.book
    }

    private fun consumeFreshTocHandoff(key: BookSessionKey): TocSessionResult? {
        val handoff = recentToc.remove(key) ?: return null
        if (isExpired(handoff.createdAtMs)) return null
        return handoff.result
    }

    private fun isExpired(createdAtMs: Long): Boolean {
        return clockMs() - createdAtMs > handoffTtlMs
    }

    private fun releaseBookInfoWaiter(
        key: BookSessionKey,
        entry: InFlightBookInfoEntry,
        cancelIfLast: Boolean
    ) {
        var deferredToCancel: Deferred<Book>? = null
        synchronized(lock) {
            val remaining = entry.waiters.decrementAndGet()
            if (cancelIfLast && remaining <= 0 && !entry.deferred.isCompleted) {
                inFlightBookInfo.remove(key, entry)
                deferredToCancel = entry.deferred
            }
        }
        deferredToCancel?.cancel()
    }

    private fun releaseTocWaiter(
        key: BookSessionKey,
        entry: InFlightTocEntry,
        onProgress: ((Int, Int) -> Unit)?,
        cancelIfLast: Boolean
    ) {
        if (onProgress != null) {
            entry.progressListeners.remove(onProgress)
        }
        var deferredToCancel: Deferred<TocSessionResult>? = null
        synchronized(lock) {
            val remaining = entry.waiters.decrementAndGet()
            if (cancelIfLast && remaining <= 0 && !entry.deferred.isCompleted) {
                inFlightToc.remove(key, entry)
                deferredToCancel = entry.deferred
            }
        }
        deferredToCancel?.cancel()
    }

    companion object {
        private const val DEFAULT_HANDOFF_TTL_MS = 30_000L

        val default = BookSessionLoadCoordinator()

        fun copyBookSnapshot(book: Book): Book {
            return book.copy(
                readConfig = book.readConfig?.copy()
            ).also { copy ->
                copy.infoHtml = book.infoHtml
                copy.tocHtml = book.tocHtml
                copy.downloadUrls = book.downloadUrls?.toList()
            }
        }

        fun applyBookInfoSnapshot(
            target: Book,
            source: Book,
            preserveNameAuthor: Boolean = false
        ) {
            val wasNotShelf = target.isNotShelf
            if (!preserveNameAuthor || target.name.isBlank()) {
                if (source.name.isNotBlank()) target.name = source.name
            }
            if (!preserveNameAuthor || target.author.isBlank()) {
                if (source.author.isNotBlank()) target.author = source.author
            }
            if (!source.originName.isBlank()) target.originName = source.originName
            if (!source.kind.isNullOrBlank()) target.kind = source.kind
            if (!source.wordCount.isNullOrBlank()) target.wordCount = source.wordCount
            if (!source.latestChapterTitle.isNullOrBlank()) target.latestChapterTitle = source.latestChapterTitle
            if (!source.intro.isNullOrBlank()) target.intro = source.intro
            if (!source.coverUrl.isNullOrBlank()) target.coverUrl = source.coverUrl
            if (source.tocUrl.isNotBlank()) target.tocUrl = source.tocUrl
            if (source.tocHtml != null) target.tocHtml = source.tocHtml
            if (source.infoHtml != null) target.infoHtml = source.infoHtml
            if (source.downloadUrls != null) target.downloadUrls = source.downloadUrls?.toList()
            if (source.variable != null) target.variable = source.variable
            if (source.charset != null) target.charset = source.charset
            target.type = source.type
            if (wasNotShelf) {
                target.addType(BookType.notShelf)
            }
        }

        fun applyTocBookSnapshot(target: Book, source: Book) {
            val wasNotShelf = target.isNotShelf
            if (source.bookUrl.isNotBlank() && target.bookUrl != source.bookUrl) {
                target.bookUrl = source.bookUrl
            }
            if (source.tocUrl.isNotBlank()) target.tocUrl = source.tocUrl
            if (source.tocHtml != null) target.tocHtml = source.tocHtml
            if (source.variable != null) target.variable = source.variable
            target.totalChapterNum = source.totalChapterNum
            if (!source.durChapterTitle.isNullOrBlank()) {
                target.durChapterTitle = source.durChapterTitle
            }
            if (!source.latestChapterTitle.isNullOrBlank()) {
                target.latestChapterTitle = source.latestChapterTitle
            }
            target.lastCheckTime = source.lastCheckTime
            target.lastCheckCount = source.lastCheckCount
            target.latestChapterTime = source.latestChapterTime
            target.type = source.type
            if (wasNotShelf) {
                target.addType(BookType.notShelf)
            }
        }

        private fun defaultPersistSampledBook(
            book: Book,
            chapters: List<BookChapter>,
            isCanceled: () -> Boolean
        ) {
            if (isCanceled()) return
            appDb.runInTransaction {
                if (isCanceled()) return@runInTransaction
                val existingByUrl = appDb.bookDao.getBook(book.bookUrl)
                val existingByIdentity = existingByUrl
                    ?: book.name.takeIf { it.isNotBlank() }?.let {
                        appDb.bookDao.getBook(it, book.author)
                    }
                val activeInReader = ReadBook.book?.takeIf { it.bookUrl == book.bookUrl }
                val readerShelved = activeInReader != null && ReadBook.inBookshelf
                val existingShelved = existingByIdentity != null && !existingByIdentity.isNotShelf
                if (existingShelved || readerShelved) {
                    book.removeType(BookType.notShelf)
                } else {
                    book.addType(BookType.notShelf)
                }
                if (existingByIdentity != null) {
                    if (existingByIdentity.durChapterTime > book.durChapterTime) {
                        book.durChapterIndex = existingByIdentity.durChapterIndex
                        book.durChapterPos = existingByIdentity.durChapterPos
                        book.durChapterTitle = existingByIdentity.durChapterTitle
                        book.durChapterTime = existingByIdentity.durChapterTime
                    }
                    if (existingShelved) {
                        book.group = existingByIdentity.group
                        book.order = existingByIdentity.order
                    }
                    if (existingByIdentity.bookUrl == book.bookUrl) {
                        appDb.bookDao.update(book)
                    } else {
                        appDb.bookDao.replace(existingByIdentity, book)
                    }
                } else {
                    if (book.order == 0) {
                        book.order = appDb.bookDao.minOrder - 1
                    }
                    appDb.bookDao.insert(book)
                }
                appDb.bookChapterDao.replaceByBook(book.bookUrl, chapters)
            }
        }
    }
}
