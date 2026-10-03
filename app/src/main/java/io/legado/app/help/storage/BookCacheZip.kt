package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.SafeZipExtractor
import io.legado.app.utils.compress.SafeZipLimits
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.postEvent
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 章节缓存信息
 * 用于记录单个章节的缓存文件信息。
 *
 * 唯一一份定义：「书籍缓存单独导出 ZIP」与常规备份恢复共用，
 * 字段名与 Legado_Max 保持一致，导出的 ZIP 可互相导入。
 */
data class ChapterCacheInfo(
    val index: Int,           // 章节序号
    val title: String,        // 章节标题
    val titleMD5: String,     // 标题MD5
    val fileName: String      // 原始文件名
)

/**
 * 书籍缓存索引数据类
 * 用于记录导出包中每本书的缓存信息，恢复时用于匹配。
 */
data class BookCacheIndex(
    val bookUrl: String,
    val bookName: String,
    val author: String,
    val folderName: String,
    val chapters: List<ChapterCacheInfo> = emptyList()
)

/**
 * 书籍缓存 ZIP 打包/恢复（移植自 Legado_Max 的书籍缓存单独导出）
 *
 * 包结构：
 * - book_cache/{folderName}/ 章节正文缓存文件
 * - bookCacheIndex.json      书籍缓存索引
 * - bookCacheBooks.json      书籍元数据
 * - bookChapterCache.json    章节目录
 */
object BookCacheZip {

    private const val bookCacheFolderName = "book_cache"
    private const val indexFileName = "bookCacheIndex.json"
    private const val booksFileName = "bookCacheBooks.json"
    private const val chaptersFileName = "bookChapterCache.json"

    /** 导出选中书籍的缓存为 ZIP，写入 targetUri 指向的目录（IO 线程执行） */
    suspend fun exportToZip(
        context: Context,
        targetUri: Uri,
        selectedBooks: List<Book>,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val cacheDir = File(BookHelp.cachePath)
        if (selectedBooks.isEmpty() || !cacheDir.isDirectory) return@withContext

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val zipName = appCtx.getString(R.string.book_cache_export_name, timestamp)

        val tempDir = File(appCtx.cacheDir, "book_cache_export").createFolderIfNotExist()
        try {
            val indexList = mutableListOf<BookCacheIndex>()
            val exportBooks = mutableListOf<Book>()
            val allChapters = mutableListOf<BookChapter>()
            val zipSources = mutableListOf<File>()
            val tempCacheDir = File(tempDir, bookCacheFolderName).createFolderIfNotExist()

            selectedBooks.forEachIndexed { index, book ->
                onProgress(appCtx.getString(R.string.book_cache_export_progress, index + 1, selectedBooks.size, book.name))

                val folderName = book.getFolderName()
                val bookFolder = File(cacheDir, folderName)
                if (!bookFolder.isDirectory) return@forEachIndexed

                val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                val chapterMap = chapterList.associateBy { it.index }
                allChapters.addAll(chapterList)

                val chapterCacheInfos = mutableListOf<ChapterCacheInfo>()
                bookFolder.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".nb")) {
                        parseChapterFileName(file.name, chapterMap)?.let {
                            chapterCacheInfos.add(it)
                        }
                    }
                }

                indexList.add(
                    BookCacheIndex(
                        bookUrl = book.bookUrl,
                        bookName = book.name,
                        author = book.author ?: "",
                        folderName = folderName,
                        chapters = chapterCacheInfos.sortedBy { it.index }
                    )
                )

