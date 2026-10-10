package io.legado.app.uikit.v5.resolve

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.uikit.v5.token.VisualSpec

/**
 * P0-4 结构取值唯一入口（docs/ui-v5-skeleton-first.md 4.2 / 手册 0.3）。
 *
 * 界面包只管非美术参数：导航形态、形状族、玻璃五件套、密度、动效、图标、排版、栏材质。
 * 颜色、字体、图片一个都不管 —— 那些在 [ThemeResolver]。
 *
 * **铁律**：[VisualSpec] 里没有任何按页面分的字段。所有窗口读同一份实例 ——
 * 这是"全局统一"的机制保证（方案验收第 5 条）。
 */
object LayoutResolver {

    var spec: VisualSpec by mutableStateOf(VisualSpec.DEFAULT)
        private set

    /** 每次结构变化 +1；宿主与 CompositionLocal 消费者据此重组。 */
    var revision by mutableStateOf(0)
        private set

    fun apply(next: VisualSpec) {
        spec = next
        revision++
    }

    /** 单项调整（P3 包内编辑器用）：基于当前值改一项并整体替换。 */
    fun update(block: (VisualSpec) -> VisualSpec) {
        apply(block(spec))
    }

    fun reset() = apply(VisualSpec.DEFAULT)

    val current: VisualSpec get() = spec
}
