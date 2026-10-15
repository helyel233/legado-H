package io.legado.app.ui.book.read

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.PackageSvgResourceResolver
import io.legado.app.model.ImageProvider
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.SvgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 段评气泡快速切换（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容由旧 AppDialogFrame + AppManagementListRow + AppManagementCard + LegadoMiuixActionButton
 * 换为 V5 AppDialog + AppRowWithSwitch + AppCard + AppGhostButton。
 * **已知降级**：`AppManagementListRow` 原带 subtitle（「识别正文图片形式的段评入口」），
 * 换成 `AppRowWithSwitch` 后不再显示（该组件未确认支持副标题参数）。
 * 业务保留：仅读本地+缓存远端快照（打开时不走网络）、切换开关后 `ImageProvider.clear()` +
 * `refreshParagraphRuleLayout()`、气泡预览 SVG 渲染（按日夜取色 + 资源解析）与来源文案。
 */
internal class BubbleQuickSwitchDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private var onSelected: ((BubblePackageManager.Entry) -> Unit)? = null
    private var onManage: (() -> Unit)? = null

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val context = LocalContext.current
        var entries by remember { mutableStateOf<List<BubblePackageManager.Entry>>(emptyList()) }
        var forceSoftwareBubble by remember {
            mutableStateOf(AppConfig.forceSoftwareParagraphBubble)
        }
        val previews = remember { mutableStateMapOf<String, Bitmap>() }
        val activeDirName = BubblePackageManager.activeDirName()

        LaunchedEffect(Unit) {
            // Local + cached remote only; no network round-trip on dialog open.
            val loaded = BubblePackageManager.loadEntrySnapshot().mergedEntries
            entries = loaded
            loaded.forEach { entry ->
                withContext(Dispatchers.Default) {
                    bubblePreviewBitmap(context.resources.displayMetrics.density, entry)
                }?.let { bitmap ->
                    previews[entry.dirName] = bitmap
                }
            }
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = "选择段评气泡",
                    style = visual.type.title,
                    color = colors.onSurface
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item(key = "forceSoftwareBubble") {
                        AppRowWithSwitch(
                            title = "强制使用软件气泡",
                            checked = forceSoftwareBubble,
                            onCheckedChange = { checked ->
                                forceSoftwareBubble = checked
                                AppConfig.forceSoftwareParagraphBubble = checked
                                ImageProvider.clear()
                                (activity as? ReadBookActivity)?.refreshParagraphRuleLayout()
                            },
                            card = false,
                        )
                    }
                    items(entries, key = { it.dirName }) { entry ->
                        val active = entry.dirName == activeDirName
                        AppCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dismissAllowingStateLoss()
                                    onSelected?.invoke(entry)
                                },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BubblePreview(bitmap = previews[entry.dirName])
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.config.name,
                                        color = if (active) colors.primary else colors.onSurface,
                                        style = visual.type.title,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (active) "已应用" else bubbleSourceText(entry.source),
                                        color = colors.onSurfaceVariant,
                                        style = visual.type.caption,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = "气泡管理",
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
        internal fun create(
            onSelected: (BubblePackageManager.Entry) -> Unit,
            onManage: () -> Unit
        ): BubbleQuickSwitchDialog {
            return BubbleQuickSwitchDialog().apply {
                this.onSelected = onSelected
                this.onManage = onManage
            }
        }
    }
}

@Composable
private fun BubblePreview(bitmap: Bitmap?) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(visual.shape(ShapeKind.COVER))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "气泡",
                color = colors.onSurfaceVariant,
                style = visual.type.caption
            )
        }
    }
}

private fun bubblePreviewBitmap(density: Float, entry: BubblePackageManager.Entry): Bitmap? {
    return runCatching {
        val config = entry.config
        val color = when {
            AppConfig.isNightTheme -> config.nightNormalColor
            else -> config.dayNormalColor
        }?.takeIf { it.isNotBlank() } ?: BubblePackageManager.DEFAULT_NORMAL_COLOR
        val svg = config.svgTemplate
            .replace("\${color}", color)
            .replace("\${num}", "12")
        val sidePx = (58 * density).toInt()
        ByteArrayInputStream(svg.toByteArray()).use { input ->
            val root = entry.localDir
            if (root == null) {
                SvgUtils.createBitmap(input, sidePx, sidePx)
            } else {
                SvgUtils.createBitmap(
                    input,
                    sidePx,
                    sidePx,
                    PackageSvgResourceResolver(root, config.resources, sidePx, sidePx)
                )
            }
        }
    }.getOrNull()
}

private fun bubbleSourceText(source: BubblePackageManager.Source): String {
    return when (source) {
        BubblePackageManager.Source.BUILTIN -> "内置"
        BubblePackageManager.Source.LOCAL -> "本地"
        BubblePackageManager.Source.REMOTE -> "云端"
        BubblePackageManager.Source.BOTH -> "本地 + 云端"
    }
}
