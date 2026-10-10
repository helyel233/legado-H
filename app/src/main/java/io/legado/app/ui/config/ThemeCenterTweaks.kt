package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.legado.app.help.config.ThemePackageStore
import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.paletteRoleFromKey
import io.legado.app.uikit.theme.Applicator
import io.legado.app.uikit.token.AppSpacing

private val TWEAK_PRESETS = listOf(
    0xFFFF0000.toInt(), 0xFFFF8800.toInt(), 0xFFFFD600.toInt(), 0xFF00C853.toInt(),
    0xFF00B0FF.toInt(), 0xFF5B6ABF.toInt(), 0xFFAA00FF.toInt(), 0xFF808080.toInt(),
)

/**
 * A1-5 手调区：已修改项列表（单项还原 / 全部还原）+ 角色下拉取值编辑器。
 */
@Composable
internal fun TweaksSection(tweaks: Map<String, String>) {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()

    if (tweaks.isEmpty()) {
        Text(
            stringResource(R.string.theme_center_tweaks_empty),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.muted,
        )
    } else {
        tweaks.forEach { (key, value) ->
            val role = paletteRoleFromKey(key) ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeSwatch(ThemePackageSpec.parseColor(value) ?: 0)
                Spacer(Modifier.width(AppSpacing.s8))
                Text(role.name, modifier = Modifier.weight(1f), color = scheme.onSurface)
                TextButton(onClick = {
                    Applicator.clearTweak(role)
                    ThemePackageStore.persistCurrent(context)
                }) { Text(stringResource(R.string.theme_center_restore)) }
            }
        }
        TextButton(onClick = {
            Applicator.clearAllTweaks()
            ThemePackageStore.persistCurrent(context)
        }) { Text(stringResource(R.string.theme_center_clear_all)) }
    }

    TweakEditor()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TweakEditor() {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    var expanded by remember { mutableStateOf(false) }
    var selectedRole by remember { mutableStateOf(PaletteRole.PRIMARY) }

    Text(
        stringResource(R.string.theme_center_add_tweak),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurface,
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedRole.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.theme_center_role)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PaletteRole.entries.forEach { role ->
                DropdownMenuItem(
                    text = { Text(role.name) },
                    onClick = {
                        selectedRole = role
                        expanded = false
                    },
                )
            }
        }
    }
    Text(
        stringResource(R.string.theme_center_pick_value),
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
                        Applicator.setTweak(selectedRole, argb)
                        ThemePackageStore.persistCurrent(context)
                    },
            )
        }
    }
}
