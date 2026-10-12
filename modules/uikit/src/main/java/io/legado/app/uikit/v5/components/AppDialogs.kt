package io.legado.app.uikit.v5.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-4 弹层：AppDialog / AppBottomSheet / AppMenuSheet。
 *
 * 全部 E3 悬浮材质 —— 弹窗、抽屉、菜单与其它容器共用同一套
 * 形状族、玻璃参数，不可能出现"这个弹窗是方角那个是圆角"。
 */

/** 对话框：E3 悬浮 + 缩放入场。 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visual = LocalVisualSpec.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(visual.density.screenPadding * 2),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(visual.motion.mediumSpec()) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = visual.motion.mediumSpec(),
                ),
                exit = fadeOut(visual.motion.shortSpec()) + scaleOut(targetScale = 0.94f),
            ) {
                Box(Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )) {
                    AppSurface(level = MaterialLevel.FLOATING, backgroundBlur = true) { content() }
                }
            }
        }
    }
}

/** 底部抽屉：E3 悬浮 + 上滑入场。 */
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visual = LocalVisualSpec.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(visual.motion.longSpec()) { it },
                exit = slideOutVertically(visual.motion.longSpec()) { it },
            ) {
                Box(Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )) {
                    AppSurface(level = MaterialLevel.FLOATING, modifier = Modifier.fillMaxWidth(), backgroundBlur = true) {
                        Column(modifier = Modifier.fillMaxWidth()) { content() }
                    }
                }
            }
        }
    }
}

/** 菜单抽屉（底部一列动作）。 */
@Composable
fun AppMenuSheet(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(visual.density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 抓手
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = visual.density.blockGap)
                    .size(width = 36.dp, height = 4.dp)
                    .background(colors.outline, visual.shape(ShapeKind.TAG)),
            )
            Text(
                text = title,
                style = visual.type.title,
                color = colors.onSurface,
                modifier = Modifier.padding(
                    start = visual.density.screenPadding / 2,
                    end = visual.density.screenPadding / 2,
                    bottom = visual.density.blockGap / 2,
                ),
            )
            actions.forEach { (label, action) ->
                AppListItem(
                    headline = label,
                    leading = {
                        Box(modifier = Modifier.size(8.dp))
                    },
                    onClick = {
                        onDismiss()
                        action()
                    },
                )
            }
        }
    }
}

/**
 * 标准输入弹窗：标题 + 输入框（AppTextField）+ 取消/确定。
 * 替掉各页手搓的 40 行分组输入弹窗（书源加分组/移出分组是第一个消费者）。
 */
@Composable
fun AppInputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (text: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = LocalSemanticColors.current.onSurface,
            )
            AppTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                AppGhostButton(cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
                AppFilledButton(confirmLabel, modifier = Modifier.weight(1f)) {
                    onConfirm(value.trim())
                }
            }
        }
    }
}

/**
 * 标准确认弹窗：标题 + 说明（可省）+ 取消/确认。
 * danger=true 时确认按钮变红 —— B 门红线：破坏性操作必须确认。
 */
@Composable
fun AppConfirmDialog(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    danger: Boolean = false,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = LocalSemanticColors.current.onSurface,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = visual.type.body,
                    color = LocalSemanticColors.current.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                AppGhostButton(cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
                if (danger) {
                    AppDangerButton(confirmLabel, modifier = Modifier.weight(1f), onClick = onConfirm)
                } else {
                    AppFilledButton(confirmLabel, modifier = Modifier.weight(1f), onClick = onConfirm)
                }
            }
        }
    }
}

/** 危险操作按钮（error 语义色）。AppFilledButton 的破坏性变体。 */
@Composable
fun AppDangerButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(colors.error)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = visual.density.screenPadding * 1.2f, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        // error 底为高饱和红，白字在日夜两套主题下对比度都达标（语义色表无 onError 角色）
        Text(text = text, style = visual.type.label, color = Color.White)
    }
}

/** 表单弹窗的单字段规格。 */data class AppFormField(
    val key: String,
    val label: String,
    val initial: String = "",
    val placeholder: String = "",
    val singleLine: Boolean = true,
    val keyboardType: androidx.compose.ui.text.input.KeyboardType =
        androidx.compose.ui.text.input.KeyboardType.Text,
)

/**
 * 多字段表单弹窗（书源编辑、替换规则编辑等 4-6 字段场景）。
 * 值托管在组件内，onConfirm 一次性回传 key→text 映射。
 */
@Composable
fun AppFormDialog(
    title: String,
    fields: List<AppFormField>,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (values: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = remember(fields) {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.key, it.initial) }
        }
    }
    val visual = LocalVisualSpec.current
    val density = visual.density
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = LocalSemanticColors.current.onSurface,
            )
            fields.forEach { field ->
                AppTextField(
                    value = values[field.key].orEmpty(),
                    onValueChange = { values[field.key] = it },
                    placeholder = field.placeholder,
                    singleLine = field.singleLine,
                    keyboardType = field.keyboardType,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                AppGhostButton(cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
                AppFilledButton(confirmLabel, modifier = Modifier.weight(1f)) {
                    onConfirm(values.toMap())
                }
            }
        }
    }
}

/**
 * 进度弹窗：标题 + 文案 + 不确定/确定进度条 + 取消按钮。
 * 导入书源（下载→解析→入库多阶段）、批量任务等流程的统一进度形态。
 * progress = null 显示不确定动画。
 */
