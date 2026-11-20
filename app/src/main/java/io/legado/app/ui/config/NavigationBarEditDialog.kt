package io.legado.app.ui.config

import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
internal fun NavigationBarEditDialog(
    entry: NavigationBarIconConfig.Entry,
    name: String,
    wallpaperValue: String,
    sidebarBackgroundValue: String,
    version: Int,
    navItems: List<NavigationBarIconConfig.NavItem>,
    isSaving: Boolean = false,
    onNameChange: (String) -> Unit,
    onClickBottomWallpaper: () -> Unit,
    onClickSidebarBackground: () -> Unit,
    onClickSingleIcon: (NavigationBarIconConfig.NavItem) -> Unit,
    onClickIcon: (NavigationBarIconConfig.NavItem, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
        ) {
            AppDialogFrame(
                title = stringResource(R.string.navigation_bar_edit),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = onNameChange,
                            label = { Text(text = stringResource(R.string.navigation_bar_name)) },
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

                        NavBarEditOptionRow(
                            title = stringResource(R.string.bottom_bar_wallpaper),
                            value = wallpaperValue,
                            style = style,
                            onClick = onClickBottomWallpaper
                        )
                        NavBarEditOptionRow(
                            title = stringResource(R.string.navigation_bar_sidebar_background),
                            value = sidebarBackgroundValue,
                            style = style,
                            onClick = onClickSidebarBackground
                        )

                        navItems.forEach { item ->
                            NavBarEditIconRow(
                                entry = entry,
                                item = item,
                                version = version,
                                style = style,
                                onClickSingleIcon = { onClickSingleIcon(item) },
                                onClickNormal = { onClickIcon(item, false) },
                                onClickSelected = { onClickIcon(item, true) }
                            )
                        }
                    }
                },
                actions = {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.cancel),
                        palette = palette,
                        enabled = !isSaving,
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.ok),
                        palette = palette,
                        enabled = !isSaving,
                        primary = true,
                        onClick = onConfirm
                    )
                }
            )
        }
    }
}

@Composable
private fun NavBarEditOptionRow(
    title: String,
    value: String,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
private fun NavBarEditIconRow(
    entry: NavigationBarIconConfig.Entry,
    item: NavigationBarIconConfig.NavItem,
    version: Int,
    style: AppDialogStyle,
    onClickSingleIcon: () -> Unit,
    onClickNormal: () -> Unit,
    onClickSelected: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(item.titleRes),
                color = style.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.menuId == 0) {
                NavBarIconPreviewButton(
                    contentDescription = stringResource(item.titleRes),
                    version = version,
                    style = style,
                    drawableProvider = { context ->
                        NavigationBarIconConfig.previewSingleDrawable(context, entry, item)
                    },
                    onClick = onClickSingleIcon
                )
            } else {
                NavBarIconPreviewButton(
                    contentDescription = stringResource(R.string.navigation_icon_normal),
                    version = version,
                    style = style,
                    drawableProvider = { context ->
                        NavigationBarIconConfig.previewDrawable(context, entry, item, false)
                    },
                    onClick = onClickNormal
                )
                Spacer(modifier = Modifier.width(8.dp))
                NavBarIconPreviewButton(
                    contentDescription = stringResource(R.string.navigation_icon_selected),
                    version = version,
                    style = style,
                    drawableProvider = { context ->
                        NavigationBarIconConfig.previewDrawable(context, entry, item, true)
                    },
                    onClick = onClickSelected
                )
            }
        }
    }
}

@Composable
private fun NavBarIconPreviewButton(
    contentDescription: String,
    version: Int,
    style: AppDialogStyle,
    drawableProvider: (android.content.Context) -> android.graphics.drawable.Drawable?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        AndroidView(
            factory = { viewContext ->
                ImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(8, 8, 8, 8)
                }
            },
            update = { imageView ->
                imageView.tag = version
                imageView.contentDescription = contentDescription
                imageView.setImageDrawable(drawableProvider(imageView.context))
            },
            modifier = Modifier.padding(4.dp)
        )
    }
}
