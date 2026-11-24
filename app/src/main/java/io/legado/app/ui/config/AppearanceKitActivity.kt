package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.pkg.ArtPackagePayload
import io.legado.app.help.config.pkg.CoverSlotApplyMode
import io.legado.app.help.config.pkg.CoverSlotApplyTarget
import io.legado.app.help.config.pkg.CoverSlotPackagePayload
import io.legado.app.help.config.pkg.LegacyPackageMigrator
import io.legado.app.help.config.pkg.PackageKind
import io.legado.app.help.config.pkg.ParamPackagePayload
import io.legado.app.help.config.pkg.UnifiedPackageRepository
import io.legado.app.lib.cloud.CloudStorageFile
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.lib.theme.PrefBatchTransaction
import io.legado.app.lib.theme.SurfaceHierarchyDerivation
import io.legado.app.lib.theme.recomputeThemeUiSignatureAfterBatch
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.EXPLORE_GLASS_MODE_SOLID
import io.legado.app.ui.main.explore.resolveExploreGlassMode
import io.legado.app.ui.widget.compose.AppComposeTopBar
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppLayoutTokens
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.LegadoSegmentedControl
import io.legado.app.ui.widget.compose.LocalLegadoTokens
import io.legado.app.ui.widget.compose.SegmentedControlTone
import io.legado.app.ui.widget.compose.SegmentedOption
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class AppearanceKitActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private data class CloneDialogTarget(
        val kind: PackageKind,
        val source: UnifiedPackageRepository.StoredEntry,
        val suggestedName: String
    )

    private data class CreateDialogTarget(
        val kind: PackageKind,
        val suggestedName: String
    )

    private data class DeleteDialogTarget(
        val kind: PackageKind,
        val entry: UnifiedPackageRepository.StoredEntry,
        val mountedSlots: List<String>
    )

    private data class ImportConflictTarget(
        val file: File,
        val applySurface: Boolean,
        val applyLayout: Boolean,
        val conflictName: String,
        val existingNames: List<String>
    )

    private var isNightState by mutableStateOf(AppConfig.isNightTheme)
    private var isEInkState by mutableStateOf(AppConfig.isEInkMode)

    private var artSlotNightState by mutableStateOf(AppConfig.isNightTheme)
    private var paramSlotNightState by mutableStateOf(AppConfig.isNightTheme)
    private var coverSlotNightState by mutableStateOf(AppConfig.isNightTheme)

    private var artPackagesState by mutableStateOf<List<UnifiedPackageRepository.StoredEntry>>(emptyList())
    private var paramPackagesState by mutableStateOf<List<UnifiedPackageRepository.StoredEntry>>(emptyList())
    private var coverSlotPackagesState by mutableStateOf<List<UnifiedPackageRepository.StoredEntry>>(emptyList())

    private var activeDayArtIdState by mutableStateOf("")
    private var activeNightArtIdState by mutableStateOf("")
    private var activeDayParamIdState by mutableStateOf("")
    private var activeNightParamIdState by mutableStateOf("")
    private var activeDayCoverSlotIdState by mutableStateOf("")
    private var activeNightCoverSlotIdState by mutableStateOf("")
    private var coverSlotLockedState by mutableStateOf(false)
    private var coverSlotTargetHintDismissedState by mutableStateOf(false)

    private var cornerScaleState by mutableFloatStateOf(AppConfig.uiCornerScale)
    private var layoutAlphaState by mutableIntStateOf(AppConfig.uiLayoutAlpha)
    private var glassModeState by mutableStateOf(EXPLORE_GLASS_MODE_SOLID)
    private var glassLevelState by mutableIntStateOf(GlassMaterialPreset.DEFAULT_LIQUID_LEVEL)

    private var useLegacyTokenPipelineState by mutableStateOf(false)
    private var useLegacyPackageManagerState by mutableStateOf(false)

    private var pendingParamImportFileState by mutableStateOf<File?>(null)
    private var importConflictTargetState by mutableStateOf<ImportConflictTarget?>(null)
    private var cloneTargetState by mutableStateOf<CloneDialogTarget?>(null)
    private var createTargetState by mutableStateOf<CreateDialogTarget?>(null)
    private var deleteTargetState by mutableStateOf<DeleteDialogTarget?>(null)
    private var editingArtEntryState by mutableStateOf<UnifiedPackageRepository.StoredEntry?>(null)
    private var editingParamEntryState by mutableStateOf<UnifiedPackageRepository.StoredEntry?>(null)
    private var editingCoverSlotEntryState by mutableStateOf<UnifiedPackageRepository.StoredEntry?>(null)
    private var cloudContainerId: String? = null
    private var containerMenuLabelState by mutableStateOf<String?>(null)
    private val handledWebDavTasks = hashSetOf<String>()
    private var pendingAssetFileCallback: ((String) -> Unit)? = null
    private var pendingAssetExpectFont: Boolean = false
    private var pendingMultiImageCallback: ((List<String>) -> Unit)? = null

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importAppearanceKit)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        if (it.uri != null) {
            toastOnUi(R.string.export_success)
        }
    }

    private val pickAssetFileLauncher = registerForActivityResult(HandleFileContract()) { result ->
        val callback = pendingAssetFileCallback
        val expectFont = pendingAssetExpectFont
        pendingAssetFileCallback = null
        pendingAssetExpectFont = false
        val uri = result.uri ?: return@registerForActivityResult
        if (callback == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                val fallbackExt = if (expectFont) "font" else "dat"
                val ext = resolveAssetExtension(uri, fallback = fallbackExt)
                val staged = PackageManageUiDelegate.stageImportUriToTempFile(
                    context = this@AppearanceKitActivity,
                    uri = uri,
                    dirName = "uiPackageImport",
                    filePrefix = if (expectFont) "art_font" else "art_asset",
                    extension = ext
                )
                if (!expectFont) {
                    return@runCatching staged
                }
                val inspection = withContext(Dispatchers.IO) {
                    FontFileValidator.inspectFile(staged)
                }
                if (!inspection.isValid || !inspection.isNativeSupported) {
                    staged.delete()
                    throw IllegalArgumentException(
                        inspection.errorReason ?: "无效的字体文件（仅支持 .ttf / .otf / .ttc）"
                    )
                }
                val targetExt = inspection.suggestedExtension
                if (!targetExt.isNullOrBlank() && !staged.extension.equals(targetExt, ignoreCase = true)) {
                    val renamed = File(staged.parentFile, "${staged.nameWithoutExtension}.$targetExt")
                    if (renamed.exists()) renamed.delete()
                    if (staged.renameTo(renamed)) {
                        return@runCatching renamed
                    }
                }
                staged
            }.onSuccess { staged ->
                callback(staged.absolutePath)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private val pickMultipleImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val callback = pendingMultiImageCallback ?: return@registerForActivityResult
        pendingMultiImageCallback = null
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val batchDir = externalFiles.getFile(
                        "uiPackageImport",
                        "covers_${System.currentTimeMillis()}"
                    ).apply { mkdirs() }
                    uris.mapNotNull { uri ->
                        val fallbackExt = resolveAssetExtension(uri, fallback = "jpg")
                        val displayName = queryOpenableDisplayName(uri)
                        val (stem, ext) = if (displayName != null) {
                            sanitizePickedCoverFileName(displayName, fallbackExt)
                        } else {
                            "cover_asset_${System.currentTimeMillis()}" to fallbackExt
                        }
                        var target = File(batchDir, "$stem.$ext")
                        var suffix = 1
                        while (target.exists()) {
                            target = File(batchDir, "${stem}_$suffix.$ext")
                            suffix++
                        }
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                        target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
                    }
                }
            }.onSuccess { paths ->
                if (paths.isNotEmpty()) callback(paths)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun queryOpenableDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) {
                    cursor.getString(col)?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun sanitizePickedCoverFileName(rawName: String, fallbackExt: String): Pair<String, String> {
        val leaf = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val lastDot = leaf.lastIndexOf('.')
        val rawStem = if (lastDot > 0) leaf.substring(0, lastDot) else leaf
        val rawExt = if (lastDot > 0 && lastDot < leaf.length - 1) {
            leaf.substring(lastDot + 1).lowercase(Locale.ROOT)
        } else {
            ""
        }
        val cleanStem = rawStem
            .normalizeFileName()
            .trim('.', ' ')
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "cover_asset_${System.currentTimeMillis()}"
        val cleanExt = rawExt
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .takeIf { it.length in 1..6 }
            ?: fallbackExt
        return cleanStem to cleanExt
    }

    private fun resolveAssetExtension(uri: Uri, fallback: String): String {
        val fromPath = uri.lastPathSegment
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it.length <= 6 }
        if (fromPath != null) return fromPath
        val mime = contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        return when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("bmp") -> "bmp"
            mime.contains("gif") -> "gif"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("ttf") -> "ttf"
            mime.contains("otf") -> "otf"
            mime.contains("ttc") -> "ttc"
            else -> fallback
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        AppearanceKitManager.alignBaselineAfterMigration(this)
        binding.titleBar.visibility = View.GONE
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        installComposeContent()
        observeWebDavTasks()
        refreshStudioState()
    }

    override fun onResume() {
        super.onResume()
        refreshStudioState()
    }

    private fun updateCloudContainerState() {
        val state = PackageManageUiDelegate.resolveCloudContainerState(this, S3ContainerScope.UI_PACKAGE.key)
        cloudContainerId = state.selectedContainerId
        containerMenuLabelState = state.menuLabel
    }

    private fun observeWebDavTasks() {
        PackageManageUiDelegate.observeWebDavTasks(
            activity = this,
            taskTypes = setOf(
                WebDavTaskType.ART_PACKAGE_UPLOAD,
                WebDavTaskType.PARAM_PACKAGE_UPLOAD,
                WebDavTaskType.COVER_SLOT_PACKAGE_UPLOAD
            ),
            handledTaskTokens = handledWebDavTasks,
            onCompleted = { state ->
                toastOnUi("「${state.bookName}」${getString(R.string.package_sync_task_status_completed)}")
            },
            onFailed = { state ->
                toastOnUi("「${state.bookName}」上传失败: ${state.message}")
            }
        )
    }

    private fun topBarMenuActions(): List<AppManagementMenuAction> = buildList {
        containerMenuLabelState?.let { label ->
            add(
                AppManagementMenuAction(
                    text = "存储桶：$label",
                    onClick = ::selectCloudContainer
                )
            )
        }
        add(
            AppManagementMenuAction(
                text = "导入美术包 / 参数包 / 书架封面包",
                onClick = ::selectImportPackage
            )
        )
        add(
            AppManagementMenuAction(
                text = "从云端下载外观包",
                onClick = ::showCloudPackagesMenu
            )
        )
        add(
            AppManagementMenuAction(
                text = "新建美术资源包（从当前状态）",
                onClick = { promptCreatePackage(PackageKind.ART) }
            )
        )
        add(
            AppManagementMenuAction(
                text = "新建参数设置包（从当前状态）",
                onClick = { promptCreatePackage(PackageKind.PARAM) }
            )
        )
        add(
            AppManagementMenuAction(
                text = "新建书架封面包",
                onClick = { promptCreatePackage(PackageKind.COVER_SLOT) }
            )
        )
        add(
            AppManagementMenuAction(
                text = getString(R.string.package_sync_task_menu),
                onClick = ::showSyncTasks
            )
        )
    }

    private fun installComposeContent() {
        val composeView = ComposeView(this).apply {
            layoutParams = binding.recyclerView.layoutParams
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val glassOptions = remember {
                    listOf(
                        SegmentedOption(GlassMaterialPreset.SOLID, "实底"),
                        SegmentedOption(GlassMaterialPreset.CLEAR, "透明"),
                        SegmentedOption(GlassMaterialPreset.FROSTED, "磨砂"),
                        SegmentedOption(GlassMaterialPreset.GLASS, "液态")
                    )
                }
                LegadoComposeTheme {
                    AppearanceStudioScreen(
                        isEInkMode = isEInkState,
                        artSlotNight = artSlotNightState,
                        paramSlotNight = paramSlotNightState,
                        coverSlotNight = coverSlotNightState,
                        artPackages = artPackagesState,
                        paramPackages = paramPackagesState,
                        coverSlotPackages = coverSlotPackagesState,
                        activeDayArtId = activeDayArtIdState,
                        activeNightArtId = activeNightArtIdState,
                        activeDayParamId = activeDayParamIdState,
                        activeNightParamId = activeNightParamIdState,
                        activeDayCoverSlotId = activeDayCoverSlotIdState,
                        activeNightCoverSlotId = activeNightCoverSlotIdState,
                        coverSlotLocked = coverSlotLockedState,
                        cornerScale = cornerScaleState,
                        layoutAlpha = layoutAlphaState,
                        glassMode = glassModeState,
                        glassOptions = glassOptions,
                        glassLevel = glassLevelState,
                        useLegacyTokenPipeline = useLegacyTokenPipelineState,
                        useLegacyPackageManager = useLegacyPackageManagerState,
                        topMenuActions = ::topBarMenuActions,
                        onBack = ::supportFinishAfterTransition,
                        onSelectArtSlotNight = { artSlotNightState = it },
                        onSelectParamSlotNight = { paramSlotNightState = it },
                        onSelectCoverSlotNight = { coverSlotNightState = it },
                        onMountArtPackage = { entry -> mountArtPackage(entry, artSlotNightState) },
                        onMountParamPackage = { entry -> mountParamPackage(entry, paramSlotNightState) },
                        onMountCoverSlotPackage = { entry -> mountCoverSlotPackage(entry, coverSlotNightState) },
                        onToggleCoverSlotLocked = ::toggleCoverSlotLocked,
                        onCreatePackage = ::promptCreatePackage,
                        onImportPackage = ::selectImportPackage,
                        onClonePackage = ::promptClonePackage,
                        onEditArtPackage = { editingArtEntryState = it },
                        onEditParamPackage = { editingParamEntryState = it },
                        onEditCoverSlotPackage = ::openEditCoverSlot,
                        onReshuffleCoverSlotPackage = ::executeReshuffleCoverSlot,
                        onExportPackage = ::exportPackageEntry,
                        onUploadPackage = ::uploadPackageEntry,
                        onDeletePackage = ::promptDeletePackage,
                        onCornerScaleDrag = { step ->
                            cornerScaleState = sliderStepToCornerScale(step)
                        },
                        onCornerScaleCommit = {
                            commitCornerScale(cornerScaleState)
                        },
                        onLayoutAlphaDrag = { alpha ->
                            layoutAlphaState = alpha.coerceIn(0, 100)
                        },
                        onLayoutAlphaCommit = {
                            commitLayoutAlpha(layoutAlphaState)
                        },
                        onSelectGlassMode = ::selectGlassMode,
                        onGlassLevelDrag = { level ->
                            glassLevelState = level.coerceIn(0, 100)
                        },
                        onGlassLevelCommit = {
                            commitGlassLevel(glassLevelState)
                        },
                        onToggleLegacyTokenPipeline = ::toggleLegacyTokenPipeline,
                        onToggleLegacyPackageManager = ::toggleLegacyPackageManager
                    )

                    pendingParamImportFileState?.let { pendingFile ->
                        ParamImportLayerDialog(
                            onDismiss = {
                                pendingFile.delete()
                                pendingParamImportFileState = null
                            },
                            onConfirm = { applySurface, applyLayout ->
                                pendingParamImportFileState = null
                                executeUnifiedImport(pendingFile, applySurface, applyLayout, customName = null)
                            }
                        )
                    }

                    importConflictTargetState?.let { target ->
                        PackageNamePromptDialog(
                            title = "导入包重名冲突",
                            subtitle = "本地已存在名为「${target.conflictName}」且内容不同的包，请修改名称后导入：",
                            initialName = UnifiedPackageRepository.suggestUniquePackageName(
                                target.existingNames,
                                target.conflictName
                            ),
                            existingNames = target.existingNames,
                            confirmText = "改名导入",
                            onDismiss = {
                                target.file.delete()
                                importConflictTargetState = null
                            },
                            onConfirm = { newName ->
                                importConflictTargetState = null
                                executeUnifiedImport(
                                    target.file,
                                    target.applySurface,
                                    target.applyLayout,
                                    customName = newName
                                )
                            }
                        )
                    }

                    createTargetState?.let { target ->
                        val existingNames = existingNamesForKind(target.kind)
                        val title = when (target.kind) {
                            PackageKind.ART -> "新建美术资源包"
                            PackageKind.PARAM -> "新建参数设置包"
                            PackageKind.COVER_SLOT -> "新建书架封面包"
                            else -> "新建外观包"
                        }
                        val subtitle = if (target.kind == PackageKind.COVER_SLOT) {
                            "将创建空书架封面包并打开编辑器配置图集与兜底封面（同类型内包名唯一）："
                        } else {
                            "将从当前界面状态捕获并生成新包（同类型内包名唯一）："
                        }
                        PackageNamePromptDialog(
                            title = title,
                            subtitle = subtitle,
                            initialName = target.suggestedName,
                            existingNames = existingNames,
                            confirmText = "创建",
                            onDismiss = { createTargetState = null },
                            onConfirm = { newName ->
                                createTargetState = null
                                executeCreatePackage(target.kind, newName)
                            }
                        )
                    }

                    cloneTargetState?.let { target ->
                        val existingNames = existingNamesForKind(target.kind)
                        PackageNamePromptDialog(
                            title = "复制包（写时复制克隆）",
                            subtitle = "基于「${target.source.manifest.name}」创建派生包（仅存储增量修改文件，导出时自动物化）：",
                            initialName = target.suggestedName,
                            existingNames = existingNames,
                            confirmText = "复制并编辑",
                            onDismiss = { cloneTargetState = null },
                            onConfirm = { newName ->
                                cloneTargetState = null
                                executeClonePackage(target.kind, target.source.manifest.id, newName)
                            }
                        )
                    }

                    deleteTargetState?.let { target ->
                        DeletePackageConfirmDialog(
                            packageName = target.entry.manifest.name,
                            mountedSlots = target.mountedSlots,
                            onDismiss = { deleteTargetState = null },
                            onConfirm = {
                                deleteTargetState = null
                                executeDeletePackage(target.kind, target.entry.manifest.id)
                            }
                        )
                    }

                    editingArtEntryState?.let { entry ->
                        val artBaseDir = entry.manifest.basePackageId?.let { baseId ->
                            artPackagesState.firstOrNull { it.manifest.id == baseId }?.dir
                        }
                        ArtPackageEditorDialog(
                            entry = entry,
                            existingNames = artPackagesState.map { it.manifest.name },
                            preferNightSlot = artSlotNightState,
                            baseDir = artBaseDir,
                            onPickAssetFile = { _, onPicked ->
                                pendingAssetExpectFont = false
                                pendingAssetFileCallback = onPicked
                                pickAssetFileLauncher.launch {
                                    mode = HandleFileContract.FILE
                                    title = "选择美术资源文件"
                                }
                            },
                            onPickFontFile = { onPicked ->
                                pendingAssetExpectFont = true
                                pendingAssetFileCallback = onPicked
                                pickAssetFileLauncher.launch {
                                    mode = HandleFileContract.FILE
                                    title = "选择字体文件 (.ttf / .otf / .ttc)"
                                    allowExtensions = arrayOf("ttf", "otf", "ttc")
                                }
                            },
                            onDismiss = { editingArtEntryState = null },
                            onSave = { newName, updatedArt ->
                                editingArtEntryState = null
                                executeSaveEditedArt(entry.manifest.id, newName, updatedArt)
                            }
                        )
                    }

                    editingParamEntryState?.let { entry ->
                        ParamPackageEditorDialog(
                            entry = entry,
                            existingNames = paramPackagesState.map { it.manifest.name },
                            onDismiss = { editingParamEntryState = null },
                            onSave = { newName, updatedParam ->
                                editingParamEntryState = null
                                executeSaveEditedParam(entry.manifest.id, newName, updatedParam)
                            }
                        )
                    }

                    editingCoverSlotEntryState?.let { entry ->
                        CoverSlotPackageEditorDialog(
                            entry = entry,
                            existingNames = coverSlotPackagesState.map { it.manifest.name },
                            showTargetHintBanner = !coverSlotTargetHintDismissedState,
                            onDismissTargetHint = {
                                coverSlotTargetHintDismissedState = true
                                putPrefBoolean(PreferKey.coverSlotTargetHintDismissed, true)
                            },
                            onReshuffle = { executeReshuffleCoverSlot(entry) },
                            onPickSingleImage = { _, onPicked ->
                                pendingAssetFileCallback = onPicked
                                pickAssetFileLauncher.launch {
                                    mode = HandleFileContract.FILE
                                    title = "选择封面图片"
                                    allowExtensions = arrayOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
                                }
                            },
                            onPickMultipleImages = { onPicked ->
                                pendingMultiImageCallback = onPicked
                                pickMultipleImagesLauncher.launch("image/*")
                            },
                            onDismiss = { editingCoverSlotEntryState = null },
                            onSave = { newName, updatedSlot ->
                                editingCoverSlotEntryState = null
                                executeSaveEditedCoverSlot(entry.manifest.id, newName, updatedSlot)
                            }
                        )
                    }
                }
            }
        }
        binding.root.addView(composeView, binding.root.indexOfChild(binding.recyclerView))
    }

    private fun refreshStudioState() {
        updateCloudContainerState()
        val night = AppConfig.isNightTheme
        val eInk = AppConfig.isEInkMode
        isNightState = night
        isEInkState = eInk
        cornerScaleState = AppConfig.uiCornerScale
        layoutAlphaState = AppConfig.uiLayoutAlpha
        val storedGlassMode = getPrefString(PreferKey.exploreGlassEffectMode, "") ?: ""
        val legacyGlassEnabled = getPrefBoolean(PreferKey.exploreGlassEnabled, true)
        val resolvedGlassMode = resolveExploreGlassMode(storedGlassMode, legacyGlassEnabled)
        glassModeState = resolvedGlassMode
        val frosted = getPrefInt(PreferKey.frostedGlassLevel, AppConfig.frostedGlassLevel)
        val liquid = getPrefInt(PreferKey.liquidGlassLevel, AppConfig.liquidGlassLevel)
        glassLevelState = GlassMaterialPreset.levelFor(resolvedGlassMode, frosted, liquid).coerceIn(0, 100)
        useLegacyTokenPipelineState = getPrefBoolean(PreferKey.useLegacyTokenPipeline, false)
        useLegacyPackageManagerState = getPrefBoolean(PreferKey.useLegacyPackageManager, false)
        coverSlotTargetHintDismissedState = getPrefBoolean(PreferKey.coverSlotTargetHintDismissed, false)

        lifecycleScope.launch {
            LegacyPackageMigrator.migrateLocalPackagesIfNeeded(this@AppearanceKitActivity)
            UnifiedPackageRepository.ensureDefaultPackagesExist(this@AppearanceKitActivity)
            val arts = UnifiedPackageRepository.listArtPackages()
            val params = UnifiedPackageRepository.listParamPackages()
            val coverSlots = UnifiedPackageRepository.listCoverSlotPackages()
            artPackagesState = arts
            paramPackagesState = params
            coverSlotPackagesState = coverSlots
            activeDayArtIdState = UnifiedPackageRepository.activeArtPackageId(this@AppearanceKitActivity, false)
            activeNightArtIdState = UnifiedPackageRepository.activeArtPackageId(this@AppearanceKitActivity, true)
            activeDayParamIdState = UnifiedPackageRepository.activeParamPackageId(this@AppearanceKitActivity, false)
            activeNightParamIdState = UnifiedPackageRepository.activeParamPackageId(this@AppearanceKitActivity, true)
            activeDayCoverSlotIdState = UnifiedPackageRepository.activeCoverSlotPackageId(this@AppearanceKitActivity, false)
            activeNightCoverSlotIdState = UnifiedPackageRepository.activeCoverSlotPackageId(this@AppearanceKitActivity, true)
            coverSlotLockedState = UnifiedPackageRepository.isCoverSlotLocked(this@AppearanceKitActivity)
        }
    }

    private fun existingNamesForKind(kind: PackageKind): List<String> {
        return when (kind) {
            PackageKind.ART -> artPackagesState.map { it.manifest.name }
            PackageKind.PARAM -> paramPackagesState.map { it.manifest.name }
            PackageKind.COVER_SLOT -> coverSlotPackagesState.map { it.manifest.name }
            else -> emptyList()
        }
    }

    private fun mountArtPackage(entry: UnifiedPackageRepository.StoredEntry, forNight: Boolean) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.mountArtPackage(
                    context = this@AppearanceKitActivity,
                    entry = entry,
                    forNight = forNight
                )
            }.onSuccess {
                refreshStudioState()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun mountParamPackage(entry: UnifiedPackageRepository.StoredEntry, forNight: Boolean) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.mountParamPackage(
                    context = this@AppearanceKitActivity,
                    entry = entry,
                    forNight = forNight,
                    applyGeometryGlobal = true
                )
            }.onSuccess {
                refreshStudioState()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun mountCoverSlotPackage(entry: UnifiedPackageRepository.StoredEntry, forNight: Boolean) {
        lifecycleScope.launch {
            runCatching {
                val lock = if (entry.manifest.id != UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID) {
                    true
                } else {
                    null
                }
                UnifiedPackageRepository.mountCoverSlotPackage(
                    context = this@AppearanceKitActivity,
                    entry = entry,
                    forNight = forNight,
                    lockCoverSlot = lock
                )
            }.onSuccess {
                refreshStudioState()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun toggleCoverSlotLocked(locked: Boolean) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.setCoverSlotFollowArt(
                    context = this@AppearanceKitActivity,
                    followArt = !locked
                )
            }.onSuccess {
                refreshStudioState()
                toastOnUi(if (locked) "已切换为独立固定书架封面包" else "已切换为跟随美术包推荐封面")
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun openEditCoverSlot(entry: UnifiedPackageRepository.StoredEntry) {
        if (entry.manifest.id == UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID) {
            toastOnUi("内置默认「原生书架封面」不可直接修改，请复制后自定义")
            promptClonePackage(PackageKind.COVER_SLOT, entry)
            return
        }
        editingCoverSlotEntryState = entry
    }

    private fun promptCreatePackage(kind: PackageKind) {
        val existing = existingNamesForKind(kind)
        val base = when (kind) {
            PackageKind.ART -> "自定义美术包"
            PackageKind.PARAM -> "自定义参数包"
            PackageKind.COVER_SLOT -> "自定义书架封面包"
            else -> "自定义包"
        }
        createTargetState = CreateDialogTarget(
            kind = kind,
            suggestedName = UnifiedPackageRepository.suggestUniquePackageName(existing, base)
        )
    }

    private fun executeCreatePackage(kind: PackageKind, newName: String) {
        lifecycleScope.launch {
            runCatching {
                when (kind) {
                    PackageKind.ART -> {
                        val created = UnifiedPackageRepository.captureArtFromCurrent(
                            context = this@AppearanceKitActivity,
                            name = newName,
                            enforceUniqueName = true
                        )
                        UnifiedPackageRepository.mountArtPackage(
                            context = this@AppearanceKitActivity,
                            entry = created,
                            forNight = artSlotNightState
                        )
                        created
                    }
                    PackageKind.PARAM -> {
                        val created = UnifiedPackageRepository.captureParamFromCurrent(
                            context = this@AppearanceKitActivity,
                            name = newName,
                            enforceUniqueName = true
                        )
                        UnifiedPackageRepository.mountParamPackage(
                            context = this@AppearanceKitActivity,
                            entry = created,
                            forNight = paramSlotNightState,
                            applyGeometryGlobal = true
                        )
                        created
                    }
                    PackageKind.COVER_SLOT -> {
                        val rememberedTarget = getPrefString(PreferKey.lastCoverSlotApplyTarget)
                            ?.takeIf { it.isNotBlank() }
                        val created = UnifiedPackageRepository.createEmptyPackage(
                            kind = PackageKind.COVER_SLOT,
                            name = newName,
                            enforceUniqueName = true,
                            initialCoverSlotApplyTarget = rememberedTarget
                        )
                        UnifiedPackageRepository.mountCoverSlotPackage(
                            context = this@AppearanceKitActivity,
                            entry = created,
                            forNight = coverSlotNightState,
                            lockCoverSlot = true
                        )
                        created
                    }
                    else -> throw IllegalArgumentException("Unsupported package kind: ${kind.id}")
                }
            }.onSuccess { created ->
                refreshStudioState()
                if (kind == PackageKind.COVER_SLOT) {
                    editingCoverSlotEntryState = created
                } else {
                    toastOnUi(R.string.success)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun promptClonePackage(kind: PackageKind, source: UnifiedPackageRepository.StoredEntry) {
        val existing = existingNamesForKind(kind)
        cloneTargetState = CloneDialogTarget(
            kind = kind,
            source = source,
            suggestedName = UnifiedPackageRepository.suggestUniquePackageName(existing, source.manifest.name)
        )
    }

    private fun executeClonePackage(kind: PackageKind, sourceId: String, newName: String) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.clonePackageCoW(kind, sourceId, newName)
            }.onSuccess { cloned ->
                refreshStudioState()
                when (kind) {
                    PackageKind.ART -> editingArtEntryState = cloned
                    PackageKind.PARAM -> editingParamEntryState = cloned
                    PackageKind.COVER_SLOT -> editingCoverSlotEntryState = cloned
                    else -> Unit
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun executeSaveEditedArt(entryId: String, newName: String, updatedArt: ArtPackagePayload) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.saveEditedArtPackage(
                    context = this@AppearanceKitActivity,
                    entryId = entryId,
                    newName = newName,
                    updatedArt = updatedArt
                )
            }.onSuccess {
                refreshStudioState()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun executeSaveEditedParam(entryId: String, newName: String, updatedParam: ParamPackagePayload) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.saveEditedParamPackage(
                    context = this@AppearanceKitActivity,
                    entryId = entryId,
                    newName = newName,
                    updatedParam = updatedParam
                )
            }.onSuccess {
                refreshStudioState()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun executeSaveEditedCoverSlot(
        entryId: String,
        newName: String,
        updatedCoverSlot: CoverSlotPackagePayload
    ) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.saveEditedCoverSlotPackage(
                    context = this@AppearanceKitActivity,
                    entryId = entryId,
                    newName = newName,
                    updatedCoverSlot = updatedCoverSlot
                )
            }.onSuccess {
                val normalizedTarget = CoverSlotApplyTarget.normalize(
                    updatedCoverSlot.applyTarget,
                    defaultValue = CoverSlotApplyTarget.GROUPS
                )
                putPrefString(PreferKey.lastCoverSlotApplyTarget, normalizedTarget)
                if (normalizedTarget != CoverSlotApplyTarget.GROUPS && !coverSlotTargetHintDismissedState) {
                    coverSlotTargetHintDismissedState = true
                    putPrefBoolean(PreferKey.coverSlotTargetHintDismissed, true)
                }
                refreshStudioState()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun executeReshuffleCoverSlot(entry: UnifiedPackageRepository.StoredEntry) {
        CoverCollectionManager.reshuffle(entry.manifest.id)
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, isNightState)
        postEvent(EventBus.MAIN_THEME_BACKGROUND_CHANGED, isNightState)
        toastOnUi("已重新洗牌封面分配")
    }

    private fun promptDeletePackage(kind: PackageKind, entry: UnifiedPackageRepository.StoredEntry) {
        if (kind == PackageKind.COVER_SLOT && entry.manifest.id == UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID) {
            toastOnUi("内置默认「原生书架封面」不可删除")
            return
        }
        val list = when (kind) {
            PackageKind.ART -> artPackagesState
            PackageKind.PARAM -> paramPackagesState
            PackageKind.COVER_SLOT -> coverSlotPackagesState
            else -> emptyList()
        }
        if (list.size <= 1) {
            toastOnUi("至少需保留 1 个预设包，无法删除最后一个包")
            return
        }
        deleteTargetState = DeleteDialogTarget(
            kind = kind,
            entry = entry,
            mountedSlots = UnifiedPackageRepository.getMountedSlotLabels(this, entry.manifest.id)
        )
    }

    private fun executeDeletePackage(kind: PackageKind, entryId: String) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.deletePackage(this@AppearanceKitActivity, kind, entryId)
            }.onSuccess { result ->
                refreshStudioState()
                if (result.affectedSlots.isNotEmpty() && result.fallbackEntry != null) {
                    toastOnUi("已删除，[${result.affectedSlots.joinToString("、")}] 已回退至「${result.fallbackEntry.manifest.name}」")
                } else {
                    toastOnUi(R.string.delete_success)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun exportPackageEntry(entry: UnifiedPackageRepository.StoredEntry) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.exportEntryZip(entry)
            }.onSuccess { zipFile ->
                val suffix = when (entry.manifest.kind) {
                    PackageKind.ART -> "lart.zip"
                    PackageKind.PARAM -> "lparam.zip"
                    PackageKind.COVER_SLOT -> "lcover.zip"
                    else -> "zip"
                }
                PackageManageUiDelegate.launchExportZip(
                    exportPackage,
                    zipFile,
                    "${entry.manifest.name}.$suffix"
                )
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun commitCornerScale(scale: Float) {
        val clamped = scale.coerceIn(0f, 3f)
        val night = isNightState
        lifecycleScope.launch {
            runCatching {
                ThemePackageManager.updateActiveRuntimeOverride(
                    context = this@AppearanceKitActivity,
                    isNightTheme = night
                ) { config ->
                    config.copy(uiCornerScale = clamped)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun commitLayoutAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 100)
        val night = isNightState
        lifecycleScope.launch {
            runCatching {
                ThemePackageManager.updateActiveRuntimeOverride(
                    context = this@AppearanceKitActivity,
                    isNightTheme = night
                ) { config ->
                    config.copy(uiLayoutAlpha = clamped)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun selectGlassMode(nextMode: String) {
        if (nextMode == glassModeState) return
        val point = GlassMaterialPreset.presetPoint(nextMode)
        glassModeState = nextMode
        glassLevelState = point.level
        PrefBatchTransaction.run(this) {
            putPrefInt(PreferKey.frostedGlassLevel, point.level)
            putPrefInt(PreferKey.liquidGlassLevel, point.level)
            putPrefInt(PreferKey.exploreGlassBlurScale, point.scales.blur)
            putPrefInt(PreferKey.exploreGlassTintScale, point.scales.tint)
            putPrefInt(PreferKey.exploreGlassShellScale, point.scales.shell)
            putPrefInt(PreferKey.exploreGlassStrokeScale, point.scales.stroke)
            putPrefString(PreferKey.exploreGlassEffectMode, nextMode)
            putPrefBoolean(PreferKey.glassPresetMatched, true)
        }
        UnifiedPackageRepository.markParamDirty(this, true)
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, isNightState)
    }

    private fun commitGlassLevel(level: Int) {
        val clamped = level.coerceIn(0, 100)
        PrefBatchTransaction.run(this) {
            putPrefInt(PreferKey.frostedGlassLevel, clamped)
            putPrefInt(PreferKey.liquidGlassLevel, clamped)
            putPrefBoolean(PreferKey.glassPresetMatched, false)
        }
        UnifiedPackageRepository.markParamDirty(this, true)
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, isNightState)
    }

    private fun toggleLegacyTokenPipeline(enabled: Boolean) {
        useLegacyTokenPipelineState = enabled
        putPrefBoolean(PreferKey.useLegacyTokenPipeline, enabled)
        recomputeThemeUiSignatureAfterBatch()
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, isNightState)
    }

    private fun toggleLegacyPackageManager(enabled: Boolean) {
        useLegacyPackageManagerState = enabled
        putPrefBoolean(PreferKey.useLegacyPackageManager, enabled)
        recomputeThemeUiSignatureAfterBatch()
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, isNightState)
    }

    private fun selectImportPackage() {
        importPackage.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.appearance_kit_import)
            allowExtensions = arrayOf("red", "zip", "json")
        }
    }

    private fun importAppearanceKit(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val file = PackageManageUiDelegate.stageImportUriToTempFile(
                    context = this@AppearanceKitActivity,
                    uri = uri,
                    dirName = "uiPackageImport",
                    filePrefix = "import_appearance_pkg",
                    extension = "zip"
                )
                val kind = UnifiedPackageRepository.peekKind(file)
                if (kind == PackageKind.PARAM || kind == PackageKind.KIT) {
                    pendingParamImportFileState = file
                } else {
                    executeUnifiedImport(file, applySurface = true, applyLayout = false, customName = null)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun executeUnifiedImport(
        file: File,
        applySurface: Boolean,
        applyLayout: Boolean,
        customName: String?
    ) {
        lifecycleScope.launch {
            runCatching {
                val result = UnifiedPackageRepository.importFile(
                    context = this@AppearanceKitActivity,
                    file = file,
                    importSurface = applySurface,
                    importLayout = applyLayout,
                    customNameOverride = customName
                )
                if (result.nameConflictName != null) {
                    val kind = UnifiedPackageRepository.peekKind(file)
                    val existingNames = existingNamesForKind(kind ?: PackageKind.ART)
                    importConflictTargetState = ImportConflictTarget(
                        file = file,
                        applySurface = applySurface,
                        applyLayout = applyLayout,
                        conflictName = result.nameConflictName,
                        existingNames = existingNames
                    )
                    return@runCatching null
                }
                file.delete()
                result.duplicateArt?.let { dup ->
                    UnifiedPackageRepository.mountArtPackage(
                        context = this@AppearanceKitActivity,
                        entry = dup,
                        forNight = artSlotNightState
                    )
                }
                result.duplicateParam?.let { dup ->
                    UnifiedPackageRepository.mountParamPackage(
                        context = this@AppearanceKitActivity,
                        entry = dup,
                        forNight = paramSlotNightState,
                        applyGeometryGlobal = applySurface
                    )
                }
                result.duplicateCoverSlot?.let { dup ->
                    UnifiedPackageRepository.mountCoverSlotPackage(
                        context = this@AppearanceKitActivity,
                        entry = dup,
                        forNight = coverSlotNightState,
                        lockCoverSlot = true
                    )
                }
                result.importedArt?.let {
                    UnifiedPackageRepository.mountArtPackage(
                        context = this@AppearanceKitActivity,
                        entry = it,
                        forNight = artSlotNightState
                    )
                }
                result.importedParam?.let {
                    UnifiedPackageRepository.mountParamPackage(
                        context = this@AppearanceKitActivity,
                        entry = it,
                        forNight = paramSlotNightState,
                        applyGeometryGlobal = applySurface
                    )
                }
                result.importedCoverSlot?.let {
                    UnifiedPackageRepository.mountCoverSlotPackage(
                        context = this@AppearanceKitActivity,
                        entry = it,
                        forNight = coverSlotNightState,
                        lockCoverSlot = true
                    )
                }
                result
            }.onSuccess { result ->
                if (result == null) return@onSuccess
                refreshStudioState()
                val newItems = listOfNotNull(
                    result.importedArt?.manifest?.name,
                    result.importedParam?.manifest?.name,
                    result.importedCoverSlot?.manifest?.name
                )
                val reusedItems = listOfNotNull(
                    result.duplicateArt?.manifest?.name,
                    result.duplicateParam?.manifest?.name,
                    result.duplicateCoverSlot?.manifest?.name
                )
                val message = when {
                    newItems.isNotEmpty() && reusedItems.isNotEmpty() ->
                        "已导入「${newItems.joinToString("、")}」，并复用相同内容的「${reusedItems.joinToString("、")}」"
                    newItems.isNotEmpty() ->
                        "成功导入并挂载「${newItems.joinToString("、")}」"
                    reusedItems.isNotEmpty() ->
                        "已存在相同内容的包「${reusedItems.joinToString("、")}」，已直接挂载"
                    result.legacyTotal > 0 ->
                        "已从旧版套件转换导入 ${result.legacyTotal} 项配置"
                    else ->
                        "未识别到可导入的美术包、参数包或书架封面包"
                }
                toastOnUi(message)
            }.onFailure {
                file.delete()
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun uploadPackageEntry(entry: UnifiedPackageRepository.StoredEntry) {
        val type = when (entry.manifest.kind) {
            PackageKind.ART -> WebDavTaskType.ART_PACKAGE_UPLOAD
            PackageKind.PARAM -> WebDavTaskType.PARAM_PACKAGE_UPLOAD
            PackageKind.COVER_SLOT -> WebDavTaskType.COVER_SLOT_PACKAGE_UPLOAD
            else -> WebDavTaskType.ART_PACKAGE_UPLOAD
        }
        val queued = WebDavTaskManager.enqueueUpload(
            key = "unified_upload:${entry.manifest.kind.id}:${entry.manifest.id}",
            name = entry.manifest.name,
            type = type,
            runningMessage = "正在上传至云端...",
            successMessage = "已上传至云端"
        ) {
            UnifiedPackageRepository.uploadEntry(
                entry = entry,
                containerId = cloudContainerId,
                scope = S3ContainerScope.UI_PACKAGE.key
            )
        }
        if (queued) {
            toastOnUi("已加入云端上传队列")
            showSyncTasks()
        } else {
            toastOnUi("该外观包正在上传中")
        }
    }

    private fun selectCloudContainer() {
        val actions = PackageManageUiDelegate.buildContainerSelectorActions(
            context = this,
            scope = S3ContainerScope.UI_PACKAGE.key,
            currentSelectedId = cloudContainerId
        ) {
            updateCloudContainerState()
        }
        if (actions.isEmpty()) return
        showComposeChoiceListDialog(
            title = getString(R.string.s3_bucket),
            labels = actions.map { it.text },
            selectedIndex = actions.indexOfFirst { it.checked }.coerceAtLeast(0)
        ) { idx ->
            actions.getOrNull(idx)?.onClick?.invoke()
        }
    }

    private fun showCloudPackagesMenu() {
        lifecycleScope.launch {
            runCatching {
                val arts = UnifiedPackageRepository.listCloudPackages(
                    kind = PackageKind.ART,
                    containerId = cloudContainerId,
                    scope = S3ContainerScope.UI_PACKAGE.key
                ).map { PackageKind.ART to it }
                val params = UnifiedPackageRepository.listCloudPackages(
                    kind = PackageKind.PARAM,
                    containerId = cloudContainerId,
                    scope = S3ContainerScope.UI_PACKAGE.key
                ).map { PackageKind.PARAM to it }
                val coverSlots = UnifiedPackageRepository.listCloudPackages(
                    kind = PackageKind.COVER_SLOT,
                    containerId = cloudContainerId,
                    scope = S3ContainerScope.UI_PACKAGE.key
                ).map { PackageKind.COVER_SLOT to it }
                arts + params + coverSlots
            }.onSuccess { remoteItems ->
                if (remoteItems.isEmpty()) {
                    toastOnUi("云端 uiPackages/{art,param,cover_slot}/ 目录暂无可用外观包")
                    return@onSuccess
                }
                showComposeChoiceListDialog(
                    title = "云端外观包（点击下载并导入）",
                    labels = remoteItems.map { (kind, file) ->
                        val prefix = when (kind) {
                            PackageKind.ART -> "[美术]"
                            PackageKind.PARAM -> "[参数]"
                            PackageKind.COVER_SLOT -> "[封面]"
                            else -> "[外观]"
                        }
                        "$prefix ${file.displayName.removeSuffix(".zip")}"
                    },
                    selectedIndex = -1
                ) { idx ->
                    val chosen = remoteItems.getOrNull(idx) ?: return@showComposeChoiceListDialog
                    downloadCloudPackage(chosen.first, chosen.second)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun downloadCloudPackage(kind: PackageKind, remoteFile: CloudStorageFile) {
        lifecycleScope.launch {
            runCatching {
                UnifiedPackageRepository.downloadAndImportCloudPackage(
                    context = this@AppearanceKitActivity,
                    kind = kind,
                    remoteName = remoteFile.displayName,
                    importSurface = true,
                    importLayout = false,
                    containerId = cloudContainerId,
                    scope = S3ContainerScope.UI_PACKAGE.key
                )
            }.onSuccess { result ->
                if (result.nameConflictName != null) {
                    toastOnUi("本地已有同名不同内容包「${result.nameConflictName}」，已取消覆盖")
                    return@onSuccess
                }
                result.duplicateArt?.let {
                    UnifiedPackageRepository.mountArtPackage(this@AppearanceKitActivity, it, artSlotNightState)
                }
                result.importedArt?.let {
                    UnifiedPackageRepository.mountArtPackage(this@AppearanceKitActivity, it, artSlotNightState)
                }
                result.duplicateParam?.let {
                    UnifiedPackageRepository.mountParamPackage(this@AppearanceKitActivity, it, paramSlotNightState, applyGeometryGlobal = true)
                }
                result.importedParam?.let {
                    UnifiedPackageRepository.mountParamPackage(this@AppearanceKitActivity, it, paramSlotNightState, applyGeometryGlobal = true)
                }
                result.duplicateCoverSlot?.let {
                    UnifiedPackageRepository.mountCoverSlotPackage(this@AppearanceKitActivity, it, coverSlotNightState, lockCoverSlot = true)
                }
                result.importedCoverSlot?.let {
                    UnifiedPackageRepository.mountCoverSlotPackage(this@AppearanceKitActivity, it, coverSlotNightState, lockCoverSlot = true)
                }
                refreshStudioState()
                toastOnUi("已从云端下载并挂载「${remoteFile.displayName.removeSuffix(".zip")}」")
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun showSyncTasks() {
        showPackageSyncTaskDialog(
            setOf(
                WebDavTaskType.ART_PACKAGE_UPLOAD,
                WebDavTaskType.PARAM_PACKAGE_UPLOAD,
                WebDavTaskType.COVER_SLOT_PACKAGE_UPLOAD,
                WebDavTaskType.THEME_PACKAGE_UPLOAD,
                WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD,
                WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD
            )
        )
    }

    companion object : ActivitySearchSource {
        override fun searchEntries(context: android.content.Context): List<SettingsSearchIndex.Entry> = listOf(
            SettingsSearchIndex.Entry(
                ownerKey = "appearanceKit",
                ownerConfigTag = "",
                key = "activity_appearance_kit_art",
                title = "美术资源包（日/夜）",
                summary = "在外观中心自由搭配与编辑主题色板、全套壁纸、启动页图、字体与底栏图标",
                aliases = listOf(
                    "美术包", "美术资源包", "主题包", "主题管理", "配色", "字体", "壁纸",
                    "背景图", "启动页", "图标", "appearance", "theme", "welcome"
                ),
                destination = SearchDestination.Activity(AppearanceKitActivity::class.java.name)
            ),
            SettingsSearchIndex.Entry(
                ownerKey = "appearanceKit",
                ownerConfigTag = "",
                key = "activity_appearance_kit_param",
                title = "参数设置包与外观微调",
                summary = "调整全局及顶栏/底栏/卡片/弹窗四区域材质（实底/透明/磨砂/液态玻璃）、圆角、阴影与密度",
                aliases = listOf(
                    "参数包", "参数设置包", "外观中心", "顶栏包", "底栏包", "发现玻璃", "液态玻璃",
                    "毛玻璃", "实底", "透明", "圆角", "阴影", "密度", "glass", "topbar", "navbar"
                ),
                destination = SearchDestination.Activity(AppearanceKitActivity::class.java.name)
            ),
            SettingsSearchIndex.Entry(
                ownerKey = "appearanceKit",
                ownerConfigTag = "",
                key = "activity_appearance_kit_cover_slot",
                title = "书架封面包（日/夜）",
                summary = "独立配置书架封面图集、应用模式（随机/顺序/混合/仅兜底）、日夜兜底单图与分组默认封面",
                aliases = listOf(
                    "书架封面包", "封面包", "封面集", "默认封面", "分组封面", "cover", "cover_slot"
                ),
                destination = SearchDestination.Activity(AppearanceKitActivity::class.java.name)
            )
        )
    }
}

@Composable
private fun AppearanceStudioScreen(
    isEInkMode: Boolean,
    artSlotNight: Boolean,
    paramSlotNight: Boolean,
    coverSlotNight: Boolean,
    artPackages: List<UnifiedPackageRepository.StoredEntry>,
    paramPackages: List<UnifiedPackageRepository.StoredEntry>,
    coverSlotPackages: List<UnifiedPackageRepository.StoredEntry>,
    activeDayArtId: String,
    activeNightArtId: String,
    activeDayParamId: String,
    activeNightParamId: String,
    activeDayCoverSlotId: String,
    activeNightCoverSlotId: String,
    coverSlotLocked: Boolean,
    cornerScale: Float,
    layoutAlpha: Int,
    glassMode: String,
    glassOptions: List<SegmentedOption<String>>,
    glassLevel: Int,
    useLegacyTokenPipeline: Boolean,
    useLegacyPackageManager: Boolean,
    topMenuActions: () -> List<AppManagementMenuAction>,
    onBack: () -> Unit,
    onSelectArtSlotNight: (Boolean) -> Unit,
    onSelectParamSlotNight: (Boolean) -> Unit,
    onSelectCoverSlotNight: (Boolean) -> Unit,
    onMountArtPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onMountParamPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onMountCoverSlotPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onToggleCoverSlotLocked: (Boolean) -> Unit,
    onCreatePackage: (PackageKind) -> Unit,
    onImportPackage: () -> Unit,
    onClonePackage: (PackageKind, UnifiedPackageRepository.StoredEntry) -> Unit,
    onEditArtPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onEditParamPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onEditCoverSlotPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onReshuffleCoverSlotPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onExportPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onUploadPackage: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onDeletePackage: (PackageKind, UnifiedPackageRepository.StoredEntry) -> Unit,
    onCornerScaleDrag: (Int) -> Unit,
    onCornerScaleCommit: () -> Unit,
    onLayoutAlphaDrag: (Int) -> Unit,
    onLayoutAlphaCommit: () -> Unit,
    onSelectGlassMode: (String) -> Unit,
    onGlassLevelDrag: (Int) -> Unit,
    onGlassLevelCommit: () -> Unit,
    onToggleLegacyTokenPipeline: (Boolean) -> Unit,
    onToggleLegacyPackageManager: (Boolean) -> Unit
) {
    val palette = rememberAppManagementPalette()
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()
    val effectiveGlassSig = glassSignature.takeIf { wallpaperVisible }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(glassPageBackgroundColor(palette.settings.page, wallpaperVisible))
    ) {
        AppComposeTopBar(
            title = stringResource(R.string.appearance_studio_title),
            palette = palette,
            actions = listOf(
                AppManagementAction(
                    text = stringResource(R.string.more),
                    menuActions = topMenuActions
                )
            ),
            onBack = onBack,
            wallpaperVisible = wallpaperVisible,
            glassSignature = glassSignature
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = AppLayoutTokens.PageHorizontal,
                top = 10.dp,
                end = AppLayoutTokens.PageHorizontal,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(AppLayoutTokens.SectionGap)
        ) {
            // 1. 实时预览卡（内嵌可折叠「快速微调」抽屉）
            item("2_live_preview") {
                StudioLivePreviewCard(
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    previewCornerScale = cornerScale,
                    previewLayoutAlpha = layoutAlpha,
                    isEInkMode = isEInkMode,
                    glassMode = glassMode,
                    glassOptions = glassOptions,
                    glassLevel = glassLevel,
                    onCornerScaleDrag = onCornerScaleDrag,
                    onCornerScaleCommit = onCornerScaleCommit,
                    onLayoutAlphaDrag = onLayoutAlphaDrag,
                    onLayoutAlphaCommit = onLayoutAlphaCommit,
                    onSelectGlassMode = onSelectGlassMode,
                    onGlassLevelDrag = onGlassLevelDrag,
                    onGlassLevelCommit = onGlassLevelCommit
                )
            }
            // 3. 美术资源包选择（日 / 夜双槽）
            item("3_art_packages") {
                StudioDualSlotPackageSection(
                    title = "美术资源包（日 / 夜）",
                    subtitle = "包含色板、全套壁纸、启动页图、字体与底栏图标",
                    isNightSlot = artSlotNight,
                    dayMountedId = activeDayArtId,
                    nightMountedId = activeNightArtId,
                    entries = artPackages,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    onSelectSlotNight = onSelectArtSlotNight,
                    onSelectEntry = onMountArtPackage,
                    onCreateNew = { onCreatePackage(PackageKind.ART) },
                    onImport = onImportPackage,
                    onCloneEntry = { onClonePackage(PackageKind.ART, it) },
                    onEditEntry = onEditArtPackage,
                    onExportEntry = onExportPackage,
                    onUploadEntry = onUploadPackage,
                    onDeleteEntry = { onDeletePackage(PackageKind.ART, it) }
                )
            }
            // 4. 参数设置包选择（日 / 夜双槽）
            item("4_param_packages") {
                StudioDualSlotPackageSection(
                    title = "参数设置包（日 / 夜）",
                    subtitle = "材质氛围随日/夜槽切换；圆角、阴影、空间密度与 4 区域覆写保持全局统一",
                    isNightSlot = paramSlotNight,
                    dayMountedId = activeDayParamId,
                    nightMountedId = activeNightParamId,
                    entries = paramPackages,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    onSelectSlotNight = onSelectParamSlotNight,
                    onSelectEntry = onMountParamPackage,
                    onCreateNew = { onCreatePackage(PackageKind.PARAM) },
                    onImport = onImportPackage,
                    onCloneEntry = { onClonePackage(PackageKind.PARAM, it) },
                    onEditEntry = onEditParamPackage,
                    onExportEntry = onExportPackage,
                    onUploadEntry = onUploadPackage,
                    onDeleteEntry = { onDeletePackage(PackageKind.PARAM, it) }
                )
            }
            // 5. 书架封面包选择（日 / 夜双槽）— 第三条正交轨
            item("5_cover_slot_packages") {
                StudioDualSlotPackageSection(
                    title = "书架封面包（日 / 夜）",
                    subtitle = "独立管理书架封面图集、应用模式（随机/顺序/混合/仅兜底）、日夜默认与分组封面",
                    isNightSlot = coverSlotNight,
                    dayMountedId = activeDayCoverSlotId,
                    nightMountedId = activeNightCoverSlotId,
                    entries = coverSlotPackages,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    headerExtra = {
                        CoverSlotFollowModeBanner(
                            coverSlotLocked = coverSlotLocked,
                            palette = palette,
                            onToggleLocked = onToggleCoverSlotLocked
                        )
                    },
                    canDeleteEntry = { entry ->
                        entry.manifest.id != UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID
                    },
                    canReshuffleEntry = { entry ->
                        val slot = entry.manifest.coverSlot
                        val mode = CoverSlotApplyMode.normalize(slot?.applyMode)
                        entry.manifest.id != UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID &&
                            !slot?.covers.isNullOrEmpty() &&
                            (mode == CoverSlotApplyMode.RANDOM || mode == CoverSlotApplyMode.MIXED)
                    },
                    onSelectSlotNight = onSelectCoverSlotNight,
                    onSelectEntry = onMountCoverSlotPackage,
                    onCreateNew = { onCreatePackage(PackageKind.COVER_SLOT) },
                    onImport = onImportPackage,
                    onCloneEntry = { onClonePackage(PackageKind.COVER_SLOT, it) },
                    onEditEntry = onEditCoverSlotPackage,
                    onReshuffleEntry = onReshuffleCoverSlotPackage,
                    onExportEntry = onExportPackage,
                    onUploadEntry = onUploadPackage,
                    onDeleteEntry = { onDeletePackage(PackageKind.COVER_SLOT, it) }
                )
            }
            // 底部折叠的兼容回滚开关（保留 1 个大版本）
            item("6_rollback_switches") {
                StudioRollbackSwitchesSection(
                    useLegacyTokenPipeline = useLegacyTokenPipeline,
                    useLegacyPackageManager = useLegacyPackageManager,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    onToggleLegacyTokenPipeline = onToggleLegacyTokenPipeline,
                    onToggleLegacyPackageManager = onToggleLegacyPackageManager
                )
            }
        }
    }
}

@Composable
private fun StudioLivePreviewCard(
    palette: AppManagementPalette,
    glassSignature: String?,
    previewCornerScale: Float,
    previewLayoutAlpha: Int,
    isEInkMode: Boolean,
    glassMode: String,
    glassOptions: List<SegmentedOption<String>>,
    glassLevel: Int,
    onCornerScaleDrag: (Int) -> Unit,
    onCornerScaleCommit: () -> Unit,
    onLayoutAlphaDrag: (Int) -> Unit,
    onLayoutAlphaCommit: () -> Unit,
    onSelectGlassMode: (String) -> Unit,
    onGlassLevelDrag: (Int) -> Unit,
    onGlassLevelCommit: () -> Unit
) {
    val tokens = LocalLegadoTokens.current
    val previewRadii = remember(previewCornerScale) {
        resolveStudioPreviewRadii(previewCornerScale)
    }
    val alphaFactor = remember(previewLayoutAlpha) {
        resolveStudioPreviewAlphaFactor(previewLayoutAlpha)
    }
    var quickDrawerExpanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val panelRadius = previewRadii.panelRadiusDp.dp
    val panelRadiusPx = with(density) { panelRadius.toPx() }
    val actionRadius = previewRadii.actionRadiusDp.dp
    val innerRadius = previewRadii.innerRadiusDp.dp
    val panelShape = RoundedCornerShape(panelRadius)
    val slotShape = RoundedCornerShape(actionRadius)
    val innerShape = RoundedCornerShape(innerRadius)
    val pillShape = if (previewRadii.isSquare) RoundedCornerShape(0.dp) else CircleShape

    val bgColor = Color(tokens?.colors?.backgroundArgb ?: palette.settings.page.value.toInt())
    val primaryColor = Color(tokens?.colors?.primaryArgb ?: palette.settings.accent.value.toInt())
    val cardColor = Color(tokens?.colors?.cardArgb ?: palette.settings.row)
    val bottomColor = Color(tokens?.colors?.bottomBarArgb ?: palette.settings.bottomBar)
    val accentColor = palette.settings.accent

    val paletteSig = tokens?.paletteSignature
    val themeSig = palette.settings.themeSignature
    val uiFontPath = remember(paletteSig, themeSig) { AppConfig.uiFontPath }
    val titleFontPath = remember(paletteSig, themeSig) { AppConfig.titleFontPath }
    val uiFontState by rememberPinnedPreviewTypeface(uiFontPath)
    val titleFontState by rememberPinnedPreviewTypeface(titleFontPath)
    val bodyFamily = remember(uiFontState.typeface, palette.settings.bodyFontFamily) {
        uiFontState.typeface?.let { FontFamily(it) } ?: palette.settings.bodyFontFamily
    }
    val titleFamily = remember(titleFontState.typeface, palette.settings.titleFontFamily) {
        titleFontState.typeface?.let { FontFamily(it) } ?: palette.settings.titleFontFamily
    }
    val uiFontLabel = remember(uiFontPath, uiFontState) {
        resolveStudioFontLabel(uiFontPath, uiFontState)
    }
    val titleFontLabel = remember(titleFontPath, titleFontState) {
        resolveStudioFontLabel(titleFontPath, titleFontState)
    }

    val topBarEffectiveAlpha = (0.85f * alphaFactor).coerceIn(0.12f, 1f)
    val effectiveTopBarColor = Color(
        ColorUtils.blendARGB(bgColor.toArgb(), primaryColor.toArgb(), topBarEffectiveAlpha)
    )
    val topBarTitleColor = if (isEInkMode) {
        if (ColorUtils.calculateLuminance(effectiveTopBarColor.toArgb()) > 0.5) Color.Black else Color.White
    } else {
        if (ColorUtils.calculateLuminance(effectiveTopBarColor.toArgb()) > 0.55) {
            palette.settings.primaryText
        } else {
            Color.White.copy(alpha = 0.94f)
        }
    }

    val previewTitleColor = if (isEInkMode) palette.settings.primaryText else palette.settings.accent
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    bgColor.copy(
                        alpha = if (glassSignature != null) 0.35f * alphaFactor else alphaFactor
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.appearance_studio_preview_title),
                    color = previewTitleColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = palette.settings.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (quickDrawerExpanded) "收起快速微调 ▴" else "快速微调 ▾",
                    color = previewTitleColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { quickDrawerExpanded = !quickDrawerExpanded }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(slotShape)
                    .background(primaryColor.copy(alpha = topBarEffectiveAlpha))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                    Text(
                        text = "我的书架",
                        color = topBarTitleColor,
                        fontFamily = titleFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(pillShape)
                            .background(accentColor)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .heightIn(min = 46.dp)
                            .clip(innerShape)
                            .background(accentColor.copy(alpha = (0.28f * alphaFactor).coerceIn(0.08f, 0.5f)))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .clip(slotShape)
                            .background(cardColor.copy(alpha = (0.88f * alphaFactor).coerceIn(0.12f, 1f)))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "书海漫游 · 第一章",
                            color = palette.settings.primaryText,
                            fontFamily = titleFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "正文字体效果预览 AaBb 123",
                            color = palette.settings.secondaryText,
                            fontFamily = bodyFamily,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(pillShape)
                        .background(bottomColor.copy(alpha = (0.90f * alphaFactor).coerceIn(0.12f, 1f)))
                )
                val fontStripBg = if (isEInkMode) {
                    Color(palette.settings.row)
                } else {
                    cardColor.copy(alpha = (0.68f * alphaFactor).coerceIn(0.24f, 0.92f))
                }
                val fontStripBorder = if (isEInkMode) {
                    palette.settings.primaryText.copy(alpha = 0.45f)
                } else {
                    palette.settings.divider.copy(alpha = 0.7f)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(slotShape)
                        .background(fontStripBg)
                        .border(0.6.dp, fontStripBorder, slotShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "标题：$titleFontLabel",
                        color = palette.settings.primaryText,
                        fontFamily = titleFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(11.dp)
                            .background(fontStripBorder)
                    )
                    Text(
                        text = "正文：$uiFontLabel",
                        color = palette.settings.primaryText,
                        fontFamily = bodyFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                AnimatedVisibility(visible = quickDrawerExpanded) {
                    val cornerStep = cornerScaleToSliderStep(previewCornerScale)
                    val glassSliderEnabled = isStudioGlassSliderEnabled(isEInkMode, glassMode)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HorizontalDivider(color = palette.settings.divider, thickness = 0.5.dp)
                        StudioSliderItem(
                            title = stringResource(R.string.ui_corner_scale),
                            valueText = String.format(Locale.US, "%.1fx", previewCornerScale),
                            value = cornerStep,
                            range = 0..30,
                            palette = palette,
                            onValueChange = onCornerScaleDrag,
                            onValueChangeFinished = onCornerScaleCommit
                        )
                        StudioSliderItem(
                            title = stringResource(R.string.ui_layout_alpha),
                            valueText = stringResource(R.string.ui_layout_alpha_value, previewLayoutAlpha),
                            value = previewLayoutAlpha.coerceIn(0, 100),
                            range = 0..100,
                            palette = palette,
                            onValueChange = onLayoutAlphaDrag,
                            onValueChangeFinished = onLayoutAlphaCommit
                        )
                        if (!isEInkMode) {
                            LegadoSegmentedControl(
                                options = glassOptions,
                                selectedValue = glassMode,
                                palette = palette.miuix,
                                onSelected = onSelectGlassMode,
                                tone = SegmentedControlTone.SurfaceThumb,
                                fontSize = 11.sp
                            )
                            if (glassSliderEnabled) {
                                StudioSliderItem(
                                    title = stringResource(R.string.explore_glass_level),
                                    valueText = "$glassLevel%",
                                    value = glassLevel.coerceIn(0, 100),
                                    range = 0..100,
                                    enabled = true,
                                    palette = palette,
                                    onValueChange = onGlassLevelDrag,
                                    onValueChangeFinished = onGlassLevelCommit
                                )
                            }
                        }
                    }
                }
            }
        }
    if (glassSignature != null) {
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = panelRadiusPx,
            configSignature = glassSignature,
            modifier = Modifier
                .fillMaxWidth()
                .clip(panelShape),
            fallback = Modifier.appSettingPanelBackground(
                normalColor = palette.settings.row,
                borderColor = palette.settings.border,
                panelImage = null,
                radiusPx = panelRadiusPx
            ),
            requireWallpaperHost = true
        ) { body() }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .appSettingPanelBackground(
                    normalColor = palette.settings.row,
                    borderColor = palette.settings.border,
                    panelImage = null,
                    radiusPx = panelRadiusPx
                )
        ) { body() }
    }
}

private fun resolveStudioFontLabel(path: String, state: FontPreviewLoadState): String {
    if (path.isBlank() || state.sourceKind == ArtFontSourceKind.DEFAULT) {
        return "系统默认"
    }
    return state.familyName?.trim()?.takeIf { it.isNotBlank() }
        ?: state.displayName.trim().takeIf { it.isNotBlank() }
        ?: "系统默认"
}

@Composable
private fun CoverSlotFollowModeBanner(
    coverSlotLocked: Boolean,
    palette: AppManagementPalette,
    onToggleLocked: (Boolean) -> Unit
) {
    val bannerShape = RoundedCornerShape(palette.miuix.actionRadius ?: 8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(bannerShape)
            .background(Color(palette.settings.slot))
            .border(0.75.dp, palette.settings.divider, bannerShape)
            .clickable { onToggleLocked(!coverSlotLocked) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (coverSlotLocked) "独立固定书架封面包" else "跟随美术包推荐封面",
                color = palette.settings.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (coverSlotLocked) {
                    "已锁定独立封面包：切换日/夜美术包时绝不覆盖当前书架封面设置"
                } else {
                    "跟随模式：切换含旧版封面推荐的美术包时自动应用；手动挂载自定义封面包将转为独立固定"
                },
                color = palette.settings.secondaryText,
                fontSize = 10.sp
            )
        }
        LegadoMiuixSwitch(
            checked = coverSlotLocked,
            onCheckedChange = onToggleLocked,
            palette = palette.miuix
        )
    }
}

@Composable
private fun StudioDualSlotPackageSection(
    title: String,
    subtitle: String,
    isNightSlot: Boolean,
    dayMountedId: String,
    nightMountedId: BooleanStringAlias,
    entries: List<UnifiedPackageRepository.StoredEntry>,
    palette: AppManagementPalette,
    glassSignature: String?,
    headerExtra: (@Composable () -> Unit)? = null,
    canDeleteEntry: (UnifiedPackageRepository.StoredEntry) -> Boolean = { true },
    canReshuffleEntry: (UnifiedPackageRepository.StoredEntry) -> Boolean = { false },
    onSelectSlotNight: (Boolean) -> Unit,
    onSelectEntry: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onCreateNew: () -> Unit,
    onImport: () -> Unit,
    onCloneEntry: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onEditEntry: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onReshuffleEntry: ((UnifiedPackageRepository.StoredEntry) -> Unit)? = null,
    onExportEntry: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onUploadEntry: (UnifiedPackageRepository.StoredEntry) -> Unit,
    onDeleteEntry: (UnifiedPackageRepository.StoredEntry) -> Unit
) {
    val dayName = entries.firstOrNull { it.manifest.id == dayMountedId }?.manifest?.name ?: "默认"
    val nightName = entries.firstOrNull { it.manifest.id == nightMountedId }?.manifest?.name ?: "默认"
    val slotOptions = remember(dayName, nightName) {
        listOf(
            SegmentedOption(false, "☀ 日间预设 ($dayName)"),
            SegmentedOption(true, "🌙 夜间预设 ($nightName)")
        )
    }
    val currentMountedId = if (isNightSlot) nightMountedId else dayMountedId
    val panelShape = RoundedCornerShape(palette.miuix.panelRadius ?: 10.dp)
    val sectionTitleColor = if (AppConfig.isEInkMode) palette.settings.primaryText else palette.settings.accent

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        color = sectionTitleColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = palette.settings.titleFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "+ 新建",
                            color = sectionTitleColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onCreateNew)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "导入",
                            color = sectionTitleColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onImport)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    color = palette.settings.secondaryText,
                    fontSize = 11.sp
                )
            }
            headerExtra?.invoke()
            LegadoSegmentedControl(
                options = slotOptions,
                selectedValue = isNightSlot,
                palette = palette.miuix,
                onSelected = onSelectSlotNight,
                tone = SegmentedControlTone.AccentThumb,
                fontSize = 11.sp
            )
            entries.forEach { entry ->
                val isSelected = entry.manifest.id == currentMountedId
                val isAlsoOtherSlot = entry.manifest.id == (if (isNightSlot) dayMountedId else nightMountedId)
                val reshuffleAction = onReshuffleEntry
                val showReshuffle = reshuffleAction != null && canReshuffleEntry(entry)
                PackageEntryCard(
                    entry = entry,
                    isNightSlot = isNightSlot,
                    isSelected = isSelected,
                    isAlsoOtherSlot = isAlsoOtherSlot,
                    otherSlotLabel = if (isNightSlot) "日间也在用" else "夜间也在用",
                    canDelete = canDeleteEntry(entry),
                    palette = palette,
                    onSelect = { onSelectEntry(entry) },
                    onEdit = { onEditEntry(entry) },
                    onReshuffle = if (showReshuffle) { { reshuffleAction(entry) } } else null,
                    onClone = { onCloneEntry(entry) },
                    onExport = { onExportEntry(entry) },
                    onUpload = { onUploadEntry(entry) },
                    onDelete = { onDeleteEntry(entry) }
                )
            }
        }
    }

    if (glassSignature != null) {
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = palette.settings.panelRadiusPx,
            configSignature = glassSignature,
            modifier = Modifier
                .fillMaxWidth()
                .clip(panelShape),
            fallback = Modifier.appSettingPanelBackground(
                normalColor = palette.settings.row,
                borderColor = palette.settings.border,
                panelImage = null,
                radiusPx = palette.settings.panelRadiusPx
            ),
            requireWallpaperHost = true
        ) { body() }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .appSettingPanelBackground(
                    normalColor = palette.settings.row,
                    borderColor = palette.settings.border,
                    panelImage = null,
                    radiusPx = palette.settings.panelRadiusPx
                )
        ) { body() }
    }
}

private typealias BooleanStringAlias = String

@Composable
private fun PackageEntryCard(
    entry: UnifiedPackageRepository.StoredEntry,
    isNightSlot: Boolean,
    isSelected: Boolean,
    isAlsoOtherSlot: Boolean,
    otherSlotLabel: String,
    canDelete: Boolean = true,
    palette: AppManagementPalette,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onReshuffle: (() -> Unit)? = null,
    onClone: () -> Unit,
    onExport: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit
) {
    val cardShape = RoundedCornerShape(palette.miuix.actionRadius ?: 8.dp)
    val borderColor = if (isSelected) {
        palette.settings.accent
    } else {
        palette.settings.border?.let(::Color) ?: palette.settings.divider
    }
    val containerColor = if (isSelected) {
        palette.settings.accent.copy(alpha = 0.12f)
    } else {
        Color(palette.settings.slot)
    }

    Surface(
        shape = cardShape,
        color = containerColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 0.75.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(onClick = onSelect)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PackageSwatchDot(entry = entry, isNightSlot = isNightSlot, palette = palette)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = entry.manifest.name,
                            color = if (isSelected) palette.settings.accent else palette.settings.primaryText,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (entry.manifest.basePackageId != null) {
                            Text(
                                text = "CoW克隆",
                                color = palette.settings.secondaryText,
                                fontSize = 10.sp
                            )
                        }
                        if (isAlsoOtherSlot) {
                            Text(
                                text = otherSlotLabel,
                                color = palette.settings.secondaryText,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Text(
                        text = buildPackageMetaSummary(entry),
                        color = palette.settings.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isSelected) {
                    Text(
                        text = "当前使用",
                        color = palette.settings.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onReshuffle != null) {
                    SmallPackageActionText("重新洗牌", palette.settings.accent, onReshuffle)
                }
                SmallPackageActionText("编辑", palette.settings.accent, onEdit)
                SmallPackageActionText("复制", palette.settings.primaryText, onClone)
                SmallPackageActionText("上传", palette.settings.primaryText, onUpload)
                SmallPackageActionText("导出", palette.settings.primaryText, onExport)
                if (canDelete) {
                    SmallPackageActionText("删除", palette.settings.danger, onDelete)
                }
            }
        }
    }
}

@Composable
private fun SmallPackageActionText(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun PackageSwatchDot(
    entry: UnifiedPackageRepository.StoredEntry,
    isNightSlot: Boolean,
    palette: AppManagementPalette
) {
    if (entry.manifest.kind == PackageKind.COVER_SLOT) {
        val count = entry.manifest.coverSlot?.covers?.size ?: 0
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.settings.accent.copy(alpha = 0.16f))
                .border(1.dp, palette.settings.accent.copy(alpha = 0.45f), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 0) count.coerceAtMost(99).toString() else "书",
                color = palette.settings.accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }
    val art = entry.manifest.art
    val artPalette = if (isNightSlot) {
        art?.nightPalette ?: art?.dayPalette
    } else {
        art?.dayPalette ?: art?.nightPalette
    }
    val primary = parseSwatchColor(artPalette?.primaryColor, palette.settings.primaryText)
    val accent = parseSwatchColor(artPalette?.accentColor, palette.settings.accent)
    val bg = parseSwatchColor(artPalette?.backgroundColor, palette.settings.page)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, palette.settings.divider, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Row {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(primary)
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

private fun buildPackageMetaSummary(entry: UnifiedPackageRepository.StoredEntry): String {
    return when (entry.manifest.kind) {
        PackageKind.ART -> {
            val art = entry.manifest.art
            val w = art?.dayWallpapers ?: art?.nightWallpapers
            val hasBg = !w?.main.isNullOrBlank() || !w?.topBar.isNullOrBlank() || !w?.welcome.isNullOrBlank()
            val fontCount = listOfNotNull(
                (art?.dayTypography ?: art?.nightTypography)?.uiFontPath,
                (art?.dayTypography ?: art?.nightTypography)?.titleFontPath
            ).count { it.isNotBlank() }
            buildString {
                append("12色板")
                if (hasBg) append(" · 含壁纸/启动图")
                if (fontCount > 0) append(" · ${fontCount}项字体")
            }
        }
        PackageKind.COVER_SLOT -> {
            val slot = entry.manifest.coverSlot
            if (entry.manifest.id == UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID) {
                return "系统原生默认封面 · 无自定义图集"
            }
            val targetText = when (
                CoverSlotApplyTarget.normalize(slot?.applyTarget, defaultValue = CoverSlotApplyTarget.BOOKS)
            ) {
                CoverSlotApplyTarget.GROUPS -> "分组封面"
                CoverSlotApplyTarget.BOTH -> "分组+书籍"
                else -> "书籍封面"
            }
            val modeText = when (CoverSlotApplyMode.normalize(slot?.applyMode)) {
                CoverSlotApplyMode.SEQUENCE -> "顺序"
                CoverSlotApplyMode.MIXED -> "混合"
                CoverSlotApplyMode.FALLBACK_ONLY -> "仅兜底"
                else -> "随机"
            }
            val coverCount = slot?.covers?.size ?: 0
            val fallbacks = listOfNotNull(
                slot?.dayDefaultCoverPath?.takeIf { it.isNotBlank() }?.let { "日默认" },
                slot?.nightDefaultCoverPath?.takeIf { it.isNotBlank() }?.let { "夜默认" },
                slot?.groupCoverPath?.takeIf { it.isNotBlank() }?.let { "分组默认" }
            )
            buildString {
                append("目标: $targetText · 模式: $modeText · 图集 ${coverCount} 张")
                if (fallbacks.isNotEmpty()) {
                    append(" · ${fallbacks.joinToString("/")}")
                }
            }
        }
        else -> {
            val g = entry.manifest.param?.surfaceGlobal
            val modeLabel = when (g?.materialMode) {
                GlassMaterialPreset.SOLID -> "实底"
                GlassMaterialPreset.CLEAR -> "透明无玻璃"
                GlassMaterialPreset.FROSTED -> "磨砂玻璃"
                GlassMaterialPreset.GLASS -> "液态玻璃"
                else -> "默认材质"
            }
            val corner = String.format(Locale.US, "%.1fx", g?.cornerBaseScale ?: 1f)
            val alpha = "${g?.surfaceAlpha ?: 100}%"
            "$modeLabel · 圆角 $corner · 不透明度 $alpha"
        }
    }
}

private fun parseSwatchColor(raw: String?, fallback: Color): Color {
    if (raw.isNullOrBlank()) return fallback
    return runCatching { Color(raw.trim().toColorInt()) }.getOrElse { fallback }
}

@Composable
private fun StudioRollbackSwitchesSection(
    useLegacyTokenPipeline: Boolean,
    useLegacyPackageManager: Boolean,
    palette: AppManagementPalette,
    glassSignature: String?,
    onToggleLegacyTokenPipeline: (Boolean) -> Unit,
    onToggleLegacyPackageManager: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(useLegacyTokenPipeline || useLegacyPackageManager) }
    val panelShape = RoundedCornerShape(palette.miuix.panelRadius ?: 10.dp)
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (expanded) "收起兼容回滚开关 ▴" else "高级兼容回滚开关（保留 1 个版本）▾",
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = palette.settings.divider, thickness = 0.5.dp)
                    StudioSwitchRow(
                        title = "回退旧版 Token 管线（停用级联覆写）",
                        subtitle = "开启后忽略 4 区域级联覆写并沿用全局默认值",
                        checked = useLegacyTokenPipeline,
                        onCheckedChange = onToggleLegacyTokenPipeline,
                        palette = palette
                    )
                    HorizontalDivider(color = palette.settings.divider, thickness = 0.5.dp)
                    StudioSwitchRow(
                        title = "回退旧版分体包读写（Kill Switch）",
                        subtitle = "开启后暂停日夜双包重投影，保留旧版兼容行为",
                        checked = useLegacyPackageManager,
                        onCheckedChange = onToggleLegacyPackageManager,
                        palette = palette
                    )
                }
            }
        }
    }

    if (glassSignature != null) {
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = palette.settings.panelRadiusPx,
            configSignature = glassSignature,
            modifier = Modifier
                .fillMaxWidth()
                .clip(panelShape),
            fallback = Modifier.appSettingPanelBackground(
                normalColor = palette.settings.row,
                borderColor = palette.settings.border,
                panelImage = null,
                radiusPx = palette.settings.panelRadiusPx
            ),
            requireWallpaperHost = true
        ) { body() }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .appSettingPanelBackground(
                    normalColor = palette.settings.row,
                    borderColor = palette.settings.border,
                    panelImage = null,
                    radiusPx = palette.settings.panelRadiusPx
                )
        ) { body() }
    }
}

@Composable
private fun StudioSliderItem(
    title: String,
    valueText: String,
    value: Int,
    range: IntRange,
    palette: AppManagementPalette,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = if (enabled) palette.settings.primaryText else palette.settings.secondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = if (enabled) palette.settings.accent else palette.settings.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        AppThemedStepperSlider(
            value = value,
            range = range,
            onValueChange = onValueChange,
            palette = palette.miuix,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@Composable
private fun StudioSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: AppManagementPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.settings.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = palette.settings.secondaryText,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        LegadoMiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            palette = palette.miuix
        )
    }
}

@Composable
private fun ParamImportLayerDialog(
    onDismiss: () -> Unit,
    onConfirm: (applySurface: Boolean, applyLayout: Boolean) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var applySurface by remember { mutableStateOf(true) }
    var applyLayout by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        AppDialogFrame(
            title = stringResource(R.string.appearance_kit_import),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applySurface = !applySurface }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = applySurface, onCheckedChange = { applySurface = it })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "应用材质与几何参数 (@atmosphere + @geometry)",
                            color = style.primaryText,
                            fontSize = 14.sp
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applyLayout = !applyLayout }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = applyLayout, onCheckedChange = { applyLayout = it })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "应用界面布局骨架 (@layout · 谨慎覆盖)",
                            color = style.primaryText,
                            fontSize = 14.sp
                        )
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    primary = true,
                    enabled = applySurface || applyLayout,
                    onClick = { onConfirm(applySurface, applyLayout) }
                )
            }
        )
    }
}
