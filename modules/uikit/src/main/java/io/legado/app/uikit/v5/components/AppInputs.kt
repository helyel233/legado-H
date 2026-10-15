package io.legado.app.uikit.v5.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-3 输入零件。全部从全局取形状（FIELD/TAG）与语义色，
 * 页面传值与回调，不传任何外观。
 */

/**
 * 搜索框：胶囊 + 前导图标。
 * [onSearch] 给定时回车键变搜索键并触发回调（搜索页不再手搓 BasicTextField 行）；
 * [trailing] 尾部动作槽（如「搜索」文字按钮）；[focusRequester] 页面自动聚焦用。
 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(density.rowHeight * 0.78f)
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(colors.surfaceVariant)
            .padding(horizontal = density.screenPadding / 1.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(density.iconSize * 0.82f),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = visual.type.body,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = visual.type.body.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = if (onSearch != null) {
                        { onSearch() }
                    } else null,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester)
                        else Modifier
                    ),
            )
        }
        trailing?.invoke()
    }
}

/** 文本输入框：E1 嵌入底 + 胶囊（形状族 FIELD）。表单类对话框的统一输入件。 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    /** 多行（URL 长文本/JS 片段等）；false = 单行 */
    singleLine: Boolean = true,
    /** 最小行数（singleLine=false 时生效） */
    minLines: Int = 1,
    maxLines: Int = 4,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** 错误态（边框色走 error 语义 + 错误说明文字） */
    errorText: String? = null,
    /** 输入变换（如密码掩码 PasswordVisualTransformation） */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** 字段标签（显示在输入框上方；表单型页面常用） */
    label: String? = null,
    /** 文字样式覆写（如弹窗调色板域注入字体/字色；null = 跟随主题） */
    textStyle: TextStyle? = null,
    /** 尾部动作槽（如「编辑代码」图标按钮） */
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val fieldTextStyle = textStyle ?: visual.type.body.copy(color = colors.onSurface)

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = visual.type.label,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp, start = density.screenPadding / 2),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(visual.shape(ShapeKind.FIELD))
                    .background(if (errorText != null) colors.error.copy(alpha = 0.10f) else colors.surfaceVariant)
                    .padding(horizontal = density.screenPadding / 1.4f, vertical = 12.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else minLines,
                    maxLines = if (singleLine) 1 else maxLines,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = fieldTextStyle,
                    cursorBrush = SolidColor(if (errorText != null) colors.error else colors.primary),
                    visualTransformation = visualTransformation,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = fieldTextStyle,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        inner()
                    },
                )
            }
            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingIcon()
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = visual.type.caption,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp, start = density.screenPadding / 2),
            )
        }
    }
}

/**
 * [AppTextField] 的 TextFieldValue 重载：保留光标/选区，
 * 供「全屏编辑回填 cursorPosition」类场景（代码/规则编辑器）使用。
 */
@Composable
fun AppTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 4,
    keyboardType: KeyboardType = KeyboardType.Text,
    errorText: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    label: String? = null,
    textStyle: TextStyle? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val fieldTextStyle = textStyle ?: visual.type.body.copy(color = colors.onSurface)

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = visual.type.label,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp, start = density.screenPadding / 2),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(visual.shape(ShapeKind.FIELD))
                    .background(if (errorText != null) colors.error.copy(alpha = 0.10f) else colors.surfaceVariant)
                    .padding(horizontal = density.screenPadding / 1.4f, vertical = 12.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else minLines,
                    maxLines = if (singleLine) 1 else maxLines,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = fieldTextStyle,
                    cursorBrush = SolidColor(if (errorText != null) colors.error else colors.primary),
                    visualTransformation = visualTransformation,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = fieldTextStyle,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        inner()
                    },
                )
            }
            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingIcon()
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = visual.type.caption,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp, start = density.screenPadding / 2),
            )
        }
    }
}

/**
 * 等分分段控件（segmented tabs）：N 个等宽选项单选切换。
 * 与 AppFilterChipRow（横滚筛选）互补——选项少而固定、需要「当前选中一眼可见」时用。
 */
