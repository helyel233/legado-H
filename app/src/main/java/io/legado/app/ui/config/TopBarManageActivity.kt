package io.legado.app.ui.config

import android.graphics.Color
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3Container
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppColorPickerDialog
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppNumberPickerDialog
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val topBarDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

internal const val TOP_BAR_COLOR_TAG_BAR = 5101
internal const val TOP_BAR_COLOR_TAG_SELECTED = 5102
internal const val TOP_BAR_COLOR_BACKGROUND = 5104

class TopBarManageActivity : AppCompatActivity() {

    internal var entries by mutableStateOf<List<TopBarConfig.Entry>>(emptyList())
    internal var activeDirName by mutableStateOf(TopBarConfig.DEFAULT_DIR_NAME)
    internal var isNightMode by mutableStateOf(false)
    internal var summaryText by mutableStateOf("")
    internal var pendingConfig by mutableStateOf<TopBarConfig.Config?>(null)
    internal var editDialogOpen by mutableStateOf(false)
    internal var styleSelectorOpen by mutableStateOf(false)
    internal var cornerScalePickerOpen by mutableStateOf(false)
    internal var wallpaperMenuOpen by mutableStateOf(false)
    internal var wallpaperAlphaPickerOpen by mutableStateOf(false)
    internal var filterDefaultSelectorOpen by mutableStateOf(false)
    internal var tagBarAlphaPickerOpen by mutableStateOf(false)
    internal var tagSelectedAlphaPickerOpen by mutableStateOf(false)
    internal var colorPickerTarget by mutableIntStateOf(0)
    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuEntry by mutableStateOf<TopBarConfig.Entry?>(null)
    internal var deleteRequest by mutableStateOf<Pair<String, suspend () -> Unit>?>(null)
    internal var containerButtonVisible by mutableStateOf(false)
    internal var containerButtonLabel by mutableStateOf("")
    internal var containerSelectorOpen by mutableStateOf(false)
    internal var containerLabels by mutableStateOf<List<String>>(emptyList())
    internal var containerSelectedIndex by mutableIntStateOf(0)

