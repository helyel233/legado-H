package io.legado.app.help.book

import android.util.LruCache
import java.io.File

/**
 * 正文内存缓存。
 *
 * 背景：正文落盘在 `book_cache` 目录，每次读取都要全量读盘。
 * 翻章、排版重算、滚动预览、缓存检查都会反复调用 `BookHelp.getContent`，
 * 同一章内容在一次阅读会话里会被读很多次。
 *
 * 这里按字符数做 LRU（sizeOf 返回字符数），上限 4M 字符 ≈ 8MB 内存。
 * key 带文件长度与修改时间，文件被改写或替换后自动失效。
 */
object ChapterContentCache {

    private const val MAX_CHARS = 4 * 1024 * 1024

    /**
     * 单章超过这个大小不入缓存，避免一章撑爆整个缓存
     */
    private const val MAX_ENTRY_CHARS = MAX_CHARS / 4

    private val cache = object : LruCache<String, String>(MAX_CHARS) {
        override fun sizeOf(key: String, value: String): Int = value.length
    }

    /**
     * 缓存键：路径 + 长度 + 修改时间，文件变化后自动换键
     */
    fun keyOf(file: File): String = "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    fun get(key: String): String? = cache.get(key)

    fun put(key: String, content: String) {
        if (content.length > MAX_ENTRY_CHARS) return
        cache.put(key, content)
    }

    /**
     * 清空缓存（删除缓存文件后调用，避免读到已删除的内容）
     */
    fun clear() {
        cache.evictAll()
    }
}
