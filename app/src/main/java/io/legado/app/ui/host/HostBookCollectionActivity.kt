package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCollection
import io.legado.app.data.entities.BookCollectionWithItems
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manage.BookshelfManageViewModel
import io.legado.app.ui.main.bookshelf.BookCollectionSelectDialog
import io.legado.app.ui.main.bookshelf.BookGroupSelectDialog
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppGridItem
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSelectionAction
import io.legado.app.uikit.v5.components.AppSelectionBar
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.cnCompare
import io.legado.app.utils.postEvent
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-3：书架集合 V5 化（2026-10-13）。
 * 数据层直接 appDb（与旧页一致）；拖拽投放降级为选择条操作
 * （加入合集/加入分组/移到根目录，功能等价，台账 #36 注明）。
 * 合集删除补确认框（B 门，旧版无确认）。
 */
class HostBookCollectionActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_COLLECTION_ID = "collectionId"
        fun start(context: Context, collectionId: Long) {
            context.startActivity(
                Intent(context, HostBookCollectionActivity::class.java)
                    .putExtra(EXTRA_COLLECTION_ID, collectionId)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collectionId = intent.getLongExtra(EXTRA_COLLECTION_ID, 0L)
        setV5Content { HostBookCollectionScreen(collectionId) }
    }
}

/** 列表单元：书或子合集。 */
private sealed interface CollectionCell {
    val key: String

    data class BookCell(val book: Book) : CollectionCell {
        override val key get() = "b:${book.bookUrl}"
    }

    data class CollectionCellUi(val item: BookCollectionWithItems) : CollectionCell {
        override val key get() = "c:${item.collection.collectionId}"
    }
}

