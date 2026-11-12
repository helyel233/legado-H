package io.legado.app.ui.widget.compose

import android.os.Build
import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import io.legado.app.R
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults as MiuixSliderDefaults
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults as MiuixSwitchDefaults

private const val MIUIX_PANEL_ANIMATION_MS = 160
private const val MIUIX_PANEL_DISMISS_MS = MIUIX_PANEL_ANIMATION_MS + 20L

@Immutable
data class LegadoMiuixPalette(
    val accent: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val danger: Color,
    val onAccent: Color = Color.White,
    val panelRadius: Dp? = null,
    val actionRadius: Dp? = null
)

fun AppDialogStyle.toMiuixPalette(): LegadoMiuixPalette {
    return LegadoMiuixPalette(
        accent = accent,
        surface = surface,
        surfaceVariant = fieldSurface,
        primaryText = primaryText,
        secondaryText = secondaryText,
        danger = danger,
        onAccent = onAccent,
        panelRadius = panelRadius,
        actionRadius = actionRadius
    )
}

@Composable
fun LegadoResourceIcon(
    iconName: String,
    modifier: Modifier = Modifier,
    resType: String = "mipmap"
) {
    val context = LocalContext.current
    val resId = remember(iconName, resType) {
        context.resources.getIdentifier(iconName, resType, context.packageName)
    }
    if (resId == 0) return
    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
        },
        update = { imageView ->
            imageView.setImageResource(resId)
        },
        modifier = modifier
    )
}

private fun canUseRealMiuix(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}

@Composable
fun LegadoMiuixCard(
    modifier: Modifier = Modifier,
    color: Color,
    contentColor: Color,
    cornerRadius: Dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (canUseRealMiuix()) {
        MiuixCard(
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = insidePadding,
            colors = MiuixCardDefaults.defaultColors(
                color = color,
                contentColor = contentColor
            ),
            content = content
        )
        return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = color,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(insidePadding), content = content)
    }
}

@Composable
fun LegadoMiuixActionButton(
    text: String,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    cornerRadius: Dp? = null,
    minWidth: Dp = 76.dp,
    minHeight: Dp = 40.dp,
    insidePadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
) {
    val resolvedCornerRadius = cornerRadius ?: palette.actionRadius ?: LocalContext.current.composeActionRadius()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val effectiveMinHeight = minHeight.coerceAtLeast((40f * fontScale).dp)
    val background = when {
        primary -> palette.accent
        danger -> palette.danger.copy(alpha = 0.13f)
        else -> palette.surfaceVariant
    }
    val content = when {
        primary -> palette.onAccent
        danger -> palette.danger
        else -> palette.primaryText
    }
    if (canUseRealMiuix()) {
        MiuixButton(
            onClick = onClick,
            modifier = modifier,
            cornerRadius = resolvedCornerRadius,
            minWidth = minWidth,
            minHeight = effectiveMinHeight,
            insideMargin = insidePadding,
            colors = MiuixButtonDefaults.buttonColors(
                color = background,
                disabledColor = background.copy(alpha = 0.46f),
                contentColor = content,
                disabledContentColor = content.copy(alpha = 0.38f)
            )
        ) {
            Text(
                text = text,
                color = content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = effectiveMinHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(resolvedCornerRadius),
        color = background,
        contentColor = content,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = effectiveMinHeight)
                .padding(insidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LegadoMiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val switchModifier = modifier.shadow(
        elevation = if (enabled) 3.dp else 1.dp,
        shape = RoundedCornerShape(50),
        clip = false
    )
    if (canUseRealMiuix()) {
        MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = switchModifier,
            colors = MiuixSwitchDefaults.switchColors(
                checkedThumbColor = palette.onAccent,
                uncheckedThumbColor = palette.secondaryText.copy(alpha = 0.72f),
                disabledCheckedThumbColor = palette.onAccent.copy(alpha = 0.42f),
                disabledUncheckedThumbColor = palette.secondaryText.copy(alpha = 0.32f),
                checkedTrackColor = palette.accent,
                uncheckedTrackColor = palette.surfaceVariant,
                disabledCheckedTrackColor = palette.accent.copy(alpha = 0.24f),
                disabledUncheckedTrackColor = palette.surfaceVariant.copy(alpha = 0.46f)
            )
        )
        return
    }
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = switchModifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = palette.onAccent,
            uncheckedThumbColor = palette.secondaryText.copy(alpha = 0.72f),
            disabledCheckedThumbColor = palette.onAccent.copy(alpha = 0.42f),
            disabledUncheckedThumbColor = palette.secondaryText.copy(alpha = 0.32f),
            checkedTrackColor = palette.accent,
            uncheckedTrackColor = palette.surfaceVariant,
            disabledCheckedTrackColor = palette.accent.copy(alpha = 0.24f),
            disabledUncheckedTrackColor = palette.surfaceVariant.copy(alpha = 0.46f)
        )
    )
}

