package io.legado.app.ui.main.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookListCardSurface
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItemStyle
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.SearchBookListItem
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.ui.widget.image.CoverImageView
import kotlinx.coroutines.flow.first

/** 现代发现列表的进程内滚动位置快照（与套件/经典快照并列，仅进程内、不落盘）。 */
data class DiscoverScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

@Composable
fun ExploreModernListScreen(
    books: List<SearchBook>,
    layoutMode: Int,
    listItemStyle: Int,
    topPaddingPx: Int,
    scrollToTopSignal: Int,
    isLoading: Boolean,
    hasMore: Boolean,
    isInBookshelf: (SearchBook) -> Boolean,
    onBookClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle,
    modifier: Modifier = Modifier,
    scrollPosition: DiscoverScrollPosition? = null,
    onScrollPositionChanged: (DiscoverScrollPosition) -> Unit = {}
) {
    if (layoutMode == 3) {
        ExploreModernGridScreen(
            books = books,
            topPaddingPx = topPaddingPx,
            scrollToTopSignal = scrollToTopSignal,
            isLoading = isLoading,
            hasMore = hasMore,
            onBookClick = onBookClick,
            onLoadMore = onLoadMore,
            onCanScrollBackwardChanged = onCanScrollBackwardChanged,
            fragment = fragment,
            lifecycle = lifecycle,
            modifier = modifier,
            scrollPosition = scrollPosition,
            onScrollPositionChanged = onScrollPositionChanged
        )
        return
    }
    val listState = rememberLazyListState()
    val topPadding = with(LocalDensity.current) { topPaddingPx.toDp() }
    // 以 books.size 为 key：列表追加时换新 List 实例不会重建 derivedStateOf，
    // 只有数量真正变化才失效
    val bookCount = books.size
    val shouldLoadMore by remember(bookCount, hasMore, isLoading) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            bookCount > 0 && hasMore && !isLoading && lastVisible >= bookCount - 4
        }
    }
    val canScrollBackward by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset > 0
        }
    }
    val renderConfig = rememberBookshelfListRenderConfig()
    var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LaunchedEffect(canScrollBackward) {
        onCanScrollBackwardChanged(canScrollBackward)
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            if (AppConfig.isEInkMode) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }
    // 恢复目标只在首次组合捕获：上报效应首帧就会把 Fragment 快照覆盖成 (0,0)，
    // 若以参数本身为 key，数据到达触发重组时恢复效应会被回写的 (0,0) 重启而失效。
    val restoreTarget = remember { scrollPosition }
    // 恢复消费完成前屏蔽上报回写：空列表首帧的 (0,0) 不能覆盖进程内快照，
    // 否则列表项尚未就绪用户就离开时，恢复目标已丢失。
    var restoreConsumed by remember { mutableStateOf(false) }
    // 持续上报滚动位置（含滚回顶部后的 (0,0)），供 Fragment 侧保存进程内快照。
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (restoreConsumed) {
                onScrollPositionChanged(DiscoverScrollPosition(index, offset))
            }
        }
    }
    // 独立恢复：等待列表项就绪后再 scrollToItem，避免空列表越界导致恢复丢失。
    LaunchedEffect(restoreTarget) {
        val target = restoreTarget
        if (target == null || target.firstVisibleItemIndex <= 0) {
            restoreConsumed = true
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > target.firstVisibleItemIndex }
        listState.scrollToItem(target.firstVisibleItemIndex, target.firstVisibleItemScrollOffset)
        restoreConsumed = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topPadding)
            .clipToBounds()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 86.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (listItemStyle == BookshelfListItemStyle.RoundedCard) 4.dp else 2.dp)
        ) {
            lazyColumnItems(
                items = books,
                key = { book -> "${book.origin}|${book.bookUrl}" },
                contentType = { "discover_book_$listItemStyle" }
            ) { book ->
                // remember 稳定 onPreview 回调，保证列表项可跳过重组
                val onPreview = remember(book) {
                    { bounds: Rect? ->
                        previewState = SearchBookPreviewState(book, bounds)
                    }
                }
                ExploreBookListItem(
                    book = book,
                    inBookshelf = isInBookshelf(book),
                    listItemStyle = listItemStyle,
                    renderConfig = renderConfig,
                    fragment = fragment,
                    lifecycle = lifecycle,
                    onClick = onBookClick,
                    onPreview = onPreview
                )
            }
            if (isLoading && books.isNotEmpty()) {
                item(key = "discover_loading_footer", contentType = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = renderConfig.palette.accent
                        )
                    }
                }
            }
        }
        ComposeLazyListFastScroller(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
        SearchBookPreviewOverlay(
            state = previewState,
            renderConfig = renderConfig,
            fragment = fragment,
            lifecycle = lifecycle,
            onDismissed = { previewState = null },
            onOpen = { book ->
                previewState = null
                onBookClick(book)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ExploreModernGridScreen(
    books: List<SearchBook>,
    topPaddingPx: Int,
    scrollToTopSignal: Int,
    isLoading: Boolean,
    hasMore: Boolean,
    onBookClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle,
    modifier: Modifier = Modifier,
    scrollPosition: DiscoverScrollPosition? = null,
    onScrollPositionChanged: (DiscoverScrollPosition) -> Unit = {}
) {
    val gridState = rememberLazyGridState()
    val topPadding = with(LocalDensity.current) { topPaddingPx.toDp() }
    val bookCount = books.size
    val shouldLoadMore by remember(bookCount, hasMore, isLoading) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            bookCount > 0 && hasMore && !isLoading && lastVisible >= bookCount - 7
        }
    }
    val canScrollBackward by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                    gridState.firstVisibleItemScrollOffset > 0
        }
    }
    val renderConfig = rememberBookshelfListRenderConfig()
    var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LaunchedEffect(canScrollBackward) {
        onCanScrollBackwardChanged(canScrollBackward)
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            if (AppConfig.isEInkMode) {
                gridState.scrollToItem(0)
            } else {
                gridState.animateScrollToItem(0)
            }
        }
    }
    // 与列表版同理：目标只首次捕获，恢复消费前屏蔽上报回写（注释见列表版）。
    val restoreTarget = remember { scrollPosition }
    var restoreConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (restoreConsumed) {
                onScrollPositionChanged(DiscoverScrollPosition(index, offset))
            }
        }
    }
    LaunchedEffect(restoreTarget) {
        val target = restoreTarget
        if (target == null || target.firstVisibleItemIndex <= 0) {
            restoreConsumed = true
            return@LaunchedEffect
        }
        snapshotFlow { gridState.layoutInfo.totalItemsCount }
            .first { it > target.firstVisibleItemIndex }
        gridState.scrollToItem(target.firstVisibleItemIndex, target.firstVisibleItemScrollOffset)
        restoreConsumed = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topPadding)
            .clipToBounds()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 86.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = books,
                key = { book -> "${book.origin}|${book.bookUrl}" },
                contentType = { "discover_grid_book" }
            ) { book ->
                val onPreview = remember(book) {
                    { bounds: Rect? ->
                        previewState = SearchBookPreviewState(book, bounds)
                    }
                }
                ExploreGridBookItem(
                    book = book,
                    renderConfig = renderConfig,
                    fragment = fragment,
                    lifecycle = lifecycle,
                    onClick = onBookClick,
                    onPreview = onPreview
                )
            }
            if (isLoading && books.isNotEmpty()) {
                item(key = "discover_grid_loading_footer", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = renderConfig.palette.accent
                        )
                    }
                }
            }
        }
        SearchBookPreviewOverlay(
            state = previewState,
            renderConfig = renderConfig,
            fragment = fragment,
            lifecycle = lifecycle,
            onDismissed = { previewState = null },
            onOpen = { book ->
                previewState = null
                onBookClick(book)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreGridBookItem(
    book: SearchBook,
    renderConfig: BookshelfListRenderConfig,
    fragment: Fragment,
    lifecycle: Lifecycle,
    onClick: (SearchBook) -> Unit,
    onPreview: (Rect?) -> Unit
) {
    val palette = renderConfig.palette
    var coverBounds by remember(book.bookUrl, book.origin, book.coverUrl) { mutableStateOf<Rect?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(book) },
                onLongClick = { onPreview(coverBounds) }
        )
    ) {
        Box {
            BookCoverImage(
                book = book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f),
                style = CoverImageView.CoverStyle.GRID,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                fragment = fragment,
                lifecycle = lifecycle,
                preferThumb = true,
                fillBounds = true,
                onBoundsChanged = { coverBounds = it }
            )
        }
        Text(
            text = book.name,
            color = palette.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.titleFontFamily,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ExploreBookListItem(
    book: SearchBook,
    inBookshelf: Boolean,
    listItemStyle: Int,
    renderConfig: BookshelfListRenderConfig,
    fragment: Fragment,
    lifecycle: Lifecycle,
    onClick: (SearchBook) -> Unit,
    onPreview: (Rect?) -> Unit
) {
    val rounded = listItemStyle == BookshelfListItemStyle.RoundedCard
    SearchBookListItem(
        book = book,
        inBookshelf = inBookshelf,
        rounded = rounded,
        renderConfig = renderConfig,
        fragment = fragment,
        lifecycle = lifecycle,
        onClick = { onClick(book) },
        onPreview = onPreview
    )
}
