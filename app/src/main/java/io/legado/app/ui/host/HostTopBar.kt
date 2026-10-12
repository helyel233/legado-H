package io.legado.app.ui.host

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel

/**
 * P1-1 顶栏（宿主独占）。
 *
 * 页面只声明 "我要大标题折叠顶栏"，这里决定：多高、什么材质、标题什么字号、
 * 滚动时怎么折叠、操作按钮什么形状 —— 页面完全不知道这些。
 *
 * 折叠行为：未滚动时大标题 + 副标题（材质 E0 平铺，融进背景）；
 * 滚动后收成小标题 + 材质 E2（浮起，压住内容）。
 */
@Composable
fun HostTopBar(
    title: DestTitle,
    subtitle: DestTitle?,
    mode: TopBarMode,
    scrolled: Boolean,
    showBack: Boolean,
    actions: List<ChromeAction>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 侧栏形态下由宿主自动注入菜单按钮（导航形态是宿主的事，页面不声明） */
    showSidebarHandle: Boolean = false,
    onOpenSidebar: () -> Unit = {},
) {
    if (mode == TopBarMode.NONE) return

    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val type = visual.type
    val motion = visual.motion

    val collapsed = scrolled || mode == TopBarMode.TITLE || mode == TopBarMode.SEARCH
    val barHeight by animateDpAsState(
        targetValue = if (collapsed) density.topBarHeight else density.topBarHeight + 48.dp,
        animationSpec = motion.mediumSpec(),
        label = "topBarHeight",
    )
    val titleScale by animateFloatAsState(
        targetValue = if (collapsed) 0.72f else 1f,
        animationSpec = motion.mediumSpec(),
        label = "titleScale",
    )

    val level = if (collapsed) visual.topBarLevel else MaterialLevel.FLAT

    io.legado.app.uikit.v5.runtime.AppSurface(
        backgroundBlur = true,
        level = level,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .padding(horizontal = density.screenPadding)
                // 大标题态给状态栏留一点呼吸空间，避免标题贴着时间/信号
                .padding(top = if (collapsed) 0.dp else 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSidebarHandle) {
                    IconButton(onClick = onOpenSidebar, modifier = Modifier.size(density.iconButtonSize)) {
                        Icon(HostIcons.Menu, contentDescription = "打开侧栏", tint = colors.onSurface)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (showBack) {
                    IconButton(onClick = onBack, modifier = Modifier.size(density.iconButtonSize)) {
                        Icon(HostIcons.Back, contentDescription = "返回", tint = colors.onSurface)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.resolveText(),
                        style = if (collapsed) type.title else type.display,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = if (collapsed) {
                            type.title.fontSize
                        } else {
                            type.display.fontSize * titleScale
                        },
                    )
                    if (!collapsed && subtitle != null) {
                        Text(
                            text = subtitle.resolveText(),
                            style = type.caption,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions.forEach { action ->
                    IconButton(
                        onClick = action.onClick,
                        modifier = Modifier.size(density.iconButtonSize),
                    ) {
                        Icon(
                            painter = action.icon.rememberPainter(),
                            contentDescription = action.contentDescription.resolveText(),
                            tint = colors.onSurface,
                            modifier = Modifier.size(density.iconSize),
                        )
                    }
                }
            }
        }
    }
}

/** 标题取值：P1 阶段只支持直接文本；资源 id 在 P4 搬迁业务页时接上。 */
@Composable
internal fun DestTitle.resolveText(): String = when (this) {
    is DestTitle.Text -> value
    is DestTitle.Res -> androidx.compose.ui.res.stringResource(id, *args.toTypedArray())
}

@Composable
internal fun io.legado.app.uikit.v5.contract.DestIcon.rememberPainter(): Painter =
    when (this) {
        is io.legado.app.uikit.v5.contract.DestIcon.Res ->
            androidx.compose.ui.res.painterResource(id)
        is io.legado.app.uikit.v5.contract.DestIcon.Vector ->
            rememberVectorPainter(imageVector)
    }

/** 顶栏折叠时给内容区的顶部留白（与 HostTopBar 同参数动画，两者同步伸缩）。 */
@Composable
internal fun hostTopBarHeight(mode: TopBarMode, scrolled: Boolean): androidx.compose.ui.unit.Dp {
    val visual = LocalVisualSpec.current
    val collapsed = scrolled || mode == TopBarMode.TITLE || mode == TopBarMode.SEARCH
    val target = if (mode == TopBarMode.NONE) 0.dp
    else if (collapsed) visual.density.topBarHeight
    else visual.density.topBarHeight + 48.dp
    val animated by animateDpAsState(
        targetValue = target,
        animationSpec = visual.motion.mediumSpec(),
        label = "topBarSpacer",
    )
    return animated
}
