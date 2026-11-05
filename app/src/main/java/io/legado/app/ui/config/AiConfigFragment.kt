package io.legado.app.ui.config

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.ai.AiToolRegistry
import io.legado.app.help.ai.AiUsageTracker
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeMultiChoiceDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialogWithChecks
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiImageGalleryActivity
import io.legado.app.ui.main.ai.AiSkillConfig
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import splitties.init.appCtx

class AiConfigFragment : ComposeSettingFragment() {

    private val defaultSkillUrls = listOf(
        "https://raw.githubusercontent.com/DandanLLab/legadoSkill/main/.trae/skills/legado-book-source-tamer/SKILL.md",
        "https://raw.githubusercontent.com/DandanLLab/legadoSkill/main/skills/SKILLV0.7.md",
        "https://raw.githubusercontent.com/DandanLLab/legadoSkill/main/SKILL.md"
    )

    private companion object {
        const val KEY_IMPORT_DEFAULT_SKILL = "aiImportDefaultSkill"
        const val KEY_MANAGE_NATIVE_TOOLS = "aiManageNativeTools"
        const val KEY_AI_WORKSPACE = "aiWorkspace"
        const val KEY_CONTEXT_COMPRESSION = "aiContextCompression"
        const val KEY_WORLD_BOOK_MANAGE = "aiWorldBookManage"
        const val KEY_CONTENT_CONSENT = "aiContentConsent"
        const val KEY_USAGE_BUDGET = "aiUsageBudget"
        const val KEY_DEFAULT_MODEL_SETTINGS = "aiDefaultModelSettings"
        const val KEY_IMAGE_GALLERY = "aiImageGallery"
        const val KEY_IMAGE_PROVIDER_MANAGE = "aiImageProviderManage"
        const val KEY_MANAGE_PROVIDERS = "aiManageProviders"
        const val KEY_ADD_MCP_SERVER = "aiAddMcpServer"
        const val KEY_MANAGE_MCP_SERVERS = "aiManageMcpServers"
    }

