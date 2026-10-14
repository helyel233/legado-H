package io.legado.app.ui.autoTask

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.R
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostSourceLoginActivity
import io.legado.app.utils.startActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.ui.host.setV5Content
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.CronSchedule
import kotlinx.coroutines.launch

class AutoTaskActivity : AppCompatActivity() {

    private val viewModel by viewModels<AutoTaskViewModel>()

    private val rulesState = mutableStateListOf<AutoTaskRule>()
    private val selectedIds = mutableStateOf<Set<String>>(emptySet())
    private val searchQuery = mutableStateOf("")

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    internal var exportResultOpen by mutableStateOf(false)
    internal var exportResultUri by mutableStateOf("")
    internal var deleteSelectedOpen by mutableStateOf(false)
    internal var deleteRuleTarget by mutableStateOf<AutoTaskRule?>(null)
    internal var batchCronOpen by mutableStateOf(false)
    internal var batchCronText by mutableStateOf("")
    internal var onlineImportOpen by mutableStateOf(false)
    internal var onlineImportText by mutableStateOf("")

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri -> showDialogFragment(ImportAutoTaskDialog(uri.toString())) }
    }

    private val exportResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            exportResultUri = uri.toString()
            exportResultOpen = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content {
            AppManagementScaffold(
                title = getString(R.string.auto_task_manage),
                selectedCount = selectedIds.value.size,
                totalCount = filteredRules().size,
                searchQuery = searchQuery.value,
                searchHint = getString(R.string.search),
                onSearchChange = ::updateSearchQuery,
                topActions = listOf(
                    AppManagementAction(
                        text = getString(R.string.auto_task_add),
                        iconRes = R.drawable.ic_add,
                        onClick = { startActivity<AutoTaskEditActivity>() }
                    ),
                    AppManagementAction(
                        text = getString(R.string.more_menu),
                        iconRes = R.drawable.ic_more_vert,
                        menuActions = ::pageMenuActions
                    )
                ),
                bottomActions = listOf(
                    AppManagementAction(
                        text = getString(R.string.enable_selection),
                        onClick = { viewModel.updateEnabled(selectedIds.value, true) }
                    ),
                    AppManagementAction(
                        text = getString(R.string.disable_selection),
                        onClick = { viewModel.updateEnabled(selectedIds.value, false) }
                    ),
                    AppManagementAction(
                        text = getString(R.string.auto_task_batch_cron),
                        onClick = ::showBatchCronDialog
                    ),
                    AppManagementAction(
                        text = getString(R.string.delete),
                        danger = true,
                        onClick = ::deleteSelected
                    ),
                    AppManagementAction(
                        text = getString(R.string.export_selection),
                        onClick = ::exportSelected
                    )
                ),
                onBack = { finish() },
                onSelectAll = { selectAll() },
                onInvertSelection = ::invertSelection
            ) {
                AutoTaskScreen(
                    rules = filteredRules(),
                    emptyText = getString(
                        if (rulesState.isEmpty()) {
                            R.string.auto_task_no_task
                        } else {
                            R.string.auto_task_no_search_result
                        }
                    ),
                    selectedIds = selectedIds.value,
                    isSelectMode = selectedIds.value.isNotEmpty(),
                    reorderEnabled = searchQuery.value.isBlank() && selectedIds.value.isEmpty(),
                    onReorder = viewModel::reorder,
                    onToggleSelection = ::toggleSelection,
                    onToggleEnabled = { rule, enabled ->
                        viewModel.toggleEnabled(rule.id, enabled)
                    },
                    onEdit = { rule ->
                        startActivity<AutoTaskEditActivity> { putExtra(EXTRA_TASK_ID, rule.id) }
                    },
                    onLogin = ::openLogin,
                    onRunNow = { rule -> viewModel.runNow(rule.id) },
                    onShowLog = { rule -> showTaskLog(rule) },
                    onDelete = ::confirmDelete
                )
            }
            if (exportResultOpen) {
                AppConfirmDialog(
                    title = getString(R.string.export_success),
                    message = exportResultUri,
                    confirmLabel = getString(R.string.copy_text),
                    cancelLabel = getString(R.string.ok),
                    onConfirm = {
                        exportResultOpen = false
                        sendToClip(exportResultUri)
                    },
                    onDismiss = { exportResultOpen = false },
                )
            }
            if (deleteSelectedOpen) {
                AppConfirmDialog(
                    title = getString(R.string.delete),
                    message = getString(R.string.sure_del),
                    confirmLabel = getString(R.string.ok),
                    cancelLabel = getString(R.string.cancel),
                    danger = true,
                    onConfirm = { onDeleteSelectedConfirmed() },
                    onDismiss = { deleteSelectedOpen = false },
                )
            }
            deleteRuleTarget?.let { rule ->
                AppConfirmDialog(
                    title = getString(R.string.auto_task_delete),
                    message = rule.name.ifBlank { rule.id },
                    confirmLabel = getString(R.string.delete),
                    cancelLabel = getString(R.string.cancel),
                    danger = true,
                    onConfirm = { onDeleteRuleConfirmed() },
                    onDismiss = { deleteRuleTarget = null },
                )
            }
            if (batchCronOpen) {
                AppInputDialog(
                    title = getString(R.string.auto_task_batch_cron),
                    value = batchCronText,
                    onValueChange = { batchCronText = it },
                    placeholder = getString(R.string.auto_task_cron),
                    confirmLabel = getString(R.string.ok),
                    cancelLabel = getString(R.string.cancel),
                    onConfirm = { onBatchCronConfirm(batchCronText) },
                    onDismiss = { batchCronOpen = false },
                )
            }
            if (onlineImportOpen) {
                AppInputDialog(
                    title = getString(R.string.import_on_line),
                    value = onlineImportText,
                    onValueChange = { onlineImportText = it },
                    placeholder = "https://...",
                    confirmLabel = getString(R.string.ok),
                    cancelLabel = getString(R.string.cancel),
                    onConfirm = { onOnlineImportConfirm(onlineImportText) },
                    onDismiss = { onlineImportOpen = false },
                )
            }
        }
        observeRules()
        supportFragmentManager.setFragmentResultListener(
            ImportAutoTaskDialog.RESULT_KEY,
            this
        ) { _, _ -> viewModel.refresh() }
        viewModel.refresh()
    }

    private fun observeRules() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rulesFlow.collect { rules ->
                    rulesState.clear()
                    rulesState.addAll(rules)
                    val ids = rules.mapTo(HashSet(), AutoTaskRule::id)
                    selectedIds.value = selectedIds.value.filterTo(linkedSetOf()) { it in ids }
                }
            }
        }
    }

    private fun filteredRules(): List<AutoTaskRule> {
        val query = searchQuery.value.trim()
        if (query.isBlank()) return rulesState.toList()
        return rulesState.filter { rule ->
            rule.name.contains(query, ignoreCase = true) ||
                rule.cron.orEmpty().contains(query, ignoreCase = true) ||
                rule.lastError.orEmpty().contains(query, ignoreCase = true) ||
                rule.comment.orEmpty().contains(query, ignoreCase = true)
        }
    }

    private fun updateSearchQuery(query: String) {
        searchQuery.value = query
        selectedIds.value = emptySet()
    }

    private fun toggleSelection(rule: AutoTaskRule) {
        selectedIds.value = selectedIds.value.toMutableSet().apply {
            if (!add(rule.id)) remove(rule.id)
        }
    }

    private fun selectAll() {
        selectedIds.value = filteredRules().mapTo(linkedSetOf(), AutoTaskRule::id)
    }

    private fun invertSelection() {
        val current = selectedIds.value
        selectedIds.value = filteredRules()
            .mapNotNullTo(linkedSetOf()) { rule -> rule.id.takeUnless { it in current } }
    }

    private fun deleteSelected() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        deleteSelectedOpen = true
    }

    private fun confirmDelete(rule: AutoTaskRule) {
        deleteRuleTarget = rule
    }

    internal fun onDeleteSelectedConfirmed() {
        deleteSelectedOpen = false
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModel.delete(ids)
        selectedIds.value = emptySet()
    }

    internal fun onDeleteRuleConfirmed() {
        val rule = deleteRuleTarget ?: return
        deleteRuleTarget = null
        viewModel.delete(listOf(rule.id))
    }

    private fun showBatchCronDialog() {
        batchCronText = ""
        batchCronOpen = true
    }

    internal fun onBatchCronConfirm(cron: String) {
        batchCronOpen = false
        val trimmed = cron.trim()
        if (CronSchedule.parse(trimmed) == null) {
            toastOnUi(R.string.auto_task_cron_invalid)
        } else {
            viewModel.updateCron(selectedIds.value, trimmed)
            selectedIds.value = emptySet()
        }
    }

    private fun showTaskLog(rule: AutoTaskRule) {
        val content = rule.lastLog
            ?.takeIf { it.isNotBlank() }
            ?: rule.lastError
            ?: getString(R.string.auto_task_not_run)
        showDialogFragment(TextDialog(rule.name.ifBlank { rule.id }, content))
    }

    private fun openLogin(rule: AutoTaskRule) {
        if (rule.loginUrl.isNullOrBlank() && rule.loginUi.isNullOrBlank()) {
            toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<HostSourceLoginActivity> {
            val sourceKey = AutoTask.sourceKey(rule.id)
            putExtra("type", AutoTask.SOURCE_TYPE)
            putExtra("key", sourceKey)
            putExtra("taskId", rule.id)
            putExtra(AutoTask.EXTRA_TASK_ID, rule.id)
            putExtra(AutoTask.EXTRA_SOURCE_KEY, sourceKey)
        }
    }

    private fun pageMenuActions(): List<AppManagementMenuAction> = listOf(
        AppManagementMenuAction(getString(R.string.import_local)) {
            importDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_auto_task)
                allowExtensions = arrayOf("json", "txt")
            }
        },
        AppManagementMenuAction(getString(R.string.import_on_line)) {
            showOnlineImportDialog()
        },
        AppManagementMenuAction(getString(R.string.export_all)) {
            exportRules(rulesState.toList(), "autoTask.json")
        },
        AppManagementMenuAction(getString(R.string.log)) {
            val content = rulesState.joinToString("\n\n") { rule ->
                val log = rule.lastLog ?: rule.lastError ?: getString(R.string.auto_task_not_run)
                "${rule.name.ifBlank { rule.id }}\n$log"
            }.ifBlank { getString(R.string.auto_task_no_task) }
            showDialogFragment(TextDialog(getString(R.string.log), content))
        },
        AppManagementMenuAction(getString(R.string.help)) {
            showHelp("autoTaskHelp")
        }
    )

    private fun showOnlineImportDialog() {
        onlineImportText = ""
        onlineImportOpen = true
    }

    internal fun onOnlineImportConfirm(value: String) {
        onlineImportOpen = false
        val url = value.trim()
        if (url.isAbsUrl()) {
            showDialogFragment(ImportAutoTaskDialog(url))
        } else {
            toastOnUi(R.string.wrong_format)
        }
    }

    private fun exportSelected() {
        val selected = rulesState.filter { it.id in selectedIds.value }
        exportRules(selected, "autoTaskSelection.json")
    }

    private fun exportRules(rules: List<AutoTaskRule>, fileName: String) {
        if (rules.isEmpty()) {
            toastOnUi(R.string.auto_task_import_select_none)
            return
        }
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                fileName,
                io.legado.app.model.AutoTaskImport.exportJson(rules)
                    .toByteArray(Charsets.UTF_8),
                "application/json"
            )
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "id"
    }
}