    internal var editingEntry: TopBarConfig.Entry? = null
    private var cloudContainerId: String? = null
    private var containers: List<S3Container> = emptyList()
    private var pendingWallpaperCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importPackage)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    private val selectWallpaper = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::startWallpaperCrop)
    }

    private val cropWallpaper = registerForActivityResult(ImageCropContract()) { result ->
        pendingWallpaperCropRequest = null
        if (result == null) return@registerForActivityResult
        if (File(result.path).exists()) {
            val config = pendingConfig ?: return@registerForActivityResult
            pendingConfig = config.copy(
                wallpaperPath = result.path,
                wallpaperCropLeft = result.cropLeft,
                wallpaperCropTop = result.cropTop,
                wallpaperCropRight = result.cropRight,
                wallpaperCropBottom = result.cropBottom
            )
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { TopBarManageContent(this@TopBarManageActivity) }
        updateContainerMenu()
        loadPackages()
        observeWebDavTasks()
        observeEvent<String>(EventBus.RECREATE) {
            loadPackages()
        }
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
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            ?: containers.firstOrNull()?.id
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
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    TopBarConfig.loadEntries(
                        this@TopBarManageActivity,
                        isNightMode,
                        includeRemote = true,
                        cloudContainerId,
                        CLOUD_SCOPE
                    )
                }
            }.onSuccess {
                if (version != loadVersion || isFinishing || isDestroyed) return@onSuccess
                entries = it
                activeDirName = TopBarConfig.activeDirName(isNightMode)
                summaryText = getString(R.string.top_bar_manage_summary)
            }.onFailure {
                if (version != loadVersion || isFinishing || isDestroyed) return@onFailure
                summaryText = it.localizedMessage.orEmpty()
            }
        }
    }

    internal fun switchDayNight(night: Boolean) {
        if (night != isNightMode) {
            isNightMode = night
            loadPackages()
        }
    }

    internal fun launchImport() {
        importPackage.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.theme_import_zip)
            allowExtensions = arrayOf("zip")
        }
    }

    // region Edit dialog

    internal fun showEditDialog(entry: TopBarConfig.Entry?) {
        val base = entry ?: TopBarConfig.Entry(
            TopBarConfig.defaultConfig(this, isNightMode).copy(name = nextPackageName()),
            TopBarConfig.Source.LOCAL,
            ""
        )
        if (entry != null && base.dirName == TopBarConfig.DEFAULT_DIR_NAME) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        if (base.localDir == null && entry != null && base.source == TopBarConfig.Source.REMOTE) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        TopBarConfig.download(base, cloudContainerId, CLOUD_SCOPE)
                    }
                }.onSuccess {
                    showEditDialog(it)
                }.onFailure {
                    toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        editingEntry = base
        pendingConfig = base.config.copy()
        editDialogOpen = true
    }

    internal fun onEditNameChanged(name: String) {
        pendingConfig = pendingConfig?.copy(name = name)
    }

    internal fun applyStyleDefaults(config: TopBarConfig.Config): TopBarConfig.Config {
        if (config.style != TopBarConfig.STYLE_REGULAR) return config
        return config.copy(
            backgroundColor = config.backgroundColor
                ?: TopBarConfig.defaultBackgroundColor(config.isNightMode),
            cornerScale = config.cornerScale ?: 0f,
            tagBarColor = config.tagBarColor ?: Color.WHITE,
            tagBarAlpha = if (config.tagBarAlpha == 100) 0 else config.tagBarAlpha
        )
    }

    internal fun onStyleSelected(style: String) {
        styleSelectorOpen = false
        val config = pendingConfig ?: return
        pendingConfig = applyStyleDefaults(config.copy(style = style))
    }

    internal fun updatePendingConfig(transform: (TopBarConfig.Config) -> TopBarConfig.Config) {
        pendingConfig = pendingConfig?.let(transform)
    }

    internal fun onEditSave() {
        editDialogOpen = false
        val config = pendingConfig ?: return
        pendingConfig = config.copy(name = config.name.trim())
        saveEditingPackage()
    }

    internal fun onColorConfirmed(target: Int, color: Int) {
        colorPickerTarget = 0
        updatePendingConfig { config ->
            when (target) {
                TOP_BAR_COLOR_BACKGROUND -> config.copy(backgroundColor = color)
                TOP_BAR_COLOR_TAG_BAR -> config.copy(tagBarColor = color)
                TOP_BAR_COLOR_TAG_SELECTED -> config.copy(tagSelectedColor = color)
                else -> config
            }
        }
    }

    internal fun onWallpaperMenuAction(index: Int) {
        wallpaperMenuOpen = false
        if (index == 0) {
            selectWallpaper.launch {
                mode = HandleFileContract.IMAGE
                title = getString(R.string.top_bar_wallpaper)
            }
        } else {
            updatePendingConfig { it.copy(wallpaperPath = null) }
            clearPendingWallpaperCrop()
        }
    }

    // endregion

    private fun startWallpaperCrop(uri: Uri) {
        val metrics = resources.displayMetrics
        val sourceFile = copyWallpaperSource(uri) ?: return
        val animatedFile = sourceFile.takeIf(ImageTypeUtils::isAnimatedImage)
        if (animatedFile == null) {
            sourceFile.delete()
        }
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = animatedFile?.toUri() ?: uri,
            requestCode = REQUEST_WALLPAPER,
            aspectWidth = metrics.widthPixels.coerceAtLeast(1),
            aspectHeight = (220 * metrics.density).toInt().coerceAtLeast(1),
            dirName = "topBarWallpapers",
            prefix = "top_bar",
            targetWidth = 1600
        )
        pendingWallpaperCropRequest = request
        cropWallpaper.launch(
            if (animatedFile != null) {
                request.params.copy(
                    outputPath = animatedFile.absolutePath,
                    viewportOnly = true
                )
            } else {
                clearPendingWallpaperCrop()
                request.params
            }
        )
    }

    private fun copyWallpaperSource(uri: Uri): File? {
        return kotlin.runCatching {
            val dir = externalFiles.getFile("topBarWallpapers").apply { mkdirs() }
            val suffix = if (
                contentResolver.getType(uri).equals("image/gif", ignoreCase = true) ||
                uri.lastPathSegment?.substringBefore('?')?.endsWith(".gif", ignoreCase = true) == true
            ) {
                "gif"
            } else {
                "img"
            }
            val file = File(dir, "top_bar_source_${System.currentTimeMillis()}.$suffix")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: error(getString(R.string.error_image_url_empty))
            file.takeIf { it.exists() && it.length() > 0L }
                ?: error(getString(R.string.error_decode_bitmap))
        }.onFailure {
            toastOnUi(getString(R.string.image_crop_failed, it.localizedMessage ?: getString(R.string.unknown)))
        }.getOrNull()
    }

    private fun clearPendingWallpaperCrop() {
        updatePendingConfig { config ->
            config.copy(
                wallpaperCropLeft = null,
                wallpaperCropTop = null,
                wallpaperCropRight = null,
                wallpaperCropBottom = null
            )
        }
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.top_bar_custom_name)
        val usedNames = entries.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    internal fun actionsFor(entry: TopBarConfig.Entry): List<Pair<String, () -> Unit>> {
        val actions = buildList {
            add(Action.APPLY)
            if (entry.dirName != TopBarConfig.DEFAULT_DIR_NAME) {
                add(Action.EDIT)
                add(Action.EXPORT)
                if (entry.source != TopBarConfig.Source.REMOTE) add(Action.UPLOAD)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DOWNLOAD)
                if (entry.source != TopBarConfig.Source.REMOTE) add(Action.DELETE_LOCAL)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DELETE_REMOTE)
                if (entry.source == TopBarConfig.Source.BOTH) add(Action.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            getString(action.titleRes) to {
                when (action) {
                    Action.APPLY -> applyPackage(entry)
                    Action.EDIT -> showEditDialog(entry)
                    Action.EXPORT -> exportPackage(entry)
                    Action.UPLOAD -> enqueueUpload(entry)
                    Action.DOWNLOAD -> runAction {
                        TopBarConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_LOCAL -> confirmDelete(
                        getString(R.string.navigation_bar_delete_local_confirm)
                    ) {
                        TopBarConfig.deleteLocal(entry)
                        postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                    }
                    Action.DELETE_REMOTE -> confirmDelete(
                        getString(R.string.navigation_bar_delete_remote_confirm)
                    ) {
                        TopBarConfig.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_BOTH -> confirmDelete(
                        getString(R.string.navigation_bar_delete_both_confirm)
                    ) {
                        TopBarConfig.delete(entry, cloudContainerId, CLOUD_SCOPE)
                        postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                    }
                }
            }
        }
    }

    private fun enqueueUpload(entry: TopBarConfig.Entry) {
        val queued = enqueueUploadTask(entry)
        toastOnUi(
            if (queued) R.string.cache_manage_upload_queued
            else R.string.cache_manage_webdav_task_duplicate
        )
        if (queued) {
            showTopBarSyncTasks()
        }
    }

    private fun enqueueUploadIfNeeded(entry: TopBarConfig.Entry): Boolean {
        if (!AppConfig.syncThemePackages) return false
        return enqueueUploadTask(entry)
    }

    private fun enqueueUploadTask(entry: TopBarConfig.Entry): Boolean {
        return WebDavTaskManager.enqueueUpload(
            key = "top_bar_upload:${entry.config.isNightMode}:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD,
            runningMessage = getString(R.string.navigation_bar_upload)
        ) {
            TopBarConfig.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
    }

    private fun observeWebDavTasks() {
        seedHandledWebDavTasks(WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD)
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                var shouldReload = false
                var failedMessage: String? = null
                states.values
                    .filter { it.type == WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD }
                    .filter {
                        it.status == WebDavTaskStatus.COMPLETED ||
                            it.status == WebDavTaskStatus.FAILED
                    }
                    .forEach { state ->
                        if (handledWebDavTasks.add(
                                webDavTaskHandleKey(state.key, state.status)
                            )
                        ) {
                            shouldReload = true
                            if (state.status == WebDavTaskStatus.FAILED) {
                                failedMessage = state.message
                            }
                        }
                    }
                if (shouldReload) {
                    loadPackages()
                    failedMessage?.let {
                        toastOnUi(getString(R.string.theme_sync_failed, it))
                    }
                }
            }
        }
    }

    private fun seedHandledWebDavTasks(type: WebDavTaskType) {
        WebDavTaskManager.states.value.values
            .filter { it.type == type }
            .filter {
                it.status == WebDavTaskStatus.COMPLETED ||
                    it.status == WebDavTaskStatus.FAILED
            }
            .forEach { handledWebDavTasks.add(webDavTaskHandleKey(it.key, it.status)) }
    }

    private fun webDavTaskHandleKey(key: String, status: WebDavTaskStatus): String {
        return "$key:$status"
    }

    internal fun showTopBarSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD))
    }

    internal fun applyPackage(entry: TopBarConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    if (entry.source == TopBarConfig.Source.REMOTE) {
                        TopBarConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    } else {
                        entry
                    }
                }
            }.onSuccess {
                TopBarConfig.apply(it)
                AppearanceKitManager.syncCurrentTopBarRef(it.config.isNightMode, it)
                postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun confirmDelete(message: String, block: suspend () -> Unit) {
        deleteRequest = message to block
    }

    internal fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { toastOnUi(R.string.success) }
                .onFailure {
                    if (it is CancellationException) throw it else toastOnUi(it.localizedMessage)
                }
            loadPackages()
        }
    }

    private fun exportPackage(entry: TopBarConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { TopBarConfig.exportZip(entry) }
            }.onSuccess { zip ->
                exportPackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun importPackage(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = externalFiles.getFile(
                    "topBarImports",
                    "import_${System.currentTimeMillis()}.zip"
                )
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                withContext(Dispatchers.IO) { TopBarConfig.importZip(file) }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showTopBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        val oldEntry = editingEntry
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    TopBarConfig.addOrUpdate(
                        config,
                        oldEntry.takeIf { it?.dirName != TopBarConfig.DEFAULT_DIR_NAME }
                    )
                }
            }.onSuccess {
                if (oldEntry?.dirName == TopBarConfig.DEFAULT_DIR_NAME ||
                    it.dirName == TopBarConfig.activeDirName(it.config.isNightMode)
                ) {
                    TopBarConfig.apply(it)
                    AppearanceKitManager.syncCurrentTopBarRef(it.config.isNightMode, it)
                    postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                }
                toastOnUi(R.string.theme_saved_local)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showTopBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private enum class Action(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.export),
        UPLOAD(R.string.navigation_bar_upload),
        DOWNLOAD(R.string.action_download),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }

    private companion object {
        private const val CLOUD_SCOPE = "theme"
        private const val REQUEST_WALLPAPER = 5103
    }
}

