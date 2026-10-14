package io.legado.app.ui.host

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * 分组管理（V5 声明式版）。
 *
 * 替代旧 `ui.book.source.manage.GroupManageDialog` / `ui.replace.GroupManageDialog` /
 * `ui.rss.source.manage.GroupManageDialog` 三个薄壳 DialogFragment（内容同为
 * 旧库 `ComposeGroupManageDialogContent`）。三者的分组语义完全一致
 * （`List<String>` + add/rename/delete），故收敛为一个 V5 组件，
 * 由三个 Host 管理页各自以 state 挂载（不再走 DialogFragment）。
 */
@Composable
internal fun GroupManageDialogContent(
    groups: List<String>,
    onAddGroup: (String) -> Unit,
    onRenameGroup: (oldGroup: String, newGroup: String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    val isEditing = editingGroup != null

    fun resetInput() {
        editingGroup = null
        input = ""
    }

    fun submit() {
        val value = input.trim()
        if (value.isBlank()) return
        val old = editingGroup
        if (old == null) {
            onAddGroup(value)
        } else {
            onRenameGroup(old, value)
        }
        resetInput()
    }

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2),
        ) {
            Text(
                text = stringResource(R.string.group_manage),
                style = visual.type.title,
                color = colors.onSurface,
            )

            Text(
                text = stringResource(if (isEditing) R.string.group_edit else R.string.add_group),
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
            )
            AppTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = stringResource(R.string.group_name),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2)) {
                if (isEditing || input.isNotEmpty()) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { resetInput() },
                    )
                }
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    modifier = Modifier.weight(1f),
                    onClick = { submit() },
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap / 4),
            ) {
                items(items = groups, key = { it }) { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group,
                            modifier = Modifier.weight(1f),
                            style = visual.type.body,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.edit),
                            style = visual.type.caption,
                            color = colors.primary,
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                editingGroup = group
                                input = group
                            },
                        )
                        Spacer(Modifier.width(visual.density.blockGap))
                        Text(
                            text = stringResource(R.string.delete),
                            style = visual.type.caption,
                            color = colors.error,
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                onDeleteGroup(group)
                            },
                        )
                    }
                }
            }

            AppGhostButton(
                text = stringResource(R.string.close),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}
