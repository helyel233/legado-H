package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.explore.ExploreShowComposeScreen
import io.legado.app.ui.book.explore.ExploreShowViewModel
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppNumberField
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.ui.host.V5DialogPanel
import io.legado.app.help.webView.WebViewPool
import io.legado.app.utils.stableSearchBookKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-3：发现文章页 V5 化（2026-10-13）。
 * 状态与 VM 接线自 ExploreShowActivity 逐行搬运；列表渲染复用自包含的
 * [ExploreShowComposeScreen]（零拷贝）；外壳换 HostV5Page。
 */
class HostExploreShowActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXPLORE_NAME = "exploreName"
        const val EXTRA_SOURCE_URL = "sourceUrl"
        const val EXTRA_EXPLORE_URL = "exploreUrl"
        fun start(context: Context, name: String, sourceUrl: String, exploreUrl: String) {
            context.startActivity(
                Intent(context, HostExploreShowActivity::class.java)
                    .putExtra(EXTRA_EXPLORE_NAME, name)
                    .putExtra(EXTRA_SOURCE_URL, sourceUrl)
                    .putExtra(EXTRA_EXPLORE_URL, exploreUrl)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostExploreShowScreen() }
    }

    override fun onPause() {
        WebViewPool.scheduleDestroyScope(WebViewPool.Scope.DISCOVERY)
        super.onPause()
    }

    override fun onDestroy() {
        WebViewPool.destroyScope(WebViewPool.Scope.DISCOVERY)
        super.onDestroy()
    }
}

