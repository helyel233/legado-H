package io.legado.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.debug.DebugEventCenter
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppRuleTabRow
import io.legado.app.ui.widget.compose.AppRuleTextField
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * 调试日志查看（移植自 Legado_Max 的调试日志体系·第一阶段）
 *
 * 分类标签（应用/网络/规则/书源/RSS/Toast/校验/崩溃/阅读器）+ 关键字过滤 + 实时刷新，
 * 点条目查看详情（含堆栈），支持清空与导出（复制全文）。
 */
class DebugLogDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val fragment = this
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DebugLogContent(
                    onDismiss = { dismissAllowingStateLoss() },
                    onShowDetail = { event ->
                        fragment.showDialogFragment(TextDialog(event.message, buildDetailText(event)))
                    }
                )
            }
        }
    }

    private fun buildDetailText(event: DebugEvent): String {
        return buildString {
            append("[${event.level.displayName}] [${event.category.displayName}]\n")
            append(DebugLogUtils.formatFullTime(event.time))
            event.sourceName?.let { append("\n书源: $it") }
            event.url?.let { append("\nURL: $it") }
            append("\n\n${event.message}")
            event.detail?.let { append("\n\n$it") }
            event.throwable?.let { append("\n\n${it.stackTraceToString()}") }
        }
    }

}

@Composable
private fun DebugLogContent(
    onDismiss: () -> Unit,
    onShowDetail: (DebugEvent) -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()

    val categories = remember { DebugCategory.entries.toList() }
    var selectedCategory by remember { mutableStateOf(DebugCategory.ALL) }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(emptyList<DebugEvent>()) }

    fun refresh() {
        events = DebugEventCenter.getLogsByCategory(selectedCategory).filter { event ->
            query.isBlank() || event.message.contains(query, ignoreCase = true)
        }
    }

    LaunchedEffect(selectedCategory, query) {
        refresh()
        // 有新事件时按当前筛选实时刷新
        DebugEventCenter.eventFlow.collect {
            events = DebugEventCenter.getLogsByCategory(selectedCategory).filter { event ->
                query.isBlank() || event.message.contains(query, ignoreCase = true)
            }
        }
    }

    AppDialogFrame(
        title = stringResource(R.string.debug_log),
        scrollContent = false,
        content = {
            Column {
                AppRuleTabRow(
                    tabs = categories.map { it.displayName },
                    selectedIndex = categories.indexOf(selectedCategory),
                    onSelected = { index -> selectedCategory = categories[index] }
                )
                Spacer(modifier = Modifier.padding(top = 6.dp))
                AppRuleTextField(
                    value = androidx.compose.ui.text.input.TextFieldValue(query),
                    onValueChange = { query = it.text },
                    label = stringResource(R.string.search),
                    singleLine = true,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.padding(top = 6.dp))
                if (events.isEmpty()) {
                    Text(
                        text = stringResource(R.string.debug_log_empty),
                        color = style.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(events, key = { it.id }) { event ->
                            DebugLogItem(
                                event = event,
                                style = style,
                                onClick = { onShowDetail(event) }
                            )
                        }
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.clear),
                palette = palette,
                danger = true,
                cornerRadius = style.actionRadius,
                onClick = {
                    DebugEventCenter.clear()
                    events = emptyList()
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.debug_log_export),
                palette = palette,
                cornerRadius = style.actionRadius,
                onClick = {
                    context.sendToClip(DebugEventCenter.exportToText())
                    context.toastOnUi(R.string.copy_complete)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.close),
                palette = palette,
                cornerRadius = style.actionRadius,
                onClick = onDismiss
            )
        }
    )
}

/** 单条日志：级别色点 + 分类 + 时间 + 消息 */
@Composable
private fun DebugLogItem(
    event: DebugEvent,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(levelColor(event.level), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = event.category.displayName,
                color = style.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = DebugLogUtils.formatShortTime(event.time),
                color = style.secondaryText,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.padding(top = 2.dp))
        Text(
            text = event.message,
            color = style.primaryText,
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 级别对应的色点颜色 */
private fun levelColor(level: DebugLevel): Color = when (level) {
    DebugLevel.DEBUG -> Color(0xFF9E9E9E)
    DebugLevel.INFO -> Color(0xFF4CAF50)
    DebugLevel.WARN -> Color(0xFFFF9800)
    DebugLevel.ERROR -> Color(0xFFF44336)
}
