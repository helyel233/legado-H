package io.legado.app.ui.main.explore

import io.legado.app.ui.main.GlassMaterialPreset
import kotlin.math.min

/**
 * 发现页书源名行的「行内液态玻璃」纯函数层。
 *
 * ## 这里为什么没有 Android 依赖
 *
 * 本文件**不 import 任何 `android.*` 类型**：着色器驱动的部分全部在
 * [RowGlassLiquidRenderer]（`@RequiresApi(33)`），本文件只负责
 * 「参数该取多少」与「这行允许走哪条管线」这两个可以确定性断言的决策。
 * 于是行高标定、uniform 口径、降级矩阵都能在本地 JVM 单测里锁死，
 * 不需要 Robolectric、模拟器或真机。
 *
 * ## 与底栏的关系（1.26.1）
 *
 * 行内不再是「发现页自己的一套观感 + 系数」：材质只有一份来源——
 * [GlassMaterialPreset.Params]（档位预设 × 强度 × 四个倍率 × API 33 以下补偿）。
 * 行渲染器用它自身的 AGSL 着色器 + 高斯出玻璃，tint 同样交给着色器，
 * 壳层与描边直接读 `material.shell`，与底栏 `StableLiquidGlassView + shell overlay`
 * 逐层对应。行内没有增益层、没有由色散派生的 rim 高光、也没有额外的拟液态叠加层。
 *
 * 几何上是唯一允许偏离底栏的地方：底栏是 56dp 的 pill，行只有 ~36dp 高，
 * 因此折射带 / 位移 / 圆角按底栏自己 `setRefractionHeight` / `setRefractionOffset` /
 * `effectiveShaderCornerRadius` 的**同一组上下限**重新标定（见 [rowRefractionBandPx]）。
 *
 * ## 与 1.25.8 的区别（为什么不会有 native 崩溃）
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
 * 行内液态玻璃的编译期总闸：置 false 时全设备走静态管线（硬件高斯 / 软件预模糊）。
 *
 * 按项目约定不新增用户可见偏好键：运行期给用户的兜底开关是既有的
 * 「全局毛玻璃效果 → 硬件模糊」（[ExploreGlassConfig.hardwareBlur]）。
 */
internal const val ROW_LIQUID_ENABLED: Boolean = true

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
 * 折射带 / 位移的上下限：与 `StableLiquidGlassView.setRefractionHeight` /
 * `setRefractionOffset` 的 clamp 逐值一致（12~50dp / 20~120dp）。
 *
 * 底栏把 dp 换成像素后再 clamp，这里保持同一顺序，于是「同一档参数」在
 * 底栏与行里得到的是同一段取值，而不是行内另立一套安全系数。
 */
internal const val REFRACTION_HEIGHT_MIN_DP: Float = 12f
internal const val REFRACTION_HEIGHT_MAX_DP: Float = 50f
internal const val REFRACTION_OFFSET_MIN_DP: Float = 20f
internal const val REFRACTION_OFFSET_MAX_DP: Float = 120f

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
 * 着色器 tint 与底栏同口径：取该档预设的 `tintAlpha`，由着色器自己把白色混进内容。
 *
 * 静态降级路径没有着色器，才由 `FrostedGlassDrawable` 额外画一层白色弱着色；
 * 液态成功时该层让位，否则同一档参数下发现页行会比底栏更白。
 */
internal const val ROW_LIQUID_TINT_RED: Float = 1f
internal const val ROW_LIQUID_TINT_GREEN: Float = 1f
internal const val ROW_LIQUID_TINT_BLUE: Float = 1f

/** 折射带宽下限（像素）：0 会让 shader 内部 `d = ... / refractionHeight` 除零。 */
private const val ROW_REFRACTION_BAND_MIN_PX = 1f

/**
 * 行内折射带宽（像素）：先按底栏口径 clamp 到 12~50dp，再取「行高 42%」的较小值。
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
    val clampedPx = (presetHeightDp * density).coerceIn(
        REFRACTION_HEIGHT_MIN_DP * density,
        REFRACTION_HEIGHT_MAX_DP * density
    )
    val safeCapPx = rowHeightPx.coerceAtLeast(0f) * ROW_REFRACTION_BAND_RATIO
    return min(clampedPx, safeCapPx).coerceAtLeast(ROW_REFRACTION_BAND_MIN_PX)
}

/**
 * 行内折射位移量（像素，**正值 = 位移幅度**），与底栏同一段取值（20~120dp）。
 *
 * 传给着色器时要取相反数（底栏 `StableLiquidGlassView.overrides()` 同样传
 * `-refractionOffset`）：`refractedCoord = coord + d * grad`，`grad` 是 SDF 的外法向，
 * 因此只有**负值**才是向内位移，取样点才会落在 node 内部，避免采到 node 边界外的
 * 透明像素而在行边缘出现黑边/透明边。
 *
 * @param presetOffsetDp 该档预设的 `refractionOffsetDp`；若外部传负值先归一为正
 */
