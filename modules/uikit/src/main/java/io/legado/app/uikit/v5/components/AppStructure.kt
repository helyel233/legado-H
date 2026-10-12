package io.legado.app.uikit.v5.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * 结构零件：折叠分组、任务队列行。
 * 编辑表单/设置页的「分类可折叠」，下载/备份/导出队列的「一行一任务」。
 */

/**
 * 可折叠分组：标题行 + 点击展开/收起（箭头旋转），展开态可跨重组保存。
 * [AppGroup] 的折叠变体 —— 编辑页字段多时按语义折叠，减少滚动长度。
 */
@Composable
fun AppCollapsibleGroup(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(horizontal = density.rowPaddingH, vertical = density.rowPaddingV / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = AppIcons.Chevron,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier
                    .size(density.iconSize * 0.72f)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(visual.motion.shortSpec()) + fadeIn(visual.motion.shortSpec()),
            exit = shrinkVertically(visual.motion.shortSpec()) + fadeOut(visual.motion.shortSpec()),
        ) {
            Column(content = content)
        }
    }
}

/**
 * 任务队列行：标题 + 摘要 + 确定进度条/状态文案 + 取消/重试动作。
 * 下载、备份、导出、WebDav 队列等「一行一任务」的统一形态。
 * [progress] 非 null 时显示进度条；null 时显示不确定形态。
 */
@Composable
fun AppTaskRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    /** 0f..1f；null = 不确定动画 */
    progress: Float? = null,
    statusLabel: String? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = density.rowPaddingH, vertical = density.rowPaddingV),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = visual.type.body, color = colors.onSurface)
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (statusLabel != null) {
                Text(
                    text = statusLabel,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                )
            }
            if (onCancel != null) {
                Text(
                    text = "✕",
                    style = visual.type.body,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = density.blockGap / 2)
                        .clip(visual.shape(ShapeKind.BUTTON))
                        .clickable(onClick = onCancel)
                        .padding(6.dp),
                )
            }
            if (onRetry != null) {
                Text(
                    text = "↻",
                    style = visual.type.body,
                    color = colors.primary,
                    modifier = Modifier
                        .padding(start = density.blockGap / 2)
                        .clip(visual.shape(ShapeKind.BUTTON))
                        .clickable(onClick = onRetry)
                        .padding(6.dp),
                )
            }
        }
        if (progress != null) {
            AppProgressBar(progress = progress)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 2.dp),
            )
        }
    }
}
