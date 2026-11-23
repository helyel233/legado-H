package io.legado.app.service

import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds deterministic cache keys for synthesized TTS audio cues so that
 * per-route HTTP TTS sources, voices/emotions, system TTS engines, and
 * speech-rate settings never collide on stale audio.
 */
internal object HttpReadAloudCacheKey {

    fun buildSpeakFileName(
        chapterTitle: String,
        content: String,
        route: SpeechRoute?,
        httpTts: HttpTTS?,
        defaultTtsEngine: String?,
        speechRate: Int,
        ttsFlowSys: Boolean,
        ttsSpeechRate: Int
    ): String {
        val titleMd5 = MD5Utils.md5Encode16(chapterTitle)
        val routePart = route
            ?.takeIf {
                it.isConfigured || it.emotionName.isNotBlank() || it.emotionTag.isNotBlank()
            }
            ?.let {
                listOf(
                    it.engineType,
                    it.engineValue,
                    it.speakerName,
                    it.toneID,
                    it.emotionName,
                    it.emotionTag,
                    it.groupId,
                    it.groupName,
                    it.source
                ).joinToString("|")
            }
            .orEmpty()
        val isSystemEngine = route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null
        val enginePart = if (isSystemEngine) {
            val systemEngine = route?.engineValue?.takeIf { it.isNotBlank() }
                ?: defaultTtsEngine.orEmpty()
            val systemRate = if (ttsFlowSys) "system_default" else ttsSpeechRate.toString()
            "system|$systemEngine|flow=$ttsFlowSys|sysRate=$systemRate|speechRate=$speechRate"
        } else {
            listOf(
                "http",
                httpTts.id.toString(),
                httpTts.url,
                httpTts.header.orEmpty(),
                httpTts.contentType.orEmpty(),
                httpTts.loginCheckJs.orEmpty(),
                httpTts.jsLib.orEmpty(),
                "speechRate=$speechRate"
            ).joinToString("|")
        }
        val payload = "$enginePart-|-$routePart-|-$speechRate-|-$content"
        return "${titleMd5}_${MD5Utils.md5Encode16(payload)}"
    }
}

/**
 * Sliding-window pruner for synthesized `.mp3` files under `cacheDir/httpTTS`.
 * Retains files belonging to active/prefetched chapters while bounding disk usage
 * across long continuous playback sessions.
 */
internal object HttpReadAloudCachePruner {

    const val SILENT_SOUND_BYTES = 2160L
    const val DEFAULT_MAX_FILES = 150
    const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
    const val DEFAULT_MAX_AGE_MS = 10 * 60 * 1000L

    data class PruneResult(
        val deletedCount: Int,
        val deletedBytes: Long,
        val remainingCount: Int,
        val remainingBytes: Long
    )

    fun chapterPrefix(chapterTitle: String?): String? {
        val title = chapterTitle?.takeIf { it.isNotBlank() } ?: return null
        return "${MD5Utils.md5Encode16(title)}_"
    }

    fun prune(
        cacheDir: File,
        protectedFileNames: Set<String> = emptySet(),
        protectedPrefixes: Set<String> = emptySet(),
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        nowMs: Long = System.currentTimeMillis()
    ): PruneResult {
        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            return PruneResult(0, 0L, 0, 0L)
        }
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return PruneResult(0, 0L, 0, 0L)
        if (files.isEmpty()) {
            return PruneResult(0, 0L, 0, 0L)
        }

        fun isProtected(file: File): Boolean {
            return file.name in protectedFileNames ||
                protectedPrefixes.any { prefix -> prefix.isNotEmpty() && file.name.startsWith(prefix) }
        }

        var deletedCount = 0
        var deletedBytes = 0L
        val protectedFiles = mutableListOf<File>()
        val unprotectedCandidates = mutableListOf<File>()

        for (file in files) {
            val length = file.length()
            val protected = isProtected(file)
            val isStaleSilent = file.name !in protectedFileNames && length == SILENT_SOUND_BYTES
            val isExpired = !protected && maxAgeMs >= 0L && (nowMs - file.lastModified()) > maxAgeMs
            if (isStaleSilent || isExpired) {
                if (file.delete()) {
                    deletedCount++
                    deletedBytes += length
                } else if (protected) {
                    protectedFiles.add(file)
                } else {
                    unprotectedCandidates.add(file)
                }
            } else if (protected) {
                protectedFiles.add(file)
            } else {
                unprotectedCandidates.add(file)
            }
        }

        val sortedUnprotected = unprotectedCandidates.sortedWith(
            compareBy<File>({ it.lastModified() }, { it.name })
        )
        var totalCount = protectedFiles.size + sortedUnprotected.size
        var totalBytes = protectedFiles.sumOf { it.length() } + sortedUnprotected.sumOf { it.length() }

        for (candidate in sortedUnprotected) {
            if (totalCount <= maxFiles && totalBytes <= maxBytes) break
            val length = candidate.length()
            if (candidate.delete()) {
                deletedCount++
                deletedBytes += length
                totalCount--
                totalBytes -= length
            }
        }

        return PruneResult(
            deletedCount = deletedCount,
            deletedBytes = deletedBytes,
            remainingCount = totalCount,
            remainingBytes = totalBytes
        )
    }
}

