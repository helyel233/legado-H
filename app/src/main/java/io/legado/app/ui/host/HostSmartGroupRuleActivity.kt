package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.SmartGroupCondition
import io.legado.app.help.book.SmartGroupRule
import io.legado.app.ui.main.bookshelf.smartgroup.RuleEditDialog
import io.legado.app.ui.main.bookshelf.smartgroup.SmartGroupRuleViewModel
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.ui.widget.dialog.rememberV5DialogColors
/**
 * P4-3：智能分组 V5 化（2026-10-13）。
 * 数据层复用 [SmartGroupRuleViewModel]（零拷贝）；规则编辑弹窗复用旧 RuleEditDialog
 * （AppDialogFrame 已随 V5 主题包走形状/颜色）；删除补确认框（B 门）。
 */
class HostSmartGroupRuleActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HostSmartGroupRuleActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostSmartGroupRuleScreen() }
    }
}

@Composable
private fun HostSmartGroupRuleScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val viewModel = remember { ViewModelProvider(activity)[SmartGroupRuleViewModel::class.java] }

    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val autoRun by viewModel.autoRun.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()

    var deleteTarget by remember { mutableStateOf<SmartGroupRule?>(null) }
    val listState = rememberLazyListState()
    val editDialogStyle = rememberV5DialogColors()

    Column(Modifier.fillMaxSize()) {
        // 顶部：自动执行开关 + 两个动作
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.smart_group_auto_run),
                style = visual.type.body,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            AppSwitch(checked = autoRun, onCheckedChange = { viewModel.setAutoRun(it) })
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            AppFilledButton(
                text = stringResource(R.string.smart_group_add_rule),
                modifier = Modifier.weight(1f),
                onClick = { viewModel.startEdit(null) },
            )
            AppGhostButton(
                text = stringResource(
                    if (running) R.string.smart_group_running else R.string.smart_group_run_now
                ),
                enabled = !running,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.applyRules() },
            )
        }
        Spacer(Modifier.height(density.blockGap / 2))

        if (rules.isEmpty()) {
            AppEmpty(
                title = stringResource(R.string.smart_group),
                supporting = stringResource(R.string.smart_group_empty),
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
                    items(rules.size, key = { rules[it].id }) { index ->
                        val rule = rules[index]
                        val groupName = groups.firstOrNull { it.groupId == rule.groupId }?.groupName
                            ?: rule.groupId.toString()
                        SmartGroupRuleRow(
                            rule = rule,
                            groupName = groupName,
                            onToggle = { viewModel.toggleEnabled(rule) },
                            onEdit = { viewModel.startEdit(rule) },
                            onDelete = { deleteTarget = rule },
                        )
                    }
                }
            }
        }
    }

    // 规则编辑（复用旧弹窗：AppDialogFrame 已 V5 化）
    editing?.let { draft ->
        RuleEditDialog(
            draft = draft,
            groups = groups,
            style = editDialogStyle,
            onConfirm = { viewModel.save(it) },
            onDismiss = { viewModel.cancelEdit() },
        )
    }

    // 删除确认（B 门：旧版无确认）
    deleteTarget?.let { target ->
        AppConfirmDialog(
            title = stringResource(R.string.smart_group_delete),
            message = target.ruleName.ifBlank { target.groupId.toString() },
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                viewModel.delete(target)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun SmartGroupRuleRow(
    rule: SmartGroupRule,
    groupName: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val conditions = rule.conditions
    AppSelectionRow(
        title = rule.ruleName.ifBlank { groupName },
        supporting = buildString {
            append(groupName)
            if (conditions.isNotEmpty()) {
                append(" · ")
                append(conditions.joinToString("；") { conditionLabel(context, it) })
            }
        },
        selected = false,
        selectionMode = false,
        onClick = onEdit,
        onLongClick = onEdit,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(checked = rule.enabled, onCheckedChange = { onToggle() })
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.v5_more),
                    glyph = "⋯",
                ) { onDelete() }
            }
        },
    )
}

private fun conditionLabel(context: android.content.Context, condition: SmartGroupCondition): String {
    return when (condition.type) {
        io.legado.app.help.book.SmartConditionType.TAG ->
            context.getString(R.string.smart_group_condition_tag, condition.value)
        io.legado.app.help.book.SmartConditionType.SOURCE ->
            context.getString(R.string.smart_group_condition_source, condition.value)
        io.legado.app.help.book.SmartConditionType.NAME ->
            context.getString(R.string.smart_group_condition_name, condition.value)
        io.legado.app.help.book.SmartConditionType.RECENT ->
            context.getString(R.string.smart_group_condition_recent, condition.days)
        else -> condition.value
    }
}
