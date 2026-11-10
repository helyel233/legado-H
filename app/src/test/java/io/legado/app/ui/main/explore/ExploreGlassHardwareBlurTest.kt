package io.legado.app.ui.main.explore

import io.legado.app.constant.PreferKey
import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毛玻璃三档材质、倍率与半径口径的确定性单测。
 *
 * 1.26.1 起材质只有一份来源：[GlassMaterialPreset]（档位预设 × 强度 × 四个倍率 ×
 * API 33 以下补偿）。底栏、顶栏、发现页行全部读同一个 `GlassMaterialPreset.current()`，
 * 行内不再有增益、rim 高光或拟液态叠加层。口径错（档位公式写反、误乘软件管线的
 * 1.4 等效系数、漏乘 density、限幅失效、倍率只进某一处宿主、旧值迁移丢失）都不会
 * 导致编译失败，只会让观感或低端 GPU 开销悄悄偏离，故用确定性断言锁住。
 */
class ExploreGlassHardwareBlurTest {

    /**
     * 独立于生产声明 [ExploreGlassConfigKeys] 的手写基准清单：只从 [PreferKey] 常量构造，
     * 绝不引用 ExploreGlassConfigKeys / EXPLORE_GLASS_*。这样下面的逐值比较才是
     * 「生产声明 vs 独立基准」，而不是由同一份声明派生出的集合代数恒等式。
     *
     * 1.26.1 起 [readExploreGlassConfig] 只读非材质键；材质键（两个强度 + 四个倍率）
     * 由 [GlassMaterialPreset.current] 统一读取，因此两份基准分开手写。
     */
    private val expectedReadKeys = setOf(
        PreferKey.exploreGlassEnabled,
        PreferKey.exploreGlassEffectMode,
        PreferKey.glassStrokeColor,
        PreferKey.exploreGlassHardwareBlur,
        PreferKey.exploreGlassAnimatedSolid
    )

    private val expectedMaterialKeys = setOf(
        PreferKey.frostedGlassLevel,
        PreferKey.liquidGlassLevel,
        PreferKey.exploreGlassBlurScale,
        PreferKey.exploreGlassTintScale,
        PreferKey.exploreGlassShellScale,
        PreferKey.exploreGlassStrokeScale
    )

    /** 设置页写入 /「恢复默认」清除的键：非材质键 + 旧日夜间强度 + 四个倍率。 */
    private val expectedSettingKeys = setOf(
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

    private val expectedPrefKeys = expectedSettingKeys + expectedBottomBarKeys

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
        assertEquals(5.04f, p.blurDp, 0.001f)              // 3 + 0.34*6
        assertEquals(0.0108f, p.tintAlpha, 0.001f)         // 0.004 + 0.34*0.02
        assertEquals(0.3216f, p.dispersion, 0.001f)        // 0.24 + 0.34*0.24
        assertEquals(28.08f, p.refractionHeightDp, 0.001f) // 24 + 0.34*12
        assertEquals(54.84f, p.refractionOffsetDp, 0.001f) // 46 + 0.34*26
        assertEquals(0.2344f, p.shell.start, 0.001f)
        assertEquals(0.1408f, p.shell.center, 0.001f)
        assertEquals(0.114f, p.shell.end, 0.001f)
        assertEquals(0.2344f, p.shell.stroke, 0.001f)
    }

