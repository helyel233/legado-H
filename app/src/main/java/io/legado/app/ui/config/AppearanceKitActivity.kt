package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppearanceSlotSummaries
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.appearance.AppearanceSlot
import io.legado.app.lib.theme.PrefBatchTransaction
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.EXPLORE_GLASS_MODE_SOLID
import io.legado.app.ui.main.explore.resolveExploreGlassMode
import io.legado.app.ui.widget.compose.AppComposeTopBar
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoSegmentedControl
import io.legado.app.ui.widget.compose.LocalLegadoTokens
import io.legado.app.ui.widget.compose.SegmentedControlTone
import io.legado.app.ui.widget.compose.SegmentedOption
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.util.Locale

class AppearanceKitActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private var themeModeState by mutableStateOf("0")
    private var isNightState by mutableStateOf(AppConfig.isNightTheme)
    private var isEInkState by mutableStateOf(AppConfig.isEInkMode)
    private var themesState by mutableStateOf<List<ThemePackageManager.Entry>>(emptyList())
    private var activeThemeDirState by mutableStateOf("")
    private var cornerScaleState by mutableFloatStateOf(AppConfig.uiCornerScale)
    private var layoutAlphaState by mutableIntStateOf(AppConfig.uiLayoutAlpha)
    private var glassModeState by mutableStateOf(EXPLORE_GLASS_MODE_SOLID)
    private var glassLevelState by mutableIntStateOf(GlassMaterialPreset.DEFAULT_LIQUID_LEVEL)
    private var slotSummariesState by mutableStateOf(
        AppearanceSlotSummaries("", "", "", "", "", "")
    )

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importAppearanceKit)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        if (it.uri != null) {
            toastOnUi(R.string.export_success)
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
        refreshStudioState()
    }

    override fun onResume() {
        super.onResume()
        refreshStudioState()
    }

    private fun topBarMenuActions(): List<AppManagementMenuAction> = listOf(
        AppManagementMenuAction(
            text = getString(R.string.appearance_kit_import),
            onClick = ::selectImportPackage
        ),
        AppManagementMenuAction(
            text = getString(R.string.appearance_kit_export),
            onClick = ::exportCurrentKit
        ),
        AppManagementMenuAction(
            text = getString(R.string.package_sync_task_title),
            onClick = ::showSyncTasks
        )
    )

    private fun installComposeContent() {
        val composeView = ComposeView(this).apply {
            layoutParams = binding.recyclerView.layoutParams
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val themeModeLabels = resources.getStringArray(R.array.theme_mode)
                val themeModeValues = resources.getStringArray(R.array.theme_mode_v)
                val modeOptions = remember(themeModeLabels, themeModeValues) {
                    themeModeValues.mapIndexed { index, value ->
                        SegmentedOption(
                            value = value,
                            label = themeModeLabels.getOrNull(index) ?: value
                        )
                    }
                }
                val glassLabels = resources.getStringArray(R.array.explore_glass_mode_entries)
                val glassValues = resources.getStringArray(R.array.explore_glass_mode_values)
                val glassOptions = remember(glassLabels, glassValues) {
                    glassValues.mapIndexed { index, value ->
                        SegmentedOption(
                            value = value,
                            label = glassLabels.getOrNull(index) ?: value
                        )
                    }
                }
                LegadoComposeTheme {
                    AppearanceStudioScreen(
                        themeMode = themeModeState,
                        modeOptions = modeOptions,
                        isNightTheme = isNightState,
                        isEInkMode = isEInkState,
                        themes = themesState,
                        activeThemeDir = activeThemeDirState,
                        cornerScale = cornerScaleState,
                        layoutAlpha = layoutAlphaState,
                        glassMode = glassModeState,
                        glassOptions = glassOptions,
                        glassLevel = glassLevelState,
                        slotSummaries = slotSummariesState,
                        topMenuActions = ::topBarMenuActions,
                        onBack = ::supportFinishAfterTransition,
                        onSwitchThemeMode = ::switchThemeMode,
                        onApplyTheme = ::applyQuickTheme,
                        onManageAllThemes = { openSlotTarget(AppearanceSlot.THEME) },
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
                        onOpenSlot = ::openSlotTarget
                    )
                }
            }
        }
        binding.root.addView(composeView, binding.root.indexOfChild(binding.recyclerView))
    }

    private fun refreshStudioState() {
        val night = AppConfig.isNightTheme
        val eInk = AppConfig.isEInkMode
        themeModeState = getPrefString(PreferKey.themeMode, "0") ?: "0"
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

        lifecycleScope.launch {
            val loadedThemes = ThemePackageManager.loadLocalOnly(night)
            val activeDir = ThemePackageManager.activeEntryDirName(this@AppearanceKitActivity, night)
            val summaries = AppearanceKitManager.currentSlotSummaries(this@AppearanceKitActivity)
            if (isNightState == night) {
                themesState = loadedThemes
                activeThemeDirState = activeDir
            }
            slotSummariesState = summaries
        }
    }

    private fun switchThemeMode(nextMode: String) {
        if (nextMode == themeModeState) return
        themeModeState = nextMode
        putPrefString(PreferKey.themeMode, nextMode)
        window.decorView.post {
            ThemeConfig.applyDayNight(this@AppearanceKitActivity)
        }
    }

    private fun applyQuickTheme(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                ThemePackageManager.apply(
                    context = this@AppearanceKitActivity,
                    entry = entry,
                    switchNightMode = false,
                    notify = true
                )
                AppearanceKitManager.syncCurrentThemeRef(entry.packageInfo.isNightTheme, entry)
            }.onSuccess {
                refreshStudioState()
                toastOnUi(R.string.success)
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
    }

    private fun commitGlassLevel(level: Int) {
        val clamped = level.coerceIn(0, 100)
        PrefBatchTransaction.run(this) {
            putPrefInt(PreferKey.frostedGlassLevel, clamped)
            putPrefInt(PreferKey.liquidGlassLevel, clamped)
            putPrefBoolean(PreferKey.glassPresetMatched, false)
        }
    }

    private fun openSlotTarget(slot: AppearanceSlot) {
        when (slot) {
            AppearanceSlot.THEME -> startActivity<ThemeManageActivity>()
            AppearanceSlot.TOP_BAR -> startActivity<TopBarManageActivity>()
            AppearanceSlot.NAVIGATION_BAR -> startActivity<NavigationBarManageActivity>()
            AppearanceSlot.GLASS -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.EXPLORE_GLASS_CONFIG)
            }
            AppearanceSlot.COVER -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_CONFIG)
            }
            AppearanceSlot.WELCOME -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.WELCOME_CONFIG)
            }
        }
    }

    private fun selectImportPackage() {
        importPackage.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.appearance_kit_import)
            allowExtensions = arrayOf("red", "zip")
        }
    }

    private fun importAppearanceKit(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val file = externalFiles.getFile("appearanceKitImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                AppearanceKitManager.importPackage(file)
            }.onSuccess { result ->
                toastOnUi(getString(R.string.appearance_kit_imported, result.total))
                refreshStudioState()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun exportCurrentKit() {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.exportCurrent(this@AppearanceKitActivity)
            }.onSuccess { file ->
                exportPackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(
                        file.name.ifBlank { "appearance_kit.zip" },
                        file,
                        "application/zip"
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun showSyncTasks() {
        showPackageSyncTaskDialog(
            setOf(
                WebDavTaskType.THEME_PACKAGE_UPLOAD,
                WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD,
                WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD
            )
        )
    }
}

@Composable
private fun AppearanceStudioScreen(
    themeMode: String,
    modeOptions: List<SegmentedOption<String>>,
    isNightTheme: Boolean,
    isEInkMode: Boolean,
    themes: List<ThemePackageManager.Entry>,
    activeThemeDir: String,
    cornerScale: Float,
    layoutAlpha: Int,
    glassMode: String,
    glassOptions: List<SegmentedOption<String>>,
    glassLevel: Int,
    slotSummaries: AppearanceSlotSummaries,
    topMenuActions: () -> List<AppManagementMenuAction>,
    onBack: () -> Unit,
    onSwitchThemeMode: (String) -> Unit,
    onApplyTheme: (ThemePackageManager.Entry) -> Unit,
    onManageAllThemes: () -> Unit,
    onCornerScaleDrag: (Int) -> Unit,
    onCornerScaleCommit: () -> Unit,
    onLayoutAlphaDrag: (Int) -> Unit,
    onLayoutAlphaCommit: () -> Unit,
    onSelectGlassMode: (String) -> Unit,
    onGlassLevelDrag: (Int) -> Unit,
    onGlassLevelCommit: () -> Unit,
    onOpenSlot: (AppearanceSlot) -> Unit
) {
    val palette = rememberAppManagementPalette()
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()
    val effectiveGlassSig = glassSignature.takeIf { wallpaperVisible }

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
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("mode_switcher") {
                StudioModeSwitcherSection(
                    themeMode = themeMode,
                    options = modeOptions,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    onSelectMode = onSwitchThemeMode
                )
            }
            item("live_preview") {
                StudioLivePreviewCard(
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    previewCornerScale = cornerScale,
                    previewLayoutAlpha = layoutAlpha
                )
            }
            item("quick_themes") {
                StudioQuickThemeSection(
                    themes = themes,
                    activeThemeDir = activeThemeDir,
                    isNightTheme = isNightTheme,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    onApplyTheme = onApplyTheme,
                    onManageAll = onManageAllThemes
                )
            }
            item("tuning") {
                StudioTuningSection(
                    cornerScale = cornerScale,
                    layoutAlpha = layoutAlpha,
                    isEInkMode = isEInkMode,
                    glassMode = glassMode,
                    glassOptions = glassOptions,
                    glassLevel = glassLevel,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    onCornerScaleDrag = onCornerScaleDrag,
                    onCornerScaleCommit = onCornerScaleCommit,
                    onLayoutAlphaDrag = onLayoutAlphaDrag,
                    onLayoutAlphaCommit = onLayoutAlphaCommit,
                    onSelectGlassMode = onSelectGlassMode,
                    onGlassLevelDrag = onGlassLevelDrag,
                    onGlassLevelCommit = onGlassLevelCommit
                )
            }
            item("slots") {
                StudioSlotsSection(
                    summaries = slotSummaries,
                    palette = palette,
                    glassSignature = effectiveGlassSig,
                    onOpenSlot = onOpenSlot
                )
            }
        }
    }
}

@Composable
private fun StudioModeSwitcherSection(
    themeMode: String,
    options: List<SegmentedOption<String>>,
    palette: AppManagementPalette,
    glassSignature: String?,
    onSelectMode: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AppSettingSectionTitle(
            title = stringResource(R.string.theme_mode),
            palette = palette.settings
        )
        LegadoSegmentedControl(
            options = options,
            selectedValue = themeMode,
            palette = palette.miuix,
            onSelected = onSelectMode,
            tone = SegmentedControlTone.AccentThumb,
            fontSize = 12.sp,
            glassSignature = glassSignature
        )
    }
}

