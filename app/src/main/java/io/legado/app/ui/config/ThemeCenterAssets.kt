package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.help.config.ThemePackageStore
import io.legado.app.theme.model.FontRole
import io.legado.app.theme.pack.ThemeImages
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.uikit.theme.Applicator

/** Image slot keys → label. Order = display order. */
internal val IMAGE_SLOTS = listOf(
    "background" to R.string.theme_img_main,
    "backgroundNight" to R.string.theme_img_main_night,
    "bookInfo" to R.string.theme_img_bookinfo,
    "bookInfoNight" to R.string.theme_img_bookinfo_night,
    "panel" to R.string.theme_img_panel,
    "panelNight" to R.string.theme_img_panel_night,
)

internal fun ThemeImages.withSlot(key: String, path: String?): ThemeImages = when (key) {
    "background" -> copy(background = path)
    "backgroundNight" -> copy(backgroundNight = path)
    "bookInfo" -> copy(bookInfo = path)
    "bookInfoNight" -> copy(bookInfoNight = path)
    "panel" -> copy(panel = path)
    "panelNight" -> copy(panelNight = path)
    else -> this
}

internal fun ThemeImages.slotValue(key: String): String? = when (key) {
    "background" -> background
    "backgroundNight" -> backgroundNight
    "bookInfo" -> bookInfo
    "bookInfoNight" -> bookInfoNight
    "panel" -> panel
    "panelNight" -> panelNight
    else -> null
}

@Composable
internal fun FontSection() {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    FontRow(
        label = stringResource(R.string.theme_font_ui),
        current = Applicator.uiFontPath,
        onPick = { (context as? ThemeCenterActivity)?.launchFontPicker(FontRole.UI) },
        onClear = {
            Applicator.applyTheme(Applicator.activeTheme.let { s ->
                s.copy(fonts = s.fonts?.copy(ui = null) ?: s.fonts)
            })
            ThemePackageStore.persistCurrent(context)
        },
    )
    FontRow(
        label = stringResource(R.string.theme_font_title),
        current = Applicator.titleFontPath,
        onPick = { (context as? ThemeCenterActivity)?.launchFontPicker(FontRole.TITLE) },
        onClear = {
            Applicator.applyTheme(Applicator.activeTheme.let { s ->
                s.copy(fonts = s.fonts?.copy(title = null) ?: s.fonts)
            })
            ThemePackageStore.persistCurrent(context)
        },
    )
    Text(
        stringResource(R.string.theme_font_hint),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.muted,
    )
}

@Composable
private fun FontRow(label: String, current: String, onPick: () -> Unit, onClear: () -> Unit) {
    val scheme = Applicator.rememberAppColorScheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            Text(
                if (current.isBlank()) stringResource(R.string.theme_asset_unset)
                else current.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.muted,
            )
        }
        TextButton(onClick = onPick) { Text(stringResource(R.string.theme_asset_pick)) }
        if (current.isNotBlank()) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.theme_center_restore)) }
        }
    }
}

@Composable
internal fun ImageSection() {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    IMAGE_SLOTS.forEach { (slot, labelRes) ->
        val current = Applicator.activeTheme.images?.slotValue(slot)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                Text(
                    if (current.isNullOrBlank()) stringResource(R.string.theme_asset_unset)
                    else current.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.muted,
                )
            }
            TextButton(onClick = { (context as? ThemeCenterActivity)?.launchImagePicker(slot) }) {
                Text(stringResource(R.string.theme_asset_pick))
            }
            if (!current.isNullOrBlank()) {
                TextButton(onClick = {
                    Applicator.applyTheme(
                        Applicator.activeTheme.copy(images = Applicator.activeTheme.images?.withSlot(slot, null))
                    )
                    ThemePackageStore.persistCurrent(context)
                }) { Text(stringResource(R.string.theme_center_restore)) }
            }
        }
    }
    Text(
        stringResource(R.string.theme_img_hint),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.muted,
    )
}
