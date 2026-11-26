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
    private val ftsSyntaxCharsRegex = Regex("""["*:()+\-^]""")
    private val pureWordTokenRegex = Regex("^[\\p{L}\\p{N}_]+$")
    private val ftsBooleanKeywords = setOf("AND", "OR", "NOT", "NEAR")

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
     * 剥离/转义 FTS4 语法字符与布尔关键字，CJK 词转单字短语，安全单词转前缀匹配，多词之间 AND。
     */
    fun buildMatchQuery(query: String): String? {
        val cleaned = ftsSyntaxCharsRegex.replace(query, " ").trim()
        if (cleaned.isEmpty()) return null
        val terms = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return null
        val expressions = terms.mapNotNull { term ->
            if (!term.any { it.isLetterOrDigit() }) return@mapNotNull null
            when {
                cjkRegex.containsMatchIn(term) -> {
                    val segmented = segmentText(term).trim()
                    if (segmented.isEmpty()) null else "\"$segmented\""
                }
                pureWordTokenRegex.matches(term) && term.uppercase() !in ftsBooleanKeywords -> {
                    "$term*"
                }
                else -> {
                    "\"$term\""
                }
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
        if (content.isBlank()) {
            appDb.libraryContentFtsDao.deleteChapter(bookUrl, chapterIndex)
            synchronized(indexedKeys) { indexedKeys.remove(keyOf(bookUrl, chapterIndex)) }
            return
        }
        val bounded = if (content.length > MaxIndexContentLength) {
            content.substring(0, MaxIndexContentLength)
        } else {
            content
        }
        val segmented = segmentText(bounded)
        appDb.runInTransaction {
            appDb.libraryContentFtsDao.deleteChapter(bookUrl, chapterIndex)
            appDb.libraryContentFtsDao.insert(
                LibraryContentFts(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    content = segmented
                )
            )
        }
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
     * 重建全库索引：按书在事务内原子替换（deleteByBook + insert），
     * 完成后再清理已不在书架的残留书目，避免中途取消时清空整个索引。
     * @return 索引的章节数
     */
    suspend fun rebuildAll(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        rebuilding = true
        try {
            runCatching {
                val dao = appDb.libraryContentFtsDao
                val books = appDb.bookDao.all
                val validUrls = books.map { it.bookUrl }.toHashSet()
                var indexed = 0
                books.forEachIndexed { index, book ->
                    // 取消检查：全库扫描很慢，调用方离开页面时要能立刻停下，
                    // 否则会空跑整个书库（且旧写法会让 rebuilding 永远复位不了）
                    currentCoroutineContext().ensureActive()
                    onProgress("[${index + 1}/${books.size}] ${book.name}")
                    if (book.isImage) {
                        runCatching {
                            dao.deleteByBook(book.bookUrl)
                            synchronized(indexedKeys) {
                                loadedBooks.add(book.bookUrl)
                                indexedKeys.removeAll { it.startsWith("${book.bookUrl}|") }
                            }
                        }.onFailure {
                            AppLog.put("全库搜索清理图片书索引失败: ${book.name}", it)
                        }
                        return@forEachIndexed
                    }
                    val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                    val items = ArrayList<LibraryContentFts>(chapters.size)
                    chapters.forEach { chapter ->
                        currentCoroutineContext().ensureActive()
                        val content = BookHelp.getContent(book, chapter) ?: return@forEach
                        if (content.isBlank()) return@forEach
                        val bounded = if (content.length > MaxIndexContentLength) {
                            content.substring(0, MaxIndexContentLength)
                        } else {
                            content
                        }
                        items.add(
                            LibraryContentFts(
                                bookUrl = book.bookUrl,
                                chapterIndex = chapter.index,
                                chapterTitle = chapter.title,
                                content = segmentText(bounded)
                            )
                        )
                    }
                    runCatching {
                        appDb.runInTransaction {
                            dao.deleteByBook(book.bookUrl)
                            if (items.isNotEmpty()) {
                                items.chunked(200).forEach { chunk ->
                                    dao.insert(*chunk.toTypedArray())
                                }
                            }
                        }
                        synchronized(indexedKeys) {
                            loadedBooks.add(book.bookUrl)
                            indexedKeys.removeAll { it.startsWith("${book.bookUrl}|") }
                            items.forEach { indexedKeys.add(keyOf(book.bookUrl, it.chapterIndex)) }
                        }
                        indexed += items.size
                    }.onFailure {
                        AppLog.put("全库搜索重建索引失败: ${book.name}", it)
                    }
                }
                // 清理已删除书籍的残留索引
                dao.indexedBookUrls().forEach { url ->
                    currentCoroutineContext().ensureActive()
                    if (url !in validUrls) {
                        dao.deleteByBook(url)
                        synchronized(indexedKeys) {
                            loadedBooks.remove(url)
                            indexedKeys.removeAll { it.startsWith("$url|") }
                        }
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
