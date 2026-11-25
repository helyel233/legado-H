package io.legado.app.ui.main.explore

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.utils.ColorUtils as AppColorUtils
import java.util.WeakHashMap

/**
 * 同一张对齐底图的两种产物，供两条管线各取所需（两者尺寸完全一致，采样矩阵可共用）：
 * - [raw]：未经软件模糊，硬件管线的 GPU 高斯采样源；
 * - [blurred]：经 `stackBlurSoftware` 预模糊，软件管线（②③）的采样源，
 *   同时也是硬件管线运行期失败时的即时降级源。
 *
 * 两份共存的价值在于「降级零等待」：硬件失败当帧就能切到 blurred 重画，
 * 不必重新触发一次异步构建，也就不会闪一下纯实底或透出清晰壁纸。
 * 代价仅是构建期多一次 IO 线程的软件模糊（按 key 去重、仅壁纸/主题变化时执行一次）。
 */
internal data class GlassBackdrop(
    val raw: Bitmap?,
    val blurred: Bitmap?
)


/**
 * 单个书源名行的毛玻璃背景，与底栏（StableLiquidGlassView + shell overlay）同一套材质：
 * 0. 液态层（API 33+）：[RowGlassLiquidRenderer] 的 AGSL 真折射 + 色散 + GPU 高斯，
 *    采样源是未预模糊的 raw 壁纸位图，tint 由着色器负责——与底栏的
 *    「着色器 → 高斯 → shell → 描边」逐层一致；不可用时下面 1 的三条既有分支原样接管；
 * 1. 玻璃层：按行当前屏幕位置采样模糊壁纸，半径取 [GlassMaterialPreset.Params.blurDp]
 *    （硬件管线由 GPU 出真高斯，降级时用 IO 线程预模糊的位图）；
 * 2. tint 层（仅静态降级）：液态路径的 tint 由着色器负责，静态路径没有着色器，
 *    因此单画一层白色弱着色，alpha 取同一份 [GlassMaterialPreset.Params.tintAlpha]；
 * 3. shell 层（两条路径都画）：底栏 createLiquidGlassShellDrawable 同款三色垂直渐变；
 * 4. 描边层：全局 glassStrokeColor 或 surfaceColor × `Params.shell.stroke`。
 *
 * 所有可变量都只有一份来源——[GlassMaterialPreset.current] 的 [GlassMaterialPreset.Params]。
 * 行内没有增益、没有由色散派生的 rim 高光、也没有拟液态叠加层：底栏没有这些层，
 * 行多画一层就必然比底栏更白/更厚。绘制层只负责把同一份材质画出来。
 */