@Composable
fun LegadoMiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    if (canUseRealMiuix()) {
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            modifier = modifier.fillMaxWidth(),
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = MiuixSliderDefaults.sliderColors(
                foregroundColor = palette.accent,
                disabledForegroundColor = palette.accent.copy(alpha = 0.28f),
                backgroundColor = palette.surfaceVariant,
                disabledBackgroundColor = palette.surfaceVariant.copy(alpha = 0.44f),
                thumbColor = palette.onAccent,
                disabledThumbColor = palette.onAccent.copy(alpha = 0.42f),
                keyPointColor = palette.secondaryText.copy(alpha = 0.32f),
                keyPointForegroundColor = palette.onAccent.copy(alpha = 0.68f)
            )
        )
        return
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.fillMaxWidth(),
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        colors = SliderDefaults.colors(
            activeTrackColor = palette.accent,
            inactiveTrackColor = palette.surfaceVariant,
            thumbColor = palette.onAccent,
            activeTickColor = palette.onAccent.copy(alpha = 0.72f),
            inactiveTickColor = palette.secondaryText.copy(alpha = 0.32f),
            disabledActiveTrackColor = palette.accent.copy(alpha = 0.28f),
            disabledInactiveTrackColor = palette.surfaceVariant.copy(alpha = 0.44f),
            disabledThumbColor = palette.onAccent.copy(alpha = 0.42f),
            disabledActiveTickColor = palette.onAccent.copy(alpha = 0.34f),
            disabledInactiveTickColor = palette.secondaryText.copy(alpha = 0.18f)
        )
    )
}

@Composable
fun AppThemedStepperSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    step: Int = 1,
    trackHeight: Dp = 34.dp,
    thumbSize: Dp = 26.dp,
    endpointWidth: Dp = 30.dp,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val safeStep = step.coerceAtLeast(1)
    val clampedValue = value.coerceIn(range)
    val rangeSize = (range.last - range.first).coerceAtLeast(1)
    val fraction = ((clampedValue - range.first).toFloat() / rangeSize).coerceIn(0f, 1f)
    val latestValue by rememberUpdatedState(clampedValue)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val endpointWidthPx = with(density) { endpointWidth.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val centerStartPx = (heightPx / 2f).coerceAtMost(widthPx / 2f)
        val centerEndPx = (widthPx - heightPx / 2f).coerceAtLeast(centerStartPx)
        val usablePx = (centerEndPx - centerStartPx).coerceAtLeast(1f)
        val thumbOffsetPx = (
            centerStartPx + usablePx * fraction - thumbSizePx / 2f
            ).roundToInt()

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(range, safeStep, enabled) {
                    if (!enabled) {
                        return@pointerInput
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        val centerStart = (size.height / 2f).coerceAtMost(width / 2f)
                        val centerEnd = (width - size.height / 2f).coerceAtLeast(centerStart)
                        val usable = (centerEnd - centerStart).coerceAtLeast(1f)
                        val touchSlop = viewConfiguration.touchSlop
                        var currentValue = latestValue
                        var didChange = false
                        var totalX = 0f
                        var totalY = 0f
                        var dragging = false

                        fun steppedValue(rawValue: Float, atStart: Boolean, atEnd: Boolean): Int {
                            if (atStart) return range.first
                            if (atEnd) return range.last
                            val stepped = range.first + (
                                (rawValue - range.first) / safeStep
                                ).roundToInt() * safeStep
                            return stepped.coerceIn(range)
                        }

                        fun valueForPosition(x: Float): Int {
                            val clamped = x.coerceIn(centerStart, centerEnd)
                            val atStart = clamped <= centerStart
                            val atEnd = clamped >= centerEnd
                            val rawValue = range.first + ((clamped - centerStart) / usable) *
                                (range.last - range.first)
                            return steppedValue(rawValue, atStart = atStart, atEnd = atEnd)
                        }

                        fun applyValue(value: Int) {
                            val next = value.coerceIn(range)
                            if (next != currentValue) {
                                currentValue = next
                                didChange = true
                                latestOnValueChange(next)
                            }
                        }

                        fun applyPosition(x: Float) {
                            applyValue(valueForPosition(x))
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            if (!dragging && abs(totalX) > touchSlop && abs(totalX) > abs(totalY)) {
                                dragging = true
                            }
                            if (dragging) {
                                applyPosition(change.position.x)
                                change.consume()
                            }
                        }

                        if (!dragging) {
                            when {
                                down.position.x <= endpointWidthPx -> {
                                    applyValue((currentValue - safeStep).coerceIn(range))
                                }

                                down.position.x >= size.width - endpointWidthPx -> {
                                    applyValue((currentValue + safeStep).coerceIn(range))
                                }

                                else -> applyPosition(down.position.x)
                            }
                        }

                        if (didChange) {
                            latestOnValueChangeFinished?.invoke()
                        }
                    }
                },
            shape = CircleShape,
            color = if (enabled) {
                palette.surfaceVariant
            } else {
                palette.surfaceVariant.copy(alpha = 0.46f)
            },
            contentColor = palette.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = if (enabled) 1.dp else 0.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepperEndpointText(
                        text = "-",
                        enabled = enabled && clampedValue > range.first,
                        palette = palette,
                        modifier = Modifier.width(endpointWidth)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    StepperEndpointText(
                        text = "+",
                        enabled = enabled && clampedValue < range.last,
                        palette = palette,
                        modifier = Modifier.width(endpointWidth)
                    )
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(thumbOffsetPx, 0) }
                        .size(thumbSize),
                    shape = CircleShape,
                    color = if (enabled) palette.surface else palette.surface.copy(alpha = 0.62f),
                    contentColor = palette.primaryText,
                    tonalElevation = 0.dp,
                    shadowElevation = if (enabled) 3.dp else 0.dp
                ) {}
            }
        }
    }
}

