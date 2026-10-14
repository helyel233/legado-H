package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3Container
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.AppAsyncImage
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 封面集合管理 V5 版（换壳迁移自旧 View 版，台账 #45）。
 *
 * 旧版：BaseActivity + ViewBinding + XML TitleBar + optionsMenu 云容器按钮，
 *       内容塞 ComposeView 但吃旧主题桥（AppManagementCard 等）。
 * 新版：AppCompatActivity + setV5Content + HostV5Page（玻璃顶栏）+ uikit.v5 组件：
 *       日/夜 Tab 换 AppFilterChipRow、列表换 LazyColumn+AppListItem+AppAsyncImage、
 *       云容器 optionsMenu 换顶栏 trailing 按钮 + AppFilterDialog 选择、
 *       创建/重命名换 AppInputDialog、删除加 AppConfirmDialog(danger)。
 * 类名/包不变（in-place 换壳），入口 CoverCollectionDetailActivity 零切换。
 */
class CoverCollectionManageActivity : AppCompatActivity() {

    internal val isNightState = mutableStateOf(false)
    internal val entriesState = mutableStateOf<List<CoverCollectionManager.Entry>>(emptyList())

    // 云容器（S3 场景才显示顶栏入口）
    private var cloudContainerId: String? = null
    internal var containerButtonVisible by mutableStateOf(false)
    internal var containerButtonLabel by mutableStateOf("")

    internal var containerSelectorOpen by mutableStateOf(false)
    internal var containerList by mutableStateOf<List<S3Container>>(emptyList())
    internal var containerSelectedIndex by mutableIntStateOf(0)

    // 弹窗状态
    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuEntry by mutableStateOf<CoverCollectionManager.Entry?>(null)
    internal var inputDialog by mutableStateOf<InputDialogState?>(null)
    internal var confirmDelete by mutableStateOf<DeleteRequest?>(null)

