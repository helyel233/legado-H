package io.legado.app.ui.config

import android.os.Build
import androidx.core.content.ContextCompat
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingHeaderSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.EXPLORE_GLASS_BOTTOM_BAR_KEYS
import io.legado.app.ui.main.explore.EXPLORE_GLASS_MODE_SOLID
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_DEFAULT
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_MAX
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SCALE_MIN
import io.legado.app.ui.main.explore.EXPLORE_GLASS_SETTING_KEYS
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolveExploreGlassMode
import io.legado.app.ui.main.explore.resolveGlassStrokeColor
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.utils.removePref
import java.util.Locale

/**
 * 「全局毛玻璃效果」：三档材质预设 + 各视觉维度微调 + 渲染开关，集中在一页。
 *
 * 材质样式（无玻璃 / 透明玻璃 / 磨砂玻璃）是底栏 solid/glass/frosted 同一套自动档的
 * **唯一事实来源**：底栏渲染读同一解析（resolveExploreGlassMode），切档后返回主界面
 * 即对底栏生效。每一档都对应滑杆空间里的一个**固定坐标**（[GlassMaterialPreset.presetPoint]：
 * 强度 + 四个倍率）：选一档会把整组数写回偏好，于是「预设」与「滑杆数值」不再是两套状态。
 * 之后拖动任一滑杆，当前值就偏离该档坐标，行尾胶囊改显示「自定义」
 * （[GlassMaterialPreset.matchesPreset]，不新增可选值、不新增偏好键）。
 * 顶栏毛玻璃与发现页行渲染共用本页的档位与强度设置；1.25.x 的「跟随底栏材质」
 * 开关已移除，升级用户由启动时的一次性迁移固化档位与强度。
 * 1.25.4 的独立总开关已从 UI 移除，由「无玻璃」档承担关闭职责；旧值仍被读取迁移，
 * 避免升级后原本关闭毛玻璃的用户被默认档重新打开。
 *
 * 页面顶部的实时预览与渲染层共用同一个材质入口（[GlassMaterialPreset.current]），
 * 拖任何一个滑杆都会立刻重建预览背景，不需要返回发现页比对。
 *
 * 强度滑杆只写两个全局键 frostedGlassLevel / liquidGlassLevel：
 * 液态玻璃管线（发现页行 / 底栏 / 顶栏）的强度全部来自这两个键，
 * 磨砂档读前者、其余档读后者（[GlassMaterialPreset.levelFor]）。
 * 旧日夜间键 themeExploreGlassBlur / Night 仅保留给启动迁移读取，刷杆不再写它们。
 *
 * 注意：构建搜索索引时会 new 一个未 attach 的实例调 [buildSpec]，
 * 因此 [buildPageSpec] 只依赖 [ctx] 与静态常量，不碰 Activity / View / 生命周期。
 */
class ExploreGlassConfigFragment : ComposeSettingFragment(), ColorPickerDialogListener {

    override val titleRes: Int = R.string.explore_glass_settings_title

    /** 与导航栏管理页同款色板对话框 id：本页只有描边色一个取色入口。 */
    private val strokeColorDialogId = 7002

