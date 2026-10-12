package io.legado.app.uikit.v5.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局修饰符：触控目标下限。
 * 无障碍门要求可点元素 ≥48dp；对图标/文字等视觉上小于 48dp 的可点件，
 * 用它扩展热区而不改变视觉尺寸。
 */
fun Modifier.appMinTouchTarget(minSize: Dp = 48.dp): Modifier =
    this.then(Modifier.sizeIn(minWidth = minSize, minHeight = minSize))
