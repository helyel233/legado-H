package io.legado.app.help.review

import android.util.AtomicFile
import androidx.collection.LruCache
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.constant.AppLog
import io.legado.app.help.book.BookHelp
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
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
     * 本快照 HTML 引用的全部资源库 key（review-resource://<key> 对应的 key）。
     * 抓取生成快照时顺手写入；GC 只读本字段判定存活资源，不再扫描巨大 HTML。
     * null = 旧格式快照（写入时没有该字段），引用未知，GC 必须放弃本次回收。
     */
    val resourceKeys: List<String>? = null,
    /**
     * 部分成功快照：页面 HTML 已抓到并落盘，但存在下载失败的资源（缺失引用以
     * # 占位）。部分快照可离线渲染，但在计数与重试判定中不等同于完整快照，
     * 对应按钮仍计为失败，等待重新抓取覆盖。
     */
    val partial: Boolean = false,
    val savedAt: Long = 0L,
    val downloadEngine: String = "web",
    /** 包括按用户完整性策略忽略的失败；不能把成功状态当作资源齐全。 */
    val resourceFailures: List<String> = emptyList(),
)

/**
 * Durable result for the latest review-cache attempt of a chapter.
 *
 * Snapshot files only exist after a successful capture, so they cannot describe
 * buttons that failed to capture. This sidecar keeps that result explicit for
 * cache management and retry actions.
 */
data class ReviewChapterSnapshotStatus(
    val version: Int = 2,
    val bookUrl: String = "",
    val chapterUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val totalSnapshots: Int = 0,
    val failedSnapshots: Int = 0,
    /** Stable identities of the failed buttons; required for an exact retry. */
    val failedButtonSources: List<String>? = null,
    val updatedAt: Long = 0L,
) {
    /**
     * A count alone cannot identify which bubble is safe to retry. Older status files without
     * these identities remain visible, but are deliberately not eligible for a broad retry.
     */
    fun failedButtonSourcesForRetry(): List<String>? {
        val sources = failedButtonSources.orEmpty().map(String::trim)
        return sources.takeIf {
            failedSnapshots > 0 &&
                it.size == failedSnapshots &&
                it.none(String::isBlank) &&
                it.distinct().size == it.size
        }
    }
}

data class ReviewSnapshotCounts(
    private val byChapterUrl: Map<String, Int>,
) {
    fun forChapter(chapter: BookChapter): Int {
        return byChapterUrl[chapter.url.trim()] ?: 0
    }
}

/**
 * 评论页快照存储。
 *
 * 存储位置：<book_cache>/<book folder>/reviews/r_<md5(chapterUrl|buttonSrc)>.json
 * 快照主键 = 章节 URL + 评论按钮 src，与正文缓存相互独立：
 * 正文已缓存绝不代表评论快照已存在，“是否需要补评论”必须单独按键检查。
 */
object ReviewSnapshotStore {

    const val REVIEWS_DIR_NAME = "reviews"
    private const val FILE_PREFIX = "r_"
    private const val STATUS_FILE_PREFIX = "s_"
    private const val FILE_SUFFIX = ".json"

    /**
     * 段评快照内存缓存（点击即读路径专用）。
     *
     * 快照 JSON 含整份渲染 HTML（内联资源，几十 KB~2MB），每次点击都重新
     * 读文件 + GSON 解析是快照模式剩余延迟的最大单点。按内容哈希容量缓存
     * 解析结果后，命中即零文件 IO、零解析。
     *
     * 一致性：写入只经 [put]（原子写 + 同步刷新缓存）；外部删除缓存目录后
     * [get] 的 isFile 前置检查自然 miss，不会返回已删快照。
     */
    private val snapshotCache = object : LruCache<String, ReviewSnapshot>(SNAPSHOT_CACHE_MAX_CHARS) {
        override fun sizeOf(key: String, value: ReviewSnapshot): Int = value.html.length
    }

    /** 约 12MB 字符上限：覆盖预热带 3 章窗口 + 近期点击章，超出按 LRU 淘汰 */
    private const val SNAPSHOT_CACHE_MAX_CHARS = 12 * 1024 * 1024

