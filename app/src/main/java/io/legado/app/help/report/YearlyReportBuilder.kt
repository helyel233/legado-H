package io.legado.app.help.report

import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 年度报告条目：本年读过的书（按累计阅读时长排序）。
 */
data class YearlyTopBook(
    val name: String,
    val author: String,
    val bookUrl: String,
    val coverUrl: String?,
    val readTime: Long
)

/**
 * 阅读年度报告聚合数据（纯只读，来源 readRecordDaily + readRecord + books）。
 */
data class YearlyReportData(
    val year: Int,
    val totalReadTime: Long,
    val activeDays: Int,
    val longestStreak: Int,
    val maxSingleDayTime: Long,
    val maxSingleDayDate: LocalDate?,
    val monthlyTimes: List<Long>,
    val topBooks: List<YearlyTopBook>
) {
    val bookCount: Int get() = topBooks.size
}

object YearlyReportBuilder {

    suspend fun build(year: Int): YearlyReportData = withContext(Dispatchers.IO) {
        val yearStart = "$year-01-01"
        val yearEnd = "$year-12-31"
        val dailyStats = appDb.readRecordDailyDao.allDesc
            .filter { it.date >= yearStart && it.date <= yearEnd }
            .mapNotNull { record ->
                runCatching { LocalDate.parse(record.date) to record.readTime }.getOrNull()
            }
            .filter { it.second > 0 }
            .sortedBy { it.first }

        val totalReadTime = dailyStats.sumOf { it.second }
        val activeDays = dailyStats.size
        val maxSingleDay = dailyStats.maxByOrNull { it.second }

        var longestStreak = 0
        var currentStreak = 0
        var lastDate: LocalDate? = null
        dailyStats.forEach { (date, _) ->
            currentStreak = if (lastDate != null && date == lastDate!!.plusDays(1)) {
                currentStreak + 1
            } else {
                1
            }
            if (currentStreak > longestStreak) longestStreak = currentStreak
            lastDate = date
        }

        val monthlyTimes = List(12) { month ->
            dailyStats.filter { it.first.monthValue == month + 1 }.sumOf { it.second }
        }

        // 本年阅读的书：以 readRecord.lastRead 或 book.durChapterTime 落在本年为准，
        // 排序用全量累计时长（readRecord 无按年分书的时长明细）
        val yearStartMillis = LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yearEndMillis = LocalDate.of(year, 12, 31).plusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        val readRecordMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
        val books = appDb.bookDao.all
        val inYearNames = linkedSetOf<String>()
        readRecordMap.forEach { (name, show) ->
            if (show.lastRead in yearStartMillis..yearEndMillis) inYearNames.add(name)
        }
        books.forEach { book ->
            if (book.durChapterTime in yearStartMillis..yearEndMillis) inYearNames.add(book.name)
        }
        val topBooks = inYearNames.mapNotNull { name ->
            val record = readRecordMap[name]
            val book = books.firstOrNull { it.name == name }
            if (book == null && record == null) return@mapNotNull null
            YearlyTopBook(
                name = name,
                author = book?.author ?: "",
                bookUrl = book?.bookUrl ?: "",
                coverUrl = book?.coverUrl,
                readTime = record?.readTime ?: 0L
            )
        }.sortedByDescending { it.readTime }.take(10)

        YearlyReportData(
            year = year,
            totalReadTime = totalReadTime,
            activeDays = activeDays,
            longestStreak = longestStreak,
            maxSingleDayTime = maxSingleDay?.second ?: 0L,
            maxSingleDayDate = maxSingleDay?.first,
            monthlyTimes = monthlyTimes,
            topBooks = topBooks
        )
    }
}
