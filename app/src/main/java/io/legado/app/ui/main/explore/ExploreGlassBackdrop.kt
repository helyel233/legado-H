package io.legado.app.ui.main.explore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
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
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.main.MainThemeBackgroundState
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.utils.ColorUtils as AppColorUtils
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.stackBlurSoftware
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

    /** 背景位图相对屏幕的降采样比例，配合模糊抹平高频纹理（布纹/细线条）保留大块明暗。 */
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
        // 与底栏 bottomBarOpacityLevel 同映射（level/200，上限 0.5），观感与底栏一致
        val glassLevel = level.coerceIn(0, 100) / 200f
        return FrostedGlassDrawable(
            host = host,
            backdrop = backdrop(context, glassLevel),
            radiusPx = context.resources.getDimension(R.dimen.ui_panel_radius),
            glassLevel = glassLevel,
            baseColor = ThemeStore.bottomBackground(context)
        )
    }

    private fun backdrop(context: Context, glassLevel: Float): Bitmap? {
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
            (glassLevel * 40).toInt()
        ).joinToString("|")
        synchronized(this) {
            cache?.takeIf { it.key == key }?.let { return it.bitmap }
        }
        // 底栏 frosted 同款模糊强度：blurRadius(dp) = 12 + glassLevel * 18。
        // 折算为位图像素：屏幕等效半径 ≈ dp*密度*1.4（GPU 高斯→stackBlur 等效系数），
        // 再除以降采样比例。模糊必须抹平壁纸高频纹理避免摩尔纹，同时保留大块明暗。
        // 固定用纯软件模糊：不依赖 renderscript toolkit 的 native 库。
        val density = context.resources.displayMetrics.density
        val blurDp = 12f + glassLevel * 18f
        val radius = (state.blur / DOWNSCALE + blurDp * density * 1.4f / DOWNSCALE)
            .toInt().coerceIn(3, 25)
        val bitmap = buildBitmap(file, state.blur, state.fallbackColor, state.crop, targetW, targetH, radius)
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
        radius: Int
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
        // 固定用纯软件模糊：不依赖 renderscript toolkit 的 native 库，
        // 避免 16KB 内核页设备上 native 库异常导致的任何不确定性。
        return runCatching { result.stackBlurSoftware(radius) }.getOrNull() ?: result
    }
}

/**
 * 单个书源名行的毛玻璃背景，与主界面底栏（StableLiquidGlassView + shell overlay）同构：
 * 1. 玻璃层：按行当前屏幕位置采样模糊壁纸；
 * 2. tint 层：底栏 LiquidGlass 同款白色弱着色；
 * 3. shell 层：底栏 createLiquidGlassShellDrawable 同款垂直渐变实底 + 描边，
 *    保证文字与壁纸内容分离（夜间 rgb(22,24,28)、日间白）。
 */
private class FrostedGlassDrawable(
    private val host: View,
    private val backdrop: Bitmap?,
    private val radiusPx: Float,
    private val glassLevel: Float,
    baseColor: Int
) : Drawable() {

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

    private val tintBaseAlpha =
        ((0.05f + glassLevel * 0.10f).coerceIn(0f, 1f) * 255).toInt()
    private val strokeBaseRatio = (0.18f + glassLevel * 0.16f).coerceIn(0f, 0.42f)
    private var shellShaderCache: LinearGradient? = null
    private var shellShaderHeight = -1
    private var alphaFactor = 1f
    private val shaderMatrix = Matrix()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val rect = RectF()
    private var shader: BitmapShader? = null

    init {
        tintPaint.color = Color.WHITE
        tintPaint.alpha = tintBaseAlpha
        strokePaint.color = AppColorUtils.withAlpha(surfaceColor, strokeBaseRatio)
    }

    private fun shellShader(height: Int): LinearGradient {
        val cached = shellShaderCache
        if (cached != null && shellShaderHeight == height) return cached
        // 滑杆直接对应遮盖度：100% 时壳层中心 ≈0.88（叠加后接近实底），
        // 60% ≈0.58，低档仅轻磨砂；上深下浅保留底栏同款垂直渐变。
        val centerAlpha = (0.10f + glassLevel * 1.6f).coerceIn(0f, 0.88f)
        val startAlpha = (centerAlpha + 0.08f).coerceIn(0f, 0.94f)
        val endAlpha = (centerAlpha - 0.08f).coerceIn(0f, 0.80f)
        return LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                AppColorUtils.withAlpha(surfaceColor, startAlpha),
                AppColorUtils.withAlpha(surfaceColor, centerAlpha),
                AppColorUtils.withAlpha(surfaceColor, endAlpha)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        ).also {
            shellShaderCache = it
            shellShaderHeight = height
        }
    }

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
        // tint（底栏 LiquidGlass 同款白色弱着色）
        canvas.drawRoundRect(rect, radiusPx, radiusPx, tintPaint)
        // shell 渐变实底（底栏 shell overlay 同款）
        if (rect.height() >= 1f) {
            shellPaint.shader = shellShader(rect.height().toInt().coerceAtLeast(1))
            shellPaint.alpha = (255 * alphaFactor).toInt()
            canvas.drawRoundRect(rect, radiusPx, radiusPx, shellPaint)
        }
        // 描边
        canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        val factor = alpha.coerceIn(0, 255) / 255f
        if (factor != alphaFactor) {
            alphaFactor = factor
            glassPaint.alpha = (255 * factor).toInt()
            tintPaint.alpha = (tintBaseAlpha * factor).toInt()
            strokePaint.alpha = (255 * strokeBaseRatio * factor).toInt()
            invalidateSelf()
        }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glassPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
        shellPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }
}
