package io.legado.app.ui.glass

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.dialogSurfaceColor
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.stackBlurSoftware

enum class BackdropCropGravity {
    BOTTOM,
    CENTER
}

interface ContentBackdropHost {
    fun captureContentBackdrop(
        gravity: BackdropCropGravity = BackdropCropGravity.BOTTOM,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Int
    ): Bitmap?

    fun contentGlassTintBaseColor(): Int
}

internal fun computeBackdropTranslateY(
    gravity: BackdropCropGravity,
    sampledHeight: Float,
    sourceHeight: Int
): Float = when (gravity) {
    BackdropCropGravity.BOTTOM -> sampledHeight - sourceHeight
    BackdropCropGravity.CENTER -> (sampledHeight - sourceHeight) / 2f
}

internal fun captureViewBackdrop(
    source: View,
    gravity: BackdropCropGravity,
    targetWidth: Int,
    targetHeight: Int,
    blurRadius: Int
): Bitmap? {
    if (blurRadius <= 0 ||
        targetWidth <= 0 ||
        targetHeight <= 0 ||
        source.width <= 0 ||
        source.height <= 0
    ) {
        return null
    }
    val raw = runCatching {
        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return null
    val scale = targetWidth.toFloat() / source.width
    val sampledHeight = targetHeight / scale
    val drawn = runCatching {
        val canvas = Canvas(raw)
        canvas.scale(scale, scale)
        canvas.translate(0f, computeBackdropTranslateY(gravity, sampledHeight, source.height))
        source.draw(canvas)
    }.isSuccess
    if (!drawn) {
        raw.recycle()
        return null
    }
    val blurred = runCatching { raw.stackBlurSoftware(blurRadius) }.getOrNull()
    raw.recycle()
    if (blurred == null || blurred === raw) return null
    return blurred
}

internal tailrec fun Context.findContentBackdropHost(): ContentBackdropHost? = when (this) {
    is ContentBackdropHost -> this
    is ContextWrapper -> {
        val base = baseContext
        if (base == null || base === this) null else base.findContentBackdropHost()
    }
    else -> null
}

internal data class ContentGlassBackdropResult(
    val drawable: Drawable,
    val bitmap: Bitmap?,
    val isFrosted: Boolean
)

internal const val CONTENT_BACKDROP_DOWNSCALE = 3f
internal const val CONTENT_GLASS_TINT_ALPHA = 0.74f
internal const val CONTENT_TINT_THEME_WEIGHT = 0.68f

internal fun isContentGlassAllowed(context: Context, modeOverride: String? = null): Boolean {
    val mode = modeOverride?.takeIf { it in GlassMaterialPreset.modes } ?: resolvedExploreGlassMode()
    return when (mode) {
        GlassMaterialPreset.SOLID, GlassMaterialPreset.CLEAR -> false
        else -> GlassSurface.isAvailable(GlassSamplingDomain.WALLPAPER, context, modeOverride)
    }
}

internal fun buildContentGlassBackdrop(
    context: Context,
    host: ContentBackdropHost? = context.findContentBackdropHost(),
    targetWidthPx: Int,
    targetHeightPx: Int,
    cornerRadiusPx: Float,
    corners: SurfaceCorners,
    gravity: BackdropCropGravity,
    strokeColor: Int = UiCorner.borderLineColor(context),
    strokeWidthPx: Int = context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
    tintBaseColor: Int? = null,
    modeOverride: String? = null,
    includeClearShell: Boolean = true
): ContentGlassBackdropResult? {
    val mode = modeOverride?.takeIf { it in GlassMaterialPreset.modes } ?: resolvedExploreGlassMode()
    val rawTintBase = tintBaseColor ?: host?.contentGlassTintBaseColor() ?: context.dialogSurfaceColor
    val tint = ColorUtils.blendColors(rawTintBase, context.dialogSurfaceColor, CONTENT_TINT_THEME_WEIGHT)
    if (mode == GlassMaterialPreset.CLEAR) {
        if (!includeClearShell) return null
        return ContentGlassBackdropResult(
            drawable = GlassMaterialPreset.createSolidShellDrawable(
                fillColor = GlassMaterialPreset.clearSurfaceColor(tint),
                borderColor = strokeColor,
                cornerRadiusPx = cornerRadiusPx,
                oval = false,
                strokeWidthPx = strokeWidthPx
            ),
            bitmap = null,
            isFrosted = true
        )
    }
    if (!isContentGlassAllowed(context, modeOverride)) {
        return null
    }
    val resolvedHost = host ?: return null
    val metrics = context.resources.displayMetrics
    val targetWidth = (targetWidthPx / CONTENT_BACKDROP_DOWNSCALE).toInt().coerceAtLeast(1)
    val targetHeight = (targetHeightPx / CONTENT_BACKDROP_DOWNSCALE).toInt().coerceAtLeast(1)
    val params = GlassMaterialPreset.current(mode)
    val blurRadius = ExploreGlassBackdrop.exploreSoftwareBlurRadius(params.blurDp, metrics.density)
    if (blurRadius <= 0) return null
    val backdrop = resolvedHost.captureContentBackdrop(
        gravity = gravity,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        blurRadius = blurRadius
    ) ?: return null
    val drawable = GlassLiquidBackdropDrawable(
        context = context,
        backdrop = backdrop,
        material = params,
        density = metrics.density,
        cornerRadiusPx = cornerRadiusPx,
        corners = corners,
        strokeColor = strokeColor,
        strokeWidthPx = strokeWidthPx.toFloat(),
        tintColor = ColorUtils.withAlpha(tint, CONTENT_GLASS_TINT_ALPHA),
        surfaceRgb = glassFrostSurfaceRgb(lightBase = ColorUtils.isColorLight(tint)),
        shell = params.shell
    )
    return ContentGlassBackdropResult(
        drawable = drawable,
        bitmap = backdrop,
        isFrosted = true
    )
}
