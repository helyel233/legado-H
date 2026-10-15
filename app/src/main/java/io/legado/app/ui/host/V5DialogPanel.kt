package io.legado.app.ui.host

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * DialogFragment 内容面板（V5 版，替代旧 `AppDialogFrame`）。
 *
 * `V5DialogFragment` 的窗口已提供尺寸与主题环境，本件只负责卡片形态：
 * 实色卡（M3 Surface，玻璃材质在墨水屏上发灰发虚故不用于弹窗卡片）
 * + 标题 + 可选说明 + 可滚动内容区（max 520dp，**scrollContent=false 时内容须自带滚动**）
 * + 底部动作行。
 * 参数与旧 `AppDialogFrame` 同名同义（title/modifier/message/scrollContent/content/actions），
 * 迁移即纯改名；旧件无调用方后随旧库删除。
 */
@Composable
internal fun V5DialogPanel(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    scrollContent: Boolean = true,
    content: @Composable () -> Unit,
    actions: (@Composable () -> Unit)? = null
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        shape = visual.shape(ShapeKind.CARD),
        color = colors.surface,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = title,
            style = visual.type.title,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = visual.density.screenPadding)
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = visual.type.body,
                color = colors.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = visual.density.blockGap / 2)
                    .padding(horizontal = visual.density.screenPadding)
            )
        }
        Spacer(modifier = Modifier.height(visual.density.blockGap))
        if (scrollContent) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = visual.density.screenPadding)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = visual.density.screenPadding)
                    .heightIn(max = 520.dp)
            ) {
                content()
            }
        }
        Spacer(modifier = Modifier.height(visual.density.blockGap))
        if (actions != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = visual.density.screenPadding)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}
