package io.legado.app.ui.widget.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.TopBarChromeSpec
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.rememberResolvedThemeTokens
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.main.GlassMaterialPreset

/**
 * 列表页玻璃配置签名。非空表示本页背后真的露出壁纸（有背景图、非墨水屏），
 * 卡片可以铺壁纸域玻璃；null 表示保持主题实底。
 *
 * 由 [AppManagementScaffold] 按页提供一次，行卡片只读不算，避免每行各注册一个
 * 偏好监听；弹窗与不透明页面没有提供者，卡片自然回退实底。
 */
val LocalAppManagementGlassSignature = staticCompositionLocalOf<String?> { null }

data class AppManagementAction(
    val text: String,
    @param:DrawableRes val iconRes: Int? = null,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val showText: Boolean = false,
    val onClick: () -> Unit = {},
    val menuActions: (() -> List<AppManagementMenuAction>)? = null
)

@Composable
fun AppManagementScaffold(
    title: String,
    selectedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
    palette: AppManagementPalette = rememberAppManagementPalette(),
    searchQuery: String? = null,
    searchHint: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    topActions: List<AppManagementAction> = emptyList(),
    bottomActions: List<AppManagementAction> = emptyList(),
    onBack: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    onInvertSelection: (() -> Unit)? = null,
    content: @Composable (AppManagementPalette) -> Unit
) {
    LegadoComposeTheme {
    // 壁纸模式下宿主根透明（BaseActivity 已置空根背景），顶/底栏背后是壁纸，属壁纸域。
    // 无背景图 / 墨水屏时保持原来的实底栏，玻璃不接管。
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()
    CompositionLocalProvider(
        LocalAppManagementGlassSignature provides glassSignature.takeIf { wallpaperVisible }
    ) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        AppComposeTopBar(
            title = title,
            palette = palette,
            searchQuery = searchQuery,
            searchHint = searchHint,
            onSearchChange = onSearchChange,
            actions = topActions,
            onBack = onBack,
            wallpaperVisible = wallpaperVisible,
            glassSignature = glassSignature
        )
        Box(modifier = Modifier.weight(1f)) {
            content(palette)
        }
        AppManagementSelectionBottomBar(
            selectedCount = selectedCount,
            totalCount = totalCount,
            palette = palette,
            actions = bottomActions,
            onSelectAll = onSelectAll,
            onInvertSelection = onInvertSelection,
            wallpaperVisible = wallpaperVisible,
            glassSignature = glassSignature
        )
    }
    }
    }
}

