package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.EpubLoadingTemplateStore
import io.legado.app.help.config.EpubReadEnginePolicy
import io.legado.app.help.config.TextReadEnginePolicy
import io.legado.app.ui.book.read.config.ContentSelectMenuConfigDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity

/**
 * 阅读设置：正文怎么渲染、用哪个解析引擎、离线怎么预载、正文模板怎么进出。
 *
 * 这些项原先和线程数、本地密码混在「其它设置」里，按任务域拆出来之后
 * 一级入口就叫「阅读」，不用再进垃圾桶页翻找。
 *
 * 本页只管「正文」。听书、漫画、视频各有自己的二级页（[AudioConfigFragment]、
 * [MangaVideoConfigFragment]），书架行为在 [BookshelfSettingsFragment]。
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
                            title = str(R.string.content_select_menu_config),
                            summary = str(R.string.content_select_menu_config_summary),
                            onClick = {
                                ContentSelectMenuConfigDialog()
                                    .show(parentFragmentManager, "contentSelectMenuConfig")
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.reading_engine_section),
                    items = listOf(
                        choice(
                            key = PreferKey.epubReadEngine,
                            title = str(R.string.epub_read_engine),
                            summary = epubReadEngineSummary(),
                            entriesRes = R.array.epub_read_engine_entries,
                            valuesRes = R.array.epub_read_engine_values,
                            defaultValue = EpubReadEnginePolicy.DEFAULT
                        ),
                        choice(
                            key = PreferKey.textReadEngine,
                            title = str(R.string.text_read_engine),
                            summary = str(R.string.text_read_engine_summary),
                            entriesRes = R.array.text_read_engine_entries,
                            valuesRes = R.array.text_read_engine_values,
                            defaultValue = TextReadEnginePolicy.DEFAULT
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.offline_preload_section),
                    items = listOf(
                        numberAction(
                            key = PreferKey.preDownloadNum,
                            title = str(R.string.pre_download),
                            summary = str(R.string.pre_download_s, AppConfig.preDownloadNum.toString()),
                            min = 0,
                            max = 9999,
                            value = AppConfig.preDownloadNum,
                            onSelected = { AppConfig.preDownloadNum = it }
                        ),
                        switch(
                            key = PreferKey.prefetchNextChapter,
                            title = str(R.string.prefetch_next_chapter),
                            summary = str(R.string.prefetch_next_chapter_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.preDownloadWifiOnly,
                            title = str(R.string.pre_download_wifi_only),
                            summary = str(R.string.pre_download_wifi_only_summary),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.replaceEnableDefault,
                            title = str(R.string.replace_enable_default_t),
                            summary = str(R.string.replace_enable_default_s),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoRefreshMediaToc,
                            title = str(R.string.auto_refresh_media_toc),
                            summary = str(R.string.auto_refresh_media_toc_summary),
                            defaultValue = true
                        )
                    )
                ),
                // 「漫画与视频」区已迁出：漫画显示、墨纸/灰度、页脚/滤镜三弹窗
                // 与视频播放器设置都收在独立的「漫画与视频」二级页里。
                // 留在本页会让同一件事出现第二个入口，用户不知道该改哪一处

                // 「阅读模板」原先挂在界面设置的「模板」区，但这两项改的都是正文本身：
                // EPUB 加载模板控制正文怎么进场，分享笔记模板控制正文怎么导出。
                // 与字号、行距、解析引擎同属「阅读」，迁回阅读设置
                SettingSectionSpec(
                    title = str(R.string.reading_template_section),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_EPUB_LOADING_TEMPLATES,
                            navigates = true,
                            title = str(R.string.epub_loading_templates),
                            summary = str(
                                R.string.epub_loading_selected,
                                EpubLoadingTemplateStore.selected(ctx).name
                            ),
                            searchKeys = listOf("EPUB", "正文加载", "加载模板", "加载画面"),
                            onClick = { startActivity<EpubLoadingTemplateManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_SHARE_NOTE_TEMPLATE_MANAGE,
                            navigates = true,
                            title = str(R.string.share_note_template_manage),
                            summary = str(R.string.share_note_template_manage_summary),
                            searchKeys = listOf("分享模板", "摘录模板", "笔记模板", "正文分享"),
                            onClick = { startActivity<ShareNoteTemplateManageActivity>() }
                        )
                    )
                )
                // 「书架与启动」区已迁出：加书提示 / 自动刷新 / 只更新已读 / 默认进入阅读 / 默认主页
                // 都属于书架行为，现在收拢在「书架设置」二级页，不再混在正文渲染页里
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
            "core" -> str(R.string.epub_read_engine_core)
            else -> str(R.string.epub_read_engine_text)
        }
        return "${str(R.string.epub_read_engine_summary)}\n$current"
    }

    companion object {
        private const val KEY_EPUB_LOADING_TEMPLATES = "epub_loading_templates"
        private const val KEY_SHARE_NOTE_TEMPLATE_MANAGE = "share_note_template_manage"
    }

}
