package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.help.CacheManager
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.help.reader.ReaderTemplateAssetStyle
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplatePackages
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateOperationQueue
import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.Relayout
import io.legado.app.reader.config.ReadConfigEvent.Typography
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.inputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 阅读模板（V5 基座版）：模板库 + 模板编辑器两段。
 *
 * 旧基类 BOTTOM/AnimDialogBottom/MATCH_PARENT → V5 五个 open val；`maxSheetHeightFraction = .9f`
 * → 面板内 `heightIn(max = screenHeightDp*0.9f)`；**外观跟随阅读页背景色**用共享 `rememberReaderPagePalette()`。
 * 组件替换：`AppPackageManageItemCard` → `AppCard` + 名称/描述 + 应用/编辑按钮 + 溢出菜单；
 * `AppManagementMoreActionButton`/`AppPackageManageActionButton`/`LegadoMiuixActionButton` →
 * `AppOverflowMenuButton`+`AppMenuSheet` 与 `AppGhostButton`/`AppFilledButton`；
 * `AppRuleTextField` → `AppTextField`（TextFieldValue 改 String + `LaunchedEffect(value)` 同步）；
 * `ReaderSegmentedOptions` → Row/LazyRow + `AppChip(selected)`。
 * **已知降级**：`AppMenuSheet` 的 actions 无 danger 语义，删除项不再标红（仅文案「删除」）。
 * 业务逻辑全部保留：`ReaderTemplateOperationQueue` 串行化、`NonCancellable` 提交、导入/导出走 HandleFileContract、
 * 代码编辑走 CodeEditActivity + CacheManager 大文本、`applyTemplateNow` 的适用范围校验与重排通知。
 */
