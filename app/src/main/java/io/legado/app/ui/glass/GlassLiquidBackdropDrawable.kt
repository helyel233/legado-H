package io.legado.app.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.ColorUtils
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ROW_LIQUID_MIN_SDK
import io.legado.app.ui.main.explore.RowGlassLiquidRenderer
import kotlin.math.roundToInt

/**
 * 「截图底图」版的真玻璃 Drawable——与全 App 其它玻璃面（底栏 / 顶栏 / 目录页 /
 * 书架 / 发现页行）走**同一颗 AGSL 着色器** [RowGlassLiquidRenderer]。
 *
 * ## 为什么需要它（架构级根因）
 *
 * 阅读设置弹窗 / 界面弹窗 / 书籍详情页在第五轮落到了 [GlassFrostDrawable]：一套
 * 纯 Canvas 手绘的「模糊位图 + 着色 + 两条直线高光 + 1dp 描边」。它**没有**真正
 * 的折射 / 色散 / GPU 高斯，因此无论怎么调那两条直线的 alpha，都不可能长得像
 * 用户认可的目录页 / 底栏玻璃——那些页面用的是 [RowGlassLiquidRenderer]（AGSL
 * RuntimeShader，真折射 + 色散 + GPU 模糊）。两套不同管线，观感永远对不上。
 *
 * 这三页之所以当初没走 AGSL，是结构性误判：
 * - 弹窗是独立 Dialog 窗口，`StableLiquidGlassView` 依赖「采样源是兄弟子树」，
 *   跨窗口拿不到 Activity 里的 `read_view`；
 * - 详情页 `showsWallpaperBehindContent=false`、`bg_book` 是 ImageView，壁纸域
 *   与内容域采样都不成立。
 *
 * 但 [RowGlassLiquidRenderer] **不采样任何 View 子树**：它把一张位图录进自己的
 * RenderNode（`drawRect`，无 `View.draw`、无重入风险），再套同一颗 AGSL shader。
 * 三页本来就已经截好了背后内容的位图——正可以直接喂给它，得到与其他页面
 * **逐值一致**的材质（[GlassMaterialPreset.Params]：模糊 / 着色 / 色散 / 折射）。
 *
 * ## 降级
 *
 * - API < 33 / 非硬件加速 Canvas / 着色器构造或绘制失败 → 回退到软件路径
 *   （直接画已模糊的底图 + 弱白着色），仍是半透明磨砂，不是死板实底；
 * - solid 档由调用方在入口判否、保持原实底，本类不参与。
 */
