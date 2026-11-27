package io.legado.app.help.review

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.util.AtomicFile
import androidx.collection.LruCache
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReviewCache
import io.legado.app.help.book.BookHelp
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 评论页快照实体：一章的某个评论按钮对应一份“真实评论页”网页快照。
 * html 已在抓取时穷尽展开/回复/加载更多，并把样式与图片内联，可完全离线渲染。
 *
 * 快照主键 = chapter.url + buttonSrc：目录前插章节、重新排序后 index 会变化，
 * url 才是稳定标识；chapterIndex 只作为兼容/展示字段。
 */
data class ReviewSnapshot(
    val version: Int = 2,
    val bookUrl: String = "",
    /** 章节稳定标识：主键一部分 */
    val chapterUrl: String = "",
    /** 章节序号，仅兼容/展示用，不作为主键 */
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    /** 抓取时的评论按钮 src（含选项 JSON），作为快照主键的一部分 */
    val buttonSrc: String = "",
    /** 真实评论页地址（click JS 执行后由 startBrowser/showBrowser 拦截得到） */
    val url: String = "",
    val title: String = "",
    val html: String = "",
    /**
     * 兼容旧格式快照字段：新快照不再落盘资源库，默认为空列表；保持可空以兼容旧快照 JSON。
     */
    val resourceKeys: List<String>? = emptyList(),
    /**
     * 部分成功快照：保留字段以兼容既有调用方与旧 JSON 反序列化。
     */
    val partial: Boolean = false,
    val savedAt: Long = 0L,
    val downloadEngine: String = "web",
    /** 包括按用户完整性策略忽略的失败；不能把成功状态当作资源齐全。 */
    val resourceFailures: List<String> = emptyList(),
)

/**
 * 评论页快照存储（单表 Room `review_cache` 作为唯一真相源）。
 *
 * 兼容策略：
 * - 首次访问某本书时，按书只读扫描旧 `<book_cache>/<book folder>/reviews/r_*.json`
 *   幂等导入 `review_cache`，绝不删除或修改磁盘旧文件。
 * - 所有新写入、统计、淘汰、清空与恢复全部走 `review_cache`，不再向 `reviews/` 写任何文件。
 */
object ReviewSnapshotStore {

    const val REVIEWS_DIR_NAME = "reviews"
    private const val FILE_PREFIX = "r_"
    private const val FILE_SUFFIX = ".json"

    /** 约 12MB 字符上限：覆盖预热带 3 章窗口 + 近期点击章，超出按 LRU 淘汰 */
    private const val SNAPSHOT_CACHE_MAX_CHARS = 12 * 1024 * 1024

    /** 排版期段评角标有界内存 LRU 缓存上限 */
    private const val CHAPTER_BADGE_CACHE_MAX_ENTRIES = 256

    /** 离线评论快照保留天数（与 TTL 无关：TTL 只决定是否重新抓取）。 */
    private const val RETENTION_DAYS = 30

    /** 单书离线评论快照总大小上限（32MB），超出按 LRU 淘汰至 90% 水位。 */
    private const val MAX_CACHE_BYTES = 32L * 1024 * 1024

    /** 全局离线评论快照总大小上限（128MB），超出按 LRU 淘汰至 90% 水位。 */
    private const val GLOBAL_MAX_CACHE_BYTES = 128L * 1024 * 1024

    private const val EVICT_WATERMARK_NUMERATOR = 9L
    private const val EVICT_WATERMARK_DENOMINATOR = 10L
    private const val EVICT_BATCH_SIZE = 64

    private const val PRUNE_INTERVAL_MS = 10 * 60 * 1000L
    private const val LAST_ACCESS_TOUCH_INTERVAL_MS = 3_600_000L

    private const val LEGACY_IMPORT_PREFS = "review_cache_legacy_import"
    private const val LEGACY_IMPORT_BATCH_SIZE = 20

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 段评快照内存缓存（点击即读路径专用）。
     */
    private val snapshotCache = object : LruCache<String, ReviewSnapshot>(SNAPSHOT_CACHE_MAX_CHARS) {
        override fun sizeOf(key: String, value: ReviewSnapshot): Int = value.html.length.coerceAtLeast(1)
    }

    /** 排版期章节级已缓存 buttonSrc 集合的有界 LRU 缓存，key = "$bookUrl|${chapterUrl.trim()}" */
    private val chapterBadgeCache = LruCache<String, Set<String>>(CHAPTER_BADGE_CACHE_MAX_ENTRIES)

    /** 记录各 cacheKey 最近一次访问时间，用于 1 小时节流刷新 lastAccessAt */
    private val lastAccessAtCache = ConcurrentHashMap<String, Long>()

    /** 已完成旧目录只读导入的书（内存热标记） */
    private val importedBooks = ConcurrentHashMap.newKeySet<String>()

