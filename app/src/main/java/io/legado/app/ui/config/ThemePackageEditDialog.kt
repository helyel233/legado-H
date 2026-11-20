package io.legado.app.ui.config

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSliderRow
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoSegmentedControl
import io.legado.app.ui.widget.compose.SegmentedControlTone
import io.legado.app.ui.widget.compose.SegmentedOption
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

enum class ThemeEditImageTarget {
    MAIN,
    BOOK_INFO,
    PANEL
}

data class ThemeEditDialogCallbacks(
    val onDraftChange: (ThemeEditDraft) -> Unit,
    val onPickRequiredColor: (targetId: Int, currentHex: String) -> Unit,
    val onPickOptionalColor: (targetId: Int, titleRes: Int, currentHex: String?) -> Unit,
    val onClearOptionalColor: (targetId: Int) -> Unit,
    val onPickImage: (ThemeEditImageTarget) -> Unit,
    val onPickPanelScaleMode: () -> Unit,
    val onPickBorderColor: () -> Unit,
    val onPickCardShadow: () -> Unit,
    val onPickCardBlur: () -> Unit,
    val onPickFontScale: () -> Unit,
    val onPickFont: (ThemeEditFontTarget) -> Unit,
    val fontDisplayName: (String?) -> String,
    val onDelete: (() -> Unit)?,
    val onDismiss: () -> Unit,
    val onConfirm: () -> Unit
)

@Composable
internal fun ThemePackageEditDialog(
    draft: ThemeEditDraft,
    callbacks: ThemeEditDialogCallbacks,
    colorIds: ThemeEditColorIds,
    isSaving: Boolean = false
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val titleText = stringResource(
        if (draft.editingEntryDirName == null) R.string.theme_manual_add else R.string.theme_edit
    )
    Dialog(
        onDismissRequest = { if (!isSaving) callbacks.onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
        ) {
            AppDialogFrame(
                title = titleText,
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = draft.themeName,
                            onValueChange = { callbacks.onDraftChange(draft.copy(themeName = it)) },
                            enabled = draft.nameEditable,
                            label = { Text(text = stringResource(R.string.theme_name)) },
                            singleLine = true,
                            shape = RoundedCornerShape(style.actionRadius),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = style.accent,
                                unfocusedBorderColor = style.stroke,
                                focusedTextColor = style.primaryText,
                                unfocusedTextColor = style.primaryText,
                                focusedLabelColor = style.accent,
                                unfocusedLabelColor = style.secondaryText,
                                focusedContainerColor = style.fieldSurface,
                                unfocusedContainerColor = style.fieldSurface,
                                cursorColor = style.accent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        LegadoSegmentedControl(
                            options = listOf(
                                SegmentedOption(0, stringResource(R.string.theme_group_color)),
                                SegmentedOption(1, stringResource(R.string.theme_group_image)),
                                SegmentedOption(2, stringResource(R.string.theme_group_interface)),
                                SegmentedOption(3, stringResource(R.string.theme_group_font))
                            ),
                            selectedValue = draft.activeGroupIndex,
                            palette = palette,
                            onSelected = { idx ->
                                callbacks.onDraftChange(draft.copy(activeGroupIndex = idx))
                            },
                            tone = SegmentedControlTone.SurfaceThumb,
                            fontSize = 12.sp,
                            itemHeight = 32.dp
                        )

                        when (draft.activeGroupIndex) {
                            0 -> ThemeEditColorGroup(draft, style, callbacks, colorIds)
                            1 -> ThemeEditImageGroup(draft, style, callbacks)
                            2 -> ThemeEditInterfaceGroup(draft, style, callbacks)
                            else -> ThemeEditFontGroup(draft, style, callbacks, colorIds)
                        }
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (draft.canDelete && callbacks.onDelete != null) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.theme_delete_local),
                                palette = palette,
                                enabled = !isSaving,
                                danger = true,
                                onClick = callbacks.onDelete
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            enabled = !isSaving,
                            onClick = callbacks.onDismiss
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.ok),
                            palette = palette,
                            enabled = !isSaving,
                            primary = true,
                            onClick = callbacks.onConfirm
                        )
                    }
                }
            )
        }
    }
}

data class ThemeEditColorIds(
    val primary: Int,
    val accent: Int,
    val background: Int,
    val bottomBackground: Int,
    val card: Int,
    val muted: Int,
    val searchFieldBackground: Int,
    val tabBackground: Int,
    val shelf: Int,
    val uiFont: Int,
    val titleFont: Int
)

