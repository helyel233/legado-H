package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.book.highlight.HighlightImport
import io.legado.app.help.book.highlight.HighlightMatcher
import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.help.book.highlight.HighlightRuleSearch
import io.legado.app.help.book.highlight.HighlightRules
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class HighlightRuleManageActivity : AppCompatActivity() {

    internal var allRules by mutableStateOf<List<HighlightRule>>(emptyList())
    internal var visibleRules by mutableStateOf<List<HighlightRule>>(emptyList())
    internal var query by mutableStateOf("")
    internal var loaded by mutableStateOf(false)
    internal var loading by mutableStateOf(true)
    internal var busy by mutableStateOf(false)
    internal var dragging by mutableStateOf(false)
    internal var summaryText by mutableStateOf("")
    internal var emptyText by mutableStateOf("")

    internal var addMenuOpen by mutableStateOf(false)
    internal var trailingMenuOpen by mutableStateOf(false)
    internal var menuRule by mutableStateOf<HighlightRule?>(null)
    internal var scopeRequest by mutableStateOf<HighlightRule?>(null)
    internal var exportMenuOpen by mutableStateOf(false)
    internal var noticeRule by mutableStateOf<HighlightRule?>(null)
    internal var helpOpen by mutableStateOf(false)
    internal var importReport by mutableStateOf<String?>(null)
    internal var uploadResult by mutableStateOf<String?>(null)
    internal var deleteRequest by mutableStateOf<HighlightRule?>(null)

    private var bookUrl: String? = null
    private var orderChanged = false
    private var loadVersion = 0
    private var loadJob: Job? = null

    internal val canInteract: Boolean
        get() = loaded && !loading && !busy && !dragging

    internal val bookUrlForPreview: String? get() = bookUrl

    private val importFile = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let(::importUri)
    }
    private val exportFile = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.scheme == "http" || uri.scheme == "https") {
                uploadResult = uri.toString()
            } else toastOnUi(R.string.export_success)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookUrl = intent.getStringExtra("bookUrl") ?: ReadBook.book?.bookUrl
        query = savedInstanceState?.getString("query").orEmpty()
        setV5Content { HighlightRuleManageContent(this@HighlightRuleManageActivity) }
        if (savedInstanceState == null) intent.data?.let(::importUri)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    internal fun onQueryChanged(value: String) {
        query = value
        if (!dragging && !busy) {
            renderRules()
        }
    }

    internal fun launchExportTrailing() {
        trailingMenuOpen = false
        if (canInteract) exportCurrentRules()
    }

    internal fun launchAssetManage() {
        trailingMenuOpen = false
        startActivity<ReaderAssetManageActivity>()
    }

    internal fun showHelp() {
        trailingMenuOpen = false
        helpOpen = true
    }

    private fun pickFile() {
        if (!canInteract) return
        importFile.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.highlight_rule_manage)
            allowExtensions = arrayOf("red")
        }
    }

    internal fun onAddAction(index: Int) {
        addMenuOpen = false
        when (index) {
            0 -> edit(null)
            1 -> pickFile()
        }
    }

    private fun importUri(uri: Uri) {
        work {
            val report = withContext(Dispatchers.IO) { HighlightImport.importUri(uri, bookUrl) }
            importReport = report.summary()
        }
    }

    internal fun edit(rule: HighlightRule?) {
        if (!canInteract) return
        startActivity<HighlightRuleEditActivity> {
            rule?.let { putExtra("id", it.id) }
            bookUrl?.let { putExtra("bookUrl", it) }
        }
    }

    internal fun toggle(rule: HighlightRule) {
        if (!canInteract) return
        val bindHint = getString(R.string.highlight_rule_bind_hint)
        work { withContext(Dispatchers.IO) {
            HighlightRules.store.update(rule.id) { current ->
                if (!current.enabled) {
                    HighlightMatcher.validationError(current)?.let { error(it) }
                    require(current.global || current.bookUrl != null) { bindHint }
                }
                current.copy(enabled = !current.enabled)
            }
        } }
    }

    internal fun onScopeSelected(global: Boolean) {
        val rule = scopeRequest ?: return
        scopeRequest = null
        if (!global && bookUrl == null) {
            toastOnUi(R.string.highlight_rule_bind_hint)
            return
        }
        work { withContext(Dispatchers.IO) {
            HighlightRules.store.update(rule.id) { current ->
                current.copy(global = global, bookUrl = if (global) null else bookUrl)
            }
        } }
    }

    internal fun exportRules(rules: List<HighlightRule>) {
        if (!canInteract || rules.isEmpty()) return
        work(changesRules = false) {
            val bytes = withContext(Dispatchers.IO) { HighlightRules.store.export(rules) }
            exportFile.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData("高亮规则.red", bytes, "application/octet-stream")
            }
        }
    }

    internal fun exportCurrentRules() {
        if (!canInteract || allRules.isEmpty()) return
        if (query.isBlank()) {
            exportRules(allRules)
            return
        }
        exportMenuOpen = true
    }

    internal fun onExportSelected(index: Int) {
        exportMenuOpen = false
        if (index == 0) {
            exportRules(allRules)
        } else if (visibleRules.isEmpty()) {
            toastOnUi(R.string.highlight_rule_search_empty)
        } else {
            exportRules(visibleRules)
        }
    }

    internal fun onDeleteConfirmed() {
        val rule = deleteRequest ?: return
        deleteRequest = null
        work {
            withContext(Dispatchers.IO) {
                SourceRecycleBinHelp.recycleHighlightRules(listOf(rule))
                HighlightRules.store.delete(rule.id)
            }
        }
    }

    internal fun work(changesRules: Boolean = true, block: suspend () -> Unit) {
        if (busy || dragging) return
        busy = true
        cancelLoad()
        lifecycleScope.launch {
            try {
                block()
                if (changesRules) setResult(RESULT_OK)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage ?: getString(R.string.wrong_format))
            } finally {
                try {
                    // Keep actions blocked until the list reflects the completed write.
                    if (isActive) acceptRules(withContext(Dispatchers.IO) { HighlightRules.store.all() })
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    toastOnUi(error.localizedMessage)
                } finally {
                    busy = false
                }
            }
        }
    }

    private fun load() {
        if (busy || dragging) return
        cancelLoad()
        loading = true
        val version = loadVersion
        loadJob = lifecycleScope.launch {
            try {
                val rules = withContext(Dispatchers.IO) { HighlightRules.store.all() }
                if (version == loadVersion) acceptRules(rules)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            } finally {
                if (version == loadVersion) {
                    loading = false
                    renderRules()
                }
            }
        }
    }

    private fun cancelLoad() {
        loadVersion++
        loadJob?.cancel()
        loadJob = null
        loading = false
    }

    private fun acceptRules(rules: List<HighlightRule>) {
        allRules = rules
        loaded = true
        renderRules()
    }

    internal fun renderRules() {
        if (isDestroyed || dragging) return
        val visible = HighlightRuleSearch.filter(allRules, query)
        visibleRules = visible
        summaryText = if (query.isBlank()) {
            getString(R.string.highlight_rule_summary, allRules.size, allRules.count { it.enabled })
        } else {
            getString(R.string.highlight_rule_search_summary, visible.size, allRules.size)
        }
        emptyText = getString(when {
            !loaded && loading -> R.string.highlight_rule_loading
            !loaded -> R.string.highlight_rule_load_failed
            query.isNotBlank() -> R.string.highlight_rule_search_empty
            else -> R.string.highlight_rule_empty
        })
    }

    internal fun moveRule(fromIndex: Int, toIndex: Int) {
        if (busy || loading) return
        if (fromIndex !in visibleRules.indices || toIndex !in visibleRules.indices) return
        if (fromIndex == toIndex) return
        visibleRules = visibleRules.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        orderChanged = true
    }

    internal fun finishDrag() {
        dragging = false
        if (!orderChanged) {
            load()
            return
        }
        orderChanged = false
        val ids = visibleRules.map { it.id }
        work { withContext(Dispatchers.IO) { HighlightRules.store.reorder(ids) } }
    }

    internal fun launchImport() {
        trailingMenuOpen = false
        if (canInteract) pickFile()
    }
}

