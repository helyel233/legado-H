package io.legado.app.reader.aloud

import com.google.gson.JsonObject
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechRouteSanitizer
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx

/**
 * P3-b: unified typed access to the read-aloud configuration. Storage stays
 * in defaultSharedPreferences (same keys as before) so existing consumers
 * and backups keep working; this object adds one import/export entry point
 * so reader-config backups can carry aloud settings.
 *
 * ttsEngine and the AI model ids are external references: they are exported
 * as-is, but the speech route is validated on restore (falls back to default
 * when the referenced HTTP TTS no longer exists).
 */
object ReadAloudConfig {

    var ignoreAudioFocus: Boolean
        get() = AppConfig.ignoreAudioFocus
        set(value) = appCtx.putPrefBoolean(PreferKey.ignoreAudioFocus, value)
    var pauseReadAloudWhilePhoneCalls: Boolean
        get() = AppConfig.pauseReadAloudWhilePhoneCalls
        set(value) {
            AppConfig.pauseReadAloudWhilePhoneCalls = value
        }
    var readAloudWakeLock: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudWakeLock, value)
    var showReadAloudFloatingBall: Boolean
        get() = AppConfig.showReadAloudFloatingBall
        set(value) {
            AppConfig.showReadAloudFloatingBall = value
        }
    var mediaButtonPerNext: Boolean
        get() = appCtx.getPrefBoolean(MEDIA_BUTTON_PER_NEXT, false)
        set(value) = appCtx.putPrefBoolean(MEDIA_BUTTON_PER_NEXT, value)
    var readAloudByPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudByPage, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudByPage, value)
    var streamReadAloudAudio: Boolean
        get() = AppConfig.streamReadAloudAudio
        set(value) = appCtx.putPrefBoolean(PreferKey.streamReadAloudAudio, value)
    var ttsFollowSys: Boolean
        get() = AppConfig.ttsFlowSys
        set(value) {
            AppConfig.ttsFlowSys = value
        }
    var ttsSpeechRate: Int
        get() = AppConfig.ttsSpeechRate
        set(value) {
            AppConfig.ttsSpeechRate = value
        }
    var aiReadAloudRoleEnabled: Boolean
        get() = AppConfig.aiReadAloudRoleEnabled
        set(value) {
            AppConfig.aiReadAloudRoleEnabled = value
        }
    var aiReadAloudRoleModelId: String?
        get() = AppConfig.aiReadAloudRoleModelId
        set(value) {
            AppConfig.aiReadAloudRoleModelId = value
        }
    var aiReadAloudRoleBackupModelId: String?
        get() = AppConfig.aiReadAloudRoleBackupModelId
        set(value) {
            AppConfig.aiReadAloudRoleBackupModelId = value
        }
    var aiReadAloudAudioModelId: String?
        get() = AppConfig.aiReadAloudAudioModelId
        set(value) {
            AppConfig.aiReadAloudAudioModelId = value
        }
    var aiReadAloudAudioBackupModelId: String?
        get() = AppConfig.aiReadAloudAudioBackupModelId
        set(value) {
            AppConfig.aiReadAloudAudioBackupModelId = value
        }
    var aiReadAloudRoleFirstResponseTimeoutSeconds: Int
        get() = AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds
        set(value) {
            AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds = value
        }
    var aiReadAloudRoleThreadCount: Int
        get() = AppConfig.aiReadAloudRoleThreadCount
        set(value) {
            AppConfig.aiReadAloudRoleThreadCount = value
        }
    var aiReadAloudRoleContextParagraphs: Int
        get() = AppConfig.aiReadAloudRoleContextParagraphs
        set(value) {
            AppConfig.aiReadAloudRoleContextParagraphs = value
        }
    var aiReadAloudRoleMergeGapParagraphs: Int
        get() = AppConfig.aiReadAloudRoleMergeGapParagraphs
        set(value) {
            AppConfig.aiReadAloudRoleMergeGapParagraphs = value
        }
    var aiReadAloudRoleMode: String
        get() = AppConfig.aiReadAloudRoleMode
        set(value) {
            AppConfig.aiReadAloudRoleMode = value
        }
    var aiReadAloudRolePreprocessRules: String
        get() = AppConfig.aiReadAloudRolePreprocessRules
        set(value) {
            AppConfig.aiReadAloudRolePreprocessRules = value
        }
    var aiReadAloudRolePrompt: String
        get() = AppConfig.aiReadAloudRolePrompt
        set(value) {
            AppConfig.aiReadAloudRolePrompt = value
        }
    var aiReadAloudAutoCreateCharacterPrompt: String
        get() = AppConfig.aiReadAloudAutoCreateCharacterPrompt
        set(value) {
            AppConfig.aiReadAloudAutoCreateCharacterPrompt = value
        }
    var aiReadAloudAutoCreateCharacters: Boolean
        get() = AppConfig.aiReadAloudAutoCreateCharacters
        set(value) {
            AppConfig.aiReadAloudAutoCreateCharacters = value
        }
    var aiReadAloudAutoCreateAvatar: Boolean
        get() = AppConfig.aiReadAloudAutoCreateAvatar
        set(value) {
            AppConfig.aiReadAloudAutoCreateAvatar = value
        }
    var aiReadAloudBgmEnabled: Boolean
        get() = AppConfig.aiReadAloudBgmEnabled
        set(value) {
            AppConfig.aiReadAloudBgmEnabled = value
        }
    var aiReadAloudBgmPrompt: String
        get() = AppConfig.aiReadAloudBgmPrompt
        set(value) {
            AppConfig.aiReadAloudBgmPrompt = value
        }
    var aiReadAloudSoundEffectPrompt: String
        get() = AppConfig.aiReadAloudSoundEffectPrompt
        set(value) {
            AppConfig.aiReadAloudSoundEffectPrompt = value
        }
    var aiReadAloudBgmVolume: Int
        get() = AppConfig.aiReadAloudBgmVolume
        set(value) {
            AppConfig.aiReadAloudBgmVolume = value
        }
    var aiReadAloudSfxVolume: Int
        get() = AppConfig.aiReadAloudSfxVolume
        set(value) {
            AppConfig.aiReadAloudSfxVolume = value
        }
    var readAloudSpeakerLoudnessEnabled: Boolean
        get() = AppConfig.readAloudSpeakerLoudnessEnabled
        set(value) {
            AppConfig.readAloudSpeakerLoudnessEnabled = value
        }
    var readAloudTargetVoiceVolume: Int
        get() = AppConfig.readAloudTargetVoiceVolume
        set(value) {
            AppConfig.readAloudTargetVoiceVolume = value
        }
    var readAloudMaxSpeakerGain: Int
        get() = AppConfig.readAloudMaxSpeakerGain
        set(value) {
            AppConfig.readAloudMaxSpeakerGain = value
        }
    var readAloudNarratorBaseGain: Int
        get() = AppConfig.readAloudNarratorBaseGain
        set(value) {
            AppConfig.readAloudNarratorBaseGain = value
        }
    var ttsEngine: String
        get() = AppConfig.ttsEngine.orEmpty()
        set(value) {
            AppConfig.ttsEngine = value
        }

    private const val MEDIA_BUTTON_PER_NEXT = "mediaButtonPerNext"

    /** Export all aloud settings as JSON (for readConfig backup zip). */
    fun toExportJson(): JsonObject {
        val o = JsonObject()
        o.addProperty("ignoreAudioFocus", ignoreAudioFocus)
        o.addProperty("pauseReadAloudWhilePhoneCalls", pauseReadAloudWhilePhoneCalls)
        o.addProperty("readAloudWakeLock", readAloudWakeLock)
        o.addProperty("showReadAloudFloatingBall", showReadAloudFloatingBall)
        o.addProperty("mediaButtonPerNext", mediaButtonPerNext)
        o.addProperty("readAloudByPage", readAloudByPage)
        o.addProperty("streamReadAloudAudio", streamReadAloudAudio)
        o.addProperty("ttsFollowSys", ttsFollowSys)
        o.addProperty("ttsSpeechRate", ttsSpeechRate)
        o.addProperty("aiReadAloudRoleEnabled", aiReadAloudRoleEnabled)
        o.addProperty("aiReadAloudRoleModelId", aiReadAloudRoleModelId)
        o.addProperty("aiReadAloudRoleBackupModelId", aiReadAloudRoleBackupModelId)
        o.addProperty("aiReadAloudAudioModelId", aiReadAloudAudioModelId)
        o.addProperty("aiReadAloudAudioBackupModelId", aiReadAloudAudioBackupModelId)
        o.addProperty("aiReadAloudRoleFirstResponseTimeoutSeconds", aiReadAloudRoleFirstResponseTimeoutSeconds)
        o.addProperty("aiReadAloudRoleThreadCount", aiReadAloudRoleThreadCount)
        o.addProperty("aiReadAloudRoleContextParagraphs", aiReadAloudRoleContextParagraphs)
        o.addProperty("aiReadAloudRoleMergeGapParagraphs", aiReadAloudRoleMergeGapParagraphs)
        o.addProperty("aiReadAloudRoleMode", aiReadAloudRoleMode)
        o.addProperty("aiReadAloudRolePreprocessRules", aiReadAloudRolePreprocessRules)
        o.addProperty("aiReadAloudRolePrompt", aiReadAloudRolePrompt)
        o.addProperty("aiReadAloudAutoCreateCharacterPrompt", aiReadAloudAutoCreateCharacterPrompt)
        o.addProperty("aiReadAloudAutoCreateCharacters", aiReadAloudAutoCreateCharacters)
        o.addProperty("aiReadAloudAutoCreateAvatar", aiReadAloudAutoCreateAvatar)
        o.addProperty("aiReadAloudBgmEnabled", aiReadAloudBgmEnabled)
        o.addProperty("aiReadAloudBgmPrompt", aiReadAloudBgmPrompt)
        o.addProperty("aiReadAloudSoundEffectPrompt", aiReadAloudSoundEffectPrompt)
        o.addProperty("aiReadAloudBgmVolume", aiReadAloudBgmVolume)
        o.addProperty("aiReadAloudSfxVolume", aiReadAloudSfxVolume)
        o.addProperty("readAloudSpeakerLoudnessEnabled", readAloudSpeakerLoudnessEnabled)
        o.addProperty("readAloudTargetVoiceVolume", readAloudTargetVoiceVolume)
        o.addProperty("readAloudMaxSpeakerGain", readAloudMaxSpeakerGain)
        o.addProperty("readAloudNarratorBaseGain", readAloudNarratorBaseGain)
        o.addProperty("ttsEngine", ttsEngine)
        return o
    }

    /** Restore from JSON; speech route is sanitized, unknown fields ignored. */
    fun applyExport(json: JsonObject) {
        json.get("ignoreAudioFocus")?.let { if (!it.isJsonNull) ignoreAudioFocus = it.asBoolean }
        json.get("pauseReadAloudWhilePhoneCalls")?.let { if (!it.isJsonNull) pauseReadAloudWhilePhoneCalls = it.asBoolean }
        json.get("readAloudWakeLock")?.let { if (!it.isJsonNull) readAloudWakeLock = it.asBoolean }
        json.get("showReadAloudFloatingBall")?.let { if (!it.isJsonNull) showReadAloudFloatingBall = it.asBoolean }
        json.get("mediaButtonPerNext")?.let { if (!it.isJsonNull) mediaButtonPerNext = it.asBoolean }
        json.get("readAloudByPage")?.let { if (!it.isJsonNull) readAloudByPage = it.asBoolean }
        json.get("streamReadAloudAudio")?.let { if (!it.isJsonNull) streamReadAloudAudio = it.asBoolean }
        json.get("ttsFollowSys")?.let { if (!it.isJsonNull) ttsFollowSys = it.asBoolean }
        json.get("ttsSpeechRate")?.let { if (!it.isJsonNull) ttsSpeechRate = it.asInt }
        json.get("aiReadAloudRoleEnabled")?.let { if (!it.isJsonNull) aiReadAloudRoleEnabled = it.asBoolean }
        json.get("aiReadAloudRoleModelId")?.let { if (!it.isJsonNull) aiReadAloudRoleModelId = it.asString }
        json.get("aiReadAloudRoleBackupModelId")?.let { if (!it.isJsonNull) aiReadAloudRoleBackupModelId = it.asString }
        json.get("aiReadAloudAudioModelId")?.let { if (!it.isJsonNull) aiReadAloudAudioModelId = it.asString }
        json.get("aiReadAloudAudioBackupModelId")?.let { if (!it.isJsonNull) aiReadAloudAudioBackupModelId = it.asString }
        json.get("aiReadAloudRoleFirstResponseTimeoutSeconds")?.let { if (!it.isJsonNull) aiReadAloudRoleFirstResponseTimeoutSeconds = it.asInt }
        json.get("aiReadAloudRoleThreadCount")?.let { if (!it.isJsonNull) aiReadAloudRoleThreadCount = it.asInt }
        json.get("aiReadAloudRoleContextParagraphs")?.let { if (!it.isJsonNull) aiReadAloudRoleContextParagraphs = it.asInt }
        json.get("aiReadAloudRoleMergeGapParagraphs")?.let { if (!it.isJsonNull) aiReadAloudRoleMergeGapParagraphs = it.asInt }
        json.get("aiReadAloudRoleMode")?.let { if (!it.isJsonNull) aiReadAloudRoleMode = it.asString }
        json.get("aiReadAloudRolePreprocessRules")?.let { if (!it.isJsonNull) aiReadAloudRolePreprocessRules = it.asString }
        json.get("aiReadAloudRolePrompt")?.let { if (!it.isJsonNull) aiReadAloudRolePrompt = it.asString }
        json.get("aiReadAloudAutoCreateCharacterPrompt")?.let { if (!it.isJsonNull) aiReadAloudAutoCreateCharacterPrompt = it.asString }
        json.get("aiReadAloudAutoCreateCharacters")?.let { if (!it.isJsonNull) aiReadAloudAutoCreateCharacters = it.asBoolean }
        json.get("aiReadAloudAutoCreateAvatar")?.let { if (!it.isJsonNull) aiReadAloudAutoCreateAvatar = it.asBoolean }
        json.get("aiReadAloudBgmEnabled")?.let { if (!it.isJsonNull) aiReadAloudBgmEnabled = it.asBoolean }
        json.get("aiReadAloudBgmPrompt")?.let { if (!it.isJsonNull) aiReadAloudBgmPrompt = it.asString }
        json.get("aiReadAloudSoundEffectPrompt")?.let { if (!it.isJsonNull) aiReadAloudSoundEffectPrompt = it.asString }
        json.get("aiReadAloudBgmVolume")?.let { if (!it.isJsonNull) aiReadAloudBgmVolume = it.asInt }
        json.get("aiReadAloudSfxVolume")?.let { if (!it.isJsonNull) aiReadAloudSfxVolume = it.asInt }
        json.get("readAloudSpeakerLoudnessEnabled")?.let { if (!it.isJsonNull) readAloudSpeakerLoudnessEnabled = it.asBoolean }
        json.get("readAloudTargetVoiceVolume")?.let { if (!it.isJsonNull) readAloudTargetVoiceVolume = it.asInt }
        json.get("readAloudMaxSpeakerGain")?.let { if (!it.isJsonNull) readAloudMaxSpeakerGain = it.asInt }
        json.get("readAloudNarratorBaseGain")?.let { if (!it.isJsonNull) readAloudNarratorBaseGain = it.asInt }
        json.get("ttsEngine")?.let { if (!it.isJsonNull) ttsEngine = it.asString }
        // 朗读引擎路由指向的 HttpTTS 可能不存在于目标设备，校验失效回落默认
        if (json.has("ttsEngine") && !json.get("ttsEngine").isJsonNull) {
            runCatching {
                val route = GSON.fromJsonObject<SpeechRoute>(json.get("ttsEngine").asString).getOrThrow()
                ttsEngine = GSON.toJson(SpeechRouteSanitizer.validOrDefault(route))
            }
        }
    }
}
