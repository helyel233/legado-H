package io.legado.app.ui.config

import io.legado.app.ui.widget.compose.LegadoComposeTheme
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.widget.compose.releaseComposeImage
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.DialogImageBlurringBinding
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.defaultThemeTextColor
import io.legado.app.lib.theme.defaultThemeTextColorHex
import io.legado.app.lib.theme.loadUiTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppComposePopupAnchorButton
import io.legado.app.ui.widget.compose.AppComposeTopBar
import io.legado.app.ui.widget.compose.AppLayoutTokens
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.imageBgTextShadow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoSegmentedControl
import io.legado.app.ui.widget.compose.SegmentedControlTone
import io.legado.app.ui.widget.compose.SegmentedOption
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LocalAppManagementGlassSignature
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThemeManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ColorPickerDialogListener,
    FontSelectDialog.CallBack {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private var entriesState by mutableStateOf<List<ThemePackageManager.Entry>>(emptyList())
    private var summaryTextState by mutableStateOf("")
    private var isNightTheme by mutableStateOf(AppConfig.isNightTheme)
    private var editingDraftState by mutableStateOf<ThemeEditDraft?>(null)
    private var isSavingDraftState by mutableStateOf(false)
    private var editingEntry: ThemePackageManager.Entry? = null
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    private var containerMenuLabelState by mutableStateOf<String?>(null)
    private val pendingRemoteSyncTasks = linkedMapOf<String, RemoteSyncTask>()
    @Volatile
    private var syncingRemoteTasks = false
    private var appliedDayThemeOverride by mutableStateOf<String?>(null)
    private var appliedNightThemeOverride by mutableStateOf<String?>(null)
    private var pendingImageCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            startImageCrop(uri, it.requestCode)
        }
    }
    private val cropImage = registerForActivityResult(ImageCropContract()) { result ->
        val request = pendingImageCropRequest ?: return@registerForActivityResult
        pendingImageCropRequest = null
        if (result == null) {
            return@registerForActivityResult
        }
        val resultPath = result.path
        if (java.io.File(resultPath).exists()) {
            val current = editingDraftState ?: return@registerForActivityResult
            editingDraftState = when (request.requestCode) {
                requestMainBackground -> current.copy(
                    mainBackgroundPath = resultPath,
                    mainBackgroundCrop = formatMainBackgroundCrop(result)
                )
                requestBookInfoBackground -> current.copy(
                    bookInfoBackgroundPath = resultPath
                )
                requestPanelBackground -> current.copy(
                    panelBackgroundPath = resultPath
                )
                else -> current
            }
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }
    private val importThemePackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            importThemeZip(uri)
        }
    }
    private val exportThemePackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let {
            toastOnUi(getString(R.string.theme_zip_exported))
        }
    }
    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.visibility = View.GONE
        savedInstanceState?.let { state ->
            if (state.containsKey(STATE_IS_NIGHT)) {
                isNightTheme = state.getBoolean(STATE_IS_NIGHT)
            }
            editingDraftState = ThemeEditDraft.fromJson(state.getString(STATE_EDIT_DRAFT_JSON))
        }
        updateContainerButton()
        restorePendingRemoteSyncTasks()
        initView()
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.ensureLocalAppliedTheme(this@ThemeManageActivity, false)
                ThemePackageManager.ensureLocalAppliedTheme(this@ThemeManageActivity, true)
            }
            loadThemes()
        }
        schedulePendingRemoteSyncTasks()
        observeWebDavTasks()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IS_NIGHT, isNightTheme)
        editingDraftState?.let { draft ->
            outState.putString(STATE_EDIT_DRAFT_JSON, draft.toJson())
        }
    }

    override fun onResume() {
        super.onResume()
        updateContainerButton()
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
                ThemePackageManageScreen(
                    entries = entriesState,
                    isNightTheme = isNightTheme,
                    summaryText = summaryTextState,
                    topActions = topBarActions(),
                    onBack = ::supportFinishAfterTransition,
                    onSwitchDayNight = { night ->
                        if (night != isNightTheme) {
                            isNightTheme = night
                            loadThemes()
                        }
                    },
                    onAdd = ::showAddDialog,
                    onApply = ::applyTheme,
                    onEdit = { entry -> showEditDialog(entry) },
                    isApplied = ::isApplied,
                    entryInfo = ::entryInfo,
                    entryActions = ::entryActions,
                    previewData = ::previewData
                )
                editingDraftState?.let { draft ->
                    ThemePackageEditDialog(
                        draft = draft,
                        callbacks = buildThemeEditCallbacks(),
                        colorIds = themeEditColorIds,
                        isSaving = isSavingDraftState
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
                    onClick = ::showThemeSyncTasks
                )
            )
        }
    }

    private fun updateContainerButton() {
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
                        updateContainerButton()
                        loadThemes()
                    }
                }
            )
        }
    }
    private fun loadThemes() {
        val version = ++loadVersion
        val useCloud = AppConfig.syncThemePackages
        summaryTextState = appendPendingRemoteSummary(getString(R.string.theme_package_summary_default))
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.load(isNightTheme, cloudContainerId, CLOUD_SCOPE)
            }.onSuccess {
                if (version != loadVersion) return@onSuccess
                if (isFinishing || isDestroyed) return@onSuccess
                entriesState = it
                summaryTextState = appendPendingRemoteSummary(
                    if (it.isEmpty()) {
                        getString(
                            R.string.theme_package_empty,
                            getString(if (isNightTheme) R.string.theme_night_short else R.string.theme_day_short)
                        )
                    } else {
                        getString(R.string.theme_package_summary_default)
                    }
                )
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                if (version != loadVersion) return@onFailure
                if (isFinishing || isDestroyed) return@onFailure
                summaryTextState = if (useCloud) {
                    getString(R.string.theme_package_cloud_load_failed, it.localizedMessage)
                } else {
                    getString(R.string.theme_package_load_failed, it.localizedMessage)
                }
            }
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
                0 -> showManualAddDialog()
                1 -> importThemePackage.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.theme_import_zip)
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun showManualAddDialog() {
        openEditDraft(currentConfig(), null)
    }

    private fun showEditDialog(entry: ThemePackageManager.Entry) {
        if (entry.source == ThemePackageManager.Source.BUILTIN) {
            toastOnUi(R.string.theme_builtin_export_forbidden)
            return
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                if (entry.source == ThemePackageManager.Source.REMOTE) {
                    ThemePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                } else {
                    entry
                }
            }.onSuccess { localEntry ->
                openEditDraft(ThemePackageManager.getConfig(localEntry), localEntry)
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_package_read_failed, it.localizedMessage))
            }
        }
    }

    private fun openEditDraft(
        current: ThemeConfig.Config,
        entry: ThemePackageManager.Entry?
    ) {
        val configNight = current.isNightTheme
        editingEntry = entry
        editingDraftState = ThemeEditDraft(
            themeName = current.themeName,
            isNightTheme = isNightTheme,
            editingConfigNight = configNight,
            editingEntryDirName = entry?.dirName,
            editingEntryNight = entry?.packageInfo?.isNightTheme,
            nameEditable = entry?.source != ThemePackageManager.Source.REMOTE,
            canDelete = entry?.source == ThemePackageManager.Source.LOCAL,
            primaryColorHex = normalizeColor(current.primaryColor, ThemeStore.primaryColor(this)),
            accentColorHex = normalizeColor(current.accentColor, ThemeStore.accentColor(this)),
            backgroundColorHex = normalizeColor(current.backgroundColor, ThemeStore.backgroundColor(this)),
            bottomBackgroundHex = normalizeColor(current.bottomBackground, ThemeStore.bottomBackground(this)),
            blur = current.backgroundImgBlur.coerceIn(0, 25),
            mainBackgroundPath = current.backgroundImgPath,
            mainBackgroundCrop = current.backgroundImgCrop,
            bookInfoBackgroundPath = current.bookInfoBackgroundImgPath,
            panelBackgroundPath = current.panelBackgroundImgPath,
            panelBackgroundScaleType = current.panelBackgroundScaleType ?: ThemeConfig.PANEL_BG_CROP,
            panelBorderColor = normalizeOptionalColor(current.panelBorderColor),
            panelBorderAlpha = (current.panelBorderAlpha ?: 100).coerceIn(0, 100),
            uiCornerScale = (current.uiCornerScale ?: themeUiCornerScale(configNight)).coerceIn(0f, 3f),
            uiLayoutAlpha = (current.uiLayoutAlpha ?: themeUiLayoutAlpha(configNight)).coerceIn(0, 100),
            dialogAlpha = (current.dialogAlpha ?: themeDialogAlpha(configNight)).coerceIn(0, 100),
            cardColor = normalizeOptionalColor(current.cardColor),
            mutedColor = normalizeOptionalColor(current.mutedColor),
            searchFieldBackgroundColor = normalizeOptionalColor(current.searchFieldBackgroundColor),
            tabBackgroundColor = normalizeOptionalColor(current.tabBackgroundColor),
            shelfColor = normalizeOptionalColor(current.shelfColor),
            cardShadow = current.cardShadow,
            cardBackgroundBlur = current.cardBackgroundBlur,
            fontScale = current.fontScale ?: getPrefInt(ThemeRuntimeKeys.fontScale(configNight), 0),
            uiCornerSearchFollow = current.uiCornerSearchFollow ?: themeUiCornerSearchFollow(configNight),
            uiCornerReplyFollow = current.uiCornerReplyFollow ?: themeUiCornerReplyFollow(configNight),
            uiFontPath = current.uiFontPath ?: getPrefString(ThemeRuntimeKeys.uiFontPath(configNight)).orEmpty(),
            titleFontPath = current.titleFontPath ?: getPrefString(ThemeRuntimeKeys.titleFontPath(configNight)).orEmpty(),
            uiFontColor = normalizeOptionalColor(
                current.uiFontColor ?: getPrefString(ThemeRuntimeKeys.uiFontColor(configNight)).orEmpty()
                    .takeIf { it.isNotBlank() }
                ?: defaultThemeTextColorHex(configNight)
            ),
            titleFontColor = normalizeOptionalColor(
                current.titleFontColor ?: getPrefString(ThemeRuntimeKeys.titleFontColor(configNight)).orEmpty()
                    .takeIf { it.isNotBlank() }
                ?: defaultThemeTextColorHex(configNight)
            )
        )
    }

    private fun resolvedEditingEntry(): ThemePackageManager.Entry? {
        editingEntry?.let { return it }
        val draft = editingDraftState ?: return null
        val dirName = draft.editingEntryDirName ?: return null
        val night = draft.editingEntryNight ?: isNightTheme
        return entriesState.firstOrNull {
            it.dirName == dirName && it.packageInfo.isNightTheme == night
        }
    }

    private val themeEditColorIds = ThemeEditColorIds(
        primary = colorPrimary,
        accent = colorAccent,
        background = colorBackground,
        bottomBackground = colorBottomBackground,
        card = colorCard,
        muted = colorMuted,
        searchFieldBackground = colorSearchFieldBackground,
        tabBackground = colorTabBackground,
        shelf = colorShelf,
        uiFont = colorUiFont,
        titleFont = colorTitleFont
    )

    private fun buildThemeEditCallbacks(): ThemeEditDialogCallbacks {
        return ThemeEditDialogCallbacks(
            onDraftChange = { next -> editingDraftState = next },
            onPickRequiredColor = { targetId, currentHex ->
                val fallback = when (targetId) {
                    colorPrimary -> ThemeStore.primaryColor(this)
                    colorAccent -> ThemeStore.accentColor(this)
                    colorBackground -> ThemeStore.backgroundColor(this)
                    else -> ThemeStore.bottomBackground(this)
                }
                ColorPickerDialog.newBuilder()
                    .setColor(colorIntOrDefault(currentHex, fallback))
                    .setShowAlphaSlider(false)
                    .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                    .setDialogId(targetId)
                    .show(this)
            },
            onPickOptionalColor = { targetId, titleRes, currentHex ->
                selector(
                    getString(titleRes),
                    listOf(getString(R.string.theme_value_follow_default), getString(R.string.select_color))
                ) { _, index ->
                    if (index == 0) {
                        setOptionalColor(targetId, null)
                    } else {
                        ColorPickerDialog.newBuilder()
                            .setColor(colorIntOrDefault(currentHex, optionalColorFallback(targetId)))
                            .setShowAlphaSlider(false)
                            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                            .setDialogId(targetId)
                            .show(this)
                    }
                }
            },
            onClearOptionalColor = { targetId -> setOptionalColor(targetId, null) },
            onPickImage = ::showImageActions,
            onPickPanelScaleMode = ::showPanelScaleModeSelector,
            onPickBorderColor = ::showBorderColorSelector,
            onPickCardShadow = ::showCardShadowPicker,
            onPickCardBlur = ::showCardBlurPicker,
            onPickFontScale = ::showFontScalePicker,
            onPickFont = { target ->
                editingDraftState = editingDraftState?.copy(fontTarget = target)
                showDialogFragment<FontSelectDialog>()
            },
            fontDisplayName = ::uiFontDisplayName,
            onDelete = {
                val localEntry = resolvedEditingEntry()?.takeIf { it.source == ThemePackageManager.Source.LOCAL }
                    ?: return@ThemeEditDialogCallbacks
                confirmDeleteTheme(
                    localEntry,
                    getString(R.string.theme_delete_local_confirm)
                ) {
                    ThemePackageManager.deleteLocal(localEntry)
                    withContext(Dispatchers.Main) {
                        isSavingDraftState = false
                        editingDraftState = null
                        editingEntry = null
                        loadThemes()
                    }
                }
            },
            onDismiss = {
                if (isSavingDraftState) return@ThemeEditDialogCallbacks
                editingDraftState = null
                editingEntry = null
            },
            onConfirm = {
                if (isSavingDraftState) return@ThemeEditDialogCallbacks
                val draft = editingDraftState ?: return@ThemeEditDialogCallbacks
                saveDraft(draft)
            }
        )
    }

    private fun optionalColorFallback(target: Int): Int {
        return when (target) {
            colorUiFont, colorTitleFont -> defaultThemeTextColor(isNightTheme)
            else -> accentColor
        }
    }

    private fun showImageActions(target: ThemeEditImageTarget) {
        val draft = editingDraftState ?: return
        val hasImage = when (target) {
            ThemeEditImageTarget.MAIN -> !draft.mainBackgroundPath.isNullOrBlank()
            ThemeEditImageTarget.BOOK_INFO -> !draft.bookInfoBackgroundPath.isNullOrBlank()
            ThemeEditImageTarget.PANEL -> !draft.panelBackgroundPath.isNullOrBlank()
        }
        val actions = buildList {
            if (target == ThemeEditImageTarget.MAIN) add(ThemeImageAction.BLUR)
            add(ThemeImageAction.SELECT)
            if (hasImage) add(ThemeImageAction.DELETE)
        }
        val title = when (target) {
            ThemeEditImageTarget.MAIN -> R.string.theme_image_main_background
            ThemeEditImageTarget.BOOK_INFO -> R.string.theme_image_book_info_background
            ThemeEditImageTarget.PANEL -> R.string.theme_image_panel_background
        }
        selector(
            getString(title),
            actions.map { getString(it.titleRes) }
        ) { _, index ->
            when (actions[index]) {
                ThemeImageAction.BLUR -> showBlurDialog()
                ThemeImageAction.SELECT -> selectImage.launch {
                    requestCode = when (target) {
                        ThemeEditImageTarget.MAIN -> requestMainBackground
                        ThemeEditImageTarget.BOOK_INFO -> requestBookInfoBackground
                        ThemeEditImageTarget.PANEL -> requestPanelBackground
                    }
                    mode = HandleFileContract.IMAGE
                }
                ThemeImageAction.DELETE -> {
                    editingDraftState = when (target) {
                        ThemeEditImageTarget.MAIN -> editingDraftState?.copy(
                            mainBackgroundPath = "",
                            mainBackgroundCrop = null
                        )
                        ThemeEditImageTarget.BOOK_INFO -> editingDraftState?.copy(
                            bookInfoBackgroundPath = ""
                        )
                        ThemeEditImageTarget.PANEL -> editingDraftState?.copy(
                            panelBackgroundPath = ""
                        )
                    }
                }
            }
        }
    }

    private fun showBlurDialog() {
        val currentBlur = editingDraftState?.blur ?: 0
        alert(R.string.theme_image_blur) {
            val blurBinding = DialogImageBlurringBinding.inflate(layoutInflater).apply {
                seekBar.progress = currentBlur
                textViewValue.text = currentBlur.toString()
                seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: android.widget.SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        textViewValue.text = progress.toString()
                    }
                })
            }
            customView { blurBinding.root }
            okButton {
                val nextBlur = blurBinding.seekBar.progress.coerceIn(0, 25)
                editingDraftState = editingDraftState?.copy(blur = nextBlur)
            }
            cancelButton()
        }
    }

    private fun showPanelScaleModeSelector() {
        val modes = listOf(ThemeConfig.PANEL_BG_CROP, ThemeConfig.PANEL_BG_FIT)
        selector(
            getString(R.string.theme_image_panel_background_mode),
            modes.map { mode ->
                getString(
                    if (mode == ThemeConfig.PANEL_BG_FIT) R.string.theme_image_mode_fit
                    else R.string.theme_image_mode_crop
                )
            }
        ) { _, index ->
            editingDraftState = editingDraftState?.copy(panelBackgroundScaleType = modes[index])
        }
    }

    private fun showBorderColorSelector() {
        selector(
            getString(R.string.theme_panel_border_color),
            listOf(getString(R.string.theme_border_follow_accent), getString(R.string.select_color))
        ) { _, index ->
            if (index == 0) {
                removePref(PreferKey.glassStrokeColor)
                editingDraftState = editingDraftState?.copy(panelBorderColor = null)
            } else {
                val currentColor = normalizeOptionalColor(editingDraftState?.panelBorderColor)
                    ?: "#${accentColor.hexString}"
                ColorPickerDialog.newBuilder()
                    .setColor(colorIntOrDefault(currentColor, accentColor))
                    .setShowAlphaSlider(false)
                    .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                    .setDialogId(colorPanelBorder)
                    .show(this)
            }
        }
    }

    private fun showCardShadowPicker() {
        val current = editingDraftState?.cardShadow
        NumberPickerDialog(this)
            .setTitle(getString(R.string.theme_card_shadow))
            .setMinValue(0)
            .setMaxValue(24)
            .setValue(current ?: 0)
            .setCustomButton(R.string.btn_default_s) {
                editingDraftState = editingDraftState?.copy(cardShadow = null)
            }
            .show {
                editingDraftState = editingDraftState?.copy(cardShadow = it.coerceIn(0, 24))
            }
    }

    private fun showCardBlurPicker() {
        val current = editingDraftState?.cardBackgroundBlur
        NumberPickerDialog(this, isDecimalMode = true)
            .setTitle(getString(R.string.theme_card_background_blur))
            .setMinValue(0)
            .setMaxValue(250)
            .setValue(((current ?: 0f) * 10f).toInt())
            .setCustomButton(R.string.btn_default_s) {
                editingDraftState = editingDraftState?.copy(cardBackgroundBlur = null)
            }
            .show {
                editingDraftState = editingDraftState?.copy(cardBackgroundBlur = (it / 10f).coerceIn(0f, 25f))
            }
    }

    private fun showFontScalePicker() {
        val current = editingDraftState?.fontScale ?: 0
        NumberPickerDialog(this)
            .setTitle(getString(R.string.font_scale))
            .setMaxValue(16)
            .setMinValue(8)
            .setValue(if (current == 0) 10 else current)
            .setCustomButton(R.string.btn_default_s) {
                editingDraftState = editingDraftState?.copy(fontScale = 0)
            }
            .show {
                editingDraftState = editingDraftState?.copy(fontScale = it.coerceIn(8, 16))
            }
    }

    private fun saveDraft(draft: ThemeEditDraft): Boolean {
        val oldEntry = resolvedEditingEntry()
        val baseConfig = oldEntry?.let {
            kotlin.runCatching { ThemePackageManager.getConfig(it) }.getOrNull()
        } ?: currentConfig()
        val config = kotlin.runCatching {
            draft.toThemeConfig(
                baseConfig = baseConfig,
                fallbackName = getString(if (draft.isNightTheme) R.string.theme_night else R.string.theme_day),
                fallbackPrimaryHex = colorToHex(ThemeStore.primaryColor(this)),
                fallbackAccentHex = colorToHex(ThemeStore.accentColor(this)),
                fallbackBackgroundHex = colorToHex(ThemeStore.backgroundColor(this)),
                fallbackBottomHex = colorToHex(ThemeStore.bottomBackground(this))
            )
        }.onFailure {
            toastOnUi(R.string.color_format_error)
        }.getOrNull() ?: return false
        addTheme(config)
        return true
    }

    private fun addTheme(config: ThemeConfig.Config) {
        val oldEntry = resolvedEditingEntry()
        isSavingDraftState = true
        lifecycleScope.launch {
            kotlin.runCatching {
                val wasApplied = oldEntry?.let { isApplied(it) } == true
                val exists = ThemePackageManager.localThemeExists(
                    config.isNightTheme,
                    config.themeName,
                    oldEntry?.dirName
                )
                if (exists) {
                    throw IllegalArgumentException(getString(R.string.theme_name_exists))
                }
                val entry = ThemePackageManager.addFromConfig(config)
                if (oldEntry != null && oldEntry.dirName != entry.dirName) {
                    if (oldEntry.source != ThemePackageManager.Source.REMOTE) {
                        ThemePackageManager.deleteLocal(oldEntry)
                    }
                    if (AppConfig.syncThemePackages && oldEntry.source != ThemePackageManager.Source.LOCAL) {
                        enqueueRemoteDelete(oldEntry)
                    }
                }
                if (wasApplied) {
                    ThemePackageManager.apply(this@ThemeManageActivity, entry, switchNightMode = false)
                    AppearanceKitManager.syncCurrentThemeRef(entry.packageInfo.isNightTheme, entry)
                    if (entry.packageInfo.isNightTheme) {
                        appliedNightThemeOverride = entry.dirName
                    } else {
                        appliedDayThemeOverride = entry.dirName
                    }
                }
                entry to wasApplied
            }.onSuccess {
                isSavingDraftState = false
                editingDraftState = null
                editingEntry = null
                toastOnUi(getString(R.string.theme_saved_local))
                loadThemes()
                if (enqueueUploadIfNeeded(it.first)) {
                    showThemeSyncTasks()
                }
            }.onFailure {
                isSavingDraftState = false
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_save_failed, it.localizedMessage))
            }
        }
    }

    private fun enqueueUploadIfNeeded(entry: ThemePackageManager.Entry): Boolean {
        if (!AppConfig.syncThemePackages) return false
        return enqueueThemeUpload(entry)
    }

    private fun enqueueThemeUpload(entry: ThemePackageManager.Entry): Boolean {
        return WebDavTaskManager.enqueueUpload(
            key = "theme_upload:${entry.packageInfo.isNightTheme}:${entry.dirName}",
            name = entry.packageInfo.name,
            type = WebDavTaskType.THEME_PACKAGE_UPLOAD,
            runningMessage = getString(R.string.theme_upload_remote),
            successMessage = getString(R.string.theme_sync_done)
        ) {
            ThemePackageManager.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
    }

    private fun observeWebDavTasks() {
        seedHandledWebDavTasks(WebDavTaskType.THEME_PACKAGE_UPLOAD)
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                var shouldReload = false
                var failedMessage: String? = null
                states.values
                    .filter { it.type == WebDavTaskType.THEME_PACKAGE_UPLOAD }
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
                    loadThemes()
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

    private fun showThemeSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.THEME_PACKAGE_UPLOAD))
    }

    private fun currentConfig(): ThemeConfig.Config {
        val name = getString(if (isNightTheme) R.string.theme_night else R.string.theme_day)
        val primary = getPrefInt(
            if (isNightTheme) PreferKey.cNPrimary else PreferKey.cPrimary,
            getCompatColor(if (isNightTheme) R.color.md_blue_grey_600 else R.color.md_brown_500)
        )
        val accent = getPrefInt(
            if (isNightTheme) PreferKey.cNAccent else PreferKey.cAccent,
            getCompatColor(if (isNightTheme) R.color.md_deep_orange_800 else R.color.md_red_600)
        )
        val background = getPrefInt(
            if (isNightTheme) PreferKey.cNBackground else PreferKey.cBackground,
            getCompatColor(if (isNightTheme) R.color.md_grey_900 else R.color.md_grey_100)
        )
        val bottom = getPrefInt(
            if (isNightTheme) PreferKey.cNBBackground else PreferKey.cBBackground,
            getCompatColor(if (isNightTheme) R.color.md_grey_850 else R.color.md_grey_200)
        )
        return ThemeConfig.Config(
            themeName = name,
            isNightTheme = isNightTheme,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bottom.hexString}",
            transparentNavBar = true,
            backgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage),
            backgroundImgBlur = getPrefInt(if (isNightTheme) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring, 0),
            backgroundImgCrop = getPrefString(if (isNightTheme) PreferKey.bgImageNCrop else PreferKey.bgImageCrop),
            bookInfoBackgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            panelBackgroundImgPath = getPrefString(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage),
            panelBackgroundScaleType = getPrefString(if (isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType)
                ?: ThemeConfig.PANEL_BG_CROP,
            panelBorderColor = getPrefString(if (isNightTheme) PreferKey.panelBorderColorN else PreferKey.panelBorderColor),
            panelBorderAlpha = getPrefInt(if (isNightTheme) PreferKey.panelBorderAlphaN else PreferKey.panelBorderAlpha, 100),
            uiCornerScale = themeUiCornerScale(isNightTheme),
            uiLayoutAlpha = themeUiLayoutAlpha(isNightTheme),
            dialogAlpha = themeDialogAlpha(isNightTheme),
            cardColor = getPrefString(ThemeRuntimeKeys.themeCardColor(isNightTheme)),
            mutedColor = getPrefString(ThemeRuntimeKeys.themeMutedColor(isNightTheme)),
            searchFieldBackgroundColor = getPrefString(
                ThemeRuntimeKeys.themeSearchFieldBackgroundColor(isNightTheme)
            ),
            tabBackgroundColor = getPrefString(ThemeRuntimeKeys.themeTabBackgroundColor(isNightTheme)),
            shelfColor = getPrefString(ThemeRuntimeKeys.themeShelfColor(isNightTheme)),
            cardShadow = getPrefInt(ThemeRuntimeKeys.themeCardShadow(isNightTheme), -1).takeIf { it >= 0 },
            cardBackgroundBlur = getPrefInt(ThemeRuntimeKeys.themeCardBackgroundBlur(isNightTheme), -1)
                .takeIf { it >= 0 }
                ?.let { it / 10f },
            uiCornerSearchFollow = themeUiCornerSearchFollow(isNightTheme),
            uiCornerReplyFollow = themeUiCornerReplyFollow(isNightTheme),
            fontScale = getPrefInt(ThemeRuntimeKeys.fontScale(isNightTheme), 0),
            uiFontPath = getPrefString(ThemeRuntimeKeys.uiFontPath(isNightTheme)).orEmpty(),
            titleFontPath = getPrefString(ThemeRuntimeKeys.titleFontPath(isNightTheme)).orEmpty(),
            uiFontColor = getPrefString(ThemeRuntimeKeys.uiFontColor(isNightTheme)).orEmpty()
                .takeIf { it.isNotBlank() }
                ?: defaultThemeTextColorHex(isNightTheme),
            titleFontColor = getPrefString(ThemeRuntimeKeys.titleFontColor(isNightTheme)).orEmpty()
                .takeIf { it.isNotBlank() }
                ?: defaultThemeTextColorHex(isNightTheme)
        )
    }

    private fun themeUiCornerScale(isNight: Boolean): Float {
        return getPrefString(ThemeRuntimeKeys.uiCornerScale(isNight), "1")
            ?.toFloatOrNull()
            ?.coerceIn(0f, 3f)
            ?: 1f
    }

    private fun themeUiLayoutAlpha(isNight: Boolean): Int {
        return getPrefInt(
            ThemeRuntimeKeys.uiLayoutAlpha(isNight),
            getPrefInt(PreferKey.uiCornerEffectLevel, 100)
        ).coerceIn(0, 100)
    }

    private fun themeDialogAlpha(isNight: Boolean): Int {
        return getPrefInt(ThemeRuntimeKeys.dialogAlpha(isNight), 100).coerceIn(0, 100)
    }

    private fun themeUiCornerSearchFollow(isNight: Boolean): Boolean {
        return getPrefBoolean(ThemeRuntimeKeys.uiCornerSearchFollow(isNight), false)
    }

    private fun themeUiCornerReplyFollow(isNight: Boolean): Boolean {
        return getPrefBoolean(ThemeRuntimeKeys.uiCornerReplyFollow(isNight), false)
    }

    override val curFontPath: String
        get() = when (editingDraftState?.fontTarget ?: ThemeEditFontTarget.UI) {
            ThemeEditFontTarget.TITLE -> editingDraftState?.titleFontPath
            ThemeEditFontTarget.UI -> editingDraftState?.uiFontPath
        }.orEmpty()

    override val applySystemTypefaceOnDefault: Boolean
        get() = false

    override fun selectFont(path: String) {
        val current = editingDraftState ?: return
        editingDraftState = when (current.fontTarget) {
            ThemeEditFontTarget.UI -> current.copy(uiFontPath = path)
            ThemeEditFontTarget.TITLE -> current.copy(titleFontPath = path)
        }
    }

    private fun uiFontDisplayName(path: String?): String {
        if (path.isNullOrBlank()) {
            return getString(R.string.default_font)
        }
        val rawName = runCatching {
            val uri = Uri.parse(path)
            when {
                uri.scheme == "content" -> androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(this, uri)
                    ?.name
                uri.scheme == "file" -> File(uri.path.orEmpty()).name
                else -> null
            }
        }.getOrNull()
            ?: path.substringAfterLast(File.separator)
                .substringAfterLast("/")
                .substringAfterLast(":")
        val displayName = when {
            rawName.startsWith("ui_font.") -> rawName.replaceFirst("ui_font", getString(R.string.ui_font))
            rawName.startsWith("title_font.") -> rawName.replaceFirst("title_font", getString(R.string.title_font))
            else -> rawName
                .removePrefix("ui_font_")
                .removePrefix("title_font_")
        }
        return runCatching {
            URLDecoder.decode(displayName, "utf-8")
        }.getOrDefault(displayName).ifBlank {
            getString(R.string.default_font)
        }
    }

    private fun Float.toScaleText(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    private fun normalizeColor(value: String?, fallbackColor: Int): String {
        return normalizeOptionalColor(value) ?: colorToHex(fallbackColor)
    }

    private fun normalizeOptionalColor(value: String?): String? {
        val normalized = value.normalizedEditableColorString() ?: return null
        val color = kotlin.runCatching { normalized.toColorInt() }.getOrNull() ?: return null
        return colorToHex(color)
    }

    private fun colorIntOrDefault(value: String?, fallbackColor: Int): Int {
        return normalizeOptionalColor(value)
            ?.let { kotlin.runCatching { it.toColorInt() }.getOrNull() }
            ?: fallbackColor
    }

    private fun String?.normalizedEditableColorString(): String? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutPrefix = raw
            .removePrefix("#")
            .removePrefix("0x")
            .removePrefix("0X")
        val isHex = withoutPrefix.isNotEmpty() &&
            withoutPrefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isHex) {
            return raw
        }
        val argb = when (withoutPrefix.length) {
            3 -> "FF" + withoutPrefix.map { "$it$it" }.joinToString("")
            4 -> withoutPrefix.map { "$it$it" }.joinToString("")
            6 -> "FF$withoutPrefix"
            8 -> withoutPrefix
            else -> return null
        }
        return "#${argb.uppercase(Locale.ROOT)}"
    }

    private fun colorToHex(color: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and color)
    }

    private fun startImageCrop(uri: Uri, requestCode: Int) {
        val aspect = if (requestCode == requestPanelBackground) 1 to 1 else ImageCropHelper.screenAspect(this)
        val prefix = when (requestCode) {
            requestMainBackground -> "main"
            requestPanelBackground -> "panel"
            else -> "book_info"
        }
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = requestCode,
            aspectWidth = aspect.first,
            aspectHeight = aspect.second,
            dirName = "themePackageTemp",
            prefix = prefix,
            targetWidth = 1600
        )
        pendingImageCropRequest = request
        val animatedSource = if (requestCode == requestMainBackground) copyAnimatedBackgroundSource(uri) else null
        cropImage.launch(
            if (animatedSource != null) {
                request.params.copy(
                    uri = animatedSource.toUri(),
                    outputPath = animatedSource.absolutePath,
                    viewportOnly = true
                )
            } else {
                if (requestCode == requestMainBackground) {
                    editingDraftState = editingDraftState?.copy(mainBackgroundCrop = null)
                }
                request.params
            }
        )
    }

    private fun copyAnimatedBackgroundSource(uri: Uri): File? {
        return kotlin.runCatching {
            val dir = externalFiles.getFile("themePackageTemp").apply { mkdirs() }
            val temp = File(dir, "main_source_${System.currentTimeMillis()}.img")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: error(getString(R.string.error_image_url_empty))
            if (!ImageTypeUtils.isAnimatedImage(temp)) {
                temp.delete()
                return null
            }
            val suffix = ImageTypeUtils.preferredRasterExtension(temp, "img")
            val target = File(dir, "main_${System.currentTimeMillis()}.$suffix")
            temp.renameTo(target)
            target.takeIf { it.exists() && it.length() > 0L }
                ?: error(getString(R.string.error_decode_bitmap))
        }.onFailure {
            toastOnUi(getString(R.string.image_crop_failed, it.localizedMessage ?: getString(R.string.unknown)))
        }.getOrNull()
    }

    private fun formatMainBackgroundCrop(result: ImageCropContract.Result): String? {
        val left = result.cropLeft ?: return null
        val top = result.cropTop ?: return null
        val right = result.cropRight ?: return null
        val bottom = result.cropBottom ?: return null
        return ThemeConfig.normalizeBackgroundCrop("$left,$top,$right,$bottom")
    }

    private fun entryActions(entry: ThemePackageManager.Entry): List<AppManagementMenuAction> {
        val actions = buildList {
            add(ThemeAction.APPLY)
            if (entry.source != ThemePackageManager.Source.REMOTE &&
                entry.source != ThemePackageManager.Source.BUILTIN
            ) {
                add(ThemeAction.EDIT)
                add(ThemeAction.EXPORT)
            }
            if (entry.source == ThemePackageManager.Source.REMOTE ||
                entry.source == ThemePackageManager.Source.BOTH
            ) add(ThemeAction.DOWNLOAD)
            if (entry.source != ThemePackageManager.Source.REMOTE &&
                entry.source != ThemePackageManager.Source.BUILTIN
            ) add(ThemeAction.UPLOAD)
            if (!isApplied(entry)) {
                if (entry.source != ThemePackageManager.Source.REMOTE &&
                    entry.source != ThemePackageManager.Source.BUILTIN
                ) add(ThemeAction.DELETE_LOCAL)
                if (entry.source != ThemePackageManager.Source.LOCAL &&
                    entry.source != ThemePackageManager.Source.BUILTIN
                ) add(ThemeAction.DELETE_REMOTE)
                if (entry.source == ThemePackageManager.Source.BOTH) add(ThemeAction.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action.name.startsWith("DELETE")
            ) {
                when (action) {
                    ThemeAction.APPLY -> applyTheme(entry)
                    ThemeAction.EDIT -> showEditDialog(entry)
                    ThemeAction.EXPORT -> exportThemeZip(entry)
                    ThemeAction.DOWNLOAD -> runAction(getString(R.string.theme_downloaded)) {
                        ThemePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    ThemeAction.UPLOAD -> uploadThemeNow(entry)
                    ThemeAction.DELETE_LOCAL -> confirmDeleteTheme(
                        entry,
                        getString(R.string.theme_delete_local_confirm)
                    ) {
                        ThemePackageManager.deleteLocal(entry)
                    }
                    ThemeAction.DELETE_REMOTE -> confirmDeleteTheme(
                        entry,
                        getString(R.string.theme_delete_remote_confirm)
                    ) {
                        enqueueRemoteDelete(entry)
                    }
                    ThemeAction.DELETE_BOTH -> confirmDeleteTheme(
                        entry,
                        getString(R.string.theme_delete_both_confirm)
                    ) {
                        ThemePackageManager.deleteLocal(entry)
                        enqueueRemoteDelete(entry)
                    }
                }
            }
        }
    }

    private fun uploadThemeNow(entry: ThemePackageManager.Entry) {
        val queued = enqueueThemeUpload(entry)
        toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        if (queued) {
            showThemeSyncTasks()
        }
    }

    private fun exportThemeZip(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.exportZip(entry)
            }.onSuccess { zipFile ->
                exportThemePackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(
                        zipFile.name,
                        zipFile,
                        "application/zip"
                    )
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_export_failed, it.localizedMessage))
            }
        }
    }

    private fun importThemeZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val dir = externalFiles.getFile("themePackageImports").apply { mkdirs() }
                val file = File(dir, "import_${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                if (AppearanceKitManager.isAppearanceKitPackage(file)) {
                    emptyList<ThemePackageManager.Entry>() to AppearanceKitManager.importPackage(file).total
                } else {
                    val result = ThemePackageManager.importPackageDetailed(file)
                    val extraCount = result.navigationBars.size + result.coverCollections.size
                    val totalCount = if (extraCount > 0) {
                        result.themes.size + extraCount
                    } else {
                        0
                    }
                    result.themes to totalCount
                }
            }.onSuccess {
                toastOnUi(
                    if (it.second > 0) {
                        getString(R.string.appearance_kit_imported, it.second)
                    } else {
                        getString(R.string.theme_imported)
                    }
                )
                loadThemes()
                val hasUploadTask = it.first.any(::enqueueUploadIfNeeded)
                if (hasUploadTask) {
                    showThemeSyncTasks()
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_import_failed, it.localizedMessage))
            }
        }
    }

    private fun applyTheme(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val localEntry = if (entry.source == ThemePackageManager.Source.REMOTE) {
                    ThemePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                } else {
                    entry
                }
                ThemePackageManager.apply(this@ThemeManageActivity, localEntry, switchNightMode = false)
                AppearanceKitManager.syncCurrentThemeRef(localEntry.packageInfo.isNightTheme, localEntry)
                localEntry
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_apply_failed, it.localizedMessage))
            }.onSuccess {
                if (it.packageInfo.isNightTheme) {
                    appliedNightThemeOverride = it.dirName
                } else {
                    appliedDayThemeOverride = it.dirName
                }
                toastOnUi(getString(R.string.theme_applied))
                loadThemes()
            }
        }
    }

    private fun isApplied(entry: ThemePackageManager.Entry): Boolean {
        val overrideName = if (entry.packageInfo.isNightTheme) {
            appliedNightThemeOverride
        } else {
            appliedDayThemeOverride
        }
        if (overrideName != null) {
            return overrideName == entry.dirName || overrideName == entry.packageInfo.name
        }
        val dirKey = if (entry.packageInfo.isNightTheme) PreferKey.dNThemeDirName else PreferKey.dThemeDirName
        val storedDirName = getPrefString(dirKey).orEmpty()
        if (storedDirName.isNotBlank()) {
            return storedDirName == entry.dirName
        }
        val nameKey = if (entry.packageInfo.isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        return getPrefString(nameKey) == entry.packageInfo.name
    }

    private fun entryInfo(entry: ThemePackageManager.Entry): String {
        val pkg = entry.packageInfo
        return buildString {
            if (isApplied(entry)) {
                append(getString(R.string.theme_current_applied))
                append(" \u00B7 ")
            }
            append(getString(if (pkg.isNightTheme) R.string.theme_night_short else R.string.theme_day_short))
            append(" \u00B7 ")
            val time = maxOf(pkg.updatedAt, entry.remoteUpdatedAt)
            append(if (time > 0) dateFormat.format(Date(time)) else getString(R.string.theme_time_unknown))
        }
    }

    private fun previewData(entry: ThemePackageManager.Entry): ThemePreviewData {
        val config = kotlin.runCatching { ThemePackageManager.getConfig(entry) }
            .getOrElse { entry.packageInfo.config }
        val fallbackColor = config?.backgroundColor.toPreviewColor(entry.packageInfo.isNightTheme)
        val primaryColor = config?.primaryColor.toPreviewColor(entry.packageInfo.isNightTheme)
        val accentColor = config?.accentColor.toPreviewColor(entry.packageInfo.isNightTheme)
        val bottomBackground = config?.bottomBackground.toPreviewColor(entry.packageInfo.isNightTheme)
        val backgroundPath = config?.backgroundImgPath?.takeIf { it.isNotBlank() }
        val previewSignature = backgroundPath
            ?.takeIf { !it.startsWith("http", ignoreCase = true) }
            ?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    ObjectKey("${file.absolutePath}:${file.length()}:${file.lastModified()}")
                } else {
                    null
                }
            }
        return ThemePreviewData(
            fallbackColor = fallbackColor,
            primaryColor = primaryColor,
            accentColor = accentColor,
            bottomBackground = bottomBackground,
            cardColor = config?.cardColor.toOptionalPreviewColor(),
            mutedColor = config?.mutedColor.toOptionalPreviewColor(),
            tabBackgroundColor = config?.tabBackgroundColor.toOptionalPreviewColor(),
            uiLayoutAlpha = config?.uiLayoutAlpha?.coerceIn(0, 100) ?: 100,
            backgroundPath = backgroundPath,
            signature = previewSignature
        )
    }

    private fun String?.toPreviewColor(isNightTheme: Boolean): Int {
        return kotlin.runCatching {
            val color = this?.trim().orEmpty()
            val normalized = if (color.startsWith("#")) color else "#$color"
            normalized.toColorInt()
        }.getOrElse {
            if (isNightTheme) Color.BLACK else Color.WHITE
        }
    }

    private fun String?.toOptionalPreviewColor(): Int? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return kotlin.runCatching {
            val normalized = if (value.startsWith("#")) value else "#$value"
            normalized.toColorInt()
        }.getOrNull()
    }

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching {
                block()
            }.onSuccess {
                toastOnUi(successMessage)
                loadThemes()
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun enqueueRemoteDelete(entry: ThemePackageManager.Entry) {
        if (!AppConfig.syncThemePackages) return
        enqueueRemoteSync(
            RemoteSyncTask(
                key = "delete:${entry.packageInfo.isNightTheme}:${entry.dirName}",
                type = RemoteSyncTask.Type.DELETE,
                isNightTheme = entry.packageInfo.isNightTheme,
                dirName = entry.dirName
            )
        )
    }

    private fun enqueueRemoteSync(task: RemoteSyncTask) {
        synchronized(pendingRemoteSyncTasks) {
            pendingRemoteSyncTasks[task.key] = task
            savePendingRemoteSyncTasksLocked()
        }
        flushPendingRemoteSyncTasks()
    }

    private fun schedulePendingRemoteSyncTasks() {
        lifecycleScope.launch {
            delay(1_500L)
            if (!isFinishing && !isDestroyed) {
                flushPendingRemoteSyncTasks()
            }
        }
    }

    private fun restorePendingRemoteSyncTasks() {
        val tasks = getPrefString(PreferKey.themePackageSyncTasks).orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { GSON.fromJsonArray<RemoteSyncTask>(it).getOrNull() }
            .orEmpty()
        if (tasks.isEmpty()) return
        synchronized(pendingRemoteSyncTasks) {
            pendingRemoteSyncTasks.clear()
            tasks.forEach { task ->
                pendingRemoteSyncTasks[task.key] = task.copy(lastError = "")
            }
        }
    }

    private fun savePendingRemoteSyncTasksLocked() {
        val tasks = pendingRemoteSyncTasks.values.toList()
        if (tasks.isEmpty()) {
            removePref(PreferKey.themePackageSyncTasks)
        } else {
            putPrefString(PreferKey.themePackageSyncTasks, GSON.toJson(tasks))
        }
    }

    private fun flushPendingRemoteSyncTasks() {
        val hasPending = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.isNotEmpty() }
        if (syncingRemoteTasks || !hasPending || !AppConfig.syncThemePackages) return
        syncingRemoteTasks = true
        themeRemoteSyncScope.launch {
            val failed = linkedMapOf<String, RemoteSyncTask>()
            val tasks = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.values.toList() }
            tasks.forEach { task ->
                kotlin.runCatching {
                    task.execute()
                }.onSuccess {
                    synchronized(pendingRemoteSyncTasks) {
                        if (pendingRemoteSyncTasks[task.key] == task) {
                            pendingRemoteSyncTasks.remove(task.key)
                            savePendingRemoteSyncTasksLocked()
                        }
                    }
                }.onFailure {
                    if (!it.isJobCancellation()) {
                        failed[task.key] = task
                    }
                }
            }
            syncingRemoteTasks = false
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (failed.isEmpty()) {
                    toastOnUi(getString(R.string.theme_sync_done))
                    loadThemes()
                } else {
                    summaryTextState = appendPendingRemoteSummary(getString(R.string.theme_sync_failed_retry))
                    toastOnUi(getString(R.string.theme_sync_failed, failed.values.first().lastError))
                }
            }
            val pendingKeys = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.keys.toSet() }
            if (pendingKeys.any { it !in failed.keys }) {
                flushPendingRemoteSyncTasks()
            }
        }
    }

    private fun appendPendingRemoteSummary(base: String): String {
        val pendingCount = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.size }
        return if (pendingCount > 0) {
            "$base\n${getString(R.string.theme_sync_pending, pendingCount)}"
        } else {
            base
        }
    }

    private fun confirmDelete(message: String, block: suspend () -> Unit) {
        alert(getString(R.string.delete), message) {
            yesButton {
                runAction(getString(R.string.delete_success), block)
            }
            noButton()
        }
    }

    private fun confirmDeleteTheme(
        entry: ThemePackageManager.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        if (isApplied(entry)) {
            toastOnUi(getString(R.string.theme_delete_applied_forbidden))
            return
        }
        confirmDelete(message, block)
    }
    override fun onColorSelected(dialogId: Int, color: Int) {
        val current = editingDraftState ?: return
        val hex = colorToHex(color)
        if (setOptionalColor(dialogId, hex)) {
            return
        }
        editingDraftState = when (dialogId) {
            colorPrimary -> current.copy(primaryColorHex = hex)
            colorAccent -> current.copy(accentColorHex = hex)
            colorBackground -> current.copy(backgroundColorHex = hex)
            colorBottomBackground -> current.copy(bottomBackgroundHex = hex)
            colorPanelBorder -> current.copy(panelBorderColor = hex)
            else -> current
        }
    }

    private fun setOptionalColor(dialogId: Int, hex: String?): Boolean {
        val current = editingDraftState ?: return false
        val updated = when (dialogId) {
            colorCard -> current.copy(cardColor = hex)
            colorMuted -> current.copy(mutedColor = hex)
            colorSearchFieldBackground -> current.copy(searchFieldBackgroundColor = hex)
            colorTabBackground -> current.copy(tabBackgroundColor = hex)
            colorShelf -> current.copy(shelfColor = hex)
            colorUiFont -> current.copy(uiFontColor = hex)
            colorTitleFont -> current.copy(titleFontColor = hex)
            else -> return false
        }
        editingDraftState = updated
        return true
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun Throwable.isJobCancellation(): Boolean {
        return this is CancellationException || cause?.isJobCancellation() == true
    }

    companion object : ActivitySearchSource {
        override fun searchEntries(context: android.content.Context): List<SettingsSearchIndex.Entry> = listOf(
            SettingsSearchIndex.Entry(
                ownerKey = "appearanceKit",
                ownerConfigTag = "",
                key = "activity_theme_manage",
                title = context.getString(R.string.theme_list),
                summary = context.getString(R.string.theme_list_summary),
                aliases = listOf("主题包", "配色", "字体", "壁纸", "theme"),
                destination = SearchDestination.Activity(ThemeManageActivity::class.java.name)
            )
        )
        private val themeRemoteSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val STATE_IS_NIGHT = "state_is_night"
        private const val STATE_EDIT_DRAFT_JSON = "state_edit_draft_json"
        private const val requestMainBackground = 301
        private const val requestBookInfoBackground = 302
        private const val requestPanelBackground = 303
        private const val colorPrimary = 401
        private const val colorAccent = 402
        private const val colorBackground = 403
        private const val colorBottomBackground = 404
        private const val colorPanelBorder = 405
        private const val colorCard = 406
        private const val colorMuted = 407
        private const val colorSearchFieldBackground = 408
        private const val colorTabBackground = 409
        private const val colorShelf = 410
        private const val colorUiFont = 411
        private const val colorTitleFont = 412
        private const val CLOUD_SCOPE = "theme"
    }

    private enum class ThemeAction(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.theme_export_zip),
        DOWNLOAD(R.string.theme_download_local),
        UPLOAD(R.string.theme_upload_remote),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }

    private enum class ThemeImageAction(val titleRes: Int) {
        BLUR(R.string.theme_image_blur),
        SELECT(R.string.theme_image_select),
        DELETE(R.string.theme_image_delete)
    }

    private data class RemoteSyncTask(
        val key: String,
        val type: Type,
        val isNightTheme: Boolean,
        val dirName: String,
        val containerId: String? = null,
        val scope: String? = null,
        var lastError: String = ""
    ) {
        suspend fun execute() {
            val entry = ThemePackageManager.Entry(
                packageInfo = ThemePackageManager.Package(
                    name = dirName,
                    dirName = dirName,
                    isNightTheme = isNightTheme,
                    updatedAt = 0L,
                    config = null
                ),
                source = ThemePackageManager.Source.LOCAL,
                localDir = ThemePackageManager.localDir(isNightTheme, dirName)
            )
            runCatching {
                when (type) {
                    Type.UPLOAD -> ThemePackageManager.upload(entry, containerId, scope)
                    Type.DELETE -> ThemePackageManager.deleteRemote(entry, containerId, scope)
                }
            }.onFailure {
                lastError = it.localizedMessage ?: it.toString()
                throw it
            }.getOrThrow()
        }

        enum class Type {
            UPLOAD,
            DELETE
        }
    }
}

