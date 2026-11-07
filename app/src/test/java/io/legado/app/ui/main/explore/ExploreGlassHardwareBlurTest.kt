package io.legado.app.ui.main.explore

import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * 独立于生产声明 [ExploreGlassConfigKeys] 的手写基准清单：只从 [PreferKey] 常量构造，
     * 绝不引用 ExploreGlassConfigKeys / EXPLORE_GLASS_*。这样下面的逐值比较才是
     * 「生产声明 vs 独立基准」，而不是由同一份声明派生出的集合代数恒等式。
     * 渲染层新增读取键、设置页新增写入键或监听集合漏登记时，断言都会失败。
     */
    private val expectedReadKeys = setOf(
        PreferKey.exploreGlassEnabled,
        PreferKey.exploreGlassEffectMode,
        PreferKey.themeExploreGlassBlur,
        PreferKey.themeExploreGlassBlurN,
        PreferKey.exploreGlassBlurScale,
        PreferKey.exploreGlassShellScale,
        PreferKey.exploreGlassTintScale,
        PreferKey.exploreGlassStrokeScale,
        PreferKey.glassStrokeColor,
        PreferKey.exploreGlassHardwareBlur,
        PreferKey.exploreGlassAnimatedSolid
    )

    /** 1.25.8 档位键统一后，底栏联动只剩两个液态强度键（bottomBarEffectMode 已废弃删除）。 */
    private val expectedBottomBarKeys = setOf(
        PreferKey.frostedGlassLevel,
        PreferKey.liquidGlassLevel
    )

    private val expectedPrefKeys = expectedReadKeys + expectedBottomBarKeys

    private fun glassConfig(
        mode: String = EXPLORE_GLASS_MODE_GLASS,
        level: Int = 60
    ) = ExploreGlassConfig(
        mode = mode,
        level = level,
        blurPercent = EXPLORE_GLASS_SCALE_DEFAULT,
        shellPercent = EXPLORE_GLASS_SCALE_DEFAULT,
        tintPercent = EXPLORE_GLASS_SCALE_DEFAULT,
        strokePercent = EXPLORE_GLASS_SCALE_DEFAULT,
        strokeColorRaw = null,
        hardwareBlur = true,
        animatedSolid = true
    )

    /**
     * 一次算出「真正画到行上的参数」：共享预设 + 本页强度 + 四个倍率滑杆。
     * fallbackBoost 固定 0，断言与 JVM 里 Build.VERSION.SDK_INT 的桩值无关。
     */
    private fun renderParams(
        mode: String = EXPLORE_GLASS_MODE_GLASS,
        level: Int = 60,
        blurPercent: Int = EXPLORE_GLASS_SCALE_DEFAULT,
        shellPercent: Int = EXPLORE_GLASS_SCALE_DEFAULT,
        tintPercent: Int = EXPLORE_GLASS_SCALE_DEFAULT,
        strokePercent: Int = EXPLORE_GLASS_SCALE_DEFAULT
    ): ExploreGlassRenderParams {
        val config = glassConfig(mode = mode, level = level).copy(
            blurPercent = blurPercent,
            shellPercent = shellPercent,
            tintPercent = tintPercent,
            strokePercent = strokePercent
        )
        return resolveExploreGlassRenderParams(config, exploreGlassMaterial(config, 0f))
    }

    /** 记录渲染层真正读过的每一个偏好键，用于反向断言监听集合是否漏键。 */
    private class TrackingPrefSource : ExploreGlassPrefSource {
        val readKeys = linkedSetOf<String>()

        override fun boolean(key: String, default: Boolean): Boolean {
            readKeys += key
            return default
        }

        override fun int(key: String, default: Int): Int {
            readKeys += key
            return default
        }

        override fun string(key: String): String? {
            readKeys += key
            return null
        }
    }

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
        // 显式档位优先：未存档位 → 默认磨砂
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(null, true)
        )
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_SOLID, true)
        )
        assertEquals(
            EXPLORE_GLASS_MODE_GLASS,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_GLASS, true)
        )
        // 1.25.5 存的 none 归一到 solid
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            resolveExploreGlassMode("none", true)
        )
        // 1.25.4 旧总开关关闭且从未设过档位 → 迁移成无玻璃
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            resolveExploreGlassMode(null, false)
        )
        // 显式档位优先级不变：即便旧总开关为 false 也尊重显式档位
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_FROSTED, false)
        )
        // 脏值回退默认
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode("ultra", true)
        )
    }

    /** 迁移用例的内存偏好存储：记录每一步写入，供逐值断言。 */
    private class MemoryPrefStore : ExploreGlassPrefStore {
        val values = linkedMapOf<String, Any>()
        val removed = linkedSetOf<String>()

        override fun getBoolean(key: String, default: Boolean): Boolean =
            values[key] as? Boolean ?: default

        override fun getString(key: String): String? =
            values[key] as? String

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
            removed += key
        }
    }

    /** 迁移用 1：follow=true → 档位与强度双固化，随后移除旧键并写迁移标记。 */
    @Test
    fun migrationSolidifiesModeAndStrengthWhenFollowEnabled() {
        val store = MemoryPrefStore().apply {
            putBoolean(PreferKey.exploreGlassFollowBottomBar, true)
            putString(PreferKey.exploreGlassEffectMode, EXPLORE_GLASS_MODE_SOLID)
            putInt(PreferKey.themeExploreGlassBlur, 10)
            putInt(PreferKey.themeExploreGlassBlurN, 20)
        }
        migrateExploreGlassFollowLocked(store, "glass", frostedLevel = 70, liquidLevel = 68)
        // 档位固化：底栏档位覆写本页档位
        assertEquals(
            GlassMaterialPreset.GLASS,
            store.values[PreferKey.exploreGlassEffectMode]
        )
        // 强度双固化：日/夜两个强度键都写为底栏对应强度（glass → liquidGlassLevel）
        assertEquals(68, store.values[PreferKey.themeExploreGlassBlur])
        assertEquals(68, store.values[PreferKey.themeExploreGlassBlurN])
        // 旧键移除、幂等标记写入
        assertTrue(PreferKey.exploreGlassFollowBottomBar in store.removed)
        assertEquals(true, store.values[PreferKey.exploreGlassFollowMigrated])
    }

    /** 迁移用 2：follow=false / 缺失 → 不动任何值，仅移除旧键并写迁移标记。 */
    @Test
    fun migrationKeepsValuesWhenFollowDisabled() {
        val store = MemoryPrefStore().apply {
            putBoolean(PreferKey.exploreGlassFollowBottomBar, false)
            putString(PreferKey.exploreGlassEffectMode, EXPLORE_GLASS_MODE_FROSTED)
            putInt(PreferKey.themeExploreGlassBlur, 42)
            putInt(PreferKey.themeExploreGlassBlurN, 43)
        }
        migrateExploreGlassFollowLocked(store, "solid", frostedLevel = 70, liquidLevel = 68)
        assertEquals(EXPLORE_GLASS_MODE_FROSTED, store.values[PreferKey.exploreGlassEffectMode])
        assertEquals(42, store.values[PreferKey.themeExploreGlassBlur])
        assertEquals(43, store.values[PreferKey.themeExploreGlassBlurN])
        assertTrue(PreferKey.exploreGlassFollowBottomBar in store.removed)
        assertEquals(true, store.values[PreferKey.exploreGlassFollowMigrated])

        // 旧键完全缺失的全新安装同样只写标记
        val fresh = MemoryPrefStore()
        migrateExploreGlassFollowLocked(fresh, "frosted", 70, 68)
        assertTrue(fresh.values.keys.none { it != PreferKey.exploreGlassFollowMigrated })
    }

    /** 迁移用 3：幂等——标记存在后二次调用是 no-op。 */
    @Test
    fun migrationIsIdempotentViaMarkerKey() {
        val store = MemoryPrefStore().apply {
            putBoolean(PreferKey.exploreGlassFollowBottomBar, true)
            putString(PreferKey.exploreGlassEffectMode, EXPLORE_GLASS_MODE_GLASS)
            putBoolean(PreferKey.exploreGlassFollowMigrated, true)
        }
        migrateExploreGlassFollowLocked(store, "solid", 70, 68)
        // 档位保持原值，旧键也未被移除
        assertEquals(EXPLORE_GLASS_MODE_GLASS, store.values[PreferKey.exploreGlassEffectMode])
        assertTrue(PreferKey.exploreGlassFollowBottomBar !in store.removed)
    }

    /** 迁移用 4：底栏档位非法（脏值）时回退默认 frosted，强度随之取 frostedGlassLevel。 */
    @Test
    fun migrationFallsBackToFrostedOnInvalidBottomBarMode() {
        val store = MemoryPrefStore().apply {
            putBoolean(PreferKey.exploreGlassFollowBottomBar, true)
        }
        migrateExploreGlassFollowLocked(store, "ultra", frostedLevel = 70, liquidLevel = 68)
        assertEquals(EXPLORE_GLASS_MODE_FROSTED, store.values[PreferKey.exploreGlassEffectMode])
        assertEquals(70, store.values[PreferKey.themeExploreGlassBlur])
        assertEquals(70, store.values[PreferKey.themeExploreGlassBlurN])
    }

    @Test
    fun exploreMaterialReadsSharedPresetWithLocalStrength() {
        // 公式/壳层/描边取共享预设，强度按本页 60 → 0.3 映射（恒走本页强度，无跟随分支）
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

    /** 真机缺陷 1：无玻璃档必须是 alpha=255 的实底，同时底栏口径逐值不变。 */
    @Test
    fun solidShellIsFullyOpaqueAndBottomBarOpacityIsUnchanged() {
        assertEquals(1f, GlassMaterialPreset.solidAlpha(72, true), 0f)
        assertEquals(1f, GlassMaterialPreset.solidAlpha(0, true), 0f)
        assertEquals(255, (GlassMaterialPreset.solidAlpha(72, true) * 255f).toInt())

        // 即便主题底色自身带半透明 alpha，实底档也要钉成 255
        val opaque = GlassMaterialPreset.solidColor(0x8012_3456.toInt(), 72, true)
        assertEquals(255, opaque ushr 24)
        assertEquals(0x12_3456, opaque and 0x00ff_ffff)

        // 底栏所有调用点都不传 forceOpaque：内置图标包 76 / Config 默认 72 仍半透明
        assertEquals(0.72f, GlassMaterialPreset.solidAlpha(72), 0.0001f)
        assertEquals(0.76f, GlassMaterialPreset.solidAlpha(76), 0.0001f)
        assertEquals(0.72f, GlassMaterialPreset.solidOpacity(72), 0.0001f)
        assertEquals(183, GlassMaterialPreset.solidColor(0x0012_3456, 72) ushr 24)
    }

    /** 真机缺陷 2：两档在最终绘制参数上确实不同，且两条管线都吃到档位差。 */
    @Test
    fun glassAndFrostedRenderParamsAreVisiblyDifferent() {
        val glass = renderParams(mode = EXPLORE_GLASS_MODE_GLASS)
        val frosted = renderParams(mode = EXPLORE_GLASS_MODE_FROSTED)
        assertTrue(frosted.blurDp > glass.blurDp)
        assertTrue(frosted.tintAlpha - glass.tintAlpha >= 0.1f)
        assertTrue(frosted.shellCenter - glass.shellCenter >= 0.1f)
        assertTrue(frosted.shellStart - glass.shellStart >= 0.1f)
        assertTrue(glass.rimAlpha - frosted.rimAlpha >= 0.1f)
        assertTrue(glass.rimBandDp > frosted.rimBandDp)
        // 壳层厚度差不能只停留在「签名」里：磨砂就是比透明玻璃更实
        assertTrue(frosted.shellEnd > glass.shellEnd)

        val density = 3f
        assertTrue(
            exploreHardwareBlurPx(frosted.blurDp, density) >
                exploreHardwareBlurPx(glass.blurDp, density)
        )
        assertTrue(
            ExploreGlassBackdrop.exploreSoftwareBlurRadius(frosted.blurDp, 0, density) >
                ExploreGlassBackdrop.exploreSoftwareBlurRadius(glass.blurDp, 0, density)
        )
    }

    /** 真机缺陷 3：四个倍率滑杆逐项落进最终渲染参数，而不是只进配置签名。 */
    @Test
    fun everyScaleSliderChangesItsOwnRenderLayer() {
        val base = renderParams()
        val blurOff = renderParams(blurPercent = 0)
        val blurMax = renderParams(blurPercent = 200)
        assertTrue(blurOff.blurDp < base.blurDp && base.blurDp < blurMax.blurDp)
        assertEquals(base.tintAlpha, blurOff.tintAlpha, 0.0001f)
        assertEquals(base.shellCenter, blurOff.shellCenter, 0.0001f)
        assertEquals(base.strokeAlpha, blurOff.strokeAlpha, 0.0001f)
        // 模糊倍率必须同时改变硬件 GPU 半径与软件 stackBlur 半径
        assertTrue(
            exploreHardwareBlurPx(blurMax.blurDp, 3f) >
                exploreHardwareBlurPx(blurOff.blurDp, 3f)
        )
        assertTrue(
            ExploreGlassBackdrop.exploreSoftwareBlurRadius(blurMax.blurDp, 0, 3f) >
                ExploreGlassBackdrop.exploreSoftwareBlurRadius(blurOff.blurDp, 0, 3f)
        )

        val shellOff = renderParams(shellPercent = 0)
        val shellMax = renderParams(shellPercent = 200)
        assertEquals(0f, shellOff.shellStart, 0.0001f)
        assertEquals(0f, shellOff.shellCenter, 0.0001f)
        assertEquals(0f, shellOff.shellEnd, 0.0001f)
        assertTrue(shellMax.shellStart > base.shellStart && base.shellStart > shellOff.shellStart)
        assertTrue(shellMax.shellCenter > base.shellCenter && base.shellCenter > shellOff.shellCenter)
        assertTrue(shellMax.shellEnd > base.shellEnd && base.shellEnd > shellOff.shellEnd)
        assertEquals(base.tintAlpha, shellMax.tintAlpha, 0.0001f)
        assertEquals(base.strokeAlpha, shellMax.strokeAlpha, 0.0001f)

        val tintOff = renderParams(tintPercent = 0)
        val tintMax = renderParams(tintPercent = 200)
        assertEquals(0f, tintOff.tintAlpha, 0.0001f)
        assertTrue(tintMax.tintAlpha > base.tintAlpha && base.tintAlpha > tintOff.tintAlpha)
        assertEquals(base.shellCenter, tintMax.shellCenter, 0.0001f)

        val strokeOff = renderParams(strokePercent = 0)
        val strokeMax = renderParams(strokePercent = 200)
        assertEquals(0f, strokeOff.strokeAlpha, 0.0001f)
        assertTrue(strokeMax.strokeAlpha > base.strokeAlpha && base.strokeAlpha > strokeOff.strokeAlpha)
        assertEquals(base.shellCenter, strokeMax.shellCenter, 0.0001f)
        // 200 倍率仍守住底栏描边 0.42 的上限
        assertEquals(0.42f, strokeMax.strokeAlpha, 0.0001f)
    }

    /** 真机缺陷 3：主强度滑杆确实改变着色、壳层、高光与模糊半径。 */
    @Test
    fun mainStrengthSliderChangesRenderParamsMonotonically() {
        val low = renderParams(level = 1)
        val mid = renderParams(level = 50)
        val high = renderParams(level = 100)
        assertTrue(low.tintAlpha < mid.tintAlpha && mid.tintAlpha < high.tintAlpha)
        assertTrue(low.shellCenter < mid.shellCenter && mid.shellCenter < high.shellCenter)
        assertTrue(low.blurDp < mid.blurDp && mid.blurDp < high.blurDp)
        assertTrue(low.rimAlpha < mid.rimAlpha && mid.rimAlpha < high.rimAlpha)
        assertTrue(
            exploreHardwareBlurPx(high.blurDp, 3f) > exploreHardwareBlurPx(low.blurDp, 3f)
        )
    }

    /** 越界倍率（备份恢复的脏值）在渲染层同样限幅，不会画出越界 alpha。 */
    @Test
    fun renderParamsClampDirtyScaleValues() {
        val wild = renderParams(
            blurPercent = 999, shellPercent = -50, tintPercent = 999, strokePercent = -50
        )
        assertTrue(wild.blurDp >= 0f)
        assertEquals(0f, wild.shellStart, 0.0001f)
        assertEquals(0f, wild.shellCenter, 0.0001f)
        assertEquals(0f, wild.shellEnd, 0.0001f)
        assertEquals(0f, wild.strokeAlpha, 0.0001f)
        assertTrue(wild.tintAlpha <= 0.45f)

        val maxed = renderParams(
            mode = EXPLORE_GLASS_MODE_FROSTED,
            level = 100,
            shellPercent = 200,
            tintPercent = 200,
            strokePercent = 200
        )
        assertTrue(maxed.shellStart <= 0.94f)
        assertTrue(maxed.shellCenter <= 0.94f)
        assertTrue(maxed.shellEnd <= 0.94f)
        assertEquals(0.45f, maxed.tintAlpha, 0.0001f)
        assertEquals(0.42f, maxed.strokeAlpha, 0.0001f)
        assertTrue(maxed.rimAlpha <= 0.45f)
    }

    /** 即时重绑监听必须覆盖所有会改变行观感的键（含日夜间强度与底栏联动键）。 */
    @Test
    fun prefKeysCoverEveryRowAffectingSetting() {
        expectedPrefKeys.forEach { key ->
            assertTrue("$key 未进入即时重绑键集合", key in EXPLORE_GLASS_PREF_KEYS)
        }
    }

    /**
     * 收尾加固 a：实底不透明的「决策 + 实参链路」由纯函数产出，不再只是单测里
     * 直接调 GlassMaterialPreset.solidColor 自证。生产侧 solidShellDrawable() 只能
     * 拿 exploreSolidShellColor() 的结果交给填充色重载，翻转决策或漏接都会失败。
     */
    @Test
    fun exploreSolidShellDecisionProducesOpaqueColorAndKeepsBottomBarOpacity() {
        assertTrue(exploreSolidShellForcesOpaque())

        // 图标包 opacity 取 0 / 内置默认 76 / Config 默认 72 / 100 / 越界脏值，
        // 实底档一律是 alpha=255，且 RGB 保持主题底色
        listOf(-5, 0, 1, 72, 76, 100, 999).forEach { iconOpacity ->
            val fill = exploreSolidShellColor(0x0012_3456, iconOpacity)
            assertEquals("iconOpacity=$iconOpacity 的实底 alpha", 255, fill ushr 24)
            assertEquals("iconOpacity=$iconOpacity 的实底 RGB", 0x12_3456, fill and 0x00ff_ffff)
        }
        // 主题底色自身带半透明 alpha 时同样钉成 255
        val translucentBase = 0x8012_3456.toInt()
        assertEquals(255, exploreSolidShellColor(translucentBase, 72) ushr 24)
        assertEquals(0x12_3456, exploreSolidShellColor(translucentBase, 72) and 0x00ff_ffff)

        // 决策必须真的透到共用构造入口：两者产出的填充色逐位一致
        assertEquals(
            GlassMaterialPreset.solidColor(0x0012_3456, 72, exploreSolidShellForcesOpaque()),
            exploreSolidShellColor(0x0012_3456, 72)
        )

        // 底栏 false 路径逐值不变：0.72 / 0.76 口径与 alpha 183 / 193
        assertEquals(0.72f, GlassMaterialPreset.solidAlpha(72, false), 0.0001f)
        assertEquals(0.76f, GlassMaterialPreset.solidAlpha(76, false), 0.0001f)
        assertEquals(183, GlassMaterialPreset.solidColor(0x0012_3456, 72, false) ushr 24)
        assertEquals(193, GlassMaterialPreset.solidColor(0x0012_3456, 76, false) ushr 24)
    }

    /**
     * QA 第 2 轮指出的恒真断言已修：原先 `readKeys - PREF_KEYS`、`SETTING_KEYS -
     * PREF_KEYS`、`SETTING+BOTTOM == PREF` 等四条都是集合代数恒等式——生产集合全由
     * 同一份 ExploreGlassConfigKeys 派生，即便新增键漏登记也照样成立。
     *
     * 现在每条断言都是「生产声明 vs [expectedReadKeys] / [expectedPrefKeys] 这份
     * 独立于生产声明的手写基准」的逐值比较：渲染层多读一个键、设置页少写一个键、
     * 监听集合漏登记或多出无来源的键，都会失败并提示同步基准与监听。
     */
    @Test
    fun everyActuallyReadOrWrittenPrefKeyIsCoveredByRebindListener() {
        val source = TrackingPrefSource()
        // 日间、夜间两个强度键都要真的走一遍生产读取链路
        listOf(
            PreferKey.themeExploreGlassBlur,
            PreferKey.themeExploreGlassBlurN
        ).forEach { levelKey ->
            val config = readExploreGlassConfig(
                source = source,
                levelKey = levelKey
            )
            assertEquals(ExploreGlassBackdrop.LEVEL_DEFAULT, config.level)
        }

        // 静态兜底：生产侧 ThemeRuntimeKeys 的日夜解析结果必须与手写基准一致。
        // 显式传 isNight 实参，绕开其 AppConfig.isNightTheme 默认值，纯 JVM 可跑。
        assertEquals(
            PreferKey.themeExploreGlassBlur, ThemeRuntimeKeys.themeExploreGlassBlur(false)
        )
        assertEquals(
            PreferKey.themeExploreGlassBlurN, ThemeRuntimeKeys.themeExploreGlassBlur(true)
        )

        // 全部与独立手写基准逐值比较；这四条都不是恒等式，改变生产声明即失败。
        assertEquals(
            "生产读取链路的键与手写基准不一致（新增键请同步基准、设置页与监听集合）",
            expectedReadKeys, source.readKeys
        )
        assertEquals(
            "设置页写入/清除键与手写基准不一致",
            expectedReadKeys, EXPLORE_GLASS_SETTING_KEYS
        )
        assertEquals(
            "底栏联动键与手写基准不一致",
            expectedBottomBarKeys, EXPLORE_GLASS_BOTTOM_BAR_KEYS
        )
        assertEquals(
            "即时重绑监听集合与手写基准不一致",
            expectedPrefKeys, EXPLORE_GLASS_PREF_KEYS
        )
    }

    /**
     * QA 第 2 轮遗留项：把「发现页实底最终传给构造实现的填充色」锁进纯 JVM 断言。
     * 生产侧 solidShellDrawable() 只走 [withExploreSolidShellSpec] 这一个出口，
     * 单测注入 `{ it }` 拿回同一份规格；绕过不透明决策或改传半透明色都会失败。
     */
    @Test
    fun exploreSolidShellSpecLocksProductionFillColor() {
        val translucentBase = 0x8012_3456.toInt()
        listOf(-5, 0, 72, 76, 100, 999).forEach { iconOpacity ->
            val spec = withExploreSolidShellSpec(
                baseColor = translucentBase,
                iconOpacity = iconOpacity,
                borderColor = 0x7f00_ff00,
                cornerRadiusPx = 12f,
                strokeWidthPx = 2
            ) { it }
            assertEquals("iconOpacity=$iconOpacity 实底填充色 alpha", 255, spec.fillColor ushr 24)
            assertEquals(
                "iconOpacity=$iconOpacity 实底填充色 RGB",
                0x12_3456, spec.fillColor and 0x00ff_ffff
            )
            // 与共享预设 forceOpaque=true 的结果逐位一致，独立于 exploreSolidShellColor 实现
            assertEquals(
                GlassMaterialPreset.solidColor(translucentBase, iconOpacity, true),
                spec.fillColor
            )
            assertEquals(0x7f00_ff00, spec.borderColor!!)
            assertEquals(12f, spec.cornerRadiusPx, 0f)
            assertTrue(!spec.oval)
            assertEquals(2, spec.strokeWidthPx)
        }
        // 底栏走另一条入口、默认 forceOpaque=false，绝不能被发现页规格带成不透明
        assertEquals(183, GlassMaterialPreset.solidColor(0x0012_3456, 72) ushr 24)
        assertEquals(193, GlassMaterialPreset.solidColor(0x0012_3456, 76) ushr 24)
    }

    /**
     * 真机缺陷 1（1.25.8）：底栏档位键统一为全局单一来源。
     * 改过旧 bottomBarEffectMode 但从未写过全局档位的用户，升级时旧档位固化进
     * exploreGlassEffectMode；写过全局档位（含跟随迁移固化）的用户不动任何值。
     */
    @Test
    fun modeUnificationMigrationCopiesLegacyBottomBarModeOnlyOnce() {
        // 用例 1：改过底栏档位 + 从未写过全局档位 → 固化
        val legacy = MemoryPrefStore().apply {
            putString(PreferKey.bottomBarEffectMode, "solid")
        }
        migrateLegacyBottomBarEffectModeLocked(legacy)
        assertEquals(
            EXPLORE_GLASS_MODE_SOLID,
            legacy.values[PreferKey.exploreGlassEffectMode]
        )
        assertEquals(true, legacy.values[PreferKey.exploreGlassModeUnifiedMigrated])
        // 旧键本身不删（常量保留 + 渲染不再读），避免误伤「迁移标记之外的原始状态」
        assertTrue(PreferKey.bottomBarEffectMode !in legacy.removed)

        // 非法旧档位经同一解析函数回退默认磨砂
        val dirty = MemoryPrefStore().apply {
            putString(PreferKey.bottomBarEffectMode, "ultra")
        }
        migrateLegacyBottomBarEffectModeLocked(dirty)
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            dirty.values[PreferKey.exploreGlassEffectMode]
        )

        // 用例 2：写过全局档位 → 不动任何值，仅写迁移标记
        val explicit = MemoryPrefStore().apply {
            putString(PreferKey.bottomBarEffectMode, "glass")
            putString(PreferKey.exploreGlassEffectMode, EXPLORE_GLASS_MODE_FROSTED)
        }
        migrateLegacyBottomBarEffectModeLocked(explicit)
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            explicit.values[PreferKey.exploreGlassEffectMode]
        )
        assertEquals(true, explicit.values[PreferKey.exploreGlassModeUnifiedMigrated])

        // 用例 3：幂等——标记存在后二次调用是 no-op
        val idempotent = MemoryPrefStore().apply {
            putString(PreferKey.bottomBarEffectMode, "solid")
            putBoolean(PreferKey.exploreGlassModeUnifiedMigrated, true)
        }
        migrateLegacyBottomBarEffectModeLocked(idempotent)
        assertTrue(PreferKey.exploreGlassEffectMode !in idempotent.values)
    }

    /** 全局描边色解析：空 / 未设置 / 脏值 → null（不描边），合法 ARGB 十进制 → 逐值。 */
    @Test
    fun glassStrokeColorResolutionKeepsNullableSemantics() {
        assertNull(resolveGlassStrokeColor(null))
        assertNull(resolveGlassStrokeColor(""))
        assertNull(resolveGlassStrokeColor("   "))
        assertNull(resolveGlassStrokeColor("not-a-color"))
        assertNull(resolveGlassStrokeColor("0x7f00ff00"))
        assertEquals(0x7f00_ff00, resolveGlassStrokeColor(0x7f00_ff00.toString()))
        assertEquals(0x00ff_0000, resolveGlassStrokeColor("16711680"))
        // 带前后空白的合法值同样可解析
        assertEquals(12345, resolveGlassStrokeColor(" 12345 "))
    }

    /**
     * 真机缺陷 2（1.25.8）：API 33+ 液态行管线与底栏逐值同源——
     * 不叠加静态管线的行可见度增益，四个倍率滑杆重映射到液态组件参数。
     */
    @Test
    fun liquidRowSpecMirrorsBottomBarParamsWithoutRowGains() {
        val density = 3f
        val material = GlassMaterialPreset.params(EXPLORE_GLASS_MODE_GLASS, 0.34f, 0f)
        val spec = resolveExploreLiquidRowSpec(
            glassConfig(mode = EXPLORE_GLASS_MODE_GLASS, level = 68), material, density, 12f
        )
        // 100 倍率 = 预设原值：与底栏 setupLiquidGlass 的注入口径逐值一致
        assertEquals(material.blurDp * density, spec.blurPx, 0.001f)
        assertEquals(material.tintAlpha, spec.tintAlpha, 0.0001f)
        assertEquals(material.dispersion, spec.dispersion, 0.0001f)
        assertEquals(material.refractionHeightDp * density, spec.refractionHeightPx, 0.001f)
        assertEquals(material.refractionOffsetDp * density, spec.refractionOffsetPx, 0.001f)
        assertEquals(material.shell.start, spec.shellStart, 0.0001f)
        assertEquals(material.shell.center, spec.shellCenter, 0.0001f)
        assertEquals(material.shell.end, spec.shellEnd, 0.0001f)
        assertEquals(material.shell.stroke, spec.strokeAlpha, 0.0001f)
        assertEquals(12f, spec.cornerRadiusPx, 0f)
        // 关键差异：液态管线 tint 不吃 ROW_TINT_GAIN=3 的行标定增益
        val staticRender = resolveExploreGlassRenderParams(
            glassConfig(mode = EXPLORE_GLASS_MODE_GLASS, level = 68),
            material
        )
        assertTrue(staticRender.tintAlpha > spec.tintAlpha)
    }

    /** 液态行管线上四个倍率滑杆各自落到对应组件参数，0 关闭、200 加倍并限幅。 */
    @Test
    fun liquidRowSpecMapsEveryScaleSlider() {
        val density = 2f
        val material = GlassMaterialPreset.params(EXPLORE_GLASS_MODE_FROSTED, 0.35f, 0f)
        val base = resolveExploreLiquidRowSpec(
            glassConfig(mode = EXPLORE_GLASS_MODE_FROSTED, level = 70), material, density, 12f
        )
        val blurOff = resolveExploreLiquidRowSpec(
            glassConfig(mode = EXPLORE_GLASS_MODE_FROSTED, level = 70)
                .copy(blurPercent = 0), material, density, 12f
        )
        val blurMax = resolveExploreLiquidRowSpec(
            glassConfig(mode = EXPLORE_GLASS_MODE_FROSTED, level = 70)
                .copy(blurPercent = 200), material, density, 12f
        )
        assertEquals(0f, blurOff.blurPx, 0.0001f)
        assertEquals(material.blurDp * 2f * density, blurMax.blurPx, 0.001f)
        assertEquals(base.tintAlpha, blurOff.tintAlpha, 0.0001f)

        val tintMax = resolveExploreLiquidRowSpec(
            glassConfig(mode = EXPLORE_GLASS_MODE_FROSTED, level = 70)
                .copy(tintPercent = 200), material, density, 12f
        )
        // tint 上限 1：磨砂档 0.085 × 2 = 0.17 未触顶
        assertEquals(material.tintAlpha * 2f, tintMax.tintAlpha, 0.0001f)
        assertEquals(base.shellCenter, tintMax.shellCenter, 0.0001f)

        val shellOff = resolveExploreLiquidRowSpec(
            glassConfig(mode = EXPLORE_GLASS_MODE_FROSTED, level = 70)
                .copy(shellPercent = 0), material, density, 12f
        )
        assertEquals(0f, shellOff.shellStart, 0.0001f)
        assertEquals(0f, shellOff.shellCenter, 0.0001f)
        assertEquals(0f, shellOff.shellEnd, 0.0001f)
        assertEquals(base.strokeAlpha, shellOff.strokeAlpha, 0.0001f)

        val strokeMax = resolveExploreLiquidRowSpec(
            glassConfig(mode = EXPLORE_GLASS_MODE_FROSTED, level = 70)
                .copy(strokePercent = 200), material, density, 12f
        )
        assertEquals(material.shell.stroke * 2f, strokeMax.strokeAlpha, 0.0001f)
        assertEquals(base.shellStart, strokeMax.shellStart, 0.0001f)
    }

    /** 液态行管线门槛：API 33+；solid 档与液态强度 0 时不启用（纯函数可测的部分）。 */
    @Test
    fun liquidPipelineGateFollowsApiAndStrength() {
        assertTrue(supportsExploreLiquidPipeline(33))
        assertTrue(supportsExploreLiquidPipeline(34))
        assertTrue(!supportsExploreLiquidPipeline(32))
        // 强度 0 → GlassMaterialPreset.current 的 glassLevel 为 0，液态行管线不启用
        val material = GlassMaterialPreset.params(EXPLORE_GLASS_MODE_GLASS, 0f, 0f)
        assertEquals(0f, material.glassLevel, 0f)
    }
}
