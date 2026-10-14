package io.legado.app.ui.config

import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.Relayout
import io.legado.app.reader.config.ReadConfigEvent.Typography
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.book.read.config.AdvancedTitleConfigDialog
import io.legado.app.ui.book.read.page.LottieImageBitmapCache
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.readBytes
import io.legado.app.utils.readBytesLimited
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val advancedTitleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

class AdvancedTitleManageActivity : AppCompatActivity(),
    AdvancedTitleConfigDialog.Host {

    internal val entriesState = mutableStateOf<List<AdvancedTitlePackageManager.Entry>>(emptyList())
    internal val activeIdState = mutableStateOf(AdvancedTitlePackageManager.activeId())
    internal val loadingState = mutableStateOf(false)
    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuTarget by mutableStateOf<AdvancedTitlePackageManager.Entry?>(null)
    internal var confirmDeleteTarget by mutableStateOf<AdvancedTitlePackageManager.Entry?>(null)
    internal var importDialogOpen by mutableStateOf(false)
    internal var importUrlText by mutableStateOf("")
    internal var exportUrl by mutableStateOf<String?>(null)

    private var loadJob: Job? = null
    private var loadVersion: Int = 0
    private val importFromNet by lazy { getString(R.string.advanced_title_import_from_net) }

    private val importJson = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.path == "/$importFromNet") {
                openImportDialog()
            } else {
                importUri(uri)
            }
        }
    }

    private val exportJson = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val value = uri.toString()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                exportUrl = value
            } else {
                toastOnUi(R.string.advanced_title_exported)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { AdvancedTitleManageScreen(this) }
        loadEntries()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun loadEntries() {
        loadJob?.cancel()
        val version = ++loadVersion
        loadJob = lifecycleScope.launch {
            loadingState.value = true
            try {
                val entries = AdvancedTitlePackageManager.loadEntries()
                if (version == loadVersion) {
                    entriesState.value = entries
                    activeIdState.value = AdvancedTitlePackageManager.activeId()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (version == loadVersion) toastOnUi(error.localizedMessage)
            } finally {
                if (version == loadVersion) loadingState.value = false
            }
        }
    }

    internal fun entryActions(
        entry: AdvancedTitlePackageManager.Entry
    ): List<Pair<String, () -> Unit>> = buildList {
        if (entry.isUsable) {
            add(getString(R.string.export_str) to { exportEntry(entry) })
        }
        if (!entry.isBuiltin) {
            add(getString(R.string.delete) to { confirmDelete(entry) })
        }
    }

    internal fun openAddMenu() {
        addMenuOpen = true
    }

    internal fun onAddAction(index: Int) {
        when (index) {
            0 -> addFromBuiltin()
            1 -> showImportPicker()
            2 -> openImportDialog()
        }
    }

    private fun showImportPicker() {
        importJson.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.advanced_title_import_title)
            allowExtensions = arrayOf("json", "lottie")
            otherActions = arrayListOf(SelectItem(importFromNet, -1))
        }
    }

    private fun addFromBuiltin() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val builtin = AdvancedTitlePackageManager.builtinEntry()
                    val json = AdvancedTitlePackageManager.readTemplate(builtin)
                    AdvancedTitlePackageManager.addOrUpdate(
                        name = getString(R.string.advanced_title_unnamed),
                        json = json
                    )
                }
            }.onSuccess {
                toastOnUi(R.string.advanced_title_added)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun importUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    uri.readBytes(this@AdvancedTitleManageActivity, AdvancedTitlePackageManager.MAX_JSON_BYTES)
                }
                val name = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    internal fun openImportDialog() {
        importUrlText = ""
        importDialogOpen = true
    }

    internal fun importNetwork(url: String) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { body ->
                        val declared = body.contentLength()
                        require(declared <= AdvancedTitlePackageManager.MAX_JSON_BYTES || declared < 0L) {
                            getString(R.string.advanced_title_too_large)
                        }
                        body.byteStream().readBytesLimited(AdvancedTitlePackageManager.MAX_JSON_BYTES)
                    }
                }
                val name = Uri.parse(url).lastPathSegment
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure {
                toastOnUi(getString(R.string.advanced_title_import_net_failed, it.localizedMessage.orEmpty()))
            }
        }
    }

    internal fun editEntry(entry: AdvancedTitlePackageManager.Entry) {
        if (entry.isBuiltin || !entry.isUsable) {
            if (!entry.isBuiltin) toastOnUi(R.string.advanced_title_invalid_json)
            return
        }
        if (!AdvancedTitlePackageManager.isEditable(entry)) {
            toastOnUi(R.string.large_config_read_only)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.readTemplate(entry) }
            }.onSuccess { json ->
                if (supportFragmentManager.isStateSaved ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
                    supportFragmentManager.findFragmentByTag("advancedTitleEdit") != null
                ) return@onSuccess
                AdvancedTitleConfigDialog.edit(
                    entryId = entry.id,
                    name = entry.name,
                    json = json,
                    splitRule = entry.config.splitRuleOrNull()
                        ?: AdvancedTitleConfig.globalRule,
                    heightFactor = entry.config.normalizedHeightFactorOrNull()
                        ?: AdvancedTitleConfig.heightFactor
                ).show(supportFragmentManager, "advancedTitleEdit")
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    override fun onAdvancedTitleSaved(
        entryId: String,
        name: String,
        json: String,
        splitRule: AdvancedTitleConfig.SplitRule,
        heightFactor: Int
    ) {
        val entry = entriesState.value.firstOrNull { it.id == entryId }
        if (entry == null || entry.isBuiltin || !entry.isUsable) {
            toastOnUi(R.string.error)
            loadEntries()
            return
        }
        saveEditedEntry(entry, name, json, splitRule, heightFactor)
    }

    private fun saveEditedEntry(
        old: AdvancedTitlePackageManager.Entry,
        name: String,
        json: String,
        splitRule: AdvancedTitleConfig.SplitRule,
        heightFactor: Int
    ) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.validateEditableJson(json)
                    val updated = AdvancedTitlePackageManager.addOrUpdate(
                        name = name,
                        json = json,
                        oldEntry = old,
                        splitRule = splitRule,
                        heightFactor = heightFactor
                    )
                    val active = AdvancedTitlePackageManager.activeId() == updated.id
                    if (active) AdvancedTitlePackageManager.apply(updated)
                    updated to active
                }
            }.onSuccess { (_, active) ->
                if (active) notifyReader()
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun exportEntry(entry: AdvancedTitlePackageManager.Entry) {
        if (!entry.isUsable) {
            toastOnUi(R.string.advanced_title_invalid_json)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.readTemplate(entry) }
            }.onSuccess { json ->
                val safeName = entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "advancedTitle" }
                exportJson.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "$safeName.json",
                        json.toByteArray(Charsets.UTF_8),
                        "application/json"
                    )
                }
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun confirmDelete(entry: AdvancedTitlePackageManager.Entry) {
        confirmDeleteTarget = entry
    }

    internal fun deleteEntry(entry: AdvancedTitlePackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.delete(entry) }
            }.onSuccess {
                activeIdState.value = AdvancedTitlePackageManager.activeId()
                notifyReader()
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    internal fun applyEntry(entry: AdvancedTitlePackageManager.Entry) {
        if (!entry.isUsable) {
            toastOnUi(R.string.advanced_title_invalid_json)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.apply(entry) }
            }.onSuccess {
                activeIdState.value = entry.id
                ReadBookConfig.titleMode = AdvancedTitleConfig.TITLE_MODE_ADVANCED
                notifyReader()
                toastOnUi(R.string.success)
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun notifyReader() {
        LottieImageBitmapCache.clear()
        ReadConfigEvent.post(Typography, Relayout)
    }
}

@Composable
private fun AdvancedTitleManageScreen(activity: AdvancedTitleManageActivity) {
    BackHandler { activity.finish() }

    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val entries = activity.entriesState.value
    val activeId = activity.activeIdState.value
    val loading = activity.loadingState.value

    HostV5Page(
        title = stringResource(R.string.advanced_title_manage),
        onBack = { activity.finish() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(
                    if (loading) R.string.loading else R.string.advanced_title_manage_summary
                ),
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = density.screenPadding, top = density.blockGap, end = density.screenPadding),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap,
                    bottom = density.blockGap / 2,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                items(entries, key = { it.id }) { entry ->
                    AdvancedTitleItem(activity, entry, active = entry.id == activeId)
                }
            }
            AppFilledButton(
                text = stringResource(R.string.advanced_title_add),
                onClick = { activity.openAddMenu() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
            )
        }
    }

    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.advanced_title_add),
            actions = listOf(
                stringResource(R.string.advanced_title_add_from_builtin) to { activity.onAddAction(0) },
                stringResource(R.string.advanced_title_import_from_file) to { activity.onAddAction(1) },
                stringResource(R.string.advanced_title_import_from_net) to { activity.onAddAction(2) },
            ),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    activity.itemMenuTarget?.let { entry ->
        AppMenuSheet(
            title = entry.name,
            actions = activity.entryActions(entry),
            onDismiss = { activity.itemMenuTarget = null },
        )
    }

    activity.confirmDeleteTarget?.let { entry ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.sure_del),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.confirmDeleteTarget = null
                activity.deleteEntry(entry)
            },
            onDismiss = { activity.confirmDeleteTarget = null },
        )
    }

    if (activity.importDialogOpen) {
        AppInputDialog(
            title = stringResource(R.string.advanced_title_input_url),
            value = activity.importUrlText,
            onValueChange = { activity.importUrlText = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = "https://...",
            onConfirm = { text ->
                activity.importDialogOpen = false
                text.trim().takeIf { it.isNotEmpty() }?.let(activity::importNetwork)
            },
            onDismiss = { activity.importDialogOpen = false },
        )
    }

    activity.exportUrl?.let { url ->
        AppConfirmDialog(
            title = stringResource(R.string.advanced_title_exported),
            message = url,
            confirmLabel = stringResource(R.string.copy_text),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                activity.sendToClip(url)
                activity.toastOnUi(R.string.copy_complete)
                activity.exportUrl = null
            },
            onDismiss = { activity.exportUrl = null },
        )
    }
}