@Composable
private fun HostBookCollectionScreen(collectionId: Long) {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val d = visual.density
    val scope = rememberCoroutineScope()
    val manageViewModel = remember { ViewModelProvider(activity)[BookshelfManageViewModel::class.java] }

    var collectionName by remember { mutableStateOf("") }
    var cells by remember { mutableStateOf<List<CollectionCell>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteOriginal by remember { mutableStateOf(false) }
    val selectedBooks = remember { mutableStateOf(setOf<String>()) }
    val selectedCollections = remember { mutableStateOf(setOf<Long>()) }
    var selectionActive by remember { mutableStateOf(false) }

    // 数据：书 + 子合集（与旧页 combine 一致）
    DisposableEffect(collectionId) {
        val job = scope.launch {
            combine(
                appDb.bookCollectionDao.flowBooks(collectionId),
                appDb.bookCollectionDao.flowChildCollections(collectionId),
            ) { books, collections ->
                val visibleBooks = books.filterNot { it.isNotShelf }
                val sorted = sortBooks(visibleBooks)
                val childCells = buildCollectionCells(collections, visibleBooks.map { it.bookUrl }.toSet())
                childCells + sorted.map { CollectionCell.BookCell(it) }
            }.catch { AppToast.show(it.toString()) }
                .flowOn(Dispatchers.IO).conflate()
                .collect { cells = it }
        }
        onDispose { job.cancel() }
    }
    DisposableEffect(collectionId) {
        val job = scope.launch {
            val c = withContext(Dispatchers.IO) { appDb.bookCollectionDao.getCollection(collectionId) }
            collectionName = c?.name ?: ""
        }
        onDispose { job.cancel() }
    }
    DisposableEffect(Unit) {
        onDispose { postEvent(EventBus.BOOKSHELF_REFRESH, "") }
    }

    val selectedCount = selectedBooks.value.size + selectedCollections.value.size

    fun exitSelection() {
        selectionActive = false
        selectedBooks.value = emptySet()
        selectedCollections.value = emptySet()
    }

    fun toggleBook(book: Book) {
        val url = book.bookUrl
        selectedBooks.value = if (url in selectedBooks.value) selectedBooks.value - url
        else selectedBooks.value + url
    }

    fun toggleCollection(id: Long) {
        selectedCollections.value = if (id in selectedCollections.value) selectedCollections.value - id
        else selectedCollections.value + id
    }

    BackHandler(enabled = true) {
        if (selectionActive) exitSelection() else activity.finish()
    }

    val selectionBar: @Composable () -> Unit = {
        if (selectionActive) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.delete), danger = true) { confirmDelete = true },
                    AppSelectionAction(stringResource(R.string.v5_select_all)) {
                        cells.filterIsInstance<CollectionCell.BookCell>()
                            .forEach { selectedBooks.value = selectedBooks.value + it.book.bookUrl }
                        cells.filterIsInstance<CollectionCell.CollectionCellUi>()
                            .forEach { selectedCollections.value = selectedCollections.value + it.item.collection.collectionId }
                    },
                    AppSelectionAction(stringResource(R.string.v5_cancel)) { exitSelection() },
                ),
                overflowActions = listOf(
                    AppSelectionAction(stringResource(R.string.book_info)) {
                        val first = cells.filterIsInstance<CollectionCell.BookCell>()
                            .firstOrNull { it.book.bookUrl in selectedBooks.value }?.book
                        if (first != null && selectedCount == 1) {
                            io.legado.app.ui.book.info.BookInfoNavigator.open(context, first)
                            exitSelection()
                        }
                    },
                    AppSelectionAction(stringResource(R.string.add_to_collection)) {
                        if (selectedBooks.value.isNotEmpty()) {
                            BookCollectionSelectDialog(ArrayList(selectedBooks.value))
                                .show(activity.supportFragmentManager, "collectionSelect")
                            exitSelection()
                        }
                    },
                    AppSelectionAction(stringResource(R.string.add_to_group)) {
                        if (selectedBooks.value.isNotEmpty() && selectedCollections.value.isEmpty()) {
                            BookGroupSelectDialog(ArrayList(selectedBooks.value))
                                .show(activity.supportFragmentManager, "groupSelect")
                            exitSelection()
                        }
                    },
                    AppSelectionAction("移到根目录") {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                appDb.bookCollectionDao.moveItemsToRoot(
                                    selectedBooks.value.toList(),
                                    selectedCollections.value.toList(),
                                )
                            }
                            postEvent(EventBus.BOOKSHELF_REFRESH, "")
                            exitSelection()
                        }
                    },
                ),
                overflowTitle = stringResource(R.string.v5_batch_actions_title, selectedCount),
                moreLabel = stringResource(R.string.v5_more),
            )
        }
    }

    HostV5Page(
        title = collectionName.ifBlank { stringResource(R.string.book_collection) },
        subtitle = stringResource(R.string.v5_source_count, cells.size),
        onBack = { activity.finish() },
        selectionCount = if (selectionActive) selectedCount else null,
        onExitSelection = { exitSelection() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
        bottomBar = selectionBar,
    ) {
        if (cells.isEmpty()) {
            AppEmpty(
                title = stringResource(R.string.book_collection),
                supporting = stringResource(R.string.empty),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = d.screenPadding,
                    end = d.screenPadding,
                    top = d.blockGap / 2,
                    bottom = d.screenPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
                verticalArrangement = Arrangement.spacedBy(d.blockGap / 2),
            ) {
                items(cells, key = { it.key }) { cell ->
                    when (cell) {
                        is CollectionCell.BookCell -> {
                            val book = cell.book
                            LongPressGridSlot(
                                onLongClick = {
                                    selectionActive = true
                                    toggleBook(book)
                                },
                            ) { modifier ->
                                AppGridItem(
                                    headline = book.name,
                                    media = {
                                        io.legado.app.ui.widget.compose.BookCoverImage(
                                            book = book,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(0.75f),
                                            fillBounds = true,
                                        )
                                    },
                                    onClick = {
                                        if (selectionActive) toggleBook(book)
                                        else context.startActivityForBook(book)
                                    },
                                    modifier = modifier,
                                )
                            }
                        }

                        is CollectionCell.CollectionCellUi -> {
                            val item = cell.item
                            val id = item.collection.collectionId
                            LongPressGridSlot(
                                onLongClick = {
                                    selectionActive = true
                                    toggleCollection(id)
                                },
                            ) { modifier ->
                                AppGridItem(
                                    headline = item.collection.name,
                                    media = {
                                        io.legado.app.ui.widget.compose.BookCoverImage(
                                            book = item.books.firstOrNull()
                                                ?: Book(name = item.collection.name),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(0.75f),
                                            fillBounds = true,
                                        )
                                    },
                                    onClick = {
                                        if (selectionActive) toggleCollection(id)
                                        else HostBookCollectionActivity.start(context, id)
                                    },
                                    modifier = modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (menuOpen) {
        AppMenuSheet(
            title = collectionName.ifBlank { stringResource(R.string.book_collection) },
            actions = listOf(
                stringResource(R.string.rename) to {
                    activity.showComposeTextInputDialog(
                        title = activity.getString(R.string.rename),
                        initialValue = collectionName,
                        onPositive = { text ->
                            if (text.isNotBlank()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        appDb.bookCollectionDao.getCollection(collectionId)?.let {
                                            appDb.bookCollectionDao.update(it.copy(name = text.trim()))
                                        }
                                    }
                                    collectionName = text.trim()
                                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                                }
                            }
                        },
                    )
                },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    if (confirmDelete) {
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.sure_del),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val books = cells.filterIsInstance<CollectionCell.BookCell>()
                            .map { it.book }
                            .filter { it.bookUrl in selectedBooks.value }
                        if (books.isNotEmpty()) {
                            manageViewModel.deleteBook(books, deleteOriginal)
                        }
                        if (selectedCollections.value.isNotEmpty()) {
                            appDb.bookCollectionDao.deleteCollectionsAndRelease(
                                selectedCollections.value.toList()
                            )
                        }
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    exitSelection()
                }
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 合集单元格构建（子合集卡片，去重 shelfKey 语义简化）。 */
private fun buildCollectionCells(
    collections: List<BookCollectionWithItems>,
    visibleBookUrls: Set<String>,
): List<CollectionCell.CollectionCellUi> {
    return collections.mapNotNull { item ->
        val hasVisible = item.books.any { it.bookUrl in visibleBookUrls || !it.isNotShelf }
        if (item.books.isNotEmpty() && !hasVisible) null
        else CollectionCell.CollectionCellUi(item)
    }
}

/** 排序口径与旧页 sortBooks 一致。 */
private fun sortBooks(books: List<Book>): List<Book> {
    return when (AppConfig.bookshelfSort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
        5 -> books.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }
        else -> books
    }
}

/** 网格单元长按选择槽（AppGridItem 无长按参数，外包 combinedClickable）。 */
@Composable
private fun LongPressGridSlot(
    onLongClick: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    content(
        Modifier.combinedClickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onLongClick = onLongClick,
            onClick = {},
        )
    )
}
