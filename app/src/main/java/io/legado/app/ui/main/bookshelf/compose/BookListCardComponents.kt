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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.ThemeConfig
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
    content: @Composable RowScope.(BookListCardMetrics) -> Unit
) {
    val palette = renderConfig.palette
    val metrics = if (rounded) {
        renderConfig.roundedCardMetrics(compact)
    } else {
        renderConfig.classicCardMetrics(compact)
    }
    val shape = RoundedCornerShape(if (rounded) palette.panelRadius else metrics.cornerRadius)
    // 经典样式原本无卡片底，自定义背景图下文字直接压图难分辨：
    // 背景图模式时套与圆角卡一致的半透明面板底（rowColor 已按界面不透明度调透明度）
    val context = LocalContext.current
    val hasBgImage = remember(context, palette.themeSignature) {
        ThemeConfig.hasUsableBgImage(context)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (rounded) {
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
            )
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
