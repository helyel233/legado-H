package io.legado.app.web

import fi.iki.elonen.NanoWSD
import io.legado.app.service.WebService
import io.legado.app.web.socket.*

/**
 * @param hostname 绑定地址：null（默认）绑所有网卡；未设访问口令时传 127.0.0.1 仅本机可访问，
 *   与 HttpServer 的回环约束保持一致（该端口上有远程执行面的调试端点）。
 */
class WebSocketServer(port: Int, hostname: String? = null) : NanoWSD(hostname, port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        WebService.serve()
        // WebSocket 握手本质是 HTTP 升级请求，同样要走口令校验。
        // 这三个端点里 /bookSourceDebug 会让手机执行外部提供的书源规则脚本，
        // 不设防等于把远程代码执行面暴露给同网段所有人。
        // 拒绝时返回 null，NanoWSD 会让握手失败。
        if (!WebServiceGuard.isAuthorized(handshake)) {
            return null
        }
        return when (handshake.uri) {
            "/bookSourceDebug" -> {
                BookSourceDebugWebSocket(handshake)
            }
            "/rssSourceDebug" -> {
                RssSourceDebugWebSocket(handshake)
            }
            "/searchBook" -> {
                BookSearchWebSocket(handshake)
            }
            else -> null
        }
    }
}
