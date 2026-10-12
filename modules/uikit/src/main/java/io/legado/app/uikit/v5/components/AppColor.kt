package io.legado.app.uikit.v5.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * 颜色字段与 HSV 取色弹窗（台账 #30-P1，形态参考 legado-with-MD3 的 ColorPickerSheet，
 * 实现改为自绘不引三方色板库）。
 * 进出统一用 Android 的 ARGB Int（设置项持久化就是这个形态）。
 */

/** 色值 → HSV 分量（0..360 / 0..1 / 0..1）。 */
private fun hsvOf(colorInt: Int): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        android.graphics.Color.red(colorInt),
        android.graphics.Color.green(colorInt),
        android.graphics.Color.blue(colorInt),
        hsv,
    )
    return hsv
}

private fun argbOf(hue: Float, sat: Float, value: Float): Int =
    android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

/** 颜色字段行：标签 + 色块预览 + 点击弹取色器。 */
@Composable
fun AppColorField(
    label: String,
    confirmLabel: String,
    cancelLabel: String,
    colorInt: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    var pickerOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { pickerOpen = true } else Modifier
            )
            .padding(horizontal = density.rowPaddingH, vertical = density.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.screenPadding / 2),
    ) {
        Text(
            text = label,
            style = visual.type.body,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(visual.shape(ShapeKind.TAG))
                .background(Color(colorInt))
                .border(1.dp, colors.outline, visual.shape(ShapeKind.TAG)),
        )
    }

    if (pickerOpen) {
        AppColorPickerDialog(
            title = label,
            initialColor = colorInt,
            confirmLabel = confirmLabel,
            cancelLabel = cancelLabel,
            onConfirm = {
                pickerOpen = false
                onColorChange(it)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * HSV 取色弹窗：色相/饱和度/明度三根滑条 + 实时预览 + 取消/确定。
 * 回传 ARGB Int；[initialColor] 默认不透明洋红之外的中性值由调用方给定。
 */
@Composable
fun AppColorPickerDialog(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialColor: Int = android.graphics.Color.WHITE,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val initial = remember(initialColor) { hsvOf(initialColor) }
    var hue by remember(initialColor) { mutableFloatStateOf(initial[0]) }
    var sat by remember(initialColor) { mutableFloatStateOf(initial[1]) }
    var value by remember(initialColor) { mutableFloatStateOf(initial[2]) }

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            Text(text = title, style = visual.type.title, color = colors.onSurface)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(visual.shape(ShapeKind.FIELD))
                    .background(Color(argbOf(hue, sat, value))),
            )
            AppSlider(
                value = hue,
                onValueChange = { hue = it },
                valueRange = 0f..360f,
                valueLabel = "H ${hue.toInt()}",
            )
            AppSlider(
                value = sat,
                onValueChange = { sat = it },
                valueLabel = "S ${(sat * 100).toInt()}%",
            )
            AppSlider(
                value = value,
                onValueChange = { value = it },
                valueLabel = "V ${(value * 100).toInt()}%",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                AppGhostButton(cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
                AppFilledButton(confirmLabel, modifier = Modifier.weight(1f)) {
                    onConfirm(argbOf(hue, sat, value))
                }
            }
        }
    }
}

/** Compose Color → ARGB Int（设置项存色时用它，避免各页手写转换）。 */
fun colorToArgb(color: Color): Int = color.toArgb()
