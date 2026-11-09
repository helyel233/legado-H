package io.legado.app.ui.main.explore

import io.legado.app.ui.main.GlassMaterialPreset
import kotlin.math.min

/**
 * 发现页书源名行的「行内液态玻璃」纯函数层（1.25.10）。
 *
 * ## 这里为什么没有 Android 依赖
 *
 * 本文件**不 import 任何 `android.*` 类型**：着色器驱动的部分全部在
 * [RowGlassLiquidRenderer]（`@RequiresApi(33)`），本文件只负责
 * 「参数该取多少」与「这行允许走哪条管线」这两个可以确定性断言的决策。
 * 于是行高标定、uniform 口径、降级矩阵都能在本地 JVM 单测里锁死，
 * 不需要 Robolectric、模拟器或真机。
 *
 * ## 与 1.25.8 的区别（为什么这次不会有 native 崩溃）
 *
 * 1.25.8 把行玻璃绑到 `StableLiquidGlassView`，采样源是列表 `RecyclerView` 自身，
 * 而玻璃视图就在该 `RecyclerView` 的 item 里 → 底层库每帧 `beginRecording` 后
 * `source.draw(canvas)` 会把玻璃自己重画一遍 → RenderNode 重入录制 → hwui abort。
 *
 * 本方案**完全不复用底层库的 Java 宿主**，只复用它的 AGSL 源码（MIT，见
 * `res/raw/glass_row_liquid.agsl`），采样源是**一张已经算好的降采样壁纸位图**
 * （`GlassBackdrop.raw`，IO 线程构建并按 key 缓存；模糊交给 GPU，与底栏同一条
 * `createChainEffect(着色器, 高斯)` 链路）。录制的内容永远是
 * 「我们自己画的一条 `drawRect`」，不是任何 `View` 子树，因此
 * `RenderNode.beginRecording` + 对祖先 `View.draw()` 这条崩溃路径在结构上不存在。
 *
 * ## 关键前提（必须向用户交代）
 *
 * 行背景背后只有主题壁纸层（`ll_title` 与书网格不重叠，见技术方案 §1.1），
 * 所以行内「真折射」折射的必然是壁纸。主题壁纸是**纯色底**时，
 * 折射无高频内容可弯折，视觉增量 ≈ 0（这不是缺陷，是前提）。
 */

/**
 * **A2 总闸**（编译期常量）：只控制「AGSL 真折射 + 色散」这一层，**不管** A1 拟液态层。
 *
 * 两个闸是**正交**的，组合语义见 [ROW_PSEUDO_LIQUID_ENABLED]：
 * | [ROW_LIQUID_ENABLED] | [ROW_PSEUDO_LIQUID_ENABLED] | 结果 |
 * |---|---|---|
 * | true（默认） | true（默认） | API 33+ 走 A2 真折射；API < 33 走 A1 拟液态 |
 * | **false** | true（默认） | **全设备纯 A1**——这就是用户做「A2 开/关」观感对比的对照组 |
 * | false | false | **逐像素回到 1.25.9** 的静态模糊渲染（最快回滚手段） |
 *
 * 按项目约定不新增用户可见偏好键：运行期给用户的兜底开关是既有的
 * 「全局毛玻璃效果 → 硬件模糊」（[ExploreGlassConfig.hardwareBlur]）。
 */
internal const val ROW_LIQUID_ENABLED: Boolean = true

/**
 * **A1 总闸**（编译期常量）：只控制「拟液态叠加层」（顶部高光带 + 底部厚度内阴影 +
 * 两侧色散彩边，见 [RowRimSpectrum]），**不管** A2。
 *
 * A1 的定位是「API < 33 的兜底」与「A2 当帧不可用时的降级观感」，因此它**只在本行
 * 没有走成 A2 时才叠加**（判定见 [rowPseudoLiquidEnabled]）——API 33+ 且 A2 成功的行
 * 只有 A2 的折射 + 色散，再叠一层拟液态会比底栏更重，也会让「纯 A2 效果」无法观测。
 *
 * 置 `false` 表示「不要拟液态叠加」，与 [ROW_LIQUID_ENABLED] 一起才能回到 1.25.9。
 */
