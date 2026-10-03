package io.legado.app.ui.widget.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 可拖拽垂直快速滚动条（自绘浮动拖柄版，移植自 Legado_Max 的 VerticalScrollbar）。
 *
 * 外观与显隐节奏对齐参考分支 NG_main 目录抽屉的快速滚动块：无常驻轨道，只有一块
 * 圆角浮动拖柄、内含上下箭头；内容不可滚动时完全不显示，滚动中或拖拽中显示、
 * 停止 1 秒后淡出，淡出后不再拦截触摸。
 *
 * 用法：与内容放在同一个 Box 中，调用点只做 `Modifier.align(Alignment.CenterEnd)`。
 */

/** 停止滚动后拖柄淡出的等待时长（毫秒） */
private const val ScrollbarHideDelayMillis = 1_000L

private val ScrollbarRailWidth = 28.dp
private val ScrollbarHandleWidth = 24.dp
private val ScrollbarHandleHeight = 56.dp
private val ScrollbarHandleCornerRadius = 12.dp
private val ScrollbarHandleBorderWidth = 0.5.dp
private val ScrollbarChevronSize = 16.dp
private val ScrollbarChevronSpacing = 2.dp
private val ScrollbarVerticalPadding = 8.dp
private val ScrollbarHandleShadowElevation = 2.dp

private const val SCROLLBAR_HANDLE_BLEND_LIGHT = 0.06f
private const val SCROLLBAR_HANDLE_BLEND_DARK = 0.12f
private const val SCROLLBAR_HANDLE_ALPHA_LIGHT = 0.90f
private const val SCROLLBAR_HANDLE_ALPHA_DARK = 0.94f
private const val SCROLLBAR_HANDLE_BORDER_ALPHA = 0.14f
private const val SCROLLBAR_HANDLE_BORDER_ALPHA_EINK = 0.42f
private const val SCROLLBAR_HANDLE_SHADOW_ALPHA_LIGHT = 0.12f
private const val SCROLLBAR_HANDLE_SHADOW_ALPHA_DARK = 0.28f
private const val SCROLLBAR_HANDLE_ICON_ALPHA = 0.86f

@Composable
fun ComposeLazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Dp = 44.dp,
    touchTargetWidth: Dp = AppConfig.fastScrollerTouchTargetDp.dp,
    dragHotZoneWidth: Dp = touchTargetWidth
) {
    val canScroll = state.canScrollForward || state.canScrollBackward
    if (!enabled || !canScroll) return
    // 像素口径：内容总高与已滚距离都用"可见项平均高度"估算，两者同口径，
    // 所以列表项高度参差、可见项数量一帧一变时比例几乎不动。
    val scrollFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val first = visible.firstOrNull()
            if (first == null || visible.isEmpty() || info.viewportSize.height <= 0) return@derivedStateOf 0f
            val avgItemHeight = visible.sumOf { it.size }.toFloat() / visible.size
            if (avgItemHeight <= 0f) return@derivedStateOf 0f
            val maxScroll = avgItemHeight * info.totalItemsCount - info.viewportSize.height
            if (maxScroll <= 0f) return@derivedStateOf 0f
            val scrolled = first.index * avgItemHeight - first.offset - info.beforeContentPadding
            (scrolled / maxScroll).coerceIn(0f, 1f)
        }
    }
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    ScrollbarHandle(
        scrollFraction = scrollFraction,
        isScrollInProgress = state.isScrollInProgress,
        onScrollFractionChange = { fraction ->
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isNotEmpty() && info.totalItemsCount > 0) {
                val avgItemHeight = visible.sumOf { it.size }.toFloat() / visible.size
                val maxScroll = avgItemHeight * info.totalItemsCount - info.viewportSize.height
                if (avgItemHeight > 0f && maxScroll > 0f) {
                    // 与显示同一口径反算下标，拖柄才会停在手指所在的位置；
                    // 未计入首项的部分可见偏移 first.offset，量级 ≤1 项的静态偏差，
                    // 是估算口径的固有取舍（scrollToItem 会把首项对齐视口开头）
                    val index = ((fraction * maxScroll + info.beforeContentPadding) / avgItemHeight)
                        .roundToInt()
                        .coerceIn(0, info.totalItemsCount - 1)
                    scrollJob?.cancel()
                    scrollJob = scope.launch { state.scrollToItem(index) }
                }
            }
        },
        modifier = modifier,
        // 调用方可配拖拽热区宽度（fastScrollerTouchTargetDp），不能静默丢掉
        touchWidth = maxOf(touchTargetWidth, dragHotZoneWidth)
    )
}

