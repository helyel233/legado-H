package io.legado.app.help.source

import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.help.book.highlight.HighlightRules
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.util.concurrent.TimeUnit

/**
 * 规则回收站帮助类（移植自 Legado_Max）
 *
 * 各类删除入口调用 recycleXxx 前置保存，恢复时反序列化回写；
 * Max 版的搜索引擎规则体系 legado-H 未移植，故不含该类型。
 */
object SourceRecycleBinHelp {

    const val TYPE_BOOK_SOURCE = "book_source"
    const val TYPE_RSS_SOURCE = "rss_source"
    const val TYPE_REPLACE_RULE = "replace_rule"
    const val TYPE_TXT_TOC_RULE = "txt_toc_rule"
    const val TYPE_HTTP_TTS = "http_tts"
    const val TYPE_DICT_RULE = "dict_rule"
    const val TYPE_HIGHLIGHT_RULE = "highlight_rule"
    private const val RETENTION_DAYS = 7L

    fun recycleBookSources(
        bookSources: List<BookSource>,
        now: Long = System.currentTimeMillis()
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (bookSources.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = bookSources.map {
            SourceRecycleBin(
                type = TYPE_BOOK_SOURCE,
                key = it.bookSourceUrl,
                name = it.bookSourceName,
                groupName = it.bookSourceGroup,
                payload = GSON.toJson(it),
                deletedAt = now,
                expireAt = expireAt
            )
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
    }

    fun recycleRssSources(
        rssSources: List<RssSource>,
        now: Long = System.currentTimeMillis()
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (rssSources.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = rssSources.map {
            SourceRecycleBin(
                type = TYPE_RSS_SOURCE,
                key = it.sourceUrl,
                name = it.sourceName,
                groupName = it.sourceGroup,
                payload = GSON.toJson(it),
                deletedAt = now,
                expireAt = expireAt
            )
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
    }

    fun recycleReplaceRules(
        replaceRules: List<ReplaceRule>,
        now: Long = System.currentTimeMillis()
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (replaceRules.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = replaceRules.map {
            SourceRecycleBin(
                type = TYPE_REPLACE_RULE,
                key = it.id.toString(),
                name = it.name,
                groupName = it.group,
                payload = GSON.toJson(it),
                deletedAt = now,
                expireAt = expireAt
            )
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
    }

    fun recycleTxtTocRules(
        txtTocRules: List<TxtTocRule>,
        now: Long = System.currentTimeMillis()
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (txtTocRules.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = txtTocRules.map {
            SourceRecycleBin(
                type = TYPE_TXT_TOC_RULE,
                key = it.id.toString(),
                name = it.name,
                payload = GSON.toJson(it),
                deletedAt = now,
                expireAt = expireAt
            )
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
    }

    fun recycleHttpTtsRules(
        httpTtsRules: List<HttpTTS>,
        now: Long = System.currentTimeMillis()
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (httpTtsRules.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = httpTtsRules.map {
            SourceRecycleBin(
                type = TYPE_HTTP_TTS,
                key = it.id.toString(),
                name = it.name,
                payload = GSON.toJson(it),
                deletedAt = now,
                expireAt = expireAt
            )
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
    }

    fun recycleDictRules(
        dictRules: List<DictRule>,
        now: Long = System.currentTimeMillis()
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (dictRules.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = dictRules.map {
            SourceRecycleBin(
                type = TYPE_DICT_RULE,
                key = it.name,
                name = it.name,
                payload = GSON.toJson(it),
                deletedAt = now,
                expireAt = expireAt
            )
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
    }

    fun recycleHighlightRules(
        highlightRules: List<HighlightRule>,
        now: Long = System.currentTimeMillis()
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (highlightRules.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = highlightRules.map {
            SourceRecycleBin(
                type = TYPE_HIGHLIGHT_RULE,
                key = it.id,
                name = it.name,
                payload = GSON.toJson(it),
                deletedAt = now,
                expireAt = expireAt
            )
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
    }

    fun restore(item: SourceRecycleBin, overwrite: Boolean) {
        cleanupExpired()
        when (item.type) {
            TYPE_BOOK_SOURCE -> {
                val source = GSON.fromJsonObject<BookSource>(item.payload).getOrNull() ?: return
                if (!overwrite && appDb.bookSourceDao.has(source.bookSourceUrl)) return
                appDb.bookSourceDao.insert(source)
            }
            TYPE_RSS_SOURCE -> {
                val source = GSON.fromJsonObject<RssSource>(item.payload).getOrNull() ?: return
                if (!overwrite && appDb.rssSourceDao.has(source.sourceUrl)) return
                appDb.rssSourceDao.insert(source)
            }
            TYPE_REPLACE_RULE -> {
                val rule = GSON.fromJsonObject<ReplaceRule>(item.payload).getOrNull() ?: return
                if (!overwrite && appDb.replaceRuleDao.findById(rule.id) != null) return
                appDb.replaceRuleDao.insert(rule)
            }
            TYPE_TXT_TOC_RULE -> {
                val rule = GSON.fromJsonObject<TxtTocRule>(item.payload).getOrNull() ?: return
                if (!overwrite && appDb.txtTocRuleDao.get(rule.id) != null) return
                appDb.txtTocRuleDao.insert(rule)
            }
            TYPE_HTTP_TTS -> {
                val rule = GSON.fromJsonObject<HttpTTS>(item.payload).getOrNull() ?: return
                if (!overwrite && appDb.httpTTSDao.get(rule.id) != null) return
                appDb.httpTTSDao.insert(rule)
            }
            TYPE_DICT_RULE -> {
                val rule = GSON.fromJsonObject<DictRule>(item.payload).getOrNull() ?: return
                if (!overwrite && appDb.dictRuleDao.getByName(rule.name) != null) return
                appDb.dictRuleDao.insert(rule)
            }
            TYPE_HIGHLIGHT_RULE -> {
                val rule =
                    GSON.fromJsonObject<HighlightRule>(item.payload)
                        .getOrNull() ?: return
                val exists = HighlightRules.store.all().any { it.id == rule.id }
                if (exists && !overwrite) return
                HighlightRules.store.put(rule)
            }
        }
        appDb.sourceRecycleBinDao.delete(item)
    }

    fun hasConflict(item: SourceRecycleBin): Boolean {
        return when (item.type) {
            TYPE_BOOK_SOURCE -> appDb.bookSourceDao.has(item.key)
            TYPE_RSS_SOURCE -> appDb.rssSourceDao.has(item.key)
            TYPE_REPLACE_RULE -> item.key.toLongOrNull()
                ?.let { appDb.replaceRuleDao.findById(it) != null } == true
            TYPE_TXT_TOC_RULE -> item.key.toLongOrNull()
                ?.let { appDb.txtTocRuleDao.get(it) != null } == true
            TYPE_HTTP_TTS -> item.key.toLongOrNull()
                ?.let { appDb.httpTTSDao.get(it) != null } == true
            TYPE_DICT_RULE -> appDb.dictRuleDao.getByName(item.key) != null
            TYPE_HIGHLIGHT_RULE -> HighlightRules.store.all().any { it.id == item.key }
            else -> false
        }
    }

    fun cleanupExpired(now: Long = System.currentTimeMillis()) {
        appDb.sourceRecycleBinDao.deleteExpired(now)
    }
}
