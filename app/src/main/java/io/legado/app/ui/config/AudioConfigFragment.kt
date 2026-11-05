package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudConfigGroup
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import splitties.init.appCtx

/**
 * 朗读与音频：把「听书」这条链路上的配置从阅读页的弹窗里提到全局设置。
 *
 * 这些开关原本只在两处可达：阅读页朗读面板 › 设置弹窗（四组 Tab），
 * 以及音频播放页的三点菜单。两者都不是设置页，用户要改「朗读时要不要唤醒锁」
 * 得先打开一本书、开始朗读、再进弹窗，而且搜不到。
 *
 * 这里收的是**全局项**（存 PreferKey）。按书保存的播放模式、倍速、跳片头尾
 * 仍然留在播放页菜单里——全局页无法替某一本书做决定，硬搬过来只会让人
 * 在设置页改完发现对当前书无效。这一点写进「音频播放」区的说明里。
 *
 * 多角色、配乐、提示词、发言人等成组的进阶项仍走原弹窗，避免把上百项
 * 摊平到一页；弹窗入口作为普通 action 参与搜索。
 */
class AudioConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.audio_config_title

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.read_aloud),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_READ_ALOUD_DETAIL,
                            title = str(R.string.read_aloud_detail_entry),
                            summary = str(R.string.read_aloud_detail_entry_summary),
                            searchKeys = listOf(
                                "多角色", "AI 朗读", "配乐", "音效", "提示词",
                                "发言人", "预处理", "朗读配置"
                            ),
                            onClick = { showDialogFragment(ReadAloudConfigDialog()) }
                        ),
                        toggle(
                            key = PreferKey.readAloudWakeLock,
                            title = str(R.string.read_aloud_wake_lock),
                            summary = str(R.string.read_aloud_wake_lock_summary),
                            defaultValue = false,
                            searchKeys = listOf("唤醒锁", "后台朗读")
                        ),
                        toggle(
                            key = PreferKey.ignoreAudioFocus,
                            title = str(R.string.ignore_audio_focus_title),
                            summary = str(R.string.ignore_audio_focus_summary),
                            defaultValue = false,
                            searchKeys = listOf("音频焦点", "打断", "混音")
                        ),
                        // 「来电暂停」只在忽略音频焦点之后才有意义：不忽略焦点时
                        // 系统本来就会在通话时把朗读压掉，开关放了也是空转
                        toggle(
                            key = PreferKey.pauseReadAloudWhilePhoneCalls,
                            title = str(R.string.pause_read_aloud_while_phone_calls_title),
                            summary = str(R.string.pause_read_aloud_while_phone_calls_summary),
                            defaultValue = false,
                            enabled = AppConfig.ignoreAudioFocus,
                            searchKeys = listOf("来电", "通话", "暂停朗读")
                        ),
                        floatingBallSwitch(),
                        toggle(
                            key = PreferKey.readAloudByPage,
                            title = str(R.string.read_aloud_by_page),
                            summary = str(R.string.read_aloud_by_page_summary),
                            defaultValue = false,
                            searchKeys = listOf("按页朗读", "翻页朗读")
                        ),
                        toggle(
                            key = PreferKey.streamReadAloudAudio,
                            title = str(R.string.stream_read_aloud_audio),
                            summary = str(R.string.stream_read_aloud_audio_summary),
                            defaultValue = false,
                            searchKeys = listOf("流式", "边合成边播")
                        ),
                        toggle(
                            key = PreferKey.ttsFollowSys,
                            title = str(R.string.flow_sys),
                            summary = str(R.string.tts_follow_sys_summary),
                            defaultValue = true,
                            searchKeys = listOf("语速", "系统语速")
                        ),
                        SettingSliderSpec(
                            key = PreferKey.ttsSpeechRate,
                            title = str(R.string.read_aloud_speed),
                            summary = formatSpeechRate(AppConfig.ttsSpeechRate),
                            value = AppConfig.ttsSpeechRate.coerceIn(0, 45),
                            valueRange = 0..45,
                            enabled = !AppConfig.ttsFlowSys,
                            valueFormatter = ::formatSpeechRate,
                            onValueChange = { updateIntSetting(PreferKey.ttsSpeechRate, it) }
                        ),
                        SettingActionSpec(
                            key = PreferKey.ttsEngine,
                            title = str(R.string.speak_engine),
                            summary = str(R.string.speak_engine_summary),
                            searchKeys = listOf("TTS", "语音引擎", "HTTP TTS", "系统语音"),
                            onClick = { showDialogFragment(SpeakEngineDialog()) }
                        )
                    )
                ),
                // 三枚媒体键原本散落在「高级设置 › 媒体按键」和朗读弹窗两处，
                // 加上音频播放器的唤醒锁，四者在语义上都是「播放过程中系统/硬件
                // 怎么响应」，合成一区；单项独占一个分区标题不值当
                SettingSectionSpec(
                    title = str(R.string.audio_section_play_controls),
                    items = listOf(
                        toggle(
                            key = PreferKey.mediaButtonPerNext,
                            title = str(R.string.pref_media_button_per_next),
                            summary = str(R.string.pref_media_button_per_next_summary),
                            defaultValue = false,
                            searchKeys = listOf("媒体键", "耳机按键", "上一首", "下一首")
                        ),
                        toggle(
                            key = PreferKey.readAloudByMediaButton,
                            title = str(R.string.read_aloud_by_media_button_title),
                            summary = str(R.string.read_aloud_by_media_button_summary),
                            defaultValue = false,
                            searchKeys = listOf("耳机按键", "启动朗读")
                        ),
                        toggle(
                            key = PreferKey.mediaButtonOnExit,
                            title = str(R.string.media_button_on_exit_title),
                            summary = str(R.string.media_button_on_exit_summary),
                            defaultValue = true,
                            searchKeys = listOf("媒体键", "退出后响应")
                        ),
                        toggle(
                            key = PreferKey.audioPlayWakeLock,
                            title = str(R.string.audio_play_wake_lock),
                            summary = str(R.string.audio_play_wake_lock_summary),
                            defaultValue = false,
                            // 播放模式 / 倍速 / 跳片头尾按书保存在 Book 实体上，
                            // 全局页给不了统一值；把这些词挂在这里，搜索「播放模式」
                            // 至少能落到本区，再由音频播放页的菜单去改
                            searchKeys = listOf(
                                "音频播放器", "播放模式", "播放速度", "倍速",
                                "跳过片头片尾", "唤醒锁"
                            )
                        )
                    )
                )
            )
        )
    }

    /**
     * 悬浮球开关要过悬浮窗权限：直接写偏好会让用户以为开了，实际什么都不显示。
     * 逻辑与朗读弹窗里的同名开关一致——两处改的是同一个 key，行为必须一致。
     */
    private fun floatingBallSwitch(): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = PreferKey.showReadAloudFloatingBall,
            title = str(R.string.read_aloud_floating_ball),
            summary = str(R.string.read_aloud_floating_ball_summary),
            checked = booleanSetting(PreferKey.showReadAloudFloatingBall, true),
            searchKeys = listOf("悬浮球", "悬浮窗", "听书悬浮"),
            onCheckedChange = ::setFloatingBallEnabled
        )
    }

    private fun setFloatingBallEnabled(enabled: Boolean) {
        if (!enabled) {
            updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
            return
        }
        PermissionsCompat.Builder()
            .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
            .rationale(R.string.read_aloud_floating_ball_permission_rationale)
            .onGranted {
                updateBooleanSetting(PreferKey.showReadAloudFloatingBall, true)
            }
            .onDenied {
                updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
            }
            .onError {
                updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
            }
            .request()
    }

    private fun toggle(
        key: String,
        title: String,
        summary: String? = null,
        defaultValue: Boolean = false,
        enabled: Boolean = true,
        searchKeys: List<String> = emptyList()
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            enabled = enabled,
            searchKeys = searchKeys,
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    private fun formatSpeechRate(value: Int): String {
        return ((value.coerceIn(0, 45) + 5) / 10f).toString()
    }

    /**
     * 朗读正在跑的时候改这些项要立刻生效，否则用户改完语速发现要等下一章。
     * 与朗读弹窗里的处理保持一致，避免同一个 key 在两处改出两种行为。
     */
    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                if (BaseReadAloudService.isRun) {
                    postEvent(EventBus.MEDIA_BUTTON, false)
                }
            }

            PreferKey.ttsFollowSys, PreferKey.ttsSpeechRate -> {
                ReadAloud.upTtsSpeechRate(ctx)
                if (!BaseReadAloudService.pause) {
                    ReadAloud.pause(ctx)
                    ReadAloud.resume(ctx)
                }
            }
        }
    }

    /**
     * 搜索命中弹窗里的项（多角色、配乐、提示词、发言人…）时，本页 spec 里没有这个 key。
     * 直接打开弹窗并切到对应分组，用户才能落到自己搜的那一项；否则点了结果只会停在
     * 一个没有任何高亮的页面上。
     */
    override fun onTargetKeyUnresolved(rawKey: String) {
        if (ReadAloudConfigGroup.groupOfKey(rawKey) == null) return
        showDialogFragment(ReadAloudConfigDialog.newInstance(rawKey))
    }

    companion object {
        /**
         * 朗读弹窗入口的 key。它不是某个偏好项（弹窗自己管一堆 key），
         * 但搜索索引需要一个稳定标识来深链到这一行。
         */
        private const val KEY_READ_ALOUD_DETAIL = "readAloudDetail"
    }

}
