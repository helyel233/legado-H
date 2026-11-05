package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec

/**
 * 书架设置：加书提示、自动刷新、只更新已读、默认进入阅读、默认主页。
 *
 * 这 5 项管的是「书架的行为」，原先挂在「阅读设置」的渲染页里，而书架自己的
 * 显示配置（分组/布局/排序/未读/简介行数…）又在书架页的布局弹窗里 —— 同一个
 * 「书架」域被拆成两处，用户调书架得跑两个页面。这里把它们收拢到书架域。
 *
 * 书架布局弹窗（BookshelfConfigDialog）保留在书架页：它带实时预览与边距拖动，
 * 属于「所见即所得」的页面内交互，迁到二级页反而丢失预览能力。
 */
class BookshelfSettingsFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.bookshelf_setting

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.bookshelf_launch_section),
                    items = listOf(
                        switch(
                            key = PreferKey.showAddToShelfAlert,
                            title = str(R.string.show_add_to_shelf_alert_title),
                            summary = str(R.string.show_add_to_shelf_alert_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoRefresh,
                            title = str(R.string.pt_auto_refresh),
                            summary = str(R.string.ps_auto_refresh),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.onlyUpdateRead,
                            title = str(R.string.only_update_read),
                            summary = str(R.string.ps_only_update_read),
                            defaultValue = false,
                            visible = booleanSetting(PreferKey.autoRefresh, false)
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.bookshelf_entry_section),
                    items = listOf(
                        switch(
                            key = PreferKey.defaultToRead,
                            title = str(R.string.pt_default_read),
                            summary = str(R.string.ps_default_read),
                            defaultValue = false
                        ),
                        choice(
                            key = PreferKey.defaultHomePage,
                            title = str(R.string.default_home_page),
                            entriesRes = R.array.default_home_page,
                            valuesRes = R.array.default_home_page_value,
                            defaultValue = "bookshelf"
                        )
                    )
                )
            )
        )
    }
}
