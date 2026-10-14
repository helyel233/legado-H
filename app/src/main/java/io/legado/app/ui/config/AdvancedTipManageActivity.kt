package io.legado.app.ui.config

import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.HeaderFooterTips
import io.legado.app.reader.config.ReadConfigEvent.TipStyle
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
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import io.legado.app.help.CacheManager
import io.legado.app.ui.code.CodeEditActivity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.config.AdvancedTipSlot
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.AdvancedTipPackageManager
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.lib.dialogs.SelectItem
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
import io.legado.app.utils.postEvent
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

private val advancedTipDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

class AdvancedTipManageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLOT = "slot"

        fun start(context: Context, slot: AdvancedTipSlot) {
            context.startActivity(
                Intent(context, AdvancedTipManageActivity::class.java)
                    .putExtra(EXTRA_SLOT, slot.name)
            )
        }
    }

    internal val slot: AdvancedTipSlot by lazy {
        val raw = intent.getStringExtra(EXTRA_SLOT).orEmpty()
        runCatching { AdvancedTipSlot.valueOf(raw) }.getOrDefault(AdvancedTipSlot.HEADER)
    }
    private val manager: AdvancedTipPackageManager by lazy { AdvancedTipPackageManager.of(slot) }

    internal val entriesState = mutableStateOf<List<AdvancedTipPackageManager.Entry>>(emptyList())
    internal val activeIdState = mutableStateOf(AdvancedTipPackageManager.BUILTIN_ID)
    internal val loadingState = mutableStateOf(false)
    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuTarget by mutableStateOf<AdvancedTipPackageManager.Entry?>(null)
    internal var confirmDeleteTarget by mutableStateOf<AdvancedTipPackageManager.Entry?>(null)
    internal var importDialogOpen by mutableStateOf(false)
    internal var importUrlText by mutableStateOf("")
    internal var exportUrl by mutableStateOf<String?>(null)
    internal var renameTarget by mutableStateOf<AdvancedTipPackageManager.Entry?>(null)
    internal var renameText by mutableStateOf("")

    private var loadJob: Job? = null
    private var loadVersion: Int = 0
    private var editingEntryId: String? = null

    private val jsonEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            editingEntryId = null
            return@registerForActivityResult
        }
        val data = result.data
        val entryId = editingEntryId
        editingEntryId = null
        if (entryId == null || data == null) return@registerForActivityResult
        val cacheKey = data.getStringExtra("cacheKey")
        val text = if (cacheKey != null) {
            CacheManager.getFromMemory(cacheKey) as? String
        } else {
            data.getStringExtra("text")
        } ?: return@registerForActivityResult
        saveEditedJson(entryId, text)
    }
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
        activeIdState.value = manager.activeId()
        setV5Content { AdvancedTipManageScreen(this) }
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
                val entries = manager.loadEntries()
                if (version == loadVersion) {
                    entriesState.value = entries
                    activeIdState.value = manager.activeId()
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
        entry: AdvancedTipPackageManager.Entry
    ): List<Pair<String, () -> Unit>> = buildList {
        if (!entry.isBuiltin && entry.isUsable) {
            add(getString(R.string.advanced_title_open_editor) to { editJsonEntry(entry) })
            add(getString(R.string.advanced_title_name) to { openRenameDialog(entry) })
        }
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
                    val builtin = manager.builtinEntry()
                    val json = manager.readTemplate(builtin)
                    manager.addOrUpdate(
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
                    uri.readBytes(this@AdvancedTipManageActivity, AdvancedTipPackageManager.MAX_JSON_BYTES)
                }
                val name = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    manager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    internal fun openImportDialog() {
        importUrlText = "https://skybook.qzz.io/file/json/19hMepHey95bDYaFXJfq89.json"
        importDialogOpen = true
    }

    internal fun importNetwork(url: String) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { body ->
                        val declared = body.contentLength()
                        require(declared <= AdvancedTipPackageManager.MAX_JSON_BYTES || declared < 0L) {
                            getString(R.string.advanced_title_too_large)
                        }
                        body.byteStream().readBytesLimited(AdvancedTipPackageManager.MAX_JSON_BYTES)
                    }
                }
                val name = Uri.parse(url).lastPathSegment
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    manager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure {
                toastOnUi(getString(R.string.advanced_title_import_net_failed, it.localizedMessage.orEmpty()))
            }
        }
    }


    internal fun openRenameDialog(entry: AdvancedTipPackageManager.Entry) {
        if (entry.isBuiltin || !entry.isUsable) {
            if (!entry.isBuiltin) toastOnUi(R.string.advanced_title_invalid_json)
            return
        }
        renameTarget = entry
        renameText = entry.name
    }

    internal fun confirmRename(entry: AdvancedTipPackageManager.Entry, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            toastOnUi(R.string.advanced_title_name_required)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val json = manager.readTemplate(entry)
                    val updated = manager.addOrUpdate(name, json, entry)
                    if (manager.activeId() == updated.id) manager.apply(updated)
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun editJsonEntry(entry: AdvancedTipPackageManager.Entry) {
        if (entry.isBuiltin || !entry.isUsable) {
            if (!entry.isBuiltin) {
                toastOnUi(R.string.advanced_title_invalid_json)
                return
            }
            toastOnUi(R.string.read_only)
            return
        }
        if (!manager.isEditable(entry)) {
            toastOnUi(R.string.advanced_title_json_too_large_to_edit)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.readTemplate(entry) }
            }.onSuccess { json ->
                if (supportFragmentManager.isStateSaved ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) return@onSuccess
                val bytes = json.toByteArray(Charsets.UTF_8).size.toLong()
                if (bytes > AdvancedTipPackageManager.MAX_EDITABLE_JSON_BYTES) {
                    toastOnUi(R.string.advanced_title_json_too_large_to_edit)
                    return@onSuccess
                }
                editingEntryId = entry.id
                val key = "advanced_tip_edit_" + System.nanoTime()
                CacheManager.putMemory(key, json)
                jsonEditor.launch(
                    Intent(this@AdvancedTipManageActivity, CodeEditActivity::class.java).apply {
                        putExtra("cacheKey", key)
                        putExtra("writable", true)
                        putExtra("title", getString(R.string.advanced_title_json_label))
                        putExtra("cursorPosition", 0)
                    }
                )
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun saveEditedJson(entryId: String, json: String) {
        val entry = entriesState.value.firstOrNull { it.id == entryId }
        if (entry == null || entry.isBuiltin || !entry.isUsable) {
            toastOnUi(R.string.error)
            loadEntries()
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    manager.validateEditableJson(json)
                    val updated = manager.addOrUpdate(
                        name = entry.name,
                        json = json,
                        oldEntry = entry
                    )
                    if (manager.activeId() == updated.id) manager.apply(updated)
                    updated
                }
            }.onSuccess {
                activeIdState.value = manager.activeId()
                notifyReader()
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun exportEntry(entry: AdvancedTipPackageManager.Entry) {
        if (!entry.isUsable) {
            toastOnUi(R.string.advanced_title_invalid_json)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.readTemplate(entry) }
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

    private fun confirmDelete(entry: AdvancedTipPackageManager.Entry) {
        confirmDeleteTarget = entry
    }

    internal fun deleteEntry(entry: AdvancedTipPackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.delete(entry) }
            }.onSuccess {
                activeIdState.value = manager.activeId()
                notifyReader()
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    internal fun applyEntry(entry: AdvancedTipPackageManager.Entry) {
        if (!entry.isUsable) {
            toastOnUi(R.string.advanced_title_invalid_json)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.apply(entry) }
            }.onSuccess {
                activeIdState.value = entry.id
                when (slot) {
                    AdvancedTipSlot.HEADER -> ReadTipConfig.headerMode = ReadTipConfig.HEADER_MODE_ADVANCED
                    AdvancedTipSlot.FOOTER -> ReadTipConfig.footerMode = ReadTipConfig.FOOTER_MODE_ADVANCED
                }
                notifyReader()
                toastOnUi(R.string.success)
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun notifyReader() {
        LottieImageBitmapCache.clear()
        ReadConfigEvent.post(HeaderFooterTips, TipStyle)
    }
}

@Composable
private fun AdvancedTipManageScreen(activity: AdvancedTipManageActivity) {
    BackHandler { activity.finish() }

    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val entries = activity.entriesState.value
    val activeId = activity.activeIdState.value
    val loading = activity.loadingState.value

    HostV5Page(
        title = stringResource(activity.slot.manageTitleRes),
        onBack = { activity.finish() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(if (loading) R.string.loading else activity.slot.manageSummaryRes),
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
                    AdvancedTipItem(activity, entry, active = entry.id == activeId)
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

    activity.renameTarget?.let { entry ->
        AppInputDialog(
            title = stringResource(R.string.advanced_title_name),
            value = activity.renameText,
            onValueChange = { activity.renameText = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = stringResource(R.string.advanced_title_name),
            onConfirm = { text ->
                activity.renameTarget = null
                activity.confirmRename(entry, text)
            },
            onDismiss = { activity.renameTarget = null },
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
private fun AdvancedTipItem(
    activity: AdvancedTipManageActivity,
    entry: AdvancedTipPackageManager.Entry,
    active: Boolean,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
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
    entry: AdvancedTipPackageManager.Entry,
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
        append(advancedTipDateFormat.format(Date(entry.updatedAt)))
    }
    if (!entry.isUsable) {
        append(" [")
        append(stringResource(R.string.advanced_title_invalid_json))
        append("]")
    }
}
