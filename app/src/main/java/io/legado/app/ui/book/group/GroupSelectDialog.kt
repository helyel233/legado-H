package io.legado.app.ui.book.group

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.conflate

/**
 * 书籍分组多选弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660
 * （旧版额外覆写的 dialogGravity=CENTER / dialogWindowAnimations=AnimDialogCenter 恰是 V5 基座默认值，无需重复覆写）；
 * 内容由旧 AppDialogFrame + LegadoComposeTheme + LegadoMiuixCard 换为 V5 AppDialog + AppCard。
 * **关键行为保留**：分组多选是 bitmask 位运算（`selectedGroupId and group.groupId` 判定、`+/- groupId` 切换），
 * 确认时 `callBack.upGroup(requestCode, selectedGroupId)` 后关闭。
 */
internal class GroupSelectDialog() : V5DialogFragment() {

    constructor(groupId: Long, requestCode: Int = -1) : this() {
        arguments = Bundle().apply {
            putLong("groupId", groupId)
            putInt("requestCode", requestCode)
        }
    }

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private var requestCode: Int = -1
    private var initialGroupId: Long = 0L
    private val callBack get() = (activity as? CallBack)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialGroupId = arguments?.getLong("groupId") ?: 0L
        requestCode = arguments?.getInt("requestCode", -1) ?: -1
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val groups by appDb.bookGroupDao.flowSelect().conflate()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        var selectedGroupId by remember { mutableLongStateOf(initialGroupId) }

        AppDialog(onDismiss = { dismiss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.group_select),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AppGhostButton(
                        text = stringResource(R.string.add_group),
                        onClick = { showDialogFragment(GroupEditDialog()) },
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups, key = { it.groupId }) { group ->
                        AppCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selectedGroupId = if ((selectedGroupId and group.groupId) > 0) {
                                    selectedGroupId - group.groupId
                                } else {
                                    selectedGroupId + group.groupId
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = (selectedGroupId and group.groupId) > 0,
                                    onCheckedChange = {
                                        selectedGroupId =
                                            if ((selectedGroupId and group.groupId) > 0) {
                                                selectedGroupId - group.groupId
                                            } else {
                                                selectedGroupId + group.groupId
                                            }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = colors.primary,
                                        uncheckedColor = colors.outline,
                                        checkmarkColor = colors.onPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = group.groupName,
                                    style = visual.type.body,
                                    color = colors.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismiss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            callBack?.upGroup(requestCode, selectedGroupId)
                            dismissAllowingStateLoss()
                        },
                    )
                }
            }
        }
    }

    interface CallBack {
        fun upGroup(requestCode: Int, groupId: Long)
    }
}
