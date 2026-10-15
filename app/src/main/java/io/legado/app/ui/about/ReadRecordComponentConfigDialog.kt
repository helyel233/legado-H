package io.legado.app.ui.about

import android.content.Context
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.host.V5DialogPanel
import io.legado.app.ui.host.HostV5ThemeRoot
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.applyModernWindowStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.min

object ReadRecordComponentConfigDialog {

    fun show(
        context: Context,
        initialItems: List<ReadRecordComponentItem>,
        onSaved: (List<ReadRecordComponentItem>) -> Unit
    ) {
        lateinit var dialog: AlertDialog
        val fixedListHeight = min(
            420.dpToPx(),
            (context.resources.displayMetrics.heightPixels * 0.48f).toInt()
        ).coerceAtLeast(260.dpToPx())
        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                HostV5ThemeRoot {
                    ReadRecordComponentConfigContent(
                        initialItems = initialItems,
                        listHeightDp = fixedListHeight / context.resources.displayMetrics.density,
                        onCancel = { dialog.dismiss() },
                        onSave = { items ->
                            val normalized = items.map { it.copy() }.toMutableList()
                            if (normalized.none { it.enabled }) {
                                normalized.firstOrNull()?.enabled = true
                            }
                            // Applied as it is edited, so the dialog stays open.
                            onSaved(normalized)
                        }
                    )
                }
            }
        }
        dialog = AlertDialog.Builder(context)
            .setView(composeView)
            .create()
        dialog.setOnShowListener {
            dialog.setLayout(0.9f, 0.68f)
        }
        dialog.applyModernWindowStyle()
        // AppDialogFrame 自带圆角面板背景，清掉 AlertDialog 自身窗口背景，避免双层背景。
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.show()
    }
}

@Composable
private fun ReadRecordComponentConfigContent(
    initialItems: List<ReadRecordComponentItem>,
    listHeightDp: Float,
    onCancel: () -> Unit,
    onSave: (List<ReadRecordComponentItem>) -> Unit
) {
    val items = remember(initialItems) {
        mutableStateListOf<ReadRecordComponentItem>().apply {
            addAll(initialItems.map { it.copy() })
        }
    }
    val colors = LocalSemanticColors.current
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items.add(to.index, items.removeAt(from.index))
    }
    // Every change takes effect immediately: the list behind this dialog is the thing being
    // edited, so waiting for a confirm button only makes the edit look lost.
    fun commit() = onSave(items.map { it.copy() })
    V5DialogPanel(
        title = stringResource(R.string.read_record_customize_components),
        message = stringResource(R.string.read_record_components_hint),
        scrollContent = false,
        content = {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = listHeightDp.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(
                    items = items,
                    key = { item -> item.type.name }
                ) { item ->
                    ReorderableItem(reorderState, key = item.type.name) {
                        ReadRecordComponentConfigRow(
                            item = item,
                            // Resolve the position at click time. Rows are keyed, so a
                            // composable outlives reordering and an index captured when it
                            // was composed would go on addressing the row's old slot.
                            onToggle = { checked ->
                                val index = items.indexOfFirst { it.type == item.type }
                                if (index >= 0) {
                                    items[index] = item.copy(enabled = checked)
                                    commit()
                                }
                            },
                            dragHandle = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_drag_handle),
                                    contentDescription = stringResource(R.string.sort),
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .draggableHandle(onDragStopped = { commit() })
                                )
                            }
                        )
                    }
                }
            }
        },
        actions = {
            AppFilledButton(
                text = stringResource(R.string.close),
                onClick = onCancel
            )
        }
    )
}

@Composable
private fun ReadRecordComponentConfigRow(
    item: ReadRecordComponentItem,
    onToggle: (Boolean) -> Unit,
    dragHandle: @Composable () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!item.enabled) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dragHandle()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.type.titleRes),
                    style = visual.type.body,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(item.type.hintRes),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            AppSwitch(
                checked = item.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}
