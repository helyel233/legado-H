package io.legado.app.ui.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import kotlinx.coroutines.delay

/**
 * P1-1 弹层宿主（Toast / Snackbar 的落点）。
 *
 * 弹窗、菜单、Toast 全部由宿主渲染，页面只能"请求显示一条消息"，
 * 拿不到弹层本身 —— 所以不可能出现"某个页面的 Toast 长得不一样"。
 *
 * P2 组件库上线后这里会接入真正的 AppDialog / AppBottomSheet / AppSnackbar。
 */
class HostOverlay {
    var message: String? by mutableStateOf(null)
        private set

    fun show(text: String) {
        message = text
    }

    fun dismiss() {
        message = null
    }
}

val LocalHostOverlay: CompositionLocal<HostOverlay> = compositionLocalOf { HostOverlay() }

@Composable
fun HostOverlayLayer(
    overlay: HostOverlay,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val message = overlay.message

    LaunchedEffect(message) {
        if (message != null) {
            delay(1800)
            overlay.dismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(visual.motion.shortSpec()) + slideInVertically(visual.motion.mediumSpec()) { it / 2 },
            exit = fadeOut(visual.motion.shortSpec()) + slideOutVertically(visual.motion.mediumSpec()) { it / 2 },
        ) {
            AppSurface(backgroundBlur = true,
                level = MaterialLevel.FLOATING,
                modifier = Modifier.padding(bottom = 112.dp),
            ) {
                Text(
                    text = message.orEmpty(),
                    style = visual.type.label,
                    color = colors.onSurface,
                    modifier = Modifier.padding(
                        horizontal = visual.density.screenPadding,
                        vertical = visual.density.rowPaddingV,
                    ),
                )
            }
        }
    }
}
