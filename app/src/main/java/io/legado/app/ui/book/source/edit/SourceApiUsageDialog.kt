package io.legado.app.ui.book.source.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.source.SourceApiUsage
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * 「源所用API」弹窗（V5 基座版）：展示书源规则中用到的 API 及使用位置，
 * 支持搜索、点击位置跳转到字段、复制清单。
 *
 * 无旧 dialogSize 覆写（沿用基座默认 MATCH_PARENT）；
 * 内容由旧 AppDialogFrame + OutlinedTextField + LegadoMiuixActionButton 换为 V5 AppDialog + AppTextField + 双 AppGhostButton。
 * 「点击位置行 → dismiss 后回调 onJumpToField(tabIndex, fieldKey)」「复制清单 buildUsageText」等语义不变。
 */
internal class SourceApiUsageDialog : V5DialogFragment() {

    private var usages: List<SourceApiUsage> = emptyList()
    private var onJumpToField: ((tabIndex: Int, fieldKey: String) -> Unit)? = null
    private var onCopyUsageList: ((String) -> Unit)? = null

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        var query by remember { mutableStateOf("") }
        val filtered = remember(usages, query) {
            if (query.isBlank()) usages else usages.filter {
                it.api.contains(query, ignoreCase = true)
            }
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.source_api_usage),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.source_api_usage_search_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.source_api_usage_empty),
                        style = visual.type.body,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        filtered.forEach { usage ->
                            item(key = usage.api) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "${usage.api}（${usage.useCount}）",
                                        style = visual.type.body.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.onSurface
                                    )
                                    usage.locations.forEach { location ->
                                        val label = buildString {
                                            append(location.tabName)
                                            append(" · ")
                                            append(location.fieldKey)
                                            append(" ×")
                                            append(location.count)
                                            if (location.snippet.isNotBlank()) {
                                                append("\n")
                                                append(location.snippet)
                                            }
                                        }
                                        Text(
                                            text = label,
                                            style = visual.type.caption,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    dismissAllowingStateLoss()
                                                    onJumpToField?.invoke(
                                                        location.tabIndex,
                                                        location.fieldKey
                                                    )
                                                }
                                                .padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.source_api_usage_copy),
                        modifier = Modifier.weight(1f),
                        onClick = { onCopyUsageList?.invoke(buildUsageText(usages)) },
                    )
                    AppGhostButton(
                        text = stringResource(R.string.close),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                }
            }
        }
    }

    private fun buildUsageText(usages: List<SourceApiUsage>): String {
        return usages.joinToString("\n\n") { usage ->
            buildString {
                append(usage.api).append(" (").append(usage.useCount).append(")")
                usage.locations.forEach { location ->
                    append("\n  [").append(location.tabName).append("] ")
                        .append(location.fieldKey).append(" ×").append(location.count)
                    if (location.snippet.isNotBlank()) {
                        append("  ").append(location.snippet)
                    }
                }
            }
        }
    }

    companion object {
        fun create(
            usages: List<SourceApiUsage>,
            onJumpToField: (tabIndex: Int, fieldKey: String) -> Unit,
            onCopyUsageList: (String) -> Unit
        ): SourceApiUsageDialog {
            return SourceApiUsageDialog().apply {
                this.usages = usages
                this.onJumpToField = onJumpToField
                this.onCopyUsageList = onCopyUsageList
            }
        }
    }
}
