package io.legado.app.uikit.token

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape

/**
 * 形状工厂（docs/ui-rewrite-plan.md §6.5.4 形状应用对照表）。
 * 所有 shape 必须从这里取，禁止业务代码直接写 RoundedCornerShape(数字)。
 */
object AppShapes {

    /** chip / 标签 → radius.xs */
    fun tag(scale: Float = 1f): Shape = RoundedCornerShape(AppRadius.xs * scale)

    /** 按钮 / 输入框 → radius.sm */
    fun action(scale: Float = 1f): Shape = RoundedCornerShape(AppRadius.sm * scale)

    /** 卡片 / 弹窗 / 面板 → radius.md */
    fun panel(scale: Float = 1f): Shape = RoundedCornerShape(AppRadius.md * scale)

    /** 底部栏 / BottomSheet → radius.lg */
    fun sheet(scale: Float = 1f): Shape = RoundedCornerShape(AppRadius.lg * scale)

    /** 头像 / 圆形 */
    val full: Shape = CircleShape
}
