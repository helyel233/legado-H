package io.legado.app.lib.prefs

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.pkg.WallpaperScaleMode
import io.legado.app.help.config.pkg.WallpaperTransform
import io.legado.app.help.config.pkg.drawTransformedBitmap
import io.legado.app.lib.theme.UiCorner

class GroupPanelImageDrawable(
    private val bitmap: Bitmap,
    private val mode: String,
    private val groupHeight: Int,
    private val offsetY: Int,
    private val topRadius: Float,
    private val bottomRadius: Float,
    private val transform: WallpaperTransform = WallpaperTransform.fromLegacyPanelScaleType(mode)
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (UiCorner.layoutAlpha() * 255).toInt().coerceIn(0, 255)
    }
    private val tileShader by lazy(LazyThreadSafetyMode.NONE) {
        if (transform.mode == WallpaperScaleMode.TILE && !bitmap.isRecycled) {
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        } else {
            null
        }
    }
    private val rect = RectF()
    private val groupRect = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || groupHeight <= 0 || bitmap.isRecycled) return
        rect.set(bounds)
        path.reset()
        path.addRoundRect(
            rect,
            floatArrayOf(
                topRadius, topRadius,
                topRadius, topRadius,
                bottomRadius, bottomRadius,
                bottomRadius, bottomRadius
            ),
            Path.Direction.CW
        )
        groupRect.set(
            bounds.left.toFloat(),
            (bounds.top - offsetY).toFloat(),
            bounds.right.toFloat(),
            (bounds.top - offsetY + groupHeight).toFloat()
        )
        val saveCount = canvas.save()
        canvas.clipPath(path)
        drawTransformedBitmap(
            canvas = canvas,
            bitmap = bitmap,
            bounds = groupRect,
            transform = transform,
            paint = paint,
            tileShader = tileShader
        )
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
