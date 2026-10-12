package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import io.legado.app.uikit.v5.components.AppFastScroller
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.ACache
import io.legado.app.model.CheckSource
import io.legado.app.model.CheckSourceSession
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModel
import io.legado.app.ui.book.source.manage.GroupManageDialog
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.compose.showComposeSuggestionTextInputDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSelectionAction
import io.legado.app.uikit.v5.components.AppSelectionBar
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.rememberAppSelectionState
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppProgressBar
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.share
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity

/**
 * P4-3 管理页原生化①：书源管理 V5 原生。
 *
 * 能力对齐 **1.7.3 分支**（origin/archive/pre-1.7.3 的 BookSourceActivity）：
 *  - 筛选：状态 chips + 分组 chips（含 group: 前缀搜索）
 *  - 排序：手动(默认)/自动(权重)/名称/URL/更新时间/启用/响应速度 × 升/降序
 *  - 按域名分组显示（开关）
 *  - 导入：新建 / 在线（URL 输入 + 历史建议）/ 本地文件（txt/json）/ 扫码
 *  - 分组管理（GroupManageDialog）
 *  - 选择模式：全选/反选、启用/禁用、选书开/关、置顶/置底、加分组/移分组、
 *    导出(SAF)、分享、校验书源（关键词 + 校验配置）、校验选中区间、删除
 *
 * 数据层全复用 BookSourceViewModel + bookSourceDao reactive Flow。
 */
class HostBookSourceManageActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostBookSourceManageActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostBookSourceManageScreen() }
    }
}

private enum class StatusFilter(val labelRes: Int) {
    ALL(R.string.v5_all), ENABLED(R.string.v5_enabled), DISABLED(R.string.v5_disabled),
    LOGIN(R.string.v5_need_login), NO_GROUP(R.string.v5_no_group),
    EXPLORE_ON(R.string.v5_explore_on), EXPLORE_OFF(R.string.v5_explore_off),
}

private const val IMPORT_RECORD_KEY = "bookSourceRecordKey"

