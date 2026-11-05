package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import org.json.JSONObject
import io.legado.app.R
import splitties.init.appCtx

data class AiResolvedTool(
    val name: String,
    val definition: JSONObject,
    val execute: suspend (JSONObject?) -> String
)

object AiToolRegistry {

    private const val TOOL_SETTINGS_VERSION = 16
    private val version2AddedDefaultTools = setOf(
        "list_speech_catalogs",
        "assign_character_speech_route",
        "batch_assign_character_speech_routes",
        "clear_character_speech_routes"
    )
    private val version3AddedDefaultTools = setOf(
        "list_read_aloud_bgm_catalog",
        "assign_read_aloud_bgm_ranges"
    )
    private val version4AddedDefaultTools = setOf(
        "list_speech_voice_groups",
        "upsert_speech_voice_group",
        "delete_speech_voice_group"
    )
    private val version5AddedDefaultTools = setOf(
        "list_world_books",
        "upsert_world_book",
        "delete_world_book",
        "upsert_world_book_entry",
        "delete_world_book_entry"
    )
    private val version6AddedDefaultTools = setOf(
        "list_world_book_bindings",
        "upsert_world_book_binding",
        "delete_world_book_binding"
    )
    private val version7AddedDefaultTools = setOf(
        "import_world_book_json",
        "export_world_book_json"
    )
    private val version8AddedDefaultTools = setOf(
        "search_book_chapter_content"
    )
    private val version9AddedDefaultTools = setOf(
        "batch_manage_speech_voice_groups"
    )
    private val version10AddedDefaultTools = setOf(
        "workspace_list_files",
        "workspace_read_file",
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_delete_file",
        "workspace_list_backups",
        "workspace_restore_backup",
        "workspace_import_book_source",
        "workspace_create_book_source_file",
        "workspace_debug_book_source",
        "workspace_apply_book_source"
    )
    private val version11AddedDefaultTools = setOf(
        "workspace_read_matches",
        "workspace_search_files",
        "workspace_save_input_file"
    )
    private val version12RemovedDefaultTools = setOf(
        "create_book_source",
        "update_book_source"
    )
    private val version13AddedDefaultTools = setOf(
        "workspace_create_backup"
    )
    private val version14AddedDefaultTools = setOf(
        "workspace_replace_text",
        "workspace_replace_regex",
        "workspace_edit_lines",
        "workspace_insert_text"
    )
    private val version15AddedDefaultTools = setOf(
        "workspace_read_lines",
        "workspace_diff_file"
    )

    val characterCompanionToolNames = setOf(
        "query_bookshelf",
        "get_bookshelf_book_info",
        "list_book_chapters",
        "search_book_chapter_content",
        "read_book_chapter_content",
        "generate_image"
    )

    val readSafeToolNames = setOf(
        "query_bookshelf",
        "get_bookshelf_book_info",
        "list_book_chapters",
        "search_book_chapter_content",
        "read_book_chapter_content",
        "query_read_records",
        "generate_image",
        "list_book_characters",
        "upsert_book_character",
        "list_book_character_relations",
        "upsert_book_character_relation",
        "list_ai_gallery_images",
        "set_book_character_avatar_from_gallery",
        "generate_book_character_avatar",
        "list_speech_catalogs",
        "assign_character_speech_route",
        "batch_assign_character_speech_routes",
        "list_speech_voice_groups",
        "list_read_aloud_bgm_catalog",
        "assign_read_aloud_bgm_ranges",
        "list_world_books",
        "list_world_book_bindings",
        "get_app_settings",
        "workspace_list_files",
        "workspace_read_file",
        "workspace_read_lines",
        "workspace_read_matches",
        "workspace_search_files",
        "workspace_list_backups",
        "workspace_diff_file",
        "workspace_import_book_source",
        "workspace_debug_book_source"
    )

    data class ToolMeta(
        val name: String,
        val label: String,
        val group: String
    )

