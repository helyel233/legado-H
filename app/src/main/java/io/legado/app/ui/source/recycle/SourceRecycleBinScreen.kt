package io.legado.app.ui.source.recycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppRuleTabRow
import io.legado.app.ui.widget.compose.AppRuleTextField
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 分类过滤（对应 Max 的 SourceRecycleBinFilter，无搜索引擎规则体系） */
private enum class RecycleBinFilter(val type: String?, val labelRes: Int) {
    ALL(null, R.string.all),
    BOOK_SOURCE(SourceRecycleBinHelp.TYPE_BOOK_SOURCE, R.string.book_source),
    RSS_SOURCE(SourceRecycleBinHelp.TYPE_RSS_SOURCE, R.string.rss_source),
    REPLACE_RULE(SourceRecycleBinHelp.TYPE_REPLACE_RULE, R.string.replace_rule),
    TXT_TOC_RULE(SourceRecycleBinHelp.TYPE_TXT_TOC_RULE, R.string.txt_toc_rule),
    HTTP_TTS(SourceRecycleBinHelp.TYPE_HTTP_TTS, R.string.speak_engine),
    DICT_RULE(SourceRecycleBinHelp.TYPE_DICT_RULE, R.string.dict_rule),
    HIGHLIGHT_RULE(SourceRecycleBinHelp.TYPE_HIGHLIGHT_RULE, R.string.highlight_rule_manage)
}

/** 确认对话框状态 */
private sealed interface RecycleBinConfirm {
    data class Restore(val items: List<SourceRecycleBin>, val conflict: Boolean) : RecycleBinConfirm
    data class Delete(val items: List<SourceRecycleBin>) : RecycleBinConfirm
    data object ClearAll : RecycleBinConfirm
}

/**
 * 规则回收站主界面：分类 chips + 搜索 + 列表 + 批量操作，
 * 恢复前检测同名冲突，支持覆盖与 7 天过期自动清理。
 */
