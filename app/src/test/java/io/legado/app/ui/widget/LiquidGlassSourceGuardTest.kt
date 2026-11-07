package io.legado.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态玻璃「采样源不得是宿主祖先」守卫的纯 JVM 单测。
 *
 * 背景：底层库 `com.qmdeve.liquidglass` 每帧在采样源的 pre-draw 里
 * `beginRecording` → `source.draw(canvas)` → `endRecording()`。若采样源是宿主祖先，
 * `source.draw()` 会把宿主再画一遍，`draw()` 里又 `drawRenderNode(node)`，
 * 于是 RenderNode 在录制中被重入录制，hwui 直接 abort（native crash，无 Java 堆栈）。
 *
 * 1.25.8 把发现页行玻璃绑到了列表 RecyclerView 自身（玻璃视图就在该 RecyclerView
 * 的 item 里），切到发现页即闪退。这里用假 parent 链锁住判定逻辑，防止回归。
 *
 * 注意：生产实现用 `===`（引用相等）而不是 `equals`——View 树的父子关系就是同一个
 * 对象实例。因此本测试用 [Node] 这类默认按引用区分的节点，而不是字符串（字符串字面量
 * 会被 JVM 驻留，容易掩盖引用判等写错）。
 */
class LiquidGlassSourceGuardTest {

    /** 假视图节点：按引用判等，语义与真实 `View` 一致。 */
    private class Node(val label: String) {
        override fun toString(): String = "Node($label)"
    }

    /** 无父节点的链：链顶即 null，用于验证遍历终止性。 */
    private val noParent: (Any) -> Any? = { null }

    /** 用「子 → 父」映射构造 [parentOf]；[Node] 不在映射里即视为链顶。 */
    private fun parentsOf(vararg pairs: Pair<Node, Node?>): (Any) -> Any? {
        val map = pairs.toMap()
        return { node -> map[node] }
    }

    @Test
    fun directAncestorIsRejected() {
        // 宿主 leaf 的直接父节点就是采样源 mid → 拒绝
        val root = Node("root")
        val mid = Node("mid")
        val leaf = Node("leaf")
        assertTrue(
            selfOrAncestorContains(
                self = leaf,
                target = mid,
                parentOf = parentsOf(leaf to mid, mid to root, root to null)
            )
        )
    }

    @Test
    fun transitiveAncestorIsRejected() {
        // 宿主 leaf 的祖先 root（跨两层）同样是非法采样源
        val root = Node("root")
        val mid = Node("mid")
        val leaf = Node("leaf")
        assertTrue(
            selfOrAncestorContains(
                self = leaf,
                target = root,
                parentOf = parentsOf(leaf to mid, mid to root, root to null)
            )
        )
    }

    @Test
    fun selfAsSourceIsRejected() {
        // 采样源绑成自己：target.draw() 就是 this.draw()，同样重入录制
        val mid = Node("mid")
        val leaf = Node("leaf")
        assertTrue(
            selfOrAncestorContains(
                self = leaf,
                target = leaf,
                parentOf = parentsOf(leaf to mid, mid to null)
            )
        )
    }

    @Test
    fun siblingOrDescendantSourceIsAllowed() {
        // 兄弟节点：底栏把 contentContainer（与玻璃视图同为 root 的孩子）当采样源即属此列
        val root = Node("root")
        val glass = Node("glass")
        val content = Node("content")
        assertFalse(
            selfOrAncestorContains(
                self = glass,
                target = content,
                parentOf = parentsOf(root to null, glass to root, content to root)
            )
        )
        // 采样源在宿主内部（子孙）：祖先链上永远不会遇到它
        assertFalse(
            selfOrAncestorContains(
                self = root,
                target = glass,
                parentOf = parentsOf(root to null, glass to root)
            )
        )
    }

    @Test
    fun nullSourceAndBrokenChainTerminate() {
        // target 为 null（解绑）永远不算祖先
        assertFalse(selfOrAncestorContains(self = Node("c"), target = null, parentOf = noParent))
        // 链顶即 null：不会 NPE，也不会误报
        assertFalse(
            selfOrAncestorContains(
                self = Node("c"),
                target = Node("a"),
                parentOf = noParent
            )
        )
    }

    @Test
    fun cyclicChainTerminatesWithinDepthBudget() {
        // 脏数据造成的环（a → b → a）不能死循环；环里没有 target 时返回 false
        val a = Node("a")
        val b = Node("b")
        val cycle = parentsOf(a to b, b to a)
        assertFalse(
            selfOrAncestorContains(
                self = a,
                target = Node("missing"),
                parentOf = cycle
            )
        )
        // 环里含 target 时仍能命中
        assertTrue(
            selfOrAncestorContains(
                self = a,
                target = b,
                parentOf = cycle
            )
        )
    }

    @Test
    fun depthBudgetIsGenerousEnoughForRealViewTrees() {
        // 真实 View 树深度一般 < 40，256 的上限不会误伤合法层级：
        // 手工构造 60 层链，最顶端的节点仍应被识别为祖先。
        val depth = 60
        val nodes = Array(depth) { Node("n$it") }
        val map = HashMap<Node, Node?>()
        for (i in 0 until depth) {
            map[nodes[i]] = if (i == 0) null else nodes[i - 1]
        }
        assertTrue(
            selfOrAncestorContains(
                self = nodes[depth - 1],
                target = nodes[0],
                parentOf = { node -> map[node] }
            )
        )
        assertTrue(DEFAULT_MAX_ANCESTOR_DEPTH >= depth * 2)
    }
}
