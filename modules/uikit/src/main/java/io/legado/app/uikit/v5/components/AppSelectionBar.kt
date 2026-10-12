package io.legado.app.uikit.v5.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * 选择模式操作条动作规格（B 门：常用固定 + 「更多」溢出）。
 */
data class AppSelectionAction(
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 选择模式底部操作条：常用动作固定平铺 + 溢出动作收进 [AppMenuSheet]。
 *
 * 对照 B 门「批量/选择模式：底部操作条 = 常用 ≤5 项固定 + 「更多 ⋮」溢出」。
 * 只负责外观与交互，不持有选择状态；溢出菜单标题由调用方给（如「批量操作 (N)」）。
 */
@Composable
fun AppSelectionBar(
    primaryActions: List<AppSelectionAction>,
    overflowActions: List<AppSelectionAction> = emptyList(),
    overflowTitle: String = "",
    moreLabel: String,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    var overflowOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.BAR))
            .background(colors.surface.copy(alpha = 0.85f), visual.shape(ShapeKind.BAR))
            .padding(horizontal = visual.density.blockGap, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        primaryActions.forEach { action ->
            AppSelectionActionItem(action)
        }
        if (overflowActions.isNotEmpty()) {
            AppSelectionActionItem(AppSelectionAction(moreLabel) { overflowOpen = true })
        }
    }

    if (overflowOpen && overflowActions.isNotEmpty()) {
        AppMenuSheet(
            title = overflowTitle,
            actions = overflowActions.map { it.label to it.onClick },
            onDismiss = { overflowOpen = false },
        )
    }
}

/** 操作条单项（原 SelectionAction，收敛为共享组件内部实现）。 */
@Composable
private fun AppSelectionActionItem(action: AppSelectionAction) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Text(
        text = action.label,
        style = visual.type.label,
        color = if (action.danger) colors.error else colors.primary,
        modifier = Modifier
            .clip(visual.shape(ShapeKind.TAG))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { action.onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * 顶栏尾部溢出菜单按钮（⋮）。原三个二级页各复制一份，收敛于此。
 */
@Composable
fun AppOverflowMenuButton(
    glyph: String = "⋮",
    onClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Text(
        text = glyph,
        style = visual.type.title,
        color = colors.primary,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
