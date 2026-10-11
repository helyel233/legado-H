package io.legado.app.ui.host

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.legado.app.uikit.v5.resolve.BackgroundScene
import io.legado.app.uikit.v5.resolve.ThemeResolver
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import java.io.File

/**
 * P1-1 / P3-1 L0 背景层（宿主独占，页面碰不到）。
 *
 * 叠层顺序（从底到顶）：
 * ① 主题包背景图（三槽的 MAIN，按显示尺寸采样解码）——无图时用底色渐变
 * ② 双色氛围光（种子色派生的大面积极淡渐变，无背景图时的主要层次来源；
 *    有背景图时保留，给照片底子提层次）
 * ③ 纹理叠层（纸张/噪点，主题包 texture，透明度由包声明）
 */
@Composable
fun HostBackgroundLayer(modifier: Modifier = Modifier) {
    val colors = LocalSemanticColors.current
    val context = LocalContext.current

    val bgPath = ThemeResolver.backgroundPath(BackgroundScene.MAIN)
    val texture = ThemeResolver.activeTheme.texture

    // 按显示尺寸采样解码，避免一张 4K 背景图吃掉几十 MB 内存
    val bgBitmap = remember(bgPath) {
        decodeSampled(bgPath, maxDim = 2048)
    }
    val textureBitmap = remember(texture?.path) {
        decodeSampled(texture?.path, maxDim = 1024)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ① 背景图 / 底色渐变
        if (bgBitmap != null) {
            Image(
                bitmap = bgBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 压暗遮罩：保证前景文字对比度不被照片底子破坏
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.background, colors.surface),
                        )
                    )
            )
        }
        // ② 氛围光
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
        // ③ 纹理叠层
        if (textureBitmap != null && texture != null) {
            Image(
                bitmap = textureBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = texture.opacity.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 采样解码：最长边不超过 [maxDim]，失败返回 null（背景层优雅回退到渐变）。 */
private fun decodeSampled(path: String?, maxDim: Int) = try {
    if (path.isNullOrBlank() || !File(path).exists()) {
        null
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
} catch (_: Exception) {
    null
}
