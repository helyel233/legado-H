package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.help.config.LocalConfig
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.contains
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AutoTaskBookSettings
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.book.manage.BookshelfManageViewModel
import io.legado.app.ui.book.manage.SourcePickerDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.V5DialogPanel
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppLoading
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppNumberField
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppReorderableItem
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSelectionAction
import io.legado.app.uikit.v5.components.AppSelectionBar
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.rememberAppReorderableListState
import io.legado.app.uikit.v5.components.rememberAppSelectionState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** 分组弹窗请求码（与 GroupSelectDialog.CallBack 对齐）。 */
private const val REQ_MOVE = 1
private const val REQ_ADD = 2
private const val REQ_REMOVE = 3
private const val REQ_ITEM = 4

/**
 * P4-3：书架批量管理 V5 化（2026-10-13）。
 * 数据层复用 [BookshelfManageViewModel]（零拷贝）；分组选择/分组管理/换源弹窗
 * 复用旧 ComposeDialogFragment（Activity 实现其 CallBack）；删除补"删除本地文件"
 * 开关与确认（B 门）。滑动框选以"长按进入 + 点选/全选/反选/选中区间"等价覆盖。
 */
class HostBookshelfManageActivity : AppCompatActivity(),
    GroupSelectDialog.CallBack,
    SourcePickerDialog.Callback {

    companion object {
        private const val EXTRA_GROUP_ID = "groupId"

        fun start(context: Context, groupId: Long) {
            context.startActivity(
                Intent(context, HostBookshelfManageActivity::class.java)
                    .putExtra(EXTRA_GROUP_ID, groupId)
            )
        }
    }

    /** 屏幕回调转发（Compose 侧注册，弹窗 CallBack 走这里回列表）。 */
    interface ScreenApi {
        fun onGroupSelected(requestCode: Int, groupId: Long)
        fun onSourcePicked(source: BookSource)
    }

    private var screenApi: ScreenApi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val groupId = intent.getLongExtra(EXTRA_GROUP_ID, BookGroup.IdRoot)
        setV5Content {
            HostBookshelfManageScreen(
                groupId,
                onApiReady = { screenApi = it },
                onApiDispose = { screenApi = null },
            )
        }
    }

    override fun onDestroy() {
        screenApi = null
        super.onDestroy()
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        screenApi?.onGroupSelected(requestCode, groupId)
    }

    override fun sourceOnClick(source: BookSource) {
        screenApi?.onSourcePicked(source)
    }
}

