package io.legado.app.ui.host

import android.app.Activity
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.V5ThemeSync
import io.legado.app.model.AutoTask
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.RelaySettingsActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.main.my.MySettingsRowKind
import io.legado.app.ui.main.my.MySettingsRowModel
import io.legado.app.ui.main.my.MySettingsScreen
import io.legado.app.ui.main.my.MySettingsSectionModel
import io.legado.app.ui.main.my.MySettingsThemeOption
import io.legado.app.ui.main.my.MyWebServiceUiState
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.source.recycle.SourceRecycleBinActivity
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.resolve.ThemeResolver
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity

/**
 * P4-1「我的」页真业务（第一批搬迁）。
 *
 * 复用已 Compose 化的 [MySettingsScreen]（473 行，零改动），只重接回调层：
 * - 站内目的地（管理中心 / 组件博物馆）走 [ContentScope.navigate] —— 新骨架内路由；
 * - 站外页面走 startActivity —— 功能保全（手册 0.3 询问制）；
 * - 主题模式选择改用新组件 [AppMenuSheet]；
 * - 主题包切换经 V5ThemeSync 同步旧栈 —— 本页的旧 palette 立即跟随新主题包。
 */
class HostMyContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("我的"),
        subtitle = DestTitle.Text("设置与个人数据"),
        // MySettingsScreen 内部是 LazyColumn（自带滚动），宿主不再包 verticalScroll
        contentOwnsScroll = true,
        actions = listOf(
            io.legado.app.uikit.v5.contract.ChromeAction(
                id = "help",
                icon = DestIcon.Vector(HostIcons.Star),
                contentDescription = DestTitle.Text("帮助"),
                onClick = {}, // P4 后续接 showHelp
            ),
        ),
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current
        var searchQuery by remember { mutableStateOf("") }
        var themeMode by remember { mutableStateOf(context.getPrefString(PreferKey.themeMode, "0") ?: "0") }
        var webServiceState by remember {
            mutableStateOf(MyWebServiceUiState(checked = false, summary = ""))
        }
        var autoTaskState by remember {
            mutableStateOf(MyWebServiceUiState(checked = false, summary = ""))
        }
        var themeSheet by remember { mutableStateOf(false) }
        var webSheet by remember { mutableStateOf(false) }

        val themeOptions = remember {
            buildThemeOptions(context)
        }
        val sections = remember { buildSections(context) }

        fun refreshWebService() {
            webServiceState = MyWebServiceUiState(
                checked = WebService.isRun,
                summary = if (WebService.isRun) WebService.hostAddress
                else context.getString(R.string.web_service_desc),
            )
        }

        fun refreshAutoTask() {
            val enabled = context.getPrefBoolean(PreferKey.autoTaskService)
            autoTaskState = MyWebServiceUiState(
                checked = enabled,
                summary = context.getString(R.string.auto_task_service_desc),
            )
        }

        remember { refreshWebService(); refreshAutoTask(); true }

        val modeLabel = themeOptions.firstOrNull { it.value == themeMode }?.label ?: "主题模式"

        MySettingsScreen(
            sections = sections,
            subSearchItems = emptyList(),
            searchQuery = searchQuery,
            themeModeLabel = modeLabel,
            webServiceState = webServiceState,
            autoTaskServiceState = autoTaskState,
            onThemeModeClick = { themeSheet = true },
            onWebServiceCheckedChange = { enabled ->
                context.putPrefBoolean(PreferKey.webService, enabled)
                if (enabled) WebService.start(context) else WebService.stop(context)
                refreshWebService()
            },
            onWebServiceClick = {
                if (WebService.isRun) webSheet = true
                else {
                    context.putPrefBoolean(PreferKey.webService, true)
                    WebService.start(context)
                    refreshWebService()
                }
            },
            onAutoTaskServiceCheckedChange = { enabled ->
                context.putPrefBoolean(PreferKey.autoTaskService, enabled)
                if (enabled) AutoTask.start(context) else AutoTask.stop(context)
                refreshAutoTask()
            },
            onAutoTaskServiceClick = {
                val enabled = !context.getPrefBoolean(PreferKey.autoTaskService)
                context.putPrefBoolean(PreferKey.autoTaskService, enabled)
                if (enabled) AutoTask.start(context) else AutoTask.stop(context)
                refreshAutoTask()
            },
            onRowClick = { key, target ->
                if (target != null) {
                    context.startActivity<ConfigActivity> {
                        putExtra("configTag", target.ownerConfigTag)
                        putExtra("targetKey", target.key)
                    }
                    return@MySettingsScreen
                }
                when (key) {
                    // 站内：新骨架路由
                    "appearanceKit" -> scope.navigate("appearance")
                    "museum" -> scope.navigate("museum")
                    // 站外：功能保全，旧 Activity 照常打开
                    "bookSourceManage" -> context.startActivity<BookSourceActivity>()
                    "rssSourceManage" -> context.startActivity<RssSourceActivity>()
                    "replaceManage" -> context.startActivity<ReplaceRuleActivity>()
                    "dictRuleManage" -> context.startActivity<DictRuleActivity>()
                    "txtTocRuleManage" -> context.startActivity<TxtTocRuleActivity>()
                    "autoTaskManage" -> context.startActivity<AutoTaskActivity>()
                    "bookmark" -> context.startActivity<AllBookmarkActivity>()
                    "setting" -> context.startActivity<ConfigActivity> {
                        putExtra("configTag", ConfigTag.OTHER_CONFIG)
                    }
                    "web_dav_setting" -> context.startActivity<ConfigActivity> {
                        putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                    }
                    "publicWebRelay" -> context.startActivity<RelaySettingsActivity>()
                    "cacheManage" -> context.startActivity<CacheManageActivity>()
                    "sourceRecycleBin" -> context.startActivity<SourceRecycleBinActivity>()
                    "theme_setting" -> context.startActivity<ConfigActivity> {
                        putExtra("configTag", ConfigTag.THEME_CONFIG)
                    }
                    "ai_setting" -> context.startActivity<ConfigActivity> {
                        putExtra("configTag", ConfigTag.AI_CONFIG)
                    }
                    "fileManage" -> context.startActivity<FileManageActivity>()
                    "readRecord" -> context.startActivity<ReadRecordActivity>()
                    "about" -> context.startActivity<AboutActivity>()
                    "exit" -> (context as? Activity)?.finish()
                }
            },
        )

        if (themeSheet) {
            AppMenuSheet(
                title = "主题模式",
                actions = themeOptions.map { opt ->
                    (if (opt.value == themeMode) "✓ " else "") + opt.label to {
                        context.putPrefString(PreferKey.themeMode, opt.value)
                        themeMode = opt.value
                        ThemeConfig.applyDayNight(context)
                        // 日夜切换同步 V5 引擎（旧栈 applyDayNight 只喂旧 Applicator）
                        ThemeResolver.applyDark(AppConfig.isNightTheme)
                        V5ThemeSync.pushToLegacy(context, io.legado.app.ui.host.pack.V5PackageStore.activeTheme())
                    }
                },
                onDismiss = { themeSheet = false },
            )
        }
        if (webSheet) {
            val url = WebService.hostAddress
            AppMenuSheet(
                title = "Web 服务",
                actions = listOf(
                    "复制地址" to { context.sendToClip(url) },
                    "浏览器打开" to { context.openUrl(url) },
                ),
                onDismiss = { webSheet = false },
            )
        }
    }

    /** 与 MyFragment.buildSections 一致；appearanceKit 行在新壳内走站内路由。 */
    private fun buildSections(context: Context): List<MySettingsSectionModel> {
        fun actionRow(key: String, titleRes: Int, summaryRes: Int?, danger: Boolean = false) =
            MySettingsRowModel(
                key = key,
                title = context.getString(titleRes),
                summary = summaryRes?.let { context.getString(it) },
                danger = danger,
            )
        return listOf(
            MySettingsSectionModel(
                title = context.getString(R.string.config_category_content),
                rows = listOf(
                    actionRow("bookSourceManage", R.string.book_source_manage, R.string.book_source_manage_desc),
                    actionRow("rssSourceManage", R.string.rss_source_manage, R.string.rss_source_manage_summary),
                    actionRow("txtTocRuleManage", R.string.txt_toc_rule, R.string.config_txt_toc_rule),
                    actionRow("replaceManage", R.string.replace_purify, R.string.replace_purify_desc),
                    actionRow("dictRuleManage", R.string.dict_rule, R.string.config_dict_rule),
                ),
            ),
            MySettingsSectionModel(
                title = context.getString(R.string.config_category_appearance),
                rows = listOf(
                    MySettingsRowModel(
                        key = PreferKey.themeMode,
                        title = context.getString(R.string.theme_mode),
                        summary = context.getString(R.string.theme_mode_desc),
                        kind = MySettingsRowKind.ThemeMode,
                    ),
                    MySettingsRowModel(
                        key = "appearanceKit",
                        title = context.getString(R.string.app_ui_config),
                        summary = context.getString(R.string.app_ui_config_summary),
                    ),
                    actionRow("theme_setting", R.string.theme_setting, R.string.theme_setting_s),
                ),
            ),
            MySettingsSectionModel(
                title = context.getString(R.string.ai_setting),
                rows = listOf(
                    actionRow("ai_setting", R.string.ai_setting, R.string.ai_setting_summary),
                ),
            ),
            MySettingsSectionModel(
                title = context.getString(R.string.config_category_sync),
                rows = listOf(
                    actionRow("web_dav_setting", R.string.backup_restore, R.string.web_dav_set_import_old),
                    actionRow("cacheManage", R.string.cache_manage_title, R.string.cache_manage_summary),
                    actionRow("sourceRecycleBin", R.string.source_recycle_bin, R.string.source_recycle_bin_summary),
                    actionRow("publicWebRelay", R.string.public_web_relay, R.string.public_web_relay_summary),
                    MySettingsRowModel(
                        key = PreferKey.webService,
                        title = context.getString(R.string.web_service),
                        summary = context.getString(R.string.web_service_desc),
                        kind = MySettingsRowKind.WebService,
                    ),
                ),
            ),
            MySettingsSectionModel(
                title = context.getString(R.string.auto_task_group),
                rows = listOf(
                    MySettingsRowModel(
                        key = PreferKey.autoTaskService,
                        title = context.getString(R.string.auto_task_service),
                        summary = context.getString(R.string.auto_task_service_desc),
                        kind = MySettingsRowKind.AutoTaskService,
                    ),
                    actionRow("autoTaskManage", R.string.auto_task_manage, R.string.auto_task_manage_desc),
                ),
            ),
            MySettingsSectionModel(
                title = context.getString(R.string.config_category_tools),
                rows = listOf(
                    actionRow("setting", R.string.other_setting, R.string.other_setting_s),
                    actionRow("bookmark", R.string.bookmark, R.string.all_bookmark),
                    actionRow("readRecord", R.string.read_record, R.string.read_record_summary),
                    actionRow("fileManage", R.string.file_manage, R.string.file_manage_summary),
                    actionRow("about", R.string.about, null),
                    actionRow("exit", R.string.exit, null, danger = true),
                ),
            ),
        )
    }

    private fun buildThemeOptions(context: Context): List<MySettingsThemeOption> {
        val labels = context.resources.getStringArray(R.array.theme_mode)
        val values = context.resources.getStringArray(R.array.theme_mode_v)
        return labels.mapIndexed { index, label ->
            MySettingsThemeOption(
                value = values.getOrElse(index) { index.toString() },
                label = label,
            )
        }.ifEmpty { listOf(MySettingsThemeOption("0", context.getString(R.string.theme_mode))) }
    }
}