    override val titleRes: Int = R.string.ai_setting

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEvent<Boolean>(EventBus.AI_CONFIG_CHANGED) {
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun buildPageSpec(): SettingPageSpec {
        val canEnable = AppConfig.aiCurrentModelConfig != null
        val currentProvider = AppConfig.aiCurrentProvider
        val mcpServers = AppConfig.aiMcpServerList
        val enabledMcpCount = mcpServers.count { it.enabled }
        val imageProviders = AppConfig.aiImageProviderList
        val skills = AppConfig.aiSkillList
        val enabledSkillCount = skills.count { it.enabled }
        val worldBooks = AppConfig.aiWorldBookList
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.ai_assistant),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.aiAssistantEnabled,
                            title = str(R.string.ai_enable),
                            summary = str(
                                if (canEnable) {
                                    R.string.ai_enable_summary
                                } else {
                                    R.string.ai_enable_summary_disabled
                                }
                            ),
                            checked = AppConfig.aiAssistantEnabled,
                            enabled = canEnable,
                            onCheckedChange = { AppConfig.aiAssistantEnabled = it }
                        ),
                        SettingActionSpec(
                            key = KEY_IMPORT_DEFAULT_SKILL,
                            title = str(R.string.ai_import_default_skill),
                            summary = str(R.string.ai_import_default_skill_summary),
                            onClick = ::importDefaultSkill
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiSkillPrompt,
                            title = str(R.string.ai_skill_prompt),
                            summary = if (skills.isEmpty()) {
                                str(R.string.ai_skill_prompt_summary_empty)
                            } else {
                                str(
                                    R.string.ai_skill_prompt_summary,
                                    enabledSkillCount,
                                    skills.size
                                )
                            },
                            onClick = ::showManageSkillsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_MANAGE_NATIVE_TOOLS,
                            title = str(R.string.ai_manage_native_tools),
                            summary = "${str(R.string.ai_manage_native_tools_summary)} · ${AiToolRegistry.effectiveEnabledToolNames().size}",
                            onClick = ::showManageNativeToolsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_AI_WORKSPACE,
                            navigates = true,
                            title = appCtx.getString(R.string.ai_ui_ai_workspace),
                            summary = appCtx.getString(R.string.ai_ui_view_files_created_edited_backed),
                            onClick = ::openAiWorkspace
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiAgentMaxToolRounds,
                            title = appCtx.getString(R.string.ai_ui_max_ai_tool_rounds),
                            summary = appCtx.getString(R.string.ai_ui_rounds, AppConfig.aiAgentMaxToolRounds),
                            onClick = ::showAgentMaxToolRoundsDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiReadToolMode,
                            title = appCtx.getString(R.string.ai_ui_tool_scope_ask_ai_content),
                            summary = readToolModeLabel(),
                            onClick = ::showReadToolModeDialog
                        ),
                        switch(
                            key = PreferKey.aiEnterToSend,
                            title = str(R.string.ai_enter_to_send),
                            summary = str(R.string.ai_enter_to_send_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.aiThinkingToolbarEnabled,
                            title = appCtx.getString(R.string.ai_ui_show_thinking_toolbar),
                            summary = appCtx.getString(R.string.ai_ui_when_off_chat_page_hides),
                            defaultValue = true
                        ),
                        SettingActionSpec(
                            key = KEY_CONTEXT_COMPRESSION,
                            title = str(R.string.ai_context_compression),
                            summary = if (AppConfig.aiContextCompressionEnabled) {
                                "${AppConfig.aiContextWindowTokens} / ${AppConfig.aiThinkingContextTokens}"
                            } else {
                                str(R.string.ai_context_compression_summary_default)
                            },
                            onClick = ::showContextCompressionDialog
                        ),
                        // 「世界书 / 默认模型 / 图库 / 生图供应商」属于模型与生图，
                        // 「正文隐私授权 / 用量预算」属于治理，三者不再挤在助手功能里
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.ai_section_privacy_usage),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_CONTENT_CONSENT,
                            title = appCtx.getString(R.string.ai_ui_content_privacy_consent),
                            summary = contentConsentSummary(),
                            onClick = ::showContentConsentDialog
                        ),
                        SettingActionSpec(
                            key = KEY_USAGE_BUDGET,
                            title = appCtx.getString(R.string.ai_ui_usage_budget),
                            summary = aiUsageBudgetSummary(),
                            onClick = ::showAiBudgetDialog
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.ai_section_model_image),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_WORLD_BOOK_MANAGE,
                            navigates = true,
                            title = appCtx.getString(R.string.ai_ui_world_book_management),
                            summary = if (worldBooks.isEmpty()) {
                                appCtx.getString(R.string.ai_ui_no_world_book_configured)
                            } else {
                                "${worldBooks.count { it.enabled }}/${worldBooks.size} 启用 · ${worldBooks.sumOf { it.entries.size }} 条目"
                            },
                            onClick = {
                                startActivity(Intent(ctx, AiWorldBookManageActivity::class.java))
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_DEFAULT_MODEL_SETTINGS,
                            title = appCtx.getString(R.string.ai_ui_default_model),
                            summary = "问AI ${modelLabel(AppConfig.aiAskModelConfig)} / 总结 ${modelLabel(AppConfig.aiSummaryModelConfig)} / 多角色 ${modelLabel(AppConfig.aiReadAloudRoleModelConfig)} / 生图 ${imageProviderLabel()}",
                            onClick = ::showDefaultModelSettingsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_IMAGE_GALLERY,
                            navigates = true,
                            title = str(R.string.ai_image_gallery),
                            summary = str(R.string.ai_image_gallery_summary),
                            onClick = {
                                startActivity(Intent(ctx, AiImageGalleryActivity::class.java))
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_IMAGE_PROVIDER_MANAGE,
                            navigates = true,
                            title = str(R.string.ai_image_provider_manage),
                            summary = if (imageProviders.isEmpty()) {
                                str(R.string.ai_image_provider_summary_empty)
                            } else {
                                str(
                                    R.string.ai_image_provider_summary,
                                    imageProviders.count { it.enabled },
                                    imageProviders.size
                                )
                            },
                            onClick = {
                                startActivity(Intent(ctx, AiImageProviderManageActivity::class.java))
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.ai_provider),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_MANAGE_PROVIDERS,
                            navigates = true,
                            title = str(R.string.ai_manage_providers),
                            summary = if (AppConfig.aiProviderList.isEmpty()) {
                                str(R.string.ai_no_providers)
                            } else {
                                buildString {
                                    append(currentProvider?.name ?: str(R.string.ai_current_provider_summary_empty))
                                    append(" · ")
                                    append(str(R.string.ai_manage_providers_summary, AppConfig.aiProviderList.size))
                                }
                            },
                            onClick = {
                                startActivity(Intent(ctx, AiProviderManageActivity::class.java))
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.ai_mcp),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_ADD_MCP_SERVER,
                            title = str(R.string.ai_add_mcp_server),
                            summary = str(R.string.ai_add_mcp_server_summary),
                            onClick = { showEditMcpServerDialog() }
                        ),
                        SettingActionSpec(
                            key = KEY_MANAGE_MCP_SERVERS,
                            title = str(R.string.ai_manage_mcp_servers),
                            summary = if (mcpServers.isEmpty()) {
                                str(R.string.ai_no_mcp_servers)
                            } else {
                                str(
                                    R.string.ai_manage_mcp_servers_summary,
                                    enabledMcpCount,
                                    mcpServers.size
                                )
                            },
                            onClick = ::showManageMcpServersDialog
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.ai_web_tools),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.aiTavilyEnabled,
                            title = str(R.string.ai_tavily_enable),
                            summary = str(
                                if (AppConfig.aiTavilyApiKey.isBlank()) {
                                    R.string.ai_tavily_enable_summary_missing
                                } else {
                                    R.string.ai_tavily_enable_summary
                                }
                            ),
                            checked = booleanSetting(PreferKey.aiTavilyEnabled, false),
                            onCheckedChange = { updateBooleanSetting(PreferKey.aiTavilyEnabled, it) }
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyApiKey,
                            title = str(R.string.ai_tavily_api_key),
                            summary = if (AppConfig.aiTavilyApiKey.isBlank()) {
                                str(R.string.ai_tavily_api_key_summary)
                            } else {
                                str(R.string.ai_tavily_api_key_summary_ready)
                            },
                            onClick = ::showTavilyApiKeyDialog
                        )
                    )
                ),
                // 接口地址、话题、检索深度、结果条数是调优参数，配好 key 之后基本不会再动，
                // 和开关/key 放在同一屏会淹没主路径，因此默认折叠
                SettingSectionSpec(
                    title = "${str(R.string.ai_web_tools)} · ${str(R.string.advanced_setting)}",
                    collapsible = true,
                    collapsedByDefault = true,
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.aiTavilyBaseUrl,
                            title = str(R.string.ai_tavily_base_url),
                            summary = AppConfig.aiTavilyBaseUrl,
                            onClick = ::showTavilyBaseUrlDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyTopic,
                            title = str(R.string.ai_tavily_topic),
                            summary = tavilyTopicLabel(),
                            onClick = ::showTavilyTopicDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilySearchDepth,
                            title = str(R.string.ai_tavily_search_depth),
                            summary = tavilySearchDepthLabel(),
                            onClick = ::showTavilySearchDepthDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyMaxResults,
                            title = str(R.string.ai_tavily_max_results),
                            summary = AppConfig.aiTavilyMaxResults.toString(),
                            onClick = ::showTavilyMaxResultsDialog
                        )
                    )
                )
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.aiAssistantEnabled -> refreshUi(notifyMain = true)
            PreferKey.aiAskModelId,
            PreferKey.aiSummaryModelId,
            PreferKey.aiReadAloudRoleModelId,
            PreferKey.aiCurrentImageProviderId -> refreshUi()
            PreferKey.aiReadToolMode -> refreshUi()
        }
    }

    private fun openAiWorkspace() {
        val root = File(ctx.filesDir, "ai_workspace").apply { mkdirs() }
        startActivity(
            Intent(ctx, FileManageActivity::class.java)
                .putExtra(FileManageActivity.EXTRA_ROOT_PATH, root.absolutePath)
                .putExtra(FileManageActivity.EXTRA_TITLE, appCtx.getString(R.string.ai_ui_ai_workspace))
        )
    }

    private fun showAgentMaxToolRoundsDialog() {
        showComposeNumberPickerDialog(
            title = appCtx.getString(R.string.ai_ui_max_ai_tool_rounds),
            value = AppConfig.aiAgentMaxToolRounds,
            minValue = 4,
            maxValue = 64,
            onValue = { value ->
                AppConfig.aiAgentMaxToolRounds = value
                refreshUi()
            }
        )
    }

    private fun switch(
        key: String,
        title: String,
        summary: String,
        defaultValue: Boolean
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    private fun readToolModeLabel(): String {
        return when (AppConfig.aiReadToolMode) {
            AppConfig.AI_READ_TOOL_MODE_ALL -> appCtx.getString(R.string.ai_ui_all_tools)
            AppConfig.AI_READ_TOOL_MODE_SAFE -> appCtx.getString(R.string.ai_ui_reading_safety_tools)
            else -> appCtx.getString(R.string.ai_ui_use_enabled_tools)
        }
    }

    private fun tavilyTopicLabel(): String {
        return str(
            when (AppConfig.aiTavilyTopic) {
                "news" -> R.string.ai_tavily_topic_news
                "finance" -> R.string.ai_tavily_topic_finance
                else -> R.string.ai_tavily_topic_general
            }
        )
    }

    private fun tavilySearchDepthLabel(): String {
        return str(
            when (AppConfig.aiTavilySearchDepth) {
                "advanced" -> R.string.ai_tavily_search_depth_advanced
                "ultra-fast" -> R.string.ai_tavily_search_depth_ultra_fast
                else -> R.string.ai_tavily_search_depth_basic
            }
        )
    }

    private fun showReadToolModeDialog() {
        val values = listOf(
            AppConfig.AI_READ_TOOL_MODE_ENABLED,
            AppConfig.AI_READ_TOOL_MODE_SAFE,
            AppConfig.AI_READ_TOOL_MODE_ALL
        )
        val labels = listOf(
            appCtx.getString(R.string.ai_ui_use_enabled_tools),
            appCtx.getString(R.string.ai_ui_reading_safety_tools),
            appCtx.getString(R.string.ai_ui_all_tools)
        )
        showComposeActionListDialog(
            title = appCtx.getString(R.string.ai_ui_tool_scope_ask_ai_content),
            labels = labels.mapIndexed { index, label ->
                if (values[index] == AppConfig.aiReadToolMode) "$label ✓" else label
            }
        ) { index ->
            AppConfig.aiReadToolMode = values[index]
            refreshUi()
        }
    }

    private fun showDefaultModelSettingsDialog() {
        val items = listOf(
            appCtx.getString(R.string.ai_ui_ask_ai_content, modelLabel(AppConfig.aiAskModelConfig)),
            appCtx.getString(R.string.ai_ui_article_summary, modelLabel(AppConfig.aiSummaryModelConfig)),
            appCtx.getString(R.string.ai_ui_multi_role, modelLabel(AppConfig.aiReadAloudRoleModelConfig)),
            appCtx.getString(R.string.ai_ui_image_provider, imageProviderLabel())
        )
        showComposeActionListDialog(
            title = appCtx.getString(R.string.ai_ui_default_model),
            labels = items
        ) { index ->
            when (index) {
                0 -> selectDefaultModel(appCtx.getString(R.string.ai_ui_ask_ai_content_model), AppConfig.aiAskModelId) {
                    AppConfig.aiAskModelId = it
                }
                1 -> selectDefaultModel(appCtx.getString(R.string.ai_ui_article_summary_model), AppConfig.aiSummaryModelId) {
                    AppConfig.aiSummaryModelId = it
                }
                2 -> selectDefaultModel(appCtx.getString(R.string.ai_ui_multi_role_model), AppConfig.aiReadAloudRoleModelId) {
                    AppConfig.aiReadAloudRoleModelId = it
                }
                3 -> selectDefaultImageProvider()
            }
        }
    }

    private fun selectDefaultModel(
        title: String,
        currentId: String?,
        onSelect: (String) -> Unit
    ) {
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_no_models)
            return
        }
        val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
        showComposeActionListDialog(
            title = title,
            labels = models.map { model ->
                val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                    ?.let { "${model.modelId} - $it" }
                    ?: model.modelId
                if (model.id == currentId) "$label ✓" else label
            }
        ) { index ->
            onSelect(models[index].id)
            refreshUi()
        }
    }

    private fun selectDefaultImageProvider() {
        val providers = AppConfig.aiEnabledImageProviders
        if (providers.isEmpty()) {
            toastOnUi(R.string.ai_image_provider_summary_empty)
            return
        }
        val currentId = AppConfig.aiCurrentImageProvider?.id
        showComposeActionListDialog(
            title = appCtx.getString(R.string.ai_ui_image_generation_provider),
            labels = providers.map { provider ->
                val label = provider.displayName()
                if (provider.id == currentId) "$label ✓" else label
            }
        ) { index ->
            AppConfig.aiCurrentImageProviderId = providers[index].id
            refreshUi()
        }
    }

    private fun modelLabel(model: AiModelConfig?): String {
        model ?: return appCtx.getString(R.string.ra_not_configured)
        val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
            ?.name
            ?.takeIf { it.isNotBlank() }
        return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
    }

    private fun imageProviderLabel(): String {
        return AppConfig.aiCurrentImageProvider?.displayName() ?: appCtx.getString(R.string.ra_not_configured)
    }

    private fun showEditMcpServerDialog(server: AiMcpServerConfig? = null) {
        showComposeTextFormDialogWithChecks(
            title = str(
                if (server == null) R.string.ai_add_mcp_server else R.string.ai_edit_mcp_server
            ),
            labels = listOf(
                str(R.string.ai_mcp_server_name),
                str(R.string.ai_mcp_server_endpoint),
                str(R.string.ai_api_key)
            ),
            initialValues = listOf(
                server?.name.orEmpty(),
                server?.endpoint.orEmpty(),
                server?.apiKey.orEmpty()
            ),
            passwordFields = setOf(2),
            checkboxLabels = listOf(str(R.string.ai_mcp_server_enabled)),
            checkedIndices = if (server?.enabled != false) setOf(0) else emptySet(),
            validateInput = { values ->
                val name = values.getOrNull(0).orEmpty().trim()
                val endpoint = values.getOrNull(1).orEmpty().trim()
                when {
                    name.isEmpty() -> {
                        toastOnUi(R.string.ai_mcp_server_name_required)
                        false
                    }
                    endpoint.isEmpty() -> {
                        toastOnUi(R.string.ai_mcp_server_endpoint_required)
                        false
                    }
                    else -> true
                }
            },
            onPositive = { values, checked ->
                val name = values[0].trim()
                val endpoint = values[1].trim()
                val apiKey = values[2].trim()
                val enabled = checked.getOrElse(0) { true }
                val servers = AppConfig.aiMcpServerList.toMutableList()
                val updated = server?.copy(
                    name = name,
                    endpoint = endpoint,
                    apiKey = apiKey,
                    enabled = enabled
                ) ?: AiMcpServerConfig(
                    name = name,
                    endpoint = endpoint,
                    apiKey = apiKey,
                    enabled = enabled
                )
                val targetIndex = servers.indexOfFirst { it.id == updated.id }
                if (targetIndex >= 0) {
                    servers[targetIndex] = updated
                } else {
                    servers.add(updated)
                }
                AppConfig.aiMcpServerList = servers
                refreshUi()
                toastOnUi(R.string.ai_mcp_server_saved)
            }
        )
    }

    private fun showManageMcpServersDialog() {
        val servers = AppConfig.aiMcpServerList
        if (servers.isEmpty()) {
            toastOnUi(R.string.ai_no_mcp_servers)
            return
        }
        showComposeActionListDialog(
            title = str(R.string.ai_manage_mcp_servers),
            labels = servers.map { server ->
                buildString {
                    append(server.name)
                    if (!server.enabled) append(" (off)")
                }
            }
        ) { index ->
            val server = servers[index]
            showComposeActionListDialog(
                title = server.name,
                labels = listOf(
                    str(
                        if (server.enabled) {
                            R.string.ai_disable_mcp_server
                        } else {
                            R.string.ai_enable_mcp_server
                        }
                    ),
                    str(R.string.ai_edit_mcp_server),
                    str(R.string.ai_remove_mcp_server)
                )
            ) { action ->
                when (action) {
                    0 -> {
                        AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.map {
                            if (it.id == server.id) it.copy(enabled = !it.enabled) else it
                        }
                        refreshUi()
                    }

                    1 -> showEditMcpServerDialog(server)
                    2 -> confirmRemoveMcpServer(server)
                }
            }
        }
    }

    private fun confirmRemoveMcpServer(server: AiMcpServerConfig) {
        showComposeConfirmDialog(
            title = server.name,
            message = str(R.string.ai_remove_mcp_server_confirm),
            onPositive = {
                AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.filterNot { it.id == server.id }
                refreshUi()
                toastOnUi(R.string.ai_mcp_server_removed)
            }
        )
    }


    private fun contentConsentSummary(): String = when (AppConfig.aiExternalContentConsent) {
        AppConfig.AI_CONTENT_CONSENT_ALLOW -> appCtx.getString(R.string.ai_ui_always_allow_sending_content)
        AppConfig.AI_CONTENT_CONSENT_DENY -> appCtx.getString(R.string.ai_ui_never_send_content)
        else -> appCtx.getString(R.string.ai_ui_ask_every_time)
    }

    private fun aiUsageBudgetSummary(): String {
        val usage = AiUsageTracker.todayUsage()
        val budget = AppConfig.aiDailyTokenBudget
        val budgetText = if (budget > 0L) formatTokenCount(budget) else appCtx.getString(R.string.ai_ui_unlimited)
        return appCtx.getString(R.string.ai_ui_today_tokens_requests_daily_limit, formatTokenCount(usage.totalTokens), usage.requests, budgetText)
    }

    private fun formatTokenCount(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    private fun showAiBudgetDialog() {
        val values = listOf(0L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L, 1_000_000L)
        showComposeActionListDialog(
            title = appCtx.getString(R.string.ai_ui_daily_token_budget),
            labels = values.map { if (it <= 0L) appCtx.getString(R.string.ai_ui_unlimited) else "${formatTokenCount(it)} tokens" }
        ) { index ->
            AppConfig.aiDailyTokenBudget = values[index]
            refreshUi()
        }
    }

    private fun showContentConsentDialog() {
        showComposeActionListDialog(
            title = appCtx.getString(R.string.ai_ui_content_privacy_consent),
            labels = listOf(
                appCtx.getString(R.string.ai_ui_ask_every_time),
                appCtx.getString(R.string.ai_ui_always_allow_sending_content),
                appCtx.getString(R.string.ai_ui_never_send_content)
            )
        ) { index ->
            AppConfig.aiExternalContentConsent = when (index) {
                1 -> AppConfig.AI_CONTENT_CONSENT_ALLOW
                2 -> AppConfig.AI_CONTENT_CONSENT_DENY
                else -> AppConfig.AI_CONTENT_CONSENT_ASK
            }
            refreshUi()
        }
    }

    private fun showContextCompressionDialog() {
        val enabledText = if (AppConfig.aiContextCompressionEnabled) appCtx.getString(R.string.ai_ui_disable_context_compression) else appCtx.getString(R.string.ai_ui_enable_context_compression)
        showComposeActionListDialog(
            title = str(R.string.ai_context_compression),
            labels = listOf(
                enabledText,
                appCtx.getString(R.string.ai_ui_context_length, AppConfig.aiContextWindowTokens),
                appCtx.getString(R.string.ai_ui_thinking_context, AppConfig.aiThinkingContextTokens)
            )
        ) { index ->
            when (index) {
                0 -> {
                    AppConfig.aiContextCompressionEnabled = !AppConfig.aiContextCompressionEnabled
                    refreshUi()
                }
                1 -> showTokenSelector(true)
                2 -> showTokenSelector(false)
            }
        }
    }

    private fun showTokenSelector(contextWindow: Boolean) {
        val values = if (contextWindow) {
            listOf(32_000, 64_000, 128_000, 258_000, 512_000, 1_000_000)
        } else {
            listOf(0, 32_000, 64_000, 128_000, 258_000)
        }
        showComposeActionListDialog(
            title = if (contextWindow) str(R.string.ai_context_tokens) else str(R.string.ai_thinking_context_tokens),
            labels = values.map { it.toString() }
        ) { index ->
            if (contextWindow) AppConfig.aiContextWindowTokens = values[index]
            else AppConfig.aiThinkingContextTokens = values[index]
            refreshUi()
        }
    }

    private fun showManageNativeToolsDialog() {
        lifecycleScope.launch {
            val tools = runCatching { AiToolRegistry.resolveAllToolNamesForManage() }
                .getOrDefault(emptyList())
            if (tools.isEmpty()) {
                toastOnUi(R.string.not_available)
                return@launch
            }
            val groupedTools = tools.map { AiToolRegistry.metaOfTool(it) }
                .groupBy { it.group }
                .toSortedMap(compareBy { groupOrder(it) })
            val toolNames = mutableListOf<String>()
            val toolLabels = mutableListOf<String>()
            groupedTools.forEach { (group, groupTools) ->
                toolNames.add("__group_$group")
                toolLabels.add("--- $group ---")
                groupTools.sortedBy { it.label }.forEach { tool ->
                    toolNames.add(tool.name)
                    toolLabels.add(tool.label)
                }
            }
            val enabledToolNames = AiToolRegistry.effectiveEnabledToolNames()
            val checkedIndices = toolNames.indices
                .filter { i -> toolNames[i] in enabledToolNames }
                .toSet()
            showComposeMultiChoiceDialog(
                title = str(R.string.ai_manage_native_tools),
                labels = toolLabels,
                checkedIndices = checkedIndices,
                positiveText = str(android.R.string.ok),
                negativeText = str(R.string.cancel),
                onPositive = { checked ->
                    val newEnabled = mutableSetOf<String>()
                    checked.forEachIndexed { index, isChecked ->
                        if (isChecked && !toolNames[index].startsWith("__group_")) {
                            newEnabled.add(toolNames[index])
                        }
                    }
                    AppConfig.aiEnabledToolNames = newEnabled
                    refreshUi()
                }
            )
        }
    }

    private fun groupOrder(group: String): Int {
        return when (group) {
            appCtx.getString(R.string.bookshelf) -> 0
            "AI workspace" -> 1
            appCtx.getString(R.string.book_source) -> 2
            appCtx.getString(R.string.app_launcher_name) -> 3
            appCtx.getString(R.string.ai_tool_reading_network) -> 4
            appCtx.getString(R.string.ai_tool_web_search) -> 5
            appCtx.getString(R.string.ai_tool_ai_image) -> 6
            appCtx.getString(R.string.bchar_character_profile) -> 7
            appCtx.getString(R.string.setting) -> 8
            appCtx.getString(R.string.ai_tool_mcp_tools) -> 9
            else -> 9
        }
    }

    private fun showTavilyApiKeyDialog() {
        showComposeTextInputDialog(
            title = str(R.string.ai_tavily_api_key),
            hint = str(R.string.ai_tavily_api_key_hint),
            initialValue = AppConfig.aiTavilyApiKey,
            onPositive = { text ->
                AppConfig.aiTavilyApiKey = text
                refreshUi()
            }
        )
    }

    private fun showTavilyBaseUrlDialog() {
        showComposeTextInputDialog(
            title = str(R.string.ai_tavily_base_url),
            hint = "https://api.tavily.com/search",
            initialValue = AppConfig.aiTavilyBaseUrl,
            neutralText = str(R.string.restore_default),
            onPositive = { text ->
                AppConfig.aiTavilyBaseUrl = text
                refreshUi()
            },
            onNeutral = {
                AppConfig.aiTavilyBaseUrl = "https://api.tavily.com/search"
                refreshUi()
            }
        )
    }

    private fun showTavilyTopicDialog() {
        val values = listOf("general", "news", "finance")
        val labels = listOf(
            str(R.string.ai_tavily_topic_general),
            str(R.string.ai_tavily_topic_news),
            str(R.string.ai_tavily_topic_finance)
        )
        showComposeActionListDialog(
            title = str(R.string.ai_tavily_topic),
            labels = labels
        ) { index ->
            AppConfig.aiTavilyTopic = values[index]
            refreshUi()
        }
    }

    private fun showTavilySearchDepthDialog() {
        val values = listOf("basic", "advanced", "ultra-fast")
        val labels = listOf(
            str(R.string.ai_tavily_search_depth_basic),
            str(R.string.ai_tavily_search_depth_advanced),
            str(R.string.ai_tavily_search_depth_ultra_fast)
        )
        showComposeActionListDialog(
            title = str(R.string.ai_tavily_search_depth),
            labels = labels
        ) { index ->
            AppConfig.aiTavilySearchDepth = values[index]
            refreshUi()
        }
    }

    private fun showTavilyMaxResultsDialog() {
        showComposeNumberPickerDialog(
            title = str(R.string.ai_tavily_max_results),
            value = AppConfig.aiTavilyMaxResults,
            minValue = 1,
            maxValue = 10,
            onValue = { value ->
                AppConfig.aiTavilyMaxResults = value
                refreshUi()
            }
        )
    }

    private fun importDefaultSkill() {
        toastOnUi(R.string.ai_skill_importing)
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching {
                    var lastError = ""
                    defaultSkillUrls.forEach { skillUrl ->
                        okHttpClient.newCallResponse {
                            url(skillUrl)
                        }.use { response ->
                            if (response.isSuccessful) {
                                return@runCatching skillUrl to response.body.string()
                            }
                            lastError = "${response.code} ${response.message}"
                        }
                    }
                    error(lastError.ifBlank { "No available SKILL.md" })
                }
            }
            result.onSuccess { (skillUrl, skill) ->
                if (skill.isBlank()) {
                    toastOnUi(R.string.ai_skill_import_empty)
                    return@onSuccess
                }
                val skillConfig = parseSkillConfig(skill, skillUrl)
                AppConfig.aiSkillList = AppConfig.aiSkillList
                    .filterNot { it.sourceUrl == skillConfig.sourceUrl || it.name == skillConfig.name }
                    .plus(skillConfig)
                refreshUi()
                toastOnUi(R.string.ai_skill_imported)
            }.onFailure {
                toastOnUi(str(R.string.ai_skill_import_failed, it.localizedMessage ?: "Error"))
            }
        }
    }

    private fun showManageSkillsDialog() {
        val skills = AppConfig.aiSkillList
        val actions = mutableListOf(str(R.string.ai_add_skill_manual))
        actions += skills.map { skill ->
            buildString {
                append(skill.name)
                append(" · ")
                append(
                    str(
                        if (skill.enabled) R.string.enabled else R.string.disabled
                    )
                )
            }
        }
        showComposeActionListDialog(
            title = str(R.string.ai_manage_skills),
            labels = actions
        ) { index ->
            if (index == 0) {
                showSkillEditDialog()
            } else {
                showSkillActionDialog(skills[index - 1])
            }
        }
    }

    private fun showSkillActionDialog(skill: AiSkillConfig) {
        showComposeActionListDialog(
            title = skill.name,
            labels = listOf(
                str(if (skill.enabled) R.string.disable else R.string.enable),
                str(R.string.edit),
                str(R.string.delete)
            )
        ) { action ->
            when (action) {
                0 -> {
                    AppConfig.aiSkillList = AppConfig.aiSkillList.map {
                        if (it.id == skill.id) it.copy(enabled = !it.enabled) else it
                    }
                    refreshUi()
                }

                1 -> showSkillEditDialog(skill)
                2 -> confirmRemoveSkill(skill)
            }
        }
    }

    private fun showSkillEditDialog(skill: AiSkillConfig? = null) {
        showComposeTextInputDialog(
            title = str(R.string.ai_skill_prompt),
            hint = str(R.string.ai_skill_prompt_hint),
            initialValue = skill?.content.orEmpty(),
            minLines = 8,
            maxLines = 16,
            validateInput = { text ->
                if (text.isBlank()) {
                    toastOnUi(R.string.ai_skill_import_empty)
                    false
                } else {
                    true
                }
            },
            onPositive = { content ->
                val updated = parseSkillConfig(content, skill?.sourceUrl.orEmpty(), skill)
                val skills = AppConfig.aiSkillList.toMutableList()
                val index = skills.indexOfFirst { it.id == updated.id }
                if (index >= 0) {
                    skills[index] = updated
                } else {
                    skills.add(updated)
                }
                AppConfig.aiSkillList = skills
                refreshUi()
            }
        )
    }

    private fun confirmRemoveSkill(skill: AiSkillConfig) {
        showComposeConfirmDialog(
            title = skill.name,
            message = str(R.string.ai_remove_skill_confirm),
            onPositive = {
                AppConfig.aiSkillList = AppConfig.aiSkillList.filterNot { it.id == skill.id }
                refreshUi()
            }
        )
    }

    private fun parseSkillConfig(
        content: String,
        sourceUrl: String = "",
        oldSkill: AiSkillConfig? = null
    ): AiSkillConfig {
        val name = Regex("""(?m)^\s*name:\s*["']?([^"'\n]+)["']?\s*$""")
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val description = Regex("""(?m)^\s*description:\s*["']?([^"'\n]+)["']?\s*$""")
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        return (oldSkill ?: AiSkillConfig(
            name = name.ifBlank { str(R.string.ai_skill_default_name) },
            content = content
        )).copy(
            name = name.ifBlank { oldSkill?.name ?: str(R.string.ai_skill_default_name) },
            description = description.ifBlank { oldSkill?.description.orEmpty() },
            content = content.trim(),
            sourceUrl = sourceUrl.ifBlank { oldSkill?.sourceUrl.orEmpty() },
            enabled = oldSkill?.enabled ?: true
        )
    }

    private fun refreshUi(notifyMain: Boolean = false) {
        val canEnable = AppConfig.aiCurrentModelConfig != null
        val storedEnabled = booleanSetting(PreferKey.aiAssistantEnabled, false)
        if (!canEnable && storedEnabled) {
            AppConfig.aiAssistantEnabled = false
        }
        refreshSettings()
        if (notifyMain || (!canEnable && storedEnabled)) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

}