@Composable
private fun HighlightRuleManageContent(activity: HighlightRuleManageActivity) {
    val density = LocalVisualSpec.current.density
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        activity.moveRule(from.index, to.index)
    }

    HostV5Page(
        title = stringResource(R.string.highlight_rule_manage),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.trailingMenuOpen = true },
            )
        },
        bottomBar = {
            AppFilledButton(
                text = stringResource(R.string.add),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 2,
                    ),
                enabled = activity.canInteract,
                onClick = { activity.addMenuOpen = true },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTextField(
                value = activity.query,
                onValueChange = { activity.onQueryChanged(it) },
                modifier = Modifier.padding(horizontal = density.screenPadding),
                placeholder = stringResource(R.string.search),
                singleLine = true,
            )
            if (activity.summaryText.isNotEmpty()) {
                Text(
                    text = activity.summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 2,
                    ),
                )
            }
            Spacer(Modifier.height(density.blockGap / 2))
            if (activity.visibleRules.isEmpty()) {
                AppEmpty(
                    title = activity.emptyText,
                    supporting = if (activity.loading) null else stringResource(R.string.highlight_rule_manage),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 4,
                    ),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    items(activity.visibleRules, key = { it.id }) { rule ->
                        ReorderableItem(reorderState, key = rule.id) {
                            HighlightRuleRow(
                                activity,
                                rule,
                                Modifier.draggableHandle(onDragStopped = { activity.finishDrag() }),
                            )
                        }
                    }
                }
            }
        }
    }

    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.highlight_rule_manage),
            actions = listOf(
                stringResource(R.string.add) to { activity.onAddAction(0) },
                stringResource(R.string.import_str) to { activity.onAddAction(1) },
            ),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    if (activity.trailingMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.highlight_rule_manage),
            actions = listOf(
                stringResource(R.string.import_str) to { activity.launchImport() },
                stringResource(R.string.export_str) to { activity.launchExportTrailing() },
                stringResource(R.string.help) to { activity.showHelp() },
                stringResource(R.string.reader_assets_title) to { activity.launchAssetManage() },
            ),
            onDismiss = { activity.trailingMenuOpen = false },
        )
    }

    activity.menuRule?.let { rule ->
        AppMenuSheet(
            title = rule.displayName(),
            actions = listOf(
                stringResource(R.string.edit) to {
                    activity.menuRule = null
                    activity.edit(rule)
                },
                stringResource(R.string.highlight_rule_scope) to {
                    activity.menuRule = null
                    activity.scopeRequest = rule
                },
                stringResource(R.string.export_str) to {
                    activity.menuRule = null
                    activity.exportRules(listOf(rule))
                },
                stringResource(R.string.highlight_rule_notice) to {
                    activity.menuRule = null
                    activity.noticeRule = rule
                },
                stringResource(R.string.delete) to {
                    activity.menuRule = null
                    activity.deleteRequest = rule
                },
            ),
            onDismiss = { activity.menuRule = null },
        )
    }

    activity.scopeRequest?.let { rule ->
        AppFilterDialog(
            title = stringResource(R.string.highlight_rule_scope),
            options = listOf(
                stringResource(R.string.highlight_rule_global),
                stringResource(R.string.highlight_rule_this_book)
            ),
            selectedIndex = if (rule.global) 0 else 1,
            onSelect = { activity.onScopeSelected(it == 0) },
            onDismiss = { activity.scopeRequest = null },
        )
    }

    if (activity.exportMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.export_str),
            actions = listOf(
                stringResource(R.string.highlight_rule_export_all, activity.allRules.size) to {
                    activity.onExportSelected(0)
                },
                stringResource(R.string.highlight_rule_export_results, activity.visibleRules.size) to {
                    activity.onExportSelected(1)
                },
            ),
            onDismiss = { activity.exportMenuOpen = false },
        )
    }

    activity.noticeRule?.let { rule ->
        AppConfirmDialog(
            title = rule.displayName(),
            message = listOfNotNull(
                rule.importWarning.takeIf { it.isNotBlank() },
                HighlightMatcher.validationError(rule),
                stringResource(R.string.highlight_rule_render_hint)
            ).joinToString("\n\n"),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.noticeRule = null },
            onDismiss = { activity.noticeRule = null },
        )
    }

    if (activity.helpOpen) {
        AppConfirmDialog(
            title = stringResource(R.string.highlight_rule_manage),
            message = stringResource(R.string.highlight_rule_help) + "\n\n" +
                stringResource(R.string.highlight_rule_render_hint),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.helpOpen = false },
            onDismiss = { activity.helpOpen = false },
        )
    }

    activity.importReport?.let { report ->
        AppConfirmDialog(
            title = stringResource(R.string.import_str),
            message = report,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.importReport = null },
            onDismiss = { activity.importReport = null },
        )
    }

    activity.uploadResult?.let { value ->
        AppConfirmDialog(
            title = stringResource(R.string.upload_url),
            message = value,
            confirmLabel = stringResource(R.string.copy_text),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                activity.uploadResult = null
                activity.sendToClip(value)
                activity.toastOnUi(R.string.copy_complete)
            },
            onDismiss = { activity.uploadResult = null },
        )
    }

    activity.deleteRequest?.let { rule ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.sure_del) + "\n" + rule.displayName(),
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteConfirmed() },
            onDismiss = { activity.deleteRequest = null },
        )
    }
}

