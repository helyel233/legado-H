package io.legado.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.debug.DebugEventCenter
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * 调试日志查看（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + AppRuleTabRow + AppRuleTextField + LegadoMiuixCard + LegadoMiuixActionButton
 * 换为 V5 AppDialog + `AppFilterChipRow`（分类标签）+ AppTextField + AppCard + AppGhostButton。
 * **行为新增**：「清空」按 V5 危险操作红线补 `AppConfirmDialog(danger = true)`
 * （旧版点击即清无确认，与 CrashLogsDialog 的处理保持一致）。
 * 业务保留：分类 + 关键字过滤、`DebugEventCenter.eventFlow` 实时刷新、点条目看详情（含堆栈）、导出复制全文。
 */
internal class DebugLogDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    @Composable
    override fun Content() {
        val context = LocalContext.current
        var clearConfirmOpen by remember { mutableStateOf(false) }
        DebugLogContent(
            onDismiss = { dismissAllowingStateLoss() },
            onClear = { clearConfirmOpen = true },
            onExport = {
                context.sendToClip(DebugEventCenter.exportToText())
                context.toastOnUi(R.string.copy_complete)
            },
            onShowDetail = { event ->
                showDialogFragment(TextDialog(event.message, buildDetailText(event)))
            }
        )
        if (clearConfirmOpen) {
            AppConfirmDialog(
                title = stringResource(R.string.debug_log),
                message = "确定清空全部调试日志？",
                confirmLabel = stringResource(R.string.clear),
                cancelLabel = stringResource(R.string.cancel),
                danger = true,
                onConfirm = {
                    clearConfirmOpen = false
                    DebugEventCenter.clear()
                },
                onDismiss = { clearConfirmOpen = false },
            )
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
    onClear: () -> Unit,
    onExport: () -> Unit,
    onShowDetail: (DebugEvent) -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    val categories = remember { DebugCategory.entries.toList() }
    var selectedCategory by remember { mutableStateOf(DebugCategory.ALL) }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(emptyList<DebugEvent>()) }

    fun filtered(): List<DebugEvent> {
        return DebugEventCenter.getLogsByCategory(selectedCategory).filter { event ->
            query.isBlank() || event.message.contains(query, ignoreCase = true)
        }
    }

    LaunchedEffect(selectedCategory, query) {
        events = filtered()
        // 有新事件时按当前筛选实时刷新
        DebugEventCenter.eventFlow.collect {
            events = filtered()
        }
    }

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
        ) {
            Text(
                text = stringResource(R.string.debug_log),
                style = visual.type.title,
                color = colors.onSurface
            )
            AppFilterChipRow(
                chips = categories.map {
                    AppFilterChip(label = it.displayName, selected = it == selectedCategory)
                },
                onChipClick = { index -> selectedCategory = categories[index] },
            )
            AppTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.search),
                singleLine = true,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.debug_log_empty),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center
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
                            onClick = { onShowDetail(event) }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 3)) {
                AppGhostButton(
                    text = stringResource(R.string.clear),
                    modifier = Modifier.weight(1f),
                    onClick = onClear,
                )
                AppGhostButton(
                    text = stringResource(R.string.debug_log_export),
                    modifier = Modifier.weight(1f),
                    onClick = onExport,
                )
                AppGhostButton(
                    text = stringResource(R.string.close),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
            }
        }
    }
}

/** 单条日志：级别色点 + 分类 + 时间 + 消息 */
@Composable
private fun DebugLogItem(
    event: DebugEvent,
    onClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(levelColor(event.level), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = event.category.displayName,
                color = colors.primary,
                style = visual.type.caption,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = DebugLogUtils.formatShortTime(event.time),
                color = colors.onSurfaceVariant,
                style = visual.type.caption
            )
        }
        Spacer(modifier = Modifier.padding(top = 2.dp))
        Text(
            text = event.message,
            color = colors.onSurface,
            style = visual.type.body,
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
