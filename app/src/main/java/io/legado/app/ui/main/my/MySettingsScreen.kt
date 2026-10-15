package io.legado.app.ui.main.my

import androidx.compose.foundation.background
import io.legado.app.uikit.v5.components.AppDivider
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

internal enum class MySettingsRowKind {
    Action,
    ThemeMode,
    WebService,
    AutoTaskService
}

internal data class MySettingsThemeOption(
    val value: String,
    val label: String,
    val summary: String = ""
)

internal data class MySettingsSubSearchItem(
    val ownerKey: String,
    val title: String,
    val summary: String,
    val key: String,
    val ownerConfigTag: String
) {
    val searchText: String = listOf(title, summary, key).joinToString(" ").lowercase()
}

internal data class MySettingsRowModel(
    val key: String,
    val title: String,
    val summary: String? = null,
    val kind: MySettingsRowKind = MySettingsRowKind.Action,
    val danger: Boolean = false
)

internal data class MySettingsSectionModel(
    val title: String,
    val rows: List<MySettingsRowModel>
)

internal data class MyWebServiceUiState(
    val checked: Boolean,
    val summary: String
)

private data class VisibleSection(
    val title: String,
    val rows: List<VisibleRow>
)

private data class VisibleRow(
    val row: MySettingsRowModel,
    val summary: String,
    val searchTarget: MySettingsSubSearchItem?
)

private val SettingsHorizontalPadding = 12.dp

@Composable
internal fun MySettingsScreen(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    themeModeLabel: String,
    webServiceState: MyWebServiceUiState,
    autoTaskServiceState: MyWebServiceUiState,
    onThemeModeClick: () -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceClick: () -> Unit,
    onAutoTaskServiceCheckedChange: (Boolean) -> Unit,
    onAutoTaskServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    // P4 整改③：V5 原生渲染 —— 语义色/字阶/密度/形状族全部来自全局解析器，
    // 不再绑定旧 palette / UiCorner / miuix 组件
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val d = visual.density
    val visibleSections = buildVisibleSections(
        sections = sections,
        subSearchItems = subSearchItems,
        searchQuery = searchQuery,
        webServiceState = webServiceState,
        autoTaskServiceState = autoTaskServiceState,
        themeModeLabel = themeModeLabel,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = d.screenPadding,
            end = d.screenPadding,
            top = 8.dp,
            bottom = d.screenPadding * 6,
        ),
        verticalArrangement = Arrangement.spacedBy(d.blockGap),
    ) {
        items(visibleSections, key = { it.title }) { section ->
            SettingsSectionCard(
                section = section,
                themeModeLabel = themeModeLabel,
                webServiceState = webServiceState,
                autoTaskServiceState = autoTaskServiceState,
                onThemeModeClick = onThemeModeClick,
                onWebServiceCheckedChange = onWebServiceCheckedChange,
                onWebServiceClick = onWebServiceClick,
                onAutoTaskServiceCheckedChange = onAutoTaskServiceCheckedChange,
                onAutoTaskServiceClick = onAutoTaskServiceClick,
                onRowClick = onRowClick,
            )
        }
        if (visibleSections.isEmpty()) {
            item("empty") {
                Text(
                    text = "没有匹配的设置",
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = d.screenPadding),
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: VisibleSection,
    themeModeLabel: String,
    webServiceState: MyWebServiceUiState,
    autoTaskServiceState: MyWebServiceUiState,
    onThemeModeClick: () -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceClick: () -> Unit,
    onAutoTaskServiceCheckedChange: (Boolean) -> Unit,
    onAutoTaskServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    AppSurface(level = MaterialLevel.RAISED, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(LocalVisualSpec.current.density.cardPadding)) {
            Text(
                text = section.title,
                style = LocalVisualSpec.current.type.label,
                color = LocalSemanticColors.current.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            section.rows.forEachIndexed { index, item ->
                val isLastRowInSection = index == section.rows.lastIndex
                val showDivider = !isLastRowInSection
                when (item.row.kind) {
                    MySettingsRowKind.ThemeMode -> SettingsActionRow(
                        item = item.copy(summary = themeModeLabel),
                        showDivider = showDivider,
                        onClick = onThemeModeClick,
                    )

                    MySettingsRowKind.WebService -> ServiceSwitchRow(
                        item = item,
                        state = webServiceState,
                        showDivider = showDivider,
                        onCheckedChange = onWebServiceCheckedChange,
                        onClick = onWebServiceClick,
                    )

                    MySettingsRowKind.AutoTaskService -> ServiceSwitchRow(
                        item = item,
                        state = autoTaskServiceState,
                        showDivider = showDivider,
                        onCheckedChange = onAutoTaskServiceCheckedChange,
                        onClick = onAutoTaskServiceClick,
                    )

                    MySettingsRowKind.Action -> SettingsActionRow(
                        item = item,
                        showDivider = showDivider,
                        onClick = { onRowClick(item.row.key, item.searchTarget) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceSwitchRow(
    item: VisibleRow,
    state: MyWebServiceUiState,
    showDivider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = visual.density.rowHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = visual.density.rowPaddingV)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.row.title,
                style = visual.type.body,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.summary,
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        AppSwitch(checked = state.checked, onCheckedChange = onCheckedChange)
    }
    if (showDivider) {
        AppDivider(inset = false)
    }
}

@Composable
private fun SettingsActionRow(
    item: VisibleRow,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = visual.density.rowHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = visual.density.rowPaddingV)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.row.title,
                style = visual.type.body,
                color = if (item.row.danger) colors.error else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.summary,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (showDivider) {
        AppDivider(inset = false)
    }
}

private fun buildVisibleSections(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    webServiceState: MyWebServiceUiState,
    autoTaskServiceState: MyWebServiceUiState,
    themeModeLabel: String
): List<VisibleSection> {
    val query = searchQuery.trim().lowercase()
    return sections.mapNotNull { section ->
        val rows = section.rows.mapNotNull { row ->
            val summary = row.effectiveSummary(
                webServiceState = webServiceState,
                autoTaskServiceState = autoTaskServiceState,
                themeModeLabel = themeModeLabel
            )
            val matchedSubItems = if (query.isBlank()) {
                emptyList()
            } else {
                subSearchItems.filter { item ->
                    item.ownerKey == row.key && item.searchText.contains(query)
                }
            }
            val visible = query.isBlank()
                || row.title.lowercase().contains(query)
                || summary.lowercase().contains(query)
                || row.key.lowercase().contains(query)
                || matchedSubItems.isNotEmpty()
            if (visible) {
                VisibleRow(
                    row = row,
                    summary = matchedSubItems.firstOrNull()?.title ?: summary,
                    searchTarget = matchedSubItems.firstOrNull()
                )
            } else {
                null
            }
        }
        rows.takeIf { it.isNotEmpty() }?.let { VisibleSection(section.title, it) }
    }
}

private fun MySettingsRowModel.effectiveSummary(
    webServiceState: MyWebServiceUiState,
    autoTaskServiceState: MyWebServiceUiState,
    themeModeLabel: String
): String {
    return when (kind) {
        MySettingsRowKind.ThemeMode -> themeModeLabel
        MySettingsRowKind.WebService -> webServiceState.summary
        MySettingsRowKind.AutoTaskService -> autoTaskServiceState.summary
        MySettingsRowKind.Action -> summary.orEmpty()
    }
}
