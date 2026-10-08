package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFtsTokenizerTest {

    @Test
    fun `index text splits cjk into bigrams`() {
        val indexed = AiFtsTokenizer.indexText("我喜欢阅读")
        val tokens = indexed.split(" ")
        // 我喜欢阅读 -> 我喜 喜欢 欢阅 阅读
        assertEquals(listOf("我喜", "喜欢", "欢阅", "阅读"), tokens)
    }

    @Test
    fun `index text keeps latin words lowercased`() {
        val indexed = AiFtsTokenizer.indexText("Hello World 测试")
        val tokens = indexed.split(" ")
        assertTrue("hello" in tokens)
        assertTrue("world" in tokens)
        assertTrue("测试" in tokens)
    }

    @Test
    fun `index text handles single cjk char`() {
        assertEquals(listOf("爱"), AiFtsTokenizer.indexText("爱").split(" "))
    }

    @Test
    fun `index text blank stays blank`() {
        assertEquals("", AiFtsTokenizer.indexText("  "))
    }

    @Test
    fun `query terms match indexed bigrams`() {
        val content = "主角在青云门修行剑法"
        val indexed = AiFtsTokenizer.indexText(content)
        val terms = AiFtsTokenizer.queryTerms("青云门", maxTerms = 12)
        val indexedTokens = indexed.split(" ").toSet()
        // 查询词的所有二元词都应出现在索引 token 中，保证 FTS 可命中
        terms.forEach { term ->
            assertTrue("term $term should be indexed", term in indexedTokens)
        }
    }

    @Test
    fun `query terms are limited`() {
        val terms = AiFtsTokenizer.queryTerms("一二三四五六七八九十甲乙丙丁戊己庚辛壬癸")
        assertTrue(terms.size <= 12)
    }
}
