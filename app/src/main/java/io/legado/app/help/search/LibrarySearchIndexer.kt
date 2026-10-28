package io.legado.app.help.search

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.LibraryContentFts
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全库全文搜索索引器：维护 libraryContentFts 表。
 * 只有已缓存正文的章节会被索引（与 .nb 缓存一致）。
 * 中文以 unicode61 无法分词，索引与查询两侧均做 CJK 单字切分后再短语匹配。
 */
object LibrarySearchIndexer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cjkRegex = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")

    /** 全库重建进行中标志：重建覆盖全部章节，期间不再接受单章懒索引 */
    @Volatile
    private var rebuilding = false

    /** 已装载过索引清单的书（避免每章都查一次 FTS） */
    private val loadedBooks = HashSet<String>()

    /** 已索引的章节键 "<bookUrl>|<chapterIndex>" */
    private val indexedKeys = HashSet<String>()

    private fun keyOf(bookUrl: String, chapterIndex: Int) = "$bookUrl|$chapterIndex"

    /** 单章索引正文上限，超过则截断，避免 FTS 行过大 */
    private const val MaxIndexContentLength = 512_000

    /**
     * 在每个 CJK 字符后插入空格，使其成为独立 token，
     * 配合 unicode61 tokenizer 实现任意中文子串的短语匹配。
     */
    fun segmentText(text: String): String {
        if (!cjkRegex.containsMatchIn(text)) return text
        val sb = StringBuilder(text.length + 16)
        text.forEach { ch ->
            sb.append(ch)
            if (cjkRegex.matches(ch.toString())) sb.append(' ')
        }
        return sb.toString()
    }

    /**
     * 将用户查询转为 FTS MATCH 表达式。
     * CJK 词转单字短语，非 CJK 词转前缀匹配，多词之间 AND。
     */
    fun buildMatchQuery(query: String): String? {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return null
        val expressions = terms.mapNotNull { term ->
            val sanitized = term.replace("\"", "")
            if (sanitized.isEmpty()) return@mapNotNull null
            if (cjkRegex.containsMatchIn(sanitized)) {
                "\"" + segmentText(sanitized).trim() + "\""
            } else {
                sanitized + "*"
            }
        }
        if (expressions.isEmpty()) return null
        return expressions.joinToString(" ")
    }

    /**
     * 单章索引（异步 fire-and-forget），供 BookHelp.saveText 钩子与本地 txt 懒索引调用。
     * 全库重建期间跳过：重建本就覆盖全部章节，避免并发重复插入。
     */
    fun indexChapterAsync(book: Book, chapter: BookChapter, content: String) {
        if (book.isImage || rebuilding) return
        scope.launch {
            runCatching {
                indexChapterInternal(book.bookUrl, chapter.index, chapter.title, content)
            }.onFailure {
                AppLog.put("全库搜索单章索引失败: ${book.name}:${chapter.index}", it)
            }
        }
    }

    /**
     * 单章是否已建索引。本地 txt 正文不落盘、每次阅读都现读，
     * 用它在阅读时懒加载建索引并避免重复建同一章。
     *
     * FTS 虚表无法为 bookUrl/chapterIndex 建普通索引，直接 count 查询会退化为全表扫描
     * （索引大时是每次读章的隐形开销），所以在进程内维护一份已索引键集合，
     * 每本书首次查询时整取一次，之后都是 O(1) 命中。
     */
    fun hasChapterIndexed(bookUrl: String, chapterIndex: Int): Boolean {
        val key = keyOf(bookUrl, chapterIndex)
        synchronized(indexedKeys) {
            if (indexedKeys.contains(key)) return true
            if (bookUrl !in loadedBooks) {
                loadedBooks.add(bookUrl)
                runCatching {
                    appDb.libraryContentFtsDao.indexedChapterIndexes(bookUrl).forEach {
                        indexedKeys.add(keyOf(bookUrl, it))
                    }
                }.onFailure {
                    AppLog.put("全库搜索装载已索引章节失败: $bookUrl", it)
                }
            }
            return indexedKeys.contains(key)
        }
    }

    suspend fun indexChapter(
        book: Book,
        chapter: BookChapter,
        content: String? = null
    ) = withContext(Dispatchers.IO) {
        if (book.isImage) return@withContext
        val text = content ?: BookHelp.getContent(book, chapter) ?: return@withContext
        runCatching {
            indexChapterInternal(book.bookUrl, chapter.index, chapter.title, text)
        }.onFailure {
            AppLog.put("全库搜索单章索引失败: ${book.name}:${chapter.index}", it)
        }
    }

    private fun indexChapterInternal(
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        content: String
    ) {
        appDb.libraryContentFtsDao.deleteChapter(bookUrl, chapterIndex)
        if (content.isBlank()) {
            synchronized(indexedKeys) { indexedKeys.remove(keyOf(bookUrl, chapterIndex)) }
            return
        }
        val bounded = if (content.length > MaxIndexContentLength) {
            content.substring(0, MaxIndexContentLength)
        } else {
            content
        }
        appDb.libraryContentFtsDao.insert(
            LibraryContentFts(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                content = segmentText(bounded)
            )
        )
        synchronized(indexedKeys) { indexedKeys.add(keyOf(bookUrl, chapterIndex)) }
    }

    /**
     * 删除单章索引（异步），供 BookHelp.delContent 钩子调用。
     */
    fun deleteChapterAsync(bookUrl: String, chapterIndex: Int) {
        scope.launch {
            runCatching {
                appDb.libraryContentFtsDao.deleteChapter(bookUrl, chapterIndex)
                synchronized(indexedKeys) { indexedKeys.remove(keyOf(bookUrl, chapterIndex)) }
            }.onFailure {
                AppLog.put("全库搜索删除章索引失败: $bookUrl:$chapterIndex", it)
            }
        }
    }

    /**
     * 删除整书索引（异步），供 BookHelp.clearCache(book) 钩子调用。
     * 本地 txt 切换编码时也会调用（旧索引是旧编码的产物）。
     */
    fun deleteBookAsync(bookUrl: String) {
        scope.launch {
            runCatching {
                appDb.libraryContentFtsDao.deleteByBook(bookUrl)
                synchronized(indexedKeys) {
                    loadedBooks.remove(bookUrl)
                    indexedKeys.removeAll { it.startsWith("$bookUrl|") }
                }
            }.onFailure {
                AppLog.put("全库搜索删除书索引失败: $bookUrl", it)
            }
        }
    }

    /**
     * 重建全库索引：清除旧表数据后扫描书架上所有有缓存正文的书。
     * 同时清理已删除书籍的残留行。
     * @return 索引的章节数
     */
    suspend fun rebuildAll(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        rebuilding = true
        try {
            runCatching {
                val dao = appDb.libraryContentFtsDao
                dao.deleteAll()
                synchronized(indexedKeys) {
                    indexedKeys.clear()
                    loadedBooks.clear()
                }
                val books = appDb.bookDao.all
                val validUrls = books.map { it.bookUrl }.toHashSet()
                var indexed = 0
                books.forEachIndexed { index, book ->
                    // 取消检查：全库扫描很慢，调用方离开页面时要能立刻停下，
                    // 否则会空跑整个书库（且旧写法会让 rebuilding 永远复位不了）
                    currentCoroutineContext().ensureActive()
                    onProgress("[${index + 1}/${books.size}] ${book.name}")
                    if (book.isImage) return@forEachIndexed
                    val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                    chapters.forEach { chapter ->
                        currentCoroutineContext().ensureActive()
                        val content = BookHelp.getContent(book, chapter) ?: return@forEach
                        runCatching {
                            indexChapterInternal(book.bookUrl, chapter.index, chapter.title, content)
                            indexed++
                        }.onFailure {
                            AppLog.put("全库搜索重建索引失败: ${book.name}:${chapter.index}", it)
                        }
                    }
                }
                // 清理已删除书籍的残留索引
                appDb.libraryContentFtsDao.indexedBookUrls().forEach { url ->
                    if (url !in validUrls) {
                        appDb.libraryContentFtsDao.deleteByBook(url)
                    }
                }
                indexed
            }.getOrElse { error ->
                // 取消不是失败：向上抛，让调用方真正结束协程
                if (error is CancellationException) throw error
                AppLog.put("全库搜索重建索引失败", error)
                -1
            }
        } finally {
            // 必须在 withContext 内部复位：写在 .also 里的话取消路径永远走不到，
            // rebuilding 会一直是 true，之后新章节再也不进索引
            rebuilding = false
        }
    }
}
