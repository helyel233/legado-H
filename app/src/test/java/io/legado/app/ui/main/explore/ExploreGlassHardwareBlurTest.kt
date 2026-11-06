package io.legado.app.ui.main.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毛玻璃参数的确定性单测。
 *
 * 半径、倍率与材质档位系数是核心参数：口径错（误乘软件管线的 1.4 等效系数、
 * 漏乘 density、限幅失效、倍率越界、默认档系数不为 1、旧开关迁移丢失、跟随底栏
 * 映射错档）都不会导致编译失败，只会让观感或低端 GPU 开销悄悄偏离，
 * 故抽成纯函数后用确定性断言锁住，不依赖真机与运行时推断。
 */
class ExploreGlassHardwareBlurTest {

    @Test
    fun frostedBlurFollowsDensityLinearly() {
        // glassLevel=0（关闭档）→ blurDp=12；density=2 → 24px
        assertEquals(24f, exploreHardwareBlurPx(0f, 2f), 0.001f)
        // glassLevel=0.5（设置滑杆 100 档）→ blurDp=12+9=21；density=2 → 42px
        assertEquals(42f, exploreHardwareBlurPx(0.5f, 2f), 0.001f)
        // glassLevel=0.3（默认 60 档）→ blurDp=17.4；density=3 → 52.2px
        assertEquals(52.2f, exploreHardwareBlurPx(0.3f, 3f), 0.001f)
    }

    @Test
    fun doesNotApplySoftwareStackBlurFactor() {
        // 若误乘软件管线的 1.4 等效系数，12dp@3x 会变成 50.4px 而非 36px
        assertEquals(36f, exploreHardwareBlurPx(0f, 3f), 0.001f)
        assertEquals(63f, exploreHardwareBlurPx(0.5f, 3f), 0.001f)
    }

    @Test
    fun radiusClampsToSupportedRange() {
        // density 为 0 / 强度为负 → 仍守住下限 1px，不把 0 半径交给 RenderEffect
        assertEquals(1f, exploreHardwareBlurPx(0f, 0f), 0.001f)
        assertEquals(1f, exploreHardwareBlurPx(-1f, 0.01f), 0.001f)
        // 超大密度 / 超量程强度 → 仍守住上限 80px，避免低端 GPU 开销陡增
        assertEquals(80f, exploreHardwareBlurPx(0.5f, 100f), 0.001f)
        assertEquals(80f, exploreHardwareBlurPx(10f, 3f), 0.001f)
    }

    @Test
    fun radiusIsMonotonicInGlassLevel() {
        var previous = 0f
        var level = 0f
        while (level <= 0.5f) {
            val current = exploreHardwareBlurPx(level, 2f)
            assertTrue(current >= previous)
            previous = current
            level += 0.05f
        }
    }

    @Test
    fun scaleParamKeepsDefaultAndClampsOutOfRange() {
        // 设置项默认 100 必须原样返回 1.25.3 既有口径，不能改变默认观感
        assertEquals(0.5f, scaledGlassParam(0.5f, 100), 0.001f)
        assertEquals(0f, scaledGlassParam(0.5f, 0), 0.001f)
        assertEquals(1f, scaledGlassParam(0.5f, 200), 0.001f)
        // 备份恢复写入越界值时先钳到 [0, 200]，避免 alpha 越界或负倍率
        assertEquals(1f, scaledGlassParam(0.5f, 999), 0.001f)
        assertEquals(0f, scaledGlassParam(0.5f, -50), 0.001f)
        // 各维度自身的上限（描边 0.42 / shell 0.88 / tint 1）仍须守住
        assertEquals(0.42f, scaledGlassParam(0.34f, 200, 0.42f), 0.001f)
        assertEquals(0.88f, scaledGlassParam(1.6f, 100, 0.88f), 0.001f)
    }

    @Test
    fun hardwareBlurRespectsBlurPercent() {
        // glassLevel=0、density=2：12dp → 24px；0 倍率受 1px 下限保护，200 倍率得 48px
        assertEquals(1f, exploreHardwareBlurPx(0f, 2f, 0), 0.001f)
        assertEquals(24f, exploreHardwareBlurPx(0f, 2f, 100), 0.001f)
        assertEquals(48f, exploreHardwareBlurPx(0f, 2f, 200), 0.001f)
        // 倍率再大也不能突破 80px 上限
        assertEquals(80f, exploreHardwareBlurPx(0.5f, 100f, 200), 0.001f)
    }