    override fun buildPageSpec(): SettingPageSpec {
        // 空串代表「从未存过档位」，让 resolveExploreGlassMode 能执行旧总开关迁移。
        // 这里与渲染层调用同一个纯函数，保证设置页显示的档位就是实际生效的档位。
        val storedMode = stringSetting(PreferKey.exploreGlassEffectMode, "")
        val legacyEnabled = booleanSetting(PreferKey.exploreGlassEnabled, true)
        val mode = resolveExploreGlassMode(storedMode, legacyEnabled)
        val slidersEnabled = mode != EXPLORE_GLASS_MODE_SOLID
        val descriptions = ctx.resources.getStringArray(R.array.explore_glass_mode_descriptions)
        val options = choiceOptionList(
            R.array.explore_glass_mode_entries, R.array.explore_glass_mode_values
        ).mapIndexed { index, option ->
            option.copy(description = descriptions.getOrNull(index))
        }
        val hardwareSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val hardwareBlur = booleanSetting(PreferKey.exploreGlassHardwareBlur, true)
        val strokeColorRaw = stringSetting(PreferKey.glassStrokeColor, "")
        // 材质唯一入口：预览与发现页行 / 底栏 / 顶栏读同一个函数产物。
        // 强度滑杆显示的就是该档渲染层实际生效的强度（磨砂档读 frosted，其余读 liquid）。
        val material = GlassMaterialPreset.current(mode)
        val frostedLevel = intSetting(PreferKey.frostedGlassLevel, AppConfig.frostedGlassLevel)
        val liquidLevel = intSetting(PreferKey.liquidGlassLevel, AppConfig.liquidGlassLevel)
        val scales = GlassMaterialPreset.Scales(
            blur = scaleSetting(PreferKey.exploreGlassBlurScale),
            tint = scaleSetting(PreferKey.exploreGlassTintScale),
            shell = scaleSetting(PreferKey.exploreGlassShellScale),
            stroke = scaleSetting(PreferKey.exploreGlassStrokeScale)
        )
        val level = GlassMaterialPreset.levelFor(mode, frostedLevel, liquidLevel).coerceIn(0, 100)
        // 当前滑杆是否仍落在该档的预设坐标上；偏离即「自定义」，与弹窗勾选互不影响。
        val presetMatched = GlassMaterialPreset.matchesPreset(mode, frostedLevel, liquidLevel, scales)
        val previewPipeline = exploreGlassPreviewPipelineNow(
            context = ctx,
            mode = mode,
            hardwareBlurPref = hardwareBlur
        )
        val previewSignature = ExploreGlassBackdrop.configSignature(ctx)
        return SettingPageSpec(
            titleRes = titleRes,
            header = SettingHeaderSpec {
                ExploreGlassPreview(
                    signature = previewSignature,
                    mode = mode,
                    material = material,
                    level = level,
                    pipeline = previewPipeline
                )
            },
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.explore_glass_section_effect),
                    items = listOf(
                        SettingChoiceSpec(
                            key = PreferKey.exploreGlassEffectMode,
                            title = str(R.string.explore_glass_effect_mode),
                            summary = str(R.string.explore_glass_effect_mode_summary),
                            options = options,
                            // 偏离预设坐标时传空值：弹窗里不再勾选任何一档（与「自定义」
                            // 一致），再点同一档就是把它重置回该档的固定滑杆数值。
                            selectedValue = if (presetMatched) mode else "",
                            // 选一档 = 写入该档的固定滑杆坐标（强度 + 四个倍率），
                            // 于是「预设」在界面上有唯一确定的滑杆位置；
                            // 之后拖动任一滑杆即偏离坐标，胶囊显示「自定义」。
                            onSelected = { next ->
                                val point = GlassMaterialPreset.presetPoint(next)
                                updateIntSetting(PreferKey.frostedGlassLevel, point.level)
                                updateIntSetting(PreferKey.liquidGlassLevel, point.level)
                                updateIntSetting(PreferKey.exploreGlassBlurScale, point.scales.blur)
                                updateIntSetting(PreferKey.exploreGlassTintScale, point.scales.tint)
                                updateIntSetting(PreferKey.exploreGlassShellScale, point.scales.shell)
                                updateIntSetting(PreferKey.exploreGlassStrokeScale, point.scales.stroke)
                                updateStringSetting(PreferKey.exploreGlassEffectMode, next)
                            },
                            labelOverride = if (presetMatched) {
                                null
                            } else {
                                str(R.string.explore_glass_mode_custom)
                            },
                            searchKeys = listOf(
                                "材质", "无玻璃", "透明玻璃", "磨砂玻璃", "自动档",
                                "预设", "毛玻璃", "glass mode", "frosted", "solid"
                            )
                        ),
                        SettingSliderSpec(
                            key = PreferKey.frostedGlassLevel,
                            title = str(R.string.explore_glass_level),
                            summary = str(R.string.explore_glass_level_summary),
                            value = level,
                            valueRange = 0..100,
                            enabled = slidersEnabled,
                            valueFormatter = { "$it%" },
                            onValueChange = {
                                // 两个全局强度键同写同值：底栏 / 顶栏 / 发现页行三处
                                // 走的都是 GlassMaterialPreset.current，不会读到不同强度。
                                updateIntSetting(PreferKey.frostedGlassLevel, it)
                                updateIntSetting(PreferKey.liquidGlassLevel, it)
                            },
                            searchKeys = listOf(
                                "强度", "毛玻璃效果", "透明度", "glass level",
                                // 旧滑杆键仍可被搜索定位（数值已迁移到两个全局键）
                                PreferKey.themeExploreGlassBlur,
                                PreferKey.themeExploreGlassBlurN
                            )
                        ),
                        strokeColorAction(strokeColorRaw = strokeColorRaw),
                        scaleSlider(
                            key = PreferKey.exploreGlassBlurScale,
                            value = scales.blur,
                            titleRes = R.string.explore_glass_blur_scale,
                            summaryRes = R.string.explore_glass_blur_scale_summary,
                            enabled = slidersEnabled,
                            searchKeys = listOf("模糊", "半径", "高斯", "磨砂", "blur radius")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassShellScale,
                            value = scales.shell,
                            titleRes = R.string.explore_glass_shell_scale,
                            summaryRes = R.string.explore_glass_shell_scale_summary,
                            enabled = slidersEnabled,
                            searchKeys = listOf("遮盖", "底色", "实底", "壳层", "scrim")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassTintScale,
                            value = scales.tint,
                            titleRes = R.string.explore_glass_tint_scale,
                            summaryRes = R.string.explore_glass_tint_scale_summary,
                            enabled = slidersEnabled,
                            searchKeys = listOf("着色", "白色", "高光", "tint")
                        ),
                        scaleSlider(
                            key = PreferKey.exploreGlassStrokeScale,
                            value = scales.stroke,
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

    /**
     * 描边颜色行：底栏包 borderColor/borderAlpha 字段已废弃删除，描边色统一存
     * [PreferKey.glassStrokeColor]（ARGB 十进制字符串；空 = 不描边）。
     * 三档材质均可用——无玻璃档描边色同样作用于实底壳层，故不随 slidersEnabled 置灰。
     * summary 展示当前色值，点击弹出「禁用 / 选择颜色」后进入色板对话框。
     */
    private fun strokeColorAction(strokeColorRaw: String): SettingActionSpec {
        val strokeColor = resolveGlassStrokeColor(strokeColorRaw)
        val summary = strokeColor?.let { colorLabel(it) } ?: str(R.string.disable)
        return SettingActionSpec(
            key = PreferKey.glassStrokeColor,
            title = str(R.string.explore_glass_stroke_color),
            summary = summary,
            searchKeys = listOf(
                "描边颜色", "边框颜色", "轮廓", "玻璃描边", "stroke color", "border"
            ),
            onClick = { showStrokeColorSelector(strokeColor) }
        )
    }

    private fun showStrokeColorSelector(current: Int?) {
        showComposeChoiceListDialog(
            title = str(R.string.explore_glass_stroke_color),
            labels = listOf(str(R.string.disable), str(R.string.select_color)),
            selectedIndex = if (current != null) 1 else 0,
            negativeText = str(R.string.cancel),
            onSelected = { index ->
                if (index == 0) {
                    updateStringSetting(PreferKey.glassStrokeColor, "")
                    refreshSettings()
                } else {
                    showColorPicker(current ?: ContextCompat.getColor(ctx, R.color.accent))
                }
            }
        )
    }

    private fun showColorPicker(color: Int) {
        // 显式挂 listener（宿主是 Fragment 而非 Activity，库默认只向 Activity 解析）
        val dialog = ColorPickerDialog.newBuilder()
            .setDialogId(strokeColorDialogId)
            .setColor(color)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .create()
        dialog.setColorPickerDialogListener(this)
        dialog.show(childFragmentManager, "ExploreGlassStrokeColor")
    }

    private fun colorLabel(color: Int): String {
        return "#${Integer.toHexString(color).takeLast(6).uppercase(Locale.ROOT)}"
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        if (dialogId != strokeColorDialogId) return
        updateStringSetting(PreferKey.glassStrokeColor, color.toString())
        refreshSettings()
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    /** 倍率键的当前值（统一限幅，预览坐标与滑杆显示读同一份）。 */
    private fun scaleSetting(key: String): Int =
        intSetting(key, EXPLORE_GLASS_SCALE_DEFAULT)
            .coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX)

    /** 四个倍率滑杆同构：范围、限幅、格式化与写入路径完全一致。 */
    private fun scaleSlider(
        key: String,
        value: Int,
        titleRes: Int,
        summaryRes: Int,
        enabled: Boolean,
        searchKeys: List<String>
    ): SettingSliderSpec = SettingSliderSpec(
        key = key,
        title = str(titleRes),
        summary = str(summaryRes),
        value = value.coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX),
        valueRange = EXPLORE_GLASS_SCALE_MIN..EXPLORE_GLASS_SCALE_MAX,
        enabled = enabled,
        valueFormatter = { "$it%" },
        onValueChange = { updateIntSetting(key, it) },
        searchKeys = searchKeys
    )

    private companion object {
        /**
         * 恢复默认时清除的键：页面键（档位、描边色、四个倍率、渲染开关、旧日夜间强度）
         * 与底栏 / 顶栏 / 行共用的两个全局强度键，两个集合都来自
         * [ExploreGlassBackdrop] 的唯一键声明，新增设置项不会漏清。
         */
        val resetKeys: List<String> =
            (EXPLORE_GLASS_SETTING_KEYS + EXPLORE_GLASS_BOTTOM_BAR_KEYS).toList()
    }
}
