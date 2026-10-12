package io.legado.app.ui.host

import io.legado.app.R

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.main.bookshelf.compose.BookshelfBookItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfFolderItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfGridItem
import io.legado.app.ui.main.bookshelf.compose.BookshelfItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItem
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.buildBookshelfItems
import io.legado.app.uikit.v5.components.AppProgressBar
import io.legado.app.uikit.v5.components.AppCover
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.utils.cnCompare
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * P4-1b「书架」真业务（最高频页）。
 *
 * 数据流与旧书架同源（appDb Flow → 内存排序 → BookshelfItemUi），
 * 渲染复用旧壳已 Compose 化的 [BookshelfGridItem] / [BookshelfListItem]
 * （封面加载、占位、更新点全在里面）—— 搬迁只换"容器"，不复制业务。
 * 旧 BookshelfFragment2 在 P4-5 随旧壳整体退役。
 */
class HostShelfContent : AppContent {

    /** 顶栏 actions 是纯数据拿不到页面状态，抽屉用静态状态桥接。 */
    companion object {
        var layoutSheetOpen by mutableStateOf(false)
        var shelfMenuOpen by mutableStateOf(false)
        var settingsSheetOpen by mutableStateOf(false)
        var groupSheetOpen by mutableStateOf(false)
        var addUrlOpen by mutableStateOf(false)
    }

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Res(R.string.v5_shelf),
        actions = listOf(
            ChromeAction(
                id = "search",
                icon = DestIcon.Vector(HostIcons.Search),
                contentDescription = DestTitle.Text("搜索"),
                onClick = {
                    // P4 整改：搜索页 V5 原生化（HostSearchActivity）
                    HostSearchActivity.start(splitties.init.appCtx)
                },
            ),
            ChromeAction(
                id = "menu",
                icon = DestIcon.Vector(HostIcons.Menu),
                contentDescription = DestTitle.Res(R.string.v5_shelf_menu),
                onClick = { shelfMenuOpen = true },
            ),
        ),
        contentOwnsScroll = true, // LazyVerticalGrid 自带滚动
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current
        var groupId by remember { mutableStateOf(BookGroup.IdRoot) }
        var layout by remember { mutableStateOf(context.getPrefInt(PreferKey.bookshelfLayout, 3)) }
        // 设置弹层改动（排序/样式等非 state 偏好）后触发数据重查与重组
        var refresh by remember { mutableStateOf(0) }

        val items by produceState<List<BookshelfItemUi>>(emptyList(), groupId, refresh) {
            combine(
                appDb.bookDao.flowShelfByGroup(groupId),
                appDb.bookGroupDao.show.asFlow(),
                appDb.bookCollectionDao.flowRootCollections(),
            ) { books, groups, collections ->
                val sorted = sortShelf(books, AppConfig.getBookSortByGroupId(groupId))
                buildBookshelfItems(
                    groups = groups,
                    books = sorted,
                    isRootGroup = groupId == BookGroup.IdRoot,
                    groupId = groupId,
                    isUpdating = { false },
                )
            }
                .conflate()
                .flowOn(Dispatchers.Default)
                .collect { value = it }
        }

        fun openItem(item: BookshelfItemUi) {
            when (item) {
                is BookshelfBookItemUi -> context.startActivityForBook(item.display.toMinimalBook())
                is BookshelfFolderItemUi -> groupId = item.group.groupId
                else -> {}
            }
        }

        fun openDetail(item: BookshelfItemUi) {
            if (item !is BookshelfBookItemUi) return
            CoroutineScope(Dispatchers.IO).launch {
                val book = appDb.bookDao.getBook(item.display.bookUrl) ?: return@launch
                BookInfoNavigator.open(context, book)
            }
        }

        val books = items.filterIsInstance<BookshelfBookItemUi>()
        val reading = books
            .filter { it.durRatio > 0.01f }
            .maxByOrNull { it.display.durChapterTime }

