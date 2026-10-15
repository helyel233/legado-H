package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.help.report.YearlyReportData
import io.legado.app.help.report.YearlyReportImageRenderer
import io.legado.app.help.report.YearlyTopBook
import io.legado.app.ui.main.readrecord.YearlyReportViewModel
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppLoading
import io.legado.app.uikit.v5.components.AppSectionHeader
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P4-3：阅读年度报告 V5 化（台账 #2 剩余项）。
 * 数据层复用 YearlyReportViewModel + YearlyReportBuilder（后端零拷贝），
 * 界面从旧 palette 风格换到 V5 组件。
 */
class HostYearlyReportActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostYearlyReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostYearlyReportScreen() }
    }
}

@Composable
private fun HostYearlyReportScreen() {
    val context = LocalContext.current
    val activity = context as HostYearlyReportActivity
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    val viewModel = androidx.compose.runtime.remember {
        ViewModelProvider(activity)[YearlyReportViewModel::class.java]
    }
    val data by viewModel.data.collectAsStateWithLifecycle()
    val year by viewModel.year.collectAsStateWithLifecycle()
    val bookMap by viewModel.bookMap.collectAsStateWithLifecycle()

    HostV5Page(
        title = stringResource(R.string.yearly_report),
        onBack = { activity.finish() },
        trailing = {
            AppGhostButton(stringResource(R.string.share)) { viewModel.shareReport() }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            // 年份切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppGhostButton("‹") { viewModel.switchYear(year - 1) }
                Text(
                    text = year.toString(),
                    style = visual.type.headline,
                    color = colors.onSurface,
                )
                AppGhostButton("›") { viewModel.switchYear(year + 1) }
            }

            val report = data
            if (report == null) {
                AppLoading(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    item { SummaryCards(report) }
                    item { MonthlyChart(report) }
                    if (report.topBooks.isNotEmpty()) {
                        item { AppSectionHeader(title = stringResource(R.string.yearly_report_top_books)) }
                        items(report.topBooks.size) { index ->
                            TopBookRow(report.topBooks[index], bookMap, index + 1)
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SummaryCards(report: YearlyReportData) {
    Row(horizontalArrangement = Arrangement.spacedBy(io.legado.app.uikit.v5.runtime.LocalVisualSpec.current.density.blockGap / 2)) {
        SummaryCard(
            value = YearlyReportImageRenderer.formatDuration(report.totalReadTime),
            label = stringResource(R.string.yearly_report_total),
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            value = report.activeDays.toString(),
            label = stringResource(R.string.yearly_report_active_days),
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            value = report.longestStreak.toString(),
            label = stringResource(R.string.yearly_report_streak),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCard(value: String, label: String, modifier: Modifier = Modifier) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppCard(modifier = modifier) {
        Column(Modifier.padding(visual.density.cardPadding)) {
            Text(
                text = value,
                style = visual.type.title,
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
}

/** 12 个月时长柱状图（V5：primary 柱 + surfaceVariant 底）。 */
@Composable
private fun MonthlyChart(report: YearlyReportData) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val maxMonth = report.monthlyTimes.max().coerceAtLeast(1L)
    AppCard {
        Column(Modifier.padding(visual.density.cardPadding)) {
            Text(
                text = stringResource(R.string.yearly_report_monthly),
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                report.monthlyTimes.forEach { time ->
                    val fraction = time.toFloat() / maxMonth
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height((fraction * 96f).coerceAtLeast(2f).dp)
                            .clip(visual.shape(ShapeKind.TAG))
                            .background(if (time > 0) colors.primary else colors.surfaceVariant),
                    ) { }
                }
            }
        }
    }
}

@Composable
private fun TopBookRow(
    book: YearlyTopBook,
    bookMap: Map<String, io.legado.app.data.entities.Book>,
    rank: Int,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val shelfBook = bookMap[book.bookUrl]
    AppCard {
        Row(
            modifier = Modifier.padding(visual.density.cardPadding / 1.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2),
        ) {
            Text(
                text = rank.toString(),
                style = visual.type.title,
                color = colors.primary,
                modifier = Modifier.width(24.dp),
            )
            if (shelfBook != null) {
                BookCoverImage(
                    book = shelfBook,
                    modifier = Modifier
                        .width(40.dp)
                        .aspectRatio(0.75f)
                        .clip(visual.shape(ShapeKind.COVER)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    style = visual.type.body,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = YearlyReportImageRenderer.formatDuration(book.readTime),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