@Composable
fun AppComposeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    palette: AppManagementPalette = rememberAppManagementPalette(),
    searchQuery: String? = null,
    searchHint: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    actions: List<AppManagementAction> = emptyList(),
    trailingReservedWidth: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    wallpaperVisible: Boolean = rememberHasUsableBgImage() && !AppConfig.isEInkMode,
    glassSignature: String = rememberGlassConfigSignature(),
    onChromeResolved: ((TopBarChromeSpec.Chrome) -> Unit)? = null
) {
    val context = LocalContext.current
    val tokens = LocalLegadoTokens.current ?: rememberResolvedThemeTokens()
    val topScope = tokens.topBar
    val borderColor = Color(UiCorner.borderLineColor(context))
    // 栏位边框统一取主题包「边框线色」，支持 TOP_BAR Scope 覆写透明度
    val barBorder = BorderStroke(
        1.dp,
        if (!tokens.env.isEInk && topScope.isCustomOverride) {
            borderColor.copy(alpha = borderColor.alpha * topScope.borderAlphaFraction)
        } else {
            borderColor
        }
    )
    val baseTopBarColor = if (AppConfig.immersiveManageBar) {
        context.backgroundColor
    } else {
        context.primaryColor
    }
    val topBarColor = Color(baseTopBarColor).let { c ->
        if (!tokens.env.isEInk && topScope.isCustomOverride) {
            c.copy(alpha = c.alpha * topScope.alphaFraction)
        } else {
            c
        }
    }
    val topBarContentColor = Color(context.titleTextColor)
    // 悬浮 chrome 几何：与 A/B 两系共用 TopBarChromeSpec 单一真相源（方案 I′：只共享数，
    // 不共享渲染——GlassPanel 本就是 Compose 侧唯一玻璃渲染入口，radius 是既有参数）。
    val isNight = AppConfig.isNightTheme
    val (chrome, chromeColorInt) = remember(isNight, TopBarConfig.currentSignature(isNight), topScope) {
        val config = TopBarConfig.currentConfig(context, isNight)
        val basePanelPx = if (topScope.isCustomOverride) {
            topScope.panelRadiusPx
        } else {
            context.resources.getDimension(R.dimen.ui_panel_radius)
        }
        TopBarChromeSpec.resolve(
            basePanelPx,
            config,
            0f
        ) to TopBarConfig.resolveBackgroundColor(config)
    }
    val chromeColor = Color(chromeColorInt).let { c ->
        if (!tokens.env.isEInk && topScope.isCustomOverride) {
            c.copy(alpha = c.alpha * topScope.alphaFraction)
        } else {
            c
        }
    }
    val effectiveWallpaperVisible = wallpaperVisible &&
        !tokens.env.isEInk &&
        topScope.materialMode != GlassMaterialPreset.SOLID
    if (onChromeResolved != null) {
        SideEffect {
            onChromeResolved(chrome)
        }
    }
    val floating = chrome.floating
    val density = LocalDensity.current
    val radiusDp = with(density) { chrome.radiusPx.toDp() }
    val chromeShape = RoundedCornerShape(radiusDp)
    val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hasSubtitle = !subtitle.isNullOrBlank()
    val barHeight = when {
        hasSubtitle -> 58.dp
        trailingReservedWidth > 0.dp -> 56.dp
        else -> 48.dp
    }
    // 悬浮卡片的三向留白：左右/底部 0.6×半径、顶部 0.5×半径 + 状态栏让位（顶栏包语法定义）。
    // 状态栏让位从内容的 windowInsetsPadding 迁到表面外边距——卡片从状态栏下方起卡。
    val surfaceInsets = if (floating) {
        Modifier.padding(
            start = with(density) { chrome.hInsetPx.toDp() },
            end = with(density) { chrome.hInsetPx.toDp() },
            top = statusTopDp + with(density) { chrome.topInsetPx.toDp() },
            bottom = with(density) { chrome.bottomInsetPx.toDp() }
        )
    } else {
        Modifier
    }
    val topBarContent: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (floating) Modifier
                        else Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .padding(start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    onBack?.let {
                        AppManagementIconAction(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = null,
                            tint = topBarContentColor,
                            onClick = it
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = title,
                            color = topBarContentColor,
                            fontSize = if (hasSubtitle) 18.sp else 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = palette.settings.titleFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (hasSubtitle) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = subtitle.orEmpty(),
                                color = topBarContentColor.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    actions.forEach { action ->
                        AppManagementTopAction(
                            action = action,
                            palette = palette,
                            contentColor = topBarContentColor
                        )
                    }
                    if (trailingReservedWidth > 0.dp) {
                        Spacer(modifier = Modifier.width(trailingReservedWidth))
                    }
                }
                if (onSearchChange != null) {
                    AppManagementSearchField(
                        query = searchQuery.orEmpty(),
                        hint = searchHint.orEmpty(),
                        palette = palette,
                        onQueryChange = onSearchChange,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }
    if (effectiveWallpaperVisible) {
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            // 非悬浮：贴边全宽、四角直角（玻璃层单一半径会在屏幕顶角留缺口），取 0；
            // 悬浮：整卡内缩，圆角不再留缺口，取顶栏包半径（半高钳制在 spec 层，0f=不钳，卡高含搜索框足够）。
            cornerRadiusPx = if (floating) chrome.radiusPx else 0f,
            // chrome 与 topScope 进签名：半径 / 悬浮态 / 作用域覆写变化必须重绑玻璃。
            configSignature = "$glassSignature|chrome=$floating|${chrome.radiusPx}|top=${topScope.materialMode}:${topScope.alphaPercent}:${topScope.panelRadiusDp}",
            modifier = modifier
                .fillMaxWidth()
                .then(surfaceInsets)
                .then(if (floating) Modifier.clip(chromeShape) else Modifier)
                .then(
                    if (floating) Modifier.border(barBorder, chromeShape)
                    else Modifier.border(barBorder)
                ),
            fallback = if (floating) {
                Modifier.clip(chromeShape).background(chromeColor)
            } else {
                Modifier.background(topBarColor)
            }
        ) {
            topBarContent()
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .then(surfaceInsets)
                .then(
                    if (floating) {
                        Modifier.clip(chromeShape)
                            .background(chromeColor)
                            .border(barBorder, chromeShape)
                    } else {
                        Modifier.background(topBarColor).border(barBorder)
                    }
                )
        ) {
            topBarContent()
        }
    }
}

@Composable
private fun AppManagementTopAction(
    action: AppManagementAction,
    palette: AppManagementPalette,
    contentColor: Color
) {
    val resolvedTint = if (action.danger) palette.settings.danger else contentColor
    val menuActions = action.menuActions
    when {
        action.showText && action.text.isNotBlank() && menuActions != null -> {
            AppComposePopupAnchorButton(
                actionsProvider = menuActions,
                containerColor = resolvedTint.copy(alpha = 0.12f),
                contentColor = resolvedTint,
                cornerRadius = palette.miuix.actionRadius,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(32.dp)
            ) {
                Text(
                    text = action.text,
                    color = resolvedTint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
        }
        action.showText && action.text.isNotBlank() -> {
            Text(
                text = action.text,
                color = resolvedTint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp))
                    .clickable(onClick = action.onClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        menuActions != null -> {
            AppManagementMoreActionButton(
                actionsProvider = menuActions,
                palette = palette,
                iconRes = action.iconRes ?: R.drawable.ic_more_vert,
                contentDescription = action.text,
                tint = resolvedTint
            )
        }
        else -> {
            AppManagementIconAction(
                iconRes = action.iconRes ?: R.drawable.ic_more_vert,
                contentDescription = action.text,
                tint = resolvedTint,
                onClick = action.onClick
            )
        }
    }
}

@Composable
private fun AppManagementSearchField(
    query: String,
    hint: String,
    palette: AppManagementPalette,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = Color(palette.settings.row),
        contentColor = palette.settings.primaryText,
        cornerRadius = palette.miuix.actionRadius ?: 12.dp,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_search),
                contentDescription = null,
                tint = palette.settings.secondaryText,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = palette.settings.primaryText,
                    fontSize = 14.sp,
                    fontFamily = palette.settings.bodyFontFamily
                ),
                cursorBrush = SolidColor(palette.settings.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isBlank()) {
                            Text(
                                text = hint,
                                color = palette.settings.secondaryText,
                                fontSize = 14.sp,
                                fontFamily = palette.settings.bodyFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                AppManagementIconAction(
                    iconRes = R.drawable.ic_baseline_close,
                    contentDescription = null,
                    tint = palette.settings.secondaryText,
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun AppManagementSelectionBottomBar(
    selectedCount: Int,
    totalCount: Int,
    palette: AppManagementPalette,
    actions: List<AppManagementAction>,
    onSelectAll: (() -> Unit)?,
    onInvertSelection: (() -> Unit)?,
    wallpaperVisible: Boolean,
    glassSignature: String
) {
    val context = LocalContext.current
    val tokens = LocalLegadoTokens.current ?: rememberResolvedThemeTokens()
    val bottomScope = tokens.bottomBar
    val borderColor = Color(UiCorner.borderLineColor(context))
    val barBorder = BorderStroke(
        1.dp,
        if (!tokens.env.isEInk && bottomScope.isCustomOverride) {
            borderColor.copy(alpha = borderColor.alpha * bottomScope.borderAlphaFraction)
        } else {
            borderColor
        }
    )
    val barBgColor = Color(palette.settings.bottomBar).let { c ->
        if (!tokens.env.isEInk && bottomScope.isCustomOverride) {
            c.copy(alpha = c.alpha * bottomScope.alphaFraction)
        } else {
            c
        }
    }
    val bottomGlassActive = wallpaperVisible &&
        !tokens.env.isEInk &&
        bottomScope.materialMode != GlassMaterialPreset.SOLID
    AnimatedVisibility(visible = selectedCount > 0) {
        val mainAction = actions.lastOrNull { it.danger } ?: actions.lastOrNull()
        val moreActions = if (mainAction == null) actions else actions.filterNot { it === mainAction }
        val barContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.select_all_count, selectedCount, totalCount),
                    color = palette.settings.bottomBarText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = onSelectAll != null) { onSelectAll?.invoke() }
                        .padding(vertical = 9.dp)
                )
                onInvertSelection?.let {
                    CompactSelectionButton(
                        text = stringResource(R.string.revert_selection),
                        palette = palette,
                        onClick = it
                    )
                }
                mainAction?.let { action ->
                    Spacer(modifier = Modifier.width(6.dp))
                    CompactSelectionButton(
                        text = action.text,
                        palette = palette,
                        danger = action.danger,
                        primary = action.primary,
                        onClick = action.onClick
                    )
                }
                if (moreActions.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    SelectionMoreMenu(
                        actions = moreActions,
                        palette = palette
                    )
                }
            }
        }
        if (bottomGlassActive) {
            GlassPanel(
                domain = GlassSamplingDomain.WALLPAPER,
                cornerRadiusPx = 0f,
                configSignature = "$glassSignature|bot=${bottomScope.materialMode}:${bottomScope.alphaPercent}",
                modifier = Modifier
                    .fillMaxWidth()
                    .border(barBorder),
                fallback = Modifier.background(barBgColor)
            ) {
                barContent()
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barBgColor)
                    .border(barBorder)
            ) {
                barContent()
            }
        }
    }
}

@Composable
private fun RowScope.CompactSelectionButton(
    text: String,
    palette: AppManagementPalette,
    danger: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    LegadoMiuixActionButton(
        text = text,
        palette = palette.miuix,
        onClick = onClick,
        primary = primary,
        danger = danger,
        minWidth = 72.dp,
        minHeight = 34.dp,
        insidePadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun SelectionMoreMenu(
    actions: List<AppManagementAction>,
    palette: AppManagementPalette
) {
    AppManagementMoreActionButton(
        actionsProvider = {
            actions.map { action ->
                AppManagementMenuAction(
                    text = action.text,
                    danger = action.danger,
                    onClick = action.onClick
                )
            }
        },
        palette = palette,
        contentDescription = stringResource(R.string.more_menu)
    )
}