@Composable
private fun StepperEndpointText(
    text: String,
    enabled: Boolean,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) palette.accent else palette.secondaryText.copy(alpha = 0.36f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun LegadoMiuixFloatingPanel(
    visible: Boolean,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    width: Dp = 304.dp,
    cornerRadius: Dp? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val resolvedCornerRadius = cornerRadius ?: palette.panelRadius ?: LocalContext.current.composePanelRadius()
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = MIUIX_PANEL_ANIMATION_MS),
        label = "legadoMiuixFloatingPanel"
    )
    Surface(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
                translationY = (1f - progress) * 12f
            },
        shape = RoundedCornerShape(resolvedCornerRadius),
        color = palette.surface,
        contentColor = palette.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 玻璃面板内行底的不透明度（纯函数，便于单测锁定）。
 *
 * 入参取**面板自己的不透明度** `palette.surface.alpha`——[LegadoMiuixPalette.surface] 由
 * `AppDialogStyle` 按「对话框不透明度」上色（`ColorUtils.withAlpha(surfaceBase,
 * layoutAlpha)`），因此这里与面板同源，而不是另读一个全局偏好，避免出现「面板不透明度
 * 改了、行底不跟」的两份口径。
 *
 * **这个函数只负责「有底感」，不负责「画出边界」。** 原因是
 * [LegadoMiuixPalette.surfaceVariant] 的真实取值（`AppComposeDialogs.kt:161-182`）：
 * `fieldSurface = blendColors(surfaceBase, accent, night ? 0.10 : 0.05)` —— 只混了
 * 5%~10% 的 accent，与面板 `surface` 本就是**同族近色**；它的 alpha 原本是
 * `layoutAlpha + 0.08`（接近实底）。
 *
 * 于是历史取值 0.18~0.32 的真实效果不是「降一点点让玻璃透出来」，而是把一个接近不透明的
 * 行底**直接抹掉 70%+**：有效色差只剩 `0.18 × (5%~10%) ≈ 1%~2%`。真机现象就是
 * 「完全看不出选项有底面」——0.18 恰好是不可辨的那一档，那条下限的注释被真机证伪。
 * 行边界因此必须由 [LegadoMiuixChoiceRow] 的 1dp 描边承担（描边不受「同族近色」影响）。
 *
 * 上下限现在的分工：
 * - 下限 0.34：面板调得很透时行底仍有一点「有底」的观感；
 * - 上限 0.6：**不再上调**。色差本身只有 5%~10%，抬到 0.8 也换不来可见度，只会把玻璃
 *   盖成实底；可见度交给描边。
 */
internal fun translucentRowSurfaceAlpha(panelAlpha: Float): Float =
    (panelAlpha.coerceIn(0f, 1f) * 0.6f).coerceIn(0.34f, 0.6f)

