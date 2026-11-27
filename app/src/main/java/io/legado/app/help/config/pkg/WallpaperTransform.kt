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

enum class WallpaperScaleMode(
    val id: String,
    val label: String,
    val minScale: Float,
    val maxScale: Float,
    val description: String
) {
    CROP(
        id = "crop",
        label = "裁切",
        minScale = 1.0f,
        maxScale = 4.0f,
        description = "保持原图比例铺满并裁切多余边缘，拖动选取原图任意区域（无黑边）"
    ),
    FIT(
        id = "fit",
        label = "等比例",
        minScale = 0.5f,
        maxScale = 4.0f,
        description = "保持原图比例完整显示，不裁切原图，留白处透出主题底色"
    ),
    FILL(
        id = "fill",
        label = "适应屏幕",
        minScale = 1.0f,
        maxScale = 4.0f,
        description = "整张原图贴合目标区域宽高铺满，无留白无黑边（放大后可平移）"
    ),
    TILE(
        id = "tile",
        label = "平铺",
        minScale = 0.25f,
        maxScale = 4.0f,
        description = "按原图比例无缝重复平铺（100% 时单块长边约占区域宽度的 35%），缩放调密度，拖动调偏移"
    );

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
        val safeScale = if (scale.isFinite()) {
            scale.coerceIn(mode.minScale, mode.maxScale)
        } else {
            1f.coerceIn(mode.minScale, mode.maxScale)
        }
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

data class WallpaperPanBounds(
    val sx: Float,
    val sy: Float,
    val drawnWidth: Float,
    val drawnHeight: Float,
    val maxShiftX: Float,
    val maxShiftY: Float,
    val isTile: Boolean
)

fun computeWallpaperPanBounds(
    srcWidth: Float,
    srcHeight: Float,
    dstWidth: Float,
    dstHeight: Float,
    transform: WallpaperTransform
): WallpaperPanBounds {
    if (srcWidth <= 0f || srcHeight <= 0f || dstWidth <= 0f || dstHeight <= 0f) {
        return WallpaperPanBounds(
            sx = 1f,
            sy = 1f,
            drawnWidth = 0f,
            drawnHeight = 0f,
            maxShiftX = 0f,
            maxShiftY = 0f,
            isTile = transform.mode == WallpaperScaleMode.TILE
        )
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
            val b = (dstWidth * 0.35f) / maxOf(srcWidth, srcHeight)
            baseSx = b
            baseSy = b
        }
    }
    val sx = baseSx * t.scale
    val sy = baseSy * t.scale
    val drawnW = srcWidth * sx
    val drawnH = srcHeight * sy
    val isTile = t.mode == WallpaperScaleMode.TILE
    val maxShiftX = if (isTile) drawnW else abs(dstWidth - drawnW) / 2f
    val maxShiftY = if (isTile) drawnH else abs(dstHeight - drawnH) / 2f
    return WallpaperPanBounds(
        sx = sx,
        sy = sy,
        drawnWidth = drawnW,
        drawnHeight = drawnH,
        maxShiftX = maxShiftX,
        maxShiftY = maxShiftY,
        isTile = isTile
    )
}

fun applyWallpaperPan(
    transform: WallpaperTransform,
    panX: Float,
    panY: Float,
    srcWidth: Float,
    srcHeight: Float,
    dstWidth: Float,
    dstHeight: Float
): WallpaperTransform {
    val t = transform.normalized()
    val bounds = computeWallpaperPanBounds(srcWidth, srcHeight, dstWidth, dstHeight, t)
    val nextX: Float
    val nextY: Float
    if (bounds.isTile) {
        val rawX = t.offsetX + if (bounds.maxShiftX > 0.5f) panX / bounds.maxShiftX else 0f
        val rawY = t.offsetY + if (bounds.maxShiftY > 0.5f) panY / bounds.maxShiftY else 0f
        nextX = wrapTileOffset(rawX)
        nextY = wrapTileOffset(rawY)
    } else {
        nextX = if (bounds.maxShiftX > 0.5f) {
            (t.offsetX + panX / bounds.maxShiftX).coerceIn(WallpaperTransform.MIN_OFFSET, WallpaperTransform.MAX_OFFSET)
        } else {
            0f
        }
        nextY = if (bounds.maxShiftY > 0.5f) {
            (t.offsetY + panY / bounds.maxShiftY).coerceIn(WallpaperTransform.MIN_OFFSET, WallpaperTransform.MAX_OFFSET)
        } else {
            0f
        }
    }
    return t.copy(offsetX = nextX, offsetY = nextY).normalized()
}

private fun wrapTileOffset(value: Float): Float {
    if (!value.isFinite()) return 0f
    var v = value
    while (v > 1f) v -= 2f
    while (v < -1f) v += 2f
    return v
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
    val bounds = computeWallpaperPanBounds(srcWidth, srcHeight, dstWidth, dstHeight, t)
    val dx = dstLeft + (dstWidth - bounds.drawnWidth) / 2f + t.offsetX * bounds.maxShiftX
    val dy = dstTop + (dstHeight - bounds.drawnHeight) / 2f + t.offsetY * bounds.maxShiftY
    outMatrix.reset()
    outMatrix.setScale(bounds.sx, bounds.sy)
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
