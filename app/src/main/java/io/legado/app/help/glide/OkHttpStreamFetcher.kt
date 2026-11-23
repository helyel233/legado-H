package io.legado.app.help.glide

import io.legado.app.help.http.dns.DnsScope
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.util.ContentLengthInputStream
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.imageHttpClient as okHttpClient
import io.legado.app.help.http.okHttpClientManga
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.ReadManga
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashMap

internal class FailedUrlCache(
    private val maxEntries: Int = DEFAULT_MAX_FAIL_URLS,
    private val ttlMs: Long = DEFAULT_FAIL_URL_TTL_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > maxEntries
        }
    }

    fun add(url: String) {
        if (maxEntries <= 0 || ttlMs <= 0L) return
        val now = timeProvider()
        synchronized(lock) {
            map[url] = now
        }
    }

    fun contains(url: String): Boolean {
        if (maxEntries <= 0 || ttlMs <= 0L) return false
        val now = timeProvider()
        synchronized(lock) {
            val failedAt = map[url] ?: return false
            if (now - failedAt >= ttlMs) {
                map.remove(url)
                return false
            }
            return true
        }
    }

    fun remove(url: String) {
        synchronized(lock) {
            map.remove(url)
        }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return map.size
        }
    }

    companion object {
        const val DEFAULT_MAX_FAIL_URLS = 512
        const val DEFAULT_FAIL_URL_TTL_MS = 60_000L
    }
}


class OkHttpStreamFetcher(
    private val url: GlideUrl,
    private val options: Options,
) :
    DataFetcher<InputStream>, okhttp3.Callback {
    private var stream: InputStream? = null
    private var responseBody: ResponseBody? = null
    private var callback: DataFetcher.DataCallback<in InputStream>? = null
    private var source: BaseSource? = null
    private val manga = options.get(OkHttpModelLoader.mangaOption) == true
    private val coroutineContext = SupervisorJob()
    private val coroutineScope = CoroutineScope(coroutineContext)
    private lateinit var analyzedUrl: GlideUrl

    @Volatile
    private var call: Call? = null

    companion object {
        // loadData 在 Glide 线程读取，onResponse/onFailure 在 OkHttp 回调线程写入，需有界 TTL LRU 缓存
        internal val failUrl = FailedUrlCache()
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (failUrl.contains(url.toStringUrl())) {
            callback.onLoadFailed(NoStackTraceException("跳过加载失败的图片"))
            return
        }
        val loadOnlyWifi = options.get(OkHttpModelLoader.loadOnlyWifiOption) ?: false
        if (loadOnlyWifi && !appCtx.isWifiConnect) {
            callback.onLoadFailed(NoStackTraceException("只在wifi加载图片"))
            return
        }

        options.get(OkHttpModelLoader.sourceOriginOption)?.let { sourceUrl ->
            source = SourceHelp.getSource(sourceUrl)
        }

        analyzedUrl = AnalyzeUrl(
            url.toString(),
            dnsScope = DnsScope.IMAGE,
            source = source,
            coroutineContext = coroutineContext
        ).getGlideUrl()

        val requestBuilder = Request.Builder().url(analyzedUrl.toStringUrl())
        requestBuilder.addHeaders(analyzedUrl.headers)
        // 兜底:部分图床(CDN/WAF)会校验浏览器请求头,缺失时补默认 Accept 与 User-Agent
        analyzedUrl.headers?.let { headerMap ->
            if (headerMap.keys.none { it.equals("Accept", true) }) {
                requestBuilder.header(
                    "Accept",
                    "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
                )
            }
            if (headerMap.keys.none { it.equals("User-Agent", true) }) {
                requestBuilder.header("User-Agent", AppConfig.userAgent)
            }
        }
        val request: Request = requestBuilder.build()
        this.callback = callback
        call = if (manga) {
            okHttpClientManga.newCall(request)
        } else {
            okHttpClient.newCall(request)
        }
        call?.enqueue(this)
    }

    override fun cleanup() {
        kotlin.runCatching {
            stream?.close()
        }
        responseBody?.close()
        coroutineContext.cancel()
        callback = null
    }

    override fun cancel() {
        call?.cancel()
        coroutineContext.cancel()
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.REMOTE
    }

    override fun onFailure(call: Call, e: IOException) {
        callback?.onLoadFailed(e)
    }

    override fun onResponse(call: Call, response: Response) {
        responseBody = response.body
        if (!response.isSuccessful) {
            if (!manga) {
                failUrl.add(url.toStringUrl())
            }
            callback?.onLoadFailed(HttpException(response.message, response.code))
            return
        }
        if (ImageUtils.skipDecode(source, !manga)) {
            onStreamReady(responseBody!!.byteStream())
            return
        }
        Coroutine.async(coroutineScope, executeContext = IO) {
            val decodeResult = runScriptWithContext(coroutineContext) {
                if (manga) {
                    ImageUtils.decode(
                        url.toString(),
                        responseBody!!.bytes(),
                        isCover = false,
                        source,
                        ReadManga.book
                    )?.inputStream()
                } else {
                    ImageUtils.decode(
                        analyzedUrl.toStringUrl(), responseBody!!.byteStream(),
                        isCover = true, source
                    )
                }
            }
            onStreamReady(decodeResult)
        }
    }

    private fun onStreamReady(inputStream: InputStream?) {
        if (inputStream == null) {
            if (!manga) {
                failUrl.add(url.toStringUrl())
            }
            callback?.onLoadFailed(NoStackTraceException("封面二次解密失败"))
        } else {
            if (!manga) {
                failUrl.remove(url.toStringUrl())
            }
            val contentLength: Long =
                if (inputStream is ByteArrayInputStream) inputStream.available().toLong()
                else responseBody!!.contentLength()
            stream = ContentLengthInputStream.obtain(inputStream, contentLength)
            callback?.onDataReady(stream)
        }
    }

}
