package io.legado.app.ui.config

import io.legado.app.ui.widget.compose.LegadoComposeTheme
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppPackageManageScreen
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TopBarManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private var entriesState by mutableStateOf<List<TopBarConfig.Entry>>(emptyList())
    private var activeDirNameState by mutableStateOf(TopBarConfig.DEFAULT_DIR_NAME)
    private var isNightMode by mutableStateOf(AppConfig.isNightTheme)
    private var summaryTextState by mutableStateOf("")
    private var editingEntry: TopBarConfig.Entry? = null
    private var pendingConfig: TopBarConfig.Config? = null
    private var pendingColorTarget = 0
    private var pendingWallpaperCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    private var containerMenuLabelState by mutableStateOf<String?>(null)
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

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
            pendingConfig?.let { config ->
                config.wallpaperPath = result.path
                config.wallpaperCropLeft = result.cropLeft
                config.wallpaperCropTop = result.cropTop
                config.wallpaperCropRight = result.cropRight
                config.wallpaperCropBottom = result.cropBottom
            }
            refreshEditDialog()
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.visibility = android.view.View.GONE
        updateContainerMenu()
        initComposeContent()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        updateContainerMenu()
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) {
            loadPackages()
        }
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        container.removeView(binding.tabBar)
        container.removeView(binding.tvSummary)
        container.removeView(binding.btnAdd)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setContent {
                LegadoComposeTheme {
                TopBarManageScreen(
                    entries = entriesState,
                    activeDirName = activeDirNameState,
                    isNightMode = isNightMode,
                    summaryText = summaryTextState,
                    dateFormat = dateFormat,
                    topActions = topBarActions(),
                    onBack = ::supportFinishAfterTransition,
                    onSwitchDayNight = { night ->
                        if (night != isNightMode) {
                            isNightMode = night
                            loadPackages()
                        }
                    },
                    onAdd = ::showAddDialog,
                    onApply = ::applyPackage,
                    onEdit = { entry -> showEditDialog(entry) },
                    entryActions = ::entryActions
                )
                            }
            }
        }
        container.addView(cv, index.coerceAtMost(container.childCount))
    }

    private fun showAddDialog() {
        showComposeActionListDialog(
            title = getString(R.string.theme_add),
            labels = listOf(
                getString(R.string.theme_manual_config),
                getString(R.string.theme_import_zip)
            )
        ) { index ->
            when (index) {
                0 -> showEditDialog(null)
                1 -> importPackage.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.theme_import_zip)
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun topBarActions(): List<AppManagementAction> {
        return buildList {
            containerMenuLabelState?.let { label ->
                add(
                    AppManagementAction(
                        text = label,
                        iconRes = R.drawable.ic_outline_cloud_24,
                        menuActions = ::containerSelectorActions
                    )
                )
            }
            add(
                AppManagementAction(
                    text = getString(R.string.package_sync_task_menu),
                    iconRes = R.drawable.ic_history,
                    onClick = ::showTopBarSyncTasks
                )
            )
        }
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            containerMenuLabelState = null
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            ?: containers.firstOrNull()?.id
        containerMenuLabelState = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
    }

    private fun containerSelectorActions(): List<AppManagementMenuAction> {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (containers.isEmpty()) {
            toastOnUi(R.string.cloud_storage_config_required)
            return emptyList()
        }
        val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
        return containers.map { container ->
            AppManagementMenuAction(
                text = AppCloudStorage.containerDisplayLabel(container),
                checked = container.id == selected,
                onClick = {
                    if (container.id != selected) {
                        AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                        cloudContainerId = container.id
                        updateContainerMenu()
                        loadPackages()
                    }
                }
            )
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
                entriesState = it
                activeDirNameState = TopBarConfig.activeDirName(isNightMode)
                summaryTextState = getString(R.string.top_bar_manage_summary)
            }.onFailure {
                if (version != loadVersion || isFinishing || isDestroyed) return@onFailure
                summaryTextState = it.localizedMessage.orEmpty()
            }
        }
    }

    private fun showEditDialog(entry: TopBarConfig.Entry?) {
        val base = entry ?: TopBarConfig.Entry(
            TopBarConfig.newPackageTemplate(nextPackageName(), isNightMode),
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
        showPendingEditDialog()
    }

    private fun refreshEditDialog() {
        if (pendingConfig == null) return
        showPendingEditDialog()
    }

    private fun showPendingEditDialog() {
        val config = pendingConfig ?: return
        dismissDialogFragment<TopBarEditDialog>()
        showDialogFragment(
            TopBarEditDialog.create(
                config = config,
                onNameChanged = { name ->
                    pendingConfig?.name = name
                },
                onShowWallpaperSelector = {
                    showWallpaperSelector()
                },
                onShowWallpaperAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_wallpaper_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.wallpaperAlpha = value.coerceIn(0, 100)
                            refreshEditDialog()
                        }
                    )
                },
                onShowTagBarAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_tag_bar_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.tagBarAlpha = value.coerceIn(0, 100)
                            refreshEditDialog()
                        }
                    )
                },
                onShowTagSelectedAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_tag_selected_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.tagSelectedAlpha = value.coerceIn(0, 100)
                            refreshEditDialog()
                        }
                    )
                },
                onSave = { name ->
                    pendingConfig?.name = name.trim()
                    saveEditingPackage()
                },
                onCancel = {}
            )
        )
    }

    private fun showWallpaperSelector() {
        val hasWallpaper = !pendingConfig?.wallpaperPath.isNullOrBlank()
        val options = buildList {
            add(getString(R.string.theme_image_select))
            if (hasWallpaper) add(getString(R.string.theme_image_delete))
        }
        showComposeActionListDialog(
            title = getString(R.string.top_bar_wallpaper),
            labels = options
        ) { index ->
            if (index == 0) {
                selectWallpaper.launch {
                    mode = HandleFileContract.IMAGE
                    title = getString(R.string.top_bar_wallpaper)
                }
            } else {
                pendingConfig?.wallpaperPath = null
                clearPendingWallpaperCrop()
                refreshEditDialog()
            }
        }
    }

    private fun entryActions(entry: TopBarConfig.Entry): List<AppManagementMenuAction> {
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
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action in setOf(Action.DELETE_LOCAL, Action.DELETE_REMOTE, Action.DELETE_BOTH)
            ) {
                when (action) {
                    Action.APPLY -> applyPackage(entry)
                    Action.EDIT -> showEditDialog(entry)
                    Action.EXPORT -> exportPackage(entry)
                    Action.UPLOAD -> enqueueUpload(entry)
                    Action.DOWNLOAD -> runAction {
                        TopBarConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_LOCAL -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_local_confirm)
                    ) {
                        TopBarConfig.deleteLocal(entry)
                        postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                    }
                    Action.DELETE_REMOTE -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_remote_confirm)
                    ) {
                        TopBarConfig.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_BOTH -> confirmDelete(
                        entry,
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

    private fun showTopBarSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD))
    }

    private fun applyPackage(entry: TopBarConfig.Entry) {
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

    private fun confirmDelete(
        entry: TopBarConfig.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = message,
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { runAction(block) }
        )
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { toastOnUi(R.string.success) }
                .onFailure { toastOnUi(it.localizedMessage) }
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
        pendingConfig?.wallpaperCropLeft = null
        pendingConfig?.wallpaperCropTop = null
        pendingConfig?.wallpaperCropRight = null
        pendingConfig?.wallpaperCropBottom = null
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.top_bar_custom_name)
        val usedNames = entriesState.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
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

    override fun onColorSelected(dialogId: Int, color: Int) {
        val config = pendingConfig ?: return
        when (dialogId) {
            COLOR_BACKGROUND -> config.backgroundColor = color
            COLOR_TAG_BAR -> config.tagBarColor = color
            COLOR_TAG_SELECTED -> config.tagSelectedColor = color
        }
        refreshEditDialog()
    }

    override fun onDialogDismissed(dialogId: Int) {
        pendingColorTarget = 0
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

    companion object {
        private const val CLOUD_SCOPE = "theme"
        const val COLOR_TAG_BAR = 5101
        const val COLOR_TAG_SELECTED = 5102
        const val REQUEST_WALLPAPER = 5103
        const val COLOR_BACKGROUND = 5104
    }
}

