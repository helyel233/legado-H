package io.legado.app.model.analyzeRule

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AnalyzeRuleCacheTest {

    @Before
    fun setUp() {
        AnalyzeRule.clearRuleCache()
    }

    @After
    fun tearDown() {
        AnalyzeRule.clearRuleCache()
    }

    @Test
    fun `compiled regex script and rule templates are reused across distinct AnalyzeRule instances`() {
        val regexPattern = """(\d+)-chapter"""
        val jsBody = """var suffix = '|' + baseUrl; result + suffix"""
        val cssRule = """p.title@text##$regexPattern##num-$1"""
        val jsRule = """@js:$jsBody"""

        val instance1 = AnalyzeRule()
            .setContent("""<div><p class="title">12-chapter</p></div>""", "https://book.test/ch1")
        assertEquals("num-12", instance1.getString(cssRule))
        assertEquals("raw1|https://book.test/ch1", instance1.getString(jsRule, mContent = "raw1"))

        val cachedRegex1 = AnalyzeRule.getCachedRegexForTest(regexPattern)
        val cachedScript1 = AnalyzeRule.getCachedScriptForTest(jsBody)
        val cachedCssTemplates1 = AnalyzeRule.getCachedRuleTemplatesForTest("split:0:Default:false:$cssRule")
        val cachedJsTemplates1 = AnalyzeRule.getCachedRuleTemplatesForTest("split:0:Default:false:$jsRule")

        assertNotNull(cachedRegex1)
        assertNotNull(cachedScript1)
        assertNotNull(cachedCssTemplates1)
        assertNotNull(cachedJsTemplates1)

        val instance2 = AnalyzeRule()
            .setContent("""<div><p class="title">99-chapter</p></div>""", "https://book.test/ch2")
        assertEquals("num-99", instance2.getString(cssRule))
        assertEquals("raw2|https://book.test/ch2", instance2.getString(jsRule, mContent = "raw2"))

        assertSame(cachedRegex1, AnalyzeRule.getCachedRegexForTest(regexPattern))
        assertSame(cachedScript1, AnalyzeRule.getCachedScriptForTest(jsBody))
        assertSame(
            cachedCssTemplates1,
            AnalyzeRule.getCachedRuleTemplatesForTest("split:0:Default:false:$cssRule")
        )
        assertSame(
            cachedJsTemplates1,
            AnalyzeRule.getCachedRuleTemplatesForTest("split:0:Default:false:$jsRule")
        )
        assertEquals(1, AnalyzeRule.getRegexCacheSizeForTest())
        assertEquals(1, AnalyzeRule.getScriptCacheSizeForTest())
        assertEquals(2, AnalyzeRule.getRuleCacheSizeForTest())

        AnalyzeRule.clearRuleCache()
        assertEquals(0, AnalyzeRule.getRegexCacheSizeForTest())
        assertEquals(0, AnalyzeRule.getScriptCacheSizeForTest())
        assertEquals(0, AnalyzeRule.getRuleCacheSizeForTest())
        assertNull(AnalyzeRule.getCachedRegexForTest(regexPattern))
        assertNull(AnalyzeRule.getCachedScriptForTest(jsBody))
    }

    @Test
    fun `source rule instances remain isolated across JSON and HTML modes and mutable makeUpRule`() {
        val plainRule = "title"
        val htmlRule1 = AnalyzeRule().setContent("<p>hello</p>", "https://a.test")
        val jsonRule = AnalyzeRule().setContent("""{"title":"hello"}""", "https://b.test")

        val htmlParsed = htmlRule1.splitSourceRule(plainRule)
        val jsonParsed = jsonRule.splitSourceRule(plainRule)
        assertEquals(1, htmlParsed.size)
        assertEquals(1, jsonParsed.size)
        assertNotSame(htmlParsed[0], jsonParsed[0])
        assertEquals(AnalyzeRule.Mode.Default, htmlParsed[0].mode)
        assertEquals(AnalyzeRule.Mode.Json, jsonParsed[0].mode)
        assertEquals("hello", jsonRule.getString(plainRule))

        // Two instances sharing the same cached template get independent SourceRule instances
        val dynamicRule = """{{result}}##foo##bar"""
        val htmlRule2 = AnalyzeRule().setContent("<p>world</p>", "https://c.test")
        assertEquals("bar-1", htmlRule1.getString(dynamicRule, mContent = "foo-1"))
        assertEquals("bar-2", htmlRule2.getString(dynamicRule, mContent = "foo-2"))
    }

    @Test
    fun `concurrent AnalyzeRule instances executing same cached JS script keep Scriptable scopes isolated`() {
        val jsScript = """
            var perEval = result + '@' + baseUrl;
            if (typeof leakedGlobal === 'undefined') {
                var leakedGlobal = result;
            }
            perEval + '#' + leakedGlobal;
        """.trimIndent()
        val fullRule = "@js:$jsScript"

        val workers = 8
        val iterations = 25
        val executor = Executors.newFixedThreadPool(workers)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(workers)
        val failure = AtomicReference<Throwable?>(null)

        try {
            repeat(workers) { workerId ->
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(iterations) { iter ->
                            val marker = "w$workerId-i$iter"
                            val url = "https://example.com/$workerId/$iter"
                            val analyzer = AnalyzeRule().setContent("seed", url)
                            val actual = analyzer.getString(fullRule, mContent = marker)
                            val expected = "$marker@$url#$marker"
                            assertEquals(expected, actual)
                        }
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS))
            assertNull("Concurrent JS evaluations must stay isolated", failure.get())
            assertEquals(1, AnalyzeRule.getScriptCacheSizeForTest())
        } finally {
            executor.shutdownNow()
        }
    }
}
