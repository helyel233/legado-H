package io.legado.app.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.legado.app.uikit.v5.contract.HostScope
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.NavPosition

/**
 * P1-1 / P2-1 宿主骨架的组合根。
 *
 * P2-1 真玻璃架构：屏幕分两层 ——
 * ① **采样源层**：背景 + 滚动内容，打 `hazeSource` 标记；
 * ② **玻璃层**：顶栏 / 底栏 / 悬浮胶囊 / 侧栏，浮在采样源之上，
 *    通过 AppSurface 的 hazeEffect 实时模糊身后内容 ——
 *    列表从底栏下面滚过时会被真实糊开，这是"现代化"的核心观感。
 *
 * 页面通过 [HostScope.create] 拿到窄接口，碰不到这里的任何外观参数。
 */
@Composable
fun HostScaffold(
    state: HostState,
    overlay: HostOverlay,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current

    val registry = state.registry
    val content = remember(state.currentId, state.currentArgs) {
        registry.byId(state.currentId)?.factory?.invoke()
    }
    val chrome: ScreenChrome = remember(state.currentId) { content?.chrome() ?: ScreenChrome() }
    val scope = remember(visual, colors, state.currentArgs) {
        HostScope.create(visual, colors, navigate = { id -> state.navigate(id) }, arguments = state.currentArgs)
    }

    val scrollState = rememberScrollState()
    val scrolled by remember { derivedStateOf { scrollState.value > 24 } }
    LaunchedEffect(scrolled) { state.onScrollChanged(scrolled) }

    // 系统返回键：先弹宿主返回栈；页面自己的 BackHandler（如退出选择模式）
    // 组合顺序在本句之前注册者优先级更高？——Compose 后注册者优先，
    // 宿主 BackHandler 写在内容组合之前，页面 BackHandler 天然覆盖它。
    androidx.activity.compose.BackHandler(enabled = state.canBack) { state.back() }

    val tabs = remember(registry, state) { registry.visibleTabs() }
    val sideGroups = remember(registry) { registry.sideItems() }
    val navIsTop = visual.navPosition == NavPosition.TOP

    Box(modifier = modifier.fillMaxSize()) {
        // ---- ① 采样源层：背景 + 滚动内容 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState),
        ) {
            HostBackgroundLayer()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                // 给玻璃顶栏（和顶栏标签形态的导航）让位
                Spacer(modifier = Modifier.height(hostTopBarHeight(chrome.topBar, scrolled)))
                if (navIsTop) {
                    Spacer(modifier = Modifier.height(visual.density.topBarHeight))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (chrome.contentOwnsScroll) {
                        // 页面自带滚动容器（LazyColumn 等），宿主只给满高约束
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    bottom = if (chrome.reserveNavSpace) hostBottomNavReserve else 0.dp,
                                ),
                        ) {
                            content?.Content(scope)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(
                                    bottom = if (chrome.reserveNavSpace) hostBottomNavReserve else 0.dp,
                                ),
                        ) {
                            content?.Content(scope)
                        }
                    }
                }
            }
        }

        // ---- ② 玻璃层：顶栏（+ 顶栏标签形态的导航）----
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        ) {
            HostTopBar(
                title = chrome.title,
                subtitle = chrome.subtitle,
                mode = chrome.topBar,
                scrolled = scrolled,
                showBack = chrome.showBack,
                actions = chrome.actions,
                onBack = { state.back() },
                showSidebarHandle = visual.navPosition == NavPosition.SIDE,
                onOpenSidebar = { state.toggleSidebar() },
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
        }

        // ---- ② 玻璃层：底栏 / 悬浮胶囊 / 侧栏把手 ----
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
                )
            }
        }

        // ---- 侧栏（玻璃，覆盖全屏含遮罩） ----
        if (visual.navPosition == NavPosition.SIDE) {
            HostSideBar(
                groups = sideGroups,
                currentId = state.currentId,
                open = state.sidebarOpen,
                onSelect = { state.navigate(it) },
                onDismiss = { state.closeSidebar() },
            )
        }

        // ---- 弹层宿主 ----
        HostOverlayLayer(overlay = overlay)

        // ---- 调参面板（仅 debug 构建） ----
        HostDebugPanel(state = state)
    }
}
