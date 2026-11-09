package io.legado.app.ui.main.explore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import androidx.annotation.RequiresApi
import io.legado.app.R
import io.legado.app.ui.main.GlassMaterialPreset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 发现页书源名行的「行内液态玻璃」渲染器（1.25.10，方案 A2'）。
 *
 * ## 它做什么
 *
 * 复用底栏 `com.qmdeve.liquidglass` 那颗 AGSL（MIT，已复制到
 * `res/raw/glass_row_liquid.agsl`，数学逐字节未改），但**完全不用它的 Java 宿主**：
 *
 * 1. 内容源 = 仓库里早就有的降采样壁纸位图 `GlassBackdrop.raw`（IO 线程构建、按 key
 *    缓存，见 `ExploreGlassBackdrop.backdrop()`）；
 * 2. 把「按行位置对齐的壁纸矩形」录进**我们自己的一颗** [RenderNode]（一条
 *    `drawRect`，纳秒级）。录制前必须 `setPosition(0, 0, w, h)`（[ensureNodePosition]）：
 *    `beginRecording(w, h)` 只定录制画布大小，不定 node 包围盒；空包围盒时
 *    `drawRenderNode` 会**静默不画**，是本层唯一「返回成功却不可见」的路径；
 * 3. 给这颗 node 设 `createChainEffect(createRuntimeShaderEffect(...), createBlurEffect(...))`
 *    ——与底栏库完全相同的「先着色器、后高斯」链路（见 [ensureEffect]）；
 * 4. `canvas.drawRenderNode(node)`，再剪到行圆角内。
 *
 * 1.25.12 起链路与材质参数都向底栏看齐：tint 由着色器负责（`material.tintAlpha`），
 * 模糊由 GPU 对 raw 位图做真高斯（sigma = 该档 blurDp × density，与底栏同段限幅），
 * 不再使用预先软件模糊的 `blurred` 位图（那会与主题背景模糊叠加成双倍模糊）。
 *
 * 折射带宽、位移量、uniform 全部来自纯函数 [rowLiquidUniforms]，因此
 * 「行高标定」「与底栏逐值对齐」都能在 JVM 单测里锁住。
 *
 * ## 为什么这里不会有 1.25.8 的 native 崩溃
 *
 * 1.25.8 的 aborts 根因是「对**采样源视图子树**录制 → 录制过程中又画到玻璃自己 →
 * RenderNode 重入录制」。本类里 `beginRecording` 之后画的**只有一条 `drawRect`**，
 * 参数是我们自己的位图与矩阵，**没有任何 `View.draw(canvas)` 调用**。
 * 也就是说崩溃路径上根本不存在对应的调用（结构性排除），而不是「加了判断所以不会」。
 * 铁律：本文件禁止出现 `View.draw` / `ViewGroup` / `LiquidGlass` / `bind`。
 *
 * ## 为什么不用 `setInputShader("content", ...)`
 *
 * `createRuntimeShaderEffect(shader, "content")` 会把 node 的录制内容绑定到
 * `content` 这个 uniform shader 上，这正是底栏库（生产环境已验证）的做法。
 * 我们不再自己 `setInputShader`，避免与 effect 的绑定冲突；内容像素由
 * 录制时的 [contentPaint]（`BitmapShader` + 采样矩阵）提供，两者语义等价。
 *
 * ## 线程与生命周期
 *
 * 每行一个实例（屏幕内可见行约 6~15 个），且随 `onBindViewHolder` 反复重建。
 * `RuntimeShader` 首次构造会做一次 AGSL 编译，因此**惰性到第一次真正绘制时**才建。
 * 失败状态记在**进程级** [RowLiquidRuntimeState.shaderFailed]（不是实例字段）：
 * 一旦 AGSL 编译或绘制抛异常，**所有实例（含之后重建的）**退回静态管线，
 * 不逐次 rebind 重试、日志全进程只打一条。
 */
