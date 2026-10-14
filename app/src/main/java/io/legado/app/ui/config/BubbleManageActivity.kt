package io.legado.app.ui.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ImageProvider
import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.Relayout
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppColorField
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppNumberPickerDialog
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BubbleManageActivity : AppCompatActivity() {

    internal var entries by mutableStateOf<List<BubblePackageManager.Entry>>(emptyList())
    internal var summary by mutableStateOf("")
    internal var activeDirName by mutableStateOf(BubblePackageManager.activeDirName())
    internal var forceSoftwareBubble by mutableStateOf(AppConfig.forceSoftwareParagraphBubble)
    internal var containerButtonVisible by mutableStateOf(false)
    internal var containerButtonLabel by mutableStateOf("")
    internal var containerSelectorOpen by mutableStateOf(false)
    internal var containerLabels by mutableStateOf<List<String>>(emptyList())
    internal var containerSelectedIndex by mutableIntStateOf(0)
    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuEntry by mutableStateOf<BubblePackageManager.Entry?>(null)
    internal var editDialogOpen by mutableStateOf(false)
    internal var sizeScalePickerOpen by mutableStateOf(false)
    internal var netImportOpen by mutableStateOf(false)
    internal var helpOpen by mutableStateOf(false)
    internal var uploadResult by mutableStateOf<String?>(null)
    internal var deleteRequest by mutableStateOf<(suspend () -> Unit)?>(null)
    internal var editingConfig by mutableStateOf<BubblePackageManager.Config?>(null)

    internal var editingEntry: BubblePackageManager.Entry? = null
    private var cloudContainerId: String? = null
    private var containers: List<S3Container> = emptyList()
    private var svgCursorPosition: Int = 0
    private var loadVersion = 0
    private var loadJob: Job? = null
    private val previewCache = BubblePreviewCache()
    private val handledWebDavTasks = mutableSetOf<String>()
    private val importFromNet by lazy { "网络导入" }

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFromNet") {
                netImportOpen = true
            } else {
                importZip(uri)
            }
        }
    }
    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val value = uri.toString()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                uploadResult = value
            } else {
                toastOnUi(R.string.export_success)
            }
        }
    }
    private val svgEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra("text")?.let { text ->
                    editingConfig = editingConfig?.copy(svgTemplate = text)
                    svgCursorPosition =
                        result.data?.getIntExtra("cursorPosition", text.length) ?: text.length
                }
            }
            // 编辑对话框保持打开，返回后直接续用
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { BubbleManageContent(this@BubbleManageActivity) }
        updateContainerMenu()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        updateContainerMenu()
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            containerButtonVisible = false
            return
        }
        cloudContainerId =
            AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id ?: containers.firstOrNull()?.id
        containerButtonVisible = true
        containerButtonLabel = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
    }

    internal fun showContainerSelector() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                AppCloudStorage.listContainers().filter { it.enabled }
            }
            if (list.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            containers = list
            containerLabels = list.map(AppCloudStorage::containerDisplayLabel)
            val selected = cloudContainerId
                ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            containerSelectedIndex = list.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            containerSelectorOpen = true
        }
    }

    internal fun onContainerSelected(index: Int) {
        containerSelectorOpen = false
        val container = containers.getOrNull(index) ?: return
        if (container.id != cloudContainerId) {
            AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
            cloudContainerId = container.id
            updateContainerMenu()
            loadPackages()
        }
    }

    private fun loadPackages() {
        val version = ++loadVersion
        val containerId = cloudContainerId
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val snapshot = BubblePackageManager.loadEntrySnapshot(containerId)
                if (version != loadVersion || isFinishing || isDestroyed) return@launch
                entries = snapshot.mergedEntries
                activeDirName = BubblePackageManager.activeDirName()
                summary = getString(R.string.bubble_manage_summary)

                val refreshed = BubblePackageManager.refreshEntries(
                    snapshot,
                    containerId,
                    CLOUD_SCOPE
                )
                if (version != loadVersion || isFinishing || isDestroyed) return@launch
                entries = refreshed
                activeDirName = BubblePackageManager.activeDirName()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (version == loadVersion && !isFinishing && !isDestroyed) {
                    summary = error.localizedMessage ?: ""
                }
            }
        }
    }

    internal fun launchImport() {
        importPackage.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
            otherActions = arrayListOf(SelectItem(importFromNet, -1))
        }
    }

    internal fun onNetImportConfirm(url: String) {
        netImportOpen = false
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) importNetZip(trimmed)
    }

    private fun importNetZip(url: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = withContext(Dispatchers.IO) {
                    externalFiles.getFile(
                        "bubbleImports",
                        "import_${System.currentTimeMillis()}.zip"
                    ).also { target ->
                        target.parentFile?.mkdirs()
                        okHttpClient.newCallResponseBody {
                            url(url)
                        }.use { body ->
                            FileOutputStream(target).use { output ->
                                body.byteStream().copyTo(output)
                            }
                        }
                    }
                }
                BubblePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun importZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = externalFiles.getFile(
                    "bubbleImports",
                    "import_${System.currentTimeMillis()}.zip"
                )
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                BubblePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    internal fun actionsFor(entry: BubblePackageManager.Entry): List<Pair<String, () -> Unit>> {
        val actions = buildList {
            add(Action.APPLY)
            if (entry.source != BubblePackageManager.Source.BUILTIN) {
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.EDIT)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.EXPORT)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.SHARE_ZIP_FILE)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.SHARE_DIRECT_LINK)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.UPLOAD)
                if (entry.source != BubblePackageManager.Source.LOCAL) add(Action.DOWNLOAD)
                if (entry.source == BubblePackageManager.Source.LOCAL ||
                    entry.source == BubblePackageManager.Source.BOTH
                ) add(Action.DELETE_LOCAL)
                if (entry.source != BubblePackageManager.Source.LOCAL) add(Action.DELETE_REMOTE)
                if (entry.source == BubblePackageManager.Source.BOTH) add(Action.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            action.title to {
                when (action) {
                    Action.APPLY -> applyEntry(entry)
                    Action.EDIT -> showEditDialog(entry)
                    Action.EXPORT -> exportZip(entry)
                    Action.SHARE_ZIP_FILE -> shareZipFile(entry)
                    Action.SHARE_DIRECT_LINK -> shareZipDirectLink(entry)
                    Action.UPLOAD -> enqueueUpload(entry)
                    Action.DOWNLOAD -> runAction(refreshReading = false) {
                        BubblePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_LOCAL -> confirmDelete {
                        BubblePackageManager.deleteLocal(entry)
                    }
                    Action.DELETE_REMOTE -> confirmDelete {
                        BubblePackageManager.deleteRemote(
                            entry, cloudContainerId, CLOUD_SCOPE
                        )
                    }
                    Action.DELETE_BOTH -> confirmDelete {
                        BubblePackageManager.deleteLocal(entry)
                        BubblePackageManager.deleteRemote(
                            entry, cloudContainerId, CLOUD_SCOPE
                        )
                    }
                }
            }
        }
    }

    // region Edit dialog

    internal fun showEditDialog(entry: BubblePackageManager.Entry?) {
        if (entry?.source == BubblePackageManager.Source.BUILTIN ||
            entry?.source == BubblePackageManager.Source.REMOTE
        ) return
        editingEntry = entry
        editingConfig = (entry?.config ?: BubblePackageManager.builtinConfig().copy(
            name = "自定义段评气泡",
            dirName = "",
            updatedAt = System.currentTimeMillis()
        )).copy(dirName = entry?.dirName.orEmpty())
        editDialogOpen = true
    }

    internal fun onEditNameChanged(name: String) {
        editingConfig = editingConfig?.copy(name = name)
    }

    internal fun updateEditingConfig(transform: (BubblePackageManager.Config) -> BubblePackageManager.Config) {
        editingConfig = editingConfig?.let(transform)
    }

    internal fun onEditSave(name: String) {
        val entry = editingEntry
        val config = editingConfig ?: return
        val next = config.copy(
            name = name.trim().ifBlank { config.name },
            dirName = entry?.dirName.orEmpty(),
            formatVersion = config.formatVersion,
            resources = config.resources
        )
        editingConfig = next
        editDialogOpen = false
        runAction(refreshReading = entry?.dirName == BubblePackageManager.activeDirName()) {
            BubblePackageManager.addOrUpdate(next, entry)
        }
    }

    internal fun openSizeScalePicker() {
        if (editingConfig == null) return
        editDialogOpen = false
        sizeScalePickerOpen = true
    }

    internal fun onSizeScaleConfirmed(value: Int) {
        val config = editingConfig ?: return
        editingConfig = config.copy(
            sizeScale = (value / 10f).coerceIn(
                BubblePackageManager.MIN_SIZE_SCALE,
                BubblePackageManager.MAX_SIZE_SCALE
            )
        )
        sizeScalePickerOpen = false
        editDialogOpen = true
    }

    internal fun onSizeScaleDismissed() {
        sizeScalePickerOpen = false
        if (editingConfig != null) {
            editDialogOpen = true
        }
    }

    internal fun openSvgEditor() {
        val svg = editingConfig?.svgTemplate.orEmpty()
        svgEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", svg)
            putExtra("title", "SVG 模板")
            putExtra("cursorPosition", svgCursorPosition.coerceIn(0, svg.length))
        })
    }

    // endregion

    internal suspend fun loadPreview(
        entry: BubblePackageManager.Entry,
        sidePx: Int,
        isNightTheme: Boolean
    ): Bitmap? = previewCache.load(entry, sidePx, isNightTheme)

    private fun enqueueUpload(entry: BubblePackageManager.Entry) {
        val queued = WebDavTaskManager.enqueueUpload(
            key = "bubble_upload:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.BUBBLE_PACKAGE_UPLOAD,
            runningMessage = "正在上传气泡"
        ) {
            BubblePackageManager.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
        toastOnUi(
            if (queued) R.string.cache_manage_upload_queued
            else R.string.cache_manage_webdav_task_duplicate
        )
    }

    private fun observeWebDavTasks() {
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                states.values
                    .filter { it.type == WebDavTaskType.BUBBLE_PACKAGE_UPLOAD }
                    .filter { it.status == WebDavTaskStatus.COMPLETED }
                    .forEach { state ->
                        if (handledWebDavTasks.add("${state.key}:${state.status}")) {
                            loadPackages()
                        }
                    }
            }
        }
    }

    private fun exportZip(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { BubblePackageManager.exportZip(entry) }
                .onSuccess { zip ->
                    exportPackage.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                    }
                }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun shareZipFile(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { BubblePackageManager.exportZip(entry) }
                .onSuccess { zip -> share(zip, "application/zip") }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun shareZipDirectLink(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val zip = BubblePackageManager.exportZip(entry)
                    DirectLinkUpload.upLoad(zip.name, zip, "application/zip")
                }
            }
                .onSuccess { url -> share(url, entry.config.name) }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun confirmDelete(block: suspend () -> Unit) {
        deleteRequest = block
    }

    internal fun runAction(
        refreshReading: Boolean = true,
        refreshPackages: Boolean = true,
        block: suspend () -> Unit
    ) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    toastOnUi(R.string.success)
                    activeDirName = BubblePackageManager.activeDirName()
                    if (refreshReading) notifyBubbleChanged()
                }
                .onFailure { toastOnUi(it.localizedMessage) }
            if (refreshPackages) loadPackages()
        }
    }

    private fun notifyBubbleChanged() {
        ImageProvider.clear()
        ReadConfigEvent.post(Relayout)
    }

    internal fun onForceSoftwareBubbleChanged(enabled: Boolean) {
        forceSoftwareBubble = enabled
        AppConfig.forceSoftwareParagraphBubble = enabled
        notifyBubbleChanged()
    }

    internal fun applyEntry(entry: BubblePackageManager.Entry) {
        runAction(refreshPackages = entry.source == BubblePackageManager.Source.REMOTE) {
            val localEntry = if (entry.source == BubblePackageManager.Source.REMOTE) {
                BubblePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
            } else {
                entry
            }
            BubblePackageManager.apply(localEntry)
        }
    }

    internal fun copyUploadResult(value: String) {
        sendToClip(value)
        toastOnUi(R.string.copy_complete)
    }

    private enum class Action(val title: String) {
        APPLY("应用"),
        EDIT("编辑"),
        EXPORT("导出 zip"),
        SHARE_ZIP_FILE("文件分享"),
        SHARE_DIRECT_LINK("直链分享"),
        UPLOAD("上传"),
        DOWNLOAD("下载"),
        DELETE_LOCAL("删除本地"),
        DELETE_REMOTE("删除云端"),
        DELETE_BOTH("同时删除")
    }

    private companion object {
        private const val CLOUD_SCOPE = "bubble"
    }
}

