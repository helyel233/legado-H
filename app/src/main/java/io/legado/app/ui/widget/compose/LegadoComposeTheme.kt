package io.legado.app.ui.widget.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import io.legado.app.lib.theme.ResolvedLayoutTokens
import io.legado.app.lib.theme.ResolvedThemeTokens
import io.legado.app.lib.theme.rememberResolvedThemeTokens
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface

/**
 * 全 App Compose 共享的运行时视觉 Token 快照。
 * 由 [LegadoComposeTheme] 统一注入；未包裹时为 null。
 */
val LocalLegadoTokens = staticCompositionLocalOf<ResolvedThemeTokens?> { null }

/**
 * 动态布局密度与空间基准 Token（由 @layout densityScale 编译产出）。
 */
val LocalLayoutTokens = staticCompositionLocalOf<ResolvedLayoutTokens> {
    ResolvedLayoutTokens.fromDensityScale(1f)
}

/**
 * 统一的 Compose 主题契约（颜色 / 圆角 / 字体 / 运行时 Token）。
 *
 * - 支持 Compose 页面零 `recreate()` 实时热更；
 * - 常规屏通过 [animateColorAsState] 平滑过渡，墨水屏强制 `0ms` 直切（E-Ink 红线）。
 * - 幂等守卫：若外层已经注入过 [LocalLegadoTokens]，则直接透传 [content]。
 */
@Composable
fun LegadoComposeTheme(content: @Composable () -> Unit) {
    if (LocalLegadoTokens.current != null) {
        content()
        return
    }
    val context = LocalContext.current
    val tokens = rememberResolvedThemeTokens()
    val bodyFamily = remember(context, tokens.paletteSignature) { FontFamily(context.uiTypeface()) }
    val titleFamily = remember(context, tokens.paletteSignature) { FontFamily(context.titleTypeface()) }
    val targetColorScheme = remember(tokens.signature) { tokens.toMaterialColorScheme() }
    val animatedColorScheme = animateColorScheme(
        target = targetColorScheme,
        durationMs = tokens.transitionDurationMs(180)
    )
    val shapes = remember(tokens.corners) { tokens.toMaterialShapes() }
    val typography = MaterialTheme.typography
    val themedTypography = remember(typography, bodyFamily, titleFamily) {
        typography.withUiFontFamilies(bodyFamily, titleFamily)
    }
    MaterialTheme(
        colorScheme = animatedColorScheme,
        shapes = shapes,
        typography = themedTypography
    ) {
        CompositionLocalProvider(
            LocalLegadoTokens provides tokens,
            LocalLayoutTokens provides tokens.layout,
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = bodyFamily)
        ) {
            content()
        }
    }
}

@Composable
private fun animateColorScheme(target: ColorScheme, durationMs: Int): ColorScheme {
    if (durationMs <= 0) return target
    val spec = remember(durationMs) { tween<Color>(durationMillis = durationMs) }
    val primary by animateColorAsState(target.primary, animationSpec = spec, label = "themePrimary")
    val onPrimary by animateColorAsState(target.onPrimary, animationSpec = spec, label = "themeOnPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, animationSpec = spec, label = "themePrimaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, animationSpec = spec, label = "themeOnPrimaryContainer")
    val background by animateColorAsState(target.background, animationSpec = spec, label = "themeBackground")
    val onBackground by animateColorAsState(target.onBackground, animationSpec = spec, label = "themeOnBackground")
    val surface by animateColorAsState(target.surface, animationSpec = spec, label = "themeSurface")
    val onSurface by animateColorAsState(target.onSurface, animationSpec = spec, label = "themeOnSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, animationSpec = spec, label = "themeSurfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, animationSpec = spec, label = "themeOnSurfaceVariant")
    val outline by animateColorAsState(target.outline, animationSpec = spec, label = "themeOutline")
    val outlineVariant by animateColorAsState(target.outlineVariant, animationSpec = spec, label = "themeOutlineVariant")
    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = onSurface,
        tertiary = primary,
        onTertiary = onPrimary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant
    )
}

/** 把界面字体设为正文/标签默认，标题字体设为 display/headline/titleLarge。 */
private fun Typography.withUiFontFamilies(body: FontFamily, title: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = title),
    displayMedium = displayMedium.copy(fontFamily = title),
    displaySmall = displaySmall.copy(fontFamily = title),
    headlineLarge = headlineLarge.copy(fontFamily = title),
    headlineMedium = headlineMedium.copy(fontFamily = title),
    headlineSmall = headlineSmall.copy(fontFamily = title),
    titleLarge = titleLarge.copy(fontFamily = title),
    titleMedium = titleMedium.copy(fontFamily = body),
    titleSmall = titleSmall.copy(fontFamily = body),
    bodyLarge = bodyLarge.copy(fontFamily = body),
    bodyMedium = bodyMedium.copy(fontFamily = body),
    bodySmall = bodySmall.copy(fontFamily = body),
    labelLarge = labelLarge.copy(fontFamily = body),
    labelMedium = labelMedium.copy(fontFamily = body),
    labelSmall = labelSmall.copy(fontFamily = body)
)