@RequiresApi(ROW_LIQUID_MIN_SDK)
internal class RowGlassLiquidRenderer(
    private val context: Context,
    private val material: GlassMaterialPreset.Params,
    private val density: Float
) {

    companion object {
        /** RenderNode 名字（gfxinfo / 渲染调试里可见，便于真机定位）。 */
        private const val NODE_NAME = "ExploreRowLiquidGlass"

        /** AGSL 源码进程级缓存：`res/raw` 只读一次，避免每行各自 decode。 */
        @Volatile
        private var agslSourceCache: String? = null

        /**
         * 降级日志的「全进程只打一次」标记。
         * 与 [RowLiquidRuntimeState.shaderFailed] 分开：故障位可能被多处并发置位，
         * 日志用 CAS 保证只输出一条可定位的第一现场。
         */
        private val logOnce = AtomicBoolean(false)

        /**
         * 读取并缓存 AGSL 源码。返回 null 表示资源读不出来（打包异常），
         * 调用方据此把本行降级到静态管线而不是崩溃（本进程内一直降级，杀进程重开 App 会重试）。
         */
        fun agslSource(context: Context): String? {
            agslSourceCache?.let { return it }
            return runCatching {
                context.resources.openRawResource(R.raw.glass_row_liquid)
                    .use { it.readBytes().decodeToString() }
            }.getOrNull()?.also { agslSourceCache = it }
        }
    }

    private val renderNode = RenderNode(NODE_NAME)

    /**
     * 最近一次写进 node 的包围盒尺寸（见 [ensureNodePosition]）。
     * 位置只随行尺寸变化，静止帧不重复调用 `setPosition`。
     */
    private var nodeWidth = -1
    private var nodeHeight = -1

    /** 录制内容用的 paint：只负责把「对齐好的壁纸」画进 node。 */
    private val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val clipPath = Path()

    private var shader: RuntimeShader? = null
    private var contentShader: BitmapShader? = null
    private var contentBitmap: Bitmap? = null
    private var uniformSignature: String? = null

    /**
     * RenderEffect 链的缓存键（shader / 内容位图 / 高斯 sigma）。
     *
     * 与底栏库同一策略：effect 只在参数真正变化时重建，静止帧只重录一条 `drawRect`。
     * sigma 用容差比较，避免滑块微小抖动导致逐帧新建 effect。
     */
    private var effectShader: RuntimeShader? = null
    private var effectBitmap: Bitmap? = null
    private var effectBlurSigma: Float = -1f

    /**
     * 持久故障位——**进程级**，见 [RowLiquidRuntimeState]。
     *
     * 故意不是实例字段：本类随 `onBindViewHolder` 反复重建（行复用），实例级故障位会让
     * 「失败一次」退化成「每次 rebind 重新编译、重新抛异常、重新打日志」。
     * 一旦置位，**所有实例（含未来新建的）**立刻走静态管线，本行与后续每一行都不再重试。
     */
    val liquidFailed: Boolean
        get() = RowLiquidRuntimeState.shaderFailed

    /** 底图更换时丢弃录制内容与 shader 引用，避免复用已回收/过期的位图。 */
    fun reset() {
        contentShader = null
        contentBitmap = null
        contentPaint.shader = null
        effectShader = null
        effectBitmap = null
        effectBlurSigma = -1f
        renderNode.discardDisplayList()
        // RuntimeShader 与 uniform 不复位；**进程级故障位尤其不复位**——
        // 换一张壁纸不会让 AGSL 突然变得可编译，这里若清位就等于恢复「逐次 rebind 重试」。
    }

    /**
     * 画玻璃层（真折射 + 色散边光 + GPU 高斯）。
     *
     * @param canvas 目标画布；非硬件加速时立即返回 false（当帧降级，不置故障位）
     * @param bitmap 未预模糊的降采样壁纸（GPU 负责模糊，与底栏同一条链路）
     * @param blurSigmaPx 高斯 sigma（像素）；≤ 0.01 时退化为「不额外模糊」
     * @param rect 行的绘制矩形（Drawable bounds，通常原点在 (0,0)）
     * @param cornerRadiusPx 行圆角
     * @param matrix 位图坐标 → 行局部坐标 的采样矩阵（来自纯函数
     *   [rowSampleTransform]，与软件/硬件管线共用同一份）
     * @param alpha Drawable alpha（0-255）
     * @param colorFilter Drawable colorFilter
     * @return true 表示已由本层完成玻璃绘制；false 表示调用方必须回落到既有管线
     */
    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        blurSigmaPx: Float,
        rect: RectF,
        cornerRadiusPx: Float,
        matrix: Matrix,
        alpha: Int,
        colorFilter: ColorFilter?
    ): Boolean {
        if (liquidFailed) return false
        if (!canvas.isHardwareAccelerated) return false
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val width = rect.width().toInt()
        val height = rect.height().toInt()
        if (width <= 0 || height <= 0) return false
        return try {
            // 必须先定包围盒：缺这一步 drawRenderNode 面对空 node 会**静默**不画，
            // 却仍走完本函数返回 true，于是 tint 让位、行内只剩壳层与描边。
            ensureNodePosition(width, height)
            val activeShader = ensureShader(bitmap, matrix, width, height, cornerRadiusPx)
                ?: return false
            applyUniforms(activeShader, width, height, cornerRadiusPx)
            ensureEffect(activeShader, bitmap, blurSigmaPx)

            contentPaint.shader = contentShader
            contentPaint.alpha = alpha.coerceIn(0, 255)
            contentPaint.colorFilter = colorFilter

            // 只录一条 drawRect：内容是「按行位置对齐的壁纸」，不是任何 View 子树。
            val recording = renderNode.beginRecording(width, height)
            try {
                recording.drawRect(0f, 0f, width.toFloat(), height.toFloat(), contentPaint)
            } finally {
                // 即便 drawRect 抛异常也要闭合录制，避免 RenderNode 停留在未结束状态。
                renderNode.endRecording()
            }

            // 圆角由 clipPath 裁剪：模糊/折射在圆角内侧不会因为「内容提前结束」而羽化。
            clipPath.reset()
            clipPath.addRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                cornerRadiusPx, cornerRadiusPx, Path.Direction.CW
            )
            val saveCount = canvas.save()
            try {
                canvas.translate(rect.left, rect.top)
                canvas.clipPath(clipPath)
                canvas.drawRenderNode(renderNode)
            } finally {
                // 异常也必须恢复 canvas，否则同帧后续 tint/shell/rim/描边会错位。
                canvas.restoreToCount(saveCount)
            }
            true
        } catch (throwable: Throwable) {
            // 这里必须真的兜住：RuntimeShader 编译、setFloatUniform 命名、RenderEffect
            // 创建、drawRenderNode 都可能在特定 GPU/驱动上抛（含 RuntimeException 与
            // LinkageError 之外的 Error）。置持久位后本行退回静态管线。
            markFailed(throwable)
            false
        }
    }

    /**
     * 把 node 位置钉成行矩形（`0, 0, width, height`），只在尺寸变化时调用。
     *
     * 与底栏库 `LiquidGlassimpl.onSizeChanged` 的 `node.setPosition(0, 0, w, h)` 同一步：
     * `beginRecording(w, h)` 只设置录制画布大小，**不设置 node 位置**；位置为空时
     * `drawRenderNode` 不抛异常、不画任何像素，是本类唯一会「成功但不可见」的路径。
     * 包围盒本身由纯函数 [rowGlassNodeBounds] 产出，可在 JVM 单测里断言非空。
     */
    private fun ensureNodePosition(width: Int, height: Int) {
        val bounds = rowGlassNodeBounds(width, height)
        if (nodeWidth == bounds.right && nodeHeight == bounds.bottom) return
        renderNode.setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom)
        nodeWidth = bounds.right
        nodeHeight = bounds.bottom
    }

    /**
     * 取（或惰性创建）RuntimeShader。
     *
     * `RuntimeShader(String)` 会触发一次 AGSL 编译，因此在第一次真正绘制时才建，
     * 后续复用同一个实例（uniform 变更不需要重建 shader）。
     */
    private fun ensureShader(
        bitmap: Bitmap,
        matrix: Matrix,
        width: Int,
        height: Int,
        cornerRadiusPx: Float
    ): RuntimeShader? {
        val current = shader
        if (current != null && contentBitmap === bitmap) {
            contentShader?.setLocalMatrix(matrix)
            return current
        }
        val source = agslSource(context) ?: run {
            markFailed(IllegalStateException("AGSL 资源不可读"))
            return null
        }
        val nextShader = RuntimeShader(source)
        val nextContent = BitmapShader(
            bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
        ).apply { setLocalMatrix(matrix) }

        shader = nextShader
        contentShader = nextContent
        contentBitmap = bitmap
        uniformSignature = null
        // effect 绑定的是旧的 shader 实例，换 shader 必须重建
        effectShader = null
        // 所有 uniform 交给 applyUniforms 统一写入（签名未变时不会重复写）
        applyUniforms(nextShader, width, height, cornerRadiusPx)
        return nextShader
    }

    /**
     * 按底栏库同一条链路更新 RenderEffect：`createChainEffect(着色器, 高斯)`。
     *
     * 顺序必须与底层库一致（先着色器、后模糊）：底栏生产环境验证的就是这个顺序，
     * 反过来会得到「先糊再折射」的不同观感。sigma 来自该档预设的 blurDp × density，
     * 与底栏 `MainActivity.setupLiquidGlass()` 的换算完全相同；缓存键包含位图，
     * 换壁纸/换行宽都会重建。
     */
    private fun ensureEffect(
        target: RuntimeShader,
        bitmap: Bitmap,
        blurSigmaPx: Float
    ) {
        val sigma = blurSigmaPx.coerceAtLeast(0f)
        if (effectShader === target && effectBitmap === bitmap &&
            kotlin.math.abs(effectBlurSigma - sigma) <= 0.01f
        ) {
            return
        }
        val shaderEffect = RenderEffect.createRuntimeShaderEffect(target, "content")
        val blurEffect = if (sigma > 0.01f) {
            // CLAMP：行边缘采样越界时钳到边缘像素，避免出现暗边/透明边（与底栏库一致）
            RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
        } else {
            null
        }
        renderNode.setRenderEffect(
            if (blurEffect != null) {
                RenderEffect.createChainEffect(shaderEffect, blurEffect)
            } else {
                shaderEffect
            }
        )
        renderNode.discardDisplayList()
        effectShader = target
        effectBitmap = bitmap
        effectBlurSigma = sigma
    }

    /**
     * 写入与行几何相关的 uniform（幂等，只有签名变化时才真正写）。
     *
     * `cornerRadii` 是 float4：行是普通圆角矩形，四角半径相等，但必须**分别**传
     * （着色器用 `centeredCoord` 的符号选角，传错个数会直接被 Skia 拒绝）。
     *
     * tint / 色散 / 对比度等材质量也在这里统一写入：它们全部来自
     * [GlassMaterialPreset.Params]，与底栏同源，避免渲染器与纯函数层各自维护一套常量。
     */
    private fun applyUniforms(
        target: RuntimeShader,
        width: Int,
        height: Int,
        cornerRadiusPx: Float
    ) {
        val uniforms = rowLiquidUniforms(
            material = material,
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            cornerRadiusPx = cornerRadiusPx,
            density = density
        )
        val signature = uniforms.signature()
        if (signature == uniformSignature) return
        target.setFloatUniform("size", uniforms.widthPx, uniforms.heightPx)
        target.setFloatUniform("offset", uniforms.offsetXPx, uniforms.offsetYPx)
        target.setFloatUniform(
            "cornerRadii",
            uniforms.cornerRadiusPx, uniforms.cornerRadiusPx,
            uniforms.cornerRadiusPx, uniforms.cornerRadiusPx
        )
        target.setFloatUniform("refractionHeight", uniforms.refractionHeightPx)
        target.setFloatUniform("refractionAmount", uniforms.refractionAmountPx)
        target.setFloatUniform(
            "tintColor",
            ROW_LIQUID_TINT_RED, ROW_LIQUID_TINT_GREEN, ROW_LIQUID_TINT_BLUE
        )
        target.setFloatUniform("tintAlpha", uniforms.tintAlpha)
        target.setFloatUniform("depthEffect", uniforms.depthEffect)
        target.setFloatUniform("chromaticAberration", uniforms.chromaticAberration)
        target.setFloatUniform("contrast", uniforms.contrast)
        target.setFloatUniform("whitePoint", uniforms.whitePoint)
        target.setFloatUniform("chromaMultiplier", uniforms.chromaMultiplier)
        uniformSignature = signature
    }

    /**
     * 置持久故障位并清空 node：之后**全进程剩余生命周期**都走既有静态管线。
     *
     * 该位不落任何持久化存储，用户杀进程重开 App 会重新尝试 AGSL
     * （若该机型确实编译不过，会很快再次置位，日志仍只打一条）。
     */
    private fun markFailed(throwable: Throwable) {
        RowLiquidRuntimeState.shaderFailed = true
        runCatching { renderNode.discardDisplayList() }
        // 全进程只打一次：本类随行复用不断重建，日志若按实例打会刷屏。
        // 用 CAS 保证并发下也只打一条；失败必须无声降级，用户侧不应看到任何异常表现。
        if (logOnce.compareAndSet(false, true)) {
            Log.w(
                "ExploreGlass",
                "行内液态玻璃降级为静态管线（进程内持久）：" +
                    "${throwable.javaClass.simpleName}: ${throwable.message}"
            )
        }
    }
}