        if (layout >= 2) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(layout),
                state = rememberLazyGridState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = scope.density.screenPadding,
                    end = scope.density.screenPadding,
                    bottom = 104.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(scope.density.gridGap),
                verticalArrangement = Arrangement.spacedBy(scope.density.gridGap),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        ContinueReadingCardV5(scope, reading) { openItem(it) }
                        if (groupId != BookGroup.IdRoot) BackToRootRow(scope) { groupId = BookGroup.IdRoot }
                    }
                }
                items(count = items.size) { idx ->
                    val item = items[idx]
                    BookshelfGridItem(
                        item = item,
                        onClick = ::openItem,
                        onLongClick = ::openDetail,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = scope.density.screenPadding,
                    end = scope.density.screenPadding,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(scope.density.gridGap / 2),
            ) {
                item {
                    Column {
                        ContinueReadingCardV5(scope, reading) { openItem(it) }
                        if (groupId != BookGroup.IdRoot) BackToRootRow(scope) { groupId = BookGroup.IdRoot }
                    }
                }
                items(items) { item ->
                    BookshelfListItem(
                        item = item,
                        listLayout = layout,
                        cardStyle = AppConfig.bookshelfListItemStyle,
                        introMaxLines = AppConfig.bookshelfListIntroLines,
                        renderConfig = BookshelfListRenderConfig(
                            palette = io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListPalette(),
                            panelImage = null,
                        ),
                        onClick = ::openItem,
                        onLongClick = ::openDetail,
                    )
                }
            }
        }

        if (layoutSheetOpen) {
            AppMenuSheet(
                title = stringResource(R.string.v5_shelf_layout),
                actions = listOf(
                    "极简列表" to { setLayout(context, 0) { layout = it } },
                    "紧凑列表" to { setLayout(context, 1) { layout = it } },
                    "两列网格" to { setLayout(context, 2) { layout = it } },
                    "三列网格" to { setLayout(context, 3) { layout = it } },
                    "四列网格" to { setLayout(context, 4) { layout = it } },
                    "五列网格" to { setLayout(context, 5) { layout = it } },
                ),
                onDismiss = { layoutSheetOpen = false },
            )
        }

        if (shelfMenuOpen) {
            val owner = context as? androidx.lifecycle.ViewModelStoreOwner
            val mainVm = owner?.let {
                androidx.lifecycle.ViewModelProvider(it)[io.legado.app.ui.main.MainViewModel::class.java]
            }
            ShelfMenuSheet(
                scope = scope,
                onDismiss = { shelfMenuOpen = false },
                onAction = { key ->
                    shelfMenuOpen = false
                    when (key) {
                        "updateToc" -> mainVm?.upToc(
                            items.filterIsInstance<BookshelfBookItemUi>()
                                .map { it.display.toMinimalBook() },
                            onlyUpdateRead = false,
                        )
                        "addLocal" -> context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.book.import.local.ImportBookActivity::class.java)
                        )
                        "addRemote" -> context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.book.import.remote.RemoteBookActivity::class.java)
                        )
                        "addUrl" -> addUrlOpen = true
                        "manage" -> context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.book.manage.BookshelfManageActivity::class.java)
                                .putExtra("groupId", groupId)
                        )
                        "groupManage" -> groupSheetOpen = true
                        "tagManage" -> context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.main.bookshelf.BookshelfTagManageActivity::class.java)
                                .putExtra("groupId", groupId)
                        )
                        "cache" -> HostCacheActivity.start(context, groupId)
                        "layoutConfig" -> settingsSheetOpen = true
                        "librarySearch" -> context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.main.librarysearch.LibrarySearchActivity::class.java)
                        )
                        // 2026-10-12 功能盘点修复：以下三项此前无 handler（点击无响应的死菜单）
                        "search" -> HostSearchActivity.start(context)
                        "collection" -> context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.main.bookshelf.BookCollectionActivity::class.java)
                        )
                        "smartGroup" -> context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.main.bookshelf.smartgroup.SmartGroupRuleActivity::class.java)
                        )
                        "log" -> HostLogActivity.start(context)
                    }
                },
            )
        }

        // ---- V5 原生弹层（书架设置 / 分组管理 / 添加网址） ----
        if (settingsSheetOpen) {
            ShelfSettingsSheet(
                scope = scope,
                layout = layout,
                onLayoutChange = { layout = it },
                onChanged = { refresh++ },
                onDismiss = {
                    settingsSheetOpen = false
                    refresh++
                },
            )
        }
        if (groupSheetOpen) {
            GroupManageSheet(scope = scope, onDismiss = { groupSheetOpen = false })
        }
        if (addUrlOpen) {
            val shelfVm = (context as? androidx.lifecycle.ViewModelStoreOwner)?.let {
                androidx.lifecycle.ViewModelProvider(it)[io.legado.app.ui.main.bookshelf.BookshelfViewModel::class.java]
            }
            if (shelfVm != null) {
                AddByUrlDialog(
                    scope = scope,
                    onConfirm = { url -> shelfVm.addBookByUrl(url) },
                    onDismiss = { addUrlOpen = false },
                )
            }
        }
    }

    private fun setLayout(context: Context, value: Int, apply: (Int) -> Unit) {
        context.putPrefInt(PreferKey.bookshelfLayout, value)
        apply(value)
    }
}

