package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.paletteRoleFromKey
import io.legado.app.uikit.theme.Applicator

private val TWEAK_PRESETS = listOf(
    0xFFFF0000.toInt(), 0xFFFF8800.toInt(), 0xFFFFD600.toInt(), 0xFF00C853.toInt(),
    0xFF00B0FF.toInt(), 0xFF5B6ABF.toInt(), 0xFFAA00FF.toInt(), 0xFF808080.toInt(),
)

/**
 * A2-4b 包内颜色角色手调：直接写入该包的 override（另存模型）。
 */
@Composable
internal fun TweaksSection(overrides: Map<String, String>) {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    val activity = context as? ThemeCenterActivity

    if (overrides.isEmpty()) {
        Text(
            stringResource(R.string.theme_center_tweaks_empty),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.muted,
        )
    } else {
        overrides.forEach { (key, value) ->
            val role = paletteRoleFromKey(key) ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeSwatch(ThemePackageSpec.parseColor(value) ?: 0)
                Row(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(role.name, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
                TextButton(onClick = {
                    activity?.updateTarget { spec -> spec.copy(override = spec.override?.minus(key)) }
                }) { Text(stringResource(R.string.theme_center_restore)) }
            }
        }
    }

    Text(
        stringResource(R.string.theme_center_add_tweak),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaletteRole.entries.take(8).forEach { role ->
            val on = overrides.containsKey("color.${role.name.lowercase()}")
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .border(
                        1.dp,
                        if (on) scheme.primary else scheme.outline,
                        CircleShape,
                    )
                    .clickable { },
            ) {
                Text(
                    role.name.take(2),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface,
                )
            }
        }
    }
    Text(
        stringResource(R.string.theme_center_role_hint),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.muted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TWEAK_PRESETS.forEach { argb ->
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(Color(argb), CircleShape)
                    .border(1.dp, scheme.outline, CircleShape)
                    .clickable {
                        val role = PaletteRole.PRIMARY
                        activity?.updateTarget { spec ->
                            spec.copy(override = (spec.override ?: emptyMap()) +
                                ("color.${role.name.lowercase()}" to String.format("#%08X", argb)))
                        }
                    },
            )
        }
    }
}