internal class ReaderTemplateDialog : V5DialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val maxSheetHeightFraction = .9f
    private val model by viewModels<ReaderTemplateViewModel>()

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    private var deleteTemplateTarget by mutableStateOf<EpubReaderTemplate?>(null)
    private var helpOpen by mutableStateOf(false)
    private var menuOpen by mutableStateOf(false)

    private val resourcePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val draftId = model.resourceDraftId
        val kind = model.resourceKind
        model.resourceDraftId = null
        model.resourceKind = null
        if (result.resultCode == RESULT_OK && kind != null && draftId != null) {
            val id = result.data?.getStringExtra("assetId") ?: return@registerForActivityResult
            val message = getString(R.string.reader_assets_template_done)
            model.perform(enqueue = true) {
                withContext(Dispatchers.IO) {
                    if (id.isNotEmpty()) require(ReaderAssets.store.find(id)?.kind == kind && ReaderAssets.store.file(id) != null) { "素材已丢失，请重新导入" }
                }
                model.draft?.takeIf { it.id == draftId }?.let { draft ->
                    model.draft = draft.copy(css = if (kind == "font") ReaderTemplateAssetStyle.font(draft.css, id.ifEmpty { null })
                    else ReaderTemplateAssetStyle.background(draft.css, id.ifEmpty { null }))
                    model.message = message
                }
            }
        }
    }

    /** The host connects this to the same pagination runtime used for reading. */
    interface PreviewHost {
        fun previewReaderTemplate(template: EpubReaderTemplate)
    }

    private val importFile = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        val importedMessage = getString(R.string.reader_template_imported)
        model.perform(enqueue = true) {
            // Complete the commit and notify the reader together, even if this sheet is dismissed.
            withContext(NonCancellable) {
                val previousId = ReadBookConfig.config.readerTemplateId
                withContext(Dispatchers.IO) {
                    uri.inputStream(context).getOrThrow().use(EpubReaderTemplatePackages::importPackage)
                }
                notifySelectionChange(previousId)
                model.refresh()
                model.message = importedMessage
            }
        }
    }

    private val exportFile = registerForActivityResult(HandleFileContract()) { result ->
        model.releaseExportFile()
        if (result.uri != null) {
            model.clearMessage()
            model.message = getString(R.string.export_success)
        }
    }

    private val codeEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val field = model.editingField
        model.editingField = null
        model.editorCacheKey?.let(CacheManager::deleteMemory)
        model.editorCacheKey = null
        if (result.resultCode == RESULT_OK && field != null) {
            val data = result.data
            val resultKey = data?.getStringExtra("cacheKey")
            val text = if (resultKey != null) {
                (CacheManager.getFromMemory(resultKey) as? String).also { CacheManager.deleteMemory(resultKey) }
            } else data?.getStringExtra("text")
            if (text != null) model.updateCode(field, text)
            else if (data?.hasExtra("cacheKey") == true) model.message = getString(R.string.reader_template_editor_unavailable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.load()
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val v5Colors = LocalSemanticColors.current
        val palette = rememberReaderPagePalette()
        val surface = Color(palette.surface)
        val panel = Color(palette.panel)
        val textColor = Color(palette.textColor)
        val secondaryText = Color(palette.secondaryTextColor)
        val accent = Color(palette.accentColor)
        val maxHeight = (LocalConfiguration.current.screenHeightDp * maxSheetHeightFraction)
            .toInt()
            .dp

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = surface,
            contentColor = textColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.reader_template_title),
                        style = visual.type.title,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AppOverflowMenuButton(
                        onClick = { menuOpen = true },
                        contentDescription = stringResource(R.string.more),
                    )
                }
                val draft = model.draft
                if (draft == null) {
                    LibraryContent(
                        panelColor = panel,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        accent = accent,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else {
                    key(draft.id) {
                        EditorContent(
                            draft = draft,
                            panelColor = panel,
                            textColor = textColor,
                            secondaryText = secondaryText,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                if (model.busy) {
                    Text(
                        text = stringResource(R.string.reader_template_busy),
                        style = visual.type.caption,
                        color = secondaryText
                    )
                }
                model.message?.let {
                    Text(
                        text = it,
                        style = visual.type.caption,
                        color = if (model.failed) v5Colors.error else secondaryText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        ReaderTemplateDialogs()
    }

    @Composable
    private fun SectionCard(
        title: String? = null,
        panelColor: Color,
        content: @Composable () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(panelColor)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = visual.type.caption,
                    color = LocalSemanticColors.current.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            content()
        }
    }

    @Composable
    private fun LibraryContent(
        panelColor: Color,
        textColor: Color,
        secondaryText: Color,
        accent: Color,
        modifier: Modifier
    ) {
        val visual = LocalVisualSpec.current
        val animationOptions = templatePageAnimationOptions()
        // Keep a single bounded lazy list in the reader sheet, as in the other package managers.
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val applied = model.templates.firstOrNull { it.id == model.appliedId }
            if (applied != null) {
                item(key = "page-animation") {
                    SectionCard(
                        title = stringResource(R.string.reader_template_page_animation),
                        panelColor = panelColor
                    ) {
                        Text(
                            text = applied.name,
                            style = visual.type.caption,
                            color = secondaryText
                        )
                        if (!applied.isScrolling) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(animationOptions.size) { index ->
                                    val (value, label) = animationOptions[index]
                                    AppChip(
                                        text = label,
                                        selected = value == (model.pageAnimation ?: -1).toString(),
                                        onClick = {
                                            if (!model.busy) changePageAnimation(value.toIntOrNull()?.takeIf { it >= 0 })
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(
                                if (applied.isScrolling) R.string.reader_template_scroll_locked
                                else R.string.reader_template_page_animation_hint
                            ),
                            style = visual.type.caption,
                            color = secondaryText
                        )
                    }
                }
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppGhostButton(
                        text = stringResource(R.string.reader_template_import),
                        modifier = Modifier.weight(1f),
                        onClick = ::openImport,
                    )
                    AppGhostButton(
                        text = stringResource(R.string.reader_template_backup),
                        modifier = Modifier.weight(1f),
                        onClick = { exportPackage(null) },
                    )
                }
            }
            items(model.templates, key = { "template:" + it.id }) { template ->
                val builtIn = EpubReaderTemplateStore.isBuiltIn(template.id)
                var itemMenuOpen by remember { mutableStateOf(false) }
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = template.name,
                            style = visual.type.title,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = template.description,
                            style = visual.type.caption,
                            color = secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (template.id == model.appliedId) {
                                AppFilledButton(
                                    text = stringResource(R.string.reader_template_selected),
                                    modifier = Modifier.weight(1f),
                                    onClick = { applyTemplate(template.id) },
                                )
                            } else {
                                AppGhostButton(
                                    text = stringResource(R.string.reader_template_apply),
                                    modifier = Modifier.weight(1f),
                                    onClick = { applyTemplate(template.id) },
                                )
                            }
                            AppGhostButton(
                                text = stringResource(
                                    if (builtIn) R.string.reader_template_copy_edit else R.string.reader_template_edit
                                ),
                                modifier = Modifier.weight(1f),
                                onClick = { editTemplate(template, asCopy = builtIn) },
                            )
                            AppOverflowMenuButton(
                                onClick = { itemMenuOpen = true },
                                contentDescription = stringResource(R.string.more),
                            )
                        }
                    }
                }
                if (itemMenuOpen) {
                    AppMenuSheet(
                        title = template.name,
                        actions = templateActions(template),
                        onDismiss = { itemMenuOpen = false },
                    )
                }
            }
        }
    }

    @Composable
    private fun templatePageAnimationOptions(): List<Pair<String, String>> = listOf(
        "-1" to stringResource(R.string.reader_template_page_animation_follow),
        PageAnim.coverPageAnim.toString() to stringResource(R.string.page_anim_cover),
        PageAnim.linkedCoverPageAnim.toString() to stringResource(R.string.page_anim_linked_cover),
        PageAnim.slidePageAnim.toString() to stringResource(R.string.page_anim_slide),
        PageAnim.simulationPageAnim.toString() to stringResource(R.string.page_anim_simulation),
        PageAnim.scrollPageAnim.toString() to stringResource(R.string.page_anim_scroll),
        PageAnim.noAnim.toString() to stringResource(R.string.page_anim_none)
    )

    private fun changePageAnimation(animation: Int?) {
        val id = model.appliedId
        model.perform {
            val previousPageAnim = ReadBook.pageAnim()
            withContext(Dispatchers.IO) { EpubReaderTemplateStore.savePageAnimation(id, animation) }
            model.pageAnimation = animation
            if (ReadBook.usesPageTemplate() && ReadBookConfig.config.readerTemplateId == id) {
                (activity as? ReadBookActivity)?.applyPageAnimationChange(previousPageAnim)
            }
        }
    }

    @Composable
    private fun EditorContent(
        draft: EpubReaderTemplate,
        panelColor: Color,
        textColor: Color,
        secondaryText: Color,
        modifier: Modifier
    ) {
        val visual = LocalVisualSpec.current
        val typeOptions = listOf(
            EpubReaderTemplate.TYPE_PAGED to stringResource(R.string.reader_template_type_paged),
            EpubReaderTemplate.TYPE_SCROLL to stringResource(R.string.reader_template_type_scroll)
        )
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Field(draft.name, R.string.reader_template_name, singleLine = true) { model.draft = draft.copy(name = it) }
            Field(draft.description, R.string.reader_template_description) { model.draft = draft.copy(description = it) }
            SectionCard(
                title = stringResource(R.string.reader_template_type),
                panelColor = panelColor
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    typeOptions.forEach { (value, label) ->
                        AppChip(
                            text = label,
                            selected = value == draft.type,
                            modifier = Modifier.weight(1f),
                            onClick = { if (!model.busy) model.changeType(value) },
                        )
                    }
                }
                if (draft.isScrolling) {
                    Text(
                        text = stringResource(R.string.reader_template_scroll_locked),
                        style = visual.type.caption,
                        color = secondaryText
                    )
                }
            }
            TemplateCodeField.entries.filter { field ->
                when (field) {
                    TemplateCodeField.FIRST, TemplateCodeField.OTHER -> !draft.isScrolling
                    TemplateCodeField.SCROLL -> draft.isScrolling
                    else -> true
                }
            }.forEach { field ->
                Action(field.label) { openCodeEditor(field) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppGhostButton(
                    text = stringResource(R.string.reader_assets_font_select),
                    modifier = Modifier.weight(1f),
                    onClick = { openResources("font") },
                )
                AppGhostButton(
                    text = stringResource(R.string.reader_assets_image_select),
                    modifier = Modifier.weight(1f),
                    onClick = { openResources("image") },
                )
            }
            AppGhostButton(
                text = stringResource(R.string.reader_assets_title),
                modifier = Modifier.fillMaxWidth(),
                onClick = { openResources(null) },
            )
            AppGhostButton(
                text = stringResource(R.string.reader_template_preview),
                modifier = Modifier.fillMaxWidth(),
                onClick = { model.draft?.let(::preview) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppGhostButton(
                    text = stringResource(R.string.reader_template_save),
                    modifier = Modifier.weight(1f),
                    onClick = { saveDraft(false) },
                )
                AppFilledButton(
                    text = stringResource(R.string.reader_template_save_apply),
                    modifier = Modifier.weight(1f),
                    onClick = { saveDraft(true) },
                )
            }
            AppGhostButton(
                text = stringResource(R.string.reader_template_cancel_edit),
                modifier = Modifier.fillMaxWidth(),
                onClick = ::cancelEdit,
            )
        }
    }

    @Composable
    private fun Action(label: Int, onClick: () -> Unit) {
        AppGhostButton(
            text = stringResource(label),
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (!model.busy) onClick() },
        )
    }

    @Composable
    private fun Field(
        value: String,
        label: Int,
        singleLine: Boolean = false,
        minLines: Int = 2,
        onChange: (String) -> Unit
    ) {
        var fieldValue by remember { mutableStateOf(value) }
        LaunchedEffect(value) {
            if (fieldValue != value) fieldValue = value
        }
        AppTextField(
            value = fieldValue,
            onValueChange = {
                if (!model.busy) {
                    fieldValue = it
                    onChange(it)
                }
            },
            placeholder = stringResource(label),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = 4,
        )
    }

    private fun templateActions(template: EpubReaderTemplate): List<Pair<String, () -> Unit>> = buildList {
        add(getString(R.string.reader_template_preview) to { preview(template) })
        if (!EpubReaderTemplateStore.isBuiltIn(template.id)) {
            add(getString(R.string.reader_template_copy_edit) to { editTemplate(template, asCopy = true) })
        }
        add(getString(R.string.reader_template_export) to { exportPackage(template) })
        add(getString(R.string.delete) to { confirmDelete(template) })
    }

    private fun libraryActions(): List<Pair<String, () -> Unit>> = buildList {
        add(getString(R.string.reader_assets_title) to { openResources(null) })
        if (model.hasHiddenBuiltIns && model.draft == null) {
            add(getString(R.string.reader_template_restore_builtins) to { restoreBuiltIns() })
        }
        add(getString(R.string.reader_template_help) to { showHelp() })
    }

    private fun openResources(kind: String?) {
        if (model.busy) return
        if (kind == null) {
            startActivity(Intent(requireContext(), ReaderAssetManageActivity::class.java))
            return
        }
        val draft = model.draft ?: return
        model.resourceDraftId = draft.id
        model.resourceKind = kind
        resourcePicker.launch(Intent(requireContext(), ReaderAssetManageActivity::class.java)
            .putExtra("pickKind", kind).putExtra("selectedId", ReaderTemplateAssetStyle.selected(draft.css,
                if (kind == "font") "font" else "background")))
    }

    private fun editTemplate(template: EpubReaderTemplate, asCopy: Boolean) {
        if (model.busy) return
        model.draft = if (asCopy) EpubReaderTemplateStore.copyOf(template) else template
        model.clearMessage()
    }

    private fun cancelEdit() {
        if (model.busy) return
        model.draft = null
        model.clearMessage()
    }

    private fun openImport() {
        if (model.busy) return
        runCatching {
            importFile.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.reader_template_import_title)
                allowExtensions = arrayOf("zip", "json")
            }
        }.onFailure(model::showError)
    }

    private fun exportPackage(template: EpubReaderTemplate?) {
        if (model.busy) return
        val cacheDir = requireContext().cacheDir
        val name = if (template == null) getString(R.string.reader_template_backup_file_name) else {
            template.name.trim().ifBlank { "reader-template" }
                .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").take(80) + ".zip"
        }
        model.perform {
            var prepared: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    File.createTempFile("reader-template-export-", ".zip", cacheDir).also {
                        prepared = it
                        it.outputStream().use { output ->
                            if (template == null) EpubReaderTemplatePackages.exportBackup(output)
                            else EpubReaderTemplatePackages.exportTemplate(template.id, output)
                        }
                    }
                }
                if (isAdded) {
                    model.pendingExportFile = file
                    exportFile.launch {
                        mode = HandleFileContract.EXPORT
                        showUploadUrl = false
                        fileData = HandleFileContract.FileData(name, file, EpubReaderTemplatePackages.mimeType)
                    }
                    prepared = null
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { prepared?.delete() }
            }
        }
    }

    private fun confirmDelete(template: EpubReaderTemplate) {
        if (model.busy) return
        deleteTemplateTarget = template
    }

    private fun restoreBuiltIns() {
        val restoredMessage = getString(R.string.reader_template_restored)
        model.perform {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { EpubReaderTemplateStore.restoreBuiltIns() }
                model.refresh()
                model.message = restoredMessage
            }
        }
    }

    private fun notifySelectionChange(previousId: String) {
        if (previousId != ReadBookConfig.config.readerTemplateId) {
            ReadConfigEvent.post(Typography, Relayout)
        }
    }

    private fun showHelp() {
        helpOpen = true
    }

    @Composable
    private fun ReaderTemplateDialogs() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density

        deleteTemplateTarget?.let { template ->
            val builtIn = EpubReaderTemplateStore.isBuiltIn(template.id)
            AppConfirmDialog(
                title = stringResource(R.string.delete),
                message = stringResource(
                    if (builtIn) R.string.reader_template_delete_builtin
                    else R.string.reader_template_delete_custom,
                    template.name
                ),
                confirmLabel = stringResource(R.string.delete),
                cancelLabel = stringResource(R.string.cancel),
                danger = true,
                onConfirm = {
                    deleteTemplateTarget = null
                    val deletedMessage = getString(R.string.reader_template_deleted)
                    model.perform {
                        withContext(NonCancellable) {
                            val previousId = ReadBookConfig.config.readerTemplateId
                            withContext(Dispatchers.IO) { EpubReaderTemplateStore.delete(template.id) }
                            notifySelectionChange(previousId)
                            model.refresh()
                            model.message = deletedMessage
                        }
                    }
                },
                onDismiss = { deleteTemplateTarget = null },
            )
        }

        if (menuOpen) {
            AppMenuSheet(
                title = stringResource(R.string.reader_template_title),
                actions = libraryActions(),
                onDismiss = { menuOpen = false },
            )
        }

        if (helpOpen) {
            AppDialog(onDismiss = { helpOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = stringResource(R.string.reader_template_help),
                        style = visual.type.title,
                        color = colors.onSurface,
                    )
                    Text(
                        text = listOf(
                            R.string.reader_template_scope,
                            R.string.reader_template_backup_help,
                            R.string.reader_template_code_note,
                            R.string.reader_template_author_help,
                            R.string.reader_template_scroll_author_help,
                            R.string.reader_template_javascript_help
                        ).joinToString("\n\n") { getString(it) } + "\n\n" +
                            getString(R.string.reader_assets_hint) +
                            "\n可在编辑页直接选择字体和背景，或从素材库复制图片地址、字体 CSS 放入页面代码。",
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { helpOpen = false },
                    )
                }
            }
        }
    }

    private fun openCodeEditor(field: TemplateCodeField) {
        val draft = model.draft ?: return
        val key = "reader_template_edit_" + System.nanoTime()
        val source = field.read(draft)
        CacheManager.putMemory(key, source)
        model.editorCacheKey = key
        model.editingField = field
        runCatching {
            codeEditor.launch(Intent(requireContext(), CodeEditActivity::class.java).apply {
                putExtra("cacheKey", key)
                putExtra("writable", true)
                putExtra("title", getString(field.label))
                putExtra("languageName", field.language)
            })
        }.onFailure {
            CacheManager.deleteMemory(key)
            model.editorCacheKey = null
            model.editingField = null
            model.showError(it)
        }
    }

    private fun saveDraft(apply: Boolean) {
        val draft = model.draft ?: return
        val savedMessage = getString(R.string.reader_template_saved)
        val scopeError = getString(R.string.reader_template_scope_error)
        model.perform {
            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) { EpubReaderTemplateStore.save(draft) }
                model.refresh()
                model.draft = null
                model.message = savedMessage
                if (apply) applyTemplateNow(saved.id, scopeError)
                else if (saved.id == ReadBookConfig.config.readerTemplateId && ReadBook.usesPageTemplate()) {
                    ReadConfigEvent.post(Typography, Relayout)
                }
            }
        }
    }

    private fun applyTemplate(id: String) {
        val scopeError = getString(R.string.reader_template_scope_error)
        model.perform { applyTemplateNow(id, scopeError) }
    }

    private suspend fun applyTemplateNow(id: String, scopeError: String) {
        require(ReadBookConfig.usingEpubLayout && ReadBook.book?.isEpub == false) { scopeError }
        require(id.isNotEmpty()) { scopeError }
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { EpubReaderTemplateStore.saveSelection(id) }
            ReadConfigEvent.post(Typography, Relayout)
            if (isAdded) dismissAllowingStateLoss()
        }
    }

    private fun preview(template: EpubReaderTemplate) {
        if (model.busy) return
        runCatching {
            val errors = template.validate()
            require(errors.isEmpty()) { errors.joinToString("\n") }
            val host = (parentFragment as? PreviewHost) ?: (activity as? PreviewHost)
            requireNotNull(host) { getString(R.string.reader_template_preview_unavailable) }.previewReaderTemplate(template)
        }.onFailure(model::showError)
    }

}

