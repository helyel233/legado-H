package io.legado.app.uikit.v5.runtime

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.chrisbanes.haze.HazeState

/**
 * P2-1 真玻璃的采样状态（宿主持有并下发）。
 *
 * - 宿主把"背景 + 滚动内容"用 `Modifier.hazeSource(state)` 标记为采样源；
 * - 所有玻璃容器（AppSurface 的 E2/E3 玻璃态）通过 `hazeEffect` 实时模糊身后内容；
 * - **null = 不具备模糊条件**（宿主未接入 / 低版本降级），玻璃退回半透明基底。
 *
 * 这个设计让 AppSurface 保持零 Android 版本判断：接不接 haze 由宿主决定，
 * 组件只认"有没有采样源"。
 */
val LocalHazeState: ProvidableCompositionLocal<HazeState?> = compositionLocalOf { null }
