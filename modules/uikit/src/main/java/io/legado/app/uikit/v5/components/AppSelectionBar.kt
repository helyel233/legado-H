package io.legado.app.uikit.v5.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * 页级选择模式状态（审计 #28-③ ManageListScaffold 的核心半件）。
 *
 * 收编管理页此前每页手搓的选中集合操作：进入/退出、单击切换、全选/反选、
 * 按已排序列表取选中项。用法：
 *
 * ```
 * val selection = rememberAppSelectionState(idOf = { it.url })
 * if (selection.selectionMode) {
 *     AppSelectionBar(
 *         primaryActions = listOf(
 *             AppSelectionAction("全选") { selection.selectAll(sources) },
 *             ...
 *         ),
 *         moreLabel = "更多 ⋮",
 *     )
 * }
 * AppSelectionRow(
 *     selected = selection.isSelected(item),
 *     onClick = { selection.toggle(item) },   // 选择模式下
 *     onLongClick = { selection.enter(item) },
 * )
 * ```
 */
class AppSelectionState<T>(private val idOf: (T) -> String) {

    var selected: Set<String> by mutableStateOf(emptySet())
        private set

    /** 是否处于选择模式（有任何选中项） */
    val selectionMode: Boolean get() = selected.isNotEmpty()

    val count: Int get() = selected.size

    fun isSelected(item: T): Boolean = idOf(item) in selected

    /** 长按进入选择模式并选中该项。 */
    fun enter(item: T) {
        selected = selected + idOf(item)
    }

    /** 选择模式下单击切换；非选择模式下返回 false（由页面做正常点击）。 */
    fun toggle(item: T): Boolean {
        val id = idOf(item)
        return if (id in selected) {
            selected = selected - id
            true
        } else if (selectionMode) {
            selected = selected + id
            true
        } else {
            false
        }
    }

    fun selectAll(items: Collection<T>) {
        selected = items.mapTo(mutableSetOf(), idOf)
    }

    fun invert(items: Collection<T>) {
        val all = items.mapTo(mutableSetOf(), idOf)
        selected = all - selected
    }

    /** 按当前已排序列表取选中项（保持列表顺序）。 */
    fun selectedFrom(items: Collection<T>): List<T> =
        items.filter { idOf(it) in selected }

    /** 退出选择模式。 */
    fun exit() {
        selected = emptySet()
    }

    /** 恢复选中集合（校验选中区间等高级操作用）。 */
    fun restoreSelected(ids: Set<String>) {
        selected = ids
    }
}

@Composable
fun <T> rememberAppSelectionState(idOf: (T) -> String): AppSelectionState<T> =
    remember { AppSelectionState(idOf) }

/** 操作条单项（原 SelectionAction，收敛为共享组件内部实现）。触控区 ≥44dp。 */
@Composable
private fun AppSelectionActionItem(action: AppSelectionAction) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(visual.shape(ShapeKind.TAG))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { action.onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            style = visual.type.label,
            color = if (action.danger) colors.error else colors.primary,
        )
    }
}

/**
 * 顶栏尾部溢出菜单按钮（⋮）。原三个二级页各复制一份，收敛于此。
 * 无障碍：必须给 [contentDescription]（TalkBack 读得出这是什么按钮）；
 * 触控区保证 ≥48dp（字形本身很小）。
 */
@Composable
fun AppOverflowMenuButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    glyph: String = "⋮",
    onClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClickLabel = contentDescription,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = visual.type.title,
            color = colors.primary,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { this.contentDescription = contentDescription },
        )
    }
}
