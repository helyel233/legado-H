package io.legado.app.help.http

import android.app.Application
import io.legado.app.help.CacheManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackstageWebViewTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        CacheManager.clearMemoryForTest()
    }

    @After
    fun tearDown() {
        CacheManager.clearMemoryForTest()
    }

    @Test
    fun `concurrent BackstageWebView instances isolate injected result without global webview_result`() {
        val payloadA = "{\"book\":\"Alpha \\\"One\\\"\",\"line\":\"a\\nb</script>\"}"
        val payloadB = "{\"book\":\"Beta 'Two'\",\"unicode\":\"中文\\u2028\\u2029\"}"

        val webViewA = BackstageWebView(url = "https://a.example", html = "<html/>", result = payloadA, isRule = true)
        val webViewB = BackstageWebView(url = "https://b.example", html = "<html/>", result = payloadB, isRule = false)

        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(2)
        try {
            val futureA = executor.submit(Callable {
                latch.countDown()
                latch.await()
                evalWindowResult(webViewA.injectResultScript()!!)
            })
            val futureB = executor.submit(Callable {
                latch.countDown()
                latch.await()
                evalWindowResult(webViewB.injectResultScript()!!)
            })

            assertEquals(payloadA, futureA.get())
            assertEquals(payloadB, futureB.get())
            assertNull(CacheManager.getFromMemory("webview_result"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `null result produces no injection script`() {
        val webView = BackstageWebView(url = "https://a.example", html = "<html/>", result = null)
        assertNull(webView.injectResultScript())
    }

    private fun evalWindowResult(script: String): Any? {
        // Use a local ContextFactory so RhinoScriptEngine's global ContextFactory is not installed
        // into the shared Robolectric sandbox classloader.
        val cx = ContextFactory().enterContext()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            return cx.evaluateString(
                scope,
                "var window = {};\n$script;\nwindow.result;",
                "backstage-test",
                1,
                null
            )
        } finally {
            Context.exit()
        }
    }
}