private data class ThemePreviewData(
    val fallbackColor: Int,
    val primaryColor: Int,
    val accentColor: Int,
    val bottomBackground: Int,
    val cardColor: Int?,
    val mutedColor: Int?,
    val tabBackgroundColor: Int?,
    val uiLayoutAlpha: Int,
    val backgroundPath: String?,
    val signature: ObjectKey?
)

@Composable
private fun ThemePackageManageScreen(
    entries: List<ThemePackageManager.Entry>,
    isNightTheme: Boolean,
    summaryText: String,
    topActions: List<AppManagementAction>,
    onBack: () -> Unit,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onApply: (ThemePackageManager.Entry) -> Unit,
    onEdit: (ThemePackageManager.Entry) -> Unit,
    isApplied: (ThemePackageManager.Entry) -> Boolean,
    entryInfo: (ThemePackageManager.Entry) -> String,
    entryActions: (ThemePackageManager.Entry) -> List<AppManagementMenuAction>,
    previewData: (ThemePackageManager.Entry) -> ThemePreviewData
) {
    val palette = rememberAppManagementPalette()
    // 根 Surface 原先铺 palette.settings.page 整页不透明：壁纸模式下主题卡玻璃只能采到被本页
    // 自己盖住的壁纸。背景图可用且非墨水屏时改铺透明（BaseActivity 已把根 View 背景置空，
    // 壁纸由此透出）；无背景图 / 墨水屏时仍返回原页色，观感逐值不变。
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    // 一页只算一次签名：主题网格卡与日夜胶囊只读，不各注册偏好监听
    val glassSignature = rememberGlassConfigSignature()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily),
        // 主列表卡（ThemePackageGridCard）是 AppManagementCard：提供签名后自动走壁纸域玻璃；
        // 提供 null（壁纸不可见）时卡片保持主题实底，与接入玻璃之前逐值一致。
        LocalAppManagementGlassSignature provides glassSignature.takeIf { wallpaperVisible }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = glassPageBackgroundColor(palette.settings.page, wallpaperVisible),
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                AppComposeTopBar(
                    title = stringResource(R.string.theme_manage_title),
                    palette = palette,
                    actions = topActions,
                    onBack = onBack,
                    wallpaperVisible = wallpaperVisible,
                    glassSignature = glassSignature
                )
                ThemePackageTabs(
                    isNightTheme = isNightTheme,
                    palette = palette,
                    glassSignature = glassSignature,
                    onSwitchDayNight = onSwitchDayNight
                )
                Text(
                    text = summaryText,
                    color = palette.settings.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    // 页根在背景图模式下已透明，这句说明文字直接压在壁纸上：
                    // 按 BackgroundReadability 的口径给独立提示文本加自适应投影
                    // （无背景图时返回 null，普通主题观感逐值不变）
                    style = LocalTextStyle.current.copy(
                        shadow = imageBgTextShadow(palette.settings.secondaryText)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 10.dp, end = 16.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = AppLayoutTokens.PageHorizontal, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(AppLayoutTokens.GridGap),
                    verticalArrangement = Arrangement.spacedBy(AppLayoutTokens.GridGap)
                ) {
                    items(
                        entries,
                        key = { "${it.packageInfo.isNightTheme}_${it.dirName}" }
                    ) { entry ->
                        val active = isApplied(entry)
                        val editable = entry.source != ThemePackageManager.Source.BUILTIN &&
                            entry.source != ThemePackageManager.Source.REMOTE
                        ThemePackageGridCard(
                            entry = entry,
                            info = entryInfo(entry),
                            preview = previewData(entry),
                            isActive = active,
                            canEdit = editable,
                            actionsProvider = { entryActions(entry) },
                            palette = palette,
                            onApply = {
                                if (!active) onApply(entry)
                            },
                            onEdit = { onEdit(entry) }
                        )
                    }
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.theme_add),
                    palette = palette.miuix,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = false,
                    cornerRadius = palette.miuix.actionRadius,
                    minHeight = AppLayoutTokens.ControlHeightProminent,
                    prominentOutline = true
                )
            }
        }
    }
}

