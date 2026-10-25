package io.legado.app.ui.config

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.view.postDelayed
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ImageProvider
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.restart
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 高级设置：语言、渲染性能、媒体按键、隐私与安全、日志调试，以及评论抓取调优。
 *
 * 这里是「其它设置」改名重组后的产物：原先一个页面塞 30 多项、名字却叫「其它」，
 * 用户完全不知道里面有什么。现在它是明确的「平时不用、需要时能调」的兜底页，
 * 评论抓取那批参数默认折叠，不干扰日常使用。
 */
class AdvancedConfigFragment : ComposeSettingFragment() {

    private val packageManager = appCtx.packageManager
    private val componentName = ComponentName(
        appCtx,
        SharedReceiverActivity::class.java.name
    )

    override val titleRes: Int = R.string.advanced_setting

    override fun onResume() {
        super.onResume()
        val enabled = isProcessTextEnabled()
        if (booleanSetting(PreferKey.processText, true) != enabled) {
            updateBooleanSetting(PreferKey.processText, enabled)
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        choice(
                            key = KEY_LANGUAGE,
                            title = getString(R.string.language),
                            entriesRes = R.array.language,
                            valuesRes = R.array.language_value,
                            defaultValue = "auto"
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.perf_rendering_section),
                    items = listOf(
                        intChoice(
                            key = PreferKey.fastScrollerTouchTargetDp,
                            title = getString(R.string.fast_scroller_touch_target),
                            entriesRes = R.array.fast_scroller_touch_target_entries,
                            valuesRes = R.array.fast_scroller_touch_target_values,
                            defaultValue = AppConfig.DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP
                        ),
                        switch(
                            key = PreferKey.antiAlias,
                            title = getString(R.string.anti_alias),
                            summary = getString(R.string.pref_anti_alias_summary),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.highBrush,
                            title = getString(R.string.high_brush_title),
                            summary = getString(R.string.high_brush_summary),
                            defaultValue = true
                        ),
                        numberAction(
                            key = PreferKey.bitmapCacheSize,
                            title = getString(R.string.bitmap_cache_size),
                            summary = getString(R.string.bitmap_cache_size_summary, AppConfig.bitmapCacheSize.toString()),
                            min = 1,
                            max = 1024,
                            value = AppConfig.bitmapCacheSize,
                            onSelected = {
                                AppConfig.bitmapCacheSize = it
                                ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                            }
                        ),
                        numberAction(
                            key = PreferKey.imageRetainNum,
                            title = getString(R.string.image_retain_number),
                            summary = getString(R.string.image_retain_number_summary, AppConfig.imageRetainNum.toString()),
                            min = 0,
                            max = 999,
                            value = AppConfig.imageRetainNum,
                            onSelected = { AppConfig.imageRetainNum = it }
                        ),
                        numberAction(
                            key = PreferKey.sourceEditMaxLine,
                            title = getString(R.string.source_edit_text_max_line),
                            summary = getString(
                                R.string.source_edit_max_line_summary,
                                AppConfig.sourceEditMaxLine.toString()
                            ),
                            min = 10,
                            max = Int.MAX_VALUE,
                            value = AppConfig.sourceEditMaxLine,
                            onSelected = { AppConfig.sourceEditMaxLine = it }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.adv_media_keys_section),
                    items = listOf(
                        switch(
                            key = KEY_MEDIA_BUTTON_ON_EXIT,
                            title = getString(R.string.media_button_on_exit_title),
                            summary = getString(R.string.media_button_on_exit_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.readAloudByMediaButton,
                            title = getString(R.string.read_aloud_by_media_button_title),
                            summary = getString(R.string.read_aloud_by_media_button_summary),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.ignoreAudioFocus,
                            title = getString(R.string.ignore_audio_focus_title),
                            summary = getString(R.string.ignore_audio_focus_summary),
                            defaultValue = false
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.adv_privacy_section),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_LOCAL_PASSWORD,
                            title = getString(R.string.set_local_password),
                            summary = getString(R.string.set_local_password_summary),
                            onClick = ::alertLocalPassword
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.adv_system_section),
                    items = listOf(
                        switch(
                            key = PreferKey.processText,
                            title = getString(R.string.add_to_text_context_menu_t),
                            summary = getString(R.string.add_to_text_context_menu_s),
                            defaultValue = true
                        ),
                        switch(
                            key = KEY_AUTO_UPDATE_VARIANT,
                            title = getString(R.string.auto_update),
                            summary = getString(R.string.auto_update_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.recordLog,
                            title = getString(R.string.record_log),
                            summary = getString(R.string.record_debug_log),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.recordHeapDump,
                            title = getString(R.string.record_heap_dump_t),
                            summary = getString(R.string.record_heap_dump_s),
                            defaultValue = false
                        )
                    )
                )
            ) + reviewDownloadSections()
        )
    }


    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            KEY_LANGUAGE -> view?.postDelayed(1000) {
                appCtx.restart()
            }

            PreferKey.highBrush -> {
                (activity as? BaseActivity<*>)?.applyPreferredRefreshRate()
            }

            PreferKey.processText -> {
                setProcessTextEnable(booleanSetting(PreferKey.processText, true))
            }

            PreferKey.recordLog -> {
                AppConfig.recordLog = booleanSetting(PreferKey.recordLog, false)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }
        }
    }

    private fun isProcessTextEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(componentName) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun setProcessTextEnable(enable: Boolean) {
        if (enable) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun alertLocalPassword() {
        showComposeTextInputDialog(
            title = getString(R.string.set_local_password),
            hint = "password",
            message = getString(R.string.set_local_password_summary),
            onPositive = { LocalConfig.password = it }
        )
    }


    /** legadoC 评论体系：评论下载各阶段并发/超时/完整性配置，定义集中在 ReviewDownloadConfig。 */
    private fun reviewDownloadSections(): List<SettingSectionSpec> {
        val cfg = io.legado.app.help.review.ReviewDownloadConfig
        fun numberSpec(setting: io.legado.app.help.review.ReviewDownloadConfig.Number, help: String) =
            numberAction(
                key = setting.key,
                title = getString(setting.title),
                summary = getString(
                    R.string.review_download_numeric_summary,
                    setting.value, setting.default, help
                ),
                min = setting.minimum,
                max = Int.MAX_VALUE,
                value = setting.value,
                onSelected = { setting.value = it }
            )
        return listOf(
            SettingSectionSpec(
                title = getString(R.string.review_cache_section),
                items = listOf(
                    switch(
                        key = PreferKey.syncCacheReview,
                        title = getString(R.string.menu_sync_cache_review),
                        summary = getString(R.string.ps_sync_cache_review),
                        defaultValue = true
                    ),
                    switch(
                        key = PreferKey.autoDownloadReview,
                        title = getString(R.string.menu_auto_download_review),
                        summary = getString(R.string.ps_auto_download_review),
                        defaultValue = false,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.cacheReviewReplies,
                        title = getString(R.string.cache_review_replies),
                        summary = getString(R.string.cache_review_replies_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.cacheReviewAvatars,
                        title = getString(R.string.cache_review_avatars),
                        summary = getString(R.string.cache_review_resource_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.cacheReviewImages,
                        title = getString(R.string.cache_review_images),
                        summary = getString(R.string.cache_review_resource_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.compressReviewAvatars,
                        title = getString(R.string.compress_review_avatars),
                        summary = getString(R.string.cache_review_resource_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    )
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_engine_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    choice(
                        key = cfg.ENGINE,
                        title = getString(R.string.review_download_engine),
                        entriesRes = R.array.review_download_engine_entries,
                        valuesRes = R.array.review_download_engine_values,
                        defaultValue = "auto",
                        summary = getString(R.string.review_download_engine_summary)
                    )
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_data_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.DATA, "数据协议请求使用此配额，与回复、资源、网页分开；当前支持 idea_comment 段评，章评、书评走网页。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.PAGE_SIZE, "单次请求的条数参数；仍按服务端游标取完全部页面。服务端实际返回数量会记录。")
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_replies_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.REPLIES, "所有楼中楼数据请求共享此配额，不占用评论数据请求配额。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.REPLY_SIZE, "回复单页请求条数；不限制回复总量。"),
                    switch(
                        key = cfg.REUSE_REPLIES,
                        title = getString(R.string.review_download_reuse_replies),
                        summary = "接口内嵌回复已完整时直接保存；关闭后从回复接口重新取全。",
                        defaultValue = true
                    )
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_pages_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.PAGES, "同时加载、展开的传统网页数量；结构化数据不占用此阶段。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.STABLE_INTERVAL, "只作用于传统网页展开和稳定检测；0 表示不主动等待。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.STABLE_ROUNDS, "传统网页连续多少轮内容稳定才结束；数据接口按明确完成状态结束。")
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_resources_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.RESOURCES, "全局资源请求并发，含头像、评论图片、样式表和字体；不再按每个页面分别建配额。"),
                    switch(
                        key = cfg.REUSE_RESOURCES,
                        title = getString(R.string.review_download_reuse_resources),
                        summary = "按准确 URL 和图片处理要求复用资源；关闭后重新下载。失败不会当作缓存命中。",
                        defaultValue = true
                    )
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_heavy_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.HEAVY, "资源改写、图片压缩、HTML 序列化与快照提交；网络等待不占用此配额。")
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_timeout_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.PAGE_TIMEOUT, "传统网页加载和展开的总时间；0 表示不设超时。网页失败直接报告，不自动换引擎。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.DATA_TIMEOUT, "一次数据请求（含正文读取）的总时间；0 表示不设超时。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.RESOURCE_TIMEOUT, "一次资源请求（含文件传输）的总时间；0 表示不设超时。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.RETRIES, "数据及资源 HTTP 请求初次失败后额外重试次数；0 不重试。每次失败、重试均记录，取消不重试。"),
                    numberSpec(io.legado.app.help.review.ReviewDownloadConfig.Number.RETRY_INTERVAL, "基础等待时间；0 不主动等待。"),
                    choice(
                        key = cfg.BACKOFF,
                        title = getString(R.string.review_download_backoff),
                        entriesRes = R.array.review_download_backoff_entries,
                        valuesRes = R.array.review_download_backoff_values,
                        defaultValue = "fixed"
                    )
                )
            ),
            SettingSectionSpec(
                title = getString(R.string.review_download_integrity_section),
                    collapsible = true,
                    collapsedByDefault = true,
                items = listOf(
                    switch(
                        key = cfg.REQUIRE_AVATARS,
                        title = getString(R.string.review_download_require_avatars),
                        summary = getString(R.string.review_download_requirement_summary),
                        defaultValue = true
                    ),
                    switch(
                        key = cfg.REQUIRE_IMAGES,
                        title = getString(R.string.review_download_require_images),
                        summary = getString(R.string.review_download_requirement_summary),
                        defaultValue = true
                    ),
                    switch(
                        key = cfg.REQUIRE_CSS,
                        title = getString(R.string.review_download_require_css),
                        summary = getString(R.string.review_download_requirement_summary),
                        defaultValue = true
                    ),
                    switch(
                        key = cfg.REQUIRE_FONTS,
                        title = getString(R.string.review_download_require_fonts),
                        summary = "同一字体按 CSS 候选关系取可用格式；全部候选失败才触发此项。${getString(R.string.review_download_requirement_summary)}",
                        defaultValue = true
                    )
                )
            )
        )
    }


    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LOCAL_PASSWORD = "localPassword"
        private const val KEY_MEDIA_BUTTON_ON_EXIT = "mediaButtonOnExit"
        private const val KEY_AUTO_UPDATE_VARIANT = "autoUpdateVariant"
    }
}