@Composable
fun LegadoMiuixChoiceRow(
    text: String,
    selected: Boolean,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    minHeight: Dp = 40.dp,
    compact: Boolean = false,
    showSelectedMark: Boolean = true,
    enabled: Boolean = true,
    leadingIconName: String? = null,
    textAlign: TextAlign = TextAlign.Center,
    /**
     * 行底色让位给所在面板的玻璃层：仅选中态保留强调色，未选中行铺一层**半透明行色**
     * 而不是全透明。
     *
     * 只用于「面板本身已经是玻璃」的宿主（如 [io.legado.app.ui.widget.ModernActionPopup]），
     * 其余弹窗保持默认实底行色。
     *
     * **为什么不是 `Color.Transparent`**：玻璃面板上没有行底时，文字直接浮在活玻璃上，
     * 相邻选项之间没有可辨的边界，真机反馈为「菜单项没有各自的底面、不够凸显」。
     * 行底取 [LegadoMiuixPalette.surfaceVariant]（与实底面板里的行同一个颜色来源），
     * 只把 alpha 降下来（见 [translucentRowSurfaceAlpha]），并额外补一条 1dp 描边
     * （`border`），因此：
     * - 非玻璃档（`translucentSurface = false`）逐值不变，仍是原来的实底行色、无描边；
     * - 玻璃档下行有可见的圆角底面 + 细边，同时不把玻璃盖成实底。
     *
     * 描边是必需的：`surfaceVariant` 与面板 `surface` 是**同族近色**——`AppComposeDialogs.kt`
     * 里 `fieldSurface = blendColors(surfaceBase, accent, night ? 0.10 : 0.05)`，只混 5%~10%
     * 的 accent；而它原本的 alpha 是 `layoutAlpha + 0.08`（接近实底）。压低 alpha 的填充
     * 换不来可见度（有效色差只剩 2%~6%），边界必须由一条与面板底色**反向**的 1dp 线给出。
     */
    translucentSurface: Boolean = false,
    /**
     * 无论面板是否玻璃，都强制给这一行画 1dp 描边。
     *
     * 存在的理由是「部分页面有框、部分没有」这个真机反馈：行边界原先只挂在
     * [translucentSurface] 上，而它是 `glassLayer != null` 的派生值——同一个菜单组件，
     * 玻璃档能拿到采样源时有描边，拿不到时（宿主页面没有可采样子树、solid 档降级、
     * 面板尚未量到尺寸）就退回只剩 `surfaceVariant` 的 5%~10% 同族色差。那点色差在
     * 浅色主题上几乎不可辨，真机就是「选项没有背景框」。
     *
     * 弹出菜单（[io.legado.app.ui.widget.ModernActionPopup]）一律传 true：它是浮在内容
     * 之上的临时面板，每一行都必须自带可辨边界，不能依赖「这一档恰好有没有玻璃」。
     * 其余 40 多个对话框宿主保持默认 false，逐值不变。
     */
    outlined: Boolean = false
) {
    val actionRadius = palette.actionRadius ?: LocalContext.current.composeActionRadius()
    val contentAlpha = if (enabled) 1f else 0.42f
    val selectedColor = palette.accent.copy(alpha = contentAlpha)
    val primaryColor = palette.primaryText.copy(alpha = contentAlpha)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(actionRadius),
        color = when {
            selected -> palette.accent.copy(alpha = 0.14f)
            translucentSurface -> palette.surfaceVariant.copy(
                alpha = translucentRowSurfaceAlpha(palette.surface.alpha)
            )
            else -> palette.surfaceVariant
        },
        // 玻璃档下补一条 1dp 细描边 —— **行边界的主承重就是这一条**：行底
        // （`palette.surfaceVariant`）与面板 `surface` 是同族近色（只混 5%~10% 的 accent，
        // 见 `AppComposeDialogs.kt:161-182`），填充那条路即使抬到 0.6，有效色差也只有
        // 3%~6%，在磨砂底上仍显微弱；描边不受「同族近色」影响，是唯一能画出边界的手段。
        // [palette.primaryText] 是唯一在浅色 / 深色主题下都与面板底色**反向**的颜色
        // （浅色主题偏深、深色主题偏浅），因此两个主题下都必然可见，且不必引入第三个
        // 色源或新偏好。alpha 取 0.18（0.12 在真机磨砂底上偏淡）。
        // 非玻璃档传 null：`Surface` 的 `border` 默认就是 null，传 null 与改动前逐值等价
        // （不能写成 alpha=0 的描边——那仍会占位并可能画出一条透明线）。
        border = if (translucentSurface || outlined) {
            BorderStroke(1.dp, palette.primaryText.copy(alpha = 0.18f))
        } else {
            null
        },
        contentColor = if (selected) selectedColor else primaryColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(
                    horizontal = if (compact) 11.dp else 13.dp,
                    vertical = if (compact) 7.dp else 9.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIconName?.let { iconName ->
                LegadoResourceIcon(
                    iconName = iconName,
                    modifier = Modifier.size(if (compact) 28.dp else 34.dp)
                )
                Spacer(modifier = Modifier.width(if (compact) 9.dp else 12.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = when (textAlign) {
                    TextAlign.Start, TextAlign.Left -> Alignment.Start
                    TextAlign.End, TextAlign.Right -> Alignment.End
                    else -> Alignment.CenterHorizontally
                }
            ) {
                Text(
                    text = text,
                    color = if (selected) selectedColor else primaryColor,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = it,
                        color = palette.secondaryText.copy(alpha = contentAlpha),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showSelectedMark) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) Modifier.background(selectedColor)
                            else Modifier.border(1.5.dp, primaryColor.copy(alpha = 0.4f), CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.94f))
                        )
                    }
                }
            }
        }
    }
}