@Composable
private fun ThemePackageTabs(
    isNightTheme: Boolean,
    palette: AppManagementPalette,
    glassSignature: String,
    onSwitchDayNight: (Boolean) -> Unit
) {
    LegadoSegmentedControl(
        options = listOf(
            SegmentedOption(value = false, label = stringResource(R.string.theme_day)),
            SegmentedOption(value = true, label = stringResource(R.string.theme_night))
        ),
        selectedValue = isNightTheme,
        palette = palette.miuix,
        onSelected = onSwitchDayNight,
        tone = SegmentedControlTone.SurfaceThumb,
        itemHeight = 32.dp,
        fontSize = 14.sp,
        glassSignature = glassSignature,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun ThemePackageGridCard(
    entry: ThemePackageManager.Entry,
    info: String,
    preview: ThemePreviewData,
    isActive: Boolean,
    canEdit: Boolean,
    actionsProvider: () -> List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    onApply: () -> Unit,
    onEdit: () -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 198.dp),
        insidePadding = PaddingValues(8.dp),
        onClick = onApply,
        onLongClick = if (canEdit) onEdit else null,
        outerPadding = PaddingValues(0.dp)
    ) {
        ThemePackagePreview(
            preview = preview,
            active = isActive,
            palette = palette,
            radius = palette.miuix.actionRadius ?: 8.dp,
            modifier = Modifier.height(96.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.packageInfo.name,
                    color = palette.settings.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ThemePackageMoreButton(
                actionsProvider = actionsProvider,
                palette = palette,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                preview.primaryColor,
                preview.accentColor,
                preview.bottomBackground,
                preview.cardColor ?: preview.fallbackColor
            ).forEach { color ->
                ThemeColorDot(color = ComposeColor(color))
            }
        }
    }
}

@Composable
private fun ThemePackageMoreButton(
    actionsProvider: () -> List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier
) {
    AppComposePopupAnchorButton(
        actionsProvider = actionsProvider,
        containerColor = ComposeColor.Transparent,
        contentColor = palette.settings.primaryText,
        cornerRadius = palette.miuix.actionRadius,
        modifier = modifier.size(32.dp)
    ) {
        Text(
            text = "\u22EE",
            color = palette.settings.primaryText,
            fontSize = 16.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun ThemeColorDot(color: ComposeColor) {
    Box(
        modifier = Modifier
            .size(width = 18.dp, height = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    )
}

@Composable
private fun ThemePackagePreview(
    preview: ThemePreviewData,
    active: Boolean,
    palette: AppManagementPalette,
    radius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(ComposeColor(preview.fallbackColor))
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    contentDescription = context.getString(R.string.background_image)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { imageView ->
                imageView.releaseComposeImage()
                imageView.setBackgroundColor(preview.fallbackColor)
                imageView.setImageDrawable(null)
                val path = preview.backgroundPath
                if (!path.isNullOrBlank()) {
                    val appContext = imageView.context.applicationContext ?: imageView.context
                    val request = ImageLoader.load(appContext, path)
                        .centerCrop()
                        .error(ColorDrawable(preview.fallbackColor))
                    preview.signature?.let { request.signature(it) }
                    request.into(imageView)
                }
            },
            onRelease = { it.releaseComposeImage() },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (active) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.settings.accent.copy(alpha = 0.90f),
                        contentColor = palette.settings.onAccent,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Text(
                            text = stringResource(R.string.theme_applied_state),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = palette.settings.onAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val cardAlpha = (preview.uiLayoutAlpha.coerceIn(0, 100) / 100f).coerceIn(0.32f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(radius / 1.6f))
                        .background(ComposeColor(preview.cardColor ?: preview.bottomBackground).copy(alpha = cardAlpha))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(ComposeColor(preview.tabBackgroundColor ?: preview.mutedColor ?: preview.cardColor ?: preview.bottomBackground).copy(alpha = 0.84f))
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(ComposeColor(preview.accentColor).copy(alpha = 0.82f))
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(radius / 1.8f))
                        .background(ComposeColor(preview.bottomBackground).copy(alpha = 0.88f))
                )
            }
        }
    }
}
