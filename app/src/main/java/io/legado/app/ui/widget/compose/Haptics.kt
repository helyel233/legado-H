package io.legado.app.ui.widget.compose

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * 统一触觉反馈入口：在组件层（卡片/按钮/弹窗）统一注入，
 * 页面无需各自处理。全局开关可在设置中扩展。
 */
@Composable
fun rememberHapticView(): View = LocalView.current

fun View.performLongPressHaptic() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

fun View.performClickHaptic() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}
