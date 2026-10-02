package io.legado.app.ui.main.explore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.ui.main.MainThemeBackgroundState
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.stackBlur
import java.io.File
import kotlin.math.max

/**
 * 发现页书源名行的毛玻璃背景。
 *
 * 取主界面背景图层（与主题背景同一文件/裁剪/模糊设置）生成低分辨率高斯模糊位图，
 * 行背景按行在屏幕上的实时位置采样模糊图，滚动时随位置重新采样，
 * 得到「透出背后壁纸并磨砂」的玻璃观感，避免书源名与壁纸内容直接重合。
 *
 * 强度（0-100）由主题设置「发现页毛玻璃效果」（themeExploreGlassBlur，日夜间分离）控制：
 * 0 为关闭（保留原透明背景），数值越大模糊与玻璃底色越明显。
 */
object ExploreGlassBackdrop {

    /** 未设置时的默认毛玻璃强度。 */
    const val LEVEL_DEFAULT = 60

    /** 背景位图相对屏幕的降采样比例，模糊后肉眼不可分辨且体积/耗时可控。 */
    private const val DOWNSCALE = 6f

    private data class BackdropCache(
        val key: String,
        val bitmap: Bitmap?
    )

    @Volatile
    private var cache: BackdropCache? = null

    fun level(context: Context): Int {
        if (AppConfig.isEInkMode) return 0
        return context.getPrefInt(ThemeRuntimeKeys.themeExploreGlassBlur(), LEVEL_DEFAULT)
            .coerceIn(0, 100)
    }

    /** 返回毛玻璃行背景；level<=0 或无法构建时返回 null，调用方保留默认透明背景。 */
    fun rowBackground(host: View, level: Int): Drawable? {
        if (level <= 0) return null
        val context = host.context
        val strength = level / 100f
        return FrostedGlassDrawable(
            host = host,
            backdrop = backdrop(context, strength),
            radiusPx = context.resources.getDimension(R.dimen.ui_panel_radius),
            tintColor = context.themeMutedColorOrDefault(),
            tintAlpha = 0.16f + 0.44f * strength
        )
    }

    private fun backdrop(context: Context, strength: Float): Bitmap? {
        val state = MainThemeBackgroundState.from(context)
        val metrics = context.resources.displayMetrics
        val targetW = max(64, (metrics.widthPixels / DOWNSCALE).toInt())
        val targetH = max(64, (metrics.heightPixels / DOWNSCALE).toInt())
        val file = state.file
        val key = listOf(
            file?.absolutePath.orEmpty(),
            file?.length() ?: 0L,
            file?.lastModified() ?: 0L,
            state.blur,
            state.crop?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
            AppConfig.isNightTheme,
            targetW,
            (strength * 20).toInt()
        ).joinToString("|")
        synchronized(this) {
            cache?.takeIf { it.key == key }?.let { return it.bitmap }
        }
        val bitmap = buildBitmap(file, state.blur, state.fallbackColor, state.crop, targetW, targetH, strength)
        synchronized(this) {
            val old = cache
            cache = BackdropCache(key, bitmap)
            if (old != null && old.key != key) {
                old.bitmap?.takeIf { it !== bitmap }?.recycle()
            }
        }
        return bitmap
    }

    private fun buildBitmap(
        file: File?,
        blurPref: Int,
        fallbackColor: Int,
        crop: ComposeThemeImageCrop?,
        targetW: Int,
        targetH: Int,
        strength: Float
    ): Bitmap? {
        var source: Bitmap? = null
        if (file != null && file.isFile && file.canRead()) {
            source = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW * 2 &&
                    bounds.outHeight / (sample * 2) >= targetH * 2
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(fallbackColor)
        source?.let { src ->
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val cropRect = if (crop != null) {
                RectF(
                    src.width * crop.left.coerceIn(0f, 1f),
                    src.height * crop.top.coerceIn(0f, 1f),
                    src.width * crop.right.coerceIn(0f, 1f),
                    src.height * crop.bottom.coerceIn(0f, 1f)
                )
            } else {
                RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            }
            if (cropRect.width() >= 1f && cropRect.height() >= 1f) {
                // 与主题背景图层一致：居中裁剪铺满屏幕
                val scale = max(targetW / cropRect.width(), targetH / cropRect.height())
                val dstW = cropRect.width() * scale
                val dstH = cropRect.height() * scale
                val dx = (targetW - dstW) / 2f
                val dy = (targetH - dstH) / 2f
                canvas.drawBitmap(
                    src,
                    Rect(
                        cropRect.left.toInt(),
                        cropRect.top.toInt(),
                        cropRect.right.toInt(),
                        cropRect.bottom.toInt()
                    ),
                    RectF(dx, dy, dx + dstW, dy + dstH),
                    paint
                )
            }
            if (src !== result) {
                src.recycle()
            }
        }
        // 主题背景自身的模糊设置按降采样比例折算，再叠加毛玻璃强度
        val radius = (blurPref / DOWNSCALE + 2f + strength * 16f).toInt().coerceIn(2, 25)
        return runCatching { result.stackBlur(radius) }.getOrNull() ?: result
    }
}

/**
 * 单个书源名行的毛玻璃背景：圆角矩形内先按行当前屏幕位置采样模糊壁纸，
 * 再叠一层主题柔和色的玻璃底，保证文字与壁纸内容分离。
 */
private class FrostedGlassDrawable(
    private val host: View,
    private val backdrop: Bitmap?,
    private val radiusPx: Float,
    tintColor: Int,
    tintAlpha: Float
) : Drawable() {

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tintColor
        alpha = (tintAlpha.coerceIn(0f, 1f) * 255).toInt()
    }
    private val shaderMatrix = Matrix()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val rect = RectF()
    private var shader: BitmapShader? = null

    override fun draw(canvas: Canvas) {
        val b = backdrop
        rect.set(bounds)
        if (b != null && b.width > 0 && b.height > 0) {
            val root = host.rootView
            if (root.width > 0 && root.height > 0) {
                host.getLocationOnScreen(location)
                root.getLocationOnScreen(rootLocation)
                val scaleX = b.width / root.width.toFloat()
                val scaleY = b.height / root.height.toFloat()
                shaderMatrix.setScale(scaleX, scaleY)
                shaderMatrix.postTranslate(
                    -(location[0] - rootLocation[0]) * scaleX,
                    -(location[1] - rootLocation[1]) * scaleY
                )
                if (shader == null) {
                    shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    glassPaint.shader = shader
                }
                shader?.setLocalMatrix(shaderMatrix)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, glassPaint)
            }
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, tintPaint)
    }

    override fun setAlpha(alpha: Int) {
        tintPaint.alpha = alpha
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glassPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
    }
}