@Composable
fun ComposeLazyGridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Dp = 44.dp,
    touchTargetWidth: Dp = AppConfig.fastScrollerTouchTargetDp.dp,
    dragHotZoneWidth: Dp = touchTargetWidth
) {
    val canScroll = state.canScrollForward || state.canScrollBackward
    if (!enabled || !canScroll) return
    // 网格要按"行"算：item 下标每行跳列数次，直接用下标会让拖柄一行一行地窜，
    // 所以先用首行列数换出行下标，再把行高与行数当像素口径。
    val scrollFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val first = visible.firstOrNull()
            if (first == null || visible.isEmpty() || info.viewportSize.height <= 0) return@derivedStateOf 0f
            val columns = columnsInFirstRow(visible)
            val avgLineHeight = visible.sumOf { it.size.height }.toFloat() / visible.size
            if (avgLineHeight <= 0f) return@derivedStateOf 0f
            val lineCount = ceil(info.totalItemsCount / columns.toFloat()).toInt()
            val maxScroll = avgLineHeight * lineCount - info.viewportSize.height
            if (maxScroll <= 0f) return@derivedStateOf 0f
            val lineIndex = first.index / columns
            val scrolled = lineIndex * avgLineHeight - first.offset.y - info.beforeContentPadding
            (scrolled / maxScroll).coerceIn(0f, 1f)
        }
    }
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    ScrollbarHandle(
        scrollFraction = scrollFraction,
        isScrollInProgress = state.isScrollInProgress,
        onScrollFractionChange = { fraction ->
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isNotEmpty() && info.totalItemsCount > 0) {
                val columns = columnsInFirstRow(visible)
                val avgLineHeight = visible.sumOf { it.size.height }.toFloat() / visible.size
                val lineCount = ceil(info.totalItemsCount / columns.toFloat()).toInt()
                val maxScroll = avgLineHeight * lineCount - info.viewportSize.height
                if (avgLineHeight > 0f && maxScroll > 0f) {
                    val lineIndex = ((fraction * maxScroll + info.beforeContentPadding) / avgLineHeight)
                        .roundToInt()
                        .coerceAtLeast(0)
                    val index = (lineIndex * columns).coerceIn(0, info.totalItemsCount - 1)
                    scrollJob?.cancel()
                    scrollJob = scope.launch { state.scrollToItem(index) }
                }
            }
        },
        modifier = modifier,
        // 调用方可配拖拽热区宽度（fastScrollerTouchTargetDp），不能静默丢掉
        touchWidth = maxOf(touchTargetWidth, dragHotZoneWidth)
    )
}

/** 首行可见项数即列数：同一行的项 offset.y 相同，且 visibleItemsInfo 按下标升序。 */
private fun columnsInFirstRow(visibleItems: List<LazyGridItemInfo>): Int {
    val first = visibleItems.firstOrNull() ?: return 1
    return visibleItems.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
}