    val defaultEnabledTools = setOf(
        "query_bookshelf",
        "get_bookshelf_book_info",
        "manage_bookshelf_group",
        "manage_bookshelf_tag",
        "set_bookshelf_book_group",
        "set_bookshelf_book_tags",
        "list_book_chapters",
        "search_book_chapter_content",
        "read_book_chapter_content",
        "query_read_records",
        "list_book_sources",
        "search_book_source",
        "search_web_tavily",
        "get_book_source",
        "fetch_source_html",
        "debug_book_source",
        "reading_ajax",
        "reading_webview",
        "capture_web_requests",
        "generate_image",
        "list_book_characters",
        "upsert_book_character",
        "delete_book_character",
        "list_book_character_relations",
        "upsert_book_character_relation",
        "delete_book_character_relation",
        "list_ai_gallery_images",
        "set_book_character_avatar_from_gallery",
        "generate_book_character_avatar",
        "list_speech_catalogs",
        "assign_character_speech_route",
        "batch_assign_character_speech_routes",
        "clear_character_speech_routes",
        "list_speech_voice_groups",
        "upsert_speech_voice_group",
        "delete_speech_voice_group",
        "batch_manage_speech_voice_groups",
        "list_read_aloud_bgm_catalog",
        "assign_read_aloud_bgm_ranges",
        "list_world_books",
        "upsert_world_book",
        "delete_world_book",
        "upsert_world_book_entry",
        "delete_world_book_entry",
        "list_world_book_bindings",
        "upsert_world_book_binding",
        "delete_world_book_binding",
        "import_world_book_json",
        "export_world_book_json",
        "get_app_settings",
        "set_app_setting",
        "set_app_settings_batch",
        "workspace_list_files",
        "workspace_read_file",
        "workspace_read_lines",
        "workspace_read_matches",
        "workspace_search_files",
        "workspace_save_input_file",
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_replace_text",
        "workspace_replace_regex",
        "workspace_edit_lines",
        "workspace_insert_text",
        "workspace_diff_file",
        "workspace_delete_file",
        "workspace_list_backups",
        "workspace_create_backup",
        "workspace_restore_backup",
        "workspace_import_book_source",
        "workspace_create_book_source_file",
        "workspace_debug_book_source",
        "workspace_apply_book_source"
    )

