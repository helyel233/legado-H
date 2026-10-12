package io.legado.app.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.components.AppBottomSheet
import io.legado.app.uikit.v5.components.AppDivider
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.DensityLevel
import io.legado.app.uikit.v5.token.GlassSpec
import io.legado.app.uikit.v5.token.IconStyle
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.MotionLevel
import io.legado.app.uikit.v5.token.NavPosition
import io.legado.app.uikit.v5.token.NavVisibility
import io.legado.app.uikit.v5.token.SHAPE_SCALE_RANGE
import io.legado.app.uikit.v5.token.ShapeFamily
import io.legado.app.uikit.v5.token.TitleWeight
import io.legado.app.uikit.v5.pack.LayoutPackageSpecV2
import io.legado.app.theme.pack.ThemeFonts
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.uikit.v5.token.IconSpec
import io.legado.app.uikit.v5.token.DensitySpec
import io.legado.app.uikit.v5.token.MotionSpec

/**
 * P3-3 包内编辑抽屉（另存模型：官方包已在打开时克隆为"我的包"）。
 * 每次改动即保存并实时应用 —— 预览就是全 App 本身。
 */

// ---------------------------------------------------------------- 主题包编辑

private val SEED_PRESETS = listOf(
    "晴空白" to "#5B6ABF", "靛蓝" to "#3D5AFE", "玫红" to "#C2185B",
    "森绿" to "#2E7D5B", "橙" to "#E07A3B", "榛子棕" to "#8D6E63",
    "纸" to "#A08A5B", "灰阶" to "#808080",
)
private val STRATEGIES = listOf("tonal" to "标准", "amoled" to "AMOLED", "muted" to "低饱和", "mono" to "灰阶")

@Composable
fun ThemeEditorSheet(
    spec: ThemePackageSpec,
    onUpdate: (ThemePackageSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val d = visual.density
    val context = androidx.compose.ui.platform.LocalContext.current

    // 资产选择器（P3-1：字体 / 背景图 / 纹理，选中即拷入 app 私有目录并落进包 spec）
    @androidx.compose.runtime.Composable
    fun picker(onPicked: (String) -> Unit) =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val path = io.legado.app.ui.host.pack.V5PackageStore.importAsset(uri, spec.id, "asset")
            if (path != null) onPicked(path)
        }

    val uiFontPicker = picker { path ->
        onUpdate(spec.copy(fonts = ThemeFonts(ui = path, title = spec.fonts?.title)))
    }
    val titleFontPicker = picker { path ->
        onUpdate(spec.copy(fonts = ThemeFonts(ui = spec.fonts?.ui, title = path)))
    }
    val bgDayPicker = picker { path ->
        val img = spec.images ?: io.legado.app.theme.pack.ThemeImages()
        onUpdate(spec.copy(images = img.copy(background = path)))
    }
    val bgNightPicker = picker { path ->
        val img = spec.images ?: io.legado.app.theme.pack.ThemeImages()
        onUpdate(spec.copy(images = img.copy(backgroundNight = path)))
    }
    val texturePicker = picker { path ->
        onUpdate(spec.copy(texture = io.legado.app.theme.pack.ThemeTexture(path = path, opacity = spec.texture?.opacity ?: 0.06f)))
    }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(d.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(d.blockGap),
        ) {
            Text(spec.name, style = visual.type.title, color = colors.onSurface)
            Text("主题包管美术：颜色、字体、背景图、纹理。结构参数去界面包。", style = visual.type.caption, color = colors.onSurfaceVariant)

            EditSection("种子色") {
                Chips(
                    items = SEED_PRESETS,
                    selected = spec.seed,
                    onSelect = { _, value -> onUpdate(spec.copy(seed = value)) },
                )
            }
            EditSection("夜间推导策略") {
                Chips(
                    items = STRATEGIES,
                    selected = spec.darkStrategy,
                    onSelect = { key, _ -> onUpdate(spec.copy(darkStrategy = key)) },
                )
            }
            EditSection("字体") {
                AssetRow(
                    "界面字体", spec.fonts?.ui?.let { java.io.File(it).name } ?: "系统默认",
                    onClick = { uiFontPicker.launch("font/*") },
                    onClear = { onUpdate(spec.copy(fonts = ThemeFonts(ui = null, title = spec.fonts?.title))) },
                )
                AssetRow(
                    "标题字体", spec.fonts?.title?.let { java.io.File(it).name } ?: "跟随界面字体",
                    onClick = { titleFontPicker.launch("font/*") },
                    onClear = { onUpdate(spec.copy(fonts = ThemeFonts(ui = spec.fonts?.ui, title = null))) },
                )
            }
            EditSection("背景图（主界面）") {
                AssetRow(
                    "日间背景", spec.images?.background?.let { java.io.File(it).name } ?: "无（用氛围渐变）",
                    onClick = { bgDayPicker.launch("image/*") },
                    onClear = { spec.images?.let { onUpdate(spec.copy(images = it.copy(background = null))) } },
                )
                AssetRow(
                    "夜间背景", spec.images?.backgroundNight?.let { java.io.File(it).name } ?: "回落日间图",
                    onClick = { bgNightPicker.launch("image/*") },
                    onClear = { spec.images?.let { onUpdate(spec.copy(images = it.copy(backgroundNight = null))) } },
                )
            }
            EditSection("纹理叠层") {
                AssetRow(
                    "纹理图", spec.texture?.path?.let { java.io.File(it).name } ?: "无",
                    onClick = { texturePicker.launch("image/*") },
                    onClear = { onUpdate(spec.copy(texture = null)) },
                )
                spec.texture?.let { tex ->
                    SliderRow("纹理不透明度", tex.opacity, 0f..0.4f) {
                        onUpdate(spec.copy(texture = tex.copy(opacity = it)))
                    }
                }
            }
            Spacer(Modifier.height(d.screenPadding))
        }
    }
}

