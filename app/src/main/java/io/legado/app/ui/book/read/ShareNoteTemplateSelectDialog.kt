package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.config.ShareNoteTemplatePreview
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 摘录分享模板选择弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容由旧 AppDialogFrame + AppManagementCard + rememberAppManagementPalette 换为
 * V5 AppDialog + AppCard + V5 令牌；预览组件 `ShareNoteTemplatePreview` 随本次一并去掉 palette 参数
 * （它是该组件唯一使用方，见台账）。
 * 「记住上次选中的模板」「预览加载失败只记日志不崩」等语义不变。
 */
internal class ShareNoteTemplateSelectDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private var onSelected: ((ShareNoteTemplateManager.Entry) -> Unit)? = null
    private var onManage: (() -> Unit)? = null

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val appContext = requireContext().applicationContext
        var entries by remember { mutableStateOf<List<ShareNoteTemplateManager.Entry>>(emptyList()) }
        var previews by remember { mutableStateOf<Map<String, File>>(emptyMap()) }

        LaunchedEffect(Unit) {
            val loaded = try {
                withContext(Dispatchers.IO) { ShareNoteTemplateManager.loadEntries() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Share note template select load failed\n${e.localizedMessage}", e)
                return@LaunchedEffect
            }
            val last = ShareNoteTemplateManager.lastDirName()
            entries = loaded.sortedBy { if (it.dirName == last) 0 else 1 }
            loaded.forEach { entry ->
                val file = try {
                    ShareNoteImageRenderer.renderPreview(appContext, entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put(
                        "Share note template select preview failed: ${entry.dirName}\n${e.localizedMessage}",
                        e
                    )
                    null
                }
                file?.let {
                    previews = previews + (entry.dirName to it)
                }
            }
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = "选择分享模板",
                    style = visual.type.title,
                    color = colors.onSurface
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(entries, key = { it.dirName }) { entry ->
                        AppCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                dismissAllowingStateLoss()
                                ShareNoteTemplateManager.rememberLast(entry)
                                onSelected?.invoke(entry)
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ShareNoteTemplatePreview(
                                    previewFile = previews[entry.dirName]
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.meta.name,
                                        color = colors.onSurface,
                                        style = visual.type.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${entry.meta.canvasLabel()} · ${entry.meta.sizeLabel()}",
                                        color = colors.onSurfaceVariant,
                                        style = visual.type.caption,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = "管理模板",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            dismissAllowingStateLoss()
                            onManage?.invoke()
                        },
                    )
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                }
            }
        }
    }

    companion object {
        fun create(
            onSelected: (ShareNoteTemplateManager.Entry) -> Unit,
            onManage: () -> Unit
        ): ShareNoteTemplateSelectDialog {
            return ShareNoteTemplateSelectDialog().apply {
                this.onSelected = onSelected
                this.onManage = onManage
            }
        }
    }
}
