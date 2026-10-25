package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.EpubReadEnginePolicy
import io.legado.app.help.config.TextReadEnginePolicy
import io.legado.app.ui.book.read.config.ContentSelectMenuConfigDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment

/**
 * 阅读设置：正文怎么渲染、用哪个解析引擎、离线怎么预载、漫画/视频怎么显示。
 *
 * 这些项原先和线程数、本地密码混在「其它设置」里，按任务域拆出来之后
 * 一级入口就叫「阅读」，不用再进垃圾桶页翻找。
 */
class ReadingConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.reading_setting

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.contentSelectMenuConfig,
                            title = getString(R.string.content_select_menu_config),
                            summary = getString(R.string.content_select_menu_config_summary),
                            onClick = {
                                ContentSelectMenuConfigDialog()
                                    .show(parentFragmentManager, "contentSelectMenuConfig")
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.reading_engine_section),
                    items = listOf(
                        choice(
                            key = PreferKey.epubReadEngine,
                            title = getString(R.string.epub_read_engine),
                            summary = epubReadEngineSummary(),
                            entriesRes = R.array.epub_read_engine_entries,
                            valuesRes = R.array.epub_read_engine_values,
                            defaultValue = EpubReadEnginePolicy.DEFAULT
                        ),
                        choice(
                            key = PreferKey.textReadEngine,
                            title = getString(R.string.text_read_engine),
                            summary = getString(R.string.text_read_engine_summary),
                            entriesRes = R.array.text_read_engine_entries,
                            valuesRes = R.array.text_read_engine_values,
                            defaultValue = TextReadEnginePolicy.DEFAULT
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.offline_preload_section),
                    items = listOf(
                        numberAction(
                            key = PreferKey.preDownloadNum,
                            title = getString(R.string.pre_download),
                            summary = getString(R.string.pre_download_s, AppConfig.preDownloadNum.toString()),
                            min = 0,
                            max = 9999,
                            value = AppConfig.preDownloadNum,
                            onSelected = { AppConfig.preDownloadNum = it }
                        ),
                        switch(
                            key = PreferKey.replaceEnableDefault,
                            title = getString(R.string.replace_enable_default_t),
                            summary = getString(R.string.replace_enable_default_s),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoRefreshMediaToc,
                            title = getString(R.string.auto_refresh_media_toc),
                            summary = getString(R.string.auto_refresh_media_toc_summary),
                            defaultValue = true
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.manga_video_section),
                    items = listOf(
                        switch(
                            key = PreferKey.showMangaUi,
                            title = getString(R.string.show_manga_ui),
                            defaultValue = true
                        ),
                        SettingActionSpec(
                            key = PreferKey.videoSetting,
                            title = getString(R.string.video_setting),
                            summary = getString(R.string.video_setting_summary),
                            onClick = { showDialogFragment(SettingsDialog(requireActivity())) }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.bookshelf_launch_section),
                    items = listOf(
                        switch(
                            key = PreferKey.showAddToShelfAlert,
                            title = getString(R.string.show_add_to_shelf_alert_title),
                            summary = getString(R.string.show_add_to_shelf_alert_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoRefresh,
                            title = getString(R.string.pt_auto_refresh),
                            summary = getString(R.string.ps_auto_refresh),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.onlyUpdateRead,
                            title = getString(R.string.only_update_read),
                            summary = getString(R.string.ps_only_update_read),
                            defaultValue = false,
                            visible = booleanSetting(PreferKey.autoRefresh, false)
                        ),
                        switch(
                            key = PreferKey.defaultToRead,
                            title = getString(R.string.pt_default_read),
                            summary = getString(R.string.ps_default_read),
                            defaultValue = false
                        ),
                        choice(
                            key = PreferKey.defaultHomePage,
                            title = getString(R.string.default_home_page),
                            entriesRes = R.array.default_home_page,
                            valuesRes = R.array.default_home_page_value,
                            defaultValue = "bookshelf"
                        )
                    )
                )
            )
        )
    }

    /**
     * 切换解析引擎必须通知阅读页重载排版管线，否则正在看的书要到下次
     * 翻章才生效。这两个 key 的事件处理原来在「其它设置」里，拆页时不能丢。
     */
    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.epubReadEngine, PreferKey.textReadEngine ->
                postEvent(EventBus.UP_CONFIG, arrayListOf(13))
        }
    }

    private fun epubReadEngineSummary(): String {
        val current = when (AppConfig.epubReadEngine) {
            "core" -> getString(R.string.epub_read_engine_core)
            else -> getString(R.string.epub_read_engine_text)
        }
        return "${getString(R.string.epub_read_engine_summary)}\n$current"
    }

}
