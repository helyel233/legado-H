package io.legado.app.ui.autoTask

import io.legado.app.ui.widget.dialog.V5DialogColors
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.association.ImportSourceItemRow
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.longToast
import io.legado.app.utils.showDialogFragment

/**
 * 导入自动任务弹出窗口（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + LegadoMiuixActionButton 换为 V5 AppDialog + AppGhostButton/AppFilledButton；
 * 列表行改用 V5 版 ImportSourceItemRow（不再吃 V5DialogColors）。
 * 「导入成功后 setFragmentResult(RESULT_REFRESH) 并关闭」「Saving 复用 Ready 列表」等语义不变。
 */
internal class ImportAutoTaskDialog() : V5DialogFragment(), CodeDialog.Callback {

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private val viewModel by viewModels<ImportAutoTaskViewModel>()

    constructor(source: String) : this() {
        arguments = bundleOf(ARG_SOURCE to source)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.load(arguments?.getString(ARG_SOURCE))
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val state by viewModel.state.collectAsStateWithLifecycle()
        // 委托属性无法 smart cast，取局部 val 后再做 when 分支判定
        val current = state

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.import_auto_task),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                when (current) {
                    ImportAutoTaskState.Loading -> LoadingContent()
                    is ImportAutoTaskState.Error -> ErrorContent(current.message)
                    ImportAutoTaskState.Imported -> {
                        Text(
                            text = stringResource(R.string.auto_task_import_done),
                            style = visual.type.body,
                            color = colors.onSurface,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                    is ImportAutoTaskState.Ready -> ReadyContent(current.items)
                    is ImportAutoTaskState.Saving -> ReadyContent(current.items)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() }
                    )
                    if (current is ImportAutoTaskState.Ready) {
                        AppFilledButton(
                            text = stringResource(R.string.import_),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.importSelected(
                                    onSuccess = {
                                        parentFragmentManager.setFragmentResult(
                                            RESULT_KEY,
                                            bundleOf(RESULT_REFRESH to true)
                                        )
                                        dismissAllowingStateLoss()
                                    },
                                    onError = { error ->
                                        longToast(
                                            error.localizedMessage
                                                ?: getString(R.string.wrong_format)
                                        )
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LoadingContent() {
        val colors = LocalSemanticColors.current
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(color = colors.primary)
            Text(
                text = stringResource(R.string.loading),
                style = visual.type.body,
                color = colors.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun ErrorContent(message: String) {
        val colors = LocalSemanticColors.current
        val visual = LocalVisualSpec.current
        Text(
            text = message,
            style = visual.type.body,
            color = colors.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        )
    }

    @Composable
    private fun ReadyContent(items: List<ImportAutoTaskItem>) {
        val colors = LocalSemanticColors.current
        val visual = LocalVisualSpec.current
        val selectedCount = items.count { it.selected }
        Text(
            text = if (selectedCount == items.size) {
                stringResource(R.string.select_cancel_count, selectedCount, items.size)
            } else {
                stringResource(R.string.select_all_count, selectedCount, items.size)
            },
            style = visual.type.caption,
            color = colors.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.toggleAll() }
                .padding(vertical = 6.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> "${item.rule.id}#$index" }
            ) { index, item ->
                ImportSourceItemRow(
                    name = item.rule.name.ifBlank { item.rule.id },
                    isChecked = item.selected,
                    stateText = stateText(item.state),
                    comment = item.rule.comment,
                    onCodeView = {
                        showDialogFragment(
                            CodeDialog(
                                code = GSON.toJson(item.rule),
                                disableEdit = false,
                                requestId = index.toString()
                            )
                        )
                    },
                    onCheckedChange = { checked -> viewModel.toggle(index, checked) }
                )
            }
        }
    }

    private fun stateText(state: io.legado.app.model.AutoTaskImport.State): String {
        return when (state) {
            io.legado.app.model.AutoTaskImport.State.NEW -> getString(R.string.auto_task_import_new)
            io.legado.app.model.AutoTaskImport.State.UPDATE -> getString(R.string.auto_task_import_update)
            io.legado.app.model.AutoTaskImport.State.EXISTING -> getString(R.string.auto_task_import_existing)
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toIntOrNull()?.let { index ->
            viewModel.updateRuleFromJson(index, code)
        }
    }

    companion object {
        const val RESULT_KEY = "auto_task_imported"
        const val RESULT_REFRESH = "refresh"
        private const val ARG_SOURCE = "source"
    }
}
