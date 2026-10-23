package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudRolePreprocessorTest {

    @Test
    fun emptyParagraphs_returnsEmptyResult() {
        val result = ReadAloudRolePreprocessor.process(emptyList())
        assertEquals(ReadAloudRolePreprocessor.VERSION, result.version)
        assertTrue(result.units.isEmpty())
    }

    @Test
    fun plainNarrativeText_isNarrator() {
        val result = ReadAloudRolePreprocessor.process(listOf("这是一个宁静的夜晚。"))
        val unit = result.units.single()
        assertEquals("narrator", unit.roleType)
        assertEquals("旁白", unit.characterName)
        assertEquals(0.82, unit.confidence, 0.0)
        assertEquals("plain_text", unit.reason)
    }

    @Test
    fun simpleQuotedDialogue_isDialogueNeedingAi() {
        val result = ReadAloudRolePreprocessor.process(listOf("“你好世界。”"))
        val dialogue = result.units.first { it.kind == "dialogue" }
        assertEquals("character", dialogue.roleType)
        assertTrue(dialogue.needsAi)
        assertEquals("quoted_dialogue", dialogue.reason)
    }

    @Test
    fun colonCuePrefix_isDialogue() {
        val result = ReadAloudRolePreprocessor.process(listOf("张三：你好吗"))
        val dialogue = result.units.first { it.kind == "dialogue" }
        assertEquals("colon_dialogue", dialogue.reason)
        assertTrue(dialogue.needsAi)
    }

    @Test
    fun shortQuotedContentWithoutPunctuation_isEmphasis() {
        val result = ReadAloudRolePreprocessor.process(listOf("“嗯”"))
        val unit = result.units.single()
        assertEquals("emphasis", unit.kind)
        assertFalse(unit.needsAi)
    }

    @Test
    fun longQuotedContentWithoutPunctuation_isDialogueByLength() {
        // 无句末标点但长度达到 dialogueMinLength，按对话处理。
        // 注意：citation 分支要求 inner.length > emphasisMaxLength(8) 且 looksLikeDialogue 为假，
        // 而 looksLikeDialogue 为假要求 inner.length < dialogueMinLength(6)，6 < 8，
        // 故 citation 在默认配置下不可达——回归网只覆盖实际可达行为。
        val result = ReadAloudRolePreprocessor.process(
            listOf("“这是一段较长的引用文字没有任何标点结束”")
        )
        val unit = result.units.single()
        assertEquals("dialogue", unit.kind)
    }

    @Test
    fun crossParagraphQuote_mergesIntoDialogue() {
        val result = ReadAloudRolePreprocessor.process(
            listOf("他说：“", "今天天气真好。”")
        )
        assertTrue(result.units.any { it.kind == "dialogue" && it.ranges.size > 1 })
    }

    @Test
    fun thoughtCuePrefix_isThought() {
        val result = ReadAloudRolePreprocessor.process(listOf("他心想：“这下完了。”"))
        val thought = result.units.firstOrNull { it.kind == "thought" }
        assertTrue(thought != null)
        assertEquals("thought", thought?.roleType)
        assertTrue(thought?.needsAi == true)
    }
}
