package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Size
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.BubbleSvgPolicy
import io.legado.app.help.config.PackageSvgResourceResolver
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.SvgUtils
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

object ParagraphBubbleRenderer {

    const val SCHEME_PREFIX = "bubble://paragraph"

    private val viewBoxPattern = Regex("""viewBox\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun isBubbleSrc(src: String): Boolean {
        return src.startsWith(SCHEME_PREFIX)
    }

    fun getSize(src: String): Size {
        val scale = BubblePackageManager.currentEntry().config.sizeScale
            .coerceIn(BubblePackageManager.MIN_SIZE_SCALE, BubblePackageManager.MAX_SIZE_SCALE)
        val side = (64f * scale).roundToInt().coerceAtLeast(1)
        return Size(side, side)
    }

    fun inlineWidth(baseWidth: Float): Float {
        val scale = BubblePackageManager.currentEntry().config.sizeScale
            .coerceIn(BubblePackageManager.MIN_SIZE_SCALE, BubblePackageManager.MAX_SIZE_SCALE)
        return baseWidth * scale
    }

    fun cacheKey(src: String, width: Int, height: Int?, cached: Boolean = false): String {
        val config = BubblePackageManager.currentEntry().config
        val color = resolveColor(config, status(src), displayColor(src))
        return buildString {
            append(src)
            append("#")
            append(width)
            append("x")
            append(height ?: 0)
            append("#")
            append(BubblePackageManager.activeDirName())
            append("#")
            append(config.updatedAt)
            append("#")
            append(config.sizeScale)
            append("#")
            append(if (AppConfig.isNightTheme) "night" else "day")
            append("#")
            append(color)
            if (cached) {
                append("#cached:")
                append(resolveCachedMarkColorHex())
            }
        }
    }

    fun render(src: String, width: Int, height: Int?, cached: Boolean = false): Bitmap? {
        val entry = BubblePackageManager.currentEntry()
        val config = entry.config
        val color = resolveColor(config, status(src), displayColor(src))
        val text = BubbleSvgPolicy.escapeText(displayText(src))
        var svg = config.svgTemplate
            .replaceBubbleValue(listOf("displayText", "num"), text)
            .replaceBubbleValue(listOf("displayColor", "color"), color)
        if (cached) {
            svg = injectCachedMark(svg, resolveCachedMarkColorHex())
        }
        val targetWidth = width.coerceAtLeast(1)
        val targetHeight = height ?: targetWidth
        val input = ByteArrayInputStream(svg.toByteArray())
        return SvgUtils.createBitmap(
            input,
            targetWidth,
            height,
            PackageSvgResourceResolver(
                entry.localDir,
                config.resources,
                targetWidth,
                targetHeight
            ) {
                ChapterProvider.contentPaint.typeface ?: ChapterProvider.typeface
            }
        )
    }

    /**
     * 已缓存角标：直接补进渲染用的 SVG，而不是依赖气泡包模板预留占位符。
     *
     * 采用主题强调色 + 高对比度外环描边垫底，确保小字号与深色模式下依然醒目。
     */
    internal fun injectCachedMark(
        svg: String,
        color: String,
        haloColor: String = contrastHaloHex(color),
    ): String {
        val close = svg.lastIndexOf("</svg>")
        if (close < 0) return svg
        val box = viewBoxPattern.find(svg)?.groupValues?.getOrNull(1) ?: return svg
        val parts = box.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4) return svg
        val (minX, minY, width, height) = parts
        if (width <= 0f || height <= 0f) return svg
        val outer = minOf(width, height) * 0.19f
        val inner = outer * 0.62f
        val cx = minX + width - outer * 0.94f
        val cy = minY + outer * 0.94f
        val mark = buildString {
            append("<g><circle cx=\"")
            append(fixed(cx))
            append("\" cy=\"")
            append(fixed(cy))
            append("\" r=\"")
            append(fixed(outer))
            append("\" fill=\"")
            append(haloColor)
            append("\" stroke=\"")
            append(haloColor)
            append("\" stroke-width=\"")
            append(fixed(outer * 0.36f))
            append("\"/><circle cx=\"")
            append(fixed(cx))
            append("\" cy=\"")
            append(fixed(cy))
            append("\" r=\"")
            append(fixed(outer))
            append("\" fill=\"none\" stroke=\"")
            append(color)
            append("\" stroke-width=\"")
            append(fixed(outer * 0.34f))
            append("\"/><circle cx=\"")
            append(fixed(cx))
            append("\" cy=\"")
            append(fixed(cy))
            append("\" r=\"")
            append(fixed(inner))
            append("\" fill=\"")
            append(color)
            append("\"/></g>")
        }
        return svg.substring(0, close) + mark + svg.substring(close)
    }

    internal fun resolveCachedMarkColorInt(): Int {
        if (runCatching { AppConfig.isEInkMode }.getOrDefault(false)) {
            return runCatching { ChapterProvider.contentPaint.color }
                .getOrDefault(0xFF000000.toInt()) or 0xFF000000.toInt()
        }
        val raw = runCatching { ThemeStore.accentColor(appCtx) }
            .getOrNull()
            ?.takeIf { (it ushr 24) > 0 }
            ?: runCatching { ReadBookConfig.textAccentColor }
                .getOrNull()
                ?.takeIf { (it ushr 24) > 0 }
            ?: 0xFF0A84FF.toInt()
        val opaque = raw or 0xFF000000.toInt()
        val isNight = runCatching { AppConfig.isNightTheme }.getOrDefault(false)
        val luminance = relativeLuminance(opaque)
        return when {
            isNight && luminance < 0.18 -> blendArgb(opaque, 0xFFFFFFFF.toInt(), 0.42f)
            !isNight && luminance > 0.72 -> blendArgb(opaque, 0xFF1E1E1E.toInt(), 0.35f)
            else -> opaque
        }
    }

    internal fun resolveCachedMarkColorHex(): String {
        return toRgbHex(resolveCachedMarkColorInt())
    }

    internal fun contrastHaloColorInt(markColor: Int): Int {
        return if (relativeLuminance(markColor) > 0.50) {
            0xF218181B.toInt()
        } else {
            0xF5FFFFFF.toInt()
        }
    }

    internal fun contrastHaloHex(markColorHex: String): String {
        val parsed = parseHexColorOrNull(markColorHex) ?: return "#FFFFFF"
        return if (relativeLuminance(parsed) > 0.50) "#18181B" else "#FFFFFF"
    }

    internal fun toRgbHex(argb: Int): String =
        String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    internal fun parseHexColorOrNull(hex: String): Int? {
        val clean = hex.trim().removePrefix("#")
        val value = clean.toLongOrNull(16) ?: return null
        return when (clean.length) {
            6 -> (0xFF000000L or value).toInt()
            8 -> value.toInt()
            else -> null
        }
    }

    internal fun relativeLuminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = (c and 0xFF) / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(argb ushr 16)
        val g = channel(argb ushr 8)
        val b = channel(argb)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun blendArgb(color1: Int, color2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        val a = (((color1 ushr 24) and 0xFF) * inv + ((color2 ushr 24) and 0xFF) * r).roundToInt()
        val red = (((color1 ushr 16) and 0xFF) * inv + ((color2 ushr 16) and 0xFF) * r).roundToInt()
        val green = (((color1 ushr 8) and 0xFF) * inv + ((color2 ushr 8) and 0xFF) * r).roundToInt()
        val blue = ((color1 and 0xFF) * inv + (color2 and 0xFF) * r).roundToInt()
        return ((a and 0xFF) shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)
    }

    internal fun fixed(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun resolveColor(
        config: BubblePackageManager.Config,
        status: String,
        overrideColor: String?
    ): String {
        val emphasis = status.equals("emphasis", true)
        val fallback = if (emphasis) {
            BubblePackageManager.DEFAULT_EMPHASIS_COLOR
        } else {
            BubblePackageManager.DEFAULT_NORMAL_COLOR
        }
        val themeColor = if (AppConfig.isNightTheme) {
            if (emphasis) config.nightEmphasisColor else config.nightNormalColor
        } else {
            if (emphasis) config.dayEmphasisColor else config.dayNormalColor
        }
        val value = overrideColor?.takeIf { it.isNotBlank() }
            ?: themeColor?.takeIf { it.isNotBlank() }
            ?: fallback
        return runCatching {
            val normalized = if (value.startsWith("#")) value else "#$value"
            Color.parseColor(normalized)
            normalized
        }.getOrDefault(fallback)
    }

    private fun displayText(src: String): String {
        return queryValue(src, "displayText")
            .ifBlank { queryValue(src, "num") }
    }

    private fun status(src: String): String {
        return queryValue(src, "status").ifBlank { "normal" }
    }

    private fun displayColor(src: String): String? {
        return queryValue(src, "displayColor")
            .ifBlank { queryValue(src, "color") }
            .takeIf { it.isNotBlank() }
    }

    private fun queryValue(src: String, key: String): String {
        val query = src.substringAfter('?', "")
        if (query.isBlank()) return ""
        return query.split('&')
            .firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.let(Uri::decode)
            .orEmpty()
    }

    private fun String.replaceBubbleValue(names: List<String>, value: String): String {
        val namePattern = names.joinToString("|") { Regex.escape(it) }
        val regex = Regex(
            """\$\{(?:$namePattern)\}|\$(?:$namePattern)\b|\{\{(?:$namePattern)\}\}""",
            RegexOption.IGNORE_CASE
        )
        return replace(regex) { value }
    }
}
