package io.legado.app.ui.main.librarysearch

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.search.LibrarySearchIndexer
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全库搜索结果条目
 */
data class LibrarySearchUiItem(
    val bookUrl: String,
    val bookName: String,
    val author: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val snippetText: String,
    val showHeader: Boolean
)

class LibrarySearchViewModel(application: Application) : BaseViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _items = MutableStateFlow<List<LibrarySearchUiItem>>(emptyList())
    val items: StateFlow<List<LibrarySearchUiItem>> = _items.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _indexedCount = MutableStateFlow(0)
    val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

    private val _rebuilding = MutableStateFlow(false)
    val rebuilding: StateFlow<Boolean> = _rebuilding.asStateFlow()

    private val _rebuildProgress = MutableStateFlow("")
    val rebuildProgress: StateFlow<String> = _rebuildProgress.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshIndexCount()
    }

    fun updateQuery(value: String) {
        _query.value = value
        searchJob?.cancel()
        if (value.isBlank()) {
            _items.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            search(value)
        }
    }

    private suspend fun search(rawQuery: String) {
        val matchQuery = LibrarySearchIndexer.buildMatchQuery(rawQuery)
        if (matchQuery.isNullOrBlank()) return
        _searching.value = true
        runCatching {
            withContext(Dispatchers.IO) {
                val rows = appDb.libraryContentFtsDao.search(matchQuery, 200, 0)
                val bookMap = appDb.bookDao.all.associateBy { it.bookUrl }
                var lastUrl = ""
                rows.mapNotNull { row ->
                    val book = bookMap[row.bookUrl] ?: return@mapNotNull null
                    LibrarySearchUiItem(
                        bookUrl = row.bookUrl,
                        bookName = book.name,
                        author = book.author ?: "",
                        chapterIndex = row.chapterIndex,
                        chapterTitle = row.chapterTitle,
                        snippetText = row.snippetText,
                        showHeader = row.bookUrl != lastUrl.also { lastUrl = row.bookUrl }
                    )
                }
            }
        }.onSuccess {
            _items.value = it
        }.onFailure {
            AppLog.put("全库搜索失败", it)
            _items.value = emptyList()
        }
        _searching.value = false
    }

    fun refreshIndexCount() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appDb.libraryContentFtsDao.countAll()
            }.onSuccess {
                _indexedCount.value = it
            }
        }
    }

    fun rebuildIndex() {
        if (_rebuilding.value) return
        _rebuilding.value = true
        viewModelScope.launch {
            runCatching {
                LibrarySearchIndexer.rebuildAll { progress ->
                    _rebuildProgress.value = progress
                }
            }.onSuccess { count ->
                _indexedCount.value = appDb.libraryContentFtsDao.countAll()
                _rebuildProgress.value = appCtx.getString(
                    io.legado.app.R.string.library_search_rebuild_done, count
                )
            }.onFailure {
                AppLog.put("全库搜索重建索引失败", it)
                _rebuildProgress.value = ""
            }
            _rebuilding.value = false
            // 重建后如有关键字，刷新结果
            if (_query.value.isNotBlank()) {
                search(_query.value)
            }
        }
    }

    fun getBookUrlProgress(item: LibrarySearchUiItem, onReady: (String) -> Unit) {
        execute {
            appDb.bookDao.getBook(item.bookUrl)?.let { book ->
                book.durChapterIndex = item.chapterIndex
                book.durChapterTitle = item.chapterTitle
                book.durChapterPos = 0
                book.durChapterTime = System.currentTimeMillis()
                appDb.bookDao.update(book)
            }
        }.onSuccess {
            onReady(item.bookUrl)
        }
    }
}
