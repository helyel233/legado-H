package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.ai.AiContentConsent
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.ReadAloudSpeakerLoudnessManager
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.role.ReadAloudQuotePair
import io.legado.app.help.readaloud.role.ReadAloudRolePreprocessor
import io.legado.app.help.readaloud.role.ReadAloudSoundEffectPreprocessor
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

private const val KEY_AI_READ_ALOUD_BGM_MANAGE = "aiReadAloudBgmManage"
private const val KEY_AI_READ_ALOUD_USAGE_RECORDS = "aiReadAloudUsageRecords"
private const val KEY_AI_READ_ALOUD_MODEL_ROUTING = "aiReadAloudModelRouting"
private const val KEY_READ_ALOUD_SPEAKER_MANAGE = "readAloudSpeakerManage"
private const val KEY_READ_ALOUD_LOUDNESS_RESET = "readAloudSpeakerLoudnessReset"
private const val KEY_MEDIA_BUTTON_PER_NEXT = PreferKey.mediaButtonPerNext
private const val KEY_SYS_TTS_CONFIG = "sysTtsConfig"
private const val SPEAKER_MANAGE_SUMMARY = R.string.ra_speaker_manage_summary

enum class ReadAloudConfigGroup(
    @param:StringRes val titleRes: Int,
    val preferenceKeys: Set<String>
) {
    Base(
        R.string.ra_group_base,
        setOf(
            PreferKey.ignoreAudioFocus,
            PreferKey.pauseReadAloudWhilePhoneCalls,
            PreferKey.readAloudWakeLock,
            PreferKey.showReadAloudFloatingBall,
            KEY_MEDIA_BUTTON_PER_NEXT,
            // 媒体键的三个行为开关必须同域：原先 mediaButtonOnExit / readAloudByMediaButton
            // 落在「高级设置 › 媒体按键」，mediaButtonPerNext 却在这里，用户要配媒体键得跑两处
            PreferKey.mediaButtonOnExit,
            PreferKey.readAloudByMediaButton
        )
    ),
    Reading(
        R.string.ra_group_reading,
        setOf(
            PreferKey.readAloudByPage,
            PreferKey.streamReadAloudAudio,
            PreferKey.ttsFollowSys,
            PreferKey.ttsSpeechRate
        )
    ),
    AiRole(
        R.string.ra_group_multi_role,
        setOf(
            PreferKey.aiReadAloudRoleEnabled,
            KEY_AI_READ_ALOUD_MODEL_ROUTING,
            PreferKey.aiReadAloudRoleBackupModelId,
            PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds,
            PreferKey.aiReadAloudAudioModelId,
            PreferKey.aiReadAloudAudioBackupModelId,
            PreferKey.aiReadAloudAutoCreateCharacters,
            PreferKey.aiReadAloudAutoCreateCharacterPrompt,
            PreferKey.aiReadAloudAutoCreateAvatar,
            KEY_READ_ALOUD_SPEAKER_MANAGE,
            PreferKey.aiReadAloudRoleMode,
            PreferKey.aiReadAloudRolePreprocess,
            PreferKey.aiReadAloudRoleThreadCount,
            PreferKey.aiReadAloudRoleContextParagraphs,
            PreferKey.aiReadAloudRoleMergeGapParagraphs,
            PreferKey.aiReadAloudRolePrompt,
            KEY_AI_READ_ALOUD_USAGE_RECORDS,
            PreferKey.aiReadAloudBgmEnabled,
            KEY_AI_READ_ALOUD_BGM_MANAGE,
            PreferKey.aiReadAloudBgmPrompt,
            PreferKey.aiReadAloudSoundEffectPrompt,
            PreferKey.aiReadAloudBgmVolume,
            PreferKey.aiReadAloudSfxVolume,
            PreferKey.readAloudSpeakerLoudnessEnabled,
            KEY_READ_ALOUD_LOUDNESS_RESET
        )
    ),
    Engine(
        R.string.ra_group_engine,
        setOf(
            PreferKey.ttsEngine,
            KEY_READ_ALOUD_SPEAKER_MANAGE,
            KEY_SYS_TTS_CONFIG
        )
    );

    companion object {
        private val hiddenPreferenceKeys = setOf(
            PreferKey.aiReadAloudRoleModelId,
            PreferKey.readAloudTargetVoiceVolume,
            PreferKey.readAloudMaxSpeakerGain,
            PreferKey.readAloudNarratorBaseGain
        )
        val allPreferenceKeys: Set<String> =
            values().flatMap { it.preferenceKeys }.toSet() + hiddenPreferenceKeys

        /**
         * 搜索深链用：把一个偏好键还原成它所在的分组。
         *
         * 弹窗一次只渲染一个 Tab，所以带着 key 打开时必须先切到对应 Tab，
         * 否则目标项根本不在 spec 里，定位会静默失败。
         */
        fun groupOfKey(key: String): ReadAloudConfigGroup? =
            values().firstOrNull { key in it.preferenceKeys }
    }
}

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"
    private val groupTabs = arrayListOf<Pair<ReadAloudConfigGroup, TextView>>()
    private var selectedGroup = ReadAloudConfigGroup.Base

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            val maxHeight = minOf(
                (resources.displayMetrics.heightPixels * 0.72f).toInt(),
                620.dpToPx()
            ).coerceAtLeast(360.dpToPx())
            setLayout(0.9f, maxHeight)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        groupTabs.clear()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = requireContext().dialogSurfaceBackground
            clipToOutline = true
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 8.dpToPx())
        }
        val tabBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            setBackgroundResource(R.drawable.bg_bookshelf_tag_track)
        }
        ReadAloudConfigGroup.values().forEach { group ->
            val tab = TextView(requireContext()).apply {
                text = requireContext().getString(group.titleRes)
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = requireContext().uiTypeface()
                isSingleLine = true
                background = UiCorner.actionSelector(
                    Color.TRANSPARENT,
                    requireContext().themeCardColorOrDefault(),
                    UiCorner.actionRadius(requireContext())
                )
                setOnClickListener { selectGroup(group) }
            }
            groupTabs.add(group to tab)
            tabBar.addView(
                tab,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        root.addView(
            tabBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                42.dpToPx()
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        )
        root.addView(
            FrameLayout(requireContext()).apply { id = R.id.tag1 },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        container?.addView(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val targetKey = arguments?.getString(ARG_TARGET_KEY)?.takeIf { it.isNotBlank() }
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) {
            preferenceFragment = ReadAloudPreferenceFragment().apply {
                arguments = Bundle().apply { putString(ARG_TARGET_KEY, targetKey) }
            }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.tag1, preferenceFragment, readAloudPreferTag)
            .commit()
        // Tab 高亮由外层管，内层自己切分组不会同步过来，所以这里先落到目标分组
        selectGroup(targetKey?.let { ReadAloudConfigGroup.groupOfKey(it) } ?: selectedGroup)
    }

    private fun selectGroup(group: ReadAloudConfigGroup) {
        selectedGroup = group
        groupTabs.forEach { (tabGroup, tab) ->
            val selected = tabGroup == group
            tab.isSelected = selected
            tab.setTextColor(
                if (selected) requireContext().primaryColor
                else requireContext().primaryTextColor
            )
        }
        (childFragmentManager.findFragmentByTag(readAloudPreferTag) as? ReadAloudPreferenceFragment)
            ?.selectGroup(group)
    }

    companion object {
        /**
         * 打开弹窗时要定位到的偏好键。
         *
         * 搜索索引把所有设置项平铺成一份结果列表，弹窗里的「智能配乐」「发言人管理」
         * 也在其中；点中它们时先落到「朗读与音频」页，再由该页把 key 转交到这里。
         */
        const val ARG_TARGET_KEY = "readAloudTargetKey"

        fun newInstance(targetKey: String? = null): ReadAloudConfigDialog {
            return ReadAloudConfigDialog().apply {
                if (!targetKey.isNullOrBlank()) {
                    arguments = Bundle().apply { putString(ARG_TARGET_KEY, targetKey) }
                }
            }
        }
    }

    class ReadAloudPreferenceFragment : ComposeSettingFragment(),
        SpeakEngineDialog.CallBack {

        private var selectedGroup = ReadAloudConfigGroup.Base

        /**
         * 构建搜索索引时置 true：一次给出四个分组的全部条目。
         *
         * 弹窗一次只渲染一个 Tab，按默认方式构 spec 只能索引到「基础」那一组，
         * 多角色、配乐、提示词等三十来项就永远搜不到。
         */
        internal var indexAllGroups: Boolean = false

        private var targetScrollDone = false

        override val titleRes: Int = R.string.aloud_config

        override val applyActivityTitle: Boolean = false

        override val autoOpenTargetItem: Boolean = false

        private val speakEngineSummary: String
            get() {
                val route = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
                val engineName = when (route.engineType) {
                    SpeechRoute.ENGINE_HTTP -> route.engineValue.toLongOrNull()
                        ?.let { appDb.httpTTSDao.getName(it) }
                        ?: "HTTP TTS"
                    SpeechRoute.ENGINE_SYSTEM -> route.speakerName.ifBlank { str(R.string.system_tts) }
                    else -> str(R.string.system_tts)
                }
                return buildList {
                    add(engineName)
                    route.speakerName
                        .takeIf { it.isNotBlank() && it != engineName }
                        ?.let(::add)
                    route.emotionName.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(" · ")
            }

        /**
         * 由外层通过 arguments 传进来的目标键。只有从搜索深链进来时才非空。
         *
         * 用 arguments 而不是 requestScrollTo：弹窗会经历一次子 Fragment 事务提交，
         * 之前设置的状态在重建后会丢，arguments 是唯一能跨重建带过来的东西。
         */
        private fun innerTargetKey(): String? =
            arguments?.getString(ARG_TARGET_KEY)?.trim()?.takeIf { it.isNotBlank() }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // 只在首次创建时定位；从别的页面返回或旋转重建不应该再跳一次
            if (savedInstanceState != null) return
            val targetKey = innerTargetKey() ?: return
            ReadAloudConfigGroup.groupOfKey(targetKey)?.let { selectedGroup = it }
        }

        override fun onResume() {
            super.onResume()
            // 定位只做一次：从「发言人管理」「消耗记录」等二级页返回时不该再跳回目标项
            if (targetScrollDone) return
            val targetKey = innerTargetKey() ?: return
            targetScrollDone = true
            ReadAloudConfigGroup.groupOfKey(targetKey)?.let { group ->
                if (selectedGroup != group) selectGroup(group)
            }
            requestScrollTo(targetKey)
        }

        override fun buildPageSpec(): SettingPageSpec {
            if (indexAllGroups) {
                // 索引要的是「这个弹窗里能改哪些东西」，与当前停在哪个 Tab 无关
                return SettingPageSpec(
                    titleRes = titleRes,
                    sections = ReadAloudConfigGroup.values().map { group ->
                        SettingSectionSpec(title = str(group.titleRes), items = itemsOf(group))
                    }
                )
            }
            return SettingPageSpec(
                titleRes = titleRes,
                sections = listOf(
                    SettingSectionSpec(
                        title = str(selectedGroup.titleRes),
                        items = itemsOf(selectedGroup)
                    )
                )
            )
        }

        private fun itemsOf(group: ReadAloudConfigGroup): List<SettingItemSpec> = when (group) {
            ReadAloudConfigGroup.Base -> baseItems()
            ReadAloudConfigGroup.Reading -> readingItems()
            ReadAloudConfigGroup.AiRole -> aiRoleItems()
            ReadAloudConfigGroup.Engine -> engineItems()
        }

        override fun onSettingPreferenceChanged(key: String) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ttsFollowSys,
                PreferKey.ttsSpeechRate -> {
                    applySpeechRate()
                }

                PreferKey.showReadAloudFloatingBall -> Unit

                PreferKey.aiReadAloudRoleEnabled,
                PreferKey.aiReadAloudRoleModelId,
                PreferKey.aiReadAloudRoleBackupModelId,
                PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds,
                PreferKey.aiReadAloudAudioModelId,
                PreferKey.aiReadAloudAudioBackupModelId,
                PreferKey.aiReadAloudAutoCreateCharacters,
                PreferKey.aiReadAloudAutoCreateCharacterPrompt,
                PreferKey.aiReadAloudAutoCreateAvatar,
                PreferKey.aiReadAloudRoleMode,
                PreferKey.aiReadAloudRolePreprocess,
                PreferKey.aiReadAloudRoleThreadCount,
                PreferKey.aiReadAloudRoleContextParagraphs,
                PreferKey.aiReadAloudRoleMergeGapParagraphs,
                PreferKey.aiReadAloudRolePrompt -> {
                    postReadAloudConfigChanged(
                        if (key == PreferKey.aiReadAloudRoleEnabled) {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE
                        } else {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH
                        }
                    )
                }
                PreferKey.aiReadAloudBgmEnabled,
                PreferKey.aiReadAloudBgmPrompt,
                PreferKey.aiReadAloudSoundEffectPrompt,
                PreferKey.aiReadAloudBgmVolume,
                PreferKey.aiReadAloudSfxVolume,
                PreferKey.readAloudSpeakerLoudnessEnabled,
                PreferKey.readAloudTargetVoiceVolume,
                PreferKey.readAloudMaxSpeakerGain,
                PreferKey.readAloudNarratorBaseGain -> {
                    postReadAloudConfigChanged(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
                }
            }
        }

        private fun postReadAloudConfigChanged(scope: String) {
            ReadAloudConfigChangeNotifier.notify(scope)
        }

        override fun upSpeakEngineSummary() {
            refreshSettings()
        }

        fun selectGroup(group: ReadAloudConfigGroup) {
            selectedGroup = group
            refreshSettings()
        }

        private fun applySpeechRate() {
            ReadAloud.upTtsSpeechRate(requireContext())
            if (!BaseReadAloudService.pause) {
                ReadAloud.pause(requireContext())
                ReadAloud.resume(requireContext())
            }
        }

        private fun updateAiRolePreferences() {
            refreshSettings()
        }

        private fun baseItems(): List<SettingItemSpec> {
            return listOf(
                switch(
                    key = PreferKey.ignoreAudioFocus,
                    title = str(R.string.ignore_audio_focus_title),
                    summary = str(R.string.ignore_audio_focus_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.pauseReadAloudWhilePhoneCalls,
                    title = str(R.string.pause_read_aloud_while_phone_calls_title),
                    summary = str(R.string.pause_read_aloud_while_phone_calls_summary),
                    defaultValue = false,
                    enabled = AppConfig.ignoreAudioFocus
                ),
                switch(
                    key = PreferKey.readAloudWakeLock,
                    title = str(R.string.read_aloud_wake_lock),
                    summary = str(R.string.read_aloud_wake_lock_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.showReadAloudFloatingBall,
                    title = str(R.string.read_aloud_floating_ball),
                    summary = str(R.string.read_aloud_floating_ball_summary),
                    checked = booleanSetting(PreferKey.showReadAloudFloatingBall, true),
                    onCheckedChange = ::setReadAloudFloatingBallEnabled
                ),
                switch(
                    key = KEY_MEDIA_BUTTON_PER_NEXT,
                    title = str(R.string.pref_media_button_per_next),
                    summary = str(R.string.pref_media_button_per_next_summary),
                    defaultValue = false
                ),
                // 下面两项从「高级设置 › 媒体按键」迁来：三枚媒体键开关收敛到朗读域，
                // 避免同一件事要在两个设置页各改一半
                switch(
                    key = PreferKey.readAloudByMediaButton,
                    title = str(R.string.read_aloud_by_media_button_title),
                    summary = str(R.string.read_aloud_by_media_button_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.mediaButtonOnExit,
                    title = str(R.string.media_button_on_exit_title),
                    summary = str(R.string.media_button_on_exit_summary),
                    defaultValue = true
                )
            )
        }

        private fun setReadAloudFloatingBallEnabled(enabled: Boolean) {
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

        private fun readingItems(): List<SettingItemSpec> {
            return listOf(
                switch(
                    key = PreferKey.readAloudByPage,
                    title = str(R.string.read_aloud_by_page),
                    summary = str(R.string.read_aloud_by_page_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.streamReadAloudAudio,
                    title = str(R.string.stream_read_aloud_audio),
                    summary = str(R.string.stream_read_aloud_audio_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.ttsFollowSys,
                    title = str(R.string.flow_sys),
                    summary = str(R.string.tts_follow_sys_summary),
                    defaultValue = true
                ),
                slider(
                    key = PreferKey.ttsSpeechRate,
                    title = str(R.string.read_aloud_speed),
                    summary = formatSpeechRate(AppConfig.ttsSpeechRate),
                    value = AppConfig.ttsSpeechRate.coerceIn(0, 45),
                    range = 0..45,
                    enabled = !AppConfig.ttsFlowSys
                )
            )
        }

        private fun aiRoleItems(): List<SettingItemSpec> {
            val enabled = AppConfig.aiReadAloudRoleEnabled
            val hasModel = AppConfig.aiReadAloudRoleModelConfig != null
            val fullMode = AppConfig.aiReadAloudRoleMode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL
            val audioEnabled = enabled && AppConfig.aiReadAloudBgmEnabled
            // 搜索索引只要标题和别名，摘要里的计数没必要为它跑数据库（配乐、音效、消耗记录
            // 各一次查询，还会把 1000 条记录捞到主线程）。索引态下让摘要留空。
            val bgmCount = if (indexAllGroups) -1 else appDb.readAloudBgmDao
                .enabledTracksByType(io.legado.app.data.entities.ReadAloudBgmTrack.TYPE_BGM)
                .size
            val sfxCount = if (indexAllGroups) -1 else appDb.readAloudBgmDao
                .enabledTracksByType(io.legado.app.data.entities.ReadAloudBgmTrack.TYPE_SFX)
                .size
            val loudnessCount = if (indexAllGroups) -1 else
                ReadAloudSpeakerLoudnessManager.learnedSpeakerCount()
            return listOf(
                switch(
                    key = PreferKey.aiReadAloudRoleEnabled,
                    title = str(R.string.ra_multi_role),
                    summary = if (hasModel) str(R.string.ra_multi_role_summary) else str(R.string.ra_multi_role_need_model),
                    checked = enabled,
                    enabled = hasModel,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            AppConfig.aiReadAloudRoleEnabled = false
                        } else {
                            AiContentConsent.withConsent(requireActivity(), str(R.string.ra_ai_multi_role_consent)) {
                                AppConfig.aiReadAloudRoleEnabled = true
                            }
                        }
                    }
                ),
                action(
                    key = KEY_AI_READ_ALOUD_MODEL_ROUTING,
                    title = str(R.string.ra_model_routing_title),
                    summary = aiReadAloudModelRoutingSummary(),
                    enabled = hasModel,
                    onClick = ::showAiReadAloudModelRoutingDialog
                ),
                switch(
                    key = PreferKey.aiReadAloudAutoCreateCharacters,
                    title = str(R.string.ra_auto_create_role),
                    summary = if (AppConfig.aiReadAloudAutoCreateCharacters) {
                        str(R.string.ra_auto_create_role_summary)
                    } else {
                        str(R.string.ra_auto_create_role_off_summary)
                    },
                    checked = AppConfig.aiReadAloudAutoCreateCharacters,
                    enabled = enabled,
                    onCheckedChange = { AppConfig.aiReadAloudAutoCreateCharacters = it }
                ),
                action(
                    key = PreferKey.aiReadAloudAutoCreateCharacterPrompt,
                    title = str(R.string.ra_auto_create_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudAutoCreateCharacterPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultAutoCreateCharacterPrompt
                    ),
                    enabled = enabled && AppConfig.aiReadAloudAutoCreateCharacters,
                    onClick = ::showAutoCreateCharacterPromptDialog
                ),
                switch(
                    key = PreferKey.aiReadAloudAutoCreateAvatar,
                    title = str(R.string.ra_auto_avatar),
                    summary = when {
                        !AppConfig.aiReadAloudAutoCreateAvatar -> str(R.string.ra_auto_avatar_off_summary)
                        AppConfig.aiEnabledImageProviders.isEmpty() -> str(R.string.ra_auto_avatar_no_provider)
                        else -> str(R.string.ra_auto_avatar_summary)
                    },
                    checked = AppConfig.aiReadAloudAutoCreateAvatar,
                    enabled = enabled && AppConfig.aiReadAloudAutoCreateCharacters,
                    onCheckedChange = { AppConfig.aiReadAloudAutoCreateAvatar = it }
                ),
                action(
                    key = KEY_READ_ALOUD_SPEAKER_MANAGE,
                    title = str(R.string.ra_speaker_manage),
                    summary = str(SPEAKER_MANAGE_SUMMARY),
                    onClick = { startActivity<SpeakerGroupManageActivity>() }
                ),
                choice(
                    key = PreferKey.aiReadAloudRoleMode,
                    title = str(R.string.ra_process_mode),
                    entriesRes = R.array.ai_read_aloud_role_mode_title,
                    valuesRes = R.array.ai_read_aloud_role_mode_value,
                    selectedValue = AppConfig.aiReadAloudRoleMode,
                    enabled = enabled
                ),
                action(
                    key = PreferKey.aiReadAloudRolePreprocess,
                    title = str(R.string.ra_preprocess_rules),
                    summary = str(
                        R.string.ra_preprocess_summary,
                        if (AppConfig.aiReadAloudRoleUsingDefaultPreprocessRules) {
                            str(R.string.ra_builtin_default_prefix)
                        } else ""
                    ),
                    enabled = enabled,
                    onClick = ::showMultiRolePreprocessRulesDialog
                ),
                action(
                    key = PreferKey.aiReadAloudRoleThreadCount,
                    title = str(R.string.ra_thread_count),
                    summary = str(R.string.ra_thread_count_summary, AppConfig.aiReadAloudRoleThreadCount),
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog(str(R.string.ra_thread_count), AppConfig.aiReadAloudRoleThreadCount, 1, 10) {
                        AppConfig.aiReadAloudRoleThreadCount = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRoleContextParagraphs,
                    title = str(R.string.ra_context_paragraphs),
                    summary = str(R.string.ra_context_paragraphs_summary, AppConfig.aiReadAloudRoleContextParagraphs),
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog(str(R.string.ra_context_paragraphs), AppConfig.aiReadAloudRoleContextParagraphs, 0, 20) {
                        AppConfig.aiReadAloudRoleContextParagraphs = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRoleMergeGapParagraphs,
                    title = str(R.string.ra_merge_gap),
                    summary = str(R.string.ra_merge_gap_summary, AppConfig.aiReadAloudRoleMergeGapParagraphs),
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog(str(R.string.ra_merge_gap), AppConfig.aiReadAloudRoleMergeGapParagraphs, 0, 10) {
                        AppConfig.aiReadAloudRoleMergeGapParagraphs = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRolePrompt,
                    title = str(R.string.ra_role_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudRolePrompt,
                        useDefault = AppConfig.aiReadAloudRoleUsingDefaultPrompt
                    ),
                    enabled = enabled,
                    onClick = ::showMultiRolePromptDialog
                ),
                action(
                    key = KEY_AI_READ_ALOUD_USAGE_RECORDS,
                    title = str(R.string.ra_usage_records),
                    summary = if (indexAllGroups) null
                    else appDb.aiReadAloudUsageRecordDao.list(limit = 1000).size
                        .let { if (it > 0) str(R.string.ra_usage_records_summary, it) else str(R.string.ra_usage_records_empty) },
                    onClick = {
                        startActivity(
                            android.content.Intent(requireContext(), AiReadAloudUsageRecordActivity::class.java)
                        )
                    }
                ),
                switch(
                    key = PreferKey.aiReadAloudBgmEnabled,
                    title = str(R.string.ra_bgm),
                    summary = if (AppConfig.aiReadAloudBgmEnabled) {
                        str(R.string.ra_bgm_summary)
                    } else {
                        str(R.string.ra_bgm_off_summary)
                    },
                    checked = AppConfig.aiReadAloudBgmEnabled,
                    enabled = enabled,
                    onCheckedChange = { AppConfig.aiReadAloudBgmEnabled = it }
                ),
                action(
                    key = KEY_AI_READ_ALOUD_BGM_MANAGE,
                    title = str(R.string.ra_audio_settings),
                    summary = if (bgmCount < 0) null else str(R.string.ra_audio_settings_summary, bgmCount, sfxCount),
                    enabled = enabled,
                    onClick = {
                        startActivity(
                            android.content.Intent(requireContext(), ReadAloudBgmManageActivity::class.java)
                        )
                    }
                ),
                action(
                    key = PreferKey.aiReadAloudBgmPrompt,
                    title = str(R.string.ra_bgm_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudBgmPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultBgmPrompt
                    ),
                    enabled = audioEnabled,
                    onClick = ::showBgmPromptDialog
                ),
                action(
                    key = PreferKey.aiReadAloudSoundEffectPrompt,
                    title = str(R.string.ra_sfx_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudSoundEffectPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultSoundEffectPrompt
                    ),
                    enabled = audioEnabled,
                    onClick = ::showSoundEffectPromptDialog
                ),
                slider(
                    key = PreferKey.aiReadAloudBgmVolume,
                    title = str(R.string.ra_bgm_volume),
                    summary = str(R.string.ra_bgm_volume_summary, AppConfig.aiReadAloudBgmVolume),
                    value = AppConfig.aiReadAloudBgmVolume,
                    range = 0..100,
                    enabled = audioEnabled
                ),
                slider(
                    key = PreferKey.aiReadAloudSfxVolume,
                    title = str(R.string.ra_sfx_volume),
                    summary = str(R.string.ra_sfx_volume_summary, AppConfig.aiReadAloudSfxVolume),
                    value = AppConfig.aiReadAloudSfxVolume,
                    range = 0..100,
                    enabled = audioEnabled
                ),
                switch(
                    key = PreferKey.readAloudSpeakerLoudnessEnabled,
                    title = str(R.string.ra_loudness),
                    summary = if (AppConfig.readAloudSpeakerLoudnessEnabled) {
                        str(R.string.ra_loudness_summary)
                    } else {
                        str(R.string.ra_loudness_off_summary)
                    },
                    checked = AppConfig.readAloudSpeakerLoudnessEnabled,
                    onCheckedChange = { AppConfig.readAloudSpeakerLoudnessEnabled = it }
                ),
                action(
                    key = KEY_READ_ALOUD_LOUDNESS_RESET,
                    title = str(R.string.ra_loudness_reset),
                    summary = when {
                        loudnessCount < 0 -> null
                        loudnessCount > 0 -> str(R.string.ra_loudness_reset_summary, loudnessCount)
                        else -> str(R.string.ra_loudness_reset_empty)
                    },
                    enabled = loudnessCount > 0
                ) {
                    ReadAloudSpeakerLoudnessManager.reset()
                    updateAiRolePreferences()
                    postReadAloudConfigChanged(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
                    toastOnUi(str(R.string.ra_loudness_reset_done))
                }
            )
        }

        private fun engineItems(): List<SettingItemSpec> {
            return listOf(
                action(
                    key = PreferKey.ttsEngine,
                    title = str(R.string.speak_engine),
                    summary = speakEngineSummary,
                    onClick = { showDialogFragment(SpeakEngineDialog()) }
                ),
                action(
                    key = KEY_READ_ALOUD_SPEAKER_MANAGE,
                    title = str(R.string.ra_speaker_manage),
                    summary = str(SPEAKER_MANAGE_SUMMARY),
                    onClick = { startActivity<SpeakerGroupManageActivity>() }
                ),
                action(
                    key = KEY_SYS_TTS_CONFIG,
                    title = str(R.string.sys_tts_config),
                    summary = str(R.string.sys_tts_config_summary),
                    onClick = { IntentHelp.openTTSSetting() }
                )
            )
        }

        private fun switch(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            defaultValue: Boolean,
            enabled: Boolean = true
        ): SettingSwitchSpec {
            return switch(
                key = key,
                title = title,
                summary = summary,
                checked = booleanSetting(key, defaultValue),
                enabled = enabled,
                onCheckedChange = { updateBooleanSetting(key, it) }
            )
        }

        private fun switch(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            checked: Boolean,
            enabled: Boolean = true,
            onCheckedChange: (Boolean) -> Unit
        ): SettingSwitchSpec {
            return SettingSwitchSpec(
                key = key,
                title = title,
                summary = summary,
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }

        private fun slider(
            key: String,
            title: CharSequence,
            summary: CharSequence,
            value: Int,
            range: IntRange,
            enabled: Boolean = true
        ): SettingSliderSpec {
            return SettingSliderSpec(
                key = key,
                title = title,
                summary = summary,
                value = value.coerceIn(range.first, range.last),
                valueRange = range,
                enabled = enabled,
                onValueChange = { updateIntSetting(key, it) }
            )
        }

        private fun action(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            visible: Boolean = true,
            enabled: Boolean = true,
            onClick: () -> Unit
        ): SettingActionSpec {
            return SettingActionSpec(
                key = key,
                title = title,
                summary = summary,
                visible = visible,
                enabled = enabled,
                onClick = onClick
            )
        }

        private fun choice(
            key: String,
            title: CharSequence,
            entriesRes: Int,
            valuesRes: Int,
            selectedValue: String,
            enabled: Boolean = true
        ): SettingChoiceSpec {
            val options = choiceOptions(entriesRes, valuesRes)
            return SettingChoiceSpec(
                key = key,
                title = title,
                summary = choiceLabel(options, selectedValue),
                options = options,
                selectedValue = selectedValue,
                enabled = enabled,
                onSelected = { updateStringSetting(key, it) }
            )
        }

        private fun choiceOptions(
            entriesRes: Int,
            valuesRes: Int
        ): List<SettingChoiceOption> {
            // ctx 而不是 resources：构建搜索索引时这个 Fragment 没有 attach，
            // Fragment.resources 会直接抛 NPE
            val entries = ctx.resources.getStringArray(entriesRes)
            val values = ctx.resources.getStringArray(valuesRes)
            return values.mapIndexed { index, value ->
                SettingChoiceOption(
                    value = value,
                    label = entries.getOrElse(index) { value }
                )
            }
        }

        private fun choiceLabel(
            options: List<SettingChoiceOption>,
            selectedValue: String
        ): String {
            return options.firstOrNull { it.value == selectedValue }
                ?.label
                ?.toString()
                ?: selectedValue
        }

        private fun firstLineSummary(
            value: String,
            useDefault: Boolean
        ): String {
            val prefix = if (useDefault) str(R.string.ra_builtin_default_prefix) else ""
            return prefix + value.lineSequence()
                .firstOrNull()
                ?.take(40)
                ?.ifBlank { null }
                .orEmpty()
        }

        private fun showAiReadAloudModelRoutingDialog() {
            val options = listOf(
                str(R.string.ra_model_role_main, modelLabel(AppConfig.aiReadAloudRoleModelConfig)),
                str(R.string.ra_model_role_backup, optionalModelLabel(AppConfig.aiReadAloudRoleBackupModelId, str(R.string.ra_not_use))),
                str(R.string.ra_model_first_timeout, AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds),
                str(R.string.ra_model_audio, optionalModelLabel(AppConfig.aiReadAloudAudioModelId, str(R.string.ra_follow_multi_role_short))),
                str(R.string.ra_model_audio_backup, optionalModelLabel(AppConfig.aiReadAloudAudioBackupModelId, str(R.string.ra_follow_multi_role_backup_short)))
            )
            showComposeChoiceListDialog(str(R.string.ra_model_routing_title), options) { index ->
                when (index) {
                    0 -> showAiModelPicker(
                        title = str(R.string.ra_model_role_main_title),
                        currentModelId = AppConfig.aiReadAloudRoleModelId,
                        noneLabel = null
                    ) {
                        AppConfig.aiReadAloudRoleModelId = it
                        updateAiRolePreferences()
                    }
                    1 -> showAiModelPicker(
                        title = str(R.string.ra_model_role_backup_title),
                        currentModelId = AppConfig.aiReadAloudRoleBackupModelId,
                        noneLabel = str(R.string.ra_model_no_backup)
                    ) {
                        AppConfig.aiReadAloudRoleBackupModelId = it
                        updateAiRolePreferences()
                    }
                    2 -> showAiRoleNumberDialog(
                        title = str(R.string.ra_model_first_timeout_title),
                        value = AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds,
                        min = 5,
                        max = 90
                    ) {
                        AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds = it
                        updateAiRolePreferences()
                    }
                    3 -> showAiModelPicker(
                        title = str(R.string.ra_model_audio_title),
                        currentModelId = AppConfig.aiReadAloudAudioModelId,
                        noneLabel = str(R.string.ra_follow_multi_role)
                    ) {
                        AppConfig.aiReadAloudAudioModelId = it
                        updateAiRolePreferences()
                    }
                    4 -> showAiModelPicker(
                        title = str(R.string.ra_model_audio_backup_title),
                        currentModelId = AppConfig.aiReadAloudAudioBackupModelId,
                        noneLabel = str(R.string.ra_follow_multi_role_backup)
                    ) {
                        AppConfig.aiReadAloudAudioBackupModelId = it
                        updateAiRolePreferences()
                    }
                }
            }
        }

        private fun showAiModelPicker(
            title: String,
            currentModelId: String?,
            noneLabel: String?,
            onSelected: (String?) -> Unit
        ) {
            val models = AppConfig.aiModelConfigList
            if (models.isEmpty()) {
                toastOnUi(R.string.ai_no_models)
                return
            }
            val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
            val offset = if (noneLabel == null) 0 else 1
            val labels = buildList {
                noneLabel?.let {
                    add(if (currentModelId.isNullOrBlank()) "$it ✓" else it)
                }
                models.forEach { model ->
                    val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                        ?.let { "${model.modelId} - $it" }
                        ?: model.modelId
                    add(if (model.id == currentModelId) "$label ✓" else label)
                }
            }
            val selectedIndex = if (currentModelId.isNullOrBlank() && noneLabel != null) {
                0
            } else {
                models.indexOfFirst { it.id == currentModelId }
                    .takeIf { it >= 0 }
                    ?.plus(offset)
                    ?: -1
            }
            showComposeChoiceListDialog(title, labels, selectedIndex = selectedIndex) { index ->
                if (index < offset) {
                    onSelected(null)
                } else {
                    onSelected(models[index - offset].id)
                }
                selectGroup(selectedGroup)
            }
        }

        private fun aiReadAloudModelRoutingSummary(): String {
            val role = modelLabel(AppConfig.aiReadAloudRoleModelConfig)
            val backup = optionalModelLabel(AppConfig.aiReadAloudRoleBackupModelId, str(R.string.ra_no_backup_short))
            val audio = optionalModelLabel(AppConfig.aiReadAloudAudioModelId, str(R.string.ra_audio_follow_short))
            return str(R.string.ra_model_routing_summary, role, backup, AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds, audio)
        }

        private fun optionalModelLabel(modelId: String?, fallback: String): String {
            if (modelId.isNullOrBlank()) return fallback
            return modelLabel(AppConfig.aiModelConfigList.firstOrNull { it.id == modelId })
        }


        private fun modelLabel(model: io.legado.app.ui.main.ai.AiModelConfig?): String {
            model ?: return str(R.string.ra_not_configured)
            val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
                ?.name
                ?.takeIf { it.isNotBlank() }
            return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
        }

        private fun formatSpeechRate(value: Int): String {
            return ((value.coerceIn(0, 45) + 5) / 10f).toString()
        }

        private fun showAiRoleNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            val displayTitle = when (max) {
                10 -> str(R.string.ra_role_threads_title)
                40 -> str(R.string.ra_role_batch_title)
                else -> title
            }
            showComposeNumberPickerDialog(
                title = displayTitle,
                value = value,
                minValue = min,
                maxValue = max,
                onValue = onValue
            )
        }

        private fun showLongTextInputDialog(
            title: String,
            hint: String,
            initialValue: String,
            maxLength: Int? = null,
            maxLengthMessage: String = "",
            minLines: Int,
            onValue: (String) -> Unit,
            neutralText: String? = null,
            onNeutral: (() -> Unit)? = null,
            validateContent: ((String) -> Boolean)? = null
        ) {
            showComposeTextInputDialog(
                title = title,
                hint = hint,
                initialValue = initialValue,
                neutralText = neutralText,
                minLines = minLines,
                maxLines = maxOf(minLines, 10),
                validateInput = { value ->
                    when {
                        maxLength != null && value.length > maxLength -> {
                            toastOnUi(maxLengthMessage)
                            false
                        }
                        validateContent?.invoke(value) == false -> false
                        else -> true
                    }
                },
                onPositive = onValue,
                onNeutral = onNeutral
            )
        }

        private fun showMultiRolePromptDialog() {
            showLongTextInputDialog(
                title = str(R.string.ra_role_prompt),
                hint = str(R.string.ra_role_prompt_hint),
                initialValue = AppConfig.aiReadAloudRolePrompt,
                maxLength = 4000,
                maxLengthMessage = str(R.string.ra_prompt_limit_4000),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudRolePrompt = it
                    updateAiRolePreferences()
                },
                neutralText = str(R.string.restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudRolePrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showAutoCreateCharacterPromptDialog() {
            showLongTextInputDialog(
                title = str(R.string.ra_auto_create_prompt),
                hint = str(R.string.ra_create_prompt_hint),
                initialValue = AppConfig.aiReadAloudAutoCreateCharacterPrompt,
                maxLength = 4000,
                maxLengthMessage = str(R.string.ra_prompt_limit_4000),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudAutoCreateCharacterPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = str(R.string.restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudAutoCreateCharacterPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showBgmPromptDialog() {
            showLongTextInputDialog(
                title = str(R.string.ra_bgm_prompt),
                hint = str(R.string.ra_bgm_prompt_hint),
                initialValue = AppConfig.aiReadAloudBgmPrompt,
                maxLength = 4000,
                maxLengthMessage = str(R.string.ra_prompt_limit_4000_max),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudBgmPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = str(R.string.restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudBgmPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showSoundEffectPromptDialog() {
            showLongTextInputDialog(
                title = str(R.string.ra_sfx_prompt),
                hint = str(R.string.ra_sfx_prompt_hint),
                initialValue = AppConfig.aiReadAloudSoundEffectPrompt,
                maxLength = 4000,
                maxLengthMessage = str(R.string.ra_prompt_limit_4000_max),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudSoundEffectPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = str(R.string.restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudSoundEffectPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showMultiRolePreprocessRulesDialog() {
            val config = ReadAloudPreprocessRuleConfig.current()
            val items = listOf(
                str(R.string.ra_quote_rules_summary, config.quotePairs.size),
                str(R.string.ra_thought_cues_summary, config.thoughtCuePatterns.size),
                str(R.string.ra_thresholds_summary, config.dialogueMinLength, config.emphasisMaxLength),
                str(R.string.ra_sfx_prefilter_summary, config.soundEffectCuePatterns.size),
                str(R.string.ra_preprocess_test),
                str(R.string.restore_default)
            )
            showComposeChoiceListDialog(str(R.string.ra_preprocess_rules), items) { index ->
                when (index) {
                    0 -> showPreprocessQuoteDialog(config)
                    1 -> showRuleListEditor(
                        title = str(R.string.ra_thought_cues),
                        values = config.thoughtCuePatterns,
                        hint = str(R.string.ra_thought_cues_hint),
                        onSave = { savePreprocessConfig(config.copy(thoughtCuePatterns = it)) }
                    )
                    2 -> showPreprocessThresholdDialog(config)
                    3 -> showSoundEffectRuleDialog(config)
                    4 -> showPreprocessTrialDialog()
                    5 -> {
                        AppConfig.aiReadAloudRolePreprocessRules = ""
                        updateAiRolePreferences()
                        toastOnUi(str(R.string.ra_preprocess_restored))
                    }
                }
            }
        }

        private fun showPreprocessQuoteDialog(config: ReadAloudPreprocessRuleConfig) {
            showLongTextInputDialog(
                title = str(R.string.ra_quote_rules),
                hint = str(R.string.ra_quote_rules_hint),
                initialValue = config.quotePairs.joinToString("\n") { "${it.open} ${it.close}" },
                minLines = 6,
                onValue = { value ->
                    savePreprocessConfig(config.copy(quotePairs = parseQuotePairs(value)))
                },
                neutralText = str(R.string.ra_sentence_punctuation),
                onNeutral = { showSentencePunctuationDialog(config) },
                validateContent = { value ->
                    if (parseQuotePairs(value).isEmpty()) {
                        toastOnUi(str(R.string.ra_quote_rules_need_one))
                        false
                    } else {
                        true
                    }
                }
            )
        }

        private fun showSentencePunctuationDialog(config: ReadAloudPreprocessRuleConfig) {
            showComposeTextInputDialog(
                title = str(R.string.ra_sentence_punctuation),
                hint = str(R.string.ra_sentence_punctuation_hint),
                initialValue = config.sentencePunctuation,
                minLines = 1,
                maxLines = 1,
                validateInput = { value ->
                    if (value.trim().isBlank()) {
                        toastOnUi(str(R.string.ra_sentence_punctuation_empty))
                        false
                    } else {
                        true
                    }
                },
                onPositive = { value ->
                    savePreprocessConfig(config.copy(sentencePunctuation = value.trim()))
                }
            )
        }

        private fun showPreprocessThresholdDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                str(R.string.ra_dialogue_min_summary, config.dialogueMinLength),
                str(R.string.ra_emphasis_max_summary, config.emphasisMaxLength),
                str(R.string.ra_colon_cue_max_summary, config.colonCueMaxLength),
                str(R.string.ra_merge_cross_quote_summary, if (config.mergeCrossParagraphQuote) str(R.string.ra_on) else str(R.string.ra_off)),
                str(R.string.ra_sfx_context_chars_summary, config.soundEffectContextChars)
            )
            showComposeChoiceListDialog(str(R.string.ra_thresholds), items) { index ->
                when (index) {
                    0 -> showPreprocessNumberDialog(str(R.string.ra_dialogue_min), config.dialogueMinLength, 1, 80) {
                        savePreprocessConfig(config.copy(dialogueMinLength = it))
                    }
                    1 -> showPreprocessNumberDialog(str(R.string.ra_emphasis_max), config.emphasisMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(emphasisMaxLength = it))
                    }
                    2 -> showPreprocessNumberDialog(str(R.string.ra_colon_cue_max), config.colonCueMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(colonCueMaxLength = it))
                    }
                    3 -> savePreprocessConfig(config.copy(mergeCrossParagraphQuote = !config.mergeCrossParagraphQuote))
                    4 -> showPreprocessNumberDialog(str(R.string.ra_sfx_context_chars), config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showSoundEffectRuleDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                str(R.string.ra_sfx_cues_summary, config.soundEffectCuePatterns.size),
                str(R.string.ra_sfx_excludes_summary, config.soundEffectExcludePatterns.size),
                str(R.string.ra_context_chars_summary, config.soundEffectContextChars)
            )
            showComposeChoiceListDialog(str(R.string.ra_sfx_prefilter), items) { index ->
                when (index) {
                    0 -> showRuleListEditor(
                        title = str(R.string.ra_sfx_cues),
                        values = config.soundEffectCuePatterns,
                        hint = str(R.string.ra_sfx_cues_hint),
                        onSave = { savePreprocessConfig(config.copy(soundEffectCuePatterns = it)) }
                    )
                    1 -> showRuleListEditor(
                        title = str(R.string.ra_sfx_excludes),
                        values = config.soundEffectExcludePatterns,
                        hint = str(R.string.ra_sfx_excludes_hint),
                        onSave = { savePreprocessConfig(config.copy(soundEffectExcludePatterns = it)) }
                    )
                    2 -> showPreprocessNumberDialog(str(R.string.ra_sfx_context_chars), config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showRuleListEditor(
            title: String,
            values: List<String>,
            hint: String,
            onSave: (List<String>) -> Unit
        ) {
            showLongTextInputDialog(
                title = title,
                hint = hint,
                initialValue = values.joinToString("\n"),
                minLines = 8,
                onValue = { value -> onSave(parseRuleItems(value)) },
                validateContent = { value ->
                    if (parseRuleItems(value).isEmpty()) {
                        toastOnUi(str(R.string.ra_keep_one_rule))
                        false
                    } else {
                        true
                    }
                }
            )
        }

        private fun showPreprocessNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            showComposeNumberPickerDialog(
                title = title,
                value = value,
                minValue = min,
                maxValue = max,
                onValue = onValue
            )
        }

        private fun showPreprocessTrialDialog() {
            val sample = """
                江艳凑在杨间身边小心翼翼地问道：
                “那栋房子真的有鬼啊？”
                门轴忽然吱呀一声响了起来。
                他心道：“事情不太对。”
            """.trimIndent()
            showComposeTextInputDialog(
                title = str(R.string.ra_preprocess_try),
                hint = str(R.string.ra_preprocess_try_hint),
                initialValue = sample,
                minLines = 10,
                maxLines = 14,
                validateInput = { value ->
                    if (value.isBlank()) {
                        toastOnUi(str(R.string.ra_preprocess_try_empty))
                        false
                    } else {
                        true
                    }
                },
                onPositive = { showPreprocessTrialResult(it) }
            )
        }

        private fun showPreprocessTrialResult(text: String) {
            val paragraphs = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val roleResult = ReadAloudRolePreprocessor.process(paragraphs)
            val sfxResult = ReadAloudSoundEffectPreprocessor.process(paragraphs)
            val message = buildString {
                appendLine(str(R.string.ra_role_units, roleResult.units.size))
                roleResult.units.take(40).forEachIndexed { index, unit ->
                    append(index + 1)
                    append(". ")
                    append(unit.kind)
                    append(" · ")
                    append(if (unit.needsAi) str(R.string.ra_needs_ai) else unit.characterName.ifBlank { unit.roleType })
                    append(" · P")
                    append(unit.firstParagraphIndex + 1)
                    append(" · ")
                    appendLine(unit.text.replace('\n', ' ').take(80))
                }
                if (roleResult.units.size > 40) appendLine("……")
                appendLine()
                appendLine(str(R.string.ra_sfx_candidates, sfxResult.candidates.size))
                sfxResult.candidates.take(30).forEachIndexed { index, item ->
                    append(index + 1)
                    append(". ")
                    append(item.cue)
                    append(" · P")
                    append(item.paragraphIndex + 1)
                    append(" · ")
                    appendLine(item.context.take(80))
                }
            }.take(6000)
            showComposeConfirmDialog(
                title = str(R.string.ra_try_result),
                message = message,
                showNegative = false,
                messageInContent = true,
                onPositive = {}
            )
        }

        private fun savePreprocessConfig(config: ReadAloudPreprocessRuleConfig) {
            val json = config.toJsonString()
            if (json.length > 8000) {
                toastOnUi(str(R.string.ra_preprocess_limit_8000))
                return
            }
            AppConfig.aiReadAloudRolePreprocessRules = json
            updateAiRolePreferences()
            toastOnUi(str(R.string.ra_preprocess_saved))
        }

        private fun parseQuotePairs(value: String): List<ReadAloudQuotePair> {
            return value.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    when {
                        parts.size >= 2 -> ReadAloudQuotePair(parts[0].take(1), parts[1].take(1))
                        line.length >= 2 -> ReadAloudQuotePair(line.take(1), line.takeLast(1))
                        else -> null
                    }
                }
                .distinct()
                .toList()
        }

        private fun parseRuleItems(value: String): List<String> {
            return value.lineSequence()
                .flatMap { it.split(',', '，', '、').asSequence() }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }


    }
}
