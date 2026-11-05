package io.legado.app.help.readaloud.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import io.legado.app.data.entities.HttpTTS
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON
import io.legado.app.R
import splitties.init.appCtx

data class SpeechVoiceOption(
    val key: String,
    val engineType: String,
    val engineValue: String,
    val engineName: String,
    val speakerName: String,
    val toneID: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val explicitSpeaker: Boolean = false
) {
    fun toRoute(emotion: SpeechEmotion? = null, source: String = SpeechRoute.SOURCE_MANUAL): SpeechRoute {
        return SpeechRoute(
            engineType = engineType,
            engineValue = engineValue,
            speakerName = speakerName,
            toneID = toneID,
            emotionName = emotion?.emotionName.orEmpty(),
            emotionTag = emotion?.emotionTag.orEmpty(),
            groupId = groupId,
            groupName = groupName,
            source = source
        )
    }
}

data class SpeechVoiceEngineGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val engineType: String,
    val engineValue: String,
    val options: List<SpeechVoiceOption>,
    val emotions: List<SpeechEmotion> = emptyList(),
    val loginKey: String = "",
    val loginUrl: String? = null,
    val warning: String = ""
)

object SpeechVoiceCatalogRepository {

    fun allGroups(
        context: Context,
        httpTtsList: List<HttpTTS>,
        includeSystem: Boolean = true
    ): List<SpeechVoiceEngineGroup> {
        return buildList {
            if (includeSystem) addAll(systemGroups(context))
            addAll(httpGroups(httpTtsList))
        }
    }

    fun httpGroups(httpTtsList: List<HttpTTS>): List<SpeechVoiceEngineGroup> {
        return httpTtsList.map { httpTts ->
            val emotions = SpeechVoiceCatalogParser.flattenEmotions(httpTts.emotionsJson)
            val speakerGroups = SpeechVoiceCatalogParser.parseSpeakerGroups(httpTts.speakersJson)
            val speakerWarning = if (httpTts.speakersJson.isNotBlank() && speakerGroups.isEmpty()) {
                appCtx.getString(R.string.misc_invalid_speaker_json)
            } else {
                ""
            }
            val options = if (speakerGroups.isEmpty()) {
                listOf(
                    SpeechVoiceOption(
                        key = "http:${httpTts.id}:default",
                        engineType = SpeechRoute.ENGINE_HTTP,
                        engineValue = httpTts.id.toString(),
                        engineName = httpTts.name.ifBlank { "HTTP TTS" },
                        speakerName = httpTts.name.ifBlank { "HTTP TTS" },
                        explicitSpeaker = false
                    )
                )
            } else {
                speakerGroups.flatMap { group ->
                    group.items.map { speaker ->
                        SpeechVoiceOption(
                            key = "http:${httpTts.id}:${speaker.groupId}:${speaker.toneID}",
                            engineType = SpeechRoute.ENGINE_HTTP,
                            engineValue = httpTts.id.toString(),
                            engineName = httpTts.name.ifBlank { "HTTP TTS" },
                            speakerName = speaker.speakerName,
                            toneID = speaker.toneID,
                            groupId = speaker.groupId,
                            groupName = speaker.groupName,
                            explicitSpeaker = true
                        )
                    }
                }
            }
            val subtitle = buildList {
                val explicitCount = options.count { it.explicitSpeaker }
                if (explicitCount > 0) add(appCtx.getString(R.string.i18n_final_speaker_count, explicitCount))
                if (emotions.isNotEmpty()) add(appCtx.getString(R.string.i18n_final_emotion_count, emotions.size))
                if (speakerWarning.isNotBlank()) add(speakerWarning)
            }.joinToString(" · ").ifBlank { appCtx.getString(R.string.misc_standard_http_tts) }
            SpeechVoiceEngineGroup(
                key = "http:${httpTts.id}",
                title = httpTts.name.ifBlank { "HTTP TTS" },
                subtitle = subtitle,
                engineType = SpeechRoute.ENGINE_HTTP,
                engineValue = httpTts.id.toString(),
                options = options,
                emotions = emotions,
                loginKey = httpTts.id.toString(),
                loginUrl = httpTts.loginUrl,
                warning = speakerWarning
            )
        }
    }

    fun systemGroups(context: Context): List<SpeechVoiceEngineGroup> {
        val engines = runCatching {
            val tts = TextToSpeech(context.applicationContext, null)
            try {
                tts.engines.map { it.label.toString() to it.name }
            } finally {
                tts.shutdown()
            }
        }.getOrDefault(emptyList())
        return (listOf("系统默认" to "") + engines)
            .distinctBy { it.second }
            .map { (title, value) ->
                val engineValue = GSON.toJson(SelectItem(title, value))
                val option = SpeechVoiceOption(
                    key = "system:$value",
                    engineType = SpeechRoute.ENGINE_SYSTEM,
                    engineValue = engineValue,
                    engineName = title,
                    speakerName = title,
                    explicitSpeaker = false
                )
                SpeechVoiceEngineGroup(
                    key = "system:$value",
                    title = title,
                    subtitle = if (value.isBlank()) appCtx.getString(R.string.ra_cfg_system_default) else appCtx.getString(R.string.system_tts),
                    engineType = SpeechRoute.ENGINE_SYSTEM,
                    engineValue = engineValue,
                    options = listOf(option)
                )
            }
    }

    fun assignableRoutes(httpTtsList: List<HttpTTS>): List<SpeechRoute> {
        val httpGroups = httpGroups(httpTtsList)
        val explicitHttp = httpGroups
            .flatMap { group -> group.options.map { it to group.emotions.firstOrNull() } }
            .filter { (option, _) -> option.explicitSpeaker }
            .map { (option, emotion) -> option.toRoute(emotion, SpeechRoute.SOURCE_AUTO) }
            .filterNot(SpeechVoiceGroupRepository::isBlockedRoute)
        if (explicitHttp.isNotEmpty()) return explicitHttp
        val httpDefaults = httpGroups
            .flatMap { group -> group.options.map { it.toRoute(group.emotions.firstOrNull(), SpeechRoute.SOURCE_AUTO) } }
            .filterNot(SpeechVoiceGroupRepository::isBlockedRoute)
        if (httpDefaults.isNotEmpty()) return httpDefaults
        val systemDefault = SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = GSON.toJson(SelectItem(appCtx.getString(R.string.ra_cfg_system_default), "")),
            speakerName = appCtx.getString(R.string.ra_cfg_system_default),
            source = SpeechRoute.SOURCE_AUTO
        )
        return listOf(systemDefault).filterNot(SpeechVoiceGroupRepository::isBlockedRoute)
    }
}
