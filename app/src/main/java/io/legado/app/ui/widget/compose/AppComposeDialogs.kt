package io.legado.app.ui.widget.compose

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.uikit.layout.LayoutEngine
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.themeDividerColorOrDefault
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils
import kotlin.math.roundToInt

@Composable
internal fun DismissWhenCallbackMissing(
    missing: Boolean,
    dismiss: () -> Unit
) {
    LaunchedEffect(missing) {
        if (missing) {
            dismiss()
        }
    }
}

@Stable
data class AppDialogStyle(
    val accent: Color,
    val onAccent: Color,
    val surface: Color,
    val fieldSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val stroke: Color,
    val danger: Color,
    val panelRadius: androidx.compose.ui.unit.Dp,
    val actionRadius: androidx.compose.ui.unit.Dp,
    val bodyFontFamily: FontFamily,
    val titleFontFamily: FontFamily
)

@Composable
fun rememberAppDialogStyle(): AppDialogStyle {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val layoutAlpha = if (AppConfig.isEInkMode) {
        1f
    } else {
        io.legado.app.uikit.layout.LayoutEngine.glassAlpha.coerceIn(0.05f, 1f)
    }
    val themeUiPalette = rememberThemeUiPalette()
    val customSurface = themeUiPalette.cardColor.takeIf { themeUiPalette.hasCustomCardColor }
    val customFieldSurface = themeUiPalette.mutedColor.takeIf { themeUiPalette.hasCustomMutedColor }
    val surfaceBase = customSurface ?: context.bottomBackground
    val fieldSurface = customFieldSurface ?: ColorUtils.blendColors(
        surfaceBase,
        accent,
        if (night) 0.10f else 0.05f
    )
    val fieldAlpha = (layoutAlpha + if (night) 0.10f else 0.08f).coerceIn(0f, 1f)
    val stroke = UiCorner.panelBorderColor(context)
        ?: if (customSurface != null || customFieldSurface != null) {
            context.themeDividerColorOrDefault()
        } else {
            UiCorner.effectStrokeColor(surfaceBase)
        }
    // 文字色按对话框实际背景明暗推导，而非全局 night 标志，避免深底深字/浅底白字
    // 形状跟随 V5 形状包（V5 包库是主题唯一真相，颜色已由 V5ThemeSync 同步，
    // 这里补齐形状）：面板取卡片角、动作按钮取按钮角；胶囊→大半径近似，
    // 斜切角对对话框形态畸变，回落旧半径。
    val v5Spec = io.legado.app.uikit.v5.resolve.LayoutResolver.current
    val v5Scale = v5Spec.shapeScale.coerceIn(io.legado.app.uikit.v5.token.SHAPE_SCALE_RANGE)
    val panelRadius = when (val c = v5Spec.shapeFamily.card) {
        is io.legado.app.uikit.v5.token.CornerSpec.Radius -> c.dp * v5Scale
        else -> context.composePanelRadius()
    }
    val actionRadius = when (val c = v5Spec.shapeFamily.button) {
        is io.legado.app.uikit.v5.token.CornerSpec.Radius -> c.dp * v5Scale
        is io.legado.app.uikit.v5.token.CornerSpec.Pill -> 100.dp
        else -> context.composeActionRadius()
    }
    return AppDialogStyle(
        accent = Color(accent),
        onAccent = if (ColorUtils.isColorLight(accent)) Color.Black else Color.White,
        surface = Color(ColorUtils.withAlpha(surfaceBase, layoutAlpha)),
        fieldSurface = Color(ColorUtils.withAlpha(fieldSurface, fieldAlpha)),
        primaryText = Color(context.primaryTextColor),
        secondaryText = Color(context.secondaryTextColor),
        stroke = Color(stroke),
        danger = Color(ContextCompat.getColor(context, R.color.md_red_500)),
        panelRadius = panelRadius,
        actionRadius = actionRadius,
        bodyFontFamily = FontFamily(context.uiTypeface()),
        titleFontFamily = FontFamily(context.titleTypeface())
    )
}

@Composable
fun AppDialogFrame(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    scrollContent: Boolean = true,
    messageInContent: Boolean = false,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val style = rememberAppDialogStyle()
    val frameShape = RoundedCornerShape(style.panelRadius)
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .shadow(14.dp, frameShape, clip = false)
                .border(1.dp, style.stroke, frameShape),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!message.isNullOrBlank() && !messageInContent) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AppDialogMessageText(message = message, style = style)
                }
                Spacer(modifier = Modifier.height(16.dp))
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                val shouldScrollContent = scrollContent || messageInContent
                if (shouldScrollContent) {
                    Column(
                        modifier = contentModifier.verticalScroll(rememberScrollState())
                    ) {
                        AppDialogContent(
                            message = message,
                            messageInContent = messageInContent,
                            style = style,
                            content = content
                        )
                    }
                } else {
                    Column(modifier = contentModifier) {
                        AppDialogContent(
                            message = message,
                            messageInContent = messageInContent,
                            style = style,
                            content = content
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun AppDialogContent(
    message: String?,
    messageInContent: Boolean,
    style: AppDialogStyle,
    content: @Composable () -> Unit
) {
    if (!message.isNullOrBlank() && messageInContent) {
        AppDialogMessageText(message = message, style = style)
    }
    content()
}

@Composable
private fun AppDialogMessageText(
    message: String,
    style: AppDialogStyle
) {
    SelectionContainer {
        Text(
            text = message,
            color = style.secondaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun AppDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    minLines: Int = 1,
    maxLines: Int = 1,
    singleLine: Boolean = false,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        label.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = style.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = maxLines,
            singleLine = singleLine,
            readOnly = readOnly,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                disabledTextColor = style.secondaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                disabledContainerColor = style.fieldSurface.copy(alpha = 0.58f),
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke,
                disabledBorderColor = style.stroke.copy(alpha = 0.38f),
                focusedPlaceholderColor = style.secondaryText,
                unfocusedPlaceholderColor = style.secondaryText
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}

@Composable
fun AppDialogSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = if (checked) style.primaryText else style.secondaryText,
                fontSize = 15.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(10.dp))
            LegadoMiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                palette = palette
            )
        }
    }
}