private val BUBBLE_HELP_TEXT = """
    段评气泡用于把规则里的 dp 图片转成原生 SVG 气泡。

    接入格式：
    <img src="dp:12,{&quot;pclick&quot;:&quot;...&quot;,&quot;status&quot;:&quot;normal&quot;}">

    dp: 后面的数字会替换 SVG 模板里的 ${'$'}{num}。
    status 可选：normal 使用常规色，emphasis 使用强调色；不写 status 时默认 normal。
    SVG 模板支持 ${'$'}{color} 和 ${'$'}{num} 两个占位。
    内置气泡只读；需要自定义时请通过添加或导入创建新气泡。
""".trimIndent()

private val itemDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

private fun parseColorInt(value: String?, emphasis: Boolean): Int {
    val fallback = if (emphasis) {
        BubblePackageManager.DEFAULT_EMPHASIS_COLOR
    } else {
        BubblePackageManager.DEFAULT_NORMAL_COLOR
    }
    fun parse(raw: String): Int = runCatching {
        (if (raw.startsWith("#")) raw else "#$raw").toColorInt()
    }.getOrDefault(fallback.toColorInt())
    return value?.trim()?.takeIf { it.isNotEmpty() }?.let(::parse) ?: parse(fallback)
}

private fun toHex(color: Int): String =
    String.format(Locale.ROOT, "#%06X", color and 0x00FFFFFF)

