package io.legado.app.help.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import io.legado.app.R
import splitties.init.appCtx
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * 年度报告分享海报：原生 Canvas 绘制 PNG，避免 ComposeView 截图兼容性问题。
 */
object YearlyReportImageRenderer {

    private const val Width = 1080
    private const val Margin = 72f
    private val BgColor = Color.parseColor("#1C1C24")
    private val CardColor = Color.parseColor("#2A2A36")
    private val AccentColor = Color.parseColor("#FFB74D")
    private val TextColor = Color.WHITE
    private val SubTextColor = Color.parseColor("#A0A0B0")

    fun render(context: Context, data: YearlyReportData): File {
        val topCount = minOf(5, data.topBooks.size)
        val height = ceil(
            Margin * 2 + 220f + 360f + (if (topCount > 0) 120f + topCount * 96f else 0f)
        ).toInt().coerceAtLeast(1200)
        val bitmap = Bitmap.createBitmap(Width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BgColor)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TextColor
            textSize = 72f
            isFakeBoldText = true
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AccentColor
            textSize = 120f
            isFakeBoldText = true
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SubTextColor
            textSize = 36f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TextColor
            textSize = 48f
            isFakeBoldText = true
        }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AccentColor }
        val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CardColor }

        var y = Margin
        canvas.drawText(
            context.getString(R.string.yearly_report_title, data.year), Margin, y + 72f, titlePaint
        )
        y += 140f
        canvas.drawText(formatDuration(data.totalReadTime), Margin, y + 100f, accentPaint)
        y += 220f

        // 三个统计块
        val blockWidth = (Width - Margin * 2 - 48f) / 3f
        val blocks = listOf(
            context.getString(R.string.yearly_report_active_days) to data.activeDays.toString(),
            context.getString(R.string.yearly_report_streak) to
                context.getString(R.string.yearly_report_days_unit, data.longestStreak),
            context.getString(R.string.yearly_report_books) to data.bookCount.toString()
        )
        blocks.forEachIndexed { index, (label, value) ->
            val left = Margin + index * (blockWidth + 24f)
            val rect = RectF(left, y, left + blockWidth, y + 160f)
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CardColor }
            canvas.drawRoundRect(rect, 24f, 24f, cardPaint)
            canvas.drawText(value, left + 24f, y + 76f, valuePaint)
            canvas.drawText(label, left + 24f, y + 128f, labelPaint)
        }
        y += 240f

        // 月度分布
        canvas.drawText(context.getString(R.string.yearly_report_monthly), Margin, y + 28f, labelPaint)
        y += 64f
        val maxMonth = max(1L, data.monthlyTimes.max())
        val chartHeight = 240f
        val barWidth = (Width - Margin * 2 - 11 * 24f) / 12f
        data.monthlyTimes.forEachIndexed { month, time ->
            val barHeight = if (maxMonth <= 0) 4f else (time.toFloat() / maxMonth * chartHeight).coerceAtLeast(4f)
            val left = Margin + month * (barWidth + 24f)
            val top = y + chartHeight - barHeight
            canvas.drawRoundRect(
                RectF(left, top, left + barWidth, y + chartHeight), 12f, 12f,
                if (time > 0) barPaint else barTrackPaint
            )
            canvas.drawText("${month + 1}", left, y + chartHeight + 44f, labelPaint)
        }
        y += chartHeight + 96f

        // Top 书籍
        if (topCount > 0) {
            canvas.drawText(
                context.getString(R.string.yearly_report_top_books), Margin, y + 28f, labelPaint
            )
            y += 72f
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TextColor
                textSize = 40f
            }
            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SubTextColor
                textSize = 32f
            }
            data.topBooks.take(topCount).forEachIndexed { index, book ->
                val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = AccentColor
                    textSize = 40f
                    isFakeBoldText = true
                }
                canvas.drawText("${index + 1}", Margin, y + 40f, rankPaint)
                val name = book.name.take(16)
                canvas.drawText(name, Margin + 64f, y + 40f, namePaint)
                val timeText = formatDuration(book.readTime)
                canvas.drawText(timeText, Width - Margin - timePaint.measureText(timeText), y + 40f, timePaint)
                y += 96f
            }
        }

        val dir = File(appCtx.cacheDir, "yearly_report").apply { mkdirs() }
        val file = File(dir, "yearly_report_${data.year}.png").apply {
            if (exists()) delete()
        }
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
    }
}