    @Test
    fun modeResolutionCoversDefaultsLegacyAndDirtyValues() {
        // 从未设置档位：默认磨砂玻璃，不受底栏当前档位影响（未开启跟随）
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(null, true, false, "glass")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_NONE,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_NONE, true, false, "frosted")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_GLASS,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_GLASS, true, false, "solid")
        )
        // 1.25.4 旧总开关关闭且从未设过档位 → 迁移成无玻璃，升级后不会莫名重新开启
        assertEquals(
            EXPLORE_GLASS_MODE_NONE,
            resolveExploreGlassMode(null, false, false, "frosted")
        )
        // 用户在本页显式选档后以显式档位为准，旧开关不再否决
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_FROSTED, false, false, "solid")
        )
        // 备份恢复写入脏值时回落到默认档，不出现「半档」状态
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode("ultra", true, false, "glass")
        )
    }

    @Test
    fun followBottomBarMapsThreeModesAndOverridesStoredMode() {
        assertEquals(
            EXPLORE_GLASS_MODE_NONE,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_FROSTED, true, true, "solid")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_GLASS,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_NONE, true, true, "glass")
        )
        assertEquals(
            EXPLORE_GLASS_MODE_FROSTED,
            resolveExploreGlassMode(EXPLORE_GLASS_MODE_NONE, true, true, "frosted")
        )
        // 底栏未知取值按玻璃兜底，不能解析成无玻璃（会让整页玻璃消失）
        assertEquals(
            EXPLORE_GLASS_MODE_GLASS,
            resolveExploreGlassMode(null, true, true, "unknown")
        )
    }

    @Test
    fun presetsKeepFrostedAsIdentity() {
        val frosted = exploreGlassPreset(EXPLORE_GLASS_MODE_FROSTED)
        assertEquals(1f, frosted.blurFactor, 0.001f)
        assertEquals(1f, frosted.shellFactor, 0.001f)
        assertEquals(1f, frosted.tintFactor, 0.001f)
        assertEquals(1f, frosted.strokeFactor, 0.001f)

        val glass = exploreGlassPreset(EXPLORE_GLASS_MODE_GLASS)
        assertEquals(0.55f, glass.blurFactor, 0.001f)
        assertEquals(0.40f, glass.shellFactor, 0.001f)
        assertEquals(0.45f, glass.tintFactor, 0.001f)
        assertEquals(1.25f, glass.strokeFactor, 0.001f)
        // 透明玻璃必须「更透」：模糊/遮盖/着色更弱，仅描边更强
        assertTrue(glass.blurFactor < frosted.blurFactor)
        assertTrue(glass.shellFactor < frosted.shellFactor)
        assertTrue(glass.tintFactor < frosted.tintFactor)
        assertTrue(glass.strokeFactor > frosted.strokeFactor)

        val none = exploreGlassPreset(EXPLORE_GLASS_MODE_NONE)
        assertEquals(0f, none.blurFactor, 0.001f)
        assertEquals(0f, none.shellFactor, 0.001f)
        assertEquals(0f, none.tintFactor, 0.001f)
        assertEquals(0f, none.strokeFactor, 0.001f)
        // 未知档位按磨砂玻璃处理
        assertEquals(1f, exploreGlassPreset("dirty").blurFactor, 0.001f)
    }

    @Test
    fun presetFactorScalesButStillClamps() {
        assertEquals(0.275f, scaledGlassParam(0.5f, 100, 1f, 0.55f), 0.001f)
        // 描边 1.25 倍 + 滑杆 200% 仍守住 0.42 上限
        assertEquals(0.42f, scaledGlassParam(0.34f, 200, 0.42f, 1.25f), 0.001f)
        // 负系数钳到 0，负基础值也不会被放大成正 alpha
        assertEquals(0f, scaledGlassParam(0.5f, 100, 1f, -1f), 0.001f)
        assertEquals(0f, scaledGlassParam(-0.5f, 100, 1f, 0.55f), 0.001f)
        // 缺省 factor=1 时口径与 1.25.4 完全一致
        assertEquals(0.5f, scaledGlassParam(0.5f, 100, 1f), 0.001f)
    }

    @Test
    fun hardwareBlurRespectsPresetFactor() {
        // glassLevel=0、density=2：磨砂档 24px；透明玻璃档 24*0.55=13.2px
        assertEquals(24f, exploreHardwareBlurPx(0f, 2f, 100, 1f), 0.001f)
        assertEquals(13.2f, exploreHardwareBlurPx(0f, 2f, 100, 0.55f), 0.001f)
        // 系数 0 仍守住 1px 下限，不把 0 半径交给 RenderEffect
        assertEquals(1f, exploreHardwareBlurPx(0f, 2f, 100, 0f), 0.001f)
        // 系数放大也不越 80px 上限
        assertEquals(80f, exploreHardwareBlurPx(0.5f, 100f, 200, 1.25f), 0.001f)
    }
}