internal const val ROW_PSEUDO_LIQUID_ENABLED: Boolean = true

/**
 * 行内液态玻璃的**进程级**运行期状态（全仓唯一一份）。
 *
 * ## 为什么必须是进程级而不是每个渲染器一份
 *
 * `FrostedGlassDrawable` 是在 `ExploreGlassBackdrop.rowBackground()` 里创建的，而
 * `rowBackground()` 在**每次 `onBindViewHolder` 都会重新走一遍**（行复用），所以
 * `RowGlassLiquidRenderer` 会随 rebind 反复重建。若「AGSL 失败」这个事实记在实例字段上，
 * 在驱动不支持 AGSL 的设备上就会变成：**每次 rebind 都重新编译、重新抛异常、重新打日志**——
 * 那是「逐帧重试」的缓释版本，与「失败即无声降级」的承诺不符，滚动时还会持续抖。
 *
 * 提到进程级后：一次失败 → 之后**所有实例**（含未来新建的）立刻走静态路径，且
 * `Log.w` 全进程只打一次。
 *
 * ## 生命周期
 *
 * 只置位、不清零。位图更换 / shader 重建（`RowGlassLiquidRenderer.reset()`）**不清**这个位：
 * 那是「纹理换了」，不是「设备突然支持 AGSL 了」。进程重启才复位。
 */
internal object RowLiquidRuntimeState {

    /** AGSL 编译或绘制是否曾经失败过（进程内持久）。 */
    @Volatile
    var shaderFailed: Boolean = false
}

/**
 * 折射带宽不得超过行高的比例。
 *
 * 与 `StableLiquidGlassView.effectiveRefractionHeight` 的 `pixelSafePill` 口径
 * （`height * 0.42f`）**逐值一致**：底栏是一条 56dp 高的 pill，预设
 * `refractionHeightDp=22~34dp` 约等于半高；行只有 ~32-40dp 高，若直接套用预设，
 * `-sd < refractionHeight` 会恒成立，整行都落进 7-taps 折射带（表现为整行被拉花
 * 且满负荷取样）。用同一个 0.42 常量做上限，两处口径不会各自漂移。
 */
internal const val ROW_REFRACTION_BAND_RATIO: Float = 0.42f

/**
 * 折射位移量不得超过带宽的倍数。
 *
 * 超过这个倍数，行内侧的位移会跨过整条折射带甚至越过行中心，7 次取样退化成
 * 对「整行平均色」的涂抹，观感从「透镜」变成「糊块」。
 */
internal const val ROW_REFRACTION_AMOUNT_RATIO: Float = 1.6f

/**
 * 与底栏 `Config` 默认值 / `StableLiquidGlassView.overrides()` 逐值对齐的着色器常量。
 *
 * 底栏实际生效的是 `noFilter() / contrast(0) / whitePoint(0) / chromaMultiplier(1)`，
 * `depthEffect` 用库 `Config` 的默认 0.3。行内必须传同一组值，否则同一份 AGSL
 * 会在两处表现不同（例如 contrast 非 0 会让行比底栏更「硬」）。
 */
internal const val ROW_LIQUID_DEPTH_EFFECT: Float = 0.3f
internal const val ROW_LIQUID_CONTRAST: Float = 0f
internal const val ROW_LIQUID_WHITE_POINT: Float = 0f
internal const val ROW_LIQUID_CHROMA_MULTIPLIER: Float = 1f

/**
 * 着色器 tint 必须与底栏同口径：直接取该档预设的 `tintAlpha`（经 tint 滑杆缩放），
 * 由着色器自己把白色混进内容。
 *
 * 1.25.10 曾把这里的 tint 钉成 0、改用行内额外的 `tintPaint` 层，并叠加 3× 行可见度
 * 增益——结果是同一档参数下发现页行明显比底栏更白。1.25.11 起液态路径与底栏 1:1：
 * 着色器负责 tint，额外的 tint/rim/A1 叠加层在液态成功时全部让位
 * （见 [rowGlassOverlayPlan]），静态降级路径仍保留行可见度标定。
 */