    private val nativeToolLabels = mapOf(
        "query_bookshelf" to appCtx.getString(R.string.ai_tool_query_bookshelf_books),
        "get_bookshelf_book_info" to appCtx.getString(R.string.ai_tool_read_book_details),
        "manage_bookshelf_group" to appCtx.getString(R.string.ai_tool_manage_bookshelf_groups),
        "manage_bookshelf_tag" to appCtx.getString(R.string.ai_tool_manage_bookshelf_tags),
        "set_bookshelf_book_group" to appCtx.getString(R.string.ai_tool_set_book_group),
        "set_bookshelf_book_tags" to appCtx.getString(R.string.ai_tool_set_book_tags),
        "query_read_records" to appCtx.getString(R.string.ai_tool_query_reading_records),
        "list_book_chapters" to appCtx.getString(R.string.ai_tool_read_chapter_list),
        "search_book_chapter_content" to appCtx.getString(R.string.ai_tool_search_chapter_content),
        "read_book_chapter_content" to appCtx.getString(R.string.ai_tool_read_chapter_content),
        "list_book_sources" to appCtx.getString(R.string.ai_tool_list_book_sources),
        "search_book_source" to appCtx.getString(R.string.ai_tool_search_book_source_content),
        "create_book_source" to appCtx.getString(R.string.ai_tool_create_book_source),
        "get_book_source" to appCtx.getString(R.string.ai_tool_read_book_source_details),
        "update_book_source" to appCtx.getString(R.string.ai_tool_update_book_source),
        "fetch_source_html" to appCtx.getString(R.string.ai_tool_fetch_page_source),
        "debug_book_source" to appCtx.getString(R.string.ai_tool_debug_book_source_rules),
        "reading_ajax" to appCtx.getString(R.string.ai_tool_reading_network_request),
        "reading_webview" to appCtx.getString(R.string.ai_tool_reading_webview),
        "capture_web_requests" to appCtx.getString(R.string.ai_tool_capture_network_requests),
        "search_web_tavily" to appCtx.getString(R.string.ai_tool_web_search),
        "generate_image" to appCtx.getString(R.string.ai_tool_generate_image),
        "list_book_characters" to appCtx.getString(R.string.ai_tool_read_character_profiles),
        "upsert_book_character" to appCtx.getString(R.string.ai_tool_add_or_update_character),
        "delete_book_character" to appCtx.getString(R.string.bchar_delete_character),
        "list_book_character_relations" to appCtx.getString(R.string.ai_tool_read_character_relationships),
        "upsert_book_character_relation" to appCtx.getString(R.string.ai_tool_add_or_update_character_relation),
        "delete_book_character_relation" to appCtx.getString(R.string.ai_tool_delete_character_relation),
        "list_ai_gallery_images" to appCtx.getString(R.string.ai_tool_read_ai_image_gallery),
        "set_book_character_avatar_from_gallery" to appCtx.getString(R.string.ai_tool_set_gallery_avatar_character),
        "generate_book_character_avatar" to appCtx.getString(R.string.bchar_generate_character_avatar),
        "list_speech_catalogs" to appCtx.getString(R.string.ai_tool_read_voice_catalog),
        "assign_character_speech_route" to appCtx.getString(R.string.ai_tool_set_character_voice),
        "batch_assign_character_speech_routes" to appCtx.getString(R.string.ai_tool_batch_assign_character_voices),
        "clear_character_speech_routes" to appCtx.getString(R.string.ai_tool_clear_character_voices),
        "list_speech_voice_groups" to appCtx.getString(R.string.ai_tool_read_speaker_groups),
        "upsert_speech_voice_group" to appCtx.getString(R.string.ai_tool_add_or_update_speaker_group),
        "delete_speech_voice_group" to appCtx.getString(R.string.ai_tool_delete_speaker_group),
        "list_read_aloud_bgm_catalog" to appCtx.getString(R.string.ai_tool_read_read_aloud_score_library),
        "assign_read_aloud_bgm_ranges" to appCtx.getString(R.string.ai_tool_assign_score_ranges),
        "list_world_books" to appCtx.getString(R.string.ai_tool_read_world_book),
        "upsert_world_book" to appCtx.getString(R.string.ai_tool_add_or_update_world_book),
        "delete_world_book" to appCtx.getString(R.string.ai_tool_delete_world_book),
        "upsert_world_book_entry" to appCtx.getString(R.string.ai_tool_add_or_update_world_book_2),
        "delete_world_book_entry" to appCtx.getString(R.string.ai_tool_delete_world_book_entry),
        "list_world_book_bindings" to appCtx.getString(R.string.ai_tool_read_enabled_world_books),
        "upsert_world_book_binding" to appCtx.getString(R.string.ai_tool_enable_world_book),
        "delete_world_book_binding" to appCtx.getString(R.string.ai_tool_disable_world_book),
        "import_world_book_json" to appCtx.getString(R.string.ai_tool_import_world_book_json),
        "export_world_book_json" to appCtx.getString(R.string.ai_tool_export_world_book_json),
        "get_app_settings" to appCtx.getString(R.string.ai_tool_read_app_settings),
        "set_app_setting" to appCtx.getString(R.string.ai_tool_change_app_settings),
        "set_app_settings_batch" to appCtx.getString(R.string.ai_tool_batch_change_settings),
        "workspace_list_files" to appCtx.getString(R.string.ai_tool_workspace_list_files),
        "workspace_read_file" to appCtx.getString(R.string.ai_tool_workspace_read_file),
        "workspace_read_lines" to appCtx.getString(R.string.ai_tool_workspace_read_lines),
        "workspace_read_matches" to appCtx.getString(R.string.ai_tool_workspace_regex_read),
        "workspace_search_files" to appCtx.getString(R.string.ai_tool_workspace_regex_search),
        "workspace_save_input_file" to appCtx.getString(R.string.ai_tool_workspace_save_input_as_file),
        "workspace_write_file" to appCtx.getString(R.string.ai_tool_workspace_write_file),
        "workspace_edit_file" to appCtx.getString(R.string.ai_tool_workspace_edit_file),
        "workspace_replace_text" to appCtx.getString(R.string.ai_tool_workspace_exact_replace),
        "workspace_replace_regex" to appCtx.getString(R.string.ai_tool_workspace_regex_replace),
        "workspace_edit_lines" to appCtx.getString(R.string.ai_tool_workspace_edit_by_line_number),
        "workspace_insert_text" to appCtx.getString(R.string.ai_tool_workspace_insert_text),
        "workspace_diff_file" to appCtx.getString(R.string.ai_tool_workspace_view_diff),
        "workspace_delete_file" to appCtx.getString(R.string.ai_tool_workspace_delete_file),
        "workspace_list_backups" to appCtx.getString(R.string.ai_tool_workspace_list_backups),
        "workspace_create_backup" to appCtx.getString(R.string.ai_tool_workspace_create_backup),
        "workspace_restore_backup" to appCtx.getString(R.string.ai_tool_workspace_restore_backup),
        "workspace_import_book_source" to appCtx.getString(R.string.ai_tool_workspace_import_book_source),
        "workspace_create_book_source_file" to appCtx.getString(R.string.ai_tool_workspace_create_book_source_file),
        "workspace_debug_book_source" to appCtx.getString(R.string.ai_tool_workspace_debug_book_source),
        "workspace_apply_book_source" to appCtx.getString(R.string.ai_tool_workspace_apply_book_source)
    )

