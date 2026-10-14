// __P1__

package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.drawable.toBitmap
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadMenuButtonConfig
import io.legado.app.ui.book.read.ReadMenuButtonIconHelper
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

// __P2__

class ReadMenuButtonManageActivity : AppCompatActivity() {

    internal var layout by mutableStateOf(ReadMenuButtonConfig.defaultLayout())
    internal var rowIndex by mutableStateOf(0)
    internal var customButtons by mutableStateOf<Map<Long, ReadMenuCustomButton>>(emptyMap())
    internal var rows by mutableStateOf<List<ReadMenuButtonConfig.ButtonRef>>(emptyList())
    internal var rowOrderDirty by mutableStateOf(false)

    internal var addMenuOpen by mutableStateOf(false)
    internal var importMenuOpen by mutableStateOf(false)
    internal var importUrlOpen by mutableStateOf(false)
    internal var overflowMenuOpen by mutableStateOf(false)
    internal var existingCustomTarget by mutableStateOf<ReadMenuCustomButton?>(null)
    internal var iconMenuRef by mutableStateOf<ReadMenuButtonConfig.ButtonRef?>(null)
    internal var deleteRef by mutableStateOf<ReadMenuButtonConfig.ButtonRef?>(null)
    internal var deleteCustomTarget by mutableStateOf<ReadMenuCustomButton?>(null)
    internal var resetConfirmOpen by mutableStateOf(false)
    internal var uploadUrl by mutableStateOf<String?>(null)

    private var pendingIconRequest: IconRequest? = null

    private val editCustomButton =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val id = result.data?.getLongExtra("id", 0L)?.takeIf { it > 0 }
                    ?: return@registerForActivityResult
                addButton(ReadMenuButtonConfig.ButtonRef(ReadMenuButtonConfig.TYPE_CUSTOM, id.toString()))
            } else {
                load()
            }
        }

    private val selectIconFile = registerForActivityResult(HandleFileContract()) { result ->
        val request = pendingIconRequest?.takeIf { it.requestCode == result.requestCode }
            ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    ReadMenuButtonIconHelper.saveIcon(this@ReadMenuButtonManageActivity, uri, request.oldPath())
                }
            }.onSuccess { path ->
                updateButtonRef(request.ref, request.ref.withIconPath(request.nightIcon, path))
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
            pendingIconRequest = null
        }
    }

    private val importCustomButton = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    parseImportedButtons(uri.readText(this@ReadMenuButtonManageActivity))
                }.onSuccess { buttons ->
                    importButtons(buttons)
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    private val exportCustomButton = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val url = uri.toString()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                uploadUrl = url
            } else {
                toastOnUi(R.string.export_success)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { ReadMenuButtonManageScreen(this@ReadMenuButtonManageActivity) }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        layout = ReadMenuButtonConfig.load(this)
        customButtons = appDb.readMenuCustomButtonDao.all().associateBy { it.id }
        rows = currentRow()
        rowOrderDirty = false
    }

    private fun currentRow(): List<ReadMenuButtonConfig.ButtonRef> {
        return if (rowIndex == 0) layout.firstRow else layout.secondRow
    }

    private fun saveCurrentRow(row: List<ReadMenuButtonConfig.ButtonRef>) {
        layout = if (rowIndex == 0) layout.copy(firstRow = row) else layout.copy(secondRow = row)
        ReadMenuButtonConfig.save(this, layout)
        notifyReadMenuChanged()
        rows = currentRow()
    }

    private fun saveLayout(newLayout: ReadMenuButtonConfig.ButtonLayout) {
        layout = newLayout
        ReadMenuButtonConfig.save(this, layout)
        notifyReadMenuChanged()
        rows = currentRow()
    }

    private fun notifyReadMenuChanged() {
        postEvent(EventBus.READ_MENU_BUTTON_CHANGED, true)
    }

    internal fun onRowSelected(index: Int) {
        if (rowIndex != index) {
            rowIndex = index
            load()
        }
    }

    internal fun addButtonActions(): List<Pair<String, () -> Unit>> {
        val usedIds = (layout.firstRow + layout.secondRow)
            .filter { it.type == ReadMenuButtonConfig.TYPE_BUILTIN }
            .map { it.id }.toSet()
        val usedCustomIds = (layout.firstRow + layout.secondRow)
            .filter { it.type == ReadMenuButtonConfig.TYPE_CUSTOM }
            .mapNotNull { it.id.toLongOrNull() }.toSet()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        builtinCandidates().filterNot { it.id in usedIds }.forEach { ref ->
            actions.add(buttonTitle(ref) to { addButton(ref) })
        }
        customButtons.values.forEach { button ->
            val usedMark = if (button.id in usedCustomIds) " (${getString(R.string.theme_source_using)})" else ""
            actions.add(
                "${getString(R.string.read_menu_existing_custom_button)}：${button.displayName()}$usedMark  \u22EE" to {
                    showExistingCustomButtonActions(button)
                }
            )
        }
        actions.add(getString(R.string.read_menu_create_custom_button) to { openCustomButtonEdit() })
        actions.add(getString(R.string.import_str) to { importMenuOpen = true })
        return actions
    }

    internal fun onAddAction() {
        val candidates = addButtonActions()
        if (candidates.isEmpty()) {
            toastOnUi(R.string.read_menu_no_available_button)
            return
        }
        addMenuOpen = true
    }

    internal fun customButtonRefOf(button: ReadMenuCustomButton): ReadMenuButtonConfig.ButtonRef {
        return customButtonRef(button)
    }

    internal fun addButtonOf(ref: ReadMenuButtonConfig.ButtonRef) {
        addButton(ref)
    }

    internal fun buttonIconDrawable(ref: ReadMenuButtonConfig.ButtonRef) =
        ReadMenuButtonIconHelper.drawable(
            this,
            ref,
            buttonIconRes(ref),
            ref.id.toLongOrNull()?.let { customButtons[it]?.iconPath }
        )