internal const val ROW_LIQUID_TINT_RED: Float = 1f
internal const val ROW_LIQUID_TINT_GREEN: Float = 1f
internal const val ROW_LIQUID_TINT_BLUE: Float = 1f

/**
 * 行内液态路径的描边上限：与底栏 `shellAlphas().stroke` 的既有上限 `0.42` 逐值一致。
 * 描边滑杆可以放大到 200%，但超过底栏上限后会变成「一圈实线」，不再像玻璃。
 */
internal const val ROW_LIQUID_MAX_STROKE: Float = 0.42f

/**
 * 液态行玻璃的最终绘制口径（纯数据）。
 *
 * 与底栏 `StableLiquidGlassView + shell overlay` **逐值 1:1**：同一份
 * [GlassMaterialPreset.Params] 经四个倍率滑杆缩放后得到，不含任何行级增益。
 * 滑杆为 100（默认）时，本类的每个字段都等于底栏同档实际使用的值。
 */
internal data class RowGlassLiquidStyle(
    val blurDp: Float,
    val tintAlpha: Float,
    val dispersion: Float,
    val refractionHeightDp: Float,
    val refractionOffsetDp: Float,
    val shellStart: Float,
    val shellCenter: Float,
    val shellEnd: Float,
    val strokeAlpha: Float
)

/**
 * 共享预设 + 四个倍率滑杆 → 液态行绘制口径（纯函数）。
 *
 * 刻意**不再乘** `ROW_TINT_GAIN / ROW_SHELL_GAIN / ROW_RIM_GAIN`：那些增益只服务于
 * API < 33 的静态降级管线（[resolveExploreGlassRenderParams]），液态管线一旦也乘，
 * 同一档参数下发现页行就会比底栏更白、更厚——这正是要修的观感不一致。
 */
internal fun rowGlassLiquidStyle(
    material: GlassMaterialPreset.Params,
    blurPercent: Int = 100,
    tintPercent: Int = 100,
    shellPercent: Int = 100,
    strokePercent: Int = 100
): RowGlassLiquidStyle {
    val scaled = GlassMaterialPreset.scaled(
        material, blurPercent, tintPercent, shellPercent, strokePercent
    )
    return RowGlassLiquidStyle(
        blurDp = scaled.blurDp,
        tintAlpha = scaled.tintAlpha,
        dispersion = scaled.dispersion,
        refractionHeightDp = scaled.refractionHeightDp,
        refractionOffsetDp = scaled.refractionOffsetDp,
        shellStart = scaled.shell.start,
        shellCenter = scaled.shell.center,
        shellEnd = scaled.shell.end,
        strokeAlpha = scaled.shell.stroke.coerceIn(0f, ROW_LIQUID_MAX_STROKE)
    )
}

/**
 * 液态层成功出图后，既有叠加层各自是否还要画（纯函数）。
 *
 * 底栏只有「着色器（含 tint）+ shell 渐变 + 描边」，没有独立 tint/高光/A1 叠加层；
 * 行若在液态成功后继续画这些层，就会比底栏更重。因此：
 * - `liquidDrewGlass=true`：三层全部让位（画面 = 着色器 + shell + 描边）；
 * - 否则：tint/rim 由静态管线保留，A1 仍按 [rowPseudoLiquidEnabled] 兜底。
 */
internal data class RowGlassOverlayPlan(
    val drawTintLayer: Boolean,
    val drawRimLayer: Boolean,
    val drawPseudoLiquidLayer: Boolean
)

internal fun rowGlassOverlayPlan(
    liquidDrewGlass: Boolean,
    pseudoLiquidEnabled: Boolean
): RowGlassOverlayPlan =
    if (liquidDrewGlass) {
        RowGlassOverlayPlan(
            drawTintLayer = false,
            drawRimLayer = false,
            drawPseudoLiquidLayer = false
        )
    } else {
        RowGlassOverlayPlan(
            drawTintLayer = true,
            drawRimLayer = true,
            drawPseudoLiquidLayer = rowPseudoLiquidEnabled(pseudoLiquidEnabled, false)
        )
    }

