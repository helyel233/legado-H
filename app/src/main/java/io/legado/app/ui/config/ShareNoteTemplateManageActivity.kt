package io.legado.app.ui.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.book.read.ShareNoteImageRenderer
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.readBytes
import io.legado.app.utils.readText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val noteTemplateDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * 摘录分享模板管理 V5 版（主题/外观编辑器批，台账 #46）。
 * 旧版：BaseActivity<ActivityThemeManageBinding> + ComposeView 塞 ShareNoteTemplateManageScreen（旧主题桥）。
 * 新版：AppCompatActivity + setV5Content + HostV5Page；样式快捷卡用 AppCard+AppChip；列表用 AppCard+
 * 预览图(AndroidView+Glide signature)+AppChip(应用/编辑)+AppOverflowMenuButton；弹窗全换 V5。
 */
class ShareNoteTemplateManageActivity : AppCompatActivity() {

    internal val entriesState = mutableStateOf<List<ShareNoteTemplateManager.Entry>>(emptyList())
    internal val activeDirNameState = mutableStateOf(ShareNoteTemplateManager.activeDirName())
    internal val shareStyleState = mutableStateOf(ShareNoteTemplateManager.currentStyle())
    internal val previewFilesState = mutableStateOf<Map<String, File>>(emptyMap())

    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuTarget by mutableStateOf<ShareNoteTemplateManager.Entry?>(null)
    internal var confirmDeleteTarget by mutableStateOf<ShareNoteTemplateManager.Entry?>(null)

    private var editingEntry: ShareNoteTemplateManager.Entry? = null
    private var loadTemplatesJob: Job? = null
    private val previewJobs = mutableListOf<Job>()
    private var previewBatch = 0