@Composable
private fun BackToRootRow(scope: ContentScope, onClick: () -> Unit) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(colors.primary.copy(alpha = 0.12f), scope.shape(io.legado.app.uikit.v5.token.ShapeKind.BUTTON))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.v5_shelf_back_to_groups),
            style = scope.type.label,
            color = colors.primary,
        )
    }
}

/** 继续阅读卡（V5 材质版；数据口径与旧 ContinueReadingCard 一致：durRatio>1% 取最近）。 */
@Composable
private fun ContinueReadingCardV5(
    scope: ContentScope,
    reading: BookshelfBookItemUi?,
    onOpen: (BookshelfItemUi) -> Unit,
) {
    if (reading == null) return
    val d = scope.density
    AppSurface(
        level = MaterialLevel.RAISED,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = d.blockGap / 2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onOpen(reading) })
                .padding(d.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(d.blockGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCover(
                label = reading.display.name,
                progress = reading.durRatio,
                modifier = Modifier
                    .height(58.dp)
                    .fillMaxWidth(0.16f),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.v5_shelf_continue_read),
                    style = scope.type.caption,
                    color = scope.color.onSurfaceVariant,
                )
                Text(
                    text = reading.display.name,
                    style = scope.type.title,
                    color = scope.color.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                AppProgressBar(reading.durRatio)
            }
        }
    }
}

/**
 * P4-1b 书架完整菜单（对齐旧版 main_bookshelf 16 项能力，实现方式由 V5 决定）。
 * 本轮接通 13 项；导出/导入书架（文件 launcher）与标签筛选（多选状态）登记下轮。
 */
@Composable
private fun ShelfMenuSheet(
    scope: ContentScope,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    val d = scope.density
    io.legado.app.uikit.v5.components.AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(d.screenPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("书架菜单", style = scope.type.title, color = colors.onSurface)
            Spacer(Modifier.height(d.blockGap))

            MenuSection(scope, "书籍") {
                MenuItem(scope, "search", "搜索") { onAction(it) }
                MenuItem(scope, "addLocal", "导入本地书籍") { onAction(it) }
                MenuItem(scope, "addRemote", "添加远程书籍") { onAction(it) }
                MenuItem(scope, "addUrl", "添加网址") { onAction(it) }
            }
            MenuSection(scope, "管理") {
                MenuItem(scope, "manage", "书架管理") { onAction(it) }
                MenuItem(scope, "groupManage", "分组管理") { onAction(it) }
                MenuItem(scope, "collection", "书架集合") { onAction(it) }
                MenuItem(scope, "tagManage", "标签管理") { onAction(it) }
                // 2026-10-12 功能盘点复活：智能分组入口随旧书架菜单丢失（台账 #6b）
                MenuItem(scope, "smartGroup", "智能分组") { onAction(it) }
            }
            MenuSection(scope, "更新与缓存") {
                MenuItem(scope, "updateToc", "刷新目录") { onAction(it) }
                MenuItem(scope, "cache", "缓存与导出") { onAction(it) }
            }
            MenuSection(scope, "布局与工具") {
                MenuItem(scope, "layoutConfig", "布局设置") { onAction(it) }
                MenuItem(scope, "librarySearch", "书库搜索") { onAction(it) }
                MenuItem(scope, "log", "运行日志") { onAction(it) }
            }
        }
    }
}

@Composable
private fun MenuSection(scope: ContentScope, title: String, content: @Composable () -> Unit) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    Column {
        Text(
            title,
            style = scope.type.caption,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MenuItem(scope: ContentScope, key: String, label: String, onAction: (String) -> Unit) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onAction(key) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = scope.type.body, color = colors.onSurface)
    }
}

/** 排序口径与旧 sortShelfDisplays 一致。 */
private fun sortShelf(
    books: List<BookShelfDisplay>,
    sort: Int,
): List<BookShelfDisplay> = when (sort) {
    1 -> books.sortedByDescending { it.latestChapterTime }
    2 -> books.sortedWith { a, b -> a.name.cnCompare(b.name) }
    3 -> books.sortedBy { it.order }
    4 -> books.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
    else -> books.sortedByDescending { it.durChapterTime }
}
