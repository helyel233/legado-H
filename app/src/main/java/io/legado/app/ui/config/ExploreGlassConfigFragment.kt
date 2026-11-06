package io.legado.app.ui.config

import android.os.Build
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_DEFAULT
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_MAX
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_MIN
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.utils.removePref

/**
 * 「我的 › 发现页毛玻璃」：把书源名行玻璃的各个视觉维度与渲染开关集中到一页。
 *
 * 与编辑主题页的「发现页毛玻璃效果」共用同一个日夜间分离的强度键
 * （[ThemeRuntimeKeys.themeExploreGlassBlur]），两处入口写同一份数据，不会互相覆盖。
 * 其余细项是全局倍率/开关，默认 100 即 1.25.3 的既有观感。
 *
 * 注意：构建搜索索引时会 new 一个未 attach 的实例调 [buildSpec]，
 * 因此 [buildPageSpec] 只依赖 [ctx] 与静态常量，不碰 Activity / View / 生命周期。
 */
class ExploreGlassConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.explore_glass_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        val enabled = booleanSetting(PreferKey.exploreGlassEnabled, true)
        val hardwareSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.explore_glass_section_effect),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.exploreGlassEnabled,
                            title = str(R.string.explore_glass_enabled),
                            summary = str(R.string.explore_glass_enabled_summary),
                            checked = enabled,
                            onCheckedChange = { updateBooleanSetting(PreferKey.exploreGlassEnabled, it) },
                            searchKeys = listOf("毛玻璃", "磨砂", "玻璃开关", "blur", "frosted")
                        ),
                        SettingSliderSpec(
                            key = ThemeRuntimeKeys.themeExploreGlassBlur(),
                            title = str(R.string.explore_glass_level),
                            summary = str(R.string.explore_glass_level_summary),
                            value = intSetting(
                                ThemeRuntimeKeys.themeExploreGlassBlur(),
                                ExploreGlassBackdrop.LEVEL_DEFAULT
                            ).coerceIn(0, 100),
                            valueRange = 0..100,
                            enabled = enabled,
                            valueFormatter = { "$it%" },
                            onValueChange = {
                                updateIntSetting(ThemeRuntimeKeys.themeExploreGlassBlur(), it)
                            },
                            searchKeys = listOf("强度", "毛玻璃效果", "透明度", "glass level")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassBlurScale,
                            titleRes = R.string.explore_glass_blur_scale,
                            summaryRes = R.string.explore_glass_blur_scale_summary,
                            enabled = enabled,
                            searchKeys = listOf("模糊", "半径", "高斯", "磨砂", "blur radius")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassShellScale,
                            titleRes = R.string.explore_glass_shell_scale,
                            summaryRes = R.string.explore_glass_shell_scale_summary,
                            enabled = enabled,
                            searchKeys = listOf("遮盖", "底色", "实底", "壳层", "scrim")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassTintScale,
                            titleRes = R.string.explore_glass_tint_scale,
                            summaryRes = R.string.explore_glass_tint_scale_summary,
                            enabled = enabled,
                            searchKeys = listOf("着色", "白色", "高光", "tint")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassStrokeScale,
                            titleRes = R.string.explore_glass_stroke_scale,
                            summaryRes = R.string.explore_glass_stroke_scale_summary,
                            enabled = enabled,
                            searchKeys = listOf("描边", "边框", "轮廓", "stroke")
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.explore_glass_section_advanced),
                    collapsible = true,
                    collapsedByDefault = true,
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.exploreGlassHardwareBlur,
                            title = str(R.string.explore_glass_hardware_blur),
                            summary = str(R.string.explore_glass_hardware_blur_summary),
                            checked = booleanSetting(PreferKey.exploreGlassHardwareBlur, true),
                            visible = hardwareSupported,
                            enabled = enabled,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.exploreGlassHardwareBlur, it)
                            },
                            searchKeys = listOf("真高斯", "硬件加速", "GPU", "RenderEffect", "软件模糊")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.exploreGlassAnimatedSolid,
                            title = str(R.string.explore_glass_animated_solid),
                            summary = str(R.string.explore_glass_animated_solid_summary),
                            checked = booleanSetting(PreferKey.exploreGlassAnimatedSolid, true),
                            enabled = enabled,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.exploreGlassAnimatedSolid, it)
                            },
                            searchKeys = listOf("动图", "gif", "webp", "壁纸", "首帧")
                        ),
                        SettingActionSpec(
                            key = PreferKey.exploreGlassRestoreDefaults,
                            title = str(R.string.explore_glass_restore_defaults),
                            summary = str(R.string.explore_glass_restore_defaults_summary),
                            onClick = {
                                resetKeys.forEach { ctx.removePref(it) }
                                refreshSettings()
                            },
                            searchKeys = listOf("重置", "默认", "还原", "reset")
                        )
                    )
                )
            )
        )
    }

    /** 四个倍率滑杆同构：范围、默认值、限幅、格式化与写入路径完全一致。 */
    private fun scaleSlider(
        key: String,
        titleRes: Int,
        summaryRes: Int,
        enabled: Boolean,
        searchKeys: List<String>
    ): SettingSliderSpec = SettingSliderSpec(
        key = key,
        title = str(titleRes),
        summary = str(summaryRes),
        value = intSetting(key, EXPLORE_GLASS_SCALE_DEFAULT)
            .coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX),
        valueRange = EXPLORE_GLASS_SCALE_MIN..EXPLORE_GLASS_SCALE_MAX,
        enabled = enabled,
        valueFormatter = { "$it%" },
        onValueChange = { updateIntSetting(key, it) },
        searchKeys = searchKeys
    )

    private companion object {
        /** 恢复默认时清除的键：全局细项 + 强度的日间/夜间两个键。 */
        val resetKeys = listOf(
            PreferKey.exploreGlassEnabled,
            PreferKey.exploreGlassBlurScale,
            PreferKey.exploreGlassShellScale,
            PreferKey.exploreGlassTintScale,
            PreferKey.exploreGlassStrokeScale,
            PreferKey.exploreGlassHardwareBlur,
            PreferKey.exploreGlassAnimatedSolid,
            PreferKey.themeExploreGlassBlur,
            PreferKey.themeExploreGlassBlurN
        )
    }
}
