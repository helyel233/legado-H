package io.legado.app.ui.main.bookshelf.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.widget.compose.appSettingPanelBackground

data class BookListCardMetrics(
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val coverWidth: Dp,
    val cornerRadius: Dp
)

fun BookshelfListRenderConfig.classicCardMetrics(compact: Boolean): BookListCardMetrics {
    return BookListCardMetrics(
        minHeight = if (compact) classicCompactMinHeight else classicMinHeight,
        horizontalPadding = 8.dp,
        verticalPadding = if (compact) 4.dp else 5.dp,
        coverWidth = if (compact) classicCompactCoverWidth else classicCoverWidth,
        cornerRadius = 2.dp
    )
}

fun BookshelfListRenderConfig.roundedCardMetrics(compact: Boolean): BookListCardMetrics {
    return BookListCardMetrics(
        minHeight = if (compact) roundedCompactMinHeight else roundedMinHeight,
        horizontalPadding = if (compact) 10.dp else 12.dp,
        verticalPadding = if (compact) 8.dp else 10.dp,
        coverWidth = if (compact) cardCompactCoverWidth else cardCoverWidth,
        cornerRadius = palette.actionRadius
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListCardSurface(
    rounded: Boolean,
    compact: Boolean,
    renderConfig: BookshelfListRenderConfig,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /**
     * 玻璃配置签名；非 null = 本卡片在壁纸域且材质为玻璃/磨砂时参与壁纸域玻璃（`null` 表示不参与）。
     *
     * 列表页面（搜书页 `SearchResultScreen`、发现标签展开列表 `ExploreShowComposeScreen`、
     * 现代发现列表 `ExploreModernListScreen`、书架列表 `BookshelfListItem`）应在页面级
     * 用 [io.legado.app.ui.glass.rememberGlassConfigSignature] 计算一次后传入；
     * 独立预览浮层等非壁纸域宿主保留 `null`。
     *
     * 实际是否启用 `GlassPanel` 同时受 `palette.cardUsesGlassSampling`（来自 `tokens.bookshelfCard`
     * 级联 Scope）与 `!palette.isEInk` 控制：`SOLID` / `CLEAR` / 墨水屏会直接落到主题实底或
     * `CLEAR_SURFACE_ALPHA` 半透明回退表面，避免再创建额外宿主 View。
     */
    glassSignature: String? = null,
    content: @Composable RowScope.(BookListCardMetrics) -> Unit
) {
    val palette = renderConfig.palette
    val metrics = if (rounded) {
        renderConfig.roundedCardMetrics(compact)
    } else {
        renderConfig.classicCardMetrics(compact)
    }
    val shape = RoundedCornerShape(if (rounded) palette.panelRadius else metrics.cornerRadius)
    val hasBgImage = palette.hasUsableWallpaper
    val shadowModifier = if (rounded && !palette.isEInk && palette.cardShadowDp > 0.dp) {
        Modifier.shadow(palette.cardShadowDp, shape, clip = false)
    } else {
        Modifier
    }
    val fallbackModifier = if (rounded) {
        Modifier.appSettingPanelBackground(
            normalColor = palette.rowColor,
            panelImage = renderConfig.panelImage,
            borderColor = palette.borderColor,
            radiusPx = palette.panelRadiusPx
        )
    } else if (hasBgImage) {
        Modifier.background(Color(palette.rowColor), shape)
    } else {
        Modifier
    }
    val glass = glassSignature
    if (glass != null && !palette.isEInk && palette.cardUsesGlassSampling) {
        val glassRadiusPx = if (rounded) {
            palette.panelRadiusPx
        } else {
            with(LocalDensity.current) { metrics.cornerRadius.toPx() }
        }
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = glassRadiusPx,
            configSignature = "$glass|card=${palette.cardMaterialMode}:${palette.panelRadiusPx}",
            requireWallpaperHost = true,
            modeOverride = palette.cardMaterialMode,
            modifier = modifier
                .fillMaxWidth()
                .then(shadowModifier)
                .clip(shape),
            fallback = fallbackModifier
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.minHeight)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                    .padding(
                        horizontal = metrics.horizontalPadding,
                        vertical = metrics.verticalPadding
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                content(metrics)
            }
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(shadowModifier)
            .clip(shape)
            .then(fallbackModifier)
            .heightIn(min = metrics.minHeight)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(
                horizontal = metrics.horizontalPadding,
                vertical = metrics.verticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content(metrics)
    }
}