@Composable
private fun HostBookSourceManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember {
        ViewModelProvider(activity)[BookSourceViewModel::class.java]
    }

    var searchKey by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }
    var sort by remember { mutableStateOf(BookSourceSort.Default) }
    var sortAscending by remember { mutableStateOf(true) }
    var groupByDomain by remember { mutableStateOf(false) }
    // 页级选择模式（AppSelectionState：集合操作收编，页面只写业务动作）
    val selection = rememberAppSelectionState<io.legado.app.data.entities.BookSourcePart>(idOf = { it.bookSourceUrl })
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var groupSheetFor by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var moreSelectionOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 长按多选首次提示（B 门：隐藏操作必须提示）
    var longPressHintShown by rememberSaveable { mutableStateOf(false) }

    val sources by remember(searchKey) {
        val key = searchKey.trim()
        when {
            key.startsWith("group:") -> appDb.bookSourceDao.flowGroupSearch(key.removePrefix("group:"))
            key.isNotBlank() -> appDb.bookSourceDao.flowSearch(key)
            else -> when (statusFilter) {
                StatusFilter.ALL -> appDb.bookSourceDao.flowAll()
                StatusFilter.ENABLED -> appDb.bookSourceDao.flowEnabled()
                StatusFilter.DISABLED -> appDb.bookSourceDao.flowDisabled()
                StatusFilter.LOGIN -> appDb.bookSourceDao.flowLogin()
                StatusFilter.NO_GROUP -> appDb.bookSourceDao.flowNoGroup()
                StatusFilter.EXPLORE_ON -> appDb.bookSourceDao.flowEnabledExplore()
                StatusFilter.EXPLORE_OFF -> appDb.bookSourceDao.flowDisabledExplore()
            }
        }
    }.collectAsState(initial = emptyList())

    val groups by remember { appDb.bookSourceDao.flowGroups() }
        .collectAsState(initial = emptyList())

    val sortedSources = remember(sources, sort, sortAscending) {
        val comparator = when (sort) {
            BookSourceSort.Default -> compareBy<BookSourcePart> { it.customOrder }
            BookSourceSort.Name -> compareBy { it.bookSourceName.lowercase() }
            BookSourceSort.Url -> compareBy { it.bookSourceUrl }
            BookSourceSort.Weight -> compareBy { it.weight }
            BookSourceSort.Update -> compareBy { it.lastUpdateTime }
            BookSourceSort.Enable -> compareBy { it.enabled }
            BookSourceSort.Respond -> compareBy { it.respondTime }
        }
        if (sortAscending) sources.sortedWith(comparator)
        else sources.sortedWith(comparator).asReversed()
    }

    // 按域名分组（连续同 host 插分组头）
    val displayRows = remember(sortedSources, groupByDomain) {
        if (!groupByDomain) {
            sortedSources.map { DomainRow.Source(it) }
        } else {
            val rows = mutableListOf<DomainRow>()
            var lastHost: String? = null
            sortedSources.forEach { source ->
                val host = runCatching { Uri.parse(source.bookSourceUrl).host }.getOrNull()
                if (host != null && host != lastHost) {
                    rows.add(DomainRow.Header(host))
                    lastHost = host
                }
                rows.add(DomainRow.Source(source))
            }
            rows
        }
    }

    val selectionMode = selection.selectionMode
    val selectedSources = selection.selectedFrom(sortedSources)

    // 首次进入选择模式提示长按多选（每个页面会话只提示一次）
    LaunchedEffect(selectionMode) {
        if (selectionMode && !longPressHintShown) {
            longPressHintShown = true
            AppToast.show(context.getString(R.string.v5_hint_long_press))
        }
    }

    // 校验会话（后端 CheckSourceSession，UI 只 collect）
    val checkSession by CheckSourceSession.state.collectAsState()
    // keepScreenOn 跟随会话（此前写在 onPositive 里永不复位 —— 泄漏，已修）
    LaunchedEffect(checkSession.running) {
        activity.keepScreenOn(checkSession.running)
    }
    // 完成/中断提示（running true→false 边沿）
    var wasRunning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(checkSession.running) {
        if (wasRunning && !checkSession.running && checkSession.finishCount > 0) {
            AppToast.show(
                context.getString(R.string.progress_show, "", checkSession.finishCount, checkSession.originSize),
            )
        }
        wasRunning = checkSession.running
    }

    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) {}
    val importDocLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportBookSourceDialog(uri.toString())
                .show(activity.supportFragmentManager, "importSource")
        }
    }
    val qrLauncher = rememberLauncherForActivityResult(QrCodeResult()) {
        it ?: return@rememberLauncherForActivityResult
        ImportBookSourceDialog(it).show(activity.supportFragmentManager, "importSource")
    }

    fun exportSelection() {
        viewModel.saveToFile(
            selectedSources, sortedSources.size, searchKey.trim().ifBlank { null },
            sortAscending, sort,
        ) { file, name ->
            exportLauncher.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(name, file, "application/json")
            }
        }
    }

    fun shareSelection() {
        viewModel.saveToFile(
            selectedSources, sortedSources.size, searchKey.trim().ifBlank { null },
            sortAscending, sort,
        ) { file, _ -> context.share(file, "application/json") }
    }

    /** 在线导入（URL 输入 + 历史建议，缓存与 1.7.3 同一 key）。 */
    fun importOnLine() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls = aCache.getAsString(IMPORT_RECORD_KEY)
            ?.splitNotBlank(",")?.toMutableList() ?: mutableListOf()
        activity.showComposeSuggestionTextInputDialog(
            title = activity.getString(R.string.import_on_line),
            hint = "url",
            suggestions = cacheUrls,
            deletable = true,
            onPositive = { text ->
                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                    cacheUrls.add(0, text)
                    aCache.put(IMPORT_RECORD_KEY, cacheUrls.joinToString(","))
                }
                ImportBookSourceDialog(text)
                    .show(activity.supportFragmentManager, "importSource")
            },
            onSuggestionDeleted = { url ->
                cacheUrls.remove(url)
                aCache.put(IMPORT_RECORD_KEY, cacheUrls.joinToString(","))
            },
        )
    }

    /** 校验书源（关键词 + 校验配置入口，与 1.7.3 同流程）。 */
    fun checkSources() {
        activity.showComposeTextInputDialog(
            title = activity.getString(R.string.search_book_key),
            hint = "search word",
            initialValue = CheckSource.keyword,
            neutralText = activity.getString(R.string.check_source_config),
            onPositive = { text ->
                if (text.isNotEmpty()) CheckSource.keyword = text
                CheckSource.start(activity, selectedSources)
            },
            onNeutral = {
                CheckSourceConfig().show(activity.supportFragmentManager, "checkSourceConfig")
            },
        )
    }

    /** 校验选中区间：把首末选中之间的源一并选中。 */
    fun checkSelectedInterval() {
        val positions = sortedSources.mapIndexedNotNull { index, source ->
            if (source.bookSourceUrl in selection.selected) index else null
        }
        if (positions.isEmpty()) return
        val newSelected = selection.selected.toMutableSet()
        for (index in positions.min()..positions.max()) {
            newSelected.add(sortedSources[index].bookSourceUrl)
        }
        selection.restoreSelected(newSelected)
    }

    fun exitSelection() {
        selection.exit()
    }

    // 选择模式操作条下沉为 AppSelectionBar（常用 5 项固定 + 「更多」溢出，迁移门 B/D2）
    val selectionBar: @Composable () -> Unit = {
        if (selectionMode) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_select_all)) {
                        selection.selectAll(sortedSources)
                    },
                    AppSelectionAction(stringResource(R.string.v5_select_invert)) {
                        selection.invert(sortedSources)
                    },
                    AppSelectionAction(stringResource(R.string.v5_enable)) { viewModel.enableSelection(selectedSources) },
                    AppSelectionAction(stringResource(R.string.v5_disable)) { viewModel.disableSelection(selectedSources) },
                    AppSelectionAction(stringResource(R.string.v5_delete), danger = true) {
                        confirmDelete = true
                    },
                ),
                overflowActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_explore_on)) { viewModel.enableSelectExplore(selectedSources) },
                    AppSelectionAction(stringResource(R.string.v5_explore_off)) { viewModel.disableSelectExplore(selectedSources) },
                    AppSelectionAction(stringResource(R.string.v5_move_top)) {
                        viewModel.topSource(*selectedSources.toTypedArray())
                        exitSelection()
                    },
                    AppSelectionAction(stringResource(R.string.v5_move_bottom)) {
                        viewModel.bottomSource(*selectedSources.toTypedArray())
                        exitSelection()
                    },
                    AppSelectionAction(stringResource(R.string.v5_add_group)) {
                        groupSheetFor = { text ->
                            viewModel.selectionAddToGroups(selectedSources, text)
                            exitSelection()
                        }
                    },
                    AppSelectionAction(stringResource(R.string.v5_remove_group)) {
                        groupSheetFor = { text ->
                            viewModel.selectionRemoveFromGroups(selectedSources, text)
                            exitSelection()
                        }
                    },
                    AppSelectionAction(stringResource(R.string.v5_check_source)) { checkSources() },
                    AppSelectionAction(stringResource(R.string.v5_check_interval)) { checkSelectedInterval() },
                    AppSelectionAction(stringResource(R.string.v5_export)) { exportSelection() },
                    AppSelectionAction(stringResource(R.string.v5_share)) { shareSelection() },
                ),
                overflowTitle = stringResource(R.string.v5_batch_actions_title, selection.count),
                moreLabel = stringResource(R.string.v5_more),
            )
        }
    }

    HostV5Page(
        title = stringResource(R.string.v5_source_manage_title),
        subtitle = if (!selectionMode) stringResource(R.string.v5_source_count, sortedSources.size) else null,
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
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    modifier = Modifier.padding(horizontal = density.screenPadding),
                    placeholder = stringResource(R.string.v5_search_hint_source),
                )
                Spacer(Modifier.height(density.blockGap))

                // 校验进度横幅（会话来自 CheckSourceSession）
                if (checkSession.running) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = density.screenPadding),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = checkSession.progressText,
                                style = visual.type.caption,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            AppGhostButton(
                                text = stringResource(R.string.cancel),
                                onClick = { CheckSource.stop(context) },
                            )
                        }
                        Spacer(Modifier.height(density.blockGap / 3))
                        AppProgressBar(
                            progress = if (checkSession.originSize > 0)
                                checkSession.finishCount.toFloat() / checkSession.originSize
                            else 0f,
                        )
                    }
                    Spacer(Modifier.height(density.blockGap))
                }

                // 状态筛选（组件化：AppFilterChipRow）
                AppFilterChipRow(
                    label = stringResource(R.string.v5_filter_status),
                    chips = StatusFilter.entries.map { f ->
                        AppFilterChip(stringResource(f.labelRes), statusFilter == f)
                    },
                    onChipClick = { index ->
                        statusFilter = StatusFilter.entries[index]
                        searchKey = ""
                    },
                )

                // 分组筛选（独立于状态，与 1.7.3 的 menu_group 对应）
                // 选项 >8 个时 chips 收拢为「全部 + ⋮」，全量选择走弹窗（横滑找不齐的解法）
                var groupFilterOpen by remember { mutableStateOf(false) }
                if (groups.isNotEmpty()) {
                    val manyGroups = groups.size > 8
                    Spacer(Modifier.height(density.blockGap))
                    AppFilterChipRow(
                        label = stringResource(R.string.v5_filter_group),
                        chips = if (manyGroups) {
                            listOf(AppFilterChip(stringResource(R.string.v5_all), searchKey.isBlank()))
                        } else {
                            listOf(AppFilterChip(stringResource(R.string.v5_all), searchKey.isBlank())) +
                                groups.map { g -> AppFilterChip(g, searchKey == "group:$g") }
                        },
                        onChipClick = { index ->
                            searchKey = if (index == 0) "" else "group:${groups[index - 1]}"
                        },
                        overflowLabel = if (manyGroups) stringResource(R.string.v5_more) else null,
                        onOverflowClick = if (manyGroups) {
                            { groupFilterOpen = true }
                        } else null,
                    )
                    if (groupFilterOpen) {
                        val selectedGroup = searchKey.removePrefix("group:")
                        AppFilterDialog(
                            title = stringResource(R.string.v5_filter_group),
                            options = listOf(stringResource(R.string.v5_all)) + groups,
                            selectedIndex = if (searchKey.isBlank()) 0
                            else (groups.indexOfFirst { it == selectedGroup } + 1).coerceAtLeast(0),
                            onSelect = { index ->
                                searchKey = if (index == 0) "" else "group:${groups[index - 1]}"
                            },
                            onDismiss = { groupFilterOpen = false },
                        )
                    }
                }
                Spacer(Modifier.height(density.blockGap))

                when {
                    sortedSources.isEmpty() -> AppEmpty(
                        title = stringResource(R.string.v5_empty_sources_title),
                        supporting = stringResource(R.string.v5_empty_sources_supporting),
                    )
                    else -> {
                        val sourceListState = rememberLazyListState()
                        AppFastScroller(
                            listState = sourceListState,
                            topContentPadding = density.blockGap / 2,
                            bottomContentPadding = density.screenPadding,
                            endContentPadding = density.screenPadding,
                        ) {
                        LazyColumn(
                            state = sourceListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = density.screenPadding,
                                end = density.screenPadding,
                                top = density.blockGap / 2,
                                bottom = density.screenPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                        ) {
                        items(displayRows, key = { row ->
                            when (row) {
                                is DomainRow.Header -> "h:${row.host}"
                                is DomainRow.Source -> row.source.bookSourceUrl
                            }
                        }) { row ->
                            when (row) {
                                is DomainRow.Header -> Text(
                                    text = row.host,
                                    style = visual.type.caption,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                                is DomainRow.Source -> {
                                    val source = row.source
                                    SourceRow(
                                        source = source,
                                        selected = selection.isSelected(source),
                                        selectionMode = selectionMode,
                                        checkMsg = checkSession.sourceMessages[source.bookSourceUrl],
                                        onToggle = { on -> viewModel.enable(on, listOf(source)) },
                                        onClick = {
                                            if (!selection.toggle(source)) {
                                                context.startActivity<BookSourceEditActivity> {
                                                    putExtra("bookSourceUrl", source.bookSourceUrl)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            selection.enter(source)
                                        },
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
    }
    }

    // 主溢出菜单（对齐 1.7.3）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.v5_source_manage_title),
            actions = buildList {
                add(stringResource(R.string.v5_new_source) to { context.startActivity<BookSourceEditActivity>() })
                add(stringResource(R.string.v5_import_online) to { importOnLine() })
                add(stringResource(R.string.v5_import_local) to {
                    importDocLauncher.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                    }
                })
                add(stringResource(R.string.v5_import_qr) to { qrLauncher.launch(null) })
                add(stringResource(R.string.v5_group_manage) to {
                    GroupManageDialog().show(activity.supportFragmentManager, "groupManage")
                })
                add(stringResource(R.string.v5_sort_mode) to { sortOpen = true })
                add((if (groupByDomain) "✓ " else "") + stringResource(R.string.v5_group_by_domain) to {
                    groupByDomain = !groupByDomain
                    menuOpen = false
                })
                add(stringResource(R.string.v5_help) to { activity.showHelp("SourceMBookHelp") })
            },
            onDismiss = { menuOpen = false },
        )
    }

    // 排序（对齐 1.7.3：手动=默认 / 自动=权重）
    if (sortOpen) {
        val sortActions = listOf(
            Triple(stringResource(R.string.v5_sort_manual), BookSourceSort.Default) { sort = BookSourceSort.Default },
            Triple(stringResource(R.string.v5_sort_auto_weight), BookSourceSort.Weight) { sort = BookSourceSort.Weight },
            Triple(stringResource(R.string.v5_sort_name), BookSourceSort.Name) { sort = BookSourceSort.Name },
            Triple(stringResource(R.string.v5_sort_url), BookSourceSort.Url) { sort = BookSourceSort.Url },
            Triple(stringResource(R.string.v5_sort_update), BookSourceSort.Update) { sort = BookSourceSort.Update },
            Triple(stringResource(R.string.v5_sort_enable), BookSourceSort.Enable) { sort = BookSourceSort.Enable },
            Triple(stringResource(R.string.v5_sort_respond), BookSourceSort.Respond) { sort = BookSourceSort.Respond },
        ).map { (label, s, apply) ->
            (if (sort == s) "✓ " else "") + label to {
                apply()
                sortOpen = false
            }
        }
        AppMenuSheet(
            title = stringResource(R.string.v5_sort_mode),
            actions = sortActions + listOf(
                (if (sortAscending) "✓ " else "") + stringResource(R.string.v5_sort_asc) to {
                    sortAscending = true
                    sortOpen = false
                },
                (if (!sortAscending) "✓ " else "") + stringResource(R.string.v5_sort_desc) to {
                    sortAscending = false
                    sortOpen = false
                },
            ),
            onDismiss = { sortOpen = false },
        )
    }

    // 分组输入（组件化：AppInputDialog）
    groupSheetFor?.let { onDone ->
        var groupsText by remember { mutableStateOf("") }
        AppInputDialog(
            title = stringResource(R.string.v5_group_input_title),
            value = groupsText,
            onValueChange = { groupsText = it },
            placeholder = stringResource(R.string.v5_group_input_hint),
            confirmLabel = stringResource(R.string.v5_ok),
            cancelLabel = stringResource(R.string.v5_cancel),
            onConfirm = { text ->
                onDone(text)
                groupSheetFor = null
            },
            onDismiss = { groupSheetFor = null },
        )
    }

    // 删除确认（B 门：破坏性操作必须确认）
    if (confirmDelete) {
        AppConfirmDialog(
            title = stringResource(R.string.v5_delete),
            message = stringResource(R.string.v5_delete_confirm_message, selection.count),
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                confirmDelete = false
                viewModel.del(selectedSources)
                exitSelection()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 列表行：域名分组头 或 源条目。 */
private sealed interface DomainRow {
    data class Header(val host: String) : DomainRow
    data class Source(val source: BookSourcePart) : DomainRow
}

@Composable
private fun SourceRow(
    source: BookSourcePart,
    selected: Boolean,
    selectionMode: Boolean,
    checkMsg: String?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val density = LocalVisualSpec.current.density
    AppSelectionRow(
        title = source.bookSourceName,
        supporting = source.bookSourceGroup?.takeIf { it.isNotBlank() },
        supportingSecondary = checkMsg,
        selected = selected,
        selectionMode = selectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        trailing = {
            if (source.hasLoginUrl) {
                Text(
                    text = stringResource(R.string.v5_need_login),
                    style = LocalVisualSpec.current.type.caption,
                    color = colors.primary,
                    modifier = Modifier.padding(end = density.blockGap / 2),
                )
            }
            if (!selectionMode) {
                AppSwitch(checked = source.enabled, onCheckedChange = onToggle)
            }
        },
    )
}

@Composable
private fun SelectionAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Text(
        text = label,
        style = visual.type.label,
        color = if (danger) colors.error else colors.primary,
        modifier = Modifier
            .clip(visual.shape(ShapeKind.TAG))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
