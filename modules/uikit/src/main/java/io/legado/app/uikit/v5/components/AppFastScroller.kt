package io.legado.app.uikit.v5.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastLastOrNull
import androidx.compose.ui.util.fastMaxBy
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** 分组粘性头 key 前缀：滚动条定位计算自动跳过带此前缀的项。 */
const val APP_STICKY_HEADER_KEY_PREFIX = "sticky:"

/**
 * 快速滚动条（台账 #30-P1，移植自 legado-with-MD3 的 VerticalFastScroller，
 * 上游 komikku）：拖动拇指直接跳转列表位置；闲置时缩为半透明胶囊并淡出。
 * 内容与滚动条在同一 SubcomposeLayout 内叠放，滚动条贴右缘。
 */
@Composable
fun AppFastScroller(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbColor: Color? = null,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val activeColor = thumbColor ?: colors.primary

    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty() || layoutInfo.totalItemsCount == 0) return@subcompose

            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()

            // 拖动时拇指保持放大形态，松手后再保持一小会儿
            var recentlyTouched by remember { mutableStateOf(false) }
            LaunchedEffect(isThumbDragged) {
                if (isThumbDragged) {
                    recentlyTouched = true
                } else {
                    delay(ThumbActiveDurationMillis.milliseconds)
                    recentlyTouched = false
                }
            }
            val thumbActive = isThumbDragged || recentlyTouched

            // 胶囊在闲置一段时间后淡出，滚动/拖动期间保持可见
            var capsuleVisible by remember { mutableStateOf(true) }
            LaunchedEffect(isThumbDragged, listState.isScrollInProgress, recentlyTouched) {
                if (isThumbDragged || listState.isScrollInProgress || recentlyTouched) {
                    capsuleVisible = true
                } else {
                    delay(ThumbHideDelayMillis.milliseconds)
                    capsuleVisible = false
                }
            }

            // listState.isScrollInProgress 偶发抖动，做一帧稳定化
            val scrollStateTracker = remember { MutableData(listState.isScrollInProgress) }
            val stableScrollInProgress = scrollStateTracker.value || listState.isScrollInProgress
            scrollStateTracker.value = listState.isScrollInProgress
            val anyScrollInProgress = stableScrollInProgress || isThumbDragged

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            val heightPx = contentHeight.toFloat() -
                    thumbTopPadding -
                    thumbBottomPadding -
                    listState.layoutInfo.afterContentPadding
            val thumbHeightPx = with(LocalDensity.current) { ThumbLength.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx
            val scrollHeightPx = contentHeight.toFloat() -
                    listState.layoutInfo.beforeContentPadding -
                    listState.layoutInfo.afterContentPadding -
                    thumbBottomPadding

            val visibleItems = layoutInfo.visibleItemsInfo
            val topItem = visibleItems.fastFirstOrNull {
                it.bottom >= 0 &&
                        (it.key as? String)?.startsWith(APP_STICKY_HEADER_KEY_PREFIX)?.not() ?: true
            } ?: visibleItems.first()
            val bottomItem = visibleItems.fastLastOrNull {
                it.top <= scrollHeightPx &&
                        (it.key as? String)?.startsWith(APP_STICKY_HEADER_KEY_PREFIX)?.not() ?: true
            } ?: visibleItems.last()

            val topHiddenProportion = -1f * topItem.top / topItem.size.coerceAtLeast(1)
            val bottomHiddenProportion = (bottomItem.bottom - scrollHeightPx) / bottomItem.size.coerceAtLeast(1)
            val previousSections = topHiddenProportion + topItem.index
            val remainingSections = bottomHiddenProportion + (layoutInfo.totalItemsCount - (bottomItem.index + 1))
            val scrollableSections = previousSections + remainingSections

            val layoutChangeTracker = remember { MutableData(scrollableSections) }
            val layoutChanged = !anyScrollInProgress && abs(layoutChangeTracker.value - scrollableSections) > 0.1
            layoutChangeTracker.value = scrollableSections

            val estimateConfidence = remember { MutableData(remainingSections) }
            if (layoutChanged) estimateConfidence.value = remainingSections
            val maxRemainingSections = remember(estimateConfidence.value) { scrollableSections }
            estimateConfidence.value = max(estimateConfidence.value, remainingSections)

            if (maxRemainingSections < 0.5) return@subcompose

            // 拖动拇指 → 跳转列表
            LaunchedEffect(thumbOffsetY) {
                if (layoutInfo.totalItemsCount == 0 || !isThumbDragged) return@LaunchedEffect
                val thumbProportion = (thumbOffsetY - thumbTopPadding) / trackHeightPx
                if (thumbProportion <= 0.001f) {
                    estimateConfidence.value = -1f
                    listState.scrollToItem(index = 0, scrollOffset = 0)
                    return@LaunchedEffect
                }
                val scrollRemainingSections = (1f - thumbProportion) * maxRemainingSections
                val currentSection = layoutInfo.totalItemsCount - scrollRemainingSections
                val scrollSectionIndex = currentSection.toInt().coerceAtMost(layoutInfo.totalItemsCount)
                val expectedScrollItem = visibleItems.find { it.index == scrollSectionIndex } ?: visibleItems.first()
                val scrollRelativeOffset = expectedScrollItem.size * (currentSection - scrollSectionIndex)
                val scrollSectionOffset = (scrollRelativeOffset - scrollHeightPx).roundToInt()
                val scrollItemIndex = scrollSectionIndex.coerceIn(0, layoutInfo.totalItemsCount - 1)
                val scrollItemOffset = scrollSectionOffset + (scrollSectionIndex - scrollItemIndex) * bottomItem.size
                listState.scrollToItem(index = scrollItemIndex, scrollOffset = scrollItemOffset)
            }

            // 列表滚动 → 同步拇指位置
            if (layoutInfo.totalItemsCount != 0 && !isThumbDragged) {
                val proportion = 1f - remainingSections / maxRemainingSections
                thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
            }

            val idleThumbColor = colors.outline.copy(alpha = IdleThumbAlpha)
            val thumbFormProgress by animateFloatAsState(
                targetValue = if (thumbActive) 1f else 0f,
                animationSpec = tween(durationMillis = ThumbFormDurationMillis),
                label = "thumbForm",
            )
            val animatedThumbColor by animateColorAsState(
                targetValue = if (thumbActive) activeColor else idleThumbColor,
                animationSpec = tween(durationMillis = ThumbFormDurationMillis),
                label = "thumbColor",
            )
            val thumbVisibilityAlpha by animateFloatAsState(
                targetValue = if (capsuleVisible) 1f else 0f,
                animationSpec = tween(durationMillis = ThumbHideFadeMillis),
                label = "thumbVisibility",
            )
            val thumbDraggable = !listState.isScrollInProgress && capsuleVisible
            val thumbWidth =
                IdleThumbThickness + (ThumbThickness - IdleThumbThickness) * thumbFormProgress
            val thumbHeight = IdleThumbLength + (ThumbLength - IdleThumbLength) * thumbFormProgress

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
                    .then(
                        if (thumbDraggable) {
                            Modifier.draggable(
                                interactionSource = dragInteractionSource,
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val newOffsetY = thumbOffsetY + delta
                                    thumbOffsetY = newOffsetY.coerceIn(
                                        thumbTopPadding,
                                        thumbTopPadding + trackHeightPx,
                                    )
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (thumbDraggable && !isThumbDragged) {
                            Modifier.systemGestureExclusion()
                        } else {
                            Modifier
                        },
                    )
                    .height(ThumbLength)
                    .padding(start = 8.dp, end = 4.dp)
                    .padding(end = endContentPadding)
                    .width(ThumbThickness),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(thumbWidth, thumbHeight)
                        .alpha(thumbVisibilityAlpha)
                        .background(
                            color = animatedThumbColor,
                            shape = RoundedCornerShape(thumbWidth / 2),
                        ),
                )
            }
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach {
                it.place(0, 0)
            }
            scrollerPlaceable.fastForEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

internal class MutableData<T>(var value: T)

private val ThumbLength = 48.dp
private val ThumbThickness = 12.dp
private val IdleThumbLength = 36.dp
private val IdleThumbThickness = 4.dp
private const val IdleThumbAlpha = 0.8f
private const val ThumbFormDurationMillis = 250
private const val ThumbActiveDurationMillis = 3000L
private const val ThumbHideDelayMillis = 3000L
private const val ThumbHideFadeMillis = 250

private val LazyListItemInfo.top: Int
    get() = offset

private val LazyListItemInfo.bottom: Int
    get() = offset + size