internal interface SystemTtsEngineAdapter {
    fun setSpeechRate(rate: Float)
    fun synthesizeToFile(text: String, outputFile: File, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
}

internal interface SystemTtsUtteranceCallback {
    fun onDone(utteranceId: String?)
    fun onError(utteranceId: String?)
}

/**
 * Lazily initializes and reuses a single [SystemTtsEngineAdapter] instance,
 * serializing synthesis requests with a [Mutex] and routing utterance callbacks
 * by unique `utteranceId` so concurrent callers cannot clobber callbacks or rate state.
 */
internal class SystemTtsSynthesisCoordinator(
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val initTimeoutMs: Long = 20_000L,
    private val synthesisTimeoutMs: Long = 120_000L,
    private val createEngine: suspend (
        engine: String?,
        callback: SystemTtsUtteranceCallback
    ) -> SystemTtsEngineAdapter?
) {

    private val synthesisMutex = Mutex()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val utteranceSeq = AtomicLong(0L)

    @Volatile
    private var released = false
    private var activeEngineName: String? = null
    private var activeEngine: SystemTtsEngineAdapter? = null
    private var hasActiveEngine = false
    private var appliedCustomSpeechRate = false

    private val utteranceCallback = object : SystemTtsUtteranceCallback {
        override fun onDone(utteranceId: String?) {
            if (utteranceId != null) {
                pendingUtterances.remove(utteranceId)?.complete(true)
            }
        }

        override fun onError(utteranceId: String?) {
            if (utteranceId != null) {
                pendingUtterances.remove(utteranceId)?.complete(false)
            }
        }
    }

    suspend fun synthesize(
        engine: String?,
        speakText: String,
        outputFile: File,
        speechRate: Float?
    ): Boolean {
        if (released) return false
        if (outputFile.exists() && outputFile.length() > 0L) return true

        return synthesisMutex.withLock {
            if (released) return@withLock false
            if (outputFile.exists() && outputFile.length() > 0L) return@withLock true

            val normalizedEngine = engine?.trim()?.takeIf { it.isNotEmpty() }
            val needsRateReset = speechRate == null && appliedCustomSpeechRate
            if (!hasActiveEngine ||
                activeEngine == null ||
                activeEngineName != normalizedEngine ||
                needsRateReset
            ) {
                resetActiveEngineLocked()
                val created = withTimeout(initTimeoutMs) {
                    createEngine(normalizedEngine, utteranceCallback)
                }
                if (created == null || released) {
                    if (created != null) {
                        shutdownEngineSafely(created)
                    }
                    return@withLock false
                }
                activeEngine = created
                activeEngineName = normalizedEngine
                hasActiveEngine = true
                appliedCustomSpeechRate = false
            }

            val engineInstance = activeEngine ?: return@withLock false
            if (speechRate != null) {
                engineInstance.setSpeechRate(speechRate)
                appliedCustomSpeechRate = true
            }

            val utteranceId = "mixed_tts_${utteranceSeq.incrementAndGet()}_${outputFile.nameWithoutExtension}"
            val done = CompletableDeferred<Boolean>()
            pendingUtterances[utteranceId] = done
            var resetOnExit = false
            var stopOnCancel = false
            try {
                val started = engineInstance.synthesizeToFile(speakText, outputFile, utteranceId)
                if (!started) {
                    resetOnExit = true
                    return@withLock false
                }
                val callbackSuccess = withTimeout(synthesisTimeoutMs) { done.await() }
                if (!callbackSuccess) {
                    resetOnExit = true
                    return@withLock false
                }
                outputFile.exists() && outputFile.length() > 0L
            } catch (e: CancellationException) {
                stopOnCancel = true
                throw e
            } catch (t: Throwable) {
                resetOnExit = true
                throw t
            } finally {
                pendingUtterances.remove(utteranceId)
                if (resetOnExit) {
                    withContext(NonCancellable) {
                        resetActiveEngineLocked()
                    }
                } else if (stopOnCancel) {
                    withContext(NonCancellable + mainDispatcher) {
                        runCatching { engineInstance.stop() }
                    }
                }
            }
        }
    }

    fun release() {
        released = true
        val pending = pendingUtterances.values.toList()
        pendingUtterances.clear()
        pending.forEach { it.complete(false) }
        val engineToShutdown = activeEngine
        activeEngine = null
        activeEngineName = null
        hasActiveEngine = false
        appliedCustomSpeechRate = false
        if (engineToShutdown != null) {
            runCatching {
                engineToShutdown.stop()
                engineToShutdown.shutdown()
            }
        }
    }

    private suspend fun resetActiveEngineLocked() {
        val previous = activeEngine
        activeEngine = null
        activeEngineName = null
        hasActiveEngine = false
        appliedCustomSpeechRate = false
        if (previous != null) {
            shutdownEngineSafely(previous)
        }
    }

    private suspend fun shutdownEngineSafely(engine: SystemTtsEngineAdapter) {
        withContext(NonCancellable + mainDispatcher) {
            runCatching {
                engine.stop()
                engine.shutdown()
            }
        }
    }
}
