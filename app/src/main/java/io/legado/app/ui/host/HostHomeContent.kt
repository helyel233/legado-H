package io.legado.app.ui.host

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import io.legado.app.uikit.v5.components.AppEmpty
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.main.homepage.HomepageModuleUi
import io.legado.app.ui.main.homepage.HomepageViewModel
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppGroup
import io.legado.app.uikit.v5.components.AppPullRefresh
import io.legado.app.uikit.v5.components.AppSkeletonList
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * P4-1c「主页」真业务。
 *
 * 数据层零改动：直接接 [HomepageViewModel]（MVI StateFlow，模块化聚合 ——
 * 书源探索/排行榜/RSS 模块/自定义集，加载与分页全在 VM）。
 * UI 重写为 V5：模块卡 + 横向封面行 + 分类芯片 + 排行榜 Tab。
 *
 * 精简范围（相对旧 405 行 Fragment）：下拉刷新 ✅(2026-10-13 AppPullRefresh)、
 * 模块内"加载更多" ✅(2026-10-13 BookRow 尾格 + 排行榜按钮)；发现套件 ✅(2026-10-13
 * DiscoverSuiteSession bind/stop + hostHomeSuiteItems，B13 裁决套件归主页)。
 * 模块管理面板/布局切换留在旧主页（我的 → 主页管理入口，P4-4 长尾并入）。
 */
class HostHomeContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("主页"),
        subtitle = DestTitle.Text("模块化聚合推荐"),
        actions = listOf(
            io.legado.app.uikit.v5.contract.ChromeAction(
                id = "manage",
                icon = io.legado.app.uikit.v5.contract.DestIcon.Vector(HostIcons.Settings),
                contentDescription = DestTitle.Text("管理模块"),
                onClick = { moduleSheetOpen = true },
            ),
        ),
        contentOwnsScroll = true,
    )

    /**
     * 模块管理抽屉开关。实例级状态：宿主 HostScaffold remember 缓存本实例，
     * chrome() 的 onClick 与 Content() 的 LaunchedEffect 共享同一实例，
     * 多开宿主互不干扰、重建不残留（原 companion 静态桥有互顶/复弹问题）。
     */
    private var moduleSheetOpen by androidx.compose.runtime.mutableStateOf(false)

    /** V5 原生模块管理抽屉（替代旧 HomepageModuleManageSheet View 弹层）。 */
    private var manageSheetVisible by androidx.compose.runtime.mutableStateOf(false)

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current
        // Activity 级实例：与模块管理抽屉（HomepageModuleManageSheet）共享，管理后自动刷新
        val viewModel = remember {
            androidx.lifecycle.ViewModelProvider(
                context as androidx.lifecycle.ViewModelStoreOwner
            )[HomepageViewModel::class.java]
        }
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        // 发现套件会话（B13 裁决：套件聚合归主页域）——随主页进出 bind/stop
        androidx.compose.runtime.DisposableEffect(Unit) {
            io.legado.app.ui.main.explore.DiscoverSuiteSession.bind()
            onDispose { io.legado.app.ui.main.explore.DiscoverSuiteSession.stop() }
        }
        val suiteConfig by io.legado.app.ui.main.explore.DiscoverSuiteSession
            .config.collectAsStateWithLifecycle()
        val suiteSelectedId by io.legado.app.ui.main.explore.DiscoverSuiteSession
            .selectedSuiteId.collectAsStateWithLifecycle()
        val suiteBooks by io.legado.app.ui.main.explore.DiscoverSuiteSession
            .widgetBooks.collectAsStateWithLifecycle()
        val suiteRanked by io.legado.app.ui.main.explore.DiscoverSuiteSession
            .rankedWidgetBooks.collectAsStateWithLifecycle()
        val suiteLoading by io.legado.app.ui.main.explore.DiscoverSuiteSession
            .loadingWidgetIds.collectAsStateWithLifecycle()
        val selectedSuite = remember(suiteConfig, suiteSelectedId) {
            suiteConfig.suites.firstOrNull { it.id == suiteSelectedId }
                ?: suiteConfig.suites.firstOrNull()
        }
        val suiteScope = rememberCoroutineScope()
        fun openSuiteBook(book: SearchBook) {
            // 与 ExploreFragment.showBookInfo 同链路：先落搜索历史，再按视频类型兜底跳详情
            suiteScope.launch {
                val isVideo = withContext(Dispatchers.IO) {
                    appDb.searchBookDao.insert(book)
                    io.legado.app.ui.book.SearchBookOpenHelper.isVideoResult(book)
                }
                io.legado.app.ui.book.SearchBookOpenHelper.open(context, book, isVideo)
            }
        }
        fun openSuiteManage() {
            context.startActivity(
                android.content.Intent(
                    context,
                    io.legado.app.ui.main.explore.DiscoverySuiteManageActivity::class.java
                )
            )
        }

        androidx.compose.runtime.LaunchedEffect(moduleSheetOpen) {
            if (moduleSheetOpen) {
                moduleSheetOpen = false
                manageSheetVisible = true
            }
        }

        fun navigateToExplore(sourceUrl: String, exploreUrl: String?, title: String?) {
            if (appDb.rssSourceDao.has(sourceUrl)) {
                // P4 整改：与订阅 Tab 一致，走 V5 原生排序页（此前误用旧 RssSortActivity）
                HostRssSortActivity.start(context, sourceUrl, sortUrl = exploreUrl, key = title)
                return
            }
            if (exploreUrl.isNullOrBlank()) return
            HostExploreShowActivity.start(context, title ?: "", sourceUrl, exploreUrl)
        }

        fun openBook(book: SearchBook) {
            SearchBookOpenHelper.open(context, book, false)
        }

        AppPullRefresh(
            refreshing = uiState.isRefreshing,
            onRefresh = { viewModel.onRefresh() },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = scope.density.screenPadding,
                    end = scope.density.screenPadding,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(scope.density.blockGap),
            ) {
                hostHomeSuiteItems(
                    scope = scope,
                    suites = suiteConfig.suites,
                    selectedSuite = selectedSuite,
                    widgetBooks = suiteBooks,
                    rankedWidgetBooks = suiteRanked,
                    loadingWidgetIds = suiteLoading,
                    onSuiteSelect = { io.legado.app.ui.main.explore.DiscoverSuiteSession.selectSuite(it) },
                    onManage = ::openSuiteManage,
                    onOpenBook = ::openSuiteBook,
                )
                if (uiState.modules.isEmpty()) {
                    item(key = "home_modules_skeleton") { AppSkeletonList(rows = 4) }
                }
                items(uiState.modules, key = { it.globalId }) { module ->
                    ModuleCard(
                        scope = scope,
                        viewModel = viewModel,
                        module = module,
                        onOpenBook = ::openBook,
                        onExplore = { url, title -> navigateToExplore(module.sourceUrl, url, title) },
                    )
                }
            }
        }

        if (manageSheetVisible) {
            HostHomeManageSheet(
                scope = scope,
                viewModel = viewModel,
                onDismiss = { manageSheetVisible = false },
            )
        }
    }
}

