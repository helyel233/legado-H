package io.legado.app.uikit.v5.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * 全局修饰符：触控目标下限。
 * 无障碍门要求可点元素 ≥48dp；对图标/文字等视觉上小于 48dp 的可点件，
 * 用它扩展热区而不改变视觉尺寸。
 */
fun Modifier.appMinTouchTarget(minSize: Dp = 48.dp): Modifier =
    this.then(Modifier.sizeIn(minWidth = minSize, minHeight = minSize))

/**
 * 全局修饰符：行卡片底（surface 72% + CARD 圆角）。
 * 背景图主题下透明行会"隐身"，与 [AppSelectionRow] 的底色观感对齐——
 * 四类行（开关/选择/动作/滑条）共用这一种底。
 */
@Composable
fun Modifier.appRowCard(): Modifier {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val shape = visual.shape(ShapeKind.CARD)
    return this
        .clip(shape)
        .background(colors.surface.copy(alpha = 0.72f), shape)
}