internal enum class TemplateCodeField(val label: Int, val language: String) {
    FIRST(R.string.reader_template_first_html, "text.html.basic"),
    OTHER(R.string.reader_template_other_html, "text.html.basic"),
    SCROLL(R.string.reader_template_scroll_html, "text.html.basic"),
    // The app bundles no CSS TextMate grammar. This registered lexical mode keeps the editor usable.
    CSS(R.string.reader_template_css, "source.js"),
    JAVASCRIPT(R.string.reader_template_javascript, "source.js");

    fun read(template: EpubReaderTemplate): String = when (this) {
        FIRST -> template.firstPageHtml
        OTHER -> template.otherPageHtml
        SCROLL -> template.scrollHtml
        CSS -> template.css
        JAVASCRIPT -> template.javascript
    }
}

internal class ReaderTemplateViewModel : ViewModel() {
    var templates by mutableStateOf<List<EpubReaderTemplate>>(emptyList())
    var appliedId by mutableStateOf(ReadBookConfig.config.readerTemplateId)
    var pageAnimation by mutableStateOf<Int?>(null)
    var hasHiddenBuiltIns by mutableStateOf(false)
    var draft by mutableStateOf<EpubReaderTemplate?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    var editingField: TemplateCodeField? = null
    var editorCacheKey: String? = null
    var pendingExportFile: File? = null
    var resourceDraftId: String? = null
    var resourceKind: String? = null
    private var loaded = false
    private val operations by lazy {
        ReaderTemplateOperationQueue(
            scope = viewModelScope,
            onBusyChanged = { busy = it },
            onStart = ::clearMessage,
            onError = ::showError
        )
    }

