package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.uikit.layout.LayoutEngine
import io.legado.app.uikit.layout.LayoutPackageSpec
import io.legado.app.uikit.theme.Applicator

/**
 * A2-1 包内调整编辑器：任何修改即基于当前包另存为自定义界面包（V4.8 模型）。
 */
@Composable
internal fun PackageEditor(active: LayoutPackageSpec) {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    var draft by remember(active.id, LayoutEngine.revision) { mutableStateOf(active) }

    val activity = context as? LayoutCenterActivity

    fun commit(spec: LayoutPackageSpec) {
        activity?.updateTarget { spec }
    }

    fun edit(): LayoutPackageSpec = active

    LabeledRow(R.string.layout_nav_position)
    SegGroup(
        listOf(
            LayoutPackageSpec.NAV_BOTTOM to R.string.layout_nav_bottom,
            LayoutPackageSpec.NAV_FLOAT to R.string.layout_nav_float,
            LayoutPackageSpec.NAV_SIDE to R.string.layout_nav_side,
            LayoutPackageSpec.NAV_TOP to R.string.layout_nav_top,
        ),
        draft.navPosition,
    ) { commit(edit().copy(navPosition = it)) }

    LabeledRow(R.string.layout_nav_visibility)
    SegGroup(
        listOf(
            LayoutPackageSpec.VIS_ALWAYS to R.string.layout_vis_always,
            LayoutPackageSpec.VIS_SCROLL_HIDE to R.string.layout_vis_scroll_hide,
        ),
        draft.navVisibility,
    ) { commit(edit().copy(navVisibility = it)) }

    LabeledRow(R.string.layout_density)
    SegGroup(
        listOf(
            LayoutPackageSpec.DENSITY_COMPACT to R.string.layout_density_compact,
            LayoutPackageSpec.DENSITY_STANDARD to R.string.layout_density_standard,
            LayoutPackageSpec.DENSITY_COMFORTABLE to R.string.layout_density_comfortable,
        ),
        draft.densityLevel,
    ) { commit(edit().copy(densityLevel = it)) }

    LabeledRow(R.string.layout_glass)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Switch(checked = draft.glassEnabled, onCheckedChange = { commit(edit().copy(glassEnabled = it)) })
    }
    if (draft.glassEnabled) {
        SliderRow(stringResource(R.string.layout_glass_transparency), draft.glassTransparency, 0f..1f) {
            commit(edit().copy(glassTransparency = it))
        }
        SliderRow(stringResource(R.string.layout_glass_blur), draft.glassBlur, 0f..24f) {
            commit(edit().copy(glassBlur = it))
        }
    }

    LabeledRow(R.string.layout_shape)
    SliderRow(stringResource(R.string.layout_shape_scale), draft.shapeScale, 0f..1.5f) {
        commit(edit().copy(shapeScale = it))
    }
    SliderRow(stringResource(R.string.layout_icon_stroke), draft.iconStroke, 0.5f..3f) {
        commit(edit().copy(iconStroke = it))
    }
}

@Composable
private fun LabeledRow(labelRes: Int) {
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.bodyMedium,
        color = Applicator.rememberAppColorScheme().onSurface,
    )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val scheme = Applicator.rememberAppColorScheme()
    Text(
        "$label  " + "%.2f".format(value),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.muted,
    )
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun SegGroup(options: List<Pair<String, Int>>, selected: String, onSelect: (String) -> Unit) {
    val scheme = Applicator.rememberAppColorScheme()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, labelRes) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .border(
                        1.dp,
                        if (on) scheme.primary else scheme.outline,
                        RoundedCornerShape(8.dp),
                    )
                    .background(if (on) scheme.surfaceVariant else scheme.surface)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) scheme.primary else scheme.muted,
                )
            }
        }
    }
}