    private fun snapshotCacheKey(bookUrl: String, chapterUrl: String, buttonSrc: String): String {
        return "$bookUrl|$chapterUrl|$buttonSrc"
    }

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
    /** 离线评论快照保留天数（与 TTL 无关：TTL 只决定是否重新抓取）。 */
    private const val RETENTION_DAYS = 30

    /** 单书离线评论快照总大小上限，超出按最旧优先清理。 */
    private const val MAX_CACHE_BYTES = 32L * 1024 * 1024

    private const val PRUNE_INTERVAL_MS = 10 * 60 * 1000L

    private val lastPruneAt = ConcurrentHashMap<String, Long>()

    const val BOOK_TAB_CHAPTER_URL = "__book_tab__"
    const val BOOK_TAB_SRC = "__book_tab__"

    /** 该 chapterUrl 是否为书评补充快照的伪章节身份 */
    fun isSupplementChapterUrl(url: String): Boolean = url.trim() == BOOK_TAB_CHAPTER_URL

    fun reviewsDir(book: Book): File {
        return File(BookHelp.getCacheDir(book), REVIEWS_DIR_NAME)
    }

    /** 新版文件名：以章节 URL 为主键 */
    fun fileName(chapterUrl: String, buttonSrc: String): String {
        return "$FILE_PREFIX${MD5Utils.md5Encode16("${chapterUrl.trim()}|${buttonSrc.trim()}")}$FILE_SUFFIX"
    }

    private fun statusFileName(chapterUrl: String): String {
        return "$STATUS_FILE_PREFIX${MD5Utils.md5Encode16(chapterUrl.trim())}$FILE_SUFFIX"
    }

    fun isSnapshotFile(file: File): Boolean {
        return file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
    }

    private fun reviewFiles(book: Book): Array<File> {
        return reviewsDir(book).listFiles()
            ?.filter(::isSnapshotFile)
            ?.toTypedArray()
            ?: emptyArray()
    }

