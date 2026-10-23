package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSoundEffectPreprocessorTest {

    private fun config(
        cues: List<String> = listOf("砰"),
        excludes: List<String> = emptyList(),
        contextChars: Int = 28
    ) = ReadAloudPreprocessRuleConfig(
        soundEffectCuePatterns = cues,
        soundEffectExcludePatterns = excludes,
        soundEffectContextChars = contextChars
    )

    @Test
    fun emptyParagraphs_returnsEmpty() {
        val r = ReadAloudSoundEffectPreprocessor.process(emptyList(), config = config())
        assertTrue(r.candidates.isEmpty())
        assertEquals(ReadAloudSoundEffectPreprocessor.VERSION, r.version)
    }

    @Test
    fun blankCuePatterns_returnsEmpty() {
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf("砰"),
            config = config(cues = listOf(" ", "", "  "))
        )
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun singleCueMatch_hasCorrectFields() {
        val para = "他砰的一声关门"
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf(para),
            config = config(cues = listOf("砰"))
        )
        assertEquals(1, r.candidates.size)
        val c = r.candidates[0]
        assertEquals(0, c.paragraphIndex)
        assertEquals(1, c.start)
        assertEquals(2, c.end)
        assertEquals("砰", c.cue)
        assertEquals("砰", c.text)
        assertEquals("cue_pattern", c.reason)
        assertTrue(c.context.contains("砰"))
        assertTrue(c.id.startsWith("sfx_0_1_"))
    }

    @Test
    fun cueAppearsTwice_producesTwoCandidates() {
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf("砰砰"),
            config = config(cues = listOf("砰"))
        )
        assertEquals(2, r.candidates.size)
        assertEquals(0, r.candidates[0].start)
        assertEquals(1, r.candidates[1].start)
    }

    @Test
    fun paragraphOffset_shiftsParagraphIndex() {
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf("a砰b", "c砰d"),
            paragraphOffset = 5,
            config = config(cues = listOf("砰"))
        )
        assertEquals(listOf(5, 6), r.candidates.map { it.paragraphIndex })
    }

    @Test
    fun excludePattern_dropsCandidateWhenContextContainsIt() {
        // "声" 作为 cue 会命中 "心声"，但 exclude "心声" 应过滤掉
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf("她的心声很轻"),
            config = config(cues = listOf("声"), excludes = listOf("心声"))
        )
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun excludePattern_keepsCandidateWhenContextClean() {
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf("枪声大作"),
            config = config(cues = listOf("声"), excludes = listOf("心声"))
        )
        assertEquals(1, r.candidates.size)
    }

    @Test
    fun contextChars_coercedToMinimum() {
        val para = "0123456789abcdef砰ghijklmnopqrstuvwxyz"
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf(para),
            config = config(cues = listOf("砰"), contextChars = 5) // 应被 coerceIn(8,120) 抬到 8
        )
        val c = r.candidates.single()
        val prefix = c.context.substringBefore("砰")
        assertTrue("前缀不应超过 8 字符，实际 ${prefix.length}", prefix.length <= 8)
    }

    @Test
    fun contextChars_coercedToMaximum() {
        val para = "x".repeat(300) + "砰" + "y".repeat(300)
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf(para),
            config = config(cues = listOf("砰"), contextChars = 200) // 应被 coerceIn 到 120
        )
        val c = r.candidates.single()
        val prefix = c.context.substringBefore("砰")
        val suffix = c.context.substringAfter("砰")
        assertTrue("前缀不应超过 120 字符，实际 ${prefix.length}", prefix.length <= 120)
        assertTrue("后缀不应超过 120 字符，实际 ${suffix.length}", suffix.length <= 120)
    }

    @Test
    fun duplicateCuePatterns_deduplicated() {
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf("砰"),
            config = config(cues = listOf("砰", "砰", " 砰 "))
        )
        assertEquals(1, r.candidates.size)
    }

    @Test
    fun result_sortedByParagraphIndexThenStart() {
        val para = "敲门声骤起砰"
        // cuePatterns 顺序与出现顺序不同，结果须按位置排序而非按 cue 顺序
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf(para),
            config = config(cues = listOf("砰", "敲门声"))
        )
        assertEquals(2, r.candidates.size)
        assertEquals(para.indexOf("敲门声"), r.candidates[0].start)
        assertEquals(para.indexOf("砰"), r.candidates[1].start)
    }

    @Test
    fun blankCueEntriesTrimmedBeforeMatch() {
        val r = ReadAloudSoundEffectPreprocessor.process(
            listOf("砰"),
            config = config(cues = listOf(" 砰 ", ""))
        )
        assertEquals(1, r.candidates.size)
    }
}
