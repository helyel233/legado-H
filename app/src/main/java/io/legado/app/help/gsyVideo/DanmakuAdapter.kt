package io.legado.app.help.gsyVideo

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ImageSpan
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.android.BaseCacheStuffer
import master.flame.danmaku.danmaku.util.IOUtils
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DanmakuAdapter(private val mDanmakuView: IDanmakuView?) : BaseCacheStuffer.Proxy() {
    private var mDrawable: Drawable? = null

    companion object {
        // 共享 IO 调度 + 内存缓存 + 在途去重：图文弹幕可能高频触发 prepareDrawing，
        // 不再每条弹幕裸起一个 Thread（原 FIXME 遗留），重复 URL 也不重复下载
        private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val drawableCache = LruCache<String, Drawable>(8)
        private val loadingUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private const val EMOJI_URL = "http://www.bilibili.com/favicon.ico"
    }

    override fun prepareDrawing(danmaku: BaseDanmaku, fromWorkerThread: Boolean) {
        if (danmaku.text is Spanned) { // 根据你的条件检查是否需要需要更新弹幕
            var drawable = mDrawable
            if (drawable != null) {
                // 已有缓存 drawable 时直接复用，不再重复起线程
                drawable.setBounds(0, 0, 100, 100)
                danmaku.text = createSpannable(drawable)
                mDanmakuView?.invalidateDanmaku(danmaku, false)
                return
            }
            drawableCache.get(EMOJI_URL)?.let { cached ->
                mDrawable = cached
                cached.setBounds(0, 0, 100, 100)
                danmaku.text = createSpannable(cached)
                mDanmakuView?.invalidateDanmaku(danmaku, false)
                return
            }
            if (!loadingUrls.add(EMOJI_URL)) return
            loadScope.launch {
                // 无论成功、失败还是中途抛异常，都必须把 URL 从在途集合移除：
                // 漏掉一次这条弹幕的图片会在此后整个进程生命周期内都加载不出来
                try {
                    var loaded: Drawable? = null
                    var inputStream: InputStream? = null
                    try {
                        val urlConnection = URL(EMOJI_URL).openConnection()
                        inputStream = urlConnection.getInputStream()
                        loaded = BitmapDrawable.createFromStream(inputStream, "bitmap")
                    } catch (e: MalformedURLException) {
                        io.legado.app.constant.AppLog.put("弹幕图片加载失败", e)
                    } catch (e: IOException) {
                        io.legado.app.constant.AppLog.put("弹幕图片加载失败", e)
                    } finally {
                        IOUtils.closeQuietly(inputStream)
                    }
                    if (loaded != null) {
                        drawableCache.put(EMOJI_URL, loaded)
                        mDrawable = loaded
                        loaded.setBounds(0, 0, 100, 100)
                        val spannable = createSpannable(loaded)
                        danmaku.text = spannable
                        mDanmakuView?.invalidateDanmaku(danmaku, false)
                    }
                } finally {
                    loadingUrls.remove(EMOJI_URL)
                }
            }
        }
    }


    override fun releaseResource(danmaku: BaseDanmaku?) {
        // 清理含有 ImageSpan 的 text 中占内存的资源（drawable 引用），避免图文混排弹幕持有 bitmap。
        // 只摘 span、不改 text：保留 Spanned 形态，后续 prepareDrawing 才能重新挂回图片。
        val text = danmaku?.text as? Spanned ?: return
        if (text !is Spannable) return
        val spans = text.getSpans(0, text.length, ImageSpan::class.java)
        for (span in spans) {
            text.removeSpan(span)
        }
    }


    private fun createSpannable(drawable: Drawable): SpannableStringBuilder {
        val text = "bitmap"
        val spannableStringBuilder = SpannableStringBuilder(text)
        val span = ImageSpan(drawable) //ImageSpan.ALIGN_BOTTOM);
        spannableStringBuilder.setSpan(span, 0, text.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        spannableStringBuilder.append("图文混排")
        spannableStringBuilder.setSpan(
            BackgroundColorSpan("#8A2233B1".toColorInt()),
            0,
            spannableStringBuilder.length,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE
        )
        return spannableStringBuilder
    }
}