internal class GlassLiquidBackdropDrawable(
    context: Context,
    /** 已降采样 + 软件模糊的截图底图（面板压着的那块内容）。 */
    private val backdrop: Bitmap,
    private val material: GlassMaterialPreset.Params,
    private val density: Float,
    private val cornerRadiusPx: Float,
    private val corners: SurfaceCorners,
    private val strokeColor: Int,
    private val strokeWidthPx: Float,
    /** 可读性着色层（阅读页背景色向弹窗表面色混合），自身带 alpha。 */
    private val tintColor: Int,
    /** 壳层渐变 RGB（浅底白 / 深底近黑）。 */
    private val surfaceRgb: Int,
    private val shell: GlassMaterialPreset.ShellAlphas
) : Drawable() {

    private val liquid: RowGlassLiquidRenderer? =
        if (Build.VERSION.SDK_INT >= ROW_LIQUID_MIN_SDK) {
            runCatching { RowGlassLiquidRenderer(context, material, density) }.getOrNull()
        } else {
            null
        }

    private val path = Path()
    private val boundsF = RectF()
    private val matrix = Matrix()
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = strokeWidthPx
    }

    private var drawableAlpha = 255
    private var colorFilter: ColorFilter? = null
    private var shellShader: LinearGradient? = null
    private var shellShaderKey: String? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        boundsF.set(bounds)
        path.reset()
        val radius = cornerRadiusPx.coerceAtLeast(0f)
        when (corners) {
            SurfaceCorners.NONE -> path.addRect(boundsF, Path.Direction.CW)
            SurfaceCorners.ALL -> path.addRoundRect(boundsF, radius, radius, Path.Direction.CW)
            SurfaceCorners.TOP -> path.addRoundRect(
                boundsF,
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
                Path.Direction.CW
            )
        }
        shellShader = null
        shellShaderKey = null
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val w = boundsF.width()
        val h = boundsF.height()
        if (w <= 0f || h <= 0f || backdrop.isRecycled ||
            backdrop.width <= 0 || backdrop.height <= 0
        ) return

        // 位图坐标 → Drawable 局部坐标：截图就是面板区域的等比例降采样，等比放大铺满。
        matrix.setScale(w / backdrop.width.toFloat(), h / backdrop.height.toFloat())

        val save = canvas.save()
        canvas.clipPath(path)

        // 1. 真 AGSL 玻璃：折射 + 色散 + 着色（底图已软件模糊，这里不再做 GPU 模糊）。
        //    与底栏 / 发现页行共用同一颗 shader 与同一组材质参数。
        val drewLiquid = liquid?.draw(
            canvas, backdrop, 0f, boundsF, cornerRadiusPx, matrix, drawableAlpha, colorFilter
        ) ?: false

        if (!drewLiquid) {
            // 软件回退：直接铺模糊底图 + 弱白着色（材质自带 tintAlpha），仍是磨砂面。
            backdropPaint.alpha = drawableAlpha
            backdropPaint.colorFilter = colorFilter
            canvas.drawBitmap(backdrop, matrix, backdropPaint)
            tintPaint.color = Color.WHITE
            tintPaint.alpha = (255 * material.tintAlpha * drawableAlpha / 255f).roundToInt()
            tintPaint.colorFilter = colorFilter
            canvas.drawPath(path, tintPaint)
        }

        // 2. 可读性着色：真玻璃时减弱，让磨砂透出来；软件回退时保留全部强度保对比度。
        tintPaint.color = tintColor
        val tintScale = if (drewLiquid) LIQUID_READABILITY_TINT_SCALE else 1f
        tintPaint.alpha = (Color.alpha(tintColor) * drawableAlpha / 255f * tintScale).roundToInt()
        tintPaint.colorFilter = colorFilter
        canvas.drawPath(path, tintPaint)

        // 3. 壳层渐变（与底栏 shell overlay 同一份 alpha）。
        val gradient = shellGradient()
        if (gradient != null) {
            shellPaint.shader = gradient
            shellPaint.alpha = drawableAlpha
            shellPaint.colorFilter = colorFilter
            canvas.drawPath(path, shellPaint)
            shellPaint.shader = null
        }
        canvas.restoreToCount(save)

        // 4. 描边：全 App 同一份 glass_stroke，画在裁剪外，保住边界辨识度。
        if (strokeWidthPx > 0f && Color.alpha(strokeColor) > 0) {
            strokePaint.alpha = (Color.alpha(strokeColor) * drawableAlpha / 255f).roundToInt()
            strokePaint.colorFilter = colorFilter
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun shellGradient(): LinearGradient? {
        val height = boundsF.height()
        if (height <= 0f) return null
        val key = "${height.toInt()}|${shell.start}|${shell.center}|${shell.end}|$surfaceRgb"
        shellShaderKey?.takeIf { it == key }?.let { return shellShader }
        val gradient = LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(
                ColorUtils.setAlphaComponent(surfaceRgb, (255 * shell.start).roundToInt()),
                ColorUtils.setAlphaComponent(surfaceRgb, (255 * shell.center).roundToInt()),
                ColorUtils.setAlphaComponent(surfaceRgb, (255 * shell.end).roundToInt())
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shellShader = gradient
        shellShaderKey = key
        return gradient
    }

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) return
        when (corners) {
            SurfaceCorners.NONE -> outline.setRect(bounds)
            SurfaceCorners.ALL -> outline.setRoundRect(bounds, cornerRadiusPx)
            SurfaceCorners.TOP -> outline.setConvexPath(path)
        }
        outline.alpha = Color.alpha(tintColor) / 255f
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        this.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /**
         * 真玻璃路径上可读性着色的保留比例。AGSL 着色器已经叠了材质自带的白色 tint
         * （frosted ≈ 0.25），若再铺满原本 0.74 的深色 tint 会把磨砂压成近似纯色——
         * 那正是 [GlassFrostDrawable] 被驳回的观感。保留约一半既压住透出的正文笔画、
         * 保住文字对比度，又让折射 / 模糊的色块变化肉眼可辨。
         */
        const val LIQUID_READABILITY_TINT_SCALE = 0.5f
    }
}
