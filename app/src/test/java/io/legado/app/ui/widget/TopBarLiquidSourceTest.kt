package io.legado.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏液态玻璃「采样源必须是宿主非祖先」不变量测试（T04 验收项）。
 *
 * 背景：1.25.10 才开始真正启用顶栏液态玻璃（1.25.7~1.25.9 两个宿主都传 `null`，
 * 玻璃层恒 `GONE`）。顶栏的视图层级是
 * `content_container → view_pager_main → fragment_explore → ll_modern_discovery → top_bar`，
 * 因此：
 * - 底栏那种「绑 `content_container`」的写法**在顶栏上会崩**（祖先源）；
 * - 文档里一度写过的 `swipe_refresh_layout` 同样是祖先（`top_bar` 在它内部的
 *   `ll_modern_discovery` 里），一样会崩；
 * - 唯一合法的是**兄弟子树**：发现页 `fl_discover_books`、书架页 `view_pager_bookshelf`。
 *
 * 这个不变量无法靠编译期保证（参数类型都是 `ViewGroup`），所以用假 parent 链锁住。
 * 生产路径上 `MainTopBarView.setBackdropBlur` 会拿同一份守卫再拦一次（纵深防御）：
 * 即使将来某个宿主又传错，也不会把祖先源喂给底层库。
 */
class TopBarLiquidSourceTest {

    /** 假视图节点：按引用判等，与真实 `View` 一致（不用字符串，避免被 JVM 驻留）。 */
    private class Node(val label: String) {
        override fun toString(): String = "Node($label)"
    }

    private fun parentsOf(vararg pairs: Pair<Node, Node?>): (Any) -> Any? {
        val map = pairs.toMap()
        return { node -> map[node] }
    }

    /**
     * 真实层级复刻：`content_container → view_pager_main → fragment_explore
     * → ll_modern_discovery → {fl_discover_books, top_bar}`。
     */
    private inner class Tree {
        val contentContainer = Node("content_container")
        val viewPagerMain = Node("view_pager_main")
        val fragmentExplore = Node("fragment_explore")
        val swipeRefreshLayout = Node("swipe_refresh_layout")
        val innerFrame = Node("inner_frame")
        val llModernDiscovery = Node("ll_modern_discovery")
        val flDiscoverBooks = Node("fl_discover_books")
        val rvDiscoverBooks = Node("rv_discover_books")
        val topBar = Node("top_bar")

        val parentOf: (Any) -> Any? = parentsOf(
            topBar to llModernDiscovery,
            flDiscoverBooks to llModernDiscovery,
            rvDiscoverBooks to flDiscoverBooks,
            llModernDiscovery to innerFrame,
            innerFrame to swipeRefreshLayout,
            swipeRefreshLayout to fragmentExplore,
            fragmentExplore to viewPagerMain,
            viewPagerMain to contentContainer,
            contentContainer to null
        )
    }

    // ------------------------------------------------------- 祖孙关系必须被拒

    /** 底栏写法（绑 Activity 的 content_container）在顶栏上必须被拒绝。 */
    @Test
    fun contentContainerIsRejectedForTopBar() {
        val tree = Tree()
        assertFalse(topBarLiquidSourceAllowed(tree.topBar, tree.contentContainer, tree.parentOf))
    }

    /** 文档里一度写过的 swipe_refresh_layout 同样是祖先，必须被拒绝（否则就是 1.25.8 重演）。 */
    @Test
    fun swipeRefreshLayoutIsRejectedForTopBar() {
        val tree = Tree()
        assertFalse(
            topBarLiquidSourceAllowed(tree.topBar, tree.swipeRefreshLayout, tree.parentOf)
        )
        // 直接父容器也一样
        assertFalse(
            topBarLiquidSourceAllowed(tree.topBar, tree.llModernDiscovery, tree.parentOf)
        )
        assertFalse(topBarLiquidSourceAllowed(tree.topBar, tree.innerFrame, tree.parentOf))
        assertFalse(topBarLiquidSourceAllowed(tree.topBar, tree.fragmentExplore, tree.parentOf))
        assertFalse(topBarLiquidSourceAllowed(tree.topBar, tree.viewPagerMain, tree.parentOf))
    }