    private val importZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importZip(uri) }
    }
    private val exportZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { ManageScreen(this) }
        updateContainerMenu()
        loadCollections()
    }

    override fun onResume() {
        super.onResume()
        updateContainerMenu()
        loadCollections()
    }

    private fun loadCollections() {
        lifecycleScope.launch {
            val items = CoverCollectionManager.loadEntries(isNightState.value, cloudContainerId, CLOUD_SCOPE)
            entriesState.value = items
        }
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            containerButtonVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
        containerButtonVisible = true
        containerButtonLabel = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
    }

    internal fun onTabChanged(night: Boolean) {
        if (isNightState.value == night) return
        isNightState.value = night
        loadCollections()
    }

    internal fun openContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) { AppCloudStorage.listContainers().filter { it.enabled } }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            containerList = containers
            containerSelectedIndex = containers.indexOfFirst { it.id == cloudContainerId }
            containerSelectorOpen = true
        }
    }

    internal fun onContainerSelected(index: Int) {
        val container = containerList.getOrNull(index) ?: return
        if (container.id == cloudContainerId) return
        AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
        cloudContainerId = container.id
        updateContainerMenu()
        loadCollections()
    }

    internal fun openCreateDialog() {
        inputDialog = InputDialogState(
            title = getString(R.string.cover_collection_name),
            initialValue = "",
            onConfirm = { name -> createCollection(name) },
        )
    }

    private fun createCollection(name: String) {
        lifecycleScope.launch {
            kotlin.runCatching { CoverCollectionManager.create(name, isNightState.value) }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadCollections()
        }
    }

    internal fun openRenameDialog(entry: CoverCollectionManager.Entry) {
        inputDialog = InputDialogState(
            title = getString(R.string.cover_collection_name),
            initialValue = entry.collection.name,
            onConfirm = { name -> renameCollection(entry, name) },
        )
    }

    private fun renameCollection(entry: CoverCollectionManager.Entry, name: String) {
        lifecycleScope.launch {
            kotlin.runCatching { CoverCollectionManager.rename(entry.collection, name) }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadCollections()
        }
    }

    internal fun launchImportZip() {
        importZip.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    private fun importZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = withContext(Dispatchers.IO) {
                    val dir = externalFiles.getFile("coverCollectionImports").apply { mkdirs() }
                    val target = File(dir, "cover_${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    target
                }
                CoverCollectionManager.importPackage(this@CoverCollectionManageActivity, file, isNightState.value)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            loadCollections()
        }
    }

    internal fun openDetail(entry: CoverCollectionManager.Entry) {
        if (entry.source == CoverCollectionManager.Source.REMOTE) {
            runAction { CoverCollectionManager.download(entry, cloudContainerId, CLOUD_SCOPE) }
            return
        }
        val item = entry.collection
        startActivity<CoverCollectionDetailActivity> {
            putExtra("isNight", item.isNight)
            putExtra("id", item.id)
        }
    }

    private fun exportCollection(entry: CoverCollectionManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { CoverCollectionManager.exportZip(entry) }
                .onSuccess { zipFile ->
                    exportZip.launch {
                        mode = HandleFileContract.EXPORT
                        showUploadUrl = false
                        fileData = HandleFileContract.FileData(zipFile.name, zipFile, "application/zip")
                    }
                }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { block() }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadCollections()
        }
    }

    internal fun actionsFor(entry: CoverCollectionManager.Entry): List<CoverAction> {
        return buildList {
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.RENAME)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.EXPORT)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.UPLOAD)
            if (entry.source != CoverCollectionManager.Source.LOCAL) add(CoverAction.DOWNLOAD)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.DELETE_LOCAL)
            if (entry.source != CoverCollectionManager.Source.LOCAL) add(CoverAction.DELETE_REMOTE)
        }
    }

    internal fun onAction(entry: CoverCollectionManager.Entry, action: CoverAction) {
        when (action) {
            CoverAction.RENAME -> openRenameDialog(entry)
            CoverAction.EXPORT -> exportCollection(entry)
            CoverAction.UPLOAD -> runAction { CoverCollectionManager.upload(entry, cloudContainerId, CLOUD_SCOPE) }
            CoverAction.DOWNLOAD -> runAction { CoverCollectionManager.download(entry, cloudContainerId, CLOUD_SCOPE) }
            CoverAction.DELETE_LOCAL -> confirmDelete = DeleteRequest(
                message = getString(R.string.sure_del) + "\n" + entry.collection.name,
                onConfirm = { runAction { CoverCollectionManager.deleteLocal(entry) } },
            )
            CoverAction.DELETE_REMOTE -> confirmDelete = DeleteRequest(
                message = getString(R.string.sure_del) + "\n" + entry.collection.name,
                onConfirm = { runAction { CoverCollectionManager.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE) } },
            )
        }
    }

    internal data class InputDialogState(
        val title: String,
        val initialValue: String,
        val onConfirm: (String) -> Unit,
    )

    internal data class DeleteRequest(
        val message: String,
        val onConfirm: () -> Unit,
    )

    private companion object {
        private const val CLOUD_SCOPE = "coverCollection"
    }

    internal enum class CoverAction(val titleRes: Int) {
        RENAME(R.string.edit),
        EXPORT(R.string.theme_export_zip),
        UPLOAD(R.string.theme_upload_remote),
        DOWNLOAD(R.string.theme_download_local),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote)
    }
}

