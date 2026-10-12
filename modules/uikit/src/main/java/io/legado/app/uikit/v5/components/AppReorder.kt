package io.legado.app.uikit.v5.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * P2 列表增强：拖拽重排（台账 #5 的组件半边）。
 *
 * sh.calvin.reorderable 的薄封装 —— 调用方无需 import sh.calvin 包，统一这样用：
 *
 * ```
 * val listState = rememberLazyListState()
 * var items by remember { mutableStateOf(...) }
 * val reorderState = rememberAppReorderableListState(
 *     lazyListState = listState,
 *     onMove = { from, to -> items = items.toMutableList().apply { add(to, removeAt(from)) } },
 * )
 * LazyColumn(state = listState) {
 *     items(items, key = { it.key }) { item ->
 *         AppReorderableItem(reorderState, key = item.key, onDragStopped = { persist(items) }) { dragging, dragHandle ->
 *             Row {
 *                 Icon(AppIcons.Chevron, modifier = dragHandle.padding(8.dp))
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * onMove 只改本地列表（拖拽动画期间的数据源）；持久层提交在
 * onDragStopped 里做（与旧页 RssSourceScreen/ReplaceRuleScreen 同模式）。
 */

/** 持有库内部状态，避免调用方直接接触 sh.calvin 类型。 */
class AppReorderableState internal constructor(
    internal val impl: ReorderableLazyListState,
) {
    /** 列表里是否有项正在拖拽（可用于禁用侧滑等冲突手势）。 */
    val isDragging: Boolean get() = impl.isAnyItemDragging
}

@Composable
fun rememberAppReorderableListState(
    lazyListState: LazyListState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): AppReorderableState {
    val impl = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMove(from.index, to.index)
    }
    return AppReorderableState(impl)
}

/**
 * 可拖拽重排的 LazyColumn 项（必须在 LazyColumn 的 item/items lambda 内调用）。
 * 内容 lambda 收到 [isDragging]（拖拽中，可加阴影/放大）与 [dragHandle]
 * （拖拽手柄 modifier：挂到"按住即拖"的元素上；挂整行则长按整行拖拽）。
 */
@Composable
fun LazyItemScope.AppReorderableItem(
    state: AppReorderableState,
    key: Any,
    modifier: Modifier = Modifier,
    onDragStopped: (() -> Unit)? = null,
    content: @Composable (isDragging: Boolean, dragHandle: Modifier) -> Unit,
) {
    ReorderableItem(state.impl, key = key, modifier = modifier) { dragging ->
        val handle = if (onDragStopped != null) {
            Modifier.draggableHandle(onDragStopped = onDragStopped)
        } else {
            Modifier.draggableHandle()
        }
        content(dragging, handle)
    }
}
