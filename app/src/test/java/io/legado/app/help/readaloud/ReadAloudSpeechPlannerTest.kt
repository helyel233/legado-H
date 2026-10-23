package io.legado.app.help.readaloud

import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSpeechPlannerTest {

    private fun cue() = ReadAloudCue(0, "", 0, 0, 0, "k")

    private fun item(
        roleType: String,
        characterName: String = "",
        characterId: Long = 0L,
        narrator: Boolean = false,
        roleLevel: Int = BookCharacter.ROLE_NORMAL,
        sourceCueIndex: Int = 0
    ): ReadAloudSpeechPlanItem {
        return ReadAloudSpeechPlanItem(
            index = 0, cue = cue(), route = null,
            roleType = roleType, characterId = characterId, characterName = characterName,
            avatar = "", emotionName = "", roleLevel = roleLevel,
            leftSide = false, narrator = narrator,
            sourceCueIndex = sourceCueIndex, sourceStart = 0, sourceEnd = 0
        )
    }

    private fun seg(
        start: Int,
        end: Int,
        roleType: String = "narrator",
        characterName: String = "旁白"
    ): AiReadAloudRoleService.Segment {
        return AiReadAloudRoleService.Segment(
            paragraphIndex = 0, start = start, end = end,
            roleType = roleType, characterName = characterName, confidence = 0.5
        )
    }

    @Test
    fun characterNameKey_normalizes() {
        assertEquals("张三", ReadAloudSpeechPlanner.characterNameKey(" 张 三 "))
        assertEquals("李四", ReadAloudSpeechPlanner.characterNameKey("《李四》"))
        assertEquals("john", ReadAloudSpeechPlanner.characterNameKey("John:"))
        assertEquals("王五", ReadAloudSpeechPlanner.characterNameKey("“王五”"))
    }

    @Test
    fun completeCueSegments_emptyText_isEmpty() {
        assertTrue(ReadAloudSpeechPlanner.completeCueSegments(0, "", emptyList()).isEmpty())
    }

    @Test
    fun completeCueSegments_noSegments_fillsNarrator() {
        val result = ReadAloudSpeechPlanner.completeCueSegments(0, "你好", emptyList())
        assertEquals(1, result.size)
        assertEquals("narrator", result[0].roleType)
        assertEquals(0, result[0].start)
        assertEquals(2, result[0].end)
    }

    @Test
    fun completeCueSegments_splitsCharacterAndNarrator() {
        val result = ReadAloudSpeechPlanner.completeCueSegments(
            0, "你好世界", listOf(seg(0, 2, "character", "张三"))
        )
        assertEquals(2, result.size)
        assertEquals("character", result[0].roleType)
        assertEquals(2, result[0].end)
        assertEquals("narrator", result[1].roleType)
        assertEquals(2, result[1].start)
        assertEquals(4, result[1].end)
    }

    @Test
    fun completeCueSegments_clampsOutOfRange() {
        val result = ReadAloudSpeechPlanner.completeCueSegments(
            0, "hi",
            listOf(AiReadAloudRoleService.Segment(0, -5, 100, "character", "张三", confidence = 0.5))
        )
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(2, result[0].end)
    }

    @Test
    fun mergeAdjacentSegments_mergesContiguousSame() {
        val result = ReadAloudSpeechPlanner.mergeAdjacentSegments(
            listOf(seg(0, 2, "character", "A"), seg(2, 4, "character", "A"))
        )
        assertEquals(1, result.size)
        assertEquals(4, result[0].end)
    }

    @Test
    fun mergeAdjacentSegments_keepsGapAndDifferentSpeaker() {
        val gap = ReadAloudSpeechPlanner.mergeAdjacentSegments(
            listOf(seg(0, 2, "character", "A"), seg(3, 5, "character", "A"))
        )
        assertEquals(2, gap.size)

        val diff = ReadAloudSpeechPlanner.mergeAdjacentSegments(
            listOf(seg(0, 2, "character", "A"), seg(2, 4, "character", "B"))
        )
        assertEquals(2, diff.size)
    }

    @Test
    fun assignConversationSides_allNarrator_unchanged() {
        val items = listOf(item("narrator", narrator = true), item("narrator", narrator = true))
        val result = ReadAloudSpeechPlanner.assignConversationSides(items)
        assertEquals(2, result.size)
        assertTrue(result.all { !it.leftSide })
    }

    @Test
    fun assignConversationSides_twoSpeakers_splitSides() {
        val a = item("character", "张三", narrator = false, sourceCueIndex = 0)
        val b = item("character", "李四", narrator = false, sourceCueIndex = 1)
        val result = ReadAloudSpeechPlanner.assignConversationSides(listOf(a, b))
        val ra = result.first { it.characterName == "张三" }
        val rb = result.first { it.characterName == "李四" }
        assertFalse(ra.leftSide) // 先到者占右侧
        assertTrue(rb.leftSide)
    }

    @Test
    fun assignConversationSides_mainCharacterStaysRight() {
        val main = item("character", "主角", narrator = false, roleLevel = BookCharacter.ROLE_MAIN, sourceCueIndex = 0)
        val minor = item("character", "配角", narrator = false, roleLevel = BookCharacter.ROLE_NORMAL, sourceCueIndex = 1)
        val result = ReadAloudSpeechPlanner.assignConversationSides(listOf(main, minor))
        assertFalse(result.first { it.characterName == "主角" }.leftSide)
        assertTrue(result.first { it.characterName == "配角" }.leftSide)
    }
}