private fun sourceLabel(source: BubblePackageManager.Source): String = when (source) {
    BubblePackageManager.Source.BUILTIN -> "内置"
    BubblePackageManager.Source.LOCAL -> "本地"
    BubblePackageManager.Source.REMOTE -> "云端"
    BubblePackageManager.Source.BOTH -> "本地 + 云端"
}

private fun buildItemInfo(entry: BubblePackageManager.Entry, active: Boolean): String {
    val time = maxOf(entry.config.updatedAt, entry.remoteUpdatedAt)
    val text = "${sourceLabel(entry.source)} · ${itemDateFormat.format(Date(time))}"
    return if (active) "已应用 · $text" else text
}

private fun canEdit(entry: BubblePackageManager.Entry): Boolean =
    entry.source == BubblePackageManager.Source.LOCAL ||
        entry.source == BubblePackageManager.Source.BOTH

@Composable
private fun BubbleManageContent(activity: BubbleManageActivity) {
    val visual = LocalVisualSpec.current
    val density = visual.density

    HostV5Page(
        title = "气泡管理",
        onBack = { activity.finish() },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (activity.containerButtonVisible) {
                    AppGhostButton(
                        text = activity.containerButtonLabel,
                        onClick = { activity.showContainerSelector() },
                    )
                }
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.help),
                    glyph = "?",
                    onClick = { activity.helpOpen = true },
                )
            }
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
                onClick = { activity.addMenuOpen = true },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (activity.summary.isNotEmpty()) {
                Text(
                    text = activity.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 2,
                    ),
                )
            }
            AppRowWithSwitch(
                title = "强制软件气泡",
                checked = activity.forceSoftwareBubble,
                onCheckedChange = { activity.onForceSoftwareBubbleChanged(it) },
                modifier = Modifier.padding(horizontal = density.screenPadding),
                summary = "关闭硬件加速渲染气泡，异常时可尝试",
            )
            Spacer(Modifier.height(density.blockGap / 2))
            if (activity.entries.isEmpty()) {
                AppEmpty(
                    title = "暂无气泡",
                    supporting = "点击下方「添加」创建或导入气泡包",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 4,
                    ),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    items(activity.entries) { entry ->
                        BubbleItemRow(activity, entry)
                    }
                }
            }
        }
    }

    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.add),
            actions = listOf(
                "手动创建" to {
                    activity.addMenuOpen = false
                    activity.showEditDialog(null)
                },
                "导入 zip" to {
                    activity.addMenuOpen = false
                    activity.launchImport()
                },
            ),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    activity.itemMenuEntry?.let { entry ->
        AppMenuSheet(
            title = entry.config.name,
            actions = activity.actionsFor(entry),
            onDismiss = { activity.itemMenuEntry = null },
        )
    }

    if (activity.containerSelectorOpen) {
        AppFilterDialog(
            title = stringResource(R.string.s3_bucket),
            options = activity.containerLabels,
            selectedIndex = activity.containerSelectedIndex,
            onSelect = { activity.onContainerSelected(it) },
            onDismiss = { activity.containerSelectorOpen = false },
        )
    }

    if (activity.netImportOpen) {
        var url by remember { mutableStateOf("") }
        AppInputDialog(
            title = "网络导入",
            value = url,
            onValueChange = { url = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = "https://...",
            onConfirm = { activity.onNetImportConfirm(url) },
            onDismiss = { activity.netImportOpen = false },
        )
    }

    if (activity.editDialogOpen) {
        BubbleEditDialogHost(activity)
    }

    if (activity.sizeScalePickerOpen) {
        val config = activity.editingConfig
        if (config != null) {
            AppNumberPickerDialog(
                title = "大小倍率",
                value = (config.sizeScale.coerceIn(
                    BubblePackageManager.MIN_SIZE_SCALE,
                    BubblePackageManager.MAX_SIZE_SCALE
                ) * 10).toInt(),
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                range = (BubblePackageManager.MIN_SIZE_SCALE * 10).toInt()..
                    (BubblePackageManager.MAX_SIZE_SCALE * 10).toInt(),
                step = 1,
                valueLabel = String.format(Locale.ROOT, "%.1f", config.sizeScale),
                onConfirm = { activity.onSizeScaleConfirmed(it) },
                onDismiss = { activity.onSizeScaleDismissed() },
            )
        }
    }

    activity.deleteRequest?.let { block ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.sure_del),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.deleteRequest = null
                activity.runAction(block = block)
            },
            onDismiss = { activity.deleteRequest = null },
        )
    }

    if (activity.helpOpen) {
        AppConfirmDialog(
            title = stringResource(R.string.help),
            message = BUBBLE_HELP_TEXT,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.helpOpen = false },
            onDismiss = { activity.helpOpen = false },
        )
    }

    activity.uploadResult?.let { value ->
        AppConfirmDialog(
            title = "上传成功",
            message = value,
            confirmLabel = stringResource(R.string.copy_text),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                activity.uploadResult = null
                activity.copyUploadResult(value)
            },
            onDismiss = { activity.uploadResult = null },
        )
    }
}

