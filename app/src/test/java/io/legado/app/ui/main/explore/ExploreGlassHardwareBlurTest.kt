package io.legado.app.ui.main.explore

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毛玻璃三档材质与半径口径的确定性单测。
 *
 * 上一版把三档做成「在发现页既有观感上乘系数」，与底栏预设并不一致；现在底栏和
 * 发现页都从 [GlassMaterialPreset] 取同一组参数。口径错（档位公式写反、误乘
 * 软件管线的 1.4 等效系数、漏乘 density、限幅失效、跟随档位解析错、旧值迁移丢失）
 * 都不会导致编译失败，只会让观感或低端 GPU 开销悄悄偏离，故用确定性断言锁住，
 * 不依赖真机与运行时推断。
 */
class ExploreGlassHardwareBlurTest {

    private fun glassConfig(
        mode: String = EXPLORE_GLASS_MODE_GLASS,
        level: Int = 60,
        followBottomBar: Boolean = false
    ) = ExploreGlassConfig(
        mode = mode,
        followBottomBar = followBottomBar,
        level = level,
        blurPercent = EXPLORE_GLASS_SCALE_DEFAULT,
        shellPercent = EXPLORE_GLASS_SCALE_DEFAULT,
        tintPercent = EXPLORE_GLASS_SCALE_DEFAULT,
        strokePercent = EXPLORE_GLASS_SCALE_DEFAULT,
        hardwareBlur = true,
        animatedSolid = true
    )