// Compose screen

@Composable
private fun TopBarManageScreen(
    entries: List<TopBarConfig.Entry>,
    activeDirName: String,
    isNightMode: Boolean,
    summaryText: String,
    dateFormat: SimpleDateFormat,
    topActions: List<AppManagementAction>,
    onBack: () -> Unit,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onApply: (TopBarConfig.Entry) -> Unit,
    onEdit: (TopBarConfig.Entry) -> Unit,
    entryActions: (TopBarConfig.Entry) -> List<AppManagementMenuAction>
) {
    val applyText = stringResource(R.string.theme_apply)
    val appliedText = stringResource(R.string.theme_applied_state)
    val editText = stringResource(R.string.edit)
    AppPackageManageScreen(
        title = stringResource(R.string.top_bar_manage),
        onBack = onBack,
        topActions = topActions,
        isNightMode = isNightMode,
        summaryText = summaryText,
        addText = stringResource(R.string.theme_add),
        onSwitchDayNight = onSwitchDayNight,
        onAdd = onAdd,
    ) { palette ->
        items(
            entries,
            key = { "${it.dirName}_${it.config.isNightMode}" }
        ) { entry ->
            val isActive = entry.dirName == activeDirName
            AppPackageManageItemCard(
                title = entry.config.name,
                info = topBarPackageInfo(entry, dateFormat),
                isActive = isActive,
                canEdit = entry.dirName != TopBarConfig.DEFAULT_DIR_NAME,
                applyText = if (isActive) appliedText else applyText,
                editText = editText,
                moreActions = entryActions(entry),
                palette = palette,
                onApply = { onApply(entry) },
                onEdit = { onEdit(entry) }
            )
        }
    }
}

@Composable
private fun topBarPackageInfo(
    entry: TopBarConfig.Entry,
    dateFormat: SimpleDateFormat
): String {
    return buildString {
        append(topBarStyleLabel(entry.config.style))
        append(" \u00B7 ")
        append(stringResource(R.string.top_bar_tag_bar_alpha))
        append(" ")
        append(entry.config.tagBarAlpha)
        append("%")
        if (entry.config.updatedAt > 0) {
            append(" \u00B7 ")
            append(
                dateFormat.format(
                    Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))
                )
            )
        }
    }
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
