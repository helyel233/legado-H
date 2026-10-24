package io.legado.app.help.book

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 段评点击热路径的编译缓存与分段求值语义回归。
 *
 * [ParagraphRuleProcessor.evalClick] 把原先「兼容层 + ctx 前缀 + jsLib + 点击脚本」
 * 一次拼接求值改为：固定文本按内容哈希缓存编译结果后分段执行，ctx 前缀仍每次求值。
 * 这里锁住两件事：
 * 1. 同一缓存键命中同一编译实例（缓存真的生效，重复点击不再编译）；
 * 2. 分段求值与一次拼接求值行为一致——跨求值边界的函数/变量可见性、
 *    同一编译脚本可重复执行、返回值相同。
 */
class ParagraphRuleCompiledScriptCacheTest {

    private val prelude = """
        ;(function(){
            if (!Array.prototype.clickTestEach) {
                Array.prototype.clickTestEach = function(f){ for (var i = 0; i < this.length; i++) f(this[i], i); };
            }
        })();
    """.trimIndent()

    private val jsLib = "function libTag(){ return 'lib:' + ctx.src; }"

    private val clickJs = "var out = []; [1, 2].clickTestEach(function(v){ out.push(v); }); libTag() + '|' + out.join(',')"

    @Test
    fun sameCacheKeyReturnsSameCompiledInstance() {
        val first = ParagraphRuleProcessor.compiledScript("clickTest:fixed", "var a = 1;")
        val second = ParagraphRuleProcessor.compiledScript("clickTest:fixed", "var a = 1;")
        assertSame(first, second)
    }

    @Test
    fun differentCacheKeysCompileIndependently() {
        val first = ParagraphRuleProcessor.compiledScript("clickTest:a", "var a = 1;")
        val second = ParagraphRuleProcessor.compiledScript("clickTest:b", "var b = 2;")
        assertNotSame(first, second)
    }

    @Test
    fun compiledScriptCanBeExecutedRepeatedly() {
        val compiled = ParagraphRuleProcessor.compiledScript("clickTest:repeat", "var r = 0; r + 1")
        val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        assertEquals(1.0, compiled.eval(scope))
        assertEquals(1.0, compiled.eval(scope))
    }

    @Test
    fun splitEvaluationMatchesSingleConcatenatedEvaluation() {
        // 与 evalClick 一致的分段顺序：兼容层 -> ctx 前缀 -> jsLib -> 点击脚本
        val splitResult = RhinoScriptEngine.run {
            val scope = getRuntimeScope(ScriptBindings())
            ParagraphRuleProcessor.compiledScript("clickTest:prelude", prelude).eval(scope)
            RhinoScriptEngine.eval("var ctx = JSON.parse('{\"src\":\"img-1\"}');", scope)
            ParagraphRuleProcessor.compiledScript("clickTest:jsLib", jsLib).eval(scope)
            ParagraphRuleProcessor.compiledScript("clickTest:clickJs", clickJs).eval(scope)
        }
        // 旧行为：整段拼接一次求值
        val concatenated = prelude + "\n" +
            "var ctx = JSON.parse('{\"src\":\"img-1\"}');\n" +
            jsLib + "\n" +
            clickJs
        val baseline = RhinoScriptEngine.eval(concatenated, ScriptBindings())
        assertEquals(baseline, splitResult)
    }

    @Test
    fun splitEvaluationKeepsFunctionsAndVarsVisibleAcrossEvalBoundaries() {
        val scope = RhinoScriptEngine.run {
            val scope = getRuntimeScope(ScriptBindings())
            ParagraphRuleProcessor.compiledScript("clickTest:lib2", jsLib).eval(scope)
            scope
        }
        RhinoScriptEngine.eval("var ctx = JSON.parse('{\"src\":\"img-2\"}');", scope)
        val result = ParagraphRuleProcessor.compiledScript("clickTest:use", "libTag()")
            .eval(scope)
        assertEquals("lib:img-2", result)
    }
}
