package io.legado.app.ui.book.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/**
 * 搜索范围选择（V5 基座版）：分组多选 / 书源单选两种模式。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容由旧 AppDialogFrame + LegadoComposeTheme + 自定义 ModeChip + OutlinedTextField + LegadoMiuixActionButton
 * 换为 V5 AppDialog（基座已提供 V5 主题与 M3 桥，不再自包 LegadoComposeTheme）
 * + `AppChip(selected)` + AppTextField + AppGhostButton/AppFilledButton。
 * **保留 M3 Checkbox / RadioButton**（V5 无多选/单选等价组件，且语义必须可区分），仅配色换 V5 语义色。
 * 业务保留：书源 Flow 走 `flowWithLifecycleAndDatabaseChange(BOOK_SOURCE_TABLE_NAME)` + catch 记日志 + conflate、
 * 切到非书源模式清空搜索词、「全部书源」直接回调 `SearchScope("")`、确认时按模式构造 scope 后回调。
 */
internal class SearchScopeDialog : V5DialogFragment() {

    val callback: Callback get() = parentFragment as? Callback ?: activity as Callback

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    @Composable
    override fun Content() {
        var mode by remember { mutableStateOf(ScopeMode.GROUP) }
        var query by remember { mutableStateOf("") }
        val selectedGroups = remember { mutableStateListOf<String>() }
        var selectedSource by remember { mutableStateOf<BookSourcePart?>(null) }

        // 分组列表（一次加载）
        var groups by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            groups = appDb.bookSourceDao.allEnabledGroups()
        }

        // 书源 Flow（仅 SOURCE 模式时收集）
        val sourceFlow = remember(mode, query) {
            if (mode != ScopeMode.SOURCE) {
                flowOf(emptyList())
            } else {
                when {
                    query.isEmpty() -> appDb.bookSourceDao.flowAll()
                    else -> appDb.bookSourceDao.flowSearch(query)
                }.flowWithLifecycleAndDatabaseChange(
                    lifecycle,
                    table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                ).catch {
                    AppLog.put("搜索范围-多分组/书源界面更新出错", it)
                }.flowOn(IO).conflate()
            }
        }
        val sources by sourceFlow.collectAsStateWithLifecycle(initialValue = emptyList())

        SearchScopeContent(
            mode = mode,
            onModeChange = {
                mode = it
                if (it != ScopeMode.SOURCE) query = ""
            },
            query = query,
            onQueryChange = { query = it },
            groups = groups,
            selectedGroups = selectedGroups,
            sources = sources,
            selectedSource = selectedSource,
            onSelectSource = { selectedSource = it },
            onAllSources = {
                callback.onSearchScopeOk(SearchScope(""))
                dismiss()
            },
            onConfirm = {
                val scope = if (mode == ScopeMode.GROUP) {
                    SearchScope(selectedGroups.toList())
                } else {
                    selectedSource?.let { SearchScope(it) } ?: SearchScope("")
                }
                callback.onSearchScopeOk(scope)
                dismiss()
            },
            onCancel = { dismiss() }
        )
    }

    enum class ScopeMode { GROUP, SOURCE }

    interface Callback {
        fun onSearchScopeOk(searchScope: SearchScope)
    }
}

@Composable
private fun SearchScopeContent(
    mode: SearchScopeDialog.ScopeMode,
    onModeChange: (SearchScopeDialog.ScopeMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    groups: List<String>,
    selectedGroups: MutableList<String>,
    sources: List<BookSourcePart>,
    selectedSource: BookSourcePart?,
    onSelectSource: (BookSourcePart) -> Unit,
    onAllSources: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    AppDialog(onDismiss = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
        ) {
            Text(
                text = stringResource(R.string.search_scope),
                style = visual.type.title,
                color = colors.onSurface
            )
            // 模式切换：分组 / 书源
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppChip(
                    text = stringResource(R.string.group),
                    selected = mode == SearchScopeDialog.ScopeMode.GROUP,
                    onClick = { onModeChange(SearchScopeDialog.ScopeMode.GROUP) },
                )
                AppChip(
                    text = "书源",
                    selected = mode == SearchScopeDialog.ScopeMode.SOURCE,
                    onClick = { onModeChange(SearchScopeDialog.ScopeMode.SOURCE) },
                )
            }

            // 书源模式下显示搜索框
            if (mode == SearchScopeDialog.ScopeMode.SOURCE) {
                AppTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.search_book_source),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                if (mode == SearchScopeDialog.ScopeMode.GROUP) {
                    items(groups, key = { it }) { group ->
                        ScopeCheckRow(
                            label = group,
                            checked = selectedGroups.contains(group),
                            onToggle = {
                                if (selectedGroups.contains(group)) {
                                    selectedGroups.remove(group)
                                } else {
                                    selectedGroups.add(group)
                                }
                            }
                        )
                    }
                } else {
                    items(sources, key = { it.bookSourceUrl }) { source ->
                        ScopeRadioRow(
                            label = source.bookSourceName,
                            selected = selectedSource == source,
                            onSelect = { onSelectSource(source) }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 3)) {
                AppGhostButton(
                    text = stringResource(R.string.all_source),
                    modifier = Modifier.weight(1f),
                    onClick = onAllSources,
                )
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onCancel,
                )
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun ScopeCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = colors.primary,
                uncheckedColor = colors.onSurfaceVariant,
                checkmarkColor = colors.onPrimary
            )
        )
        Text(
            text = label,
            color = colors.onSurface,
            style = visual.type.body
        )
    }
}

@Composable
private fun ScopeRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.onSurfaceVariant
            )
        )
        Text(
            text = label,
            color = colors.onSurface,
            style = visual.type.body
        )
    }
}
