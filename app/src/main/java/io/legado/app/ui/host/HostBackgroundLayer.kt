package io.legado.app.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.legado.app.uikit.v5.runtime.LocalSemanticColors

/**
 * P1-1 L0 背景层（宿主独占，页面碰不到）。
 *
 * 三层叠加：底色 → 主色氛围光 → 次色氛围光。
 * 氛围光是"现代化观感"的关键之一：纯色平底会显得像上一代 App，
 * 用种子色派生的大面积极淡渐变能立刻拉开档次。
 *
 * 主题包的背景图 / 纹理叠层在 P3 包体系上线后接进来，接口预留在此。
 */
@Composable
fun HostBackgroundLayer(modifier: Modifier = Modifier) {
    val colors = LocalSemanticColors.current

    Box(modifier = modifier.fillMaxSize()) {
        // ① 底色：背景 → 表面，纵向极淡渐变
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.background, colors.surface),
                    )
                )
        )
        // ② 主色氛围光（左上）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(colors.primary.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(0.12f, 0.06f),
                        radius = 1400f,
                    )
                )
        )
        // ③ 次色氛围光（右下）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(colors.secondary.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(0.92f, 0.96f),
                        radius = 1200f,
                    )
                )
        )
    }
}