// __P3__

    private fun customButtonRef(button: ReadMenuCustomButton): ReadMenuButtonConfig.ButtonRef {
        return ReadMenuButtonConfig.ButtonRef(ReadMenuButtonConfig.TYPE_CUSTOM, button.id.toString())
    }

    private fun addButton(ref: ReadMenuButtonConfig.ButtonRef) {
        load()
        val row = currentRow().toMutableList()
        if (row.any { it.type == ref.type && it.id == ref.id }) return
        row.add(ref)
        saveCurrentRow(row)
    }

    private fun builtinCandidates(): List<ReadMenuButtonConfig.ButtonRef> {
        return buildList {
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.SEARCH))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.AUTO_PAGE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.REPLACE_RULE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.NIGHT_THEME))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.CATALOG))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_ALOUD))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_STYLE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.SETTING))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_ASSISTANT))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.AI_SUMMARY))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.BUBBLE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.CHARACTERS))
        }
    }

    internal fun moveToOtherRow(ref: ReadMenuButtonConfig.ButtonRef) {
        val first = layout.firstRow.toMutableList()
        val second = layout.secondRow.toMutableList()
        if (rowIndex == 0) {
            first.remove(ref)
            second.add(ref)
        } else {
            second.remove(ref)
            first.add(ref)
        }
        saveLayout(ReadMenuButtonConfig.ButtonLayout(first, second))
    }

    internal fun openCustomButtonEdit(id: Long = 0L) {
        editCustomButton.launch(Intent(this, ReadMenuCustomButtonEditActivity::class.java).apply {
            if (id > 0) putExtra("id", id)
        })
    }

    internal fun onImportMenuAction(index: Int) {
        importMenuOpen = false
        when (index) {
            0 -> launchImportButtonFile()
            1 -> importUrlOpen = true
        }
    }

    private fun launchImportButtonFile() {
        importCustomButton.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.import_str)
            allowExtensions = arrayOf("json")
        }
    }

    internal fun onImportUrlConfirmed(url: String) {
        importUrlOpen = false
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) importButtonsFromUrl(trimmed)
    }

    private fun importButtonsFromUrl(url: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val text = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
                parseImportedButtons(text)
            }.onSuccess { buttons ->
                importButtons(buttons)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun parseImportedButtons(raw: String): List<ReadMenuCustomButton> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        GSON.fromJsonArray<ReadMenuCustomButton>(text).getOrNull()?.let { return it }
        GSON.fromJsonObject<ReadMenuCustomButton>(text).getOrNull()?.let { return listOf(it) }
        return emptyList()
    }

    private fun importButtons(buttons: List<ReadMenuCustomButton>) {
        if (buttons.isEmpty()) {
            toastOnUi(R.string.wrong_format)
            return
        }
        lifecycleScope.launch {
            val importedIds = withContext(Dispatchers.IO) {
                var order = (appDb.readMenuCustomButtonDao.maxOrder() ?: 0) + 1
                buttons.mapNotNull { imported ->
                    val normalized = imported.copy(
                        id = 0L,
                        name = imported.name.trim(),
                        order = order++,
                        updateTime = System.currentTimeMillis()
                    )
                    if (normalized.name.isBlank() || normalized.script.isBlank()) {
                        null
                    } else {
                        appDb.readMenuCustomButtonDao.insert(normalized)
                    }
                }
            }
            if (importedIds.isEmpty()) {
                toastOnUi(R.string.wrong_format)
                return@launch
            }
            load()
            val row = currentRow().toMutableList()
            importedIds.forEach { id ->
                val ref = ReadMenuButtonConfig.ButtonRef(ReadMenuButtonConfig.TYPE_CUSTOM, id.toString())
                if (row.none { it.type == ref.type && it.id == ref.id }) {
                    row.add(ref)
                }
            }
            saveCurrentRow(row)
            toastOnUi(R.string.success)
        }
    }

    internal fun exportButton(button: ReadMenuCustomButton) {
        exportCustomButton.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "${sanitizeFileName(button.displayName())}.json",
                GSON.toJson(button).toByteArray(),
                "application/json"
            )
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim().ifBlank { "read_menu_custom_button" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    internal fun onDeleteRefConfirmed() {
        val ref = deleteRef ?: return
        deleteRef = null
        val row = currentRow().toMutableList()
        row.remove(ref)
        saveCurrentRow(row)
    }

    internal fun onDeleteCustomConfirmed() {
        val button = deleteCustomTarget ?: return
        deleteCustomTarget = null
        val ref = customButtonRef(button)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.readMenuCustomButtonDao.delete(button)
            }
            val first = layout.firstRow.filterNot { it.type == ref.type && it.id == ref.id }
            val second = layout.secondRow.filterNot { it.type == ref.type && it.id == ref.id }
            saveLayout(ReadMenuButtonConfig.ButtonLayout(first, second))
            toastOnUi(R.string.delete_success)
        }
    }

    internal fun showExistingCustomButtonActions(button: ReadMenuCustomButton) {
        addMenuOpen = false
        existingCustomTarget = button
    }

    internal fun onResetConfirmed() {
        resetConfirmOpen = false
        saveLayout(ReadMenuButtonConfig.defaultLayout())
    }

    internal fun onDragFinished() {
        if (!rowOrderDirty) return
        rowOrderDirty = false
        saveCurrentRow(rows)
    }

    internal fun showIconOptions(ref: ReadMenuButtonConfig.ButtonRef) {
        iconMenuRef = ref
    }

    internal fun onIconAction(index: Int) {
        val ref = iconMenuRef ?: return
        iconMenuRef = null
        val isNightButton = ref.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
            ref.id == ReadMenuButtonConfig.Builtin.NIGHT_THEME
        if (isNightButton) {
            when (index) {
                0 -> selectIcon(ref, nightIcon = false)
                1 -> selectIcon(ref, nightIcon = true)
                2 -> clearIcon(ref, nightIcon = false)
                3 -> clearIcon(ref, nightIcon = true)
            }
        } else {
            if (index == 0) selectIcon(ref, nightIcon = false) else clearIcon(ref, nightIcon = false)
        }
    }

    private fun selectIcon(ref: ReadMenuButtonConfig.ButtonRef, nightIcon: Boolean) {
        pendingIconRequest = IconRequest(ref, nightIcon, ICON_REQUEST)
        selectIconFile.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.read_menu_icon_select_file)
            requestCode = ICON_REQUEST
            allowExtensions = arrayOf("png", "jpg", "jpeg", "webp", "bmp", "svg")
        }
    }

    private fun clearIcon(ref: ReadMenuButtonConfig.ButtonRef, nightIcon: Boolean) {
        ReadMenuButtonIconHelper.clearIcon(if (nightIcon) ref.nightIconPath else ref.iconPath)
        updateButtonRef(ref, ref.withIconPath(nightIcon, ""))
        toastOnUi(R.string.success)
    }

    private fun updateButtonRef(oldRef: ReadMenuButtonConfig.ButtonRef, newRef: ReadMenuButtonConfig.ButtonRef) {
        val row = currentRow().toMutableList()
        val index = row.indexOf(oldRef)
        if (index < 0) return
        row[index] = newRef
        saveCurrentRow(row)
    }

    internal fun buttonTitle(ref: ReadMenuButtonConfig.ButtonRef): String {
        ref.titleOverride.trim().takeIf { it.isNotBlank() }?.let { return it }
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
            return ref.id.toLongOrNull()?.let { customButtons[it]?.displayName() } ?: ref.id
        }
        return when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> getString(R.string.search_content)
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> getString(R.string.auto_next_page)
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> getString(R.string.replace_rule_title)
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> getString(R.string.dark_theme)
            ReadMenuButtonConfig.Builtin.CATALOG -> getString(R.string.chapter_list)
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> getString(R.string.read_aloud)
            ReadMenuButtonConfig.Builtin.READ_STYLE -> getString(R.string.interface_setting)
            ReadMenuButtonConfig.Builtin.SETTING -> getString(R.string.setting)
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> getString(R.string.ai_assistant)
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> "AI总结"
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> getString(R.string.paragraph_rule)
            ReadMenuButtonConfig.Builtin.BUBBLE -> "气泡"
            ReadMenuButtonConfig.Builtin.CHARACTERS -> "角色"
            else -> ref.id
        }
    }

    internal fun buttonIconRes(ref: ReadMenuButtonConfig.ButtonRef): Int {
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) return R.drawable.ic_custom
        return when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> R.drawable.ic_search
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> R.drawable.ic_auto_page
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> R.drawable.ic_find_replace
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> R.drawable.ic_brightness
            ReadMenuButtonConfig.Builtin.CATALOG -> R.drawable.ic_toc
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> R.drawable.ic_read_aloud
            ReadMenuButtonConfig.Builtin.READ_STYLE -> R.drawable.ic_interface_setting
            ReadMenuButtonConfig.Builtin.SETTING -> R.drawable.ic_settings
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> R.drawable.ic_bottom_ai_assistant
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> R.drawable.ic_bottom_ai
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> R.drawable.ic_code
            ReadMenuButtonConfig.Builtin.BUBBLE -> R.drawable.ic_bubble_chart
            ReadMenuButtonConfig.Builtin.CHARACTERS -> R.drawable.ic_bottom_person
            else -> R.drawable.ic_custom
        }
    }

    private data class IconRequest(
        val ref: ReadMenuButtonConfig.ButtonRef,
        val nightIcon: Boolean,
        val requestCode: Int
    ) {
        fun oldPath(): String = if (nightIcon) ref.nightIconPath else ref.iconPath
    }

    private companion object {
        private const val ICON_REQUEST = 1001
    }
}

