package io.legado.app.uikit.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App semantic color scheme (docs/ui-rewrite-plan.md 6.5.1).
 * Stable contract that the future `modules/theme` package will provide.
 * Until P0a lands, the app injects its palette via [AppColorProvider].
 */
@Immutable
data class AppColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val background: Color,
    val onBackground: Color,
    val outline: Color,
    val muted: Color,
    val error: Color,
    val readerText: Color,
    val readerBackground: Color,
    val isDark: Boolean,
)

fun lightAppColorScheme(): AppColorScheme = AppColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    secondary = Color(0xFF5C6BC0),
    onSecondary = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF0F0F4),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),
    outline = Color(0xFFE0E0E0),
    muted = Color(0xFF8A8A8E),
    error = Color(0xFFB3261E),
    readerText = Color(0xFF1C1B1F),
    readerBackground = Color(0xFFF5F1E8),
    isDark = false,
)

fun darkAppColorScheme(): AppColorScheme = AppColorScheme(
    primary = Color(0xFF8C9EFF),
    onPrimary = Color(0xFF10122B),
    secondary = Color(0xFF9FA8DA),
    onSecondary = Color(0xFF10122B),
    surface = Color(0xFF1E1E22),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2A30),
    background = Color(0xFF121216),
    onBackground = Color(0xFFE6E1E5),
    outline = Color(0xFF33333A),
    muted = Color(0xFF9E9EA4),
    error = Color(0xFFF2B8B5),
    readerText = Color(0xFFCCC8C0),
    readerBackground = Color(0xFF121212),
    isDark = true,
)

val LocalAppColorScheme = staticCompositionLocalOf { lightAppColorScheme() }

/** Global corner scale (user setting uiCornerScale), injected by the app. */
val LocalAppRadiusScale = staticCompositionLocalOf { 1f }
