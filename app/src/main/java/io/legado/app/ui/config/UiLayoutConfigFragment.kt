package io.legado.app.ui.config

import android.os.Build
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.MainBottomNavConfig
import io.legado.app.help.config.MainLayoutPresetConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.main.explore.DiscoverySuiteManageActivity
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.startActivity
import kotlinx.coroutines.launch

/**
 * 界面布局与系统栏：集中管理「导航骨架、底部入口、发现套件、页面布局、书籍详情、系统栏与图标」。
 *
 * 与「外观中心（6 大视觉资产）」彻底解耦：切换主导航形态不再清空主题/顶底栏包。
 */
class UiLayoutConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.ui_layout_config_title

    override fun buildPageSpec(): SettingPageSpec {
        val currentPreset = MainLayoutPresetConfig.currentPreset()
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.ui_layout_section_navigation),
                    items = listOf(
                        SettingChoiceSpec(
                            key = PreferKey.mainLayoutPreset,
                            title = str(R.string.ui_layout_preset_title),
                            summary = str(R.string.ui_layout_preset_summary),
                            options = listOf(
                                SettingChoiceOption(
                                    MainLayoutPresetConfig.PRESET_DEFAULT,
                                    str(R.string.bottom_bar_layout_floating)
                                ),
                                SettingChoiceOption(
                                    MainLayoutPresetConfig.PRESET_REGULAR,
                                    str(R.string.bottom_bar_layout_standard)
                                ),
                                SettingChoiceOption(
                                    MainLayoutPresetConfig.PRESET_SIDEBAR,
                                    str(R.string.bottom_bar_layout_sidebar)
                                )
                            ),
                            selectedValue = currentPreset,
                            onSelected = ::applyNavigationPreset,
                            searchKeys = listOf("导航布局", "悬浮底栏", "标准底栏", "侧边栏", "layout preset")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.topBarFloatingChrome,
                            title = str(R.string.top_bar_floating_chrome),
                            summary = str(R.string.ui_layout_topbar_floating_summary),
                            checked = AppConfig.topBarFloatingChrome,
                            onCheckedChange = { checked ->
                                AppConfig.topBarFloatingChrome = checked
                                postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
                                refreshSettings()
                            },
                            searchKeys = listOf("悬浮顶栏", "圆角顶栏", "floatingChrome", "top bar")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.defaultTopBarShowSearch,
                            title = str(R.string.ui_layout_topbar_show_search),
                            checked = AppConfig.defaultTopBarShowSearch,
                            visible = currentPreset != MainLayoutPresetConfig.PRESET_REGULAR,
                            onCheckedChange = { checked ->
                                AppConfig.defaultTopBarShowSearch = checked
                                postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
                                refreshSettings()
                            },
                            searchKeys = listOf("顶栏搜索", "显示搜索", "showSearch")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.floatingBottomBarHideSearch,
                            title = str(R.string.ui_layout_bottom_hide_search),
                            checked = AppConfig.floatingBottomBarHideSearch,
                            visible = currentPreset == MainLayoutPresetConfig.PRESET_DEFAULT,
                            onCheckedChange = { checked ->
                                AppConfig.floatingBottomBarHideSearch = checked
                                postEvent(EventBus.NOTIFY_MAIN, true)
                                postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
                                refreshSettings()
                            },
                            searchKeys = listOf("隐藏搜索", "底栏搜索", "hideSearch")
                        ),
                        SettingChoiceSpec(
                            key = PreferKey.bottomBarSidebarGravity,
                            title = str(R.string.ui_layout_sidebar_gravity),
                            options = listOf(
                                SettingChoiceOption("start", str(R.string.ui_layout_sidebar_gravity_start)),
                                SettingChoiceOption("end", str(R.string.ui_layout_sidebar_gravity_end))
                            ),
                            selectedValue = AppConfig.bottomBarSidebarGravity,
                            visible = currentPreset == MainLayoutPresetConfig.PRESET_SIDEBAR,
                            onSelected = { gravity ->
                                AppConfig.bottomBarSidebarGravity = gravity
                                postEvent(EventBus.NOTIFY_MAIN, true)
                                refreshSettings()
                            },
                            searchKeys = listOf("侧边栏方向", "左侧", "右侧", "sidebarGravity")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.topBarExpandFilters,
                            title = str(R.string.top_bar_filter_default),
                            summary = str(R.string.ui_layout_topbar_filter_expand_summary),
                            checked = AppConfig.topBarExpandFilters,
                            visible = currentPreset == MainLayoutPresetConfig.PRESET_REGULAR,
                            onCheckedChange = { checked ->
                                AppConfig.topBarExpandFilters = checked
                                postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
                                refreshSettings()
                            },
                            searchKeys = listOf("默认展开筛选", "筛选栏", "expandFilters")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.topBarHideFilterToggle,
                            title = str(R.string.top_bar_filter_toggle_button),
                            summary = str(R.string.ui_layout_topbar_filter_hide_toggle_summary),
                            checked = AppConfig.topBarHideFilterToggle,
                            visible = currentPreset == MainLayoutPresetConfig.PRESET_REGULAR,
                            onCheckedChange = { checked ->
                                AppConfig.topBarHideFilterToggle = checked
                                postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
                                refreshSettings()
                            },
                            searchKeys = listOf("隐藏筛选按钮", "展开箭头", "hideFilterToggle")
                        ),
                        SettingSwitchSpec(
                            key = KEY_HOMEPAGE_VISIBLE,
                            title = str(R.string.homepage),
                            summary = str(R.string.homepage_switch_summary),
                            checked = MainBottomNavConfig.isVisible(MainBottomNavConfig.KEY_HOMEPAGE),
                            onCheckedChange = { checked ->
                                MainBottomNavConfig.setVisible(MainBottomNavConfig.KEY_HOMEPAGE, checked)
                                postEvent(EventBus.NOTIFY_MAIN, true)
                            },
                            searchKeys = listOf("首页", "homepage", "底部导航")
                        ),
                        SettingSwitchSpec(
                            key = KEY_STATS_VISIBLE,
                            title = str(R.string.side_nav_stats),
                            summary = str(R.string.stats_switch_summary),
                            checked = MainBottomNavConfig.isVisible(MainBottomNavConfig.KEY_READ_RECORD),
                            onCheckedChange = { checked ->
                                MainBottomNavConfig.setVisible(MainBottomNavConfig.KEY_READ_RECORD, checked)
                                postEvent(EventBus.NOTIFY_MAIN, true)
                            },
                            searchKeys = listOf("统计", "stats", "底部导航")
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.ui_layout_section_structure),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_DISCOVERY_SUITE,
                            navigates = true,
                            title = str(R.string.ui_layout_discovery_suite),
                            summary = str(R.string.ui_layout_discovery_suite_summary),
                            searchKeys = listOf("发现套件", "discovery suite", "套件"),
                            onClick = { startActivity<DiscoverySuiteManageActivity>() }
                        ),
                        SettingChoiceSpec(
                            key = PreferKey.discoveryPageLayout,
                            title = str(R.string.discovery_page_layout),
                            options = listOf(
                                SettingChoiceOption("1", str(R.string.discovery_page_layout_list)),
                                SettingChoiceOption("2", str(R.string.discovery_page_layout_waterfall)),
                                SettingChoiceOption("3", str(R.string.discovery_page_layout_grid))
                            ),
                            selectedValue = AppConfig.discoveryPageLayout.toString(),
                            summary = str(R.string.page_layout_discovery_summary),
                            onSelected = {
                                AppConfig.discoveryPageLayout = it.toIntOrNull() ?: 1
                            },
                            searchKeys = listOf("发现页布局", "瀑布流", "网格")
                        ),
                        SettingChoiceSpec(
                            key = PreferKey.rssSourceLayout,
                            title = str(R.string.page_layout_rss_columns),
                            options = (1..3).map {
                                SettingChoiceOption(it.toString(), str(R.string.page_layout_column, it))
                            },
                            selectedValue = AppConfig.rssSourceLayout.toString(),
                            summary = str(R.string.page_layout_rss_summary),
                            onSelected = {
                                AppConfig.rssSourceLayout = it.toIntOrNull() ?: 1
                            },
                            searchKeys = listOf("订阅列数", "rss layout")
                        ),
                        SettingChoiceSpec(
                            key = PreferKey.homepageLayoutMode,
                            title = str(R.string.page_layout_homepage),
                            options = listOf(
                                SettingChoiceOption("0", str(R.string.homepage_layout_mixed)),
                                SettingChoiceOption("1", str(R.string.homepage_layout_source_tab))
                            ),
                            selectedValue = AppConfig.homepageLayoutMode.coerceIn(0, 1).toString(),
                            summary = str(R.string.page_layout_homepage_summary),
                            onSelected = {
                                AppConfig.homepageLayoutMode = (it.toIntOrNull() ?: 0).coerceIn(0, 1)
                            },
                            searchKeys = listOf("首页布局", "分源")
                        ),
                        SettingActionSpec(
                            key = KEY_BOOK_INFO_MANAGE,
                            navigates = true,
                            title = str(R.string.book_info_manage),
                            summary = str(R.string.book_info_manage_summary),
                            searchKeys = listOf("书籍详情", "book info"),
                            onClick = { startActivity<BookInfoManageActivity>() }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.theme_category_status_bar),
                    items = listOf(
                        SettingChoiceSpec(
                            key = PreferKey.launcherIcon,
                            title = str(R.string.change_icon),
                            summary = str(R.string.change_icon_summary),
                            options = iconOptions(),
                            selectedValue = stringSetting(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON),
                            visible = Build.VERSION.SDK_INT >= 26,
                            onSelected = { updateStringSetting(PreferKey.launcherIcon, it) }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.overlapStatusBar,
                            title = str(R.string.overlap_status_bar),
                            summary = str(R.string.overlap_status_bar_summary),
                            checked = booleanSetting(PreferKey.overlapStatusBar, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.overlapStatusBar, it)
                            },
                            searchKeys = listOf("状态栏", "覆盖", "status bar")
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.mainTransparentStatusBar,
                            title = str(R.string.main_immersion_status_bar),
                            summary = str(R.string.main_status_bar_immersion),
                            checked = booleanSetting(PreferKey.mainTransparentStatusBar, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.mainTransparentStatusBar, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.immersiveManageBar,
                            title = str(R.string.manage_bar_immersion),
                            summary = str(R.string.manage_bar_immersion_summary),
                            checked = booleanSetting(PreferKey.immersiveManageBar, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.immersiveManageBar, it)
                            }
                        )
                    )
                )
            )
        )
    }

    private fun applyNavigationPreset(nextPreset: String) {
        val currentTopBarStyle = TopBarConfig.currentConfig(ctx).style
        val isMismatched = MainLayoutPresetConfig.isTopBarStyleMismatched(nextPreset, currentTopBarStyle)
        MainLayoutPresetConfig.apply(ctx, nextPreset, notify = true)
        refreshSettings()
        if (isMismatched && TopBarConfig.activeDirName(AppConfig.isNightTheme) != TopBarConfig.DEFAULT_DIR_NAME) {
            showComposeConfirmDialog(
                title = str(R.string.ui_layout_topbar_mismatch_title),
                message = str(R.string.ui_layout_topbar_mismatch_message),
                positiveText = str(R.string.ui_layout_topbar_reset_confirm),
                negativeText = str(R.string.ui_layout_topbar_keep),
                onPositive = {
                    lifecycleScope.launch {
                        val isNight = AppConfig.isNightTheme
                        TopBarConfig.apply(TopBarConfig.defaultEntryForKit(ctx, isNight))
                        postEvent(EventBus.TOP_BAR_CHANGED, isNight)
                    }
                }
            )
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(
                stringSetting(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON)
            )
            PreferKey.discoveryPageLayout,
            PreferKey.rssSourceLayout,
            PreferKey.homepageLayoutMode -> {
                postEvent(EventBus.UP_PAGE_LAYOUT, key)
                postEvent(EventBus.NOTIFY_MAIN, false)
            }
            PreferKey.overlapStatusBar,
            PreferKey.mainTransparentStatusBar,
            PreferKey.transparentStatusBar,
            PreferKey.immersiveManageBar,
            PreferKey.immNavigationBar -> postEvent(EventBus.RECREATE, "")
        }
    }

    private fun iconOptions(): List<SettingChoiceOption> {
        val entries = ctx.resources.getStringArray(R.array.icon_names)
        val values = ctx.resources.getStringArray(R.array.icons)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value },
                iconName = value
            )
        }
    }

    companion object {
        private const val DEFAULT_LAUNCHER_ICON = "ic_launcher"
        private const val KEY_HOMEPAGE_VISIBLE = "ui_layout_homepage_visible"
        private const val KEY_STATS_VISIBLE = "ui_layout_stats_visible"
        private const val KEY_DISCOVERY_SUITE = "ui_layout_discovery_suite"
        private const val KEY_BOOK_INFO_MANAGE = "ui_layout_book_info_manage"
    }
}
