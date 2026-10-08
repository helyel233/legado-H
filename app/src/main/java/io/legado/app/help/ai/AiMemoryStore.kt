package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiMemoryFragment
import io.legado.app.data.entities.AiMemoryItem
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx
import org.json.JSONObject

data class AiMemoryContext(
    val scope: String = AiMemoryItem.SCOPE_GLOBAL,
    val bookKey: String = "",
    val sessionId: String = "",
    val companionId: String = "",
    val title: String = ""
)

data class AiRetrievedMemory(
    val items: List<AiMemoryItem> = emptyList(),
    val fragments: List<AiMemoryFragment> = emptyList()
) {
    val isNotEmpty: Boolean
        get() = items.isNotEmpty() || fragments.isNotEmpty()

    fun toSystemPrompt(maxChars: Int = 2_800): String {
        if (!isNotEmpty) return ""
        val builder = StringBuilder("Relevant long-term memories:\n")
        items.forEach { item ->
            val line = buildString {
                append("- ")
                if (item.subject.isNotBlank()) append(item.subject).append(": ")
                append(item.content.ifBlank {
                    listOf(item.subject, item.predicate, item.objectValue)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                })
            }.trim()
            if (line.length > 2 && builder.length + line.length < maxChars) {
                builder.append(line.take(360)).append('\n')
            }
        }
        fragments.forEach { fragment ->
            val line = "- ${fragment.title.ifBlank { fragment.sourceType }}: ${fragment.content.replace(Regex("\\s+"), " ").trim()}"
            if (builder.length + line.length < maxChars) {
                builder.append(line.take(520)).append('\n')
            }
        }
        return builder.toString().trim()
    }
}

object AiMemoryStore {

    private const val FTS_BIGRAM_VERSION = 1
    private val reindexLock = Any()

    fun bookKey(bookName: String, author: String): String {
        return listOf(bookName.trim(), author.trim())
            .filter { it.isNotBlank() }
            .joinToString("::")
    }

    /**
     * 记忆 FTS 索引引入 CJK 二元词后重建一次历史数据。
     * 由 AiMemoryRetriever.retrieve 触发，通过版本标记保证只执行一次。
     */
    fun ensureFtsBigramIndex() {
        if (appCtx.getPrefInt(PreferKey.aiMemoryFtsBigramVersion, 0) >= FTS_BIGRAM_VERSION) return
        synchronized(reindexLock) {
            if (appCtx.getPrefInt(PreferKey.aiMemoryFtsBigramVersion, 0) >= FTS_BIGRAM_VERSION) return
            runCatching {
                appDb.aiMemoryDao.allItems().forEach { upsertItem(it) }
                appDb.aiMemoryDao.allFragments().forEach { upsertFragment(it) }
            }
            appCtx.putPrefInt(PreferKey.aiMemoryFtsBigramVersion, FTS_BIGRAM_VERSION)
        }
    }

    fun upsertItem(item: AiMemoryItem) {
        val saving = item.withFingerprint()
        appDb.aiMemoryDao.upsertItem(saving)
        appDb.aiMemoryDao.deleteItemFts(saving.memoryId)
        appDb.aiMemoryDao.upsertItemFts(
            memoryId = saving.memoryId,
            subject = AiFtsTokenizer.indexText(saving.subject),
            predicate = AiFtsTokenizer.indexText(saving.predicate),
            objectValue = AiFtsTokenizer.indexText(saving.objectValue),
            content = AiFtsTokenizer.indexText(saving.content)
        )
    }

    fun upsertFragment(fragment: AiMemoryFragment) {
        val saving = fragment.withContentHash()
        appDb.aiMemoryDao.upsertFragment(saving)
        appDb.aiMemoryDao.deleteFragmentFts(saving.fragmentId)
        appDb.aiMemoryDao.upsertFragmentFts(
            fragmentId = saving.fragmentId,
            title = AiFtsTokenizer.indexText(saving.title),
            content = AiFtsTokenizer.indexText(saving.content),
            chapterTitle = AiFtsTokenizer.indexText(saving.chapterTitle)
        )
    }

    private fun AiMemoryItem.withFingerprint(): AiMemoryItem {
        if (fingerprint.isNotBlank()) return this
        val raw = listOf(scope, bookKey, sessionId, type, subject, predicate, objectValue, content)
            .joinToString("|")
        return copy(fingerprint = MD5Utils.md5Encode(raw))
    }

    private fun AiMemoryFragment.withContentHash(): AiMemoryFragment {
        if (contentHash.isNotBlank()) return this
        val raw = listOf(scope, bookKey, sessionId, sourceType, title, content, chapterIndex.toString())
            .joinToString("|")
        return copy(contentHash = MD5Utils.md5Encode(raw))
    }
}

object AiMemoryRetriever {

