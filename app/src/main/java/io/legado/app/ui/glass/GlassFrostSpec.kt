package io.legado.app.ui.glass

import io.legado.app.ui.main.GlassMaterialPreset

/**
 * 玻璃面的「壳层 + 折射边缘」规格（纯 JVM 可测，不含任何 Android 运行时调用）。
 *
 * ## 为什么需要这一层
 *
 * 前四轮把「玻璃」翻译成「把面 alpha 让出来 / 截一张底图做软件模糊」。这两件事在
 * **有高频细节的底图**上成立，但用户实际场景是纯色主题 / 系统壁纸：
 *
 * - 详情页 `bg_book` 在纯色档下是单色 ImageView，模糊单色仍是单色；
 * - 阅读设置面板磨的是正文，正文是「大片纯色 + 稀疏文字」，1/3 降采样再半径 25 的
 *   stackBlur 把笔画糊成均匀色块，74% 着色盖上去后与实底**像素级接近**；
 * - 于是用户看到「有透明 / 有代码，但完全看不出磨砂和折射」。
 *
 * 真实玻璃在纯色底上靠的从来不是「底图糊不糊」，而是**边缘**：上缘镜面高光、
 * 下缘折射暗带、整面渐变壳层、1dp 描边、极弱的色散边。本规格把这些量从
 * [GlassMaterialPreset] 的既有材质参数里唯一派生出来，详情页 / 阅读设置 /
 * 阅读底部弹层三处共用同一份，不再各写一套「玻璃观感」。
 *
 * 所有数值都在 [0,1]，阈值与 [GlassMaterialPreset.shellAlphas] 的既有口径一致，
 * 不新增偏好键、不改任何一档的既有参数表。
 */
internal data class GlassFrostSpec(
    /** 壳层渐变顶部 alpha（surface 色）。 */
    val shellStartAlpha: Float,
    /** 壳层渐变中部 alpha。 */
    val shellCenterAlpha: Float,
    /** 壳层渐变底部 alpha。 */
    val shellEndAlpha: Float,
    /** 上缘高光 / 下缘折射暗带的 alpha。 */
    val edgeAlpha: Float,
    /** 边缘色散（青/暖两侧）的 alpha。 */
    val dispersionAlpha: Float
) {
    init {
        require(shellStartAlpha in 0f..1f) { "shellStartAlpha 越界: $shellStartAlpha" }
        require(shellCenterAlpha in 0f..1f) { "shellCenterAlpha 越界: $shellCenterAlpha" }
        require(shellEndAlpha in 0f..1f) { "shellEndAlpha 越界: $shellEndAlpha" }
        require(edgeAlpha in 0f..1f) { "edgeAlpha 越界: $edgeAlpha" }
        require(dispersionAlpha in 0f..1f) { "dispersionAlpha 越界: $dispersionAlpha" }
    }
}

/**
 * 从唯一材质参数派生玻璃壳层规格（纯函数）。
 *
 * 壳层三色 alpha 直接取 [GlassMaterialPreset.shellAlphas]——那已经是底栏 / 发现页行
 * 生产环境在用的同一份表，这里不做二次标定，避免「详情页像玻璃、底栏不像」。
 *
 * 边缘强度由 [GlassMaterialPreset.Params.dispersion] 派生：
 * 透明玻璃档（0.24~0.36）边缘更亮、色散更明显；磨砂档（0.04~0.08）收敛成均匀雾面。
 * 两档在这一维上同样有可见差异，而不是同一观感的深浅版本。
 */
internal fun glassFrostSpec(params: GlassMaterialPreset.Params): GlassFrostSpec {
    val shell = params.shell
    return GlassFrostSpec(
        shellStartAlpha = shell.start.coerceIn(0f, 1f),
        shellCenterAlpha = shell.center.coerceIn(0f, 1f),
        shellEndAlpha = shell.end.coerceIn(0f, 1f),
        edgeAlpha = (0.05f + params.dispersion * 0.22f).coerceIn(0.05f, 0.16f),
        dispersionAlpha = (params.dispersion * 0.16f).coerceIn(0f, 0.08f)
    )
}

/** 测试与调用点共用的便捷入口：档位 + 强度 → 壳层规格（纯函数）。 */
internal fun glassFrostSpecFor(
    mode: String,
    glassLevel: Float,
    fallbackBoost: Float = 0f
): GlassFrostSpec = glassFrostSpec(
    GlassMaterialPreset.params(mode, glassLevel, fallbackBoost)
)

/**
 * 壳层渐变的 RGB（不含 alpha），纯函数。
 *
 * 与 `ExploreGlassBackdrop.RowGlassDrawable.surfaceColor` 同一口径：浅底用白壳
 * （高光更明显），深底用近黑壳（折射暗带更明显）。入参 [lightBase] 由调用方用
 * `AppColorUtils.isColorLight` 传入——把判据留在 Android 侧，本函数保持纯算术。
 */
internal fun glassFrostSurfaceRgb(lightBase: Boolean): Int =
    if (lightBase) 0xFFFFFF else 0x16181C

/**
 * 边缘高光 / 色散线的内缩距离（px，纯函数）。
 *
 * 取圆角的一半，保证线段两端仍落在圆角直边上；小半径（按钮）至少 1px，
 * 否则线段会退化成点。density 由调用方传入，不在纯函数里读 Android 资源。
 */
internal fun glassFrostEdgeInsetPx(cornerRadiusPx: Float, density: Float): Float =
    (cornerRadiusPx * 0.5f).coerceAtLeast(density.coerceAtLeast(1f))

/**
 * 边缘线宽（px，纯函数）：1dp 起步，超大圆角时略粗以保持可见。
 */
internal fun glassFrostEdgeWidthPx(cornerRadiusPx: Float, density: Float): Float =
    (density * (1f + (cornerRadiusPx / (density * 24f)).coerceIn(0f, 0.5f)))
        .coerceAtLeast(1f)
