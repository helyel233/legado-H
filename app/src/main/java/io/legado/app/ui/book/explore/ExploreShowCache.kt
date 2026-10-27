package io.legado.app.ui.book.explore

import io.legado.app.data.entities.SearchBook
import io.legado.app.help.CacheManager
import io.legado.app.ui.main.explore.DiscoveryCachePolicy
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils

/**
 * 经典发现分类列表（ExploreShowActivity）的首屏快照缓存。
 *
 * 用途是"秒开"：进页时先展示上次浏览到的内容（含已翻过的页），
 * 随后台网络加载自动从快照记录的下一页续拉。缓存只作加载加速，
 * 内容允许陈旧（每次进页都会重新拉网络覆盖），任何读写失败都
 * 静默放弃，绝不影响主流程。
 *
 * key 含 exploreUrl 的 md5，分类规则变化（exploreUrl 变化）后自然换新缓存；
 * key 前缀与 exploreKinds 的 "explore" ACache 隔离，互不干扰。
 */
internal object ExploreShowCache {

    /** 快照最多保存的条目数，防止超长浏览会话把缓存文件撑大 */
    internal const val MAX_SNAPSHOT_BOOKS = 120
    internal const val CACHE_PREFIX = "explore_show_snapshot_"

    /** 缓存 30 天后自动过期（ACache saveTime 秒） */
    private const val TTL_SECONDS = 30 * 24 * 60 * 60

    internal data class Snapshot(
        /** 下一页页码（网络续拉的起点），快照内容是 1..page-1 页的累积列表 */
        val page: Int,
        val books: List<SearchBook>
    )

    internal fun cacheKey(sourceUrl: String, exploreUrl: String): String {
        return CACHE_PREFIX + MD5Utils.md5Encode16("$sourceUrl\n$exploreUrl")
    }

    internal fun toJson(snapshot: Snapshot): String? {
        if (snapshot.page < 1) return null
        // 超长会话截断时必须保留**最新**的条目：books 是第 1..N 页的正序累积，
        // 而 snapshot.page 记的是「下一待拉页」（N+1）。若取最早的条目，
        // 恢复后会显示最旧几页、再从最新页续拉，中间出现页码断层；
        // takeLast 保证快照尾部与续拉页码连续。
        val books = snapshot.books.takeLast(MAX_SNAPSHOT_BOOKS)
            .mapNotNull { DiscoveryCachePolicy.compact(it) }
        if (books.isEmpty()) return null
        return DiscoveryCachePolicy.toBoundedJson(SnapshotDto(snapshot.page, books))
    }

    internal fun fromJson(json: String, sourceUrl: String): Snapshot? {
        if (json.isBlank()) return null
        val dto = runCatching { GSON.fromJson(json, SnapshotDto::class.java) }.getOrNull()
            ?: return null
        if (dto.page < 1) return null
        //只认属于当前书源且身份字段完整的条目，防书源换址后串源
        val books = dto.books.orEmpty().filter {
            it.origin == sourceUrl && it.bookUrl.isNotBlank() && it.name.isNotBlank()
        }
        if (books.isEmpty()) return null
        return Snapshot(dto.page, books)
    }

    fun read(sourceUrl: String, exploreUrl: String): Snapshot? {
        if (sourceUrl.isBlank() || exploreUrl.isBlank()) return null
        val json = runCatching {
            CacheManager.getFile(cacheKey(sourceUrl, exploreUrl))
        }.getOrNull() ?: return null
        return fromJson(json, sourceUrl)
    }

    fun write(sourceUrl: String, exploreUrl: String, snapshot: Snapshot) {
        if (sourceUrl.isBlank() || exploreUrl.isBlank()) return
        val json = toJson(snapshot) ?: return
        runCatching {
            CacheManager.putFile(cacheKey(sourceUrl, exploreUrl), json, TTL_SECONDS)
        }
    }

    /**
     * 序列化 DTO：books 用可空类型承接 GSON 反序列化（GSON 会绕过
     * Kotlin 构造器，缺字段时非空声明会埋下伪非空 null）。
     */
    private class SnapshotDto(
        val page: Int = 0,
        val books: List<SearchBook>? = null
    )
}
