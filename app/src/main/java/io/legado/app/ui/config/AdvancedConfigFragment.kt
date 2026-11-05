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
 * 高级设置：渲染性能、隐私与安全、系统（含语言）三类兜底项。
 *
 * 这里是「其它设置」改名重组后的产物：原先一个页面塞 30 多项、名字却叫「其它」，
 * 用户完全不知道里面有什么。现在它是明确的「平时不用、需要时能调」的兜底页。
 * 媒体按键已归入「朗读与音频」、评论抓取已独立为 CommentConfigFragment、
 * 语言并入「系统」区首项，均不再属于本页。
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
                    title = str(R.string.perf_rendering_section),
                    items = listOf(
                        intChoice(
                            key = PreferKey.fastScrollerTouchTargetDp,
                            title = str(R.string.fast_scroller_touch_target),
                            entriesRes = R.array.fast_scroller_touch_target_entries,
                            valuesRes = R.array.fast_scroller_touch_target_values,
                            defaultValue = AppConfig.DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP
                        ),
                        switch(
                            key = PreferKey.antiAlias,
                            title = str(R.string.anti_alias),
                            summary = str(R.string.pref_anti_alias_summary),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.highBrush,
                            title = str(R.string.high_brush_title),
                            summary = str(R.string.high_brush_summary),
                            defaultValue = true
                        ),
                        numberAction(
                            key = PreferKey.bitmapCacheSize,
                            title = str(R.string.bitmap_cache_size),
                            summary = str(R.string.bitmap_cache_size_summary, AppConfig.bitmapCacheSize.toString()),
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
                            title = str(R.string.image_retain_number),
                            summary = str(R.string.image_retain_number_summary, AppConfig.imageRetainNum.toString()),
                            min = 0,
                            max = 999,
                            value = AppConfig.imageRetainNum,
                            onSelected = { AppConfig.imageRetainNum = it }
                        ),
                        numberAction(
                            key = PreferKey.sourceEditMaxLine,
                            title = str(R.string.source_edit_text_max_line),
                            summary = str(
                                R.string.source_edit_max_line_summary,
                                AppConfig.sourceEditMaxLine.toString()
                            ),
                            min = 10,
                            max = 200_000,
                            value = AppConfig.sourceEditMaxLine,
                            onSelected = { AppConfig.sourceEditMaxLine = it }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.adv_privacy_section),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_LOCAL_PASSWORD,
                            title = str(R.string.set_local_password),
                            summary = str(R.string.set_local_password_summary),
                            onClick = ::alertLocalPassword
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.adv_system_section),
                    items = listOf(
                        // 语言原先独占一个无标题分区，只有一项却占一格，且属于「系统」域，
                        // 并入系统区后与自动更新、日志等并列
                        choice(
                            key = KEY_LANGUAGE,
                            title = str(R.string.language),
                            entriesRes = R.array.language,
                            valuesRes = R.array.language_value,
                            defaultValue = "auto"
                        ),
                        switch(
                            key = PreferKey.processText,
                            title = str(R.string.add_to_text_context_menu_t),
                            summary = str(R.string.add_to_text_context_menu_s),
                            defaultValue = true
                        ),
                        switch(
                            key = KEY_AUTO_UPDATE_VARIANT,
                            title = str(R.string.auto_update),
                            summary = str(R.string.auto_update_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.recordLog,
                            title = str(R.string.record_log),
                            summary = str(R.string.record_debug_log),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.recordHeapDump,
                            title = str(R.string.record_heap_dump_t),
                            summary = str(R.string.record_heap_dump_s),
                            defaultValue = false
                        )
                    )
                )
            )
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
            title = str(R.string.set_local_password),
            hint = "password",
            message = str(R.string.set_local_password_summary),
            onPositive = { LocalConfig.password = it }
        )
    }


    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LOCAL_PASSWORD = "localPassword"
        private const val KEY_AUTO_UPDATE_VARIANT = "autoUpdateVariant"
    }
}