package io.legado.app.ui.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.help.config.EpubLoadingTemplateStore
import io.legado.app.ui.book.read.epub.EpubLoadingPreviewView
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.inputStream
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EPUB 加载模板管理 V5 版（主题/外观编辑器批，台账 #46）。
 * 旧版：BaseActivity<ActivityThemeManageBinding>，隐藏 XML 的 Tab/概要/添加，用 ComposeView 塞
 *       EpubLoadingTemplateManageScreen（旧主题桥 AppPackageManageScreen + AppPackageManageItemCard）。
 * 新版：AppCompatActivity + setV5Content + HostV5Page；列表项改用 AppCard + AppChip（应用/预览）
 *       + AppOverflowMenuButton；预览图 EpubLoadingPreviewView 用 AndroidView 承载（102x210 实时预览）。
 * 弹窗全换 V5：添加/条目菜单 AppMenuSheet、删除 AppConfirmDialog(danger)。
 * 编辑仍走 CodeEditActivity（代码编辑器），无旧 View Dialog，属「轻」页。
 */
class EpubLoadingTemplateManageActivity : AppCompatActivity() {

    internal val templates = mutableStateOf(EpubLoadingTemplate.builtins)
    internal val selectedId = mutableStateOf(EpubLoadingTemplate.default.id)
    internal val previewNight = mutableStateOf(AppConfig.isNightTheme)
    private var editingId: String? = null

    internal var addMenuOpen by mutableStateOf(false)
    internal var itemMenuTarget by mutableStateOf<EpubLoadingTemplate?>(null)
    internal var confirmDeleteTarget by mutableStateOf<EpubLoadingTemplate?>(null)

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        runCatching { EpubLoadingTemplateStore.save(this, text, editingId) }
            .onSuccess { editingId = null; reload() }
            .onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                launchEditor(text, editingId)
            }
    }
    private val importer = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    uri.inputStream(this@EpubLoadingTemplateManageActivity).getOrThrow().use { input ->
                        val limit = EpubLoadingTemplateStore.MAX_IMPORT_BYTES
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            require(output.size() + read <= limit) { "模板文件过大" }
                            output.write(buffer, 0, read)
                            read = input.read(buffer)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
                EpubLoadingTemplateStore.save(this@EpubLoadingTemplateManageActivity, source)
                reload()
                toastOnUi(R.string.success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }
    private val exporter = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingId = savedInstanceState?.getString("editingId")
        previewNight.value = savedInstanceState?.getBoolean("previewNight") ?: AppConfig.isNightTheme
        setV5Content { ManageScreen(this) }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("editingId", editingId)
        outState.putBoolean("previewNight", previewNight.value)
        super.onSaveInstanceState(outState)
    }

    private fun reload() {
        templates.value = EpubLoadingTemplateStore.all(this)
        selectedId.value = EpubLoadingTemplateStore.selected(this).id
    }

    internal fun preview(template: EpubLoadingTemplate) {
        startActivity<EpubLoadingTemplatePreviewActivity> {
            putExtra("templateId", template.id)
            putExtra("night", previewNight.value)
        }
    }

    internal fun applyTemplate(template: EpubLoadingTemplate) {
        EpubLoadingTemplateStore.apply(this, template)
        reload()
    }

    private fun launchEditor(text: String, id: String?) {
        editingId = id
        editor.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("title", getString(R.string.epub_loading_templates))
            putExtra("text", text)
            // The bundled JavaScript grammar also highlights JSON; no JSON grammar is registered.
            putExtra("languageName", "source.js")
        })
    }

    private fun copy(template: EpubLoadingTemplate) {
        val draft = template.copy(name = getString(R.string.epub_loading_copy_name, template.name.take(48)))
        launchEditor(EpubLoadingTemplateStore.encode(draft), null)
    }

    internal fun openAddMenu() {
        addMenuOpen = true
    }

    internal fun onAddAction(index: Int) {
        if (index == 0) copy(EpubLoadingTemplateStore.selected(this))
        else importer.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.epub_loading_import)
            allowExtensions = arrayOf("json")
        }
    }

    internal fun openItemMenu(template: EpubLoadingTemplate) {
        itemMenuTarget = template
    }

    internal fun itemActions(template: EpubLoadingTemplate): List<Pair<String, () -> Unit>> = buildList {
        add(getString(R.string.epub_loading_preview) to { preview(template) })
        add(getString(R.string.epub_loading_copy) to { copy(template) })
        if (!template.builtIn) add(getString(R.string.edit) to {
            launchEditor(EpubLoadingTemplateStore.encode(template), template.id)
        })
        add(getString(R.string.epub_loading_export) to {
            exporter.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    template.name.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".json",
                    EpubLoadingTemplateStore.encode(template).toByteArray(Charsets.UTF_8), "application/json")
            }
        })
        if (!template.builtIn) add(getString(R.string.delete) to {
            confirmDeleteTarget = template
        })
    }

    internal fun deleteTemplate(template: EpubLoadingTemplate) {
        EpubLoadingTemplateStore.delete(this, template)
        reload()
    }
}

