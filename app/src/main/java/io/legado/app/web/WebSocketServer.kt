package io.legado.app.web

import fi.iki.elonen.NanoWSD
import io.legado.app.service.WebService
import io.legado.app.web.socket.*

class WebSocketServer(port: Int) : NanoWSD(port) {

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
