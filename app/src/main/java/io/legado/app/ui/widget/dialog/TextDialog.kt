package io.legado.app.ui.widget.dialog

import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.delay

/**
 * 文本查看弹窗（V5 基座版）：支持 Markdown / HTML / 纯文本三种模式。
 *
 * 尺寸由旧 `AppDialogSize.Wide` 映射为 widthFraction 0.98f / maxWidthDp 760，
 * `dialogHeight = WRAP_CONTENT` 与旧值一致（V5 默认即 WRAP_CONTENT，显式写出以便对照）。
 * 内容由旧 AppDialogFrame + LegadoMiuixActionButton 换为 V5 AppDialog + AppGhostButton/AppFilledButton；
 * Markdown/HTML 仍走 Markwon 与 HtmlCompat 的 AndroidView(TextView)（含链接可点、可选中复制、关闭文本分类器）。
 * **行为保留**：`isCancelable = false`、构造可传 `time` 倒计时（倒计时期间隐藏关闭按钮），
 * `autoClose` 到点自动关闭、TEXT 模式且可关闭时才显示「复制全文」、setOnDismissListener 回调。
 */
internal class TextDialog() : V5DialogFragment() {

    enum class Mode {
        MD, HTML, TEXT
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT,
        time: Long = 0,
        autoClose: Boolean = false
    ) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("content", IntentData.put(content))
            putString("mode", mode.name)
            putLong("time", time)
        }
        isCancelable = false
        this.autoClose = autoClose
    }

    /** 旧 AppDialogSize.Wide(widthFraction = 0.98f, maxWidthDp = 760) */
    override val widthFraction: Float = 0.98f
    override val maxWidthDp: Int = 760
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private var autoClose: Boolean = false
    private var onDismissListener: DialogInterface.OnDismissListener? = null

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        this.onDismissListener = listener
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val args = arguments ?: Bundle()
        val title = args.getString("title").orEmpty()
        val content = IntentData.get(args.getString("content")) ?: ""
        val mode = args.getString("mode") ?: Mode.TEXT.name
        val initialTime = args.getLong("time", 0L)

        var countdownSeconds by remember {
            mutableIntStateOf((initialTime / 1000).toInt())
        }
        var canClose by remember { mutableStateOf(initialTime <= 0) }

        LaunchedEffect(initialTime) {
            if (initialTime > 0) {
                var remaining = initialTime
                while (remaining > 0) {
                    delay(1000)
                    remaining -= 1000
                    countdownSeconds = (remaining / 1000).toInt()
                }
                canClose = true
                if (autoClose) {
                    dismissAllowingStateLoss()
                }
            }
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement
                    .spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = title,
                    style = visual.type.title,
                    color = colors.onSurface
                )
                TextDialogContent(
                    content = content,
                    mode = mode
                )
                // 复制全文（如崩溃日志），方便用户直接粘贴反馈
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (mode == Mode.TEXT.name && canClose) {
                        AppGhostButton(
                            text = stringResource(R.string.copy_text),
                            modifier = Modifier.weight(1f),
                            onClick = { requireContext().sendToClip(content) },
                        )
                    }
                    if (countdownSeconds > 0) {
                        Text(
                            text = "${countdownSeconds}s",
                            color = colors.primary,
                            style = visual.type.body,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    if (canClose) {
                        AppGhostButton(
                            text = stringResource(R.string.close),
                            modifier = Modifier.weight(1f),
                            onClick = { dismissAllowingStateLoss() },
                        )
                    }
                    AppFilledButton(
                        text = stringResource(R.string.edit_content),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cacheKey = "code_text_${System.currentTimeMillis()}"
                            CacheManager.putMemory(cacheKey, content)
                            startActivity<CodeEditActivity> {
                                putExtra("cacheKey", cacheKey)
                                putExtra("title", title)
                                putExtra(
                                    "languageName",
                                    if (mode == Mode.MD.name) {
                                        "text.html.markdown"
                                    } else {
                                        "text.html.basic"
                                    }
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextDialogContent(
    content: String,
    mode: String
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxContentHeight = (screenHeight * 0.72f).coerceIn(200.dp, 600.dp)

    when (mode) {
        TextDialog.Mode.MD.name -> {
            MarkdownContent(
                content = content,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
            )
        }

        TextDialog.Mode.HTML.name -> {
            HtmlContent(
                content = content,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
            )
        }

        else -> {
            val displayText = if (content.length >= 32 * 1024) {
                content.take(32 * 1024) + "\n\n数据太大，无法全部显示…"
            } else {
                content
            }
            val scrollState = rememberScrollState()
            val colors = LocalSemanticColors.current
            val visual = LocalVisualSpec.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(scrollState)
            ) {
                SelectionContainer {
                    Text(
                        text = displayText,
                        color = colors.onSurfaceVariant,
                        style = visual.type.body
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalSemanticColors.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(GlideImagesPlugin.create(Glide.with(context)))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }
    val textColorArgb = remember(colors) { colors.onSurfaceVariant.toArgb() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setPadding(12, 12, 12, 12)
                movementMethod = LinkMovementMethod.getInstance()
                isFocusable = true
                setTextIsSelectable(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setTextClassifier(TextClassifier.NO_OP)
                }
                includeFontPadding = true
            }
        },
        update = { textView ->
            textView.setTextColor(textColorArgb)
            textView.textSize = 14f
            textView.typeface = context.uiTypeface()
            markwon.setMarkdown(textView, content)
        }
    )
}

@Composable
private fun HtmlContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalSemanticColors.current
    val spanned = remember(content) {
        HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }
    val textColorArgb = remember(colors) { colors.onSurfaceVariant.toArgb() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setPadding(12, 12, 12, 12)
                movementMethod = LinkMovementMethod.getInstance()
                isFocusable = true
                setTextIsSelectable(true)
                includeFontPadding = true
            }
        },
        update = { textView ->
            textView.setTextColor(textColorArgb)
            textView.textSize = 14f
            textView.typeface = context.uiTypeface()
            textView.text = spanned
        }
    )
}
