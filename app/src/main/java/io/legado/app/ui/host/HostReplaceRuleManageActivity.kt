package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.replace.GroupManageDialog
import io.legado.app.ui.replace.ReplaceRuleViewModel
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppFilterChipRow
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
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.ACache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/**
 * P4-3：替换规则管理 V5 化（2026-10-12，管理页零件第二次复用验证）。
 *
 * 数据层全复用 [ReplaceRuleViewModel]；功能对齐旧 ReplaceRuleActivity（迁移门 A）：
 * 搜索/状态+分组筛选/长按多选/批量启停/置顶置底/导出/删除确认/拖拽排序/导入三项。
 */
class HostReplaceRuleManageActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostReplaceRuleManageActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostReplaceRuleManageScreen() }
    }
}

private enum class ReplaceStatusFilter(val labelRes: Int) {
    ALL(R.string.v5_all), ENABLED(R.string.v5_enabled),
    DISABLED(R.string.v5_disabled), NO_GROUP(R.string.v5_no_group),
}

@Composable
private fun HostReplaceRuleManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember { ViewModelProvider(activity)[ReplaceRuleViewModel::class.java] }

    var searchKey by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf(ReplaceStatusFilter.ALL) }
    val selection = rememberAppSelectionState<ReplaceRule>(idOf = { it.id.toString() })
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ReplaceRule?>(null) }
    var longPressHintShown by rememberSaveable { mutableStateOf(false) }

    val importDocLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportReplaceRuleDialog(uri.toString()).show(activity.supportFragmentManager, "importReplaceRule")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { AppToast.show(it.toString()) }
    }
    val qrLauncher = rememberLauncherForActivityResult(QrCodeResult()) {
        it ?: return@rememberLauncherForActivityResult
        ImportReplaceRuleDialog(it).show(activity.supportFragmentManager, "importReplaceRule")
    }

    val rules by remember(searchKey) {
        val key = searchKey.trim()
        when {
            key.startsWith("group:") -> appDb.replaceRuleDao.flowGroupSearch("%${key.removePrefix("group:")}%")
            key.isNotBlank() -> appDb.replaceRuleDao.flowSearch("%$key%")
            else -> when (statusFilter) {
                ReplaceStatusFilter.ALL -> appDb.replaceRuleDao.flowAll()
                ReplaceStatusFilter.ENABLED -> appDb.replaceRuleDao.flowEnabled()
                ReplaceStatusFilter.DISABLED -> appDb.replaceRuleDao.flowDisabled()
                ReplaceStatusFilter.NO_GROUP -> appDb.replaceRuleDao.flowNoGroup()
            }
        }.catch { AppLog.put("替换规则管理-V5数据更新出错", it) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsState(initial = emptyList())

    val groups by remember { appDb.replaceRuleDao.flowGroups() }
        .collectAsState(initial = emptyList())

    val selectionMode = selection.selectionMode
    val selectedRules = selection.selectedFrom(rules)

    var orderedRules by remember(rules) { mutableStateOf(rules) }
    val listState = rememberLazyListState()
    val reorderEnabled = searchKey.isBlank() && statusFilter == ReplaceStatusFilter.ALL && !selectionMode
    val reorderState = rememberAppReorderableListState(
        lazyListState = listState,
        onMove = { from, to ->
            orderedRules = orderedRules.toMutableList().apply { add(to, removeAt(from)) }
        },
    )

    androidx.compose.runtime.LaunchedEffect(selectionMode) {
        if (selectionMode && !longPressHintShown) {
            longPressHintShown = true
            AppToast.show(context.getString(R.string.v5_hint_long_press))
        }
    }

    fun editRule(rule: ReplaceRule?) {
        activity.startActivity(ReplaceEditActivity.startIntent(activity, rule?.id ?: -1))
    }

    val selectionBar: @Composable () -> Unit = {
        if (selectionMode) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_select_all)) { selection.selectAll(rules) },
                    AppSelectionAction(stringResource(R.string.v5_select_invert)) { selection.invert(rules) },
                    AppSelectionAction(stringResource(R.string.v5_enable)) { viewModel.enableSelection(selectedRules) },
                    AppSelectionAction(stringResource(R.string.v5_disable)) { viewModel.disableSelection(selectedRules) },
                    AppSelectionAction(stringResource(R.string.v5_delete), danger = true) { confirmDelete = true },
                ),
                overflowActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_move_top)) {
                        viewModel.topSelect(selectedRules)
                        selection.exit()
                    },
                    AppSelectionAction(stringResource(R.string.v5_move_bottom)) {
                        viewModel.bottomSelect(selectedRules)
                        selection.exit()
                    },
                    AppSelectionAction(stringResource(R.string.v5_export)) {
                        exportLauncher.launch {
                            mode = HandleFileContract.EXPORT
                            fileData = HandleFileContract.FileData(
                                "exportReplaceRule.json",
                                Gson().toJson(selectedRules).toByteArray(),
                                "application/json",
                            )
                        }
                    },
                ),
                overflowTitle = stringResource(R.string.v5_batch_actions_title, selection.count),
                moreLabel = stringResource(R.string.v5_more),
            )
        }
    }

    HostV5Page(
        title = stringResource(R.string.replace_purify),
        subtitle = if (!selectionMode) stringResource(R.string.v5_source_count, rules.size) else null,
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
                placeholder = stringResource(R.string.replace_purify_search),
            )
            Spacer(Modifier.height(density.blockGap))

            AppFilterChipRow(
                label = stringResource(R.string.v5_filter_status),
                chips = ReplaceStatusFilter.entries.map { f ->
                    AppFilterChip(stringResource(f.labelRes), statusFilter == f)
                },
                onChipClick = { index ->
                    statusFilter = ReplaceStatusFilter.entries[index]
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

            if (rules.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.replace_purify),
                    supporting = stringResource(R.string.replace_purify_desc),
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
                        items(orderedRules, key = { it.id }) { rule ->
                            AppReorderableItem(
                                reorderState,
                                key = rule.id,
                                onDragStopped = { viewModel.upOrder(orderedRules) },
                            ) { _, dragHandle ->
                                ReplaceRuleRow(
                                    rule = rule,
                                    selected = selection.isSelected(rule),
                                    selectionMode = selectionMode,
                                    dragHandle = dragHandle,
                                    onToggle = { on -> viewModel.update(rule.copy(isEnabled = on)) },
                                    onClick = {
                                        if (!selection.toggle(rule)) editRule(rule)
                                    },
                                    onLongClick = { selection.enter(rule) },
                                    onDelete = { deleteTarget = rule },
                                )
                            }
                        }
                    } else {
                        items(rules, key = { it.id }) { rule ->
                            ReplaceRuleRow(
                                rule = rule,
                                selected = selection.isSelected(rule),
                                selectionMode = selectionMode,
                                dragHandle = null,
                                onToggle = { on -> viewModel.update(rule.copy(isEnabled = on)) },
                                onClick = {
                                    if (!selection.toggle(rule)) editRule(rule)
                                },
                                onLongClick = { selection.enter(rule) },
                                onDelete = { deleteTarget = rule },
                            )
                        }
                    }
                }
                }
            }
        }
    }

    // ⋮ 菜单（对齐旧 replace_rule 菜单 + 规则订阅）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.replace_purify),
            actions = listOf(
                stringResource(R.string.add_replace_rule) to { editRule(null) },
                stringResource(R.string.v5_import_local) to {
                    importDocLauncher.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                    }
                },
                stringResource(R.string.v5_import_online) to {
                    val aCache = ACache.get(cacheDir = false)
                    val cacheUrls = aCache.getAsString("replaceRuleRecordKey")
                        ?.splitNotBlank(",")?.toMutableList() ?: mutableListOf()
                    activity.showComposeTextInputDialog(
                        title = activity.getString(R.string.import_on_line),
                        hint = "url",
                        onPositive = { text ->
                            if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                                cacheUrls.add(0, text)
                                aCache.put("replaceRuleRecordKey", cacheUrls.joinToString(","))
                            }
                            ImportReplaceRuleDialog(text)
                                .show(activity.supportFragmentManager, "importReplaceRule")
                        },
                    )
                },
                stringResource(R.string.v5_import_qr) to { qrLauncher.launch(null) },
                stringResource(R.string.v5_group_manage) to {
                    GroupManageDialog().show(activity.supportFragmentManager, "groupManage")
                },
                stringResource(R.string.rule_subscription) to {
                    context.startActivity(
                        android.content.Intent(
                            context,
                            io.legado.app.ui.rss.subscription.RuleSubActivity::class.java,
                        ),
                    )
                },
                stringResource(R.string.v5_help) to { activity.showHelp("replaceRuleHelp") },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    // 删除确认（B 门：批量 + 行级都走这里）
    if (confirmDelete) {
        AppConfirmDialog(
            title = stringResource(R.string.v5_delete),
            message = stringResource(R.string.v5_delete_confirm_message, selection.count),
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                confirmDelete = false
                viewModel.delSelection(selectedRules)
                selection.exit()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    deleteTarget?.let { target ->
        AppConfirmDialog(
            title = stringResource(R.string.v5_delete),
            message = stringResource(R.string.sure_del) + "\n" + target.name,
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                deleteTarget = null
                viewModel.delete(target)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ReplaceRuleRow(
    rule: ReplaceRule,
    selected: Boolean,
    selectionMode: Boolean,
    dragHandle: Modifier?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    AppSelectionRow(
        title = rule.name,
        supporting = rule.group?.takeIf { it.isNotBlank() },
        selected = selected,
        selectionMode = selectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = dragHandle?.let { handle -> { androidx.compose.foundation.layout.Box(modifier = handle) } },
        trailing = {
            if (!selectionMode) {
                AppSwitch(checked = rule.isEnabled, onCheckedChange = onToggle)
            }
        },
    )
}