    /** 单书串行导入锁 */
    private val legacyImportLocks = ConcurrentHashMap<String, Any>()

    /** 避免主线程对同一章节重复派发角标预热任务 */
    private val badgePreloadInFlight = ConcurrentHashMap.newKeySet<String>()

    private val lastPruneAt = ConcurrentHashMap<String, Long>()

    /**
     * 章评 tab 补充快照的保留 buttonSrc：主键 = (真实章节 url, CHAPTER_TAB_SRC)，
     * 每章只存一份，与该章评论按钮的段评快照互不覆盖。
     */
    const val CHAPTER_TAB_SRC = "__chapter_tab__"

    /**
     * 书评 tab 补充快照的保留伪章节 url 与 buttonSrc：主键 = (BOOK_TAB_CHAPTER_URL,
     * BOOK_TAB_SRC)，整本书只存一份。伪章节 url 不对应任何真实章节，
     * 章节统计与按章导出必须排除。
     */
    const val BOOK_TAB_CHAPTER_URL = "__book_tab__"
    const val BOOK_TAB_SRC = "__book_tab__"

    /**
     * 快照写入观察者（IO 线程回调）：阅读页据此刷新段评气泡上的「已缓存」角标。
     * 只通知，不参与写入；实现方需自行切回主线程。
     */
    fun interface WriteObserver {
        fun onSnapshotWritten(bookUrl: String, chapterUrl: String, buttonSrc: String)
    }

    @Volatile
    private var writeObserver: WriteObserver? = null

    fun setWriteObserver(observer: WriteObserver?) {
        writeObserver = observer
    }

    private fun snapshotCacheKey(bookUrl: String, chapterUrl: String, buttonSrc: String): String {
        return "$bookUrl|${chapterUrl.trim()}|${buttonSrc.trim()}"
    }

    private fun badgeCacheKey(bookUrl: String, chapterUrl: String): String {
        return "$bookUrl|${chapterUrl.trim()}"
    }

    private fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    private fun legacyImportPrefs(): SharedPreferences {
        return appCtx.getSharedPreferences(LEGACY_IMPORT_PREFS, Context.MODE_PRIVATE)
    }

    private fun legacyImportPrefKey(bookUrl: String): String {
        return MD5Utils.md5Encode(bookUrl)
    }

    private fun isLegacyImported(bookUrl: String): Boolean {
        if (bookUrl in importedBooks) return true
        val persisted = runCatching {
            legacyImportPrefs().getBoolean(legacyImportPrefKey(bookUrl), false)
        }.getOrDefault(false)
        if (persisted) importedBooks.add(bookUrl)
        return persisted
    }

    private fun markLegacyImported(bookUrl: String) {
        if (bookUrl.isBlank()) return
        importedBooks.add(bookUrl)
        runCatching {
            legacyImportPrefs().edit().putBoolean(legacyImportPrefKey(bookUrl), true).apply()
        }
    }

    /**
     * 按书一次性只读导入旧 `reviews/r_*.json` 到 `review_cache`。
     * 主线程绝不执行读盘或写库，仅异步触发后立即返回。
     */
    fun ensureLegacyImported(book: Book) {
        if (book.bookUrl.isBlank()) return
        if (book.bookUrl in importedBooks) return
        if (isMainThread()) {
            ioScope.launch { ensureLegacyImportedOnIo(book) }
            return
        }
        ensureLegacyImportedOnIo(book)
    }

