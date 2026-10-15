package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.IntentData
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceViewModel
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
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
import io.legado.app.utils.ACache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate

/**
 * P4-3：订阅源管理 V5 化（RSS 源管理迁移，2026-10-12 启动）。
 *
 * 数据层全复用：[RssSourceViewModel]（置顶/置底/删除/启停/导出/分组批量）。
 * 交互层走新组件：AppSelectionState + AppSelectionRow + AppSelectionBar +
 * AppFilterChipRow + AppReorderableItem（拖拽排序，非搜索态可用）。
 * 功能对齐旧 RssSourceActivity（迁移门 A）。
 */
class HostRssSourceManageActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostRssSourceManageActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostRssSourceManageScreen() }
    }
}

@Composable
private fun HostRssSourceManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember {
        ViewModelProvider(activity)[RssSourceViewModel::class.java]
    }

    var searchKey by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf(RssStatusFilter.ALL) }
    val selection = rememberAppSelectionState<RssSource>(idOf = { it.sourceUrl })
    var menuOpen by remember { mutableStateOf(false) }
    var groupManageOpen by remember { mutableStateOf(false) }
    var groupSheetFor by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var importUrlOpen by remember { mutableStateOf(false) }
    var importUrlText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var longPressHintShown by rememberSaveable { mutableStateOf(false) }

    val selectionMode = selection.selectionMode

    val importDocLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportRssSourceDialog(uri.toString()).show(activity.supportFragmentManager, "importRssSource")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val url = uri.toString()
            AppToast.show(url)
            context.sendToClip(url)
            DirectLinkUpload.getSummary().takeIf { it.isNotBlank() }?.let { AppToast.show(it) }
        }
    }
    val qrLauncher = rememberLauncherForActivityResult(io.legado.app.ui.qrcode.QrCodeResult()) {
        it ?: return@rememberLauncherForActivityResult
        ImportRssSourceDialog(it).show(activity.supportFragmentManager, "importRssSource")
    }

    // 数据流（与旧页同：状态/分组/搜索共用一个 searchKey 通道）
    val sources by remember(searchKey) {
        val key = searchKey.trim()
        when {
            key.startsWith("group:") -> appDb.rssSourceDao.flowGroupSearch(key.removePrefix("group:"))
            key.isNotBlank() -> appDb.rssSourceDao.flowSearch(key)
            else -> when (statusFilter) {
                RssStatusFilter.ALL -> appDb.rssSourceDao.flowAll()
                RssStatusFilter.ENABLED -> appDb.rssSourceDao.flowEnabled()
                RssStatusFilter.DISABLED -> appDb.rssSourceDao.flowDisabled()
                RssStatusFilter.LOGIN -> appDb.rssSourceDao.flowLogin()
                RssStatusFilter.NO_GROUP -> appDb.rssSourceDao.flowNoGroup()
            }
        }.catch { AppLog.put("订阅源管理-V5数据更新出错", it) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val groups by remember { appDb.rssSourceDao.flowGroups() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val selectedSources = selection.selectedFrom(sources)

    // 拖拽排序的本地即时列表（新数据到来时重置）
    var orderedSources by remember(sources) { mutableStateOf(sources) }
    val listState = rememberLazyListState()
    val reorderEnabled = searchKey.isBlank() && statusFilter == RssStatusFilter.ALL && !selectionMode
    val reorderState = rememberAppReorderableListState(
        lazyListState = listState,
        onMove = { from, to ->
            orderedSources = orderedSources.toMutableList().apply {
                add(to, removeAt(from))
            }
        },
    )

    // 长按多选提示（B 门）
    androidx.compose.runtime.LaunchedEffect(selectionMode) {
        if (selectionMode && !longPressHintShown) {
            longPressHintShown = true
            AppToast.show(context.getString(R.string.v5_hint_long_press))
        }
    }

    val selectionBar: @Composable () -> Unit = {
        if (selectionMode) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_select_all)) { selection.selectAll(sources) },
                    AppSelectionAction(stringResource(R.string.v5_select_invert)) { selection.invert(sources) },
                    AppSelectionAction(stringResource(R.string.v5_enable)) { viewModel.enableSelection(selectedSources) },
                    AppSelectionAction(stringResource(R.string.v5_disable)) { viewModel.disableSelection(selectedSources) },
                    AppSelectionAction(stringResource(R.string.v5_delete), danger = true) { confirmDelete = true },
                ),
                overflowActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_add_group)) {
                        groupSheetFor = { text ->
                            viewModel.selectionAddToGroups(selectedSources, text)
                            selection.exit()
                        }
                    },
                    AppSelectionAction(stringResource(R.string.v5_remove_group)) {
                        groupSheetFor = { text ->
                            viewModel.selectionRemoveFromGroups(selectedSources, text)
                            selection.exit()
                        }
                    },
                    AppSelectionAction(stringResource(R.string.v5_move_top)) {
                        viewModel.topSource(*selectedSources.toTypedArray())
                        selection.exit()
                    },
                    AppSelectionAction(stringResource(R.string.v5_move_bottom)) {
                        viewModel.bottomSource(*selectedSources.toTypedArray())
                        selection.exit()
                    },
                    AppSelectionAction(stringResource(R.string.v5_export)) {
                        viewModel.saveToFile(selectedSources) { file, name ->
                            // 与旧页一致：先落文件，再 SAF 选目标
                            exportLauncher.launch {
                                mode = HandleFileContract.EXPORT
                                fileData = HandleFileContract.FileData(name, file, "application/json")
                            }
                        }
                    },
                    AppSelectionAction(stringResource(R.string.v5_share)) {
                        viewModel.saveToFile(selectedSources) { file, _ -> context.share(file, "application/json") }
                    },
                    AppSelectionAction(stringResource(R.string.v5_check_interval)) {
                        val positions = sources.mapIndexedNotNull { index, s ->
                            if (s.sourceUrl in selection.selected) index else null
                        }
                        if (positions.isNotEmpty()) {
                            val ids = selection.selected.toMutableSet()
                            for (i in positions.min()..positions.max()) ids.add(sources[i].sourceUrl)
                            selection.restoreSelected(ids)
                        }
                    },
                ),
                overflowTitle = stringResource(R.string.v5_batch_actions_title, selection.count),
                moreLabel = stringResource(R.string.v5_more),
            )
        }
    }

    HostV5Page(
        title = stringResource(R.string.rss_source_manage),
        subtitle = if (!selectionMode) stringResource(R.string.v5_source_count, sources.size) else null,
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
                placeholder = stringResource(R.string.search_rss_source),
            )
            Spacer(Modifier.height(density.blockGap))

            AppFilterChipRow(
                label = stringResource(R.string.v5_filter_status),
                chips = RssStatusFilter.entries.map { f ->
                    AppFilterChip(stringResource(f.labelRes), statusFilter == f)
                },
                onChipClick = { index ->
                    statusFilter = RssStatusFilter.entries[index]
                    searchKey = ""
                },
            )

            if (groups.isNotEmpty()) {
                var groupFilterOpen by remember { mutableStateOf(false) }
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
                    onOverflowClick = if (manyGroups) { { groupFilterOpen = true } } else null,
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

            if (sources.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.v5_rss_no_sources_title),
                    supporting = stringResource(R.string.v5_rss_manage),
                )
            } else if (reorderEnabled) {
                AppFastScroller(
                    listState = listState,
                    topContentPadding = density.blockGap / 2,
                    bottomContentPadding = density.screenPadding,
                    endContentPadding = density.screenPadding,
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = density.screenPadding,
                        end = density.screenPadding,
                        top = density.blockGap / 2,
                        bottom = density.screenPadding,
                    ),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    items(orderedSources, key = { it.sourceUrl }) { source ->
                        AppReorderableItem(
                            reorderState,
                            key = source.sourceUrl,
                            onDragStopped = { viewModel.upOrder(orderedSources) },
                        ) { _, dragHandle ->
                            RssSourceRow(
                                source = source,
                                selected = selection.isSelected(source),
                                selectionMode = selectionMode,
                                dragHandle = dragHandle,
                                onToggle = { on -> viewModel.update(source.copy(enabled = on)) },
                                onClick = {
                                    if (!selection.toggle(source)) {
                                        context.startActivity<RssSourceEditActivity> {
                                            putExtra("sourceUrl", source.sourceUrl)
                                        }
                                    }
                                },
                                onLongClick = { selection.enter(source) },
                            )
                        }
                    }
                }
                }
            } else {
                val fsState349 = rememberLazyListState()
                AppFastScroller(
                    listState = fsState349,
                    topContentPadding = Dp.Hairline,
                    bottomContentPadding = Dp.Hairline,
                    endContentPadding = Dp.Hairline,
                ) {
                LazyColumn(
                    state = fsState349,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = density.screenPadding,
                        end = density.screenPadding,
                        top = density.blockGap / 2,
                        bottom = density.screenPadding,
                    ),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    items(sources, key = { it.sourceUrl }) { source ->
                        RssSourceRow(
                            source = source,
                            selected = selection.isSelected(source),
                            selectionMode = selectionMode,
                            dragHandle = null,
                            onToggle = { on -> viewModel.update(source.copy(enabled = on)) },
                            onClick = {
                                if (!selection.toggle(source)) {
                                    context.startActivity<RssSourceEditActivity> {
                                        putExtra("sourceUrl", source.sourceUrl)
                                    }
                                }
                            },
                            onLongClick = { selection.enter(source) },
                        )
                    }
                }
                }
            }
        }
    }

    // 主溢出菜单（对齐旧 rss_source 菜单）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.rss_source_manage),
            actions = listOf(
                stringResource(R.string.add) to {
                    context.startActivity<RssSourceEditActivity>()
                },
                stringResource(R.string.v5_import_online) to {
                    // 在线导入（缓存历史与旧页同 key）
                    importUrlText = ""
                    importUrlOpen = true
                },
                stringResource(R.string.v5_import_local) to {
                    importDocLauncher.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                    }
                },
                stringResource(R.string.v5_import_qr) to { qrLauncher.launch(null) },
                stringResource(R.string.v5_group_manage) to { groupManageOpen = true },
                stringResource(R.string.v5_help) to { activity.showHelp("SourceMRssHelp") },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    // 分组输入（AppInputDialog）
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

    // 分组管理（V5 组件化，替代旧 GroupManageDialog DialogFragment）
    if (groupManageOpen) {
        val groups by produceState<List<String>>(emptyList()) {
            appDb.rssSourceDao.flowGroups().collect { value = it }
        }
        GroupManageDialogContent(
            groups = groups,
            onAddGroup = { viewModel.addGroup(it) },
            onRenameGroup = { old, new -> viewModel.upGroup(old, new) },
            onDeleteGroup = { viewModel.delGroup(it) },
            onDismiss = { groupManageOpen = false },
        )
    }

    // 在线导入 URL 输入（AppInputDialog）
    if (importUrlOpen) {
        AppInputDialog(
            title = stringResource(R.string.import_on_line),
            value = importUrlText,
            onValueChange = { importUrlText = it },
            placeholder = "url",
            confirmLabel = stringResource(R.string.v5_ok),
            cancelLabel = stringResource(R.string.v5_cancel),
            onConfirm = { text ->
                importUrlOpen = false
                // 缓存历史与旧页同 key
                val aCache = ACache.get(cacheDir = false)
                val cacheUrls = aCache.getAsString("rssSourceRecordKey")
                    ?.splitNotBlank(",")?.toMutableList() ?: mutableListOf()
                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                    cacheUrls.add(0, text)
                    aCache.put("rssSourceRecordKey", cacheUrls.joinToString(","))
                }
                ImportRssSourceDialog(text)
                    .show(activity.supportFragmentManager, "importRssSource")
            },
            onDismiss = { importUrlOpen = false },
        )
    }

    // 删除确认（B 门）
    if (confirmDelete) {
        AppConfirmDialog(
            title = stringResource(R.string.v5_delete),
            message = stringResource(R.string.v5_delete_confirm_message, selection.count),
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                confirmDelete = false
                viewModel.del(*selectedSources.toTypedArray())
                selection.exit()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private enum class RssStatusFilter(val labelRes: Int) {
    ALL(R.string.v5_all), ENABLED(R.string.v5_enabled), DISABLED(R.string.v5_disabled),
    LOGIN(R.string.v5_need_login), NO_GROUP(R.string.v5_no_group),
}

@Composable
private fun RssSourceRow(
    source: RssSource,
    selected: Boolean,
    selectionMode: Boolean,
    dragHandle: Modifier?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val density = LocalVisualSpec.current.density
    AppSelectionRow(
        title = source.sourceName,
        supporting = source.sourceGroup?.takeIf { it.isNotBlank() },
        selected = selected,
        selectionMode = selectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = dragHandle?.let { handle -> { androidx.compose.foundation.layout.Box(modifier = handle) } },
        trailing = {
            if (!selectionMode) {
                AppSwitch(checked = source.enabled, onCheckedChange = onToggle)
            } else {
                Text(
                    text = if (source.loginUrl.isNullOrBlank()) "" else stringResource(R.string.v5_need_login),
                    style = LocalVisualSpec.current.type.caption,
                    color = colors.primary,
                    modifier = Modifier.padding(end = density.blockGap / 2),
                )
            }
        },
    )
}