    private val nativeToolGroups = mapOf(
        "query_bookshelf" to appCtx.getString(R.string.bookshelf),
        "get_bookshelf_book_info" to appCtx.getString(R.string.bookshelf),
        "manage_bookshelf_group" to appCtx.getString(R.string.bookshelf),
        "manage_bookshelf_tag" to appCtx.getString(R.string.bookshelf),
        "set_bookshelf_book_group" to appCtx.getString(R.string.bookshelf),
        "set_bookshelf_book_tags" to appCtx.getString(R.string.bookshelf),
        "query_read_records" to appCtx.getString(R.string.bookshelf),
        "list_book_chapters" to appCtx.getString(R.string.app_launcher_name),
        "search_book_chapter_content" to appCtx.getString(R.string.app_launcher_name),
        "read_book_chapter_content" to appCtx.getString(R.string.app_launcher_name),
        "list_book_sources" to appCtx.getString(R.string.book_source),
        "search_book_source" to appCtx.getString(R.string.book_source),
        "create_book_source" to appCtx.getString(R.string.book_source),
        "get_book_source" to appCtx.getString(R.string.book_source),
        "update_book_source" to appCtx.getString(R.string.book_source),
        "fetch_source_html" to appCtx.getString(R.string.book_source),
        "debug_book_source" to appCtx.getString(R.string.book_source),
        "reading_ajax" to appCtx.getString(R.string.ai_tool_reading_network),
        "reading_webview" to appCtx.getString(R.string.ai_tool_reading_network),
        "capture_web_requests" to appCtx.getString(R.string.ai_tool_reading_network),
        "search_web_tavily" to appCtx.getString(R.string.ai_tool_web_search),
        "generate_image" to appCtx.getString(R.string.ai_tool_ai_image),
        "list_book_characters" to appCtx.getString(R.string.bchar_character_profile),
        "upsert_book_character" to appCtx.getString(R.string.bchar_character_profile),
        "delete_book_character" to appCtx.getString(R.string.bchar_character_profile),
        "list_book_character_relations" to appCtx.getString(R.string.bchar_character_profile),
        "upsert_book_character_relation" to appCtx.getString(R.string.bchar_character_profile),
        "delete_book_character_relation" to appCtx.getString(R.string.bchar_character_profile),
        "list_ai_gallery_images" to appCtx.getString(R.string.ai_tool_ai_gallery),
        "set_book_character_avatar_from_gallery" to appCtx.getString(R.string.bchar_character_profile),
        "generate_book_character_avatar" to appCtx.getString(R.string.bchar_character_profile),
        "list_speech_catalogs" to appCtx.getString(R.string.bchar_character_voice),
        "assign_character_speech_route" to appCtx.getString(R.string.bchar_character_voice),
        "batch_assign_character_speech_routes" to appCtx.getString(R.string.bchar_character_voice),
        "clear_character_speech_routes" to appCtx.getString(R.string.bchar_character_voice),
        "list_speech_voice_groups" to appCtx.getString(R.string.bchar_character_voice),
        "upsert_speech_voice_group" to appCtx.getString(R.string.bchar_character_voice),
        "delete_speech_voice_group" to appCtx.getString(R.string.bchar_character_voice),
        "list_read_aloud_bgm_catalog" to appCtx.getString(R.string.ra_bgm),
        "assign_read_aloud_bgm_ranges" to appCtx.getString(R.string.ra_bgm),
        "list_world_books" to appCtx.getString(R.string.ai_tool_world_book),
        "upsert_world_book" to appCtx.getString(R.string.ai_tool_world_book),
        "delete_world_book" to appCtx.getString(R.string.ai_tool_world_book),
        "upsert_world_book_entry" to appCtx.getString(R.string.ai_tool_world_book),
        "delete_world_book_entry" to appCtx.getString(R.string.ai_tool_world_book),
        "list_world_book_bindings" to appCtx.getString(R.string.ai_tool_world_book),
        "upsert_world_book_binding" to appCtx.getString(R.string.ai_tool_world_book),
        "delete_world_book_binding" to appCtx.getString(R.string.ai_tool_world_book),
        "import_world_book_json" to appCtx.getString(R.string.ai_tool_world_book),
        "export_world_book_json" to appCtx.getString(R.string.ai_tool_world_book),
        "get_app_settings" to appCtx.getString(R.string.setting),
        "set_app_setting" to appCtx.getString(R.string.setting),
        "set_app_settings_batch" to appCtx.getString(R.string.setting),
        "workspace_list_files" to "AI workspace",
        "workspace_read_file" to "AI workspace",
        "workspace_read_lines" to "AI workspace",
        "workspace_read_matches" to "AI workspace",
        "workspace_search_files" to "AI workspace",
        "workspace_save_input_file" to "AI workspace",
        "workspace_write_file" to "AI workspace",
        "workspace_edit_file" to "AI workspace",
        "workspace_replace_text" to "AI workspace",
        "workspace_replace_regex" to "AI workspace",
        "workspace_edit_lines" to "AI workspace",
        "workspace_insert_text" to "AI workspace",
        "workspace_diff_file" to "AI workspace",
        "workspace_delete_file" to "AI workspace",
        "workspace_list_backups" to "AI workspace",
        "workspace_create_backup" to "AI workspace",
        "workspace_restore_backup" to "AI workspace",
        "workspace_import_book_source" to "AI workspace",
        "workspace_create_book_source_file" to "AI workspace",
        "workspace_debug_book_source" to "AI workspace",
        "workspace_apply_book_source" to "AI workspace"
    )

