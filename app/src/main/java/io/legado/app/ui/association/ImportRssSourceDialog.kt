package io.legado.app.ui.association

import io.legado.app.ui.widget.dialog.V5DialogColors
import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import io.legado.app.data.entities.RssSource
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
import io.legado.app.utils.showDialogFragment

/**
 * 导入 rss 源弹出窗口（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + LegadoMiuixActionButton + KeepOptionChip(palette) 换为
 * V5 AppDialog + AppGhostButton/AppFilledButton + AppChip(selected)；
 * 列表行改用 V5 版 ImportSourceItemRow（不再吃 V5DialogColors）。
 * 保留选项落盘、自定义分组、编辑直接回写 allSources（不触发刷新）等语义不变。
 */
internal class ImportRssSourceDialog() : V5DialogFragment(),
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

    private val viewModel by viewModels<ImportRssSourceViewModel>()

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

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        var loadState by remember { mutableStateOf(ImportLoadState.LOADING) }
        var errorMsg by remember { mutableStateOf("") }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        var keepName by remember { mutableStateOf(AppConfig.importKeepName) }
        var keepGroup by remember { mutableStateOf(AppConfig.importKeepGroup) }
        var keepEnable by remember { mutableStateOf(AppConfig.importKeepEnable) }
        var showComment by remember { mutableStateOf(AppConfig.importShowComment) }
        var customGroup by remember { mutableStateOf(viewModel.groupName) }
        var addGroup by remember { mutableStateOf(viewModel.isAddGroup) }
        var groupDialogOpen by remember { mutableStateOf(false) }
        var groupText by remember { mutableStateOf("") }
        var groupAddChecked by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        val syncOptions = {
            keepName = AppConfig.importKeepName
            keepGroup = AppConfig.importKeepGroup
            keepEnable = AppConfig.importKeepEnable
            showComment = AppConfig.importShowComment
            customGroup = viewModel.groupName
            addGroup = viewModel.isAddGroup
        }
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
                    text = stringResource(R.string.import_rss_source),
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
                        text = getString(R.string.more),
                        onClick = { menuOpen = true }
                    )
                }

                // 保留选项状态一览，可直接点按切换，高亮为已开启
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 3)
                ) {
                    KeepOptionChip(
                        text = getString(R.string.keep_original_name),
                        checked = keepName
                    ) {
                        AppConfig.importKeepName = it
                        keepName = it
                    }
                    KeepOptionChip(
                        text = getString(R.string.keep_group),
                        checked = keepGroup
                    ) {
                        AppConfig.importKeepGroup = it
                        keepGroup = it
                    }
                    KeepOptionChip(
                        text = getString(R.string.keep_enable),
                        checked = keepEnable
                    ) {
                        AppConfig.importKeepEnable = it
                        keepEnable = it
                    }
                    KeepOptionChip(
                        text = getString(R.string.show_source_comment),
                        checked = showComment
                    ) {
                        AppConfig.importShowComment = it
                        showComment = it
                    }
                }

                if (!customGroup.isNullOrBlank()) {
                    Text(
                        text = customGroup + if (addGroup) stringResource(R.string.custom_group_add_suffix) else "",
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant
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
                                key = { index, it -> "${it.sourceUrl}#$index" }
                            ) { index, item ->
                                val isChecked = selectStatus.getOrNull(index) ?: false
                                val localSource = checkSources.getOrNull(index)
                                val stateText = when {
                                    localSource == null -> "新增"
                                    item.lastUpdateTime > localSource.lastUpdateTime -> "更新"
                                    else -> "已有"
                                }
                                val comment = if (AppConfig.importShowComment) {
                                    item.sourceComment?.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                }
                                ImportSourceItemRow(
                                    name = item.sourceName ?: "",
                                    isChecked = isChecked,
                                    stateText = stateText,
                                    comment = comment,
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
                                syncOptions()
                            },
                        )
                    }
                }
            }
        }

        if (menuOpen) {
            AppMenuSheet(
                title = stringResource(R.string.import_rss_source),
                actions = listOf(
                    getString(R.string.diy_source_group) +
                        customGroup?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() to {
                            openGroupDialog()
                        },
                    (if (keepName) "✓ " else "") + getString(R.string.keep_original_name) to {
                        AppConfig.importKeepName = !AppConfig.importKeepName
                        syncOptions()
                    },
                    (if (keepGroup) "✓ " else "") + getString(R.string.keep_group) to {
                        AppConfig.importKeepGroup = !AppConfig.importKeepGroup
                        syncOptions()
                    },
                    (if (keepEnable) "✓ " else "") + getString(R.string.keep_enable) to {
                        AppConfig.importKeepEnable = !AppConfig.importKeepEnable
                        syncOptions()
                    },
                    (if (showComment) "✓ " else "") + getString(R.string.show_source_comment) to {
                        AppConfig.importShowComment = !AppConfig.importShowComment
                        syncOptions()
                    },
                ),
                onDismiss = { menuOpen = false },
            )
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<RssSource>(code).getOrNull()?.let { source ->
                viewModel.allSources[it] = source
            }
        }
    }
}
