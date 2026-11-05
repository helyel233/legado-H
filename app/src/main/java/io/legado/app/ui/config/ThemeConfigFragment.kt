package io.legado.app.ui.config

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.MainBottomNavConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.utils.applyTint
import io.legado.app.utils.postEvent
import io.legado.app.utils.startActivity

class ThemeConfigFragment : ComposeSettingFragment(), MenuProvider {

    override val titleRes: Int = R.string.theme_setting

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.addMenuProvider(this, viewLifecycleOwner)
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
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
                        ),
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.theme_category_navigation),
                    items = listOf(
                        SettingSwitchSpec(
                            key = KEY_HOMEPAGE_VISIBLE,
                            title = str(R.string.homepage),
                            summary = str(R.string.homepage_switch_summary),
                            checked = MainBottomNavConfig.isVisible(MainBottomNavConfig.KEY_HOMEPAGE),
                            onCheckedChange = { checked ->
                                MainBottomNavConfig.setVisible(MainBottomNavConfig.KEY_HOMEPAGE, checked)
                                postEvent(EventBus.NOTIFY_MAIN, true)
                            },
                            searchKeys = listOf("首页", "homepage", "底部导航", "底部导航栏")
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
                            searchKeys = listOf("统计", "stats", "底部导航", "底部导航栏")
                        ),
                        SettingActionSpec(
                            key = KEY_NAVIGATION_BAR_MANAGE,
                            navigates = true,
                            title = str(R.string.navigation_bar_manage),
                            summary = str(R.string.navigation_bar_manage_summary),
                            onClick = { startActivity<NavigationBarManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_TOP_BAR_MANAGE,
                            navigates = true,
                            title = str(R.string.top_bar_manage),
                            summary = str(R.string.top_bar_manage_summary),
                            onClick = { startActivity<TopBarManageActivity>() }
                        )
                    )
                ),
                // 「外观」区只留改「长什么样」的素材入口：主题包、书籍详情、气泡。
                // 模板类（加载画面、分享样式）和页面形态（发现/订阅版本）另起一区，
                // 它们改的是「某一屏的内容结构」，不是全局外观
                SettingSectionSpec(
                    title = str(R.string.theme_category_appearance),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_THEME_MANAGE,
                            navigates = true,
                            title = str(R.string.theme_list),
                            summary = str(R.string.theme_list_summary),
                            onClick = { startActivity<ThemeManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_BOOK_INFO_MANAGE,
                            navigates = true,
                            title = str(R.string.book_info_manage),
                            summary = str(R.string.book_info_manage_summary),
                            onClick = { startActivity<BookInfoManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_BUBBLE_MANAGE,
                            navigates = true,
                            title = str(R.string.bubble_manage),
                            summary = str(R.string.bubble_manage_summary),
                            onClick = { startActivity<BubbleManageActivity>() }
                        )
                        // 封面设置不再重复挂在这里：它已经是「我的」页的一级行，
                        // 同一个 ConfigTag 两个入口只会让用户纠结该从哪进
                    )
                )
                // 「模板」区已清空：
                //  · EPUB 加载模板 / 分享笔记模板 → 阅读设置「阅读模板」区（它们改的是正文加载与分享）
                //  · 发现/订阅页模式 → 「内容与规则」一级行（它改的是内容呈现）
                // 界面设置自此回归纯外观，不再混入正文档与内容页形态
            )
        )
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.theme_config, menu)
        updateThemeModeMenu(menu)
        menu.applyTint(ctx)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_theme_mode -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                menuItem.setIcon(themeModeIconRes())
                ThemeConfig.applyDayNight(ctx)
                return true
            }
        }
        return false
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(
                stringSetting(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON)
            )

            PreferKey.mainTransparentStatusBar,
            PreferKey.transparentStatusBar,
            PreferKey.immersiveManageBar,
            PreferKey.immNavigationBar -> recreateActivities()
        }
    }

    private fun updateThemeModeMenu(menu: Menu) {
        menu.findItem(R.id.menu_theme_mode)?.setIcon(themeModeIconRes())
    }

    private fun themeModeIconRes(): Int {
        return if (AppConfig.isNightTheme) {
            R.drawable.ic_daytime
        } else {
            R.drawable.ic_brightness
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

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    companion object {
        private const val DEFAULT_LAUNCHER_ICON = "ic_launcher"
        private const val KEY_THEME_MANAGE = "theme_manage"
        private const val KEY_HOMEPAGE_VISIBLE = "homepage_visible"
        private const val KEY_STATS_VISIBLE = "stats_visible"
        private const val KEY_NAVIGATION_BAR_MANAGE = "navigation_bar_manage"
        private const val KEY_TOP_BAR_MANAGE = "top_bar_manage"
        private const val KEY_BOOK_INFO_MANAGE = "book_info_manage"
        private const val KEY_BUBBLE_MANAGE = "bubble_manage"
    }
}