internal fun rowRefractionAmountPx(
    presetOffsetDp: Float,
    density: Float
): Float {
    val rawPx = kotlin.math.abs(presetOffsetDp) * density
    return rawPx.coerceIn(
        REFRACTION_OFFSET_MIN_DP * density,
        REFRACTION_OFFSET_MAX_DP * density
    )
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
    /** 白色 tint 的混入强度：与底栏同源，直接取该档预设的 `tintAlpha`。 */
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
 * 由该档材质 + 行的实际像素尺寸算出整组 uniform（纯函数）。
 *
 * 逐值对齐底栏的地方：
 * - `depthEffect` = [ROW_LIQUID_DEPTH_EFFECT]（库 `Config` 默认 0.3）
 * - `contrast` / `whitePoint` / `chromaMultiplier` = 0 / 0 / 1
 *   （对应 `StableLiquidGlassView.overrides()` 的 `noFilter/contrast/whitePoint/chromaMultiplier`）
 * - `chromaticAberration` = `material.dispersion`（同一份 [GlassMaterialPreset] 取值）
 * - `tintAlpha` = `material.tintAlpha`（底栏同样把 tint 交给着色器）
 *
 * 只有几何按行高重新标定（底栏 `pixelSafePill` 与 `setRefraction*` 的同一组规则）：
 * - `refractionHeightPx` / `refractionAmountPx` 见 [rowRefractionBandPx] /
 *   [rowRefractionAmountPx]；
 * - `cornerRadiusPx` 钳到行高的一半（与 `effectiveShaderCornerRadius` 同口径）。
 *
 * @param material 该档最终材质（[GlassMaterialPreset.current] 的产物）
 * @param widthPx 行宽（像素）
 * @param heightPx 行高（像素）
 * @param cornerRadiusPx 行圆角（像素）
 * @param density 屏幕密度
 */
internal fun rowLiquidUniforms(
    material: GlassMaterialPreset.Params,
    widthPx: Float,
    heightPx: Float,
    cornerRadiusPx: Float,
    density: Float
): RowGlassLiquidUniforms {
    val bandPx = rowRefractionBandPx(
        presetHeightDp = material.refractionHeightDp,
        rowHeightPx = heightPx,
        density = density
    )
    val amountPx = rowRefractionAmountPx(
        presetOffsetDp = material.refractionOffsetDp,
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
        chromaticAberration = material.dispersion,
        tintAlpha = material.tintAlpha,
        contrast = ROW_LIQUID_CONTRAST,
        whitePoint = ROW_LIQUID_WHITE_POINT,
        chromaMultiplier = ROW_LIQUID_CHROMA_MULTIPLIER
    )
}

/**
 * 采样矩阵的四个分量（纯数据，便于逐值断言）。
 *
 * 语义与 `BitmapShader.setLocalMatrix` 一致：**位图坐标 → 行局部坐标**
 * （与 `BitmapDrawable.updateShaderMatrix`、`CircleImageView` 等处同一约定：
 * `scale = 目标尺寸 / 位图尺寸`，再 `postTranslate`）。生产侧把它写进一个复用的
 * [android.graphics.Matrix]：`setScale(scaleX, scaleY)` 然后
 * `postTranslate(translateX, translateY)`。
 */
internal data class RowSampleTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float
)

/**
 * 降采样壁纸位图坐标 → 行局部像素坐标 的仿射变换（纯函数）。
 *
 * 底图是按**整屏**对齐构建的（`targetW = screenW / DOWNSCALE`），位图坐标是行局部
 * 坐标的 1/DOWNSCALE：`bitmap = (local + rowLoc - rootLoc) * bitmapSize / rootSize`。
 *
 * `setLocalMatrix` 要的是反方向（位图 → 局部），因此矩阵写作
 * `local = bitmap * rootSize / bitmapSize - (rowLoc - rootLoc)`：
 * scale 必须**放大**回根视图尺寸，平移是未乘 scale 的局部像素偏移。
 *
 * 修正记录（真机核验）：此前按「局部 → 位图」方向填了 `bitmapSize / rootSize` 与
 * `-(rowLoc - rootLoc) * scale`，两处都与实际约定相反。后果是每行把采样点推到
 * 位图范围之外并 CLAMP 到最后一行附近，整页书源行呈现统一的暗色条——参数再对齐
 * 也无法与底栏一致。
 *
 * @param bitmapWidth 降采样底图宽（像素）；≤ 0 时视为不可用，返回单位变换
 * @param bitmapHeight 降采样底图高（像素）；≤ 0 时视为不可用，返回单位变换
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
    if (rootWidth <= 0 || rootHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return RowSampleTransform(1f, 1f, 0f, 0f)
    }
    // 位图 → 局部：降采样位图必须放大回根视图尺寸；平移是行相对根的偏移（局部像素）。
    val scaleX = rootWidth / bitmapWidth.toFloat()
    val scaleY = rootHeight / bitmapHeight.toFloat()
    return RowSampleTransform(
        scaleX = scaleX,
        scaleY = scaleY,
        translateX = -(locationX - rootX).toFloat(),
        translateY = -(locationY - rootY).toFloat()
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

    /** 走既有静态管线（软件 BitmapShader / 硬件高斯）。 */
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
 * 行内 RenderNode 的包围盒（纯函数，像素坐标）。
 *
 * ## 为什么单独抽出来
 *
 * `RenderNode` 的默认位置是 `(0, 0, 0, 0)`：`beginRecording(w, h)` 只决定录制画布大小，
 * **不会**顺带设置 node 的位置。位置仍为空时 `Canvas.drawRenderNode` 不抛任何异常，
 * 只是什么都不画——调用方于是拿到「返回成功但画面没有玻璃」的结果：tint 层按
 * 「液态已上色」让位，行内只剩壳层与描边，透出清晰的壁纸。
 *
 * 底栏库 `LiquidGlassimpl.onSizeChanged` 的做法同样是在 `record()` 之前
 * `node.setPosition(0, 0, width, height)`，这里把这个前提固化成可断言的数据。
 */