@Composable
private fun HostBookshelfManageScreen(
    initialGroupId: Long,
    onApiReady: (HostBookshelfManageActivity.ScreenApi) -> Unit,
    onApiDispose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val viewModel = remember { ViewModelProvider(activity)[BookshelfManageViewModel::class.java] }

    var groupId by remember { mutableStateOf(initialGroupId) }
    var groupName by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteOriginal by remember { mutableStateOf(LocalConfig.deleteBookOriginal) }
    var autoTaskOpen by remember { mutableStateOf(false) }
    var itemGroupTarget by remember { mutableStateOf<Book?>(null) }
    var itemDeleteTarget by remember { mutableStateOf<Book?>(null) }
    var clickTitleOpenInfo by remember { mutableStateOf(AppConfig.openBookInfoByClickTitle) }

    val selection = rememberAppSelectionState<Book>(idOf = { it.bookUrl })
    val selectionMode = selection.selectionMode

    // 分组列表
    val groups by remember {
        appDb.bookGroupDao.flowAll()
            .catch { AppToast.show(it.toString()) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // 当前分组的书（排序口径与旧一致）
    val allBooks by remember(groupId) {
        appDb.bookDao.flowByGroup(groupId).map { list ->
            when (AppConfig.getBookSortByGroupId(groupId)) {
                1 -> list.sortedByDescending { it.latestChapterTime }
                2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
                3 -> list.sortedBy { it.order }
                4 -> list.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
                else -> list.sortedByDescending { it.durChapterTime }
            }
        }.catch { AppToast.show(it.toString()) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val books = remember(allBooks, filter) {
        if (filter.isBlank()) allBooks else allBooks.filter { it.contains(filter) }
    }
    val reorderEnabled = !selectionMode && AppConfig.getBookSortByGroupId(groupId) == 3

    // 批量换源进度（LiveData → 行内展示）
    val changingSource by viewModel.batchChangeSourceState.observeAsStateCompat(false)
    val changeProgress by viewModel.batchChangeSourceProcessLiveData.observeAsStateCompat("")

    // 弹窗回调注册（分组选择 / 换源）
    DisposableEffect(Unit) {
        val api = object : HostBookshelfManageActivity.ScreenApi {
            override fun onGroupSelected(requestCode: Int, gid: Long) {
                when (requestCode) {
                    REQ_MOVE, REQ_ADD, REQ_REMOVE -> {
                        val sel = selection.selectedFrom(books)
                        if (sel.isNotEmpty()) {
                            val array = Array(sel.size) { i ->
                                val book = sel[i]
                                when (requestCode) {
                                    REQ_ADD -> book.copy(group = book.group or gid)
                                    REQ_REMOVE -> book.copy(group = book.group and gid.inv())
                                    else -> book.copy(group = gid)
                                }
                            }
                            viewModel.updateBook(*array)
                            selection.exit()
                        }
                    }
                    REQ_ITEM -> itemGroupTarget?.let { book ->
                        viewModel.updateBook(book.copy(group = gid))
                        itemGroupTarget = null
                    }
                }
            }

            override fun onSourcePicked(source: BookSource) {
                val sel = selection.selectedFrom(books)
                if (sel.isNotEmpty()) {
                    viewModel.changeSource(sel, source)
                    viewModel.batchChangeSourceState.value = true
                }
            }
        }
        onApiReady(api)
        onDispose { onApiDispose() }
    }

    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { AppToast.show(it.toString()) }
    }

    val listState = rememberLazyListState()
    var orderedBooks by remember(books) { mutableStateOf(books) }
    val reorderState = rememberAppReorderableListState(
        lazyListState = listState,
        onMove = { from, to ->
            orderedBooks = orderedBooks.toMutableList().apply { add(to, removeAt(from)) }
        },
    )

    val selectionBar: @Composable () -> Unit = {
        if (selectionMode) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.move_to_group)) {
                        GroupSelectDialog(0, REQ_MOVE)
                            .show(activity.supportFragmentManager, "groupSelect")
                    },
                    AppSelectionAction(stringResource(R.string.v5_select_all)) { selection.selectAll(books) },
                    AppSelectionAction(stringResource(R.string.v5_select_invert)) { selection.invert(books) },
                    AppSelectionAction(stringResource(R.string.v5_delete), danger = true) { confirmDelete = true },
                ),
                overflowActions = listOf(
                    AppSelectionAction(stringResource(R.string.allow_update)) {
                        viewModel.upCanUpdate(selection.selectedFrom(books), true)
                    },
                    AppSelectionAction(stringResource(R.string.disable_update)) {
                        viewModel.upCanUpdate(selection.selectedFrom(books), false)
                    },
                    AppSelectionAction(stringResource(R.string.add_to_group)) {
                        GroupSelectDialog(0, REQ_ADD).show(activity.supportFragmentManager, "groupSelect")
                    },
                    AppSelectionAction(stringResource(R.string.remove_group)) {
                        GroupSelectDialog(0, REQ_REMOVE).show(activity.supportFragmentManager, "groupSelect")
                    },
                    AppSelectionAction(stringResource(R.string.change_source_batch)) {
                        SourcePickerDialog().show(activity.supportFragmentManager, "sourcePicker")
                    },
                    AppSelectionAction(stringResource(R.string.clear_cache)) {
                        viewModel.clearCache(selection.selectedFrom(books))
                    },
                    AppSelectionAction(stringResource(R.string.check_selected_interval)) {
                        val ids = selection.selectedFrom(books).map { it.bookUrl }.toSet()
                        val first = books.indexOfFirst { it.bookUrl in ids }
                        val last = books.indexOfLast { it.bookUrl in ids }
                        if (first >= 0 && last > first) {
                            books.subList(first, last + 1).forEach { selection.enter(it) }
                        }
                    },
                    AppSelectionAction(stringResource(R.string.batch_auto_task_update)) {
                        viewModel.findAutoTaskBooks(selection.selectedFrom(books)) { eligible ->
                            if (eligible.isEmpty()) {
                                AppToast.show(context.getString(R.string.no_book_can_auto_update))
                            } else {
                                autoTaskOpen = true
                            }
                        }
                    },
                ),
                overflowTitle = stringResource(R.string.v5_batch_actions_title, selection.count),
                moreLabel = stringResource(R.string.v5_more),
            )
        }
    }

    HostV5Page(
        title = groupName.ifBlank { stringResource(R.string.bookshelf_management) },
        subtitle = stringResource(R.string.v5_source_count, books.size),
        onBack = { activity.finish() },
        selectionCount = if (selectionMode) selection.count else null,
        onExitSelection = { selection.exit() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
        bottomBar = selectionBar,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppSearchField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 4),
                placeholder = stringResource(R.string.screen),
            )
            if (changingSource) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = density.screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = changeProgress.ifBlank { stringResource(R.string.change_source_batch) },
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    AppLoading(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.cancel),
                        style = visual.type.caption,
                        color = colors.primary,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                viewModel.batchChangeSourceCoroutine?.cancel()
                                viewModel.batchChangeSourceState.value = false
                            }
                            .padding(4.dp),
                    )
                }
            }
            if (books.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.bookshelf_management),
                    supporting = stringResource(R.string.no_book),
                )
            } else {
                AppFastScroller(
                    listState = listState,
                    topContentPadding = density.blockGap / 2,
                    bottomContentPadding = density.screenPadding,
                    endContentPadding = density.screenPadding,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = density.screenPadding,
                            end = density.screenPadding,
                            top = density.blockGap / 2,
                            bottom = density.screenPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        if (reorderEnabled) {
                            items(orderedBooks, key = { it.bookUrl }) { book ->
                                AppReorderableItem(
                                    reorderState,
                                    key = book.bookUrl,
                                    onDragStopped = { viewModel.updateBook(*orderedBooks.toTypedArray()) },
                                ) { _, dragHandle ->
                                    ManageBookRow(
                                        book = book,
                                        groups = groups,
                                        selected = selection.isSelected(book),
                                        selectionMode = selectionMode,
                                        clickTitleOpenInfo = clickTitleOpenInfo,
                                        dragHandle = dragHandle,
                                        onClick = {
                                            if (selectionMode) selection.toggle(book)
                                            else if (clickTitleOpenInfo) BookInfoNavigator.open(context, book)
                                        },
                                        onLongClick = { selection.enter(book) },
                                        onChangeGroup = { itemGroupTarget = book },
                                        onDelete = { itemDeleteTarget = book },
                                    )
                                }
                            }
                        } else {
                            items(books, key = { it.bookUrl }) { book ->
                                ManageBookRow(
                                    book = book,
                                    groups = groups,
                                    selected = selection.isSelected(book),
                                    selectionMode = selectionMode,
                                    clickTitleOpenInfo = clickTitleOpenInfo,
                                    dragHandle = null,
                                    onClick = {
                                        if (selectionMode) selection.toggle(book)
                                        else if (clickTitleOpenInfo) BookInfoNavigator.open(context, book)
                                    },
                                    onLongClick = { selection.enter(book) },
                                    onChangeGroup = { itemGroupTarget = book },
                                    onDelete = { itemDeleteTarget = book },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ⋮ 菜单：分组切换 / 分组管理 / 导出在用书源 / 点书名开详情开关
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.bookshelf_management),
            actions = buildList {
                groups.forEach { group ->
                    add(
                        (if (group.groupId == groupId) "✓ " else "") + group.groupName to {
                            groupId = group.groupId
                            groupName = group.groupName
                        }
                    )
                }
                add(stringResource(R.string.group_manage) to {
                    GroupManageDialog().show(activity.supportFragmentManager, "groupManage")
                })
                add(stringResource(R.string.export_all_use_book_source) to {
                    viewModel.saveAllUseBookSourceToFile { file ->
                        exportLauncher.launch {
                            mode = HandleFileContract.EXPORT
                            fileData = HandleFileContract.FileData(
                                "bookSource.json",
                                file,
                                "application/json",
                            )
                        }
                    }
                })
                add(
                    (if (clickTitleOpenInfo) "✓ " else "") +
                        stringResource(R.string.open_book_info_by_click_title) to {
                        clickTitleOpenInfo = !clickTitleOpenInfo
                        AppConfig.openBookInfoByClickTitle = clickTitleOpenInfo
                    }
                )
            },
            onDismiss = { menuOpen = false },
        )
    }

    // 批量删除确认（带"删除本地文件"开关）
    if (confirmDelete) {
        DeleteConfirmDialog(
            bookName = null,
            deleteOriginal = deleteOriginal,
            onDeleteOriginalChange = { deleteOriginal = it },
            onConfirm = {
                LocalConfig.deleteBookOriginal = deleteOriginal
                viewModel.deleteBook(selection.selectedFrom(books), deleteOriginal)
                selection.exit()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }

    // 单本删除确认（本地书才给"删除本地文件"）
    itemDeleteTarget?.let { book ->
        DeleteConfirmDialog(
            bookName = book.name,
            showOriginal = book.isLocal,
            deleteOriginal = deleteOriginal,
            onDeleteOriginalChange = { deleteOriginal = it },
            onConfirm = {
                if (book.isLocal) LocalConfig.deleteBookOriginal = deleteOriginal
                viewModel.deleteBook(listOf(book), LocalConfig.deleteBookOriginal)
                itemDeleteTarget = null
            },
            onDismiss = { itemDeleteTarget = null },
        )
    }

    // 批量自动更新对话框（V5：开关×3 + 间隔小时）
    if (autoTaskOpen) {
        var enabled by remember { mutableStateOf(true) }
        var notify by remember { mutableStateOf(true) }
        var cache by remember { mutableStateOf(false) }
        var hours by remember {
            mutableStateOf(
                LocalConfig.bookAutoTaskIntervalHours.coerceIn(
                    AutoTaskBookSettings.MIN_INTERVAL_HOURS,
                    AutoTaskBookSettings.MAX_INTERVAL_HOURS,
                )
            )
        }
        V5DialogPanel(
            title = stringResource(R.string.batch_auto_task_update_dialog_title, selection.count),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    SwitchLine(stringResource(R.string.auto_task_enable), enabled) { enabled = it }
                    SwitchLine(stringResource(R.string.auto_task_notify_switch), notify) { notify = it }
                    SwitchLine(stringResource(R.string.auto_task_cache_switch), cache) { cache = it }
                    AppNumberField(
                        value = hours,
                        onValueChange = { hours = it },
                        range = AutoTaskBookSettings.MIN_INTERVAL_HOURS..
                            AutoTaskBookSettings.MAX_INTERVAL_HOURS,
                        label = stringResource(R.string.auto_task_interval) +
                            "（${stringResource(R.string.time_hour)}）",
                    )
                }
            },
            actions = {
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    onClick = { autoTaskOpen = false },
                )
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        viewModel.saveAutoTaskBooks(
                            selection.selectedFrom(books),
                            AutoTaskBookSettings(
                                enabled = enabled,
                                notifyEnabled = notify,
                                cacheEnabled = cache,
                                intervalHours = hours,
                            ),
                        ) { saved ->
                            if (saved == 0) {
                                AppToast.show(context.getString(R.string.no_book_can_auto_update))
                            } else {
                                LocalConfig.bookAutoTaskIntervalHours = hours
                                AppToast.show(
                                    context.getString(
                                        if (enabled) R.string.auto_task_book_update_saved
                                        else R.string.auto_task_book_update_deleted
                                    )
                                )
                            }
                        }
                        autoTaskOpen = false
                    },
                )
            },
        )
    }
}

