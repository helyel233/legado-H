package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isVideo
import io.legado.app.help.http.mediaHttpClient as okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import okhttp3.CacheControl
import splitties.init.appCtx
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


/**
 * 地址里是否出现该流类型标记：`.m3u8` 这类扩展名写法一律算命中（保持原有行为），
 * 另外 `/m3u8/`、`=m3u8` 这种没有扩展名的独立标记也算；黏在别的单词里的（`/prism/`）不算
 */
private fun String.containsMediaTypeToken(token: String): Boolean {
    if (contains(".$token")) return true
    var index = indexOf(token)
    while (index >= 0) {
        val charBefore = if (index > 0) this[index - 1] else null
        val afterIndex = index + token.length
        val charAfter = if (afterIndex < length) this[afterIndex] else null
        if ((charBefore == null || !charBefore.isLetterOrDigit()) &&
            (charAfter == null || !charAfter.isLetterOrDigit())
        ) {
            return true
        }
        index = indexOf(token, index + 1)
    }
    return false
}

/**
 * 媒体地址对应的自适应流 MIME（m3u8 / mpd / ism），普通文件（mp4 等）返回 null
 *
 * 这是"下载用什么下载器 / 缓存算不算完整 / 播放按哪种流播"共用的唯一口径（播放侧见 [mediaExtensionOfUrl]）。
 * 只按路径扩展名判断会漏掉 `/api/m3u8/?url=<真实地址>` 这类代理地址——路径结尾不是 `.m3u8`：
 * 下载侧会把它当普通文件下（只下到一个播放列表、还落了完成标记），播放侧却按 HLS 读同一份缓存，
 * 切片从来没下过，表现为"显示已缓存、点使用缓存也看不了"。
 */
internal fun mediaMimeTypeOfUrl(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.containsMediaTypeToken("m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.containsMediaTypeToken("mpd") -> MimeTypes.APPLICATION_MPD
        lower.containsMediaTypeToken("ism") -> MimeTypes.APPLICATION_SS
        else -> null
    }
}

/**
 * [mediaMimeTypeOfUrl] 对应的播放器 overrideExtension（m3u8 / mpd / ism），非自适应流返回 null
 */
internal fun mediaExtensionOfUrl(url: String): String? = when (mediaMimeTypeOfUrl(url)) {
    MimeTypes.APPLICATION_M3U8 -> "m3u8"
    MimeTypes.APPLICATION_MPD -> "mpd"
    MimeTypes.APPLICATION_SS -> "ism"
    else -> null
}