    /** 自身也是非法源（`target.draw()` 就是 `this.draw()`，同样重入录制）。 */
    @Test
    fun selfIsRejected() {
        val tree = Tree()
        assertFalse(topBarLiquidSourceAllowed(tree.topBar, tree.topBar, tree.parentOf))
    }

    /** null 采样源（关闭玻璃）不算非法，但也不能算「可用」，由 active 判定另判。 */
    @Test
    fun nullSourceIsNotAllowed() {
        val tree = Tree()
        assertFalse(topBarLiquidSourceAllowed(tree.topBar, null, tree.parentOf))
    }

    // ------------------------------------------------------- 兄弟子树必须放行

    /** 发现页 fl_discover_books：兄弟子树 → 放行。 */
    @Test
    fun discoverBooksContainerIsAllowed() {
        val tree = Tree()
        assertTrue(
            topBarLiquidSourceAllowed(tree.topBar, tree.flDiscoverBooks, tree.parentOf)
        )
    }

    /** 兄弟的子孙（rv_discover_books）同样安全：祖先链上不会遇到它。 */
    @Test
    fun siblingDescendantIsAllowed() {
        val tree = Tree()
        assertTrue(
            topBarLiquidSourceAllowed(tree.topBar, tree.rvDiscoverBooks, tree.parentOf)
        )
    }

    /** 书架页 view_pager_bookshelf 与 top_bar 平级 → 放行。 */
    @Test
    fun bookshelfViewPagerIsAllowed() {
        val root = Node("root")
        val topBar = Node("top_bar")
        val viewPager = Node("view_pager_bookshelf")
        val parentOf = parentsOf(topBar to root, viewPager to root, root to null)
        assertTrue(topBarLiquidSourceAllowed(topBar, viewPager, parentOf))
        // 反过来：书架页若误传 content_container 这类祖先，仍被拒
        assertFalse(topBarLiquidSourceAllowed(topBar, root, parentOf))
    }

    // ------------------------------------------------------- 启用条件矩阵

    /** 只有「覆盖式布局 + API≥33 + 有合法源」三者同时成立才启用。 */
    @Test
    fun enableMatrixRequiresAllThreeConditions() {
        assertTrue(
            shouldEnableTopBarBackdrop(overlayContent = true, sdkInt = 33, sourceAvailable = true)
        )
        // classic 布局：顶栏在列表上方，背后没内容 → 不启用
        assertFalse(
            shouldEnableTopBarBackdrop(overlayContent = false, sdkInt = 33, sourceAvailable = true)
        )
        // 低版本：没有 RuntimeShader → 由宿主的非覆盖布局兜底
        listOf(21, 26, 30, 31, 32).forEach { sdk ->
            assertFalse(
                "sdk=$sdk 必须关闭",
                shouldEnableTopBarBackdrop(
                    overlayContent = true, sdkInt = sdk, sourceAvailable = true
                )
            )
        }
        // 没有合法源 → 不启用
        assertFalse(
            shouldEnableTopBarBackdrop(overlayContent = true, sdkInt = 33, sourceAvailable = false)
        )
        // API 33 以上都启用
        listOf(34, 35, 36).forEach { sdk ->
            assertTrue(
                shouldEnableTopBarBackdrop(
                    overlayContent = true, sdkInt = sdk, sourceAvailable = true
                )
            )
        }
    }

    /** API 门槛常量必须与 `supportsBackdropBlur()`（TIRAMISU=33）一致。 */
    @Test
    fun minSdkMatchesTiramisu() {
        assertEquals(33, TOP_BAR_BACKDROP_MIN_SDK)
        // 32 及以下必被拒、33 恰好放行（边界不能差一）
        assertFalse(
            shouldEnableTopBarBackdrop(overlayContent = true, sdkInt = 32, sourceAvailable = true)
        )
        assertTrue(
            shouldEnableTopBarBackdrop(overlayContent = true, sdkInt = 33, sourceAvailable = true)
        )
    }

}
