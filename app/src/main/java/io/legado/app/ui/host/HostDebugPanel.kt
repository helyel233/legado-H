package io.legado.app.ui.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.legado.app.BuildConfig
import io.legado.app.uikit.theme.BuiltinThemes
import io.legado.app.uikit.v5.resolve.LayoutResolver
import io.legado.app.uikit.v5.resolve.ThemeResolver
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ALL_SHAPE_FAMILIES
import io.legado.app.uikit.v5.token.DensityLevel
import io.legado.app.uikit.v5.token.GlassSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.MotionLevel
import io.legado.app.uikit.v5.token.NavPosition
import io.legado.app.uikit.v5.token.NavVisibility
import io.legado.app.uikit.v5.token.SHAPE_SCALE_RANGE

/**
 * P1-5 调参面板（仅 debug 构建）。
 *
 * 存在的意义：在业务还没搬进来之前，就能把"骨架对不对"验证透 ——
 * 形状族、玻璃、密度、动效、导航形态、主题包全部可以实时拧，
 * **改任何一项都是即时生效、不重启 Activity**。
 *
 * 发布构建里它根本不会被组合进来。
 */
@Composable
fun HostDebugPanel() {
    if (!BuildConfig.DEBUG) return

    var open by remember { mutableStateOf(false) }
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        // 把手
        AppSurface(
            level = MaterialLevel.FLOATING,
            modifier = Modifier
                .padding(end = 0.dp)
                .size(40.dp, 64.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp, 64.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { open = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HostIcons.Settings,
                    contentDescription = "调参面板",
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
        ) {
            val width = (LocalConfiguration.current.screenWidthDp.dp * 0.82f).coerceAtMost(330.dp)
            AppSurface(
                level = MaterialLevel.FLOATING,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(density.screenPadding)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "骨架调参",
                            style = visual.type.headline,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "关闭",
                            style = visual.type.label,
                            color = colors.primary,
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { open = false },
                        )
                    }
                    Text(
                        text = "全部改动即时生效，不重启 Activity",
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                    )

                    Section("主题包（美术）") {
                        Chips(
                            items = BuiltinThemes.all.map { it.id to it.name },
                            selected = ThemeResolver.activeTheme.id,
                        ) { id -> BuiltinThemes.byId(id)?.let { ThemeResolver.applyTheme(it) } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("夜间模式", style = visual.type.body, color = colors.onSurface,
                                modifier = Modifier.weight(1f))
                            Switch(checked = ThemeResolver.isDark, onCheckedChange = { ThemeResolver.applyDark(it) })
                        }
                    }

                    Section("导航形态") {
                        Chips(
                            items = NavPosition.entries.map { it.id to it.displayName },
                            selected = visual.navPosition.id,
                        ) { id -> LayoutResolver.update { it.copy(navPosition = NavPosition.byId(id)) } }
                        Chips(
                            items = NavVisibility.entries.map { it.id to it.displayName },
                            selected = visual.navVisibility.id,
                        ) { id -> LayoutResolver.update { it.copy(navVisibility = NavVisibility.byId(id)) } }
                    }

                    Section("形状族") {
                        Chips(
                            items = ALL_SHAPE_FAMILIES.map { it.id to it.displayName },
                            selected = visual.shapeFamily.id,
                        ) { id -> LayoutResolver.update { it.copy(shapeFamily = io.legado.app.uikit.v5.token.ShapeFamily.byId(id)) } }
                        SliderRow(
                            label = "缩放", value = visual.shapeScale,
                            range = SHAPE_SCALE_RANGE.start..SHAPE_SCALE_RANGE.endInclusive,
                        ) { v -> LayoutResolver.update { it.copy(shapeScale = v) } }
                    }

                    Section("玻璃五件套") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启用玻璃", style = visual.type.body, color = colors.onSurface,
                                modifier = Modifier.weight(1f))
                            Switch(checked = visual.glass.enabled, onCheckedChange = {
                                LayoutResolver.update { s -> s.copy(glass = s.glass.copy(enabled = it)) }
                            })
                        }
                        if (visual.glass.enabled) {
                            SliderRow("透明度", visual.glass.alpha, GlassSpec.ALPHA_RANGE) {
                                LayoutResolver.update { s -> s.copy(glass = s.glass.copy(alpha = it)) }
                            }
                            SliderRow("模糊 dp", visual.glass.blurDp, GlassSpec.BLUR_RANGE) {
                                LayoutResolver.update { s -> s.copy(glass = s.glass.copy(blurDp = it)) }
                            }
                            SliderRow("噪声", visual.glass.noise, GlassSpec.NOISE_RANGE) {
                                LayoutResolver.update { s -> s.copy(glass = s.glass.copy(noise = it)) }
                            }
                            SliderRow("饱和度", visual.glass.vibrancy, GlassSpec.VIBRANCY_RANGE) {
                                LayoutResolver.update { s -> s.copy(glass = s.glass.copy(vibrancy = it)) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("高光描边", style = visual.type.body, color = colors.onSurface,
                                    modifier = Modifier.weight(1f))
                                Switch(checked = visual.glass.stroke, onCheckedChange = {
                                    LayoutResolver.update { s -> s.copy(glass = s.glass.copy(stroke = it)) }
                                })
                            }
                        }
                    }

                    Section("密度") {
                        Chips(
                            items = DensityLevel.entries.map { it.id to it.displayName },
                            selected = DensityLevel.entries.firstOrNull { it.factor == visual.density.factor }?.id
                                ?: DensityLevel.STANDARD.id,
                        ) { id ->
                            LayoutResolver.update {
                                it.copy(density = io.legado.app.uikit.v5.token.DensitySpec(DensityLevel.byId(id).factor))
                            }
                        }
                    }

                    Section("动效") {
                        Chips(
                            items = MotionLevel.entries.map { it.id to it.displayName },
                            selected = visual.motion.level.id,
                        ) { id ->
                            LayoutResolver.update {
                                it.copy(motion = io.legado.app.uikit.v5.token.MotionSpec(MotionLevel.byId(id)))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("减少动效", style = visual.type.body, color = colors.onSurface,
                                modifier = Modifier.weight(1f))
                            Switch(checked = visual.motion.reduce, onCheckedChange = {
                                LayoutResolver.update { s -> s.copy(motion = s.motion.copy(reduce = it)) }
                            })
                        }
                    }

                    Section("栏材质") {
                        Chips(
                            items = MaterialLevel.entries.map { it.id to it.id },
                            selected = visual.bottomBarLevel.id,
                        ) { id -> LayoutResolver.update { it.copy(bottomBarLevel = MaterialLevel.byId(id)) } }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = visual.type.label, color = colors.primary)
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Chips(
    items: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (id, name) ->
            val on = id == selected
            Box(
                modifier = Modifier
                    .background(
                        if (on) colors.primary else colors.surfaceVariant,
                        visual.shape(io.legado.app.uikit.v5.token.ShapeKind.TAG),
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(id) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = name,
                    style = visual.type.caption,
                    color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                )
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
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column {
        Row {
            Text(text = label, style = visual.type.caption, color = colors.onSurface,
                modifier = Modifier.weight(1f))
            Text(
                text = "%.2f".format(value),
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.height(20.dp),
        )
    }
}
