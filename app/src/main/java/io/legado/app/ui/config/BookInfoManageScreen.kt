package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoPageStyle
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.LocalAppManagementGlassSignature
import io.legado.app.ui.widget.compose.imageBgTextShadow
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage

@Composable
internal fun BookInfoManageScreen(
    style: BookInfoPageStyle,
    components: List<BookInfoComponentItem>,
    onStyleChanged: (BookInfoPageStyle) -> Unit,
    onComponentToggle: (Int, Boolean) -> Unit,
    onReset: () -> Unit,
    onMoveBy: (BookInfoComponentItem, Int) -> Unit
) {
    val palette = rememberAppManagementPalette()
    // 根背景原先铺 palette.settings.page 整页不透明——壁纸模式下卡片玻璃只能采到被本页盖住的
    // 壁纸。背景图可用且非墨水屏时改铺透明（BaseActivity 已把根 View 背景置空，壁纸由此透出）；
    // 无背景图 / 墨水屏时仍返回原页色，观感逐值不变。
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()

    CompositionLocalProvider(
        // 列表卡与沉浸式面板都是 AppManagementCard：提供签名后自动走壁纸域玻璃；
        // 提供 null（壁纸不可见）时卡片保持主题实底，与接入玻璃之前逐值一致。
        LocalAppManagementGlassSignature provides glassSignature.takeIf { wallpaperVisible }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(glassPageBackgroundColor(palette.settings.page, wallpaperVisible))
        ) {
            // Tab bar
            StyleTabBar(
                style = style,
                palette = palette,
                onStyleChanged = onStyleChanged
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Summary text
            val summaryText = when (style) {
                BookInfoPageStyle.CLASSIC -> stringResource(R.string.book_info_components_hint)
                BookInfoPageStyle.IMMERSIVE_COMPOSE -> stringResource(R.string.book_info_style_immersive_hint)
            }
            Text(
                text = summaryText,
                color = palette.settings.secondaryText,
                fontSize = 13.sp,
                // 页根在壁纸模式下透明，这句说明直接压图难分辨，补自适应投影；
                // imageBgTextShadow 无背景图时返回 null，普通主题观感不变。
                style = LocalTextStyle.current.copy(
                    shadow = imageBgTextShadow(palette.settings.secondaryText)
                ),
                modifier = Modifier.padding(horizontal = 18.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (style) {
                BookInfoPageStyle.CLASSIC -> {
                    // Component list
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                    ) {
                        itemsIndexed(
                            items = components,
                            key = { _, item -> item.type.name }
                        ) { index, item ->
                            ComponentItemRow(
                                item = item,
                                palette = palette,
                                onCheckedChange = { checked ->
                                    onComponentToggle(index, checked)
                                },
                                onMoveBy = { delta ->
                                    // 只传条目身份+偏移量，索引由 Activity 侧按最新状态解析：
                                    // 拖动期间列表实时重排，同一帧内连续移动时组合侧列表会过期。
                                    onMoveBy(item, delta)
                                }
                            )
                        }
                    }

                    // Reset button
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.reset),
                        palette = palette.miuix,
                        onClick = onReset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }

                BookInfoPageStyle.IMMERSIVE_COMPOSE -> {
                    // Immersive info panel
                    AppManagementCard(
                        palette = palette,
                        modifier = Modifier
                            .fillMaxWidth(),
                        insidePadding = PaddingValues(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.book_info_style_immersive_title),
                            color = palette.settings.primaryText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.book_info_style_immersive_desc),
                            color = palette.settings.secondaryText,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StyleTabBar(
    style: BookInfoPageStyle,
    palette: AppManagementPalette,
    onStyleChanged: (BookInfoPageStyle) -> Unit
) {
    val isClassic = style == BookInfoPageStyle.CLASSIC
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StyleTabButton(
            text = stringResource(R.string.book_info_style_classic),
            selected = isClassic,
            palette = palette,
            onClick = { onStyleChanged(BookInfoPageStyle.CLASSIC) },
            modifier = Modifier.weight(1f)
        )
        StyleTabButton(
            text = stringResource(R.string.book_info_style_immersive),
            selected = !isClassic,
            palette = palette,
            onClick = { onStyleChanged(BookInfoPageStyle.IMMERSIVE_COMPOSE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StyleTabButton(
    text: String,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) {
        palette.settings.accent.copy(alpha = 0.14f)
    } else {
        palette.miuix.surfaceVariant
    }
    val textColor = if (selected) palette.settings.accent else palette.settings.secondaryText

    Box(
        modifier = modifier
            .height(42.dp)
            .background(backgroundColor, RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ComponentItemRow(
    item: BookInfoComponentItem,
    palette: AppManagementPalette,
    onCheckedChange: (Boolean) -> Unit,
    onMoveBy: (Int) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegadoMiuixSwitch(
                checked = item.enabled,
                onCheckedChange = onCheckedChange,
                palette = palette.miuix
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.type.titleRes),
                    color = palette.settings.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(item.type.hintRes),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            ComponentDragHandle(
                palette = palette,
                onMoveBy = onMoveBy
            )
        }
    }
}

/**
 * 详情页组件排序手柄：长按后上下拖动即移动一项（与订阅规则页同一套阈值拖动）。
 */
@Composable
private fun ComponentDragHandle(
    palette: AppManagementPalette,
    onMoveBy: (Int) -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 58.dp.toPx() }
    var accumulatedY by remember { mutableFloatStateOf(0f) }
    // pointerInput(Unit) 不随重组重启，必须始终调用最新回调，防止拖动中用旧闭包
    val currentMoveBy by rememberUpdatedState(onMoveBy)
    Icon(
        painter = painterResource(R.drawable.ic_arrange),
        contentDescription = stringResource(R.string.read_record_drag_sort),
        tint = palette.settings.secondaryText.copy(alpha = 0.6f),
        modifier = Modifier
            .size(36.dp)
            .padding(8.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = { accumulatedY = 0f },
                    onDragCancel = { accumulatedY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedY += dragAmount.y
                        while (accumulatedY >= thresholdPx) {
                            currentMoveBy(1)
                            accumulatedY -= thresholdPx
                        }
                        while (accumulatedY <= -thresholdPx) {
                            currentMoveBy(-1)
                            accumulatedY += thresholdPx
                        }
                    }
                )
            }
    )
}
