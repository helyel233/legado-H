package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.compress.readTextLimited
import java.io.File

object CacheManifestHelper {

    const val MANIFEST_FILE_NAME = "cache_manifest.json"
    private const val MAX_MANIFEST_BYTES = 2L * 1024L * 1024L

    fun manifestFile(book: Book): File {
        return File(BookHelp.getCacheDir(book), MANIFEST_FILE_NAME)
    }

    fun hasManifest(cacheDir: File): Boolean {
        return File(cacheDir, MANIFEST_FILE_NAME).isFile
    }

    fun read(book: Book): CacheBookManifest? {
        return read(manifestFile(book))
    }

    fun read(file: File): CacheBookManifest? {
        if (!file.isFile) return null
        return runCatching {
            GSON.fromJsonObject<CacheBookManifest>(file.readTextLimited(MAX_MANIFEST_BYTES)).getOrNull()
        }.getOrNull()
    }

    fun listManifests(): List<CacheBookManifest> {
        return listManifests(listCacheDirs())
    }

    fun listCacheDirs(): List<File> {
        val root = File(BookHelp.cachePath)
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.toList()
            .orEmpty()
    }

    fun listManifests(cacheDirs: List<File>): List<CacheBookManifest> {
        return cacheDirs
            .asSequence()
            .mapNotNull { read(File(it, MANIFEST_FILE_NAME)) }
            .toList()
    }

    fun write(
        book: Book,
        chapters: List<BookChapter>,
        isChapterCached: (BookChapter) -> Boolean
    ): CacheBookManifest? {
        val realChapters = chapters.filterNot { it.isVolume }
        val cachedByIndex = realChapters.associate { it.index to isChapterCached(it) }
        val cachedCount = cachedByIndex.values.count { it }
        val file = manifestFile(book)
        if (cachedCount <= 0) {
            file.delete()
            return null
        }
        val cacheDir = file.parentFile ?: return null
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val now = System.currentTimeMillis()
        val manifest = CacheBookManifest(
            bookUrl = book.bookUrl,
            tocUrl = book.tocUrl,
            origin = book.origin,
            originName = book.originName,
            name = book.name,
            author = book.author,
            kind = book.kind,
            coverUrl = book.coverUrl,
            intro = book.intro,
            type = book.type,
            folderName = book.getFolderName(),
            latestChapterTitle = book.latestChapterTitle,
            totalChapterNum = chapters.size.takeIf { it > 0 } ?: book.totalChapterNum,
            updatedAt = now,
            chapters = chapters.map { chapter ->
                CacheChapterManifest(
                    index = chapter.index,
                    title = chapter.title,
                    isVolume = chapter.isVolume,
                    url = chapter.url,
                    baseUrl = chapter.baseUrl,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl,
                    cached = !chapter.isVolume && cachedByIndex[chapter.index] == true
                )
            }
        )
        file.writeText(GSON.toJson(manifest))
        return manifest
    }

    fun delete(book: Book) {
        manifestFile(book).delete()
    }

    fun delete(manifest: CacheBookManifest) {
        val folderName = manifest.folderName.takeIf { it.isNotBlank() }
            ?: toBook(manifest).getFolderName()
        File(File(BookHelp.cachePath), folderName)
            .resolve(MANIFEST_FILE_NAME)
            .delete()
    }

    fun toBook(manifest: CacheBookManifest): Book {
        return Book(
            bookUrl = manifest.bookUrl,
            tocUrl = manifest.tocUrl,
            origin = manifest.origin,
            originName = manifest.originName,
            name = manifest.name,
            author = manifest.author,
            kind = manifest.kind,
            coverUrl = manifest.coverUrl,
            intro = manifest.intro,
            type = manifest.type,
            latestChapterTitle = manifest.latestChapterTitle,
            totalChapterNum = manifest.totalChapterNum,
            canUpdate = false
        )
    }

    fun toChapters(
        manifest: CacheBookManifest,
        targetBookUrl: String = manifest.bookUrl
    ): List<BookChapter> {
        return manifest.chapters
            .sortedBy { it.index }
            .map { chapter ->
                BookChapter(
                    url = chapter.url,
                    title = chapter.title,
                    isVolume = chapter.isVolume,
                    baseUrl = chapter.baseUrl,
                    bookUrl = targetBookUrl,
                    index = chapter.index,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl
                )
            }
    }

