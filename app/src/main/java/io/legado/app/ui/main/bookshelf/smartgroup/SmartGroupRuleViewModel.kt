package io.legado.app.ui.main.bookshelf.smartgroup

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.SmartGroupEngine
import io.legado.app.help.book.SmartGroupRule
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmartGroupRuleViewModel(application: Application) : BaseViewModel(application) {

    private val _rules = MutableStateFlow<List<SmartGroupRule>>(emptyList())
    val rules = _rules.asStateFlow()

    private val _groups = MutableStateFlow<List<BookGroup>>(emptyList())
    val groups = _groups.asStateFlow()

    private val _autoRun = MutableStateFlow(SmartGroupEngine.autoRunOnShelfRefresh)
    val autoRun = _autoRun.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _editing = MutableStateFlow<SmartGroupRule?>(null)
    val editing = _editing.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        execute {
            withContext(Dispatchers.IO) {
                _rules.value = SmartGroupEngine.rules.sortedBy { it.priority }
                _groups.value = appDb.bookGroupDao.all
            }
        }
    }

    fun startEdit(rule: SmartGroupRule?) {
        _editing.value = rule ?: SmartGroupRule()
    }

    fun save(rule: SmartGroupRule) {
        execute {
            withContext(Dispatchers.IO) {
                SmartGroupEngine.rules =
                    SmartGroupEngine.rules.filterNot { it.id == rule.id } + rule
            }
            _editing.value = null
            loadData()
        }
    }

    fun cancelEdit() {
        _editing.value = null
    }

    fun toggleEnabled(rule: SmartGroupRule) {
        execute {
            withContext(Dispatchers.IO) {
                SmartGroupEngine.rules =
                    SmartGroupEngine.rules.filterNot { it.id == rule.id } +
                        rule.copy(enabled = !rule.enabled)
            }
            loadData()
        }
    }

    fun delete(rule: SmartGroupRule) {
        execute {
            withContext(Dispatchers.IO) {
                SmartGroupEngine.rules = SmartGroupEngine.rules.filterNot { it.id == rule.id }
            }
            loadData()
        }
    }

    fun setAutoRun(enabled: Boolean) {
        SmartGroupEngine.autoRunOnShelfRefresh = enabled
        _autoRun.value = enabled
    }

    fun applyRules() {
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    SmartGroupEngine.applyRules()
                }
            }.onSuccess { changed ->
                context.toastOnUi(context.getString(R.string.smart_group_apply_done, changed))
            }.onFailure {
                context.toastOnUi(it.localizedMessage ?: it.toString())
            }
            _running.value = false
        }
    }

}