@Composable
private fun TopBarManageContent(activity: TopBarManageActivity) {
    val density = LocalVisualSpec.current.density

    HostV5Page(
        title = stringResource(R.string.top_bar_manage),
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
                    contentDescription = stringResource(R.string.package_sync_task_menu),
                    glyph = "⟳",
                    onClick = { activity.showTopBarSyncTasks() },
                )
            }
        },
        bottomBar = {
            AppFilledButton(
                text = stringResource(R.string.theme_add),
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
            Row(
                modifier = Modifier.padding(horizontal = density.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppChip(
                    text = stringResource(R.string.day),
                    selected = !activity.isNightMode,
                    onClick = { activity.switchDayNight(false) },
                )
                AppChip(
                    text = stringResource(R.string.night),
                    selected = activity.isNightMode,
                    onClick = { activity.switchDayNight(true) },
                )
            }
            Spacer(Modifier.height(density.blockGap / 2))
            if (activity.entries.isEmpty()) {
                AppEmpty(
                    title = "暂无顶栏配置",
                    supporting = "点击下方「添加」创建或导入顶栏包",
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
                    items(
                        activity.entries,
                        key = { "${it.dirName}_${it.config.isNightMode}" }
                    ) { entry ->
                        TopBarItemRow(activity, entry)
                    }
                }
            }
        }
    }

    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.theme_add),
            actions = listOf(
                stringResource(R.string.theme_manual_config) to {
                    activity.addMenuOpen = false
                    activity.showEditDialog(null)
                },
                stringResource(R.string.theme_import_zip) to {
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

    if (activity.styleSelectorOpen) {
        AppFilterDialog(
            title = stringResource(R.string.top_bar_style),
            options = listOf(
                stringResource(R.string.top_bar_style_default),
                stringResource(R.string.top_bar_style_regular)
            ),
            selectedIndex = if (activity.pendingConfig?.style == TopBarConfig.STYLE_REGULAR) 1 else 0,
            onSelect = { index ->
                activity.onStyleSelected(
                    if (index == 1) TopBarConfig.STYLE_REGULAR else TopBarConfig.STYLE_DEFAULT
                )
            },
            onDismiss = { activity.styleSelectorOpen = false },
        )
    }

    if (activity.filterDefaultSelectorOpen) {
        AppFilterDialog(
            title = stringResource(R.string.top_bar_filter_default),
            options = listOf(
                stringResource(R.string.top_bar_filter_default_collapsed),
                stringResource(R.string.top_bar_filter_default_expanded)
            ),
            selectedIndex = if (activity.pendingConfig?.expandFiltersByDefault == true) 1 else 0,
            onSelect = { index ->
                activity.filterDefaultSelectorOpen = false
                activity.updatePendingConfig { it.copy(expandFiltersByDefault = index == 1) }
            },
            onDismiss = { activity.filterDefaultSelectorOpen = false },
        )
    }

    if (activity.wallpaperMenuOpen) {
        val hasWallpaper = !activity.pendingConfig?.wallpaperPath.isNullOrBlank()
        val options = buildList {
            add(stringResource(R.string.theme_image_select))
            if (hasWallpaper) add(stringResource(R.string.theme_image_delete))
        }
        AppMenuSheet(
            title = stringResource(R.string.top_bar_wallpaper),
            actions = options.mapIndexed { index, label ->
                label to { activity.onWallpaperMenuAction(index) }
            },
            onDismiss = { activity.wallpaperMenuOpen = false },
        )
    }

    TopBarPickers(activity)

    activity.deleteRequest?.let { (message, block) ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = message,
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.deleteRequest = null
                activity.runAction(block)
            },
            onDismiss = { activity.deleteRequest = null },
        )
    }
}