    fun groupLabelOfTool(name: String): String {
        return when {
            name.startsWith("mcp_") -> appCtx.getString(R.string.ai_tool_mcp_tools)
            else -> nativeToolGroups[name] ?: appCtx.getString(R.string.discover_group_other)
        }
    }

    fun displayNameOfTool(name: String): String {
        return when {
            name.startsWith("mcp_") -> name.removePrefix("mcp_").ifBlank { name }
            else -> nativeToolLabels[name] ?: name
        }
    }

    fun metaOfTool(name: String): ToolMeta {
        return ToolMeta(
            name = name,
            label = displayNameOfTool(name),
            group = groupLabelOfTool(name)
        )
    }

    fun isReadOnlyTool(name: String): Boolean {
        return name in readSafeToolNames ||
                name == "load_ai_skill" ||
                name.startsWith("search_") ||
                name.startsWith("list_") ||
                name.startsWith("get_") ||
                name.startsWith("query_") ||
                name.startsWith("read_") ||
                name.startsWith("fetch_")
    }

    private fun nativeResolvedTools(): List<AiResolvedTool> {
        val tools = AiBookshelfTool.resolvedTools().toMutableList()
        tools += AiLibraryTool.resolvedTools()
        tools += AiTavilyTool.resolvedTools()
        tools += AiBookSourceTool.resolvedTools()
        tools += AiReadingNetworkTool.resolvedTools()
        tools += AiSettingsTool.resolvedTools()
        tools += AiWorkspaceTool.resolvedTools()
        tools += AiImageTool.resolvedTools()
        tools += AiBookCharacterTool.resolvedTools()
        tools += AiReadAloudBgmTool.resolvedTools()
        tools += AiWorldBookTool.resolvedTools()
        return tools.distinctBy { it.name }
    }

