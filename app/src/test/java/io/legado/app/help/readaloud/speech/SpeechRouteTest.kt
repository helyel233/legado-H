package io.legado.app.help.readaloud.speech

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SpeechRouteTest {

    @Test
    fun blankFromJson_isDefault() {
        assertEquals(SpeechRoute(), SpeechRoute.fromJson(""))
        assertEquals(SpeechRoute(), SpeechRoute.fromJson(null))
    }

    @Test
    fun toJsonThenFromJson_roundTrips() {
        val route = SpeechRoute(
            engineType = "http",
            engineValue = "123",
            speakerName = "小明",
            toneID = "tone-1",
            emotionName = "开心",
            emotionTag = "happy",
            groupId = "g1",
            groupName = "组1",
            source = "manual"
        )
        val restored = SpeechRoute.fromJson(route.toJson())
        assertEquals(route, restored)
    }

    @Test
    fun fromJson_readsToneIdAlias() {
        val json = """{"engineType":"http","engineValue":"1","speakerName":"x","toneId":"alias-9"}"""
        val route = SpeechRoute.fromJson(json)
        assertEquals("alias-9", route.toneID)
    }

    @Test
    fun fromJson_corrupt_returnsDefault() {
        assertEquals(SpeechRoute(), SpeechRoute.fromJson("{not valid"))
    }

    @Test
    fun fromTtsEngineValue_blank_isSystemDefault() {
        val route = SpeechRoute.fromTtsEngineValue("")
        assertEquals(SpeechRoute.ENGINE_SYSTEM, route.engineType)
        assertEquals("系统默认", route.speakerName)
        assertEquals(SpeechRoute.SOURCE_MANUAL, route.source)
    }

    @Test
    fun fromTtsEngineValue_numeric_isHttp() {
        val route = SpeechRoute.fromTtsEngineValue("12345")
        assertEquals(SpeechRoute.ENGINE_HTTP, route.engineType)
        assertEquals("12345", route.engineValue)
    }

    @Test
    fun fromTtsEngineValue_plainString_isSystemWithValue() {
        val route = SpeechRoute.fromTtsEngineValue("自定义音色名")
        assertEquals(SpeechRoute.ENGINE_SYSTEM, route.engineType)
        assertEquals("自定义音色名", route.engineValue)
    }

    @Test
    fun isConfigured_reflectsFields() {
        assertFalse(SpeechRoute().isConfigured)
        assertTrue(SpeechRoute(engineValue = "x").isConfigured)
        assertTrue(SpeechRoute(toneID = "y").isConfigured)
        assertTrue(SpeechRoute(speakerName = "z").isConfigured)
    }

    @Test
    fun toJson_skipsEmptyUnconfigured() {
        assertEquals("", SpeechRoute().toJson())
    }
}
