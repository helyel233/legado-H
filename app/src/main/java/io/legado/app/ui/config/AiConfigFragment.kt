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
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.host.HostAiImageGalleryActivity
import io.legado.app.ui.main.ai.AiSkillConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppNumberPickerDialog
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.ui.host.HostAiImageProviderManageActivity
import io.legado.app.ui.host.HostAiProviderManageActivity
import io.legado.app.ui.host.HostAiWorldBookManageActivity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    internal var toolRoundsOpen by mutableStateOf(false)
    internal var readToolModeOpen by mutableStateOf(false)
    internal var defaultModelMenuOpen by mutableStateOf(false)
    internal var modelPickerKind by mutableStateOf(0)
    internal var modelPickerOpen by mutableStateOf(false)
    internal var imageProviderPickerOpen by mutableStateOf(false)
    internal var mcpServerEditorOpen by mutableStateOf(false)
    internal var mcpEditing by mutableStateOf<AiMcpServerConfig?>(null)
    internal var mcpNameText by mutableStateOf("")
    internal var mcpEndpointText by mutableStateOf("")
    internal var mcpApiKeyText by mutableStateOf("")
    internal var mcpEnabled by mutableStateOf(true)
    internal var mcpMenuOpen by mutableStateOf(false)
    internal var mcpActionTarget by mutableStateOf<AiMcpServerConfig?>(null)
    internal var mcpDeleteTarget by mutableStateOf<AiMcpServerConfig?>(null)
    internal var budgetMenuOpen by mutableStateOf(false)
    internal var consentMenuOpen by mutableStateOf(false)
    internal var compressionMenuOpen by mutableStateOf(false)
    internal var tokenMenuContextWindow by mutableStateOf(true)
    internal var tokenMenuOpen by mutableStateOf(false)
    internal var nativeToolsOpen by mutableStateOf(false)
    internal var tavilyApiKeyOpen by mutableStateOf(false)
    internal var tavilyApiKeyText by mutableStateOf("")
    internal var tavilyBaseUrlOpen by mutableStateOf(false)
    internal var tavilyBaseUrlText by mutableStateOf("")
    internal var tavilyTopicOpen by mutableStateOf(false)
    internal var tavilyDepthOpen by mutableStateOf(false)
    internal var tavilyMaxResultsOpen by mutableStateOf(false)
    internal var skillMenuOpen by mutableStateOf(false)
    internal var skillActionTarget by mutableStateOf<AiSkillConfig?>(null)
    internal var skillEditOpen by mutableStateOf(false)
    internal var skillEditing by mutableStateOf<AiSkillConfig?>(null)
    internal var skillEditText by mutableStateOf("")
    internal var skillDeleteTarget by mutableStateOf<AiSkillConfig?>(null)
    internal var nativeToolNames by mutableStateOf<List<String>>(emptyList())
    internal var nativeToolChecked by mutableStateOf<Set<Int>>(emptySet())

    override val extraDialogs: @Composable () -> Unit = { AiConfigDialogs(this@AiConfigFragment) }

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
                    title = getString(R.string.ai_assistant),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.aiAssistantEnabled,
                            title = getString(R.string.ai_enable),
                            summary = getString(
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
                            title = getString(R.string.ai_import_default_skill),
                            summary = getString(R.string.ai_import_default_skill_summary),
                            onClick = ::importDefaultSkill
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiSkillPrompt,
                            title = getString(R.string.ai_skill_prompt),
                            summary = if (skills.isEmpty()) {
                                getString(R.string.ai_skill_prompt_summary_empty)
                            } else {
                                getString(
                                    R.string.ai_skill_prompt_summary,
                                    enabledSkillCount,
                                    skills.size
                                )
                            },
                            onClick = ::showManageSkillsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_MANAGE_NATIVE_TOOLS,
                            title = getString(R.string.ai_manage_native_tools),
                            summary = "${getString(R.string.ai_manage_native_tools_summary)} · ${AiToolRegistry.effectiveEnabledToolNames().size}",
                            onClick = ::showManageNativeToolsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_AI_WORKSPACE,
                            title = "AI 工作区",
                            summary = "查看 Agent 创建、编辑和备份的文件",
                            onClick = ::openAiWorkspace
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiAgentMaxToolRounds,
                            title = "AI 工具轮次上限",
                            summary = "${AppConfig.aiAgentMaxToolRounds} 轮",
                            onClick = ::showAgentMaxToolRoundsDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiReadToolMode,
                            title = "正文问 AI 工具范围",
                            summary = readToolModeLabel(),
                            onClick = ::showReadToolModeDialog
                        ),
                        switch(
                            key = PreferKey.aiEnterToSend,
                            title = getString(R.string.ai_enter_to_send),
                            summary = getString(R.string.ai_enter_to_send_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.aiThinkingToolbarEnabled,
                            title = "显示思考工具栏",
                            summary = "关闭后聊天页不显示思考和工具调用过程卡片，不影响后台执行",
                            defaultValue = true
                        ),
                        SettingActionSpec(
                            key = KEY_CONTEXT_COMPRESSION,
                            title = getString(R.string.ai_context_compression),
                            summary = if (AppConfig.aiContextCompressionEnabled) {
                                "${AppConfig.aiContextWindowTokens} / ${AppConfig.aiThinkingContextTokens}"
                            } else {
                                getString(R.string.ai_context_compression_summary_default)
                            },
                            onClick = ::showContextCompressionDialog
                        ),
                        SettingActionSpec(
                            key = KEY_WORLD_BOOK_MANAGE,
                            title = "世界书管理",
                            summary = if (worldBooks.isEmpty()) {
                                "未配置世界书"
                            } else {
                                "${worldBooks.count { it.enabled }}/${worldBooks.size} 启用 · ${worldBooks.sumOf { it.entries.size }} 条目"
                            },
                            onClick = {
                                startActivity(Intent(requireContext(), HostAiWorldBookManageActivity::class.java))
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_CONTENT_CONSENT,
                            title = "正文隐私授权",
                            summary = contentConsentSummary(),
                            onClick = ::showContentConsentDialog
                        ),
                        SettingActionSpec(
                            key = KEY_USAGE_BUDGET,
                            title = "用量与预算",
                            summary = aiUsageBudgetSummary(),
                            onClick = ::showAiBudgetDialog
                        ),
                        SettingActionSpec(
                            key = KEY_DEFAULT_MODEL_SETTINGS,
                            title = "默认模型",
                            summary = "问AI ${modelLabel(AppConfig.aiAskModelConfig)} / 总结 ${modelLabel(AppConfig.aiSummaryModelConfig)} / 多角色 ${modelLabel(AppConfig.aiReadAloudRoleModelConfig)} / 生图 ${imageProviderLabel()}",
                            onClick = ::showDefaultModelSettingsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_IMAGE_GALLERY,
                            title = getString(R.string.ai_image_gallery),
                            summary = getString(R.string.ai_image_gallery_summary),
                            onClick = {
                                startActivity(Intent(requireContext(), HostAiImageGalleryActivity::class.java))
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_IMAGE_PROVIDER_MANAGE,
                            title = getString(R.string.ai_image_provider_manage),
                            summary = if (imageProviders.isEmpty()) {
                                getString(R.string.ai_image_provider_summary_empty)
                            } else {
                                getString(
                                    R.string.ai_image_provider_summary,
                                    imageProviders.count { it.enabled },
                                    imageProviders.size
                                )
                            },
                            onClick = {
                                startActivity(Intent(requireContext(), HostAiImageProviderManageActivity::class.java))
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.ai_provider),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_MANAGE_PROVIDERS,
                            title = getString(R.string.ai_manage_providers),
                            summary = if (AppConfig.aiProviderList.isEmpty()) {
                                getString(R.string.ai_no_providers)
                            } else {
                                buildString {
                                    append(currentProvider?.name ?: getString(R.string.ai_current_provider_summary_empty))
                                    append(" · ")
                                    append(getString(R.string.ai_manage_providers_summary, AppConfig.aiProviderList.size))
                                }
                            },
                            onClick = {
                                startActivity(Intent(requireContext(), HostAiProviderManageActivity::class.java))
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.ai_mcp),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_ADD_MCP_SERVER,
                            title = getString(R.string.ai_add_mcp_server),
                            summary = getString(R.string.ai_add_mcp_server_summary),
                            onClick = { showEditMcpServerDialog() }
                        ),
                        SettingActionSpec(
                            key = KEY_MANAGE_MCP_SERVERS,
                            title = getString(R.string.ai_manage_mcp_servers),
                            summary = if (mcpServers.isEmpty()) {
                                getString(R.string.ai_no_mcp_servers)
                            } else {
                                getString(
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
                    title = getString(R.string.ai_web_tools),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.aiTavilyEnabled,
                            title = getString(R.string.ai_tavily_enable),
                            summary = getString(
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
                            title = getString(R.string.ai_tavily_api_key),
                            summary = if (AppConfig.aiTavilyApiKey.isBlank()) {
                                getString(R.string.ai_tavily_api_key_summary)
                            } else {
                                getString(R.string.ai_tavily_api_key_summary_ready)
                            },
                            onClick = ::showTavilyApiKeyDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyBaseUrl,
                            title = getString(R.string.ai_tavily_base_url),
                            summary = AppConfig.aiTavilyBaseUrl,
                            onClick = ::showTavilyBaseUrlDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyTopic,
                            title = getString(R.string.ai_tavily_topic),
                            summary = tavilyTopicLabel(),
                            onClick = ::showTavilyTopicDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilySearchDepth,
                            title = getString(R.string.ai_tavily_search_depth),
                            summary = tavilySearchDepthLabel(),
                            onClick = ::showTavilySearchDepthDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyMaxResults,
                            title = getString(R.string.ai_tavily_max_results),
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
        val root = File(requireContext().filesDir, "ai_workspace").apply { mkdirs() }
        // P4-3 迁移：AI 工作区走 V5 文件管理页（HostFileManageActivity）
        io.legado.app.ui.host.HostFileManageActivity.start(
            requireContext(),
            rootPath = root.absolutePath,
            title = "AI 工作区",
        )
    }

    private fun showAgentMaxToolRoundsDialog() {
        toolRoundsOpen = true
    }

    internal fun onToolRoundsConfirmed(value: Int) {
        toolRoundsOpen = false
        AppConfig.aiAgentMaxToolRounds = value
        refreshUi()
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
            AppConfig.AI_READ_TOOL_MODE_ALL -> "全量工具"
            AppConfig.AI_READ_TOOL_MODE_SAFE -> "阅读安全工具"
            else -> "使用已启用工具"
        }
    }

    private fun tavilyTopicLabel(): String {
        return getString(
            when (AppConfig.aiTavilyTopic) {
                "news" -> R.string.ai_tavily_topic_news
                "finance" -> R.string.ai_tavily_topic_finance
                else -> R.string.ai_tavily_topic_general
            }
        )
    }

    private fun tavilySearchDepthLabel(): String {
        return getString(
            when (AppConfig.aiTavilySearchDepth) {
                "advanced" -> R.string.ai_tavily_search_depth_advanced
                "ultra-fast" -> R.string.ai_tavily_search_depth_ultra_fast
                else -> R.string.ai_tavily_search_depth_basic
            }
        )
    }

    private fun showReadToolModeDialog() {
        readToolModeOpen = true
    }

    internal fun onReadToolModeSelected(index: Int) {
        readToolModeOpen = false
        val values = listOf(
            AppConfig.AI_READ_TOOL_MODE_ENABLED,
            AppConfig.AI_READ_TOOL_MODE_SAFE,
            AppConfig.AI_READ_TOOL_MODE_ALL
        )
        values.getOrNull(index)?.let {
            AppConfig.aiReadToolMode = it
            refreshUi()
        }
    }

    private fun showDefaultModelSettingsDialog() {
        defaultModelMenuOpen = true
    }

    internal fun onDefaultModelMenuSelected(index: Int) {
        defaultModelMenuOpen = false
        when (index) {
            0 -> {
                modelPickerKind = 0
                modelPickerOpen = true
            }
            1 -> {
                modelPickerKind = 1
                modelPickerOpen = true
            }
            2 -> {
                modelPickerKind = 2
                modelPickerOpen = true
            }
            3 -> selectDefaultImageProvider()
        }
    }

    private fun selectDefaultModel(
        title: String,
        currentId: String?,
        onSelect: (String) -> Unit
    ) {
        // 兼容保留：V5 化后由弹窗内联调用（见 AiConfigDialogs）
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_no_models)
            return
        }
        onSelect(models.firstOrNull { it.id == currentId }?.id ?: models.first().id)
        refreshUi()
    }

    internal fun onModelPickerSelected(model: AiModelConfig) {
        modelPickerOpen = false
        when (modelPickerKind) {
            0 -> AppConfig.aiAskModelId = model.id
            1 -> AppConfig.aiSummaryModelId = model.id
            2 -> AppConfig.aiReadAloudRoleModelId = model.id
        }
        refreshUi()
    }

    private fun selectDefaultImageProvider() {
        val providers = AppConfig.aiEnabledImageProviders
        if (providers.isEmpty()) {
            toastOnUi(R.string.ai_image_provider_summary_empty)
            return
        }
        imageProviderPickerOpen = true
    }

    internal fun onImageProviderSelected(provider: io.legado.app.ui.main.ai.AiImageProviderConfig) {
        imageProviderPickerOpen = false
        AppConfig.aiCurrentImageProviderId = provider.id
        refreshUi()
    }

    internal fun modelLabel(model: AiModelConfig?): String {
        model ?: return "未配置"
        val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
            ?.name
            ?.takeIf { it.isNotBlank() }
        return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
    }

    internal fun imageProviderLabel(): String {
        return AppConfig.aiCurrentImageProvider?.displayName() ?: "未配置"
    }

    private fun showEditMcpServerDialog(server: AiMcpServerConfig? = null) {
        mcpEditing = server
        mcpNameText = server?.name.orEmpty()
        mcpEndpointText = server?.endpoint.orEmpty()
        mcpApiKeyText = server?.apiKey.orEmpty()
        mcpEnabled = server?.enabled ?: true
        mcpServerEditorOpen = true
    }

    internal fun onMcpServerSave() {
        val server = mcpEditing
        val name = mcpNameText.trim()
        val endpoint = mcpEndpointText.trim()
        val apiKey = mcpApiKeyText.trim()
        when {
            name.isEmpty() -> {
                toastOnUi(R.string.ai_mcp_server_name_required)
                return
            }
            endpoint.isEmpty() -> {
                toastOnUi(R.string.ai_mcp_server_endpoint_required)
                return
            }
        }
        mcpServerEditorOpen = false
        val servers = AppConfig.aiMcpServerList.toMutableList()
        val updated = server?.copy(
            name = name,
            endpoint = endpoint,
            apiKey = apiKey,
            enabled = mcpEnabled
        ) ?: AiMcpServerConfig(
            name = name,
            endpoint = endpoint,
            apiKey = apiKey,
            enabled = mcpEnabled
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

    private fun showManageMcpServersDialog() {
        val servers = AppConfig.aiMcpServerList
        if (servers.isEmpty()) {
            toastOnUi(R.string.ai_no_mcp_servers)
            return
        }
        mcpMenuOpen = true
    }

    internal fun onMcpServerSelected(server: AiMcpServerConfig) {
        mcpMenuOpen = false
        mcpActionTarget = server
    }

    internal fun onMcpActionSelected(action: Int) {
        val server = mcpActionTarget ?: return
        mcpActionTarget = null
        when (action) {
            0 -> {
                AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.map {
                    if (it.id == server.id) it.copy(enabled = !it.enabled) else it
                }
                refreshUi()
            }
            1 -> showEditMcpServerDialog(server)
            2 -> mcpDeleteTarget = server
        }
    }

    internal fun onMcpDeleteConfirmed() {
        val server = mcpDeleteTarget ?: return
        mcpDeleteTarget = null
        AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.filterNot { it.id == server.id }
        refreshUi()
        toastOnUi(R.string.ai_mcp_server_removed)
    }


    private fun contentConsentSummary(): String = when (AppConfig.aiExternalContentConsent) {
        AppConfig.AI_CONTENT_CONSENT_ALLOW -> "始终允许发送正文"
        AppConfig.AI_CONTENT_CONSENT_DENY -> "始终禁止发送正文"
        else -> "每次询问"
    }

    private fun aiUsageBudgetSummary(): String {
        val usage = AiUsageTracker.todayUsage()
        val budget = AppConfig.aiDailyTokenBudget
        val budgetText = if (budget > 0L) formatTokenCount(budget) else "不限"
        return "今日 ${formatTokenCount(usage.totalTokens)} tokens · ${usage.requests} 次请求 · 每日上限 $budgetText"
    }

    internal fun formatTokenCount(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    private fun showAiBudgetDialog() {
        budgetMenuOpen = true
    }

    internal fun onBudgetSelected(index: Int) {
        budgetMenuOpen = false
        val values = listOf(0L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L, 1_000_000L)
        values.getOrNull(index)?.let {
            AppConfig.aiDailyTokenBudget = it
            refreshUi()
        }
    }

    private fun showContentConsentDialog() {
        consentMenuOpen = true
    }

    internal fun onConsentSelected(index: Int) {
        consentMenuOpen = false
        AppConfig.aiExternalContentConsent = when (index) {
            1 -> AppConfig.AI_CONTENT_CONSENT_ALLOW
            2 -> AppConfig.AI_CONTENT_CONSENT_DENY
            else -> AppConfig.AI_CONTENT_CONSENT_ASK
        }
        refreshUi()
    }

    private fun showContextCompressionDialog() {
        compressionMenuOpen = true
    }

    internal fun onCompressionMenuSelected(index: Int) {
        compressionMenuOpen = false
        when (index) {
            0 -> {
                AppConfig.aiContextCompressionEnabled = !AppConfig.aiContextCompressionEnabled
                refreshUi()
            }
            1 -> showTokenSelector(true)
            2 -> showTokenSelector(false)
        }
    }

    private fun showTokenSelector(contextWindow: Boolean) {
        tokenMenuContextWindow = contextWindow
        tokenMenuOpen = true
    }

    internal fun onTokenSelected(index: Int) {
        tokenMenuOpen = false
        val values = if (tokenMenuContextWindow) {
            listOf(32_000, 64_000, 128_000, 258_000, 512_000, 1_000_000)
        } else {
            listOf(0, 32_000, 64_000, 128_000, 258_000)
        }
        values.getOrNull(index)?.let {
            if (tokenMenuContextWindow) AppConfig.aiContextWindowTokens = it
            else AppConfig.aiThinkingContextTokens = it
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
            nativeToolNames = toolNames
            nativeToolChecked = toolNames.indices
                .filter { i -> toolNames[i] in enabledToolNames }
                .toSet()
            nativeToolsOpen = true
        }
    }

    internal fun onNativeToolsConfirmed(checked: Set<Int>) {
        nativeToolsOpen = false
        val newEnabled = mutableSetOf<String>()
        checked.forEach { index ->
            val name = nativeToolNames.getOrNull(index)
            if (name != null && !name.startsWith("__group_")) {
                newEnabled.add(name)
            }
        }
        AppConfig.aiEnabledToolNames = newEnabled
        refreshUi()
    }

    private fun groupOrder(group: String): Int {
        return when (group) {
            "书架" -> 0
            "AI workspace" -> 1
            "书源" -> 2
            "阅读" -> 3
            "阅读网络" -> 4
            "联网搜索" -> 5
            "AI 生图" -> 6
            "角色资料" -> 7
            "设置" -> 8
            "MCP 工具" -> 9
            else -> 9
        }
    }

    private fun showTavilyApiKeyDialog() {
        tavilyApiKeyText = AppConfig.aiTavilyApiKey
        tavilyApiKeyOpen = true
    }

    internal fun onTavilyApiKeyConfirmed(text: String) {
        tavilyApiKeyOpen = false
        AppConfig.aiTavilyApiKey = text
        refreshUi()
    }

    private fun showTavilyBaseUrlDialog() {
        tavilyBaseUrlText = AppConfig.aiTavilyBaseUrl
        tavilyBaseUrlOpen = true
    }

    internal fun onTavilyBaseUrlConfirmed(text: String) {
        tavilyBaseUrlOpen = false
        AppConfig.aiTavilyBaseUrl = text
        refreshUi()
    }

    internal fun onTavilyBaseUrlRestore() {
        tavilyBaseUrlOpen = false
        AppConfig.aiTavilyBaseUrl = "https://api.tavily.com/search"
        refreshUi()
    }

    private fun showTavilyTopicDialog() {
        tavilyTopicOpen = true
    }

    internal fun onTavilyTopicSelected(index: Int) {
        tavilyTopicOpen = false
        val values = listOf("general", "news", "finance")
        values.getOrNull(index)?.let {
            AppConfig.aiTavilyTopic = it
            refreshUi()
        }
    }

    private fun showTavilySearchDepthDialog() {
        tavilyDepthOpen = true
    }

    internal fun onTavilyDepthSelected(index: Int) {
        tavilyDepthOpen = false
        val values = listOf("basic", "advanced", "ultra-fast")
        values.getOrNull(index)?.let {
            AppConfig.aiTavilySearchDepth = it
            refreshUi()
        }
    }

    private fun showTavilyMaxResultsDialog() {
        tavilyMaxResultsOpen = true
    }

    internal fun onTavilyMaxResultsConfirmed(value: Int) {
        tavilyMaxResultsOpen = false
        AppConfig.aiTavilyMaxResults = value
        refreshUi()
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
                toastOnUi(getString(R.string.ai_skill_import_failed, it.localizedMessage ?: "Error"))
            }
        }
    }

    private fun showManageSkillsDialog() {
        skillMenuOpen = true
    }

    internal fun onSkillMenuSelected(index: Int) {
        val skills = AppConfig.aiSkillList
        skillMenuOpen = false
        if (index == 0) {
            showSkillEditDialog()
        } else {
            skills.getOrNull(index - 1)?.let { skillActionTarget = it }
        }
    }

    internal fun onSkillActionSelected(action: Int) {
        val skill = skillActionTarget ?: return
        skillActionTarget = null
        when (action) {
            0 -> {
                AppConfig.aiSkillList = AppConfig.aiSkillList.map {
                    if (it.id == skill.id) it.copy(enabled = !it.enabled) else it
                }
                refreshUi()
            }
            1 -> showSkillEditDialog(skill)
            2 -> skillDeleteTarget = skill
        }
    }

    private fun showSkillEditDialog(skill: AiSkillConfig? = null) {
        skillEditing = skill
        skillEditText = skill?.content.orEmpty()
        skillEditOpen = true
    }

    internal fun onSkillEditConfirmed(content: String) {
        if (content.isBlank()) {
            toastOnUi(R.string.ai_skill_import_empty)
            return
        }
        val skill = skillEditing
        skillEditOpen = false
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

    internal fun onSkillDeleteConfirmed() {
        val skill = skillDeleteTarget ?: return
        skillDeleteTarget = null
        AppConfig.aiSkillList = AppConfig.aiSkillList.filterNot { it.id == skill.id }
        refreshUi()
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
            name = name.ifBlank { getString(R.string.ai_skill_default_name) },
            content = content
        )).copy(
            name = name.ifBlank { oldSkill?.name ?: getString(R.string.ai_skill_default_name) },
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
    }

}

// ── V5 弹窗集合（替代旧 showComposeXxx 命令式弹窗） ─────────────────────────
@Composable
private fun AiConfigDialogs(fragment: AiConfigFragment) {
    val density = LocalVisualSpec.current.density

    if (fragment.toolRoundsOpen) {
        AppNumberPickerDialog(
            title = "AI 工具轮次上限",
            value = AppConfig.aiAgentMaxToolRounds,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            range = 4..64,
            step = 1,
            valueLabel = AppConfig.aiAgentMaxToolRounds.toString(),
            onConfirm = { fragment.onToolRoundsConfirmed(it) },
            onDismiss = { fragment.toolRoundsOpen = false },
        )
    }

    if (fragment.readToolModeOpen) {
        val values = listOf(
            AppConfig.AI_READ_TOOL_MODE_ENABLED,
            AppConfig.AI_READ_TOOL_MODE_SAFE,
            AppConfig.AI_READ_TOOL_MODE_ALL
        )
        val labels = listOf("使用已启用工具", "阅读安全工具", "全量工具")
        AppMenuSheet(
            title = "正文问 AI 工具范围",
            actions = labels.mapIndexed { index, label ->
                (if (values[index] == AppConfig.aiReadToolMode) "$label ✓" else label) to {
                    fragment.onReadToolModeSelected(index)
                }
            },
            onDismiss = { fragment.readToolModeOpen = false },
        )
    }

    if (fragment.defaultModelMenuOpen) {
        AppMenuSheet(
            title = "默认模型",
            actions = listOf(
                "正文问 AI：${fragment.modelLabel(AppConfig.aiAskModelConfig)}" to { fragment.onDefaultModelMenuSelected(0) },
                "文章总结：${fragment.modelLabel(AppConfig.aiSummaryModelConfig)}" to { fragment.onDefaultModelMenuSelected(1) },
                "多角色：${fragment.modelLabel(AppConfig.aiReadAloudRoleModelConfig)}" to { fragment.onDefaultModelMenuSelected(2) },
                "图像生成供应商：${fragment.imageProviderLabel()}" to { fragment.onDefaultModelMenuSelected(3) },
            ),
            onDismiss = { fragment.defaultModelMenuOpen = false },
        )
    }

    if (fragment.modelPickerOpen) {
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            fragment.modelPickerOpen = false
        } else {
            val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
            val currentId = when (fragment.modelPickerKind) {
                0 -> AppConfig.aiAskModelId
                1 -> AppConfig.aiSummaryModelId
                else -> AppConfig.aiReadAloudRoleModelId
            }
            AppMenuSheet(
                title = when (fragment.modelPickerKind) {
                    0 -> "正文问 AI 模型"
                    1 -> "文章总结模型"
                    else -> "多角色模型"
                },
                actions = models.map { model ->
                    val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                        ?.let { "${model.modelId} - $it" } ?: model.modelId
                    (if (model.id == currentId) "$label ✓" else label) to {
                        fragment.onModelPickerSelected(model)
                    }
                },
                onDismiss = { fragment.modelPickerOpen = false },
            )
        }
    }

    if (fragment.imageProviderPickerOpen) {
        val providers = AppConfig.aiEnabledImageProviders
        if (providers.isEmpty()) {
            fragment.imageProviderPickerOpen = false
        } else {
            val currentId = AppConfig.aiCurrentImageProvider?.id
            AppMenuSheet(
                title = "图像生成供应商",
                actions = providers.map { provider ->
                    val label = provider.displayName()
                    (if (provider.id == currentId) "$label ✓" else label) to {
                        fragment.onImageProviderSelected(provider)
                    }
                },
                onDismiss = { fragment.imageProviderPickerOpen = false },
            )
        }
    }

    if (fragment.mcpServerEditorOpen) {
        AppDialog(onDismiss = { fragment.mcpServerEditorOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(density.screenPadding),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = stringResource(
                        if (fragment.mcpEditing == null) R.string.ai_add_mcp_server
                        else R.string.ai_edit_mcp_server
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppTextField(
                    value = fragment.mcpNameText,
                    onValueChange = { fragment.mcpNameText = it },
                    placeholder = stringResource(R.string.ai_mcp_server_name),
                    singleLine = true,
                )
                AppTextField(
                    value = fragment.mcpEndpointText,
                    onValueChange = { fragment.mcpEndpointText = it },
                    placeholder = stringResource(R.string.ai_mcp_server_endpoint),
                    singleLine = true,
                )
                AppTextField(
                    value = fragment.mcpApiKeyText,
                    onValueChange = { fragment.mcpApiKeyText = it },
                    placeholder = stringResource(R.string.ai_api_key),
                    singleLine = true,
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.ai_mcp_server_enabled),
                    checked = fragment.mcpEnabled,
                    onCheckedChange = { fragment.mcpEnabled = it },
                    card = false,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.mcpServerEditorOpen = false },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.onMcpServerSave() },
                    )
                }
            }
        }
    }

    if (fragment.mcpMenuOpen) {
        val servers = AppConfig.aiMcpServerList
        AppMenuSheet(
            title = stringResource(R.string.ai_manage_mcp_servers),
            actions = servers.map { server ->
                buildString {
                    append(server.name)
                    if (!server.enabled) append(" (off)")
                } to { fragment.onMcpServerSelected(server) }
            },
            onDismiss = { fragment.mcpMenuOpen = false },
        )
    }

    fragment.mcpActionTarget?.let { server ->
        AppMenuSheet(
            title = server.name,
            actions = listOf(
                stringResource(
                    if (server.enabled) R.string.ai_disable_mcp_server
                    else R.string.ai_enable_mcp_server
                ) to { fragment.onMcpActionSelected(0) },
                stringResource(R.string.ai_edit_mcp_server) to { fragment.onMcpActionSelected(1) },
                stringResource(R.string.ai_remove_mcp_server) to { fragment.onMcpActionSelected(2) },
            ),
            onDismiss = { fragment.mcpActionTarget = null },
        )
    }

    fragment.mcpDeleteTarget?.let { server ->
        AppConfirmDialog(
            title = server.name,
            message = stringResource(R.string.ai_remove_mcp_server_confirm),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { fragment.onMcpDeleteConfirmed() },
            onDismiss = { fragment.mcpDeleteTarget = null },
        )
    }

    if (fragment.budgetMenuOpen) {
        val values = listOf(0L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L, 1_000_000L)
        AppMenuSheet(
            title = "每日 token 预算",
            actions = values.mapIndexed { index, value ->
                val label = if (value <= 0L) "不限" else "${fragment.formatTokenCount(value)} tokens"
                (if (value == AppConfig.aiDailyTokenBudget) "$label ✓" else label) to {
                    fragment.onBudgetSelected(index)
                }
            },
            onDismiss = { fragment.budgetMenuOpen = false },
        )
    }

    if (fragment.consentMenuOpen) {
        val consentValues = listOf(
            AppConfig.AI_CONTENT_CONSENT_ASK,
            AppConfig.AI_CONTENT_CONSENT_ALLOW,
            AppConfig.AI_CONTENT_CONSENT_DENY
        )
        AppMenuSheet(
            title = "正文隐私授权",
            actions = listOf("每次询问", "始终允许发送正文", "始终禁止发送正文")
                .mapIndexed { index, label ->
                    (if (consentValues[index] == AppConfig.aiExternalContentConsent) "$label ✓" else label) to {
                        fragment.onConsentSelected(index)
                    }
                },
            onDismiss = { fragment.consentMenuOpen = false },
        )
    }

    if (fragment.compressionMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.ai_context_compression),
            actions = listOf(
                (if (AppConfig.aiContextCompressionEnabled) "关闭上下文压缩" else "启用上下文压缩") to { fragment.onCompressionMenuSelected(0) },
                "上下文长度: ${AppConfig.aiContextWindowTokens}" to { fragment.onCompressionMenuSelected(1) },
                "思考上下文: ${AppConfig.aiThinkingContextTokens}" to { fragment.onCompressionMenuSelected(2) },
            ),
            onDismiss = { fragment.compressionMenuOpen = false },
        )
    }

    if (fragment.tokenMenuOpen) {
        val values = if (fragment.tokenMenuContextWindow) {
            listOf(32_000, 64_000, 128_000, 258_000, 512_000, 1_000_000)
        } else {
            listOf(0, 32_000, 64_000, 128_000, 258_000)
        }
        val current = if (fragment.tokenMenuContextWindow) {
            AppConfig.aiContextWindowTokens
        } else {
            AppConfig.aiThinkingContextTokens
        }
        AppMenuSheet(
            title = stringResource(
                if (fragment.tokenMenuContextWindow) R.string.ai_context_tokens
                else R.string.ai_thinking_context_tokens
            ),
            actions = values.mapIndexed { index, value ->
                (if (value == current) "$value ✓" else value.toString()) to {
                    fragment.onTokenSelected(index)
                }
            },
            onDismiss = { fragment.tokenMenuOpen = false },
        )
    }

    if (fragment.nativeToolsOpen) {
        var checked by remember(fragment.nativeToolsOpen) {
            mutableStateOf(fragment.nativeToolChecked)
        }
        AppDialog(onDismiss = { fragment.nativeToolsOpen = false }) {
            Column(modifier = Modifier.padding(density.screenPadding)) {
                Text(
                    text = stringResource(R.string.ai_manage_native_tools),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(fragment.nativeToolNames) { index, name ->
                        val isGroup = name.startsWith("__group_")
                        AppListItem(
                            headline = name.removePrefix("__group_").let {
                                if (isGroup) "--- $it ---" else it
                            },
                            card = false,
                            leading = {
                                if (!isGroup) {
                                    Checkbox(
                                        checked = index in checked,
                                        onCheckedChange = { isChecked ->
                                            checked = if (isChecked) checked + index else checked - index
                                        },
                                    )
                                }
                            },
                            onClick = {
                                if (!isGroup) {
                                    checked = if (index in checked) checked - index else checked + index
                                }
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.nativeToolsOpen = false },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.onNativeToolsConfirmed(checked) },
                    )
                }
            }
        }
    }

    if (fragment.tavilyApiKeyOpen) {
        AppInputDialog(
            title = stringResource(R.string.ai_tavily_api_key),
            value = fragment.tavilyApiKeyText,
            onValueChange = { fragment.tavilyApiKeyText = it },
            placeholder = stringResource(R.string.ai_tavily_api_key_hint),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { fragment.onTavilyApiKeyConfirmed(fragment.tavilyApiKeyText) },
            onDismiss = { fragment.tavilyApiKeyOpen = false },
        )
    }

    if (fragment.tavilyBaseUrlOpen) {
        AppDialog(onDismiss = { fragment.tavilyBaseUrlOpen = false }) {
            Column(
                modifier = Modifier.padding(density.screenPadding),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = stringResource(R.string.ai_tavily_base_url),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppTextField(
                    value = fragment.tavilyBaseUrlText,
                    onValueChange = { fragment.tavilyBaseUrlText = it },
                    placeholder = "https://api.tavily.com/search",
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.tavilyBaseUrlOpen = false },
                    )
                    AppGhostButton(
                        text = stringResource(R.string.restore_default),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.onTavilyBaseUrlRestore() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.onTavilyBaseUrlConfirmed(fragment.tavilyBaseUrlText) },
                    )
                }
            }
        }
    }

    if (fragment.tavilyTopicOpen) {
        val values = listOf("general", "news", "finance")
        val labels = listOf(
            R.string.ai_tavily_topic_general,
            R.string.ai_tavily_topic_news,
            R.string.ai_tavily_topic_finance
        )
        AppMenuSheet(
            title = stringResource(R.string.ai_tavily_topic),
            actions = labels.mapIndexed { index, labelRes ->
                val label = stringResource(labelRes)
                (if (values[index] == AppConfig.aiTavilyTopic) "$label ✓" else label) to {
                    fragment.onTavilyTopicSelected(index)
                }
            },
            onDismiss = { fragment.tavilyTopicOpen = false },
        )
    }

    if (fragment.tavilyDepthOpen) {
        val values = listOf("basic", "advanced", "ultra-fast")
        val labels = listOf(
            R.string.ai_tavily_search_depth_basic,
            R.string.ai_tavily_search_depth_advanced,
            R.string.ai_tavily_search_depth_ultra_fast
        )
        AppMenuSheet(
            title = stringResource(R.string.ai_tavily_search_depth),
            actions = labels.mapIndexed { index, labelRes ->
                val label = stringResource(labelRes)
                (if (values[index] == AppConfig.aiTavilySearchDepth) "$label ✓" else label) to {
                    fragment.onTavilyDepthSelected(index)
                }
            },
            onDismiss = { fragment.tavilyDepthOpen = false },
        )
    }

    if (fragment.tavilyMaxResultsOpen) {
        AppNumberPickerDialog(
            title = stringResource(R.string.ai_tavily_max_results),
            value = AppConfig.aiTavilyMaxResults,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            range = 1..10,
            step = 1,
            valueLabel = AppConfig.aiTavilyMaxResults.toString(),
            onConfirm = { fragment.onTavilyMaxResultsConfirmed(it) },
            onDismiss = { fragment.tavilyMaxResultsOpen = false },
        )
    }

    if (fragment.skillMenuOpen) {
        val skills = AppConfig.aiSkillList
        AppMenuSheet(
            title = stringResource(R.string.ai_manage_skills),
            actions = buildList {
                add(stringResource(R.string.ai_add_skill_manual) to { fragment.onSkillMenuSelected(0) })
                skills.forEachIndexed { index, skill ->
                    add(
                        "${skill.name} · " + stringResource(
                            if (skill.enabled) R.string.enabled else R.string.disabled
                        ) to { fragment.onSkillMenuSelected(index + 1) }
                    )
                }
            },
            onDismiss = { fragment.skillMenuOpen = false },
        )
    }

    fragment.skillActionTarget?.let { skill ->
        AppMenuSheet(
            title = skill.name,
            actions = listOf(
                stringResource(if (skill.enabled) R.string.disable else R.string.enable) to { fragment.onSkillActionSelected(0) },
                stringResource(R.string.edit) to { fragment.onSkillActionSelected(1) },
                stringResource(R.string.delete) to { fragment.onSkillActionSelected(2) },
            ),
            onDismiss = { fragment.skillActionTarget = null },
        )
    }

    if (fragment.skillEditOpen) {
        AppDialog(onDismiss = { fragment.skillEditOpen = false }) {
            Column(
                modifier = Modifier.padding(density.screenPadding),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = stringResource(R.string.ai_skill_prompt),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppTextField(
                    value = fragment.skillEditText,
                    onValueChange = { fragment.skillEditText = it },
                    placeholder = stringResource(R.string.ai_skill_prompt_hint),
                    singleLine = false,
                    minLines = 8,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.skillEditOpen = false },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = { fragment.onSkillEditConfirmed(fragment.skillEditText) },
                    )
                }
            }
        }
    }

    fragment.skillDeleteTarget?.let { skill ->
        AppConfirmDialog(
            title = skill.name,
            message = stringResource(R.string.ai_remove_skill_confirm),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { fragment.onSkillDeleteConfirmed() },
            onDismiss = { fragment.skillDeleteTarget = null },
        )
    }
}
