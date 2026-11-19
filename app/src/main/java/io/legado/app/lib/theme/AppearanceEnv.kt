package io.legado.app.lib.theme

/**
 * 主题 Token 编译的环境输入。
 *
 * 把「当前是否夜间 / 墨水屏 / 是否有可用壁纸 / 是否支持硬件模糊」收口为不可变值对象，
 * 作为纯函数 [ThemeTokenCompiler.compileRaw] 与缓存键的显式维度，避免跟随系统切深浅色
 * 或切换墨水屏时因未写 SP 而读到陈旧视觉产物。
 */
data class AppearanceEnv(
    val isNight: Boolean,
    val isEInk: Boolean,
    val hasUsableWallpaper: Boolean,
    val hardwareBlurSupported: Boolean
)
