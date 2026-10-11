package io.legado.app.uikit.v5.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import io.legado.app.uikit.v5.contract.AppSemanticColors
import io.legado.app.uikit.v5.token.GlassSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.uikit.v5.token.VisualSpec

/**
 * P2-1 宿主下发视觉规格的 CompositionLocal。
 *
 * 全 App 只有一个提供者（宿主），页面只能读不能写。
 * 改任何参数 → 这里的值变化 → 整棵树自动重组，**不需要 recreate()**。
 */
val LocalVisualSpec: ProvidableCompositionLocal<VisualSpec> =
    compositionLocalOf { VisualSpec.DEFAULT }

val LocalSemanticColors: ProvidableCompositionLocal<AppSemanticColors> =
    compositionLocalOf { NeutralSemanticColors }

/** 未注入主题包时的中性默认色（保证 Provider 缺失也不崩）。 */
val NeutralSemanticColors = AppSemanticColors(
    background = Color(0xFF12141C),
    surface = Color(0xFF1A1D28),
    surfaceVariant = Color(0xFF22263A),
    primary = Color(0xFF8C9BEF),
    onPrimary = Color(0xFF0B0D14),
    secondary = Color(0xFFB79AEE),
    onSurface = Color(0xFFF0F1F7),
    onSurfaceVariant = Color(0xFFB4B8C8),
    outline = Color(0xFF2C3142),
    success = Color(0xFF4CC38A),
    warning = Color(0xFFE8A33D),
    error = Color(0xFFFF6B5B),
)

/**
 * P2-1 材质容器 —— 页面唯一能拿到的"带背景的盒子"（宿主内部也用它）。
 *
 * 四档材质（docs/ui-v5-skeleton-first.md 2.2）：
 * - E0 平铺：透明，内容直接落在背景上
 * - E1 嵌入：实色 surfaceVariant，无描边无阴影
 * - E2 浮起：真玻璃（模糊 + 基底 + 高光描边 + 噪声）+ 极弱阴影
 * - E3 悬浮：更强玻璃 + 亮描边 + 明显阴影
 *
 * 玻璃三态：
 * ① 采样源可用（hazeState != null）→ **真背景模糊**（hazeEffect），身后内容实时被糊开
 * ② 采样源不可用但玻璃开 → 半透明基底（P1 行为，降级）
 * ③ 玻璃关 → 干净实色卡片 + 细描边（低端机与"不喜欢玻璃"的兜底，两套观感都成立）
 */
@Composable
fun AppSurface(
    level: MaterialLevel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val glass = visual.glass
    val shape = visual.shape(ShapeKind.CARD)
    val hazeState = LocalHazeState.current

    val glassOn = level.usesGlass && glass.enabled
    val hazeActive = glassOn && hazeState != null

    // ---- 背景 ----
    val fallbackBackground = when {
        level == MaterialLevel.FLAT -> Color.Transparent
        !glassOn -> when (level) {
            MaterialLevel.EMBEDDED -> colors.surfaceVariant
            else -> colors.surface
        }
        else -> colors.surface.copy(alpha = when (level) {
            MaterialLevel.FLOATING -> glass.effectiveAlpha()
            else -> glass.effectiveAlpha() * 0.86f
        })
    }

    // ---- 描边 ----
    val borderColor = when {
        !level.hasStroke -> Color.Transparent
        !glassOn -> colors.outline
        glass.stroke -> glass.strokeColor(colors.onSurface)
        else -> Color.Transparent
    }

    // ---- 阴影（玻璃态略强，模拟光学厚度） ----
    val elevation = when {
        !glassOn -> level.shadowDp
        hazeActive -> level.shadowDp * 1.35f
        else -> level.shadowDp * 1.15f
    }

    var m: Modifier = modifier
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
        .clip(shape)

    if (hazeActive) {
        // 真玻璃：模糊由 haze 完成，基底色/噪声/饱和度并入样式
        m = m.hazeEffect(state = hazeState) {
            style = HazeStyle(
                backgroundColor = colors.surface,
                tints = listOf(
                    HazeTint(colors.surface.copy(alpha = when (level) {
                        MaterialLevel.FLOATING -> glass.effectiveAlpha()
                        else -> glass.effectiveAlpha() * 0.86f
                    })),
                ),
                blurRadius = glass.effectiveBlur().dp,
                noiseFactor = glass.noiseAlpha() * 2f,
                fallbackTint = HazeTint(colors.surface.copy(alpha = 0.94f)),
            )
        }
    } else {
        m = m.background(fallbackBackground)
    }

    if (borderColor != Color.Transparent) {
        m = m.border(1.dp, borderColor, shape)
    }

    Box(m, propagateMinConstraints = false) { content() }
}
