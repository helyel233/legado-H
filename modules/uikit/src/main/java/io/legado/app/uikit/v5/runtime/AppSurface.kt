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
import io.legado.app.uikit.v5.contract.AppSemanticColors
import io.legado.app.uikit.v5.token.GlassSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.uikit.v5.token.VisualSpec

/**
 * P0-4 宿主下发视觉规格的 CompositionLocal。
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
 * P0-4 内容容器 —— 页面唯一能拿到的"带背景的盒子"。
 *
 * 页面只说用哪一档材质，外观参数全部读 [LocalVisualSpec]。
 * P0 提供基础实现（底色 + 描边 + 投影）；真实的背景模糊与噪声
 * 在 P1 宿主层补齐（需要 Android 12+ RenderEffect 或兼容降级）。
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

    val background = when {
        // E0 平铺：无容器
        level == MaterialLevel.FLAT -> Color.Transparent
        // 实色态：关玻璃时 E2/E3 退回干净实色卡片，两套观感都要成立
        !level.usesGlass || !glass.enabled -> when (level) {
            MaterialLevel.EMBEDDED -> colors.surfaceVariant
            else -> colors.surface
        }
        // 玻璃态：P1 起由宿主替换为真实模糊；此处先用半透明基底表达层次
        else -> {
            val a = when (level) {
                MaterialLevel.FLOATING -> glass.effectiveAlpha()
                else -> glass.effectiveAlpha() * 0.86f
            }
            colors.surface.copy(alpha = a)
        }
    }

    val borderColor = when {
        level == MaterialLevel.FLAT || level == MaterialLevel.EMBEDDED -> Color.Transparent
        !glass.enabled -> colors.outline
        else -> glass.strokeColor(colors.onSurface)
    }

    val elevation = when {
        !level.usesGlass || !glass.enabled -> level.shadowDp
        else -> level.shadowDp * 1.15f
    }

    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
            .clip(shape)
            .background(background)
            .then(
                if (level.hasStroke && borderColor != Color.Transparent)
                    Modifier.border(1.dp, borderColor, shape)
                else Modifier
            ),
        propagateMinConstraints = false,
    ) {
        content()
    }
}
