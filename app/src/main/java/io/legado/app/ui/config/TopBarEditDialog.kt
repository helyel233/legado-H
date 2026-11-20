package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.TopBarConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 顶栏皮肤编辑弹窗（R2-M2 方案 B 纯化版）：
 * 仅管理顶栏包名称、顶栏壁纸与局部透明遮罩；
 * 布局形态/搜索/筛选交互已收归「界面布局与系统栏」，颜色与圆角已收归「主题」。
 */
class TopBarEditDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var onNameChanged: ((String) -> Unit)? = null
    private var onShowWallpaperSelector: (() -> Unit)? = null
    private var onShowWallpaperAlphaPicker: ((Int) -> Unit)? = null
    private var onShowTagBarAlphaPicker: ((Int) -> Unit)? = null
    private var onShowTagSelectedAlphaPicker: ((Int) -> Unit)? = null
    private var onSave: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val callbacksReady = onSave != null &&
                    onShowWallpaperSelector != null &&
                    onShowWallpaperAlphaPicker != null &&
                    onShowTagBarAlphaPicker != null &&
                    onShowTagSelectedAlphaPicker != null
                LaunchedEffect(callbacksReady) {
                    if (!callbacksReady) {
                        dismissAllowingStateLoss()
                    }
                }
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(
                        fontFamily = style.bodyFontFamily
                    )
                ) {
                    TopBarEditDialogContent(
                        args = args,
                        style = style,
                        onNameChanged = { name ->
                            onNameChanged?.invoke(name)
                        },
                        onShowWallpaperSelector = {
                            onShowWallpaperSelector?.invoke() ?: dismissAllowingStateLoss()
                        },
                        onShowWallpaperAlphaPicker = {
                            onShowWallpaperAlphaPicker?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onShowTagBarAlphaPicker = {
                            onShowTagBarAlphaPicker?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onShowTagSelectedAlphaPicker = {
                            onShowTagSelectedAlphaPicker?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onSave = { name ->
                            val saveCallback = onSave
                            if (saveCallback != null) {
                                dismissAllowingStateLoss()
                                saveCallback(name)
                            } else {
                                dismissAllowingStateLoss()
                            }
                        },
                        onCancel = {
                            dismissAllowingStateLoss()
                            onCancel?.invoke()
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun create(
            config: TopBarConfig.Config,
            onNameChanged: (String) -> Unit,
            onShowWallpaperSelector: () -> Unit,
            onShowWallpaperAlphaPicker: (Int) -> Unit,
            onShowTagBarAlphaPicker: (Int) -> Unit,
            onShowTagSelectedAlphaPicker: (Int) -> Unit,
            onSave: (String) -> Unit,
            onCancel: () -> Unit
        ): TopBarEditDialog {
            return TopBarEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, config.name)
                    putString(ARG_WALLPAPER_PATH, config.wallpaperPath)
                    putInt(ARG_WALLPAPER_ALPHA, config.wallpaperAlpha)
                    putInt(ARG_TAG_BAR_ALPHA, config.tagBarAlpha)
                    putInt(ARG_TAG_SELECTED_ALPHA, config.tagSelectedAlpha)
                }
                this.onNameChanged = onNameChanged
                this.onShowWallpaperSelector = onShowWallpaperSelector
                this.onShowWallpaperAlphaPicker = onShowWallpaperAlphaPicker
                this.onShowTagBarAlphaPicker = onShowTagBarAlphaPicker
                this.onShowTagSelectedAlphaPicker = onShowTagSelectedAlphaPicker
                this.onSave = onSave
                this.onCancel = onCancel
            }
        }

        const val ARG_NAME = "name"
        const val ARG_WALLPAPER_PATH = "wallpaperPath"
        const val ARG_WALLPAPER_ALPHA = "wallpaperAlpha"
        const val ARG_TAG_BAR_ALPHA = "tagBarAlpha"
        const val ARG_TAG_SELECTED_ALPHA = "tagSelectedAlpha"
    }
}

@Composable
private fun TopBarEditDialogContent(
    args: Bundle,
    style: AppDialogStyle,
    onNameChanged: (String) -> Unit,
    onShowWallpaperSelector: () -> Unit,
    onShowWallpaperAlphaPicker: (Int) -> Unit,
    onShowTagBarAlphaPicker: (Int) -> Unit,
    onShowTagSelectedAlphaPicker: (Int) -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val palette = style.toMiuixPalette()
    var name by rememberSaveable { mutableStateOf(args.getString(TopBarEditDialog.ARG_NAME).orEmpty()) }
    val wallpaperPath = args.getString(TopBarEditDialog.ARG_WALLPAPER_PATH)
    val wallpaperAlpha = args.getInt(TopBarEditDialog.ARG_WALLPAPER_ALPHA, 100)
    val tagBarAlpha = args.getInt(TopBarEditDialog.ARG_TAG_BAR_ALPHA, 100)
    val tagSelectedAlpha = args.getInt(TopBarEditDialog.ARG_TAG_SELECTED_ALPHA, 100)

    AppDialogFrame(
        title = stringResource(R.string.top_bar_edit),
        scrollContent = true,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TopBarEditTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onNameChanged(it)
                    },
                    label = stringResource(R.string.top_bar_name),
                    style = style
                )
                TopBarEditOptionRow(
                    title = stringResource(R.string.top_bar_wallpaper),
                    value = wallpaperLabelResource(wallpaperPath),
                    style = style,
                    onClick = onShowWallpaperSelector
                )
                TopBarEditOptionRow(
                    title = stringResource(R.string.top_bar_wallpaper_alpha),
                    value = "$wallpaperAlpha%",
                    style = style,
                    onClick = { onShowWallpaperAlphaPicker(wallpaperAlpha) }
                )
                TopBarEditOptionRow(
                    title = stringResource(R.string.top_bar_tag_bar_alpha),
                    value = "$tagBarAlpha%",
                    style = style,
                    onClick = { onShowTagBarAlphaPicker(tagBarAlpha) }
                )
                TopBarEditOptionRow(
                    title = stringResource(R.string.top_bar_tag_selected_alpha),
                    value = "$tagSelectedAlpha%",
                    style = style,
                    onClick = { onShowTagSelectedAlphaPicker(tagSelectedAlpha) }
                )
                Text(
                    text = stringResource(R.string.top_bar_edit_scope_hint),
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
                )
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = { onSave(name) },
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun TopBarEditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}

@Composable
private fun TopBarEditOptionRow(
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
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
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
private fun wallpaperLabelResource(path: String?): String {
    return if (path.isNullOrBlank()) {
        stringResource(R.string.theme_image_value_unselected)
    } else {
        stringResource(R.string.theme_image_selected)
    }
}
