package io.legado.app.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import io.legado.app.lib.theme.surface.SurfaceCorners
import kotlin.math.roundToInt

/**
 * 在同一个圆角裁剪路径内绘制「模糊底图 + 着色 + 壳层渐变 + 折射高光 + 色散边 + 描边」。
 *
 * ## 与 [io.legado.app.lib.theme.surface.SurfaceDrawable] 的分工
 *
 * `SurfaceDrawable` 只画「底图 + 着色 + 描边」，适合壁纸域（底图本身有细节）；
 * 本类额外画壳层与折射边缘，专门解决**底图是纯色 / 近似纯色**时「只有透明、没有玻璃」
 * 的缺陷：详情页背景是纯色主题、阅读面板底下是大片纯色正文，这两种场景里
 * 模糊像素与着色叠完和实底几乎同色，只有边缘能给肉眼一个「这是玻璃」的证据。
 *
 * 边缘层只沿内侧 1~2dp 绘制，不覆盖内容区，因此正文对比度仍由 [tintColor] 的
 * alpha 单独决定——详情页卡片沿用 WCAG 倒推的 0.80，到这里也不会退化。
 *
 * [backdrop] 允许为 null：此时只画着色 + 壳层 + 边缘，在纯色底上仍有完整玻璃观感，
 * 且没有任何模糊 / 采样开销。这是 solid 档之外所有「截图失败」分支的正确降级：
 * 不退回死板实底，而是退回「纯壳层玻璃」。
 */
internal class GlassFrostDrawable(
    private val backdrop: Bitmap?,
    private val tintColor: Int,
    private val surfaceRgb: Int,
    private val spec: GlassFrostSpec,
    private val cornerRadiusPx: Float,
    private val corners: SurfaceCorners,
    private val strokeColor: Int,
    private val strokeWidthPx: Float,
    private val edgeWidthPx: Float,
    private val edgeInsetPx: Float
) : Drawable() {

    private val path = Path()
    private val boundsF = RectF()
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val dispersionStartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF7FD4FF.toInt()
    }
    private val dispersionEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFC98A.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }

    private var drawableAlpha = 255
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
        val save = canvas.save()
        canvas.clipPath(path)

        // 1. 真实模糊像素：有底图时铺满裁剪区（底图按目标区域生成，center-crop 退化为整图）。
        backdrop?.takeUnless(Bitmap::isRecycled)?.let { bitmap ->
            if (bitmap.width > 0 && bitmap.height > 0) {
                backdropPaint.alpha = drawableAlpha
                canvas.drawBitmap(
                    bitmap,
                    centerCropSrcRect(bitmap, boundsF),
                    bounds,
                    backdropPaint
                )
            }
        }

        // 2. 着色层：alpha 由调用方按对比度倒推，本类不放大也不缩小。
        tintPaint.alpha = (Color.alpha(tintColor) * drawableAlpha / 255f).roundToInt()
        canvas.drawPath(path, tintPaint)

        // 3. 壳层三色垂直渐变：纯色底上唯一能看出「面有厚度」的一层。
        val gradient = shellGradient()
        if (gradient != null) {
            shellPaint.shader = gradient
            shellPaint.alpha = drawableAlpha
            canvas.drawPath(path, shellPaint)
            shellPaint.shader = null
        }

        // 4~5. 边缘折射：上缘镜面高光、下缘暗带；极弱青/暖色散收在两端。
        // 只画内侧，不覆盖正文区域。
        val inset = edgeInsetPx.coerceAtMost(minOf(boundsF.width(), boundsF.height()) / 3f)
        val left = boundsF.left + inset
        val right = boundsF.right - inset
        if (right > left) {
            val topY = boundsF.top + edgeWidthPx / 2f
            val bottomY = boundsF.bottom - edgeWidthPx / 2f
            highlightPaint.strokeWidth = edgeWidthPx
            highlightPaint.alpha = (255 * spec.edgeAlpha * drawableAlpha / 255f).roundToInt()
            canvas.drawLine(left, topY, right, topY, highlightPaint)
            shadePaint.strokeWidth = edgeWidthPx
            shadePaint.alpha =
                (255 * spec.edgeAlpha * 0.8f * drawableAlpha / 255f).roundToInt()
            canvas.drawLine(left, bottomY, right, bottomY, shadePaint)

            val dispersionAlpha =
                (255 * spec.dispersionAlpha * drawableAlpha / 255f).roundToInt()
            if (dispersionAlpha > 0) {
                dispersionStartPaint.strokeWidth = edgeWidthPx
                dispersionStartPaint.alpha = dispersionAlpha
                dispersionEndPaint.strokeWidth = edgeWidthPx
                dispersionEndPaint.alpha = dispersionAlpha
                canvas.drawLine(left, topY, left + edgeWidthPx * 3f, topY, dispersionStartPaint)
                canvas.drawLine(
                    right - edgeWidthPx * 3f,
                    bottomY,
                    right,
                    bottomY,
                    dispersionEndPaint
                )
            }
        }
        canvas.restoreToCount(save)

        // 6. 描边：全 App 同一份 glass_stroke，画在裁剪外，保住边界辨识度。
        if (strokeWidthPx > 0f && Color.alpha(strokeColor) > 0) {
            strokePaint.alpha = (Color.alpha(strokeColor) * drawableAlpha / 255f).roundToInt()
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun shellGradient(): LinearGradient? {
        val height = boundsF.height()
        if (height <= 0f) return null
        val key = "${height.toInt()}|${spec.shellStartAlpha}|${spec.shellCenterAlpha}|" +
            "${spec.shellEndAlpha}|$surfaceRgb"
        shellShaderKey?.takeIf { it == key }?.let { return shellShader }
        val gradient = LinearGradient(
            0f,
            boundsF.top,
            0f,
            boundsF.bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(
                    surfaceRgb,
                    (255 * spec.shellStartAlpha).roundToInt().coerceIn(0, 255)
                ),
                ColorUtils.setAlphaComponent(
                    surfaceRgb,
                    (255 * spec.shellCenterAlpha).roundToInt().coerceIn(0, 255)
                ),
                ColorUtils.setAlphaComponent(
                    surfaceRgb,
                    (255 * spec.shellEndAlpha).roundToInt().coerceIn(0, 255)
                )
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shellShader = gradient
        shellShaderKey = key
        return gradient
    }

    /** 等比居中裁剪源矩形：底图与 bounds 宽高比不一致时铺满不变形。 */
    private fun centerCropSrcRect(bitmap: Bitmap, dst: RectF): Rect {
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetRatio = dst.width() / dst.height()
        return if (bitmapRatio > targetRatio) {
            val cropWidth = (bitmap.height * targetRatio).roundToInt()
            val left = (bitmap.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetRatio).roundToInt()
            val top = (bitmap.height - cropHeight) / 2
            Rect(0, top, bitmap.width, top + cropHeight)
        }
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
        backdropPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
        shellPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
