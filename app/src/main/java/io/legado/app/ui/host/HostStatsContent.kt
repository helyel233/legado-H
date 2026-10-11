package io.legado.app.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.ui.main.readrecord.YearlyReportActivity
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * P4-1c「统计」真业务（V5 自绘版）。
 *
 * 数据直连 appDb（与旧统计页同源：readRecordDailyDao / readRecentBookDao），
 * UI 用 V5 组件自绘 —— 概览数字、最近在读、日时长时间线、年报入口。
 * 旧统计页（901 行，含热力图/组件显隐配置/目标卡）保留在"我的 → 阅读记录"，
 * 长尾升级按需逐块并入。
 */
class HostStatsContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("统计"),
        subtitle = DestTitle.Text("阅读时长与记录"),
        actions = listOf(
            ChromeAction(
                id = "yearly",
                icon = DestIcon.Vector(HostIcons.Stats),
                contentDescription = DestTitle.Text("年度报告"),
                onClick = {
                    splitties.init.appCtx.startActivity<YearlyReportActivity>()
                },
            ),
        ),
    )

    private data class StatsData(
        val todayTime: Long = 0,
        val monthTime: Long = 0,
        val totalTime: Long = 0,
        val bookCount: Int = 0,
        val activeDays: Int = 0,
        val recent: List<RecentBook> = emptyList(),
        val daily: List<Pair<LocalDate, Long>> = emptyList(),
    )

    private data class RecentBook(
        val name: String,
        val author: String,
        val bookUrl: String,
        val coverUrl: String?,
        val readTime: Long,
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current
        val data by produceState(StatsData()) {
            value = withContext(Dispatchers.IO) { load() }
        }
        val d = scope.density

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenPadding),
            verticalArrangement = Arrangement.spacedBy(d.blockGap),
        ) {
            // ---- 概览四数字 ----
            Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                StatCell(scope, "今日", fmt(data.todayTime), Modifier.weight(1f))
                StatCell(scope, "本月", fmt(data.monthTime), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                StatCell(scope, "累计", fmt(data.totalTime), Modifier.weight(1f))
                StatCell(scope, "读过", "${data.bookCount} 本", Modifier.weight(1f))
            }

            // ---- 最近在读 ----
            if (data.recent.isNotEmpty()) {
                AppCard {
                    Column(Modifier.padding(d.cardPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("最近在读", style = scope.type.label, color = scope.color.onSurfaceVariant)
                        data.recent.forEach { book ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) {
                                        context.startActivityForBook(
                                            io.legado.app.data.entities.Book(
                                                name = book.name, author = book.author, bookUrl = book.bookUrl,
                                            )
                                        )
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
                            ) {
                                io.legado.app.ui.widget.compose.BookCoverImage(
                                    book = io.legado.app.data.entities.Book(
                                        name = book.name, author = book.author,
                                        bookUrl = book.bookUrl, coverUrl = book.coverUrl,
                                    ),
                                    modifier = Modifier.size(width = 34.dp, height = 46.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(book.name, style = scope.type.body, color = scope.color.onSurface, maxLines = 1)
                                    Text(
                                        book.author.ifBlank { " " },
                                        style = scope.type.caption,
                                        color = scope.color.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    fmt(book.readTime),
                                    style = scope.type.caption,
                                    color = scope.color.primary,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 日时长时间线（最近 14 天） ----
            if (data.daily.isNotEmpty()) {
                AppCard {
                    Column(Modifier.padding(d.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("最近 14 天", style = scope.type.label, color = scope.color.onSurfaceVariant)
                        val maxTime = data.daily.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
                        data.daily.take(14).forEach { (date, time) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
                            ) {
                                Text(
                                    text = "${date.monthValue}/${date.dayOfMonth}",
                                    style = scope.type.caption,
                                    color = scope.color.onSurfaceVariant,
                                    modifier = Modifier.size(width = 44.dp, height = 16.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(5.dp)
                                        .clip(scope.shape(io.legado.app.uikit.v5.token.ShapeKind.TAG))
                                        .background(scope.color.surfaceVariant),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((time.toFloat() / maxTime).coerceIn(0.02f, 1f))
                                            .height(5.dp)
                                            .clip(scope.shape(io.legado.app.uikit.v5.token.ShapeKind.TAG))
                                            .background(scope.color.primary),
                                    )
                                }
                                Text(
                                    text = fmt(time),
                                    style = scope.type.caption,
                                    color = scope.color.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(d.screenPadding))
        }
    }

    // ---------------------------------------------------------------- 数据

    private fun load(): StatsData {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val dailyRaw = appDb.readRecordDailyDao.allDesc.mapNotNull { record ->
            runCatching { LocalDate.parse(record.date) to record.readTime }.getOrNull()
        }.sortedByDescending { it.first }
        val dailyMap = dailyRaw.toMap()
        val recordMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
        val recent = appDb.readRecentBookDao.recentBooks(6).map { book ->
            RecentBook(
                name = book.name,
                author = book.author,
                bookUrl = book.bookUrl,
                coverUrl = book.coverUrl,
                readTime = recordMap[book.name]?.readTime ?: 0L,
            )
        }
        return StatsData(
            todayTime = dailyMap[today] ?: 0L,
            monthTime = dailyRaw.filter { YearMonth.from(it.first) == month }.sumOf { it.second },
            totalTime = appDb.readRecordDao.allTime,
            bookCount = appDb.readRecordDao.allShow.size,
            activeDays = dailyRaw.count { it.second > 0L },
            recent = recent,
            daily = dailyRaw,
        )
    }

    private fun fmt(ms: Long): String = when {
        ms <= 0 -> "0 分钟"
        ms < 3_600_000 -> "${ms / 60_000} 分钟"
        else -> String.format("%.1f 小时", ms / 3_600_000.0)
    }
}

@Composable
private fun StatCell(scope: ContentScope, label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(scope.density.cardPadding)) {
            Text(label, style = scope.type.caption, color = scope.color.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = scope.type.headline, color = scope.color.onSurface)
        }
    }
}
