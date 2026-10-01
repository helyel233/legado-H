package io.legado.app.ui.book.source.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.source.SourceApiUsage
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 「源所用API」弹窗：展示书源规则中用到的 API 及使用位置，
 * 支持搜索、点击位置跳转到字段、复制清单。
 */
class SourceApiUsageDialog : ComposeDialogFragment() {

    private var usages: List<SourceApiUsage> = emptyList()
    private var onJumpToField: ((tabIndex: Int, fieldKey: String) -> Unit)? = null
    private var onCopyUsageList: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                var query by remember { mutableStateOf("") }
                val filtered = remember(usages, query) {
                    if (query.isBlank()) usages else usages.filter {
                        it.api.contains(query, ignoreCase = true)
                    }
                }
                AppDialogFrame(
                    title = stringResource(R.string.source_api_usage),
                    scrollContent = false,
                    content = {
                        Column {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.source_api_usage_search_hint),
                                        fontSize = 13.sp,
                                        color = palette.secondaryText
                                    )
                                },
                                shape = RoundedCornerShape(style.actionRadius ?: 8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = palette.accent,
                                    unfocusedBorderColor = palette.surfaceVariant
                                )
                            )
                            if (filtered.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.source_api_usage_empty),
                                    fontSize = 13.sp,
                                    color = palette.secondaryText,
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
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.primaryText
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
                                                        fontSize = 12.sp,
                                                        color = palette.secondaryText,
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
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.source_api_usage_copy),
                            palette = palette,
                            onClick = { onCopyUsageList?.invoke(buildUsageText(usages)) },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(0.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.close),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
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
