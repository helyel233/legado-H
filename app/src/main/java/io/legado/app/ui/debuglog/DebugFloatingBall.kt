package io.legado.app.ui.debuglog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.lib.theme.accentColor
import kotlin.math.roundToInt

/**
 * 调试悬浮球（移植自辞晨版 legados）。
 *
 * 可拖拽、松手后按距离吸附到屏幕左右边缘（吸边时半个球探出屏外），
 * 点击由宿主 [DebugFloatingBallManager] 打开调试日志面板。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DebugFloatingBall(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ballSize = 56.dp
    val endMargin = 16.dp
    val bottomMargin = 100.dp
    val initialInset = 8.dp
    val snapThreshold = 84.dp
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var initialized by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val ballSizePx = with(density) { ballSize.toPx() }
    val endMarginPx = with(density) { endMargin.toPx() }
    val bottomMarginPx = with(density) { bottomMargin.toPx() }
    val initialInsetPx = with(density) { initialInset.toPx() }
    val snapThresholdPx = with(density) { snapThreshold.toPx() }
    val halfBallSizePx = ballSizePx / 2f

    fun snapHalfIntoHorizontalEdge(currentOffset: Offset): Offset {
        val maxX = (containerSize.width - ballSizePx).coerceAtLeast(0f)
        val maxY = (containerSize.height - ballSizePx).coerceAtLeast(0f)
        val clampedY = currentOffset.y.coerceIn(0f, maxY)
        val targetX = when {
            currentOffset.x <= snapThresholdPx -> -halfBallSizePx
            maxX - currentOffset.x <= snapThresholdPx -> maxX + halfBallSizePx
            else -> currentOffset.x.coerceIn(0f, maxX)
        }
        return Offset(targetX, clampedY)
    }

    LaunchedEffect(containerSize) {
        if (initialized || containerSize.width <= 0 || containerSize.height <= 0) return@LaunchedEffect
        val maxX = (containerSize.width - ballSizePx).coerceAtLeast(0f)
        val maxY = (containerSize.height - ballSizePx).coerceAtLeast(0f)
        offset = Offset(
            x = (maxX - endMarginPx - initialInsetPx).coerceAtLeast(0f),
            y = (maxY - bottomMarginPx - initialInsetPx).coerceAtLeast(0f)
        )
        initialized = true
    }

    val accent = Color(LocalContext.current.accentColor)
    val startColor = lerp(accent, Color.White, 0.18f)
    val endColor = lerp(accent, Color.Black, 0.18f)
    val ringColor = Color.White.copy(alpha = 0.28f)
    val glowColor = Color.White.copy(alpha = 0.18f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        if (initialized) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(ballSize)
                    .shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        ambientColor = startColor,
                        spotColor = endColor
                    )
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colors = listOf(startColor, endColor)))
                    .border(1.5.dp, ringColor, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { },
                            onDragCancel = {
                                offset = snapHalfIntoHorizontalEdge(offset)
                            },
                            onDragEnd = {
                                offset = snapHalfIntoHorizontalEdge(offset)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxX = (containerSize.width - ballSizePx).coerceAtLeast(0f)
                                val maxY = (containerSize.height - ballSizePx).coerceAtLeast(0f)
                                offset = Offset(
                                    x = (offset.x + dragAmount.x).coerceIn(0f, maxX),
                                    y = (offset.y + dragAmount.y).coerceIn(0f, maxY)
                                )
                            }
                        )
                    }
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = glowColor
                ) {}

                Text(
                    text = "\u2139",
                    color = Color.White,
                    fontSize = 22.sp
                )
            }
        }
    }
}
