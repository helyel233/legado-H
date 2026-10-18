package io.legado.app.ui.book.read.page

import android.graphics.Color
import io.legado.app.ui.book.read.page.entities.TextPage
import org.json.JSONObject

/**
 * 进阶标题 Lottie 的 JSON 样式化（从 `PageView` 拆出）。
 *
 * PageView 原来把「标题字号换算、宽度比例换算、给 Lottie JSON 打补丁（补字体/配色/缩放）、
 * 全宽图片图层归一化」这些纯逻辑和 View 渲染混在一个 2800 行的文件里。这里只搬纯逻辑，
 * 行为逐行不变，页面渲染仍由 PageView 负责。
 *
 * 全部入参都显式传样式值（不在内部读 ReadBookConfig / AdvancedTitleConfig），
 * 目的是让这些换算可以被纯 JVM 单测覆盖——PageView 主体没有测试网，
 * 而这里的字号/比例算错会直接表现为标题大小不对、图片被裁切。
 */
internal object LottieTitleJsonStyler {

    private const val SIZE_FACTOR = 1.25f
    private const val WIDTH_FACTOR = 0.86f

    private const val FALLBACK_FONT = "legado_default_font"

    private const val MAX_STYLED_LOTTIE_CACHE_CHARS = 1024 * 1024
    private const val MAX_STYLED_LOTTIE_CACHE_UTF8_BYTES = 1024 * 1024

    /**
     * 样式化 JSON 的缓存。
     *
     * 原本是 PageView.companion 里的字段，只有这里用，随拆分一起搬过来更内聚。
     */
    private val styledLottieJsonCache = StyledLottieJsonCache(
        maxChars = MAX_STYLED_LOTTIE_CACHE_CHARS,
        maxUtf8Bytes = MAX_STYLED_LOTTIE_CACHE_UTF8_BYTES
    )

    /** 进阶标题字号 = 正文字号 + 标题字号 × 系数（下限 1sp） */
    fun textSizeSp(textSize: Float, titleSize: Float): Float {
        return (textSize + titleSize * SIZE_FACTOR).coerceAtLeast(1f)
    }

    /** 标题相对正文的缩放倍率，限制在 0.6~2.5 之间 */
    fun scale(textSize: Float, titleSize: Float): Float {
        return (textSizeSp(textSize, titleSize) / textSize.coerceAtLeast(1f))
            .coerceIn(0.6f, 2.5f)
    }

    /**
     * 内嵌 EPUB 标题块的宽度缩放。
     *
     * 只有「块宽度铺满内容区」（比例 ≥0.98）时才放大，避免把窄标题拉变形。
     *
     * @param heightFactorRatio 进阶标题高度系数与默认值之比
     *        （调用方传 `AdvancedTitleConfig.heightFactor / DEFAULT_HEIGHT_FACTOR`）
     */
    fun textLayerScale(
        block: TextPage.EpubEmbeddedBlock,
        pageWidth: Float,
        textSize: Float,
        titleSize: Float,
        heightFactorRatio: Float
    ): Float {
        val contentWidth = pageWidth.takeIf { it > 0f } ?: block.width
        if (contentWidth <= 0f) return 1f
        val actualWidthRatio = block.width / contentWidth
        if (actualWidthRatio < 0.98f) return 1f
        val requestedWidthRatio = WIDTH_FACTOR * scale(textSize, titleSize) * heightFactorRatio
        return (requestedWidthRatio / actualWidthRatio).coerceIn(1f, 2.5f)
    }