@Composable
fun AppSegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(colors.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .clip(visual.shape(ShapeKind.BUTTON))
                    .background(if (selected) colors.surface else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                    ) { onSelect(index) }
                    .padding(horizontal = density.blockGap / 2, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = visual.type.label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 选项设置行：标题+摘要+当前值，点击弹单选菜单（底层 AppMenuSheet）。
 * "点行→弹单选→显示当前值"是设置页最基本行型，此前每页手拼 MenuSheet。
 */
@Composable
fun AppOptionRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    var open by remember { mutableStateOf(false) }
    val density = visual.density

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { open = true }
            .padding(horizontal = density.rowPaddingH, vertical = density.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.screenPadding / 2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = visual.type.body, color = colors.onSurface)
            if (summary != null) {
                Text(text = summary, style = visual.type.caption, color = colors.onSurfaceVariant)
            }
        }
        Text(
            text = options.getOrElse(selectedIndex) { "" },
            style = visual.type.caption,
            color = colors.primary,
        )
        Icon(
            imageVector = AppIcons.Chevron,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(density.iconSize * 0.72f),
        )
    }

    if (open) {
        AppMenuSheet(
            title = title,
            actions = options.mapIndexed { index, label ->
                (if (index == selectedIndex) "✓ " else "") + label to { onSelect(index) }
            },
            onDismiss = { open = false },
        )
    }
}

/** 开关：轨道读语义色，滑块随弹性曲线位移。 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val track = when {
        !enabled -> colors.outline
        checked -> colors.primary
        else -> colors.surfaceVariant
    }
    val knob = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.6f)
        checked -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        val x by animateDpAsState(
            targetValue = if (checked) 24.dp else 4.dp,
            animationSpec = visual.motion.springSpec(),
            label = "switchKnob",
        )
        Box(
            modifier = Modifier
                .offset(x = x)
                .size(18.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

/** 单选 / 多选行：整行可点，视觉状态由组件表达。 */
@Composable
fun AppSelectableRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    multi: Boolean = false,
    supporting: String? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = density.rowPaddingH, vertical = density.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.screenPadding / 2),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(if (multi) visual.shape(ShapeKind.TAG) else CircleShape)
                .background(if (selected) colors.primary else colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(if (multi) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(colors.onPrimary),
                )
            }
        }
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
    }
}

/**
 * 标题+摘要+开关行（设置类页面统一件）。
 * 整行可点（如需跳转详情/弹选择层）；开关自身点击不冒泡到行。
 */
@Composable
fun AppRowWithSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onRowClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** 行卡片底（surface 72%）。已在卡片/弹窗面板内时传 false 避免双层底。 */
    card: Boolean = true,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (card) Modifier.appRowCard() else Modifier)
            .then(
                if (onRowClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRowClick,
                ) else Modifier
            )
            .padding(horizontal = density.rowPaddingH, vertical = density.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.screenPadding / 2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = visual.type.body, color = colors.onSurface)
            if (summary != null) {
                Text(
                    text = summary,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 滑块：轨道读 primary 渐变，支持点按与拖动，拇指带弹性。
 * [steps] > 0 时按 M3 语义吸附：steps 为两端之间的**中间停靠点数**（区间共 steps+1 份）；
 * [valueLabel] 显示当前值文案（右对齐）；
 * [onValueChangeFinished] 拖动结束回调（设置类页面保存偏好用）。
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    steps: Int = 0,
    valueLabel: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val span = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        if (valueLabel != null) {
            Text(
                text = valueLabel,
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(bottom = 2.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val w = maxWidth

            fun update(x: Float, widthPx: Float) {
                if (!enabled || widthPx <= 0f) return
                var f = (x / widthPx).coerceIn(0f, 1f)
                if (steps > 0) {
                    // M3 语义：steps = 中间停靠点数，区间共 steps+1 份
                    val parts = steps + 1
                    f = (f * parts).toFloat().let { kotlin.math.round(it) } / parts
                }
                onValueChange(valueRange.start + f * span)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(visual.shape(ShapeKind.TAG))
                    .background(colors.surfaceVariant),
            )
            // 触摸层：点按与拖动分别挂 pointerInput（同一块内挂两个检测器只有一个生效）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(valueRange, enabled, steps) {
                        detectTapGestures { offset -> update(offset.x, size.width.toFloat()) }
                    }
                    .pointerInput(valueRange, enabled, steps) {
                        detectDragGestures(
                            onDragEnd = { onValueChangeFinished?.invoke() },
                            onDragCancel = { onValueChangeFinished?.invoke() },
                        ) { change, _ ->
                            change.consume()
                            update(change.position.x, size.width.toFloat())
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .clip(visual.shape(ShapeKind.TAG))
                    .background(
                        if (enabled) Brush.horizontalGradient(listOf(colors.primary, colors.secondary))
                        else Brush.horizontalGradient(listOf(colors.outline, colors.outline))
                    ),
            )
            val thumbX = w * fraction - 8.dp
            Box(
                modifier = Modifier
                    .offset(x = thumbX.coerceAtLeast(0.dp))
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (enabled) colors.primary else colors.onSurfaceVariant),
            )
        }
    }
}

/**
 * 数字输入行：标签 + 「−」步进 + 数字框 + 「+」步进。
 * 整数区间/步进语义在后端收敛，页面不再手拼三个控件。
 */
@Composable
fun AppNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 合法区间；null 不限 */
    range: IntRange? = null,
    /** ±按钮步长（输入框可任意键入） */
    step: Int = 1,
    label: String? = null,
    enabled: Boolean = true,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val focusManager = LocalFocusManager.current

    fun coerce(v: Int): Int = if (range != null) v.coerceIn(range.first, range.last) else v

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = visual.type.body,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        AppStepButton("−", enabled && (range == null || value > range.first)) {
            focusManager.clearFocus()
            onValueChange(coerce(value - step))
        }
        // 键入期保留中间输入（含越界值如 "10000"），只有失焦或步进时才吸附到合法区间，
        // 避免旧实现「键入即 coerce 回写 → remember(value) 重置 text」把用户输入打回边界。
        var text by rememberSaveable { mutableStateOf(value.toString()) }
        var focused by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(value, focused) {
            if (!focused && text.toIntOrNull() != value) {
                text = value.toString()
            }
        }
        Box(
            modifier = Modifier
                .widthIn(min = 64.dp)
                .clip(visual.shape(ShapeKind.FIELD))
                .background(colors.surfaceVariant)
                .padding(horizontal = density.screenPadding / 2, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    // 只向上传递合法值；越界输入留在框内等用户继续修改
                    input.toIntOrNull()?.let { v ->
                        if (range == null || v in range) onValueChange(v)
                    }
                },
                enabled = enabled,
                singleLine = true,
                textStyle = visual.type.body.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { fs ->
                        val wasFocused = focused
                        focused = fs.isFocused
                        // 失焦时把越界/非法输入吸附回合法区间（合法值不写回，避免与步进按钮竞争）
                        if (wasFocused && !fs.isFocused) {
                            val parsed = text.toIntOrNull()
                            if (parsed == null || (range != null && parsed !in range)) {
                                val snapped = parsed?.let { coerce(it) } ?: value
                                text = snapped.toString()
                                onValueChange(snapped)
                            }
                        }
                    },
            )
        }
        AppStepButton("+", enabled && (range == null || value < range.last)) {
            focusManager.clearFocus()
            onValueChange(coerce(value + step))
        }
    }
}