/** 资产行：名称 + 当前值 + 清除。 */
@Composable
private fun AssetRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(visual.density.screenPadding / 2),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = visual.type.body, color = colors.onSurface)
            Text(
                value,
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            "选择",
            style = visual.type.label,
            color = colors.primary,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() },
        )
        Text(
            "清除",
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClear() },
        )
    }
}

// ---------------------------------------------------------------- 界面包编辑

private val NAV_POSITIONS = NavPosition.entries.map { it.id to it.displayName }
private val NAV_VIS = NavVisibility.entries.map { it.id to it.displayName }
private val SHAPE_FAMILIES = listOf("soft" to "柔和", "round" to "圆润", "sharp" to "利落", "petal" to "花瓣", "cut" to "切角")
private val MOTIONS = MotionLevel.entries.map { it.id to it.displayName }
private val ICON_STYLES = IconStyle.entries.map { it.id to it.displayName }
private val WEIGHTS = TitleWeight.entries.map { it.id to it.displayName }
private val LEVELS = MaterialLevel.entries.map { it.id to it.id }

@Composable
fun LayoutEditorSheet(
    spec: LayoutPackageSpecV2,
    onUpdate: (LayoutPackageSpecV2) -> Unit,
    onDismiss: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val d = visual.density

    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(d.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(d.blockGap),
        ) {
            Text(spec.name, style = visual.type.title, color = colors.onSurface)
            Text("界面包管结构：导航、形状、玻璃、密度、动效、图标、排版。", style = visual.type.caption, color = colors.onSurfaceVariant)

            EditSection("导航形态") {
                Chips(NAV_POSITIONS, spec.navPosition) { k, _ -> onUpdate(spec.copy(navPosition = k)) }
                Chips(NAV_VIS, spec.navVisibility) { k, _ -> onUpdate(spec.copy(navVisibility = k)) }
            }

            EditSection("形状族") {
                Chips(SHAPE_FAMILIES, spec.shapeFamily) { k, _ -> onUpdate(spec.copy(shapeFamily = k)) }
                SliderRow("缩放", spec.shapeScale, SHAPE_SCALE_RANGE) {
                    onUpdate(spec.copy(shapeScale = it))
                }
            }

            EditSection("玻璃") {
                SwitchRow("启用玻璃", spec.glassEnabled) {
                    onUpdate(spec.copy(glassEnabled = it))
                }
                if (spec.glassEnabled) {
                    SliderRow("透明度", spec.glassAlpha, GlassSpec.ALPHA_RANGE) { onUpdate(spec.copy(glassAlpha = it)) }
                    SliderRow("模糊 dp", spec.glassBlur, GlassSpec.BLUR_RANGE) { onUpdate(spec.copy(glassBlur = it)) }
                    SliderRow("噪声", spec.glassNoise, GlassSpec.NOISE_RANGE) { onUpdate(spec.copy(glassNoise = it)) }
                    SliderRow("饱和度", spec.glassVibrancy, GlassSpec.VIBRANCY_RANGE) { onUpdate(spec.copy(glassVibrancy = it)) }
                    SwitchRow("高光描边", spec.glassStroke) { onUpdate(spec.copy(glassStroke = it)) }
                }
            }

            EditSection("密度") {
                SliderRow("系数", spec.densityFactor, DensityLevel.FACTOR_RANGE) { onUpdate(spec.copy(densityFactor = it)) }
            }

            EditSection("动效") {
                Chips(MOTIONS, spec.motionLevel) { k, _ -> onUpdate(spec.copy(motionLevel = k)) }
                SwitchRow("减少动效", spec.motionReduce) { onUpdate(spec.copy(motionReduce = it)) }
            }

            EditSection("图标") {
                Chips(ICON_STYLES, spec.iconStyle) { k, _ -> onUpdate(spec.copy(iconStyle = k)) }
                SliderRow("线宽", spec.iconStroke, IconSpec.STROKE_RANGE) { onUpdate(spec.copy(iconStroke = it)) }
            }

            EditSection("排版") {
                Chips(WEIGHTS, spec.titleWeight) { k, _ -> onUpdate(spec.copy(titleWeight = k)) }
                SwitchRow("中文字距", spec.cjkSpacing) { onUpdate(spec.copy(cjkSpacing = it)) }
            }

            EditSection("栏材质") {
                Text("顶栏", style = visual.type.caption, color = colors.onSurfaceVariant)
                Chips(LEVELS, spec.topBarLevel) { k, _ -> onUpdate(spec.copy(topBarLevel = k)) }
                Text("底栏", style = visual.type.caption, color = colors.onSurfaceVariant)
                Chips(LEVELS, spec.bottomBarLevel) { k, _ -> onUpdate(spec.copy(bottomBarLevel = k)) }
            }

            Spacer(Modifier.height(d.screenPadding))
        }
    }
}

// ---------------------------------------------------------------- 控件

@Composable
private fun EditSection(title: String, content: @Composable () -> Unit) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = visual.type.label, color = colors.primary)
        content()
        AppDivider(inset = false)
    }
}

@Composable
private fun Chips(
    items: List<Pair<String, String>>,
    selected: String,
    onSelect: (key: String, value: String) -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(4).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (key, label) ->
                    val on = key.equals(selected, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(visual.shape(io.legado.app.uikit.v5.token.ShapeKind.TAG))
                            .background(if (on) colors.primary else colors.surfaceVariant)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onSelect(key, label) }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            style = visual.type.caption,
                            color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    AppSlider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        valueLabel = "$label  %.2f".format(value),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = visual.type.body, color = colors.onSurface, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = onChange)
    }
}