@Composable
private fun ThemeEditColorGroup(
    draft: ThemeEditDraft,
    style: AppDialogStyle,
    callbacks: ThemeEditDialogCallbacks,
    colorIds: ThemeEditColorIds
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_color_primary),
            value = draft.primaryColorHex,
            swatchHex = draft.primaryColorHex,
            style = style,
            onClick = { callbacks.onPickRequiredColor(colorIds.primary, draft.primaryColorHex) }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_color_accent),
            value = draft.accentColorHex,
            swatchHex = draft.accentColorHex,
            style = style,
            onClick = { callbacks.onPickRequiredColor(colorIds.accent, draft.accentColorHex) }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_color_background),
            value = draft.backgroundColorHex,
            swatchHex = draft.backgroundColorHex,
            style = style,
            onClick = { callbacks.onPickRequiredColor(colorIds.background, draft.backgroundColorHex) }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_color_bottom_background),
            value = draft.bottomBackgroundHex,
            swatchHex = draft.bottomBackgroundHex,
            style = style,
            onClick = { callbacks.onPickRequiredColor(colorIds.bottomBackground, draft.bottomBackgroundHex) }
        )

        val overriddenCount = draft.overriddenAdvancedColorCount()
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_advanced_colors_toggle),
            value = if (overriddenCount > 0) {
                stringResource(R.string.theme_advanced_colors_summary_custom, overriddenCount)
            } else {
                stringResource(R.string.theme_advanced_colors_summary_auto)
            },
            swatchHex = null,
            style = style,
            onClick = {
                callbacks.onDraftChange(
                    draft.copy(advancedColorsExpanded = !draft.advancedColorsExpanded)
                )
            }
        )

        if (draft.advancedColorsExpanded) {
            ThemeEditOptionalColorRow(
                titleRes = R.string.theme_color_card,
                valueHex = draft.cardColor,
                targetId = colorIds.card,
                style = style,
                callbacks = callbacks
            )
            ThemeEditOptionalColorRow(
                titleRes = R.string.theme_color_muted,
                valueHex = draft.mutedColor,
                targetId = colorIds.muted,
                style = style,
                callbacks = callbacks
            )
            ThemeEditOptionalColorRow(
                titleRes = R.string.theme_color_search_field_background,
                valueHex = draft.searchFieldBackgroundColor,
                targetId = colorIds.searchFieldBackground,
                style = style,
                callbacks = callbacks
            )
            ThemeEditOptionalColorRow(
                titleRes = R.string.theme_color_tab_background,
                valueHex = draft.tabBackgroundColor,
                targetId = colorIds.tabBackground,
                style = style,
                callbacks = callbacks
            )
            ThemeEditOptionalColorRow(
                titleRes = R.string.theme_color_shelf,
                valueHex = draft.shelfColor,
                targetId = colorIds.shelf,
                style = style,
                callbacks = callbacks
            )
        }
    }
}

@Composable
private fun ThemeEditImageGroup(
    draft: ThemeEditDraft,
    style: AppDialogStyle,
    callbacks: ThemeEditDialogCallbacks
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_image_main_background),
            value = formatImageValue(
                path = draft.mainBackgroundPath,
                blur = draft.blur,
                includeBlur = true
            ),
            swatchHex = null,
            style = style,
            onClick = { callbacks.onPickImage(ThemeEditImageTarget.MAIN) }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_image_book_info_background),
            value = formatImageValue(
                path = draft.bookInfoBackgroundPath,
                blur = 0,
                includeBlur = false
            ),
            swatchHex = null,
            style = style,
            onClick = { callbacks.onPickImage(ThemeEditImageTarget.BOOK_INFO) }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_image_panel_background),
            value = formatImageValue(
                path = draft.panelBackgroundPath,
                blur = 0,
                includeBlur = false
            ),
            swatchHex = null,
            style = style,
            onClick = { callbacks.onPickImage(ThemeEditImageTarget.PANEL) }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_image_panel_background_mode),
            value = stringResource(
                if (draft.panelBackgroundScaleType == ThemeConfig.PANEL_BG_FIT) {
                    R.string.theme_image_mode_fit
                } else {
                    R.string.theme_image_mode_crop
                }
            ),
            swatchHex = null,
            style = style,
            onClick = callbacks.onPickPanelScaleMode
        )
    }
}