    fun load() {
        if (!loaded) { loaded = true; perform { refresh() } }
    }

    suspend fun refresh() {
        withContext(NonCancellable) {
            val previousId = ReadBookConfig.config.readerTemplateId
            val library = withContext(Dispatchers.IO) {
                if (ReadBook.usesPageTemplate()) EpubReaderTemplateStore.ensureReaderSelection()
                EpubReaderTemplateStore.list() to EpubReaderTemplateStore.hasHiddenBuiltIns()
            }
            appliedId = ReadBookConfig.config.readerTemplateId
            pageAnimation = EpubReaderTemplateStore.pageAnimation(appliedId)
            if (previousId != appliedId) ReadConfigEvent.post(Typography, Relayout)
            templates = library.first
            hasHiddenBuiltIns = library.second
        }
    }
    fun clearMessage() { message = null; failed = false }
    fun showError(error: Throwable) { message = error.localizedMessage ?: error.javaClass.simpleName; failed = true }

    fun perform(enqueue: Boolean = false, action: suspend () -> Unit) {
        operations.submit(enqueue, action)
    }

    fun updateCode(field: TemplateCodeField, source: String) {
        val current = draft ?: return
        draft = when (field) {
            TemplateCodeField.FIRST -> current.copy(firstPageHtml = source)
            TemplateCodeField.OTHER -> current.copy(otherPageHtml = source)
            TemplateCodeField.SCROLL -> current.copy(scrollHtml = source)
            TemplateCodeField.CSS -> current.copy(css = source)
            TemplateCodeField.JAVASCRIPT -> current.copy(javascript = source)
        }
    }

    fun changeType(type: String) {
        val current = draft ?: return
        if (type == current.type) return
        draft = current.copy(
            type = type,
            schemaVersion = if (type == EpubReaderTemplate.TYPE_SCROLL) EpubReaderTemplate.SCROLL_SCHEMA_VERSION else EpubReaderTemplate.SCHEMA_VERSION,
            scrollHtml = current.scrollHtml.ifBlank { "<main data-reader-flow=\"body\"></main>" },
            firstPageHtml = current.firstPageHtml.ifBlank { "<main data-reader-flow=\"body\" style=\"height:100%\"></main>" },
            otherPageHtml = current.otherPageHtml.ifBlank { "<main data-reader-flow=\"body\" style=\"height:100%\"></main>" }
        )
    }

    fun releaseExportFile() {
        val file = pendingExportFile ?: return
        pendingExportFile = null
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { file.delete() }
    }

    override fun onCleared() {
        editorCacheKey?.let(CacheManager::deleteMemory)
        pendingExportFile?.delete()
        super.onCleared()
    }
}