internal class FrostedGlassDrawable(
    private val host: View,
    private val radiusPx: Float,
    private val baseColor: Int,
    private val material: GlassMaterialPreset.Params,
    /** 全局描边色（非空时覆盖默认的 surfaceColor × strokeAlpha 描边）。 */
    private val strokeColor: Int?,
    private val useHardware: Boolean,
    /** 硬件 / 液态管线的高斯 sigma（像素）；0 = 模糊倍率 0，不额外模糊。 */
    private val blurPx: Float,
    /** 是否尝试行内液态玻璃（A2）；为 false 时只走静态管线。 */
    private val useLiquid: Boolean,
    /**
     * 采样根是否取屏幕而非宿主根视图。对话框是独立窗口，根视图只有面板大小，
     * 按根视图换算会把整张壁纸缩进面板；置 true 后与壁纸位图的屏幕口径一致。
     */
    private val screenAligned: Boolean = false
) : Drawable() {

    fun matchesConfig(
        radiusPx: Float,
        baseColor: Int,
        material: GlassMaterialPreset.Params,
        strokeColor: Int?,
        useHardware: Boolean,
        blurPx: Float,
        useLiquid: Boolean,
        screenAligned: Boolean
    ): Boolean =
        this.radiusPx == radiusPx &&
            this.baseColor == baseColor &&
            this.material == material &&
            this.strokeColor == strokeColor &&
            this.useHardware == useHardware &&
            this.blurPx == blurPx &&
            this.useLiquid == useLiquid &&
            this.screenAligned == screenAligned

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = host.resources.displayMetrics.density
    }

    /** 底栏 shell 同款表面色。 */
    private val surfaceColor =
        if (AppColorUtils.isColorLight(baseColor)) Color.WHITE else Color.rgb(22, 24, 28)

    // 全部直接来自唯一材质入口；行内不做任何二次缩放。
    private val tintBaseAlpha = (material.tintAlpha * 255).toInt()
    private val strokeBaseRatio = material.shell.stroke
    private var shellShaderCache: LinearGradient? = null
    private var alphaFactor = 1f
    private val shaderMatrix = Matrix()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val rect = RectF()
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    /** 最近一次「已同步到绘制」的屏幕坐标；用于判断位置变化是否需要重录内容。 */
    private var cachedLeft = Int.MIN_VALUE
    private var cachedTop = Int.MIN_VALUE

    /** 对齐底图（raw + blurred 两份）；缓存未命中时先为空，异步构建完成后由 [updateBackdrop] 回填。 */
    private var backdrop: GlassBackdrop? = null

    /**
     * API 31+ 的硬件模糊层，仅在 [useHardware] 且设备确实支持时创建。
     * 运行期若该层不可用，[hardwareFailed] 置位，本行改走 [backdrop] 的 blurred 位图，
     * 即 ②③ 已验证的软件管线——不是丢弃效果，也不是透出清晰壁纸。
     */
    private var hardwareLayer: HardwareGlassLayer? = null
    private var hardwareFailed = false

    /**
     * 1.25.10 的行内液态玻璃层（AGSL 真折射+色散）。仅在 [useLiquid] 且 API 33+ 时创建，
     * 创建本身失败（极少数 ROM 上 `RenderNode`/`RuntimeShader` 构造抛异常）只是让本行走
     * 静态管线，不影响任何既有行为。`liquidFailed` 由渲染器自己持有的持久位管理。
     */
    private val liquidLayer: RowGlassLiquidRenderer? =
        if (useLiquid && Build.VERSION.SDK_INT >= ROW_LIQUID_MIN_SDK) {
            runCatching {
                RowGlassLiquidRenderer(
                    host.context, material, host.resources.displayMetrics.density
                )
            }.getOrNull()
        } else {
            null
        }

    init {
        if (useHardware && supportsHardwareBlur() && blurPx > 0f) {
            hardwareLayer = runCatching { HardwareGlassLayer(blurPx) }.getOrNull()
        }
    }

    fun updateBackdrop(next: GlassBackdrop?) {
        if (next === backdrop) return
        backdrop = next
        //位图更换后旧 shader 引用的已是过期位图，必须重建
        shader = null
        shaderBitmap = null
        glassPaint.shader = null
        hardwareLayer?.reset()
        liquidLayer?.reset()
        invalidateSelf()
    }

    /**
     * 布局 / 滚动回调驱动的位置缓存：只有位置真正变化才重算 shader matrix。
     * draw() 内仍会按当帧坐标重采样一次（见 [ensureLocationSampled]），
     * 这里只需保证「位置变了会重录内容」，即 invalidateSelf。
     */
    fun onPositionChanged(left: Int, top: Int, rootLeft: Int, rootTop: Int) {
        location[0] = left
        location[1] = top
        rootLocation[0] = rootLeft
        rootLocation[1] = rootTop
        if (left == cachedLeft && top == cachedTop) return
        cachedLeft = left
        cachedTop = top
        invalidateSelf()
    }

    /**
     * 按**当帧**的屏幕坐标重采样。
     *
     * 早期实现只在位置回调里采样并缓存，前提是「行位移一定会走布局/滚动回调」。
     * 真机验证不成立：RecyclerView 折叠上方行时用 change 动画的 translation 移动子项，
     * 子项自身的 frame 不变、也不产生滚动事件，缓存坐标与实际绘制位置出现偏差，
     * 表现为折叠后下方行玻璃错位、滑动一下（滚动回调）才恢复。
     * `getLocationOnScreen` 只是沿父链累加偏移，相对本帧的 AGSL + 高斯开销可忽略，
     * 因此改为每次绘制都取当前真实坐标，动画帧内也始终与绘制位置对齐。
     */
    private fun ensureLocationSampled() {
        host.getLocationOnScreen(location)
        host.rootView.getLocationOnScreen(rootLocation)
        cachedLeft = location[0]
        cachedTop = location[1]
    }

    init {
        tintPaint.color = Color.WHITE
        tintPaint.alpha = tintBaseAlpha
        applyStrokeColor(1f, strokeBaseRatio)
    }

    private var shellShaderKey: String? = null

    /**
     * 描边色：全局描边色非空时用它（保留其自身 alpha），否则回退
     * surfaceColor × [strokeAlpha] 的底栏同口径着色。[factor] 为 Drawable alpha 系数。
     */
    private fun applyStrokeColor(factor: Float, strokeAlpha: Float) {
        val override = strokeColor
        strokePaint.color = if (override != null) {
            AppColorUtils.withAlpha(override and 0x00ff_ffff, (override ushr 24) / 255f * factor)
        } else {
            AppColorUtils.withAlpha(surfaceColor, strokeAlpha * factor)
        }
    }

    /** 底图可用性统一判定：已回收 / 空尺寸一律视为不可用，交给 shell 兜底。 */
    private fun usable(bitmap: Bitmap): Boolean =
        !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0

    private fun shellShader(
        height: Int,
        start: Float,
        center: Float,
        end: Float
    ): LinearGradient {
        val key = "$height|$start|$center|$end"
        val cached = shellShaderCache
        if (cached != null && shellShaderKey == key) return cached
        // 三色 alpha 取当前绘制路径的壳层口径（液态 = 底栏 1:1，静态 = 行标定）；
        // 上深下浅的垂直渐变同样来自底栏。
        return LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                AppColorUtils.withAlpha(surfaceColor, start),
                AppColorUtils.withAlpha(surfaceColor, center),
                AppColorUtils.withAlpha(surfaceColor, end)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        ).also {
            shellShaderCache = it
            shellShaderKey = key
        }
    }

    override fun draw(canvas: Canvas) {
        val bd = backdrop
        rect.set(bounds)
        // A2 本帧是否真的画出了玻璃层：决定后面要不要让位/叠加既有层。
        var drewLiquidGlass = false
        // 先筛掉已回收 / 空位图：两条管线各自取源，任一缺失都不允许透出清晰壁纸。
        val software = bd?.blurred?.takeIf { usable(it) }
        val raw = bd?.raw?.takeIf { usable(it) }
        // 1.25.11 行内液态（A2）优先用 raw：高斯交给 GPU，与底栏「先着色器、后模糊」
        // 完全同一条链路，也不再受主题背景模糊偏好的二次叠加影响。
        // raw 不可用时退回已预模糊的 blurred，并关闭额外模糊（否则双重模糊）。
        val liquidSource = raw ?: software
        val liquid = liquidLayer?.takeIf {
            rowLiquidDrawableNow(
                canvas.isHardwareAccelerated, liquidSource != null, it.liquidFailed
            )
        }
        // 硬件管线需同时满足 API 31+、层创建成功、此前无持久故障、raw 底图可用。
        // raw 缺失时直接让位给软件管线：拿 blurred 去做 GPU 模糊会变成双重模糊。
        // 液态层激活时同一笔玻璃由 shader 完成，硬件高斯层让位（否则重复出图）。
        val activeLayer = hardwareLayer?.takeIf {
            liquid == null && !hardwareFailed && supportsHardwareBlur() && raw != null
        }
        // 硬件激活时 glassBitmap 就是 raw，液态用 raw 或降级后的 blurred，软件用它自己的 blurred。
        val glassBitmap = when {
            liquid != null -> liquidSource
            activeLayer != null -> raw
            else -> software
        }
        if (glassBitmap != null) {
            val root = host.rootView
            // 对话框窗口的根视图只有面板大小，不能当壁纸的对齐基准；此时改用屏幕尺寸与
            // 屏幕原点，和 decodeRaw 里「按 displayMetrics 构建位图」的口径保持一致。
            val rootWidth = if (screenAligned) host.resources.displayMetrics.widthPixels else root.width
            val rootHeight = if (screenAligned) host.resources.displayMetrics.heightPixels else root.height
            if (rootWidth > 0 && rootHeight > 0) {
                ensureLocationSampled()
                if (screenAligned) {
                    rootLocation[0] = 0
                    rootLocation[1] = 0
                }
                // 采样矩阵：与软件 / 硬件 / 液态三条管线共用同一份口径（纯函数在
                // RowGlassLiquidSpec.rowSampleTransform）。方向是「位图 → 局部」，
                // 与 BitmapShader.setLocalMatrix 的约定一致：降采样位图放大回根视图尺寸。
                val transform = rowSampleTransform(
                    bitmapWidth = glassBitmap.width,
                    bitmapHeight = glassBitmap.height,
                    rootWidth = rootWidth,
                    rootHeight = rootHeight,
                    locationX = location[0],
                    locationY = location[1],
                    rootX = rootLocation[0],
                    rootY = rootLocation[1]
                )
                shaderMatrix.setScale(transform.scaleX, transform.scaleY)
                shaderMatrix.postTranslate(transform.translateX, transform.translateY)
                var drewGlass = false
                if (liquid != null) {
                    // 液态管线：AGSL 真折射 + 色散 + GPU 高斯，tint 由着色器负责。
                    // 失败时渲染器自己置**进程级**故障位并返回 false，本帧立即走静态分支。
                    drewLiquidGlass = liquid.draw(
                        canvas, glassBitmap,
                        // raw 走 GPU 高斯；只有退回 blurred 时才不额外模糊。
                        if (raw != null) blurPx else 0f,
                        rect, radiusPx, shaderMatrix,
                        glassPaint.alpha, glassPaint.colorFilter
                    )
                    drewGlass = drewLiquidGlass
                }
                if (!drewGlass && activeLayer != null) {
                    // 硬件管线：把这一层录进独立 RenderNode，GPU 做真高斯。
                    drewGlass = activeLayer.draw(
                        canvas, glassBitmap, rect, radiusPx, shaderMatrix,
                        glassPaint.alpha, glassPaint.colorFilter
                    )
                    if (!drewGlass && canvas.isHardwareAccelerated) {
                        // 硬件 Canvas 上仍失败 = RenderEffect/drawRenderNode 的持久性故障，
                        // 置位后本行不再重试，避免逐帧抛异常。位图更换也不复位：设备级
                        // 故障换一张底图不会自己变好，而软件管线已验证可用、观感连续。
                        hardwareFailed = true
                    }
                    // 非硬件加速 Canvas（软件 layer / 截图等）只是当帧状态，不置位，
                    // 下一帧回到硬件 Canvas 会自动恢复真高斯。
                }
                if (!drewGlass && software != null) {
                    // 软件管线（②③ 已实测验证的既有实现）：底图在 IO 线程预模糊，
                    // 这里用 BitmapShader 按位置采样。硬件失败当帧也走这里，观感连续。
                    if (shader == null || shaderBitmap !== software) {
                        shader = BitmapShader(software, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        shaderBitmap = software
                        glassPaint.shader = shader
                    }
                    shader?.setLocalMatrix(shaderMatrix)
                    canvas.drawRoundRect(rect, radiusPx, radiusPx, glassPaint)
                }
            }
        }
        // 静态降级路径没有着色器，tint 由这一层补上；液态成功时着色器已经完成
        // tint，再叠一层会比底栏更白，因此只在此让位。
        if (!drewLiquidGlass) {
            canvas.drawRoundRect(rect, radiusPx, radiusPx, tintPaint)
        }
        if (rect.height() >= 1f) {
            val shaderHeight = rect.height().toInt().coerceAtLeast(1)
            shellPaint.shader = shellShader(
                shaderHeight,
                material.shell.start,
                material.shell.center,
                material.shell.end
            )
            shellPaint.alpha = (255 * alphaFactor).toInt()
            canvas.drawRoundRect(rect, radiusPx, radiusPx, shellPaint)
        }
        // 描边：与底栏同一份 shell.stroke，液态与静态路径不再各有一套系数。
        applyStrokeColor(alphaFactor, strokeBaseRatio)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        val factor = alpha.coerceIn(0, 255) / 255f
        if (factor != alphaFactor) {
            alphaFactor = factor
            glassPaint.alpha = (255 * factor).toInt()
            tintPaint.alpha = (tintBaseAlpha * factor).toInt()
            applyStrokeColor(factor, strokeBaseRatio)
            invalidateSelf()
        }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * 圆角轮廓：对话框把结论 View 设为 `clipToOutline`，若这里只给矩形轮廓，
     * 子内容会按直角裁剪、在圆角处露出方角。与绘制用的圆角保持同一半径。
     */
    override fun getOutline(outline: Outline) {
        super.getOutline(outline)
        if (bounds.width() > 0 && bounds.height() > 0) {
            outline.setRoundRect(bounds, radiusPx)
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glassPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
        shellPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        // 硬件层的 paint 在 draw() 里读 glassPaint.colorFilter 同步，需触发一次重绘才会生效。
        invalidateSelf()
    }
}

/**
 * API 31+ 的硬件模糊层：把「玻璃层采样」录进一颗独立 [RenderNode]，
 * 再对该 RenderNode 设置 [RenderEffect.createBlurEffect] 交给 GPU 做真高斯。
 *
 * 为什么不直接 `View.setRenderEffect(blur)`：
 * 那会模糊整行 View 的 RenderNode，书源名文字、箭头、加载动画会一起糊掉。
 * 这里 RenderEffect 只作用于「玻璃层自己」这颗独立 RenderNode，文字与
 * tint/shell/描边都在 [FrostedGlassDrawable.draw] 的普通 Canvas 上绘制，不受影响。
 *
 * 为什么不需要「离屏预渲染整屏玻璃位图」：
 * RenderNode 的 display list 由调用方传入的 `shaderMatrix` 在**录制时**就完成位置采样，
 * 与 ②③ 的 `BitmapShader` 使用同一份矩阵，因此随滚动重录即可，无需任何位图回读、
 * Surface、HardwareRenderer 或跨线程同步；模糊发生在合成阶段，无额外全屏 Bitmap 内存。
 *
 * 生命周期：每个行背景 Drawable 持有一层，`reset()` 在位图更换时丢弃旧录制内容。
 * 类本身带 [RequiresApi]，只在 SDK_INT >= S 时由 [FrostedGlassDrawable] 创建。
 */
@RequiresApi(Build.VERSION_CODES.S)
private class HardwareGlassLayer(private val blurRadiusPx: Float) {

    private val renderNode = RenderNode("ExploreGlassBackdrop")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null

    init {
        // CLAMP：模糊采样越出内容边界时钳制到边缘像素，避免行边缘出现暗边/透明边。
        // 半径由外部按「底栏 frosted 同款 dp 口径」换算成屏幕像素，已在调用方限幅。
        renderNode.setRenderEffect(
            RenderEffect.createBlurEffect(
                blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP
            )
        )
    }

    /** 底图更换后丢弃录制内容与 shader 引用，避免复用已回收/过期的位图。 */
    fun reset() {
        shader = null
        shaderBitmap = null
        paint.shader = null
        renderNode.discardDisplayList()
    }

    /**
     * 画玻璃层。返回 false 表示当前环境不可用（canvas 非硬件加速、RenderEffect 异常等），
     * 调用方据此立即回落到 ②③ 软件管线。
     */
    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        rect: RectF,
        cornerRadius: Float,
        matrix: Matrix,
        alpha: Int,
        colorFilter: ColorFilter?
    ): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val width = rect.width().toInt()
        val height = rect.height().toInt()
        if (width <= 0 || height <= 0) return false
        return runCatching {
            val current = shader
            val next = if (current != null && shaderBitmap === bitmap) {
                current
            } else {
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    shader = it
                    shaderBitmap = bitmap
                }
            }
            // 与软件管线共用同一个 matrix：Drawable 作为 View background 时 bounds 恒为
            // (0,0,w,h)，RenderNode 录制坐标与之同为局部原点，采样位置因此完全一致；
            // 外层 translate(rect.left, rect.top) 只负责把结果摆到 bounds 起点。
            next.setLocalMatrix(matrix)
            paint.shader = next
            paint.alpha = alpha
            paint.colorFilter = colorFilter

            // beginRecording(width, height) 会隐式把 RenderNode position 设为
            // (0,0,width,height)，无需再 setPosition；滚动导致 matrix 变化才逐帧重录，
            // 静止时 GlassPositionTracker 不触发 invalidate，没有额外开销。
            val recording = renderNode.beginRecording(width, height)
            try {
                // 录整块矩形（不画圆角），圆角由外层 clipPath 裁剪：
                // 模糊核在圆角内侧不会因「内容提前结束」而出现半透明羽化边。
                recording.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            } finally {
                // 即便 drawRect 抛异常也要闭合录制，避免 RenderNode 停留在未结束状态。
                renderNode.endRecording()
            }

            clipPath.reset()
            clipPath.addRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                cornerRadius, cornerRadius, Path.Direction.CW
            )
            val saveCount = canvas.save()
            try {
                // rect 可能不在原点（Drawable bounds 有偏移），平移到对应位置后裁剪、绘制
                canvas.translate(rect.left, rect.top)
                canvas.clipPath(clipPath)
                canvas.drawRenderNode(renderNode)
            } finally {
                // 异常也必须恢复 canvas 状态，否则同帧后续的 tint/shell/描边会错位绘制。
                canvas.restoreToCount(saveCount)
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * 位置跟踪绑定所在的 keyed tag key。
 *
 * 单独暴露（而不是留在 [GlassPositionTracker] 内部）是为了让「切档 / 关背景图后
 * 旧跟踪是否真的被解绑」可被单测直接观察：解绑的唯一可观测副作用就是把这一格
 * tag 写成 null，测试据此断言，不必反射私有字段、也不必伪造 tracker。
 */
internal const val GLASS_POSITION_TRACKING_TAG_KEY: Int = 0x5F_4753_50

/**
 * 把 [FrostedGlassDrawable] 挂到 host 的布局 / 滚动 / attach 回调上，
 * 用「位置缓存 + 变化才重算」替代 draw() 里的逐帧 getLocationOnScreen。
 *
 * 同一可滚动祖先（[RecyclerView] / [NestedScrollView]）与同一 [ViewTreeObserver] 上的
 * 所有已挂载行共享同一个协调器（各只注册 1 个滚动监听 / 1 个绘制前监听），避免 N 行
 * 扇出 N 倍回调；行复用重复绑定同一 host 时直接就地复用既有 [TrackerBinding]，
 * 不再逐次拆装监听。视图脱离窗口或主动调用 [detach] 时自动从协调器注销并清理空闲监听。
 */
internal object GlassPositionTracker {

    private const val TAG_KEY = GLASS_POSITION_TRACKING_TAG_KEY
    private const val TAG_KEY_SCROLLER = 0x5F_4753_51
    private const val TAG_KEY_COORDINATOR = 0x5F_4753_52

    private val preDrawCoordinators = WeakHashMap<ViewTreeObserver, PreDrawCoordinator>()

    fun attach(host: View, drawable: FrostedGlassDrawable) {
        val existing = host.getTag(TAG_KEY) as? TrackerBinding
        if (existing != null && existing.host === host) {
            existing.updateDrawable(drawable)
            if (host.isAttachedToWindow) {
                existing.ensureAttached(host)
            }
            return
        }
        detach(host)
        val binding = TrackerBinding(host, drawable)
        host.setTag(TAG_KEY, binding)
        binding.attach()
        if (host.isAttachedToWindow) {
            binding.onViewAttachedToWindow(host)
        }
    }

    /**
     * 显式解绑：切到无玻璃档 / 降级分支时不会再走 [attach]，若不处理，上一份
     * [TrackerBinding]（含 host 的 attach/layout 监听、与共享滚动/PreDraw 协调器的注册
     * 以及旧 drawable 引用）会滞留在 tag 里直到 View detach 或切回玻璃档。
     */
    fun detach(host: View) {
        (host.getTag(TAG_KEY) as? TrackerBinding)?.detach(host)
        host.setTag(TAG_KEY, null)
    }

    /** 设置页预览：Host 被 Compose 平移到新位置后，主动重算一次采样坐标。 */
    fun refresh(host: View) {
        (host.getTag(TAG_KEY) as? TrackerBinding)?.updatePosition(host)
    }

    private fun getOrCreateScrollCoordinator(target: View): ScrollableCoordinator {
        return (target.getTag(TAG_KEY_COORDINATOR) as? ScrollableCoordinator)
            ?: ScrollableCoordinator(target).also {
                target.setTag(TAG_KEY_COORDINATOR, it)
            }
    }

    private fun getOrCreatePreDrawCoordinator(observer: ViewTreeObserver): PreDrawCoordinator {
        return preDrawCoordinators[observer]
            ?: PreDrawCoordinator(observer).also {
                preDrawCoordinators[observer] = it
            }
    }

    private class ScrollableCoordinator(val target: View) {
        private val bindings = LinkedHashSet<TrackerBinding>()
        private val dispatchSnapshot = ArrayList<TrackerBinding>()
        private var dispatching = false
        private var installed = false

        private val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                notifyBindings()
            }
        }

        private val scrollChangeListener =
            NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                notifyBindings()
            }

        fun register(binding: TrackerBinding) {
            if (bindings.add(binding) && !installed) {
                install()
            }
        }

        fun unregister(binding: TrackerBinding) {
            if (bindings.remove(binding) && bindings.isEmpty()) {
                uninstall()
            }
        }

        private fun install() {
            installed = true
            when (target) {
                is RecyclerView -> target.addOnScrollListener(scrollListener)
                is NestedScrollView -> {
                    target.setOnScrollChangeListener(scrollChangeListener)
                    // 打标记录「监听是我们装的」，detach 时只清自己的，
                    // 不动别人（如 host 自身业务）设置的监听
                    target.setTag(TAG_KEY_SCROLLER, scrollChangeListener)
                }
            }
        }

        private fun uninstall() {
            if (!installed) return
            installed = false
            when (target) {
                is RecyclerView -> target.removeOnScrollListener(scrollListener)
                is NestedScrollView -> {
                    if (target.getTag(TAG_KEY_SCROLLER) === scrollChangeListener) {
                        target.setOnScrollChangeListener(null as NestedScrollView.OnScrollChangeListener?)
                        target.setTag(TAG_KEY_SCROLLER, null)
                    }
                }
            }
            if (target.getTag(TAG_KEY_COORDINATOR) === this) {
                target.setTag(TAG_KEY_COORDINATOR, null)
            }
        }

        private fun notifyBindings() {
            if (bindings.isEmpty()) return
            if (dispatching) {
                bindings.toList().forEach { it.updatePosition() }
                return
            }
            dispatching = true
            try {
                dispatchSnapshot.clear()
                dispatchSnapshot.addAll(bindings)
                for (i in 0 until dispatchSnapshot.size) {
                    dispatchSnapshot[i].updatePosition()
                }
            } finally {
                dispatchSnapshot.clear()
                dispatching = false
            }
        }
    }

    /**
     * 共享绘制前协调器：同窗口所有玻璃行共用一个 `OnPreDrawListener`，
     * 覆盖「展开 / 折叠上方行引发 RecyclerView translation 位移」这类不产生滚动事件的场景。
     * 注册时保存 observer 实例：View 脱离窗口后 `viewTreeObserver` 可能返回新的游离 observer。
     */
    private class PreDrawCoordinator(
        val observer: ViewTreeObserver
    ) : ViewTreeObserver.OnPreDrawListener {
        private val bindings = LinkedHashSet<TrackerBinding>()
        private val dispatchSnapshot = ArrayList<TrackerBinding>()
        private var dispatching = false
        private var installed = false

        fun register(binding: TrackerBinding) {
            if (bindings.add(binding) && !installed) {
                install()
            }
        }

        fun unregister(binding: TrackerBinding) {
            if (bindings.remove(binding) && bindings.isEmpty()) {
                uninstall()
            }
        }

        private fun install() {
            if (!observer.isAlive) return
            observer.addOnPreDrawListener(this)
            installed = true
        }

        private fun uninstall() {
            if (installed && observer.isAlive) {
                observer.removeOnPreDrawListener(this)
            }
            installed = false
            if (preDrawCoordinators[observer] === this) {
                preDrawCoordinators.remove(observer)
            }
        }

        override fun onPreDraw(): Boolean {
            if (!observer.isAlive || bindings.isEmpty()) {
                uninstall()
                return true
            }
            if (dispatching) {
                bindings.toList().forEach { it.updatePosition() }
                return true
            }
            dispatching = true
            try {
                dispatchSnapshot.clear()
                dispatchSnapshot.addAll(bindings)
                for (i in 0 until dispatchSnapshot.size) {
                    dispatchSnapshot[i].updatePosition()
                }
            } finally {
                dispatchSnapshot.clear()
                dispatching = false
            }
            return true
        }
    }

    private class TrackerBinding(
        val host: View,
        private var drawable: FrostedGlassDrawable
    ) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private val scrollCoordinators = ArrayList<ScrollableCoordinator>(2)
        private var preDrawCoordinator: PreDrawCoordinator? = null
        private val location = IntArray(2)
        private val rootLocation = IntArray(2)

        fun updateDrawable(next: FrostedGlassDrawable) {
            drawable = next
        }

        fun attach() {
            host.addOnAttachStateChangeListener(this)
            host.addOnLayoutChangeListener(this)
        }

        fun detach(v: View) {
            v.removeOnAttachStateChangeListener(this)
            v.removeOnLayoutChangeListener(this)
            onViewDetachedFromWindow(v)
        }

        fun ensureAttached(v: View) {
            if (scrollCoordinators.isEmpty() && preDrawCoordinator == null) {
                onViewAttachedToWindow(v)
                return
            }
            val observer = v.viewTreeObserver
            val currentNearestScrollable = nearestScrollableAncestor(v)
            val sameObserver = preDrawCoordinator?.observer === observer || !observer.isAlive
            val sameNearestScrollable =
                scrollCoordinators.firstOrNull()?.target === currentNearestScrollable
            if (!sameObserver || !sameNearestScrollable) {
                onViewAttachedToWindow(v)
                return
            }
            updatePosition(v)
        }

        override fun onViewAttachedToWindow(v: View) {
            // attach 可能连续发生（复用/重挂）而中间没有走 detach，
            // 先注销旧协调器注册，避免重复挂接
            unregisterCoordinators()
            var parent = v.parent
            while (parent is ViewGroup) {
                if (parent is RecyclerView || parent is NestedScrollView) {
                    val coordinator = getOrCreateScrollCoordinator(parent)
                    coordinator.register(this)
                    scrollCoordinators.add(coordinator)
                }
                parent = parent.parent
            }
            val observer = v.viewTreeObserver
            if (observer.isAlive) {
                val preDraw = getOrCreatePreDrawCoordinator(observer)
                preDraw.register(this)
                preDrawCoordinator = preDraw
            }
            updatePosition(v)
        }

        override fun onViewDetachedFromWindow(v: View) {
            unregisterCoordinators()
        }

        private fun unregisterCoordinators() {
            for (i in 0 until scrollCoordinators.size) {
                scrollCoordinators[i].unregister(this)
            }
            scrollCoordinators.clear()
            preDrawCoordinator?.unregister(this)
            preDrawCoordinator = null
        }

        override fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        ) {
            updatePosition(v)
        }

        /** 供 [GlassPositionTracker.refresh] 与共享协调器调用：主动重采样位置。 */
        fun updatePosition(v: View = host) {
            val root = v.rootView
            if (v.width <= 0 || v.height <= 0 || root.width <= 0 || root.height <= 0) return
            v.getLocationOnScreen(location)
            root.getLocationOnScreen(rootLocation)
            drawable.onPositionChanged(location[0], location[1], rootLocation[0], rootLocation[1])
        }
    }

    private fun nearestScrollableAncestor(view: View): View? {
        var parent = view.parent
        while (parent is ViewGroup) {
            when (parent) {
                is RecyclerView, is NestedScrollView -> return parent
            }
            parent = parent.parent
        }
        return null
    }
}