internal data class RowGlassNodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/**
 * 行矩形 → RenderNode 包围盒：恒从原点开始，宽高取行尺寸（负数钳到 0）。
 *
 * 渲染器只在尺寸变化时调用一次 `setPosition`，因此这个函数必须只依赖尺寸，
 * 不含任何「行在屏幕上的位置」——node 坐标是行局部的，屏幕位置已经写进采样矩阵。
 */
internal fun rowGlassNodeBounds(width: Int, height: Int): RowGlassNodeBounds =
    RowGlassNodeBounds(
        left = 0,
        top = 0,
        right = width.coerceAtLeast(0),
        bottom = height.coerceAtLeast(0)
    )

/**
 * 行内液态玻璃的降级矩阵（纯函数，逐条可断言）。
 *
 * 判定顺序（任一命中即 [RowLiquidCapability.STATIC]，且都不会留空白帧）：
 * 1. `sdkInt < 33`：没有 `RuntimeShader`，走静态管线；
 * 2. 编译期总闸 [ROW_LIQUID_ENABLED] 关闭：→ 静态管线；
 * 3. 用户关掉「硬件模糊」开关（[ExploreGlassConfig.hardwareBlur]）：复用既有偏好键兜底，
 *    不新增设置项；
 * 4. 墨水屏 / 低内存设备：与 `rowBackground()` 的既有降级一致，牺牲观感换滚动流畅；
 * 5. 非硬件加速 Canvas（软件 layer / 截图 / 部分 ROM 的离屏绘制）：当帧退静态管线，
 *    但这只是**当帧状态**，不置持久故障位；
 * 6. 底图不可用（动图壁纸纯色底、构建失败、已回收）：没有纹理可折射；
 * 7. [liquidFailed] 持久位已置：AGSL 编译或绘制曾经抛过异常，本行不再重试。
 *
 * 注意第 5/6/7 条都是「本次绘制」的事实（由 [rowLiquidDrawableNow] 统一判定），
 * 第 1-4 条是「设备/偏好」的事实。生产代码在构造期调一次本函数
 * （`canvasHardwareAccelerated` / `backdropAvailable` 传 true、`liquidFailed` 传 false
 * 即只筛设备与偏好），绘制期调 [rowLiquidDrawableNow]。
 *
 * 强度不参与判定：「无玻璃」档才是关闭玻璃的开关，其余档即使强度为 0
 * 也应该保留与底栏一致的壳层（底栏在同样强度下也不会消失）。
 *
 * @param sdkInt `Build.VERSION.SDK_INT`
 * @param rowLiquidEnabled 编译期总闸 [ROW_LIQUID_ENABLED]
 * @param hardwareBlurPref 「全局毛玻璃效果 → 硬件模糊」开关
 * @param isEInk 墨水屏模式
 * @param isLowRam 低内存设备
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
    canvasHardwareAccelerated: Boolean,
    backdropAvailable: Boolean,
    liquidFailed: Boolean
): RowLiquidCapability = when {
    sdkInt < ROW_LIQUID_MIN_SDK -> RowLiquidCapability.STATIC
    !rowLiquidEnabled -> RowLiquidCapability.STATIC
    !hardwareBlurPref -> RowLiquidCapability.STATIC
    isEInk -> RowLiquidCapability.STATIC
    isLowRam -> RowLiquidCapability.STATIC
    !rowLiquidDrawableNow(canvasHardwareAccelerated, backdropAvailable, liquidFailed) ->
        RowLiquidCapability.STATIC
    else -> RowLiquidCapability.LIQUID
}
