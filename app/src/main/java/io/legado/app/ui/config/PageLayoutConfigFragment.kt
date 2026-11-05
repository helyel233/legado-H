package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.postEvent

/**
 * 页面布局：把原先分散在发现/订阅设置页、订阅页菜单、首页菜单的三项布局偏好归到一处。
 *
 * 三项仍然可以从各自页面的菜单切换（就近入口保留），这里提供统一入口与全局搜索命中。
 * 偏好键一律沿用原值，不做数据迁移；改值后由 [EventBus.UP_PAGE_LAYOUT] 通知订阅页 /
 * 首页即时重排，避免出现「设置页改了、切回去还是旧布局」。
 *
 * 注意：构建搜索索引时会 new 一个未 attach 的 Fragment 调 [buildSpec]，
 * 因此 [buildPageSpec] 只能依赖 [ctx] 与 [AppConfig]（都基于 appCtx），
 * 所有 onClick / onSelected 回调在索引态不会执行。
 */
class PageLayoutConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.page_layout_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.page_layout_section),
                    items = listOf(
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
                            searchKeys = listOf("布局", "layout", "瀑布流", "网格", "发现页")
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
                            searchKeys = listOf("布局", "layout", "列数", "columns", "订阅")
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
                            searchKeys = listOf("布局", "layout", "首页", "homepage", "分源")
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.page_layout_links_section),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_GOTO_EXPLORE,
                            title = str(R.string.page_layout_goto_explore),
                            summary = str(R.string.page_layout_goto_summary),
                            navigates = true,
                            onClick = { MainActivity.openExplore(ctx) }
                        ),
                        SettingActionSpec(
                            key = KEY_GOTO_RSS,
                            title = str(R.string.page_layout_goto_rss),
                            summary = str(R.string.page_layout_goto_summary),
                            navigates = true,
                            onClick = { MainActivity.openRss(ctx) }
                        ),
                        SettingActionSpec(
                            key = KEY_GOTO_HOMEPAGE,
                            title = str(R.string.page_layout_goto_homepage),
                            summary = str(R.string.page_layout_goto_summary),
                            navigates = true,
                            onClick = { MainActivity.openHomepage(ctx) }
                        )
                    )
                )
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.discoveryPageLayout,
            PreferKey.rssSourceLayout,
            PreferKey.homepageLayoutMode -> {
                // 订阅页的 GridLayoutManager 与首页的 StateFlow 都不会自己重读偏好，
                // 必须显式通知；NOTIFY_MAIN 保持与「发现/订阅页设置」原有行为一致。
                postEvent(EventBus.UP_PAGE_LAYOUT, key)
                postEvent(EventBus.NOTIFY_MAIN, false)
            }
        }
    }

    companion object {
        private const val KEY_GOTO_EXPLORE = "pageLayoutGotoExplore"
        private const val KEY_GOTO_RSS = "pageLayoutGotoRss"
        private const val KEY_GOTO_HOMEPAGE = "pageLayoutGotoHomepage"
    }
}
