// __PART2__

package io.legado.app.ui.book.read.config

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.help.reader.ReaderAsset
import io.legado.app.help.reader.ReaderAssetFolder
import io.legado.app.help.reader.ReaderAssetLibrary
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.FileDoc
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// __PART3__

internal data class AssetEntry(
    val asset: ReaderAsset? = null,
    val folder: ReaderAssetFolder? = null,
    val local: FileDoc? = null,
) {
    val key: String get() = asset?.id ?: folder?.id ?: local?.uri.toString()
    val name: String get() = asset?.name ?: folder?.name ?: local?.name.orEmpty()
    val isFolder: Boolean get() = folder != null || local?.isDir == true
}

class ReaderAssetManageActivity : AppCompatActivity() {

    internal var navigation by mutableStateOf<List<FileDoc>>(emptyList())
    internal var library by mutableStateOf(ReaderAssetLibrary())
    internal var localFiles by mutableStateOf<List<FileDoc>>(emptyList())
    internal var query by mutableStateOf("")
    internal var filter by mutableStateOf("all")
    internal var loaded by mutableStateOf(false)
    internal var busy by mutableStateOf(false)
    internal var loading by mutableStateOf(true)

    internal var overflowMenuOpen by mutableStateOf(false)
    internal var itemMenuEntry by mutableStateOf<AssetEntry?>(null)
    internal var renameAsset by mutableStateOf<ReaderAsset?>(null)
    internal var deleteAsset by mutableStateOf<ReaderAsset?>(null)
    internal var importFolderFiles by mutableStateOf<List<FileDoc>?>(null)
    internal var infoDialog by mutableStateOf<Pair<String, String>?>(null)

    internal val pickKind: String
        get() = intent.getStringExtra("pickKind").orEmpty()
            .takeIf { it in setOf("font", "image", "any") }.orEmpty()

    internal val selectedId: String?
        get() = intent.getStringExtra("selectedId")

    internal val canInteract: Boolean
        get() = loaded && !busy && !loading

    private var loadJob: Job? = null

    private val importFile = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let(::importOne)
    }
    private val folderPicker = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri -> work {
            withContext(Dispatchers.IO) {
                if (uri.scheme == "content") runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ReaderAssets.addFolder(uri)
            }
            filter = "folder"
            navigation = emptyList()
        } }
    }
    private val exportFile = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = savedInstanceState?.getString("filter")
            ?: pickKind.takeIf { it in setOf("image", "font") } ?: "all"
        query = savedInstanceState?.getString("query").orEmpty()
        val directoryNames = savedInstanceState?.getStringArrayList("directoryNames").orEmpty()
        val directoryUris = savedInstanceState?.getStringArrayList("directoryUris").orEmpty()
        navigation = directoryNames.zip(directoryUris).map { (name, uri) ->
            FileDoc(name, true, 0L, 0L, uri.toUri())
        }
        setV5Content { ReaderAssetManageContent(this@ReaderAssetManageActivity) }
        renderInitial()
    }

    private fun renderInitial() {
        // 与旧版一致：进入即渲染一次当前过滤结果（恢复导航栈后 localFiles 稍后由 reload 填充）
    }

    override fun onResume() {
        super.onResume()
        if (!busy) reload()
    }

    internal fun onQueryChanged(value: String) {
        query = value.take(256)
    }

    internal fun onFilterSelected(kind: String) {
        if (!canInteract) return
        filter = kind
        navigation = emptyList()
        localFiles = emptyList()
    }

    internal fun pickDefault() {
        if (!canInteract) return
        setResult(RESULT_OK, Intent().putExtra("assetId", ""))
        finish()
    }

    internal fun pickFile() {
        if (!canInteract) return
        importFile.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.reader_assets_import)
            showUploadUrl = false
            allowExtensions = when (pickKind) {
                "font" -> arrayOf("ttf", "otf", "ttc", "woff", "woff2")
                "image" -> arrayOf("png", "jpg", "jpeg", "gif", "webp")
                else -> ReaderAssets.extensions
            }
        }
    }

    internal fun pickFolder() {
        if (!canInteract) return
        folderPicker.launch {
            mode = HandleFileContract.DIR
            title = getString(R.string.reader_assets_add_folder)
            showUploadUrl = false
        }
    }

    internal fun importOne(uri: Uri) = work {
        val asset = withContext(Dispatchers.IO) { ReaderAssets.importUri(uri) }
        changed()
        if (pickKind.isNotBlank()) select(asset) else toastOnUi("已导入：${asset.name}")
    }

