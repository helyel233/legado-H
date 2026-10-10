package io.legado.app.uikit.v5.token

/**
 * P0-1 图标（docs/ui-v5-skeleton-first.md 3.6）。
 *
 * 全 App 线宽一致、可切换描边/填充、选中时有形变动画。
 * 线宽在显示层统一施加，不改动几百个图标文件本身。
 */
data class IconSpec(
    val style: IconStyle = IconStyle.OUTLINED,
    /** 基准线宽 dp，典型 1.6 ~ 2.2 */
    val strokeDp: Float = DEFAULT_STROKE,
    /** 底栏/侧栏选中时图标做形变 + 微弹（M3 Expressive 风格） */
    val selectedMorph: Boolean = true,
) {
    companion object {
        const val DEFAULT_STROKE = 1.8f
        val STROKE_RANGE = 1.2f..2.6f
        val DEFAULT = IconSpec()
    }
}

enum class IconStyle(val id: String, val displayName: String) {
    /** 描边（线性） */
    OUTLINED("outlined", "描边"),

    /** 填充（实心） */
    FILLED("filled", "填充"),
    ;

    companion object {
        fun byId(id: String): IconStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OUTLINED
    }
}

/**
 * 主界面骨架的四种形态（docs/ui-v5-skeleton-first.md 3.1 导航旋钮）。
 * 宿主按这个档位决定导航怎么画；菜单来自注册表的同一张表，不是各形态自己写。
 */
enum class NavPosition(val id: String, val displayName: String) {
    BOTTOM("bottom", "底栏"),
    FLOAT("float", "悬浮胶囊"),
    SIDE("side", "侧栏"),
    TOP("top", "顶栏标签"),
    ;

    companion object {
        fun byId(id: String): NavPosition =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FLOAT
    }
}

/** 导航栏可见性。 */
enum class NavVisibility(val id: String, val displayName: String) {
    /** 常驻 */
    ALWAYS("always", "常驻"),

    /** 向下滚隐藏、向上滚出现 */
    SCROLL_HIDE("scrollHide", "滚动隐藏"),
    ;

    companion object {
        fun byId(id: String): NavVisibility =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALWAYS
    }
}
