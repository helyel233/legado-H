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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.ThemeConfig
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
     * 玻璃配置签名；非 null = 本卡片参与壁纸域玻璃（`null` 表示不参与）。
     *
     * 默认 `null` 是**刻意**的：本组件同时被书架页、发现页与搜索预览浮层复用，
     * 其中只有搜书页的页面根布局无自绘背景、卡片背后真的是壁纸。做成「默认不参与 +
     * 调用方显式传签名」而不是「有背景图就玻璃」，是为了让书架 / 发现页逐值不变
     * ——这三页共用同一个卡片骨架，任何默认开启的判定都会一次性改掉它们的观感。
     *
     * 签名由页面级 [io.legado.app.ui.glass.rememberGlassConfigSignature] 算一次后逐行传入，
     * 因此「观感配置」监听只有页级 1 个，不会每行各注册一个。
     *
     * **但每行仍会各注册 1 个「壁纸可用性」监听**（[GlassPanel] 在
     * `requireWallpaperHost = true` 时的既有实现，见 `GlassPanel.kt` 的
     * `rememberGlassAvailability`）：可见 10~20 行就有 10~20 个，随行销毁时注销。
     * 这是有界的（每次偏好写入触发一次档位读取 + 一次 ContextWrapper 回溯，与
     * 书架页 / 发现页逐行玻璃完全同构），底图本身由进程级 cache 共享、不按行复制，
     * 因此不构成泄漏或内存放大。要减到 1 个需要把可用性判定提到页级再传下来，
     * 那会改变 `GlassPanel` 的公共契约，不在本次范围。
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
    // 经典样式原本无卡片底，自定义背景图下文字直接压图难分辨：
    // 背景图模式时套与圆角卡一致的半透明面板底（rowColor 已按界面不透明度调透明度）
    val context = LocalContext.current
    val hasBgImage = remember(context, palette.themeSignature) {
        ThemeConfig.hasUsableBgImage(context)
    }
    // 经典样式在背景图模式下同样有半透明面板底（可让位的卡面），因此与圆角卡一样参与玻璃：
    // 只有几何与回退面按样式分流（经典 2dp 圆角、回退 rowColor 纯色底）。
    // 不传签名的调用方（书架页 / 发现页 / 搜索预览浮层）glass 恒为 null，观感逐值不变。
    val glass = glassSignature
    if (glass != null) {
        // 让位给玻璃：卡片自身不再铺不透明 rowColor，玻璃层由 GlassPanel 垫在内容之下。
        // 判否（solid 档 / 墨水屏 / 低内存 / 无背景图）时 GlassPanel 铺 fallback，
        // 那份 fallback 与非玻璃分支同一样式（圆角卡：实底 + 可选面板图 + 描边；
        // 经典：背景图模式的 rowColor 半透明底，无背景图时本来就没有卡面）。
        // 圆角必须跟样式走：经典卡的非玻璃形是 2dp 小圆角，玻璃层沿用 panelRadiusPx
        // 会让玻璃卡与非玻璃卡（以及 clip(shape) 的点击涟漪）形状不一致。
        val glassRadiusPx = if (rounded) {
            palette.panelRadiusPx
        } else {
            with(LocalDensity.current) { metrics.cornerRadius.toPx() }
        }
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = glassRadiusPx,
            configSignature = glass,
            // 页面内卡片必须要求「这张卡背后真的有壁纸」，否则透明根背景的判定
            // 会漏到自铺不透明内容的页面上，采出用户看不见的壁纸
            requireWallpaperHost = true,
            modifier = modifier
                .fillMaxWidth()
                .clip(shape),
            fallback = if (rounded) {
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
