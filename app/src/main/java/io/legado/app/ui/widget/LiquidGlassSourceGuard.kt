package io.legado.app.ui.widget

/**
 * [StableLiquidGlassView] 的采样源合法性判定（纯 JVM 函数，不引用任何 Android 类型）。
 *
 * ## 为什么需要这条守卫
 *
 * 底层库 `com.qmdeve.liquidglass`（core 1.0.3）的机制是：
 * 1. `LiquidGlass.init(source)` 把 `OnPreDrawListener` 注册到 **source 的 ViewTreeObserver**；
 * 2. 每帧 pre-draw 里 `LiquidGlassimpl.record()` 执行
 *    `node.beginRecording(w, h)` → `target.draw(canvas)` → `node.endRecording()`，
 *    即把**整个采样源子树**重绘进一颗 RenderNode；
 * 3. `LiquidGlassimpl.draw()` 只做 `canvas.drawRenderNode(node)`。
 *
 * 因此采样源必须是与宿主**平级的背景节点**（底栏绑 `contentContainer`、玻璃视图在
 * `bottomControls` 里，互为兄弟）。一旦采样源是宿主的**祖先**（典型误用：把
 * `RecyclerView` 自身当采样源、而玻璃视图又在该 RecyclerView 的 item 里），
 * `target.draw()` 会再次绘制玻璃视图 → `LiquidGlass.onDraw` → `drawRenderNode(node)`，
 * 而此刻 node 正处于 recording 中，形成 **RenderNode 重入录制**。hwui 层直接 abort
 * （native crash，Java 侧无堆栈，表现为「切到页面即闪退、日志里查不到崩溃堆栈」）。
 *
 * 即使不崩，每行一个采样源 = 每帧 N 次整树录制，性能上也不成立。
 *
 * ## 本文件的职责
 *
 * 把「沿 parent 链向上走能否遇到 source」这件事从 Android View 上剥离成
 * [selfOrAncestorContains]，于是拒绝绑定的逻辑可以被纯 JVM 单测覆盖，
 * 不需要 Robolectric 或真机。
 */

/**
 * 从 [self] 出发，沿 [parentOf] 给出的链向上遍历（**包含 [self] 自身**），
 * 判断链上是否出现 [target]。
 *
 * 语义细节：
 * - 包含 self 自身，因此 `self === target` 也返回 true —— 采样源绑成自己同样会让
 *   `target.draw()` 重入录制，必须一并拒绝；
 * - [parentOf] 返回 null（或链出现环）时终止；环由 [maxDepth] 兜底，避免脏链表死循环；
 * - 全部用 `===` 判等：这里要的是「同一个对象」，不是 `equals`。
 *
 * @param self 起点（宿主视图本身）
 * @param target 待判定的采样源
 * @param parentOf 取父节点；返回 null 表示到达链顶
 * @param maxDepth 遍历上限，防御脏数据造成的环
 * @return true 表示 [target] 是 [self] 自身或它的祖先，采样源不可用
 */
internal fun selfOrAncestorContains(
    self: Any?,
    target: Any?,
    parentOf: (Any) -> Any?,
    maxDepth: Int = DEFAULT_MAX_ANCESTOR_DEPTH
): Boolean {
    if (target == null) return false
    var current: Any? = self
    var depth = 0
    while (current != null && depth <= maxDepth) {
        if (current === target) return true
        current = parentOf(current)
        depth++
    }
    return false
}

/**
 * parent 链遍历的默认深度上限。真实 View 树远达不到这个量级（一般 < 40），
 * 取 256 既不会误伤合法层级，又能让环状脏链在有限步内结束。
 */
internal const val DEFAULT_MAX_ANCESTOR_DEPTH: Int = 256
