package io.legado.app.ui.config

import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.MainBottomNavConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3Container
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val NAV_COLOR_BORDER = 7002
private const val NAV_REQUEST_SIDEBAR_BACKGROUND = 7001
private const val NAV_REQUEST_BOTTOM_WALLPAPER = 7003
private const val NAV_REQUEST_SINGLE_ICON_BASE = 7100

private val navDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

internal data class NavIconMenuRequest(
    val item: NavigationBarIconConfig.NavItem,
    val selected: Boolean,
    val single: Boolean,
)

class NavigationBarManageActivity : AppCompatActivity() {

    internal var entries by mutableStateOf<List<NavigationBarIconConfig.Entry>>(emptyList())
    internal var activeDirName by mutableStateOf(NavigationBarIconConfig.DEFAULT_DIR_NAME)
    internal var summaryText by mutableStateOf("")
    internal var isNightMode by mutableStateOf(false)
    internal var bottomNavItems by mutableStateOf(MainBottomNavConfig.items())
    internal var pendingConfig by mutableStateOf<NavigationBarIconConfig.Config?>(null)
    internal var editDialogOpen by mutableStateOf(false)
    internal var layoutModeSelectorOpen by mutableStateOf(false)
    internal var effectModeSelectorOpen by mutableStateOf(false)
    internal var wallpaperMenuOpen by mutableStateOf(false)
    internal var sidebarBgMenuOpen by mutableStateOf(false)
    internal var opacityPickerOpen by mutableStateOf(false)
    internal var borderAlphaPickerOpen by mutableStateOf(false)
    internal var borderColorMenuOpen by mutableStateOf(false)
    internal var colorPickerTarget by mutableIntStateOf(0)
    internal var iconMenuRequest by mutableStateOf<NavIconMenuRequest?>(null)
    internal var bottomNavItemsDialogOpen by mutableStateOf(false)
    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuEntry by mutableStateOf<NavigationBarIconConfig.Entry?>(null)
    internal var deleteRequest by mutableStateOf<Pair<String, suspend () -> Unit>?>(null)
    internal var containerButtonVisible by mutableStateOf(false)
    internal var containerButtonLabel by mutableStateOf("")
    internal var containerSelectorOpen by mutableStateOf(false)
    internal var containerLabels by mutableStateOf<List<String>>(emptyList())
    internal var containerSelectedIndex by mutableIntStateOf(0)

    internal var editingEntry: NavigationBarIconConfig.Entry? = null
    private var pendingIconRequest: IconRequest? = null
    private var pendingSidebarBackgroundEntry: NavigationBarIconConfig.Entry? = null
    private var pendingBottomWallpaperCropRequest: ImageCropHelper.Request? = null
    private var cloudContainerId: String? = null
    private var containers: List<S3Container> = emptyList()
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0

    private data class IconRequest(
        val code: Int,
        val entry: NavigationBarIconConfig.Entry,
        val item: NavigationBarIconConfig.NavItem,
        val selected: Boolean,
        val single: Boolean = false
    )