@Composable
private fun HighlightRuleRow(
    activity: HighlightRuleManageActivity,
    rule: HighlightRule,
    dragHandleModifier: Modifier,
) {
    val bookUrl = activity.bookUrlForPreview
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { activity.menuRule = rule },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.sort),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            val scope = when {
                rule.global -> stringResource(R.string.highlight_rule_global)
                rule.bookUrl == bookUrl && bookUrl != null -> stringResource(R.string.highlight_rule_this_book)
                rule.bookUrl == null -> stringResource(R.string.highlight_rule_unbound)
                else -> stringResource(R.string.highlight_rule_other_book)
            }
            Text(
                text = listOf(
                    rule.groupName,
                    scope,
                    if (rule.titleOnly) stringResource(R.string.highlight_rule_title) else "",
                    if (rule.importWarning.isNotBlank()) stringResource(R.string.highlight_rule_has_notice) else ""
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppChip(
                    text = stringResource(if (rule.enabled) R.string.disable else R.string.enable),
                    selected = rule.enabled,
                    onClick = { activity.toggle(rule) },
                )
                AppChip(
                    text = stringResource(R.string.edit),
                    selected = false,
                    onClick = { activity.edit(rule) },
                )
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.more),
                    onClick = { activity.menuRule = rule },
                )
            }
        }
    }
}
