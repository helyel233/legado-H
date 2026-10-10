package io.legado.app.uikit.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.legado.app.uikit.layout.LayoutEngine
import io.legado.app.uikit.token.AppTypeScale

/**
 * App theme bridge: maps [AppColorScheme] onto Material3.
 * Also provides the global corner scale via [LocalAppRadiusScale].
 * A2-1: radiusScale defaults to the layout engine's shape scale — callers
 * that don't pass it explicitly follow the active layout package.
 */
@Composable
fun AppTheme(
    scheme: AppColorScheme,
    radiusScale: Float = -1f,
    content: @Composable () -> Unit,
) {
    val effectiveRadiusScale = if (radiusScale >= 0f) radiusScale else LayoutEngine.radiusScale
    val base = if (scheme.isDark) darkColorScheme() else lightColorScheme()
    val m3 = base.copy(
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        secondary = scheme.secondary,
        onSecondary = scheme.onSecondary,
        surface = scheme.surface,
        onSurface = scheme.onSurface,
        surfaceVariant = scheme.surfaceVariant,
        background = scheme.background,
        onBackground = scheme.onBackground,
        outline = scheme.outline,
        error = scheme.error,
    )
    val typography = remember { AppTypeScale.typography() }
    CompositionLocalProvider(
        LocalAppColorScheme provides scheme,
        LocalAppRadiusScale provides effectiveRadiusScale,
    ) {
        MaterialTheme(colorScheme = m3, typography = typography, content = content)
    }
}
