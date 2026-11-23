package io.legado.app.service

import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.readaloud.speech.SpeechRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HttpReadAloudCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cache key stays identical when all synthesis parameters match`() {
        val httpTts = HttpTTS(
            id = 101L,
            name = "Azure",
            url = "https://tts.example.com/speak?text={{speakText}}",
            header = "{\"Authorization\":\"Bearer token\"}",
            contentType = "audio/mpeg"
        )
        val route = SpeechRoute(
            engineType = SpeechRoute.ENGINE_HTTP,
            engineValue = "101",
            speakerName = "Xiaoxiao",
            toneID = "zh-CN-XiaoxiaoNeural",
            emotionName = "cheerful",
            emotionTag = "cheerful"
        )

        val key1 = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "第一章",
            content = "你好世界",
            route = route,
            httpTts = httpTts,
            defaultTtsEngine = "com.google.android.tts",
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )
        val key2 = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "第一章",
            content = "你好世界",
            route = route.copy(),
            httpTts = httpTts.copy(),
            defaultTtsEngine = "com.google.android.tts",
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )

        assertEquals(key1, key2)
        assertTrue(key1.matches(Regex("[0-9a-f]{16}_[0-9a-f]{16}")))
    }

    @Test
    fun `cache key differentiates route-specific httpTts, voice, system engine, and speech rate`() {
        val httpTtsA = HttpTTS(
            id = 1L,
            name = "EngineA",
            url = "https://a.example.com/tts?rate={{speakSpeed}}",
            header = "{\"X-Key\":\"A\"}",
            contentType = "audio/mpeg"
        )
        val httpTtsB = httpTtsA.copy(
            id = 2L,
            url = "https://b.example.com/tts?rate={{speakSpeed}}"
        )
        val httpTtsEditedHeader = httpTtsA.copy(header = "{\"X-Key\":\"B\"}")
        val routeA = SpeechRoute(
            engineType = SpeechRoute.ENGINE_HTTP,
            engineValue = "1",
            speakerName = "Alice",
            toneID = "voice-a",
            emotionTag = "calm"
        )
        val routeB = routeA.copy(toneID = "voice-b")
        val sysRoute = SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = "com.google.android.tts",
            speakerName = "System"
        )

        val base = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = routeA,
            httpTts = httpTtsA,
            defaultTtsEngine = null,
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )
        val diffHttpTts = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = routeA,
            httpTts = httpTtsB,
            defaultTtsEngine = null,
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )
        val diffHeader = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = routeA,
            httpTts = httpTtsEditedHeader,
            defaultTtsEngine = null,
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )
        val diffVoice = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = routeB,
            httpTts = httpTtsA,
            defaultTtsEngine = null,
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )
        val diffRate = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = routeA,
            httpTts = httpTtsA,
            defaultTtsEngine = null,
            speechRate = 15,
            ttsFlowSys = false,
            ttsSpeechRate = 10
        )
        val sysCustomRate = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = sysRoute,
            httpTts = null,
            defaultTtsEngine = "com.google.android.tts",
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )
        val sysFollowRate = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = sysRoute,
            httpTts = null,
            defaultTtsEngine = "com.google.android.tts",
            speechRate = 10,
            ttsFlowSys = true,
            ttsSpeechRate = 5
        )
        val sysOtherEngine = HttpReadAloudCacheKey.buildSpeakFileName(
            chapterTitle = "Chapter 1",
            content = "Hello",
            route = sysRoute.copy(engineValue = "com.samsung.SMT"),
            httpTts = null,
            defaultTtsEngine = "com.google.android.tts",
            speechRate = 10,
            ttsFlowSys = false,
            ttsSpeechRate = 5
        )

        val allKeys = listOf(
            base,
            diffHttpTts,
            diffHeader,
            diffVoice,
            diffRate,
            sysCustomRate,
            sysFollowRate,
            sysOtherEngine
        )
        assertEquals(allKeys.size, allKeys.distinct().size)
    }

    @Test
    fun `sliding window pruner keeps protected chapter files and trims oldest excess files`() {
        val cacheDir = tempFolder.newFolder("httpTTS")
        val now = 1_000_000L
        val currentPrefix = HttpReadAloudCachePruner.chapterPrefix("Chapter 5")!!
        val nextPrefix = HttpReadAloudCachePruner.chapterPrefix("Chapter 6")!!
        val oldPrefix = HttpReadAloudCachePruner.chapterPrefix("Chapter 1")!!

        fun createCachedFile(name: String, size: Int, ageMs: Long): File {
            return File(cacheDir, name).apply {
                writeBytes(ByteArray(size) { 1 })
                setLastModified(now - ageMs)
            }
        }

        val currentFile = createCachedFile("${currentPrefix}cue1.mp3", 1000, ageMs = 900_000L)
        val nextFile = createCachedFile("${nextPrefix}cue1.mp3", 1000, ageMs = 900_000L)
        val protectedExact = createCachedFile("blank_chapter_active.mp3", 1000, ageMs = 900_000L)
        val activeSilent = createCachedFile("active_silent.mp3", 2160, ageMs = 1_000L)
        val staleSilent = createCachedFile("${currentPrefix}stale_silent.mp3", 2160, ageMs = 1_000L)
        val expiredOld = createCachedFile("${oldPrefix}expired.mp3", 1000, ageMs = 700_000L)
        val recentOldest = createCachedFile("${oldPrefix}recent1.mp3", 1000, ageMs = 50_000L)
        val recentMiddle = createCachedFile("${oldPrefix}recent2.mp3", 1000, ageMs = 30_000L)
        val recentNewest = createCachedFile("${oldPrefix}recent3.mp3", 1000, ageMs = 10_000L)

        val result = HttpReadAloudCachePruner.prune(
            cacheDir = cacheDir,
            protectedFileNames = setOf(protectedExact.name, activeSilent.name),
            protectedPrefixes = setOf(currentPrefix, nextPrefix),
            maxFiles = 5,
            maxBytes = 100_000L,
            maxAgeMs = 600_000L,
            nowMs = now
        )

        assertTrue(currentFile.exists())
        assertTrue(nextFile.exists())
        assertTrue(protectedExact.exists())
        assertTrue(activeSilent.exists())
        assertTrue(recentNewest.exists())

        assertFalse(staleSilent.exists())
        assertFalse(expiredOld.exists())
        assertFalse(recentOldest.exists())
        assertFalse(recentMiddle.exists())
        assertEquals(4, result.deletedCount)
        assertEquals(5, result.remainingCount)
    }

    @Test
    fun `sliding window pruner enforces maxBytes cap while preserving protected files`() {
        val cacheDir = tempFolder.newFolder("httpTTS_bytes")
        val now = 2_000_000L
        val currentPrefix = HttpReadAloudCachePruner.chapterPrefix("Current")!!

        val protectedLarge = File(cacheDir, "${currentPrefix}keep.mp3").apply {
            writeBytes(ByteArray(3000) { 2 })
            setLastModified(now - 10_000L)
        }
        val unprotectedOld = File(cacheDir, "old_a.mp3").apply {
            writeBytes(ByteArray(2000) { 3 })
            setLastModified(now - 5_000L)
        }
        val unprotectedNew = File(cacheDir, "old_b.mp3").apply {
            writeBytes(ByteArray(1500) { 4 })
            setLastModified(now - 1_000L)
        }

        val result = HttpReadAloudCachePruner.prune(
            cacheDir = cacheDir,
            protectedPrefixes = setOf(currentPrefix),
            maxFiles = 50,
            maxBytes = 4500L,
            maxAgeMs = 600_000L,
            nowMs = now
        )

        assertTrue(protectedLarge.exists())
        assertFalse(unprotectedOld.exists())
        assertTrue(unprotectedNew.exists())
        assertEquals(1, result.deletedCount)
        assertEquals(4500L, result.remainingBytes)
    }

    @Test
    fun `serial tts coordinator reuses single engine and serializes concurrent utterances`() = runBlocking {
        val dir = tempFolder.newFolder("tts_serial")
        val createCount = AtomicInteger(0)
        val shutdownCount = AtomicInteger(0)
        val activeSyntheses = AtomicInteger(0)
        val maxConcurrentSyntheses = AtomicInteger(0)
        val observedUtterances = CopyOnWriteArrayList<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val coordinator = SystemTtsSynthesisCoordinator(
            mainDispatcher = Dispatchers.Unconfined,
            createEngine = { _, callback ->
                createCount.incrementAndGet()
                object : SystemTtsEngineAdapter {
                    override fun setSpeechRate(rate: Float) = Unit

                    override fun synthesizeToFile(
                        text: String,
                        outputFile: File,
                        utteranceId: String
                    ): Boolean {
                        val concurrent = activeSyntheses.incrementAndGet()
                        maxConcurrentSyntheses.updateAndGet { prev -> maxOf(prev, concurrent) }
                        observedUtterances.add(text)
                        Thread {
                            if (text == "cue-1") {
                                firstStarted.complete(Unit)
                                runBlocking { releaseFirst.await() }
                            }
                            outputFile.writeBytes(byteArrayOf(1, 2, 3, 4))
                            activeSyntheses.decrementAndGet()
                            callback.onDone(utteranceId)
                        }.start()
                        return true
                    }

                    override fun stop() = Unit

                    override fun shutdown() {
                        shutdownCount.incrementAndGet()
                    }
                }
            }
        )

        val job1 = async(Dispatchers.Default) {
            coordinator.synthesize("engine-a", "cue-1", File(dir, "cue1.mp3"), 1.0f)
        }
        firstStarted.await()
        val job2 = async(Dispatchers.Default) {
            coordinator.synthesize("engine-a", "cue-2", File(dir, "cue2.mp3"), 1.2f)
        }
        val job3 = async(Dispatchers.Default) {
            coordinator.synthesize("engine-a", "cue-3", File(dir, "cue3.mp3"), 1.5f)
        }
        releaseFirst.complete(Unit)

        val results = awaitAll(job1, job2, job3)
        assertEquals(listOf(true, true, true), results)
        assertEquals(1, createCount.get())
        assertEquals(1, maxConcurrentSyntheses.get())
        assertEquals("cue-1", observedUtterances.first())
        assertEquals(setOf("cue-1", "cue-2", "cue-3"), observedUtterances.toSet())

        // Switching engine recreates once and shuts down the old instance
        val switched = coordinator.synthesize("engine-b", "cue-4", File(dir, "cue4.mp3"), 1.0f)
        assertTrue(switched)
        assertEquals(2, createCount.get())
        assertEquals(1, shutdownCount.get())

        coordinator.release()
        assertEquals(2, shutdownCount.get())
    }

    @Test
    fun `serial tts coordinator recovers by reinitializing engine after synthesis error`() = runBlocking {
        val dir = tempFolder.newFolder("tts_recover")
        val createCount = AtomicInteger(0)
        val shutdownCount = AtomicInteger(0)

        val coordinator = SystemTtsSynthesisCoordinator(
            mainDispatcher = Dispatchers.Unconfined,
            createEngine = { _, callback ->
                val attempt = createCount.incrementAndGet()
                object : SystemTtsEngineAdapter {
                    override fun setSpeechRate(rate: Float) = Unit

                    override fun synthesizeToFile(
                        text: String,
                        outputFile: File,
                        utteranceId: String
                    ): Boolean {
                        if (attempt == 1) {
                            callback.onError(utteranceId)
                        } else {
                            outputFile.writeBytes(byteArrayOf(7, 8, 9))
                            callback.onDone(utteranceId)
                        }
                        return true
                    }

                    override fun stop() = Unit

                    override fun shutdown() {
                        shutdownCount.incrementAndGet()
                    }
                }
            }
        )

        val first = coordinator.synthesize("engine-a", "fail", File(dir, "f1.mp3"), 1.0f)
        val second = coordinator.synthesize("engine-a", "ok", File(dir, "f2.mp3"), 1.0f)

        assertFalse(first)
        assertTrue(second)
        assertEquals(2, createCount.get())
        assertEquals(1, shutdownCount.get())

        coordinator.release()
        assertEquals(2, shutdownCount.get())
    }
}
