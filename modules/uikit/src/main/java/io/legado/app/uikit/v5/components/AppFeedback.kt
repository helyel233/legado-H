package io.legado.app.uikit.v5.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-4 反馈零件：骨架屏 / 空态 / 错误态 / 进度。
 *
 * "加载是转圈、空态是一行字、错误是个 Toast" 的旧观感在这里终结：
 * 每个页面都应该有设计过的状态，全部由这几个零件统一提供。
 */

/** 骨架块：shimmer 流光。形状跟随内容（用 ShapeKind 声明），减少动效时静态。 */
@Composable
fun AppSkeleton(
    modifier: Modifier = Modifier,
    kind: ShapeKind = ShapeKind.TAG,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current

    if (!visual.motion.allowMovement) {
        Box(modifier.clip(visual.shape(kind)).background(colors.surfaceVariant))
        return
    }

    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )

    Box(
        modifier = modifier
            .clip(visual.shape(kind))
            .drawBehind {
                drawRect(colors.surfaceVariant)
                val bandWidth = size.width * 0.55f
                val x = sweep * size.width
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            colors.onSurface.copy(alpha = 0.09f),
                            Color.Transparent,
                        ),
                        start = Offset(x, 0f),
                        end = Offset(x + bandWidth, size.height),
                    ),
                )
            },
    )
}

/**
 * 空态：插画槽 + 标题 + 人话 + 行动按钮。
 * 插画由调用方提供（保持主题色语义），标题/文案/按钮全部走全局字阶与形状。
 */
@Composable
fun AppEmpty(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    illustration: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = visual.density.screenPadding * 2,
                vertical = visual.density.screenPadding * 2,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2),
    ) {
        illustration?.invoke()
        Text(
            text = title,
            style = visual.type.title,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(visual.density.blockGap / 2))
            AppFilledButton(actionLabel, onClick = onAction)
        }
    }
}

/** 错误态：可重试。 */
@Composable
fun AppError(
    title: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppEmpty(
        title = title,
        supporting = supporting,
        modifier = modifier,
        illustration = {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.error.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Error,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        actionLabel = if (onRetry != null) retryLabel else null,
        onAction = onRetry,
    )
}

/** 不确定加载：环形读 primary。 */
@Composable
fun AppLoading(modifier: Modifier = Modifier) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val transition = rememberInfiniteTransition(label = "loading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "loadingAngle",
    )
    val static = !visual.motion.allowMovement
    Box(
        modifier = modifier
            .size(30.dp)
            .drawBehind {
                val stroke = 3.dp.toPx()
                val inset = stroke
                drawArc(
                    color = colors.outline,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                )
                drawArc(
                    color = colors.primary,
                    startAngle = if (static) -90f else angle - 90f,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                )
            },
    )
}

/** 确定进度：细条读 primary 渐变。 */
@Composable
fun AppProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(visual.shape(ShapeKind.TAG))
            .background(colors.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(visual.shape(ShapeKind.TAG))
                .background(Brush.horizontalGradient(listOf(colors.primary, colors.secondary))),
        )
    }
}

/** 实心按钮（唯一允许大面积 primary 的地方）。onClick 放最后以便尾随 lambda。 */
@Composable
fun AppFilledButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(if (enabled) colors.primary else colors.outline)
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = visual.density.screenPadding * 1.2f, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = visual.type.label,
            color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

/** 弱化按钮（实色 + 细描边，玻璃关闭时的次级形态）。onClick 放最后以便尾随 lambda。 */
@Composable
fun AppGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(if (enabled) colors.surfaceVariant else colors.surfaceVariant.copy(alpha = 0.4f))
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = visual.density.screenPadding * 1.2f, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = visual.type.label,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

/** 行内骨架组（列表加载态的常用组合）。 */
@Composable
fun AppSkeletonList(rows: Int = 5, modifier: Modifier = Modifier) {
    val visual = LocalVisualSpec.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(visual.density.blockGap)) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = visual.density.screenPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(visual.density.screenPadding / 2),
            ) {
                AppSkeleton(modifier = Modifier.size(38.dp), kind = ShapeKind.TAG)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSkeleton(modifier = Modifier.fillMaxWidth(0.7f).height(13.dp))
                    AppSkeleton(modifier = Modifier.fillMaxWidth(0.45f).height(10.dp))
                }
            }
        }
    }
}

/**
 * 下拉刷新容器：M3 PullToRefreshBox 的 V5 薄壳。
 * 内容占满剩余空间；[refreshing] 为 true 时顶部出现刷新指示器。
 */
@Composable
fun AppPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        content = content,
    )
}