/** 折射带宽下限（像素）：0 会让 shader 内部 `d = ... / refractionHeight` 除零。 */
private const val ROW_REFRACTION_BAND_MIN_PX = 1f

/**
 * 行内折射带宽（像素）：取「该档预设」与「行高 42%」的较小值。
 *
 * @param presetHeightDp 该档预设的 `refractionHeightDp`（[GlassMaterialPreset.Params]）
 * @param rowHeightPx 行的实际像素高度（draw 时才知道，因此调用点是惰性的）
 * @param density 屏幕密度
 * @return 恒 ≥ [ROW_REFRACTION_BAND_MIN_PX]，永不为 0 或负数
 */
internal fun rowRefractionBandPx(
    presetHeightDp: Float,
    rowHeightPx: Float,
    density: Float
): Float {
    val presetPx = presetHeightDp * density
    val safeCapPx = rowHeightPx.coerceAtLeast(0f) * ROW_REFRACTION_BAND_RATIO
    return min(presetPx, safeCapPx).coerceAtLeast(ROW_REFRACTION_BAND_MIN_PX)
}

/**
 * 行内折射位移量（像素，**正值 = 位移幅度**）：取预设与「带宽 1.6 倍」的较小值。
 *
 * 传给着色器时要取相反数（底栏 `StableLiquidGlassView.overrides()` 同样传
 * `-refractionOffset`）：`refractedCoord = coord + d * grad`，`grad` 是 SDF 的外法向，
 * 因此只有**负值**才是向内位移，取样点才会落在 node 内部，避免采到 node 边界外的
 * 透明像素而在行边缘出现黑边/透明边。
 *
 * @param presetOffsetDp 该档预设的 `refractionOffsetDp`；若外部传负值先归一为正
 */
internal fun rowRefractionAmountPx(
    bandPx: Float,
    presetOffsetDp: Float,
    density: Float
): Float {
    val presetPx = kotlin.math.abs(presetOffsetDp) * density
    val safeCapPx = bandPx.coerceAtLeast(0f) * ROW_REFRACTION_AMOUNT_RATIO
    return min(presetPx, safeCapPx).coerceAtLeast(0f)
}

/**
 * 一行玻璃层需要写入 AGSL 的全部 uniform（再加采样矩阵与内容纹理）。
 *
 * 用扁平标量而不是 `FloatArray`，是为了让单测能逐个字段做语义断言
 * （数组下标写错一位只会让画面轻微跑偏，不会被编译器和类型系统拦住）。
 */
internal data class RowGlassLiquidUniforms(
    /** 玻璃层尺寸（行宽、行高，像素）。同时决定着色器坐标空间与圆角 SDF 的半尺寸。 */
    val widthPx: Float,
    val heightPx: Float,
    /** 着色器坐标系偏移；行内恒为 0（坐标就是行局部的 `coord`）。 */
    val offsetXPx: Float,
    val offsetYPx: Float,
    /** 四角圆角半径；四值相等（行是普通圆角矩形，不是 pill）。 */
    val cornerRadiusPx: Float,
    /** 折射带宽度（像素），见 [rowRefractionBandPx]。 */
    val refractionHeightPx: Float,
    /**
     * 折射位移量（像素），**负值 = 向内位移**，见 [rowRefractionAmountPx]。
     * 恒 ≤ 0：`0` 表示该行不产生折射（例如行太矮、带宽被钳到 1px）。
     */
    val refractionAmountPx: Float,
    val depthEffect: Float,
    /** 色散强度：与底栏同源，直接取该档预设的 `dispersion`。 */
    val chromaticAberration: Float,
    /** 白色 tint 的混入强度：与底栏同源，取 [RowGlassLiquidStyle.tintAlpha]。 */
    val tintAlpha: Float,
    val contrast: Float,
    val whitePoint: Float,
    val chromaMultiplier: Float
) {
    /**
     * uniform 的稳定签名：尺寸/位置/色散/着色任一变化都会改变它。
     * 渲染器用它判断「是否需要重写 uniform」，
     * 避免逐帧 `setFloatUniform`（着色器 uniform 写入会触发 Skia 侧的状态失效）。
     */
    fun signature(): String = listOf(
        widthPx, heightPx, offsetXPx, offsetYPx, cornerRadiusPx,
        refractionHeightPx, refractionAmountPx,
        depthEffect, chromaticAberration, tintAlpha,
        contrast, whitePoint, chromaMultiplier
    ).joinToString("|")
}

