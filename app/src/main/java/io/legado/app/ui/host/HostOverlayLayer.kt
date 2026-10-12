package io.legado.app.ui.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * 2026-10-12：升级为带 action 的 Snackbar（"已删除 · 撤销"形态），
 * LocalHostOverlay 由宿主 provide，页面 `LocalHostOverlay.current.showSnackbar(...)`。
 */
class HostSnackbar(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val durationMs: Long = 2600L,
)

class HostOverlay {
    var snackbar: HostSnackbar? by mutableStateOf(null)
        private set

    fun show(text: String) {
        snackbar = HostSnackbar(text)
    }

    fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long = 2600L,
    ) {
        snackbar = HostSnackbar(message, actionLabel, onAction, durationMs)
    }

    fun dismiss() {
        snackbar = null
    }
}

val LocalHostOverlay: androidx.compose.runtime.ProvidableCompositionLocal<HostOverlay> =
    compositionLocalOf { HostOverlay() }

@Composable
fun HostOverlayLayer(
    overlay: HostOverlay,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val snackbar = overlay.snackbar

    LaunchedEffect(snackbar) {
        if (snackbar != null) {
            delay(snackbar.durationMs)
            overlay.dismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = snackbar != null,
            enter = fadeIn(visual.motion.shortSpec()) + slideInVertically(visual.motion.mediumSpec()) { it / 2 },
            exit = fadeOut(visual.motion.shortSpec()) + slideOutVertically(visual.motion.mediumSpec()) { it / 2 },
        ) {
            AppSurface(backgroundBlur = true,
                level = MaterialLevel.FLOATING,
                modifier = Modifier.padding(bottom = 112.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = snackbar?.message.orEmpty(),
                        style = visual.type.label,
                        color = colors.onSurface,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(
                                start = visual.density.screenPadding,
                                end = visual.density.screenPadding / 2,
                                top = visual.density.rowPaddingV,
                                bottom = visual.density.rowPaddingV,
                            ),
                    )
                    snackbar?.actionLabel?.let { label ->
                        Text(
                            text = label,
                            style = visual.type.label,
                            color = colors.primary,
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) {
                                    snackbar?.onAction?.invoke()
                                    overlay.dismiss()
                                }
                                .padding(
                                    end = visual.density.screenPadding,
                                    top = visual.density.rowPaddingV,
                                    bottom = visual.density.rowPaddingV,
                                ),
                        )
                    }
                }
            }
        }
    }
}
