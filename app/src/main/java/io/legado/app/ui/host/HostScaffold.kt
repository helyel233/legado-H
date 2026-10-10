package io.legado.app.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.contract.HostScope
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.NavPosition

/**
 * P1-1 宿主骨架的组合根。
 *
 * 这一层把宿主该干的七件事拼起来：背景、顶栏、导航、内容、弹层、侧栏、调试。
 * **没有任何业务知识** —— 它只知道"现在显示哪个 AppDestination"。
 *
 * 页面通过 [HostScope.create] 拿到窄接口，碰不到这里的任何外观参数。
 */
@Composable
fun HostScaffold(
    state: HostState,
    overlay: HostOverlay,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current

    val registry = state.registry
    val content = remember(state.currentId) {
        registry.byId(state.currentId)?.factory?.invoke()
    }
    val chrome: ScreenChrome = remember(state.currentId) { content?.chrome() ?: ScreenChrome() }
    val scope = remember(visual, colors) { HostScope.create(visual, colors) }

    val scrollState = rememberScrollState()
    val scrolled by remember { derivedStateOf { scrollState.value > 24 } }
    LaunchedEffect(scrolled) { state.onScrollChanged(scrolled) }

    val tabs = remember(registry, state) { registry.visibleTabs() }
    val sideGroups = remember(registry) { registry.sideItems() }
    val navIsTop = visual.navPosition == NavPosition.TOP

    Box(modifier = modifier.fillMaxSize()) {
        // L0 背景
        HostBackgroundLayer()

        // L1 容器 + L2 内容
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            HostTopBar(
                title = chrome.title,
                subtitle = chrome.subtitle,
                mode = chrome.topBar,
                scrolled = scrolled,
                showBack = chrome.showBack,
                actions = chrome.actions,
                onBack = { state.navigate("shelf") },
            )
            if (navIsTop) {
                HostNavBar(
                    items = tabs,
                    currentId = state.currentId,
                    navPosition = visual.navPosition,
                    navVisibility = visual.navVisibility,
                    scrolled = scrolled,
                    onSelect = { state.navigate(it) },
                    onOpenSidebar = { state.toggleSidebar() },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(bottom = if (chrome.reserveNavSpace) hostBottomNavReserve else 0.dp),
                ) {
                    content?.Content(scope)
                }
            }
        }

        // 底部导航（底栏 / 悬浮胶囊 / 侧栏把手）
        if (!navIsTop) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding(),
            ) {
                HostNavBar(
                    items = tabs,
                    currentId = state.currentId,
                    navPosition = visual.navPosition,
                    navVisibility = visual.navVisibility,
                    scrolled = scrolled,
                    onSelect = { state.navigate(it) },
                    onOpenSidebar = { state.toggleSidebar() },
                    modifier = Modifier.padding(bottom = 0.dp),
                )
            }
        }

        // 侧栏（覆盖全屏，含遮罩）
        if (visual.navPosition == NavPosition.SIDE) {
            HostSideBar(
                groups = sideGroups,
                currentId = state.currentId,
                open = state.sidebarOpen,
                onSelect = { state.navigate(it) },
                onDismiss = { state.closeSidebar() },
            )
        }

        // 弹层宿主
        HostOverlayLayer(overlay = overlay)

        // 调参面板（仅 debug 构建）
        HostDebugPanel()
    }
}
