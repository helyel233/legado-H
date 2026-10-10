package io.legado.app.uikit.v5.contract

import androidx.compose.ui.graphics.Color

/**
 * P0-2 读书页接入接口（docs/ui-v5-skeleton-first.md 5.2）。
 *
 * 设计原则：
 * 1. **读书页主动来问，主题体系不主动灌** —— 本轮默认 [ReaderThemeMode.AUTONOMOUS]，
 *    即读书页行为零变化（冻结红线）。
 * 2. 返回值是"名字 → 值"，读书页自己决定怎么用，不被迫改渲染方式。
 * 3. 未来的读书工程只需在设置里加一个开关改 [mode]，即可接线，
 *    不需要改方案、不需要改骨架。
 */
object ReaderBridge {

    @Volatile
    var mode: ReaderThemeMode = ReaderThemeMode.AUTONOMOUS
        private set

    /**
     * 由读书工程调用。切换后读书页下次取值时生效。
     */
    fun setMode(mode: ReaderThemeMode) {
        this.mode = mode
    }

    /**
     * 读书页调用，询问"我现在该用什么"。
     * @param provider 由主题体系提供的一整套值；仅在需要时才计算，自治模式下不调用。
     * @return null 表示"你继续保持自治"（本轮默认，等于冻结）
     */
    fun resolveForReader(provider: () -> ReaderTheme): ReaderTheme? =
        if (mode == ReaderThemeMode.AUTONOMOUS) null else provider()
}

enum class ReaderThemeMode(val id: String, val displayName: String) {
    /** 完全自治：读书页一切照旧（本轮默认） */
    AUTONOMOUS("autonomous", "不跟随"),

    /** 只跟字体与背景：不影响排版与翻页性能 */
    FONTS_AND_BACKGROUND("fontsAndBackground", "只跟字体与背景"),

    /** 全部跟随：颜色、字体、背景、纹理 */
    FULL("full", "全部跟随"),
    ;

    companion object {
        fun byId(id: String): ReaderThemeMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTONOMOUS
    }
}

/**
 * 交给读书页的一整套值。
 *
 * **刻意不含**：形状、玻璃、动效、密度 —— 这些会影响排版与翻页性能，
 * 即便"全部跟随"也不给，避免读书工程被性能倒逼返工。
 */
data class ReaderTheme(
    val background: Color,
    val text: Color,
    val accent: Color,
    val divider: Color,
    /** 界面字体绝对路径，空 = 沿用读书页自己的字体设置 */
    val uiFontPath: String,
    /** 背景图绝对路径，null = 沿用读书页自己的背景 */
    val backgroundImagePath: String?,
    /** 纹理叠层（纸张/噪点），null = 不加 */
    val texturePath: String?,
    val textureAlpha: Float,
)