@Composable
private fun TopBarPickers(activity: TopBarManageActivity) {
    if (activity.cornerScalePickerOpen) {
        val config = activity.pendingConfig
        if (config != null) {
            AppNumberPickerDialog(
                title = stringResource(R.string.top_bar_corner_scale),
                value = ((config.cornerScale ?: 1f).coerceIn(0f, 3f) * 10).toInt(),
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                range = 0..30,
                step = 1,
                valueLabel = String.format(
                    Locale.ROOT, "%.1f", (config.cornerScale ?: 1f).coerceIn(0f, 3f)
                ),
                onConfirm = { value ->
                    activity.cornerScalePickerOpen = false
                    activity.updatePendingConfig {
                        it.copy(cornerScale = (value / 10f).coerceIn(0f, 3f))
                    }
                },
                onDismiss = { activity.cornerScalePickerOpen = false },
            )
        }
    }

    if (activity.wallpaperAlphaPickerOpen) {
        val config = activity.pendingConfig
        if (config != null) {
            AppNumberPickerDialog(
                title = stringResource(R.string.top_bar_wallpaper_alpha),
                value = config.wallpaperAlpha,
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                range = 0..100,
                step = 1,
                valueLabel = "${config.wallpaperAlpha}%",
                onConfirm = { value ->
                    activity.wallpaperAlphaPickerOpen = false
                    activity.updatePendingConfig {
                        it.copy(wallpaperAlpha = value.coerceIn(0, 100))
                    }
                },
                onDismiss = { activity.wallpaperAlphaPickerOpen = false },
            )
        }
    }

    if (activity.tagBarAlphaPickerOpen) {
        val config = activity.pendingConfig
        if (config != null) {
            AppNumberPickerDialog(
                title = stringResource(R.string.top_bar_tag_bar_alpha),
                value = config.tagBarAlpha,
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                range = 0..100,
                step = 1,
                valueLabel = "${config.tagBarAlpha}%",
                onConfirm = { value ->
                    activity.tagBarAlphaPickerOpen = false
                    activity.updatePendingConfig {
                        it.copy(tagBarAlpha = value.coerceIn(0, 100))
                    }
                },
                onDismiss = { activity.tagBarAlphaPickerOpen = false },
            )
        }
    }

    if (activity.tagSelectedAlphaPickerOpen) {
        val config = activity.pendingConfig
        if (config != null) {
            AppNumberPickerDialog(
                title = stringResource(R.string.top_bar_tag_selected_alpha),
                value = config.tagSelectedAlpha,
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                range = 0..100,
                step = 1,
                valueLabel = "${config.tagSelectedAlpha}%",
                onConfirm = { value ->
                    activity.tagSelectedAlphaPickerOpen = false
                    activity.updatePendingConfig {
                        it.copy(tagSelectedAlpha = value.coerceIn(0, 100))
                    }
                },
                onDismiss = { activity.tagSelectedAlphaPickerOpen = false },
            )
        }
    }

    val colorTarget = activity.colorPickerTarget
    if (colorTarget != 0) {
        val config = activity.pendingConfig
        if (config != null) {
            AppColorPickerDialog(
                title = stringResource(
                    when (colorTarget) {
                        TOP_BAR_COLOR_BACKGROUND -> R.string.top_bar_background_color
                        TOP_BAR_COLOR_TAG_BAR -> R.string.top_bar_tag_bar_color
                        else -> R.string.top_bar_tag_selected_color
                    }
                ),
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = { activity.onColorConfirmed(colorTarget, it) },
                onDismiss = { activity.colorPickerTarget = 0 },
                initialColor = when (colorTarget) {
                    TOP_BAR_COLOR_BACKGROUND -> config.backgroundColor ?: 0
                    TOP_BAR_COLOR_TAG_BAR -> config.tagBarColor ?: 0
                    else -> config.tagSelectedColor ?: 0
                },
            )
        }
    }
}