    suspend fun resolveAllToolNamesForManage(): List<String> {
        val dynamic = mutableSetOf<String>()
        dynamic += nativeResolvedTools().map { it.name }
        dynamic += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers).map { it.name }
        dynamic += AppConfig.aiEnabledToolNames
        dynamic += defaultEnabledTools
        return dynamic.toList().sorted()
    }

    suspend fun resolveAvailableTools(includeMcp: Boolean = false): List<AiResolvedTool> {
        val tools = nativeResolvedTools().toMutableList()
        if (includeMcp) {
            tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
        }
        val enabled = effectiveEnabledToolNames()
        return tools
            .distinctBy { it.name }
            .filter { it.name in enabled }
    }

    fun effectiveEnabledToolNames(): Set<String> {
        val stored = AppConfig.aiEnabledToolNames
        if (AppConfig.aiEnabledToolNamesVersion < TOOL_SETTINGS_VERSION) {
            val additions = buildSet {
                if (AppConfig.aiEnabledToolNamesVersion < 2) addAll(version2AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 3) addAll(version3AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 4) addAll(version4AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 5) addAll(version5AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 6) addAll(version6AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 7) addAll(version7AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 8) addAll(version8AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 9) addAll(version9AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 10) addAll(version10AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 11) addAll(version11AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 13) addAll(version13AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 14) addAll(version14AddedDefaultTools)
                if (AppConfig.aiEnabledToolNamesVersion < 15) addAll(version15AddedDefaultTools)
            }
            val migrated = (stored.ifEmpty { defaultEnabledTools } + additions)
                .minus(if (AppConfig.aiEnabledToolNamesVersion < 12) version12RemovedDefaultTools else emptySet())
                .filter { it.isNotBlank() }
                .toSet()
            AppConfig.aiEnabledToolNames = migrated
            AppConfig.aiEnabledToolNamesVersion = TOOL_SETTINGS_VERSION
            return migrated
        }
        return stored.ifEmpty { defaultEnabledTools }
    }

    suspend fun resolveReadTools(includeMcp: Boolean = false): List<AiResolvedTool> {
        return when (AppConfig.aiReadToolMode) {
            AppConfig.AI_READ_TOOL_MODE_ALL -> resolveAllTools(includeMcp)
            AppConfig.AI_READ_TOOL_MODE_SAFE -> {
                val tools = nativeResolvedTools().toMutableList()
                if (includeMcp) {
                    tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
                }
                tools.distinctBy { it.name }.filter { it.name in readSafeToolNames }
            }
            else -> resolveAvailableTools(includeMcp)
        }
    }

    fun resolveNativeTools(names: Set<String>): List<AiResolvedTool> {
        return nativeResolvedTools()
            .filter { it.name in names }
    }

    suspend fun resolveMcpTools(serverIds: Set<String>): List<AiResolvedTool> {
        if (serverIds.isEmpty()) return emptyList()
        val servers = AppConfig.aiMcpServerList.filter { server ->
            server.enabled && server.id in serverIds
        }
        if (servers.isEmpty()) return emptyList()
        return AiMcpClient.resolveTools(servers)
    }

    suspend fun resolveAllTools(includeMcp: Boolean = false): List<AiResolvedTool> {
        val tools = nativeResolvedTools().toMutableList()
        if (includeMcp) {
            tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
        }
        return tools.distinctBy { it.name }
    }
}
