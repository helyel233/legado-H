package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import io.legado.app.help.config.AppConfig

/**
 * Web 服务访问口令守卫。
 *
 * ## 为什么需要
 *
 * 内置 Web 服务开启后绑定所有网卡，而 `HttpServer` 原先**没有任何鉴权**：
 * 同一局域网内任何人打开 `http://手机IP:1122` 就能增删书源、删书、
 * 读取书源登录态。口令留空时（默认）完全跳过校验，行为与旧版一致。
 *
 * ## 三种放行方式（任一即可）
 *
 * 1. `Authorization: Bearer <口令>` —— 给脚本/第三方客户端用；
 *    Web 前端本身已有 Authorization 通道（原本给反向隧道用）。
 * 2. Cookie `legado_web_token` —— 浏览器首次带 `?token=` 打开后由服务端种下，
 *    之后同源请求自动带上，用户无感。
 * 3. URL 查询参数 `?token=<口令>` —— 首次打开用。
 *
 * 之所以不需要改前端：浏览器对同源请求默认携带 Cookie，所以只要服务端在
 * 返回页面时种下 Cookie，页面里的 XHR/fetch 都会自动带上令牌。
 *
 * 本对象只做判断与字符串拼装，不依赖 Android 框架（除 AppConfig），便于单测。
 */
object WebServiceGuard {

    private const val COOKIE_NAME = "legado_web_token"

    /** Cookie 有效期：7 天，与回收站保留期一致，够用又不至于长期挂在那 */
    private const val COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60

    private fun expected(): String = AppConfig.webServiceToken.trim()

    /** 是否启用了口令。留空即未启用。 */
    fun isEnabled(): Boolean = expected().isNotEmpty()

    /**
     * 本次请求是否放行。
     *
     * 口令未启用（[expected] 为空）时恒为 true（旧行为）。
     */
    fun isAuthorized(session: NanoHTTPD.IHTTPSession, expected: String): Boolean {
        if (expected.isEmpty()) return true
        return extractToken(session) == expected
    }

    fun isAuthorized(session: NanoHTTPD.IHTTPSession): Boolean =
        isAuthorized(session, expected())

    /**
     * 是否需要给本次响应种 Cookie。
     *
     * 条件：口令已启用、令牌来自 URL 查询参数且正确、请求本身没带有效 Cookie。
     * 只在返回页面（静态资源）时调用，避免给每个 API 响应都种一次。
     */
    fun shouldSetCookie(session: NanoHTTPD.IHTTPSession, expected: String): Boolean {
        if (expected.isEmpty()) return false
        val fromQuery = session.parameters["token"]?.firstOrNull() ?: return false
        if (fromQuery != expected) return false
        return cookieToken(header(session, "cookie")) != expected
    }

    fun shouldSetCookie(session: NanoHTTPD.IHTTPSession): Boolean =
        shouldSetCookie(session, expected())

    /** 种 Cookie 用的 Set-Cookie 头内容。 */
    fun cookieHeader(token: String): String {
        // HttpOnly 让 JS 读不到（降低被页面内脚本偷走的风险）；
        // SameSite=Lax 挡跨站携带；Path=/ 覆盖全部接口
        return "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=$COOKIE_MAX_AGE_SECONDS"
    }

    /**
     * 按优先级取令牌：Authorization 头 → Cookie → URL 查询参数。
     *
     * 三者取到的值都必须与口令**完全相等**才算通过，不做模糊匹配。
     */
    fun extractToken(session: NanoHTTPD.IHTTPSession): String? {
        val fromHeader = header(session, "authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromHeader != null) return fromHeader

        val fromCookie = cookieToken(header(session, "cookie"))
        if (fromCookie != null) return fromCookie

        return session.parameters["token"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 按名字取请求头，**大小写不敏感**。
     *
     * HTTP 头名本身大小写不敏感，而 NanoHTTPD 按客户端发来的原样存 key：
     * 浏览器发的是 `Cookie`/`Authorization`，直接 `headers["cookie"]` 取不到，
     * 会导致口令明明对了却判未授权。
     */
    private fun header(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value

    /** 从 Cookie 头里取出口令值。 */
    fun cookieToken(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        cookieHeader.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            if (part.take(idx).trim() == COOKIE_NAME) {
                return part.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** 给用户的提示：带上口令的完整地址。 */
    fun authorizedUrl(hostAddress: String, token: String): String {
        if (token.isBlank()) return hostAddress
        val separator = if (hostAddress.contains("?")) "&" else "?"
        return "$hostAddress${separator}token=$token"
    }
}
