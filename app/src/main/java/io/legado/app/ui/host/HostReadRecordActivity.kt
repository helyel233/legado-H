package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.ReadRecordRankDialog
import io.legado.app.ui.main.readrecord.YearlyReportActivity
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSectionHeader
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * P4-3：阅读记录 V5 化（2026-10-12，核心统计 + 热力图 + 列表）。
 *
 * 数据层与旧页同源（readRecord/readRecordDaily/readRecentBook DAO +
 * ReadRecordWidgetStore.buildRankItems）。已知裁剪（登记台账 #29）：
 * 组件显隐配置、目标卡片与头像裁剪、组件封面行留待后续。
 */
class HostReadRecordActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostReadRecordActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostReadRecordScreen() }
    }
}

private data class ReadRecordDashboardV5(
    val today: LocalDate = LocalDate.now(),
    val todayTime: Long = 0,
    val monthTime: Long = 0,
    val totalTime: Long = 0,
    val activeDays: Int = 0,
    val heatmapCells: List<Pair<LocalDate, Long>> = emptyList(),
    val recentBooks: List<Triple<String, String, String>> = emptyList(),
    val dailyTimeline: List<Pair<LocalDate, Long>> = emptyList(),
    val hasDailyStats: Boolean = false,
    val rankItems: List<io.legado.app.ui.about.ReadRecordRankItem> = emptyList(),
)

