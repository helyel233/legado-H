package io.legado.app.ui.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.contract.AppDestination
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.NavPosition
import io.legado.app.uikit.v5.token.NavVisibility
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P1-3 主界面导航（四种形态，菜单全部来自注册表）。
 *
 * **关键**：四种形态共用同一份 `items`（注册表给的），
 * 不存在"侧栏是底栏菜单的镜像"这种两处真相 —— 生成逻辑只有这一份。
 * 所以切换形态时功能入口一个都不会少。
 */
@Composable
fun HostNavBar(
    items: List<AppDestination>,
    currentId: String,
    navPosition: NavPosition,
    navVisibility: NavVisibility,
    scrolled: Boolean,
    onSelect: (String) -> Unit,
    onOpenSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hidden = scrolled && navVisibility == NavVisibility.SCROLL_HIDE

    when (navPosition) {
        NavPosition.BOTTOM -> BottomBar(
            items = items, currentId = currentId, hidden = hidden,
            onSelect = onSelect, modifier = modifier,
        )
        NavPosition.FLOAT -> FloatingDock(
            items = items, currentId = currentId, hidden = hidden,
            onSelect = onSelect, modifier = modifier,
        )
        NavPosition.TOP -> TopTabs(
            items = items, currentId = currentId, hidden = hidden,
            onSelect = onSelect, modifier = modifier,
        )
        NavPosition.SIDE -> SideHandle(onOpenSidebar = onOpenSidebar, modifier = modifier)
    }
}

// ---------------------------------------------------------------- 底栏

@Composable
private fun BottomBar(
    items: List<AppDestination>,
    currentId: String,
    hidden: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    AnimatedVisibility(
        visible = !hidden,
        enter = slideInVertically(visual.motion.mediumSpec()) { it },
        exit = slideOutVertically(visual.motion.mediumSpec()) { it },
    ) {
        AppSurface(
            level = visual.bottomBarLevel,
            modifier = modifier
                .padding(horizontal = density.screenPadding / 2)
                .padding(bottom = density.screenPadding / 2)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.topBarHeight)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { dest ->
                    NavItem(
                        dest = dest,
                        selected = dest.id == currentId,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 悬浮胶囊

@Composable
private fun FloatingDock(
    items: List<AppDestination>,
    currentId: String,
    hidden: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    AnimatedVisibility(
        visible = !hidden,
        enter = slideInVertically(visual.motion.mediumSpec()) { it },
        exit = slideOutVertically(visual.motion.mediumSpec()) { it },
    ) {
        AppSurface(
            level = visual.bottomBarLevel,
            modifier = modifier
                .padding(horizontal = density.screenPadding)
                .padding(bottom = density.screenPadding)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.topBarHeight)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { dest ->
                    NavItem(
                        dest = dest,
                        selected = dest.id == currentId,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 顶栏标签

@Composable
private fun TopTabs(
    items: List<AppDestination>,
    currentId: String,
    hidden: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    Row(
        modifier = modifier
            .padding(horizontal = density.screenPadding)
            .fillMaxWidth()
            .height(density.topBarHeight - 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { dest ->
            val selected = dest.id == currentId
            val colors = LocalSemanticColors.current
            val bg = if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(visual.shape(ShapeKind.TAG))
                    .background(bg)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(dest.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = dest.title.resolveText(),
                    style = visual.type.label,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 侧栏把手

@Composable
private fun SideHandle(
    onOpenSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    AppSurface(
        level = visual.bottomBarLevel,
        modifier = modifier
            .padding(start = density.screenPadding, bottom = density.screenPadding)
            .size(density.iconButtonSize + 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(density.iconButtonSize + 8.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onOpenSidebar,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HostIcons.Menu,
                contentDescription = "打开侧栏",
                tint = colors.onSurface,
                modifier = Modifier.size(density.iconSize),
            )
        }
    }
}

// ---------------------------------------------------------------- 单项

/**
 * 单个导航项。选中态做**形变 + 微弹**（M3 Expressive 风格），
 * 不是简单换张图 —— 这是"现代化"最容易被感知的细节之一。
 */
@Composable
private fun NavItem(
    dest: AppDestination,
    selected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val targetScale = if (selected && visual.icon.selectedMorph) 1.16f else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = visual.motion.springSpec(),
        label = "navIconMorph",
    )
    val showLabel = visual.density.factor >= 0.95f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onSelect(dest.id) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Icon(
                painter = dest.icon?.let { it.rememberPainter() }
                    ?: rememberVectorPainter(HostIcons.Shelf),
                contentDescription = dest.title.resolveText(),
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier
                    .size(visual.density.iconSize)
                    .scale(scale)
                    .offset(y = if (selected) (-1).dp else 0.dp),
            )
            // 选中指示器：主色小条，随选中出现
            if (selected) {
                Box(
                    modifier = Modifier
                        .offset(y = (-4).dp)
                        .size(width = 18.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.primary),
                )
            }
        }
        if (showLabel) {
            Text(
                text = dest.title.resolveText(),
                style = visual.type.caption,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