private fun ReadMenuButtonConfig.ButtonRef.withIconPath(
    nightIcon: Boolean,
    path: String
): ReadMenuButtonConfig.ButtonRef {
    return if (nightIcon) copy(nightIconPath = path) else copy(iconPath = path)
}

// __SCREEN__

@Composable
private fun ReadMenuButtonManageScreen(activity: ReadMenuButtonManageActivity) {
    val density = LocalVisualSpec.current.density
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in activity.rows.indices && to.index in activity.rows.indices &&
            from.index != to.index
        ) {
            activity.rows = activity.rows.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            activity.rowOrderDirty = true
        }
    }

    HostV5Page(
        title = stringResource(R.string.read_menu_button_manage),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.reset),
                onClick = { activity.overflowMenuOpen = true },
            )
        },
        bottomBar = {
            AppFilledButton(
                text = stringResource(R.string.read_menu_add_button),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 2,
                    ),
                onClick = { activity.onAddAction() },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(horizontal = density.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppChip(
                    text = stringResource(R.string.read_menu_first_row),
                    selected = activity.rowIndex == 0,
                    onClick = { activity.onRowSelected(0) },
                )
                AppChip(
                    text = stringResource(R.string.read_menu_second_row),
                    selected = activity.rowIndex == 1,
                    onClick = { activity.onRowSelected(1) },
                )
            }
            Text(
                text = stringResource(R.string.read_menu_button_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = density.screenPadding,
                    vertical = density.blockGap / 2,
                ),
            )
            if (activity.rows.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.read_menu_no_available_button),
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
                    items(activity.rows, key = { "${it.type}_${it.id}" }) { ref ->
                        ReorderableItem(reorderState, key = "${ref.type}_${ref.id}") {
                            ReadMenuButtonRow(
                                activity,
                                ref,
                                Modifier.draggableHandle(onDragStopped = { activity.onDragFinished() }),
                            )
                        }
                    }
                }
            }
        }
    }

    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.read_menu_add_button),
            actions = activity.addButtonActions(),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    if (activity.importMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.import_str),
            actions = listOf(
                stringResource(R.string.import_str) to { activity.onImportMenuAction(0) },
                stringResource(R.string.import_on_line) to { activity.onImportMenuAction(1) },
            ),
            onDismiss = { activity.importMenuOpen = false },
        )
    }

    if (activity.importUrlOpen) {
        var url by remember { mutableStateOf("") }
        AppInputDialog(
            title = stringResource(R.string.import_on_line),
            value = url,
            onValueChange = { url = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = "https://...",
            onConfirm = { activity.onImportUrlConfirmed(url) },
            onDismiss = { activity.importUrlOpen = false },
        )
    }

    if (activity.overflowMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.read_menu_button_manage),
            actions = listOf(
                stringResource(R.string.reset) to {
                    activity.overflowMenuOpen = false
                    activity.resetConfirmOpen = true
                },
            ),
            onDismiss = { activity.overflowMenuOpen = false },
        )
    }

    activity.existingCustomTarget?.let { button ->
        val ref = activity.customButtonRefOf(button)
        val alreadyAdded = (activity.layout.firstRow + activity.layout.secondRow).any {
            it.type == ref.type && it.id == ref.id
        }
        val actions = buildList {
            if (!alreadyAdded) {
                add(stringResource(R.string.add) to {
                    activity.existingCustomTarget = null
                    activity.addButtonOf(ref)
                })
            }
            add(stringResource(R.string.edit) to {
                activity.existingCustomTarget = null
                activity.openCustomButtonEdit(button.id)
            })
            add(stringResource(R.string.delete) to {
                activity.existingCustomTarget = null
                activity.deleteCustomTarget = button
            })
            add(stringResource(R.string.export) to {
                activity.existingCustomTarget = null
                activity.exportButton(button)
            })
        }
        AppMenuSheet(
            title = button.displayName(),
            actions = actions,
            onDismiss = { activity.existingCustomTarget = null },
        )
    }

    val iconRef = activity.iconMenuRef
    if (iconRef != null) {
        val isNightButton = iconRef.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
            iconRef.id == ReadMenuButtonConfig.Builtin.NIGHT_THEME
        AppMenuSheet(
            title = stringResource(R.string.change_icon),
            actions = if (isNightButton) {
                listOf(
                    stringResource(R.string.read_menu_icon_set_day) to { activity.onIconAction(0) },
                    stringResource(R.string.read_menu_icon_set_night) to { activity.onIconAction(1) },
                    stringResource(R.string.read_menu_icon_clear_day) to { activity.onIconAction(2) },
                    stringResource(R.string.read_menu_icon_clear_night) to { activity.onIconAction(3) },
                )
            } else {
                listOf(
                    stringResource(R.string.change_icon) to { activity.onIconAction(0) },
                    stringResource(R.string.clear) to { activity.onIconAction(1) },
                )
            },
            onDismiss = { activity.iconMenuRef = null },
        )
    }

    if (activity.deleteRef != null) {
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.del_msg),
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteRefConfirmed() },
            onDismiss = { activity.deleteRef = null },
        )
    }

    activity.deleteCustomTarget?.let { button ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = button.displayName(),
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteCustomConfirmed() },
            onDismiss = { activity.deleteCustomTarget = null },
        )
    }

    if (activity.resetConfirmOpen) {
        AppConfirmDialog(
            title = stringResource(R.string.reset),
            message = stringResource(R.string.del_msg),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.onResetConfirmed() },
            onDismiss = { activity.resetConfirmOpen = false },
        )
    }

    activity.uploadUrl?.let { value ->
        AppConfirmDialog(
            title = stringResource(R.string.upload_url),
            message = value,
            confirmLabel = stringResource(R.string.copy_text),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                activity.uploadUrl = null
                activity.sendToClip(value)
                activity.toastOnUi(R.string.copy_complete)
            },
            onDismiss = { activity.uploadUrl = null },
        )
    }
}

