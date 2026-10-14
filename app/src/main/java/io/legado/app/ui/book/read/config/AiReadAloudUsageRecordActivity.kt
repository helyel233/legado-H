package io.legado.app.ui.book.read.config

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudUsageRecord
import io.legado.app.model.ReadBook
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val usageTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 朗读 AI 消耗记录 V5 版（主题/外观编辑器批，台账 #46）。
 * 旧版：BaseActivity<ActivityThemeManageBinding> + RecyclerView 自绘 adapter（旧主题 UiCorner/主题色）。
 * 新版：AppCompatActivity + setV5Content + HostV5Page；列表用 LazyColumn + AppCard；顶栏溢出菜单承载
 * 类型筛选/当前书切换/删除选中/清空全部；弹窗全换 V5（类型筛选 AppMenuSheet、删除 AppConfirmDialog(danger)）。
 */
class AiReadAloudUsageRecordActivity : AppCompatActivity() {

    internal val records = mutableStateOf<List<AiReadAloudUsageRecord>>(emptyList())
    internal val selectedIds = mutableStateOf<Set<Long>>(emptySet())
    internal var typeFilter by mutableStateOf("")
    internal var currentBookOnly by mutableStateOf(false)

    internal var menuOpen by mutableStateOf(false)
    internal var typeFilterOpen by mutableStateOf(false)
    internal var recordMenuTarget by mutableStateOf<AiReadAloudUsageRecord?>(null)
    internal var confirmDeleteSelected by mutableStateOf(false)
    internal var confirmClearAll by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { UsageRecordScreen(this) }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val bookUrl = if (currentBookOnly) ReadBook.book?.bookUrl.orEmpty() else ""
            val loaded = withContext(Dispatchers.IO) {
                appDb.aiReadAloudUsageRecordDao.list(typeFilter, bookUrl)
            }
            records.value = loaded
            selectedIds.value = selectedIds.value.intersect(loaded.mapTo(hashSetOf()) { it.id })
        }
    }

    internal fun toggleBookFilter() {
        currentBookOnly = !currentBookOnly
        selectedIds.value = emptySet()
        load()
    }

    internal fun selectType(value: String) {
        typeFilter = value
        selectedIds.value = emptySet()
        load()
    }

    internal fun toggleSelectAll() {
        val all = records.value.mapTo(hashSetOf()) { it.id }
        selectedIds.value = if (all.isNotEmpty() && selectedIds.value.size == all.size) emptySet() else all
    }

    internal fun toggle(id: Long) {
        val cur = selectedIds.value
        selectedIds.value = if (id in cur) cur - id else cur + id
    }

    internal fun deleteSelected() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) {
            toastOnUi("请先长按选择记录")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiReadAloudUsageRecordDao.deleteByIds(ids)
            selectedIds.value = emptySet()
            launch(Dispatchers.Main) { load() }
        }
    }

    internal fun clearAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiReadAloudUsageRecordDao.clear()
            selectedIds.value = emptySet()
            launch(Dispatchers.Main) { load() }
        }
    }

    internal fun deleteRecord(record: AiReadAloudUsageRecord) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiReadAloudUsageRecordDao.delete(record.id)
            selectedIds.value = selectedIds.value - record.id
            launch(Dispatchers.Main) { load() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UsageRecordScreen(activity: AiReadAloudUsageRecordActivity) {
    BackHandler { activity.finish() }

    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val records = activity.records.value
    val selectedIds = activity.selectedIds.value
    val typeFilter = activity.typeFilter
    val currentBookOnly = activity.currentBookOnly

    val totalInput = records.sumOf { it.inputTokens }
    val totalCached = records.sumOf { it.cachedInputTokens }
    val totalOutput = records.sumOf { it.outputTokens }
    val total = records.sumOf { it.totalTokens.takeIf { v -> v > 0 } ?: (it.inputTokens + it.outputTokens) }
    val typeText = if (typeFilter.isNotBlank()) typeLabel(typeFilter) else "全部类型"
    val bookText = if (currentBookOnly) "当前书" else "全部书籍"
    val summary = "$typeText · $bookText · ${records.size} 条 · 输入 $totalInput · 缓存 $totalCached · 输出 $totalOutput · 总 $total · 已选 ${selectedIds.size}"

    HostV5Page(
        title = "消耗记录",
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.menuOpen = true },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = summary,
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = density.screenPadding, top = density.blockGap, end = density.screenPadding),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap,
                    bottom = density.blockGap / 2,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                items(records, key = { it.id }) { record ->
                    val selected = record.id in selectedIds
                    AppCard {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectedIds.isNotEmpty()) activity.toggle(record.id)
                                        else activity.recordMenuTarget = record
                                    },
                                    onLongClick = { activity.toggle(record.id) },
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = recordTitle(record, selected),
                                style = visual.type.body,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = recordSubtitle(record),
                                style = visual.type.caption,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            AppFilledButton(
                text = "全选",
                onClick = { activity.toggleSelectAll() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
            )
        }
    }

    // 顶栏溢出菜单
    if (activity.menuOpen) {
        AppMenuSheet(
            title = "消耗记录",
            actions = listOf(
                "类型筛选" to { activity.typeFilterOpen = true },
                (if (currentBookOnly) "显示全部" else "只看当前书") to { activity.toggleBookFilter() },
                "删除选中" to { activity.confirmDeleteSelected = true },
                "清空全部" to { activity.confirmClearAll = true },
            ),
            onDismiss = { activity.menuOpen = false },
        )
    }

    // 类型筛选
    if (activity.typeFilterOpen) {
        val types = listOf(
            "" to "全部",
            AiReadAloudUsageRecord.TYPE_ROLE to "多角色",
            AiReadAloudUsageRecord.TYPE_BGM to "配乐",
            AiReadAloudUsageRecord.TYPE_SFX to "音效",
            AiReadAloudUsageRecord.TYPE_AUDIO to "音频分析",
        )
        AppMenuSheet(
            title = "类型筛选",
            actions = types.map { (value, label) ->
                (if (value == typeFilter) "$label ✓" else label) to {
                    activity.typeFilterOpen = false
                    activity.selectType(value)
                }
            },
            onDismiss = { activity.typeFilterOpen = false },
        )
    }

    // 单条记录菜单（删除）
    activity.recordMenuTarget?.let { record ->
        AppMenuSheet(
            title = typeLabel(record.type),
            actions = listOf(
                stringResource(R.string.delete) to {
                    activity.recordMenuTarget = null
                    activity.deleteRecord(record)
                }
            ),
            onDismiss = { activity.recordMenuTarget = null },
        )
    }

    // 删除选中确认
    if (activity.confirmDeleteSelected) {
        AppConfirmDialog(
            title = "删除记录",
            message = "确定删除选中的 ${selectedIds.size} 条消耗记录？",
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.confirmDeleteSelected = false
                activity.deleteSelected()
            },
            onDismiss = { activity.confirmDeleteSelected = false },
        )
    }

    // 清空确认
    if (activity.confirmClearAll) {
        AppConfirmDialog(
            title = "清空记录",
            message = "确定清空所有朗读 AI 消耗记录？",
            confirmLabel = stringResource(R.string.clear),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.confirmClearAll = false
                activity.clearAll()
            },
            onDismiss = { activity.confirmClearAll = false },
        )
    }
}