@Composable
private fun TopBarEditDialogHost(activity: TopBarManageActivity) {
    val config = activity.pendingConfig ?: return
    val density = LocalVisualSpec.current.density
    val isRegular = config.style == TopBarConfig.STYLE_REGULAR

    AppDialog(onDismiss = { activity.editDialogOpen = false }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            Text(
                text = stringResource(R.string.top_bar_edit),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = config.name,
                onValueChange = { activity.onEditNameChanged(it) },
                placeholder = stringResource(R.string.top_bar_name),
                singleLine = true,
            )
            AppListItem(
                headline = stringResource(R.string.top_bar_style),
                supporting = topBarStyleLabel(config.style),
                card = false,
                onClick = { activity.styleSelectorOpen = true },
            )
            if (isRegular) {
                AppListItem(
                    headline = stringResource(R.string.top_bar_corner_scale),
                    supporting = String.format(
                        Locale.ROOT, "%.1f", (config.cornerScale ?: 0f).coerceIn(0f, 3f)
                    ),
                    card = false,
                    onClick = { activity.cornerScalePickerOpen = true },
                )
                TopBarColorRow(
                    label = stringResource(R.string.top_bar_background_color),
                    color = config.backgroundColor,
                    onClick = { activity.colorPickerTarget = TOP_BAR_COLOR_BACKGROUND },
                )
                AppListItem(
                    headline = stringResource(R.string.top_bar_wallpaper),
                    supporting = if (config.wallpaperPath.isNullOrBlank()) {
                        stringResource(R.string.theme_image_value_unselected)
                    } else {
                        stringResource(R.string.theme_image_selected)
                    },
                    card = false,
                    onClick = { activity.wallpaperMenuOpen = true },
                )
                AppListItem(
                    headline = stringResource(R.string.top_bar_wallpaper_alpha),
                    supporting = "${config.wallpaperAlpha}%",
                    card = false,
                    onClick = { activity.wallpaperAlphaPickerOpen = true },
                )
                AppListItem(
                    headline = stringResource(R.string.top_bar_filter_default),
                    supporting = stringResource(
                        if (config.expandFiltersByDefault) R.string.top_bar_filter_default_expanded
                        else R.string.top_bar_filter_default_collapsed
                    ),
                    card = false,
                    onClick = { activity.filterDefaultSelectorOpen = true },
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.top_bar_filter_toggle_button),
                    checked = config.hideFilterToggleWhenExpanded,
                    onCheckedChange = { hidden ->
                        activity.updatePendingConfig {
                            it.copy(hideFilterToggleWhenExpanded = hidden)
                        }
                    },
                    card = false,
                )
                TopBarColorRow(
                    label = stringResource(R.string.top_bar_tag_bar_color),
                    color = config.tagBarColor,
                    onClick = { activity.colorPickerTarget = TOP_BAR_COLOR_TAG_BAR },
                )
                AppListItem(
                    headline = stringResource(R.string.top_bar_tag_bar_alpha),
                    supporting = "${config.tagBarAlpha}%",
                    card = false,
                    onClick = { activity.tagBarAlphaPickerOpen = true },
                )
                TopBarColorRow(
                    label = stringResource(R.string.top_bar_tag_selected_color),
                    color = config.tagSelectedColor,
                    onClick = { activity.colorPickerTarget = TOP_BAR_COLOR_TAG_SELECTED },
                )
                AppListItem(
                    headline = stringResource(R.string.top_bar_tag_selected_alpha),
                    supporting = "${config.tagSelectedAlpha}%",
                    card = false,
                    onClick = { activity.tagSelectedAlphaPickerOpen = true },
                )
            } else {
                AppRowWithSwitch(
                    title = stringResource(R.string.search),
                    checked = config.showSearchInDefaultStyle,
                    onCheckedChange = { enabled ->
                        activity.updatePendingConfig {
                            it.copy(showSearchInDefaultStyle = enabled)
                        }
                    },
                    card = false,
                )
                TopBarColorRow(
                    label = stringResource(R.string.top_bar_tag_bar_color),
                    color = config.tagBarColor,
                    onClick = { activity.colorPickerTarget = TOP_BAR_COLOR_TAG_BAR },
                )
                AppListItem(
                    headline = stringResource(R.string.top_bar_tag_bar_alpha),
                    supporting = "${config.tagBarAlpha}%",
                    card = false,
                    onClick = { activity.tagBarAlphaPickerOpen = true },
                )
                TopBarColorRow(
                    label = stringResource(R.string.top_bar_tag_selected_color),
                    color = config.tagSelectedColor,
                    onClick = { activity.colorPickerTarget = TOP_BAR_COLOR_TAG_SELECTED },
                )
                AppListItem(
                    headline = stringResource(R.string.top_bar_tag_selected_alpha),
                    supporting = "${config.tagSelectedAlpha}%",
                    card = false,
                    onClick = { activity.tagSelectedAlphaPickerOpen = true },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { activity.editDialogOpen = false },
                )
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    modifier = Modifier.weight(1f),
                    onClick = { activity.onEditSave() },
                )
            }
        }
    }
}