@Composable
private fun AdvancedTitleItem(
    activity: AdvancedTitleManageActivity,
    entry: AdvancedTitlePackageManager.Entry,
    active: Boolean,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val editable = AdvancedTitlePackageManager.isEditable(entry)
    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 72.dp)
                    .clip(visual.shape(ShapeKind.CARD))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (entry.isUsable) "Lottie" else stringResource(R.string.advanced_title_invalid_json),
                    style = visual.type.caption,
                    color = if (entry.isUsable) colors.onSurfaceVariant else colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = visual.type.body,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildEntryInfo(entry, active),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppChip(
                        text = stringResource(
                            if (active) R.string.advanced_title_applied else R.string.advanced_title_apply
                        ),
                        selected = active,
                        onClick = { if (entry.isUsable && !active) activity.applyEntry(entry) },
                    )
                    if (!entry.isBuiltin) {
                        AppChip(
                            text = stringResource(if (editable) R.string.edit else R.string.read_only),
                            selected = false,
                            onClick = { if (editable) activity.editEntry(entry) },
                        )
                    }
                    AppOverflowMenuButton(
                        contentDescription = stringResource(R.string.more),
                        onClick = { activity.itemMenuTarget = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun buildEntryInfo(
    entry: AdvancedTitlePackageManager.Entry,
    active: Boolean,
): String = buildString {
    if (active) {
        append(stringResource(R.string.advanced_title_current))
        append(" · ")
    }
    append(
        stringResource(
            if (entry.isBuiltin) R.string.advanced_title_source_builtin
            else R.string.advanced_title_source_local
        )
    )
    if (entry.updatedAt > 0L) {
        append(" · ")
        append(advancedTitleDateFormat.format(Date(entry.updatedAt)))
    }
    if (!entry.isUsable) {
        append(" [")
        append(stringResource(R.string.advanced_title_invalid_json))
        append("]")
    }
}
