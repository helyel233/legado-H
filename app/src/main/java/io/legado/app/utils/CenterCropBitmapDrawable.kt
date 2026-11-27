package io.legado.app.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import io.legado.app.help.config.pkg.WallpaperScaleMode
import io.legado.app.help.config.pkg.WallpaperTransform
import io.legado.app.help.config.pkg.drawTransformedBitmap

class CenterCropBitmapDrawable(
    resources: Resources,
    private val bitmap: Bitmap,
    private val transform: WallpaperTransform = WallpaperTransform.DEFAULT
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val drawRect = RectF()
    private val tileShader by lazy(LazyThreadSafetyMode.NONE) {
        if (transform.mode == WallpaperScaleMode.TILE && !bitmap.isRecycled) {
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        } else {
            null
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        drawRect.set(bounds)
        drawTransformedBitmap(
            canvas = canvas,
            bitmap = bitmap,
            bounds = drawRect,
            transform = transform,
            paint = paint,
            tileShader = tileShader
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