@Composable
private fun BubbleEditDialogHost(activity: BubbleManageActivity) {
    val config = activity.editingConfig ?: return
    val visual = LocalVisualSpec.current
    val density = visual.density
    val isAdd = activity.editingEntry == null
    var name by remember { mutableStateOf(config.name) }
    val okLabel = stringResource(R.string.ok)
    val cancelLabel = stringResource(R.string.cancel)

    AppDialog(onDismiss = { activity.editDialogOpen = false }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            Text(
                text = if (isAdd) "添加气泡" else "编辑气泡",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = name,
                onValueChange = {
                    name = it
                    activity.onEditNameChanged(it)
                },
                placeholder = "名称",
                singleLine = true,
            )
            AppListItem(
                headline = "大小倍率",
                supporting = String.format(Locale.ROOT, "%.1f", config.sizeScale),
                card = false,
                onClick = { activity.openSizeScalePicker() },
            )
            AppColorField(
                label = "日间常规色",
                confirmLabel = okLabel,
                cancelLabel = cancelLabel,
                colorInt = parseColorInt(config.dayNormalColor, false),
                onColorChange = { color ->
                    activity.updateEditingConfig { it.copy(dayNormalColor = toHex(color)) }
                },
            )
            AppColorField(
                label = "日间强调色",
                confirmLabel = okLabel,
                cancelLabel = cancelLabel,
                colorInt = parseColorInt(config.dayEmphasisColor, true),
                onColorChange = { color ->
                    activity.updateEditingConfig { it.copy(dayEmphasisColor = toHex(color)) }
                },
            )
            AppColorField(
                label = "夜间常规色",
                confirmLabel = okLabel,
                cancelLabel = cancelLabel,
                colorInt = parseColorInt(config.nightNormalColor, false),
                onColorChange = { color ->
                    activity.updateEditingConfig { it.copy(nightNormalColor = toHex(color)) }
                },
            )
            AppColorField(
                label = "夜间强调色",
                confirmLabel = okLabel,
                cancelLabel = cancelLabel,
                colorInt = parseColorInt(config.nightEmphasisColor, true),
                onColorChange = { color ->
                    activity.updateEditingConfig { it.copy(nightEmphasisColor = toHex(color)) }
                },
            )
            AppListItem(
                headline = "SVG 模板",
                supporting = "点击编辑，支持 \${color} 和 \${num} 占位",
                card = false,
                onClick = { activity.openSvgEditor() },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppGhostButton(
                    text = cancelLabel,
                    modifier = Modifier.weight(1f),
                    onClick = { activity.editDialogOpen = false },
                )
                AppFilledButton(
                    text = okLabel,
                    modifier = Modifier.weight(1f),
                    onClick = { activity.onEditSave(name) },
                )
            }
        }
    }
}

