package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import splitties.init.appCtx

/**
 * 漫画与视频：把漫画阅读页三点菜单里的全局配置，和视频播放器设置收到一处。
 *
 * 原来这两块都不在设置体系里：漫画的页脚/滤镜/墨纸是三个只挂在 ReadMangaActivity
 * 菜单上的弹窗，视频的 8 项是 VideoPlay 自己那套 video_config 偏好文件里的弹窗。
 * 用户想改「漫画页脚显示什么」必须先打开一本漫画，搜索也无从命中。
 *
 * 这里只收**全局项**（存 PreferKey 或 video_config）。缩放、点按滚动、横向滚动、
 * 自动翻页速度这些按书保存在 BookMangaReadConfig 上，全局页给不了一个统一的答案，
 * 仍然留在阅读页菜单里——迁移这些会造成「设置页改了，当前漫画没变」的错觉。
 */
class MangaVideoConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.manga_video_section

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.manga_section),
                    items = listOf(
                        switch(
                            key = PreferKey.showMangaUi,
                            title = str(R.string.show_manga_ui),
                            summary = str(R.string.show_manga_ui_summary),
                            defaultValue = true,
                            searchKeys = listOf("漫画", "漫画浏览", "漫画界面")
                        ),
                        numberAction(
                            key = PreferKey.mangaPreDownloadNum,
                            title = str(R.string.manga_pre_download),
                            summary = str(R.string.pre_download_m, AppConfig.mangaPreDownloadNum.toString()),
                            min = 0,
                            max = 100,
                            value = AppConfig.mangaPreDownloadNum,
                            searchKeys = listOf("漫画预下载", "预载"),
                            onSelected = { AppConfig.mangaPreDownloadNum = it }
                        ),
                        switch(
                            key = PreferKey.hideMangaTitle,
                            title = str(R.string.hide_manga_title),
                            summary = str(R.string.hide_manga_title_summary),
                            defaultValue = false,
                            searchKeys = listOf("漫画标题", "标题栏")
                        ),
                        // 墨纸与灰度在阅读页菜单里是互斥的（开一个会关掉另一个），
                        // 这里必须保持同样的约束，否则两处状态不一致，用户会看到
                        // 设置页两个都开、阅读页只生效一个
                        einkSwitch(),
                        SettingSliderSpec(
                            key = PreferKey.mangaEInkThreshold,
                            title = str(R.string.manga_epaper_threshold),
                            summary = str(R.string.manga_epaper_threshold_summary),
                            value = AppConfig.mangaEInkThreshold,
                            valueRange = 0..255,
                            enabled = AppConfig.enableMangaEInk,
                            valueFormatter = { it.toString() },
                            onValueChange = { AppConfig.mangaEInkThreshold = it }
                        ),
                        graySwitch(),
                        SettingActionSpec(
                            key = PreferKey.mangaFooterConfig,
                            title = str(R.string.manga_footer_config),
                            summary = str(R.string.manga_footer_config_summary),
                            searchKeys = listOf("页脚", "章节名", "页码", "进度", "页脚对齐"),
                            onClick = { showDialogFragment(MangaFooterSettingDialog()) }
                        ),
                        SettingActionSpec(
                            key = PreferKey.mangaColorFilter,
                            title = str(R.string.manga_color_filter),
                            summary = str(R.string.manga_color_filter_summary),
                            searchKeys = listOf("滤镜", "亮度", "对比度", "红", "绿", "蓝", "透明度"),
                            onClick = { showDialogFragment(MangaColorFilterDialog()) }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.video_section),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.videoSetting,
                            title = str(R.string.video_setting),
                            summary = str(R.string.video_setting_summary),
                            searchKeys = listOf(
                                "视频", "自动播放", "全屏", "长按倍速",
                                "边播边缓存", "跳过片头片尾", "播放器"
                            ),
                            onClick = { showDialogFragment(SettingsDialog(requireActivity())) }
                        )
                    )
                )
            )
        )
    }

    private fun einkSwitch(): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = PreferKey.enableMangaEInk,
            title = str(R.string.manga_epaper),
            summary = str(R.string.manga_epaper_summary),
            checked = AppConfig.enableMangaEInk,
            searchKeys = listOf("墨水屏", "墨纸", "e-ink"),
            onCheckedChange = { enabled ->
                updateBooleanSetting(PreferKey.enableMangaEInk, enabled)
                if (enabled) updateBooleanSetting(PreferKey.enableMangaGray, false)
            }
        )
    }

    private fun graySwitch(): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = PreferKey.enableMangaGray,
            title = str(R.string.enable_manga_gray),
            summary = str(R.string.enable_manga_gray_summary),
            checked = AppConfig.enableMangaGray,
            searchKeys = listOf("灰度", "黑白", "灰色"),
            onCheckedChange = { enabled ->
                updateBooleanSetting(PreferKey.enableMangaGray, enabled)
                if (enabled) updateBooleanSetting(PreferKey.enableMangaEInk, false)
            }
        )
    }

    /**
     * 漫画显示项改了要通知还在栈里的漫画阅读页，否则用户从设置返回会发现
     * 画面没变，只剩「是不是没保存」的困惑。页脚弹窗自己会发事件，这里不管。
     */
    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.enableMangaEInk,
            PreferKey.mangaEInkThreshold,
            PreferKey.enableMangaGray,
            PreferKey.hideMangaTitle,
            PreferKey.showMangaUi -> postEvent(EventBus.UP_MANGA_DISPLAY, key)
        }
    }

}
