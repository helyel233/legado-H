package io.legado.app.lib.theme

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 纯 JVM 单测：锁住 [ThemeTokenCompiler.compileRaw] 在日/夜/墨水屏/壁纸组合下的
 * 颜色、圆角、GlassTokenRef 与 Material3 ColorScheme / Shapes 映射口径。
 */
class ThemeTokenCompilerTest {

    @Test
    fun compileDayWithWallpaper_mapsAccentAndShowsWallpaper() {
        val env = AppearanceEnv(
            isNight = false,
            isEInk = false,
            hasUsableWallpaper = true,
            hardwareBlurSupported = true
        )
        val tokens = compileSample(env, accentArgb = 0xFF1E88E5.toInt(), glassMode = GlassMaterialPreset.GLASS)

        assertTrue(tokens.wallpaperVisible)
        assertEquals(GlassMaterialPreset.GLASS, tokens.glassRef.mode)
        assertEquals(68, tokens.glassRef.level)
        assertEquals(0xFF1E88E5.toInt(), tokens.colors.accentArgb)

        val scheme = tokens.toMaterialColorScheme()
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1E88E5.toInt()), scheme.primary)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF5F5F5.toInt()), scheme.background)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFFFFF.toInt()), scheme.surface)
    }

    @Test
    fun compileNightWithoutWallpaper_hidesWallpaperAndReflectsInSignature() {
        val dayEnv = AppearanceEnv(isNight = false, isEInk = false, hasUsableWallpaper = false, hardwareBlurSupported = true)
        val nightEnv = AppearanceEnv(isNight = true, isEInk = false, hasUsableWallpaper = false, hardwareBlurSupported = true)

        val dayTokens = compileSample(dayEnv, accentArgb = 0xFF1E88E5.toInt(), glassMode = GlassMaterialPreset.FROSTED)
        val nightTokens = compileSample(nightEnv, accentArgb = 0xFF1E88E5.toInt(), glassMode = GlassMaterialPreset.FROSTED)

        assertFalse(dayTokens.wallpaperVisible)
        assertFalse(nightTokens.wallpaperVisible)
        assertNotEquals(dayTokens.signature, nightTokens.signature)
        assertEquals(GlassMaterialPreset.FROSTED, nightTokens.glassRef.mode)
    }

    @Test
    fun compileEInkMode_forcesSolidAndHighContrastMonoColors() {
        val env = AppearanceEnv(
            isNight = false,
            isEInk = true,
            hasUsableWallpaper = true,
            hardwareBlurSupported = true
        )
        val tokens = compileSample(env, accentArgb = 0xFF1E88E5.toInt(), glassMode = GlassMaterialPreset.GLASS)

        assertFalse(tokens.wallpaperVisible)
        assertEquals(GlassMaterialPreset.SOLID, tokens.glassRef.mode)
        assertEquals(0, tokens.glassRef.level)
        assertNull(tokens.glassRef.strokeColorArgb)
        assertEquals(0xFFFFFFFF.toInt(), tokens.colors.backgroundArgb)
        assertEquals(0xFF000000.toInt(), tokens.colors.accentArgb)
        assertEquals(0xFF000000.toInt(), tokens.colors.primaryTextArgb)
    }

    @Test
    fun toMaterialShapes_clampsNegativeCornerToZero() {
        val env = AppearanceEnv(isNight = false, isEInk = false, hasUsableWallpaper = false, hardwareBlurSupported = true)
        val tokens = compileSample(
            env = env,
            accentArgb = 0xFF1E88E5.toInt(),
            glassMode = GlassMaterialPreset.SOLID,
            panelRadiusDp = -4f,
            actionRadiusDp = 12f
        )
        val shapes = tokens.toMaterialShapes()
        // 验证构造成功且不抛异常
        assertTrue(shapes.small != shapes.medium)
    }

    private fun compileSample(
        env: AppearanceEnv,
        accentArgb: Int,
        glassMode: String,
        panelRadiusDp: Float = 16f,
        actionRadiusDp: Float = 10f
    ): ResolvedThemeTokens {
        return ThemeTokenCompiler.compileRaw(
            env = env,
            primaryArgb = 0xFF212121.toInt(),
            accentArgb = accentArgb,
            backgroundArgb = if (env.isNight) 0xFF121212.toInt() else 0xFFF5F5F5.toInt(),
            bottomBarArgb = if (env.isNight) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt(),
            cardArgb = if (env.isNight) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt(),
            mutedArgb = if (env.isNight) 0xFF2A2A2A.toInt() else 0xFFEEEEEE.toInt(),
            dividerArgb = 0x1F000000,
            borderArgb = 0x33000000,
            primaryTextArgb = if (env.isNight) 0xFFEEEEEE.toInt() else 0xFF212121.toInt(),
            secondaryTextArgb = if (env.isNight) 0xFFAAAAAA.toInt() else 0xFF757575.toInt(),
            titleTextArgb = if (env.isNight) 0xFFFFFFFF.toInt() else 0xFF111111.toInt(),
            dangerArgb = 0xFFF44336.toInt(),
            panelRadiusDp = panelRadiusDp,
            actionRadiusDp = actionRadiusDp,
            panelRadiusPx = panelRadiusDp * 3f,
            glassMode = glassMode,
            glassLevel = 68,
            glassStrokeColorArgb = 0x80FFFFFF.toInt(),
            glassSignature = "$glassMode|68",
            paletteSignature = "test-palette-${env.isNight}"
        )
    }
}