    private val selectIcon = registerForActivityResult(HandleFileContract()) { result ->
        val request = pendingIconRequest?.takeIf { it.code == result.requestCode } ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    if (request.single) {
                        NavigationBarIconConfig.saveSingleIconToPackage(
                            this@NavigationBarManageActivity,
                            uri,
                            request.entry,
                            request.item.key,
                            resources.getDimensionPixelSize(R.dimen.main_sidebar_search_icon_size)
                        )
                    } else {
                        NavigationBarIconConfig.saveIconToPackage(
                            this@NavigationBarManageActivity,
                            uri,
                            request.entry,
                            request.item.key,
                            request.selected,
                            resources.getDimensionPixelSize(R.dimen.main_bottom_nav_icon_size)
                        )
                    }
                }
            }.onSuccess {
                applyIconResult(it)
                loadPackages()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
        }
    }

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importPackage(uri) }
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let {
            toastOnUi(R.string.export_success)
        }
    }

    private val selectSidebarBackground = registerForActivityResult(HandleFileContract()) { result ->
        val entry = pendingSidebarBackgroundEntry ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        val metrics = resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        val sidebarWidth = if (isLandscape) {
            (metrics.widthPixels * 0.33f).toInt()
        } else {
            (metrics.widthPixels * 0.66f).toInt()
        }.coerceAtLeast(1)
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = NAV_REQUEST_SIDEBAR_BACKGROUND,
            aspectWidth = sidebarWidth,
            aspectHeight = metrics.heightPixels.coerceAtLeast(1),
            dirName = "navigationBarSidebarBackground",
            prefix = "sidebar_bg",
            targetWidth = 1440
        )
        pendingSidebarBackgroundEntry = entry
        cropSidebarBackground.launch(request.params)
    }

    private val cropSidebarBackground = registerForActivityResult(ImageCropContract()) { result ->
        val entry = pendingSidebarBackgroundEntry ?: return@registerForActivityResult
        pendingSidebarBackgroundEntry = null
        if (result == null) {
            return@registerForActivityResult
        }
        val resultPath = result.path
        if (!File(resultPath).exists()) {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.saveSidebarBackgroundToPackage(
                        this@NavigationBarManageActivity,
                        resultPath,
                        entry
                    )
                }
            }.onSuccess {
                applyIconResult(it)
                loadPackages()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
        }
    }

    private val selectBottomWallpaper = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::startBottomWallpaperCrop)
    }

    private val cropBottomWallpaper = registerForActivityResult(ImageCropContract()) { result ->
        pendingBottomWallpaperCropRequest = null
        if (result == null) return@registerForActivityResult
        if (File(result.path).exists()) {
            updatePendingConfig { it.copy(wallpaperPath = result.path) }
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { NavigationBarManageContent(this@NavigationBarManageActivity) }
        updateContainerMenu()
        loadPackages()
        observeWebDavTasks()
        observeEvent<String>(EventBus.RECREATE) {
            loadPackages()
        }
    }

    override fun onResume() {
        super.onResume()
        bottomNavItems = MainBottomNavConfig.items()
        updateContainerMenu()
    }

    internal fun updatePendingConfig(transform: (NavigationBarIconConfig.Config) -> NavigationBarIconConfig.Config) {
        pendingConfig = pendingConfig?.let { normalizeStandardBottomConfig(transform(it)) }
    }

    private fun normalizeStandardBottomConfig(config: NavigationBarIconConfig.Config): NavigationBarIconConfig.Config {
        if (config.layoutMode == "standard") {
            return config.copy(effectMode = "solid")
        }
        return config
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
            val list = withContext(Dispatchers.IO) { AppCloudStorage.listContainers().filter { it.enabled } }
            if (list.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            containers = list
            containerLabels = list.map(AppCloudStorage::containerDisplayLabel)
            val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
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
                    NavigationBarIconConfig.loadEntries(isNightMode, includeRemote = true, cloudContainerId, CLOUD_SCOPE)
                }
            }.onSuccess {
                if (version != loadVersion || isFinishing || isDestroyed) return@onSuccess
                entries = it
                activeDirName = NavigationBarIconConfig.activeDirName(isNightMode)
                summaryText = if (it.size <= 1) {
                    getString(R.string.navigation_bar_package_empty)
                } else {
                    getString(R.string.navigation_bar_package_summary)
                }
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

    internal fun saveBottomNavItems(items: List<MainBottomNavConfig.ItemState>) {
        val normalized = items.map { item ->
            val spec = MainBottomNavConfig.spec(item.key)
            if (spec?.lockedVisible == true) {
                item.copy(visible = true)
            } else {
                item
            }
        }
        MainBottomNavConfig.save(normalized)
        bottomNavItems = MainBottomNavConfig.items()
    }

    internal fun showEditDialog(entry: NavigationBarIconConfig.Entry?) {
        val base = entry ?: NavigationBarIconConfig.Entry(
            NavigationBarIconConfig.Config(
                name = nextPackageName(),
                isNightMode = isNightMode,
                layoutMode = AppConfig.bottomBarLayoutMode,
                sidebarGravity = AppConfig.bottomBarSidebarGravity,
                effectMode = AppConfig.bottomBarEffectMode,
                opacity = if (AppConfig.bottomBarEffectMode == "frosted") AppConfig.frostedGlassLevel else AppConfig.liquidGlassLevel
            ),
            NavigationBarIconConfig.Source.LOCAL,
            ""
        )
        if (base.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        if (base.localDir == null && entry != null && base.source == NavigationBarIconConfig.Source.REMOTE) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) { NavigationBarIconConfig.download(base, cloudContainerId, CLOUD_SCOPE) }
                }.onSuccess {
                    showEditDialog(it)
                }.onFailure {
                    toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        editingEntry = base
        pendingConfig = normalizeStandardBottomConfig(base.config.copy(icons = base.config.icons.toMutableMap()))
        editDialogOpen = true
    }

    internal fun onEditNameChanged(name: String) {
        pendingConfig = pendingConfig?.copy(name = name)
    }

    internal fun onEditSave() {
        editDialogOpen = false
        val config = pendingConfig ?: return
        pendingConfig = normalizeStandardBottomConfig(config.copy(name = config.name.trim()))
        saveEditingPackage()
    }

    internal fun onLayoutModeSelected(index: Int) {
        layoutModeSelectorOpen = false
        updatePendingConfig {
            it.copy(
                layoutMode = when (index) {
                    1 -> "standard"
                    2 -> "sidebar"
                    else -> "floating"
                }
            )
        }
    }

    internal fun onEffectModeSelected(index: Int) {
        effectModeSelectorOpen = false
        updatePendingConfig {
            it.copy(
                effectMode = when (index) {
                    0 -> "solid"
                    2 -> "frosted"
                    else -> "glass"
                }
            )
        }
    }

    internal fun onWallpaperMenuAction(index: Int) {
        wallpaperMenuOpen = false
        if (index == 0) {
            selectBottomWallpaper.launch {
                mode = HandleFileContract.IMAGE
                title = getString(R.string.bottom_bar_wallpaper)
            }
        } else {
            updatePendingConfig { it.copy(wallpaperPath = null) }
        }
    }

    internal fun onSidebarBgMenuAction(index: Int) {
        sidebarBgMenuOpen = false
        val currentEntry = editingEntry
        if (index == 0) {
            pendingSidebarBackgroundEntry = currentEntry
            selectSidebarBackground.launch {
                mode = HandleFileContract.IMAGE
                title = getString(R.string.navigation_bar_sidebar_background)
            }
        } else if (currentEntry != null) {
            applyIconResult(NavigationBarIconConfig.clearSidebarBackground(currentEntry))
            loadPackages()
        }
    }

    internal fun onBorderColorMenuAction(index: Int) {
        borderColorMenuOpen = false
        if (index == 0) {
            updatePendingConfig { it.copy(borderColor = null) }
        } else {
            colorPickerTarget = NAV_COLOR_BORDER
        }
    }

    internal fun onBorderColorConfirmed(color: Int) {
        colorPickerTarget = 0
        updatePendingConfig { it.copy(borderColor = color) }
    }

    internal fun applyIconResult(entry: NavigationBarIconConfig.Entry) {
        editingEntry = entry
        pendingConfig = entry.config.copy(icons = entry.config.icons.toMutableMap())
        notifyAppliedIfNeeded(entry)
    }

    internal fun onIconMenuAction(index: Int) {
        val request = iconMenuRequest ?: return
        iconMenuRequest = null
        val entry = editingEntry ?: return
        if (index == 0) {
            val code = if (request.single) {
                NAV_REQUEST_SINGLE_ICON_BASE + NavigationBarIconConfig.extraItems.indexOf(request.item)
            } else {
                NavigationBarIconConfig.items.indexOf(request.item) * 2 + if (request.selected) 1 else 0
            }
            pendingIconRequest = IconRequest(code, entry, request.item, request.selected, request.single)
            selectIcon.launch {
                mode = HandleFileContract.FILE
                requestCode = code
                title = getString(R.string.navigation_icon_select_file)
                allowExtensions = arrayOf("ico", "svg", "png", "jpg", "jpeg")
            }
        } else {
            val cleared = if (request.single) {
                NavigationBarIconConfig.clearSingleIcon(entry, request.item.key)
            } else {
                NavigationBarIconConfig.clearIcon(entry, request.item.key, request.selected)
            }
            applyIconResult(cleared)
            loadPackages()
            toastOnUi(R.string.success)
        }
    }

    private fun startBottomWallpaperCrop(uri: Uri) {
        val metrics = resources.displayMetrics
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = NAV_REQUEST_BOTTOM_WALLPAPER,
            aspectWidth = metrics.widthPixels.coerceAtLeast(1),
            aspectHeight = (96 * metrics.density).toInt().coerceAtLeast(1),
            dirName = "bottomBarWallpapers",
            prefix = "bottom_bar",
            targetWidth = 1600
        )
        pendingBottomWallpaperCropRequest = request
        cropBottomWallpaper.launch(request.params)
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.navigation_bar_custom_name)
        val usedNames = entries.map { it.config.name }.toSet()
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
                    NavigationBarIconConfig.addOrUpdate(config, oldEntry)
                }
            }.onSuccess {
                if (notifyAppliedIfNeeded(it)) {
                    AppearanceKitManager.syncCurrentNavigationBarRef(it.config.isNightMode, it)
                }
                toastOnUi(R.string.theme_saved_local)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showNavigationBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    internal fun actionsFor(entry: NavigationBarIconConfig.Entry): List<Pair<String, () -> Unit>> {
        val actions = buildList {
            add(NavAction.APPLY)
            if (entry.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME) {
                add(NavAction.EDIT)
                add(NavAction.EXPORT)
                if (entry.source != NavigationBarIconConfig.Source.REMOTE) add(NavAction.UPLOAD)
                if (entry.source != NavigationBarIconConfig.Source.LOCAL) add(NavAction.DOWNLOAD)
                if (entry.source != NavigationBarIconConfig.Source.REMOTE) add(NavAction.DELETE_LOCAL)
                if (entry.source != NavigationBarIconConfig.Source.LOCAL) add(NavAction.DELETE_REMOTE)
                if (entry.source == NavigationBarIconConfig.Source.BOTH) add(NavAction.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            getString(action.titleRes) to {
                when (action) {
                    NavAction.APPLY -> applyPackage(entry)
                    NavAction.EDIT -> showEditDialog(entry)
                    NavAction.EXPORT -> exportPackage(entry)
                    NavAction.UPLOAD -> enqueueUpload(entry)
                    NavAction.DOWNLOAD -> runAction {
                        NavigationBarIconConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    NavAction.DELETE_LOCAL -> confirmDelete(
                        getString(R.string.navigation_bar_delete_local_confirm)
                    ) {
                        NavigationBarIconConfig.deleteLocal(entry)
                        postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                    }
                    NavAction.DELETE_REMOTE -> confirmDelete(
                        getString(R.string.navigation_bar_delete_remote_confirm)
                    ) {
                        NavigationBarIconConfig.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    NavAction.DELETE_BOTH -> confirmDelete(
                        getString(R.string.navigation_bar_delete_both_confirm)
                    ) {
                        NavigationBarIconConfig.delete(entry, cloudContainerId, CLOUD_SCOPE)
                        postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                    }
                }
            }
        }
    }

    private fun confirmDelete(message: String, block: suspend () -> Unit) {
        deleteRequest = message to block
    }

    internal fun applyPackage(entry: NavigationBarIconConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    if (entry.source == NavigationBarIconConfig.Source.REMOTE) {
                        NavigationBarIconConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    } else {
                        entry
                    }
                }
            }.onSuccess {
                NavigationBarIconConfig.apply(it)
                AppearanceKitManager.syncCurrentNavigationBarRef(it.config.isNightMode, it)
                postEvent(EventBus.NAVIGATION_BAR_CHANGED, it.config.isNightMode)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun enqueueUpload(entry: NavigationBarIconConfig.Entry) {
        val queued = enqueueUploadTask(entry)
        toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        if (queued) {
            showNavigationBarSyncTasks()
        }
    }

    private fun enqueueUploadIfNeeded(entry: NavigationBarIconConfig.Entry): Boolean {
        if (!AppConfig.syncThemePackages) return false
        return enqueueUploadTask(entry)
    }

    private fun enqueueUploadTask(entry: NavigationBarIconConfig.Entry): Boolean {
        return WebDavTaskManager.enqueueUpload(
            key = "navigation_bar_upload:${entry.config.isNightMode}:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD,
            runningMessage = getString(R.string.navigation_bar_upload)
        ) {
            NavigationBarIconConfig.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
    }

    private fun observeWebDavTasks() {
        seedHandledWebDavTasks(WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD)
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                var shouldReload = false
                var failedMessage: String? = null
                states.values
                    .filter { it.type == WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD }
                    .filter { it.status == WebDavTaskStatus.COMPLETED || it.status == WebDavTaskStatus.FAILED }
                    .forEach { state ->
                        if (handledWebDavTasks.add(webDavTaskHandleKey(state.key, state.status))) {
                            shouldReload = true
                            if (state.status == WebDavTaskStatus.FAILED) {
                                failedMessage = state.message
                            }
                        }
                    }
                if (shouldReload) {
                    loadPackages()
                    failedMessage?.let { toastOnUi(getString(R.string.theme_sync_failed, it)) }
                }
            }
        }
    }

    private fun seedHandledWebDavTasks(type: WebDavTaskType) {
        WebDavTaskManager.states.value.values
            .filter { it.type == type }
            .filter { it.status == WebDavTaskStatus.COMPLETED || it.status == WebDavTaskStatus.FAILED }
            .forEach { handledWebDavTasks.add(webDavTaskHandleKey(it.key, it.status)) }
    }

    private fun webDavTaskHandleKey(key: String, status: WebDavTaskStatus): String {
        return "$key:$status"
    }

    internal fun showNavigationBarSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD))
    }

    private fun exportPackage(entry: NavigationBarIconConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { NavigationBarIconConfig.exportZip(entry) }
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
                val file = externalFiles.getFile("navigationBarImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                withContext(Dispatchers.IO) { NavigationBarIconConfig.importPackage(file) }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showNavigationBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
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

    private fun notifyAppliedIfNeeded(entry: NavigationBarIconConfig.Entry): Boolean {
        if (entry.dirName == NavigationBarIconConfig.activeDirName(entry.config.isNightMode)) {
            NavigationBarIconConfig.apply(entry)
            postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
            return true
        }
        return false
    }

    internal fun effectModeLabel(value: String): String {
        return when (value) {
            "solid" -> getString(R.string.bottom_bar_effect_solid)
            "frosted" -> getString(R.string.bottom_bar_effect_frosted)
            else -> getString(R.string.bottom_bar_effect_glass)
        }
    }

    internal fun layoutModeLabel(value: String): String {
        return when (value) {
            "sidebar" -> getString(R.string.bottom_bar_layout_sidebar)
            "standard" -> getString(R.string.bottom_bar_layout_standard)
            else -> getString(R.string.bottom_bar_layout_floating)
        }
    }

    internal fun bottomNavSummary(items: List<MainBottomNavConfig.ItemState>): String {
        val visible = items.filter { it.visible || MainBottomNavConfig.spec(it.key)?.lockedVisible == true }
        return visible.joinToString(" / ") { item ->
            MainBottomNavConfig.spec(item.key)?.let { getString(it.titleRes) } ?: item.key
        }
    }

    internal fun entryInfo(entry: NavigationBarIconConfig.Entry): String {
        return buildString {
            append(layoutModeLabel(entry.config.layoutMode))
            if (entry.config.layoutMode == "floating") {
                append(" \u00B7 ")
                append(effectModeLabel(entry.config.effectMode))
            }
            if (entry.config.layoutMode != "sidebar") {
                append(" \u00B7 ")
                append(getString(R.string.bottom_bar_opacity))
                append(" ")
                append(entry.config.opacity)
                append("%")
                if (entry.config.layoutMode == "standard" && !entry.config.wallpaperPath.isNullOrBlank()) {
                    append(" \u00B7 ")
                    append(getString(R.string.bottom_bar_wallpaper))
                }
            }
            if (entry.config.updatedAt > 0) {
                append(" \u00B7 ")
                append(navDateFormat.format(Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))))
            }
        }
    }

    private enum class NavAction(val titleRes: Int) {
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
    }
}

