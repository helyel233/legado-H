package io.legado.app.ui.config.coverhtml

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityCoverHtmlBinding
import io.legado.app.help.config.CoverHtmlTemplateConfig
import io.legado.app.help.glide.HtmlCoverRenderer
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * HTML 封面模板管理页。（移植自 Max，界面按 H 的 Compose 风格实现）
 *
 * 支持模板列表、选用、新建、编辑（复用 CodeEditActivity）、重命名、预览与删除；
 * 模板变更后清空渲染缓存并刷新书架。
 */
class CoverHtmlActivity : BaseActivity<ActivityCoverHtmlBinding>() {

    override val binding by viewBinding(ActivityCoverHtmlBinding::inflate)

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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeView.setContent {
            LegadoComposeTheme {
                CoverHtmlTemplateScreen(
                    version = versionState.intValue,
                    onEdit = ::editTemplate
                )
            }
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
    val palette = rememberAppManagementPalette()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.settings.page)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
        FilledTonalButton(
            onClick = {
                val template = CoverHtmlTemplateConfig.Template(
                    id = CoverHtmlTemplateConfig.generateId(),
                    name = "$newTemplatePrefix " +
                        (CoverHtmlTemplateConfig.templateList.size + 1),
                    htmlCode = ""
                )
                CoverHtmlTemplateConfig.addTemplate(template)
                versionTick++
                onEdit(template)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(text = "+ " + stringResource(R.string.add))
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
    val palette = rememberAppManagementPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Color(palette.settings.row), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text(
                    text = template.name,
                    fontSize = 16.sp,
                    color = palette.settings.primaryText
                )
                Text(
                    text = if (template.htmlCode.isBlank()) {
                        stringResource(R.string.cover_html_empty)
                    } else {
                        stringResource(R.string.html_code) + " (${template.htmlCode.length})"
                    },
                    fontSize = 12.sp,
                    color = palette.settings.secondaryText
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onEdit) {
                Text(text = stringResource(R.string.edit))
            }
            TextButton(onClick = onPreview) {
                Text(text = stringResource(R.string.cover_html_preview))
            }
            TextButton(onClick = onRename) {
                Text(text = stringResource(R.string.rename))
            }
            TextButton(onClick = onDelete) {
                Text(text = stringResource(R.string.delete))
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(text = stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ConfirmOverlay(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PreviewOverlay(
    bitmap: Bitmap?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.cover_html_preview)) },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(text = stringResource(R.string.cover_html_empty))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        }
    )
}
