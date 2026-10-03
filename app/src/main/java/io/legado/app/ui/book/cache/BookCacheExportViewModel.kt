package io.legado.app.ui.book.cache

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.storage.BookCacheSelectorConfig
import io.legado.app.help.storage.BookCacheZip
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 单本书缓存条目 */
data class BookCacheExportItem(
    val book: Book,
    val cacheSize: Long,
    val formattedSize: String,
    val isSelected: Boolean
)

/** 导出界面状态 */
sealed class BookCacheExportState {
    data object Loading : BookCacheExportState()
    data object Idle : BookCacheExportState()
    data class Exporting(val message: String) : BookCacheExportState()
}

/**
 * 书籍缓存单独导出（移植自 Legado_Max）：勾选书籍后打包缓存为 ZIP，
 * 含缓存索引，可在本机或其他设备恢复书架与章节内容。
 */
class BookCacheExportViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<BookCacheExportState>(BookCacheExportState.Loading)
    val state: StateFlow<BookCacheExportState> = _state.asStateFlow()

    private val _items = MutableStateFlow<List<BookCacheExportItem>>(emptyList())
    val items: StateFlow<List<BookCacheExportItem>> = _items.asStateFlow()

    init {
        loadBooks()
    }

    fun loadBooks() {
        viewModelScope.launch {
            _state.value = BookCacheExportState.Loading
            runCatching {
                val books = withContext(Dispatchers.IO) {
                    BookCacheSelectorConfig.getBooksWithCache()
                }
                books.map { book ->
                    async(Dispatchers.IO) {
                        val size = calculateBookCacheSize(book)
                        BookCacheExportItem(
                            book = book,
                            cacheSize = size,
                            formattedSize = ConvertUtils.formatFileSize(size),
                            isSelected = BookCacheSelectorConfig.isSelected(book)
                        )
                    }
                }.awaitAll()
            }.onSuccess { items ->
                _items.value = items
                _state.value = BookCacheExportState.Idle
            }.onFailure {
                _items.value = emptyList()
                _state.value = BookCacheExportState.Idle
            }
        }
    }

    fun toggleSelect(book: Book) {
        val current = _items.value.toMutableList()
        val index = current.indexOfFirst { it.book.bookUrl == book.bookUrl }
        if (index == -1) return
        val item = current[index]
        current[index] = item.copy(isSelected = !item.isSelected)
        BookCacheSelectorConfig.setSelected(item.book, !item.isSelected)
        persistSelection()
        _items.value = current
    }

    fun setAllSelected(selected: Boolean) {
        _items.value = _items.value.map { item ->
            BookCacheSelectorConfig.setSelected(item.book, selected)
            item.copy(isSelected = selected)
        }
        persistSelection()
    }

    fun isAllSelected(): Boolean {
        val items = _items.value
        return items.isNotEmpty() && items.all { it.isSelected }
    }

    /** 持久化勾选状态（重启后保留） */
    private fun persistSelection() {
        viewModelScope.launch(Dispatchers.IO) {
            BookCacheSelectorConfig.save()
        }
    }

    fun exportSelectedBooks(targetUri: Uri) {
        val selectedBooks = _items.value.filter { it.isSelected }.map { it.book }
        if (selectedBooks.isEmpty()) return
        viewModelScope.launch {
            _state.value = BookCacheExportState.Exporting("")
            runCatching {
                BookCacheZip.exportToZip(getApplication(), targetUri, selectedBooks) { message ->
                    _state.value = BookCacheExportState.Exporting(message)
                }
            }.onFailure {
                AppLog.put("书籍缓存导出失败\n${it.localizedMessage}", it)
                appCtx.toastOnUi("导出失败：${it.localizedMessage}")
            }.onSuccess {
                appCtx.toastOnUi(R.string.book_cache_export_done)
            }
            _state.value = BookCacheExportState.Idle
        }
    }

    fun importFromZip(context: Context, zipUri: Uri) {
        viewModelScope.launch {
            _state.value = BookCacheExportState.Exporting("")
            runCatching {
                BookCacheZip.importFromZip(context, zipUri) { message ->
                    _state.value = BookCacheExportState.Exporting(message)
                }
            }.onFailure {
                AppLog.put("书籍缓存导入失败\n${it.localizedMessage}", it)
                appCtx.toastOnUi("导入失败：${it.localizedMessage}")
            }.onSuccess {
                appCtx.toastOnUi(R.string.book_cache_import_done)
            }
            _state.value = BookCacheExportState.Idle
            loadBooks()
        }
    }

    private fun calculateBookCacheSize(book: Book): Long {
        val cacheDir = File(BookHelp.cachePath, book.getFolderName())
        return calculateDirSize(cacheDir)
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        runCatching {
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                current.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        size += file.length()
                    } else if (file.isDirectory) {
                        stack.addLast(file)
                    }
                }
            }
        }
        return size
    }
}
