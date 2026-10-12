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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
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
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.FIELD))
            .background(colors.surfaceVariant)
            .padding(horizontal = density.screenPadding / 1.4f, vertical = 12.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = visual.type.body.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = visual.type.body,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                inner()
            },
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
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
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

/** 滑块：轨道读 primary 渐变，支持点按与拖动，拇指带弹性。 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)
    val span = valueRange.endInclusive - valueRange.start

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth

        fun update(x: Float, widthPx: Float) {
            if (!enabled || widthPx <= 0f) return
            val f = (x / widthPx).coerceIn(0f, 1f)
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
                .pointerInput(valueRange, enabled) {
                    detectTapGestures { offset -> update(offset.x, size.width.toFloat()) }
                }
                .pointerInput(valueRange, enabled) {
                    detectDragGestures { change, _ ->
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
