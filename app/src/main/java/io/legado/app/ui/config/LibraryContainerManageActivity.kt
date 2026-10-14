package io.legado.app.ui.config

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.library.LibraryCloudBackend
import io.legado.app.help.book.library.LibraryContainerConfig
import io.legado.app.help.book.library.LibraryContainerExportCrypto
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.setV5Content
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class LibraryContainerManageActivity : AppCompatActivity() {

    internal val containersState = mutableStateOf<List<LibraryContainerConfig>>(emptyList())
    private var _waitDialog: WaitDialog? = null
    private val waitDialog: WaitDialog
        get() = _waitDialog ?: WaitDialog(this).also { _waitDialog = it }
    private var editingSourceUrls: MutableSet<String> = mutableSetOf()
    private var pendingExportDecryptKey: String? = null

    // V5 弹窗状态
    internal var editDialogOpen by mutableStateOf(false)
    internal var editingItem by mutableStateOf<LibraryContainerConfig?>(null)
    internal var editingTitle by mutableStateOf("")
    internal var fieldName by mutableStateOf("")
    internal var fieldEndpoint by mutableStateOf("")
    internal var fieldBucket by mutableStateOf("")
    internal var fieldAccessKey by mutableStateOf("")
    internal var fieldSecretKey by mutableStateOf("")
    internal var fieldCapacity by mutableStateOf("")
    internal var fieldPrefix by mutableStateOf("")
    internal var fieldRegion by mutableStateOf("")
    internal var fieldSessionToken by mutableStateOf("")
    internal var fieldPassword by mutableStateOf("")
    internal var fieldMinUploadChars by mutableStateOf("")
    internal var fieldDailyUploadLimit by mutableStateOf("")
    internal var checkEnabled by mutableStateOf(true)
    internal var checkPathStyle by mutableStateOf(true)
    internal var importMenuOpen by mutableStateOf(false)
    internal var importUrlOpen by mutableStateOf(false)
    internal var importUrlText by mutableStateOf("")
    internal var decryptOpen by mutableStateOf(false)
    internal var decryptText by mutableStateOf("")
    private var decryptTargetText: String = ""
    internal var exportResult by mutableStateOf<Pair<String, String?>?>(null)
    internal var sourcePickerOpen by mutableStateOf(false)
    internal var sourcePickerSources by mutableStateOf<List<BookSource>>(emptyList())
    internal var sourcePickerChecked by mutableStateOf<Set<Int>>(emptySet())
    internal var deleteTarget by mutableStateOf<LibraryContainerConfig?>(null)
    private val importJson = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                runCatching { uri.readText(this@LibraryContainerManageActivity) }
                    .onSuccess { handleImportText(it) }
                    .onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format)) }
            }
        }
    }
    private val exportJson = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri
        if (uri == null) {
            pendingExportDecryptKey = null
        } else {
            val value = uri.toString()
            showExportResult(value, pendingExportDecryptKey)
            pendingExportDecryptKey = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { LibraryContainerManageHost(this@LibraryContainerManageActivity) }
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        _waitDialog?.dismiss()
    }

    private fun reload() {
        containersState.value = LibraryContainerManager.containers()
    }

    private fun showImportActions() {
        importMenuOpen = true
    }

    internal fun onImportMenuAction(index: Int) {
        importMenuOpen = false
        when (index) {
            0 -> importJson.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_str)
                allowExtensions = arrayOf("json")
            }
            1 -> importUrlText = ""
            else -> Unit
        }
        if (index == 1) importUrlOpen = true
    }

    internal fun pageMenuActions(): List<Pair<String, () -> Unit>> {
        return listOf(
            getString(R.string.import_str) to {
                showImportActions()
            },
            "导出全部" to {
                exportContainers(LibraryContainerManager.containers(), "library-containers.json")
            }
        )
    }

    private fun showImportUrlDialog() {
        importUrlText = ""
        importUrlOpen = true
    }

    internal fun onImportUrlConfirm(url: String) {
        importUrlOpen = false
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) importContainersFromUrl(trimmed)
    }

    private fun importContainersFromUrl(url: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
            }.onSuccess { text ->
                handleImportText(text)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun handleImportText(text: String) {
        if (LibraryContainerExportCrypto.isEncrypted(text)) {
            decryptTargetText = text
            showDecryptImportDialog(text)
            return
        }
        runCatching { importContainers(text, lockedImported = false) }
            .onSuccess(::showImportResult)
            .onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format)) }
    }

    private fun showDecryptImportDialog(text: String) {
        decryptText = ""
        decryptOpen = true
    }

    internal fun onDecryptConfirm(key: String) {
        decryptOpen = false
        val trimmed = key.trim()
        runCatching {
            val decrypted = LibraryContainerExportCrypto.decrypt(decryptTargetText.orEmpty(), trimmed)
            importContainers(decrypted, lockedImported = true)
        }.onSuccess(::showImportResult)
            .onFailure { error ->
                toastOnUi(error.localizedMessage ?: getString(R.string.wrong_format))
            }
    }

    private fun showImportResult(count: Int) {
        if (count > 0) {
            reload()
            toastOnUi("已导入 $count 个书库容器")
        } else {
            toastOnUi(R.string.wrong_format)
        }
    }

    private fun importContainers(text: String, lockedImported: Boolean): Int {
        val imported = parseImportedContainers(text)
        if (imported.containers.isEmpty()) return 0
        val idMap = mutableMapOf<String, String>()
        var count = 0
        imported.containers
            .map { it.normalized() }
            .filter { it.container.endpoint.isNotBlank() && it.container.bucket.isNotBlank() }
            .forEach { config ->
                val saveConfig = if (lockedImported) {
                    config.copy(
                        container = config.container.copy(id = S3Container.newId()),
                        lockedImported = true
                    )
                } else {
                    config.copy(lockedImported = false)
                }
                val saved = LibraryContainerManager.upsert(saveConfig)
                idMap[config.id] = saved.id
                count++
            }
        imported.selectedId?.let { selectedId ->
            idMap[selectedId]?.let { LibraryContainerManager.select(it) }
        }
        return count
    }

    private fun parseImportedContainers(text: String): LibraryContainerImport {
        val raw = text.trim()
        if (raw.isBlank()) return LibraryContainerImport()
        GSON.fromJsonObject<LibraryContainerExport>(raw).getOrNull()
            ?.takeIf { it.containers.isNotEmpty() }
            ?.let { return LibraryContainerImport(it.containers, it.selectedId) }
        GSON.fromJsonArray<LibraryContainerConfig>(raw).getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return LibraryContainerImport(it) }
        GSON.fromJsonObject<LibraryContainerConfig>(raw).getOrNull()
            ?.takeIf { it.container.endpoint.isNotBlank() || it.container.bucket.isNotBlank() }
            ?.let { return LibraryContainerImport(listOf(it)) }
        GSON.fromJsonObject<S3Container>(raw).getOrNull()
            ?.takeIf { it.endpoint.isNotBlank() || it.bucket.isNotBlank() }
            ?.let { return LibraryContainerImport(listOf(LibraryContainerConfig(container = it))) }
        return LibraryContainerImport()
    }

    private fun exportContainers(items: List<LibraryContainerConfig>, fileName: String) {
        val exportableItems = items.filterNot { it.lockedImported }
        if (exportableItems.size < items.size) {
            toastOnUi("已跳过加密导入的书库容器")
        }
        if (exportableItems.isEmpty()) {
            toastOnUi("没有可导出的书库容器")
            return
        }
        val selectedId = LibraryContainerManager.selectedId()
            ?.takeIf { id -> exportableItems.any { it.id == id } }
        val payload = LibraryContainerExport(
            selectedId = selectedId,
            containers = exportableItems.map { it.normalized().copy(lockedImported = false) }
        )
        val encrypted = LibraryContainerExportCrypto.encrypt(GSON.toJson(payload))
        pendingExportDecryptKey = encrypted.decryptKey
        exportJson.launch {
            mode = HandleFileContract.EXPORT
            title = "导出书库容器"
            fileData = HandleFileContract.FileData(
                fileName,
                GSON.toJson(encrypted.payload).toByteArray(),
                "application/json"
            )
        }
    }

    private fun showExportResult(value: String, decryptKey: String?) {
        exportResult = value to decryptKey
    }

    internal fun onExportResultDismiss() {
        exportResult = null
    }

    internal fun showEditDialog(item: LibraryContainerConfig?) {
        val locked = item?.lockedImported == true
        editingSourceUrls = item?.sourceUrls.orEmpty().toMutableSet()

        editingTitle = when {
            item == null -> "添加书库容器"
            locked -> "编辑同步书源"
            else -> "编辑书库容器"
        }

        if (locked) {
            showLockedSourcePicker(item)
            return
        }

        val container = item?.container
        val password = item?.password.orEmpty()
        val minUploadChars = item?.minUploadChars ?: 1500
        val dailyUploadLimit = item?.dailyUploadLimit ?: 0

        editingItem = item
        fieldName = container?.name.orEmpty()
        fieldEndpoint = container?.endpoint.orEmpty()
        fieldBucket = container?.bucket.orEmpty()
        fieldAccessKey = container?.accessKey.orEmpty()
        fieldSecretKey = container?.secretKey.orEmpty()
        fieldCapacity = capacityMbToGbText(container?.capacityMb ?: DEFAULT_CAPACITY_MB)
        fieldPrefix = container?.prefix ?: "Library"
        fieldRegion = container?.region ?: "auto"
        fieldSessionToken = container?.sessionToken.orEmpty()
        fieldPassword = password
        fieldMinUploadChars = minUploadChars.toString()
        fieldDailyUploadLimit = dailyUploadLimit.toString()
        checkEnabled = container?.enabled ?: true
        checkPathStyle = container?.pathStyle ?: true
        editDialogOpen = true
    }

    internal fun onEditSave() {
        editDialogOpen = false
        val item = editingItem
        val fields = listOf(
            fieldName, fieldEndpoint, fieldBucket, fieldAccessKey, fieldSecretKey,
            fieldCapacity, fieldPrefix, fieldRegion, fieldSessionToken,
            fieldPassword, fieldMinUploadChars, fieldDailyUploadLimit
        )
        val checks = booleanArrayOf(checkEnabled, checkPathStyle)
        saveDialogItem(item, fields, checks)?.let { saved ->
            if (item == null) refreshCapacity(saved, showWait = false)
        }
    }

    private fun showLockedSourcePicker(item: LibraryContainerConfig) {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { appDb.bookSourceDao.allEnabled }
            sourcePickerSources = sources
            sourcePickerChecked = sources.mapIndexedNotNull { index, source ->
                index.takeIf { editingSourceUrls.contains(source.bookSourceUrl) }
            }.toSet()
            sourcePickerOpen = true
        }
    }

    internal fun onSourcePickerConfirm(checked: Set<Int>) {
        val sources = sourcePickerSources
        sourcePickerOpen = false
        val item = editingItem
        val selected = sources.mapIndexedNotNull { index, source ->
            source.bookSourceUrl.takeIf { index in checked }
        }.toSet()
        editingSourceUrls = selected.toMutableSet()
        val target = item?.takeIf { it.lockedImported }
        if (target != null) {
            LibraryContainerManager.upsert(
                target.copy(sourceUrls = selected, lockedImported = true)
            )
            reload()
            toastOnUi("已保存指定书源")
        }
    }

    private fun saveDialogItem(
        oldItem: LibraryContainerConfig?,
        fields: List<String>,
        checks: BooleanArray
    ): LibraryContainerConfig? {
        val pathStyle = checks.checkedAt(CHECK_PATH_STYLE, default = true)
        val parsed = S3Config.parseAddress(
            fields.fieldAt(FIELD_ENDPOINT),
            fields.fieldAt(FIELD_BUCKET),
            fields.fieldAt(FIELD_REGION),
            pathStyle
        )
        val capacityMb = gbTextToCapacityMb(fields.fieldAt(FIELD_CAPACITY))
        val usedBytes = oldItem?.container?.usedBytes?.coerceAtLeast(0L) ?: 0L
        if (parsed.endpoint.isBlank() || parsed.bucket.isBlank()) {
            toastOnUi(R.string.s3_container_endpoint_bucket_required)
            return null
        }
        if (fields.fieldAt(FIELD_ACCESS_KEY).isBlank()
            || fields.fieldAt(FIELD_SECRET_KEY).isBlank()
        ) {
            toastOnUi(R.string.s3_container_key_required)
            return null
        }
        val container = S3Container(
            id = oldItem?.id ?: S3Container.newId(),
            name = fields.fieldAt(FIELD_NAME).trim().ifBlank { parsed.bucket },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            prefix = fields.fieldAt(FIELD_PREFIX).trim().ifBlank { "Library" },
            region = parsed.region.ifBlank { "auto" },
            accessKey = fields.fieldAt(FIELD_ACCESS_KEY).trim(),
            secretKey = fields.fieldAt(FIELD_SECRET_KEY).trim(),
            sessionToken = fields.fieldAt(FIELD_SESSION_TOKEN).trim().ifBlank { null },
            pathStyle = parsed.pathStyle,
            capacityMb = capacityMb,
            usedBytes = if (capacityMb > 0) usedBytes.coerceAtMost(mbToBytes(capacityMb)) else usedBytes,
            lastRefreshTime = oldItem?.container?.lastRefreshTime ?: 0L,
            isFull = capacityMb > 0 && usedBytes >= mbToBytes(capacityMb),
            enabled = checks.checkedAt(CHECK_ENABLED, default = true)
        )
        val password = fields.fieldAt(FIELD_PASSWORD).takeIf { it.isNotBlank() }
        val minUploadChars = fields.fieldAt(FIELD_MIN_UPLOAD_CHARS).toIntOrNull()?.coerceAtLeast(0) ?: 1500
        val dailyUploadLimit = fields.fieldAt(FIELD_DAILY_UPLOAD_LIMIT).toIntOrNull()?.coerceAtLeast(0) ?: 0
        val saved = LibraryContainerManager.upsert(
            LibraryContainerConfig(
                container = container,
                password = password,
                sourceUrls = editingSourceUrls.toSet(),
                minUploadChars = minUploadChars,
                dailyUploadLimit = dailyUploadLimit,
                lockedImported = oldItem?.lockedImported == true
            )
        )
        editingSourceUrls = mutableSetOf()
        reload()
        return saved
    }

    internal fun containerActions(item: LibraryContainerConfig): List<Pair<String, () -> Unit>> {
        val actions = if (item.lockedImported) {
            listOf(Action.EDIT, Action.DELETE)
        } else {
            listOf(
                Action.EDIT,
                Action.EXPORT,
                Action.TEST,
                Action.REFRESH,
                Action.SET_DEFAULT,
                if (item.container.enabled) Action.DISABLE else Action.ENABLE,
                Action.DELETE
            )
        }
        return actions.map { action ->
            action.title to {
                when (action) {
                    Action.EDIT -> showEditDialog(item)
                    Action.EXPORT -> exportContainers(
                        listOf(item),
                        "library-container-${safeExportName(LibraryContainerManager.displayLabel(item))}.json"
                    )
                    Action.TEST -> testConnection(item)
                    Action.REFRESH -> refreshCapacity(item)
                    Action.SET_DEFAULT -> {
                        if (!item.container.enabled) {
                            toastOnUi(R.string.s3_container_disabled)
                        } else {
                            LibraryContainerManager.select(item.id)
                            toastOnUi(R.string.s3_container_set_default_success)
                            reload()
                        }
                    }
                    Action.ENABLE -> updateItem(item.copy(container = item.container.copy(enabled = true, isFull = false)))
                    Action.DISABLE -> updateItem(item.copy(container = item.container.copy(enabled = false)))
                    Action.DELETE -> confirmDelete(item)
                }
            }
        }
    }

    private fun updateItem(item: LibraryContainerConfig) {
        LibraryContainerManager.upsert(item)
        reload()
    }

    private fun confirmDelete(item: LibraryContainerConfig) {
        deleteTarget = item
    }

    internal fun onDeleteConfirmed() {
        val item = deleteTarget ?: return
        deleteTarget = null
        LibraryContainerManager.delete(item.id)
        reload()
    }

    private fun testConnection(item: LibraryContainerConfig) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { LibraryCloudBackend(item).check() }
            }.onSuccess {
                toastOnUi(R.string.s3_container_test_success)
            }.onFailure {
                toastOnUi(getString(R.string.s3_container_test_failed, it.localizedMessage.orEmpty()))
            }
            waitDialog.dismiss()
        }
    }

    private fun refreshCapacity(item: LibraryContainerConfig, showWait: Boolean = true) {
        if (showWait) {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { LibraryCloudBackend(item).refreshUsage() }
            }.onSuccess {
                LibraryContainerManager.updateUsage(item.id, it)
                toastOnUi(R.string.s3_container_capacity_refreshed)
                reload()
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
            if (showWait) waitDialog.dismiss()
        }
    }

    private enum class Action(val title: String) {
        EDIT("编辑"),
        EXPORT("导出"),
        TEST("测试连接"),
        REFRESH("刷新容量"),
        SET_DEFAULT("设为默认"),
        ENABLE("启用"),
        DISABLE("禁用"),
        DELETE("删除")
    }

    internal companion object {
        const val DEFAULT_CAPACITY_MB = 5L * 1024L
        private const val FIELD_NAME = 0
        private const val FIELD_ENDPOINT = 1
        private const val FIELD_BUCKET = 2
        private const val FIELD_ACCESS_KEY = 3
        private const val FIELD_SECRET_KEY = 4
        private const val FIELD_CAPACITY = 5
        private const val FIELD_PREFIX = 6
        private const val FIELD_REGION = 7
        private const val FIELD_SESSION_TOKEN = 8
        private const val FIELD_PASSWORD = 9
        private const val FIELD_MIN_UPLOAD_CHARS = 10
        private const val FIELD_DAILY_UPLOAD_LIMIT = 11
        private const val CHECK_ENABLED = 0
        private const val CHECK_PATH_STYLE = 1

        private fun List<String>.fieldAt(index: Int): String = getOrNull(index).orEmpty()

        private fun BooleanArray.checkedAt(index: Int, default: Boolean): Boolean {
            return if (index in indices) this[index] else default
        }

        internal fun mbToBytes(value: Long): Long = value.coerceAtLeast(0L) * 1024L * 1024L

        internal fun gbTextToCapacityMb(value: String): Long {
            val gb = value.trim().toDoubleOrNull() ?: return 0L
            if (gb <= 0.0) return 0L
            return max(ceil(gb * 1024.0).toLong(), 1L)
        }

        internal fun capacityMbToGbText(value: Long): String {
            if (value <= 0L) return ""
            val gb = value / 1024.0
            return if (value % 1024L == 0L) {
                (value / 1024L).toString()
            } else {
                String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
            }
        }

        internal fun formatBytes(value: Long): String {
            val bytes = value.coerceAtLeast(0L).toDouble()
            val gb = bytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) {
                "${formatDecimal(gb)} GB"
            } else {
                val mb = bytes / 1024.0 / 1024.0
                "${max(mb.toLong(), 0L)} MB"
            }
        }

        internal fun formatDecimal(value: Double): String {
            return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

        internal fun safeExportName(value: String): String {
            return value.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").trim('-').ifBlank { "default" }
        }
    }

    private data class LibraryContainerImport(
        val containers: List<LibraryContainerConfig> = emptyList(),
        val selectedId: String? = null
    )

    private data class LibraryContainerExport(
        val version: Int = 1,
        val selectedId: String? = null,
        val containers: List<LibraryContainerConfig> = emptyList()
    )
}