/**
 * 由液态行绘制口径 + 行的实际像素尺寸算出整组 uniform（纯函数）。
 *
 * 逐值对齐底栏的地方：
 * - `depthEffect` = [ROW_LIQUID_DEPTH_EFFECT]（库 `Config` 默认 0.3）
 * - `contrast` / `whitePoint` / `chromaMultiplier` = 0 / 0 / 1
 *   （对应 `StableLiquidGlassView.overrides()` 的 `noFilter/contrast/whitePoint/chromaMultiplier`）
 * - `chromaticAberration` = `style.dispersion`（同一份 `GlassMaterialPreset` 取值）
 * - `tintAlpha` = `style.tintAlpha`（底栏同样把 tint 交给着色器）
 *
 * 刻意与底栏不同、只允许在行内偏离的两处（几何标定，不是材质）：
 * - `refractionHeightPx` / `refractionAmountPx` 走**行高标定**（[rowRefractionBandPx] /
 *   [rowRefractionAmountPx]）：行只有 ~36dp 高，直接套 56dp pill 的折射带会整行落进
 *   折射区。用的 0.42 上限正是底栏 `pixelSafePill` 的同一条安全规则；
 * - `cornerRadiusPx` 额外钳到行高的一半（与 `StableLiquidGlassView` 的
 *   `effectiveShaderCornerRadius` 同口径）。
 *
 * @param style 液态行绘制口径（[rowGlassLiquidStyle] 的产物，已含档位与滑杆）
 * @param widthPx 行宽（像素）
 * @param heightPx 行高（像素）
 * @param cornerRadiusPx 行圆角（像素）
 * @param density 屏幕密度
 */
internal fun rowLiquidUniforms(
    style: RowGlassLiquidStyle,
    widthPx: Float,
    heightPx: Float,
    cornerRadiusPx: Float,
    density: Float
): RowGlassLiquidUniforms {
    val bandPx = rowRefractionBandPx(
        presetHeightDp = style.refractionHeightDp,
        rowHeightPx = heightPx,
        density = density
    )
    val amountPx = rowRefractionAmountPx(
        bandPx = bandPx,
        presetOffsetDp = style.refractionOffsetDp,
        density = density
    )
    val safeHeight = heightPx.coerceAtLeast(0f)
    return RowGlassLiquidUniforms(
        widthPx = widthPx.coerceAtLeast(0f),
        heightPx = safeHeight,
        offsetXPx = 0f,
        offsetYPx = 0f,
        cornerRadiusPx = cornerRadiusPx.coerceIn(0f, safeHeight / 2f),
        refractionHeightPx = bandPx,
        // 负值 = 向内位移，见 rowRefractionAmountPx 的 kdoc
        refractionAmountPx = -amountPx,
        depthEffect = ROW_LIQUID_DEPTH_EFFECT,
        chromaticAberration = style.dispersion,
        tintAlpha = style.tintAlpha,
        contrast = ROW_LIQUID_CONTRAST,
        whitePoint = ROW_LIQUID_WHITE_POINT,
        chromaMultiplier = ROW_LIQUID_CHROMA_MULTIPLIER
    )
}

/**
 * 采样矩阵的四个分量（纯数据，便于逐值断言）。
 *
 * 生产侧把它写进一个复用的 [android.graphics.Matrix]：
 * `setScale(scaleX, scaleY)` 然后 `postTranslate(translateX, translateY)`，
 * 语义与 `ExploreGlassBackdrop.FrostedGlassDrawable.draw()` 里既有的内联计算
 * **逐值一致**（1.25.10 把这段内联逻辑提取为纯函数，行为零变化）。
 */
internal data class RowSampleTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float
)