    fun mergeResourceUrls(
        chapters: List<BookChapter>,
        manifest: CacheBookManifest?
    ): Boolean {
        if (manifest == null) return false
        val byIndex = manifest.chapters.associateBy { it.index }
        var changed = false
        chapters.forEach { chapter ->
            val resourceUrl = byIndex[chapter.index]?.resourceUrl
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            if (chapter.resourceUrl.isNullOrBlank()) {
                chapter.resourceUrl = resourceUrl
                changed = true
            }
        }
        return changed
    }

    /**
     * 刷新清单（音视频书按"缓存时的地址"记录）
     *
     * 章节表里的 resourceUrl 可能已被新解析结果覆盖、或被目录刷新清空，直接记它会把
     * "缓存按哪个地址存的"这个信息弄丢（之后既读不到缓存、点使用缓存也救不回来）。
     * 音视频书先按探测找回实际有缓存的地址，再写入清单。
     */
    fun refresh(
        book: Book,
        chapters: List<BookChapter> = appDb.bookChapterDao.getChapterList(book.bookUrl)
    ): CacheBookManifest? {
        return runCatching {
            if (chapters.isEmpty()) {
                // 章节表里没有记录（多半是书已从书架删除、只剩缓存）：
                // 缓存目录还在就保留清单，否则缓存管理页再也列不出这本书
                val cacheDir = BookHelp.getCacheDir(book)
                val hasCacheFiles = cacheDir.isDirectory && !cacheDir.listFiles().isNullOrEmpty()
                if (!hasCacheFiles) {
                    delete(book)
                }
                return@runCatching null
            }
            val cacheNames = if (book.isAudio || book.isVideo) {
                emptySet()
            } else {
                BookHelp.getCacheDir(book).list()?.toSet().orEmpty()
            }
            // 本轮要覆盖的那份清单就是旧清单，用它补回"缓存时用的那个地址"
            val oldManifest = read(book)
            // 同一章判定两次要花掉两遍缓存查询，这里按序号记一份结果
            // 注意未缓存的章节结果是 null，不能用 getOrPut（null 会被当作"不存在"而重复探测）
            val cachedUrlByIndex = HashMap<Int, String?>()
            val cachedUrlOf: (BookChapter) -> String? = { chapter ->
                if (cachedUrlByIndex.containsKey(chapter.index)) {
                    cachedUrlByIndex[chapter.index]
                } else {
                    cachedMediaUrl(book, chapter, oldManifest).also {
                        cachedUrlByIndex[chapter.index] = it
                    }
                }
            }
            val manifestChapters = if (book.isAudio || book.isVideo) {
                chapters.map { chapter ->
                    cachedUrlOf(chapter)?.let { url ->
                        if (chapter.resourceUrl != url) chapter.copy(resourceUrl = url) else chapter
                    } ?: chapter
                }
            } else {
                chapters
            }
            write(book, manifestChapters) { chapter ->
                when {
                    book.isAudio || book.isVideo -> cachedUrlOf(chapter) != null
                    else -> cacheNames.contains(chapter.getFileName())
                }
            }
        }.onFailure {
            AppLog.put("刷新缓存清单失败 ${book.name}\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    /** 后台刷新清单：调用点在章节列表/缓存状态变化处，不阻塞当前流程 */
    fun refreshAsync(
        book: Book,
        chapters: List<BookChapter>? = null
    ) {
        Coroutine.async {
            if (chapters == null) {
                refresh(book)
            } else {
                refresh(book, chapters)
            }
        }
    }

    /**
     * 该章节"确实还有缓存"的媒体地址
     *
     * 缓存是按**缓存当时的地址**做 key 的，而章节表里的 `resourceUrl` 之后可能被新解析结果覆盖、
     * 或者地址本身带了会过期的时间签名；清单里留着缓存时用的那个地址。
     * 两个都试一遍，只要有一个在缓存里，这章就还能离线播——否则会出现
     * "缓存明明在磁盘上，却因为地址变了读不到"。
     *
     * @param manifest 已知清单时传入，避免逐章重复读文件
     */
    fun cachedMediaUrl(
        book: Book,
        chapter: BookChapter,
        manifest: CacheBookManifest? = read(book)
    ): String? {
        if (!book.isAudio && !book.isVideo) return null
        val recorded = manifest?.let { manifestMediaUrl(it, chapter) }
        return sequenceOf(chapter.resourceUrl, recorded, contentMediaUrl(book, chapter))
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .firstOrNull { isMediaCached(it, book) }
    }

    /**
     * 缓存正文里记着的媒体地址
     *
     * 音视频章节的缓存正文就是缓存当时的媒体地址。清单功能之前缓存的老书、
     * 或清单里的地址已被新解析结果覆盖时，缓存正文是另一处地址痕迹，
     * 靠它把地址捞回来，已下好的媒体才不会变成判定不到的孤儿缓存。
     */
    private fun contentMediaUrl(book: Book, chapter: BookChapter): String? {
        // 按当前章节直接算缓存正文的文件名去读（不列目录、不查清单）：
        // 逐章判定的热路径上，为"找回地址"多付一次目录扫描不值得，标题/序号变过的情况由清单兕底
        val file = File(BookHelp.getCacheDir(book), chapter.getFileName())
        if (!file.isFile) return null
        val content = runCatching { file.readText() }.getOrNull() ?: return null
        return ExoPlayerHelper.mediaUrlsOf(content).firstOrNull { isMediaCached(it, book) }
    }

    /** 清单里记录的该章媒体地址（缓存时用的那个） */
    fun manifestMediaUrl(manifest: CacheBookManifest, chapter: BookChapter): String? {
        val recorded = manifest.chapters.firstOrNull { it.index == chapter.index }
            ?: manifest.chapters.firstOrNull { it.url == chapter.url }
            ?: return null
        return (recorded.resourceUrl ?: recorded.url).takeIf { it.isNotBlank() }
    }

    /** 地址在该书的媒体缓存里是否完整可用（音频走全局缓存、视频走书级缓存） */
    fun isMediaCached(url: String, book: Book): Boolean = when {
        book.isVideo -> ExoPlayerHelper.isVideoCached(url, book)
        book.isAudio -> ExoPlayerHelper.isMediaCached(url)
        else -> false
    }

    /**
     * 找回"清单功能之前"缓存的媒体地址
     *
     * 那时缓存按当时的地址存，章节表里的地址之后被新解析结果覆盖了：
     * "哪个缓存 key 属于哪一章"没有任何记录可查，该书只有一章时这份缓存必然全属于它——
     * 从缓存里挑能当章节地址用的那个（播放列表优先）。
     *
     * @return 找回来的媒体地址；没能确定时返回 null
     */
    fun recoverLegacyMediaUrl(book: Book): String? {
        if (!book.isVideo && !book.isAudio) return null
        // 先做最便宜的判断：整库绝大多数书没有媒体缓存，不必为它们查章节表与缓存内容
        if (!ExoPlayerHelper.hasDownloadedMedia(book)) return null
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
            .ifEmpty {
                // 书已不在书架、只剩缓存：章节按清单里的来
                read(book)?.let { toChapters(it).filterNot { c -> c.isVolume } }.orEmpty()
            }
        if (chapters.size != 1) return null
        val chapter = chapters.first()
        val entries = ExoPlayerHelper.cachedMediaEntries(book)
        val url = ExoPlayerHelper.pickPlayableCachedUrl(entries.map { it.second }, book) ?: return null
        if (chapter.resourceUrl != url) {
            appDb.bookChapterDao.upResourceUrl(book.bookUrl, chapter.url, url)
            AppLog.put("按缓存找回媒体地址 ${book.name}\n$url")
            // 把地址与"已缓存"记进清单：之后缓存管理页计数、使用缓存、播放都不用再反推一遍
            refreshAsync(book)
        }
        return url
    }
}

data class CacheBookManifest(
    val version: Int = 1,
    val bookUrl: String = "",
    val tocUrl: String = "",
    val origin: String = "",
    val originName: String = "",
    val name: String = "",
    val author: String = "",
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val type: Int = 0,
    val folderName: String = "",
    val latestChapterTitle: String? = null,
    val totalChapterNum: Int = 0,
    val updatedAt: Long = 0L,
    val chapters: List<CacheChapterManifest> = emptyList()
) {
    val cachedChapterCount: Int
        get() = chapters.count { !it.isVolume && it.cached }
}

data class CacheChapterManifest(
    val index: Int = 0,
    val title: String = "",
    val isVolume: Boolean = false,
    val url: String = "",
    val baseUrl: String = "",
    val isVip: Boolean = false,
    val isPay: Boolean = false,
    val resourceUrl: String? = null,
    val tag: String? = null,
    val wordCount: String? = null,
    val start: Long? = null,
    val end: Long? = null,
    val startFragmentId: String? = null,
    val endFragmentId: String? = null,
    val variable: String? = null,
    val imgUrl: String? = null,
    val cached: Boolean = false
)
