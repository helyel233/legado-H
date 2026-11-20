package io.legado.app.ui.config

import android.os.Bundle
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKit
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppearanceKitType
import io.legado.app.help.config.AppearanceSlotSummaries
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.appearance.AppearanceSlot
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LocalLegadoTokens
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val MENU_CREATE = 1
private const val MENU_IMPORT = 2
private const val MENU_EXPORT = 3
private const val MENU_SYNC_TASKS = 4

class AppearanceKitActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private var kitsState by mutableStateOf<List<AppearanceKit>>(emptyList())
    private var kitPreviewsState by mutableStateOf<Map<String, KitPreviewData>>(emptyMap())
    private var currentKitIdState by mutableStateOf("")
    private var dirtySlotsState by mutableStateOf<Set<AppearanceSlot>>(emptySet())
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
        binding.titleBar.title = getString(R.string.appearance_studio_title)
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        installComposeContent()
        refreshKits()
    }

    override fun onResume() {
        super.onResume()
        refreshKits()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CREATE, 0, R.string.appearance_kit_create)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_IMPORT, 1, R.string.appearance_kit_import)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_EXPORT, 2, R.string.appearance_kit_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SYNC_TASKS, 3, R.string.package_sync_task_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CREATE -> { showCreateKitDialog(); true }
            MENU_IMPORT -> { selectImportPackage(); true }
            MENU_EXPORT -> { exportCurrentKit(); true }
            MENU_SYNC_TASKS -> { showSyncTasks(); true }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun installComposeContent() {
        val composeView = ComposeView(this).apply {
            layoutParams = binding.recyclerView.layoutParams
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    AppearanceKitScreen(
                        kits = kitsState,
                        previews = kitPreviewsState,
                        currentKitId = currentKitIdState,
                        dirtySlots = dirtySlotsState,
                        slotSummaries = slotSummariesState,
                        onApply = ::applyKit,
                        onEdit = ::editKit,
                        onDelete = ::confirmDeleteKit,
                        onOverwriteDirty = ::overwriteActiveKit,
                        onSaveAsNew = ::showCreateKitDialog,
                        onRestoreActive = ::restoreActiveKit,
                        onOpenSlot = ::openSlotTarget
                    )
                }
            }
        }
        binding.root.addView(composeView, binding.root.indexOfChild(binding.recyclerView))
    }

    private fun refreshKits() {
        lifecycleScope.launch {
            val kits = AppearanceKitManager.builtinKits() + AppearanceKitManager.importedThemeKits()
            val currentId = AppearanceKitManager.currentKitId()
            val activeKit = kits.firstOrNull { it.id == currentId }
            kitsState = kits
            currentKitIdState = currentId
            dirtySlotsState = AppearanceKitManager.computeDirtySlots(this@AppearanceKitActivity, activeKit)
            slotSummariesState = AppearanceKitManager.currentSlotSummaries(this@AppearanceKitActivity)
            kitPreviewsState = withContext(Dispatchers.IO) {
                kits.associate { it.id to buildKitPreview(it) }
            }
        }
    }

    private suspend fun buildKitPreview(kit: AppearanceKit): KitPreviewData {
        val binding = kit.binding
        val themeRef = binding?.dayTheme ?: binding?.nightTheme
        val isNight = binding?.dayTheme == null && binding?.nightTheme != null
        val entry = themeRef?.let { ref ->
            ThemePackageManager.loadLocalOnly(isNight).firstOrNull {
                it.dirName == ref.dirName || it.packageInfo.name == ref.name
            }
        }
        val config = entry?.let {
            runCatching { ThemePackageManager.getConfig(it) }.getOrNull()
        }
        val darkFallback = isNight
        val backgroundColor = config?.backgroundColor.toPreviewColor(darkFallback)
        val primaryColor = config?.primaryColor.toPreviewColor(darkFallback)
        val accentColor = config?.accentColor.toPreviewColor(darkFallback, defaultAccent = true)
        val bottomColor = config?.bottomBackground.toPreviewColor(darkFallback)
        val cardColor = config?.cardColor.toOptionalPreviewColor() ?: bottomColor
        val backgroundPath = config?.backgroundImgPath?.takeIf { it.isNotBlank() } ?: kit.previewPath
        val signature = backgroundPath
            ?.takeIf { !it.startsWith("http", ignoreCase = true) }
            ?.let { path ->
                val file = File(path)
                if (file.exists()) ObjectKey("${file.absolutePath}:${file.length()}:${file.lastModified()}") else null
            }
        return KitPreviewData(
            backgroundColor = backgroundColor,
            primaryColor = primaryColor,
            accentColor = accentColor,
            cardColor = cardColor,
            bottomColor = bottomColor,
            backgroundPath = backgroundPath,
            signature = signature
        )
    }

    private fun String?.toPreviewColor(isNight: Boolean, defaultAccent: Boolean = false): Int {
        return runCatching {
            val value = this?.trim().orEmpty()
            val normalized = if (value.startsWith("#")) value else "#$value"
            normalized.toColorInt()
        }.getOrElse {
            when {
                defaultAccent -> getColor(R.color.accent)
                isNight -> android.graphics.Color.rgb(26, 28, 32)
                else -> android.graphics.Color.rgb(246, 247, 249)
            }
        }
    }

    private fun String?.toOptionalPreviewColor(): Int? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val normalized = if (value.startsWith("#")) value else "#$value"
            normalized.toColorInt()
        }.getOrNull()
    }

    private fun applyKit(kit: AppearanceKit) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.apply(this@AppearanceKitActivity, kit)
            }.onSuccess {
                currentKitIdState = kit.id
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun confirmDeleteKit(kit: AppearanceKit) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.appearance_kit_delete_confirm, kit.name),
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { deleteKit(kit) }
        )
    }

    private fun editKit(kit: AppearanceKit) {
        if (kit.type != AppearanceKitType.IMPORTED_THEME) return
        showComposeChoiceListDialog(
            title = kit.name,
            labels = listOf(
                getString(R.string.rename),
                getString(R.string.appearance_studio_overwrite_save),
                getString(R.string.delete)
            )
        ) { which ->
            when (which) {
                0 -> showRenameKitDialog(kit)
                1 -> overwriteKit(kit.id)
                2 -> confirmDeleteKit(kit)
            }
        }
    }

    private fun showRenameKitDialog(kit: AppearanceKit) {
        showComposeTextInputDialog(
            title = getString(R.string.rename),
            hint = getString(R.string.appearance_kit_name),
            initialValue = kit.name,
            validateInput = { it.trim().isNotBlank() },
            onPositive = { nextName ->
                lifecycleScope.launch {
                    runCatching {
                        AppearanceKitManager.renameKit(kit, nextName)
                    }.onSuccess { ok ->
                        toastOnUi(if (ok) R.string.success else R.string.error)
                        refreshKits()
                    }.onFailure {
                        toastOnUi(it.localizedMessage ?: getString(R.string.error))
                    }
                }
            }
        )
    }

    private fun overwriteActiveKit() {
        val current = kitsState.firstOrNull { it.id == currentKitIdState } ?: return
        if (current.type != AppearanceKitType.IMPORTED_THEME) {
            showCreateKitDialog()
            return
        }
        overwriteKit(current.id)
    }

    private fun overwriteKit(kitId: String) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.overwriteKitFromCurrent(this@AppearanceKitActivity, kitId)
            }.onSuccess { ok ->
                toastOnUi(if (ok) R.string.success else R.string.error)
                refreshKits()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun restoreActiveKit() {
        val current = kitsState.firstOrNull { it.id == currentKitIdState } ?: return
        applyKit(current)
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

    private fun deleteKit(kit: AppearanceKit) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.deleteImportedTheme(this@AppearanceKitActivity, kit)
            }.onSuccess { deleted ->
                toastOnUi(if (deleted) R.string.delete_success else R.string.error)
                refreshKits()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun showCreateKitDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.appearance_kit_create),
            hint = getString(R.string.appearance_kit_name),
            initialValue = getString(R.string.appearance_kit_manage),
            validateInput = { it.trim().isNotBlank() },
            onPositive = ::createKit
        )
    }

    private fun createKit(name: String) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.createFromCurrent(this@AppearanceKitActivity, name)
            }.onSuccess {
                toastOnUi(R.string.success)
                refreshKits()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
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
                refreshKits()
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
private fun AppearanceKitScreen(
    kits: List<AppearanceKit>,
    previews: Map<String, KitPreviewData>,
    currentKitId: String,
    dirtySlots: Set<AppearanceSlot>,
    slotSummaries: AppearanceSlotSummaries,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
    onDelete: (AppearanceKit) -> Unit,
    onOverwriteDirty: () -> Unit,
    onSaveAsNew: () -> Unit,
    onRestoreActive: () -> Unit,
    onOpenSlot: (AppearanceSlot) -> Unit
) {
    val palette = rememberAppManagementPalette()
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()
    val effectiveGlassSig = glassSignature.takeIf { wallpaperVisible }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("live_preview") {
            StudioLivePreviewCard(
                palette = palette,
                glassSignature = effectiveGlassSig
            )
        }
        if (dirtySlots.isNotEmpty()) {
            item("dirty_banner") {
                DirtySlotsBanner(
                    dirtySlots = dirtySlots,
                    palette = palette,
                    onOverwrite = onOverwriteDirty,
                    onSaveAsNew = onSaveAsNew,
                    onRestore = onRestoreActive
                )
            }
        }
        item("kits") {
            KitSection(
                title = stringResourceCompat(R.string.appearance_kit_manage),
                palette = palette,
                rows = kits,
                previews = previews,
                currentKitId = currentKitId,
                glassSignature = effectiveGlassSig,
                onApply = onApply,
                onEdit = onEdit,
                onDelete = onDelete
            )
        }
        item("slots") {
            StudioSlotsSection(
                summaries = slotSummaries,
                dirtySlots = dirtySlots,
                palette = palette,
                glassSignature = effectiveGlassSig,
                onOpenSlot = onOpenSlot
            )
        }
    }
}

