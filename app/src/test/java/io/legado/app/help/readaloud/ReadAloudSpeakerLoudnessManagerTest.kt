package io.legado.app.help.readaloud

import android.app.Application
import io.legado.app.help.readaloud.ReadAloudSpeakerLoudnessManager.LoudnessStat
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReadAloudSpeakerLoudnessManagerTest {

    private fun stat(activeRmsDb: Float, fullRmsDb: Float = activeRmsDb, count: Int = 1): LoudnessStat {
        return LoudnessStat(
            count = count,
            activeRmsDb = activeRmsDb,
            fullRmsDb = fullRmsDb,
            peak = 0f,
            voicedRatio = 1f,
            updatedAt = 0L,
            narrator = false
        )
    }

    @Test
    fun rmsToDbAndDbToRms_areInverse() {
        val db = ReadAloudSpeakerLoudnessManager.rmsToDb(0.5f)
        assertEquals(0.5f, ReadAloudSpeakerLoudnessManager.dbToRms(db), 1e-4f)
    }

    @Test
    fun dbToGainAndDbForGain_areInverse() {
        assertEquals(1f, ReadAloudSpeakerLoudnessManager.dbToGain(0f), 0f)
        assertEquals(2f, ReadAloudSpeakerLoudnessManager.dbToGain(6.0206f), 1e-3f)
        assertEquals(0f, ReadAloudSpeakerLoudnessManager.dbForGain(1f), 0f)
        val gain = 2f
        assertEquals(
            gain,
            ReadAloudSpeakerLoudnessManager.dbToGain(ReadAloudSpeakerLoudnessManager.dbForGain(gain)),
            1e-4f
        )
    }

    @Test
    fun medianDb_needsAtLeastTwoSpeakers() {
        assertNull(ReadAloudSpeakerLoudnessManager.medianDb(emptyList()))
        assertNull(ReadAloudSpeakerLoudnessManager.medianDb(listOf(stat(0f))))
    }

    @Test
    fun medianDb_evenAndOdd() {
        assertEquals(5f, ReadAloudSpeakerLoudnessManager.medianDb(listOf(stat(0f), stat(10f)))!!, 1e-5f)
        assertEquals(10f, ReadAloudSpeakerLoudnessManager.medianDb(listOf(stat(0f), stat(10f), stat(20f)))!!, 1e-5f)
    }

    @Test
    fun speakerKey_routeTakesPrecedence() {
        val route = SpeechRoute(engineType = "http", engineValue = "v", toneID = "t", speakerName = "s")
        assertEquals("voice|http|v|t|s", ReadAloudSpeakerLoudnessManager.speakerKey(null, route))
    }

    @Test
    fun speakerKey_narratorDefaults() {
        val item = ReadAloudSpeechPlanItem(
            index = 0, cue = ReadAloudCue(0, "", 0, 0, 0, "k"),
            route = null, roleType = "narrator", characterId = 0L, characterName = "旁白",
            avatar = "", emotionName = "", roleLevel = 0, leftSide = false, narrator = true,
            sourceCueIndex = 0, sourceStart = 0, sourceEnd = 0
        )
        assertEquals("narrator|default", ReadAloudSpeakerLoudnessManager.speakerKey(item, null))
    }

    @Test
    fun speakerKey_characterIdBranch() {
        val item = ReadAloudSpeechPlanItem(
            index = 0, cue = ReadAloudCue(0, "", 0, 0, 0, "k"),
            route = null, roleType = "character", characterId = 5L, characterName = "张三",
            avatar = "", emotionName = "", roleLevel = 0, leftSide = false, narrator = false,
            sourceCueIndex = 0, sourceStart = 0, sourceEnd = 0
        )
        assertEquals("character:5", ReadAloudSpeakerLoudnessManager.speakerKey(item, null))
    }

    @Test
    fun speakerKey_roleBranch() {
        val item = ReadAloudSpeechPlanItem(
            index = 0, cue = ReadAloudCue(0, "", 0, 0, 0, "k"),
            route = null, roleType = "character", characterId = 0L, characterName = "张三",
            avatar = "", emotionName = "", roleLevel = 0, leftSide = false, narrator = false,
            sourceCueIndex = 0, sourceStart = 0, sourceEnd = 0
        )
        assertEquals("role|character|张三", ReadAloudSpeakerLoudnessManager.speakerKey(item, null))
    }

    @Test
    fun parse_blankAndCorrupt_returnEmpty() {
        assertTrue(ReadAloudSpeakerLoudnessManager.parse("").isEmpty())
        assertTrue(ReadAloudSpeakerLoudnessManager.parse("{not valid").isEmpty())
    }

    @Test
    fun parse_readsActiveRmsDb() {
        val raw = """{"k1":{"count":2,"activeRmsDb":-3.0,"fullRmsDb":-4.0,"peak":0.5,"voicedRatio":0.8,"updatedAt":100,"narrator":false}}"""
        val map = ReadAloudSpeakerLoudnessManager.parse(raw)
        assertEquals(1, map.size)
        assertEquals(2, map["k1"]!!.count)
        assertEquals(-3.0f, map["k1"]!!.activeRmsDb, 1e-5f)
    }

    @Test
    fun parse_legacyRmsDerivesDb() {
        // 旧格式只有 rms，应推导 activeRmsDb / fullRmsDb
        val raw = """{"k2":{"count":1,"rms":0.5}}"""
        val map = ReadAloudSpeakerLoudnessManager.parse(raw)
        assertEquals(1, map.size)
        assertEquals(-6.0206f, map["k2"]!!.activeRmsDb, 1e-3f)
    }

    @Test
    fun parse_dropsZeroCount() {
        val raw = """{"k3":{"count":0,"activeRmsDb":1.0}}"""
        assertTrue(ReadAloudSpeakerLoudnessManager.parse(raw).isEmpty())
    }
}
