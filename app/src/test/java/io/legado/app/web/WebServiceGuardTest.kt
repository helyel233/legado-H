package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Web 服务口令守卫单测。
 *
 * 口令逻辑分支很多（Authorization / Cookie / URL 三种来源 + 空口令 + 错口令 + 优先级），
 * 且错一点的后果是"用户打不开 Web 页"或"口令形同虚设"，所以用纯 JVM 测试锁死行为。
 */
class WebServiceGuardTest {

    private val token = "s3cr3t-token"

    // ---- 放行 / 拒绝 ----

    @Test
    fun `未启口令时任何请求都放行`() {
        val session = FakeSession()
        assertTrue(WebServiceGuard.isAuthorized(session, expected = ""))
    }

    @Test
    fun `Authorization Bearer 正确时放行`() {
        val session = FakeSession(headers = mapOf("Authorization" to "Bearer $token"))
        assertTrue(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `Authorization 大小写不敏感`() {
        val session = FakeSession(headers = mapOf("authorization" to "bearer $token"))
        assertTrue(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `Authorization 错误时拒绝`() {
        val session = FakeSession(headers = mapOf("Authorization" to "Bearer wrong"))
        assertFalse(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `Authorization 不是 Bearer 方案时拒绝`() {
        val session = FakeSession(headers = mapOf("Authorization" to "Basic $token"))
        assertFalse(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `Cookie 正确时放行`() {
        val session = FakeSession(headers = mapOf("Cookie" to "legado_web_token=$token"))
        assertTrue(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `Cookie 混在其它 cookie 里也能取到`() {
        val session = FakeSession(
            headers = mapOf("Cookie" to "other=1; legado_web_token=$token; another=2")
        )
        assertTrue(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `Cookie 错误时拒绝`() {
        val session = FakeSession(headers = mapOf("Cookie" to "legado_web_token=wrong"))
        assertFalse(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `URL 查询参数正确时放行`() {
        val session = FakeSession(parameters = mapOf("token" to listOf(token)))
        assertTrue(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `URL 查询参数错误时拒绝`() {
        val session = FakeSession(parameters = mapOf("token" to listOf("wrong")))
        assertFalse(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `什么都没带时拒绝`() {
        assertFalse(WebServiceGuard.isAuthorized(FakeSession(), token))
    }

    @Test
    fun `Authorization 优先于 Cookie`() {
        // 头里带错口令、Cookie 带对口令：以头为准，应拒绝
        val session = FakeSession(
            headers = mapOf(
                "Authorization" to "Bearer wrong",
                "Cookie" to "legado_web_token=$token"
            )
        )
        assertFalse(WebServiceGuard.isAuthorized(session, token))
    }

    @Test
    fun `Cookie 优先于 URL 参数`() {
        val session = FakeSession(
            headers = mapOf("Cookie" to "legado_web_token=wrong"),
            parameters = mapOf("token" to listOf(token))
        )
        assertFalse(WebServiceGuard.isAuthorized(session, token))
    }

    // ---- 取令牌 ----

    @Test
    fun `extractToken 三种来源都能取到`() {
        assertEquals(
            token,
            WebServiceGuard.extractToken(
                FakeSession(headers = mapOf("Authorization" to "Bearer $token"))
            )
        )
        assertEquals(
            token,
            WebServiceGuard.extractToken(
                FakeSession(headers = mapOf("Cookie" to "legado_web_token=$token"))
            )
        )
        assertEquals(
            token,
            WebServiceGuard.extractToken(
                FakeSession(parameters = mapOf("token" to listOf(token)))
            )
        )
    }

    @Test
    fun `extractToken 都取不到时返回 null`() {
        assertNull(WebServiceGuard.extractToken(FakeSession()))
        assertNull(
            WebServiceGuard.extractToken(
                FakeSession(headers = mapOf("Authorization" to "Bearer "))
            )
        )
    }

    @Test
    fun `cookieToken 空头或空值返回 null`() {
        assertNull(WebServiceGuard.cookieToken(null))
        assertNull(WebServiceGuard.cookieToken(""))
        assertNull(WebServiceGuard.cookieToken("legado_web_token="))
        assertNull(WebServiceGuard.cookieToken("other=1"))
    }

    @Test
    fun `cookieToken 两侧空格也认得`() {
        assertEquals(token, WebServiceGuard.cookieToken("  legado_web_token = $token  "))
    }

    // ---- 种 Cookie ----

    @Test
    fun `URL 带正确令牌且无 cookie 时才需要种 cookie`() {
        assertTrue(
            WebServiceGuard.shouldSetCookie(
                FakeSession(parameters = mapOf("token" to listOf(token))),
                token
            )
        )
    }

    @Test
    fun `已带正确 cookie 就不再种`() {
        assertFalse(
            WebServiceGuard.shouldSetCookie(
                FakeSession(
                    headers = mapOf("Cookie" to "legado_web_token=$token"),
                    parameters = mapOf("token" to listOf(token))
                ),
                token
            )
        )
    }

    @Test
    fun `URL 令牌错误不种 cookie`() {
        assertFalse(
            WebServiceGuard.shouldSetCookie(
                FakeSession(parameters = mapOf("token" to listOf("wrong"))),
                token
            )
        )
    }

    @Test
    fun `未启口令不种 cookie`() {
        assertFalse(
            WebServiceGuard.shouldSetCookie(
                FakeSession(parameters = mapOf("token" to listOf(token))),
                ""
            )
        )
    }

    @Test
    fun `cookieHeader 带全安全属性`() {
        val header = WebServiceGuard.cookieHeader(token)
        assertTrue(header.contains("legado_web_token=$token"))
        assertTrue(header.contains("Path=/"))
        assertTrue(header.contains("HttpOnly"))
        assertTrue(header.contains("SameSite=Lax"))
        assertTrue(header.contains("Max-Age="))
    }

    // ---- 地址拼接 ----

    @Test
    fun `authorizedUrl 未启口令时原样返回`() {
        assertEquals("http://1.2.3.4:1122", WebServiceGuard.authorizedUrl("http://1.2.3.4:1122", ""))
    }

    @Test
    fun `authorizedUrl 追加 token 参数`() {
        assertEquals(
            "http://1.2.3.4:1122?token=abc",
            WebServiceGuard.authorizedUrl("http://1.2.3.4:1122", "abc")
        )
    }

    @Test
    fun `authorizedUrl 已有查询串时用 and 连接`() {
        assertEquals(
            "http://1.2.3.4:1122?a=1&token=abc",
            WebServiceGuard.authorizedUrl("http://1.2.3.4:1122?a=1", "abc")
        )
    }
}

/** 只实现口令校验用得到的几个方法，其余抛异常防止误用。 */
private class FakeSession(
    private val headers: Map<String, String> = emptyMap(),
    private val parameters: Map<String, List<String>> = emptyMap()
) : NanoHTTPD.IHTTPSession {

    override fun execute() = Unit

    override fun getCookies(): NanoHTTPD.CookieHandler? = null

    override fun getHeaders(): MutableMap<String, String> = headers.toMutableMap()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.GET

    override fun getParms(): MutableMap<String, String> = mutableMapOf()

    override fun getParameters(): MutableMap<String, MutableList<String>> =
        parameters.mapValues { it.value.toMutableList() }.toMutableMap()

    override fun getQueryParameterString(): String = ""

    override fun getUri(): String = "/"

    override fun parseBody(files: MutableMap<String, String>?) = Unit

    override fun getRemoteIpAddress(): String = "127.0.0.1"

    override fun getRemoteHostName(): String = "localhost"
}