private fun recordTitle(record: AiReadAloudUsageRecord, selected: Boolean): String {
    return buildString {
        if (selected) append("✓ ")
        append(typeLabel(record.type))
        append(" · ")
        append(statusLabel(record.status))
        if (record.batchName.isNotBlank()) append(" · ").append(record.batchName)
    }
}

private fun recordSubtitle(record: AiReadAloudUsageRecord): String {
    return buildList {
        add(record.chapterTitle.ifBlank { record.bookName.ifBlank { "未知章节" } })
        add(formatTime(record.createdAt))
        if (record.modelId.isNotBlank()) add(record.modelId)
        add("输入 ${record.inputTokens}")
        if (record.cachedInputTokens > 0) add("缓存 ${record.cachedInputTokens}")
        add("输出 ${record.outputTokens}")
        add("总 ${record.totalTokens.takeIf { it > 0 } ?: (record.inputTokens + record.outputTokens)}")
        if (record.error.isNotBlank()) add(record.error)
    }.joinToString(" · ")
}

private fun typeLabel(type: String): String = when (type) {
    AiReadAloudUsageRecord.TYPE_ROLE -> "多角色"
    AiReadAloudUsageRecord.TYPE_BGM -> "配乐"
    AiReadAloudUsageRecord.TYPE_SFX -> "音效"
    AiReadAloudUsageRecord.TYPE_AUDIO -> "音频分析"
    else -> "全部"
}

private fun statusLabel(status: String): String = when (status) {
    AiReadAloudUsageRecord.STATUS_SUCCESS -> "成功"
    AiReadAloudUsageRecord.STATUS_FAILED -> "失败"
    AiReadAloudUsageRecord.STATUS_CACHE -> "缓存"
    else -> status.ifBlank { "未知" }
}

private fun formatTime(time: Long): String {
    return if (time > 0) usageTimeFormat.format(Date(time)) else "-"
}
