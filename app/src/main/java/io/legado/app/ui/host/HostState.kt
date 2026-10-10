package io.legado.app.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.uikit.v5.registry.DestinationRegistry

/**
 * P1-1 宿主状态。
 *
 * 只管"现在在哪一页、侧栏开没开、滚没滚"，不管任何外观 ——
 * 外观全部从 LocalVisualSpec / ThemeResolver 读。
 */
class HostState(
    val registry: DestinationRegistry,
    initialId: String,
) {
    /** 当前页 id */
    var currentId: String by mutableStateOf(initialId)
        private set

    /** 侧栏展开状态（仅 side 形态有效） */
    var sidebarOpen: Boolean by mutableStateOf(false)
        private set

    /** 内容区是否已滚动（顶栏折叠、底栏滚动隐藏都看它） */
    var scrolled: Boolean by mutableStateOf(false)
        private set

    /** 页面切换时是否使用容器变换（列表→详情） */
    var lastTransitionWasPop: Boolean by mutableStateOf(false)
        private set

    fun navigate(id: String) {
        if (id == currentId) return
        lastTransitionWasPop = false
        currentId = id
        sidebarOpen = false
        scrolled = false
    }

    fun toggleSidebar() {
        sidebarOpen = !sidebarOpen
    }

    fun closeSidebar() {
        sidebarOpen = false
    }

    fun onScrollChanged(scrolled: Boolean) {
        this.scrolled = scrolled
    }
}