@Composable
private fun TopBarColorRow(
    label: String,
    color: Int?,
    onClick: () -> Unit,
) {
    AppListItem(
        headline = label,
        supporting = if (color != null) {
            "#${Integer.toHexString(color).takeLast(6).uppercase(Locale.ROOT)}"
        } else {
            stringResource(R.string.top_bar_follow_theme)
        },
        card = false,
        trailing = {
            if (color != null) {
                Surface(
                    modifier = Modifier.size(18.dp),
                    shape = RoundedCornerShape(50),
                    color = androidx.compose.ui.graphics.Color(color),
                ) {}
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun TopBarItemRow(
    activity: TopBarManageActivity,
    entry: TopBarConfig.Entry,
) {
    val active = activity.activeDirName == entry.dirName

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { activity.itemMenuEntry = entry },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            Text(
                text = entry.config.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = topBarPackageInfo(entry),
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
                    text = if (active) {
                        stringResource(R.string.theme_applied_state)
                    } else {
                        stringResource(R.string.theme_apply)
                    },
                    selected = active,
                    onClick = { activity.applyPackage(entry) },
                )
                if (entry.dirName != TopBarConfig.DEFAULT_DIR_NAME) {
                    AppChip(
                        text = stringResource(R.string.edit),
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

@Composable
private fun topBarPackageInfo(entry: TopBarConfig.Entry): String {
    val parts = mutableListOf(topBarStyleLabel(entry.config.style))
    parts.add("${stringResource(R.string.top_bar_tag_bar_alpha)} ${entry.config.tagBarAlpha}%")
    if (entry.config.updatedAt > 0) {
        val time = maxOf(entry.config.updatedAt, entry.remoteUpdatedAt)
        parts.add(topBarDateFormat.format(Date(time)))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun topBarStyleLabel(style: String): String {
    return stringResource(
        when (style) {
            TopBarConfig.STYLE_REGULAR -> R.string.top_bar_style_regular
            else -> R.string.top_bar_style_default
        }
    )
}