    private fun ensureLegacyImportedOnIo(book: Book) {
        val bookUrl = book.bookUrl
        if (bookUrl.isBlank() || isLegacyImported(bookUrl)) return
        val lock = legacyImportLocks.computeIfAbsent(bookUrl) { Any() }
        synchronized(lock) {
            if (isLegacyImported(bookUrl)) return
            val dir = runCatching { reviewsDir(book) }.getOrNull()
            if (dir == null || !dir.isDirectory) {
                markLegacyImported(bookUrl)
                return
            }
            val files = dir.listFiles()?.filter { it.isFile && isSnapshotFile(it) }.orEmpty()
            if (files.isEmpty()) {
                markLegacyImported(bookUrl)
                return
            }
            val now = System.currentTimeMillis()
            val batch = ArrayList<ReviewCache>(LEGACY_IMPORT_BATCH_SIZE)
            var importedCount = 0
            try {
                for (file in files) {
                    val snapshot = runCatching { readSnapshot(file) }.getOrNull() ?: continue
                    if (snapshot.bookUrl != bookUrl) continue
                    val chapterUrl = snapshot.chapterUrl.trim()
                    val buttonSrc = snapshot.buttonSrc.trim()
                    if (chapterUrl.isEmpty() || buttonSrc.isEmpty() || snapshot.html.isBlank()) continue
                    val savedAt = snapshot.savedAt.takeIf { it > 0L }
                        ?: file.lastModified().takeIf { it > 0L }
                        ?: now
                    batch.add(
                        ReviewCache(
                            cacheKey = ReviewCache.cacheKey(bookUrl, chapterUrl, buttonSrc),
                            bookUrl = bookUrl,
                            chapterUrl = chapterUrl,
                            chapterIndex = snapshot.chapterIndex,
                            chapterTitle = snapshot.chapterTitle,
                            buttonSrc = buttonSrc,
                            url = snapshot.url,
                            title = snapshot.title,
                            html = snapshot.html,
                            downloadEngine = snapshot.downloadEngine,
                            savedAt = savedAt,
                            lastAccessAt = savedAt,
                            sizeBytes = snapshot.html.toByteArray(Charsets.UTF_8).size.toLong(),
                        )
                    )
                    if (batch.size >= LEGACY_IMPORT_BATCH_SIZE) {
                        appDb.reviewCacheDao.insertIgnoreAll(batch)
                        importedCount += batch.size
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) {
                    appDb.reviewCacheDao.insertIgnoreAll(batch)
                    importedCount += batch.size
                    batch.clear()
                }
                if (importedCount > 0) {
                    invalidateBadgeCacheForBook(bookUrl)
                }
                markLegacyImported(bookUrl)
            } catch (error: Throwable) {
                AppLog.put("导入旧评论缓存失败：${book.name}\n${error.localizedMessage}", error)
            }
        }
    }

    /** 该 chapterUrl 是否为书评补充快照的伪章节身份 */
    fun isSupplementChapterUrl(url: String): Boolean = url.trim() == BOOK_TAB_CHAPTER_URL

    fun reviewsDir(book: Book): File {
        return File(BookHelp.getCacheDir(book), REVIEWS_DIR_NAME)
    }

    /** 兼容旧版文件名计算：以章节 URL + buttonSrc 为主键 */
    fun fileName(chapterUrl: String, buttonSrc: String): String {
        return "$FILE_PREFIX${MD5Utils.md5Encode16("${chapterUrl.trim()}|${buttonSrc.trim()}")}$FILE_SUFFIX"
    }

    fun isSnapshotFile(file: File): Boolean {
        return file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
    }

    internal fun hasPersistedReviewData(book: Book): Boolean {
        if (book.bookUrl.isBlank()) return false
        ensureLegacyImported(book)
        return runCatching {
            appDb.reviewCacheDao.totalBytesForBook(book.bookUrl) > 0L
        }.getOrDefault(false)
    }

    internal fun put(
        book: Book,
        snapshot: ReviewSnapshot,
        diagnostics: CacheOperationDiagnostics.Context? = null,
    ) {
        require(snapshot.html.isNotBlank()) { "review snapshot HTML must not be blank" }
        val trace = CacheOperationDiagnostics.begin(
            diagnostics?.forChapter(snapshot.chapterIndex)
                ?: CacheOperationDiagnostics.Context(
                    domain = CacheOperationDiagnostics.Domain.REVIEW,
                    chapterIndex = snapshot.chapterIndex,
                ),
            "SNAPSHOT_WRITE",
            CacheOperationDiagnostics.Metrics(inputChars = snapshot.html.length),
            startAlways = true,
        )
        try {
            require(book.bookUrl.isNotBlank()) { "review snapshot requires bookUrl" }
            require(snapshot.chapterUrl.isNotBlank()) { "review snapshot requires chapterUrl" }
            require(snapshot.buttonSrc.isNotBlank()) { "review snapshot requires buttonSrc" }
            val normalizedChapterUrl = snapshot.chapterUrl.trim()
            val normalizedButtonSrc = snapshot.buttonSrc.trim()
            val now = System.currentTimeMillis()
            val savedAt = snapshot.savedAt.takeIf { it > 0L } ?: now
            val sizeBytes = snapshot.html.toByteArray(Charsets.UTF_8).size.toLong()
            val dbKey = ReviewCache.cacheKey(book.bookUrl, normalizedChapterUrl, normalizedButtonSrc)
            val entity = ReviewCache(
                cacheKey = dbKey,
                bookUrl = book.bookUrl,
                chapterUrl = normalizedChapterUrl,
                chapterIndex = snapshot.chapterIndex,
                chapterTitle = snapshot.chapterTitle,
                buttonSrc = normalizedButtonSrc,
                url = snapshot.url,
                title = snapshot.title,
                html = snapshot.html,
                downloadEngine = snapshot.downloadEngine,
                savedAt = savedAt,
                lastAccessAt = now,
                sizeBytes = sizeBytes,
            )
            appDb.reviewCacheDao.upsert(entity)
            trace.done(CacheOperationDiagnostics.Metrics(outputBytes = sizeBytes))

            val cachedSnapshot = snapshot.copy(
                bookUrl = book.bookUrl,
                chapterUrl = normalizedChapterUrl,
                buttonSrc = normalizedButtonSrc,
                savedAt = savedAt,
            )
            snapshotCache.put(
                snapshotCacheKey(book.bookUrl, normalizedChapterUrl, normalizedButtonSrc),
                cachedSnapshot,
            )
            lastAccessAtCache[dbKey] = now

            if (!isSupplementChapterUrl(normalizedChapterUrl) &&
                normalizedButtonSrc != CHAPTER_TAB_SRC &&
                normalizedButtonSrc != BOOK_TAB_SRC
            ) {
                val badgeKey = badgeCacheKey(book.bookUrl, normalizedChapterUrl)
                synchronized(chapterBadgeCache) {
                    val existing = chapterBadgeCache.get(badgeKey)
                    if (existing != null && normalizedButtonSrc !in existing) {
                        chapterBadgeCache.put(badgeKey, existing + normalizedButtonSrc)
                    }
                }
            }

            maybePrune(book)
            // 通知阅读页把对应段评气泡标成「已缓存」。观察者异常不得影响写入结果
            runCatching {
                writeObserver?.onSnapshotWritten(
                    book.bookUrl, snapshot.chapterUrl, snapshot.buttonSrc
                )
            }
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
    }

    fun get(book: Book, chapter: BookChapter, buttonSrc: String): ReviewSnapshot? {
        return getByKey(book, chapter.url, buttonSrc)
    }

    /**
     * 内存查询（零 IO、不抛异常）：快照点击链路的热命中快速路径用。
     */
    fun getCached(book: Book, chapter: BookChapter, buttonSrc: String): ReviewSnapshot? {
        if (book.bookUrl.isBlank() || chapter.url.isBlank() || buttonSrc.isBlank()) return null
        return snapshotCache.get(snapshotCacheKey(book.bookUrl, chapter.url, buttonSrc))
    }

    /**
     * 存在性判定：内存命中直接返回，未命中走 Room `review_cache`。
     */
    fun hasSnapshot(book: Book, chapterUrl: String, buttonSrc: String): Boolean {
        if (book.bookUrl.isBlank() || chapterUrl.isBlank() || buttonSrc.isBlank()) return false
        val memKey = snapshotCacheKey(book.bookUrl, chapterUrl, buttonSrc)
        if (snapshotCache.get(memKey) != null) return true
        ensureLegacyImported(book)
        val dbKey = ReviewCache.cacheKey(book.bookUrl, chapterUrl, buttonSrc)
        return runCatching {
            appDb.reviewCacheDao.exists(dbKey) != null
        }.getOrDefault(false)
    }

    /**
     * 按主键读取（弹窗进程重建用：只有 Book 与主键字符串，没有章节实体）。
     */
    fun getByKey(book: Book, chapterUrl: String, buttonSrc: String): ReviewSnapshot? {
        val targetChapter = chapterUrl.trim()
        val targetButton = buttonSrc.trim()
        if (book.bookUrl.isBlank() || targetChapter.isEmpty() || targetButton.isEmpty()) return null
        val memKey = snapshotCacheKey(book.bookUrl, targetChapter, targetButton)
        val dbKey = ReviewCache.cacheKey(book.bookUrl, targetChapter, targetButton)
        snapshotCache.get(memKey)?.let { cached ->
            maybeTouchLastAccessAsync(dbKey, lastAccessAtCache[dbKey] ?: cached.savedAt)
            return cached
        }
        ensureLegacyImported(book)
        val entity = appDb.reviewCacheDao.get(dbKey) ?: return null
        val snapshot = entity.toSnapshot()
        snapshotCache.put(memKey, snapshot)
        lastAccessAtCache[dbKey] = entity.lastAccessAt
        maybeTouchLastAccessAsync(dbKey, entity.lastAccessAt)
        return snapshot
    }

    private fun maybeTouchLastAccessAsync(dbKey: String, lastAccessAt: Long) {
        val now = System.currentTimeMillis()
        if (now - lastAccessAt < LAST_ACCESS_TOUCH_INTERVAL_MS) return
        lastAccessAtCache[dbKey] = now
        ioScope.launch {
            runCatching { appDb.reviewCacheDao.touchLastAccessAt(dbKey, now) }
        }
    }

    /**
     * 导入缓存包里的评论快照目录（书籍缓存目录下的 reviews/）。
     *
     * 只读遍历其中的快照文件（[isSnapshotFile]），校验后直接 upsert 入 `review_cache`
     * （不再向旧 `reviews/` 目录复制文件）。
     *
     * @return 成功导入的快照数；归档目录不存在或为空时返回 0
     */
    fun restoreFromDirectory(book: Book, sourceDir: File): Int {
        if (book.bookUrl.isBlank() || !sourceDir.isDirectory) return 0
        val files = sourceDir.listFiles()?.filter { it.isFile && isSnapshotFile(it) }.orEmpty()
        if (files.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var restored = 0
        files.forEach { source ->
            runCatching {
                val snapshot = readSnapshot(source)
                    ?: error("review snapshot file is empty: ${source.absolutePath}")
                require(snapshot.bookUrl == book.bookUrl) {
                    "review snapshot bookUrl mismatch: ${source.name}"
                }
                val chapterUrl = snapshot.chapterUrl.trim()
                val buttonSrc = snapshot.buttonSrc.trim()
                require(chapterUrl.isNotEmpty()) { "review snapshot chapterUrl is blank: ${source.name}" }
                require(buttonSrc.isNotEmpty()) { "review snapshot buttonSrc is blank: ${source.name}" }
                val savedAt = snapshot.savedAt.takeIf { it > 0L }
                    ?: source.lastModified().takeIf { it > 0L }
                    ?: now
                val entity = ReviewCache(
                    cacheKey = ReviewCache.cacheKey(book.bookUrl, chapterUrl, buttonSrc),
                    bookUrl = book.bookUrl,
                    chapterUrl = chapterUrl,
                    chapterIndex = snapshot.chapterIndex,
                    chapterTitle = snapshot.chapterTitle,
                    buttonSrc = buttonSrc,
                    url = snapshot.url,
                    title = snapshot.title,
                    html = snapshot.html,
                    downloadEngine = snapshot.downloadEngine,
                    savedAt = savedAt,
                    lastAccessAt = now,
                    sizeBytes = snapshot.html.toByteArray(Charsets.UTF_8).size.toLong(),
                )
                appDb.reviewCacheDao.upsert(entity)
                restored++
            }.onFailure { error ->
                AppLog.put("导入评论缓存文件失败: ${source.name}\n${error.localizedMessage}", error)
            }
        }
        clearBookCache(book)
        markLegacyImported(book.bookUrl)
        return restored
    }

    /** 只清掉某本书的内存缓存（快照缓存与角标缓存），其余书不受影响 */
    private fun invalidateBookCache(bookUrl: String) {
        val prefix = "$bookUrl|"
        snapshotCache.snapshot().keys
            .filter { it.startsWith(prefix) }
            .forEach { snapshotCache.remove(it) }
        invalidateBadgeCacheForBook(bookUrl)
    }

    private fun invalidateBadgeCacheForBook(bookUrl: String) {
        val prefix = "$bookUrl|"
        synchronized(chapterBadgeCache) {
            chapterBadgeCache.snapshot().keys
                .filter { it.startsWith(prefix) }
                .forEach { chapterBadgeCache.remove(it) }
        }
    }

    /**
     * 清理单本书在评论快照存储中的内存缓存，
     * 供整书缓存清理联动调用。
     */
    fun clearBookCache(book: Book) {
        invalidateBookCache(book.bookUrl)
    }

    /**
     * 排版期按章节一次性批量获取已缓存的按钮 `buttonSrc` 集合（供 O(1) 内存比对）。
     * - 内存 LRU 命中直接返回；
     * - 主线程未命中时绝不查库，异步在 IO 线程预热并立即返回 emptySet()；
     * - 后台线程未命中时确保完成旧目录导入并查询 Room 后写入缓存。
     */
    fun getCachedButtonSrcSet(book: Book, chapterUrl: String): Set<String> {
        val targetChapter = chapterUrl.trim()
        if (book.bookUrl.isBlank() || targetChapter.isEmpty() || isSupplementChapterUrl(targetChapter)) {
            return emptySet()
        }
        val badgeKey = badgeCacheKey(book.bookUrl, targetChapter)
        synchronized(chapterBadgeCache) {
            chapterBadgeCache.get(badgeKey)
        }?.let { return it }

        if (isMainThread()) {
            if (badgePreloadInFlight.add(badgeKey)) {
                ioScope.launch {
                    try {
                        loadChapterBadgeSetOnIo(book, targetChapter, badgeKey)
                    } finally {
                        badgePreloadInFlight.remove(badgeKey)
                    }
                }
            }
            return emptySet()
        }

        return loadChapterBadgeSetOnIo(book, targetChapter, badgeKey)
    }

    private fun loadChapterBadgeSetOnIo(
        book: Book,
        targetChapter: String,
        badgeKey: String,
    ): Set<String> {
        ensureLegacyImportedOnIo(book)
        val loaded = runCatching {
            appDb.reviewCacheDao.buttonSrcsForChapter(book.bookUrl, targetChapter)
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it != CHAPTER_TAB_SRC && it != BOOK_TAB_SRC }
                .toHashSet()
        }.getOrElse { emptySet() }
        synchronized(chapterBadgeCache) {
            chapterBadgeCache.put(badgeKey, loaded)
        }
        return loaded
    }

    /**
     * 仅静默刷新元数据时间戳（用于 SNAPSHOT_FIRST 内容无实质变化时更新保鲜期）。
     * 不触发 writeObserver，避免已有气泡无效重绘。
     */
    fun touchSavedAt(book: Book, chapterUrl: String, buttonSrc: String, savedAt: Long): Boolean {
        if (book.bookUrl.isBlank() || chapterUrl.isBlank() || buttonSrc.isBlank() || savedAt <= 0L) {
            return false
        }
        return runCatching {
            val dbKey = ReviewCache.cacheKey(book.bookUrl, chapterUrl, buttonSrc)
            val updated = appDb.reviewCacheDao.touchSavedAt(dbKey, savedAt)
            if (updated <= 0) return false
            val memKey = snapshotCacheKey(book.bookUrl, chapterUrl, buttonSrc)
            snapshotCache.get(memKey)?.let { existing ->
                if (savedAt > existing.savedAt) {
                    snapshotCache.put(memKey, existing.copy(savedAt = savedAt))
                }
            }
            true
        }.getOrDefault(false)
    }

    /** Validate an extracted snapshot file. */
    @Suppress("UNUSED_PARAMETER")
    internal fun validateImportedSnapshot(book: Book, file: File): ReviewSnapshot {
        require(isSnapshotFile(file)) { "not a review snapshot file: ${file.absolutePath}" }
        val snapshot = readSnapshot(file)
            ?: error("review snapshot file is empty: ${file.absolutePath}")
        require(snapshot.bookUrl.isNotBlank()) { "review snapshot bookUrl is blank: ${file.absolutePath}" }
        require(snapshot.chapterUrl.isNotBlank()) { "review snapshot chapterUrl is blank: ${file.absolutePath}" }
        require(snapshot.buttonSrc.isNotBlank()) { "review snapshot buttonSrc is blank: ${file.absolutePath}" }
        return snapshot
    }

    private fun readSnapshot(file: File): ReviewSnapshot? {
        if (!file.isFile) return null
        return file.bufferedReader(Charsets.UTF_8).use { reader ->
            GSON.fromJson(reader, ReviewSnapshot::class.java)
        }
            ?.also { require(it.html.isNotBlank()) { "review snapshot HTML is blank: ${file.absolutePath}" } }
            ?: error("review snapshot file is empty: ${file.absolutePath}")
    }

    fun has(book: Book, chapter: BookChapter, buttonSrc: String): Boolean {
        return hasSnapshot(book, chapter.url, buttonSrc)
    }

    /**
     * 该按钮是否已有完整快照。
     */
    fun hasComplete(book: Book, chapter: BookChapter, buttonSrc: String): Boolean {
        if (book.bookUrl.isBlank() || chapter.url.isBlank() || buttonSrc.isBlank()) return false
        val memKey = snapshotCacheKey(book.bookUrl, chapter.url, buttonSrc)
        snapshotCache.get(memKey)?.let { return !it.partial }
        ensureLegacyImported(book)
        val dbKey = ReviewCache.cacheKey(book.bookUrl, chapter.url, buttonSrc)
        return runCatching {
            appDb.reviewCacheDao.exists(dbKey) != null
        }.getOrDefault(false)
    }

    fun delete(book: Book, chapter: BookChapter, buttonSrc: String) {
        if (book.bookUrl.isBlank() || chapter.url.isBlank() || buttonSrc.isBlank()) return
        val dbKey = ReviewCache.cacheKey(book.bookUrl, chapter.url, buttonSrc)
        appDb.reviewCacheDao.deleteByKey(dbKey)
        snapshotCache.remove(snapshotCacheKey(book.bookUrl, chapter.url, buttonSrc))
        lastAccessAtCache.remove(dbKey)
        synchronized(chapterBadgeCache) {
            chapterBadgeCache.remove(badgeCacheKey(book.bookUrl, chapter.url))
        }
    }

    /**
     * 删除该章节（含章内全部评论按钮）的快照。
     */
    fun deleteChapter(book: Book, chapter: BookChapter) {
        val targetChapter = chapter.url.trim()
        if (book.bookUrl.isBlank() || targetChapter.isEmpty()) return
        appDb.reviewCacheDao.deleteChapter(book.bookUrl, targetChapter)
        val keyPrefix = "${book.bookUrl}|$targetChapter|"
        snapshotCache.snapshot().keys
            .filter { it.startsWith(keyPrefix) }
            .forEach { snapshotCache.remove(it) }
        synchronized(chapterBadgeCache) {
            chapterBadgeCache.remove(badgeCacheKey(book.bookUrl, targetChapter))
        }
    }

    /**
     * 离线评论快照的保留策略：
     * 1. 删除超过 [RETENTION_DAYS] 天的过期条目（单书 + 全局）；
     * 2. 单书上限 [MAX_CACHE_BYTES]（32MB），超限时按 `lastAccessAt ASC, savedAt ASC` 淘汰至 90% 水位；
     * 3. 全局上限 [GLOBAL_MAX_CACHE_BYTES]（128MB），超限时按 `lastAccessAt ASC, savedAt ASC` 淘汰至 90% 水位。
     */
    fun prune(book: Book, now: Long = System.currentTimeMillis()): Int {
        val dao = appDb.reviewCacheDao
        val expireBefore = now - RETENTION_DAYS * 24L * 60 * 60 * 1000L
        var removed = 0
        var otherBooksAffected = false

        if (book.bookUrl.isNotBlank()) {
            removed += dao.deleteExpiredForBook(book.bookUrl, expireBefore)
        }
        val expiredGlobal = dao.deleteExpiredAll(expireBefore)
        if (expiredGlobal > 0) {
            removed += expiredGlobal
            otherBooksAffected = true
        }

        if (book.bookUrl.isNotBlank()) {
            var bookBytes = dao.totalBytesForBook(book.bookUrl)
            if (bookBytes > MAX_CACHE_BYTES) {
                val targetBookBytes = MAX_CACHE_BYTES * EVICT_WATERMARK_NUMERATOR / EVICT_WATERMARK_DENOMINATOR
                while (bookBytes > targetBookBytes) {
                    val candidates = dao.oldestEntriesForBook(book.bookUrl, EVICT_BATCH_SIZE)
                    if (candidates.isEmpty()) break
                    val keys = ArrayList<String>(candidates.size)
                    for (candidate in candidates) {
                        keys.add(candidate.cacheKey)
                        bookBytes -= candidate.sizeBytes
                        if (bookBytes <= targetBookBytes) break
                    }
                    if (keys.isEmpty()) break
                    val deleted = dao.deleteByKeys(keys)
                    keys.forEach { lastAccessAtCache.remove(it) }
                    removed += deleted
                    if (deleted <= 0) break
                }
            }
        }

        var globalBytes = dao.totalBytesAll()
        if (globalBytes > GLOBAL_MAX_CACHE_BYTES) {
            val targetGlobalBytes =
                GLOBAL_MAX_CACHE_BYTES * EVICT_WATERMARK_NUMERATOR / EVICT_WATERMARK_DENOMINATOR
            while (globalBytes > targetGlobalBytes) {
                val candidates = dao.oldestEntriesGlobal(EVICT_BATCH_SIZE)
                if (candidates.isEmpty()) break
                val keys = ArrayList<String>(candidates.size)
                for (candidate in candidates) {
                    keys.add(candidate.cacheKey)
                    globalBytes -= candidate.sizeBytes
                    if (globalBytes <= targetGlobalBytes) break
                }
                if (keys.isEmpty()) break
                val deleted = dao.deleteByKeys(keys)
                keys.forEach { lastAccessAtCache.remove(it) }
                removed += deleted
                if (deleted > 0) otherBooksAffected = true
                if (deleted <= 0) break
            }
        }

        if (removed > 0) {
            if (otherBooksAffected) {
                snapshotCache.evictAll()
                synchronized(chapterBadgeCache) {
                    chapterBadgeCache.evictAll()
                }
            } else if (book.bookUrl.isNotBlank()) {
                invalidateBookCache(book.bookUrl)
            }
        }
        return removed
    }

    /**
     * 单书快照总占用字节数，供缓存管理页展示。
     */
    fun totalBytes(book: Book): Long {
        if (book.bookUrl.isBlank()) return 0L
        ensureLegacyImported(book)
        return appDb.reviewCacheDao.totalBytesForBook(book.bookUrl)
    }

    /**
     * 全局评论快照总占用字节数，供评论设置页展示。
     */
    fun totalBytesAll(): Long {
        return appDb.reviewCacheDao.totalBytesAll()
    }

    /**
     * 清空单书全部评论缓存：先标记旧目录已导入防止复活，再删库并失效内存缓存。
     * 不删除磁盘旧 `reviews/` 目录文件。
     */
    fun clearAll(book: Book): Int {
        if (book.bookUrl.isBlank()) return 0
        markLegacyImported(book.bookUrl)
        val removed = appDb.reviewCacheDao.deleteByBook(book.bookUrl)
        clearBookCache(book)
        return removed
    }

    /**
     * 清空全部书籍的评论缓存：删库并清空内存缓存。
     * 不删除磁盘旧 `reviews/` 目录文件。
     */
    fun clearAllGlobal(): Int {
        val removed = appDb.reviewCacheDao.deleteAll()
        snapshotCache.evictAll()
        synchronized(chapterBadgeCache) {
            chapterBadgeCache.evictAll()
        }
        lastAccessAtCache.clear()
        return removed
    }

    /** 同一本书最多每 [PRUNE_INTERVAL_MS] 清理一次，避免高频触发淘汰查询。 */
    private fun maybePrune(book: Book) {
        val now = System.currentTimeMillis()
        val last = lastPruneAt.putIfAbsent(book.bookUrl, now)
        if (last != null && now - last < PRUNE_INTERVAL_MS) return
        if (last != null) lastPruneAt[book.bookUrl] = now
        runCatching { prune(book, now) }
            .onSuccess { count ->
                if (count > 0) AppLog.put("清理过期/超额评论快照 $count 个：${book.name}")
            }
            .onFailure { AppLog.put("清理评论快照失败：${book.name}", it) }
    }

    /** 统计已缓存评论的真实章节 URL 集合（排除书评伪章节）。 */
    fun chapterUrls(book: Book, checkActive: () -> Unit = {}): Set<String> {
        checkActive()
        if (book.bookUrl.isBlank()) return emptySet()
        ensureLegacyImported(book)
        checkActive()
        return appDb.reviewCacheDao.chapterUrlsForBook(book.bookUrl)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !isSupplementChapterUrl(it) }
            .toSet()
    }

    /** 导出该书 `review_cache` 快照为 `r_*.json` 到目标目录（兼容缓存包导出接口）。 */
    fun copyAllTo(
        book: Book,
        targetDir: File,
        onIssue: (item: String, error: Throwable) -> Unit = { _, _ -> },
    ): String? {
        return exportFromDb(book, targetDir, selectedChapterUrls = null, onIssue = onIssue)
    }

    /** 导出指定章节的 `review_cache` 快照为 `r_*.json` 到目标目录（兼容缓存包导出接口）。 */
    fun copyChaptersTo(
        book: Book,
        targetDir: File,
        chapters: Collection<BookChapter>,
        onIssue: (item: String, error: Throwable) -> Unit = { _, _ -> },
    ): String? {
        val chapterUrls = chapters.mapNotNullTo(linkedSetOf()) { chapter ->
            chapter.url.trim().takeIf(String::isNotBlank) ?: run {
                onIssue(
                    appCtx.getString(R.string.misc_chapter_4, chapter.index + 1, chapter.title),
                    IllegalArgumentException("评论导出章节缺少 chapterUrl"),
                )
                null
            }
        }
        return exportFromDb(book, targetDir, selectedChapterUrls = chapterUrls, onIssue = onIssue)
    }

    private fun exportFromDb(
        book: Book,
        targetDir: File,
        selectedChapterUrls: Set<String>?,
        onIssue: (item: String, error: Throwable) -> Unit,
    ): String? {
        if (book.bookUrl.isBlank()) return appCtx.getString(R.string.misc_no_cached_review_snapshot)
        ensureLegacyImported(book)
        val entries = appDb.reviewCacheDao.listByBook(book.bookUrl).filter { entry ->
            selectedChapterUrls == null ||
                isSupplementChapterUrl(entry.chapterUrl) ||
                entry.chapterUrl.trim() in selectedChapterUrls
        }
        if (entries.isEmpty()) return appCtx.getString(R.string.misc_no_cached_review_snapshot)
        check(targetDir.isDirectory || targetDir.mkdirs()) {
            "无法创建评论快照导出目录: ${targetDir.absolutePath}"
        }
        var failed = 0
        entries.forEach { entry ->
            val name = fileName(entry.chapterUrl, entry.buttonSrc)
            val target = File(targetDir, name)
            try {
                writeJsonAtomically(target) { writer ->
                    GSON.toJson(entry.toSnapshot(), writer)
                }
            } catch (error: Throwable) {
                failed++
                target.delete()
                onIssue(name, error)
            }
        }
        return if (failed > 0) "评论缓存有 $failed 个文件未能导出，其余文件已导出" else null
    }

    private fun writeJsonAtomically(target: File, write: (java.io.Writer) -> Unit) {
        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            write(writer)
            writer.flush()
            atomicFile.finishWrite(stream)
            output = null
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun ReviewCache.toSnapshot(): ReviewSnapshot {
        return ReviewSnapshot(
            version = 2,
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            buttonSrc = buttonSrc,
            url = url,
            title = title,
            html = html,
            resourceKeys = emptyList(),
            partial = false,
            savedAt = savedAt,
            downloadEngine = downloadEngine,
            resourceFailures = emptyList(),
        )
    }
}