/** ±步进小按钮（AppNumberField 内部件，40dp 触控目标）。 */
@Composable
private fun AppStepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(if (enabled) colors.surfaceVariant else colors.surfaceVariant.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = visual.type.title,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

/**
 * 表单下拉字段（编辑页/筛选表单用；形态对齐 legado-with-MD3 的 ExposedDropdown 用法）。
 * 与 [AppOptionRow] 的区别：前者是「设置行」（整行点击弹菜单），这里是「表单字段」
 * （带标签、错误态、内嵌下拉展开）。
 */
@Composable
fun AppDropdownField(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    errorText: String? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(visual.shape(ShapeKind.FIELD))
                    .background(if (errorText != null) colors.error.copy(alpha = 0.10f) else colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                    ) { expanded = true }
                    .padding(horizontal = density.screenPadding / 1.4f, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = options.getOrNull(selectedIndex) ?: placeholder,
                    style = visual.type.body,
                    color = if (options.getOrNull(selectedIndex) == null) colors.onSurfaceVariant
                    else colors.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = AppIcons.Chevron,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier
                        .size(density.iconSize * 0.72f)
                        .graphicsLayer { rotationZ = 90f },
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEachIndexed { index, option ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                style = visual.type.body,
                                color = if (index == selectedIndex) colors.primary else colors.onSurface,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = visual.type.caption,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp, start = density.screenPadding / 2),
            )
        }
    }
}

/**
 * 竖向滑条（阅读菜单亮度/进度用；移植自 legado-with-MD3 的旋转实现）：
 * 内部复用 [AppSlider]，graphicsLayer 旋转 -90° 呈竖向。
 * [accessibilityLabel]/[accessibilityValue] 供无障碍朗读（旋转后语义会乱，
 * 页面应传入原始语义）。
 */
@Composable
fun AppVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    height: androidx.compose.ui.unit.Dp = 160.dp,
    onValueChangeFinished: (() -> Unit)? = null,
    accessibilityLabel: String? = null,
    accessibilityValue: String? = null,
) {
    Box(
        modifier = modifier
            .width(48.dp)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        AppSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .requiredWidth(height)
                .requiredHeight(48.dp)
                .graphicsLayer { rotationZ = -90f }
                .then(
                    if (accessibilityLabel != null || accessibilityValue != null) {
                        Modifier.semantics {
                            accessibilityLabel?.let { contentDescription = it }
                            accessibilityValue?.let { stateDescription = it }
                        }
                    } else Modifier
                ),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}