    private fun statusFiles(book: Book): Array<File> {
        return reviewsDir(book).listFiles()
            ?.filter { it.name.startsWith(STATUS_FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.toTypedArray()
            ?: emptyArray()
    }

    internal fun hasPersistedReviewData(book: Book): Boolean {
        return reviewFiles(book).isNotEmpty() || statusFiles(book).isNotEmpty()
    }

    private fun requireCurrentFormatIfReviewData(book: Book) {
        if (hasPersistedReviewData(book)) {
            ReviewSnapshotResourceStore.requireDatabase(book)
        }
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
        val dir = reviewsDir(book)
        try {
            check(dir.exists() || dir.mkdirs()) {
                "cannot create review snapshot directory: ${dir.absolutePath}"
            }
            require(snapshot.chapterUrl.isNotBlank()) { "review snapshot requires chapterUrl" }
            require(snapshot.buttonSrc.isNotBlank()) { "review snapshot requires buttonSrc" }
            require(snapshot.resourceKeys != null) { "review snapshot requires resourceKeys" }
            ReviewSnapshotResourceStore.requireDatabase(book)
            ReviewSnapshotResourceStore.validateSnapshot(book, snapshot)
            val name = fileName(snapshot.chapterUrl, snapshot.buttonSrc)
            val target = File(dir, name)
            // 快照 HTML 可能很大。Gson 直接写入 Writer，避免先构造整份 JSON String 和 UTF-8
            // ByteArray；它们会在原 HTML 仍存活时额外复制完整快照，放大 Java heap 峰值。
            writeJsonAtomically(target) { writer -> GSON.toJson(snapshot, writer) }
            trace.done(CacheOperationDiagnostics.Metrics(outputBytes = target.length()))
            // 写盘成功即刷新内存缓存：下次点击同一按钮零读文件、零解析
            snapshotCache.put(
                snapshotCacheKey(book.bookUrl, snapshot.chapterUrl, snapshot.buttonSrc),
                snapshot
            )
            maybePrune(book)
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
    }

    fun get(book: Book, chapter: BookChapter, buttonSrc: String): ReviewSnapshot? {
        requireCurrentFormatIfReviewData(book)
        val dir = reviewsDir(book)
        val file = File(dir, fileName(chapter.url, buttonSrc))
        // 先查文件存在性：缓存目录被删（删书缓存/清理）时自然 miss，不会返回陈旧快照
        if (!file.isFile) return null
        val cacheKey = snapshotCacheKey(book.bookUrl, chapter.url, buttonSrc)
        snapshotCache.get(cacheKey)?.let { return it }
        val snapshot = readCompleteSnapshot(book, file)
        require(snapshot.chapterUrl.trim() == chapter.url.trim()) {
            "review snapshot chapterUrl mismatch: ${file.absolutePath}"
        }
        require(snapshot.buttonSrc.trim() == buttonSrc.trim()) {
            "review snapshot buttonSrc mismatch: ${file.absolutePath}"
        }
        snapshotCache.put(cacheKey, snapshot)
        return snapshot
    }

    /**
     * 内存查询（只一次 stat，不解析、不抛异常）：快照点击链路的热命中快速路径用。
     * 命中说明该按钮快照已被 [get] 或 [put] 加载/写入过，可直接弹窗。
     *
     * 必须校验文件仍然存在：缓存不随「删除书缓存」失效，若只信内存，
     * 删缓存后点击仍会打开已删除的快照（且绕过了 [get] 的 isFile 前置）。
     */
    fun getCached(book: Book, chapter: BookChapter, buttonSrc: String): ReviewSnapshot? {
        val file = File(reviewsDir(book), fileName(chapter.url, buttonSrc))
        if (!file.isFile) {
            snapshotCache.remove(snapshotCacheKey(book.bookUrl, chapter.url, buttonSrc))
            return null
        }
        return snapshotCache.get(snapshotCacheKey(book.bookUrl, chapter.url, buttonSrc))
    }

    /**
     * 按主键读取（弹窗进程重建用：只有 Book 与主键字符串，没有章节实体）。
     * 与 [get] 同路径，校验放宽为主键相等（chapterUrl/buttonSrc 即主键）。
     */
    fun getByKey(book: Book, chapterUrl: String, buttonSrc: String): ReviewSnapshot? {
        requireCurrentFormatIfReviewData(book)
        val file = File(reviewsDir(book), fileName(chapterUrl, buttonSrc))
        if (!file.isFile) return null
        val cacheKey = snapshotCacheKey(book.bookUrl, chapterUrl, buttonSrc)
        snapshotCache.get(cacheKey)?.let { return it }
        val snapshot = readCompleteSnapshot(book, file)
        require(snapshot.chapterUrl.trim() == chapterUrl.trim()) {
            "review snapshot chapterUrl mismatch: ${file.absolutePath}"
        }
        require(snapshot.buttonSrc.trim() == buttonSrc.trim()) {
            "review snapshot buttonSrc mismatch: ${file.absolutePath}"
        }
        snapshotCache.put(cacheKey, snapshot)
        return snapshot
    }

    /** 读取该章的章评 tab 补充快照；缺失或校验失败返回 null */
    fun getChapterTab(book: Book, chapter: BookChapter): ReviewSnapshot? {
        return runCatching { get(book, chapter, CHAPTER_TAB_SRC) }.getOrNull()
    }

    /** 读取该书的书评 tab 补充快照（伪章节主键）；缺失或校验失败返回 null */
    fun getBookTab(book: Book): ReviewSnapshot? {
        requireCurrentFormatIfReviewData(book)
        val file = File(reviewsDir(book), fileName(BOOK_TAB_CHAPTER_URL, BOOK_TAB_SRC))
        if (!file.isFile) return null
        return runCatching { readCompleteSnapshot(book, file) }.getOrNull()
    }

    /** Validate an extracted snapshot against the already-imported resource database. */
    internal fun validateImportedSnapshot(book: Book, file: File): ReviewSnapshot {
        require(isSnapshotFile(file)) { "not a review snapshot file: ${file.absolutePath}" }
        val snapshot = readSnapshot(file)
            ?: error("review snapshot file is empty: ${file.absolutePath}")
        require(snapshot.bookUrl.isNotBlank()) { "review snapshot bookUrl is blank: ${file.absolutePath}" }
        require(snapshot.chapterUrl.isNotBlank()) { "review snapshot chapterUrl is blank: ${file.absolutePath}" }
        require(snapshot.buttonSrc.isNotBlank()) { "review snapshot buttonSrc is blank: ${file.absolutePath}" }
        ReviewSnapshotResourceStore.validateSnapshot(book, snapshot)
        return snapshot
    }

    private fun readCompleteSnapshot(book: Book, file: File): ReviewSnapshot {
        val snapshot = readSnapshot(file)
            ?: error("review snapshot file is empty: ${file.absolutePath}")
        require(snapshot.bookUrl == book.bookUrl) {
            "review snapshot bookUrl mismatch: ${file.absolutePath}"
        }
        ReviewSnapshotResourceStore.validateSnapshot(book, snapshot)
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
        return readOwnSnapshotMetadata(book, chapter, buttonSrc) != null
    }

    /**
     * 该按钮是否已有完整快照。部分成功快照（partial）可离线渲染但不完整，
     * 在跳过/重试/进度基线判定中必须视为未完成，等待重新抓取覆盖。
     */
    fun hasComplete(book: Book, chapter: BookChapter, buttonSrc: String): Boolean {
        val metadata = readOwnSnapshotMetadata(book, chapter, buttonSrc) ?: return false
        return !metadata.partial
    }

    /**
     * 补充快照（章评/书评 tab）按显式 (chapterUrl, buttonSrc) 按键读取元数据。
     * 与 [readOwnSnapshotMetadata] 的差异仅在于不需要 BookChapter 实体：
     * 书评补充快照挂在伪章节 url 上，整本书只有一份。
     */
    private fun readSupplementMetadata(
        book: Book,
        chapterUrl: String,
        buttonSrc: String,
    ): ReviewSnapshotHotMetadata? {
        requireCurrentFormatIfReviewData(book)
        val file = File(reviewsDir(book), fileName(chapterUrl, buttonSrc))
        if (!file.isFile) return null
        val metadata = readHotMetadata(book, file)
        require(metadata.chapterUrl.trim() == chapterUrl.trim()) {
            "review snapshot chapterUrl mismatch: ${file.absolutePath}"
        }
        require(metadata.buttonSrc.trim() == buttonSrc.trim()) {
            "review snapshot buttonSrc mismatch: ${file.absolutePath}"
        }
        return metadata
    }

    /** 该章的章评 tab 补充快照是否已完整存在 */
    fun hasCompleteChapterTab(book: Book, chapter: BookChapter): Boolean {
        val metadata = readSupplementMetadata(book, chapter.url, CHAPTER_TAB_SRC) ?: return false
        return !metadata.partial
    }

    /** 该书的书评 tab 补充快照是否已完整存在 */
    fun hasCompleteBookTab(book: Book): Boolean {
        val metadata = readSupplementMetadata(book, BOOK_TAB_CHAPTER_URL, BOOK_TAB_SRC)
            ?: return false
        return !metadata.partial
    }

    /** 读取并校验属于 [chapter]/[buttonSrc] 的快照热元数据；文件不存在返回 null。 */
    private fun readOwnSnapshotMetadata(
        book: Book,
        chapter: BookChapter,
        buttonSrc: String,
    ): ReviewSnapshotHotMetadata? {
        requireCurrentFormatIfReviewData(book)
        val file = File(reviewsDir(book), fileName(chapter.url, buttonSrc))
        if (!file.isFile) return null
        val metadata = readHotMetadata(book, file)
        require(metadata.chapterUrl.trim() == chapter.url.trim()) {
            "review snapshot chapterUrl mismatch: ${file.absolutePath}"
        }
        require(metadata.buttonSrc.trim() == buttonSrc.trim()) {
            "review snapshot buttonSrc mismatch: ${file.absolutePath}"
        }
        return metadata
    }

    fun delete(book: Book, chapter: BookChapter, buttonSrc: String) {
        File(reviewsDir(book), fileName(chapter.url, buttonSrc)).delete()
        invalidateChapterCount(book.bookUrl)
    }

    /**
     * 删除该章节（含章内全部评论按钮）的快照。
     * 主键是整体 md5，无法按文件名前缀过滤，因此逐条解析后按 chapterUrl 匹配。
     */
    fun deleteChapter(book: Book, chapter: BookChapter) {
        reviewFiles(book).forEach { file ->
            readMetadata(file)?.let { snapshot ->
                val matches = snapshot.chapterUrl.trim() == chapter.url.trim()
                if (matches) file.delete()
            }
        }
        File(reviewsDir(book), statusFileName(chapter.url)).delete()
        invalidateChapterCount(book.bookUrl)
    }

    /**
     * 离线评论快照的保留策略：超过 [RETENTION_DAYS] 天未更新的快照删除；单书总量超过
     * [MAX_CACHE_BYTES] 时按最旧优先删到上限内。
     *
     * 之前 TTL 只用于「是否过期需重新抓取」，从不删文件，快照目录只增不减。
     * 这里只动快照文件本身，状态文件与资源库由各自的回收逻辑负责：
     * 资源 blob 的孤儿回收是 [ReviewSnapshotResourceStore.gc]，由缓存管线在该书最后一个
     * REVIEW 任务终态时触发，存活判定只看「是否仍被某个快照引用(快照的 resourceKeys)」，
     * 因此这里删掉的快照不会造成「索引在而文件缺失」，只会让相关 blob 变成孤儿等待下次 GC。
     *
     * 内存缓存不必逐条失效：[get]/[getCached] 都以 isFile 前置检查为准，
     * 文件被删后命中即失效并顺手清理缓存条目，不会返回已删除的快照。
     */
    fun prune(book: Book, now: Long = System.currentTimeMillis()): Int {
        val files = reviewFiles(book)
        if (files.isEmpty()) return 0
        val expireBefore = now - RETENTION_DAYS * 24L * 60 * 60 * 1000
        var removed = 0
        val remaining = ArrayList<File>(files.size)
        files.forEach { file ->
            val modified = file.lastModified()
            if (modified in 1 until expireBefore) {
                if (file.delete()) removed++ else remaining.add(file)
            } else {
                remaining.add(file)
            }
        }
        if (removed > 0) invalidateChapterCount(book.bookUrl)
        var total = remaining.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return removed
        remaining.sortBy { it.lastModified() }
        for (file in remaining) {
            if (total <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                removed++
            }
        }
        return removed
    }

    /** 快照被删除后，让内存里的「有快照章数」计数失效，下次查询重新按目录统计。 */
    private fun invalidateChapterCount(bookUrl: String) {
        ReviewSnapshotManager.invalidateCachedReviewChapters(bookUrl)
    }

    /** 同一本书最多每 [PRUNE_INTERVAL_MS] 清理一次，避免每写一章都扫目录。 */
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

    /** 管理统计使用目录索引，书评补充快照不计入章节。 */
    fun chapterUrls(book: Book, checkActive: () -> Unit = {}): Set<String> {
        return ReviewSnapshotInventory.read(book, checkActive).files.values.asSequence()
            .filter { it.status == null }
            .map { it.chapterUrl }
            .filterNot(::isSupplementChapterUrl)
            .toSet()
    }

    /** Counts persisted snapshots without reading their potentially huge HTML fields. */
    fun snapshotCounts(book: Book): ReviewSnapshotCounts {
        return managementState(book).first
    }

    /** 一次索引查询同时提供快照计数和状态，避免详情页重复遍历。 */
    fun managementState(
        book: Book,
        checkActive: () -> Unit = {},
    ): Pair<ReviewSnapshotCounts, List<ReviewChapterSnapshotStatus>> {
        val entries = ReviewSnapshotInventory.read(book, checkActive).files.values
        val counts = entries.asSequence()
            .filter { it.status == null && !isSupplementChapterUrl(it.chapterUrl) }
            .groupingBy { it.chapterUrl }.eachCount()
        return ReviewSnapshotCounts(counts) to entries.mapNotNull { it.status }
    }

    /** Latest completed capture result for every chapter that has been attempted. */
    fun chapterStatuses(book: Book): List<ReviewChapterSnapshotStatus> {
        requireCurrentFormatIfReviewData(book)
        return statusFiles(book).mapNotNull(::readChapterStatus)
    }

    fun chapterStatus(book: Book, chapter: BookChapter): ReviewChapterSnapshotStatus? {
        requireCurrentFormatIfReviewData(book)
        return readChapterStatus(File(reviewsDir(book), statusFileName(chapter.url)))
    }

    fun isChapterStatusFile(file: File): Boolean {
        return file.name.startsWith(STATUS_FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
    }

    fun readChapterStatus(file: File): ReviewChapterSnapshotStatus? {
        if (!isChapterStatusFile(file)) return null
        return file.bufferedReader(Charsets.UTF_8).use { reader ->
            GSON.fromJson(reader, ReviewChapterSnapshotStatus::class.java)
        }?.also { status ->
            require(status.chapterUrl.isNotBlank()) {
                "review chapter status is missing chapterUrl: ${file.absolutePath}"
            }
            require(status.totalSnapshots > 0) {
                "review chapter status has invalid totalSnapshots: ${file.absolutePath}"
            }
            require(status.failedSnapshots in 0..status.totalSnapshots) {
                "review chapter status has invalid failedSnapshots: ${file.absolutePath}"
            }
            if (status.version >= 2) {
                val failedSources = requireNotNull(status.failedButtonSources) {
                    "review chapter status version 2 requires failed button identities"
                }
                require(status.failedSnapshots == failedSources.size) {
                    "review chapter status failed button identities are incomplete"
                }
                require(failedSources.all { it.isNotBlank() }) {
                    "review chapter status contains blank failed button identity"
                }
                require(failedSources.distinct().size == failedSources.size) {
                    "review chapter status contains duplicate failed button identity"
                }
            }
        } ?: error("review chapter status file is empty: ${file.absolutePath}")
    }

    /** Persists status independently of the successful snapshot payloads. */
    fun putChapterStatus(book: Book, status: ReviewChapterSnapshotStatus) {
        require(status.chapterUrl.isNotBlank()) { "review status requires chapterUrl" }
        require(status.totalSnapshots > 0) { "review status requires totalSnapshots" }
        require(status.failedSnapshots in 0..status.totalSnapshots) {
            "review status failedSnapshots is outside totalSnapshots"
        }
        if (status.version >= 2) {
            val failedSources = requireNotNull(status.failedButtonSources) {
                "review status version 2 requires failed button identities"
            }
            require(status.failedSnapshots == failedSources.size) {
                "review status failed button identities are incomplete"
            }
            require(failedSources.all { it.isNotBlank() }) {
                "review status contains blank failed button identity"
            }
            require(failedSources.distinct().size == failedSources.size) {
                "review status contains duplicate failed button identity"
            }
        }
        ReviewSnapshotResourceStore.requireDatabase(book)
        val dir = reviewsDir(book)
        if (!dir.exists()) check(dir.mkdirs()) { "cannot create review status directory: ${dir.absolutePath}" }
        writeJsonAtomically(File(dir, statusFileName(status.chapterUrl))) { writer ->
            GSON.toJson(status, writer)
        }
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

    /** 按原文件字节流导出，避免“读取所有快照 -> 重新序列化所有快照”的全量内存占用。 */
    fun copyAllTo(
        book: Book,
        targetDir: File,
        onIssue: (item: String, error: Throwable) -> Unit = { _, _ -> },
    ): String? {
        return copyTo(book, targetDir, selectedChapterUrls = null, onIssue = onIssue)
    }

    /**
     * Exports review artifacts owned by [chapters] only. The snapshot/status files and their
     * resource library are selected by the same stable chapterUrl set.
     */
    fun copyChaptersTo(
        book: Book,
        targetDir: File,
        chapters: Collection<BookChapter>,
        onIssue: (item: String, error: Throwable) -> Unit = { _, _ -> },
    ): String? {
        val chapterUrls = chapters.mapNotNullTo(linkedSetOf()) { chapter ->
            chapter.url.trim().takeIf(String::isNotBlank) ?: run {
                onIssue(
                    "第${chapter.index + 1}章 ${chapter.title}",
                    IllegalArgumentException("评论导出章节缺少 chapterUrl"),
                )
                null
            }
        }
        return copyTo(book, targetDir, selectedChapterUrls = chapterUrls, onIssue = onIssue)
    }

    private fun copyTo(
        book: Book,
        targetDir: File,
        selectedChapterUrls: Set<String>?,
        onIssue: (item: String, error: Throwable) -> Unit,
    ): String? {
        val sourceDir = reviewsDir(book)
        val sources = sourceDir.walkTopDown().onFail { file, error ->
            onIssue(file.relativeToOrSelf(sourceDir).path, error)
        }.filter(File::isFile).filter { file ->
            if (selectedChapterUrls == null || !isSnapshotFile(file) && !isChapterStatusFile(file)) {
                true
            } else {
                runCatching {
                    val chapterUrl = if (isSnapshotFile(file)) {
                        requireNotNull(readMetadata(file)).chapterUrl
                    } else {
                        requireNotNull(readChapterStatus(file)).chapterUrl
                    }
                    isSupplementChapterUrl(chapterUrl) || chapterUrl.trim() in selectedChapterUrls
                }.getOrElse { error ->
                    // 无法识别归属的原始文件仍然出包，同时明确记录无法筛选的原因。
                    onIssue(file.name, error)
                    true
                }
            }
        }.toList()
        if (sources.isEmpty()) return "没有已缓存的评论快照"
        check(targetDir.isDirectory || targetDir.mkdirs()) {
            "无法创建评论快照导出目录: ${targetDir.absolutePath}"
        }
        var failed = 0
        sources.forEach { source ->
            val relative = source.relativeTo(sourceDir)
            val target = File(targetDir, relative.path)
            try {
                val expected = source.length()
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                check(expected == source.length() && target.length() == expected) {
                    "评论缓存文件在复制期间变化或复制不完整"
                }
            } catch (error: Throwable) {
                failed++
                target.delete()
                onIssue(relative.path, error)
                return@forEach
            }
            // 原始字节已经可靠复制。后续解析只用于把已知的不完整状态写进报告，
            // 解析失败不能反过来删除这个仍有逃逸价值的原始文件。
            try {
                when {
                    isSnapshotFile(source) -> readMetadata(source)?.takeIf { it.partial }?.let {
                        onIssue(relative.path, IllegalStateException("原缓存是部分快照"))
                    }
                    isChapterStatusFile(source) -> readChapterStatus(source)
                        ?.takeIf { it.failedSnapshots > 0 }
                        ?.let { status ->
                            onIssue(
                                relative.path,
                                IllegalStateException("记录了 ${status.failedSnapshots} 项抓取失败"),
                            )
                        }
                }
            } catch (error: Throwable) {
                onIssue(relative.path, error)
            }
        }
        return if (failed > 0) "评论缓存有 $failed 个文件未能复制，其余文件已原样导出" else null
    }

    private fun readMetadata(file: File): ReviewSnapshotHotMetadata? {
        if (!file.isFile) return null
        return file.bufferedReader(Charsets.UTF_8).use(::readReviewSnapshotHotMetadata)
    }

    /** Read only metadata/resourceKeys; the HTML value is skipped by JsonReader. */
    private fun readHotMetadata(book: Book, file: File): ReviewSnapshotHotMetadata {
        val metadata = readMetadata(file)
            ?: error("review snapshot file is empty: ${file.absolutePath}")
        require(metadata.bookUrl == book.bookUrl) {
            "review snapshot bookUrl mismatch: ${file.absolutePath}"
        }
        require(metadata.chapterUrl.isNotBlank()) {
            "review snapshot chapterUrl is blank: ${file.absolutePath}"
        }
        require(metadata.buttonSrc.isNotBlank()) {
            "review snapshot buttonSrc is blank: ${file.absolutePath}"
        }
        require(metadata.htmlPresent) {
            "review snapshot HTML is missing: ${file.absolutePath}"
        }
        val keys = requireNotNull(metadata.resourceKeys) {
            "review snapshot is missing resourceKeys: ${file.absolutePath}"
        }
        ReviewSnapshotResourceStore.validateResourceKeys(book, keys)
        return metadata
    }
}
