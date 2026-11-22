package io.legado.app.ui.config

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoSegmentedControl
import io.legado.app.ui.widget.compose.SegmentedControlTone
import io.legado.app.ui.widget.compose.SegmentedOption
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 通用 Compose 调色盘弹窗：支持「预设调色盘（当前色板参考 + 纸墨中性色 + 18 主色系 × 10 级色阶）」
 * 与「自定义取色（2D 饱和度/明度面板 + 色相滑条 + HEX 实时双向同步）」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPalettePickerDialog(
    title: String,
    initialColorRaw: String,
    allowDecimalInput: Boolean = false,
    allowClear: Boolean = true,
    referenceColors: List<Pair<String, String>> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val style = rememberAppDialogStyle()
    val dialogPalette = style.toMiuixPalette()

    val initialParsed = remember(initialColorRaw, allowDecimalInput) {
        ColorPaletteHelper.parseColorInput(initialColorRaw, allowDecimalInput)
    }
    val seedArgb = remember(initialParsed, style.accent) {
        initialParsed ?: android.graphics.Color.argb(
            255,
            (style.accent.red * 255).toInt().coerceIn(0, 255),
            (style.accent.green * 255).toInt().coerceIn(0, 255),
            (style.accent.blue * 255).toInt().coerceIn(0, 255)
        )
    }
    val initialHsv = remember(seedArgb) { ColorPaletteHelper.rgbToHsv(seedArgb) }

    var activeTab by remember { mutableIntStateOf(0) }
    var selectedArgb by remember { mutableIntStateOf(seedArgb or 0xFF000000.toInt()) }
    var hue by remember { mutableFloatStateOf(initialHsv.first) }
    var saturation by remember { mutableFloatStateOf(initialHsv.second) }
    var value by remember { mutableFloatStateOf(initialHsv.third) }
    var hexInput by remember { mutableStateOf(ColorPaletteHelper.formatHex6(seedArgb)) }
    var selectedFamilyIdx by remember {
        mutableIntStateOf(ColorPaletteHelper.findNearestFamilyIndex(seedArgb))
    }

    fun selectArgb(
        argb: Int,
        updateFamily: Boolean = true,
        updateHexField: Boolean = true
    ) {
        val opaque = argb or 0xFF000000.toInt()
        selectedArgb = opaque
        val (h, s, v) = ColorPaletteHelper.rgbToHsv(opaque)
        hue = h
        saturation = s
        value = v
        if (updateHexField) {
            hexInput = ColorPaletteHelper.formatHex6(opaque)
        }
        if (updateFamily) {
            selectedFamilyIdx = ColorPaletteHelper.findNearestFamilyIndex(opaque)
        }
    }

    fun updateFromHsv(nextH: Float, nextS: Float, nextV: Float) {
        hue = nextH.coerceIn(0f, 360f)
        saturation = nextS.coerceIn(0f, 1f)
        value = nextV.coerceIn(0f, 1f)
        val argb = ColorPaletteHelper.hsvToArgb(hue, saturation, value)
        selectedArgb = argb
        hexInput = ColorPaletteHelper.formatHex6(argb)
    }

    val resolvedReferences = remember(referenceColors) {
        referenceColors.mapNotNull { (label, raw) ->
            ColorPaletteHelper.parseColorInput(raw, allowDecimalArgb = false)?.let { argb ->
                label to (argb or 0xFF000000.toInt())
            }
        }
    }
    val parsedHexInput = remember(hexInput) {
        ColorPaletteHelper.parseColorInput(hexInput, allowDecimalArgb = false)
    }
    val isHexValid = parsedHexInput != null

    val tabs = remember {
        listOf(
            SegmentedOption(0, "预设调色盘"),
            SegmentedOption(1, "自定义调色")
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
        ) {
            AppDialogFrame(
                title = title,
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 顶部实时对比预览 + HEX 输入
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, style.stroke, RoundedCornerShape(10.dp)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (initialParsed != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 24.dp, height = 44.dp)
                                            .background(Color(initialParsed or 0xFF000000.toInt()))
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(
                                            width = if (initialParsed != null) 34.dp else 52.dp,
                                            height = 44.dp
                                        )
                                        .background(Color(selectedArgb))
                                )
                            }
                            OutlinedTextField(
                                value = hexInput,
                                onValueChange = { next ->
                                    hexInput = next
                                    ColorPaletteHelper.parseColorInput(next, allowDecimalArgb = false)
                                        ?.let { parsed ->
                                            selectArgb(parsed, updateFamily = true, updateHexField = false)
                                        }
                                },
                                label = { Text("HEX 色值", fontSize = 11.sp) },
                                placeholder = { Text("#RRGGBB", fontSize = 12.sp) },
                                singleLine = true,
                                isError = !isHexValid,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        LegadoSegmentedControl(
                            options = tabs,
                            selectedValue = activeTab,
                            palette = dialogPalette,
                            onSelected = { activeTab = it },
                            tone = SegmentedControlTone.AccentThumb,
                            fontSize = 12.sp
                        )

                        if (activeTab == 0) {
                            if (resolvedReferences.isNotEmpty()) {
                                Text(
                                    text = "当前包色板参考（点击复用）",
                                    color = style.secondaryText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    resolvedReferences.forEach { (refLabel, refArgb) ->
                                        val isCurrent = (selectedArgb and 0x00FFFFFF) == (refArgb and 0x00FFFFFF)
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isCurrent) style.accent.copy(alpha = 0.14f)
                                                    else style.fieldSurface
                                                )
                                                .border(
                                                    width = if (isCurrent) 1.2.dp else 0.75.dp,
                                                    color = if (isCurrent) style.accent else style.stroke,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable { selectArgb(refArgb) }
                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(refArgb))
                                                    .border(0.75.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                                            )
                                            Text(
                                                text = refLabel,
                                                color = style.primaryText,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "常用纸墨与中性色",
                                color = style.secondaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            PaletteSwatchRows(
                                swatches = ColorPaletteHelper.neutralSwatches.map { it.second },
                                selectedArgb = selectedArgb,
                                columns = 5,
                                accentColor = style.accent,
                                onSelect = { selectArgb(it, updateFamily = false) }
                            )

                            Text(
                                text = "主色系（点选切换下方明暗色阶）",
                                color = style.secondaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            val families = ColorPaletteHelper.materialFamilies
                            val familyRows = families.chunked(6)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                familyRows.forEachIndexed { rowIdx, rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        rowItems.forEachIndexed { colIdx, family ->
                                            val idx = rowIdx * 6 + colIdx
                                            val isFamilySelected = idx == selectedFamilyIdx
                                            SwatchCircle(
                                                argb = family.primaryArgb,
                                                selected = isFamilySelected,
                                                accentColor = style.accent,
                                                onClick = {
                                                    selectedFamilyIdx = idx
                                                    selectArgb(family.primaryArgb, updateFamily = false)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            val activeFamily = families.getOrElse(selectedFamilyIdx) { families.first() }
                            Text(
                                text = "${activeFamily.name} · 明暗色阶参考 (50 ~ 900)",
                                color = style.secondaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            PaletteSwatchRows(
                                swatches = activeFamily.shades,
                                selectedArgb = selectedArgb,
                                columns = 5,
                                accentColor = style.accent,
                                onSelect = { selectArgb(it, updateFamily = false) }
                            )
                        } else {
                            Text(
                                text = "拖动选择饱和度与明度",
                                color = style.secondaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            SvPickerPanel(
                                hue = hue,
                                saturation = saturation,
                                value = value,
                                strokeColor = style.stroke,
                                onSvChanged = { nextS, nextV ->
                                    updateFromHsv(hue, nextS, nextV)
                                }
                            )
                            Text(
                                text = "色相 (${hue.toInt()}°)",
                                color = style.secondaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            HueSliderBar(
                                hue = hue,
                                strokeColor = style.stroke,
                                onHueChanged = { nextH ->
                                    updateFromHsv(nextH, saturation, value)
                                }
                            )
                        }
                    }
                },
                actions = {
                    if (allowClear) {
                        LegadoMiuixActionButton(
                            text = "留空/自动",
                            palette = dialogPalette,
                            minWidth = 72.dp,
                            insidePadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            onClick = { onConfirm("") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    LegadoMiuixActionButton(
                        text = "取消",
                        palette = dialogPalette,
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = "确定",
                        palette = dialogPalette,
                        primary = true,
                        enabled = isHexValid,
                        onClick = {
                            val resolved = parsedHexInput ?: selectedArgb
                            onConfirm(ColorPaletteHelper.formatHex6(resolved))
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun PaletteSwatchRows(
    swatches: List<Int>,
    selectedArgb: Int,
    columns: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit
) {
    val rows = swatches.chunked(columns)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { argb ->
                    val isSelected = (argb and 0x00FFFFFF) == (selectedArgb and 0x00FFFFFF)
                    SwatchCircle(
                        argb = argb,
                        selected = isSelected,
                        accentColor = accentColor,
                        onClick = { onSelect(argb) }
                    )
                }
                repeat((columns - row.size).coerceAtLeast(0)) {
                    Spacer(modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
private fun SwatchCircle(
    argb: Int,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val checkColor = Color(ColorPaletteHelper.contrastOnColor(argb))
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) accentColor else Color.Gray.copy(alpha = 0.45f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text(
                text = "✓",
                color = checkColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SvPickerPanel(
    hue: Float,
    saturation: Float,
    value: Float,
    strokeColor: Color,
    onSvChanged: (Float, Float) -> Unit
) {
    val huePureColor = remember(hue) { Color(ColorPaletteHelper.hsvToArgb(hue, 1f, 1f)) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(shape)
            .border(1.dp, strokeColor, shape)
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val nextS = (offset.x / w).coerceIn(0f, 1f)
                    val nextV = (1f - offset.y / h).coerceIn(0f, 1f)
                    onSvChanged(nextS, nextV)
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val nextS = (change.position.x / w).coerceIn(0f, 1f)
                    val nextV = (1f - change.position.y / h).coerceIn(0f, 1f)
                    onSvChanged(nextS, nextV)
                }
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, huePureColor)
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black)
                )
            )
            val cx = (saturation.coerceIn(0f, 1f) * size.width)
            val cy = ((1f - value.coerceIn(0f, 1f)) * size.height)
            drawCircle(
                color = Color.Black.copy(alpha = 0.65f),
                radius = 9.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 7.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun HueSliderBar(
    hue: Float,
    strokeColor: Color,
    onHueChanged: (Float) -> Unit
) {
    val hueColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(shape)
            .border(1.dp, strokeColor, shape)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    onHueChanged(((offset.x / w) * 360f).coerceIn(0f, 360f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    onHueChanged(((change.position.x / w) * 360f).coerceIn(0f, 360f))
                }
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(brush = Brush.horizontalGradient(colors = hueColors))
            val cx = ((hue.coerceIn(0f, 360f) / 360f) * size.width)
                .coerceIn(8.dp.toPx(), (size.width - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
            val cy = size.height / 2f
            drawCircle(
                color = Color.Black.copy(alpha = 0.65f),
                radius = 10.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 8.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
