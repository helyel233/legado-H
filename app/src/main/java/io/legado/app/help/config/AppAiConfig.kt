package io.legado.app.help.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.ai.AiSecretCipher
import io.legado.app.ui.book.read.ReadAiBookHistory
import io.legado.app.ui.main.ai.AI_API_MODE_CHAT_COMPLETIONS
import io.legado.app.ui.main.ai.AI_API_MODE_RESPONSES
import io.legado.app.ui.main.ai.AiAgentMode
import io.legado.app.ui.main.ai.AiChatCompanionConfig
import io.legado.app.ui.main.ai.AiChatSession
import io.legado.app.ui.main.ai.AiContextSummary
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiPersonaConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.main.ai.AiSkillConfig
import io.legado.app.ui.main.ai.AiWorldBookBinding
import io.legado.app.ui.main.ai.AiWorldBookConfig
import io.legado.app.ui.main.ai.AiWorldBookEntry
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.removePref
import io.legado.app.constant.AppLog
import splitties.init.appCtx
import java.net.URI

/**
 * AI assistant, model, provider, MCP, companion, skill, world-book, and read-aloud role config.
 * Extracted from [AppConfig] to keep the main configuration facade lean while preserving
 * 100% compatibility via delegating properties on [AppConfig].
 */
@Suppress("MemberVisibilityCanBePrivate", "ConstPropertyName")
object AppAiConfig {

    const val DEFAULT_AI_SYSTEM_PROMPT =
        "你是阅读应用内的 AI 助手。回答直接、准确、简洁。需要真实应用数据时必须优先调用工具，工具返回的数据优先级高于你的记忆，不允许编造工具未返回的结果。用户询问书架、书籍、作者、阅读记录、书籍简介、书源、分组、标签、分类方案时，必须先调用 query_bookshelf、get_bookshelf_book_info、manage_bookshelf_group 或 manage_bookshelf_tag，不要只说“我先看看”却不调用工具。用户要求创建、修改或调试书源时，你要像一个小型书源 agent 一样闭环执行：新建书源先调用 create_book_source(save=false) 生成草稿；修改已有书源先调用 get_book_source 读取完整 JSON；缺少页面结构时调用 fetch_source_html 获取搜索页、详情页、目录页或正文页 HTML；每次调试失败后都调用 update_book_source(save=false) 按日志和 HTML 修正规则，可传 patch，也可直接传 ruleToc/ruleContent/searchUrl 等字段；再调用 debug_book_source 调试，优先使用用户给出的详情页 URL、目录 URL、正文 URL 或关键词；最多循环 3 次。不要在第一次调试前询问“是否继续”，也不要只输出未经调试的 JSON。只有搜索、详情、目录、正文主要链路通过，或达到 3 次仍失败时，才给出最终结果和剩余失败点。只有用户明确要求保存、导入或完成时，才调用 update_book_source(save=true) 或 create_book_source(save=true) 写入本地书源库。用户要求整理书架时，顶层书架使用分组，分组内的小分类使用书籍标签；未分标签的书按“全部”处理。批量设置标签、重命名标签、移动分组或删除分组前，先查询书架并给出方案，用户确认后再调用写入工具。任何情况下都不允许删除书籍。工具返回图片路径时直接展示已有图片；用户只要求查看、展示头像时不要调用生图工具，只有明确要求生成、重绘或重新生成时才生成图片。"