// __PART4__

    internal fun confirmImportFolder() {
        val files = importFolderFiles ?: return
        importFolderFiles = null
        work {
            val failures = arrayListOf<String>()
            val count = withContext(Dispatchers.IO) {
                files.count { file -> runCatching { ReaderAssets.importUri(file.uri) }
                    .onFailure { failures.add(file.name + "：" + it.localizedMessage) }.isSuccess }
            }
            changed()
            infoDialog = getString(R.string.reader_assets_import) to
                ("已导入或合并 $count 个素材" + if (failures.isEmpty()) "" else "\n\n" + failures.take(10).joinToString("\n"))
        }
    }

    internal fun importFolder() {
        if (!canInteract) return
        if (navigation.isEmpty()) {
            toastOnUi("请先打开一个来源文件夹")
            return
        }
        val files = filteredEntries().mapNotNull { it.local }.filterNot { it.isDir }
        if (files.isEmpty()) {
            toastOnUi(R.string.reader_assets_search_empty)
            return
        }
        if (files.size > 100) {
            toastOnUi("一次最多导入 100 个素材，请搜索后分批导入")
            return
        }
        importFolderFiles = files
    }

    internal fun openFolder(entry: AssetEntry) {
        if (!canInteract) return
        work {
            val folder = withContext(Dispatchers.IO) {
                entry.local ?: FileDoc.fromUri(requireNotNull(entry.folder).uri.toUri(), true)
            }
            // Read before changing navigation, so an expired grant leaves a usable screen.
            val children = withContext(Dispatchers.IO) { ReaderAssets.browse(folder) }
            navigation = navigation + folder
            localFiles = children
            filter = "folder"
            query = ""
        }
    }

    internal fun up() {
        if (busy || navigation.isEmpty()) return
        navigation = navigation.dropLast(1)
        query = ""
        reload()
    }

    internal fun select(asset: ReaderAsset) {
        if (pickKind.isBlank()) {
            copyReference(asset)
            return
        }
        if (pickKind != "any" && asset.kind != pickKind) {
            toastOnUi("请选择${if (pickKind == "font") "字体" else "图片"}素材")
            return
        }
        setResult(RESULT_OK, Intent().putExtra("assetId", asset.id).putExtra("kind", asset.kind))
        finish()
    }

    internal fun onItemAction(index: Int) {
        val entry = itemMenuEntry ?: return
        itemMenuEntry = null
        val asset = entry.asset
        val folder = entry.folder
        if (folder != null) {
            when (index) {
                0 -> openFolder(entry)
                1 -> work {
                    withContext(Dispatchers.IO) { ReaderAssets.store.removeFolder(folder.id) }
                }
            }
            return
        }
        if (asset == null) return
        when (index) {
            0 -> preview(asset)
            1 -> renameAsset = asset
            2 -> work {
                val used = withContext(Dispatchers.IO) { ReaderAssets.references(asset.id) }
                infoDialog = getString(R.string.reader_assets_references) to
                    used.joinToString("\n").ifEmpty { getString(R.string.reader_assets_unused) }
            }
            3 -> work {
                val bytes = withContext(Dispatchers.IO) { ReaderAssets.store.verifiedBytes(asset.id) }
                exportFile.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    val name = asset.name.takeIf { it.endsWith(".${asset.extension}", true) }
                        ?: "${asset.name}.${asset.extension}"
                    fileData = HandleFileContract.FileData(name, bytes, asset.mimeType)
                }
            }
            4 -> copyReference(asset)
            5 -> checkDelete(asset)
        }
    }

    private fun checkDelete(asset: ReaderAsset) = work {
        val used = withContext(Dispatchers.IO) { ReaderAssets.references(asset.id) }
        if (used.isNotEmpty()) {
            infoDialog = getString(R.string.reader_assets_references) to
                (getString(R.string.reader_assets_reference_block) + "\n\n" + used.joinToString("\n"))
        } else {
            deleteAsset = asset
        }
    }

    internal fun onRenameConfirmed(name: String) {
        val asset = renameAsset ?: return
        renameAsset = null
        work { withContext(Dispatchers.IO) { ReaderAssets.store.rename(asset.id, name) } }
    }

    internal fun onDeleteConfirmed() {
        val asset = deleteAsset ?: return
        deleteAsset = null
        work {
            withContext(Dispatchers.IO) { ReaderAssets.deleteUnused(asset.id) }
            changed()
        }
    }

    internal fun onOverflowAction(index: Int) {
        overflowMenuOpen = false
        when (index) {
            0 -> importFolder()
            1 -> if (!busy) reload()
            2 -> infoDialog = getString(R.string.reader_assets_title) to
                (getString(R.string.reader_assets_hint) + "\n\n" + getString(R.string.reader_assets_supported))
        }
    }

    internal fun copyReference(asset: ReaderAsset) {
        sendToClip(
            if (asset.kind == "font") "font-family:'${ReaderAssetReferences.fontFamily(asset.id)}';"
            else ReaderAssetReferences.url(asset.id)
        )
    }

    internal fun preview(asset: ReaderAsset) =
        startActivity<ReaderAssetPreviewActivity> { putExtra("assetId", asset.id) }

    private fun changed() {
        ReadConfigEvent.post(ReadConfigEvent.Typography, ReadConfigEvent.Relayout)
    }

    private suspend fun readContent() {
        val directory = navigation.lastOrNull()
        val result = withContext(Dispatchers.IO) {
            ReaderAssets.store.library() to (directory?.let(ReaderAssets::browse) ?: emptyList())
        }
        library = result.first
        localFiles = result.second
        loaded = true
    }

    internal fun reload() {
        if (busy) return
        loadJob?.cancel()
        loading = true
        loadJob = lifecycleScope.launch {
            try {
                readContent()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            } finally {
                if (isActive) {
                    loading = false
                }
            }
        }
    }

    internal fun work(action: suspend () -> Unit) {
        if (busy) return
        loadJob?.cancel()
        loading = false
        busy = true
        lifecycleScope.launch {
            try {
                // A file/index commit may finish after rotation; the next instance reloads it.
                action()
                if (!isFinishing) readContent()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            } finally {
                if (isActive && !isDestroyed) {
                    busy = false
                }
            }
        }
    }

    internal fun filteredEntries(): List<AssetEntry> {
        val entries = when {
            navigation.isNotEmpty() -> localFiles.filter { file -> file.isDir || pickKind !in setOf("font", "image") ||
                (file.name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc", "woff", "woff2")) == (pickKind == "font")
            }.map { AssetEntry(local = it) }
            filter == "folder" -> library.folders.map { AssetEntry(folder = it) }
            else -> library.assets.filter { (filter == "all" || it.kind == filter) &&
                (pickKind !in setOf("font", "image") || it.kind == pickKind) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map { AssetEntry(asset = it) }
        }
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries.filter { entry ->
            terms.all { (entry.name + " " + entry.asset?.extension.orEmpty()).contains(it, true) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        outState.putString("filter", filter)
        outState.putStringArrayList("directoryNames", ArrayList(navigation.map { it.name }))
        outState.putStringArrayList("directoryUris", ArrayList(navigation.map { it.uri.toString() }))
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun ReaderAssetManageContent(activity: ReaderAssetManageActivity) {
    val density = LocalVisualSpec.current.density
    val entries = activity.filteredEntries()
    val isPicking = activity.pickKind in setOf("font", "image")

    BackHandler(enabled = activity.navigation.isNotEmpty() && !activity.busy) { activity.up() }

    HostV5Page(
        title = stringResource(
            when (activity.pickKind) {
                "font" -> R.string.reader_assets_font_select
                "image" -> R.string.reader_assets_image_select
                else -> R.string.reader_assets_title
            }
        ),
        onBack = {
            if (activity.navigation.isNotEmpty() && !activity.busy) activity.up() else activity.finish()
        },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.overflowMenuOpen = true },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTextField(
                value = activity.query,
                onValueChange = { activity.onQueryChanged(it) },
                modifier = Modifier.padding(horizontal = density.screenPadding),
                placeholder = stringResource(R.string.search),
                singleLine = true,
            )
            Row(
                modifier = Modifier.padding(horizontal = density.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                listOf("all" to "全部", "image" to "图片", "font" to "字体", "folder" to "文件夹")
                    .filter { (kind, _) ->
                        !((activity.pickKind == "font" && kind in setOf("image", "all")) ||
                            (activity.pickKind == "image" && kind in setOf("font", "all")))
                    }
                    .forEach { (kind, labelRes) ->
                        AppChip(
                            text = labelRes,
                            selected = activity.filter == kind,
                            onClick = { activity.onFilterSelected(kind) },
                        )
                    }
            }
            Row(
                modifier = Modifier.padding(horizontal = density.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppGhostButton(stringResource(R.string.reader_assets_import)) { activity.pickFile() }
                AppGhostButton(stringResource(R.string.reader_assets_add_folder)) { activity.pickFolder() }
                if (activity.navigation.isNotEmpty()) {
                    AppGhostButton("上级") { activity.up() }
                }
                if (isPicking) {
                    AppGhostButton(
                        stringResource(
                            if (activity.pickKind == "font") R.string.reader_assets_follow_font
                            else R.string.reader_assets_no_image
                        )
                    ) { activity.pickDefault() }
                }
            }
            Text(
                text = stringResource(
                    if (activity.filter == "folder") R.string.reader_assets_folder_hint
                    else R.string.reader_assets_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = density.screenPadding, vertical = density.blockGap / 4),
            )
            Text(
                text = if (activity.busy || activity.loading) {
                    stringResource(R.string.reader_assets_busy)
                } else {
                    activity.navigation.lastOrNull()?.let { "${it.name} · ${entries.size} 项" }
                        ?: "${entries.size} 项 · 素材库 ${Formatter.formatFileSize(activity, activity.library.assets.sumOf { it.size })}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = density.screenPadding),
            )
            Spacer(Modifier.height(density.blockGap / 2))
            if (entries.isEmpty()) {
                AppEmpty(
                    title = stringResource(
                        when {
                            !activity.loaded && (activity.busy || activity.loading) -> R.string.reader_assets_loading
                            !activity.loaded -> R.string.reader_assets_load_failed
                            activity.query.isNotBlank() || activity.navigation.isNotEmpty() -> R.string.reader_assets_search_empty
                            activity.filter == "folder" -> R.string.reader_assets_folder_empty
                            else -> R.string.reader_assets_empty
                        }
                    ),
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
                    items(entries, key = { it.key }) { entry ->
                        AssetRow(activity, entry)
                    }
                }
            }
        }
    }

    if (activity.overflowMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.reader_assets_title),
            actions = listOf(
                stringResource(R.string.reader_assets_import_folder) to { activity.onOverflowAction(0) },
                "刷新" to { activity.onOverflowAction(1) },
                stringResource(R.string.help) to { activity.onOverflowAction(2) },
            ),
            onDismiss = { activity.overflowMenuOpen = false },
        )
    }

    activity.itemMenuEntry?.let { entry ->
        val asset = entry.asset
        val folder = entry.folder
        val actions: List<Pair<String, () -> Unit>> = if (folder != null) {
            listOf(
                "打开文件夹" to { activity.onItemAction(0) },
                stringResource(R.string.reader_assets_remove_folder) to { activity.onItemAction(1) },
            )
        } else if (asset != null) {
            listOf(
                stringResource(R.string.reader_assets_preview) to { activity.onItemAction(0) },
                stringResource(R.string.reader_assets_rename) to { activity.onItemAction(1) },
                stringResource(R.string.reader_assets_references) to { activity.onItemAction(2) },
                stringResource(R.string.export_str) to { activity.onItemAction(3) },
                stringResource(
                    if (asset.kind == "font") R.string.reader_assets_copy_font
                    else R.string.reader_assets_copy_url
                ) to { activity.onItemAction(4) },
                stringResource(R.string.delete) to { activity.onItemAction(5) },
            )
        } else {
            emptyList()
        }
        AppMenuSheet(
            title = entry.name,
            actions = actions,
            onDismiss = { activity.itemMenuEntry = null },
        )
    }

    activity.renameAsset?.let { asset ->
        var name by remember(asset.id) { mutableStateOf(asset.name) }
        AppInputDialog(
            title = stringResource(R.string.reader_assets_rename),
            value = name,
            onValueChange = { name = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = stringResource(R.string.reader_assets_rename_hint),
            onConfirm = { if (name.isNotBlank() && name.length <= 256) activity.onRenameConfirmed(name) },
            onDismiss = { activity.renameAsset = null },
        )
    }

    if (activity.importFolderFiles != null) {
        AppConfirmDialog(
            title = stringResource(R.string.reader_assets_import_folder),
            message = stringResource(R.string.reader_assets_import_folder_hint) + "\n\n" +
                "${activity.importFolderFiles?.size ?: 0} 个文件",
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.confirmImportFolder() },
            onDismiss = { activity.importFolderFiles = null },
        )
    }

    activity.deleteAsset?.let { asset ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = asset.name + "\n\n" + stringResource(R.string.reader_assets_delete_hint),
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteConfirmed() },
            onDismiss = { activity.deleteAsset = null },
        )
    }

    activity.infoDialog?.let { (title, message) ->
        AppConfirmDialog(
            title = title,
            message = message,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.infoDialog = null },
            onDismiss = { activity.infoDialog = null },
        )
    }
}

@Composable
private fun AssetRow(activity: ReaderAssetManageActivity, entry: AssetEntry) {
    val asset = entry.asset
    val isFont = asset?.kind == "font" ||
        entry.local?.name?.substringAfterLast('.')?.lowercase() in setOf("ttf", "otf", "ttc", "woff", "woff2")
    val info = when {
        asset != null -> listOf(
            if (asset.id == activity.selectedId) stringResource(R.string.reader_assets_current)
            else asset.extension.uppercase(),
            if (asset.kind == "image") "${asset.width} × ${asset.height}"
            else stringResource(R.string.reader_assets_fonts),
            Formatter.formatFileSize(activity, asset.size)
        ).joinToString(" · ")
        entry.isFolder -> if (entry.folder != null) "本地来源 · 点击浏览" else "子文件夹"
        else -> "待导入 · " + Formatter.formatFileSize(activity, entry.local?.size ?: 0)
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (activity.canInteract) when {
                entry.isFolder -> activity.openFolder(entry)
                asset != null -> activity.preview(asset)
                entry.local != null -> activity.importOne(entry.local.uri)
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AssetPreview(activity, entry, isFont)
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            AppGhostButton(
                text = when {
                    entry.isFolder -> "打开"
                    entry.local != null -> "导入"
                    activity.pickKind.isNotBlank() -> stringResource(R.string.reader_assets_select)
                    else -> stringResource(R.string.reader_assets_use)
                },
                onClick = {
                    if (activity.canInteract) when {
                        entry.isFolder -> activity.openFolder(entry)
                        entry.local != null -> activity.importOne(entry.local.uri)
                        asset != null -> activity.select(asset)
                    }
                },
            )
            if (entry.local == null) {
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.more),
                    onClick = { activity.itemMenuEntry = entry },
                )
            }
        }
    }
}

@Composable
private fun AssetPreview(activity: ReaderAssetManageActivity, entry: AssetEntry, isFont: Boolean) {
    val asset = entry.asset
    when {
        entry.isFolder -> Icon(
            painter = painterResource(android.R.drawable.ic_menu_agenda),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        isFont && asset != null -> {
            val face by produceState<Typeface?>(null, asset.id) {
                value = withContext(Dispatchers.IO) { runCatching { ReaderAssets.typeface(asset.id) }.getOrNull() }
            }
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        text = context.getString(R.string.reader_assets_font_tile_sample)
                        typeface = context.uiTypeface()
                    }
                },
                update = { view -> face?.let { view.typeface = it } },
                modifier = Modifier.size(48.dp),
            )
        }
        else -> AndroidView(
            factory = { context ->
                ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            },
            update = { view ->
                Glide.with(view)
                    .load(asset?.let { File(ReaderAssets.store.directory, "files/${it.id}") } ?: entry.local?.uri)
                    .fitCenter()
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(view)
            },
            modifier = Modifier.size(48.dp),
        )
    }
}
