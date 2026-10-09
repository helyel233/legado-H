package io.legado.app.uikit.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距 token，严格 4dp 网格（docs/ui-rewrite-plan.md 6.1）。
 */
object AppSpacing {

    val s2: Dp = 2.dp
    val s4: Dp = 4.dp
    val s8: Dp = 8.dp
    val s12: Dp = 12.dp
    val s16: Dp = 16.dp
    val s24: Dp = 24.dp
    val s32: Dp = 32.dp

    /** 页面左右边距 */
    val screenH: Dp = s16

    /** 卡片内边距 */
    val cardIn: Dp = s12

    /** 列表项水平/垂直内边距 */
    val listH: Dp = s16
    val listV: Dp = s12

    /** 书架栅格间距 */
    val grid: Dp = s8
}
