package io.legado.app.help.book

import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    private val lock = Any()
    private val filePathToKey = ConcurrentHashMap<String, String>()

    private val cache = object : LruCache<String, String>(MAX_CHARS) {
        override fun sizeOf(key: String, value: String): Int = value.length

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: String,
            newValue: String?
        ) {
            if (newValue == null) {
                extractPath(key)?.let { path ->
                    filePathToKey.remove(path, key)
                }
            }
        }
    }

    /**
     * 缓存键：路径 + 长度 + 修改时间，文件变化后自动换键
     */
    fun keyOf(file: File): String = "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    fun get(key: String): String? = synchronized(lock) { cache.get(key) }

    fun put(key: String, content: String) {
        val path = extractPath(key)
        synchronized(lock) {
            if (content.length > MAX_ENTRY_CHARS) {
                if (path != null) {
                    filePathToKey.remove(path)?.let { previousKey ->
                        cache.remove(previousKey)
                    }
                }
                return
            }
            if (path != null) {
                val previousKey = filePathToKey.put(path, key)
                if (previousKey != null && previousKey != key) {
                    cache.remove(previousKey)
                }
            }
            cache.put(key, content)
        }
    }

    /**
     * 按缓存键移除单个条目。
     */
    fun remove(key: String): String? = synchronized(lock) { cache.remove(key) }

    /**
     * 精确移除某个缓存文件对应的所有内存缓存条目（不依赖文件当前 length/lastModified，删除文件前后均可调用）。
     */
    fun remove(file: File) {
        synchronized(lock) {
            filePathToKey.remove(file.absolutePath)?.let { key ->
                cache.remove(key)
            }
        }
    }

    /**
     * 精确移除一组候选缓存文件对应的所有内存缓存条目。
     */
    fun removeAll(files: Iterable<File>) {
        synchronized(lock) {
            for (file in files) {
                filePathToKey.remove(file.absolutePath)?.let { key ->
                    cache.remove(key)
                }
            }
        }
    }

    private fun extractPath(key: String): String? {
        val lastSep = key.lastIndexOf(':')
        if (lastSep <= 0 || lastSep >= key.lastIndex) return null
        val prevSep = key.lastIndexOf(':', lastSep - 1)
        if (prevSep <= 0) return null
        val lengthPart = key.substring(prevSep + 1, lastSep)
        val modifiedPart = key.substring(lastSep + 1)
        if (lengthPart.isEmpty() || !lengthPart.all { it.isDigit() }) return null
        if (!modifiedPart.all { it.isDigit() }) return null
        return key.substring(0, prevSep)
    }

    internal fun indexSizeForTest(): Int = filePathToKey.size

    /**
     * 清空缓存（删除全部缓存后调用，避免读到已删除的内容）
     */
    fun clear() {
        synchronized(lock) {
            filePathToKey.clear()
            cache.evictAll()
        }
    }
}