    var aiAssistantEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiAssistantEnabled, false) && aiCurrentModelConfig != null
        set(value) {
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, value && aiCurrentModelConfig != null)
        }

    var aiProviderList: List<AiProviderConfig>
        get() {
            val providers = readAiProviders()
            syncAiState(providers, readAiModels(providers.map { it.id }.toSet()))
            return providers
        }
        set(value) {
            val providers = normalizeAiProviders(value)
            persistAiProviders(providers)
            val models = normalizeAiModels(
                readAiModelsRaw(),
                providers.map { it.id }.toSet()
            )
            persistAiModels(models)
            syncAiState(providers, models)
        }

    var aiCurrentProviderId: String?
        get() {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            syncAiState(providers, models)
            return appCtx.getPrefString(PreferKey.aiCurrentProviderId)
        }
        set(value) {
            val providers = readAiProviders()
            val providerId = providers.firstOrNull { it.id == value }?.id
            if (providerId.isNullOrBlank()) {
                appCtx.removePref(PreferKey.aiCurrentProviderId)
                appCtx.removePref(PreferKey.aiCurrentModelId)
            } else {
                appCtx.putPrefString(PreferKey.aiCurrentProviderId, providerId)
            }
            syncAiState(providers, readAiModels(providers.map { it.id }.toSet()))
        }

    val aiCurrentProvider: AiProviderConfig?
        get() = aiProviderList.firstOrNull { it.id == aiCurrentProviderId }

    var aiModelConfigList: List<AiModelConfig>
        get() {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            syncAiState(providers, models)
            return models
        }
        set(value) {
            val providers = readAiProviders()
            val models = normalizeAiModels(value, providers.map { it.id }.toSet())
            persistAiModels(models)
            syncAiState(providers, models)
        }

    var aiCurrentModelId: String?
        get() {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            syncAiState(providers, models)
            return appCtx.getPrefString(PreferKey.aiCurrentModelId)
        }
        set(value) {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            val model = models.firstOrNull { it.id == value }
            if (model == null) {
                appCtx.removePref(PreferKey.aiCurrentModelId)
            } else {
                appCtx.putPrefString(PreferKey.aiCurrentModelId, model.id)
                appCtx.putPrefString(PreferKey.aiCurrentProviderId, model.providerId)
            }
            syncAiState(providers, models)
        }

    val aiCurrentModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiCurrentModelId }

    var aiAskModelId: String?
        get() = sceneAiModelId(PreferKey.aiAskModelId)
        set(value) = setSceneAiModelId(PreferKey.aiAskModelId, value)

    val aiAskModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiAskModelId }

    var aiSummaryModelId: String?
        get() = sceneAiModelId(PreferKey.aiSummaryModelId)
        set(value) = setSceneAiModelId(PreferKey.aiSummaryModelId, value)

    val aiSummaryModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiSummaryModelId }

    var aiReadAloudRoleModelId: String?
        get() = sceneAiModelId(PreferKey.aiReadAloudRoleModelId)
        set(value) = setSceneAiModelId(PreferKey.aiReadAloudRoleModelId, value)

    val aiReadAloudRoleModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiReadAloudRoleModelId }

    var aiReadAloudRoleBackupModelId: String?
        get() = optionalSceneAiModelId(PreferKey.aiReadAloudRoleBackupModelId)
        set(value) = setOptionalSceneAiModelId(PreferKey.aiReadAloudRoleBackupModelId, value)

    val aiReadAloudRoleBackupModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiReadAloudRoleBackupModelId }

    var aiReadAloudRoleFirstResponseTimeoutSeconds: Int
        get() = appCtx.getPrefInt(PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds, 18)
            .coerceIn(5, 90)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds,
            value.coerceIn(5, 90)
        )

    val aiReadAloudRoleFirstResponseTimeoutMillis: Long
        get() = aiReadAloudRoleFirstResponseTimeoutSeconds * 1000L

    var aiReadAloudAudioModelId: String?
        get() = optionalSceneAiModelId(PreferKey.aiReadAloudAudioModelId)
        set(value) = setOptionalSceneAiModelId(PreferKey.aiReadAloudAudioModelId, value)

    val aiReadAloudAudioModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiReadAloudAudioModelId }
            ?: aiReadAloudRoleModelConfig

    var aiReadAloudAudioBackupModelId: String?
        get() = optionalSceneAiModelId(PreferKey.aiReadAloudAudioBackupModelId)
        set(value) = setOptionalSceneAiModelId(PreferKey.aiReadAloudAudioBackupModelId, value)

    val aiReadAloudAudioBackupModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiReadAloudAudioBackupModelId }
            ?: aiReadAloudRoleBackupModelConfig

    fun aiProviderForModel(model: AiModelConfig?): AiProviderConfig? {
        val providerId = model?.providerId ?: return null
        return aiProviderList.firstOrNull { it.id == providerId }
    }

    var aiMcpServerList: List<AiMcpServerConfig>
        get() = readAiMcpServers()
        set(value) {
            persistAiMcpServers(normalizeAiMcpServers(value))
        }

    val aiEnabledMcpServers: List<AiMcpServerConfig>
        get() = aiMcpServerList.filter { it.enabled }

    /** 书籍正文外发授权取值 */
    const val AI_CONTENT_CONSENT_ASK = "ask"
    const val AI_CONTENT_CONSENT_ALLOW = "allow"
    const val AI_CONTENT_CONSENT_DENY = "deny"

    /** 书籍正文外发授权：ask(每次询问)/allow(始终允许)/deny(始终禁止) */
    var aiExternalContentConsent: String
        get() {
            val stored = appCtx.getPrefString(PreferKey.aiExternalContentConsent)
            return when (stored) {
                AI_CONTENT_CONSENT_ALLOW, AI_CONTENT_CONSENT_DENY, AI_CONTENT_CONSENT_ASK -> stored
                else -> AI_CONTENT_CONSENT_ASK
            }
        }
        set(value) {
            val normalized = when (value) {
                AI_CONTENT_CONSENT_ALLOW, AI_CONTENT_CONSENT_DENY -> value
                else -> AI_CONTENT_CONSENT_ASK
            }
            appCtx.putPrefString(PreferKey.aiExternalContentConsent, normalized)
            postEvent(EventBus.AI_CONFIG_CHANGED, true)
        }

    var aiChatSessionList: List<AiChatSession>
        get() = runCatching {
            GSON.fromJsonArray<AiChatSession>(appCtx.getPrefString(PreferKey.aiChatSessionList))
                .getOrDefault(emptyList())
                .filter { session ->
                    session.id.isNotBlank() &&
                            session.title.isNotBlank() &&
                            session.messages.all { it.content.isNotBlank() }
                }
                .map { session ->
                    session.copy(
                        companionId = session.companionId
                            .trim()
                            .ifBlank { AiChatCompanionConfig.DEFAULT_COMPANION_ID },
                        messages = session.messages.map { message ->
                            message.copy(
                                kind = message.kind ?: io.legado.app.ui.main.ai.AiChatMessage.Kind.TEXT,
                                statusName = message.statusName,
                                statusStage = message.statusStage,
                                statusSuccess = message.statusSuccess
                            )
                        }
                    )
                }
                .sortedByDescending { it.updatedAt }
        }.getOrElse {
            AppLog.put("读取 AI 聊天历史失败, 已清理历史\n${it.localizedMessage}", it)
            appCtx.removePref(PreferKey.aiChatSessionList)
            appCtx.removePref(PreferKey.aiCurrentChatSessionId)
            emptyList()
        }
        set(value) {
            val sessions = value.distinctBy { it.id }
                .mapNotNull { session ->
                    val title = session.title.trim()
                    val normalizedMessages = session.messages
                        .filter { it.content.isNotBlank() }
                        .map { it.copy(pending = false) }
                    if (session.id.isBlank() || title.isBlank() || normalizedMessages.isEmpty()) {
                        null
                    } else {
                        session.copy(
                            id = session.id.trim(),
                            title = title,
                            companionId = session.companionId
                                .trim()
                                .ifBlank { AiChatCompanionConfig.DEFAULT_COMPANION_ID },
                            messages = normalizedMessages
                        )
                    }
                }
                .sortedByDescending { it.updatedAt }
                .take(100)
            if (sessions.isEmpty()) {
                appCtx.removePref(PreferKey.aiChatSessionList)
            } else {
                appCtx.putPrefString(PreferKey.aiChatSessionList, GSON.toJson(sessions))
            }
            val currentId = appCtx.getPrefString(PreferKey.aiCurrentChatSessionId)
            if (currentId != null && sessions.none { it.id == currentId }) {
                appCtx.removePref(PreferKey.aiCurrentChatSessionId)
            }
        }

    var aiReadHistoryList: List<ReadAiBookHistory>
        get() = runCatching {
            readAiReadHistories()
                .filter { it.bookUrl.isNotBlank() && it.sessions.isNotEmpty() }
                .map { history ->
                    val sessions = history.sessions
                        .filter { it.id.isNotBlank() && it.messages.any { message -> message.content.isNotBlank() } }
                        .map { session ->
                            session.copy(
                                messages = session.messages.filter { it.content.isNotBlank() }
                            )
                        }
                        .sortedByDescending { it.updatedAt }
                        .take(20)
                    history.copy(
                        bookUrl = history.bookUrl.trim(),
                        bookName = history.bookName.trim(),
                        currentSessionId = history.currentSessionId.takeIf { id -> sessions.any { it.id == id } }
                            ?: sessions.firstOrNull()?.id.orEmpty(),
                        sessions = sessions
                    )
                }
                .filter { it.sessions.isNotEmpty() }
                .sortedByDescending { it.updatedAt }
                .take(200)
        }.getOrElse {
            AppLog.put("读取阅读问 AI 历史失败, 已清理历史\n${it.localizedMessage}", it)
            appCtx.removePref(PreferKey.aiReadHistoryList)
            emptyList()
        }
        set(value) {
            val histories = value.distinctBy { it.bookUrl }
                .mapNotNull { history ->
                    val bookUrl = history.bookUrl.trim()
                    val sessions = history.sessions
                        .filter { it.id.isNotBlank() && it.messages.any { message -> message.content.isNotBlank() } }
                        .map { session ->
                            session.copy(
                                messages = session.messages.filter { it.content.isNotBlank() }.takeLast(80)
                            )
                        }
                        .sortedByDescending { it.updatedAt }
                        .take(20)
                    if (bookUrl.isBlank() || sessions.isEmpty()) {
                        null
                    } else {
                        history.copy(
                            bookUrl = bookUrl,
                            bookName = history.bookName.trim(),
                            updatedAt = history.updatedAt,
                            currentSessionId = history.currentSessionId.takeIf { id -> sessions.any { it.id == id } }
                                ?: sessions.first().id,
                            sessions = sessions
                        )
                    }
                }
                .sortedByDescending { it.updatedAt }
                .take(200)
            if (histories.isEmpty()) {
                appCtx.removePref(PreferKey.aiReadHistoryList)
            } else {
                appCtx.putPrefString(PreferKey.aiReadHistoryList, GSON.toJson(histories))
            }
        }

    private fun readAiReadHistories(): List<ReadAiBookHistory> {
        val raw = appCtx.getPrefString(PreferKey.aiReadHistoryList).orEmpty()
        val histories = GSON.fromJsonArray<ReadAiBookHistory>(raw).getOrDefault(emptyList())
        if (histories.any { it.sessions.isNotEmpty() }) {
            return histories
        }
        return migrateLegacyReadAiHistories(raw)
    }

    private fun migrateLegacyReadAiHistories(raw: String): List<ReadAiBookHistory> {
        return runCatching {
            org.json.JSONArray(raw).let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val records = item.optJSONArray("records") ?: continue
                        val sessions = buildList {
                            for (recordIndex in 0 until records.length()) {
                                val record = records.optJSONObject(recordIndex) ?: continue
                                val question = record.optString("question")
                                val answer = record.optString("answer")
                                if (question.isBlank() || answer.isBlank()) continue
                                val createdAt = record.optLong("createdAt", System.currentTimeMillis())
                                add(
                                    io.legado.app.ui.book.read.ReadAiSession(
                                        id = record.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                                        title = question.lineSequence().firstOrNull().orEmpty().take(24),
                                        chapterTitle = record.optString("chapterTitle"),
                                        chapterIndex = record.optInt("chapterIndex", -1),
                                        createdAt = createdAt,
                                        updatedAt = createdAt,
                                        messages = listOf(
                                            io.legado.app.ui.book.read.ReadAiMessage(
                                                role = io.legado.app.ui.book.read.ReadAiMessage.Role.USER,
                                                content = question,
                                                createdAt = createdAt
                                            ),
                                            io.legado.app.ui.book.read.ReadAiMessage(
                                                role = io.legado.app.ui.book.read.ReadAiMessage.Role.ASSISTANT,
                                                content = answer,
                                                createdAt = createdAt
                                            )
                                        )
                                    )
                                )
                            }
                        }
                        if (sessions.isNotEmpty()) {
                            add(
                                ReadAiBookHistory(
                                    bookUrl = item.optString("bookUrl"),
                                    bookName = item.optString("bookName"),
                                    updatedAt = item.optLong("updatedAt", sessions.maxOf { it.updatedAt }),
                                    currentSessionId = sessions.first().id,
                                    sessions = sessions
                                )
                            )
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    var aiCurrentChatSessionId: String?
        get() = appCtx.getPrefString(PreferKey.aiCurrentChatSessionId)
        set(value) {
            if (value.isNullOrBlank()) {
                appCtx.removePref(PreferKey.aiCurrentChatSessionId)
            } else {
                appCtx.putPrefString(PreferKey.aiCurrentChatSessionId, value.trim())
            }
        }

    var aiChatCompanionList: List<AiChatCompanionConfig>
        get() = readAiChatCompanions()
        set(value) {
            val companions = normalizeAiChatCompanions(value)
            persistAiChatCompanions(companions)
            syncCurrentAiChatCompanionId(companions)
        }

    var aiCurrentChatCompanionId: String
        get() = syncCurrentAiChatCompanionId(aiChatCompanionList)
        set(value) {
            val companions = aiChatCompanionList
            val targetId = companions.firstOrNull { it.id == value.trim() && it.enabled }?.id
                ?: AiChatCompanionConfig.DEFAULT_COMPANION_ID
            if (targetId == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
                appCtx.removePref(PreferKey.aiCurrentChatCompanionId)
            } else {
                appCtx.putPrefString(PreferKey.aiCurrentChatCompanionId, targetId)
            }
        }

    val aiCurrentChatCompanion: AiChatCompanionConfig
        get() {
            val companions = aiChatCompanionList
            val currentId = syncCurrentAiChatCompanionId(companions)
            return companions.firstOrNull { it.id == currentId }
                ?: defaultAiChatCompanion()
        }

    var aiChatAutoSpeakEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiChatAutoSpeakEnabled)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiChatAutoSpeakEnabled, value)

    var aiThinkingToolbarEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiThinkingToolbarEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiThinkingToolbarEnabled, value)

    fun upsertAiChatCompanion(config: AiChatCompanionConfig) {
        val companions = aiChatCompanionList.toMutableList()
        val index = companions.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            companions[index] = config.copy(updatedAt = System.currentTimeMillis())
        } else {
            companions += config.copy(order = companions.size, updatedAt = System.currentTimeMillis())
        }
        aiChatCompanionList = companions
    }

    fun removeAiChatCompanion(id: String) {
        if (id == AiChatCompanionConfig.DEFAULT_COMPANION_ID) return
        val companions = aiChatCompanionList.filterNot { it.id == id }
        aiChatCompanionList = companions
        aiChatSessionList = aiChatSessionList.filterNot { it.companionId == id }
        aiWorldBookList = aiWorldBookList.map { book ->
            book.copy(
                bindings = book.bindings.filterNot {
                    it.targetType == AiWorldBookBinding.TARGET_COMPANION && it.targetKey == id
                }
            )
        }
    }

    var aiSystemPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiSystemPrompt, DEFAULT_AI_SYSTEM_PROMPT)
            ?: DEFAULT_AI_SYSTEM_PROMPT
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank() || prompt == DEFAULT_AI_SYSTEM_PROMPT) {
                appCtx.removePref(PreferKey.aiSystemPrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiSystemPrompt, prompt)
            }
        }

    var aiSkillPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiSkillPrompt).orEmpty()
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank()) {
                appCtx.removePref(PreferKey.aiSkillPrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiSkillPrompt, prompt)
            }
        }

    var aiSkillList: List<AiSkillConfig>
        get() = readAiSkills()
        set(value) {
            persistAiSkills(normalizeAiSkills(value))
        }

    var aiWorldBookList: List<AiWorldBookConfig>
        get() = normalizeAiWorldBooks(
            GSON.fromJsonArray<AiWorldBookConfig>(appCtx.getPrefString(PreferKey.aiWorldBookList))
                .getOrDefault(emptyList())
        )
        set(value) {
            persistAiWorldBooks(normalizeAiWorldBooks(value))
        }

    var aiPersonaList: List<AiPersonaConfig>
        get() = normalizeAiPersonas(
            GSON.fromJsonArray<AiPersonaConfig>(appCtx.getPrefString(PreferKey.aiPersonaList))
                .getOrDefault(emptyList())
        )
        set(value) {
            val personas = normalizeAiPersonas(value)
            if (personas.isEmpty()) appCtx.removePref(PreferKey.aiPersonaList)
            else appCtx.putPrefString(PreferKey.aiPersonaList, GSON.toJson(personas))
        }

    var aiCurrentPersonaId: String?
        get() = appCtx.getPrefString(PreferKey.aiCurrentPersonaId)
        set(value) {
            if (value.isNullOrBlank()) appCtx.removePref(PreferKey.aiCurrentPersonaId)
            else appCtx.putPrefString(PreferKey.aiCurrentPersonaId, value)
        }

    val aiCurrentPersona: AiPersonaConfig?
        get() = aiPersonaList.firstOrNull { it.id == aiCurrentPersonaId }

    var aiImageProviderList: List<AiImageProviderConfig>
        get() = normalizeAiImageProviders(
            GSON.fromJsonArray<AiImageProviderConfig>(appCtx.getPrefString(PreferKey.aiImageProviderList))
                .getOrDefault(emptyList())
                .map { provider ->
                    provider.copy(
                        apiKey = AiSecretCipher.decrypt(provider.apiKey),
                        headers = AiSecretCipher.decrypt(provider.headers)
                    )
                }
        )
        set(value) {
            val providers = normalizeAiImageProviders(value)
            if (providers.isEmpty()) {
                appCtx.removePref(PreferKey.aiImageProviderList)
            } else {
                val saving = providers.map { provider ->
                    provider.copy(
                        apiKey = AiSecretCipher.encrypt(provider.apiKey),
                        headers = AiSecretCipher.encrypt(provider.headers)
                    )
                }
                appCtx.putPrefString(PreferKey.aiImageProviderList, GSON.toJson(saving))
            }
            syncAiImageState(providers)
        }

    val aiEnabledImageProviders: List<AiImageProviderConfig>
        get() = aiImageProviderList.filter { it.enabled }

    var aiCurrentImageProviderId: String?
        get() = appCtx.getPrefString(PreferKey.aiCurrentImageProviderId)
        set(value) {
            if (value.isNullOrBlank()) appCtx.removePref(PreferKey.aiCurrentImageProviderId)
            else appCtx.putPrefString(PreferKey.aiCurrentImageProviderId, value)
        }

    val aiCurrentImageProvider: AiImageProviderConfig?
        get() {
            val providers = aiEnabledImageProviders
            val currentId = aiCurrentImageProviderId
            if (currentId.isNullOrBlank()) {
                return providers.firstOrNull()?.also { aiCurrentImageProviderId = it.id }
            }
            return providers.firstOrNull { it.id == currentId }
                ?: providers.firstOrNull()?.also { aiCurrentImageProviderId = it.id }
        }

    fun findEnabledImageProvider(id: String?): AiImageProviderConfig? {
        val cleanId = id?.trim().orEmpty()
        if (cleanId.isBlank()) return null
        return aiEnabledImageProviders.firstOrNull { it.id == cleanId }
    }

    fun ensureCurrentImageProvider(preferredId: String? = null) {
        syncAiImageState(aiImageProviderList, preferredId)
    }

    private fun syncAiImageState(
        providers: List<AiImageProviderConfig>,
        preferredId: String? = null
    ) {
        val enabled = providers.filter { it.enabled }
        val currentId = appCtx.getPrefString(PreferKey.aiCurrentImageProviderId)
        val nextId = enabled.firstOrNull { it.id == preferredId }?.id
            ?: enabled.firstOrNull { it.id == currentId }?.id
            ?: enabled.firstOrNull()?.id
        if (nextId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCurrentImageProviderId)
        } else if (nextId != currentId) {
            appCtx.putPrefString(PreferKey.aiCurrentImageProviderId, nextId)
        }
    }

    const val AI_READ_ALOUD_ROLE_MODE_FULL = "full_tool"
    const val AI_READ_ALOUD_ROLE_MODE_CHUNK = "chunk_context"
    val DEFAULT_AI_READ_ALOUD_ROLE_PROMPT = """
        请按“适合朗读配音”的标准做多角色分配：
        1. 客户端已经切好 unitId，只按 unitId 给出朗读身份，不要改写或丢弃原文。
        2. 台词、心理活动、旁白按 unit 归因；引号和句末符号属于同一句台词时跟随台词角色。
        3. 说话人必须有文本证据；无法确认时 status=unknown，characterName 留空，不要猜。
        4. 优先使用已有角色卡里的准确角色名；角色卡会注入到上下文。
        5. 只有角色列表不存在且文本明确出现稳定新角色/稳定路人称谓时，才记录 newCharacters，并给出 evidence。
        6. 不要把代词、称呼对象、动作、语气、情绪、副词、短语当成新角色。
        7. 情绪明确时填写 emotionName/emotionTag；不明确留空，避免过度脑补。
    """.trimIndent()
    val DEFAULT_AI_READ_ALOUD_AUTO_CREATE_CHARACTER_PROMPT = """
        自动创建角色只用于补齐角色卡：
        1. 只有已有角色卡中不存在，且当前章节有明确文本证据的新人物、稳定路人称谓，才写入 newCharacters。
        2. 不要把“我、你、他、她、众人、有人、旁白”、称呼对象、动作、语气、情绪、副词或短语当作新角色。
        3. 尽量填写 gender=male/female、ageStage、roleLevel、identity、appearance；无法明确判断时留空，不要硬猜。
        4. ageStage 只写年纪阶段，例如“幼童、少年、青年、中年、老年”等适合小说资料卡的短文本，不要填写具体岁数。
        5. appearance 只写文本能支持的可见形象，例如发型、服饰、体型、气质、明显特征；没有证据留空。
        6. evidence 必须引用当前章节的简短证据。
    """.trimIndent()
    val DEFAULT_AI_READ_ALOUD_BGM_PROMPT = """
        配乐策略：
        1. 只在氛围、场景、危机、战斗、悬疑、温情、转折明显的连续段落使用配乐。
        2. 一段配乐至少覆盖 3 个 cue，除非是极短的开场、结尾或强转场。
        3. 普通对话、解释说明、平静过渡可以不配乐，避免逐句切换。
        4. 优先匹配曲库名称、分组和标签；没有合适曲目就不返回该范围。
    """.trimIndent()
    val DEFAULT_AI_READ_ALOUD_SOUND_EFFECT_PROMPT = """
        音效策略：
        1. 音效可以比配乐更频繁，但必须是文本中明确发生的声音事件。
        2. 优先标注开门、关门、敲门、脚步、撞击、破碎、枪声、爆炸、铃声、风雨雷电、车辆、衣物摩擦、金属碰撞等可听见事件。
        3. 不要把人物说话声、语气描写、心理活动、心声、名声、形容词当音效。
        4. 只能从音效候选事件中选择；没有合适音效就不要返回。
    """.trimIndent()
    val DEFAULT_AI_READ_ALOUD_PREPROCESS_RULES = """
        {
          "quotePairs": [
            {"open": "“", "close": "”"},
            {"open": "‘", "close": "’"},
            {"open": "\"", "close": "\""},
            {"open": "'", "close": "'"},
            {"open": "「", "close": "」"},
            {"open": "『", "close": "』"}
          ],
          "sentencePunctuation": "。！？…!?",
          "thoughtCuePatterns": ["心道", "暗道", "想道", "心想"],
          "dialogueMinLength": 6,
          "emphasisMaxLength": 8,
          "colonCueMaxLength": 24,
          "mergeCrossParagraphQuote": true,
          "soundEffectCuePatterns": [
            "吱呀", "砰", "咚", "哐", "啪", "轰", "咔嚓", "咯吱", "哗啦", "嗡",
            "铃声", "脚步声", "敲门声", "开门声", "关门声", "枪声", "雷声", "风声", "雨声"
          ],
          "soundEffectExcludePatterns": ["心声", "名声", "声音很", "声音低", "声音冷", "声音沙哑"],
          "soundEffectContextChars": 28
        }
    """.trimIndent()

    var aiReadAloudRoleEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiReadAloudRoleEnabled, false) &&
                aiReadAloudRoleModelConfig != null
        set(value) {
            appCtx.putPrefBoolean(PreferKey.aiReadAloudRoleEnabled, value && aiReadAloudRoleModelConfig != null)
        }

    var aiReadAloudRoleThreadCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiReadAloudRoleThreadCount, 2).coerceIn(1, 10)
        set(value) = appCtx.putPrefInt(PreferKey.aiReadAloudRoleThreadCount, value.coerceIn(1, 10))

    var aiReadAloudRoleContextParagraphs: Int
        get() = appCtx.getPrefInt(PreferKey.aiReadAloudRoleContextParagraphs, 2).coerceIn(0, 20)
        set(value) = appCtx.putPrefInt(PreferKey.aiReadAloudRoleContextParagraphs, value.coerceIn(0, 20))

    var aiReadAloudRoleMergeGapParagraphs: Int
        get() = appCtx.getPrefInt(PreferKey.aiReadAloudRoleMergeGapParagraphs, 1).coerceIn(0, 10)
        set(value) = appCtx.putPrefInt(PreferKey.aiReadAloudRoleMergeGapParagraphs, value.coerceIn(0, 10))

    var aiReadAloudRolePrompt: String
        get() = appCtx.getPrefString(PreferKey.aiReadAloudRolePrompt)
            .orEmpty()
            .ifBlank { DEFAULT_AI_READ_ALOUD_ROLE_PROMPT }
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank() || prompt == DEFAULT_AI_READ_ALOUD_ROLE_PROMPT) {
                appCtx.removePref(PreferKey.aiReadAloudRolePrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiReadAloudRolePrompt, prompt.take(4000))
            }
        }

    val aiReadAloudRoleUsingDefaultPrompt: Boolean
        get() = appCtx.getPrefString(PreferKey.aiReadAloudRolePrompt).isNullOrBlank()

    var aiReadAloudRolePreprocessRules: String
        get() = appCtx.getPrefString(PreferKey.aiReadAloudRolePreprocessRules)
            .orEmpty()
            .ifBlank { DEFAULT_AI_READ_ALOUD_PREPROCESS_RULES }
        set(value) {
            val rules = value.trim()
            if (rules.isBlank() || rules == DEFAULT_AI_READ_ALOUD_PREPROCESS_RULES) {
                appCtx.removePref(PreferKey.aiReadAloudRolePreprocessRules)
            } else {
                appCtx.putPrefString(PreferKey.aiReadAloudRolePreprocessRules, rules.take(8000))
            }
        }

    val aiReadAloudRoleUsingDefaultPreprocessRules: Boolean
        get() = appCtx.getPrefString(PreferKey.aiReadAloudRolePreprocessRules).isNullOrBlank()

    var aiReadAloudRoleMode: String
        get() = appCtx.getPrefString(PreferKey.aiReadAloudRoleMode)
            ?.takeIf { it == AI_READ_ALOUD_ROLE_MODE_FULL || it == AI_READ_ALOUD_ROLE_MODE_CHUNK }
            ?: AI_READ_ALOUD_ROLE_MODE_CHUNK
        set(value) = appCtx.putPrefString(
            PreferKey.aiReadAloudRoleMode,
            value.takeIf { it == AI_READ_ALOUD_ROLE_MODE_FULL || it == AI_READ_ALOUD_ROLE_MODE_CHUNK }
                ?: AI_READ_ALOUD_ROLE_MODE_CHUNK
        )

    var aiReadAloudAutoCreateCharacters: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiReadAloudAutoCreateCharacters, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiReadAloudAutoCreateCharacters, value)

    var aiReadAloudAutoCreateCharacterPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiReadAloudAutoCreateCharacterPrompt)
            .orEmpty()
            .ifBlank { DEFAULT_AI_READ_ALOUD_AUTO_CREATE_CHARACTER_PROMPT }
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank() || prompt == DEFAULT_AI_READ_ALOUD_AUTO_CREATE_CHARACTER_PROMPT) {
                appCtx.removePref(PreferKey.aiReadAloudAutoCreateCharacterPrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiReadAloudAutoCreateCharacterPrompt, prompt.take(4000))
            }
        }

    val aiReadAloudUsingDefaultAutoCreateCharacterPrompt: Boolean
        get() = appCtx.getPrefString(PreferKey.aiReadAloudAutoCreateCharacterPrompt).isNullOrBlank()

    var aiReadAloudAutoCreateAvatar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiReadAloudAutoCreateAvatar, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiReadAloudAutoCreateAvatar, value)

    var aiReadAloudBgmEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiReadAloudBgmEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiReadAloudBgmEnabled, value)

    var aiReadAloudBgmPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiReadAloudBgmPrompt)
            .orEmpty()
            .ifBlank { DEFAULT_AI_READ_ALOUD_BGM_PROMPT }
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank() || prompt == DEFAULT_AI_READ_ALOUD_BGM_PROMPT) {
                appCtx.removePref(PreferKey.aiReadAloudBgmPrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiReadAloudBgmPrompt, prompt.take(4000))
            }
        }

    val aiReadAloudUsingDefaultBgmPrompt: Boolean
        get() = appCtx.getPrefString(PreferKey.aiReadAloudBgmPrompt).isNullOrBlank()

    var aiReadAloudSoundEffectPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiReadAloudSoundEffectPrompt)
            .orEmpty()
            .ifBlank { DEFAULT_AI_READ_ALOUD_SOUND_EFFECT_PROMPT }
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank() || prompt == DEFAULT_AI_READ_ALOUD_SOUND_EFFECT_PROMPT) {
                appCtx.removePref(PreferKey.aiReadAloudSoundEffectPrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiReadAloudSoundEffectPrompt, prompt.take(4000))
            }
        }

    val aiReadAloudUsingDefaultSoundEffectPrompt: Boolean
        get() = appCtx.getPrefString(PreferKey.aiReadAloudSoundEffectPrompt).isNullOrBlank()

    var aiReadAloudBgmVolume: Int
        get() = appCtx.getPrefInt(PreferKey.aiReadAloudBgmVolume, 100).coerceIn(0, 100)
        set(value) = appCtx.putPrefInt(PreferKey.aiReadAloudBgmVolume, value.coerceIn(0, 100))

    val aiReadAloudBgmVolumeScale: Float
        get() = aiReadAloudBgmVolume / 100f

    var aiReadAloudSfxVolume: Int
        get() = appCtx.getPrefInt(PreferKey.aiReadAloudSfxVolume, 80).coerceIn(0, 100)
        set(value) = appCtx.putPrefInt(PreferKey.aiReadAloudSfxVolume, value.coerceIn(0, 100))

    val aiReadAloudSfxVolumeScale: Float
        get() = aiReadAloudSfxVolume / 100f

    var readAloudSpeakerLoudnessEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudSpeakerLoudnessEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudSpeakerLoudnessEnabled, value)

    var readAloudTargetVoiceVolume: Int
        get() = appCtx.getPrefInt(PreferKey.readAloudTargetVoiceVolume, 100).coerceIn(60, 160)
        set(value) = appCtx.putPrefInt(PreferKey.readAloudTargetVoiceVolume, value.coerceIn(60, 160))

    var readAloudMaxSpeakerGain: Int
        get() = appCtx.getPrefInt(PreferKey.readAloudMaxSpeakerGain, 135).coerceIn(100, 200)
        set(value) = appCtx.putPrefInt(PreferKey.readAloudMaxSpeakerGain, value.coerceIn(100, 200))

    var readAloudNarratorBaseGain: Int
        get() = appCtx.getPrefInt(PreferKey.readAloudNarratorBaseGain, 110).coerceIn(60, 160)
        set(value) = appCtx.putPrefInt(PreferKey.readAloudNarratorBaseGain, value.coerceIn(60, 160))

    var readAloudSpeakerLoudnessStats: String
        get() = appCtx.getPrefString(PreferKey.readAloudSpeakerLoudnessStats).orEmpty()
        set(value) = appCtx.putPrefString(PreferKey.readAloudSpeakerLoudnessStats, value.take(200_000))

    var aiContextCompressionEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiContextCompressionEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiContextCompressionEnabled, value)

    var aiContextWindowTokens: Int
        get() = appCtx.getPrefInt(PreferKey.aiContextWindowTokens, 258_000).coerceIn(8_000, 2_000_000)
        set(value) = appCtx.putPrefInt(PreferKey.aiContextWindowTokens, value.coerceIn(8_000, 2_000_000))

    var aiThinkingContextTokens: Int
        get() = appCtx.getPrefInt(PreferKey.aiThinkingContextTokens, 128_000).coerceIn(0, 1_000_000)
        set(value) = appCtx.putPrefInt(PreferKey.aiThinkingContextTokens, value.coerceIn(0, 1_000_000))

    /** 每日 AI token 预算，0 表示不限；超额后当日所有模型请求被拦截 */
    var aiDailyTokenBudget: Long
        get() = appCtx.getPrefLong(PreferKey.aiDailyTokenBudget, 0L).coerceAtLeast(0L)
        set(value) = appCtx.putPrefLong(PreferKey.aiDailyTokenBudget, value.coerceAtLeast(0L))

    var aiTavilyEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiTavilyEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiTavilyEnabled, value)

    var aiEnterToSend: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiEnterToSend, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiEnterToSend, value)

    var aiEnabledToolNames: Set<String>
        get() = appCtx.getPrefStringSet(PreferKey.aiEnabledToolNames, mutableSetOf())
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet<String>()
        set(value) = appCtx.putPrefStringSet(
            PreferKey.aiEnabledToolNames,
            value.filter { it.isNotBlank() }.toMutableSet()
        )

    var aiEnabledToolNamesVersion: Int
        get() = appCtx.getPrefInt(PreferKey.aiEnabledToolNamesVersion, 0)
        set(value) = appCtx.putPrefInt(PreferKey.aiEnabledToolNamesVersion, value.coerceAtLeast(0))

    const val AI_READ_TOOL_MODE_ENABLED = "enabled"
    const val AI_READ_TOOL_MODE_SAFE = "read_safe"
    const val AI_READ_TOOL_MODE_ALL = "all"

    var aiReadToolMode: String
        get() = appCtx.getPrefString(PreferKey.aiReadToolMode)
            ?.takeIf {
                it == AI_READ_TOOL_MODE_ENABLED ||
                        it == AI_READ_TOOL_MODE_SAFE ||
                        it == AI_READ_TOOL_MODE_ALL
            }
            ?: AI_READ_TOOL_MODE_ENABLED
        set(value) = appCtx.putPrefString(
            PreferKey.aiReadToolMode,
            value.takeIf {
                it == AI_READ_TOOL_MODE_ENABLED ||
                        it == AI_READ_TOOL_MODE_SAFE ||
                        it == AI_READ_TOOL_MODE_ALL
            } ?: AI_READ_TOOL_MODE_ENABLED
        )

    var aiChatAgentMode: AiAgentMode
        get() = AiAgentMode.fromId(appCtx.getPrefString(PreferKey.aiChatAgentMode))
        set(value) = appCtx.putPrefString(PreferKey.aiChatAgentMode, value.id)

    var aiAgentToolMaxAttempts: Int
        get() = appCtx.getPrefInt(PreferKey.aiAgentToolMaxAttempts, 3).coerceIn(1, 5)
        set(value) = appCtx.putPrefInt(PreferKey.aiAgentToolMaxAttempts, value.coerceIn(1, 5))

    var aiAgentMaxToolRounds: Int
        get() = appCtx.getPrefInt(PreferKey.aiAgentMaxToolRounds, 32).coerceIn(4, 64)
        set(value) = appCtx.putPrefInt(PreferKey.aiAgentMaxToolRounds, value.coerceIn(4, 64))

    var aiAgentToolRetryBackoffMillis: Int
        get() = appCtx.getPrefInt(PreferKey.aiAgentToolRetryBackoffMillis, 600).coerceIn(0, 5_000)
        set(value) = appCtx.putPrefInt(PreferKey.aiAgentToolRetryBackoffMillis, value.coerceIn(0, 5_000))

    var aiTavilyApiKey: String
        get() = SecurePrefs.read(PreferKey.aiTavilyApiKey).orEmpty()
        set(value) {
            val key = value.trim()
            SecurePrefs.write(PreferKey.aiTavilyApiKey, key.ifBlank { null })
        }

    var aiTavilyBaseUrl: String
        get() = appCtx.getPrefString(PreferKey.aiTavilyBaseUrl, "https://api.tavily.com/search")
            ?: "https://api.tavily.com/search"
        set(value) {
            val url = value.trim().ifBlank { "https://api.tavily.com/search" }
            appCtx.putPrefString(PreferKey.aiTavilyBaseUrl, url)
        }

    var aiTavilySearchDepth: String
        get() = appCtx.getPrefString(PreferKey.aiTavilySearchDepth, "basic") ?: "basic"
        set(value) = appCtx.putPrefString(PreferKey.aiTavilySearchDepth, value.trim().ifBlank { "basic" })

    var aiTavilyTopic: String
        get() = appCtx.getPrefString(PreferKey.aiTavilyTopic, "general") ?: "general"
        set(value) = appCtx.putPrefString(PreferKey.aiTavilyTopic, value.trim().ifBlank { "general" })

    var aiTavilyMaxResults: Int
        get() = appCtx.getPrefInt(PreferKey.aiTavilyMaxResults, 5).coerceIn(1, 10)
        set(value) = appCtx.putPrefInt(PreferKey.aiTavilyMaxResults, value.coerceIn(1, 10))

    val aiEnabledSkills: List<AiSkillConfig>
        get() = aiSkillList.filter { it.enabled }

    private fun readAiProviders(): List<AiProviderConfig> {
        migrateLegacyAiConfigIfNeeded()
        return normalizeAiProviders(
            GSON.fromJsonArray<AiProviderConfig>(appCtx.getPrefString(PreferKey.aiProviderList))
                .getOrDefault(emptyList())
                .map { provider ->
                    provider.copy(
                        apiKey = AiSecretCipher.decrypt(provider.apiKey),
                        headers = AiSecretCipher.decrypt(provider.headers.orEmpty())
                    )
                }
        )
    }

    private fun readAiModels(validProviderIds: Set<String>): List<AiModelConfig> {
        migrateLegacyAiConfigIfNeeded()
        return normalizeAiModels(readAiModelsRaw(), validProviderIds)
    }

    private fun readAiModelsRaw(): List<AiModelConfig> {
        return GSON.fromJsonArray<AiModelConfig>(appCtx.getPrefString(PreferKey.aiModelConfigList))
            .getOrDefault(emptyList())
    }

    private fun normalizeAiProviders(value: List<AiProviderConfig>): List<AiProviderConfig> {
        return value.mapNotNull { provider ->
            val name = safeString { provider.name }.trim()
            val id = safeString { provider.id }.trim()
            if (name.isEmpty() || id.isEmpty()) {
                null
            } else {
                AiProviderConfig(
                    id = id,
                    name = name,
                    baseUrl = safeString { provider.baseUrl }.trim(),
                    apiKey = safeString { provider.apiKey }.trim(),
                    headers = safeString { provider.headers }.trim(),
                    apiMode = normalizeAiApiMode(safeString { provider.apiMode }),
                    promptCache = safeBoolean(false) { provider.promptCache }
                )
            }
        }.distinctBy { it.id }
    }

    private fun normalizeAiModels(
        value: List<AiModelConfig>,
        validProviderIds: Set<String>
    ): List<AiModelConfig> {
        return value.mapNotNull { model ->
            val id = safeString { model.id }.trim()
            val providerId = safeString { model.providerId }.trim()
            val modelId = safeString { model.modelId }.trim()
            if (id.isEmpty() || providerId !in validProviderIds || modelId.isEmpty()) {
                null
            } else {
                AiModelConfig(id = id, providerId = providerId, modelId = modelId)
            }
        }.distinctBy { "${it.providerId}|${it.modelId}" }
    }

    private fun persistAiProviders(providers: List<AiProviderConfig>) {
        if (providers.isEmpty()) {
            appCtx.removePref(PreferKey.aiProviderList)
        } else {
            val saving = providers.map { provider ->
                provider.copy(
                    apiKey = AiSecretCipher.encrypt(provider.apiKey),
                    headers = AiSecretCipher.encrypt(provider.headers.orEmpty())
                )
            }
            appCtx.putPrefString(PreferKey.aiProviderList, GSON.toJson(saving))
        }
    }

    private fun persistAiModels(models: List<AiModelConfig>) {
        if (models.isEmpty()) {
            appCtx.removePref(PreferKey.aiModelConfigList)
        } else {
            appCtx.putPrefString(PreferKey.aiModelConfigList, GSON.toJson(models))
        }
    }

    private fun sceneAiModelId(key: String): String? {
        val models = aiModelConfigList
        val stored = appCtx.getPrefString(key)
        val modelId = models.firstOrNull { it.id == stored }?.id
            ?: models.firstOrNull()?.id
        if (modelId.isNullOrBlank()) {
            appCtx.removePref(key)
        } else if (modelId != stored) {
            appCtx.putPrefString(key, modelId)
        }
        return modelId
    }

    private fun optionalSceneAiModelId(key: String): String? {
        val stored = appCtx.getPrefString(key)
        if (stored.isNullOrBlank()) return null
        val modelId = aiModelConfigList.firstOrNull { it.id == stored }?.id
        if (modelId.isNullOrBlank()) {
            appCtx.removePref(key)
            return null
        }
        return modelId
    }

    private fun setSceneAiModelId(key: String, value: String?) {
        val models = aiModelConfigList
        val modelId = models.firstOrNull { it.id == value }?.id
        if (modelId.isNullOrBlank()) {
            appCtx.removePref(key)
        } else {
            appCtx.putPrefString(key, modelId)
        }
    }

    private fun setOptionalSceneAiModelId(key: String, value: String?) {
        val modelId = aiModelConfigList.firstOrNull { it.id == value }?.id
        if (modelId.isNullOrBlank()) {
            appCtx.removePref(key)
        } else {
            appCtx.putPrefString(key, modelId)
        }
    }

    private fun readAiMcpServers(): List<AiMcpServerConfig> {
        return normalizeAiMcpServers(
            GSON.fromJsonArray<AiMcpServerConfig>(appCtx.getPrefString(PreferKey.aiMcpServerList))
                .getOrDefault(emptyList())
                .map { server ->
                    server.copy(apiKey = AiSecretCipher.decrypt(server.apiKey))
                }
        )
    }

    private fun normalizeAiMcpServers(value: List<AiMcpServerConfig>): List<AiMcpServerConfig> {
        return value.mapNotNull { server ->
            val id = safeString { server.id }.trim()
            val name = safeString { server.name }.trim()
            val endpoint = safeString { server.endpoint }.trim()
            if (id.isEmpty() || name.isEmpty() || endpoint.isEmpty()) {
                null
            } else {
                AiMcpServerConfig(
                    id = id,
                    name = name,
                    endpoint = endpoint,
                    apiKey = safeString { server.apiKey }.trim(),
                    enabled = safeBoolean(true) { server.enabled }
                )
            }
        }.distinctBy { it.id }
    }

    private fun persistAiMcpServers(servers: List<AiMcpServerConfig>) {
        if (servers.isEmpty()) {
            appCtx.removePref(PreferKey.aiMcpServerList)
        } else {
            val saving = servers.map { server ->
                server.copy(apiKey = AiSecretCipher.encrypt(server.apiKey))
            }
            appCtx.putPrefString(PreferKey.aiMcpServerList, GSON.toJson(saving))
        }
    }

    private fun readAiChatCompanions(): List<AiChatCompanionConfig> {
        val companions = normalizeAiChatCompanions(
            GSON.fromJsonArray<AiChatCompanionConfig>(appCtx.getPrefString(PreferKey.aiChatCompanionList))
                .getOrDefault(emptyList())
        )
        if (appCtx.getPrefString(PreferKey.aiChatCompanionList).isNullOrBlank()) {
            persistAiChatCompanions(companions)
        }
        syncCurrentAiChatCompanionId(companions)
        return companions
    }

    private fun normalizeAiChatCompanions(
        value: List<AiChatCompanionConfig>
    ): List<AiChatCompanionConfig> {
        val normalized = value.mapNotNull { config ->
            val id = safeString { config.id }.trim()
            val name = safeString { config.name }.trim()
            if (id.isEmpty() || name.isEmpty()) {
                null
            } else {
                val type = safeString { config.type }.trim()
                    .takeIf {
                        it == AiChatCompanionConfig.TYPE_DEFAULT ||
                                it == AiChatCompanionConfig.TYPE_CHARACTER
                    }
                    ?: AiChatCompanionConfig.TYPE_DEFAULT
                AiChatCompanionConfig(
                    id = id,
                    type = if (id == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
                        AiChatCompanionConfig.TYPE_DEFAULT
                    } else {
                        type
                    },
                    name = name,
                    avatar = safeString { config.avatar }.trim(),
                    bookKey = safeString { config.bookKey }.trim(),
                    characterId = safeString { config.characterId }.trim(),
                    prompt = safeString { config.prompt }.trim(),
                    worldBookIds = safeStringList { config.worldBookIds },
                    ttsRouteJson = safeString { config.ttsRouteJson }.trim(),
                    order = safeInt(0) { config.order },
                    enabled = if (id == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
                        true
                    } else {
                        safeBoolean(true) { config.enabled }
                    },
                    updatedAt = safeLong(System.currentTimeMillis()) { config.updatedAt }
                )
            }
        }
            .distinctBy { it.id }
        val default = normalized
            .firstOrNull { it.id == AiChatCompanionConfig.DEFAULT_COMPANION_ID }
            ?.let { config ->
                config.copy(
                    type = AiChatCompanionConfig.TYPE_DEFAULT,
                    name = config.name.ifBlank { appCtx.getString(R.string.svc_default_assistant) },
                    prompt = config.prompt.ifBlank { DEFAULT_AI_SYSTEM_PROMPT },
                    enabled = true,
                    order = 0
                )
            }
            ?: defaultAiChatCompanion()
        val custom = normalized
            .filter { it.id != AiChatCompanionConfig.DEFAULT_COMPANION_ID }
            .sortedBy { it.order }
        return (listOf(default) + custom)
            .mapIndexed { index, config -> config.copy(order = index) }
    }

    private fun defaultAiChatCompanion(): AiChatCompanionConfig {
        val prompt = appCtx.getPrefString(PreferKey.aiSystemPrompt, DEFAULT_AI_SYSTEM_PROMPT)
            ?.trim()
            ?.ifBlank { DEFAULT_AI_SYSTEM_PROMPT }
            ?: DEFAULT_AI_SYSTEM_PROMPT
        return AiChatCompanionConfig(
            id = AiChatCompanionConfig.DEFAULT_COMPANION_ID,
            type = AiChatCompanionConfig.TYPE_DEFAULT,
            name = appCtx.getString(R.string.svc_default_assistant),
            prompt = prompt,
            order = 0,
            enabled = true
        )
    }

    private fun persistAiChatCompanions(companions: List<AiChatCompanionConfig>) {
        appCtx.putPrefString(PreferKey.aiChatCompanionList, GSON.toJson(companions))
    }

    private fun syncCurrentAiChatCompanionId(companions: List<AiChatCompanionConfig>): String {
        val stored = appCtx.getPrefString(PreferKey.aiCurrentChatCompanionId).orEmpty()
        val targetId = companions.firstOrNull { it.id == stored && it.enabled }?.id
            ?: companions.firstOrNull { it.enabled }?.id
            ?: AiChatCompanionConfig.DEFAULT_COMPANION_ID
        if (targetId == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
            appCtx.removePref(PreferKey.aiCurrentChatCompanionId)
        } else if (targetId != stored) {
            appCtx.putPrefString(PreferKey.aiCurrentChatCompanionId, targetId)
        }
        return targetId
    }

    private fun readAiSkills(): List<AiSkillConfig> {
        migrateLegacyAiSkillIfNeeded()
        return normalizeAiSkills(
            GSON.fromJsonArray<AiSkillConfig>(appCtx.getPrefString(PreferKey.aiSkillList))
                .getOrDefault(emptyList())
        )
    }

    private fun normalizeAiSkills(value: List<AiSkillConfig>): List<AiSkillConfig> {
        return value.mapNotNull { skill ->
            val id = safeString { skill.id }.trim()
            val name = safeString { skill.name }.trim()
            val content = safeString { skill.content }.trim()
            if (id.isEmpty() || name.isEmpty() || content.isEmpty()) {
                null
            } else {
                AiSkillConfig(
                    id = id,
                    name = name,
                    description = safeString { skill.description }.trim(),
                    content = content,
                    sourceUrl = safeString { skill.sourceUrl }.trim(),
                    enabled = safeBoolean(true) { skill.enabled }
                )
            }
        }.distinctBy { it.id }
    }

    private fun normalizeAiWorldBooks(value: List<AiWorldBookConfig>): List<AiWorldBookConfig> {
        return value.mapNotNull { book ->
            val id = safeString { book.id }.trim()
            val name = safeString { book.name }.trim()
            if (id.isEmpty() || name.isEmpty()) {
                null
            } else {
                val scope = safeString { book.scope }.trim()
                    .takeIf {
                        it == AiWorldBookConfig.SCOPE_GLOBAL ||
                                it == AiWorldBookConfig.SCOPE_BOOK ||
                                it == AiWorldBookConfig.SCOPE_SESSION
                    }
                    ?: AiWorldBookConfig.SCOPE_GLOBAL
                AiWorldBookConfig(
                    id = id,
                    name = name,
                    description = safeString { book.description }.trim(),
                    version = safeInt(1) { book.version }.takeIf { it > 0 } ?: 1,
                    type = safeString { book.type }.trim()
                        .takeIf { it == AiWorldBookConfig.TYPE_LOREBOOK }
                        ?: AiWorldBookConfig.TYPE_LOREBOOK,
                    scope = scope,
                    bookKey = safeString { book.bookKey }.trim(),
                    enabled = safeBoolean(true) { book.enabled },
                    bindingVersion = 1,
                    maxEntries = safeInt(12) { book.maxEntries }.coerceIn(1, 40),
                    bindings = normalizeAiWorldBookBindings(book, scope),
                    order = safeInt(0) { book.order },
                    entries = normalizeAiWorldBookEntries(book.entries)
                )
            }
        }
            .distinctBy { it.id }
            .sortedBy { it.order }
            .mapIndexed { index, book -> book.copy(order = index) }
    }

    private fun normalizeAiWorldBookEntries(value: List<AiWorldBookEntry>): List<AiWorldBookEntry> {
        return value.mapNotNull { entry ->
            val id = safeString { entry.id }.trim()
            val displayName = safeString { entry.name }.trim()
                .ifBlank { safeString { entry.title }.trim() }
            val content = safeString { entry.content }.trim()
            if (id.isEmpty() || displayName.isEmpty() || content.isEmpty()) {
                null
            } else {
                val keywords = safeStringList { entry.keywords }
                    .ifEmpty { safeStringList { entry.keys } }
                val useRegex = safeBoolean(false) { entry.useRegex } ||
                        safeBoolean(false) { entry.regexEnabled }
                val constantActive = safeBoolean(false) { entry.constantActive } ||
                        safeBoolean(false) { entry.constant }
                AiWorldBookEntry(
                    id = id,
                    title = displayName,
                    name = displayName,
                    content = content.take(8_000),
                    keys = keywords,
                    keywords = keywords,
                    secondaryKeys = safeStringList { entry.secondaryKeys },
                    excludeKeys = safeStringList { entry.excludeKeys },
                    regexEnabled = useRegex,
                    useRegex = useRegex,
                    caseSensitive = safeBoolean(false) { entry.caseSensitive },
                    enabled = safeBoolean(true) { entry.enabled },
                    constant = constantActive,
                    constantActive = constantActive,
                    priority = safeInt(50) { entry.priority }.coerceIn(0, 100),
                    position = normalizeWorldBookPosition(safeString { entry.position }.trim()),
                    injectDepth = safeInt(4) { entry.injectDepth }.coerceIn(0, 64),
                    role = normalizeWorldBookRole(safeString { entry.role }.trim()),
                    scanDepth = safeInt(8) { entry.scanDepth }.coerceIn(1, 64),
                    maxMatches = safeInt(1) { entry.maxMatches }.coerceIn(1, 20),
                    order = safeInt(0) { entry.order }
                )
            }
        }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<AiWorldBookEntry> { it.priority }.thenBy { it.order })
            .mapIndexed { index, entry -> entry.copy(order = index) }
    }

    private fun normalizeWorldBookPosition(position: String): String {
        return when (position) {
            AiWorldBookEntry.POSITION_AFTER_SYSTEM_PROMPT,
            AiWorldBookEntry.POSITION_BEFORE_PROMPT,
            AiWorldBookEntry.POSITION_INJECT_DEPTH,
            AiWorldBookEntry.POSITION_BEFORE_LAST_USER -> position
            else -> AiWorldBookEntry.POSITION_AFTER_SYSTEM_PROMPT
        }
    }

    private fun normalizeWorldBookRole(role: String): String {
        return when (role) {
            AiWorldBookEntry.ROLE_SYSTEM,
            AiWorldBookEntry.ROLE_USER,
            AiWorldBookEntry.ROLE_ASSISTANT -> role
            else -> AiWorldBookEntry.ROLE_USER
        }
    }

    private fun normalizeAiWorldBookBindings(
        book: AiWorldBookConfig,
        legacyScope: String
    ): List<AiWorldBookBinding> {
        val explicitBindings = runCatching { book.bindings }.getOrDefault(emptyList())
        val sourceBindings = if (explicitBindings.isEmpty() && safeInt(0) { book.bindingVersion } <= 0) {
            legacyWorldBookBinding(book, legacyScope)?.let(::listOf) ?: emptyList()
        } else {
            explicitBindings
        }
        return sourceBindings.mapNotNull { binding ->
            val id = safeString { binding.id }.trim()
            val targetType = normalizeWorldBookTarget(safeString { binding.targetType }.trim())
            val targetKey = safeString { binding.targetKey }.trim()
            if (id.isEmpty() || targetType.isBlank()) {
                null
            } else {
                AiWorldBookBinding(
                    id = id,
                    targetType = targetType,
                    targetKey = targetKey,
                    enabled = safeBoolean(true) { binding.enabled },
                    order = safeInt(0) { binding.order }
                )
            }
        }
            .distinctBy { "${it.targetType}|${it.targetKey}" }
            .sortedBy { it.order }
            .mapIndexed { index, binding -> binding.copy(order = index) }
    }

    private fun legacyWorldBookBinding(
        book: AiWorldBookConfig,
        legacyScope: String
    ): AiWorldBookBinding? {
        return when (legacyScope) {
            AiWorldBookConfig.SCOPE_GLOBAL -> AiWorldBookBinding(targetType = AiWorldBookBinding.TARGET_GLOBAL)
            else -> null
        }
    }

    private fun normalizeWorldBookTarget(targetType: String): String {
        return when (targetType) {
            AiWorldBookBinding.TARGET_GLOBAL,
            AiWorldBookBinding.TARGET_COMPANION -> targetType
            else -> ""
        }
    }

    private fun normalizeAiPersonas(value: List<AiPersonaConfig>): List<AiPersonaConfig> {
        return value.mapNotNull { persona ->
            val id = safeString { persona.id }.trim()
            val name = safeString { persona.name }.trim()
            val prompt = safeString { persona.prompt }.trim()
            if (id.isEmpty() || name.isEmpty() || prompt.isEmpty()) {
                null
            } else {
                AiPersonaConfig(
                    id = id,
                    name = name,
                    prompt = prompt,
                    current = safeBoolean(false) { persona.current }
                )
            }
        }.distinctBy { it.id }
    }

    private fun normalizeAiImageProviders(value: List<AiImageProviderConfig>): List<AiImageProviderConfig> {
        return value.mapNotNull { provider ->
            val id = safeString { provider.id }.trim()
            val name = safeString { provider.name }.trim()
            if (id.isEmpty() || name.isEmpty()) {
                null
            } else {
                val type = safeString { provider.type }.trim()
                    .takeIf { it == AiImageProviderConfig.TYPE_JS || it == AiImageProviderConfig.TYPE_OPENAI }
                    ?: AiImageProviderConfig.TYPE_OPENAI
                AiImageProviderConfig(
                    id = id,
                    name = name,
                    type = type,
                    baseUrl = safeString { provider.baseUrl }.trim(),
                    apiKey = safeString { provider.apiKey }.trim(),
                    headers = safeString { provider.headers }.trim(),
                    model = safeString { provider.model }.trim(),
                    defaultParamsJson = safeString { provider.defaultParamsJson }.trim(),
                    stylePrompt = safeString { provider.stylePrompt }.trim(),
                    jsLib = safeString { provider.jsLib },
                    loginUrl = safeString { provider.loginUrl }.trim(),
                    loginUi = safeString { provider.loginUi },
                    enabledCookieJar = safeBoolean(false) { provider.enabledCookieJar },
                    script = safeString { provider.script },
                    timeoutMillisecond = safeLong(120_000L) { provider.timeoutMillisecond }
                        .takeIf { it > 0L } ?: 120_000L,
                    order = safeInt(0) { provider.order },
                    enabled = safeBoolean(true) { provider.enabled }
                )
            }
        }
            .distinctBy { it.id }
            .sortedBy { it.order }
            .mapIndexed { index, provider -> provider.copy(order = index) }
    }

    private fun normalizeAiApiMode(value: String): String {
        return if (value.trim() == AI_API_MODE_RESPONSES) {
            AI_API_MODE_RESPONSES
        } else {
            AI_API_MODE_CHAT_COMPLETIONS
        }
    }

    private inline fun safeString(block: () -> String?): String {
        return runCatching { block() }.getOrNull().orEmpty()
    }

    private inline fun safeBoolean(default: Boolean, block: () -> Boolean): Boolean {
        return runCatching { block() }.getOrDefault(default)
    }

    private inline fun safeInt(default: Int, block: () -> Int): Int {
        return runCatching { block() }.getOrDefault(default)
    }

    private inline fun safeLong(default: Long, block: () -> Long): Long {
        return runCatching { block() }.getOrDefault(default)
    }

    private inline fun safeStringList(block: () -> List<String>): List<String> {
        return runCatching { block() }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(40)
    }

    private fun persistAiSkills(skills: List<AiSkillConfig>) {
        if (skills.isEmpty()) {
            appCtx.removePref(PreferKey.aiSkillList)
        } else {
            appCtx.putPrefString(PreferKey.aiSkillList, GSON.toJson(skills))
        }
    }

    private fun persistAiWorldBooks(worldBooks: List<AiWorldBookConfig>) {
        if (worldBooks.isEmpty()) {
            appCtx.removePref(PreferKey.aiWorldBookList)
        } else {
            appCtx.putPrefString(PreferKey.aiWorldBookList, GSON.toJson(worldBooks))
        }
    }

    private fun migrateLegacyAiSkillIfNeeded() {
        if (!appCtx.getPrefString(PreferKey.aiSkillList).isNullOrBlank()) {
            return
        }
        val prompt = appCtx.getPrefString(PreferKey.aiSkillPrompt).orEmpty().trim()
        if (prompt.isBlank()) {
            return
        }
        persistAiSkills(
            listOf(
                AiSkillConfig(
                    name = "Legado Skill",
                    description = appCtx.getString(R.string.svc_migrated_from_legacy_single_text),
                    content = prompt
                )
            )
        )
        appCtx.removePref(PreferKey.aiSkillPrompt)
    }

    private fun syncAiState(
        providers: List<AiProviderConfig>,
        models: List<AiModelConfig>
    ) {
        val sceneModelKeys = arrayOf(
            PreferKey.aiAskModelId,
            PreferKey.aiSummaryModelId,
            PreferKey.aiReadAloudRoleModelId,
            PreferKey.aiReadAloudRoleBackupModelId,
            PreferKey.aiReadAloudAudioModelId,
            PreferKey.aiReadAloudAudioBackupModelId
        )
        val validModelIds = models.mapTo(hashSetOf()) { it.id }
        val providerId = providers.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCurrentProviderId)
        }?.id ?: providers.firstOrNull()?.id

        if (providerId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCurrentProviderId)
            appCtx.removePref(PreferKey.aiCurrentModelId)
            sceneModelKeys.forEach { appCtx.removePref(it) }
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, false)
            appCtx.putPrefBoolean(PreferKey.aiReadAloudRoleEnabled, false)
            return
        }

        if (providerId != appCtx.getPrefString(PreferKey.aiCurrentProviderId)) {
            appCtx.putPrefString(PreferKey.aiCurrentProviderId, providerId)
        }

        val providerModels = models.filter { it.providerId == providerId }
        val currentModelId = providerModels.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCurrentModelId)
        }?.id ?: providerModels.firstOrNull()?.id

        if (currentModelId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCurrentModelId)
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, false)
            appCtx.putPrefBoolean(PreferKey.aiReadAloudRoleEnabled, false)
        } else if (currentModelId != appCtx.getPrefString(PreferKey.aiCurrentModelId)) {
            appCtx.putPrefString(PreferKey.aiCurrentModelId, currentModelId)
        }

        sceneModelKeys.forEach { key ->
            val stored = appCtx.getPrefString(key)
            if (!stored.isNullOrBlank() && stored !in validModelIds) {
                appCtx.removePref(key)
            }
        }
    }

    private fun migrateLegacyAiConfigIfNeeded() {
        if (!appCtx.getPrefString(PreferKey.aiProviderList).isNullOrBlank()
            || !appCtx.getPrefString(PreferKey.aiModelConfigList).isNullOrBlank()
        ) {
            return
        }
        val legacyBaseUrl = appCtx.getPrefString(PreferKey.aiBaseUrl, "")?.trim().orEmpty()
        val legacyApiKey = appCtx.getPrefString(PreferKey.aiApiKey, "")?.trim().orEmpty()
        val legacyModels = GSON.fromJsonArray<String>(appCtx.getPrefString(PreferKey.aiModelList))
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (legacyBaseUrl.isBlank() && legacyApiKey.isBlank() && legacyModels.isEmpty()) {
            return
        }
        val provider = AiProviderConfig(
            name = resolveAiProviderName(legacyBaseUrl),
            baseUrl = legacyBaseUrl,
            apiKey = legacyApiKey
        )
        val models = legacyModels.map { modelId ->
            AiModelConfig(providerId = provider.id, modelId = modelId)
        }
        persistAiProviders(listOf(provider))
        if (models.isNotEmpty()) {
            persistAiModels(models)
        }
        appCtx.putPrefString(PreferKey.aiCurrentProviderId, provider.id)
        val legacyCurrentModel = appCtx.getPrefString(PreferKey.aiCurrentModel)?.trim().orEmpty()
        val currentModel = models.firstOrNull { it.modelId == legacyCurrentModel } ?: models.firstOrNull()
        if (currentModel == null) {
            appCtx.removePref(PreferKey.aiCurrentModelId)
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, false)
        } else {
            appCtx.putPrefString(PreferKey.aiCurrentModelId, currentModel.id)
        }
    }

    private fun resolveAiProviderName(baseUrl: String): String {
        if (baseUrl.isBlank()) return "Provider 1"
        return runCatching {
            URI(baseUrl).host?.removePrefix("www.")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Provider 1"
    }
}
