package io.legado.app.ui.config

import android.os.Build
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.main.explore.EXPLORE_GLASS_MODE_SOLID
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_DEFAULT
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_MAX
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_MIN
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolveExploreGlassMode
import io.legado.app.utils.removePref

/**
 * 「我的 › 发现页毛玻璃」：三档材质预设 + 各视觉维度微调 + 渲染开关，集中在一页。
 *
 * 材质样式（无玻璃 / 透明玻璃 / 磨砂玻璃）与底栏 solid/glass/frosted 是同一套自动档：
 * 选一档即套用 GlassMaterialPreset 里该档的完整参数（模糊、着色、壳层、描边、
 * 色散/折射），下面四个倍率滑杆在预设原值之上继续微调，切档不清空微调值。
 * 「跟随底栏材质」开启时档位与强度都取底栏当前预设，本页档位保留但不生效。
 * 1.25.4 的独立总开关已从 UI 移除，由「无玻璃」档承担关闭职责；旧值仍被读取迁移，
 * 避免升级后原本关闭毛玻璃的用户被默认档重新打开。
 *
 * 与编辑主题页的「发现页毛玻璃效果」共用同一个日夜间分离的强度键
 * （[ThemeRuntimeKeys.themeExploreGlassBlur]），两处入口写同一份数据。
 *
 * 注意：构建搜索索引时会 new 一个未 attach 的实例调 [buildSpec]，
 * 因此 [buildPageSpec] 只依赖 [ctx] 与静态常量，不碰 Activity / View / 生命周期。
 */
class ExploreGlassConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.explore_glass_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        // 空串代表「从未存过档位」，让 resolveExploreGlassMode 能执行旧总开关迁移。
        // 这里与渲染层调用同一个纯函数，保证设置页显示的档位就是发现页实际生效的档位。
        val storedMode = stringSetting(PreferKey.exploreGlassEffectMode, "")
        val legacyEnabled = booleanSetting(PreferKey.exploreGlassEnabled, true)
        val followBottomBar = booleanSetting(PreferKey.exploreGlassFollowBottomBar, false)
        val mode = resolveExploreGlassMode(
            storedMode, legacyEnabled, followBottomBar, AppConfig.bottomBarEffectMode
        )
        val slidersEnabled = mode != EXPLORE_GLASS_MODE_SOLID
        val descriptions = ctx.resources.getStringArray(R.array.explore_glass_mode_descriptions)
        val options = choiceOptionList(
            R.array.explore_glass_mode_entries, R.array.explore_glass_mode_values
        ).mapIndexed { index, option ->
            option.copy(description = descriptions.getOrNull(index))
        }
        val modeLabel = options.firstOrNull { it.value == mode }?.label?.toString() ?: mode
        val hardwareSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.explore_glass_section_effect),
                    items = listOf(
                        SettingChoiceSpec(
                            key = PreferKey.exploreGlassEffectMode,
                            title = str(R.string.explore_glass_effect_mode),
                            summary = if (followBottomBar) {
                                str(R.string.explore_glass_effect_mode_following, modeLabel)
                            } else str(R.string.explore_glass_effect_mode_summary),
                            options = options,
                            selectedValue = mode,
                            enabled = !followBottomBar,
                            onSelected = {
                                updateStringSetting(PreferKey.exploreGlassEffectMode, it)
                            },
                            searchKeys = listOf(
                                "材质", "无玻璃", "透明玻璃", "磨砂玻璃", "自动档",
                                "预设", "跟随底栏", "glass mode", "frosted", "solid"
                            )
                        ),
                        SettingSliderSpec(
                            key = ThemeRuntimeKeys.themeExploreGlassBlur(),
                            title = str(R.string.explore_glass_level),
                            summary = if (followBottomBar) {
                                // 跟随时强度也取底栏的 frostedGlassLevel / liquidGlassLevel，
                                // 本滑杆不参与计算，置灰并如实说明，避免显示一个无效的数值。
                                str(R.string.explore_glass_level_following)
                            } else str(R.string.explore_glass_level_summary),
                            value = intSetting(
                                ThemeRuntimeKeys.themeExploreGlassBlur(),
                                ExploreGlassBackdrop.LEVEL_DEFAULT
                            ).coerceIn(0, 100),
                            valueRange = 0..100,
                            // 跟随底栏开启时强度逐值取 frostedGlassLevel / liquidGlassLevel，
                            // 本页强度不参与计算，置灰是如实反映联动关系（不是「滑杆失效」）；
                            // 四个倍率滑杆仍生效，关掉跟随即恢复本页强度。
                            enabled = slidersEnabled && !followBottomBar,
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
                            enabled = slidersEnabled,
                            searchKeys = listOf("模糊", "半径", "高斯", "磨砂", "blur radius")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassShellScale,
                            titleRes = R.string.explore_glass_shell_scale,
                            summaryRes = R.string.explore_glass_shell_scale_summary,
                            enabled = slidersEnabled,
                            searchKeys = listOf("遮盖", "底色", "实底", "壳层", "scrim")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassTintScale,
                            titleRes = R.string.explore_glass_tint_scale,
                            summaryRes = R.string.explore_glass_tint_scale_summary,
                            enabled = slidersEnabled,
                            searchKeys = listOf("着色", "白色", "高光", "tint")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassStrokeScale,
                            titleRes = R.string.explore_glass_stroke_scale,
                            summaryRes = R.string.explore_glass_stroke_scale_summary,
                            enabled = slidersEnabled,
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
                            enabled = slidersEnabled,
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
                            enabled = slidersEnabled,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.exploreGlassAnimatedSolid, it)
                            },
                            searchKeys = listOf("动图", "gif", "webp", "壁纸", "首帧")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.exploreGlassFollowBottomBar,
                            title = str(R.string.explore_glass_follow_bottom_bar),
                            summary = str(R.string.explore_glass_follow_bottom_bar_summary),
                            checked = followBottomBar,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.exploreGlassFollowBottomBar, it)
                            },
                            searchKeys = listOf("跟随底栏", "联动", "自动", "底栏材质", "sync")
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
        /** 恢复默认时清除的键：全局细项 + 档位/联动 + 强度的日间与夜间两个键。 */
        val resetKeys = listOf(
            PreferKey.exploreGlassEnabled,
            PreferKey.exploreGlassEffectMode,
            PreferKey.exploreGlassFollowBottomBar,
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
