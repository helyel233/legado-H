package io.legado.app.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.uikit.v5.registry.DestinationRegistry

/**
 * P1-1 宿主状态。
 *
 * 只管"现在在哪一页、侧栏开没开、滚没滚"，不管任何外观 ——
 * 外观全部从 LocalVisualSpec / ThemeResolver 读。
 *
 * 2026-10-12（审计 #28-①）：导航从"单层 Tab 平面切换"升级为**返回栈** ——
 * - [navigate] 到 `topLevel` 目的地时清栈（Tab 语义），否则压栈（二级内容语义）；
 * - [back] 弹栈，供系统返回键（BackHandler）与顶栏返回共用；
 * - [currentArgs] 携带路由参数（如分组 id、源 url），页面经 ContentScope.arguments 读。
 */
class HostState(
    val registry: DestinationRegistry,
    initialId: String,
) {
    /** 栈条目：目的地 id + 路由参数 */
    data class Entry(val id: String, val args: Map<String, String> = emptyMap())

    /** 返回栈（不重组观测，canBack/内容都以 current* 为准） */
    private val stack = ArrayDeque<Entry>()

    /** 当前页 id */
    var currentId: String by mutableStateOf(initialId)
        private set

    /** 当前页路由参数 */
    var currentArgs: Map<String, String> by mutableStateOf(emptyMap())
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

    /** 是否可以返回（栈非空；顶层 Tab 返回交由系统默认行为） */
    val canBack: Boolean get() = stack.isNotEmpty()

    fun navigate(id: String, args: Map<String, String> = emptyMap()) {
        if (id == currentId && args == currentArgs) return
        lastTransitionWasPop = false
        if (registry.byId(id)?.topLevel == true) {
            // Tab 语义：跳一级 Tab = 回到根
            stack.clear()
        } else {
            stack.addLast(Entry(currentId, currentArgs))
        }
        currentId = id
        currentArgs = args
        sidebarOpen = false
        scrolled = false
    }

    /**
     * 返回上一页；栈空时返回 false（调用方决定兜底，如退出 Activity）。
     */
    fun back(): Boolean {
        val prev = stack.removeLastOrNull() ?: return false
        lastTransitionWasPop = true
        currentId = prev.id
        currentArgs = prev.args
        sidebarOpen = false
        scrolled = false
        return true
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