/**
 * 行局部像素坐标 → 降采样壁纸位图坐标 的仿射变换（纯函数）。
 *
 * 底图是按**整屏**对齐构建的（`targetW = screenW / DOWNSCALE`），行只是屏上的一块，
 * 因此必须按「行在窗口中的位置」把采样原点平移过去：
 * `bitmap = (local + rowLoc - rootLoc) * scale`。
 *
 * @param bitmapWidth 降采样底图宽（像素）
 * @param bitmapHeight 降采样底图高（像素）
 * @param rootWidth 根视图宽（像素）；≤ 0 时视为不可用，返回单位变换
 * @param rootHeight 根视图高（像素）；≤ 0 时视为不可用，返回单位变换
 * @param locationX 行在屏幕上的 x（`getLocationOnScreen`）
 * @param locationY 行在屏幕上的 y
 * @param rootX 根视图在屏幕上的 x
 * @param rootY 根视图在屏幕上的 y
 */
internal fun rowSampleTransform(
    bitmapWidth: Int,
    bitmapHeight: Int,
    rootWidth: Int,
    rootHeight: Int,
    locationX: Int,
    locationY: Int,
    rootX: Int,
    rootY: Int
): RowSampleTransform {
    if (rootWidth <= 0 || rootHeight <= 0) {
        return RowSampleTransform(1f, 1f, 0f, 0f)
    }
    // 同时把 Y 轴镜像掉：drawable 与 RenderNode 的录制坐标同为正向下，无需翻转；
    // 这里只做「缩放 + 平移」，与既有实现完全一致。
    val scaleX = bitmapWidth / rootWidth.toFloat()
    val scaleY = bitmapHeight / rootHeight.toFloat()
    return RowSampleTransform(
        scaleX = scaleX,
        scaleY = scaleY,
        translateX = -(locationX - rootX) * scaleX,
        translateY = -(locationY - rootY) * scaleY
    )
}

/**
 * 行玻璃层的落地形态。
 *
 * 只有两态而不是技术方案 §8.1 写的三态（`AGSL / RENDER_NODE / STATIC`）：
 * 本实现采用的小 RenderNode + [android.graphics.RenderEffect.createRuntimeShaderEffect]
 * 形态（方案 A2'）本身就是「跑 AGSL 的那一态」——它和「把 RuntimeShader 当 Paint shader」
 * 是同一份数学的两种宿主写法，对降级矩阵没有语义差别，因此不设第三个状态以免留下
 * 永远不会被取到的分支。
 */
internal enum class RowLiquidCapability {
    /** 走液态折射层（AGSL，API 33+，硬件加速 Canvas）。 */
    LIQUID,

    /** 走既有静态管线（软件 BitmapShader / 硬件高斯 / A1 拟液态叠加）。 */
    STATIC
}

/** [rowLiquidCapability] 的 API 门槛：`RuntimeShader` / `createRuntimeShaderEffect` = API 33。 */
internal const val ROW_LIQUID_MIN_SDK: Int = 33

/**
 * 降级矩阵里**「本次绘制」**的那三条（会随帧变化，因此与设备/偏好级的判定分开）。
 *
 * 抽成独立函数是为了让「构造期只筛设备与偏好」和「绘制期只筛当帧事实」两处调用
 * 共享同一份判定，不会出现「一个地方改了、另一个地方忘了改」的双份实现。
 *
 * @param canvasHardwareAccelerated 本次绘制是否为硬件加速 Canvas
 * @param backdropAvailable 本次绘制是否有可采样的降采样壁纸位图
 * @param liquidFailed 本行是否已置持久故障位
 * @return true 表示当帧允许走液态层
 */
internal fun rowLiquidDrawableNow(
    canvasHardwareAccelerated: Boolean,
    backdropAvailable: Boolean,
    liquidFailed: Boolean
): Boolean = canvasHardwareAccelerated && backdropAvailable && !liquidFailed