@Composable
private fun HostReadRecordScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density

    var dashboard by remember { mutableStateOf(ReadRecordDashboardV5()) }
    var menuOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }

    fun loadData() {
        activity.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                val month = YearMonth.from(today)
                val readRecordMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
                val dailyStats = appDb.readRecordDailyDao.allDesc.mapNotNull { record ->
                    runCatching { LocalDate.parse(record.date) to record.readTime }.getOrNull()
                }.sortedByDescending { it.first }
                val dailyMap = dailyStats.toMap()
                val recentBooks = appDb.readRecentBookDao.recentBooks(6).map { book ->
                    Triple(book.name, book.author, book.coverUrl ?: "")
                }
                val heatmapStart = today.minusDays(111)
                ReadRecordDashboardV5(
                    today = today,
                    todayTime = dailyMap[today] ?: 0L,
                    monthTime = dailyStats.filter { YearMonth.from(it.first) == month }.sumOf { it.second },
                    totalTime = appDb.readRecordDao.allTime,
                    activeDays = dailyStats.count { it.second > 0L },
                    heatmapCells = (0L..111L).map { offset ->
                        val d = heatmapStart.plusDays(offset)
                        d to (dailyMap[d] ?: 0L)
                    },
                    recentBooks = recentBooks,
                    dailyTimeline = dailyStats.take(14),
                    hasDailyStats = dailyStats.isNotEmpty(),
                    rankItems = io.legado.app.ui.about.ReadRecordWidgetStore.buildRankItems(),
                )
            }
            dashboard = data
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { loadData() }

    HostV5Page(
        title = stringResource(R.string.read_record),
        subtitle = if (dashboard.hasDailyStats) stringResource(R.string.read_record_stats_ready)
        else stringResource(R.string.read_record_stats_waiting),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
    ) {
        if (!dashboard.hasDailyStats && dashboard.recentBooks.isEmpty()) {
            AppEmpty(title = stringResource(R.string.read_record_stats_waiting))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap / 2,
                    bottom = density.screenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                // 概览统计
                item {
                    AppSectionHeader(title = stringResource(R.string.read_record_component_overview))
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        StatCard(
                            label = stringResource(R.string.read_record_today_label),
                            value = if (dashboard.hasDailyStats) formatDuring(context, dashboard.todayTime) else "--",
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            label = stringResource(R.string.read_record_month_label),
                            value = if (dashboard.hasDailyStats) formatDuring(context, dashboard.monthTime) else "--",
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            label = stringResource(R.string.read_record_total_label),
                            value = formatDuring(context, dashboard.totalTime),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 热力图（16 周 × 7 天）
                item {
                    AppSectionHeader(title = stringResource(R.string.read_record_heatmap))
                }
                item {
                    ReadHeatmapGrid(
                        cells = dashboard.heatmapCells,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 最近阅读
                if (dashboard.recentBooks.isNotEmpty()) {
                    item {
                        AppSectionHeader(title = stringResource(R.string.read_record_recent_books))
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                            items(dashboard.recentBooks) { (name, _, cover) ->
                                Column(
                                    modifier = Modifier.width(64.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    BookCoverImage(
                                        path = cover,
                                        name = name,
                                        author = null,
                                        sourceOrigin = null,
                                        modifier = Modifier
                                            .width(52.dp)
                                            .height(70.dp)
                                            .clip(visual.shape(io.legado.app.uikit.v5.token.ShapeKind.COVER)),
                                    )
                                    Text(
                                        text = name,
                                        style = visual.type.caption,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 每日记录
                if (dashboard.dailyTimeline.isNotEmpty()) {
                    item {
                        AppSectionHeader(title = stringResource(R.string.read_record_component_daily_records))
                    }
                    items(dashboard.dailyTimeline, key = { it.first.toString() }) { (date, time) ->
                        AppListItem(
                            headline = date.format(DateTimeFormatter.ofPattern("MM-dd", Locale.getDefault())),
                            supporting = formatDuring(context, time),
                            onClick = {},
                        )
                    }
                }

                // 排行榜
                if (dashboard.rankItems.isNotEmpty()) {
                    item {
                        AppSectionHeader(
                            title = stringResource(R.string.read_record_read_rank),
                            trailing = {
                                AppGhostButton(
                                    text = stringResource(R.string.more_menu),
                                    onClick = {
                                        ReadRecordRankDialog.show(
                                            activity,
                                            dashboard.rankItems,
                                            { mss -> formatDuring(context, mss) },
                                        ) { item ->
                                            activity.lifecycleScope.launch {
                                                withContext(Dispatchers.IO) {
                                                    appDb.readRecordDao.deleteByName(item.displayName)
                                                }
                                                loadData()
                                            }
                                        }
                                    },
                                )
                            },
                        )
                    }
                    items(dashboard.rankItems.take(5), key = { it.displayName }) { item ->
                        AppListItem(
                            headline = item.displayName,
                            supporting = formatDuring(context, item.readTime),
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    // ⋮ 菜单（对齐旧 book_read_record 菜单）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.read_record),
            actions = listOf(
                ((if (AppConfig.enableReadRecord) "✓ " else "") + stringResource(R.string.enable_record)) to {
                    AppConfig.enableReadRecord = !AppConfig.enableReadRecord
                    menuOpen = false
                },
                stringResource(R.string.yearly_report) to {
                    context.startActivity<YearlyReportActivity>()
                    menuOpen = false
                },
                stringResource(R.string.read_record_clear_all) to {
                    clearConfirm = true
                    menuOpen = false
                },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    if (clearConfirm) {
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.sure_del),
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                clearConfirm = false
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        appDb.readRecordDao.clear()
                        appDb.readRecordDailyDao.clear()
                        appDb.readRecentBookDao.clear()
                        io.legado.app.ui.about.ReadRecordWidgetStore.clearRecentSnapshots()
                    }
                    loadData()
                }
            },
            onDismiss = { clearConfirm = false },
        )
    }
}

private fun formatDuring(context: Context, mss: Long): String {
    val days = mss / (1000 * 60 * 60 * 24)
    val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
    val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
    val seconds = mss % (1000 * 60) / 1000
    val d = if (days > 0) context.getString(R.string.duration_day, days) else ""
    val h = if (hours > 0) context.getString(R.string.duration_hour, hours) else ""
    val m = if (minutes > 0) context.getString(R.string.duration_minute, minutes) else ""
    val s = if (seconds > 0 && days == 0L && hours == 0L) {
        context.getString(R.string.duration_second, seconds)
    } else {
        ""
    }
    val time = "$d$h$m$s"
    return if (time.isBlank()) context.getString(R.string.duration_zero) else time
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Column(
        modifier = modifier
            .clip(visual.shape(io.legado.app.uikit.v5.token.ShapeKind.CARD))
            .background(colors.surfaceVariant)
            .padding(vertical = visual.density.blockGap / 1.5f, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = visual.type.label,
            color = colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 16 周 × 7 天热力图（V5 自绘：primary 透明度分档）。 */
@Composable
private fun ReadHeatmapGrid(
    cells: List<Pair<LocalDate, Long>>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSemanticColors.current
    val max = cells.maxOfOrNull { it.second } ?: 0L
    // cells 按日期升序（0..111）；按列（周）铺 7 行
    val weeks = cells.chunked(7)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        weeks.forEach { week ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                week.forEach { (_, time) ->
                    val level = if (max <= 0L || time <= 0L) 0f
                    else (0.2f + 0.8f * (time.toFloat() / max)).coerceIn(0.2f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (time <= 0L) colors.surfaceVariant
                                else colors.primary.copy(alpha = level)
                            ),
                    )
                }
            }
        }
    }
}