    private val importTemplate = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        val name = uri.lastPathSegment.orEmpty().lowercase()
                        if (name.endsWith(".zip")) {
                            val temp = File(cacheDir, "share_note_template_import.zip")
                            temp.writeBytes(uri.readBytes(this@ShareNoteTemplateManageActivity))
                            ShareNoteTemplateManager.importZip(temp)
                        } else {
                            ShareNoteTemplateManager.importHtml(uri.readText(this@ShareNoteTemplateManageActivity))
                        }
                    }
                }.onSuccess {
                    toastOnUi(R.string.success)
                    loadTemplates()
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    private val exportTemplate = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    private val editTemplateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        ShareNoteTemplateManager.addOrUpdate(text, editingEntry)
                    }
                }.onSuccess {
                    editingEntry = null
                    toastOnUi(R.string.success)
                    loadTemplates()
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { ManageScreen(this) }
        loadTemplates()
    }

    override fun onResume() {
        super.onResume()
        loadTemplates()
    }

    override fun onDestroy() {
        loadTemplatesJob?.cancel()
        cancelPreviewJobs()
        super.onDestroy()
    }

    private fun loadTemplates() {
        loadTemplatesJob?.cancel()
        cancelPreviewJobs()
        loadTemplatesJob = lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { ShareNoteTemplateManager.loadEntries() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Share note template load failed\n${e.localizedMessage}", e)
                toastOnUi(getString(R.string.theme_package_load_failed, e.localizedMessage ?: getString(R.string.error)))
                return@launch
            }
            entriesState.value = entries
            activeDirNameState.value = ShareNoteTemplateManager.activeDirName()
            refreshPreviews(entries)
        }
    }

    private fun refreshPreviews(
        entries: List<ShareNoteTemplateManager.Entry>,
        force: Boolean = false
    ) {
        cancelPreviewJobs()
        val currentDirs = entries.mapTo(hashSetOf()) { it.dirName }
        previewFilesState.value = previewFilesState.value.filterKeys { it in currentDirs }
        val batch = previewBatch
        val style = shareStyleState.value
        entries.forEach { entry ->
            val job = lifecycleScope.launch {
                val file = try {
                    ShareNoteImageRenderer.renderPreview(
                        context = this@ShareNoteTemplateManageActivity,
                        entry = entry,
                        force = force,
                        style = style
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("Share note template preview failed: ${entry.dirName}\n${e.localizedMessage}", e)
                    null
                }
                file?.let {
                    if (batch == previewBatch) {
                        previewFilesState.value = previewFilesState.value + (entry.dirName to it)
                    }
                }
            }
            previewJobs += job
        }
    }

    private fun cancelPreviewJobs() {
        previewBatch += 1
        previewJobs.forEach { it.cancel() }
        previewJobs.clear()
    }

    internal fun applyTemplate(entry: ShareNoteTemplateManager.Entry) {
        ShareNoteTemplateManager.apply(entry)
        activeDirNameState.value = entry.dirName
        toastOnUi(R.string.success)
    }

    internal fun updateShareStyle(style: ShareNoteTemplateManager.ShareStyle) {
        if (style == shareStyleState.value) return
        ShareNoteTemplateManager.saveStyle(style)
        shareStyleState.value = ShareNoteTemplateManager.currentStyle()
        previewFilesState.value = emptyMap()
        refreshPreviews(entriesState.value, force = true)
    }

    internal fun openAddMenu() {
        addMenuOpen = true
    }

    internal fun onAddAction(index: Int) {
        when (index) {
            0 -> copyTemplate(ShareNoteTemplateManager.builtinEntry(), editAfterCopy = true)
            1 -> importTemplate.launch {
                mode = HandleFileContract.FILE
                title = "导入 HTML"
                allowExtensions = arrayOf("html", "htm")
            }
            2 -> importTemplate.launch {
                mode = HandleFileContract.FILE
                title = "导入 ZIP"
                allowExtensions = arrayOf("zip")
            }
        }
    }

    internal fun openItemMenu(entry: ShareNoteTemplateManager.Entry) {
        itemMenuTarget = entry
    }

    internal fun itemActions(entry: ShareNoteTemplateManager.Entry): List<Pair<String, () -> Unit>> {
        return buildList {
            add("预览头部" to { openPreview(entry) })
            add("复制新建" to { copyTemplate(entry, editAfterCopy = true) })
            add("导出 HTML" to { exportHtml(entry) })
            add("导出 ZIP" to { exportZip(entry) })
            if (entry.source == ShareNoteTemplateManager.Source.LOCAL) {
                add("删除" to { confirmDelete(entry) })
            }
        }
    }

    internal fun deleteTemplate(entry: ShareNoteTemplateManager.Entry) {
        ShareNoteTemplateManager.deleteLocal(entry)
        loadTemplates()
    }

    private fun openPreview(entry: ShareNoteTemplateManager.Entry) {
        lifecycleScope.launch {
            val file = try {
                ShareNoteImageRenderer.renderPreview(
                    context = this@ShareNoteTemplateManageActivity,
                    entry = entry,
                    force = true,
                    style = shareStyleState.value
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Share note template manual preview failed: ${entry.dirName}\n${e.localizedMessage}", e, true)
                null
            }
            if (file == null) {
                toastOnUi(R.string.error)
            } else {
                previewFilesState.value = previewFilesState.value + (entry.dirName to file)
                toastOnUi(R.string.success)
            }
        }
    }

    private fun copyTemplate(entry: ShareNoteTemplateManager.Entry, editAfterCopy: Boolean) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { ShareNoteTemplateManager.copyToLocal(entry) }
            }.onSuccess {
                loadTemplates()
                if (editAfterCopy) editTemplate(it)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    internal fun editTemplate(entry: ShareNoteTemplateManager.Entry) {
        val editable = if (entry.source == ShareNoteTemplateManager.Source.BUILTIN) {
            copyTemplate(entry, editAfterCopy = true)
            return
        } else {
            entry
        }
        editingEntry = editable
        editTemplateLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("title", editable.meta.name)
            putExtra("text", ShareNoteTemplateManager.readTemplateHtml(editable))
            putExtra("languageName", "text.html.basic")
        })
    }

    private fun exportHtml(entry: ShareNoteTemplateManager.Entry) {
        exportTemplate.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "${safeFileName(entry.meta.name)}.html",
                ShareNoteTemplateManager.exportHtmlBytes(entry),
                "text/html"
            )
        }
    }

    private fun exportZip(entry: ShareNoteTemplateManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { ShareNoteTemplateManager.exportZip(entry) }
            }.onSuccess { file ->
                exportTemplate.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "${safeFileName(entry.meta.name)}.zip",
                        file,
                        "application/zip"
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: "导出失败")
            }
        }
    }

    private fun confirmDelete(entry: ShareNoteTemplateManager.Entry) {
        confirmDeleteTarget = entry
    }

    private fun safeFileName(name: String): String {
        return name.trim().ifBlank { "share_note_template" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }
}

