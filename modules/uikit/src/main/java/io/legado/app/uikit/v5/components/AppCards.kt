package io.legado.app.uikit.v5.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-1 基础容器：AppCard / AppPanel / AppGroup。
 *
 * 页面只能说"用哪一档材质""要不要可点"，**不接受任何颜色 / 圆角 / 阴影参数**。
 * 按压反馈（微缩放）由组件内置，页面不需要自己写。
 */

/** 卡片：可点（带按压微缩放）或不点，默认 E2 浮起。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    level: MaterialLevel = MaterialLevel.RAISED,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visual = LocalVisualSpec.current
    val pressedScale = if (onClick != null && visual.motion.allowMovement) 0.98f else 1f
    val scale by animateFloatAsState(
        targetValue = pressedScale,
        animationSpec = visual.motion.springSpec(),
        label = "cardPress",
    )

    val interaction = remember { MutableInteractionSource() }
    AppSurface(
        level = level,
        modifier = modifier
            .scale(scale)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            )
            .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** 面板：区域容器（不响应点击），默认 E2。 */
@Composable
fun AppPanel(
    modifier: Modifier = Modifier,
    level: MaterialLevel = MaterialLevel.RAISED,
    content: @Composable ColumnScope.() -> Unit,
) = AppCard(modifier = modifier, level = level, onClick = null, content = content)

/**
 * 分组：组标题 + 内容 + 可选尾部动作。
 * 组标题走 label 字阶 + 弱色 —— 排版层级从这里来，页面不用自己定字号。
 */
@Composable
fun AppGroup(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = visual.density.screenPadding / 2,
                    end = visual.density.screenPadding / 2,
                    bottom = visual.density.blockGap / 2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = visual.type.label,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        content()
    }
}