    /**
     * 给 Lottie 标题 JSON 打补丁：缺字体/缺配色的文字层补上阅读器的正文字色与默认字体，
     * 并按 [textScale] 放大字号行高。解析失败或超出大小预算时原样返回。
     */
    fun applyFallbackStyle(rawJson: String, textScale: Float, textColor: Int): StyledLottieJson {
        if (!AdvancedTitleLottieKeys.canApplyFallbackStyle(rawJson)) {
            return StyledLottieJson(rawJson, null)
        }
        val fallbackColor = textColor
        val fallbackHex = String.format("#%06X", 0xFFFFFF and fallbackColor)
        val normalizedTextScale = textScale.coerceIn(1f, 2.5f)
        val cacheKey = AdvancedTitleLottieKeys.styledJson(
            rawJson = rawJson,
            fallbackColor = fallbackColor,
            textScaleBits = normalizedTextScale.toBits()
        )
        styledLottieJsonCache[cacheKey]?.let { return it }
        val styled = runCatching {
            val root = JSONObject(rawJson)
            val compositionSize = root.optInt("w").takeIf { it > 0 }?.let { width ->
                root.optInt("h").takeIf { it > 0 }?.let { height ->
                    LottieDecodeSize(width, height)
                }
            }
            normalizeFullWidthImageLayers(root)
            val layers = root.optJSONArray("layers")
            if (layers != null) {
                for (i in 0 until layers.length()) {
                    val layer = layers.optJSONObject(i) ?: continue
                    if (layer.optInt("ty") != 5) continue
                    val text = layer.optJSONObject("t") ?: continue
                    val d = text.optJSONObject("d") ?: continue
                    val kArr = d.optJSONArray("k") ?: continue
                    for (j in 0 until kArr.length()) {
                        val keyFrame = kArr.optJSONObject(j) ?: continue
                        val style = keyFrame.optJSONObject("s") ?: continue
                        if (!style.has("f") || style.optString("f").isBlank()) {
                            style.put("f", FALLBACK_FONT)
                        }
                        if (style.optString("f") == FALLBACK_FONT ||
                            !style.has("fc") || style.optJSONArray("fc") == null
                        ) {
                            style.put("fc", parseColorArray(fallbackHex))
                        }
                        scaleLottieTextStyle(style, normalizedTextScale)
                    }
                }
                val fonts = root.optJSONObject("fonts")
                    ?: JSONObject().also { root.put("fonts", it) }
                val list = fonts.optJSONArray("list")
                    ?: org.json.JSONArray().also { fonts.put("list", it) }
                var hasFont = false
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    if (item.optString("fName") == FALLBACK_FONT) {
                        hasFont = true
                        break
                    }
                }
                if (!hasFont) {
                    list.put(JSONObject().apply {
                        put("fName", FALLBACK_FONT)
                        put("fFamily", FALLBACK_FONT)
                        put("fStyle", "Regular")
                        put("ascent", 75)
                    })
                }
            }
            StyledLottieJson(
                json = if (layers == null) rawJson else root.toString(),
                compositionSize = compositionSize
            )
        }.getOrElse {
            StyledLottieJson(rawJson, null)
        }
        styledLottieJsonCache.put(cacheKey, styled)
        return styled
    }

    /**
     * 把「宽度与整个动画等宽」的图片图层放大到铺满。
     *
     * Lottie 导出的全宽图片常带一个不到 100% 的缩放，直接显示会露出一条边，
     * 这里按缺口补足（最多放大 2 倍，避免把小图拉爆）。
     */
    private fun normalizeFullWidthImageLayers(root: JSONObject) {
        val rootWidth = root.optDouble("w", 0.0)
        if (rootWidth <= 0.0) return
        val assets = root.optJSONArray("assets") ?: return
        val assetWidthMap = mutableMapOf<String, Double>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val id = asset.optString("id").takeIf { it.isNotBlank() } ?: continue
            assetWidthMap[id] = asset.optDouble("w", 0.0)
        }
        val layers = root.optJSONArray("layers") ?: return
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            if (layer.optInt("ty") != 2) continue
            val assetWidth = assetWidthMap[layer.optString("refId")] ?: continue
            if (kotlin.math.abs(assetWidth - rootWidth) > 1.0) continue
            val scaleArray = layer.optJSONObject("ks")
                ?.optJSONObject("s")
                ?.optJSONArray("k") ?: continue
            val scaleX = scaleArray.optDouble(0, 100.0)
            val scaleY = scaleArray.optDouble(1, scaleX)
            if (scaleX <= 0.0 || scaleX >= 99.9) continue
            val fillScale = (100.0 / scaleX).coerceIn(1.0, 2.0)
            scaleArray.put(0, scaleX * fillScale)
            scaleArray.put(1, scaleY * fillScale)
        }
    }

    /** 放大文字层的字号/行高，并同步修正垂直居中位置 */
    private fun scaleLottieTextStyle(style: JSONObject, scale: Float) {
        if (scale <= 1.001f) return
        val fontSize = style.optDouble("s", 0.0)
        if (fontSize > 0.0) {
            style.put("s", fontSize * scale)
        }
        val lineHeight = style.optDouble("lh", 0.0)
        if (lineHeight > 0.0) {
            style.put("lh", lineHeight * scale)
        }
        val size = style.optJSONArray("sz")
        val oldHeight = size?.optDouble(1, 0.0) ?: 0.0
        if (size != null && oldHeight > 0.0) {
            val newHeight = oldHeight * scale
            size.put(1, newHeight)
            val position = style.optJSONArray("ps")
            if (position != null && position.length() > 1) {
                val oldY = position.optDouble(1, 0.0)
                if (kotlin.math.abs(oldY + oldHeight / 2.0) < 1.0) {
                    position.put(1, -newHeight / 2.0)
                }
            }
        }
    }

    /** Lottie 的颜色是 0~1 的 RGB 三元组 */
    private fun parseColorArray(hex: String): org.json.JSONArray {
        val color = Color.parseColor(hex)
        return org.json.JSONArray().apply {
            put(Color.red(color) / 255.0)
            put(Color.green(color) / 255.0)
            put(Color.blue(color) / 255.0)
        }
    }
}
