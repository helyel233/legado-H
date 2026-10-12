package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.help.readrecord.ReadRecordRankItem
import io.legado.app.help.readrecord.ReadRecordWidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * 阅读记录数据汇聚（台账 #29-B8）。
 *
 * 此前 HostStatsContent / HostReadRecordActivity 各自直连 appDb 拼聚合查询
 * （两份近似的统计逻辑）。新壳与旧页统一走这里，页面只负责渲染。
 */
object ReadRecordRepository {

    data class RecentBook(
        val name: String,
        val author: String,
        val bookUrl: String,
        val coverUrl: String?,
        val readTime: Long,
    )

    data class Dashboard(
        val today: LocalDate = LocalDate.now(),
        val todayTime: Long = 0,
        val monthTime: Long = 0,
        val totalTime: Long = 0,
        val bookCount: Int = 0,
        val activeDays: Int = 0,
        /** 按日期降序的日时长（含 0 值日，用于时间线） */
        val daily: List<Pair<LocalDate, Long>> = emptyList(),
        /** 日期 → 时长（热力图与时间线取值的快速索引） */
        val dailyMap: Map<LocalDate, Long> = emptyMap(),
        /** 日历热力图格子：从 today 往前 [heatmapDays] 天，按日期升序 */
        val heatmapCells: List<Pair<LocalDate, Long>> = emptyList(),
        val recent: List<RecentBook> = emptyList(),
        val rankItems: List<ReadRecordRankItem> = emptyList(),
    ) {
        val hasDailyStats: Boolean get() = daily.isNotEmpty()
    }

    /** 汇总当前阅读记录面板数据（IO 线程执行，页面在协程里调用）。 */
    suspend fun dashboard(
        heatmapDays: Int = 112,
        recentLimit: Int = 6,
    ): Dashboard = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val daily = appDb.readRecordDailyDao.allDesc.mapNotNull { record ->
            runCatching { LocalDate.parse(record.date) to record.readTime }.getOrNull()
        }.sortedByDescending { it.first }
        val dailyMap = daily.toMap()
        val recordMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
        val recent = appDb.readRecentBookDao.recentBooks(recentLimit).map { book ->
            RecentBook(
                name = book.name,
                author = book.author,
                bookUrl = book.bookUrl,
                coverUrl = book.coverUrl,
                readTime = recordMap[book.name]?.readTime ?: 0L,
            )
        }
        val heatmapStart = today.minusDays((heatmapDays - 1).toLong())
        Dashboard(
            today = today,
            todayTime = dailyMap[today] ?: 0L,
            monthTime = daily.filter { YearMonth.from(it.first) == month }.sumOf { it.second },
            totalTime = appDb.readRecordDao.allTime,
            bookCount = appDb.readRecordDao.allShow.size,
            activeDays = daily.count { it.second > 0L },
            daily = daily,
            dailyMap = dailyMap,
            heatmapCells = (0L until heatmapDays.toLong()).map { offset ->
                val date = heatmapStart.plusDays(offset)
                date to (dailyMap[date] ?: 0L)
            },
            recent = recent,
            rankItems = ReadRecordWidgetStore.buildRankItems(),
        )
    }

    /** 清空全部阅读记录（书籍记录 + 每日记录 + 最近阅读 + 小组件快照）。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        appDb.readRecordDao.clear()
        appDb.readRecordDailyDao.clear()
        appDb.readRecentBookDao.clear()
        ReadRecordWidgetStore.clearRecentSnapshots()
    }

    /** 按书名删除单条记录。 */
    suspend fun deleteByName(name: String) = withContext(Dispatchers.IO) {
        appDb.readRecordDao.deleteByName(name)
    }
}
