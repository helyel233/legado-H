package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleEditComposeDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleViewModel
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppReorderableItem
import io.legado.app.uikit.v5.components.AppSelectionAction
import io.legado.app.uikit.v5.components.AppSelectionBar
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.rememberAppReorderableListState
import io.legado.app.uikit.v5.components.rememberAppSelectionState
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.showHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/**
 * P4-3：TXT 目录规则管理 V5 化（2026-10-12）。
 * 数据层复用 [TxtTocRuleViewModel]；功能对齐旧 TxtTocRuleActivity（迁移门 A）。
 */
class HostTxtTocRuleManageActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostTxtTocRuleManageActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostTxtTocRuleManageScreen() }
    }
}

@Composable
private fun HostTxtTocRuleManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember { ViewModelProvider(activity)[TxtTocRuleViewModel::class.java] }

    val selection = rememberAppSelectionState<TxtTocRule>(idOf = { it.id.toString() })
    var menuOpen by remember { mutableStateOf(false) }
    var importUrlOpen by remember { mutableStateOf(false) }
    var importUrlText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TxtTocRule?>(null) }
    var editTarget by remember { mutableStateOf<Long?>(null) }
    var longPressHintShown by rememberSaveable { mutableStateOf(false) }

    val importDocLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportTxtTocRuleDialog(uri.toString()).show(activity.supportFragmentManager, "importTxtTocRule")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.let { AppToast.show(it.toString()) }
    }
    val qrLauncher = rememberLauncherForActivityResult(QrCodeResult()) {
        it ?: return@rememberLauncherForActivityResult
        ImportTxtTocRuleDialog(it).show(activity.supportFragmentManager, "importTxtTocRule")
    }

    val rules by remember {
        appDb.txtTocRuleDao.observeAll()
            .catch { AppLog.put("TXT目录规则管理-V5数据获取失败", it) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val selectionMode = selection.selectionMode
    val selectedRules = selection.selectedFrom(rules)

    var orderedRules by remember(rules) { mutableStateOf(rules) }
    val listState = rememberLazyListState()
    val reorderEnabled = !selectionMode
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

    val selectionBar: @Composable () -> Unit = {
        if (selectionMode) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_select_all)) { selection.selectAll(rules) },
                    AppSelectionAction(stringResource(R.string.v5_select_invert)) { selection.invert(rules) },
                    AppSelectionAction(stringResource(R.string.v5_enable)) { viewModel.enableSelection(*selectedRules.toTypedArray()) },
                    AppSelectionAction(stringResource(R.string.v5_disable)) { viewModel.disableSelection(*selectedRules.toTypedArray()) },
                    AppSelectionAction(stringResource(R.string.v5_delete), danger = true) { confirmDelete = true },
                ),
                overflowActions = listOf(
                    AppSelectionAction(stringResource(R.string.v5_export)) {
                        exportLauncher.launch {
                            mode = HandleFileContract.EXPORT
                            fileData = HandleFileContract.FileData(
                                "exportTxtTocRule.json",
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
        title = stringResource(R.string.txt_toc_rule),
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
            if (rules.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.txt_toc_rule),
                    supporting = stringResource(R.string.import_default_rule),
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
                                TxtTocRuleRow(
                                    rule = rule,
                                    selected = selection.isSelected(rule),
                                    selectionMode = selectionMode,
                                    dragHandle = dragHandle,
                                    onToggle = { on -> viewModel.update(rule.copy(enable = on)) },
                                    onClick = {
                                        if (!selection.toggle(rule)) editTarget = rule.id
                                    },
                                    onLongClick = { selection.enter(rule) },
                                    onDelete = { deleteTarget = rule },
                                )
                            }
                        }
                    } else {
                        items(rules, key = { it.id }) { rule ->
                            TxtTocRuleRow(
                                rule = rule,
                                selected = selection.isSelected(rule),
                                selectionMode = selectionMode,
                                dragHandle = null,
                                onToggle = { on -> viewModel.update(rule.copy(enable = on)) },
                                onClick = {
                                    if (!selection.toggle(rule)) editTarget = rule.id
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

    // ⋮ 菜单（对齐旧 txt_toc_rule 菜单）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.txt_toc_rule),
            actions = listOf(
                stringResource(R.string.add) to { editTarget = -1L },
                stringResource(R.string.v5_import_local) to {
                    importDocLauncher.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                    }
                },
                stringResource(R.string.v5_import_online) to {
                    importUrlText = ""
                    importUrlOpen = true
                },
                stringResource(R.string.v5_import_qr) to { qrLauncher.launch(null) },
                stringResource(R.string.import_default_rule) to {
                    viewModel.importDefault()
                    AppToast.show(activity.getString(R.string.success))
                },
                stringResource(R.string.v5_help) to { activity.showHelp("txtTocRuleHelp") },
            ),
            onDismiss = { menuOpen = false },
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
                if (text.isNotBlank()) {
                    ImportTxtTocRuleDialog(text)
                        .show(activity.supportFragmentManager, "importTxtTocRule")
                }
            },
            onDismiss = { importUrlOpen = false },
        )
    }

    // 新建（id=-1）/编辑（复用旧 Compose 弹窗）
    editTarget?.let { id ->
        androidx.compose.runtime.LaunchedEffect(id) {
            TxtTocRuleEditComposeDialog.create(id.takeIf { it >= 0 } ?: -1)
                .show(activity.supportFragmentManager, "txtTocRuleEdit")
            editTarget = null
        }
    }

    // 删除确认（B 门）
    if (confirmDelete) {
        AppConfirmDialog(
            title = stringResource(R.string.v5_delete),
            message = stringResource(R.string.sure_del),
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                confirmDelete = false
                viewModel.del(*selectedRules.toTypedArray())
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
                viewModel.del(target)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun TxtTocRuleRow(
    rule: TxtTocRule,
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
        supporting = rule.rule.takeIf { it.isNotBlank() },
        selected = selected,
        selectionMode = selectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = dragHandle?.let { handle -> { Box(modifier = handle) } },
        trailing = {
            if (!selectionMode) {
                AppSwitch(checked = rule.enable, onCheckedChange = onToggle)
            } else {
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.v5_delete),
                    glyph = "⋯",
                ) { onDelete() }
            }
        },
    )
}
