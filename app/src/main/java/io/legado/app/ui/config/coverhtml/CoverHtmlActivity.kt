package io.legado.app.ui.config.coverhtml

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.CoverHtmlTemplateConfig
import io.legado.app.help.glide.HtmlCoverRenderer
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * HTML 封面模板管理页。（移植自 Max，界面按 H 的 Compose 风格实现）
 *
 * 支持模板列表、选用、新建、编辑（复用 CodeEditActivity）、重命名、预览与删除；
 * 模板变更后清空渲染缓存并刷新书架。
 */
class CoverHtmlActivity : AppCompatActivity() {

    private var versionState = mutableIntStateOf(0)
    private var editingTemplateId: String? = null
    private var editingCursorPosition: Int = 0

    private val templateEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra("text")?.let { text ->
                    editingCursorPosition =
                        result.data?.getIntExtra("cursorPosition", text.length) ?: text.length
                    editingTemplateId?.let { id ->
                        val template = CoverHtmlTemplateConfig.getTemplateById(id) ?: return@let
                        template.htmlCode = text
                        CoverHtmlTemplateConfig.updateTemplate(template)
                        HtmlCoverRenderer.clearCache()
                        versionState.intValue++
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content {
            CoverHtmlTemplateScreen(
                version = versionState.intValue,
                onEdit = ::editTemplate
            )
        }
    }

    private fun editTemplate(template: CoverHtmlTemplateConfig.Template) {
        editingTemplateId = template.id
        templateEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", template.htmlCode)
            putExtra("title", template.name)
            putExtra("cursorPosition", editingCursorPosition.coerceIn(0, template.htmlCode.length))
        })
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CoverHtmlActivity::class.java))
        }
    }
}

private sealed interface OverlaySpec {
    data class TextInput(
        val title: String,
        val initial: String,
        val onConfirm: (String) -> Unit
    ) : OverlaySpec

    data class Confirm(
        val title: String,
        val message: String,
        val onConfirm: () -> Unit
    ) : OverlaySpec

    data class Preview(val bitmap: Bitmap?) : OverlaySpec
}

@Composable
private fun CoverHtmlTemplateScreen(
    version: Int,
    onEdit: (CoverHtmlTemplateConfig.Template) -> Unit
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val renameTitleStr = stringResource(R.string.cover_html_template_name)
    val deleteTitleStr = stringResource(R.string.draw)
    val keepOneStr = stringResource(R.string.cover_html_keep_one_template)
    val newTemplatePrefix = stringResource(R.string.cover_html_template)
    var versionTick by remember(version) { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<OverlaySpec?>(null) }

    fun refresh() {
        HtmlCoverRenderer.clearCache()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
        versionTick++
    }

    val templates = remember(version, versionTick) {
        CoverHtmlTemplateConfig.templateList.toList()
    }
    val selectedId = templates.firstOrNull { it.isSelected }?.id

    HostV5Page(
        title = newTemplatePrefix,
        onBack = { (context as? AppCompatActivity)?.finish() },
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = density.blockGap / 2),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
            itemsIndexed(
                items = templates,
                key = { _, template -> template.id }
            ) { _, template ->
                TemplateRow(
                    template = template,
                    selected = template.id == selectedId,
                    onEdit = { onEdit(template) },
                    onSelect = {
                        CoverHtmlTemplateConfig.setSelectedTemplate(template.id)
                        refresh()
                    },
                    onRename = {
                        overlay = OverlaySpec.TextInput(
                            title = renameTitleStr,
                            initial = template.name
                        ) { name ->
                            if (name.isNotBlank()) {
                                template.name = name
                                CoverHtmlTemplateConfig.updateTemplate(template)
                                versionTick++
                            }
                        }
                    },
                    onDelete = {
                        if (CoverHtmlTemplateConfig.templateList.size <= 1) {
                            context.toastOnUi(keepOneStr)
                        } else {
                            overlay = OverlaySpec.Confirm(
                                title = deleteTitleStr,
                                message = template.name
                            ) {
                                CoverHtmlTemplateConfig.deleteTemplateById(template.id)
                                refresh()
                            }
                        }
                    },
                    onPreview = {
                        scope.launch {
                            overlay = OverlaySpec.Preview(
                                HtmlCoverRenderer.load("斗破苍穹", "天蚕土豆")
                            )
                        }
                    }
                )
            }
        }
            AppFilledButton(
                text = "+ " + stringResource(R.string.add),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
            ) {
                val template = CoverHtmlTemplateConfig.Template(
                    id = CoverHtmlTemplateConfig.generateId(),
                    name = "$newTemplatePrefix " +
                        (CoverHtmlTemplateConfig.templateList.size + 1),
                    htmlCode = ""
                )
                CoverHtmlTemplateConfig.addTemplate(template)
                versionTick++
                onEdit(template)
            }
        }
    }

    when (val spec = overlay) {
        is OverlaySpec.TextInput -> TextInputOverlay(
            title = spec.title,
            initial = spec.initial,
            onConfirm = {
                spec.onConfirm(it)
                overlay = null
            },
            onDismiss = { overlay = null }
        )

        is OverlaySpec.Confirm -> ConfirmOverlay(
            title = spec.title,
            message = spec.message,
            onConfirm = {
                spec.onConfirm()
                overlay = null
            },
            onDismiss = { overlay = null }
        )

        is OverlaySpec.Preview -> PreviewOverlay(
            bitmap = spec.bitmap,
            onDismiss = { overlay = null }
        )

        null -> Unit
    }
}

@Composable
private fun TemplateRow(
    template: CoverHtmlTemplateConfig.Template,
    selected: Boolean,
    onEdit: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = density.screenPadding),
        onClick = onSelect
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.primary else colors.surfaceVariant)
                        .border(
                            width = if (selected) 0.dp else 2.dp,
                            color = if (selected) colors.primary else colors.outline,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.onPrimary)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = density.blockGap / 2)
                ) {
                    Text(
                        text = template.name,
                        style = visual.type.body,
                        color = colors.onSurface
                    )
                    Text(
                        text = if (template.htmlCode.isBlank()) {
                            stringResource(R.string.cover_html_empty)
                        } else {
                            stringResource(R.string.html_code) + " (${template.htmlCode.length})"
                        },
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                modifier = Modifier.fillMaxWidth()
            ) {
                AppGhostButton(stringResource(R.string.edit), Modifier.weight(1f), onClick = onEdit)
                AppGhostButton(stringResource(R.string.cover_html_preview), Modifier.weight(1f), onClick = onPreview)
                AppGhostButton(stringResource(R.string.rename), Modifier.weight(1f), onClick = onRename)
                AppGhostButton(stringResource(R.string.delete), Modifier.weight(1f), onClick = onDelete)
            }
        }
    }
}

@Composable
private fun TextInputOverlay(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AppInputDialog(
        title = title,
        value = text,
        onValueChange = { text = it },
        confirmLabel = stringResource(R.string.ok),
        cancelLabel = stringResource(R.string.cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun ConfirmOverlay(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppConfirmDialog(
        title = title,
        message = message,
        confirmLabel = stringResource(R.string.ok),
        cancelLabel = stringResource(R.string.cancel),
        danger = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun PreviewOverlay(
    bitmap: Bitmap?,
    onDismiss: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap)
        ) {
            Text(
                text = stringResource(R.string.cover_html_preview),
                style = visual.type.title,
                color = colors.onSurface
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = stringResource(R.string.cover_html_empty),
                    style = visual.type.body,
                    color = colors.onSurfaceVariant
                )
            }
            AppGhostButton(
                text = stringResource(R.string.close),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss
            )
        }
    }
}
