package io.legado.app.ui.main.explore

import kotlin.math.min

/**
 * API < 33（以及液态失败降级后）的「拟液态」视觉叠加参数（方案 A1，1.25.10）。
 *
 * ## 它补的是什么
 *
 * 这些设备上没有 `RuntimeShader`，行的玻璃层只能靠 `BitmapShader` 采样预模糊位图。
 * 与底栏 6 个可感知要素对比，行内此时缺的是：
 * - ⑤ 边缘折射带带来的**体积感**：底栏由着色器把像素沿法向弯折，行内只能「画」出来；
 * - ⑥ 色散彩边：底栏由 7-taps 位移产生，行内只能用亚像素级的**三色错位描边**近似。
 *
 * 本文件用三个廉价层次补这两点：
 * 1. 圆角内侧**顶部高光带**（液面受光的窄亮带）；
 * 2. 底部**厚度内阴影**（玻璃有厚度的视觉暗示）；
 * 3. 左右**色散彩边**（暖色 / 冷色各一条，亚像素错位，≤ 1px）。
 *
 * ## 纪律
 *
 * - **不改既有模糊公式**、不改 `ROW_*GAIN` 口径、不改 [io.legado.app.ui.main.GlassMaterialPreset]
 *   的任何参数：这里只有「新增的叠加层」，取值全部**由 `dispersion` 派生**，
 *   因为 `dispersion` 是预设里唯一同时表达「该档边缘锐利程度」的量
 *   （glass 档高、frosted 档低），所以叠加强度天然随档位变化，不需要第二套系数表。
 * - **无 Android 依赖**，可在本地 JVM 单测里逐值锁住（A1 是 API<33 用户的唯一观感来源，
 *   口径跑偏不会被编译器和真机冒烟测出来）。
 */

/** 顶部高光带的 alpha 上限（超过会让行看起来「发白起雾」而不是「玻璃受光」）。 */
internal const val ROW_RIM_MAX_TOP_ALPHA: Float = 0.5f

/** 底部厚度内阴影的 alpha 上限（超过会像「脏边」而不是「厚度」）。 */
internal const val ROW_RIM_MAX_INNER_SHADOW_ALPHA: Float = 0.3f

/** 边缘色散彩边描边的 alpha 上限（彩边是点缀，不能压过书源名文字）。 */
internal const val ROW_RIM_MAX_CHROMA_ALPHA: Float = 0.22f

/** 色散彩边错位量的硬上限：亚像素级，超过 1px 就会被看出「重影」而不是「色散」。 */
internal const val ROW_RIM_CHROMA_MAX_OFFSET_PX: Float = 1f

/** 高光带占行高的比例下限：行很矮时也要有一点点受光，否则完全看不出来。 */
internal const val ROW_RIM_TOP_BAND_MIN_FRACTION: Float = 0.12f

/** 高光带占行高的比例上限：半行以上就不再是「带」而是「渐变填充」。 */
internal const val ROW_RIM_TOP_BAND_MAX_FRACTION: Float = 0.45f

/**
 * 一行拟液态叠加层的全部绘制参数（纯数据）。
 *
 * @param topBandAlpha 顶部高光带起始 alpha（向下渐变到透明）
 * @param topBandFraction 高光带占总高度的比例（0.12~0.45）
 * @param innerShadowAlpha 底部内阴影 alpha（向上渐变到透明）
 * @param chromaOffsetPx 暖/冷彩边的错位量（像素，≤ [ROW_RIM_CHROMA_MAX_OFFSET_PX]）
 * @param chromaAlpha 彩边描边 alpha
 */
internal data class RowRimSpectrum(
    val topBandAlpha: Float,
    val topBandFraction: Float,
    val innerShadowAlpha: Float,
    val chromaOffsetPx: Float,
    val chromaAlpha: Float
)

/**
 * 由该档 `dispersion` + 行几何算出拟液态叠加参数（纯函数，单调性可断言）。
 *
 * 单调性是有意的：`dispersion` 越大（越接近透明玻璃档）→ 高光带更亮、厚度阴影更重、
 * 彩边错位与 alpha 更大；磨砂档的 `dispersion` 更小 → 整体更含蓄。
 * 于是「三档差异肉眼可分」这件事完全由共享预设驱动，本文件不引入第二套档位系数。
 *
 * @param dispersion 该档预设的 `dispersion`（0~1，`GlassMaterialPreset.Params.dispersion`）
 * @param refractionHeightDp 该档预设的 `refractionHeightDp`，用于决定高光带宽度，
 *   与真折射带（[rowRefractionBandPx]）同一份口径
 * @param rowHeightPx 行的像素高度
 * @param density 屏幕密度
 */