@Composable
private fun ScrollbarHandle(
    scrollFraction: Float,
    isScrollInProgress: Boolean,
    onScrollFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    // 拖拽热区宽度（调用方传 fastScrollerTouchTargetDp 等用户配置），
    // 至少不小于拖柄轨道宽度；minThumbHeight 在浮动拖柄设计下无对应概念，忽略
    touchWidth: Dp = ScrollbarRailWidth
) {
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    // 显隐节奏：滚动中/拖拽中立即出现；静止满 ScrollbarHideDelayMillis 后淡出
    LaunchedEffect(isScrollInProgress, dragging) {
        when {
            isScrollInProgress || dragging -> visible = true
            else -> {
                delay(ScrollbarHideDelayMillis)
                visible = false
            }
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = SpringSpec(stiffness = Spring.StiffnessMediumLow),
        label = "VerticalScrollbarAlpha"
    )
    val currentOnScrollFractionChange by rememberUpdatedState(onScrollFractionChange)
    val density = LocalDensity.current
    val isDark = AppConfig.isNightTheme
    val isEInk = AppConfig.isEInkMode
    val palette = rememberAppManagementPalette()
    val accent = palette.settings.accent
    // 底色取色口径：浅色主题用白、深色主题用比背景更亮的容器色，再掺一点主色避免死灰
    val baseColor = if (isDark) Color(palette.settings.rowPressed) else Color.White
    val containerColor = Color(
        ColorUtils.blendColors(
            baseColor.toArgb(),
            accent.toArgb(),
            if (isDark) SCROLLBAR_HANDLE_BLEND_DARK else SCROLLBAR_HANDLE_BLEND_LIGHT
        )
    ).copy(
        alpha = when {
            isEInk -> 1f
            isDark -> SCROLLBAR_HANDLE_ALPHA_DARK
            else -> SCROLLBAR_HANDLE_ALPHA_LIGHT
        }
    )
    val borderColor = accent.copy(
        alpha = if (isEInk) SCROLLBAR_HANDLE_BORDER_ALPHA_EINK else SCROLLBAR_HANDLE_BORDER_ALPHA
    )
    val shadowColor = Color.Black.copy(
        alpha = if (isDark) SCROLLBAR_HANDLE_SHADOW_ALPHA_DARK else SCROLLBAR_HANDLE_SHADOW_ALPHA_LIGHT
    )
    val iconColor = accent.copy(alpha = SCROLLBAR_HANDLE_ICON_ALPHA)
    val handleShape = RoundedCornerShape(ScrollbarHandleCornerRadius)
    var railHeightPx by remember { mutableStateOf(0f) }
    val paddingPx = with(density) { ScrollbarVerticalPadding.toPx() }
    val handleHeightPx = with(density) { ScrollbarHandleHeight.toPx() }
    val travelPx = (railHeightPx - paddingPx * 2f - handleHeightPx).coerceAtLeast(1f)

    Box(
        modifier = modifier
            .width(maxOf(touchWidth, ScrollbarRailWidth))
            .fillMaxHeight()
            .onSizeChanged { railHeightPx = it.height.toFloat() }
            .then(
                // 淡出后不吃触摸，避免挡住内容右缘的点击与滑动
                if (visible) {
                    Modifier.pointerInput(railHeightPx) {
                        // 手指绝对位置映射到滚动比例：轨道顶部 = 开头，底部 = 结尾
                        fun scrollTo(positionY: Float) {
                            val fraction = ((positionY - paddingPx - handleHeightPx / 2f) / travelPx)
                                .coerceIn(0f, 1f)
                            currentOnScrollFractionChange(fraction)
                        }

                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                scrollTo(it.y)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onDrag = { change, _ ->
                                change.consume()
                                scrollTo(change.position.y)
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // 只在拿到真实高度后绘制，避免首帧用 0 计算偏移
        if (railHeightPx > 0f) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (paddingPx + travelPx * scrollFraction.coerceIn(0f, 1f)).roundToInt()
                        )
                    }
                    .width(ScrollbarHandleWidth)
                    .height(ScrollbarHandleHeight)
                    .graphicsLayer { this.alpha = alpha }
                    .shadow(
                        elevation = if (isEInk) 0.dp else ScrollbarHandleShadowElevation,
                        shape = handleShape,
                        clip = false,
                        ambientColor = shadowColor,
                        spotColor = shadowColor
                    )
                    .clip(handleShape)
                    .background(containerColor)
                    .border(
                        width = ScrollbarHandleBorderWidth,
                        color = borderColor,
                        shape = handleShape
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Chevron(isUp = true, color = iconColor, size = ScrollbarChevronSize)
                Box(Modifier.height(ScrollbarChevronSpacing))
                Chevron(isUp = false, color = iconColor, size = ScrollbarChevronSize)
            }
        }
    }
}

/** 上下箭头（应用未引入 material-icons 依赖，用 Canvas 画三角形替代） */
@Composable
private fun Chevron(isUp: Boolean, color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path()
        if (isUp) {
            path.moveTo(w / 2f, h * 0.2f)
            path.lineTo(w * 0.85f, h * 0.8f)
            path.lineTo(w * 0.15f, h * 0.8f)
        } else {
            path.moveTo(w / 2f, h * 0.8f)
            path.lineTo(w * 0.85f, h * 0.2f)
            path.lineTo(w * 0.15f, h * 0.2f)
        }
        path.close()
        drawPath(path, color)
    }
}
