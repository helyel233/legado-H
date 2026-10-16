package io.legado.app.ui.host

import android.app.Activity
import android.content.Context
import android.content.res.XmlResourceParser
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.LauncherIconHelp
import io.legado.app.ui.host.pack.LegacyAppearanceImporter
import io.legado.app.uikit.v5.components.AppBottomSheet
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.RelaySettingsActivity
import io.legado.app.ui.main.my.MySettingsRowKind
import io.legado.app.ui.main.my.MySettingsRowModel
import io.legado.app.ui.main.my.MySettingsScreen
import io.legado.app.ui.main.my.MySettingsSectionModel
import io.legado.app.ui.main.my.MySettingsSubSearchItem
import io.legado.app.ui.main.my.MySettingsThemeOption
import io.legado.app.ui.main.my.MyWebServiceUiState
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.uikit.v5.components.AppMenuSheet
import androidx.compose.ui.res.stringResource
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
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import org.xmlpull.v1.XmlPullParser

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

    private companion object {
        /** 桌面图标默认值（与旧 ThemeConfigFragment 一致） */
        const val DEFAULT_LAUNCHER_ICON = "ic_launcher"
    }

    /**
     * 帮助请求桥（chrome() 无 context，经计数器回 Content 消费）。
     * 实例级：宿主 remember 缓存本实例，chrome()/Content() 共享；
     * 原 companion 静态计数在多开宿主/重建时会串味。
     */
    private var helpRevision by mutableIntStateOf(0)

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Res(R.string.v5_my),
        subtitle = DestTitle.Res(R.string.v5_my_subtitle),
        // MySettingsScreen 内部是 LazyColumn（自带滚动），宿主不再包 verticalScroll
        contentOwnsScroll = true,
        actions = listOf(
            io.legado.app.uikit.v5.contract.ChromeAction(
                id = "help",
                icon = DestIcon.Vector(HostIcons.Star),
                contentDescription = DestTitle.Res(R.string.v5_help),
                onClick = { helpRevision++ }, // 对齐 1.7.3 menu_help → appHelp
            ),
        ),
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current
        val colors = LocalSemanticColors.current
        val visual = LocalVisualSpec.current
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
        var iconSheet by remember { mutableStateOf(false) }
        var legacyImportProposal by remember { mutableStateOf<LegacyAppearanceImporter.Proposal?>(null) }
        // 界面分区的开关态与桌面图标当前值
        var immersiveManageBar by remember { mutableStateOf(AppConfig.immersiveManageBar) }
        var launcherIcon by remember {
            mutableStateOf(context.getPrefString(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON) ?: DEFAULT_LAUNCHER_ICON)
        }
        val iconNames = remember { context.resources.getStringArray(R.array.icon_names).toList() }
        val iconValues = remember { context.resources.getStringArray(R.array.icons).toList() }

        val themeOptions = remember {
            buildThemeOptions(context)
        }
        val sections = remember { buildSections(context) }
        val subSearchItems = remember { buildSubSearchItems(context) }

        // 帮助（chrome 顶栏动作 → appHelp 文档，与 1.7.3 MyFragment.menu_help 一致）
        LaunchedEffect(helpRevision) {
            if (helpRevision > 0) {
                (context as? AppCompatActivity)?.showHelp("appHelp")
            }
        }

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

        // 服务运行态实时刷新（#27-2：此前只读一次，服务异常退出后状态陈旧）
        val webServiceEvent by io.legado.app.utils.eventObservableAsState<String>(EventBus.WEB_SERVICE, "")
        LaunchedEffect(webServiceEvent) { refreshWebService() }
        val autoTaskEvent by io.legado.app.utils.eventObservableAsState<String>(EventBus.AUTO_TASK_SERVICE, "")
        LaunchedEffect(autoTaskEvent) { refreshAutoTask() }

        remember { refreshWebService(); refreshAutoTask(); true }

        val modeLabel = themeOptions.firstOrNull { it.value == themeMode }?.label ?: "主题模式"

        MySettingsScreen(
            sections = sections,
            subSearchItems = subSearchItems,
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
                    HostSettingsActivity.start(context, target.ownerConfigTag, target.key)
                    return@MySettingsScreen
                }
                when (key) {
                    // 站内：新骨架路由
                    "appearanceKit" -> scope.navigate("appearance")
                    "museum" -> scope.navigate("museum")
                    "legacyImport" -> legacyImportProposal = LegacyAppearanceImporter.read(context)
                    // 站外：功能保全，旧 Activity 照常打开
                    "bookSourceManage" -> HostBookSourceManageActivity.start(context)
                    "rssSourceManage" -> HostRssSourceManageActivity.start(context)
                    "replaceManage" -> HostReplaceRuleManageActivity.start(context)
                    "dictRuleManage" -> HostDictRuleManageActivity.start(context)
                    "txtTocRuleManage" -> HostTxtTocRuleManageActivity.start(context)
                    "autoTaskManage" -> context.startActivity<AutoTaskActivity>()
                    "bookmark" -> HostBookmarkActivity.start(context)
                    "setting" -> HostSettingsActivity.start(context, ConfigTag.OTHER_CONFIG)
                    "web_dav_setting" -> HostBackupActivity.start(context)
                    "publicWebRelay" -> context.startActivity<RelaySettingsActivity>()
                    "cacheManage" -> HostCacheManageActivity.start(context)
                    "sourceRecycleBin" -> context.startActivity<HostSourceRecycleBinActivity>()
                    // 界面：各 UI 管理页直达（原「主题设置」页已拆散）
                    "topBarManage" -> context.startActivity<io.legado.app.ui.config.TopBarManageActivity>()
                    "navigationBarManage" ->
                        context.startActivity<io.legado.app.ui.config.NavigationBarManageActivity>()
                    "bubbleManage" -> context.startActivity<io.legado.app.ui.config.BubbleManageActivity>()
                    "readMenuButton" ->
                        context.startActivity<io.legado.app.ui.book.read.config.ReadMenuButtonManageActivity>()
                    "epubLoadingTemplate" ->
                        context.startActivity<io.legado.app.ui.config.EpubLoadingTemplateManageActivity>()
                    "coverConfig" -> HostSettingsActivity.start(context, ConfigTag.COVER_CONFIG)
                    "launcherIcon" -> iconSheet = true
                    "ai_setting" -> HostSettingsActivity.start(context, ConfigTag.AI_CONFIG)
                    "fileManage" -> HostFileManageActivity.start(context)
                    "readRecord" -> HostReadRecordActivity.start(context)
                    "about" -> HostAboutActivity.start(context)
                    "exit" -> (context as? Activity)?.finish()
                }
            },
            switchStates = mapOf(PreferKey.immersiveManageBar to immersiveManageBar),
            onSwitchChange = { key, checked ->
                when (key) {
                    PreferKey.immersiveManageBar -> {
                        context.putPrefBoolean(PreferKey.immersiveManageBar, checked)
                        immersiveManageBar = checked
                    }
                }
            },
        )

        legacyImportProposal?.let { proposal ->
            AppBottomSheet(onDismiss = { legacyImportProposal = null }) {
                Column(Modifier.padding(scope.density.screenPadding)) {
                    Text(
                        stringResource(R.string.v5_import_from_old),
                        style = visual.type.title,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(scope.density.blockGap / 2))
                    Text(
                        stringResource(R.string.v5_import_from_old_desc),
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(scope.density.blockGap))
                    proposal.summary.forEach { (k, v) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                k,
                                style = visual.type.body,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(v, style = visual.type.label, color = colors.onSurface)
                        }
                    }
                    Spacer(Modifier.height(scope.density.blockGap))
                    // 导入（落库并应用）
                    AppFilledButton(
                        text = stringResource(R.string.v5_import_apply),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LegacyAppearanceImporter.commit(context, proposal)
                        legacyImportProposal = null
                        context.toastOnUi(R.string.v5_import_done)
                    }
                    Spacer(Modifier.height(scope.density.blockGap / 2))
                    // 放弃
                    AppGhostButton(
                        text = stringResource(R.string.v5_give_up),
                        modifier = Modifier.fillMaxWidth(),
                    ) { legacyImportProposal = null }
                }
            }
        }

        if (themeSheet) {
            AppMenuSheet(
                title = stringResource(R.string.v5_theme_mode),
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
        if (iconSheet) {
            AppFilterDialog(
                title = stringResource(R.string.change_icon),
                options = iconNames,
                selectedIndex = iconValues.indexOf(launcherIcon).coerceAtLeast(0),
                onSelect = { index ->
                    val value = iconValues.getOrNull(index) ?: return@AppFilterDialog
                    iconSheet = false
                    launcherIcon = value
                    context.putPrefString(PreferKey.launcherIcon, value)
                    LauncherIconHelp.changeIcon(value)
                },
                onDismiss = { iconSheet = false },
            )
        }
        if (webSheet) {
            val url = WebService.hostAddress
            AppMenuSheet(
                title = stringResource(R.string.v5_web_service),
                actions = listOf(
                    stringResource(R.string.v5_copy_url) to { context.sendToClip(url) },
                    stringResource(R.string.v5_open_browser) to { context.openUrl(url) },
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
                rows = listOfNotNull(
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
                    // P5-2 一次性通道：旧版本外观设置 → V5 双包（仅检测有旧设置时显示）
                    if (LegacyAppearanceImporter.hasLegacyAppearance(context)) {
                        MySettingsRowModel(
                            key = "legacyImport",
                            title = context.getString(R.string.v5_import_from_old_row),
                            summary = context.getString(R.string.v5_import_from_old_row_desc),
                        )
                    } else {
                        null
                    }
                ),
            ),
            // 界面：旧「主题设置」一页打包所有 UI 入口，已拆成下面这些直达项
            // （已删除：主题管理—由 App UI 配置接管；主界面沉浸状态栏—V5 恒沉浸，开关失效）
            MySettingsSectionModel(
                title = context.getString(R.string.interface_setting),
                rows = listOf(
                    actionRow("topBarManage", R.string.top_bar_manage, R.string.top_bar_manage_summary),
                    actionRow(
                        "navigationBarManage",
                        R.string.navigation_bar_manage,
                        R.string.navigation_bar_manage_summary
                    ),
                    actionRow("bubbleManage", R.string.bubble_manage, R.string.bubble_manage_summary),
                    actionRow(
                        "readMenuButton",
                        R.string.read_menu_button_manage,
                        R.string.read_menu_button_summary
                    ),
                    actionRow(
                        "epubLoadingTemplate",
                        R.string.epub_loading_templates,
                        R.string.epub_loading_templates_summary
                    ),
                    actionRow("coverConfig", R.string.cover_config, R.string.cover_config_summary),
                    actionRow("launcherIcon", R.string.change_icon, R.string.change_icon_summary),
                    MySettingsRowModel(
                        key = PreferKey.immersiveManageBar,
                        title = context.getString(R.string.manage_bar_immersion),
                        summary = context.getString(R.string.manage_bar_immersion_summary),
                        kind = MySettingsRowKind.Switch,
                    ),
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

    /** 设置搜索索引：各设置页 pref XML 解析成可搜条目（与 MyFragment.buildSubSearchItems 一致）。 */
    private fun buildSubSearchItems(context: Context): List<MySettingsSubSearchItem> {
        // 清理：pref_config_theme 各项目前已是「界面」分区的直达行，不再进索引；
        // pref_config_welcome（V4 旧日夜主题体系，已由 V5 主题包接管）
        // 与 pref_config_discovery_subscription（modernDiscoveryMode/modernRssMode 已无引用）整页作废。
        return listOf(
            Triple("coverConfig", R.xml.pref_config_cover, ConfigTag.COVER_CONFIG),
            Triple("web_dav_setting", R.xml.pref_config_backup, ConfigTag.BACKUP_CONFIG),
            Triple("ai_setting", R.xml.pref_config_ai, ConfigTag.AI_CONFIG),
            Triple("setting", R.xml.pref_config_other, ConfigTag.OTHER_CONFIG),
            Triple("setting", R.xml.pref_config_network_dns, ConfigTag.NETWORK_DNS_CONFIG),
        ).flatMap { (ownerKey, xmlRes, ownerConfigTag) ->
            buildPreferenceXmlSearchItems(context, ownerKey, xmlRes, ownerConfigTag)
        }
    }

    private fun buildPreferenceXmlSearchItems(
        context: Context,
        ownerKey: String,
        xmlRes: Int,
        ownerConfigTag: String
    ): List<MySettingsSubSearchItem> {
        val items = ArrayList<MySettingsSubSearchItem>()
        val parser: XmlResourceParser = context.resources.getXml(xmlRes)
        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val title = collectPreferenceAttr(context, parser, "title").orEmpty()
                    val key = collectPreferenceAttr(context, parser, "key").orEmpty()
                    if (title.isNotBlank() && key.isNotBlank()) {
                        items.add(
                            MySettingsSubSearchItem(
                                ownerKey = ownerKey,
                                title = title,
                                summary = collectPreferenceAttr(context, parser, "summary").orEmpty(),
                                key = key.removePrefix("search_jump_"),
                                ownerConfigTag = ownerConfigTag
                            )
                        )
                    }
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }
        return items
    }

    private fun collectPreferenceAttr(context: Context, parser: XmlResourceParser, attrName: String): String? {
        val namespace = "http://schemas.android.com/apk/res/android"
        val attrValue = parser.getAttributeValue(namespace, attrName)?.trim().orEmpty()
        if (attrValue.isBlank()) return null
        val attrRes = parser.getAttributeResourceValue(namespace, attrName, 0)
        return if (attrRes != 0) {
            runCatching {
                context.getString(attrRes)
            }.getOrNull()?.trim().orEmpty().takeIf { it.isNotBlank() }
        } else {
            attrValue.removePrefix("@").takeIf { it.isNotBlank() }
        }
    }
}