@Composable
private fun NavigationBarManageContent(activity: NavigationBarManageActivity) {
    val density = LocalVisualSpec.current.density

    HostV5Page(
        title = stringResource(R.string.navigation_bar_manage),
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
                    onClick = { activity.showNavigationBarSyncTasks() },
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
                    title = "暂无导航栏配置",
                    supporting = "点击下方「添加」创建或导入导航栏包",
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
                    item(key = "main_bottom_bar_items") {
                        AppCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { activity.bottomNavItemsDialogOpen = true },
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    text = stringResource(R.string.bottom_bar_items_manage),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = activity.bottomNavSummary(activity.bottomNavItems),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    items(
                        activity.entries,
                        key = { "${it.dirName}_${it.config.isNightMode}" }
                    ) { entry ->
                        NavItemRow(activity, entry)
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

    if (activity.bottomNavItemsDialogOpen) {
        AppDialog(onDismiss = { activity.bottomNavItemsDialogOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(density.screenPadding)) {
                Text(
                    text = stringResource(R.string.bottom_bar_items_manage),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(density.blockGap / 2))
                BottomNavItemsManageContent(
                    initialItems = activity.bottomNavItems,
                    onItemsChange = { activity.saveBottomNavItems(it) },
                )
            }
        }
    }

    NavEditDialogHost(activity)
}

@Composable
private fun NavEditDialogHost(activity: NavigationBarManageActivity) {
    val config = activity.pendingConfig ?: return
    val density = LocalVisualSpec.current.density
    val context = LocalContext.current

    AppDialog(onDismiss = { activity.editDialogOpen = false }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            Text(
                text = stringResource(R.string.navigation_bar_edit),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = config.name,
                onValueChange = { activity.onEditNameChanged(it) },
                placeholder = stringResource(R.string.navigation_bar_name),
                singleLine = true,
            )
            AppListItem(
                headline = stringResource(R.string.bottom_bar_layout_mode),
                supporting = activity.layoutModeLabel(config.layoutMode),
                card = false,
                onClick = { activity.layoutModeSelectorOpen = true },
            )
            if (config.layoutMode != "sidebar") {
                if (config.layoutMode == "floating") {
                    AppListItem(
                        headline = stringResource(R.string.bottom_bar_material_mode),
                        supporting = activity.effectModeLabel(config.effectMode),
                        card = false,
                        onClick = { activity.effectModeSelectorOpen = true },
                    )
                    AppRowWithSwitch(
                        title = stringResource(R.string.search),
                        checked = !config.hideSearchInFloatingStyle,
                        onCheckedChange = { enabled ->
                            activity.updatePendingConfig {
                                it.copy(hideSearchInFloatingStyle = !enabled)
                            }
                        },
                        card = false,
                    )
                }
                if (config.layoutMode == "standard") {
                    AppListItem(
                        headline = stringResource(R.string.bottom_bar_wallpaper),
                        supporting = if (config.wallpaperPath.isNullOrBlank()) {
                            stringResource(R.string.theme_image_select)
                        } else {
                            stringResource(R.string.theme_image_selected)
                        },
                        card = false,
                        onClick = { activity.wallpaperMenuOpen = true },
                    )
                }
                AppListItem(
                    headline = stringResource(R.string.bottom_bar_opacity),
                    supporting = "${config.opacity}%",
                    card = false,
                    onClick = { activity.opacityPickerOpen = true },
                )
                AppListItem(
                    headline = stringResource(R.string.bottom_bar_border_color),
                    supporting = config.borderColor?.let { colorLabel(it) }
                        ?: stringResource(R.string.disable),
                    card = false,
                    onClick = { activity.borderColorMenuOpen = true },
                )
                AppListItem(
                    headline = stringResource(R.string.bottom_bar_border_alpha),
                    supporting = "${config.borderAlpha}%",
                    card = false,
                    onClick = { activity.borderAlphaPickerOpen = true },
                )
            } else {
                AppListItem(
                    headline = stringResource(R.string.navigation_bar_sidebar_background),
                    supporting = if (config.sidebarBackgroundPath.isNullOrBlank()) {
                        stringResource(R.string.select_image)
                    } else {
                        stringResource(R.string.theme_image_selected)
                    },
                    card = false,
                    onClick = { activity.sidebarBgMenuOpen = true },
                )
            }
            val navItems = NavigationBarIconConfig.extraItems +
                NavigationBarIconConfig.items.filter {
                    config.layoutMode == "sidebar" || it.key != "ai"
                }
            val entry = activity.editingEntry
            navItems.forEach { item ->
                if (entry == null) return@forEach
                if (item.menuId == 0) {
                    NavSingleIconRow(activity, entry, item)
                } else {
                    NavPairIconRow(activity, entry, item)
                }
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

    if (activity.layoutModeSelectorOpen) {
        AppFilterDialog(
            title = stringResource(R.string.bottom_bar_layout_mode),
            options = listOf(
                stringResource(R.string.bottom_bar_layout_floating),
                stringResource(R.string.bottom_bar_layout_standard),
                stringResource(R.string.bottom_bar_layout_sidebar)
            ),
            selectedIndex = when (config.layoutMode) {
                "standard" -> 1
                "sidebar" -> 2
                else -> 0
            },
            onSelect = { activity.onLayoutModeSelected(it) },
            onDismiss = { activity.layoutModeSelectorOpen = false },
        )
    }

    if (activity.effectModeSelectorOpen) {
        AppFilterDialog(
            title = stringResource(R.string.bottom_bar_material_mode),
            options = listOf(
                stringResource(R.string.bottom_bar_effect_solid),
                stringResource(R.string.bottom_bar_effect_glass),
                stringResource(R.string.bottom_bar_effect_frosted)
            ),
            selectedIndex = when (config.effectMode) {
                "solid" -> 0
                "frosted" -> 2
                else -> 1
            },
            onSelect = { activity.onEffectModeSelected(it) },
            onDismiss = { activity.effectModeSelectorOpen = false },
        )
    }

    if (activity.wallpaperMenuOpen) {
        val hasWallpaper = !config.wallpaperPath.isNullOrBlank()
        val options = buildList {
            add(stringResource(R.string.theme_image_select))
            if (hasWallpaper) add(stringResource(R.string.theme_image_delete))
        }
        AppMenuSheet(
            title = stringResource(R.string.bottom_bar_wallpaper),
            actions = options.mapIndexed { index, label ->
                label to { activity.onWallpaperMenuAction(index) }
            },
            onDismiss = { activity.wallpaperMenuOpen = false },
        )
    }

    if (activity.sidebarBgMenuOpen) {
        val hasBg = !config.sidebarBackgroundPath.isNullOrBlank()
        val options = buildList {
            add(stringResource(R.string.select_image))
            if (hasBg) add(stringResource(R.string.delete))
        }
        AppMenuSheet(
            title = stringResource(R.string.navigation_bar_sidebar_background),
            actions = options.mapIndexed { index, label ->
                label to { activity.onSidebarBgMenuAction(index) }
            },
            onDismiss = { activity.sidebarBgMenuOpen = false },
        )
    }

    if (activity.opacityPickerOpen) {
        AppNumberPickerDialog(
            title = stringResource(R.string.bottom_bar_opacity),
            value = config.opacity,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            range = 0..100,
            step = 1,
            valueLabel = "${config.opacity}%",
            onConfirm = { value ->
                activity.opacityPickerOpen = false
                activity.updatePendingConfig { it.copy(opacity = value.coerceIn(0, 100)) }
            },
            onDismiss = { activity.opacityPickerOpen = false },
        )
    }

    if (activity.borderAlphaPickerOpen) {
        AppNumberPickerDialog(
            title = stringResource(R.string.bottom_bar_border_alpha),
            value = config.borderAlpha,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            range = 0..100,
            step = 1,
            valueLabel = "${config.borderAlpha}%",
            onConfirm = { value ->
                activity.borderAlphaPickerOpen = false
                activity.updatePendingConfig { it.copy(borderAlpha = value.coerceIn(0, 100)) }
            },
            onDismiss = { activity.borderAlphaPickerOpen = false },
        )
    }

    if (activity.borderColorMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.bottom_bar_border_color),
            actions = listOf(
                stringResource(R.string.disable) to { activity.onBorderColorMenuAction(0) },
                stringResource(R.string.select_color) to { activity.onBorderColorMenuAction(1) },
            ),
            onDismiss = { activity.borderColorMenuOpen = false },
        )
    }

    if (activity.colorPickerTarget == NAV_COLOR_BORDER) {
        AppColorPickerDialog(
            title = stringResource(R.string.bottom_bar_border_color),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.onBorderColorConfirmed(it) },
            onDismiss = { activity.colorPickerTarget = 0 },
            initialColor = config.borderColor
                ?: ContextCompat.getColor(context, R.color.accent),
        )
    }

    activity.iconMenuRequest?.let { request ->
        AppMenuSheet(
            title = stringResource(request.item.titleRes),
            actions = listOf(
                stringResource(R.string.select_image) to { activity.onIconMenuAction(0) },
                stringResource(R.string.delete) to { activity.onIconMenuAction(1) },
            ),
            onDismiss = { activity.iconMenuRequest = null },
        )
    }
}

private fun colorLabel(color: Int): String {
    return "#${Integer.toHexString(color).takeLast(6).uppercase(Locale.ROOT)}"
}

@Composable
private fun NavItemRow(
    activity: NavigationBarManageActivity,
    entry: NavigationBarIconConfig.Entry,
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
                text = activity.entryInfo(entry),
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
                if (entry.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME) {
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
private fun NavIconPreviewButton(
    activity: NavigationBarManageActivity,
    entry: NavigationBarIconConfig.Entry,
    item: NavigationBarIconConfig.NavItem,
    selected: Boolean,
    single: Boolean,
) {
    val context = LocalContext.current
    // pendingConfig 变化（图标写入/清除）后重新生成预览
    val bitmap = remember(entry, item, selected, single, activity.pendingConfig) {
        val drawable: Drawable? = if (single) {
            NavigationBarIconConfig.previewSingleDrawable(context, entry, item)
        } else {
            NavigationBarIconConfig.previewDrawable(context, entry, item, selected)
        }
        runCatching { drawable?.toBitmap()?.asImageBitmap() }.getOrNull()
    }
    val description = stringResource(
        when {
            single -> item.titleRes
            selected -> R.string.navigation_icon_selected
            else -> R.string.navigation_icon_normal
        }
    )
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            modifier = Modifier
                .size(44.dp)
                .padding(6.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { activity.iconMenuRequest = NavIconMenuRequest(item, selected, single) },
        )
    }
}

@Composable
private fun NavPairIconRow(
    activity: NavigationBarManageActivity,
    entry: NavigationBarIconConfig.Entry,
    item: NavigationBarIconConfig.NavItem,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(item.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        NavIconPreviewButton(activity, entry, item, selected = false, single = false)
        NavIconPreviewButton(activity, entry, item, selected = true, single = false)
    }
}

@Composable
private fun NavSingleIconRow(
    activity: NavigationBarManageActivity,
    entry: NavigationBarIconConfig.Entry,
    item: NavigationBarIconConfig.NavItem,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(item.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        NavIconPreviewButton(activity, entry, item, selected = false, single = true)
    }
}

@Composable
private fun BottomNavItemsManageContent(
    initialItems: List<MainBottomNavConfig.ItemState>,
    onItemsChange: (List<MainBottomNavConfig.ItemState>) -> Unit
) {
    val palette = rememberAppManagementPalette()
    val listState = rememberLazyListState()
    var orderedItems by remember { mutableStateOf(initialItems) }
    LaunchedEffect(initialItems) {
        orderedItems = initialItems
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        orderedItems = orderedItems.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .heightIn(max = 460.dp)
            .padding(bottom = 4.dp)
    ) {
        items(
            items = orderedItems,
            key = { it.key }
        ) { item ->
            val spec = MainBottomNavConfig.spec(item.key) ?: return@items
            val locked = spec.lockedVisible
            ReorderableItem(reorderState, key = item.key) {
                AppManagementListRow(
                    title = stringResource(spec.titleRes),
                    subtitle = if (locked) {
                        stringResource(R.string.bottom_bar_item_locked)
                    } else if (item.visible) {
                        stringResource(R.string.enabled)
                    } else {
                        stringResource(R.string.bottom_bar_item_hidden)
                    },
                    palette = palette,
                    switchChecked = if (locked) null else item.visible,
                    onSwitchChange = if (locked) null else { checked ->
                        val next = orderedItems.map { current ->
                            if (current.key == item.key) current.copy(visible = checked) else current
                        }
                        orderedItems = next
                        onItemsChange(next)
                    },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_drag_handle),
                            contentDescription = stringResource(R.string.sort),
                            tint = palette.settings.secondaryText,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .draggableHandle(
                                    onDragStopped = { onItemsChange(orderedItems) }
                                )
                        )
                    }
                )
            }
        }
    }
}
