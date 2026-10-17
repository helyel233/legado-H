package io.legado.app.help

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 封面位图进程级缓存。
 *
 * 书架 Compose 视图随 tab 切换会被销毁重建（MainActivity.viewPagerMain.offscreenPageLimit=1，
 * 离开超过一屏的 tab 再回来 Fragment 视图整体重建），组合重启后 BookCoverImage 的 remember
 * 状态丢失，所有可见封面会先回落到默认封面再异步加载回来，表现为「打开书架页整屏封面
 * 闪一下重新加载」。
 *
 * 这里只缓存「自己持有的位图」——缩略图文件解码结果或 Glide 结果的拷贝。
 * Glide 经 BitmapPool 回收复用的位图不能强引用：被池回收时可能被 recycle 或像素被改写，
 * 缓存后再画会错乱/崩溃。
 */
object CoverMemoryCache {

    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun get(key: String): Bitmap? {
        if (key.isBlank()) return null
        return cache.get(key)?.takeIf { !it.isRecycled }
    }

    fun put(key: String, bitmap: Bitmap?) {
        if (key.isBlank()) return
        val bmp = bitmap?.takeIf { !it.isRecycled } ?: return
        if (bmp.byteCount > MAX_CACHE_BYTES) return
        cache.put(key, bmp)
    }

    fun clear() {
        cache.evictAll()
    }
}
