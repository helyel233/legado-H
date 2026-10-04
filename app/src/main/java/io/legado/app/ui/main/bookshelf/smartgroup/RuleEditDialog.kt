package io.legado.app.ui.main.bookshelf.smartgroup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.SmartConditionType
import io.legado.app.help.book.SmartGroupCondition
import io.legado.app.help.book.SmartGroupRule
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 智能分组规则编辑弹窗。
 */
@Composable
fun RuleEditDialog(
    draft: SmartGroupRule,
    groups: List<BookGroup>,
    style: AppDialogStyle,
    onConfirm: (SmartGroupRule) -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(
        if (draft.ruleName.isBlank()) R.string.smart_group_add_rule
        else R.string.smart_group_edit_rule
    )
    var ruleName by remember(draft.id) { mutableStateOf(draft.ruleName) }
    var groupId by remember(draft.id) { mutableStateOf(draft.groupId) }
    var priority by remember(draft.id) { mutableStateOf(draft.priority.toString()) }
    var conditions by remember(draft.id) { mutableStateOf(draft.conditions) }
    val palette = style.toMiuixPalette()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AppDialogFrame(
            title = title,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StyleTextField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = stringResource(R.string.smart_group_rule_name),
                        style = style
                    )
                    GroupSelector(
                        groups = groups,
                        selectedId = groupId,
                        style = style,
                        onSelect = { groupId = it }
                    )
                    StyleTextField(
                        value = priority,
                        onValueChange = { priority = it.filter(Char::isDigit).take(4) },
                        label = stringResource(R.string.smart_group_priority),
                        style = style
                    )
                    Text(
                        text = stringResource(R.string.smart_group_conditions),
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    conditions.forEachIndexed { index, condition ->
                        ConditionRow(
                            condition = condition,
                            style = style,
                            onUpdate = { updated ->
                                conditions = conditions.mapIndexed { i, c ->
                                    if (i == index) updated else c
                                }
                            },
                            onRemove = {
                                conditions = conditions.filterIndexed { i, _ -> i != index }
                            }
                        )
                    }
                    Text(
                        text = stringResource(R.string.smart_group_add_condition),
                        color = style.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                conditions = conditions + SmartGroupCondition()
                            }
                            .padding(4.dp)
                    )
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = onDismiss,
                    cornerRadius = style.actionRadius
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    primary = true,
                    cornerRadius = style.actionRadius,
                    onClick = {
                        val gid = groupId
                        if (gid <= 0 || conditions.isEmpty()) return@LegadoMiuixActionButton
                        onConfirm(
                            draft.copy(
                                ruleName = ruleName.trim(),
                                groupId = gid,
                                priority = priority.toIntOrNull() ?: 0,
                                conditions = conditions
                            )
                        )
                    }
                )
            }
        )
    }
}

@Composable
private fun StyleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        label = { Text(text = label, color = style.secondaryText, fontSize = 12.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            cursorColor = style.accent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent
        ),
        textStyle = LocalTextStyle.current.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    )
}

@Composable
private fun GroupSelector(
    groups: List<BookGroup>,
    selectedId: Long,
    style: AppDialogStyle,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.firstOrNull { it.groupId == selectedId }?.groupName
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(style.fieldSurface, RoundedCornerShape(style.actionRadius))
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.smart_group_target_group),
                color = style.secondaryText,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = selectedName
                    ?: stringResource(R.string.smart_group_select_group),
                color = style.primaryText,
                fontSize = 14.sp
            )
        }
        if (groups.isEmpty()) {
            Text(
                text = stringResource(R.string.smart_group_no_groups),
                color = style.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else if (expanded) {
            groups.forEach { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(group.groupId)
                            expanded = false
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (group.groupId == selectedId) "●" else "○",
                        color = style.accent,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = group.groupName,
                        color = style.primaryText,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ConditionRow(
    condition: SmartGroupCondition,
    style: AppDialogStyle,
    onUpdate: (SmartGroupCondition) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.fieldSurface, RoundedCornerShape(style.actionRadius))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val typeOptions = listOf(
                SmartConditionType.TAG to stringResource(R.string.smart_group_type_tag),
                SmartConditionType.SOURCE to stringResource(R.string.smart_group_type_source),
                SmartConditionType.NAME to stringResource(R.string.smart_group_type_name),
                SmartConditionType.RECENT to stringResource(R.string.smart_group_type_recent)
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                typeOptions.forEach { (type, label) ->
                    Text(
                        text = label,
                        color = if (condition.type == type) style.onAccent else style.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(
                                if (condition.type == type) style.accent else style.surface,
                                RoundedCornerShape(style.actionRadius)
                            )
                            .clickable { onUpdate(condition.copy(type = type)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Text(
                text = "✕",
                color = style.danger,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { onRemove() }
                    .padding(6.dp)
            )
        }
        if (condition.type != SmartConditionType.RECENT) {
            StyleTextField(
                value = condition.value,
                onValueChange = { onUpdate(condition.copy(value = it)) },
                label = stringResource(R.string.smart_group_condition_value),
                style = style
            )
        } else {
            StyleTextField(
                value = condition.days.toString(),
                onValueChange = { text ->
                    val days = text.filter(Char::isDigit).take(4).toIntOrNull() ?: 1
                    onUpdate(condition.copy(days = days.coerceAtLeast(1)))
                },
                label = stringResource(R.string.smart_group_days),
                style = style
            )
        }
    }
}
