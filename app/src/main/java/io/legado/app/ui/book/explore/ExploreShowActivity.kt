package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.stableSearchBookKey
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>() {

    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val composeBooks = mutableStateListOf<SearchBook>()
    private val composeBottomLoading = mutableStateOf(false)
    private val composeTopLoading = mutableStateOf(false)
    private val composeHasMore = mutableStateOf(true)
    private val composeHasPrevious = mutableStateOf(false)
    private val composeBottomError = mutableStateOf<String?>(null)
    private val composeTopError = mutableStateOf<String?>(null)
    private val composeScrollToTopSignal = mutableIntStateOf(0)
    private val composeKeepPositionAfterPrependSignal = mutableIntStateOf(0)
    private val composePrependedItemCount = mutableIntStateOf(0)
    private val bookshelfTick = mutableIntStateOf(0)
    private var oldPage = -1
    private var isClearAll = false
    private var composeListCanScrollBackward = false

    private val menuPage by lazy {
        binding.titleBar.menu.add(getString(R.string.menu_page, 1)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val page = viewModel.pageLiveData.value ?: 1
                NumberPickerDialog(this@ExploreShowActivity)
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(page)
                    .show { targetPage ->
                        if (page != targetPage) {
                            oldPage = targetPage
                            // 跳页会作废在途请求（含下拉刷新的请求）：作废的结果
                            // 不会走成功/失败回调，刷新圈必须在这里主动收起，
                            // 否则下拉刷新与跳页连续操作时转圈卡死
                            binding.swipeRefreshLayout.isRefreshing = false
                            viewModel.skipPage(targetPage)
                            isClearAll = true
                            composeBooks.clear()
                            composeHasMore.value = true
                            composeHasPrevious.value = targetPage > 1
                            composeBottomError.value = null
                            composeTopError.value = null
                            composeTopLoading.value = false
                            scrollToBottom(forceLoad = true)
                        }
                    }
                true
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        initSwipeRefresh()
        initComposeList()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.errorLiveData.observe(this) {
            composeBottomLoading.value = false
            composeBottomError.value = it
            binding.swipeRefreshLayout.isRefreshing = false
        }
        viewModel.errorTopLiveData.observe(this) {
            composeTopLoading.value = false
            composeTopError.value = it
            composeHasPrevious.value = oldPage > 1
            binding.swipeRefreshLayout.isRefreshing = false
        }
        viewModel.upAdapterLiveData.observe(this) {
            bookshelfTick.intValue++
        }
        viewModel.pageLiveData.observe(this) {
            //统一维护"当前页"：正常翻页、跳页、快照恢复都从这里校准，
            //并让恢复到多页快照时立即可向上翻页
            oldPage = it
            menuPage.title = getString(R.string.menu_page, it)
            composeHasPrevious.value = it > 1
        }
        viewModel.initData(intent)
    }

    private fun initSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        //ComposeView 自身不产生 View 级滚动偏移，须由列表回传滚动状态
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            composeListCanScrollBackward
        }
        binding.swipeRefreshLayout.setOnRefreshListener { refreshBooks() }
    }

    /**
     * 下拉刷新：回到第 1 页重新加载。skipPage 作废所有在途请求，
     * 成功后覆盖列表与快照缓存。
     */
    private fun refreshBooks() {
        if (composeTopLoading.value) return
        oldPage = 1
        // skipPage 会作废在途请求（不会走成功/失败回调）：先收起刷新圈，
        // 加载指示交由 composeBottomLoading 的底部加载态接管
        binding.swipeRefreshLayout.isRefreshing = false
        viewModel.skipPage(1)
        isClearAll = true
        composeBooks.clear()
        composeHasMore.value = true
        composeHasPrevious.value = false
        composeBottomError.value = null
        composeTopError.value = null
        composeTopLoading.value = false
        composeBottomLoading.value = true
        viewModel.explore()
    }

    private fun initComposeList() {
        composeBottomLoading.value = true
        binding.composeList.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeList.setContent {
            LegadoComposeTheme {
                ExploreShowComposeScreen(
                    books = composeBooks,
                    isLoading = composeBottomLoading.value,
                    isLoadingPrevious = composeTopLoading.value,
                    hasMore = composeHasMore.value,
                    hasPrevious = composeHasPrevious.value,
                    errorMessage = composeBottomError.value,
                    previousErrorMessage = composeTopError.value,
                    scrollToTopSignal = composeScrollToTopSignal.intValue,
                    keepPositionAfterPrependSignal = composeKeepPositionAfterPrependSignal.intValue,
                    prependedItemCount = composePrependedItemCount.intValue,
                    bookshelfTick = bookshelfTick.intValue,
                    isInBookshelf = { book -> isInBookshelf(book) },
                    lifecycle = lifecycle,
                    onBookClick = { book -> showBookInfo(book) },
                    onLoadMore = { scrollToBottom(forceLoad = composeBottomError.value != null) },
                    onLoadPrevious = { scrollToTop(forceLoad = composeTopError.value != null) },
                    onCanScrollBackwardChanged = { composeListCanScrollBackward = it }
                )
            }
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        val canLoad = composeHasMore.value && !composeBottomLoading.value && !composeTopLoading.value
        if (!canLoad && !forceLoad) return
        composeHasMore.value = true
        composeBottomLoading.value = true
        composeBottomError.value = null
        viewModel.explore()
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if (composeBottomLoading.value || composeTopLoading.value) return
        val targetPage = if (forceLoad) oldPage else oldPage - 1
        if (targetPage < 1) return
        oldPage = targetPage
        composeTopLoading.value = true
        composeTopError.value = null
        composeHasPrevious.value = targetPage > 1
        viewModel.explore(targetPage)
    }

    private fun upData(books: List<SearchBook>) {
        val oldSize = composeBooks.size
        composeBottomLoading.value = false
        composeBottomError.value = null
        binding.swipeRefreshLayout.isRefreshing = false
        if (books.isEmpty() && oldSize == 0) {
            composeHasMore.value = false
            replaceComposeBooks(emptyList())
            isClearAll = false
            return
        }
        composeHasMore.value = isClearAll || books.size > oldSize
        replaceComposeBooks(books)
        if (isClearAll) {
            composeScrollToTopSignal.intValue++
            isClearAll = false
        }
    }

    private fun upDataTop(books: List<SearchBook>) {
        val oldSize = composeBooks.size
        val oldFirstKey = composeBooks.firstOrNull()?.stableSearchBookKey()
        composeTopLoading.value = false
        composeTopError.value = null
        binding.swipeRefreshLayout.isRefreshing = false
        replaceComposeBooks(books, resetPrepend = false)
        val prependedCount = oldFirstKey?.let { key ->
            books.indexOfFirst { it.stableSearchBookKey() == key }
        }?.takeIf { it > 0 } ?: (books.size - oldSize).coerceAtLeast(0)
        if (prependedCount > 0) {
            composePrependedItemCount.intValue = prependedCount
            composeKeepPositionAfterPrependSignal.intValue++
        } else {
            composePrependedItemCount.intValue = 0
        }
        composeHasPrevious.value = oldPage > 1
    }

    private fun replaceComposeBooks(books: List<SearchBook>, resetPrepend: Boolean = true) {
        composeBooks.clear()
        composeBooks.addAll(books)
        if (resetPrepend) {
            composePrependedItemCount.intValue = 0
        }
    }

    private fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    private fun showBookInfo(book: SearchBook) {
        lifecycleScope.launch {
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(book, viewModel.sourceTypeHint())
            }
            SearchBookOpenHelper.open(this@ExploreShowActivity, book, isVideo)
        }
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