@Composable
fun SourceRecycleBinScreen() {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val scope = rememberCoroutineScope()

    val filters = remember { RecycleBinFilter.entries.toList() }
    var filter by remember { mutableStateOf(RecycleBinFilter.ALL) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var enabled by remember { mutableStateOf(AppConfig.sourceRecycleBinEnabled) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf<RecycleBinConfirm?>(null) }

    val items by produceState(emptyList<SourceRecycleBin>(), filter) {
        val flow = filter.type?.let { appDb.sourceRecycleBinDao.flowByType(it) }
            ?: appDb.sourceRecycleBinDao.flowAll()
        flow.collect { value = it }
    }

    val displayedItems = remember(items, query) {
        val key = query.text.trim()
        if (key.isEmpty()) {
            items
        } else {
            items.filter { item ->
                item.name.contains(key, ignoreCase = true) ||
                    item.key.contains(key, ignoreCase = true) ||
                    item.groupName.orEmpty().contains(key, ignoreCase = true) ||
                    item.payload.contains(key, ignoreCase = true)
            }
        }
    }
    val selectedItems = remember(displayedItems, selectedIds) {
        displayedItems.filter { it.id in selectedIds }
    }

    // 列表刷新后剔除已不存在的选中项
    LaunchedEffect(items) {
        val validIds = items.mapTo(mutableSetOf()) { it.id }
        selectedIds = selectedIds.filterTo(mutableSetOf()) { it in validIds }
    }

    fun requestRestore(targets: List<SourceRecycleBin>) {
        scope.launch {
            val conflict = withContext(Dispatchers.IO) {
                targets.any { SourceRecycleBinHelp.hasConflict(it) }
            }
            confirm = RecycleBinConfirm.Restore(targets, conflict)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppRuleTabRow(
            tabs = filters.map { stringResource(it.labelRes) },
            selectedIndex = filters.indexOf(filter),
            onSelected = { index -> filter = filters[index] }
        )
        AppRuleTextField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.search),
            singleLine = true,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        if (displayedItems.isEmpty()) {
            Text(
                text = stringResource(R.string.source_recycle_bin_empty),
                color = style.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedItems, key = { it.id }) { item ->
                    RecycleBinItemCard(
                        item = item,
                        selected = item.id in selectedIds,
                        style = style,
                        onClick = {
                            selectedIds = if (item.id in selectedIds) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        },
                        onRestore = { requestRestore(listOf(item)) },
                        onDelete = { confirm = RecycleBinConfirm.Delete(listOf(item)) }
                    )
                }
            }
            // 底部批量操作行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(
                        if (enabled) R.string.disable_source_recycle_bin
                        else R.string.enable_source_recycle_bin
                    ),
                    palette = palette,
                    cornerRadius = style.actionRadius,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val newValue = !enabled
                        AppConfig.sourceRecycleBinEnabled = newValue
                        enabled = newValue
                    }
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.clear),
                    palette = palette,
                    danger = true,
                    cornerRadius = style.actionRadius,
                    onClick = { confirm = RecycleBinConfirm.ClearAll }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.select_all),
                    palette = palette,
                    cornerRadius = style.actionRadius,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedIds = if (selectedIds.size == displayedItems.size) {
                            emptySet()
                        } else {
                            displayedItems.mapTo(mutableSetOf()) { it.id }
                        }
                    }
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.restore),
                    palette = palette,
                    primary = true,
                    cornerRadius = style.actionRadius,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (selectedItems.isNotEmpty()) requestRestore(selectedItems)
                    }
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.delete_forever),
                    palette = palette,
                    danger = true,
                    cornerRadius = style.actionRadius,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (selectedItems.isNotEmpty()) {
                            confirm = RecycleBinConfirm.Delete(selectedItems)
                        }
                    }
                )
            }
        }
    }

    // 确认对话框
    when (val state = confirm) {
        is RecycleBinConfirm.Restore -> {
            val message = if (state.conflict) {
                if (state.items.size == 1) {
                    stringResource(R.string.source_recycle_bin_conflict_msg, state.items.first().name)
                } else {
                    stringResource(
                        R.string.source_recycle_bin_batch_conflict_msg, state.items.size
                    )
                }
            } else {
                if (state.items.size == 1) {
                    stringResource(R.string.source_recycle_bin_restore_msg, state.items.first().name)
                } else {
                    stringResource(R.string.source_recycle_bin_batch_restore_msg, state.items.size)
                }
            }
            RecycleBinConfirmDialog(
                title = if (state.conflict) {
                    stringResource(R.string.source_recycle_bin_conflict_title)
                } else {
                    stringResource(R.string.restore)
                },
                message = message,
                confirmText = stringResource(
                    if (state.conflict) R.string.overwrite else R.string.restore
                ),
                danger = false,
                style = style,
                onConfirm = {
                    scope.launch {
                        runRestore(state.items, overwrite = state.conflict)
                    }
                    confirm = null
                },
                onDismiss = { confirm = null }
            )
        }
        is RecycleBinConfirm.Delete -> RecycleBinConfirmDialog(
            title = stringResource(R.string.delete_forever),
            message = if (state.items.size == 1) {
                stringResource(R.string.source_recycle_bin_delete_msg, state.items.first().name)
            } else {
                stringResource(R.string.source_recycle_bin_batch_delete_msg, state.items.size)
            },
            confirmText = stringResource(R.string.delete_forever),
            danger = true,
            style = style,
            onConfirm = {
                scope.launch { runDelete(state.items) }
                confirm = null
            },
            onDismiss = { confirm = null }
        )
        RecycleBinConfirm.ClearAll -> RecycleBinConfirmDialog(
            title = stringResource(R.string.source_recycle_bin_clear_title),
            message = stringResource(R.string.source_recycle_bin_clear_msg),
            confirmText = stringResource(R.string.clear),
            danger = true,
            style = style,
            onConfirm = {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { appDb.sourceRecycleBinDao.deleteAll() }
                    }.onFailure {
                        AppLog.put("回收站清空失败\n${it.localizedMessage}", it)
                        appCtx.toastOnUi("清空失败：${it.localizedMessage}")
                        return@launch
                    }
                    appCtx.toastOnUi(R.string.source_recycle_bin_cleared)
                }
                confirm = null
            },
            onDismiss = { confirm = null }
        )
        null -> Unit
    }
}