internal fun rowRimSpectrum(
    dispersion: Float,
    refractionHeightDp: Float,
    rowHeightPx: Float,
    density: Float
): RowRimSpectrum {
    val d = dispersion.coerceIn(0f, 1f)
    // 高光带宽度与真折射带同口径（预设与行高 42% 的较小值），两档视觉语言因此一致。
    val bandPx = rowRefractionBandPx(refractionHeightDp, rowHeightPx, density)
    val fraction = if (rowHeightPx > 0f) {
        (bandPx / rowHeightPx).coerceIn(
            ROW_RIM_TOP_BAND_MIN_FRACTION, ROW_RIM_TOP_BAND_MAX_FRACTION
        )
    } else {
        ROW_RIM_TOP_BAND_MIN_FRACTION
    }
    return RowRimSpectrum(
        // 透明玻璃档 dispersion≈0.27 → 0.25；磨砂档 ≈0.14 → 0.18（浅色主题下不过曝）
        topBandAlpha = (0.10f + d * 0.55f).coerceIn(0f, ROW_RIM_MAX_TOP_ALPHA),
        topBandFraction = fraction,
        innerShadowAlpha = (0.05f + d * 0.30f).coerceIn(0f, ROW_RIM_MAX_INNER_SHADOW_ALPHA),
        chromaOffsetPx = (0.2f + d * 1.6f).coerceIn(0f, ROW_RIM_CHROMA_MAX_OFFSET_PX),
        chromaAlpha = (d * 0.55f).coerceIn(0f, ROW_RIM_MAX_CHROMA_ALPHA)
    )
}

/** 一对色散彩边颜色（ARGB Int）。 */
internal data class RowChromaEdgeColors(
    /** 暖侧（红通道抬升、蓝绿略降）—— 出现在一侧边缘。 */
    val warmColor: Int,
    /** 冷侧（蓝通道抬升、红绿略降）—— 出现在另一侧边缘。 */
    val coolColor: Int
)

/**
 * 由基准色推导两条色散彩边颜色（纯函数，纯 Int 位运算，无 Android 依赖）。
 *
 * 基准色是全局描边色 `glassStrokeColor`（未配置时由调用方传底栏同口径的表面色）。
 * 做法是对 RGB 通道做**饱和度/亮度偏移**，而不是凭空取两种红蓝：
 * 这样在浅色主题（近白描边）与深色主题（近黑描边）下彩边都不会突兀，
 * 也不会出现「玻璃边上一圈纯红纯蓝」的塑料感。
 *
 * @param baseColor 基准色（ARGB，alpha 分量被忽略，由 [alpha] 重新给出）
 * @param dispersion 该档色散强度（0~1），越大偏移越明显
 * @param alpha 目标 alpha（0~1）
 */
internal fun rowChromaEdgeColors(
    baseColor: Int,
    dispersion: Float,
    alpha: Float
): RowChromaEdgeColors {
    val a = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    val r = (baseColor ushr 16) and 0xFF
    val g = (baseColor ushr 8) and 0xFF
    val b = baseColor and 0xFF
    val boost = (dispersion.coerceIn(0f, 1f) * 96f).toInt()
    val half = boost / 2
    val third = boost / 3
    val warmR = (r + boost).coerceAtMost(255)
    val warmG = (g - third).coerceAtLeast(0)
    val warmB = (b - half).coerceAtLeast(0)
    val coolR = (r - half).coerceAtLeast(0)
    val coolG = (g - third).coerceAtLeast(0)
    val coolB = (b + boost).coerceAtMost(255)
    return RowChromaEdgeColors(
        warmColor = (a shl 24) or (warmR shl 16) or (warmG shl 8) or warmB,
        coolColor = (a shl 24) or (coolR shl 16) or (coolG shl 8) or coolB
    )
}

/**
 * 底部内阴影带的高度（像素）：与高光带同宽但不超过行高的一半。
 *
 * 单独抽出来是为了让「高光带与阴影带不会在矮行上重叠成一条糊线」这条约束可断言。
 */
internal fun rowInnerShadowHeightPx(rowHeightPx: Float, spectrum: RowRimSpectrum): Float {
    if (rowHeightPx <= 0f) return 0f
    val band = rowHeightPx * spectrum.topBandFraction
    return min(band, rowHeightPx / 2f).coerceAtLeast(0f)
}
