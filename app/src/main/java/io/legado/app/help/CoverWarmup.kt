package io.legado.app.help

import android.content.Context
import io.legado.app.constant.AppPattern
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.toCoverRequest
import io.legado.app.ui.widget.compose.coverLoadKey
import io.legado.app.ui.widget.compose.decodeCoverThumbFile

/**
 * 书架封面预热
 *
 * ## 解决什么
 *
 * 书架首屏有 N 个 `BookCoverImage`，每个都要等自己组合完、进 LaunchedEffect 才开始解码封面，
 * 于是首屏先渲染一批默认封面，封面再一个个"补"上来——封面多的书架尤其明显。
 *
 * 更关键的是 tab 切换/视图重建后 `remember` 状态全丢，即便已有 `CoverMemoryCache`，
 * 也仍然是"重组 → 逐个 LaunchedEffect → 逐个解码"，一屏封面要闪一下。
 *
 * 这里改成在**列表数据 collect 到、首帧渲染之前**，先把前几本已有磁盘缩略图的封面
 * 解码好放进 `CoverMemoryCache`。首屏组合时 `BookCoverImage` 的初值直接命中缓存，
 * 不再走默认封面。
 *
 * ## 刻意只预热"磁盘上已有的缩略图"
 *
 * - 不碰网络：冷启动时并发打网络会拖慢首屏，也与"仅 WIFI 加载封面"等开关纠缠；
 * - 不解原图：原图可能是全尺寸大图，解码成本高且容易把内存缓存挤爆；
 * - 缩略图是 240x320 的小文件，解码只要几毫秒，且 decodeCoverThumbFile 产出的位图
 *   归自己所有，可以安全进进程缓存（Glide 经 BitmapPool 回收复用的位图则不行）。
 *
 * 没有缩略图的书（新书、刚换过封面）维持原逻辑：由 LaunchedEffect 自己走 Glide 加载，
 * 这次加载成功后 `CoverThumbnailCache.saveAsync` 会落盘，下次启动就能被预热命中。
 * 也就是说书架用几天之后，预热命中率会自己涨上来。
 */
object CoverWarmup {

    /** 预热本数。取"一屏多一点"，覆盖冷启动后用户能看到的所有封面即可。 */
    private const val WARM_UP_LIMIT = 12

    /**
     * 预热书架前几本书的封面。IO 上执行，调用方应放到 IO 线程。
     *
     * @param displays 书架当前分组的显示数据（未过滤、未排序前的原始列表即可，
     *                 取前 [WARM_UP_LIMIT] 本就够，不必全量遍历）
     */
    suspend fun warmUpShelfCovers(context: Context, displays: List<BookShelfDisplay>) {
        if (displays.isEmpty()) return
        // 全用默认封面时，没有任何封面需要解码
        if (AppConfig.useDefaultCover) return
        displays.take(WARM_UP_LIMIT).forEach { display ->
            runCatching { warmUpOne(context, display) }
        }
    }

    private fun warmUpOne(context: Context, display: BookShelfDisplay) {
        val request = display.toCoverRequest()
        // 用默认封面就不用预热；与 BookCoverImage 的提前返回条件保持一致
        if (AppConfig.useDefaultCover && !request.forcePath) return

        val cleanName = request.name?.replace(AppPattern.bdRegex, "")?.trim()
        val cleanAuthor = request.author?.replace(AppPattern.bdRegex, "")?.trim()
        val useThumb = request.preferThumb && !AppConfig.loadCoverHighQuality

        val loadKey = coverLoadKey(
            path = request.path,
            cleanName = cleanName,
            cleanAuthor = cleanAuthor,
            sourceOrigin = request.sourceOrigin,
            loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
            useThumb = useThumb,
            forcePath = request.forcePath,
            allowNameOverlay = request.allowNameOverlay
        )

        // 已在缓存里就不用再解一次
        if (CoverMemoryCache.get(loadKey) != null) return

        // 只有"缩略图已在磁盘"的书才预热：不打网络、不解原图
        if (!useThumb) return
        val thumbFile = CoverThumbnailCache.existing(
            context,
            "${request.sourceOrigin}|${request.path}|$cleanName|$cleanAuthor"
        ) ?: return

        val bitmap = decodeCoverThumbFile(thumbFile) ?: return
        CoverMemoryCache.put(loadKey, bitmap)
    }
}