    fun retrieve(
        context: AiMemoryContext?,
        messages: List<AiChatMessage>,
        limit: Int = 8
    ): AiRetrievedMemory {
        if (context == null) return AiRetrievedMemory()
        AiMemoryStore.ensureFtsBigramIndex()
        val queryText = messages
            .takeLast(6)
            .joinToString("\n") { it.content }
            .take(4_000)
        if (queryText.isBlank()) return AiRetrievedMemory()
        val ftsQuery = buildFtsQuery(queryText)
        val ftsItems = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            runCatching {
                appDb.aiMemoryDao.searchItems(ftsQuery, context.scope, context.bookKey, context.sessionId, limit)
            }.getOrDefault(emptyList())
        }
        val ftsFragments = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            runCatching {
                appDb.aiMemoryDao.searchFragments(ftsQuery, context.scope, context.bookKey, context.sessionId, limit)
            }.getOrDefault(emptyList())
        }
        val keywords = keywords(queryText)
        val rankedItems = (ftsItems + appDb.aiMemoryDao.candidateItems(context.scope, context.bookKey, context.sessionId))
            .distinctBy { it.memoryId }
            .sortedByDescending { score("${it.subject} ${it.predicate} ${it.objectValue} ${it.content}", keywords) + it.importance }
            .take(limit)
        val rankedFragments = (ftsFragments + appDb.aiMemoryDao.candidateFragments(context.scope, context.bookKey, context.sessionId))
            .distinctBy { it.fragmentId }
            .sortedByDescending { score("${it.title} ${it.chapterTitle} ${it.content}", keywords) + it.importance }
            .take(limit)
        if (rankedItems.isNotEmpty()) {
            appDb.aiMemoryDao.markItemsUsed(rankedItems.map { it.memoryId })
        }
        if (rankedFragments.isNotEmpty()) {
            appDb.aiMemoryDao.markFragmentsUsed(rankedFragments.map { it.fragmentId })
        }
        return AiRetrievedMemory(rankedItems, rankedFragments)
    }

    private fun buildFtsQuery(text: String): String {
        return AiFtsTokenizer.queryTerms(text)
            .joinToString(" OR ") { term -> "\"${term.replace("\"", "")}\"" }
    }

    private fun keywords(text: String): List<String> {
        val compact = text.replace(Regex("\\s+"), "")
        val words = Regex("[\\p{L}\\p{N}_]{2,}")
            .findAll(text)
            .map { it.value }
            .toMutableList()
        if (compact.length >= 2) {
            compact.windowed(2, 2, partialWindows = false)
                .take(24)
                .forEach(words::add)
        }
        return words.distinct().take(48)
    }

    private fun score(text: String, keywords: List<String>): Int {
        if (keywords.isEmpty()) return 0
        return keywords.count { keyword -> text.contains(keyword, ignoreCase = true) } * 10
    }
}

/**
 * FTS 索引/查询的分词工具：SQLite 默认 simple 分词器把整段 CJK 视作单个 token，
 * 导致中文记忆检索失效。写入索引前把 CJK 串切为空格分隔的二元词，查询侧用同样的方式生成词元。
 */
internal object AiFtsTokenizer {

    private val latinTokenRegex = Regex("[A-Za-z0-9_]{2,}")
    private val cjkRunRegex = Regex("[\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}]+")
    private const val MAX_INDEX_TOKENS = 1_200

    private fun tokens(text: String): List<String> {
        val result = mutableListOf<String>()
        latinTokenRegex.findAll(text).forEach { result.add(it.value.lowercase()) }
        cjkRunRegex.findAll(text).forEach { match ->
            val run = match.value
            if (run.length == 1) {
                result.add(run)
            } else {
                run.windowed(2, 1).forEach(result::add)
            }
        }
        return result
    }

    /** 生成 FTS 索引文本：拉丁词原样，CJK 转为空格分隔的二元词 */
    fun indexText(text: String): String {
        if (text.isBlank()) return ""
        return tokens(text).take(MAX_INDEX_TOKENS).joinToString(" ")
    }

    /** 生成 FTS MATCH 查询词元：拉丁词 + CJK 二元词，去重限量 */
    fun queryTerms(text: String, maxTerms: Int = 12): List<String> {
        return tokens(text).distinct().take(maxTerms)
    }
}

object AiMemoryExtractor {

    fun recordConversation(
        context: AiMemoryContext?,
        requestMessages: List<AiChatMessage>,
        assistantContent: String
    ) {
        if (context == null || assistantContent.isBlank()) return
        val userContent = requestMessages.lastOrNull { it.role == AiChatMessage.Role.USER }
            ?.content
            ?.trim()
            .orEmpty()
        if (userContent.isBlank()) return
        val now = System.currentTimeMillis()
        val fragmentContent = buildString {
            append("User: ").append(userContent.take(2_000))
            append("\nAssistant: ").append(assistantContent.trim().take(2_000))
        }
        if (fragmentContent.length >= 40) {
            AiMemoryStore.upsertFragment(
                AiMemoryFragment(
                    scope = context.scope,
                    bookKey = context.bookKey,
                    sessionId = context.sessionId,
                    sourceType = if (context.scope == AiMemoryItem.SCOPE_BOOK) {
                        AiMemoryFragment.SOURCE_READ_AI
                    } else {
                        AiMemoryFragment.SOURCE_CHAT
                    },
                    title = context.title.ifBlank { "AI 对话" },
                    content = fragmentContent,
                    importance = 40,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        extractPreference(userContent, context, now)?.let(AiMemoryStore::upsertItem)
    }

    private fun extractPreference(content: String, context: AiMemoryContext, now: Long): AiMemoryItem? {
        val normalized = content.replace(Regex("\\s+"), " ").trim()
        val hit = listOf("我希望", "我喜欢", "我不希望", "我不喜欢", "以后", "记住")
            .firstOrNull { normalized.contains(it) }
            ?: return null
        return AiMemoryItem(
            scope = AiMemoryItem.SCOPE_GLOBAL,
            sessionId = context.sessionId,
            type = AiMemoryItem.TYPE_USER_PREFERENCE,
            subject = "用户偏好",
            predicate = hit,
            objectValue = normalized.take(240),
            content = normalized.take(500),
            confidence = 70,
            importance = 75,
            sourceIds = JSONObject().put("sessionId", context.sessionId).toString(),
            createdAt = now,
            updatedAt = now
        )
    }
}
