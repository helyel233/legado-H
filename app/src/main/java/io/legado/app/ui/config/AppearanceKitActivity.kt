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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
                        currentKitId = currentKitIdState,
                        dirtySlots = dirtySlotsState,
                        slotSummaries = slotSummariesState,
                        onApply = ::applyKit,
                        onEdit = ::editKit,
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
        }
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
    currentKitId: String,
    dirtySlots: Set<AppearanceSlot>,
    slotSummaries: AppearanceSlotSummaries,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
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
        item("snapshots") {
            SnapshotCapsuleStrip(
                kits = kits,
                currentKitId = currentKitId,
                hasDirty = dirtySlots.isNotEmpty(),
                palette = palette,
                onApply = onApply,
                onEdit = onEdit,
                onSaveAsNew = onSaveAsNew,
                onRestoreActive = onRestoreActive
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
    val panelRadius = palette.miuix.panelRadius ?: 10.dp
    val actionRadius = palette.miuix.actionRadius ?: 7.dp
    val panelShape = RoundedCornerShape(panelRadius)
    val slotShape = RoundedCornerShape(actionRadius)
    val innerRadius = (actionRadius - 2.dp).coerceAtLeast(if (actionRadius > 0.dp) 3.dp else 0.dp)
    val innerShape = RoundedCornerShape(innerRadius)
    val pillShape = if (panelRadius > 0.dp) CircleShape else RoundedCornerShape(0.dp)
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
                        .clip(slotShape)
                        .background(primaryColor.copy(alpha = 0.85f))
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
                            .background(accentColor.copy(alpha = 0.28f))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(slotShape)
                            .background(cardColor.copy(alpha = 0.88f))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnapshotCapsuleStrip(
    kits: List<AppearanceKit>,
    currentKitId: String,
    hasDirty: Boolean,
    palette: AppManagementPalette,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
    onSaveAsNew: () -> Unit,
    onRestoreActive: () -> Unit
) {
    val capsuleShape = if ((palette.miuix.panelRadius ?: 10.dp) > 0.dp) {
        CircleShape
    } else {
        RoundedCornerShape(0.dp)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val activeEditableKit = kits.firstOrNull {
            it.id == currentKitId && it.type == AppearanceKitType.IMPORTED_THEME
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResourceCompat(R.string.appearance_snapshot_title),
                color = palette.settings.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.settings.titleFontFamily
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeEditableKit != null) {
                    Text(
                        text = stringResourceCompat(R.string.edit),
                        color = palette.settings.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(capsuleShape)
                            .clickable { onEdit(activeEditableKit) }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                if (hasDirty) {
                    Text(
                        text = stringResourceCompat(R.string.appearance_snapshot_restore),
                        color = palette.settings.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(capsuleShape)
                            .clickable(onClick = onRestoreActive)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(kits, key = { it.id }) { kit ->
                val active = kit.id == currentKitId
                val bgColor = if (active) palette.settings.accent else Color(palette.settings.row)
                val textColor = if (active) palette.settings.onAccent else palette.settings.primaryText
                Surface(
                    shape = capsuleShape,
                    color = bgColor,
                    modifier = Modifier
                        .clip(capsuleShape)
                        .combinedClickable(
                            onClick = { if (!active) onApply(kit) },
                            onLongClick = if (kit.type == AppearanceKitType.IMPORTED_THEME) {
                                { onEdit(kit) }
                            } else {
                                null
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = kit.name,
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (active && hasDirty) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(palette.settings.onAccent)
                            )
                        }
                    }
                }
            }
            item("save_snapshot") {
                Surface(
                    shape = capsuleShape,
                    color = Color(palette.settings.slot),
                    modifier = Modifier
                        .clip(capsuleShape)
                        .clickable(onClick = onSaveAsNew)
                ) {
                    Text(
                        text = stringResourceCompat(R.string.appearance_snapshot_save),
                        color = palette.settings.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
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
        val panelShape = RoundedCornerShape(palette.miuix.panelRadius ?: 10.dp)
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