                val targetBookDir = File(tempCacheDir, folderName).createFolderIfNotExist()
                if (!bookFolder.copyRecursively(targetBookDir, overwrite = true)) {
                    AppLog.put("书籍缓存导出复制不完整：${book.name}")
                }
                exportBooks.add(book)
            }

            if (indexList.isEmpty()) return@withContext
            zipSources.add(tempCacheDir)

            val indexFile = File(tempDir, indexFileName)
            indexFile.writeText(GSON.toJson(indexList))
            zipSources.add(indexFile)

            val booksFile = File(tempDir, booksFileName)
            booksFile.writeText(GSON.toJson(exportBooks))
            zipSources.add(booksFile)

            if (allChapters.isNotEmpty()) {
                val chapterFile = File(tempDir, chaptersFileName)
                chapterFile.writeText(GSON.toJson(allChapters))
                zipSources.add(chapterFile)
            }

            onProgress(appCtx.getString(R.string.book_cache_export_packaging))
            val tempZip = File(tempDir, zipName)
            ZipUtils.zipFiles(zipSources, tempZip)

            onProgress(appCtx.getString(R.string.book_cache_export_writing))
            writeZipToTarget(context, targetUri, tempZip, zipName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** 从 ZIP 恢复书籍缓存（书架 + 章节目录 + 正文文件），IO 线程执行 */
    suspend fun importFromZip(
        context: Context,
        zipUri: Uri,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tempDir = File(appCtx.cacheDir, "book_cache_import").createFolderIfNotExist()
        try {
            val zipFile = if (zipUri.isContentScheme()) {
                val outFile = File(tempDir, "import.zip")
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext
                outFile
            } else {
                File(zipUri.path!!)
            }
            onProgress(appCtx.getString(R.string.book_cache_import_extracting))
            SafeZipExtractor.extract(
                zipFile,
                tempDir,
                SafeZipLimits(
                    maxEntries = 200_000,
                    maxEntryBytes = 64L * 1024 * 1024,
                    maxTotalBytes = 8L * 1024 * 1024 * 1024,
                    maxCompressionRatio = 200
                ),
                filter = { name -> !name.endsWith(".zip") }
            )
            importFromDir(tempDir, onProgress)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** 从目录恢复书籍缓存，目录需含索引文件（兼容常规备份目录内 book_cache 结构） */
    suspend fun importFromDir(dir: File, onProgress: (String) -> Unit) {
        restoreIndexedBookCache(dir, onProgress)
    }

    /**
     * 若目录内存在书籍缓存索引（Max 语义备份），恢复书架记录、章节目录与正文缓存。
     * 常规备份恢复（Restore.restoreBookFiles）结束后也会调用本方法。
     */
    internal suspend fun restoreIndexedBookCache(path: File, onProgress: (String) -> Unit = {}) {
        val indexFile = File(path, indexFileName)
        if (!indexFile.isFile) return

        val cacheIndexList = parseIndexList(indexFile.readText()) ?: return
        if (cacheIndexList.isEmpty()) return

        onProgress(appCtx.getString(R.string.book_cache_import_restoring_shelf))
        restoreBooksToShelf(path, cacheIndexList)

        onProgress(appCtx.getString(R.string.book_cache_import_restoring_toc))
        restoreChapterCache(path, cacheIndexList)

        onProgress(appCtx.getString(R.string.book_cache_import_restoring_files))
        restoreCacheFiles(path, cacheIndexList)
    }

    /** 恢复书籍到书架（只补缺失的书，已有则跳过） */
    private suspend fun restoreBooksToShelf(path: File, cacheIndexList: List<BookCacheIndex>) {
        val backupBooks = readJsonFileList<Book>(File(path, booksFileName))
            .orEmpty()
        val books = backupBooks.ifEmpty {
            cacheIndexList.map {
                Book(
                    bookUrl = it.bookUrl,
                    name = it.bookName,
                    author = it.author,
                    originName = it.bookName
                )
            }
        }
        if (books.isEmpty()) return

        val localBooks = appDb.bookDao.all
        val missingBooks = books.filter { book ->
            findMatchingBook(book.bookUrl, book.name, book.author ?: "", localBooks) == null
        }.map { book ->
            book.copy(
                group = 0,
                type = book.type and BookType.notShelf.inv()
            )
        }
        if (missingBooks.isNotEmpty()) {
            appDb.bookDao.insert(*missingBooks.toTypedArray())
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    /** 恢复章节目录（覆盖本地同名书目的章节） */
    private suspend fun restoreChapterCache(path: File, cacheIndexList: List<BookCacheIndex>) {
        val chapters = readJsonFileList<BookChapter>(File(path, chaptersFileName))
        if (chapters.isNullOrEmpty()) return

        val allBooks = appDb.bookDao.all
        chapters.groupBy { it.bookUrl }.forEach { (bookUrl, chapterList) ->
            val book = appDb.bookDao.getBook(bookUrl)
            val resolvedChapters = if (book != null) {
                chapterList
            } else {
                // 章节对应的 bookUrl 在本地不存在，按缓存索引书名匹配已有书后改写 bookUrl
                val cacheIndex = cacheIndexList.find { it.bookUrl == bookUrl }
                    ?: return@forEach
                val matchedBook = findMatchingBook(
                    bookUrl, cacheIndex.bookName, cacheIndex.author, allBooks
                ) ?: return@forEach
                chapterList.map { it.copy(bookUrl = matchedBook.bookUrl) }
            }
            val targetBookUrl = resolvedChapters.first().bookUrl
            appDb.runInTransaction {
                appDb.bookChapterDao.delByBook(targetBookUrl)
                appDb.bookChapterDao.insert(*resolvedChapters.toTypedArray())
            }
        }
    }

    /** 恢复正文缓存文件，按章节序号/标题重命名到本地目录 */
    private suspend fun restoreCacheFiles(path: File, cacheIndexList: List<BookCacheIndex>) {
        var backupCacheDir = File(path, bookCacheFolderName)
        if (!backupCacheDir.isDirectory) {
            backupCacheDir = path
        }
        if (cacheIndexList.none { File(backupCacheDir, it.folderName).isDirectory }) return

        val targetCacheDir = File(BookHelp.cachePath)
        if (!targetCacheDir.exists()) targetCacheDir.mkdirs()

        val allBooks = appDb.bookDao.all
        cacheIndexList.forEach { cacheIndex ->
            val matchedBook = findMatchingBook(
                cacheIndex.bookUrl, cacheIndex.bookName, cacheIndex.author, allBooks
            ) ?: return@forEach

            val sourceCacheDir = File(backupCacheDir, cacheIndex.folderName)
            if (!sourceCacheDir.isDirectory) return@forEach

            val targetBookDir = File(targetCacheDir, matchedBook.getFolderName())
            if (!targetBookDir.exists()) targetBookDir.mkdirs()

            val currentChapters = appDb.bookChapterDao.getChapterList(matchedBook.bookUrl)
            val byIndex = currentChapters.associateBy { it.index }
            val byTitle = currentChapters.associateBy { it.title }

            val copiedSourceNames = hashSetOf<String>()
            cacheIndex.chapters.forEach { chapterInfo ->
                val sourceFile = File(sourceCacheDir, chapterInfo.fileName)
                if (!sourceFile.isFile) return@forEach

                val targetChapter = byIndex[chapterInfo.index] ?: byTitle[chapterInfo.title]
                if (targetChapter == null) return@forEach

                sourceFile.copyTo(File(targetBookDir, targetChapter.getFileName()), overwrite = true)
                copiedSourceNames.add(sourceFile.name)
            }

            // 索引未收录的章节文件原样拷贝
            sourceCacheDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".nb") && it.name !in copiedSourceNames }
                ?.forEach { sourceFile ->
                    sourceFile.copyTo(File(targetBookDir, sourceFile.name), overwrite = true)
                }

            // 复制图片文件夹（如果有）
            val sourceImageDir = File(sourceCacheDir, "images")
            if (sourceImageDir.isDirectory) {
                if (!sourceImageDir.copyRecursively(File(targetBookDir, "images"), overwrite = true)) {
                    AppLog.put("书籍缓存恢复图片复制不完整：${cacheIndex.bookName}")
                }
            }
        }
    }

    /** 匹配书籍：bookUrl 精确 -> 书名+作者 -> 书名 */
    private fun findMatchingBook(
        bookUrl: String,
        bookName: String,
        author: String?,
        allBooks: List<Book>
    ): Book? {
        allBooks.find { it.bookUrl == bookUrl }?.let { return it }
        val normalizedAuthor = author?.trim().orEmpty()
        // 作者非空时要求书名+作者都匹配，避免同名不同作者的书被误匹配导致正文串书；
        // 仅当备份作者信息缺失时才退化为仅书名匹配
        allBooks.filter {
            it.name == bookName &&
                (normalizedAuthor.isEmpty() || (it.author?.trim().orEmpty()) == normalizedAuthor)
        }.firstOrNull()?.let { return it }
        if (normalizedAuthor.isNotEmpty()) return null
        return allBooks.find { it.name == bookName }
    }

    private fun parseChapterFileName(
        fileName: String,
        chapterMap: Map<Int, BookChapter>
    ): ChapterCacheInfo? {
        if (!fileName.endsWith(".nb")) return null
        val nameWithoutExt = fileName.removeSuffix(".nb")
        val parts = nameWithoutExt.split("-")
        if (parts.size != 2) return null
        val index = parts[0].toIntOrNull() ?: return null
        val titleMD5 = parts[1]
        val chapter = chapterMap[index] ?: return null
        return ChapterCacheInfo(
            index = index,
            title = chapter.title,
            titleMD5 = titleMD5,
            fileName = fileName
        )
    }

    private fun parseIndexList(json: String): List<BookCacheIndex>? {
        return GSON.fromJsonArray<BookCacheIndex>(json).getOrNull()
    }

    private inline fun <reified T> readJsonFileList(file: File): List<T>? {
        if (!file.isFile) return null
        return runCatching {
            GSON.fromJsonArray<T>(file.readText()).getOrNull()
        }.getOrNull()
    }

    private fun writeZipToTarget(context: Context, targetUri: Uri, zipFile: File, zipName: String) {
        if (targetUri.isContentScheme()) {
            val targetDir = DocumentFile.fromTreeUri(context, targetUri) ?: return
            val docFile = targetDir.findFile(zipName)
                ?: targetDir.createFile("application/zip", zipName)
                ?: return
            context.contentResolver.openOutputStream(docFile.uri)?.use { output ->
                zipFile.inputStream().use { input -> input.copyTo(output) }
            }
        } else {
            zipFile.copyTo(File(targetUri.path!!, zipName), overwrite = true)
        }
    }
}
