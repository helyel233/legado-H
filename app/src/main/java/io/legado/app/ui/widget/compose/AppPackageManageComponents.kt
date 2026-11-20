package io.legado.app.ui.widget.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfig
import io.legado.app.R
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature

@Composable
fun AppPackageManageScreen(
    isNightMode: Boolean,
    summaryText: String,
    addText: String,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    topActions: List<AppManagementAction> = emptyList(),
    showDayNightTabs: Boolean = true,
    headerContent: LazyListScope.(AppManagementPalette) -> Unit = {},
    listContent: LazyListScope.(AppManagementPalette) -> Unit
) {
    val palette = rememberAppManagementPalette()
    // 页面根背景必须能在壁纸模式下透出壁纸：原来这里铺 palette.settings.page 整页不透明，
    // 结果卡片玻璃采样的是「被本页自己盖住的壁纸」，属于错误管线。背景图可用（且非墨水屏）
    // 时改铺透明，让 BaseActivity.applyRootBackgroundPolicy() 已置空的根背景生效；
    // 无背景图 / 墨水屏时仍返回原页色，观感逐值不变。
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    // 一个页面只算一次玻璃签名：行卡片与日夜胶囊只读，不再各自注册偏好监听
    val glassSignature = rememberGlassConfigSignature()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily),
        // 本页的卡片（AppPackageManageItemCard / AppPackageManageSettingCard）与日夜切换胶囊
        // 都是 AppManagementCard，提供签名后自动接到壁纸域玻璃；壁纸不可见时提供 null，
        // 卡片保持主题实底——与接入玻璃之前逐值一致。
        LocalAppManagementGlassSignature provides glassSignature.takeIf { wallpaperVisible }
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = glassPageBackgroundColor(palette.settings.page, wallpaperVisible),
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                if (!title.isNullOrBlank()) {
                    AppComposeTopBar(
                        title = title,
                        palette = palette,
                        actions = topActions,
                        onBack = onBack,
                        wallpaperVisible = wallpaperVisible,
                        glassSignature = glassSignature
                    )
                }
                if (showDayNightTabs) {
                    AppPackageManageTabs(
                        isNightMode = isNightMode,
                        palette = palette,
                        onSwitch = onSwitchDayNight
                    )
                }
                if (summaryText.isNotBlank()) {
                    Text(
                        text = summaryText,
                        color = palette.settings.secondaryText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        // 页根在背景图模式下已透明，这句说明文字直接压在壁纸上：
                        // 按 BackgroundReadability 的口径给独立提示文本加自适应投影
                        // （无背景图时返回 null，普通主题观感逐值不变）
                        style = LocalTextStyle.current.copy(
                            shadow = imageBgTextShadow(palette.settings.secondaryText)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 10.dp, end = 16.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    headerContent(palette)
                    listContent(palette)
                }
                LegadoMiuixActionButton(
                    text = addText,
                    palette = palette.miuix,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = false,
                    cornerRadius = palette.miuix.actionRadius,
                    minHeight = 46.dp
                )
            }
        }
    }
}

@Composable
fun AppPackageManageSettingCard(
    title: String,
    info: String,
    valueText: String,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppManagementCard(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            AppPackageManageActionButton(
                text = valueText,
                palette = palette.miuix,
                selected = false,
                onClick = onClick
            )
        }
    }
}

@Composable
fun AppPackageManageItemCard(
    title: String,
    info: String,
    isActive: Boolean,
    canEdit: Boolean,
    applyText: String,
    editText: String,
    moreActions: List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null
) {
    AppManagementCard(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingContent?.let {
                it()
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppPackageManageActionButton(
                        text = applyText,
                        palette = palette.miuix,
                        selected = isActive,
                        onClick = onApply
                    )
                    if (canEdit) {
                        AppPackageManageActionButton(
                            text = editText,
                            palette = palette.miuix,
                            onClick = onEdit
                        )
                    }
                    AppPackageManageMoreButton(
                        actionsProvider = { moreActions },
                        palette = palette,
                        text = stringResource(R.string.more)
                    )
                }
            }
        }
    }
}

@Composable
fun AppPackageManageActionButton(
    text: String,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val radius = palette.actionRadius ?: 12.dp
    val background = if (selected) {
        palette.accent.copy(alpha = 0.14f)
    } else {
        palette.surfaceVariant
    }
    val content = if (selected) palette.accent else palette.primaryText
    Surface(
        modifier = modifier
            .widthIn(min = 72.dp)
            .height(34.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(radius),
        color = background,
        contentColor = content,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = content,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppPackageManageMoreButton(
    actionsProvider: () -> List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    text: String,
    modifier: Modifier = Modifier
) {
    AppComposePopupAnchorButton(
        actionsProvider = actionsProvider,
        containerColor = palette.miuix.surfaceVariant,
        contentColor = palette.miuix.primaryText,
        cornerRadius = palette.miuix.actionRadius,
        modifier = modifier
            .widthIn(min = 72.dp)
            .height(34.dp)
    ) {
        Text(
            text = text,
            color = palette.miuix.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun AppPackageManageTabs(
    isNightMode: Boolean,
    palette: AppManagementPalette,
    onSwitch: (Boolean) -> Unit
) {
    LegadoSegmentedControl(
        options = listOf(
            SegmentedOption(value = false, label = stringResource(R.string.theme_day)),
            SegmentedOption(value = true, label = stringResource(R.string.theme_night))
        ),
        selectedValue = isNightMode,
        palette = palette.miuix,
        onSelected = onSwitch,
        tone = SegmentedControlTone.SurfaceThumb,
        fontSize = 14.sp,
        glassSignature = LocalAppManagementGlassSignature.current,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}