@Composable
private fun StudioLivePreviewCard(
    palette: AppManagementPalette,
    glassSignature: String?
) {
    val tokens = LocalLegadoTokens.current
    val panelShape = RoundedCornerShape(palette.miuix.panelRadius ?: 14.dp)
    val bgColor = Color(tokens?.colors?.backgroundArgb ?: palette.settings.page.value.toInt())
    val primaryColor = Color(tokens?.colors?.primaryArgb ?: palette.settings.accent.value.toInt())
    val cardColor = Color(tokens?.colors?.cardArgb ?: palette.settings.row)
    val bottomColor = Color(tokens?.colors?.bottomBarArgb ?: palette.settings.bottomBar)
    val accentColor = palette.settings.accent

    Column(modifier = Modifier.fillMaxWidth()) {
        AppSettingSectionTitle(
            title = stringResourceCompat(R.string.appearance_studio_preview_title),
            palette = palette.settings
        )
        val body: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor.copy(alpha = if (glassSignature != null) 0.35f else 1f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(primaryColor.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.85f))
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.28f))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cardColor.copy(alpha = 0.88f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.settings.primaryText.copy(alpha = 0.72f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(palette.settings.secondaryText.copy(alpha = 0.45f))
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(bottomColor.copy(alpha = 0.90f))
                )
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
            ) { body() }
        } else {
            Box(
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
}

@Composable
private fun DirtySlotsBanner(
    dirtySlots: Set<AppearanceSlot>,
    palette: AppManagementPalette,
    onOverwrite: () -> Unit,
    onSaveAsNew: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val slotNames = dirtySlots.joinToString(" / ") { slot ->
        when (slot) {
            AppearanceSlot.THEME -> context.getString(R.string.appearance_slot_theme)
            AppearanceSlot.TOP_BAR -> context.getString(R.string.appearance_slot_top_bar)
            AppearanceSlot.NAVIGATION_BAR -> context.getString(R.string.appearance_slot_nav_bar)
            AppearanceSlot.GLASS -> context.getString(R.string.appearance_slot_glass)
            AppearanceSlot.COVER -> context.getString(R.string.appearance_slot_cover)
            AppearanceSlot.WELCOME -> context.getString(R.string.appearance_slot_welcome)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appSettingPanelBackground(
                normalColor = palette.settings.row,
                borderColor = palette.settings.border,
                panelImage = null,
                radiusPx = palette.settings.panelRadiusPx
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.appearance_studio_dirty_banner, slotNames),
            color = palette.settings.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DirtyBannerChip(
                text = stringResourceCompat(R.string.appearance_studio_overwrite_save),
                accent = true,
                palette = palette,
                onClick = onOverwrite
            )
            DirtyBannerChip(
                text = stringResourceCompat(R.string.appearance_studio_save_as),
                accent = false,
                palette = palette,
                onClick = onSaveAsNew
            )
            DirtyBannerChip(
                text = stringResourceCompat(R.string.appearance_studio_restore),
                accent = false,
                palette = palette,
                onClick = onRestore
            )
        }
    }
}

@Composable
private fun DirtyBannerChip(
    text: String,
    accent: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (accent) palette.settings.accent else Color(palette.settings.rowPressed),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            color = if (accent) palette.settings.onAccent else palette.settings.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun StudioSlotsSection(
    summaries: AppearanceSlotSummaries,
    dirtySlots: Set<AppearanceSlot>,
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
            title = stringResourceCompat(R.string.appearance_studio_slots_title),
            palette = palette.settings
        )
        Text(
            text = stringResourceCompat(R.string.appearance_studio_slots_hint),
            color = palette.settings.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        val panelShape = RoundedCornerShape(palette.miuix.panelRadius ?: 14.dp)
        val content: @Composable () -> Unit = {
            Column(modifier = Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, (slot, titleRes, summary) ->
                    val isLast = index == rows.lastIndex
                    val displaySummary = if (slot in dirtySlots) "● $summary" else summary
                    ActionRow(
                        title = stringResourceCompat(titleRes),
                        summary = displaySummary,
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
private fun KitSection(
    title: String,
    palette: AppManagementPalette,
    rows: List<AppearanceKit>,
    previews: Map<String, KitPreviewData>,
    currentKitId: String,
    glassSignature: String?,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
    onDelete: (AppearanceKit) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        AppSettingSectionTitle(title = title, palette = palette.settings)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height((((rows.size + 1) / 2) * 214).dp),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            items(rows, key = { it.id }) { kit ->
                AppearanceKitCard(
                    kit = kit,
                    preview = previews[kit.id] ?: KitPreviewData.default(),
                    active = kit.id == currentKitId,
                    palette = palette,
                    glassSignature = glassSignature,
                    onClick = { if (kit.id != currentKitId) onApply(kit) },
                    onLongClick = if (kit.type == AppearanceKitType.IMPORTED_THEME) {
                        { onEdit(kit) }
                    } else {
                        null
                    },
                    onDelete = if (kit.type == AppearanceKitType.IMPORTED_THEME) {
                        { onDelete(kit) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

@Composable
private fun ActionRow(
    title: String,
    summary: String,
    trailing: String,
    secondaryTrailing: String = "",
    tertiaryTrailing: String = "",
    previewSeed: String? = null,
    palette: AppSettingPalette,
    isLast: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)? = null,
    onTertiaryClick: (() -> Unit)? = null
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
        previewSeed?.let {
            KitPreview(seed = it, palette = palette)
            Spacer(modifier = Modifier.width(12.dp))
        }
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
        if (secondaryTrailing.isNotBlank() && onSecondaryClick != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = secondaryTrailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSecondaryClick
                )
            )
        }
        if (tertiaryTrailing.isNotBlank() && onTertiaryClick != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = tertiaryTrailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTertiaryClick
                )
            )
        }
    }
}

@Composable
private fun KitPreview(seed: String, palette: AppSettingPalette) {
    val colors = remember(seed) { legacyPreviewColors(seed) }
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(colors))
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.66f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(palette.row).copy(alpha = 0.72f))
        )
    }
}

private fun legacyPreviewColors(seed: String): List<Color> {
    val hash = seed.hashCode()
    val first = Color(0xFF000000 or (hash and 0x00FFFFFF).toLong())
    val second = Color(0xFF000000 or ((hash * 31) and 0x00FFFFFF).toLong())
    return listOf(first, second)
}

private data class KitPreviewData(
    val backgroundColor: Int,
    val primaryColor: Int,
    val accentColor: Int,
    val cardColor: Int,
    val bottomColor: Int,
    val backgroundPath: String?,
    val signature: ObjectKey?
) {
    companion object {
        fun default(): KitPreviewData {
            return KitPreviewData(
                backgroundColor = android.graphics.Color.rgb(246, 247, 249),
                primaryColor = android.graphics.Color.rgb(42, 46, 52),
                accentColor = android.graphics.Color.rgb(80, 120, 220),
                cardColor = android.graphics.Color.WHITE,
                bottomColor = android.graphics.Color.rgb(236, 238, 242),
                backgroundPath = null,
                signature = null
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppearanceKitCard(
    kit: AppearanceKit,
    preview: KitPreviewData,
    active: Boolean,
    palette: AppManagementPalette,
    /**
     * 本页玻璃签名；null 表示壁纸此刻不可见（无背景图 / 墨水屏 / solid 档），
     * 卡片保持接入玻璃之前的主题实底，观感逐值不变。
     */
    glassSignature: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onDelete: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val radius = palette.miuix.panelRadius ?: 14.dp
    val shape = RoundedCornerShape(radius)
    // 原来的实底（含按下态）逐值保留下来，作为玻璃不可用时的 fallback
    val solidColor = if (pressed) Color(palette.settings.rowPressed) else Color(palette.settings.row)
    val clickableModifier = Modifier.combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick
    )
    val outerModifier = Modifier
        .fillMaxWidth()
        .height(206.dp)
    if (glassSignature != null) {
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = with(LocalDensity.current) { radius.toPx() },
            configSignature = glassSignature,
            // 卡片是页面内表面：先问一句「背后真的有壁纸吗」，自铺不透明底的页面不该建采样宿主
            requireWallpaperHost = true,
            modifier = outerModifier.clip(shape),
            fallback = Modifier.background(solidColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 玻璃 drawable 没有按下态，用主题行按下色叠在玻璃之上保留触摸反馈
                    // （与 AppManagementCard 同一口径；界面不透明度 < 1 时按下态会略深一点，
                    // 只在手指按住期间可见）
                    .background(
                        if (pressed) Color(palette.settings.rowPressed) else Color.Transparent
                    )
                    .then(clickableModifier)
                    .padding(9.dp)
            ) {
                AppearanceKitCardBody(
                    kit = kit,
                    preview = preview,
                    active = active,
                    palette = palette,
                    onDelete = onDelete
                )
            }
        }
    } else {
        Column(
            modifier = outerModifier
                .clip(shape)
                .background(solidColor)
                .then(clickableModifier)
                .padding(9.dp)
        ) {
            AppearanceKitCardBody(
                kit = kit,
                preview = preview,
                active = active,
                palette = palette,
                onDelete = onDelete
            )
        }
    }
}

/**
 * 外观套卡的内容体：玻璃分支与实底分支共用，避免两份布局各自演化。
 * 抽成 [ColumnScope] 扩展是因为中间有 `Spacer(Modifier.weight(1f))` 把色卡行压到底部。
 */
@Composable
private fun ColumnScope.AppearanceKitCardBody(
    kit: AppearanceKit,
    preview: KitPreviewData,
    active: Boolean,
    palette: AppManagementPalette,
    onDelete: (() -> Unit)?
) {
    AppearanceKitPreview(
        preview = preview,
        active = active,
        palette = palette,
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = kit.name,
        color = palette.settings.primaryText,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = kit.summary,
        color = palette.settings.secondaryText,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.weight(1f))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(preview.primaryColor, preview.accentColor, preview.cardColor, preview.bottomColor).forEach {
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(it))
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_outline_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = palette.settings.secondaryText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun AppearanceKitPreview(
    preview: KitPreviewData,
    active: Boolean,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(palette.miuix.panelRadius ?: 12.dp))
            .background(Color(preview.backgroundColor))
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.background_image)
                }
            },
            update = { imageView ->
                imageView.setBackgroundColor(preview.backgroundColor)
                val path = preview.backgroundPath?.takeIf { it.isNotBlank() }
                val loadTag = "${path.orEmpty()}|${preview.backgroundColor}|${preview.signature}"
                if (imageView.tag != loadTag) {
                    imageView.tag = loadTag
                    imageView.releaseComposeImage()
                    if (path != null) {
                        val appContext = imageView.context.applicationContext ?: imageView.context
                        val request = ImageLoader.load(appContext, path)
                            .centerCrop()
                            .error(ColorDrawable(preview.backgroundColor))
                        preview.signature?.let { request.signature(it) }
                        request.into(imageView)
                    }
                }
            },
            onRelease = { it.releaseComposeImage() },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(9.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (active) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.settings.accent.copy(alpha = 0.92f),
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(preview.cardColor).copy(alpha = 0.86f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(preview.bottomColor).copy(alpha = 0.90f))
                )
            }
        }
    }
}