@Composable
private fun LibraryContainerManageHost(activity: LibraryContainerManageActivity) {
    LibraryContainerManageScreen(
        containers = activity.containersState.value,
        onBack = { activity.finish() },
        onAdd = { activity.showEditDialog(null) },
        onItemClick = { activity.showEditDialog(it) },
        pageMenuActions = { activity.pageMenuActions() },
        onMoreActions = { activity.containerActions(it) },
    )

    if (activity.editDialogOpen) {
        val density = LocalVisualSpec.current.density
        AppDialog(onDismiss = { activity.editDialogOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(density.screenPadding),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = activity.editingTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppTextField(
                    value = activity.fieldName,
                    onValueChange = { activity.fieldName = it },
                    placeholder = stringResource(R.string.s3_container_name),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldEndpoint,
                    onValueChange = { activity.fieldEndpoint = it },
                    placeholder = stringResource(R.string.s3_endpoint),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldBucket,
                    onValueChange = { activity.fieldBucket = it },
                    placeholder = stringResource(R.string.s3_bucket),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldAccessKey,
                    onValueChange = { activity.fieldAccessKey = it },
                    placeholder = stringResource(R.string.s3_access_key),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldSecretKey,
                    onValueChange = { activity.fieldSecretKey = it },
                    placeholder = stringResource(R.string.s3_secret_key),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldCapacity,
                    onValueChange = { activity.fieldCapacity = it },
                    placeholder = stringResource(R.string.s3_container_capacity_gb),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldPrefix,
                    onValueChange = { activity.fieldPrefix = it },
                    placeholder = stringResource(R.string.s3_prefix),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldRegion,
                    onValueChange = { activity.fieldRegion = it },
                    placeholder = stringResource(R.string.s3_region),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldSessionToken,
                    onValueChange = { activity.fieldSessionToken = it },
                    placeholder = stringResource(R.string.s3_session_token),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldPassword,
                    onValueChange = { activity.fieldPassword = it },
                    placeholder = "书库加密密码",
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldMinUploadChars,
                    onValueChange = { activity.fieldMinUploadChars = it },
                    placeholder = "最少自动上传字数",
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldDailyUploadLimit,
                    onValueChange = { activity.fieldDailyUploadLimit = it },
                    placeholder = "每日自动上传章节上限",
                    singleLine = true,
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.s3_container_enabled),
                    checked = activity.checkEnabled,
                    onCheckedChange = { activity.checkEnabled = it },
                    card = false,
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.s3_path_style),
                    checked = activity.checkPathStyle,
                    onCheckedChange = { activity.checkPathStyle = it },
                    card = false,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    AppGhostButton(
                        text = stringResource(R.string.dialog_cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { activity.editDialogOpen = false },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.dialog_confirm),
                        modifier = Modifier.weight(1f),
                        onClick = { activity.onEditSave() },
                    )
                }
            }
        }
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
        AppInputDialog(
            title = stringResource(R.string.import_on_line),
            value = activity.importUrlText,
            onValueChange = { activity.importUrlText = it },
            confirmLabel = stringResource(R.string.dialog_confirm),
            cancelLabel = stringResource(R.string.dialog_cancel),
            placeholder = "https://...",
            onConfirm = { activity.onImportUrlConfirm(activity.importUrlText) },
            onDismiss = { activity.importUrlOpen = false },
        )
    }

    if (activity.decryptOpen) {
        AppInputDialog(
            title = "导入加密书库容器",
            value = activity.decryptText,
            onValueChange = { activity.decryptText = it },
            confirmLabel = stringResource(R.string.dialog_confirm),
            cancelLabel = stringResource(R.string.dialog_cancel),
            placeholder = "请输入解密密钥",
            onConfirm = { activity.onDecryptConfirm(activity.decryptText) },
            onDismiss = { activity.decryptOpen = false },
        )
    }

    activity.exportResult?.let { (value, keyText) ->
        val isUrl = value.startsWith("http://", true) || value.startsWith("https://", true)
        val density = LocalVisualSpec.current.density
        AppDialog(onDismiss = { activity.onExportResultDismiss() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(density.screenPadding),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = stringResource(if (isUrl) R.string.upload_url else R.string.export_success),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        if (isUrl) {
                            append(value)
                            append("\n\n")
                        }
                        append("解密密钥：\n")
                        append(keyText.orEmpty())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    if (isUrl) {
                        AppGhostButton(
                            text = "复制URL",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                activity.onExportResultDismiss()
                                activity.sendToClip(value)
                                activity.toastOnUi(R.string.copy_complete)
                            },
                        )
                    }
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { activity.onExportResultDismiss() },
                    )
                    AppFilledButton(
                        text = "复制密钥",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activity.onExportResultDismiss()
                            activity.sendToClip(keyText.orEmpty())
                            activity.toastOnUi(R.string.copy_complete)
                        },
                    )
                }
            }
        }
    }

    if (activity.sourcePickerOpen) {
        var checked by remember { mutableStateOf(activity.sourcePickerChecked) }
        AppDialog(onDismiss = { activity.sourcePickerOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(densityScreenPadding()),
            ) {
                Text(
                    text = "指定书源",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(activity.sourcePickerSources) { source ->
                        val index = activity.sourcePickerSources.indexOf(source)
                        val group = source.bookSourceGroup
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " · $it" }
                            .orEmpty()
                        AppListItem(
                            headline = "${source.bookSourceName}$group",
                            card = false,
                            leading = {
                                Checkbox(
                                    checked = index in checked,
                                    onCheckedChange = { isChecked ->
                                        checked = if (isChecked) {
                                            checked + index
                                        } else {
                                            checked - index
                                        }
                                    },
                                )
                            },
                            onClick = {
                                checked = if (index in checked) {
                                    checked - index
                                } else {
                                    checked + index
                                }
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(densityBlockGapHalf()),
                ) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { activity.sourcePickerOpen = false },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = { activity.onSourcePickerConfirm(checked) },
                    )
                }
            }
        }
    }

    activity.deleteTarget?.let { item ->
        AppConfirmDialog(
            title = "删除书库容器",
            message = "确认删除 ${LibraryContainerManager.displayLabel(item)}？",
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteConfirmed() },
            onDismiss = { activity.deleteTarget = null },
        )
    }
}

@Composable
private fun densityScreenPadding() = LocalVisualSpec.current.density.screenPadding

@Composable
private fun densityBlockGapHalf() = LocalVisualSpec.current.density.blockGap / 2
