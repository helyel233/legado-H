package io.legado.app.ui.association

import android.content.DialogInterface
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import io.legado.app.R
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * 导入 TXT 目录规则弹出窗口（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + LegadoMiuixActionButton 换为 V5 AppDialog + AppGhostButton/AppFilledButton；
 * 列表行改用 V5 版 ImportSourceItemRow（不再吃 AppDialogStyle）。
 * 全选切换、导入等待框、finishOnDismiss 等语义不变。
 */
internal class ImportTxtTocRuleDialog() : V5DialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private val viewModel by viewModels<ImportTxtTocRuleViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismissAllowingStateLoss()
        } else {
            viewModel.importSource(source)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        var loadState by remember { mutableStateOf(ImportLoadState.LOADING) }
        var errorMsg by remember { mutableStateOf("") }
        var refreshTrigger by remember { mutableIntStateOf(0) }

        DisposableEffect(Unit) {
            val errorObserver = Observer<String> {
                loadState = ImportLoadState.ERROR
                errorMsg = it ?: ""
            }
            val successObserver = Observer<Int> { count ->
                if (count != null && count > 0) {
                    loadState = ImportLoadState.SUCCESS
                    refreshTrigger++
                } else {
                    loadState = ImportLoadState.ERROR
                    errorMsg = getString(R.string.wrong_format)
                }
            }
            viewModel.errorLiveData.observe(viewLifecycleOwner, errorObserver)
            viewModel.successLiveData.observe(viewLifecycleOwner, successObserver)
            onDispose {
                viewModel.errorLiveData.removeObserver(errorObserver)
                viewModel.successLiveData.removeObserver(successObserver)
            }
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.import_txt_toc_rule),
                    style = visual.type.title,
                    color = colors.onSurface
                )

                when (loadState) {
                    ImportLoadState.LOADING -> {
                        Text(
                            text = getString(R.string.loading),
                            style = visual.type.body,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                    ImportLoadState.ERROR -> {
                        Text(
                            text = errorMsg,
                            style = visual.type.body,
                            color = colors.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                    ImportLoadState.SUCCESS -> {
                        refreshTrigger
                        val allSources = viewModel.allSources
                        val selectStatus = viewModel.selectStatus
                        val checkSources = viewModel.checkSources

                        val selectAllText = if (viewModel.isSelectAll) {
                            getString(R.string.select_cancel_count, viewModel.selectCount, allSources.size)
                        } else {
                            getString(R.string.select_all_count, viewModel.selectCount, allSources.size)
                        }
                        Text(
                            text = selectAllText,
                            style = visual.type.caption,
                            color = colors.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val selectAll = viewModel.isSelectAll
                                    viewModel.selectStatus.forEachIndexed { index, b ->
                                        if (b != !selectAll) {
                                            viewModel.selectStatus[index] = !selectAll
                                        }
                                    }
                                    refreshTrigger++
                                }
                                .padding(vertical = 6.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                allSources,
                                key = { index, it -> "${it.id}#$index" }
                            ) { index, item ->
                                val isChecked = selectStatus.getOrNull(index) ?: false
                                val localSource = checkSources.getOrNull(index)
                                val stateText = when {
                                    localSource == null -> "新增"
                                    item != localSource -> "更新"
                                    else -> "已有"
                                }
                                val comment = item.example?.takeIf { it.isNotBlank() }
                                ImportSourceItemRow(
                                    name = item.name ?: "",
                                    isChecked = isChecked,
                                    stateText = stateText,
                                    comment = comment,
                                    onCheckedChange = { checked ->
                                        if (index in selectStatus.indices) {
                                            viewModel.selectStatus[index] = checked
                                            refreshTrigger++
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() }
                    )
                    if (loadState == ImportLoadState.SUCCESS) {
                        AppFilledButton(
                            text = stringResource(R.string.ok),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val waitDialog = WaitDialog(requireContext())
                                waitDialog.show()
                                viewModel.importSelect {
                                    waitDialog.dismiss()
                                    dismissAllowingStateLoss()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
