package io.legado.app.ui.config

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.book.library.LibraryContainerConfig
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LocalAppManagementGlassSignature
import io.legado.app.ui.widget.compose.imageBgTextShadow
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage

@Composable
internal fun LibraryContainerManageScreen(
    containers: List<LibraryContainerConfig>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onItemClick: (LibraryContainerConfig) -> Unit,
    pageMenuActions: () -> List<AppManagementMenuAction>,
    onMoreActions: (LibraryContainerConfig) -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    // 本页自带 Compose 顶栏，根 Surface 原先铺 palette.settings.page 整页不透明——壁纸模式下
    // 卡片玻璃只能采到被本页盖住的壁纸。背景图可用且非墨水屏时改铺透明（BaseActivity 已把
    // 根 View 背景置空，壁纸由此透出）；无背景图 / 墨水屏时仍返回原页色，观感逐值不变。
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily),
        // 列表卡是 AppManagementCard：提供签名后自动走壁纸域玻璃；提供 null（壁纸不可见）时
        // 卡片保持主题实底，与接入玻璃之前逐值一致。
        LocalAppManagementGlassSignature provides glassSignature.takeIf { wallpaperVisible }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = glassPageBackgroundColor(palette.settings.page, wallpaperVisible),
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                LibraryContainerTopBar(
                    onBack = onBack,
                    pageMenuActions = pageMenuActions
                )
                Text(
                    text = stringResource(R.string.library_container_summary_full),
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
                        .padding(start = 16.dp, top = 6.dp, end = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    items(containers, key = { it.id }) { item ->
                        LibraryContainerCard(
                            item = item,
                            isDefault = LibraryContainerManager.selectedId() == item.id,
                            onClick = { onItemClick(item) },
                            moreActions = onMoreActions(item)
                        )
                    }
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.library_container_add),
                    palette = palette.miuix,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = true,
                    cornerRadius = palette.miuix.actionRadius,
                    minHeight = 46.dp
                )
            }
        }
    }
}

@Composable
private fun LibraryContainerTopBar(
    onBack: () -> Unit,
    pageMenuActions: () -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onBack),
            shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
            color = Color.Transparent,
            contentColor = palette.settings.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = palette.settings.primaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = stringResource(R.string.library_container),
            color = palette.settings.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.settings.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        AppManagementMoreActionButton(
            actionsProvider = pageMenuActions,
            palette = palette,
            contentDescription = stringResource(R.string.more)
        )
    }
}

@Composable
private fun LibraryContainerCard(
    item: LibraryContainerConfig,
    isDefault: Boolean,
    onClick: () -> Unit,
    moreActions: List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    val container = item.container
    val displayName = LibraryContainerManager.displayLabel(item)
    val capacityMb = container.capacityMb.coerceAtLeast(0)
    val usedBytes = container.usedBytes.coerceAtLeast(0)
    val capacityText = if (capacityMb > 0) {
        val capacityBytes = LibraryContainerManageActivity.mbToBytes(capacityMb)
        stringResource(
            R.string.library_container_capacity,
            LibraryContainerManageActivity.formatBytes(capacityBytes),
            LibraryContainerManageActivity.formatBytes(usedBytes),
            LibraryContainerManageActivity.formatBytes((capacityBytes - usedBytes).coerceAtLeast(0)),
            stringResource(if (container.isFull) R.string.library_container_yes else R.string.library_container_no)
        )
    } else {
        stringResource(
            R.string.library_container_used_unlimited,
            LibraryContainerManageActivity.formatBytes(usedBytes)
        )
    }
    val minUpload = if (item.minUploadChars > 0) {
        stringResource(R.string.library_container_min_chars, item.minUploadChars)
    } else {
        stringResource(R.string.library_container_no_filter_short)
    }
    val dailyLimit = if (item.dailyUploadLimit > 0) {
        stringResource(R.string.library_container_daily_chapters, item.dailyUploadLimit)
    } else {
        stringResource(R.string.library_container_daily_unlimited)
    }
    val lockState = if (item.lockedImported) stringResource(R.string.library_container_locked_import) else ""
    val stateText = stringResource(
        R.string.library_container_state,
        stringResource(if (container.enabled) R.string.library_container_enable else R.string.library_container_disable),
        lockState,
        item.sourceUrls.size,
        minUpload,
        dailyLimit
    )

    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        onClick = onClick,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "${container.bucket}/${container.prefix.trim('/')}",
                    color = palette.settings.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = capacityText,
                    color = palette.settings.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stateText,
                    color = if (container.enabled) palette.settings.accent else palette.settings.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = if (container.enabled) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isDefault) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "✓",
                    color = palette.settings.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            AppManagementMoreActionButton(
                actionsProvider = { moreActions },
                palette = palette,
                contentDescription = stringResource(R.string.more)
            )
        }
    }
}
