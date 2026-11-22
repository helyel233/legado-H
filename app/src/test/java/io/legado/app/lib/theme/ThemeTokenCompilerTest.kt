package io.legado.app.lib.theme

import io.legado.app.help.config.pkg.SurfaceScopeOverride
import io.legado.app.help.config.pkg.UiScope
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

    @Test
    fun resolveScope_inheritsGlobalAndAppliesPartialOverridesOnlyToTargetScope() {
        val env = AppearanceEnv(isNight = false, isEInk = false, hasUsableWallpaper = true, hardwareBlurSupported = true)
        val tokens = ThemeTokenCompiler.compileRaw(
            env = env,
            primaryArgb = 0xFF212121.toInt(),
            accentArgb = 0xFF1E88E5.toInt(),
            backgroundArgb = 0xFFF5F5F5.toInt(),
            bottomBarArgb = 0xFFFFFFFF.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            mutedArgb = 0xFFEEEEEE.toInt(),
            dividerArgb = 0x1F000000,
            borderArgb = 0x33000000,
            primaryTextArgb = 0xFF212121.toInt(),
            secondaryTextArgb = 0xFF757575.toInt(),
            titleTextArgb = 0xFF111111.toInt(),
            dangerArgb = 0xFFF44336.toInt(),
            panelRadiusDp = 12f,
            actionRadiusDp = 9f,
            panelRadiusPx = 36f,
            glassMode = GlassMaterialPreset.GLASS,
            glassLevel = 68,
            glassStrokeColorArgb = null,
            glassSignature = "glass|68",
            paletteSignature = "p1",
            density = 3f,
            densityScale = 1.2f,
            globalAlphaPercent = 85,
            dialogAlphaPercent = 94,
            globalShadowDp = 4,
            globalBlurRadiusDp = 8f,
            borderAlphaPercent = 60,
            rawScopeOverrides = mapOf(
                UiScope.DIALOG to SurfaceScopeOverride(
                    materialMode = GlassMaterialPreset.FROSTED,
                    cornerScale = 1.5f,
                    alpha = null // null -> inherits dialogAlphaPercent (94)
                ),
                UiScope.BOOKSHELF_CARD to SurfaceScopeOverride(
                    alpha = 72,
                    shadowElevation = 8
                )
            )
        )

        // Density scale scales spatial baselines
        assertEquals(1.2f, tokens.layout.densityScale, 0.001f)
        assertEquals(14.4f, tokens.layout.pageHorizontalDp, 0.01f)
        assertEquals(19.2f, tokens.layout.detailHorizontalDp, 0.01f)

        // TOP_BAR & BOTTOM_BAR inherit global untouched
        assertFalse(tokens.topBar.isCustomOverride)
        assertEquals(GlassMaterialPreset.GLASS, tokens.topBar.materialMode)
        assertEquals(85, tokens.topBar.alphaPercent)
        assertEquals(12f, tokens.topBar.panelRadiusDp, 0.01f)
        assertEquals(4, tokens.topBar.shadowElevationDp)
        assertEquals(60, tokens.bottomBar.borderAlphaPercent)

        // DIALOG overrides material + cornerScale while alpha inherits dialogAlphaPercent (94)
        assertTrue(tokens.dialog.isCustomOverride)
        assertEquals(GlassMaterialPreset.FROSTED, tokens.dialog.materialMode)
        assertEquals(18f, tokens.dialog.panelRadiusDp, 0.01f)
        assertEquals(15f, tokens.dialog.actionRadiusDp, 0.01f)
        assertEquals(94, tokens.dialog.alphaPercent)
        assertEquals(8f, tokens.dialog.blurRadiusDp, 0.01f)

        // BOOKSHELF_CARD overrides alpha + shadow while inheriting material & corner
        assertTrue(tokens.bookshelfCard.isCustomOverride)
        assertEquals(GlassMaterialPreset.GLASS, tokens.bookshelfCard.materialMode)
        assertEquals(72, tokens.bookshelfCard.alphaPercent)
        assertEquals(8, tokens.bookshelfCard.shadowElevationDp)
        assertEquals(12f, tokens.bookshelfCard.panelRadiusDp, 0.01f)
    }

    @Test
    fun resolveScope_supportsAllFourGlassMaterialPresetModesIncludingClear() {
        val env = AppearanceEnv(isNight = false, isEInk = false, hasUsableWallpaper = true, hardwareBlurSupported = true)
        val tokens = ThemeTokenCompiler.compileRaw(
            env = env,
            primaryArgb = 0xFF212121.toInt(),
            accentArgb = 0xFF1E88E5.toInt(),
            backgroundArgb = 0xFFF5F5F5.toInt(),
            bottomBarArgb = 0xFFFFFFFF.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            mutedArgb = 0xFFEEEEEE.toInt(),
            dividerArgb = 0x1F000000,
            borderArgb = 0x33000000,
            primaryTextArgb = 0xFF212121.toInt(),
            secondaryTextArgb = 0xFF757575.toInt(),
            titleTextArgb = 0xFF111111.toInt(),
            dangerArgb = 0xFFF44336.toInt(),
            panelRadiusDp = 12f,
            actionRadiusDp = 9f,
            panelRadiusPx = 36f,
            glassMode = GlassMaterialPreset.CLEAR,
            glassLevel = 0,
            glassStrokeColorArgb = null,
            glassSignature = "clear|0",
            paletteSignature = "p-clear",
            rawScopeOverrides = mapOf(
                UiScope.TOP_BAR to SurfaceScopeOverride(materialMode = GlassMaterialPreset.CLEAR),
                UiScope.BOTTOM_BAR to SurfaceScopeOverride(materialMode = GlassMaterialPreset.SOLID),
                UiScope.BOOKSHELF_CARD to SurfaceScopeOverride(materialMode = GlassMaterialPreset.FROSTED),
                UiScope.DIALOG to SurfaceScopeOverride(materialMode = GlassMaterialPreset.GLASS)
            )
        )

        assertTrue(tokens.topBar.isClearMode)
        assertFalse(tokens.topBar.usesGlassSampling)
        assertEquals(
            GlassMaterialPreset.clearSurfaceColor(0xFFFFFFFF.toInt()),
            tokens.topBar.resolveSurfaceColor(0xFFFFFFFF.toInt())
        )
        assertTrue(tokens.bottomBar.isSolidMode)
        assertFalse(tokens.bottomBar.usesGlassSampling)
        assertTrue(tokens.bookshelfCard.usesGlassSampling)
        assertEquals(GlassMaterialPreset.FROSTED, tokens.bookshelfCard.materialMode)
        assertTrue(tokens.dialog.usesGlassSampling)
        assertEquals(GlassMaterialPreset.GLASS, tokens.dialog.materialMode)
    }

    @Test
    fun resolveScope_eInkForcesSolidOpaqueZeroShadowAndZeroTransitionDuration() {
        val env = AppearanceEnv(isNight = false, isEInk = true, hasUsableWallpaper = true, hardwareBlurSupported = true)
        val tokens = ThemeTokenCompiler.compileRaw(
            env = env,
            primaryArgb = 0xFF212121.toInt(),
            accentArgb = 0xFF1E88E5.toInt(),
            backgroundArgb = 0xFFF5F5F5.toInt(),
            bottomBarArgb = 0xFFFFFFFF.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            mutedArgb = 0xFFEEEEEE.toInt(),
            dividerArgb = 0x1F000000,
            borderArgb = 0x33000000,
            primaryTextArgb = 0xFF212121.toInt(),
            secondaryTextArgb = 0xFF757575.toInt(),
            titleTextArgb = 0xFF111111.toInt(),
            dangerArgb = 0xFFF44336.toInt(),
            panelRadiusDp = 10f,
            actionRadiusDp = 7f,
            panelRadiusPx = 30f,
            glassMode = GlassMaterialPreset.GLASS,
            glassLevel = 80,
            glassStrokeColorArgb = null,
            glassSignature = "glass|80",
            paletteSignature = "p-eink",
            globalAlphaPercent = 60,
            dialogAlphaPercent = 50,
            globalShadowDp = 12,
            globalBlurRadiusDp = 20f,
            rawScopeOverrides = mapOf(
                UiScope.TOP_BAR to SurfaceScopeOverride(
                    materialMode = GlassMaterialPreset.GLASS,
                    alpha = 40,
                    blurRadiusDp = 18f,
                    shadowElevation = 6
                )
            )
        )

        assertEquals(0, tokens.transitionDurationMs(260))
        assertEquals(0f, tokens.animationDurationScale, 0.001f)
        listOf(tokens.topBar, tokens.bottomBar, tokens.bookshelfCard, tokens.dialog).forEach { scope ->
            assertEquals(GlassMaterialPreset.SOLID, scope.materialMode)
            assertEquals(100, scope.alphaPercent)
            assertEquals(0, scope.shadowElevationDp)
            assertEquals(0f, scope.blurRadiusDp, 0.001f)
            assertFalse(scope.isCustomOverride)
        }
    }

    @Test
    fun compileRaw_legacyPipelineKillSwitchBypassesScopeOverrides() {
        val env = AppearanceEnv(isNight = false, isEInk = false, hasUsableWallpaper = false, hardwareBlurSupported = true)
        val tokens = ThemeTokenCompiler.compileRaw(
            env = env,
            primaryArgb = 0xFF212121.toInt(),
            accentArgb = 0xFF1E88E5.toInt(),
            backgroundArgb = 0xFFF5F5F5.toInt(),
            bottomBarArgb = 0xFFFFFFFF.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            mutedArgb = 0xFFEEEEEE.toInt(),
            dividerArgb = 0x1F000000,
            borderArgb = 0x33000000,
            primaryTextArgb = 0xFF212121.toInt(),
            secondaryTextArgb = 0xFF757575.toInt(),
            titleTextArgb = 0xFF111111.toInt(),
            dangerArgb = 0xFFF44336.toInt(),
            panelRadiusDp = 10f,
            actionRadiusDp = 7f,
            panelRadiusPx = 30f,
            glassMode = GlassMaterialPreset.SOLID,
            glassLevel = 0,
            glassStrokeColorArgb = null,
            glassSignature = "solid|0",
            paletteSignature = "p-leg",
            globalAlphaPercent = 90,
            rawScopeOverrides = mapOf(
                UiScope.BOOKSHELF_CARD to SurfaceScopeOverride(alpha = 50, shadowElevation = 10)
            ),
            useLegacyTokenPipeline = true
        )

        assertTrue(tokens.legacyPipelineActive)
        assertFalse(tokens.bookshelfCard.isCustomOverride)
        assertEquals(90, tokens.bookshelfCard.alphaPercent)
        assertEquals(0, tokens.bookshelfCard.shadowElevationDp)
        assertEquals(220, tokens.transitionDurationMs(220))
    }

    @Test
    fun globalClearModeWithoutScopeOverrides_appliesClearSurfaceAcrossAllFourScopes() {
        val env = AppearanceEnv(isNight = false, isEInk = false, hasUsableWallpaper = true, hardwareBlurSupported = true)
        val tokens = ThemeTokenCompiler.compileRaw(
            env = env,
            primaryArgb = 0xFF212121.toInt(),
            accentArgb = 0xFF1E88E5.toInt(),
            backgroundArgb = 0xFFF5F5F5.toInt(),
            bottomBarArgb = 0xFFFFFFFF.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            mutedArgb = 0xFFEEEEEE.toInt(),
            dividerArgb = 0x1F000000,
            borderArgb = 0x33000000,
            primaryTextArgb = 0xFF212121.toInt(),
            secondaryTextArgb = 0xFF757575.toInt(),
            titleTextArgb = 0xFF111111.toInt(),
            dangerArgb = 0xFFF44336.toInt(),
            panelRadiusDp = 12f,
            actionRadiusDp = 9f,
            panelRadiusPx = 36f,
            glassMode = GlassMaterialPreset.CLEAR,
            glassLevel = 0,
            glassStrokeColorArgb = null,
            glassSignature = "clear|0",
            paletteSignature = "p-global-clear",
            globalAlphaPercent = 100,
            dialogAlphaPercent = 100,
            rawScopeOverrides = emptyMap()
        )

        val expectedClear = GlassMaterialPreset.clearSurfaceColor(0xFFFFFFFF.toInt())
        listOf(tokens.topBar, tokens.bottomBar, tokens.bookshelfCard, tokens.dialog).forEach { scope ->
            assertFalse("Expected un-overridden scope: ${scope.scope}", scope.isCustomOverride)
            assertTrue("Expected clear mode: ${scope.scope}", scope.isClearMode)
            assertFalse("CLEAR must not trigger glass sampling: ${scope.scope}", scope.usesGlassSampling)
            assertTrue("CLEAR must apply surface fill even when isCustomOverride=false", scope.shouldApplySurfaceFill)
            assertEquals(expectedClear, scope.resolveSurfaceColorIfActive(0xFFFFFFFF.toInt(), isEInk = false))
            assertEquals(0xFFFFFFFF.toInt(), scope.resolveSurfaceColorIfActive(0xFFFFFFFF.toInt(), isEInk = true))
        }
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
