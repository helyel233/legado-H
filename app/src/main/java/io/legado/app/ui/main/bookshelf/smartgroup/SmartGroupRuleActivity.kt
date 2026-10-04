package io.legado.app.ui.main.bookshelf.smartgroup

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivitySmartGroupRuleBinding
import io.legado.app.help.book.SmartConditionType
import io.legado.app.help.book.SmartGroupCondition
import io.legado.app.help.book.SmartGroupEngine
import io.legado.app.help.book.SmartGroupRule
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 智能书架分组：规则列表 + 编辑 + 手动执行。
 */
class SmartGroupRuleActivity :
    VMBaseActivity<ActivitySmartGroupRuleBinding, SmartGroupRuleViewModel>() {

    override val binding by viewBinding(ActivitySmartGroupRuleBinding::inflate)
    override val viewModel by viewModels<SmartGroupRuleViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setNavigationOnClickListener {
            finish()
        }
        binding.composeView.setContent {
            LegadoComposeTheme {
                SmartGroupScreen(viewModel)
            }
        }
    }
}

@Composable
private fun SmartGroupScreen(viewModel: SmartGroupRuleViewModel) {
    val style = rememberAppDialogStyle()
    val rules by viewModel.rules.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val autoRun by viewModel.autoRun.collectAsState()
    val running by viewModel.running.collectAsState()
    val editing by viewModel.editing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.surface)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.smart_group_auto_run),
                color = style.primaryText,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = autoRun,
                onCheckedChange = { viewModel.setAutoRun(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = style.accent)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton(
                text = stringResource(R.string.smart_group_add_rule),
                style = style,
                modifier = Modifier.weight(1f)
            ) {
                viewModel.startEdit(null)
            }
            ActionButton(
                text = stringResource(
                    if (running) R.string.smart_group_running else R.string.smart_group_run_now
                ),
                style = style,
                modifier = Modifier.weight(1f)
            ) {
                viewModel.applyRules()
            }
        }

        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.smart_group_empty),
                color = style.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules.size) { index ->
                    val rule = rules[index]
                    RuleCard(
                        rule = rule,
                        groupName = groups.firstOrNull { it.groupId == rule.groupId }?.groupName
                            ?: rule.groupId.toString(),
                        style = style,
                        onToggle = { viewModel.toggleEnabled(rule) },
                        onEdit = { viewModel.startEdit(rule) },
                        onDelete = { viewModel.delete(rule) }
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    editing?.let { draft ->
        RuleEditDialog(
            draft = draft,
            groups = groups,
            style = style,
            onConfirm = { viewModel.save(it) },
            onDismiss = { viewModel.cancelEdit() }
        )
    }
}

@Composable
private fun RuleCard(
    rule: SmartGroupRule,
    groupName: String,
    style: AppDialogStyle,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.fieldSurface, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.ruleName.ifBlank { groupName },
                    color = style.primaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = groupName,
                    color = style.secondaryText,
                    fontSize = 12.sp
                )
            }
            Text(
                text = stringResource(if (rule.enabled) R.string.smart_group_enabled else R.string.smart_group_disabled),
                color = if (rule.enabled) style.accent else style.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onToggle() }.padding(6.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        rule.conditions.forEach { condition ->
            Text(
                text = conditionLabel(condition),
                color = style.secondaryText,
                fontSize = 12.sp
            )
        }
        Row(modifier = Modifier.align(Alignment.End)) {
            Text(
                text = stringResource(R.string.smart_group_edit),
                color = style.accent,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onEdit() }.padding(6.dp)
            )
            Text(
                text = stringResource(R.string.smart_group_delete),
                color = style.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onDelete() }.padding(6.dp)
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = style.accent,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .background(style.fieldSurface, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun conditionLabel(condition: SmartGroupCondition): String {
    return when (condition.type) {
        SmartConditionType.TAG -> stringResource(R.string.smart_group_condition_tag, condition.value)
        SmartConditionType.SOURCE ->
            stringResource(R.string.smart_group_condition_source, condition.value)
        SmartConditionType.NAME ->
            stringResource(R.string.smart_group_condition_name, condition.value)
        SmartConditionType.RECENT ->
            stringResource(R.string.smart_group_condition_recent, condition.days)
        else -> condition.value
    }
}
