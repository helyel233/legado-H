package io.legado.app.ui.glass

/**
 * 玻璃宿主的采样域（纯 JVM，不引用任何 Android 类型，便于单测锁定）。
 *
 * 决定「能不能用壁纸采样管线」的不是页面名，而是宿主视觉后方是什么：
 *
 * - [WALLPAPER]：宿主后方就是壁纸层。要求同时满足 `BaseActivity` 背景图开关为默认开启、
 *   设备上存在可用背景图（根布局背景被置空、透出 decorView 背景图）、宿主到根布局的
 *   路径上没有不透明容器遮挡。
 * - [PANEL]：宿主位于不透明面板 / 弹层内（对话框卡片、Compose 面板、弹层 RecyclerView）。
 *   壁纸被面板遮住，采样壁纸等于画出用户根本看不到的背景，观感与周围不连续。
 * - [CONTENT]：宿主后方是封面、书页、视频帧等内容。壁纸同样不可见，需要兄弟子树采样。
 *
 * 采样域由调用方**显式声明**：运行期无法可靠判定任意外层容器是否不透明
 * （取决于布局、主题与运行时状态），把判定写成猜测只会引入假阴性。
 * 显式声明 + [glassBackdropSource] 纯函数 + 单测锁表，是唯一可确定性的防线。
 */
enum class GlassSamplingDomain {
    WALLPAPER,
    PANEL,
    CONTENT
}

/**
 * 宿主视觉后方的真实来源，由采样域唯一决定。
 *
 * [GlassSurface]（drawable 入口）只生产 [WALLPAPER_BITMAP] 这一种；另外两种意味着
 * 调用方不能走该入口：
 * - [SIBLING_SUBTREE]：改用 `StableLiquidGlassView` + `LiquidGlassSourceGuard`，
 *   采样源必须是宿主的**兄弟子树**（顶栏已验证的模式）。
 * - [OPAQUE_SHELL]：保持宿主原有的不透明背景，不做玻璃采样（入口返回 null）。
 */
enum class GlassBackdropSource {
    WALLPAPER_BITMAP,
    SIBLING_SUBTREE,
    OPAQUE_SHELL
}

/**
 * 采样域 → 底图来源。这张表就是架构约束本身：
 * 面板域永远拿不到壁纸采样，内容域永远拿不到 drawable 入口。
 */
fun glassBackdropSource(domain: GlassSamplingDomain): GlassBackdropSource = when (domain) {
    GlassSamplingDomain.WALLPAPER -> GlassBackdropSource.WALLPAPER_BITMAP
    GlassSamplingDomain.PANEL -> GlassBackdropSource.OPAQUE_SHELL
    GlassSamplingDomain.CONTENT -> GlassBackdropSource.SIBLING_SUBTREE
}

/** [GlassSurface] 的壁纸 drawable 入口是否允许为该采样域出图。 */
fun glassSurfaceDrawableAllowed(domain: GlassSamplingDomain): Boolean =
    glassBackdropSource(domain) == GlassBackdropSource.WALLPAPER_BITMAP