@Composable
private fun StudioLivePreviewCard(
    palette: AppManagementPalette,
    glassSignature: String?,
    previewCornerScale: Float,
    previewLayoutAlpha: Int
) {
    val tokens = LocalLegadoTokens.current
    val previewRadii = resolveStudioPreviewRadii(previewCornerScale)
    val panelRadius = previewRadii.panelRadiusDp.dp
    val actionRadius = previewRadii.actionRadiusDp.dp
    val innerRadius = previewRadii.innerRadiusDp.dp
    val panelRadiusPx = with(LocalDensity.current) { panelRadius.toPx() }
    val alphaFactor = resolveStudioPreviewAlphaFactor(previewLayoutAlpha)

    val panelShape = RoundedCornerShape(panelRadius)
    val slotShape = RoundedCornerShape(actionRadius)
    val innerShape = RoundedCornerShape(innerRadius)
    val pillShape = if (previewRadii.isSquare) RoundedCornerShape(0.dp) else CircleShape

    val bgColor = Color(tokens?.colors?.backgroundArgb ?: palette.settings.page.value.toInt())
    val primaryColor = Color(tokens?.colors?.primaryArgb ?: palette.settings.accent.value.toInt())
    val cardColor = Color(tokens?.colors?.cardArgb ?: palette.settings.row)
    val bottomColor = Color(tokens?.colors?.bottomBarArgb ?: palette.settings.bottomBar)
    val accentColor = palette.settings.accent

    Column(modifier = Modifier.fillMaxWidth()) {
        AppSettingSectionTitle(
            title = stringResource(R.string.appearance_studio_preview_title),
            palette = palette.settings
        )
        val body: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        bgColor.copy(
                            alpha = if (glassSignature != null) 0.35f * alphaFactor else alphaFactor
                        )
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(slotShape)
                        .background(primaryColor.copy(alpha = (0.85f * alphaFactor).coerceIn(0.12f, 1f)))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(8.dp)
                            .clip(pillShape)
                            .background(Color.White.copy(alpha = 0.85f))
                    )
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
                            .height(46.dp)
                            .clip(innerShape)
                            .background(accentColor.copy(alpha = (0.28f * alphaFactor).coerceIn(0.08f, 0.5f)))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(slotShape)
                            .background(cardColor.copy(alpha = (0.88f * alphaFactor).coerceIn(0.12f, 1f)))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(8.dp)
                                .clip(pillShape)
                                .background(palette.settings.primaryText.copy(alpha = 0.72f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(6.dp)
                                .clip(pillShape)
                                .background(palette.settings.secondaryText.copy(alpha = 0.45f))
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
            }
        }
        if (glassSignature != null) {
            GlassPanel(
                domain = GlassSamplingDomain.WALLPAPER,
                cornerRadiusPx = panelRadiusPx,
                configSignature = glassSignature,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panelShape)
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
}

@Composable
private fun StudioQuickThemeSection(
    themes: List<ThemePackageManager.Entry>,
    activeThemeDir: String,
    isNightTheme: Boolean,
    palette: AppManagementPalette,
    glassSignature: String?,
    onApplyTheme: (ThemePackageManager.Entry) -> Unit,
    onManageAll: () -> Unit
) {
    val chipShape = RoundedCornerShape(palette.miuix.actionRadius ?: 8.dp)
    val borderColor = palette.settings.border?.let(::Color) ?: palette.settings.divider
    val modeLabel = stringResource(if (isNightTheme) R.string.theme_night_short else R.string.theme_day_short)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.appearance_studio_quick_themes),
                    color = palette.settings.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = modeLabel,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            Text(
                text = "${stringResource(R.string.appearance_studio_manage_all)} ›",
                color = palette.settings.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onManageAll)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
        ) {
            items(themes, key = { it.dirName }) { entry ->
                val isSelected = entry.dirName == activeThemeDir
                val config = entry.packageInfo.config
                val primarySwatch = parseSwatchColor(config?.primaryColor, palette.settings.accent)
                val accentSwatch = parseSwatchColor(config?.accentColor, palette.settings.accent)
                val bgSwatch = parseSwatchColor(config?.backgroundColor, palette.settings.page)
                Surface(
                    shape = chipShape,
                    color = if (isSelected) {
                        palette.settings.accent.copy(alpha = 0.12f)
                    } else if (glassSignature != null) {
                        Color(palette.settings.slot).copy(alpha = 0.72f)
                    } else {
                        Color(palette.settings.row)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) palette.settings.accent else borderColor
                    ),
                    modifier = Modifier
                        .width(126.dp)
                        .clip(chipShape)
                        .clickable { onApplyTheme(entry) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(bgSwatch)
                                .border(1.dp, borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Row {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(primarySwatch)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(accentSwatch)
                                )
                            }
                        }
                        Text(
                            text = entry.packageInfo.name.ifBlank { entry.dirName },
                            color = if (isSelected) palette.settings.accent else palette.settings.primaryText,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private fun parseSwatchColor(raw: String?, fallback: Color): Color {
    if (raw.isNullOrBlank()) return fallback
    return runCatching { Color(raw.trim().toColorInt()) }.getOrElse { fallback }
}

@Composable
private fun StudioTuningSection(
    cornerScale: Float,
    layoutAlpha: Int,
    isEInkMode: Boolean,
    glassMode: String,
    glassOptions: List<SegmentedOption<String>>,
    glassLevel: Int,
    palette: AppManagementPalette,
    glassSignature: String?,
    onCornerScaleDrag: (Int) -> Unit,
    onCornerScaleCommit: () -> Unit,
    onLayoutAlphaDrag: (Int) -> Unit,
    onLayoutAlphaCommit: () -> Unit,
    onSelectGlassMode: (String) -> Unit,
    onGlassLevelDrag: (Int) -> Unit,
    onGlassLevelCommit: () -> Unit
) {
    val panelShape = RoundedCornerShape(palette.miuix.panelRadius ?: 10.dp)
    val cornerStep = cornerScaleToSliderStep(cornerScale)
    val glassSliderEnabled = isStudioGlassSliderEnabled(isEInkMode, glassMode)
    Column(modifier = Modifier.fillMaxWidth()) {
        AppSettingSectionTitle(
            title = stringResource(R.string.appearance_studio_tuning_title),
            palette = palette.settings
        )
        val content: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudioSliderItem(
                    title = stringResource(R.string.ui_corner_scale),
                    valueText = String.format(Locale.US, "%.1fx", cornerScale),
                    value = cornerStep,
                    range = 0..30,
                    palette = palette,
                    onValueChange = onCornerScaleDrag,
                    onValueChangeFinished = onCornerScaleCommit
                )
                StudioSliderItem(
                    title = stringResource(R.string.ui_layout_alpha),
                    valueText = stringResource(R.string.ui_layout_alpha_value, layoutAlpha),
                    value = layoutAlpha.coerceIn(0, 100),
                    range = 0..100,
                    palette = palette,
                    onValueChange = onLayoutAlphaDrag,
                    onValueChangeFinished = onLayoutAlphaCommit
                )
                HorizontalDivider(color = palette.settings.divider, thickness = 0.5.dp)
                if (isEInkMode) {
                    Text(
                        text = stringResource(R.string.appearance_studio_eink_glass_disabled),
                        color = palette.settings.secondaryText,
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        text = stringResource(R.string.explore_glass_effect_mode),
                        color = palette.settings.primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    LegadoSegmentedControl(
                        options = glassOptions,
                        selectedValue = glassMode,
                        palette = palette.miuix,
                        onSelected = onSelectGlassMode,
                        tone = SegmentedControlTone.SurfaceThumb,
                        fontSize = 11.sp,
                        glassSignature = null
                    )
                    StudioSliderItem(
                        title = stringResource(R.string.explore_glass_level),
                        valueText = "$glassLevel%",
                        value = glassLevel.coerceIn(0, 100),
                        range = 0..100,
                        enabled = glassSliderEnabled,
                        palette = palette,
                        onValueChange = onGlassLevelDrag,
                        onValueChangeFinished = onGlassLevelCommit
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
                    .clip(panelShape)
            ) { content() }
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
            ) { content() }
        }
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
private fun StudioSlotsSection(
    summaries: AppearanceSlotSummaries,
    palette: AppManagementPalette,
    glassSignature: String?,
    onOpenSlot: (AppearanceSlot) -> Unit
) {
    val rows = listOf(
        Triple(AppearanceSlot.THEME, R.string.appearance_slot_theme, summaries.theme),
        Triple(AppearanceSlot.TOP_BAR, R.string.appearance_slot_top_bar, summaries.topBar),
        Triple(AppearanceSlot.NAVIGATION_BAR, R.string.appearance_slot_nav_bar, summaries.navigationBar),
        Triple(AppearanceSlot.GLASS, R.string.appearance_slot_glass, summaries.glass),
        Triple(AppearanceSlot.COVER, R.string.appearance_slot_cover, summaries.cover),
        Triple(AppearanceSlot.WELCOME, R.string.appearance_slot_welcome, summaries.welcome)
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        AppSettingSectionTitle(
            title = stringResource(R.string.appearance_studio_slots_title),
            palette = palette.settings
        )
        Text(
            text = stringResource(R.string.appearance_studio_slots_hint),
            color = palette.settings.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        val panelShape = RoundedCornerShape(palette.miuix.panelRadius ?: 10.dp)
        val content: @Composable () -> Unit = {
            Column(modifier = Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, (slot, titleRes, summary) ->
                    val isLast = index == rows.lastIndex
                    ActionRow(
                        title = stringResource(titleRes),
                        summary = summary,
                        trailing = "",
                        palette = palette.settings,
                        isLast = isLast,
                        onClick = { onOpenSlot(slot) }
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
                    .clip(panelShape)
            ) { content() }
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
            ) { content() }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    summary: String,
    trailing: String,
    palette: AppSettingPalette,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 62.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = palette.rowPressed,
                dividerColor = palette.divider,
                showDivider = !isLast,
                radiusPx = palette.panelRadiusPx,
                isFirst = false,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                color = palette.secondaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
    }
}