@Composable
private fun BubbleItemRow(
    activity: BubbleManageActivity,
    entry: BubblePackageManager.Entry,
) {
    val active = activity.activeDirName == entry.dirName

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { activity.itemMenuEntry = entry },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 86.dp)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BubblePreview(activity, entry)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.config.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = buildItemInfo(entry, active),
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
                        text = if (active) "已应用" else "应用",
                        selected = active,
                        onClick = { activity.applyEntry(entry) },
                    )
                    if (canEdit(entry)) {
                        AppChip(
                            text = "编辑",
                            selected = false,
                            onClick = { activity.showEditDialog(entry) },
                        )
                    }
                    AppOverflowMenuButton(
                        contentDescription = stringResource(R.string.more),
                        onClick = { activity.itemMenuEntry = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun BubblePreview(activity: BubbleManageActivity, entry: BubblePackageManager.Entry) {
    val isNightTheme = AppConfig.isNightTheme
    val sidePx = with(LocalDensity.current) { 64.dp.roundToPx() }
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        entry.dirName,
        entry.config.updatedAt,
        entry.remoteUpdatedAt,
        entry.config.svgTemplate.hashCode(),
        entry.config.svgTemplate.length,
        isNightTheme,
        sidePx,
    ) {
        value = activity.loadPreview(entry, sidePx, isNightTheme)
    }
    Surface(
        modifier = Modifier.size(64.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