private object LegadoMiuixCenterPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = ((windowSize.width - popupContentSize.width) / 2)
            .coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val y = ((windowSize.height - popupContentSize.height) / 2)
            .coerceIn(0, max(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}

@Composable
fun <T> LegadoMiuixSelectField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    optionDescription: ((T) -> String)? = null,
    onSelected: (T) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    compact: Boolean = false,
    showSelectedMark: Boolean = true,
    popupTitle: String? = label,
    popupWidth: Dp = 304.dp
) {
    val resolvedCornerRadius = cornerRadius ?: palette.actionRadius ?: LocalContext.current.composeActionRadius()
    var expanded by remember { mutableStateOf(false) }
    var panelVisible by remember { mutableStateOf(false) }
    var transitionVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    fun dismissOptions() {
        val version = ++transitionVersion
        panelVisible = false
        scope.launch {
            delay(MIUIX_PANEL_DISMISS_MS)
            if (transitionVersion == version) {
                expanded = false
            }
        }
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "miuixSelectArrow"
    )
    val labelSpacing = if (compact) 5.dp else 7.dp
    val fieldHorizontalPadding = if (compact) 12.dp else 13.dp
    val fieldVerticalPadding = if (compact) 9.dp else 11.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(labelSpacing)
    ) {
        Text(
            text = label,
            color = palette.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (expanded) {
                        dismissOptions()
                    } else {
                        transitionVersion++
                        expanded = true
                    }
            },
            color = palette.surface,
            contentColor = palette.primaryText,
            cornerRadius = resolvedCornerRadius,
            insidePadding = PaddingValues(
                horizontal = fieldHorizontalPadding,
                vertical = fieldVerticalPadding
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = optionLabel(selected),
                        color = palette.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    optionDescription?.invoke(selected)?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = it,
                            color = palette.secondaryText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier.width(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_expand_more),
                        contentDescription = null,
                        tint = palette.secondaryText,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = arrowRotation }
                    )
                }
            }
        }
    }
    if (expanded) {
        Popup(
            popupPositionProvider = LegadoMiuixCenterPopupPositionProvider,
            onDismissRequest = { dismissOptions() },
            properties = PopupProperties(focusable = true)
        ) {
            LaunchedEffect(Unit) {
                panelVisible = true
            }
            LegadoMiuixFloatingPanel(
                visible = panelVisible,
                palette = palette,
                width = popupWidth,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                popupTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = palette.primaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    options.forEach { option ->
                        val isSelected = option == selected
                        LegadoMiuixChoiceRow(
                            text = optionLabel(option),
                            selected = isSelected,
                            palette = palette,
                            onClick = {
                                onSelected(option)
                                dismissOptions()
                            },
                            description = optionDescription?.invoke(option),
                            minHeight = if (compact) 38.dp else 42.dp,
                            compact = compact,
                            showSelectedMark = showSelectedMark
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LegadoMiuixSection(
    title: String,
    palette: LegadoMiuixPalette,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = cornerRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = palette.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
fun LegadoMiuixActionRow(
    text: String,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    danger: Boolean = false,
    cornerRadius: Dp? = null
) {
    val resolvedCornerRadius = cornerRadius ?: palette.actionRadius ?: LocalContext.current.composeActionRadius()
    LegadoMiuixCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (danger) palette.danger.copy(alpha = 0.12f) else palette.surfaceVariant,
        contentColor = if (danger) palette.danger else palette.primaryText,
        cornerRadius = resolvedCornerRadius,
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Column {
            Text(
                text = text,
                color = if (danger) palette.danger else palette.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            description?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = it,
                    color = palette.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