/** 回收站条目卡片：勾选 + 名称/类型分组/删除时间 + 单条恢复/彻底删除 */
@Composable
private fun RecycleBinItemCard(
    item: SourceRecycleBin,
    selected: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = if (selected) style.accent.copy(alpha = 0.10f) else style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (selected) "☑" else "☐",
                color = if (selected) style.accent else style.secondaryText,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.ifBlank { item.key },
                    color = style.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.padding(top = 2.dp))
                Text(
                    text = typeLabel(item.type),
                    color = style.accent,
                    fontSize = 12.sp
                )
                item.groupName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stringResource(
                        R.string.source_recycle_bin_time_left,
                        formatTime(item.deletedAt),
                        remainingDays(item.expireAt)
                    ),
                    color = style.secondaryText,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.restore),
                color = style.accent,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onRestore() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.delete_forever),
                color = style.danger,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onDelete() }
            )
        }
    }
}

@Composable
private fun RecycleBinConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    style: AppDialogStyle,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = title,
        message = message,
        scrollContent = false,
        content = {},
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                cornerRadius = style.actionRadius,
                onClick = onDismiss
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = confirmText,
                palette = palette,
                danger = danger,
                cornerRadius = style.actionRadius,
                onClick = onConfirm
            )
        }
    )
}

@Composable
private fun typeLabel(type: String): String {
    return when (type) {
        SourceRecycleBinHelp.TYPE_BOOK_SOURCE -> stringResource(R.string.book_source)
        SourceRecycleBinHelp.TYPE_RSS_SOURCE -> stringResource(R.string.rss_source)
        SourceRecycleBinHelp.TYPE_REPLACE_RULE -> stringResource(R.string.replace_rule)
        SourceRecycleBinHelp.TYPE_TXT_TOC_RULE -> stringResource(R.string.txt_toc_rule)
        SourceRecycleBinHelp.TYPE_HTTP_TTS -> stringResource(R.string.speak_engine)
        SourceRecycleBinHelp.TYPE_DICT_RULE -> stringResource(R.string.dict_rule)
        SourceRecycleBinHelp.TYPE_HIGHLIGHT_RULE -> stringResource(R.string.highlight_rule_manage)
        else -> type
    }
}

private fun formatTime(time: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}

private fun remainingDays(expireAt: Long): Long {
    val millis = expireAt - System.currentTimeMillis()
    return TimeUnit.MILLISECONDS.toDays(millis).coerceAtLeast(0)
}

/** 批量恢复（IO 线程执行，完成后提示；失败记日志并提示，不崩溃） */
private suspend fun runRestore(items: List<SourceRecycleBin>, overwrite: Boolean) {
    runCatching {
        withContext(Dispatchers.IO) {
            items.forEach { SourceRecycleBinHelp.restore(it, overwrite) }
        }
    }.onFailure {
        AppLog.put("回收站恢复失败\n${it.localizedMessage}", it)
        appCtx.toastOnUi("恢复失败：${it.localizedMessage}")
        return
    }
    appCtx.toastOnUi(R.string.source_recycle_bin_restored)
}

/** 批量彻底删除（IO 线程执行，完成后提示；失败记日志并提示，不崩溃） */
private suspend fun runDelete(items: List<SourceRecycleBin>) {
    runCatching {
        withContext(Dispatchers.IO) {
            appDb.sourceRecycleBinDao.delete(*items.toTypedArray())
        }
    }.onFailure {
        AppLog.put("回收站删除失败\n${it.localizedMessage}", it)
        appCtx.toastOnUi("删除失败：${it.localizedMessage}")
        return
    }
    appCtx.toastOnUi(R.string.source_recycle_bin_deleted)
}
