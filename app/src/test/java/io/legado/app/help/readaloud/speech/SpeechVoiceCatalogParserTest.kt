package io.legado.app.help.readaloud.speech

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SpeechVoiceCatalogParserTest {

    @Test
    fun blankJson_returnsEmpty() {
        assertTrue(SpeechVoiceCatalogParser.parseSpeakerGroups("").isEmpty())
        assertTrue(SpeechVoiceCatalogParser.parseSpeakerGroups(null).isEmpty())
    }

    @Test
    fun arrayOfGroups_parsesItems() {
        val json = """
        [
          {"groupId":"g1","groupName":"组一","items":[
            {"speakerName":"张三","toneID":"t1"},
            {"speakerName":"李四","toneID":"t2"}
          ]}
        ]
        """.trimIndent()
        val groups = SpeechVoiceCatalogParser.parseSpeakerGroups(json)
        assertEquals(1, groups.size)
        assertEquals("组一", groups[0].groupName)
        assertEquals(2, groups[0].items.size)
    }

    @Test
    fun flatObject_wrappedAsDefaultGroup() {
        val json = """{"speakerName":"张三","toneID":"t1"}"""
        val groups = SpeechVoiceCatalogParser.parseSpeakerGroups(json)
        assertEquals(1, groups.size)
        assertEquals("默认", groups[0].groupName)
        assertEquals(1, groups[0].items.size)
    }

    @Test
    fun invalidSpeakers_filteredOut() {
        val json = """
        [
          {"speakerName":"有效","toneID":"t1"},
          {"speakerName":"缺tone"},
          {"toneID":"缺name"}
        ]
        """.trimIndent()
        val speakers = SpeechVoiceCatalogParser.flattenSpeakers(json)
        assertEquals(1, speakers.size)
        assertEquals("有效", speakers[0].speakerName)
    }

    @Test
    fun emotions_parsedAndValidFiltered() {
        val json = """
        [
          {"groupId":"e1","groupName":"情绪","items":[
            {"emotionName":"开心","emotionTag":"happy"},
            {"emotionName":"缺tag"}
          ]}
        ]
        """.trimIndent()
        val emotions = SpeechVoiceCatalogParser.flattenEmotions(json)
        assertEquals(1, emotions.size)
        assertEquals("开心", emotions[0].emotionName)
    }
}