@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    /** 全局播放缓存容量上限（历史遗留目录） */
    private const val PLAYBACK_CACHE_MAX_BYTES = 100L * 1024 * 1024

    /** 每本书的视频离线缓存目录容量上限 */
    private const val VIDEO_CACHE_MAX_BYTES = 4L * 1024 * 1024 * 1024

    /** 每本书的音频离线缓存目录容量上限 */
    private const val AUDIO_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024

    private const val VIDEO_BOOK_CACHE_DIR = "video_media"

    private const val AUDIO_BOOK_CACHE_DIR = "audio_media"

    /** 完成标记目录后缀，与缓存目录同级（video_media -> video_media_complete） */
    private const val VIDEO_COMPLETE_SUFFIX = "_complete"

    /** 非自适应流媒体的完成标记内容 */
    private const val MEDIA_MARK_PROGRESSIVE = "progressive"

    /** 探测缓存内容是不是播放列表时读取的字节数 */
    private const val PLAYLIST_SNIFF_BYTES = 16 * 1024

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        val mediaItemBuilder = MediaItem.Builder().setUri(formatUrl)
        return mediaItemBuilder.build()
    }

    fun createMediaRequest(url: String, headers: Map<String, String>): MediaRequest {
        return MediaRequest(url, headers.toMap())
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()
        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            ).setDataSourceFactory(resolvingDataSource)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }


    private val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        ResolvingDataSource.Factory(audioReadDataSourceFactory) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(Uri.parse(url))
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                } catch (_: Exception) {
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    val cacheDataSourceFactory by lazy {
        //使用自定义的CacheDataSource以支持设置UA
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    private val audioCacheDataSourceFactory by lazy {
        CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(audioCache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    private val audioReadDataSourceFactory by lazy {
        CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(null)
    }

    /**
     * Okhttp DataSource.Factory
     */
    private val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    /**
     * Exoplayer 内置的缓存
     */
    private val cache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        return@lazy SimpleCache(
            //Exoplayer的缓存路径
            File(appCtx.externalCache, "exoplayer"),
            //100M的缓存
            LeastRecentlyUsedCacheEvictor((100 * 1024 * 1024).toLong()),
            //记录缓存的数据库
            databaseProvider
        )
    }

    private val audioCache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        return@lazy SimpleCache(
            File(appCtx.externalCache, "audio_exoplayer"),
            LeastRecentlyUsedCacheEvictor(AUDIO_OFFLINE_CACHE_MAX_BYTES),
            databaseProvider
        )
    }

    private val audioCompleteMarkerDir: File by lazy {
        File(appCtx.externalCache, "audio_exoplayer_complete").apply { mkdirs() }
    }

    /**
     * 通过kotlin扩展函数+反射实现CacheDataSource.Factory设置默认请求头
     * 需要添加混淆规则 -keepclassmembers class com.google.android.exoplayer2.upstream.cache.CacheDataSource$Factory{upstreamDataSourceFactory;}
     * @param headers
     * @return
     */
//    private fun CacheDataSource.Factory.setDefaultRequestProperties(headers: Map<String, String> = mapOf()): CacheDataSource.Factory {
//        val declaredField = this.javaClass.getDeclaredField("upstreamDataSourceFactory")
//        declaredField.isAccessible = true
//        val df = declaredField[this] as DataSource.Factory
//        if (df is OkHttpDataSource.Factory) {
//            df.setDefaultRequestProperties(headers)
//        }
//        return this
//    }


    fun getMediaSource(context: Context, url: String): MediaSource? {
        val uris = GSON.fromJsonArray<String>(url).getOrNull() ?: return null
        if (uris.isEmpty()) return null
        val mediaSourceBuilder = ConcatenatingMediaSource2.Builder()
        for (uri in uris) {
            mediaSourceBuilder.add(
                ProgressiveMediaSource.Factory(audioReadDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri)), 3000
            )
        }
        return mediaSourceBuilder.build()
    }

    fun cacheMedia(
        request: MediaRequest,
        progress: ((requestLength: Long, bytesCached: Long, newBytesCached: Long) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null
    ): Long {
        var totalCached = 0L
        val urls = getMediaUrls(request.url)
        require(urls.isNotEmpty()) { "media url is empty" }
        urls.forEach { url ->
            if (shouldCancel?.invoke() == true) {
                throw kotlinx.coroutines.CancellationException("audio cache cancelled")
            }
            val dataSpec = DataSpec.Builder()
                .setUri(url)
                .setKey(url)
                .setHttpRequestHeaders(request.headers)
                .build()
            var cached = 0L
            CacheWriter(
                audioCacheDataSourceFactory.createDataSourceForDownloading(),
                dataSpec,
                null
            ) { requestLength, bytesCached, newBytesCached ->
                if (shouldCancel?.invoke() == true) {
                    throw kotlinx.coroutines.CancellationException("audio cache cancelled")
                }
                cached = bytesCached
                progress?.invoke(requestLength, bytesCached, newBytesCached)
            }.cache()
            markMediaUrlComplete(url)
            totalCached += cached
        }
        return totalCached
    }

    fun isMediaCached(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return false
        return urls.all { isMediaUrlCached(it) }
    }

    fun removeMediaCache(url: String?) {
        if (url.isNullOrBlank()) return
        getMediaUrls(url).forEach {
            audioCache.removeResource(it)
            mediaCompleteMarker(it).delete()
        }
    }

    fun copyMediaCache(url: String?, targetDir: File): Int {
        if (url.isNullOrBlank()) return 0
        if (!targetDir.exists()) targetDir.mkdirs()
        var count = 0
        getMediaUrls(url).forEachIndexed { urlIndex, mediaUrl ->
            for (span in audioCache.getCachedSpans(mediaUrl)) {
                if (!span.isCached) continue
                val source = span.file ?: continue
                if (!source.exists() || !source.isFile) continue
                val name = "${urlIndex}_${span.position}_${span.length}_${source.name}"
                source.copyTo(File(targetDir, name), overwrite = true)
                count++
            }
        }
        return count
    }

    fun importMediaCache(url: String?, sourceDir: File): Int {
        if (url.isNullOrBlank() || !sourceDir.exists() || !sourceDir.isDirectory) return 0
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return 0
        var count = 0
        sourceDir.listFiles()
            ?.filter { it.isFile }
            ?.forEach { source ->
                val prefix = source.name.substringBefore('_', "")
                val urlIndex = prefix.toIntOrNull() ?: return@forEach
                val targetUrl = urls.getOrNull(urlIndex) ?: return@forEach
                val remain = source.name.substringAfter('_', "")
                val position = remain.substringBefore('_', "").toLongOrNull() ?: return@forEach
                val lengthPart = remain.substringAfter("${position}_", "")
                val expectedLength = lengthPart.substringBefore('_', "").toLongOrNull()
                    ?: source.length()
                val cacheFile = audioCache.startFile(targetUrl, position, expectedLength)
                cacheFile.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                audioCache.commitFile(cacheFile, source.length())
                markMediaUrlComplete(targetUrl)
                count++
            }
        return count
    }

    private fun isMediaUrlCached(url: String): Boolean {
        val contentLength = ContentMetadata.getContentLength(audioCache.getContentMetadata(url))
        return if (contentLength > 0) {
            audioCache.isCached(url, 0, contentLength)
        } else {
            mediaCompleteMarker(url).isFile &&
                audioCache.getCachedBytes(url, 0, Long.MAX_VALUE) > 0
        }
    }

    private fun markMediaUrlComplete(url: String) {
        runCatching {
            mediaCompleteMarker(url).writeText(System.currentTimeMillis().toString())
        }
    }

    private fun mediaCompleteMarker(url: String): File {
        return File(audioCompleteMarkerDir, MD5Utils.md5Encode(url))
    }

    private fun getMediaUrls(url: String): List<String> {
        if (url.isJsonArray()) {
            GSON.fromJsonArray<String>(url).getOrNull()?.filter { it.isNotBlank() }?.let {
                return it
            }
        }
        return listOf(url)
    }

    /** 带请求头的 OkHttp 数据源工厂（书级媒体缓存/播放用） */
    private fun httpDataFactory(headers: Map<String, String>): OkHttpDataSource.Factory {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        return OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
            .setDefaultRequestProperties(headers)
    }

    private val databaseProvider: StandaloneDatabaseProvider by lazy {
        StandaloneDatabaseProvider(appCtx)
    }

    /** media3 同一目录只允许一个 SimpleCache 实例，这里按目录复用 */
    private val cacheMap = ConcurrentHashMap<String, Cache>()
    private val cacheLock = Any()

    private fun simpleCache(dir: File, maxBytes: Long): Cache {
        val path = dir.absolutePath
        // 旧的全局播放缓存目录已有单例（cache），media3 同一目录不允许第二个实例，直接复用它
        if (path == legacyCacheDir.absolutePath) return cache
        cacheMap[path]?.let { return it }
        return synchronized(cacheLock) {
            cacheMap[path] ?: SimpleCache(
                dir.apply { mkdirs() },
                LeastRecentlyUsedCacheEvictor(maxBytes),
                databaseProvider,
            ).also { cacheMap[path] = it }
        }
    }

    private val legacyCacheDir: File
        get() = File(appCtx.externalCache, "exoplayer")

    /** 每本书的视频离线缓存目录（book_cache/<书>/video_media） */
    fun videoBookCacheDir(book: Book): File = File(BookHelp.getCacheDir(book), VIDEO_BOOK_CACHE_DIR)

    /** 每本书的音频离线缓存目录（book_cache/<书>/audio_media） */
    fun audioBookCacheDir(book: Book): File = File(BookHelp.getCacheDir(book), AUDIO_BOOK_CACHE_DIR)

    /** 该书媒体缓存目录，音频与视频只在目录（与容量上限）上不同 */
    fun mediaBookCacheDir(book: Book, useVideoCache: Boolean): File =
        if (useVideoCache) videoBookCacheDir(book) else audioBookCacheDir(book)

    /**
     * 释放指定缓存目录的实例，删除缓存目录前必须调用，否则 media3 会持有目录锁
     */
    private fun releaseCache(cacheDir: File) {
        synchronized(cacheLock) {
            cacheMap.remove(cacheDir.absolutePath)?.release()
        }
    }

    fun releaseBookCaches(book: Book) {
        releaseCache(videoBookCacheDir(book))
        releaseCache(audioBookCacheDir(book))
    }

    /** 按书籍缓存目录释放媒体缓存实例（删除/移动目录前调用） */
    fun releaseBookMediaCacheOf(bookCacheDir: File) {
        releaseCache(File(bookCacheDir, VIDEO_BOOK_CACHE_DIR))
        releaseCache(File(bookCacheDir, AUDIO_BOOK_CACHE_DIR))
    }

    fun releaseAllBookCaches() {
        synchronized(cacheLock) {
            val iterator = cacheMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.key.startsWith(legacyCacheDir.absolutePath)) {
                    entry.value.release()
                    iterator.remove()
                }
            }
        }
    }

    /**
     * 构建视频播放的 MediaSource：优先读指定缓存目录（书级视频缓存），
     * [writable] 为 true 时未缓存的部分边播边写；本地文件用库默认数据源即可
     */
    fun createVideoMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        cacheDir: File? = null,
        mimeType: String? = null,
        writable: Boolean = true,
    ): MediaSource = DefaultMediaSourceFactory(
        mediaPlaybackDataSourceFactory(context, url, headers, cacheDir, writable),
    )
        .setLiveTargetOffsetMs(5000)
        .createMediaSource(
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType ?: mediaMimeTypeOfUrl(url))
                .build(),
        )

    /** 播放器扩展名 -> MIME，地址本身看不出流类型时（如 m3u8 只出现在 query 里）使用 */
    fun mimeTypeOfExtension(extension: String?): String? = when (extension?.lowercase()) {
        "m3u8" -> MimeTypes.APPLICATION_M3U8
        "mpd" -> MimeTypes.APPLICATION_MPD
        "ism" -> MimeTypes.APPLICATION_SS
        else -> null
    }

    /** 判定该章节的视频媒体文件是否已经完整缓存（按书级视频缓存目录） */
    fun isVideoCached(url: String?, book: Book): Boolean = isMediaCached(url, book, useVideoCache = true)

    /** 判定该章节的音频媒体文件是否已经完整缓存（按书级音频缓存目录） */
    fun isMediaCached(url: String?, book: Book): Boolean = isMediaCached(url, book, useVideoCache = false)

    private fun isMediaCached(url: String?, book: Book, useVideoCache: Boolean): Boolean {
        if (url.isNullOrBlank()) return false
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return false
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return false
        if (urls.any { !isDownloadableMediaUrl(it) }) return false
        val cache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        return urls.all { isMediaUrlCached(cache, it, cacheDir) }
    }

    /** 该书是否有媒体缓存（完成标记目录非空），给"找回老缓存"做前置判断 */
    fun hasDownloadedMedia(book: Book): Boolean {
        val cacheDir = mediaBookCacheDir(book, book.isVideo)
        val markerDir = completeMarkerDir(cacheDir)
        return markerDir.isDirectory && !markerDir.listFiles().isNullOrEmpty()
    }

    /** 该书媒体缓存里已经存在的内容：(缓存 key -> 媒体地址)；key 就是缓存时的地址 */
    fun cachedMediaEntries(book: Book): List<Pair<String, String>> {
        val useVideoCache = book.isVideo
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return emptyList()
        val maxBytes = if (useVideoCache) VIDEO_CACHE_MAX_BYTES else AUDIO_CACHE_MAX_BYTES
        val keys = runCatching { simpleCache(cacheDir, maxBytes).keys }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return keys.mapNotNull { key ->
            key.takeIf { it.startsWith("http") }?.let { key to it }
        }
    }

    /**
     * 从该书已缓存的一组地址里挑出能当"章节播放地址"用的那个
     *
     * HLS 下载会留下播放列表 + 大量切片两类 key：切片地址不能当章节地址，
     * 多个都完整时优先挑内容是播放列表（`#EXTM3U`）的；普通文件一章通常就一个 key。
     * 都完整却挑不出播放列表时宁可返回 null，也别拿半截地址当作章节地址。
     */
    fun pickPlayableCachedUrl(urls: Collection<String>, book: Book): String? {
        val useVideoCache = book.isVideo
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return null
        val cache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        val playable = urls.filter { isMediaUrlCached(cache, it, cacheDir) }
        if (playable.size <= 1) return playable.firstOrNull()
        return playable.firstOrNull { url ->
            readCachedText(cache, url)?.trimStart()?.startsWith("#EXTM3U") == true
        }
    }

    /**
     * 缓存被 LRU 淘汰后完成标记可能残留，所以判定时都要求缓存里确实还有数据
     */
    private fun isMediaUrlCached(cache: Cache, url: String, cacheDir: File): Boolean {
        val cachedBytes = cache.getCachedBytes(url, 0, Long.MAX_VALUE)
        if (cachedBytes <= 0) return false
        val mediaMimeType = mediaMimeTypeOfUrl(url)
        if (mediaMimeType != null) {
            // 自适应流的切片按切片地址单独缓存，只能靠完成标记判定；
            // 标记里必须写着同一个自适应流类型，才算真的按自适应流下过
            val mark = readCompleteMarker(url, cacheDir)
            if (mark == mediaMimeType) return true
            // 没落过标记：只是边播边写的缓存，不算完整
            if (mark == null) return false
            // 旧版本的标记没记类型，按内容确认一次：确认是"只下到播放列表"的假缓存才清掉
            if (adaptiveCacheLooksFake(cache, url)) {
                clearFakeAdaptiveCache(cache, url, cacheDir)
                return false
            }
            // 确认过就补齐标记，之后判定不用再读内容
            markMediaComplete(url, cacheDir)
            return true
        }
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return if (contentLength > 0) {
            cache.isCached(url, 0, contentLength)
        } else {
            readCompleteMarker(url, cacheDir) != null
        }
    }

    fun removeMediaCache(url: String?, book: Book, useVideoCache: Boolean = true) {
        if (url.isNullOrBlank()) return
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return
        val cache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        getMediaUrls(url).forEach { mediaUrl ->
            cache.removeResource(mediaUrl)
            completeMarker(mediaUrl, cacheDir).delete()
        }
    }

    /**
     * 媒体播放数据源：始终优先读缓存目录（离线缓存的章节才能离线播放），
     * [writable] 为 false 时不写缓存，此时若缓存目录都还不存在就直接走网络，
     * 避免"只是播放"也在书籍缓存目录里凭空建出空目录
     */
    private fun mediaPlaybackDataSourceFactory(
        context: Context,
        url: String,
        headers: Map<String, String>,
        cacheDir: File? = null,
        writable: Boolean = true,
    ): DataSource.Factory {
        val targetCacheDir = cacheDir ?: legacyCacheDir
        // 不写缓存、缓存目录还不存在（也没缓存可读）且是网络地址时直接走网络；
        // 本地文件/内容 URI 不短路，仍走下面的缓存数据源（读侧是 FileDataSource）
        if (!writable && !targetCacheDir.exists() && isHttpUrl(url)) {
            return httpDataFactory(headers)
        }
        // OkHttp 打不开 file://（书源下发的本地 mpd 临时文件等），非网络地址交给
        // DefaultDataSource 按协议分流：file/content 走本地数据源，http(s) 仍走带请求头的 OkHttp
        val upstream = if (isHttpUrl(url)) {
            httpDataFactory(headers)
        } else {
            DefaultDataSource.Factory(context, httpDataFactory(headers))
        }
        return mediaCacheDataSourceFactory(upstream, targetCacheDir, writable, ignoreCacheError = true)
    }

    /**
     * 走缓存目录的数据源，[writable] 为 false 时是只读缓存
     *
     * @param ignoreCacheError 播放可以容忍缓存读写出错（缓存只是加速器，出错后退回网络）
     */
    private fun mediaCacheDataSourceFactory(
        upstreamDataSourceFactory: DataSource.Factory,
        cacheDir: File,
        writable: Boolean,
        ignoreCacheError: Boolean = false,
    ): CacheDataSource.Factory {
        val dataCache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        return CacheDataSource.Factory()
            .setCache(dataCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .apply {
                if (ignoreCacheError) {
                    setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                }
                if (writable) {
                    setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory()
                            .setCache(dataCache)
                            .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE),
                    )
                }
            }
    }

    /** 缓存目录对应的容量上限：旧全局播放缓存沿用历史的 100MB，视频 4GB，音频 2GB */
    private fun cacheMaxBytes(cacheDir: File): Long = when (cacheDir.name) {
        VIDEO_BOOK_CACHE_DIR -> VIDEO_CACHE_MAX_BYTES
        AUDIO_BOOK_CACHE_DIR -> AUDIO_CACHE_MAX_BYTES
        else -> PLAYBACK_CACHE_MAX_BYTES
    }

    /**
     * 落完成标记，内容记下这份缓存是按哪种流下载的
     *
     * 自适应流"已完整缓存"只能靠标记判定，记清类型才能把"代理地址被当普通文件下、
     * 只下到播放列表"的假缓存与真正的自适应流缓存区分开
     */
    private fun markMediaComplete(url: String, cacheDir: File) {
        val mark = mediaMimeTypeOfUrl(url) ?: MEDIA_MARK_PROGRESSIVE
        runCatching {
            completeMarkerDir(cacheDir).mkdirs()
            completeMarker(url, cacheDir).writeText(mark)
        }
    }

    private fun readCompleteMarker(url: String, cacheDir: File): String? {
        val marker = completeMarker(url, cacheDir)
        if (!marker.isFile) return null
        return runCatching { marker.readText().trim() }.getOrNull()
    }

    /**
     * 缓存里的这份内容是不是"只下到播放列表"的假缓存
     *
     * 播放列表本身很小，把播放列表当普通文件下就只有一个列表、不带任何切片；
     * 只有内容确实是一份播放列表（`#EXTM3U` 开头）**并且**它引用的第一个切片不在缓存里才判定为假：
     * 读不到内容、或内容不像列表时一律返回 false，宁可当作真缓存，也不能误删用户已经下好的内容。
     */
    private fun adaptiveCacheLooksFake(cache: Cache, url: String): Boolean {
        val playlist = readCachedText(cache, url) ?: return false
        if (!playlist.trimStart().startsWith("#EXTM3U")) return false
        val firstUri = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return false
        val segmentUrl = runCatching { URI(url).resolve(firstUri).toString() }.getOrNull() ?: return false
        return cache.getCachedBytes(segmentUrl, 0, Long.MAX_VALUE) <= 0
    }

    /** 读缓存里某个地址的内容（只读缓存，不联网；没缓存时返回 null） */
    private fun readCachedText(cache: Cache, url: String): String? {
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        return runCatching {
            dataSource.open(DataSpec(url.toUri()))
            val buffer = ByteArray(PLAYLIST_SNIFF_BYTES)
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read <= 0) null else String(buffer, 0, read)
        }.also {
            runCatching { dataSource.close() }
        }.getOrNull()
    }

    /**
     * 清掉"把自适应流当普通文件下"留下的假缓存
     *
     * 这种缓存里只有一个播放列表（切片从没下过），而播放走缓存时命中已缓存的列表后
     * 就不会再去网络取，列表里的切片地址通常带签名、早已失效。清掉这一条即可：
     * 切片按各自地址单独缓存，真正下过的切片不受影响。
     */
    private fun clearFakeAdaptiveCache(cache: Cache, url: String, cacheDir: File) {
        runCatching {
            cache.removeResource(url)
            completeMarker(url, cacheDir).delete()
        }
    }

    private fun completeMarker(url: String, cacheDir: File): File =
        File(completeMarkerDir(cacheDir), MD5Utils.md5Encode(url))

    /** 完成标记所在目录，与缓存目录同级（video_media -> video_media_complete） */
    private fun completeMarkerDir(cacheDir: File): File =
        File(cacheDir.parentFile, cacheDir.name + VIDEO_COMPLETE_SUFFIX)

    /**
     * 章节内容/媒体地址文本 -> 候选媒体地址列表
     *
     * 供"从缓存正文里找回媒体地址"用（音视频章节的缓存正文就是缓存当时的媒体地址），
     * 地址可能是 JSON 数组、单个地址或多行文本，这里都拆开，由调用方逐个去缓存里核对。
     */
    fun mediaUrlsOf(content: String?): List<String> {
        if (content.isNullOrBlank()) return emptyList()
        return getMediaUrls(content.trim())
            .flatMap { it.lineSequence().toList() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun isDownloadableMediaUrl(url: String): Boolean {
        val scheme = url.toUri().scheme ?: return false
        return isHttpUrl(url) ||
            (scheme.equals("file", true) && isAdaptiveMediaUrl(url))
    }

    private fun isHttpUrl(url: String): Boolean {
        val scheme = url.toUri().scheme ?: return false
        return scheme.equals("http", true) || scheme.equals("https", true)
    }

    private fun isAdaptiveMediaUrl(url: String): Boolean = mediaMimeTypeOfUrl(url) != null

    data class MediaRequest(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    private const val AUDIO_OFFLINE_CACHE_MAX_BYTES = 4L * 1024 * 1024 * 1024
}