@Composable
fun AppProgressDialog(
    title: String,
    message: String,
    cancelLabel: String?,
    onCancel: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = LocalSemanticColors.current.onSurface,
            )
            Text(
                text = message,
                style = visual.type.body,
                color = LocalSemanticColors.current.onSurfaceVariant,
            )
            if (progress != null) {
                AppProgressBar(progress = progress)
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { AppLoading() }
            }
            if (cancelLabel != null && onCancel != null) {
                AppGhostButton(cancelLabel, modifier = Modifier.fillMaxWidth(), onClick = onCancel)
            }
        }
    }
}

/**
 * 筛选弹窗：选项多到横滑 chips 不好用时（分组筛选等）的全量选择形态。
 * 单选：点行即选并关闭；多选用 [AppMultiFilterDialog]。
 */
@Composable
fun AppFilterDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = visual.density.screenPadding),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = LocalSemanticColors.current.onSurface,
                modifier = Modifier
                    .padding(horizontal = visual.density.screenPadding)
                    .padding(bottom = visual.density.blockGap / 2),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                itemsIndexed(options) { index, label ->
                    AppListItem(
                        headline = label,
                        leading = {
                            Box(modifier = Modifier.size(8.dp))
                        },
                        trailing = if (index == selectedIndex) {
                            {
                                Icon(
                                    imageVector = io.legado.app.uikit.v5.components.AppIcons.Check,
                                    contentDescription = null,
                                    tint = LocalSemanticColors.current.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                        onClick = {
                            onSelect(index)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 多选筛选弹窗：与 [AppFilterDialog] 同形态，点行切换勾选态、确认后一次性回传。
 * 分组多选移入/移出、批量筛选等场景。选中集合托管在组件内，首次组合给定。
 */
@Composable
fun AppMultiFilterDialog(
    title: String,
    options: List<String>,
    selectedIndices: Set<Int>,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (selected: Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val checked = remember(options, selectedIndices) {
        androidx.compose.runtime.mutableStateListOf<Int>().apply { addAll(selectedIndices) }
    }
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = density.screenPadding),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = LocalSemanticColors.current.onSurface,
                modifier = Modifier
                    .padding(horizontal = density.screenPadding)
                    .padding(bottom = density.blockGap / 2),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                itemsIndexed(options) { index, label ->
                    val isChecked = index in checked
                    AppListItem(
                        headline = label,
                        leading = {
                            Box(modifier = Modifier.size(8.dp))
                        },
                        trailing = {
                            Icon(
                                imageVector = AppIcons.Check,
                                contentDescription = null,
                                tint = if (isChecked) LocalSemanticColors.current.primary
                                else LocalSemanticColors.current.outline,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            if (isChecked) checked.remove(index) else checked.add(index)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap),
            ) {
                AppGhostButton(cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
                AppFilledButton(confirmLabel, modifier = Modifier.weight(1f)) {
                    onConfirm(checked.toSortedSet())
                }
            }
        }
    }
}

/**
 * 数字选择弹窗：标题 + 「−/+」步进大按钮 + 当前值 + 取消/确定。
 * 定时分钟数、并发数等「选一个数」的统一形态（旧 NumberPickerDialog 的 V5 对应件）。
 */
@Composable
fun AppNumberPickerDialog(
    title: String,
    value: Int,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..100,
    step: Int = 1,
    valueLabel: String? = null,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val colors = LocalSemanticColors.current
    var current by remember(value) { mutableStateOf(value.coerceIn(range.first, range.last)) }

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = colors.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(density.blockGap),
            ) {
                AppGhostButton(
                    "−",
                    modifier = Modifier.widthIn(min = 56.dp),
                    enabled = current > range.first,
                ) { current = (current - step).coerceAtLeast(range.first) }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = current.toString(),
                        style = visual.type.title,
                        color = colors.primary,
                    )
                    if (valueLabel != null) {
                        Text(
                            text = valueLabel,
                            style = visual.type.caption,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                AppGhostButton(
                    "+",
                    modifier = Modifier.widthIn(min = 56.dp),
                    enabled = current < range.last,
                ) { current = (current + step).coerceAtMost(range.last) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                AppGhostButton(cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
                AppFilledButton(confirmLabel, modifier = Modifier.weight(1f)) {
                    onConfirm(current)
                }
            }
        }
    }
}

/**
 * 24 小时制时间选择弹窗（M3 TimePicker 包进 AppDialog；形态参考 legado-with-MD3）。
 * [currentValue] 与回调值均为 "HH:mm" 字符串，解析失败回落 00:00。
 * 定时朗读、自动任务定时等场景。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppTimePickerDialog(
    title: String,
    currentValue: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val timePickerState = androidx.compose.material3.rememberTimePickerState(
        initialHour = currentValue.substringBefore(':').trim().toIntOrNull()?.coerceIn(0, 23) ?: 0,
        initialMinute = currentValue.substringAfter(':', "").trim().toIntOrNull()?.coerceIn(0, 59) ?: 0,
        is24Hour = true,
    )
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = title,
                style = visual.type.title,
                color = LocalSemanticColors.current.onSurface,
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.TimePicker(state = timePickerState)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                AppGhostButton(cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
                AppFilledButton(confirmLabel, modifier = Modifier.weight(1f)) {
                    onConfirm(
                        String.format(java.util.Locale.ROOT, "%02d:%02d",
                            timePickerState.hour, timePickerState.minute)
                    )
                }
            }
        }
    }
}