@Composable
private fun HostExploreShowScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val viewModel = remember { ViewModelProvider(activity)[ExploreShowViewModel::class.java] }

    val composeBooks = remember { mutableStateListOf<SearchBook>() }
    var bottomLoading by remember { mutableStateOf(true) }
    var topLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var hasPrevious by remember { mutableStateOf(false) }
    var bottomError by remember { mutableStateOf<String?>(null) }
    var topError by remember { mutableStateOf<String?>(null) }
    val scrollToTopSignal = remember { mutableIntStateOf(0) }
    val keepPositionSignal = remember { mutableIntStateOf(0) }
    val prependedCount = remember { mutableIntStateOf(0) }
    val bookshelfTick = remember { mutableIntStateOf(0) }
    var oldPage by remember { mutableIntStateOf(-1) }
    var isClearAll by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    val booksData by viewModel.booksData.observeAsStateCompat(emptyList())
    val addBooksData by viewModel.addBooksData.observeAsStateCompat(emptyList())
    val errorData by viewModel.errorLiveData.observeAsStateCompat("")
    val errorTopData by viewModel.errorTopLiveData.observeAsStateCompat("")
    val upAdapter by viewModel.upAdapterLiveData.observeAsStateCompat("")
    val page by viewModel.pageLiveData.observeAsStateCompat(1)

    LaunchedEffect(booksData) {
        if (booksData.isEmpty() && composeBooks.isEmpty()) {
            hasMore = false
            composeBooks.clear()
            isClearAll = false
            bottomLoading = false
            bottomError = null
            return@LaunchedEffect
        }
        val oldSize = composeBooks.size
        bottomLoading = false
        bottomError = null
        hasMore = isClearAll || booksData.size > oldSize
        composeBooks.clear()
        composeBooks.addAll(booksData)
        if (isClearAll) {
            scrollToTopSignal.intValue++
            isClearAll = false
        }
    }

    LaunchedEffect(addBooksData) {
        if (addBooksData.isEmpty()) return@LaunchedEffect
        val oldSize = composeBooks.size
        val oldFirstKey = composeBooks.firstOrNull()?.stableSearchBookKey()
        topLoading = false
        topError = null
        composeBooks.clear()
        composeBooks.addAll(addBooksData)
        val prepended = oldFirstKey?.let { key ->
            addBooksData.indexOfFirst { it.stableSearchBookKey() == key }
        }?.takeIf { it > 0 } ?: (addBooksData.size - oldSize).coerceAtLeast(0)
        prependedCount.intValue = if (prepended > 0) {
            keepPositionSignal.intValue++
            prepended
        } else 0
        hasPrevious = oldPage > 1
    }

    LaunchedEffect(errorData) {
        if (errorData.isNotBlank()) {
            bottomLoading = false
            bottomError = errorData
        }
    }
    LaunchedEffect(errorTopData) {
        if (errorTopData.isNotBlank()) {
            topLoading = false
            topError = errorTopData
            hasPrevious = oldPage > 1
        }
    }
    LaunchedEffect(upAdapter) {
        if (upAdapter.isNotBlank()) bookshelfTick.intValue++
    }

    LaunchedEffect(Unit) { viewModel.initData(activity.intent) }

    fun scrollToBottom(forceLoad: Boolean = false) {
        val canLoad = hasMore && !bottomLoading && !topLoading
        if (!canLoad && !forceLoad) return
        hasMore = true
        bottomLoading = true
        bottomError = null
        viewModel.explore()
    }

    fun scrollToTop(forceLoad: Boolean = false) {
        if (bottomLoading || topLoading) return
        val targetPage = if (forceLoad) oldPage else oldPage - 1
        if (targetPage < 1) return
        oldPage = targetPage
        topLoading = true
        topError = null
        hasPrevious = targetPage > 1
        viewModel.explore(targetPage)
    }

    fun showBookInfo(book: SearchBook) {
        activity.lifecycleScope.launch {
            val isVideo = withContext(Dispatchers.IO) {
                SearchBookOpenHelper.isVideoResult(book, viewModel.sourceTypeHint())
            }
            SearchBookOpenHelper.open(activity, book, isVideo)
        }
    }

    HostV5Page(
        title = activity.intent.getStringExtra(HostExploreShowActivity.EXTRA_EXPLORE_NAME)
            ?: "",
        subtitle = stringResource(R.string.menu_page, page),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) {
                pickerOpen = true
            }
        },
    ) {
        if (pickerOpen) {
            PagePickerDialog(
                current = page,
                onDismiss = { pickerOpen = false },
                onPick = { target ->
                    if (target != page) {
                        oldPage = target
                        viewModel.skipPage(target)
                        isClearAll = true
                        composeBooks.clear()
                        hasMore = true
                        hasPrevious = target > 1
                        bottomError = null
                        topError = null
                        topLoading = false
                        scrollToBottom(forceLoad = true)
                    }
                    pickerOpen = false
                },
            )
        }
        ExploreShowComposeScreen(
            books = composeBooks,
            isLoading = bottomLoading,
            isLoadingPrevious = topLoading,
            hasMore = hasMore,
            hasPrevious = hasPrevious,
            errorMessage = bottomError,
            previousErrorMessage = topError,
            scrollToTopSignal = scrollToTopSignal.intValue,
            keepPositionAfterPrependSignal = keepPositionSignal.intValue,
            prependedItemCount = prependedCount.intValue,
            bookshelfTick = bookshelfTick.intValue,
            isInBookshelf = { book -> viewModel.isInBookShelf(book) },
            lifecycle = activity.lifecycle,
            onBookClick = { book -> showBookInfo(book) },
            onLoadMore = { scrollToBottom(forceLoad = bottomError != null) },
            onLoadPrevious = { scrollToTop(forceLoad = topError != null) },
        )
    }
}

/** 页码选择（旧 NumberPickerDialog 的 V5 等价）。 */
@Composable
private fun PagePickerDialog(
    current: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    Dialog(onDismissRequest = onDismiss) {
        V5DialogPanel(
            title = stringResource(R.string.change_page),
            content = {
                AppNumberField(
                    value = text.toIntOrNull() ?: current,
                    onValueChange = { text = it.toString() },
                    range = 1..999,
                    label = stringResource(R.string.change_page),
                )
            },
            actions = {
                AppGhostButton(text = stringResource(R.string.cancel), onClick = onDismiss)
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    onClick = { onPick(text.toIntOrNull()?.coerceIn(1, 999) ?: current) },
                )
            },
        )
    }
}