@Composable
fun AppDialogOptionGroup(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = style.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, label ->
                LegadoMiuixChoiceRow(
                    text = label,
                    selected = selectedIndex == index,
                    palette = palette,
                    onClick = { onSelected(index) },
                    minHeight = 40.dp,
                    compact = true
                )
            }
        }
    }
}

@Composable
fun AppDialogSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showStepper: Boolean = false,
    step: Int = 1,
    valueFormatter: (Int) -> String = { it.toString() },
    onValueChangeFinished: (() -> Unit)? = null
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val safeStep = step.coerceAtLeast(1)
    val safeValue = value.coerceIn(range)
    val displayValue = valueFormatter(safeValue)
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(
            horizontal = if (compact) 11.dp else 13.dp,
            vertical = if (compact) 8.dp else 10.dp
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = style.primaryText,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = displayValue,
                    color = style.secondaryText,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))
            AppThemedStepperSlider(
                value = safeValue,
                range = range,
                onValueChange = { onValueChange(it.coerceIn(range)) },
                palette = palette,
                step = safeStep,
                onValueChangeFinished = onValueChangeFinished
            )
        }
    }
}

data class AppDialogSliderItem(
    val title: String,
    val value: Int,
    val range: IntRange,
    val onValueChange: (Int) -> Unit,
    val showStepper: Boolean = false,
    val step: Int = 1,
    val valueFormatter: (Int) -> String = { it.toString() },
    val onValueChangeFinished: (() -> Unit)? = null
)

@Composable
fun AppDialogSliderGrid(
    items: List<AppDialogSliderItem>,
    modifier: Modifier = Modifier,
    minTwoColumnWidth: Dp = 330.dp
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= minTwoColumnWidth
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (twoColumns) {
                items.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            AppDialogSliderRow(
                                title = item.title,
                                value = item.value,
                                range = item.range,
                                onValueChange = item.onValueChange,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                showStepper = item.showStepper,
                                step = item.step,
                                valueFormatter = item.valueFormatter,
                                onValueChangeFinished = item.onValueChangeFinished
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items.forEach { item ->
                    AppDialogSliderRow(
                        title = item.title,
                        value = item.value,
                        range = item.range,
                        onValueChange = item.onValueChange,
                        showStepper = item.showStepper,
                        step = item.step,
                        valueFormatter = item.valueFormatter,
                        onValueChangeFinished = item.onValueChangeFinished
                    )
                }
            }
        }
    }
}

/**
 * 模型选择弹窗（V5 基座版，旧 ComposeDialogFragment 退役批最后一个子类）。
 * 尺寸：旧 AppDialogSize.Management → widthFraction 0.96f / maxWidthDp 700。
 * 内容沿用 FetchedModelSelectorContent（旧桥 rememberAppDialogStyle 只吃主题键 +
 * V5 形状镜像，不依赖 LegadoComposeTheme，在 V5 壳下渲染正常）。
 * 行为保留：`onAddSingle == null` 时自动 dismiss（DismissWhenCallbackMissing）。
 */
internal class ComposeFetchedModelDialog : V5DialogFragment() {

    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private var onAddSingle: ((String) -> Unit)? = null
    private var onAddSelected: ((List<String>) -> Unit)? = null
    private var onAddAll: ((List<String>) -> Unit)? = null

    @Composable
    override fun Content() {
        DismissWhenCallbackMissing(
            missing = onAddSingle == null,
            dismiss = ::dismissAllowingStateLoss
        )
        val args = arguments ?: Bundle()
        val modelIds = remember {
            args.getStringArrayList(ARG_MODEL_IDS)?.toList().orEmpty()
        }
        val existingIds = remember {
            args.getStringArrayList(ARG_EXISTING_IDS)?.toSet().orEmpty()
        }
        io.legado.app.ui.host.FetchedModelSelectorContent(
            modelIds = modelIds,
            existingIds = existingIds,
            onAddSingle = { modelId ->
                dismissAllowingStateLoss()
                onAddSingle?.invoke(modelId)
            },
            onAddSelected = { selectedIds ->
                dismissAllowingStateLoss()
                onAddSelected?.invoke(selectedIds)
            },
            onAddAll = { allIds ->
                dismissAllowingStateLoss()
                onAddAll?.invoke(allIds)
            },
            onDismiss = { dismissAllowingStateLoss() }
        )
    }

    companion object {
        fun create(
            modelIds: List<String>,
            existingIds: Set<String>,
            onAddSingle: (String) -> Unit,
            onAddSelected: (List<String>) -> Unit,
            onAddAll: (List<String>) -> Unit
        ): ComposeFetchedModelDialog {
            return ComposeFetchedModelDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_MODEL_IDS, ArrayList(modelIds))
                    putStringArrayList(ARG_EXISTING_IDS, ArrayList(existingIds))
                }
                this.onAddSingle = onAddSingle
                this.onAddSelected = onAddSelected
                this.onAddAll = onAddAll
            }
        }

        private const val ARG_MODEL_IDS = "modelIds"
        private const val ARG_EXISTING_IDS = "existingIds"
    }
}
