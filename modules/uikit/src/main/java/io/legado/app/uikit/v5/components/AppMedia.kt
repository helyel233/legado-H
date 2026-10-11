package io.legado.app.uikit.v5.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-5 展示零件：封面 / 头像 / 芯片 / 徽章 / 图标底盘。
 * 封面加载策略（采样解码、占位、失败占位）在 P3 包体系接入图片源时补齐，
 * 本组件先定义好语义槽位。
 */

/**
 * 封面：无图时用主题派生渐变 + 首字；有图用 painter。
 * 底部进度条、右上更新点都是可选语义，不算"页面专属视觉"。
 */
@Composable
fun AppCover(
    label: String,
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    tint: Color? = null,
    progress: Float? = null,
    updateDot: Boolean = false,
    done: Boolean = false,
    shape: Shape? = null,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val coverShape = shape ?: visual.shape(ShapeKind.COVER)

    Box(
        modifier = modifier
            .clip(coverShape)
            .background(colors.surfaceVariant),
    ) {
        if (painter != null) {
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                tint ?: colors.primary,
                                (tint ?: colors.primary).copy(alpha = 0.55f),
                            ),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.take(1),
                    style = visual.type.headline,
                    color = colors.onPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // 已读完角标（主色对勾）
        if (done) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = "已读完",
                    tint = colors.onPrimary,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        // 更新圆点
        if (updateDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.error),
            )
        }
        // 阅读进度条（封面底部）
        if (progress != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.30f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(colors.primary),
                )
            }
        }
    }
}

/** 头像：首字圆形。 */
@Composable
fun AppAvatar(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.primary, colors.secondary))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1),
            style = visual.type.label,
            color = colors.onPrimary,
        )
    }
}

/** 芯片：横滑筛选、历史记录共用。 */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .clip(visual.shape(ShapeKind.TAG))
            .background(if (selected) colors.primary else colors.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = visual.type.caption,
            color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

/** 图标底盘：语义图标放在 E1 底上 —— 列表前导位的统一形态。 */
@Composable
fun AppIconBox(
    icon: ImageVectorHolder,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 38.dp,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(visual.shape(ShapeKind.TAG))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon.vector,
            contentDescription = null,
            tint = tint ?: colors.primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** 图标引用封装（避免 uikit 直接依赖 app 的图标集）。 */
class ImageVectorHolder(val vector: androidx.compose.ui.graphics.vector.ImageVector)

/** 简易徽章（数字）。 */
@Composable
fun AppBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .clip(visual.shape(ShapeKind.TAG))
            .background(colors.error)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = count.toString(),
            style = visual.type.caption,
            color = Color.White,
        )
    }
}
