package io.legado.app.uikit.v5.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
