package io.legado.app.ui.main.readrecord

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.report.YearlyReportBuilder
import io.legado.app.help.report.YearlyReportData
import io.legado.app.help.report.YearlyReportImageRenderer
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.time.LocalDate

class YearlyReportViewModel(application: Application) : BaseViewModel(application) {

    private val _year = MutableStateFlow(LocalDate.now().year)
    val year: StateFlow<Int> = _year.asStateFlow()

    private val _data = MutableStateFlow<YearlyReportData?>(null)
    val data: StateFlow<YearlyReportData?> = _data.asStateFlow()

    private val _bookMap = MutableStateFlow<Map<String, Book>>(emptyMap())
    val bookMap: StateFlow<Map<String, Book>> = _bookMap.asStateFlow()

    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    init {
        loadData()
    }

    fun switchYear(year: Int) {
        if (year < 2000 || year > LocalDate.now().year) return
        _year.value = year
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            runCatching {
                val report = YearlyReportBuilder.build(_year.value)
                val urlSet = report.topBooks.map { it.bookUrl }.filter { it.isNotBlank() }.toSet()
                val map = if (urlSet.isEmpty()) {
                    emptyMap()
                } else {
                    appDb.bookDao.all.associateBy { it.bookUrl }.filterKeys { it in urlSet }
                }
                withContext(Dispatchers.Main) {
                    _data.value = report
                    _bookMap.value = map
                }
            }.onFailure {
                AppLog.put("年度报告数据加载失败", it)
            }
        }
    }

    fun getBook(bookUrl: String): Book? = _bookMap.value[bookUrl]

    fun shareReport() {
        val report = _data.value ?: return
        if (_sharing.value) return
        _sharing.value = true
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = YearlyReportImageRenderer.render(appCtx, report)
                    appCtx.share(file, "image/png")
                }
            }.onFailure {
                AppLog.put("年度报告分享失败", it)
                appCtx.toastOnUi(appCtx.getString(R.string.yearly_report_share_failed))
            }
            _sharing.value = false
        }
    }
}