    @Test
    fun glassPresetMatchesBottomBarFormulaExactly() {
        // 底栏 MainActivity.setupLiquidGlass 的 glass 档：level=bottomBarOpacityLevel(68)=0.34
        val p = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)
        assertEquals(11.4f, p.blurDp, 0.001f)              // 8 + 0.34*10
        assertEquals(0.037f, p.tintAlpha, 0.001f)          // 0.02 + 0.34*0.05
        assertEquals(0.2816f, p.dispersion, 0.001f)        // 0.20 + 0.34*0.24
        assertEquals(26.08f, p.refractionHeightDp, 0.001f) // 22 + 0.34*12
        assertEquals(50.16f, p.refractionOffsetDp, 0.001f) // 42 + 0.34*24
        assertEquals(0.2344f, p.shell.start, 0.001f)
        assertEquals(0.1408f, p.shell.center, 0.001f)
        assertEquals(0.114f, p.shell.end, 0.001f)
        assertEquals(0.2344f, p.shell.stroke, 0.001f)
    }

    @Test
    fun frostedPresetMatchesBottomBarFormulaExactly() {
        // frosted 档：level=bottomBarOpacityLevel(70)=0.35
        val p = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)
        assertEquals(18.3f, p.blurDp, 0.001f)              // 12 + 0.35*18
        assertEquals(0.085f, p.tintAlpha, 0.001f)          // 0.05 + 0.35*0.10
        assertEquals(0.142f, p.dispersion, 0.001f)         // 0.10 + 0.35*0.12
        assertEquals(16.8f, p.refractionHeightDp, 0.001f)  // 14 + 0.35*8
        assertEquals(31.6f, p.refractionOffsetDp, 0.001f)  // 26 + 0.35*16
        assertEquals(0.236f, p.shell.start, 0.001f)
        assertEquals(0.142f, p.shell.center, 0.001f)
        assertEquals(0.115f, p.shell.end, 0.001f)
        assertEquals(0.236f, p.shell.stroke, 0.001f)
    }

    @Test
    fun glassAndFrostedAreDistinctMaterialsNotScaledCopies() {
        val glass = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f)
        val frosted = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.3f)
        assertTrue(glass.blurDp < frosted.blurDp)
        assertTrue(glass.tintAlpha < frosted.tintAlpha)
        assertTrue(glass.dispersion > frosted.dispersion)
        assertTrue(glass.refractionHeightDp > frosted.refractionHeightDp)
        assertTrue(glass.refractionOffsetDp > frosted.refractionOffsetDp)
        // 底栏对两档用的是同一套壳层/描边公式，旧实现给透明玻璃乘 0.40/1.25 是错的。
        assertEquals(frosted.shell.start, glass.shell.start, 0.001f)
        assertEquals(frosted.shell.center, glass.shell.center, 0.001f)
        assertEquals(frosted.shell.end, glass.shell.end, 0.001f)
        assertEquals(frosted.shell.stroke, glass.shell.stroke, 0.001f)
    }

    @Test
    fun shellAlphasMatchBottomBarFallbackAndSelectedBoost() {
        val normal = GlassMaterialPreset.shellAlphas(0.34f, 0.08f)
        assertEquals(0.3144f, normal.start, 0.001f)
        assertEquals(0.1928f, normal.center, 0.001f)
        assertEquals(0.15f, normal.end, 0.001f)
        // 描边不含 fallbackBoost
        assertEquals(0.2344f, normal.stroke, 0.001f)

        val selected = GlassMaterialPreset.shellAlphas(0.34f, 0.08f, 0.05f)
        assertEquals(0.3644f, selected.start, 0.001f)
        assertEquals(0.2428f, selected.center, 0.001f)
        assertEquals(0.20f, selected.end, 0.001f)
        assertEquals(0.2844f, selected.stroke, 0.001f)

        // 异常强度下各上限仍生效
        val capped = GlassMaterialPreset.shellAlphas(10f, 0.08f)
        assertEquals(0.44f, capped.start, 0.001f)
        assertEquals(0.32f, capped.center, 0.001f)
        assertEquals(0.26f, capped.end, 0.001f)
        assertEquals(0.42f, capped.stroke, 0.001f)
    }

    @Test
    fun dispersionAndOpacityConversionsClamp() {
        assertEquals(0.48f, GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 10f).dispersion, 0.001f)
        assertEquals(0.28f, GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 10f).dispersion, 0.001f)

        assertEquals(0f, GlassMaterialPreset.opacityLevel(0), 0.001f)
        assertEquals(0.25f, GlassMaterialPreset.opacityLevel(50), 0.001f)
        assertEquals(0.5f, GlassMaterialPreset.opacityLevel(100), 0.001f)
        assertEquals(0.5f, GlassMaterialPreset.opacityLevel(999), 0.001f)
        assertEquals(0f, GlassMaterialPreset.opacityLevel(-5), 0.001f)

        assertEquals(0.72f, GlassMaterialPreset.solidOpacity(72), 0.001f)
        assertEquals(1f, GlassMaterialPreset.solidOpacity(150), 0.001f)
        assertEquals(0f, GlassMaterialPreset.solidOpacity(-5), 0.001f)
    }

    @Test
    fun modeValuesMatchBottomBarAndLegacyNoneIsMigrated() {
        assertEquals("solid", GlassMaterialPreset.SOLID)
        assertEquals("glass", GlassMaterialPreset.GLASS)
        assertEquals("frosted", GlassMaterialPreset.FROSTED)
        assertEquals(setOf("solid", "glass", "frosted"), GlassMaterialPreset.modes)
        assertEquals(GlassMaterialPreset.SOLID, EXPLORE_GLASS_MODE_SOLID)
        assertEquals(GlassMaterialPreset.GLASS, EXPLORE_GLASS_MODE_GLASS)
        assertEquals(GlassMaterialPreset.FROSTED, EXPLORE_GLASS_MODE_FROSTED)
    }

    @Test
    fun modeResolutionCoversDefaultsLegacyAndDirtyValues() {
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(null, true, false, "glass")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_SOLID, true, false, "frosted")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_GLASS,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_GLASS, true, false, "solid")
        )
        // 1.25.5 存的 none 归一到 solid
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            resolveExploreGlassMode("none", true, false, "frosted")
        )
        // 1.25.4 旧总开关关闭且从未设过档位 → 迁移成无玻璃
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            resolveExploreGlassMode(null, false, false, "frosted")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_FROSTED, false, false, "solid")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode("ultra", true, false, "glass")
        )
    }

    @Test
    fun followBottomBarMapsThreeModesAndOverridesStoredMode() {
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_FROSTED, true, true, "solid")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_GLASS,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_SOLID, true, true, "glass")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_SOLID, true, true, "frosted")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_GLASS,
            resolveExploreGlassMode(null, true, true, "unknown")
        )
    }

    @Test
    fun exploreMaterialReadsSharedPresetWithLocalStrength() {
        // 未跟随底栏：公式/壳层/描边取共享预设，强度按本页 60 → 0.3 映射
        val material = exploreGlassMaterial(glassConfig(level = 60), 0f)
        val expected = GlassMaterialPreset.params(EXPLORE_GLASS_MODE_GLASS, 0.3f, 0f)
        assertEquals(0.3f, material.glassLevel, 0.001f)
        assertEquals(expected.blurDp, material.blurDp, 0.001f)
        assertEquals(expected.tintAlpha, material.tintAlpha, 0.001f)
        assertEquals(expected.shell.center, material.shell.center, 0.001f)
        assertEquals(expected.shell.stroke, material.shell.stroke, 0.001f)
    }

    @Test
    fun hardwareBlurUsesPresetBlurDpAndClamps() {
        // glass 档 11.4dp、frosted 档 18.3dp：半径直接来自预设，不再自行推算公式
        assertEquals(22.8f, exploreHardwareBlurPx(11.4f, 2f), 0.001f)
        assertEquals(36.6f, exploreHardwareBlurPx(18.3f, 2f), 0.001f)
        // 不乘软件管线的 1.4 等效系数
        assertEquals(36f, exploreHardwareBlurPx(12f, 3f), 0.001f)
        // 倍率 0 仍守住 1px 下限，200 倍率线性加倍
        assertEquals(1f, exploreHardwareBlurPx(11.4f, 2f, 0), 0.001f)
        assertEquals(45.6f, exploreHardwareBlurPx(11.4f, 2f, 200), 0.001f)
        // 上下限
        assertEquals(1f, exploreHardwareBlurPx(11.4f, 0f), 0.001f)
        assertEquals(80f, exploreHardwareBlurPx(11.4f, 100f, 200), 0.001f)
        // 同一强度下透明玻璃档必须比磨砂档更轻
        assertTrue(
            exploreHardwareBlurPx(
                GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f).blurDp, 2f
            ) < exploreHardwareBlurPx(
                GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.3f).blurDp, 2f
            )
        )
    }

    @Test
    fun scaleParamKeepsPresetValueAndClampsOutOfRange() {
        assertEquals(0.5f, scaledGlassParam(0.5f, 100), 0.001f)
        assertEquals(0f, scaledGlassParam(0.5f, 0), 0.001f)
        assertEquals(1f, scaledGlassParam(0.5f, 200), 0.001f)
        assertEquals(1f, scaledGlassParam(0.5f, 999), 0.001f)
        assertEquals(0f, scaledGlassParam(0.5f, -50), 0.001f)
        assertEquals(0.42f, scaledGlassParam(0.34f, 200, 0.42f), 0.001f)
        assertEquals(0.88f, scaledGlassParam(1.6f, 100, 0.88f), 0.001f)
    }

    @Test
    fun rimHighlightFollowsPresetDispersion() {
        val glass = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f)
        val frosted = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.3f)
        assertTrue(exploreGlassRimAlpha(glass.dispersion) > exploreGlassRimAlpha(frosted.dispersion))
        assertEquals(0.136f, exploreGlassRimAlpha(glass.dispersion), 0.001f)
        assertEquals(0.068f, exploreGlassRimAlpha(frosted.dispersion), 0.001f)
        assertEquals(0.24f, exploreGlassRimAlpha(10f), 0.001f)
        assertEquals(0f, exploreGlassRimAlpha(-1f), 0.001f)
    }
}