@Composable
private fun ModuleCard(
    scope: ContentScope,
    viewModel: HomepageViewModel,
    module: HomepageModuleUi,
    onOpenBook: (SearchBook) -> Unit,
    onExplore: (String?, String?) -> Unit,
) {
    val colors = LocalSemanticColors.current
    AppCard {
        Column(Modifier.padding(scope.density.cardPadding)) {
            // 模块头：标题 + 更多
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = module.title,
                    style = scope.type.title,
                    color = scope.color.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onExplore(module.exploreUrl, module.title) },
                )
                Text(
                    text = "更多 →",
                    style = scope.type.label,
                    color = colors.primary,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onExplore(module.exploreUrl, module.title) },
                )
            }
            Spacer(Modifier.height(scope.density.blockGap / 2))

            when (val state = module.state) {
                is io.legado.app.ui.main.homepage.ModuleLoadState.Loading ->
                    AppSkeletonList(rows = 2)

                is io.legado.app.ui.main.homepage.ModuleLoadState.Loaded -> {
                    if (state.books.isEmpty()) {
                        AppEmpty(
                            title = "暂无内容",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        BookRow(
                            scope,
                            state.books,
                            state.hasMore,
                            state.isLoadingMore,
                            loadMore = { viewModel.loadMoreModule(module.globalId) },
                            onOpenBook = onOpenBook,
                            viewModel = viewModel,
                        )
                    }
                }

                is io.legado.app.ui.main.homepage.ModuleLoadState.Buttons -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.kinds.take(6).forEach { kind ->
                            io.legado.app.uikit.v5.components.AppChip(
                                text = kind.title,
                                selected = false,
                                onClick = { onExplore(kind.url, kind.title) },
                            )
                        }
                    }
                }

                is io.legado.app.ui.main.homepage.ModuleLoadState.RankingTabs -> {
                    val tab = state.tabs.getOrNull(state.selectedIndex)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.tabs.forEachIndexed { index, t ->
                            io.legado.app.uikit.v5.components.AppChip(
                                text = t.title,
                                selected = index == state.selectedIndex,
                                onClick = { viewModel.selectRankingTab(module.globalId, index) },
                            )
                        }
                    }
                    Spacer(Modifier.height(scope.density.blockGap / 2))
                    tab?.books?.take(8)?.forEach { item ->
                        BookLine(scope, item.book) { onOpenBook(item.book) }
                    }
                    if (tab != null && tab.hasMore) {
                        Spacer(Modifier.height(scope.density.blockGap / 2))
                        AppGhostButton(
                            text = if (tab.isLoadingMore) "加载中…" else "加载更多",
                            enabled = !tab.isLoadingMore,
                            onClick = {
                                viewModel.loadMoreRankingTab(module.globalId, state.selectedIndex)
                            },
                        )
                    }
                }

                is io.legado.app.ui.main.homepage.ModuleLoadState.Error ->
                    Text(state.message, style = scope.type.caption, color = colors.error)
            }
        }
    }
}

@Composable
private fun BookRow(
    scope: ContentScope,
    books: List<io.legado.app.ui.main.homepage.HomepageBookItemUi>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    loadMore: () -> Unit,
    onOpenBook: (SearchBook) -> Unit,
    viewModel: HomepageViewModel,
) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(scope.density.blockGap)) {
        items(books, key = { it.book.bookUrl }) { item ->
            Column(
                modifier = Modifier
                    .width(88.dp)
                    .combinedClickable(
                        onClick = { onOpenBook(item.book) },
                        onLongClick = { viewModel.onAddToShelf(item.book) },
                    ),
            ) {
                io.legado.app.ui.widget.compose.BookCoverImage(
                    book = item.book,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(118.dp),
                )
                Text(
                    text = item.book.name,
                    style = scope.type.caption,
                    color = scope.color.onSurface,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (hasMore) {
            item(key = "load_more") {
                Column(
                    modifier = Modifier
                        .width(64.dp)
                        .height(118.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppGhostButton(
                        text = if (isLoadingMore) "…" else "更多",
                        enabled = !isLoadingMore,
                        onClick = loadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookLine(
    scope: ContentScope,
    book: SearchBook,
    onClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scope.density.blockGap / 2),
    ) {
        io.legado.app.ui.widget.compose.BookCoverImage(
            book = book,
            modifier = Modifier.size(width = 32.dp, height = 44.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(book.name, style = scope.type.body, color = colors.onSurface, maxLines = 1)
            Text(
                book.author.ifBlank { " " },
                style = scope.type.caption,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun androidx.compose.foundation.layout.ColumnScope??.androidx_compose_foundation_layoutSpacer() {}
