package io.legado.app.ui.book.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 书籍分组管理弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 保留旧版 `dialogWindowAnimations = R.style.AnimDialogFade`（V5 基座同名 open val 直接对应）；
 * 内容由旧 AppDialogFrame + LegadoComposeTheme + LegadoMiuixCard + LegadoMiuixSwitch 换为
 * V5 AppDialog + AppCard + M3 Switch（套 V5 语义色，基座已提供 M3 主题桥）。
 * **关键行为保留**：拖拽排序仍走 sh.calvin.reorderable（`rememberReorderableLazyListState` +
 * `draggableHandle(onDragStopped = { onReorder(orderedGroups) })`），组分变化时用
 * `referentialEqualityPolicy` + `LaunchedEffect(groupsSignature)` 重置本地顺序快照；
 * 「分组达上限(64)则 toast 不新增」「切换 show 走 viewModel.upGroup」等语义不变。
 */
internal class GroupManageDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700
    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    private val viewModel: GroupViewModel by viewModels()

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val context = LocalContext.current
        val groups by appDb.bookGroupDao.flowAll()
            .catch { }
            .flowOn(IO)
            .conflate()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val lazyListState = rememberLazyListState()
        val groupsSnapshot = groups.toList()
        val groupsSignature = groupsSnapshot.joinToString(separator = "\u001F") { it.toString() }
        var orderedGroups by remember {
            mutableStateOf(groupsSnapshot, referentialEqualityPolicy())
        }
        LaunchedEffect(groupsSignature) {
            orderedGroups = groupsSnapshot
        }
        val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
            orderedGroups = orderedGroups.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }

        AppDialog(onDismiss = { dismiss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.group_manage),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AppGhostButton(
                        text = stringResource(R.string.add_group),
                        onClick = {
                            if (appDb.bookGroupDao.canAddGroup) {
                                showDialogFragment(GroupEditDialog())
                            } else {
                                context.toastOnUi("分组已达上限(64个)")
                            }
                        },
                    )
                }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(orderedGroups, key = { it.groupId }) { group ->
                        ReorderableItem(reorderState, key = group.groupId) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_drag_handle),
                                    contentDescription = stringResource(R.string.sort),
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(22.dp)
                                        .draggableHandle(
                                            onDragStopped = { viewModel.upOrder(orderedGroups) }
                                        )
                                )
                                Text(
                                    text = group.getManageName(context),
                                    style = visual.type.body,
                                    color = colors.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = group.show,
                                    onCheckedChange = {
                                        viewModel.upGroup(group.copy(show = !group.show))
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = colors.onPrimary,
                                        checkedTrackColor = colors.primary,
                                        uncheckedThumbColor = colors.outline,
                                        uncheckedTrackColor = colors.surfaceVariant
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = { showDialogFragment(GroupEditDialog(group)) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit),
                                        contentDescription = stringResource(R.string.edit),
                                        tint = colors.primary
                                    )
                                }
                            }
                        }
                    }
                }
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { dismiss() },
                )
            }
        }
    }
}
