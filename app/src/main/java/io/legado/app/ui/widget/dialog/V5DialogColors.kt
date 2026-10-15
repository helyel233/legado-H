package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors

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

/**
 * 旧 AppDialogStyle 的 V5 换源版：字段名保持不变（surface/fieldSurface/primaryText/...
 * /panelRadius/actionRadius/bodyFontFamily/titleFontFamily），取值全部改自 V5 运行时
 * （LocalSemanticColors / LayoutResolver 形状 / ThemeResolver 字体），
 * 使参数串联型消费者零改动吃到 V5 主题源。调用方清零后随旧库整体删除。
 */
@Stable
data class V5DialogColors(
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
fun rememberV5DialogColors(): V5DialogColors {
    val context = LocalContext.current
    val colors = LocalSemanticColors.current
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
    // 字体入键：主题包仅换字体时也要重建
    val bodyFont = io.legado.app.uikit.v5.resolve.ThemeResolver.uiFontFamily()
        ?: FontFamily(context.uiTypeface())
    val titleFont = io.legado.app.uikit.v5.resolve.ThemeResolver.titleFontFamily()
        ?: FontFamily(context.titleTypeface())
    return remember(
        colors, v5Spec, panelRadius, actionRadius, bodyFont, titleFont
    ) {
        V5DialogColors(
            accent = colors.primary,
            onAccent = colors.onPrimary,
            surface = colors.surface,
            fieldSurface = colors.surfaceVariant,
            primaryText = colors.onSurface,
            secondaryText = colors.onSurfaceVariant,
            stroke = colors.outline,
            danger = colors.error,
            panelRadius = panelRadius,
            actionRadius = actionRadius,
            bodyFontFamily = bodyFont,
            titleFontFamily = titleFont
        )
    }
}
