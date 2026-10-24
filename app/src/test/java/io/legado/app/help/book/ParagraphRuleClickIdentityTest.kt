package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 段落规则点击值的规则 id 提取回归。
 * 快照弹窗按 `paragraph_rule_<id>` 源键加载规则宿主（页内 ajax / 发表评论
 * 走规则登录态），id 取错会整个弹窗行为漂移，故单独钉住两种包装格式。
 */
class ParagraphRuleClickIdentityTest {

    @Test
    fun extractsRuleIdFromWrappedClick() {
        // wrapClick：paragraphRule:rule:<id>\n<js>
        assertEquals(
            12L,
            ParagraphRuleProcessor.ruleIdOfClick("paragraphRule:rule:12\njava.showBrowser('u')")
        )
    }

    @Test
    fun extractsRuleIdFromCompactClick() {
        // wrapRuleClick：rule:<id>:<js>
        assertEquals(34L, ParagraphRuleProcessor.ruleIdOfClick("rule:34:java.showBrowser('u')"))
    }

    @Test
    fun extractsRuleIdFromCompactClickWithMultilineJs() {
        // wrapRuleClick 的 js 含真实换行时，换行分隔分支解析失败须回退冒号分支
        assertEquals(34L, ParagraphRuleProcessor.ruleIdOfClick("rule:34:line1\nline2"))
        assertEquals(7L, ParagraphRuleProcessor.ruleIdOfClick("rule:7:\nvar x = 1;"))
    }

    @Test
    fun extractsRuleIdFromWrappedClickWithMultilineJs() {
        // wrapClick（paragraphRule:rule:<id>\n<js>）的 js 本身含换行时不受影响
        assertEquals(12L, ParagraphRuleProcessor.ruleIdOfClick("paragraphRule:rule:12\nline1\nline2"))
    }

    @Test
    fun returnsNullForNonRuleClickValues() {
        assertNull(ParagraphRuleProcessor.ruleIdOfClick(null))
        assertNull(ParagraphRuleProcessor.ruleIdOfClick(""))
        assertNull(ParagraphRuleProcessor.ruleIdOfClick("java.showBrowser('u')"))
        assertNull(ParagraphRuleProcessor.ruleIdOfClick("rule:"))
        assertNull(ParagraphRuleProcessor.ruleIdOfClick("rule:notANumber:js"))
        assertNull(ParagraphRuleProcessor.ruleIdOfClick("paragraphRule:rule:7"))
    }
}
