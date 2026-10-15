package io.legado.app.ui.association

import io.legado.app.ui.widget.dialog.V5DialogColors
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
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment

/**
 * 导入替换规则弹出窗口（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + LegadoMiuixActionButton 换为 V5 AppDialog + AppGhostButton/AppFilledButton；
 * 列表行改用 V5 版 ImportSourceItemRow（不再吃 V5DialogColors）。
 * 顶部第二个按钮文案沿用原文（`keep_original_name`，疑似原稿笔误，行为仍是打开「更多」菜单）。
 * 保留选项落盘（PreferKey.importKeepName）、自定义分组、编辑直接回写 allRules 等语义不变。
 */
internal class ImportReplaceRuleDialog() : V5DialogFragment(),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private val viewModel by viewModels<ImportReplaceRuleViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismissAllowingStateLoss()
        } else {
            viewModel.import(source)
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
        var groupDialogOpen by remember { mutableStateOf(false) }
        var groupText by remember { mutableStateOf("") }
        var groupAddChecked by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        var keepOriginal by remember { mutableStateOf(AppConfig.importKeepName) }

        val openGroupDialog = {
            groupText = viewModel.groupName ?: ""
            groupAddChecked = viewModel.isAddGroup
            groupDialogOpen = true
        }

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
                    text = stringResource(R.string.import_replace_rule),
                    style = visual.type.title,
                    color = colors.onSurface
                )

                // Menu row
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = getString(R.string.diy_source_group),
                        onClick = openGroupDialog
                    )
                    AppGhostButton(
                        text = getString(R.string.keep_original_name),
                        onClick = { menuOpen = true }
                    )
                }

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
                        val allRules = viewModel.allRules
                        val selectStatus = viewModel.selectStatus
                        val checkRules = viewModel.checkRules

                        val selectAllText = if (viewModel.isSelectAll) {
                            getString(R.string.select_cancel_count, viewModel.selectCount, allRules.size)
                        } else {
                            getString(R.string.select_all_count, viewModel.selectCount, allRules.size)
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
                                allRules,
                                key = { index, it -> "${it.id}#$index" }
                            ) { index, item ->
                                val isChecked = selectStatus.getOrNull(index) ?: false
                                val localRule = checkRules.getOrNull(index)
                                val nameText = if (item.group.isNullOrBlank()) {
                                    item.name ?: ""
                                } else {
                                    "${item.name}(${item.group})"
                                }
                                val stateText = when {
                                    localRule == null -> "新增"
                                    item.pattern != localRule.pattern
                                            || item.replacement != localRule.replacement
                                            || item.isRegex != localRule.isRegex
                                            || item.scope != localRule.scope -> "更新"
                                    else -> "已有"
                                }
                                ImportSourceItemRow(
                                    name = nameText,
                                    isChecked = isChecked,
                                    stateText = stateText,
                                    onCodeView = {
                                        showDialogFragment(
                                            CodeDialog(
                                                GSON.toJson(item),
                                                disableEdit = false,
                                                requestId = index.toString()
                                            )
                                        )
                                    },
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

        if (groupDialogOpen) {
            AppDialog(onDismiss = { groupDialogOpen = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    AppTextField(
                        value = groupText,
                        onValueChange = { groupText = it },
                        placeholder = getString(R.string.group_name),
                    )
                    AppRowWithSwitch(
                        title = stringResource(R.string.custom_group_summary),
                        checked = groupAddChecked,
                        onCheckedChange = { groupAddChecked = it },
                        card = false,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        AppGhostButton(
                            text = stringResource(R.string.cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { groupDialogOpen = false },
                        )
                        AppFilledButton(
                            text = stringResource(android.R.string.ok),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                groupDialogOpen = false
                                viewModel.isAddGroup = groupAddChecked
                                viewModel.groupName = groupText
                            },
                        )
                    }
                }
            }
        }

        if (menuOpen) {
            AppMenuSheet(
                title = stringResource(R.string.import_replace_rule),
                actions = listOf(
                    getString(R.string.diy_source_group) to openGroupDialog,
                    (if (keepOriginal) "✓ " else "") + getString(R.string.keep_original_name) to {
                        keepOriginal = !keepOriginal
                        putPrefBoolean(
                            PreferKey.importKeepName,
                            keepOriginal
                        )
                    }
                ),
                onDismiss = { menuOpen = false },
            )
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<ReplaceRule>(code).getOrNull()?.let { rule ->
                viewModel.allRules[it] = rule
            }
        }
    }
}
