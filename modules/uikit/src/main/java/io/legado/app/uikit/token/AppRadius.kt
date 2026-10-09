package io.legado.app.uikit.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆角档位（docs/ui-rewrite-plan.md §6.1）。
 * 实际渲染值 = 档位 × [io.legado.app.uikit.theme.LocalAppRadiusScale]，
 * 缩放系数由 App 侧注入（对应 uiCornerScale 用户设置）。
 */
object AppRadius {

    /** 标签、chip、封面角标 */
    val xs: Dp = 4.dp

    /** 小按钮、输入框、列表内元素 */
    val sm: Dp = 8.dp

    /** 卡片、面板、弹窗 */
    val md: Dp = 12.dp

    /** 底部栏、BottomSheet、大面板 */
    val lg: Dp = 24.dp
}
