package io.legado.app.ui.book.read.page

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.airbnb.lottie.ImageAssetDelegate
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieImageAsset
import io.legado.app.help.config.LottieDerivedResourceCache
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.decodeBase64DataUrlBytes
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 进阶标题 Lottie 的图片资产解码（从 `PageView` 拆出）。
 *
 * 与 [LottieTitleJsonStyler] 同理：把纯解码/解析逻辑从 2800 行的 PageView 里搬出来，
 * 行为逐行不变。这里仍保留 BitmapFactory / SvgUtils 等 native 解码调用，
 * 但**不再依赖任何 PageView 实例状态**（原来虽为 inner class，实际只用构造参数）。
 *
 * ## 行为要点（搬运时不可变更）
 *
 * - `fetchBitmap` 先看 `asset.bitmap`，已解码的直接复用；
 * - 解不出来的单张图片交给 Lottie 跳过，**绝不**因此把有效标题降级回纯文本；
 * - 只有"声明了图片却一张都解不出"才算组合不可用；
 * - 文件超 [MAX_DERIVED_RESOURCE_BYTES] 时跳过文件、走 data-uri 兜底。
 */
internal class LottieImageAssetResolver(
    private val viewWidth: Int,
    private val viewHeight: Int,
    private val compositionWidth: Int,
    private val compositionHeight: Int,
    private val fallbackAssets: Map<String, String>
) : ImageAssetDelegate {

    override fun fetchBitmap(asset: LottieImageAsset): Bitmap? {
        asset.bitmap?.takeUnless { it.isRecycled }?.let { return it }
        val request = decodeRequest(asset) ?: return null
        return bitmapFor(request)
    }

    /**
     * Decode this composition's images off the UI thread. Individual assets that
     * cannot be resolved or decoded are left to [fetchBitmap]; Lottie simply skips
     * them. Only a composition that declares images yet yields none at all is
     * reported as not ready, so a partially decodable package keeps rendering
     * instead of dropping to the plain-text title.
     */
    fun prepareCompositionImages(composition: LottieComposition): Boolean {
        return synchronized(composition) {
            val assets = composition.images.values
            if (assets.isEmpty()) return@synchronized true
            val requests = assets.mapNotNull { asset -> decodeRequest(asset) }
            if (requests.isEmpty()) return@synchronized false
            val uniqueSizes = LinkedHashMap<LottieImageCacheKey, LottieDecodeSize>()
            requests.forEach { request ->
                uniqueSizes.putIfAbsent(request.cacheKey, request.decodeSize)
            }
            // Shrink an oversized package rather than refusing to render it.
            val scale = LottieImageMemoryPolicy.compositionBudgetScale(uniqueSizes.values)
            val decoded = LinkedHashMap<LottieImageCacheKey, Bitmap>(uniqueSizes.size)
            val assignments = ArrayList<Pair<LottieImageAsset, Bitmap>>(requests.size)
            requests.forEach { request ->
                val effective = if (scale >= 1f) {
                    request
                } else {
                    val size = LottieImageMemoryPolicy.scaled(request.decodeSize, scale)
                    request.copy(
                        decodeSize = size,
                        cacheKey = request.cacheKey.copy(
                            width = size.width,
                            height = size.height
                        )
                    )
                }
                val bitmap = effective.asset.bitmap?.takeUnless { it.isRecycled }
                    ?: decoded[effective.cacheKey]
                    ?: bitmapFor(effective)
                    ?: return@forEach
                decoded[effective.cacheKey] = bitmap
                assignments.add(effective.asset to bitmap)
            }
            if (assignments.isEmpty()) return@synchronized false
            assignments.forEach { (asset, bitmap) -> asset.bitmap = bitmap }
            true
        }
    }

    private fun decodeRequest(asset: LottieImageAsset): LottieAssetDecodeRequest? {
        val source = resolveLottieAssetSource(asset, fallbackAssets) ?: return null
        val decodeSize = LottieImageMemoryPolicy.decodeSize(
            assetWidth = asset.width.takeIf { it > 0 }
                ?: compositionWidth.coerceAtLeast(viewWidth),
            assetHeight = asset.height.takeIf { it > 0 }
                ?: compositionHeight.coerceAtLeast(viewHeight),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            compositionWidth = compositionWidth,
            compositionHeight = compositionHeight
        ) ?: return null
        return LottieAssetDecodeRequest(
            asset = asset,
            source = source,
            decodeSize = decodeSize,
            cacheKey = LottieImageCacheKey(
                sourceSha256 = LottieImageMemoryPolicy.sourceSha256(source.identity),
                width = decodeSize.width,
                height = decodeSize.height
            )
        )
    }

    private fun bitmapFor(request: LottieAssetDecodeRequest): Bitmap? {
        LottieImageBitmapCache.get(request.cacheKey)?.let { return it }
        return loadLottieAssetBitmap(request.source, request.decodeSize)?.also { bitmap ->
            LottieImageBitmapCache.put(request.cacheKey, bitmap)
        }
    }

    private fun resolveLottieAssetSource(
        asset: LottieImageAsset,
        fallbackAssets: Map<String, String>
    ): ResolvedLottieAssetSource? {
        val candidates = arrayListOf<String>()
        asset.fileName?.let(candidates::add)
        if (!asset.dirName.isNullOrBlank() && !asset.fileName.isNullOrBlank()) {
            candidates.add(asset.dirName + asset.fileName)
        }
        candidates.forEach { candidate ->
            if (candidate.startsWith("data:image", ignoreCase = true)) {
                return ResolvedLottieAssetSource(
                    identity = candidate,
                    file = null,
                    fallbackDataUri = candidate
                )
            }
            if (LottieDerivedResourceCache.isResourceAlias(candidate)) {
                val fallback = fallbackAssets[candidate]
                    ?.takeIf { it.startsWith("data:image", ignoreCase = true) }
                    ?: return@forEach
                return ResolvedLottieAssetSource(
                    identity = candidate,
                    file = LottieDerivedResourceCache.resourceFile(candidate),
                    fallbackDataUri = fallback
                )
            }
        }
        return null
    }

    private fun loadLottieAssetBitmap(
        source: ResolvedLottieAssetSource,
        decodeSize: LottieDecodeSize
    ): Bitmap? {
        source.file?.takeIf {
            it.isFile && it.length() in 1..MAX_DERIVED_RESOURCE_BYTES
        }?.let { file ->
            runCatching { decodeBitmapFileByType(file, decodeSize) }
                .getOrNull()
                ?.let { return it }
        }
        val fallback = source.fallbackDataUri ?: return null
        return runCatching {
            val bytes = fallback.decodeBase64DataUrlBytes() ?: return@runCatching null
            decodeBitmapByType(fallback, bytes, decodeSize)
        }.getOrNull()
    }

    private fun decodeBitmapFileByType(file: File, decodeSize: LottieDecodeSize): Bitmap? {
        return if (file.extension.equals("svg", ignoreCase = true)) {
            file.inputStream().buffered().use { input ->
                SvgUtils.createBitmap(
                    input,
                    decodeSize.width,
                    decodeSize.height
                )
            }
        } else {
            decodeRasterBitmap(file, decodeSize)
        }
    }

    private fun decodeBitmapByType(
        source: String,
        bytes: ByteArray,
        decodeSize: LottieDecodeSize
    ): Bitmap? {
        val lower = source.lowercase()
        return if (lower.contains("image/svg+xml") || lower.endsWith(".svg")) {
            SvgUtils.createBitmap(
                ByteArrayInputStream(bytes),
                decodeSize.width,
                decodeSize.height
            )
        } else {
            decodeRasterBitmap(bytes, decodeSize)
        }
    }

    private fun decodeRasterBitmap(bytes: ByteArray, decodeSize: LottieDecodeSize): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = LottieImageMemoryPolicy.fitSourceInto(bounds.outWidth, bounds.outHeight, decodeSize)
            ?: return null
        val sampleSize = LottieImageMemoryPolicy.sampleSize(bounds.outWidth, bounds.outHeight, target)
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        if (decoded.width == target.width && decoded.height == target.height) return decoded
        return Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun decodeRasterBitmap(file: File, decodeSize: LottieDecodeSize): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val target = LottieImageMemoryPolicy.fitSourceInto(bounds.outWidth, bounds.outHeight, decodeSize)
            ?: return null
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = LottieImageMemoryPolicy.sampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    target
                )
            }
        ) ?: return null
        if (decoded.width == target.width && decoded.height == target.height) return decoded
        return Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }
}

/** 解析出的图片来源：本地文件、或 data-uri 兜底。 */
internal data class ResolvedLottieAssetSource(
    val identity: String,
    val file: File?,
    val fallbackDataUri: String?
)

/** 一次解码请求：解哪个资产、按什么尺寸、缓存键是什么。 */
internal data class LottieAssetDecodeRequest(
    val asset: LottieImageAsset,
    val source: ResolvedLottieAssetSource,
    val decodeSize: LottieDecodeSize,
    val cacheKey: LottieImageCacheKey
)

/** 派生资源文件的大小上限，超过就不读文件、走 data-uri。 */
private const val MAX_DERIVED_RESOURCE_BYTES = 8L * 1024L * 1024L