@Composable
private fun ReadMenuButtonRow(
    activity: ReadMenuButtonManageActivity,
    ref: ReadMenuButtonConfig.ButtonRef,
    dragHandleModifier: Modifier,
) {
    val iconBitmap by produceState<Bitmap?>(
        initialValue = null,
        ref.type, ref.id, ref.iconPath, ref.nightIconPath,
        activity.customButtons[ref.id.toLongOrNull()]?.iconPath,
    ) {
        value = runCatching {
            activity.buttonIconDrawable(ref)?.toBitmap()
        }.getOrNull()
    }
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { activity.showIconOptions(ref) },
                )
            } else {
                Icon(
                    painter = painterResource(activity.buttonIconRes(ref)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { activity.showIconOptions(ref) },
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = activity.buttonTitle(ref),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = listOf(
                        stringResource(
                            if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) R.string.read_menu_custom_button
                            else R.string.read_menu_builtin_button
                        ),
                        stringResource(
                            if (activity.rowIndex == 0) R.string.read_menu_first_row
                            else R.string.read_menu_second_row
                        ),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            AppGhostButton(
                text = stringResource(
                    if (activity.rowIndex == 0) R.string.read_menu_move_to_second
                    else R.string.read_menu_move_to_first
                ),
                onClick = { activity.moveToOtherRow(ref) },
            )
            if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
                AppGhostButton(stringResource(R.string.edit)) {
                    ref.id.toLongOrNull()?.let { activity.openCustomButtonEdit(it) }
                }
            }
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.delete),
                onClick = { activity.deleteRef = ref },
            )
            Icon(
                painter = painterResource(R.drawable.ic_drag_handle),
                contentDescription = stringResource(R.string.sort),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier.padding(start = 4.dp),
            )
        }
    }
}