/**
 * 本行是否应叠加 A1 拟液态层（纯函数）：**只有没走成 A2 的时候才叠**。
 *
 * A1 的定位是「API < 33 的兜底」与「A2 当帧不可用时的降级观感」。API 33+ 且 A2 画成功
 * 的行若再叠一层高光带 / 内阴影 / 彩边，会比底栏更重，而且「纯 A2 效果」永远无法观测
 * （A/B 不正交）。因此判据是「本行这一帧确实没有由 A2 出图」——既覆盖 A2 不适用
 * （SDK / 开关 / 无底图），也覆盖 A2 **当帧失败**（渲染器置故障位后回落静态管线）。
 *
 * @param pseudoLiquidEnabled 编译期总闸 [ROW_PSEUDO_LIQUID_ENABLED]
 * @param liquidDrewGlass 本帧 A2 是否真的画出了玻璃层
 */
internal fun rowPseudoLiquidEnabled(
    pseudoLiquidEnabled: Boolean,
    liquidDrewGlass: Boolean
): Boolean = pseudoLiquidEnabled && !liquidDrewGlass

/**
 * 行内液态玻璃的四级降级矩阵（纯函数，逐条可断言）。
 *
 * 判定顺序（任一命中即 [RowLiquidCapability.STATIC]，且都不会留空白帧）：
 * 1. `sdkInt < 33`：没有 `RuntimeShader`，走静态管线（A1 拟液态叠加）；
 * 2. 编译期总闸 [ROW_LIQUID_ENABLED] 关闭：→ 静态管线（纯 A1）；
 * 3. 用户关掉「硬件模糊」开关（[ExploreGlassConfig.hardwareBlur]）：复用既有偏好键兜底，
 *    不新增设置项；
 * 4. 墨水屏 / 低内存设备：与 `rowBackground()` 的既有降级一致，牺牲观感换滚动流畅；
 * 5. 强度 ≤ 0：观感档已被用户关掉；
 * 6. 非硬件加速 Canvas（软件 layer / 截图 / 部分 ROM 的离屏绘制）：当帧退静态管线，
 *    但这只是**当帧状态**，不置持久故障位；
 * 7. 底图不可用（动图壁纸纯色底、构建失败、已回收）：没有纹理可折射；
 * 8. [liquidFailed] 持久位已置：AGSL 编译或绘制曾经抛过异常，本行不再重试。
 *
 * 注意第 6/7/8 条都是「本次绘制」的事实（由 [rowLiquidDrawableNow] 统一判定），
 * 第 1-5 条是「设备/偏好」的事实。生产代码在构造期调一次本函数
 * （`canvasHardwareAccelerated` / `backdropAvailable` 传 true、`liquidFailed` 传 false
 * 即只筛设备与偏好），绘制期调 [rowLiquidDrawableNow]。
 *
 * @param sdkInt `Build.VERSION.SDK_INT`
 * @param rowLiquidEnabled 编译期总闸 [ROW_LIQUID_ENABLED]
 * @param hardwareBlurPref 「全局毛玻璃效果 → 硬件模糊」开关
 * @param isEInk 墨水屏模式
 * @param isLowRam 低内存设备
 * @param level 该页生效的毛玻璃强度（0-100）
 * @param canvasHardwareAccelerated 本次绘制是否为硬件加速 Canvas
 * @param backdropAvailable 本次绘制是否有可采样的降采样壁纸位图
 * @param liquidFailed 本行是否已置持久故障位
 */
internal fun rowLiquidCapability(
    sdkInt: Int,
    rowLiquidEnabled: Boolean,
    hardwareBlurPref: Boolean,
    isEInk: Boolean,
    isLowRam: Boolean,
    level: Int,
    canvasHardwareAccelerated: Boolean,
    backdropAvailable: Boolean,
    liquidFailed: Boolean
): RowLiquidCapability = when {
    sdkInt < ROW_LIQUID_MIN_SDK -> RowLiquidCapability.STATIC
    !rowLiquidEnabled -> RowLiquidCapability.STATIC
    !hardwareBlurPref -> RowLiquidCapability.STATIC
    isEInk -> RowLiquidCapability.STATIC
    isLowRam -> RowLiquidCapability.STATIC
    level <= 0 -> RowLiquidCapability.STATIC
    !rowLiquidDrawableNow(canvasHardwareAccelerated, backdropAvailable, liquidFailed) ->
        RowLiquidCapability.STATIC
    else -> RowLiquidCapability.LIQUID
}
