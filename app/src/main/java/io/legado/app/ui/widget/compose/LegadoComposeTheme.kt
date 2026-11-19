package io.legado.app.ui.widget.compose

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
 * 统一的 Compose 主题契约（颜色 / 圆角 / 字体 / 运行时 Token）。
 *
 * 幂等守卫：若外层已经注入过 [LocalLegadoTokens]，则直接透传 [content]，避免嵌套
 * 重复订阅 SP 监听与重复构造 MaterialTheme。
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
    val colorScheme = remember(tokens.signature) { tokens.toMaterialColorScheme() }
    val shapes = remember(tokens.corners) { tokens.toMaterialShapes() }
    val typography = MaterialTheme.typography
    val themedTypography = remember(typography, bodyFamily, titleFamily) {
        typography.withUiFontFamilies(bodyFamily, titleFamily)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = themedTypography
    ) {
        CompositionLocalProvider(
            LocalLegadoTokens provides tokens,
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = bodyFamily)
        ) {
            content()
        }
    }
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
