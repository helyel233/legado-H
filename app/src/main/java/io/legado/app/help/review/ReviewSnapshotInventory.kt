package io.legado.app.help.review

import android.util.AtomicFile
import com.google.gson.stream.JsonReader
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import splitties.init.appCtx
import java.io.File

/**
 * 管理页的派生目录索引，不参与快照完整性判定，也不修改原始缓存。
 * 文件名、长度和修改时间用于增量更新；已有快照只读 chapterUrl 所在的 JSON 头，
 * 取得身份立即关闭流，绝不 skipValue 穿过 HTML 或检查资源 blob。
 * 索引放在应用临时缓存中，即使被系统清理也能从原文件重新建立。
 */
internal object ReviewSnapshotInventory {
    data class Entry(
        val size: Long,
        val modified: Long,
        val chapterUrl: String,
        val status: ReviewChapterSnapshotStatus? = null,
        val buttonSrc: String? = null,
    )

    data class SnapshotHeader(
        val bookUrl: String,
        val chapterUrl: String,
        val buttonSrc: String,
    )

    data class Inventory(val files: Map<String, Entry> = emptyMap())

    private data class CachedInventory(
        val length: Long,
        val modified: Long,
        val inventory: Inventory,
    )

    private const val MEMORY_CACHE_LIMIT = 8
    private val memoryCache = object : LinkedHashMap<String, CachedInventory>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedInventory>?): Boolean =
            size > MEMORY_CACHE_LIMIT
    }

    @Synchronized
    fun invalidate(dir: File) {
        val key = MD5Utils.md5Encode(dir.absolutePath)
        memoryCache.remove(key)
    }

    @Synchronized
    fun read(book: Book, checkActive: () -> Unit = {}): Inventory {
        checkActive()
        val dir = ReviewSnapshotStore.reviewsDir(book)
        if (!dir.exists()) return Inventory()
        val files = checkNotNull(dir.listFiles { file ->
            ReviewSnapshotStore.isSnapshotFile(file) || ReviewSnapshotStore.isChapterStatusFile(file)
        }) { "无法读取评论缓存目录: ${dir.absolutePath}" }
        val indexDir = File(appCtx.cacheDir, "review_inventory")
        check(indexDir.isDirectory || indexDir.mkdirs()) {
            "无法创建评论目录索引: ${indexDir.absolutePath}"
        }
        val dirKey = MD5Utils.md5Encode(dir.absolutePath)
        val index = AtomicFile(File(indexDir, "$dirKey.json"))
        val previous = readPreviousInventory(dirKey, index)
        val entries = files.associate { file ->
            checkActive()
            val size = file.length()
            val modified = file.lastModified()
            val old = previous.files[file.name]
            val isStatus = ReviewSnapshotStore.isChapterStatusFile(file)
            val entry = if (old != null && old.size == size && old.modified == modified && (isStatus || old.buttonSrc != null)) {
                old
            } else if (isStatus) {
                val status = requireNotNull(ReviewSnapshotStore.readChapterStatus(file))
                require(status.bookUrl == book.bookUrl) { "评论状态书籍不匹配: ${file.absolutePath}" }
                Entry(size, modified, status.chapterUrl.trim(), status)
            } else {
                val header = readHeader(file, book)
                Entry(size, modified, header.chapterUrl, buttonSrc = header.buttonSrc)
            }
            file.name to entry
        }
        val result = Inventory(entries)
        checkActive()
        if (result != previous) {
            val stream = index.startWrite()
            try {
                val writer = stream.writer(Charsets.UTF_8)
                GSON.toJson(result, writer)
                writer.flush()
                index.finishWrite(stream)
                val baseFile = index.baseFile
                memoryCache[dirKey] = CachedInventory(baseFile.length(), baseFile.lastModified(), result)
            } catch (error: Throwable) {
                index.failWrite(stream)
                memoryCache.remove(dirKey)
                throw error
            }
        }
        return result
    }

    private fun readPreviousInventory(dirKey: String, index: AtomicFile): Inventory {
        val baseFile = index.baseFile
        if (!baseFile.exists()) {
            memoryCache.remove(dirKey)
            return Inventory()
        }
        val length = baseFile.length()
        val modified = baseFile.lastModified()
        memoryCache[dirKey]?.let { cached ->
            if (cached.length == length && cached.modified == modified) {
                return cached.inventory
            }
        }
        val loaded = index.openRead().bufferedReader(Charsets.UTF_8).use {
            requireNotNull(GSON.fromJson(it, Inventory::class.java)) {
                "评论目录索引为空: ${baseFile.absolutePath}"
            }
        }
        memoryCache[dirKey] = CachedInventory(length, modified, loaded)
        return loaded
    }

    private fun readHeader(file: File, book: Book): SnapshotHeader {
        val header = readHeaderUnchecked(file)
        require(header.bookUrl == book.bookUrl && header.chapterUrl.isNotBlank() && header.buttonSrc.isNotBlank()) {
            "评论快照身份错误: ${file.absolutePath}"
        }
        return header
    }

    internal fun readHeaderUnchecked(file: File): SnapshotHeader {
        JsonReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
            var bookUrl: String? = null
            var chapterUrl: String? = null
            var buttonSrc: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "bookUrl" -> bookUrl = reader.nextString()
                    "chapterUrl" -> chapterUrl = reader.nextString().trim()
                    "buttonSrc" -> buttonSrc = reader.nextString().trim()
                    "html" -> {
                        if (bookUrl == null || chapterUrl == null) {
                            error("评论快照头缺少书籍或章节标识: ${file.absolutePath}")
                        }
                        reader.skipValue()
                    }
                    else -> reader.skipValue()
                }
                if (bookUrl != null && chapterUrl != null && buttonSrc != null) {
                    return SnapshotHeader(bookUrl, chapterUrl, buttonSrc)
                }
            }
            return SnapshotHeader(
                bookUrl = bookUrl.orEmpty(),
                chapterUrl = chapterUrl.orEmpty(),
                buttonSrc = buttonSrc.orEmpty(),
            )
        }
    }
}