/** 删除确认（V5：V5DialogPanel + 删除本地文件开关）。 */
@Composable
private fun DeleteConfirmDialog(
    bookName: String?,
    showOriginal: Boolean = true,
    deleteOriginal: Boolean,
    onDeleteOriginalChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val extraContent: (@Composable () -> Unit)? = if (showOriginal) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.delete_book_file),
                    style = visual.type.body,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AppSwitch(checked = deleteOriginal, onCheckedChange = onDeleteOriginalChange)
            }
        }
    } else null
    V5DialogPanel(
        title = stringResource(R.string.draw),
        message = bookName?.let { stringResource(R.string.sure_del) + "\n" + it }
            ?: stringResource(R.string.sure_del),
        content = extraContent ?: {},
        actions = {
            AppGhostButton(text = stringResource(R.string.cancel), onClick = onDismiss)
            AppFilledButton(text = stringResource(R.string.ok), onClick = onConfirm)
        },
    )
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = visual.type.body,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ManageBookRow(
    book: Book,
    groups: List<BookGroup>,
    selected: Boolean,
    selectionMode: Boolean,
    clickTitleOpenInfo: Boolean,
    dragHandle: Modifier?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onChangeGroup: () -> Unit,
    onDelete: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val groupLabel = groups.filter { book.group and it.groupId > 0 }
        .joinToString("、") { it.groupName }
    AppSelectionRow(
        title = book.name,
        supporting = buildString {
            append(book.author)
            if (groupLabel.isNotBlank()) {
                append(" · ")
                append(groupLabel)
            }
            if (book.originName.isNotBlank()) {
                append(" · ")
                append(book.originName)
            }
        },
        selected = selected,
        selectionMode = selectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = dragHandle?.let { handle -> { Box(modifier = handle) } },
        trailing = {
            if (!selectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!clickTitleOpenInfo) {
                        Text(
                            text = "分组",
                            style = visual.type.caption,
                            color = colors.primary,
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { onChangeGroup() }
                                .padding(6.dp),
                        )
                    }
                    AppOverflowMenuButton(
                        contentDescription = stringResource(R.string.v5_more),
                        glyph = "⋯",
                    ) { onDelete() }
                }
            }
        },
    )
}