@Composable
private fun ThemeEditInterfaceGroup(
    draft: ThemeEditDraft,
    style: AppDialogStyle,
    callbacks: ThemeEditDialogCallbacks
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppDialogSliderRow(
            title = stringResource(R.string.ui_corner_scale),
            value = (draft.uiCornerScale * 10f).roundToInt().coerceIn(0, 30),
            range = 0..30,
            showStepper = true,
            valueFormatter = { steps ->
                val scale = steps / 10f
                if (scale % 1f == 0f) {
                    scale.toInt().toString()
                } else {
                    String.format(Locale.US, "%.1f", scale)
                }
            },
            onValueChange = { steps ->
                callbacks.onDraftChange(draft.copy(uiCornerScale = (steps / 10f).coerceIn(0f, 3f)))
            }
        )
        AppDialogSliderRow(
            title = stringResource(R.string.ui_layout_alpha),
            value = draft.uiLayoutAlpha.coerceIn(0, 100),
            range = 0..100,
            showStepper = true,
            valueFormatter = { context.getString(R.string.ui_layout_alpha_value, it) },
            onValueChange = { alpha ->
                callbacks.onDraftChange(draft.copy(uiLayoutAlpha = alpha.coerceIn(0, 100)))
            }
        )
        AppDialogSliderRow(
            title = stringResource(R.string.dialog_alpha),
            value = draft.dialogAlpha.coerceIn(0, 100),
            range = 0..100,
            showStepper = true,
            valueFormatter = { context.getString(R.string.ui_layout_alpha_value, it) },
            onValueChange = { alpha ->
                callbacks.onDraftChange(draft.copy(dialogAlpha = alpha.coerceIn(0, 100)))
            }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_panel_border_color),
            value = draft.panelBorderColor ?: stringResource(R.string.theme_border_follow_accent),
            swatchHex = draft.panelBorderColor,
            style = style,
            onClick = callbacks.onPickBorderColor
        )
        AppDialogSliderRow(
            title = stringResource(R.string.theme_panel_border_alpha),
            value = draft.panelBorderAlpha.coerceIn(0, 100),
            range = 0..100,
            showStepper = true,
            valueFormatter = { context.getString(R.string.ui_layout_alpha_value, it) },
            onValueChange = { alpha ->
                callbacks.onDraftChange(draft.copy(panelBorderAlpha = alpha.coerceIn(0, 100)))
            }
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_card_shadow),
            value = draft.cardShadow?.toString() ?: stringResource(R.string.theme_value_follow_default),
            swatchHex = null,
            style = style,
            onClick = callbacks.onPickCardShadow
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.theme_card_background_blur),
            value = draft.cardBackgroundBlur?.let { "%.1f".format(Locale.US, it) }
                ?: stringResource(R.string.theme_value_follow_default),
            swatchHex = null,
            style = style,
            onClick = callbacks.onPickCardBlur
        )
        AppDialogSwitchRow(
            text = stringResource(R.string.ui_corner_search_follow),
            checked = draft.uiCornerSearchFollow,
            onCheckedChange = { checked ->
                callbacks.onDraftChange(draft.copy(uiCornerSearchFollow = checked))
            }
        )
        AppDialogSwitchRow(
            text = stringResource(R.string.ui_corner_reply_follow),
            checked = draft.uiCornerReplyFollow,
            onCheckedChange = { checked ->
                callbacks.onDraftChange(draft.copy(uiCornerReplyFollow = checked))
            }
        )
    }
}

@Composable
private fun ThemeEditFontGroup(
    draft: ThemeEditDraft,
    style: AppDialogStyle,
    callbacks: ThemeEditDialogCallbacks,
    colorIds: ThemeEditColorIds
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeEditOptionRow(
            title = stringResource(R.string.font_scale),
            value = if (draft.fontScale == 0) {
                stringResource(R.string.btn_default_s)
            } else {
                "%.1f".format(Locale.US, draft.fontScale / 10f)
            },
            swatchHex = null,
            style = style,
            onClick = callbacks.onPickFontScale
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.ui_font),
            value = callbacks.fontDisplayName(draft.uiFontPath),
            swatchHex = null,
            style = style,
            onClick = { callbacks.onPickFont(ThemeEditFontTarget.UI) }
        )
        ThemeEditOptionalColorRow(
            titleRes = R.string.theme_ui_font_color,
            valueHex = draft.uiFontColor,
            targetId = colorIds.uiFont,
            style = style,
            callbacks = callbacks
        )
        ThemeEditOptionRow(
            title = stringResource(R.string.title_font),
            value = callbacks.fontDisplayName(draft.titleFontPath),
            swatchHex = null,
            style = style,
            onClick = { callbacks.onPickFont(ThemeEditFontTarget.TITLE) }
        )
        ThemeEditOptionalColorRow(
            titleRes = R.string.theme_title_font_color,
            valueHex = draft.titleFontColor,
            targetId = colorIds.titleFont,
            style = style,
            callbacks = callbacks
        )
    }
}

@Composable
private fun ThemeEditOptionalColorRow(
    titleRes: Int,
    valueHex: String?,
    targetId: Int,
    style: AppDialogStyle,
    callbacks: ThemeEditDialogCallbacks
) {
    ThemeEditOptionRow(
        title = stringResource(titleRes),
        value = valueHex ?: stringResource(R.string.theme_value_follow_default),
        swatchHex = valueHex,
        style = style,
        onClick = { callbacks.onPickOptionalColor(targetId, titleRes, valueHex) },
        onLongClick = { callbacks.onClearOptionalColor(targetId) }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeEditOptionRow(
    title: String,
    value: String,
    swatchHex: String?,
    style: AppDialogStyle,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val swatchColor = swatchHex?.let { hex ->
        runCatching { Color(hex.toColorInt()) }.getOrNull()
    }
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (swatchColor != null) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(1.dp, style.stroke, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = value,
                color = style.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun formatImageValue(
    path: String?,
    blur: Int,
    includeBlur: Boolean
): String {
    val base = if (path.isNullOrBlank()) {
        stringResource(R.string.select_image)
    } else {
        File(path).name.ifBlank { stringResource(R.string.theme_image_selected) }
    }
    return if (includeBlur && blur > 0) {
        "$base · ${stringResource(R.string.theme_image_blur)} $blur"
    } else {
        base
    }
}
