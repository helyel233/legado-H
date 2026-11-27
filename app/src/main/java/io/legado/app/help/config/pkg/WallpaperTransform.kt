package io.legado.app.help.config.pkg

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import io.legado.app.help.config.ThemeConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class WallpaperScaleMode(val id: String, val label: String) {
    CROP("crop", "裁切"),
    FIT("fit", "等比例"),
    FILL("fill", "适应屏幕"),
    TILE("tile", "平铺");

    companion object {
        fun fromId(raw: String?): WallpaperScaleMode {
            val normalized = raw?.trim()?.lowercase(Locale.US)
            return entries.firstOrNull { it.id == normalized } ?: CROP
        }
    }
}

data class WallpaperTransform(
    val mode: WallpaperScaleMode = WallpaperScaleMode.CROP,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    fun normalized(): WallpaperTransform {
        val safeScale = if (scale.isFinite()) scale.coerceIn(MIN_SCALE, MAX_SCALE) else 1f
        val safeX = if (offsetX.isFinite()) offsetX.coerceIn(MIN_OFFSET, MAX_OFFSET) else 0f
        val safeY = if (offsetY.isFinite()) offsetY.coerceIn(MIN_OFFSET, MAX_OFFSET) else 0f
        return copy(scale = safeScale, offsetX = safeX, offsetY = safeY)
    }

    val isDefault: Boolean
        get() = mode == WallpaperScaleMode.CROP &&
            abs(scale - 1f) < 0.001f &&
            abs(offsetX) < 0.001f &&
            abs(offsetY) < 0.001f

    fun encode(): String {
        val t = normalized()
        return "${t.mode.id}|${format3(t.scale)}|${format3(t.offsetX)}|${format3(t.offsetY)}"
    }

    fun summaryText(): String {
        val t = normalized()
        val pct = (t.scale * 100f).roundToInt()
        val base = "${t.mode.label} · ${pct}%"
        val xPct = (t.offsetX * 100f).roundToInt()
        val yPct = (t.offsetY * 100f).roundToInt()
        if (xPct == 0 && yPct == 0) return base
        val xPart = if (xPct >= 0) "+${xPct}%" else "${xPct}%"
        val yPart = if (yPct >= 0) "+${yPct}%" else "${yPct}%"
        return "$base · X$xPart Y$yPart"
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 4.0f
        const val MIN_OFFSET = -1f
        const val MAX_OFFSET = 1f

        const val SLOT_MAIN = "main"
        const val SLOT_TOP_BAR = "topBar"
        const val SLOT_BOTTOM_BAR = "bottomBar"
        const val SLOT_BOOK_INFO = "bookInfo"
        const val SLOT_PANEL = "panel"
        const val SLOT_WELCOME = "welcome"

        val DEFAULT = WallpaperTransform()

        fun decode(raw: String?): WallpaperTransform? {
            val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val parts = trimmed.split('|')
            if (parts.isEmpty()) return null
            val mode = WallpaperScaleMode.fromId(parts[0])
            val scale = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
            val offsetX = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            val offsetY = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
            return WallpaperTransform(
                mode = mode,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY
            ).normalized()
        }

        fun fromLegacyPanelScaleType(raw: String?): WallpaperTransform {
            return if (raw == ThemeConfig.PANEL_BG_FIT) {
                WallpaperTransform(mode = WallpaperScaleMode.FIT)
            } else {
                DEFAULT
            }
        }

        private fun format3(value: Float): String = String.format(Locale.US, "%.3f", value)
    }
}

fun buildWallpaperMatrix(
    srcWidth: Float,
    srcHeight: Float,
    dstWidth: Float,
    dstHeight: Float,
    transform: WallpaperTransform,
    dstLeft: Float = 0f,
    dstTop: Float = 0f,
    outMatrix: Matrix = Matrix()
): Matrix {
    if (srcWidth <= 0f || srcHeight <= 0f || dstWidth <= 0f || dstHeight <= 0f) {
        outMatrix.reset()
        return outMatrix
    }
    val t = transform.normalized()
    val baseSx: Float
    val baseSy: Float
    when (t.mode) {
        WallpaperScaleMode.CROP -> {
            val b = maxOf(dstWidth / srcWidth, dstHeight / srcHeight)
            baseSx = b
            baseSy = b
        }
        WallpaperScaleMode.FIT -> {
            val b = minOf(dstWidth / srcWidth, dstHeight / srcHeight)
            baseSx = b
            baseSy = b
        }
        WallpaperScaleMode.FILL -> {
            baseSx = dstWidth / srcWidth
            baseSy = dstHeight / srcHeight
        }
        WallpaperScaleMode.TILE -> {
            val b = minOf(dstWidth / srcWidth, dstHeight / srcHeight) * 0.5f
            baseSx = b
            baseSy = b
        }
    }
    val sx = baseSx * t.scale
    val sy = baseSy * t.scale
    val dx = dstLeft + (dstWidth - srcWidth * sx) / 2f + t.offsetX * dstWidth
    val dy = dstTop + (dstHeight - srcHeight * sy) / 2f + t.offsetY * dstHeight
    outMatrix.reset()
    outMatrix.setScale(sx, sy)
    outMatrix.postTranslate(dx, dy)
    return outMatrix
}

fun drawTransformedBitmap(
    canvas: Canvas,
    bitmap: Bitmap,
    bounds: RectF,
    transform: WallpaperTransform,
    paint: Paint,
    tileShader: BitmapShader? = null
) {
    if (bounds.isEmpty || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
    val matrix = buildWallpaperMatrix(
        srcWidth = bitmap.width.toFloat(),
        srcHeight = bitmap.height.toFloat(),
        dstWidth = bounds.width(),
        dstHeight = bounds.height(),
        transform = transform,
        dstLeft = bounds.left,
        dstTop = bounds.top
    )
    if (transform.mode == WallpaperScaleMode.TILE) {
        val shader = tileShader ?: BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader.setLocalMatrix(matrix)
        val previousShader = paint.shader
        paint.shader = shader
        canvas.drawRect(bounds, paint)
        paint.shader = previousShader
    } else {
        val save = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(bitmap, matrix, paint)
        canvas.restoreToCount(save)
    }
}
