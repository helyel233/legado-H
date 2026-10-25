package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.content.res.XmlResourceParser
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.help.LocalNetworkAccess
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.AutoTask
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.AppearanceKitActivity
import io.legado.app.ui.config.RelaySettingsActivity
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.source.recycle.SourceRecycleBinActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.LogUtils
import org.xmlpull.v1.XmlPullParser

class MyFragment() : BaseFragment(R.layout.fragment_my_config),
    MainFragmentInterface,
    SharedPreferences.OnSharedPreferenceChangeListener {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)
    private val settingsSearchView by lazy(LazyThreadSafetyMode.NONE) {
        binding.root.findViewById<SearchView>(R.id.search_view)
    }
    private val searchQueryState = mutableStateOf("")
    private val themeModeState = mutableStateOf("0")
    private val webServiceState = mutableStateOf(
        MyWebServiceUiState(checked = false, summary = "")
    )
    private val autoTaskServiceState = mutableStateOf(
        MyWebServiceUiState(checked = false, summary = "")
    )
    private val sections by lazy(LazyThreadSafetyMode.NONE) { buildSections() }
    private val themeOptions by lazy(LazyThreadSafetyMode.NONE) { buildThemeOptions() }
    private val subSearchItems by lazy(LazyThreadSafetyMode.NONE) { buildSubSearchItems() }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initSearchView()
        applySearchBarStyle()
        installComposeContent()
        updateSettingsState()
    }

    override fun observeLiveBus() {
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            updateWebServiceState()
        }
        observeEventSticky<String>(EventBus.AUTO_TASK_SERVICE) {
            updateAutoTaskServiceState()
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateSettingsState()
    }

    override fun onPause() {
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_my, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_help -> showHelp("appHelp")
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.webService -> {
                if (requireContext().getPrefBoolean(PreferKey.webService)) {
                    startWebService()
                } else {
                    WebService.stop(requireContext())
                    updateWebServiceState()
                }
            }

            PreferKey.themeMode -> {
                themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
            }

            PreferKey.autoTaskService -> updateAutoTaskServiceState()

            "recordLog" -> LogUtils.upLevel()
        }
    }

    private fun installComposeContent() {
        binding.preFragment.removeAllViews()
        val composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MySettingsScreen(
                    sections = sections,
                    subSearchItems = subSearchItems,
                    searchQuery = searchQueryState.value,
                    themeModeLabel = currentThemeModeLabel(),
                    webServiceState = webServiceState.value,
                    autoTaskServiceState = autoTaskServiceState.value,
                    onThemeModeClick = ::showThemeModeActions,
                    onWebServiceCheckedChange = ::setWebServiceEnabled,
                    onWebServiceClick = ::handleWebServiceClick,
                    onAutoTaskServiceCheckedChange = ::setAutoTaskServiceEnabled,
                    onAutoTaskServiceClick = ::handleAutoTaskServiceClick,
                    onRowClick = ::handleRowClick
                )
            }
        }
        binding.preFragment.addView(composeView)
    }

    private fun initSearchView() {
        settingsSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applySearchQuery(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                applySearchQuery(newText)
                return true
            }
        })
        settingsSearchView.setOnCloseListener {
            applySearchQuery("")
            false
        }
    }

    private fun applySearchQuery(query: String?) {
        searchQueryState.value = query?.trim().orEmpty()
    }

    private fun applySearchBarStyle() {
        settingsSearchView.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        TopBarSearchStyle.apply(settingsSearchView)
    }

    private fun updateSettingsState() {
        themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
        updateWebServiceState()
        updateAutoTaskServiceState()
    }

    private fun updateWebServiceState() {
        webServiceState.value = MyWebServiceUiState(
            checked = WebService.isRun,
            summary = if (WebService.isRun) {
                WebService.hostAddress
            } else {
                getString(R.string.web_service_desc)
            }
        )
    }

    private fun updateAutoTaskServiceState() {
        val enabled = requireContext().getPrefBoolean(PreferKey.autoTaskService)
        autoTaskServiceState.value = MyWebServiceUiState(
            checked = enabled,
            summary = getString(R.string.auto_task_service_desc)
        )
    }

    private fun setThemeMode(value: String) {
        requireContext().putPrefString(PreferKey.themeMode, value)
        themeModeState.value = value
        view?.post {
            //延迟到下一帧执行时 Fragment 可能已分离，requireContext 会抛 IllegalStateException
            val context = context ?: return@post
            ThemeConfig.applyDayNight(context)
        }
    }

    private fun currentThemeModeLabel(): String {
        return themeOptions.firstOrNull { it.value == themeModeState.value }?.label
            ?: themeOptions.firstOrNull()?.label
            ?: getString(R.string.theme_mode)
    }

    private fun showThemeModeActions() {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.theme_mode),
                labels = themeOptions.map { option ->
                    if (option.value == themeModeState.value) {
                        "${option.label}  ✓"
                    } else {
                        option.label
                    }
                },
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    themeOptions.getOrNull(index)?.value?.let(::setThemeMode)
                }
            )
        )
    }

    private fun setWebServiceEnabled(enabled: Boolean) {
        requireContext().putPrefBoolean(PreferKey.webService, enabled)
        if (enabled) {
            startWebService()
        } else {
            WebService.stop(requireContext())
            updateWebServiceState()
        }
    }

    /**
     * Android 17 起访问局域网需要先授权，未授权时 Web 服务只能被本机访问，
     * 因此开启前先申请，授权成功再启动。
     */
    private fun startWebService() {
        LocalNetworkAccess.request(
            requireContext(),
            onGranted = {
                WebService.start(requireContext())
                updateWebServiceState()
            },
            // 拒绝授权时服务没起来，也要刷新一次，避免开关显示成已开启却没运行
            onDenied = { updateWebServiceState() }
        )
    }

    private fun handleWebServiceClick() {
        if (WebService.isRun) {
            showWebServiceActions()
        } else {
            setWebServiceEnabled(true)
        }
    }

    private fun setAutoTaskServiceEnabled(enabled: Boolean) {
        requireContext().putPrefBoolean(PreferKey.autoTaskService, enabled)
        if (enabled) {
            AutoTask.start(requireContext())
        } else {
            AutoTask.stop(requireContext())
        }
        updateAutoTaskServiceState()
    }

    private fun handleAutoTaskServiceClick() {
        setAutoTaskServiceEnabled(!requireContext().getPrefBoolean(PreferKey.autoTaskService))
    }

    private fun showWebServiceActions() {
        val url = WebService.hostAddress.takeIf { WebService.isRun } ?: return
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.web_service),
                labels = listOf(
                    getString(R.string.copy_text),
                    getString(R.string.open_in_browser)
                ),
                descriptions = listOf(url, url),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    when (index) {
                        0 -> requireContext().sendToClip(url)
                        1 -> requireContext().openUrl(url)
                    }
                }
            )
        )
    }

    private fun handleRowClick(key: String, searchTarget: MySettingsSubSearchItem?) {
        if (searchTarget != null) {
            startActivity<ConfigActivity> {
                putExtra("configTag", searchTarget.ownerConfigTag)
                putExtra("targetKey", searchTarget.key)
            }
            return
        }
        when (key) {
            "bookSourceManage" -> startActivity<BookSourceActivity>()
            "rssSourceManage" -> startActivity<RssSourceActivity>()
            "replaceManage" -> startActivity<ReplaceRuleActivity>()
            "dictRuleManage" -> startActivity<DictRuleActivity>()
            "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
            "autoTaskManage" -> startActivity<AutoTaskActivity>()
            "bookmark" -> startActivity<AllBookmarkActivity>()
            // 一级行里凡是直接用 ConfigTag 常量做 key 的，都是二级设置页直达入口，
            // 跳转目标就是它自己，不需要再写一遍 mapping
            ConfigTag.READING_CONFIG,
            ConfigTag.NETWORK_CONFIG,
            ConfigTag.STORAGE_CONFIG,
            ConfigTag.ADVANCED_CONFIG,
            ConfigTag.COVER_CONFIG,
            ConfigTag.WELCOME_CONFIG -> startActivity<ConfigActivity> {
                putExtra("configTag", key)
            }

            "web_dav_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.BACKUP_CONFIG)
            }

            "publicWebRelay" -> startActivity<RelaySettingsActivity>()

            "cacheManage" -> startActivity<CacheManageActivity>()
            "sourceRecycleBin" -> startActivity<SourceRecycleBinActivity>()
            "theme_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.THEME_CONFIG)
            }

            "appearanceKit" -> startActivity<AppearanceKitActivity>()

            "ai_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.AI_CONFIG)
            }

            "fileManage" -> startActivity<FileManageActivity>()
            "readRecord" -> startActivity<ReadRecordActivity>()
            "about" -> startActivity<AboutActivity>()
            "exit" -> activity?.finish()
        }
    }

    /**
     * 「我的」页的一级分类。
     *
     * 排布原则：按**用户的任务域**划分（内容的来龙去脉 → 怎么读 → 长什么样 →
     * 智能与自动化 → 网络与同步 → 本机数据 → 兜底高级项），而不是按代码模块。
     * 顺序是「日常常用在前，少见维护在后」，每区 3~6 项，避免一项独占一区。
     *
     * 关键约定 1：**行的 key 就是搜索深链的 ownerKey**，改 key 会连带断开
     * [buildSubSearchItems] 里子项到一级行的挂载，两者必须同时改。
     * 关键约定 2：一级行尽量用 [ConfigTag] 常量做 key，跳转、搜索、层级三者自动对齐。
     */
    private fun buildSections(): List<MySettingsSectionModel> {
        return listOf(
            MySettingsSectionModel(
                title = getString(R.string.config_category_content),
                rows = listOf(
                    actionRow(
                        "bookSourceManage", R.string.book_source_manage,
                        R.string.book_source_manage_desc, R.drawable.ic_cfg_source
                    ),
                    actionRow(
                        "rssSourceManage", R.string.rss_source_manage,
                        R.string.rss_source_manage_summary, R.drawable.ic_bottom_rss_feed
                    ),
                    actionRow(
                        "replaceManage", R.string.replace_purify,
                        R.string.replace_purify_desc, R.drawable.ic_cfg_replace
                    ),
                    actionRow(
                        "txtTocRuleManage", R.string.txt_toc_rule,
                        R.string.config_txt_toc_rule, R.drawable.ic_toc
                    ),
                    actionRow(
                        "dictRuleManage", R.string.dict_rule,
                        R.string.config_dict_rule, R.drawable.ic_translate
                    ),
                    // 回收站装的是删掉的书源/订阅源，属于内容域而不是本机存储
                    actionRow(
                        "sourceRecycleBin", R.string.source_recycle_bin,
                        R.string.source_recycle_bin_summary, R.drawable.ic_restore
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_reading),
                rows = listOf(
                    actionRow(
                        ConfigTag.READING_CONFIG, R.string.reading_setting,
                        R.string.reading_setting_s, R.drawable.ic_book_has
                    ),
                    actionRow(
                        "bookmark", R.string.bookmark,
                        R.string.all_bookmark, R.drawable.ic_bookmark
                    ),
                    actionRow(
                        "readRecord", R.string.read_record,
                        R.string.read_record_summary, R.drawable.ic_bottom_read_record
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_appearance),
                rows = listOf(
                    MySettingsRowModel(
                        key = PreferKey.themeMode,
                        title = getString(R.string.theme_mode),
                        summary = getString(R.string.theme_mode_desc),
                        kind = MySettingsRowKind.ThemeMode,
                        iconRes = R.drawable.ic_daytime
                    ),
                    MySettingsRowModel(
                        key = "appearanceKit",
                        title = getString(R.string.appearance_kit_manage),
                        summary = getString(R.string.appearance_kit_summary),
                        iconRes = R.drawable.ic_cfg_theme
                    ),
                    actionRow(
                        "theme_setting", R.string.theme_setting,
                        R.string.theme_setting_s, R.drawable.ic_interface_setting
                    ),
                    actionRow(
                        ConfigTag.COVER_CONFIG, R.string.cover_config,
                        R.string.cover_config_summary, R.drawable.ic_image
                    ),
                    actionRow(
                        ConfigTag.WELCOME_CONFIG, R.string.welcome_style,
                        R.string.welcome_style_summary, R.drawable.ic_screen
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_ai_automation),
                rows = listOf(
                    actionRow(
                        "ai_setting", R.string.ai_setting,
                        R.string.ai_setting_summary, R.drawable.ic_bottom_ai
                    ),
                    MySettingsRowModel(
                        key = PreferKey.autoTaskService,
                        title = getString(R.string.auto_task_service),
                        summary = getString(R.string.auto_task_service_desc),
                        kind = MySettingsRowKind.AutoTaskService,
                        iconRes = R.drawable.ic_timer_black_24dp
                    ),
                    actionRow(
                        key = "autoTaskManage",
                        titleRes = R.string.auto_task_manage,
                        summaryRes = R.string.auto_task_manage_desc,
                        iconRes = R.drawable.ic_time_add_24dp
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_network_sync),
                rows = listOf(
                    actionRow(
                        ConfigTag.NETWORK_CONFIG, R.string.network_setting,
                        R.string.network_setting_s, R.drawable.ic_network_check
                    ),
                    actionRow(
                        "web_dav_setting", R.string.backup_restore,
                        R.string.web_dav_set_import_old, R.drawable.ic_cfg_backup
                    ),
                    MySettingsRowModel(
                        key = PreferKey.webService,
                        title = getString(R.string.web_service),
                        summary = getString(R.string.web_service_desc),
                        kind = MySettingsRowKind.WebService,
                        iconRes = R.drawable.ic_cfg_web
                    ),
                    actionRow(
                        "publicWebRelay",
                        R.string.public_web_relay,
                        R.string.public_web_relay_summary,
                        R.drawable.ic_web_outline
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_data_device),
                rows = listOf(
                    actionRow(
                        "cacheManage", R.string.cache_manage_title,
                        R.string.cache_manage_summary, R.drawable.ic_download
                    ),
                    actionRow(
                        ConfigTag.STORAGE_CONFIG, R.string.storage_cleanup_title,
                        R.string.storage_cleanup_s, R.drawable.ic_storage_black_24dp
                    ),
                    actionRow(
                        "fileManage", R.string.file_manage,
                        R.string.file_manage_summary, R.drawable.ic_folder
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_advanced),
                rows = listOf(
                    actionRow(
                        ConfigTag.ADVANCED_CONFIG, R.string.advanced_setting,
                        R.string.advanced_setting_s, R.drawable.ic_settings
                    ),
                    actionRow("about", R.string.about, null, R.drawable.ic_cfg_about),
                    // 退出不是「进入下一层」，所以标红 + 不带箭头：避免和跳转项混淆
                    actionRow("exit", R.string.exit, null, R.drawable.ic_exit, danger = true)
                )
            )
        )
    }

    private fun actionRow(
        key: String,
        titleRes: Int,
        summaryRes: Int?,
        iconRes: Int? = null,
        danger: Boolean = false
    ): MySettingsRowModel {
        return MySettingsRowModel(
            key = key,
            title = getString(titleRes),
            summary = summaryRes?.let(::getString),
            iconRes = iconRes,
            danger = danger
        )
    }

    private fun buildThemeOptions(): List<MySettingsThemeOption> {
        val labels = resources.getStringArray(R.array.theme_mode)
        val values = resources.getStringArray(R.array.theme_mode_v)
        return labels.mapIndexed { index, label ->
            MySettingsThemeOption(
                value = values.getOrElse(index) { index.toString() },
                label = label
            )
        }.ifEmpty {
            listOf(MySettingsThemeOption("0", getString(R.string.theme_mode)))
        }
    }

    /**
     * 二级设置项（Kotlin spec 里的每一项）挂到一级行的 [MySettingsSubSearchItem.ownerKey] 上，
     * 这样搜索命中深层设置时能直接从一级行跳进去并定位。
     *
     * ownerKey 必须等于 [buildSections] 里某一行的 key：封面与欢迎页已各自成为一级行，
     * 就各自挂自己的 key，不再全挂在 "theme_setting" 下（否则搜一个问题会出现三行同结果）。
     */
    private fun buildSubSearchItems(): List<MySettingsSubSearchItem> {
        return listOf(
            Triple(ConfigTag.READING_CONFIG, R.xml.pref_config_reading, ConfigTag.READING_CONFIG),
            Triple("theme_setting", R.xml.pref_config_theme, ConfigTag.THEME_CONFIG),
            Triple(ConfigTag.COVER_CONFIG, R.xml.pref_config_cover, ConfigTag.COVER_CONFIG),
            Triple(ConfigTag.WELCOME_CONFIG, R.xml.pref_config_welcome, ConfigTag.WELCOME_CONFIG),
            Triple("web_dav_setting", R.xml.pref_config_backup, ConfigTag.BACKUP_CONFIG),
            Triple("ai_setting", R.xml.pref_config_ai, ConfigTag.AI_CONFIG),
            Triple(ConfigTag.NETWORK_CONFIG, R.xml.pref_config_network, ConfigTag.NETWORK_CONFIG),
            // DNS 是「网络设置」的下一层，所以它的子项挂在「网络设置」这一行下面
            Triple(ConfigTag.NETWORK_CONFIG, R.xml.pref_config_network_dns, ConfigTag.NETWORK_DNS_CONFIG),
            Triple(ConfigTag.STORAGE_CONFIG, R.xml.pref_config_storage, ConfigTag.STORAGE_CONFIG),
            Triple(ConfigTag.ADVANCED_CONFIG, R.xml.pref_config_advanced, ConfigTag.ADVANCED_CONFIG),
            Triple(
                "theme_setting",
                R.xml.pref_config_discovery_subscription,
                ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG
            )
        ).flatMap { (ownerKey, xmlRes, ownerConfigTag) ->
            buildPreferenceXmlSearchItems(ownerKey, xmlRes, ownerConfigTag)
        }
    }

    private fun buildPreferenceXmlSearchItems(
        ownerKey: String,
        xmlRes: Int,
        ownerConfigTag: String
    ): List<MySettingsSubSearchItem> {
        val items = ArrayList<MySettingsSubSearchItem>()
        val parser: XmlResourceParser = resources.getXml(xmlRes)
        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    // 分组头（PreferenceCategory）也可能带 key+title，但它们不是设置项，
                    // 收进来会出现一条"点过去永远定位不到"的死搜索结果
                    if (parser.name?.endsWith("PreferenceCategory") == true) {
                        eventType = parser.next()
                        continue
                    }
                    val title = collectPreferenceAttr(parser, "title").orEmpty()
                    val key = collectPreferenceAttr(parser, "key").orEmpty()
                    if (title.isNotBlank() && key.isNotBlank()) {
                        items.add(
                            MySettingsSubSearchItem(
                                ownerKey = ownerKey,
                                title = title,
                                summary = collectPreferenceAttr(parser, "summary").orEmpty(),
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

    private fun collectPreferenceAttr(parser: XmlResourceParser, attrName: String): String? {
        val namespace = "http://schemas.android.com/apk/res/android"
        val attrValue = parser.getAttributeValue(namespace, attrName)?.trim().orEmpty()
        if (attrValue.isBlank()) return null
        val attrRes = parser.getAttributeResourceValue(namespace, attrName, 0)
        return if (attrRes != 0) {
            runCatching {
                getString(attrRes)
            }.getOrNull()?.trim().orEmpty().takeIf { it.isNotBlank() }
        } else {
            attrValue.removePrefix("@").takeIf { it.isNotBlank() }
        }
    }
}
