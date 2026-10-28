package io.legado.app.model

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RuleSub
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

object RuleUpdate {
    val cacheBookSourceMap = ConcurrentHashMap<String, List<BookSource>>()
    val cacheRssSourceMap = ConcurrentHashMap<String, List<RssSource>>()
    val cacheReplaceRuleMap = ConcurrentHashMap<String, List<ReplaceRule>>()

    /** 中转缓存的写入时间：用户点了更新却取消导入时，条目会一直滞留，按 TTL 清理 */
    private val cacheTimes = ConcurrentHashMap<String, Long>()

    /** 30 分钟未消费视为放弃导入 */
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    private fun putCache(url: String) {
        cacheTimes[url] = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        cacheTimes.filterValues { now - it > CACHE_TTL_MS }.keys.forEach { expired ->
            cacheBookSourceMap.remove(expired)
            cacheRssSourceMap.remove(expired)
            cacheReplaceRuleMap.remove(expired)
            cacheTimes.remove(expired)
        }
    }

    suspend fun cacheSource(ruleSub: RuleSub): Boolean {
        val url = ruleSub.url
        val type = ruleSub.type
        val silentUpdate = ruleSub.silentUpdate
        val update = ruleSub.update
        val updateInterval = ruleSub.updateInterval
        // interval 先转 Long 再乘，避免 Int 溢出
        if (update + updateInterval * 3600_000L > System.currentTimeMillis()) {
            return false
        } else {
            ruleSub.update = System.currentTimeMillis()
            appDb.ruleSubDao.update(ruleSub)
        }
        var upRules = false
        try {
            okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }.decompressed().byteStream().use {
                when (type) {
                    0 -> GSON.fromJsonArray<BookSource>(it).getOrThrow().let { lists ->
                        val source = lists.firstOrNull() ?: return@let
                        if (source.bookSourceUrl.isEmpty()) {
                            throw NoStackTraceException("不是书源")
                        }
                        lists.forEach { list ->
                            val localSource = appDb.bookSourceDao.getBookSourcePart(list.bookSourceUrl)
                            if (localSource == null || localSource.lastUpdateTime < list.lastUpdateTime) {
                                if (silentUpdate) {
                                    if (localSource != null) {
                                        list.bookSourceGroup = localSource.bookSourceGroup
                                    }
                                    SourceHelp.insertBookSource(list)
                                    upRules = true
                                }
                                else {
                                    putCache(url)
                                    cacheBookSourceMap[url] = lists
                                    return true
                                }
                            }
                        }
                    }
                    1 -> GSON.fromJsonArray<RssSource>(it).getOrThrow().let { lists ->
                        val source = lists.firstOrNull() ?: return@let
                        if (source.sourceUrl.isEmpty()) {
                            throw NoStackTraceException("不是订阅源")
                        }
                        lists.forEach { list ->
                            val localSource = appDb.rssSourceDao.getByKey(list.sourceUrl)
                            if (localSource == null || localSource.lastUpdateTime < list.lastUpdateTime) {
                                if (silentUpdate) {
                                    if (localSource != null) {
                                        list.sourceGroup = localSource.sourceGroup
                                    }
                                    SourceHelp.insertRssSource(list)
                                }
                                else {
                                    putCache(url)
                                    cacheRssSourceMap[url] = lists
                                    return true
                                }
                            }
                        }
                    }
                    2 -> GSON.fromJsonArray<ReplaceRule>(it).getOrThrow().let { lists ->
                        lists.forEach { list ->
                            val oldRule = appDb.replaceRuleDao.findById(list.id)
                            if (oldRule == null || list.pattern != oldRule.pattern || list.replacement != oldRule.replacement) {
                                if (silentUpdate) {
                                    appDb.replaceRuleDao.insert(list)
                                    upRules = true
                                }
                                else {
                                    putCache(url)
                                    cacheReplaceRuleMap[url] = lists
                                    return true
                                }
                            }
                        }
                    }
                }
                if (upRules) {
                    ContentProcessor.upReplaceRules()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 拉取失败要把检查时间回退，否则该订阅在下一个周期内不会再重试
            ruleSub.update = update
            appDb.ruleSubDao.update(ruleSub)
            throw e
        }
        return false
    }
}