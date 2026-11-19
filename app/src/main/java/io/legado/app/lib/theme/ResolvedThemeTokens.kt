package io.legado.app.lib.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 材质与毛玻璃轻量引用（遵守 R2：唯一参数表在 GlassMaterialPreset）。
 *
 * 这里只记录当前生效档位、强度与描边色，渲染层需要模糊半径 / 着色 / 折射 / 壳层 alpha 时
 * 必须回查 `GlassMaterialPreset`，严禁在此处或调用方复制第二份参数表。
 */
@Immutable
data class GlassTokenRef(
    val mode: String,
    val level: Int,
    val strokeColorArgb: Int?,
    val signature: String
)

@Immutable
data class ThemeTokenColors(
    val primaryArgb: Int,
    val onPrimaryArgb: Int,
    val accentArgb: Int,
    val onAccentArgb: Int,
    val backgroundArgb: Int,
    val bottomBarArgb: Int,
    val bottomBarTextArgb: Int,
    val cardArgb: Int,
    val mutedArgb: Int,
    val dividerArgb: Int,
    val borderArgb: Int?,
    val primaryTextArgb: Int,
    val secondaryTextArgb: Int,
    val titleTextArgb: Int,
    val dangerArgb: Int
)

@Immutable
data class ThemeTokenCorners(
    val panelRadiusDp: Float,
    val actionRadiusDp: Float,
    val panelRadiusPx: Float
)

/**
 * 全 App Compose 与 View 桥接层共享的不可变视觉 Token 快照。
 */
@Immutable
data class ResolvedThemeTokens(
    val env: AppearanceEnv,
    val colors: ThemeTokenColors,
    val corners: ThemeTokenCorners,
    val wallpaperVisible: Boolean,
    val glassRef: GlassTokenRef,
    val paletteSignature: String,
    val signature: String
) {
    fun toMaterialColorScheme(): ColorScheme {
        val primary = Color(colors.primaryArgb)
        val onPrimary = Color(colors.onPrimaryArgb)
        val accent = Color(colors.accentArgb)
        val onAccent = Color(colors.onAccentArgb)
        val background = Color(colors.backgroundArgb)
        val surface = Color(colors.cardArgb)
        val surfaceVariant = Color(colors.mutedArgb)
        val onBackground = Color(colors.primaryTextArgb)
        val onSurface = Color(colors.primaryTextArgb)
        val onSurfaceVariant = Color(colors.secondaryTextArgb)
        val outline = Color(colors.borderArgb ?: colors.dividerArgb)
        val outlineVariant = Color(colors.dividerArgb)
        val error = Color(colors.dangerArgb)

        return if (env.isNight) {
            darkColorScheme(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = primary,
                onPrimaryContainer = onPrimary,
                secondary = accent,
                onSecondary = onAccent,
                secondaryContainer = surfaceVariant,
                onSecondaryContainer = onSurface,
                tertiary = accent,
                onTertiary = onAccent,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = error,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = primary,
                onPrimaryContainer = onPrimary,
                secondary = accent,
                onSecondary = onAccent,
                secondaryContainer = surfaceVariant,
                onSecondaryContainer = onSurface,
                tertiary = accent,
                onTertiary = onAccent,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = error,
                onError = Color.White
            )
        }
    }

    fun toMaterialShapes(): Shapes {
        val actionDp: Dp = corners.actionRadiusDp.coerceAtLeast(0f).dp
        val panelDp: Dp = corners.panelRadiusDp.coerceAtLeast(0f).dp
        return Shapes(
            extraSmall = RoundedCornerShape((actionDp * 0.5f).coerceAtLeast(2.dp)),
            small = RoundedCornerShape(actionDp),
            medium = RoundedCornerShape(panelDp),
            large = RoundedCornerShape(panelDp),
            extraLarge = RoundedCornerShape(panelDp)
        )
    }
}
