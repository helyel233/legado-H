package io.legado.app.help.storage

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.io.File

/**
 * 书籍缓存选择配置（移植自 Legado_Max）
 *
 * 记录用户勾选要单独导出缓存的具体书籍。
 */
object BookCacheSelectorConfig {

    private val configPath = FileUtils.getPath(appCtx.filesDir, "bookCacheSelector.json")

    private var selectedBookUrls: MutableSet<String> = load()

    private fun load(): MutableSet<String> {
        val set = HashSet<String>()
        val file = File(configPath)
        if (file.exists() && file.length() > 0) {
            GSON.fromJsonObject<Set<String>>(file.readText()).getOrNull()?.let {
                set.addAll(it)
            }
        }
        return set
    }

    /** 获取所有有缓存的书籍 */
    fun getBooksWithCache(): List<Book> {
        val folderNames = getCacheFolderNames()
        if (folderNames.isEmpty()) {
            return emptyList()
        }
        return appDb.bookDao.all.filter { book ->
            book.getFolderName() in folderNames
        }
    }

    private fun getCacheFolderNames(): Set<String> {
        val cacheDir = File(BookHelp.cachePath)
        if (!cacheDir.isDirectory) {
            return emptySet()
        }
        return cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    fun isSelected(book: Book): Boolean {
        return selectedBookUrls.contains(book.bookUrl)
    }

    fun setSelected(book: Book, selected: Boolean) {
        if (selected) {
            selectedBookUrls.add(book.bookUrl)
        } else {
            selectedBookUrls.remove(book.bookUrl)
        }
    }

    /** 获取选中的书籍列表 */
    fun getSelectedBooks(): List<Book> {
        return getBooksWithCache().filter { isSelected(it) }
    }

    /** 持久化配置 */
    fun save() {
        FileUtils.createFileIfNotExist(configPath).writeText(GSON.toJson(selectedBookUrls.toSet()))
    }
}
