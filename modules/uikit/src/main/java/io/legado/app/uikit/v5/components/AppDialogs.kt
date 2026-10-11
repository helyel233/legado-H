package io.legado.app.uikit.v5.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-4 弹层：AppDialog / AppBottomSheet / AppMenuSheet。
 *
 * 全部 E3 悬浮材质 —— 弹窗、抽屉、菜单与其它容器共用同一套
 * 形状族、玻璃参数，不可能出现"这个弹窗是方角那个是圆角"。
 */

/** 对话框：E3 悬浮 + 缩放入场。 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visual = LocalVisualSpec.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(visual.density.screenPadding * 2),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(visual.motion.mediumSpec()) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = visual.motion.mediumSpec(),
                ),
                exit = fadeOut(visual.motion.shortSpec()) + scaleOut(targetScale = 0.94f),
            ) {
                Box(Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )) {
                    AppSurface(level = MaterialLevel.FLOATING) { content() }
                }
            }
        }
    }
}

/** 底部抽屉：E3 悬浮 + 上滑入场。 */
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visual = LocalVisualSpec.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(visual.motion.longSpec()) { it },
                exit = slideOutVertically(visual.motion.longSpec()) { it },
            ) {
                Box(Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )) {
                    AppSurface(level = MaterialLevel.FLOATING, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) { content() }
                    }
                }
            }
        }
    }
}

/** 菜单抽屉（底部一列动作）。 */
@Composable
fun AppMenuSheet(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(visual.density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 抓手
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = visual.density.blockGap)
                    .size(width = 36.dp, height = 4.dp)
                    .background(colors.outline, visual.shape(ShapeKind.TAG)),
            )
            Text(
                text = title,
                style = visual.type.title,
                color = colors.onSurface,
                modifier = Modifier.padding(
                    start = visual.density.screenPadding / 2,
                    end = visual.density.screenPadding / 2,
                    bottom = visual.density.blockGap / 2,
                ),
            )
            actions.forEach { (label, action) ->
                AppListItem(
                    headline = label,
                    leading = {
                        Box(modifier = Modifier.size(8.dp))
                    },
                    onClick = {
                        onDismiss()
                        action()
                    },
                )
            }
        }
    }
}