@Composable
private fun ManageScreen(activity: CoverCollectionManageActivity) {
    BackHandler { activity.finish() }

    val density = LocalVisualSpec.current.density
    val isNight = activity.isNightState.value
    val entries = activity.entriesState.value

    HostV5Page(
        title = stringResource(R.string.cover_collection_manage),
        onBack = { activity.finish() },
        trailing = {
            if (activity.containerButtonVisible) {
                AppGhostButton(
                    text = activity.containerButtonLabel,
                    onClick = { activity.openContainerSelector() },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            AppFilterChipRow(
                chips = listOf(
                    AppFilterChip(stringResource(R.string.theme_day), !isNight),
                    AppFilterChip(stringResource(R.string.theme_night), isNight),
                ),
                onChipClick = { index -> activity.onTabChanged(index == 1) },
                modifier = Modifier.padding(top = density.blockGap),
            )
            Spacer(Modifier.height(density.blockGap))

            if (entries.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.cover_collection_manage),
                    supporting = stringResource(R.string.cover_collection_add),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = density.screenPadding,
                        end = density.screenPadding,
                        top = density.blockGap / 2,
                        bottom = density.blockGap / 2,
                    ),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    items(entries, key = { "${it.collection.id}_${it.source}" }) { entry ->
                        CoverCollectionItemRow(
                            entry = entry,
                            onClick = { activity.openDetail(entry) },
                            onMoreClick = { activity.itemMenuEntry = entry },
                        )
                    }
                }
            }

            AppFilledButton(
                text = stringResource(R.string.cover_collection_add),
                onClick = { activity.addMenuOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
            )
        }
    }

    // 添加菜单（新建 / 导入 zip）
    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.add),
            actions = listOf(
                stringResource(R.string.cover_collection_add) to {
                    activity.addMenuOpen = false
                    activity.openCreateDialog()
                },
                stringResource(R.string.cover_collection_import_zip) to {
                    activity.addMenuOpen = false
                    activity.launchImportZip()
                },
            ),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    // 条目溢出菜单
    activity.itemMenuEntry?.let { entry ->
        AppMenuSheet(
            title = entry.collection.name,
            actions = activity.actionsFor(entry).map { action ->
                stringResource(action.titleRes) to { activity.onAction(entry, action) }
            },
            onDismiss = { activity.itemMenuEntry = null },
        )
    }

    // 云容器选择
    if (activity.containerSelectorOpen) {
        AppFilterDialog(
            title = stringResource(R.string.s3_bucket),
            options = activity.containerList.map(AppCloudStorage::containerDisplayLabel),
            selectedIndex = activity.containerSelectedIndex,
            onSelect = { index -> activity.onContainerSelected(index) },
            onDismiss = { activity.containerSelectorOpen = false },
        )
    }

    // 创建 / 重命名输入弹窗
    activity.inputDialog?.let { dialog ->
        var text by remember(dialog) { mutableStateOf(dialog.initialValue) }
        AppInputDialog(
            title = dialog.title,
            value = text,
            onValueChange = { text = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = dialog.title,
            onConfirm = {
                activity.inputDialog = null
                dialog.onConfirm(it)
            },
            onDismiss = { activity.inputDialog = null },
        )
    }

    // 删除确认（破坏性操作走 B 门红线）
    activity.confirmDelete?.let { req ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = req.message,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.confirmDelete = null
                req.onConfirm()
            },
            onDismiss = { activity.confirmDelete = null },
        )
    }
}

@Composable
private fun CoverCollectionItemRow(
    entry: CoverCollectionManager.Entry,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    val collection = entry.collection
    val sourceText = when (entry.source) {
        CoverCollectionManager.Source.LOCAL -> stringResource(R.string.theme_source_local)
        CoverCollectionManager.Source.REMOTE -> stringResource(R.string.theme_source_remote)
        CoverCollectionManager.Source.BOTH -> stringResource(R.string.theme_source_both)
    }
    val infoText = "${stringResource(R.string.cover_collection_images_count, collection.images.size)} · $sourceText"

    AppListItem(
        headline = collection.name,
        supporting = infoText,
        leading = {
            AppAsyncImage(
                model = collection.images.firstOrNull(),
                contentDescription = null,
                cornerRadius = 8.dp,
                modifier = Modifier
                    .size(width = 54.dp, height = 72.dp),
            )
        },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = onMoreClick,
            )
        },
        onClick = onClick,
    )
}
