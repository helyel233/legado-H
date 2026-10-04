package io.legado.app.ui.main.readrecord

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityYearlyReportBinding
import io.legado.app.help.report.YearlyReportData
import io.legado.app.help.report.YearlyReportImageRenderer
import io.legado.app.help.report.YearlyTopBook
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 阅读年度报告页。
 */
class YearlyReportActivity :
    VMBaseActivity<ActivityYearlyReportBinding, YearlyReportViewModel>() {

    override val binding by viewBinding(ActivityYearlyReportBinding::inflate)
    override val viewModel by viewModels<YearlyReportViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setNavigationOnClickListener {
            finish()
        }
        binding.composeView.setContent {
            LegadoComposeTheme {
                YearlyReportScreen(viewModel)
            }
        }
    }
}

@Composable
private fun YearlyReportScreen(viewModel: YearlyReportViewModel) {
    val style = rememberAppDialogStyle()
    val data by viewModel.data.collectAsState()
    val year by viewModel.year.collectAsState()
    val bookMap by viewModel.bookMap.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(style.surface)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "‹",
                    color = style.accent,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clickable { viewModel.switchYear(year - 1) }
                        .padding(8.dp)
                )
                Text(
                    text = year.toString(),
                    color = style.primaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Text(
                    text = "›",
                    color = style.accent,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clickable { viewModel.switchYear(year + 1) }
                        .padding(8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.share),
                    color = style.accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { viewModel.shareReport() }
                        .padding(8.dp)
                )
            }
        }
        val report = data
        if (report == null) {
            item {
                Text(
                    text = stringResource(R.string.loading),
                    color = style.secondaryText,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        } else {
            item { SummaryCards(report, style) }
            item { MonthlyChart(report, style) }
            if (report.topBooks.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.yearly_report_top_books),
                        color = style.secondaryText,
                        fontSize = 13.sp
                    )
                }
                items(report.topBooks.size) { index ->
                    TopBookRow(report.topBooks[index], bookMap, index + 1, style)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SummaryCards(report: YearlyReportData, style: AppDialogStyle) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SummaryCard(
            value = YearlyReportImageRenderer.formatDuration(report.totalReadTime),
            label = stringResource(R.string.yearly_report_total),
            style = style,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            value = report.activeDays.toString(),
            label = stringResource(R.string.yearly_report_active_days),
            style = style,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            value = report.longestStreak.toString(),
            label = stringResource(R.string.yearly_report_streak),
            style = style,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    value: String,
    label: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(style.fieldSurface, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(text = value, color = style.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = style.secondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun MonthlyChart(report: YearlyReportData, style: AppDialogStyle) {
    val maxMonth = report.monthlyTimes.max().coerceAtLeast(1L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.fieldSurface, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(R.string.yearly_report_monthly),
            color = style.secondaryText,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            report.monthlyTimes.forEach { time ->
                val fraction = time.toFloat() / maxMonth
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((fraction * 96f).coerceAtLeast(2f).dp)
                        .background(
                            if (time > 0) style.accent else style.secondaryText,
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun TopBookRow(
    book: YearlyTopBook,
    bookMap: Map<String, io.legado.app.data.entities.Book>,
    rank: Int,
    style: AppDialogStyle
) {
    val shelfBook = bookMap[book.bookUrl]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.fieldSurface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            color = style.accent,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.width(24.dp)
        )
        if (shelfBook != null) {
            BookCoverImage(
                book = shelfBook,
                modifier = Modifier
                    .width(40.dp)
                    .height(54.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = book.name, color = style.primaryText, fontSize = 15.sp)
            Text(
                text = YearlyReportImageRenderer.formatDuration(book.readTime),
                color = style.secondaryText,
                fontSize = 12.sp
            )
        }
    }
}
