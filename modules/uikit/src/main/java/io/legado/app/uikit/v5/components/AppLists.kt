package io.legado.app.uikit.v5.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-2 列表零件：AppListItem / AppGridItem / AppDivider。
 *
 * 行高、内边距、字阶、按压反馈全部读全局密度与动效 ——
 * "某页面行高 48、某页面 56" 的旧病在结构上不可能再出现。
 */

/** 列表项：overline(可选) + headline + supporting(可选) + leading/trailing 槽位。 */
@Composable
fun AppListItem(
    headline: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    dense: Boolean = false,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val pressed = if (onClick != null && visual.motion.allowMovement) 0.985f else 1f
    val scale by animateFloatAsState(pressed, visual.motion.springSpec(), label = "rowPress")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            )
            .padding(
                horizontal = density.rowPaddingH,
                vertical = if (dense) density.rowPaddingV / 2 else density.rowPaddingV,
            )
            .height(
                if (dense) density.rowHeight * 0.72f
                else if (supporting != null) density.rowHeight * 1.18f
                else density.rowHeight
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.screenPadding / 2),
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            if (overline != null) {
                Text(
                    text = overline,
                    style = visual.type.caption,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = headline,
                style = visual.type.body,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 网格项：media 槽（封面）+ 标题 + 辅助行。书架卡片、发现网格共用这一个零件。
 */
@Composable
fun AppGridItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    media: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val scale by animateFloatAsState(
        targetValue = if (onClick != null && visual.motion.allowMovement) 0.97f else 1f,
        animationSpec = visual.motion.springSpec(),
        label = "gridPress",
    )
    AppSurface(
        level = MaterialLevel.RAISED,
        modifier = modifier
            .scale(scale)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(density.cardPadding / 1.4f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(visual.shape(ShapeKind.COVER)),
            ) { media() }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(density.blockGap / 2))
            Text(
                text = headline,
                style = visual.type.label,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 分隔线：弱化到几乎看不见，但可感知。 */
@Composable
fun AppDivider(modifier: Modifier = Modifier, inset: Boolean = true) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (inset) visual.density.screenPadding else 0.dp,
                top = 1.dp,
                bottom = 1.dp,
            )
            .height(1.dp)
            .background(colors.outline),
    )
}

/**
 * 管理页选择行（☑/☐ + 选中底色 + 单击/长按语义）。
 *
 * 与 [AppSelectableRow]（弹窗里的单选/多选行）不同：这是管理页列表行的统一件，
 * 卡片容器 + 勾选指示 + 选中底色，长按进入选择模式（B 门：长按操作必须给提示）。
 * selectionMode=false 时也可以渲染非选择态（勾选指示不显示）。
 */
@Composable
fun AppSelectionRow(
    title: String,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    /** 第二行辅助文本（如书源校验的实时结果消息）。 */
    supportingSecondary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.CARD))
            .background(
                if (selected) colors.primary.copy(alpha = 0.14f)
                else colors.surface.copy(alpha = 0.72f),
                visual.shape(ShapeKind.CARD),
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = density.cardPadding, vertical = density.cardPadding / 1.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.blockGap / 1.5f),
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(visual.shape(ShapeKind.TAG))
                    .background(if (selected) colors.primary else colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = visual.type.body,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (supportingSecondary != null) {
                Text(
                    text = supportingSecondary,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 筛选 chips 单条规格（label + 选中态）。 */
data class AppFilterChip(val label: String, val selected: Boolean)

/**
 * 筛选 chips 行：caption 标签（可省）+ 横滑 chips。
 * 书源/订阅/发现/书架分组的状态与分组筛选共用这一个零件，
 * "每页自己写一排 Row+horizontalScroll" 到此为止。
 *
 * [overflowLabel] 非空 + [onOverflowClick]：选项太多时末尾挂「更多/全部 ⋮」入口，
 * 由调用方开 [AppFilterDialog] 全量选择（横滑 chips 找不齐选项的解法）。
 */
@Composable
fun AppFilterChipRow(
    chips: List<AppFilterChip>,
    onChipClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    overflowLabel: String? = null,
    onOverflowClick: (() -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = visual.type.caption,
                color = LocalSemanticColors.current.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = density.screenPadding),
            )
            Spacer(Modifier.height(density.blockGap / 3))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = density.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            chips.forEachIndexed { index, chip ->
                AppChip(chip.label, chip.selected, onClick = { onChipClick(index) })
            }
            if (overflowLabel != null && onOverflowClick != null) {
                AppChip(overflowLabel, false, onClick = onOverflowClick)
            }
        }
    }
}

/** 分区标题：左侧 caption 标题 + 右侧动作槽（可选）。 */
@Composable
fun AppSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = visual.density.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = visual.type.caption,
            color = LocalSemanticColors.current.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 加载更多三态（LazyColumn footer 用）：IDLE 不渲染，LOADING 转圈，END 显示到底文案。 */
enum class AppLoadMore { IDLE, LOADING, END }

@Composable
fun AppLoadMoreFooter(
    state: AppLoadMore,
    loadingText: String,
    endText: String,
    modifier: Modifier = Modifier,
) {
    if (state == AppLoadMore.IDLE) return
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = visual.density.blockGap),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == AppLoadMore.LOADING) {
            AppLoading(modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(visual.density.blockGap / 2))
        }
        Text(
            text = if (state == AppLoadMore.LOADING) loadingText else endText,
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
        )
    }
}
