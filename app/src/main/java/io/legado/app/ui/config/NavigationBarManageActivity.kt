package io.legado.app.ui.config

import io.legado.app.ui.widget.compose.LegadoComposeTheme
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.MainBottomNavConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppLayoutTokens
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen
import io.legado.app.ui.widget.compose.AppPackageManageSettingCard
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
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

class NavigationBarManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private var entriesState by mutableStateOf<List<NavigationBarIconConfig.Entry>>(emptyList())
    private var activeDirNameState by mutableStateOf(NavigationBarIconConfig.DEFAULT_DIR_NAME)
    private var summaryTextState by mutableStateOf("")
    private var isNightMode by mutableStateOf(AppConfig.isNightTheme)
    private var bottomNavItemsState by mutableStateOf(MainBottomNavConfig.items())
    private var editingEntry: NavigationBarIconConfig.Entry? = null
    private var editingEntryState by mutableStateOf<NavigationBarIconConfig.Entry?>(null)
    private var editingConfigState by mutableStateOf<NavigationBarIconConfig.Config?>(null)
    private var editingNameState by mutableStateOf("")
    private var isSavingEditState by mutableStateOf(false)
    private var editDialogVersion by mutableIntStateOf(0)
    private var pendingConfig: NavigationBarIconConfig.Config? = null
    private var pendingIconRequest: IconRequest? = null
    private var pendingSidebarBackgroundEntry: NavigationBarIconConfig.Entry? = null
    private var pendingBottomWallpaperCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    private var containerMenuLabelState by mutableStateOf<String?>(null)
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

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
                editingEntry = it
                pendingConfig = it.config.copy(icons = it.config.icons.toMutableMap())
                notifyAppliedIfNeeded(it)
                refreshEditDialog()
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
            requestCode = requestSidebarBackground,
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
                editingEntry = it
                pendingConfig = it.config.copy(icons = it.config.icons.toMutableMap())
                notifyAppliedIfNeeded(it)
                refreshEditDialog()
                loadPackages()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.visibility = View.GONE
        restoreEditInstanceState(savedInstanceState)
        updateContainerMenu()
        initView()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IS_NIGHT, isNightMode)
        val entry = editingEntry
        val config = pendingConfig
        if (entry != null && config != null) {
            val snapshot = NavigationBarEditSnapshot(
                entryDirName = entry.dirName,
                entrySourceName = entry.source.name,
                entryRemoteUpdatedAt = entry.remoteUpdatedAt,
                editingName = editingNameState,
                config = config.copy(icons = LinkedHashMap(config.icons))
            )
            outState.putString(STATE_EDIT_SNAPSHOT_JSON, snapshot.toJson())
        }
        pendingIconRequest?.let { req ->
            outState.putInt(STATE_PENDING_ICON_CODE, req.code)
            outState.putString(STATE_PENDING_ICON_KEY, req.item.key)
            outState.putBoolean(STATE_PENDING_ICON_SELECTED, req.selected)
            outState.putBoolean(STATE_PENDING_ICON_SINGLE, req.single)
        }
    }

    private fun restoreEditInstanceState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        if (state.containsKey(STATE_IS_NIGHT)) {
            isNightMode = state.getBoolean(STATE_IS_NIGHT)
        }
        NavigationBarEditSnapshot.fromJson(state.getString(STATE_EDIT_SNAPSHOT_JSON))?.let { snapshot ->
            val source = runCatching {
                NavigationBarIconConfig.Source.valueOf(snapshot.entrySourceName)
            }.getOrDefault(NavigationBarIconConfig.Source.LOCAL)
            val localDir = if (
                snapshot.entryDirName.isNotBlank() &&
                source != NavigationBarIconConfig.Source.REMOTE
            ) {
                NavigationBarIconConfig.localDir(snapshot.config.isNightMode, snapshot.entryDirName)
            } else {
                null
            }
            val restoredEntry = NavigationBarIconConfig.Entry(
                config = snapshot.config.copy(icons = LinkedHashMap(snapshot.config.icons)),
                source = source,
                dirName = snapshot.entryDirName,
                localDir = localDir,
                remoteUpdatedAt = snapshot.entryRemoteUpdatedAt
            )
            editingEntry = restoredEntry
            pendingConfig = restoredEntry.config.copy(icons = LinkedHashMap(restoredEntry.config.icons))
            editingNameState = snapshot.editingName
            refreshEditDialog()
            if (state.containsKey(STATE_PENDING_ICON_CODE)) {
                val itemKey = state.getString(STATE_PENDING_ICON_KEY).orEmpty()
                val single = state.getBoolean(STATE_PENDING_ICON_SINGLE, false)
                val navItem = (NavigationBarIconConfig.extraItems + NavigationBarIconConfig.items)
                    .firstOrNull { it.key == itemKey }
                if (navItem != null) {
                    pendingIconRequest = IconRequest(
                        code = state.getInt(STATE_PENDING_ICON_CODE),
                        entry = restoredEntry,
                        item = navItem,
                        selected = state.getBoolean(STATE_PENDING_ICON_SELECTED, false),
                        single = single
                    )
                }
            }
            if (restoredEntry.localDir != null) {
                pendingSidebarBackgroundEntry = restoredEntry
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bottomNavItemsState = MainBottomNavConfig.items()
        updateContainerMenu()
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) {
            loadPackages()
        }
    }

    private fun initView() {
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
                NavigationBarPackageManageScreen(
                    entries = entriesState,
                    activeDirName = activeDirNameState,
                    isNightMode = isNightMode,
                    summaryText = summaryTextState,
                    bottomNavSummary = bottomNavSummary(bottomNavItemsState),
                    topActions = topBarActions(),
                    onBack = ::supportFinishAfterTransition,
                    onSwitchDayNight = { night ->
                        if (night != isNightMode) {
                            isNightMode = night
                            loadPackages()
                        }
                    },
                    onAdd = ::showAddDialog,
                    onManageBottomNavItems = ::showBottomNavItemsDialog,
                    onApply = ::applyPackage,
                    onEdit = { entry -> showEditDialog(entry) },
                    entryInfo = ::entryInfo,
                    entryActions = ::entryActions
                )
                val dialogEntry = editingEntryState
                val dialogConfig = editingConfigState
                if (dialogEntry != null && dialogConfig != null) {
                    NavigationBarEditDialog(
                        entry = dialogEntry,
                        name = editingNameState,
                        wallpaperValue = wallpaperLabel(dialogConfig.wallpaperPath),
                        sidebarBackgroundValue = if (dialogConfig.sidebarBackgroundPath.isNullOrBlank()) {
                            getString(R.string.select_image)
                        } else {
                            getString(R.string.theme_image_selected)
                        },
                        version = editDialogVersion,
                        navItems = NavigationBarIconConfig.items
                            .filter { AppConfig.bottomBarLayoutMode == "sidebar" || it.key != "ai" }
                            .let { NavigationBarIconConfig.extraItems + it },
                        onNameChange = { editingNameState = it },
                        isSaving = isSavingEditState,
                        onClickBottomWallpaper = ::showBottomWallpaperSelector,
                        onClickSidebarBackground = ::showSidebarBackgroundSelector,
                        onClickSingleIcon = ::showSingleIconSelector,
                        onClickIcon = ::showIconSelector,
                        onDismiss = {
                            if (!isSavingEditState) dismissEditDialog()
                        },
                        onConfirm = {
                            if (!isSavingEditState) saveEditingPackage()
                        }
                    )
                }
                            }
            }
        }
        container.addView(cv, index.coerceAtMost(container.childCount))
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
                    onClick = ::showNavigationBarSyncTasks
                )
            )
        }
    }

    private val selectBottomWallpaper = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::startBottomWallpaperCrop)
    }

    private val cropBottomWallpaper = registerForActivityResult(ImageCropContract()) { result ->
        pendingBottomWallpaperCropRequest = null
        if (result == null) return@registerForActivityResult
        if (File(result.path).exists()) {
            pendingConfig?.wallpaperPath = result.path
            refreshEditDialog()
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
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

    private fun showBottomNavItemsDialog() {
        alert(R.string.bottom_bar_items_manage) {
            customView {
                ComposeView(this@NavigationBarManageActivity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        LegadoComposeTheme {
                        BottomNavItemsManageContent(
                            initialItems = bottomNavItemsState,
                            onItemsChange = ::saveBottomNavItems
                        )
                                            }
                    }
                }
            }
            okButton()
        }
    }

    private fun saveBottomNavItems(items: List<MainBottomNavConfig.ItemState>) {
        val normalized = items.map { item ->
            val spec = MainBottomNavConfig.spec(item.key)
            if (spec?.lockedVisible == true) {
                item.copy(visible = true)
            } else {
                item
            }
        }
        MainBottomNavConfig.save(normalized)
        bottomNavItemsState = MainBottomNavConfig.items()
        postEvent(EventBus.NOTIFY_MAIN, true)
    }

    private fun bottomNavSummary(items: List<MainBottomNavConfig.ItemState>): String {
        val visible = items.filter { it.visible || MainBottomNavConfig.spec(it.key)?.lockedVisible == true }
        return visible.joinToString(" / ") { item ->
            MainBottomNavConfig.spec(item.key)?.let { getString(it.titleRes) } ?: item.key
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
                    NavigationBarIconConfig.loadEntries(isNightMode, includeRemote = true, cloudContainerId, CLOUD_SCOPE)
                }
            }.onSuccess {
                if (version != loadVersion || isFinishing || isDestroyed) return@onSuccess
                entriesState = it
                activeDirNameState = NavigationBarIconConfig.activeDirName(isNightMode)
                summaryTextState = if (it.size <= 1) {
                    getString(R.string.navigation_bar_package_empty)
                } else {
                    getString(R.string.navigation_bar_package_summary)
                }
            }.onFailure {
                if (version != loadVersion || isFinishing || isDestroyed) return@onFailure
                summaryTextState = it.localizedMessage.orEmpty()
            }
        }
    }

    private fun showEditDialog(entry: NavigationBarIconConfig.Entry?) {
        val base = entry ?: NavigationBarIconConfig.Entry(
            NavigationBarIconConfig.Config(
                name = nextPackageName(),
                isNightMode = isNightMode,
                opacity = if (resolvedExploreGlassMode() == "frosted") {
                    AppConfig.frostedGlassLevel
                } else {
                    AppConfig.liquidGlassLevel
                }
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
        pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
        editingNameState = pendingConfig!!.name
        refreshEditDialog()
    }

    private fun dismissEditDialog() {
        isSavingEditState = false
        editingEntryState = null
        editingConfigState = null
        editingEntry = null
        pendingConfig = null
    }

    private fun refreshEditDialog() {
        editingEntryState = editingEntry
        editingConfigState = pendingConfig
        editDialogVersion++
    }

    private fun showSidebarBackgroundSelector() {
        val config = pendingConfig ?: return
        val currentEntry = editingEntry
        selector(
            getString(R.string.navigation_bar_sidebar_background),
            buildList {
                add(getString(R.string.select_image))
                if (!config.sidebarBackgroundPath.isNullOrBlank()) {
                    add(getString(R.string.delete))
                }
            }
        ) { _, index ->
            if (index == 0) {
                pendingSidebarBackgroundEntry = currentEntry
                selectSidebarBackground.launch {
                    mode = HandleFileContract.IMAGE
                    title = getString(R.string.navigation_bar_sidebar_background)
                }
            } else if (currentEntry != null) {
                editingEntry = NavigationBarIconConfig.clearSidebarBackground(currentEntry)
                pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                notifyAppliedIfNeeded(editingEntry!!)
                refreshEditDialog()
                loadPackages()
            }
        }
    }

    private fun showBottomWallpaperSelector() {
        val hasWallpaper = !pendingConfig?.wallpaperPath.isNullOrBlank()
        selector(
            getString(R.string.bottom_bar_wallpaper),
            buildList {
                add(getString(R.string.theme_image_select))
                if (hasWallpaper) add(getString(R.string.theme_image_delete))
            }
        ) { _, index ->
            if (index == 0) {
                selectBottomWallpaper.launch {
                    mode = HandleFileContract.IMAGE
                    title = getString(R.string.bottom_bar_wallpaper)
                }
            } else {
                pendingConfig?.wallpaperPath = null
                refreshEditDialog()
            }
        }
    }

    private fun startBottomWallpaperCrop(uri: Uri) {
        val metrics = resources.displayMetrics
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = requestBottomWallpaper,
            aspectWidth = metrics.widthPixels.coerceAtLeast(1),
            aspectHeight = (96 * metrics.density).toInt().coerceAtLeast(1),
            dirName = "bottomBarWallpapers",
            prefix = "bottom_bar",
            targetWidth = 1600
        )
        pendingBottomWallpaperCropRequest = request
        cropBottomWallpaper.launch(request.params)
    }

    private fun showSingleIconSelector(item: NavigationBarIconConfig.NavItem) {
        val entry = editingEntry ?: return
        selector(
            getString(item.titleRes),
            listOf(getString(R.string.select_image), getString(R.string.delete))
        ) { _, index ->
            if (index == 0) {
                val code = requestSingleIconBase + NavigationBarIconConfig.extraItems.indexOf(item)
                pendingIconRequest = IconRequest(code, entry, item, selected = false, single = true)
                selectIcon.launch {
                    mode = HandleFileContract.FILE
                    requestCode = code
                    title = getString(R.string.navigation_icon_select_file)
                    allowExtensions = arrayOf("ico", "svg", "png", "jpg", "jpeg")
                }
            } else {
                editingEntry = NavigationBarIconConfig.clearSingleIcon(entry, item.key)
                pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                notifyAppliedIfNeeded(editingEntry!!)
                refreshEditDialog()
                loadPackages()
            }
        }
    }

    private fun showIconSelector(item: NavigationBarIconConfig.NavItem, selected: Boolean) {
        val entry = editingEntry ?: return
        selector(
            getString(if (selected) R.string.navigation_icon_selected else R.string.navigation_icon_normal),
            listOf(getString(R.string.select_image), getString(R.string.delete))
        ) { _, index ->
            if (index == 0) {
                val code = NavigationBarIconConfig.items.indexOf(item) * 2 + if (selected) 1 else 0
                pendingIconRequest = IconRequest(code, entry, item, selected)
                selectIcon.launch {
                    mode = HandleFileContract.FILE
                    requestCode = code
                    title = getString(R.string.navigation_icon_select_file)
                    allowExtensions = arrayOf("ico", "svg", "png", "jpg", "jpeg")
                }
            } else {
                editingEntry = NavigationBarIconConfig.clearIcon(entry, item.key, selected)
                pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                notifyAppliedIfNeeded(editingEntry!!)
                refreshEditDialog()
                loadPackages()
            }
        }
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        val name = editingNameState.trim()
        if (isSavingEditState) return
        isSavingEditState = true
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.addOrUpdate(config.copy(name = name), editingEntry)
                }
            }.onSuccess {
                isSavingEditState = false
                dismissEditDialog()
                if (notifyAppliedIfNeeded(it)) {
                    AppearanceKitManager.syncCurrentNavigationBarRef(it.config.isNightMode, it)
                }
                toastOnUi(R.string.theme_saved_local)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showNavigationBarSyncTasks()
                }
            }.onFailure {
                isSavingEditState = false
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun entryActions(entry: NavigationBarIconConfig.Entry): List<AppManagementMenuAction> {
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
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action.name.startsWith("DELETE")
            ) {
                when (action) {
                    NavAction.APPLY -> applyPackage(entry)
                    NavAction.EDIT -> showEditDialog(entry)
                    NavAction.EXPORT -> exportPackage(entry)
                    NavAction.UPLOAD -> enqueueUpload(entry)
                    NavAction.DOWNLOAD -> runAction {
                        NavigationBarIconConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    NavAction.DELETE_LOCAL -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_local_confirm)
                    ) {
                        NavigationBarIconConfig.deleteLocal(entry)
                        postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                    }
                    NavAction.DELETE_REMOTE -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_remote_confirm)
                    ) {
                        NavigationBarIconConfig.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    NavAction.DELETE_BOTH -> confirmDelete(
                        entry,
                        getString(R.string.navigation_bar_delete_both_confirm)
                    ) {
                        NavigationBarIconConfig.delete(entry, cloudContainerId, CLOUD_SCOPE)
                        postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                    }
                }
            }
        }
    }

    private fun confirmDelete(
        entry: NavigationBarIconConfig.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        alert(getString(R.string.delete), message) {
            yesButton {
                runAction(block)
            }
            noButton()
        }
    }

    private fun applyPackage(entry: NavigationBarIconConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { if (entry.source == NavigationBarIconConfig.Source.REMOTE) NavigationBarIconConfig.download(entry, cloudContainerId, CLOUD_SCOPE) else entry }
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

    private fun showNavigationBarSyncTasks() {
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

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    toastOnUi(R.string.success)
                }
                .onFailure { toastOnUi(it.localizedMessage) }
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

    private fun layoutModeLabel(value: String): String {
        return when (value) {
            "sidebar" -> getString(R.string.bottom_bar_layout_sidebar)
            "standard" -> getString(R.string.bottom_bar_layout_standard)
            else -> getString(R.string.bottom_bar_layout_floating)
        }
    }

    private fun wallpaperLabel(path: String?): String {
        return if (path.isNullOrBlank()) getString(R.string.theme_image_select) else getString(R.string.theme_image_selected)
    }

    private fun entryInfo(entry: NavigationBarIconConfig.Entry): String {
        val parts = mutableListOf<String>()
        val iconCount = entry.config.icons.values.count { it.isNotBlank() }
        if (iconCount > 0) {
            parts.add("${getString(R.string.navigation_bar_manage)} ($iconCount)")
        }
        if (!entry.config.wallpaperPath.isNullOrBlank()) {
            parts.add(getString(R.string.bottom_bar_wallpaper))
        }
        if (!entry.config.sidebarBackgroundPath.isNullOrBlank()) {
            parts.add(getString(R.string.navigation_bar_sidebar_background))
        }
        if (entry.config.updatedAt > 0 || entry.remoteUpdatedAt > 0) {
            parts.add(dateFormat.format(Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))))
        }
        if (parts.isEmpty()) {
            parts.add(getString(R.string.navigation_bar_default_readonly))
        }
        return parts.joinToString(" \u00B7 ")
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.navigation_bar_custom_name)
        val usedNames = entriesState.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private data class IconRequest(
        val code: Int,
        val entry: NavigationBarIconConfig.Entry,
        val item: NavigationBarIconConfig.NavItem,
        val selected: Boolean,
        val single: Boolean = false
    )

    companion object : ActivitySearchSource {
        override fun searchEntries(context: android.content.Context): List<SettingsSearchIndex.Entry> = listOf(
            SettingsSearchIndex.Entry(
                ownerKey = "appearanceKit",
                ownerConfigTag = "",
                key = "activity_navigation_bar_manage",
                title = context.getString(R.string.navigation_bar_manage),
                summary = context.getString(R.string.navigation_bar_manage_summary),
                aliases = listOf("底栏包", "导航栏图标", "bottom bar", "navigation bar"),
                destination = SearchDestination.Activity(NavigationBarManageActivity::class.java.name)
            )
        )
        private const val CLOUD_SCOPE = "theme"
        private const val STATE_IS_NIGHT = "state_is_night"
        private const val STATE_EDIT_SNAPSHOT_JSON = "state_edit_snapshot_json"
        private const val STATE_PENDING_ICON_CODE = "state_pending_icon_code"
        private const val STATE_PENDING_ICON_KEY = "state_pending_icon_key"
        private const val STATE_PENDING_ICON_SELECTED = "state_pending_icon_selected"
        private const val STATE_PENDING_ICON_SINGLE = "state_pending_icon_single"
        const val requestSidebarBackground = 7001
        const val requestBottomWallpaper = 7003
        const val requestSingleIconBase = 7100
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
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

@Composable
private fun NavigationBarPackageManageScreen(
    entries: List<NavigationBarIconConfig.Entry>,
    activeDirName: String,
    isNightMode: Boolean,
    summaryText: String,
    bottomNavSummary: String,
    topActions: List<AppManagementAction>,
    onBack: () -> Unit,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onManageBottomNavItems: () -> Unit,
    onApply: (NavigationBarIconConfig.Entry) -> Unit,
    onEdit: (NavigationBarIconConfig.Entry) -> Unit,
    entryInfo: (NavigationBarIconConfig.Entry) -> String,
    entryActions: (NavigationBarIconConfig.Entry) -> List<AppManagementMenuAction>
) {
    val applyText = stringResource(R.string.theme_apply)
    val appliedText = stringResource(R.string.theme_applied_state)
    val editText = stringResource(R.string.edit)
    AppPackageManageScreen(
        title = stringResource(R.string.navigation_bar_manage),
        onBack = onBack,
        topActions = topActions,
        isNightMode = isNightMode,
        summaryText = summaryText,
        addText = stringResource(R.string.theme_add),
        onSwitchDayNight = onSwitchDayNight,
        onAdd = onAdd,
        headerContent = { palette ->
            item(key = "main_bottom_bar_items") {
                AppPackageManageSettingCard(
                    title = stringResource(R.string.bottom_bar_items_manage),
                    info = bottomNavSummary,
                    valueText = stringResource(R.string.edit),
                    palette = palette,
                    onClick = onManageBottomNavItems
                )
            }
        }
    ) { palette ->
        items(
            entries,
            key = { "${it.dirName}_${it.config.isNightMode}" }
        ) { entry ->
            val isActive = entry.dirName == activeDirName
            AppPackageManageItemCard(
                title = entry.config.name,
                info = entryInfo(entry),
                isActive = isActive,
                canEdit = entry.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME,
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
            .padding(bottom = 4.dp),
        // 卡片去掉 4dp 垂直外边距后，行间距不再由卡片自带，必须由列表显式给（否则拖拽行两两贴死）。
        // 同理水平基准也不再由卡片持有，须由本列表给，否则拖拽行贴弹窗左右边。
        contentPadding = PaddingValues(horizontal = AppLayoutTokens.PageHorizontal),
        verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
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
