package io.legado.app.help.gsyVideo

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import androidx.core.graphics.toColorInt

class DanmakuAdapter(private val mDanmakuView: IDanmakuView?) : BaseCacheStuffer.Proxy() {
    private var mDrawable: Drawable? = null


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
            // FIXME 这里只是简单启个线程来加载远程url图片，请使用你自己的异步线程池，最好加上你的缓存池
            object : Thread() {
                override fun run() {
                    val url = "http://www.bilibili.com/favicon.ico"
                    var inputStream: InputStream? = null
                    var loaded = mDrawable
                    if (loaded == null) {
                        try {
                            val urlConnection = URL(url).openConnection()
                            inputStream = urlConnection.getInputStream()
                            loaded = BitmapDrawable.createFromStream(inputStream, "bitmap")
                            mDrawable = loaded
                        } catch (e: MalformedURLException) {
                            io.legado.app.constant.AppLog.put("弹幕图片加载失败", e)
                        } catch (e: IOException) {
                            io.legado.app.constant.AppLog.put("弹幕图片加载失败", e)
                        } finally {
                            IOUtils.closeQuietly(inputStream)
                        }
                    }
                    if (loaded != null) {
                        loaded.setBounds(0, 0, 100, 100)
                        val spannable = createSpannable(loaded)
                        danmaku.text = spannable
                        mDanmakuView?.invalidateDanmaku(danmaku, false)
                    }
                }
            }.start()
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