@Composable
private fun ManageScreen(activity: ShareNoteTemplateManageActivity) {
    BackHandler { activity.finish() }

    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val entries = activity.entriesState.value
    val activeDirName = activity.activeDirNameState.value
    val shareStyle = activity.shareStyleState.value
    val previewFiles = activity.previewFilesState.value

    HostV5Page(
        title = "摘录分享模板",
        onBack = { activity.finish() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "管理正文长按摘录分享图片使用的 HTML 模板。预览只显示模板头部，分享时会生成完整图片。",
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = density.screenPadding, top = density.blockGap, end = density.screenPadding),
            )
            ShareNoteStyleQuickCard(
                shareStyle = shareStyle,
                onStyleChange = activity::updateShareStyle,
                modifier = Modifier.padding(horizontal = density.screenPadding, vertical = density.blockGap),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    bottom = density.blockGap / 2,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                items(entries, key = { it.dirName }) { entry ->
                    val active = activeDirName == entry.dirName
                    AppCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TemplatePreview(
                                previewFile = previewFiles[entry.dirName],
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.meta.name,
                                    style = visual.type.body,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = buildInfoText(entry),
                                    style = visual.type.caption,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppChip(
                                        text = stringResource(if (active) R.string.theme_applied_state else R.string.theme_apply),
                                        selected = active,
                                        onClick = { activity.applyTemplate(entry) },
                                    )
                                    if (entry.source == ShareNoteTemplateManager.Source.LOCAL) {
                                        AppChip(
                                            text = stringResource(R.string.edit),
                                            selected = false,
                                            onClick = { activity.editTemplate(entry) },
                                        )
                                    }
                                    AppOverflowMenuButton(
                                        contentDescription = stringResource(R.string.more),
                                        onClick = { activity.openItemMenu(entry) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            AppFilledButton(
                text = "添加模板",
                onClick = { activity.openAddMenu() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
            )
        }
    }

    // 添加菜单（复制内置模板 / 导入 HTML / 导入 ZIP）
    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = "添加模板",
            actions = listOf(
                "复制内置模板新建" to { activity.onAddAction(0) },
                "导入 HTML" to { activity.onAddAction(1) },
                "导入 ZIP" to { activity.onAddAction(2) },
            ),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    // 条目溢出菜单
    activity.itemMenuTarget?.let { entry ->
        AppMenuSheet(
            title = entry.meta.name,
            actions = activity.itemActions(entry),
            onDismiss = { activity.itemMenuTarget = null },
        )
    }

    // 删除确认
    activity.confirmDeleteTarget?.let { entry ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = entry.meta.name,
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.confirmDeleteTarget = null
                activity.deleteTemplate(entry)
            },
            onDismiss = { activity.confirmDeleteTarget = null },
        )
    }
}

@Composable
private fun ShareNoteStyleQuickCard(
    shareStyle: ShareNoteTemplateManager.ShareStyle,
    onStyleChange: (ShareNoteTemplateManager.ShareStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "分享样式",
                style = visual.type.body,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "快速切换摘录分享图片的配色和字体，预览与分享图片会同步更新。",
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "配色",
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShareNoteTemplateManager.stylePalettes.forEach { stylePalette ->
                    AppChip(
                        text = stylePalette.name,
                        selected = stylePalette.id == shareStyle.paletteId,
                        onClick = { onStyleChange(shareStyle.copy(paletteId = stylePalette.id)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "字体",
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShareNoteTemplateManager.fontFamilies.forEach { font ->
                    AppChip(
                        text = ShareNoteTemplateManager.fontLabel(font),
                        selected = font == shareStyle.fontFamily,
                        onClick = { onStyleChange(shareStyle.copy(fontFamily = font)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplatePreview(previewFile: File?) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .size(width = 78.dp, height = 110.dp)
            .clip(visual.shape(ShapeKind.COVER))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (previewFile != null && previewFile.exists() && previewFile.length() > 0L) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                },
                update = { imageView ->
                    Glide.with(imageView)
                        .load(previewFile)
                        .signature(
                            ObjectKey("${previewFile.absolutePath}:${previewFile.length()}:${previewFile.lastModified()}")
                        )
                        .centerCrop()
                        .into(imageView)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "预览",
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun buildInfoText(entry: ShareNoteTemplateManager.Entry): String {
    val source = when (entry.source) {
        ShareNoteTemplateManager.Source.BUILTIN -> "内置"
        ShareNoteTemplateManager.Source.LOCAL -> "本地"
    }
    val time = entry.meta.updatedAt.takeIf { it > 0L }?.let {
        noteTemplateDateFormat.format(Date(it))
    }
    return listOfNotNull(
        entry.meta.canvasLabel(),
        entry.meta.sizeLabel(),
        source,
        time
    ).joinToString(" · ")
}
