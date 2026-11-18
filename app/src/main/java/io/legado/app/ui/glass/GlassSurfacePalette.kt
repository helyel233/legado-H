package io.legado.app.ui.glass

/**
 * 玻璃面板壳层渐变的 RGB（不含 alpha），纯函数。
 *
 * 与 `ExploreGlassBackdrop.RowGlassDrawable.surfaceColor` 同一口径：浅底用白壳
 * （高光更明显），深底用近黑壳（折射暗带更明显）。入参 [lightBase] 由调用方用
 * `AppColorUtils.isColorLight` 传入——把判据留在 Android 侧，本函数保持纯算术。
 *
 * 历史注记：本函数原属 `GlassFrostSpec.kt`（旧手绘磨砂管线的规格层）。该管线被
 * [GlassLiquidBackdropDrawable]（AGSL 单管线）取代后删除，仅此函数仍被阅读设置
 * 弹窗 / 界面弹层 / 书籍详情页三处的壳层取色使用，故单独保留。
 */
internal fun glassFrostSurfaceRgb(lightBase: Boolean): Int =
    if (lightBase) 0xFFFFFF else 0x16181C