    @Test
    fun frostedPresetMatchesBottomBarFormulaExactly() {
        // frosted 档：level=bottomBarOpacityLevel(70)=0.35
        val p = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)
        assertEquals(28.4f, p.blurDp, 0.001f)              // 20 + 0.35*24
        assertEquals(0.25f, p.tintAlpha, 0.001f)           // 0.18 + 0.35*0.20
        assertEquals(0.068f, p.dispersion, 0.001f)         // 0.04 + 0.35*0.08
        assertEquals(14.1f, p.refractionHeightDp, 0.001f)  // 12 + 0.35*6
        assertEquals(24.2f, p.refractionOffsetDp, 0.001f)  // 20 + 0.35*12
        assertEquals(0.236f, p.shell.start, 0.001f)
        assertEquals(0.142f, p.shell.center, 0.001f)
        assertEquals(0.115f, p.shell.end, 0.001f)
        assertEquals(0.236f, p.shell.stroke, 0.001f)
    }

    /**
     * 真机反馈「切纯玻璃 / 磨砂玻璃没有变化」的回归锁（初版只靠 tint 差 ≈0.13 不够，
     * 本轮改为四条维度同时拉开）：
     * 默认强度下 tint 差必须 ≥0.2，模糊、色散、折射几何方向与幅度也都要在预设层写明。
     */
    @Test
    fun barLevelTintGapMakesGlassAndFrostedDistinguishable() {
        val glass = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.34f)
        val frosted = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)
        assertTrue(frosted.tintAlpha - glass.tintAlpha >= 0.2f)
        // 玻璃档接近无色、磨砂档明显奶白，两端的绝对值本身也要在合理区间
        assertTrue(glass.tintAlpha <= 0.015f)
        assertTrue(frosted.tintAlpha >= 0.24f)
        // 模糊：磨砂至少是透明玻璃的 3 倍（默认强度 28.4dp vs 5.04dp）
        assertTrue(frosted.blurDp >= glass.blurDp * 3f)
        // 色散仍保持「玻璃档更强」，且两档差不再只差一点点
        assertTrue(glass.dispersion - frosted.dispersion >= 0.2f)
        // 折射几何：透明玻璃更高更深，磨砂浅而短
        assertTrue(glass.refractionHeightDp >= frosted.refractionHeightDp * 1.8f)
        assertTrue(glass.refractionOffsetDp >= frosted.refractionOffsetDp * 2f)
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
        assertEquals(0.50f, GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 10f).dispersion, 0.001f)
        assertEquals(0.18f, GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 10f).dispersion, 0.001f)

        assertEquals(0f, GlassMaterialPreset.opacityLevel(0), 0.001f)
        assertEquals(0.25f, GlassMaterialPreset.opacityLevel(50), 0.001f)
        assertEquals(0.5f, GlassMaterialPreset.opacityLevel(100), 0.001f)
        assertEquals(0.5f, GlassMaterialPreset.opacityLevel(999), 0.001f)
        assertEquals(0f, GlassMaterialPreset.opacityLevel(-5), 0.001f)

        assertEquals(0.72f, GlassMaterialPreset.solidOpacity(72), 0.001f)
        assertEquals(1f, GlassMaterialPreset.solidOpacity(150), 0.001f)
        assertEquals(0f, GlassMaterialPreset.solidOpacity(-5), 0.001f)
    }

    /**
     * 倍率语义：100 = 预设原值（逐值恒等）、0 = 关闭该维度、>200 = 按 200 限幅。
     * 四个倍率必须同时落在底栏 / 顶栏 / 行共用的这份结果上，否则同一档参数会分叉。
     */
    @Test
    fun materialScalingIsIdentityAtHundredAndCapsOutOfRange() {
        val base = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)
        // 100 倍率逐值恒等：底栏 / 顶栏 / 行什么都不调时就是预设本身
        assertEquals(base, GlassMaterialPreset.scaled(base))

        val extreme = base.copy(
            tintAlpha = 0.4f,
            // end 也取 ≥0.47，才能验证「×2 后被 0.94 限幅」而不是只验证未触顶的 0.8
            shell = GlassMaterialPreset.ShellAlphas(0.6f, 0.5f, 0.5f, 0.4f)
        )
        val wild = GlassMaterialPreset.scaled(
            extreme,
            blurPercent = 999,
            tintPercent = 999,
            shellPercent = 999,
            strokePercent = 999
        )
        // 倍率先钳到 200 再乘：模糊按 2 倍计，不会无限放大
        assertEquals(base.blurDp * 2f, wild.blurDp, 0.0001f)
        assertEquals(GlassMaterialPreset.MAX_TINT_ALPHA, wild.tintAlpha, 0f)
        assertEquals(GlassMaterialPreset.MAX_SHELL_ALPHA, wild.shell.start, 0f)
        assertEquals(GlassMaterialPreset.MAX_SHELL_ALPHA, wild.shell.center, 0f)
        assertEquals(GlassMaterialPreset.MAX_SHELL_ALPHA, wild.shell.end, 0f)
        assertEquals(GlassMaterialPreset.MAX_STROKE_ALPHA, wild.shell.stroke, 0f)
        // 色散与折射几何由档位唯一决定，不随倍率变化
        assertEquals(base.dispersion, wild.dispersion, 0f)
        assertEquals(base.refractionHeightDp, wild.refractionHeightDp, 0f)
        assertEquals(base.refractionOffsetDp, wild.refractionOffsetDp, 0f)

        val negative = GlassMaterialPreset.scaled(base, -50, -50, -50, -50)
        assertEquals(0f, negative.blurDp, 0f)
        assertEquals(0f, negative.tintAlpha, 0f)
        assertEquals(0f, negative.shell.start, 0f)
        assertEquals(0f, negative.shell.center, 0f)
        assertEquals(0f, negative.shell.end, 0f)
        assertEquals(0f, negative.shell.stroke, 0f)
    }

    /** 磨砂档读 frostedGlassLevel，其余档（含 solid）读 liquidGlassLevel，与底栏历史口径一致。 */
    @Test
    fun levelForFollowsMaterialMode() {
        assertEquals(70, GlassMaterialPreset.levelFor(GlassMaterialPreset.FROSTED, 70, 68))
        assertEquals(68, GlassMaterialPreset.levelFor(GlassMaterialPreset.GLASS, 70, 68))
        assertEquals(68, GlassMaterialPreset.levelFor(GlassMaterialPreset.SOLID, 70, 68))
    }

    /** 材质签名必须覆盖每一个可调维度：漏一个维度，该宿主的缓存就不会随它重建。 */
    @Test
    fun signatureCoversEveryAdjustableDimension() {
        val base = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f)
        val signature = GlassMaterialPreset.signature(base)
        assertEquals(signature, GlassMaterialPreset.signature(base.copy()))
        assertTrue(signature != GlassMaterialPreset.signature(base.copy(mode = GlassMaterialPreset.FROSTED)))
        assertTrue(signature != GlassMaterialPreset.signature(base.copy(glassLevel = 0.4f)))
        assertTrue(signature != GlassMaterialPreset.signature(base.copy(blurDp = base.blurDp + 0.1f)))
        assertTrue(signature != GlassMaterialPreset.signature(base.copy(tintAlpha = base.tintAlpha + 0.01f)))
        assertTrue(signature != GlassMaterialPreset.signature(base.copy(dispersion = base.dispersion + 0.01f)))
        assertTrue(
            signature != GlassMaterialPreset.signature(
                base.copy(refractionHeightDp = base.refractionHeightDp + 1f)
            )
        )
        assertTrue(
            signature != GlassMaterialPreset.signature(
                base.copy(refractionOffsetDp = base.refractionOffsetDp + 1f)
            )
        )
        assertTrue(
            signature != GlassMaterialPreset.signature(
                base.copy(shell = base.shell.copy(start = base.shell.start + 0.01f))
            )
        )
        assertTrue(
            signature != GlassMaterialPreset.signature(
                base.copy(shell = base.shell.copy(center = base.shell.center + 0.01f))
            )
        )
        assertTrue(
            signature != GlassMaterialPreset.signature(
                base.copy(shell = base.shell.copy(end = base.shell.end + 0.01f))
            )
        )
        assertTrue(
            signature != GlassMaterialPreset.signature(
                base.copy(shell = base.shell.copy(stroke = base.shell.stroke + 0.01f))
            )
        )
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
    fun hardwareBlurUsesPresetBlurDpAndClamps() {
        // glass 档 11.4dp、frosted 档 18.3dp：半径直接来自预设（已含强度与模糊倍率）
        assertEquals(22.8f, exploreHardwareBlurPx(11.4f, 2f), 0.001f)
        assertEquals(36.6f, exploreHardwareBlurPx(18.3f, 2f), 0.001f)
        // 不乘软件管线的 1.4 等效系数
        assertEquals(36f, exploreHardwareBlurPx(12f, 3f), 0.001f)
        // 模糊倍率 0 在材质层已把 blurDp 乘成 0：与底栏 setBlurRadius 的 0.01 下限一致
        assertEquals(0.01f, exploreHardwareBlurPx(0f, 2f), 0.001f)
        // 上下限（density 0 退化为下限，超大半径钳到 50px）
        assertEquals(0.01f, exploreHardwareBlurPx(11.4f, 0f), 0.001f)
        assertEquals(50f, exploreHardwareBlurPx(11.4f, 100f), 0.001f)
        // 同一强度下透明玻璃档必须比磨砂档更轻
        assertTrue(
            exploreHardwareBlurPx(
                GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f).blurDp, 2f
            ) < exploreHardwareBlurPx(
                GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.3f).blurDp, 2f
            )
        )
    }

    /**
     * 软件管线半径：只由该档 blurDp × 模糊倍率决定，**不**再叠加主题背景模糊偏好，
     * 也不乘硬件管线的口径；0 表示不模糊（与 GPU 管线传 0 的语义一致）。
     */
    @Test
    fun softwareBlurRadiusFollowsPresetAndClamps() {
        // (blurDp × density × 1.4) / 3 取整
        assertEquals(10, ExploreGlassBackdrop.exploreSoftwareBlurRadius(11.4f, 2f))
        assertEquals(17, ExploreGlassBackdrop.exploreSoftwareBlurRadius(18.3f, 2f))
        assertEquals(0, ExploreGlassBackdrop.exploreSoftwareBlurRadius(0f, 3f))
        assertEquals(0, ExploreGlassBackdrop.exploreSoftwareBlurRadius(-5f, 3f))
        // 极小半径仍守住 1px 下限，超大半径钳到 25px
        assertEquals(1, ExploreGlassBackdrop.exploreSoftwareBlurRadius(0.5f, 2f))
        assertEquals(25, ExploreGlassBackdrop.exploreSoftwareBlurRadius(50f, 3f))
        // 同一强度下透明玻璃档必须比磨砂档更轻
        assertTrue(
            ExploreGlassBackdrop.exploreSoftwareBlurRadius(
                GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f).blurDp, 3f
            ) < ExploreGlassBackdrop.exploreSoftwareBlurRadius(
                GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.3f).blurDp, 3f
            )
        )
    }

    /** 真机缺陷：无玻璃档必须是 alpha=255 的实底；标准布局的底栏壁纸层仍用旧半透明口径。 */
    @Test
    fun solidShellAndBarSurfaceAreFullyOpaqueWhileLegacyFormulaStays() {
        assertEquals(1f, GlassMaterialPreset.solidAlpha(72, true), 0f)
        assertEquals(1f, GlassMaterialPreset.solidAlpha(0, true), 0f)
        assertEquals(255, (GlassMaterialPreset.solidAlpha(72, true) * 255f).toInt())

        // 即便主题底色自身带半透明 alpha，实底档也要钉成 255
        val opaque = GlassMaterialPreset.solidColor(0x8012_3456.toInt(), 72, true)
        assertEquals(255, opaque ushr 24)
        assertEquals(0x12_3456, opaque and 0x00ff_ffff)

        // 底栏 / 搜索按钮 / 悬浮球统一走命名纯函数：任何主题底色都固定 alpha=255
        listOf(
            0x0012_3456,
            0x8012_3456.toInt(),
            0x00_000000,
            0xFF_FFFFFF.toInt()
        ).forEach { base ->
            val fill = GlassMaterialPreset.solidSurfaceFillColor(base)
            assertEquals("base=$base 的底栏表面 alpha", 255, fill ushr 24)
            assertEquals("base=$base 的底栏表面 RGB", base and 0x00ff_ffff, fill and 0x00ff_ffff)
        }

        // 标准布局的底栏壁纸层不传 forceOpaque：内置图标包 76 / Config 默认 72 仍半透明
        assertEquals(0.72f, GlassMaterialPreset.solidAlpha(72), 0.0001f)
        assertEquals(0.76f, GlassMaterialPreset.solidAlpha(76), 0.0001f)
        assertEquals(0.72f, GlassMaterialPreset.solidOpacity(72), 0.0001f)
        assertEquals(183, GlassMaterialPreset.solidColor(0x0012_3456, 72) ushr 24)
    }

    /**
     * 真机缺陷（1.25.11 追加）：无玻璃档底栏浮在同样颜色的行背景上时轮廓完全消失。
     * 兜边色必须与底色对比：深色底给白色亮边、浅色底给黑色暗边，且 alpha 足以看见。
     */
    @Test
    fun solidShellStrokeAlwaysContrastsWithBottomBackground() {
        assertTrue(GlassMaterialPreset.SOLID_SHELL_STROKE_ALPHA >= 0.18f)
        val dark = GlassMaterialPreset.solidShellStroke(lightBase = false)
        assertEquals(0xFFFFFF, dark and 0x00ff_ffff)
        assertEquals(
            (GlassMaterialPreset.SOLID_SHELL_STROKE_ALPHA * 255).toInt(), dark ushr 24
        )
        val light = GlassMaterialPreset.solidShellStroke(lightBase = true)
        assertEquals(0x000000, light and 0x00ff_ffff)
        assertEquals(
            (GlassMaterialPreset.SOLID_SHELL_STROKE_ALPHA * 255).toInt(), light ushr 24
        )
        // 调用方可以覆盖 alpha（例如全局描边未设置时的主题化微调）
        assertEquals(255, GlassMaterialPreset.solidShellStroke(true, 1f) ushr 24)
        assertEquals(0, GlassMaterialPreset.solidShellStroke(false, 0f) ushr 24)
    }

    /**
     * 收尾加固 a：实底不透明的「决策 + 实参链路」由纯函数产出，不再只是单测里
     * 直接调 GlassMaterialPreset.solidColor 自证。生产侧 solidShellDrawable() 只能
     * 拿 exploreSolidShellColor() 的结果交给填充色重载，翻转决策或漏接都会失败。
     */
    @Test
    fun exploreSolidShellDecisionProducesOpaqueColorAndKeepsLegacyWallpaperLayerOpacity() {
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

        // 标准布局底栏壁纸层仍走 false 路径逐值不变：0.72 / 0.76 口径与 alpha 183 / 193
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
     * 现在每条断言都是「生产声明 vs 各自的手写基准」的逐值比较：渲染层多读一个键、
     * 设置页少写一个键、监听集合漏登记或多出无来源的键，都会失败并提示同步基准与监听。
     */
    @Test
    fun everyActuallyReadOrWrittenPrefKeyIsCoveredByRebindListener() {
        val source = TrackingPrefSource()
        val config = readExploreGlassConfig(source)
        // 只读非材质键：材质键由 GlassMaterialPreset.current 统一读取
        assertEquals(EXPLORE_GLASS_MODE_FROSTED, config.mode)
        assertNull(config.strokeColorRaw)
        assertTrue(config.hardwareBlur)
        assertTrue(config.animatedSolid)

        // 全部与独立手写基准逐值比较；这四条都不是恒等式，改变生产声明即失败。
        assertEquals(
            "渲染层实际读取的键与手写基准不一致（新增键请同步基准、设置页与监听集合）",
            expectedReadKeys, source.readKeys
        )
        assertEquals(
            "设置页写入/清除键与手写基准不一致",
            expectedSettingKeys, EXPLORE_GLASS_SETTING_KEYS
        )
        assertEquals(
            "底栏联动键与手写基准不一致",
            expectedBottomBarKeys, EXPLORE_GLASS_BOTTOM_BAR_KEYS
        )
        assertEquals(
            "即时重绑监听集合与手写基准不一致",
            expectedPrefKeys, EXPLORE_GLASS_PREF_KEYS
        )
        // 材质键（两个强度 + 四个倍率）虽然不在 readExploreGlassConfig 里，
        // 但必须进监听集合：它们由 GlassMaterialPreset.current 读取，漏登记同样
        // 会导致「改了滑杆返回发现页，行不刷新」。
        expectedMaterialKeys.forEach { key ->
            assertTrue("材质键 $key 未进入即时重绑键集合", key in EXPLORE_GLASS_PREF_KEYS)
        }
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
}
