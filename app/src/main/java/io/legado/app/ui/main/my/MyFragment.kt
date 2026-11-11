package io.legado.app.ui.main.my

import android.content.SharedPreferences
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
import io.legado.app.BuildConfig
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.AutoTask
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
import io.legado.app.ui.config.SettingsSearchIndex
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.source.recycle.SourceRecycleBinActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.widget.applyGlassDomain
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.LogUtils

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
    private val autoTaskServiceState = mutableStateOf(
        MyServiceToggleUiState(checked = false, summary = "")
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
                    subSearchItemsProvider = { subSearchItems },
                    searchQuery = searchQueryState.value,
                    themeOptions = themeOptions,
                    themeModeValue = themeModeState.value,
                    autoTaskServiceState = autoTaskServiceState.value,
                    onThemeModeSelect = ::setThemeMode,
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
        // 设置搜索栏直接在页面根布局里，根布局无底色、背后是主题壁纸
        settingsSearchView.applyGlassDomain(GlassSamplingDomain.WALLPAPER)
    }

    private fun updateSettingsState() {
        themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
        updateAutoTaskServiceState()
    }

    private fun updateAutoTaskServiceState() {
        val enabled = requireContext().getPrefBoolean(PreferKey.autoTaskService)
        autoTaskServiceState.value = MyServiceToggleUiState(
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
            ConfigTag.WELCOME_CONFIG,
            ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG,
            ConfigTag.COMMENT_CONFIG,
            ConfigTag.BOOKSHELF_CONFIG,
            ConfigTag.AUDIO_CONFIG,
            ConfigTag.MANGA_VIDEO_CONFIG,
            ConfigTag.PAGE_LAYOUT_CONFIG,
            ConfigTag.EXPLORE_GLASS_CONFIG -> startActivity<ConfigActivity> {
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
            // 新增一级行时忘了在这里接跳转，点下去毫无反应且不报错。
            // debug 包直接把漏接的 key 抛出来；release 保持静默，不给用户白屏崩溃
            else -> if (BuildConfig.DEBUG) error("一级行未接跳转: $key")
        }
    }

    /**
     * 「我的」页的一级分类。
     *
     * 排布原则：按**用户的任务域**划分（内容的来龙去脉 → 怎么读和看起来怎样 →
     * 智能与自动化 → 网络与同步 → 我的数据 → 兜底高级项），而不是按代码模块。
     * 顺序是「日常常用在前，少见维护在后」，每区 3~6 项，避免一项独占一区。
     *
     * 为什么「阅读」和「外观」合成一区：阅读设置里本来就管字号、行距、背景，
     * 和界面设置管的是同一件事的两半，拆成两区后用户经常要在两处找字号。
     * 合并为「阅读与界面」后一区覆盖「读起来长什么样」。
     *
     * 为什么书签/阅读记录归「数据与工具」：它们是用户攒下来的阅读数据，
     * 和缓存、文件同属本机数据域（只是前者要保留、后者可清理），
     * 也让原来的「阅读」区不至于只剩 3 行却独占一个分区标题。
     *
     * 关键约定 1：**行的 key 就是搜索深链的 ownerKey**，改 key 会连带断开
     * [io.legado.app.ui.config.SettingsSearchIndex] 里子项到一级行的挂载，
     * 两者必须同时改（[verifySubSearchOwners] 会在 debug 包里把断链当场拦下来）。
     * 关键约定 2：一级行尽量用 [ConfigTag] 常量做 key，跳转、搜索、层级三者自动对齐。
     * 行在哪个分区不影响深链，跨区搬行是安全操作。
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
                        R.string.rss_source_manage_summary, R.drawable.ic_bottom_rss_feed_e
                    ),
                    // 发现/订阅页的形态（入口显隐、页面模式、合并发现）改的是「内容怎么呈现」，
                    // 与书源/订阅源同属内容域；原先藏在「界面设置 › 模板」里，用户找不到。
                    // 三处「页面布局」已另立 PAGE_LAYOUT_CONFIG 一级行，不再混在本页
                    actionRow(
                        ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG,
                        R.string.discovery_subscription_settings_title,
                        R.string.discovery_subscription_settings_summary,
                        R.drawable.ic_bottom_explore_e
                    ),
                    // 发现/订阅/首页三处布局原先散落在各 Tab 菜单且不可搜，统一归到这一页
                    actionRow(
                        ConfigTag.PAGE_LAYOUT_CONFIG,
                        R.string.page_layout_settings_title,
                        R.string.page_layout_settings_summary,
                        R.drawable.ic_image
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
                title = getString(R.string.config_category_reading_ui),
                rows = listOf(
                    actionRow(
                        ConfigTag.READING_CONFIG, R.string.reading_setting,
                        R.string.reading_setting_s, R.drawable.ic_book_has
                    ),
                    // 书架行为（加书提示/自动刷新/默认主页…）从阅读设置拆出，独立成页
                    actionRow(
                        ConfigTag.BOOKSHELF_CONFIG, R.string.bookshelf_setting,
                        R.string.bookshelf_setting_summary, R.drawable.ic_bottom_books_e
                    ),
                    // 听书与漫画/视频原先只有「打开一本书→阅读页菜单」一条路可达，
                    // 提到一级行后，改朗读或漫画显示不必先找一本书
                    actionRow(
                        ConfigTag.AUDIO_CONFIG, R.string.audio_config_title,
                        R.string.audio_config_summary, R.drawable.ic_volume_up
                    ),
                    actionRow(
                        ConfigTag.MANGA_VIDEO_CONFIG, R.string.manga_video_section,
                        R.string.manga_video_config_summary, R.drawable.ic_image
                    ),
                    MySettingsRowModel(
                        key = PreferKey.themeMode,
                        title = getString(R.string.theme_mode),
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
                    ),
                    // 全局毛玻璃效果管的是「读起来长什么样」（顶栏与发现页的玻璃材质），
                    // 与主题、封面同属界面域；原先在「内容与规则」区，用户按外观诉求找不到。
                    actionRow(
                        ConfigTag.EXPLORE_GLASS_CONFIG,
                        R.string.explore_glass_settings_title,
                        R.string.explore_glass_settings_summary,
                        R.drawable.ic_cfg_theme
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_ai_automation),
                rows = listOf(
                    actionRow(
                        "ai_setting", R.string.ai_setting,
                        R.string.ai_setting_summary, R.drawable.ic_bottom_ai_e
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
                    // Web 服务（启停 + 地址 + 复制/浏览器打开）已整体并入「网络设置」，
                    // 那里本来就有端口、口令、唤醒锁，启停和它的配置参数现在在同一屏。
                    // 这里不再保留整行开关：两个入口都能启停同一个服务，状态一乱就很难解释。
                    actionRow(
                        "publicWebRelay",
                        R.string.public_web_relay,
                        R.string.public_web_relay_summary,
                        R.drawable.ic_web_outline
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_data_tools),
                rows = listOf(
                    // 有价值的数据在前，可清理的维护项在后
                    actionRow(
                        "bookmark", R.string.bookmark,
                        R.string.all_bookmark, R.drawable.ic_bookmark
                    ),
                    actionRow(
                        "readRecord", R.string.read_record,
                        R.string.read_record_summary, R.drawable.ic_bottom_read_record_e
                    ),
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
                    // 评论抓取/下载原先塞在高级设置里默认折叠，拆成独立页后可直接进入
                    actionRow(
                        ConfigTag.COMMENT_CONFIG, R.string.review_config_title,
                        R.string.review_config_summary, R.drawable.ic_bubble_chart
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
     * 二级设置项挂到一级行的 [MySettingsSubSearchItem.ownerKey] 上，
     * 这样搜索命中深层设置时能直接从一级行跳进去并定位。
     *
     * 索引内容直接来自各设置页的 spec（[SettingsSearchIndex]），不再解析影子 XML：
     * 设置项从「页面里能看到」到「搜得到」之间不再有需要人工同步的第二份数据。
     *
     * 索引是懒构建的（[subSearchItems]），首次在搜索框输入才会算，不占「我的」页首帧。
     */
    private fun buildSubSearchItems(): List<MySettingsSubSearchItem> {
        val items = SettingsSearchIndex.build(requireContext()).map { entry ->
            MySettingsSubSearchItem(
                ownerKey = entry.ownerKey,
                title = entry.title,
                summary = entry.summary,
                key = entry.key,
                ownerConfigTag = entry.ownerConfigTag,
                aliases = entry.aliases
            )
        }
        verifySubSearchOwners(items)
        return items
    }

    /**
     * 搜索深链靠「子项 ownerKey == 一级行 key」这条隐式约定维系，搬行、改名的时候
     * 很容易一边改一边忘。debug 包在首次构建索引时校验一次：断链会当场崩溃并打印
     * 缺口的 key，而不是等到用户搜不到东西才发现。release 包不校验。
     */
    private fun verifySubSearchOwners(items: List<MySettingsSubSearchItem>) {
        if (!BuildConfig.DEBUG) return
        val rowKeys = sections.flatMap { it.rows }.mapTo(HashSet()) { it.key }
        val orphanOwners = items.map { it.ownerKey }.filterNot { it in rowKeys }.distinct()
        check(orphanOwners.isEmpty()) {
            "搜索子项挂载到不存在的一级行: $orphanOwners —— " +
                "改 buildSections 的行 key 时要同步 SettingsSearchIndex 的 ownerKey"
        }
    }
}
