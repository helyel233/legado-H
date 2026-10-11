package io.legado.app.ui.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.contract.AppDestination
import io.legado.app.uikit.v5.contract.SideGroup
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P1-3 侧栏（宿主独占）。
 *
 * 项来自注册表的 `sideItems()`，不是底栏菜单的镜像 ——
 * 现状那种"侧栏跟着底栏菜单变"的耦合在这里不复存在。
 */
@Composable
fun HostSideBar(
    groups: Map<SideGroup, List<AppDestination>>,
    currentId: String,
    open: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val width = (LocalConfiguration.current.screenWidthDp.dp * 0.78f).coerceAtMost(320.dp)

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(visual.motion.mediumSpec()),
            exit = fadeOut(visual.motion.mediumSpec()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss,
                    ),
            )
        }
        // 面板
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(visual.motion.longSpec()) { -it },
            exit = slideOutHorizontally(visual.motion.longSpec()) { -it },
        ) {
            AppSurface(
                level = visual.bottomBarLevel,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(
                            top = density.screenPadding,
                            bottom = density.screenPadding,
                            start = density.screenPadding,
                            end = density.screenPadding,
                        )
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "legado-H",
                        style = visual.type.headline,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "骨架先行 · 空壳验证版",
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(density.blockGap * 1.5f))

                    groups.forEach { (group, items) ->
                        if (group != SideGroup.NONE) {
                            Text(
                                text = group.displayName,
                                style = visual.type.caption,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = density.screenPadding / 2,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items.forEach { dest ->
                            SideItem(
                                dest = dest,
                                selected = dest.id == currentId,
                                onSelect = onSelect,
                            )
                        }
                        Spacer(Modifier.height(density.blockGap))
                    }
                }
            }
        }
    }
}

@Composable
private fun SideItem(
    dest: AppDestination,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val bg = if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent

    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .background(bg, visual.shape(ShapeKind.TAG))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onSelect(dest.id) }
            .padding(horizontal = density.screenPadding / 2, vertical = density.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = dest.icon?.let { it.rememberPainter() }
                ?: androidx.compose.ui.graphics.vector.rememberVectorPainter(HostIcons.Shelf),
            contentDescription = dest.title.resolveText(),
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(density.iconSize),
        )
        Spacer(Modifier.width(density.screenPadding / 2))
        Text(
            text = dest.title.resolveText(),
            style = visual.type.body,
            color = if (selected) colors.primary else colors.onSurface,
        )
    }
}