@Composable
private fun ManageScreen(activity: EpubLoadingTemplateManageActivity) {
    BackHandler { activity.finish() }

    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val night = activity.previewNight.value
    val templates = activity.templates.value
    val selectedId = activity.selectedId.value

    HostV5Page(
        title = stringResource(R.string.epub_loading_templates),
        onBack = { activity.finish() },
    ) {
        Column(Modifier.fillMaxSize()) {
            AppFilterChipRow(
                chips = listOf(
                    AppFilterChip(stringResource(R.string.theme_day), !night),
                    AppFilterChip(stringResource(R.string.theme_night), night),
                ),
                onChipClick = { index -> activity.previewNight.value = index == 1 },
                modifier = Modifier.padding(top = density.blockGap),
            )
            Text(
                text = stringResource(R.string.epub_loading_templates_help),
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = density.screenPadding, top = density.blockGap / 2, end = density.screenPadding),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap,
                    bottom = density.blockGap / 2,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                items(templates, key = { it.id }) { template ->
                    val active = template.id == selectedId
                    val info = stringResource(if (template.builtIn) R.string.epub_loading_builtin else R.string.epub_loading_custom) +
                        " · " + template.description
                    AppCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AndroidView(
                                factory = { context ->
                                    EpubLoadingPreviewView(context).apply {
                                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                    }
                                },
                                update = { it.template = template; it.night = night },
                                modifier = Modifier
                                    .width(102.dp)
                                    .height(210.dp)
                                    .clip(visual.shape(ShapeKind.COVER))
                                    .clickable { activity.preview(template) },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = template.name,
                                    style = visual.type.body,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = info,
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
                                        onClick = { activity.applyTemplate(template) },
                                    )
                                    AppChip(
                                        text = stringResource(R.string.epub_loading_preview),
                                        selected = false,
                                        onClick = { activity.preview(template) },
                                    )
                                    AppOverflowMenuButton(
                                        contentDescription = stringResource(R.string.more),
                                        onClick = { activity.openItemMenu(template) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            AppFilledButton(
                text = stringResource(R.string.epub_loading_add),
                onClick = { activity.openAddMenu() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
            )
        }
    }

    // 添加菜单（复制 / 导入）
    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.epub_loading_add),
            actions = listOf(
                stringResource(R.string.epub_loading_copy) to { activity.onAddAction(0) },
                stringResource(R.string.epub_loading_import) to { activity.onAddAction(1) },
            ),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    // 条目溢出菜单
    activity.itemMenuTarget?.let { template ->
        AppMenuSheet(
            title = template.name,
            actions = activity.itemActions(template),
            onDismiss = { activity.itemMenuTarget = null },
        )
    }

    // 删除确认
    activity.confirmDeleteTarget?.let { template ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = template.name,
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.confirmDeleteTarget = null
                activity.deleteTemplate(template)
            },
            onDismiss = { activity.confirmDeleteTarget = null },
        )
    }
}
