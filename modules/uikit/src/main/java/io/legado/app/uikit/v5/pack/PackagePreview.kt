package io.legado.app.uikit.v5.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.resolvePalette
import io.legado.app.uikit.v5.resolve.ThemeResolver

/**
 * P3-3 包卡片的预览色卡：从主题包派生三个展示色（主色 / 强调 / 表面）。
 * 解析失败给中性灰，绝不崩。
 */
@Composable
fun packSeedColors(spec: ThemePackageSpec): List<Color> {
    return try {
        val p = spec.resolvePalette(dark = ThemeResolver.isDark)
        listOf(
            Color(p[PaletteRole.PRIMARY] ?: NEUTRAL),
            Color(p[PaletteRole.TERTIARY] ?: NEUTRAL),
            Color(p[PaletteRole.SURFACE] ?: NEUTRAL),
        )
    } catch (_: Exception) {
        listOf(Color(0xFF808080), Color(0xFF9E9E9E), Color(0xFFBDBDBD))
    }
}

private const val NEUTRAL = 0xFF808080.toInt()
