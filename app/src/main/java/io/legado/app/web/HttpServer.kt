package io.legado.app.web

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.BookSourceWebController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.service.WebService
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.runBlocking
import okio.Pipe
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")

    override fun serve(session: IHTTPSession): Response {
        WebService.serve()
        var returnData: ReturnData? = null
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = ct.contentTypeHeader
        var uri = session.uri

        val startAt = System.currentTimeMillis()
        LogUtils.d(TAG) {
            "${session.method.name} - $uri - ${sanitizeQuery(session.queryParameterString)} - Start($startAt)"
        }

        // 访问口令校验。口令未启用（默认）时这里直接放行，行为与旧版一致。
        // 必须放在静态资源之前：启用了口令就连页面都不该能打开。
        // 放在 try 外：守卫是纯字符串判断不应抛异常，而一旦真抛了也必须
        // fail-closed（拒绝请求），绝不能落进 catch 变成 200 放行。
        // OPTIONS 预检不执行业务、不泄露数据，放行以保住跨域场景的 CORS 协商，
        // 真正的数据请求仍会在下面被校验。
        if (session.method != Method.OPTIONS && !WebServiceGuard.isAuthorized(session)) {
            return unauthorizedResponse(session)
        }

        try {
            when (session.method) {
                Method.OPTIONS -> {
                    val response = newFixedLengthResponse("")
                    response.addHeader("Access-Control-Allow-Methods", "POST")
                    response.addHeader("Access-Control-Allow-Headers", "content-type")
                    response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
                    //response.addHeader("Access-Control-Max-Age", "3600");
                    return response
                }

                Method.POST -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val postData = files["postData"]

                    returnData = runBlocking {
                        when (uri) {
                            "/saveBookSource" -> BookSourceController.saveSource(postData)
                            "/saveBookSources" -> BookSourceController.saveSources(postData)
                            "/importBookSource" -> BookSourceWebController.importBookSource(postData)
                            "/importBookSources" -> BookSourceWebController.importBookSources(postData)
                            "/loginBookSource" -> BookSourceWebController.saveLoginData(postData)
                            "/saveBookSourceLogin" -> BookSourceWebController.saveLoginData(postData)
                            "/deleteBookSources" -> BookSourceController.deleteSources(postData)
                            "/saveBook" -> BookController.saveBook(postData)
                            "/deleteBook" -> BookController.deleteBook(postData)
                            "/saveBookProgress" -> BookController.saveBookProgress(postData)
                            "/addLocalBook" -> BookController.addLocalBook(session.parameters, files)
                            "/saveReadConfig" -> BookController.saveWebReadConfig(postData)
                            "/saveRssSource" -> RssSourceController.saveSource(postData)
                            "/saveRssSources" -> RssSourceController.saveSources(postData)
                            "/deleteRssSources" -> RssSourceController.deleteSources(postData)
                            "/saveReplaceRule" -> ReplaceRuleController.saveRule(postData)
                            "/deleteReplaceRule" -> ReplaceRuleController.delete(postData)
                            "/testReplaceRule" -> ReplaceRuleController.testRule(postData)
                            else -> null
                        }
                    }
                }

                Method.GET -> {
                    val parameters = session.parameters

                    returnData = when (uri) {
                        "/getBookSource" -> BookSourceController.getSource(parameters)
                        "/getBookSources" -> BookSourceController.sources
                        "/getBookSourceLoginInfo" -> BookSourceWebController.getLoginData(parameters)
                        "/getBookSourceLogin" -> BookSourceWebController.getLoginData(parameters)
                        "/getBookSourceDiscoverSources" -> BookSourceWebController.getDiscoverSources()
                        "/getBookSourceDiscover" -> BookSourceWebController.getDiscoverSources()
                        "/getBookSourceExploreKinds" -> BookSourceWebController.getDiscoverKinds(parameters)
                        "/getBookSourceExploreBooks" -> BookSourceWebController.getDiscoverBooks(parameters)
                        "/getBookSourceDiscoverBooks" -> BookSourceWebController.getDiscoverBooks(parameters)
                        "/getBookshelf" -> BookController.bookshelf
                        "/getChapterList" -> BookController.getChapterList(parameters)
                        "/refreshToc" -> BookController.refreshToc(parameters)
                        "/getBookContent" -> BookController.getBookContent(parameters)
                        "/cover" -> BookController.getCover(parameters)
                        "/image" -> BookController.getImg(parameters)
                        "/getReadConfig" -> BookController.getWebReadConfig()
                        "/getRssSource" -> RssSourceController.getSource(parameters)
                        "/getRssSources" -> RssSourceController.sources
                        "/getReplaceRules" -> ReplaceRuleController.allRules
                        else -> null
                    }
                }

                else -> Unit
            }

            if (returnData == null) {
                if (uri.endsWith("/"))
                    uri += "index.html"
                val assetResponse = assetsWeb.getResponse(uri)
                // 令牌是刚通过 URL 参数带进来的：种下 Cookie，之后同一浏览器不再需要
                // 每次带 ?token=（前端零改动，同源请求自动带 Cookie）
                if (WebServiceGuard.shouldSetCookie(session)) {
                    assetResponse.addHeader("Set-Cookie", WebServiceGuard.cookieHeader(AppConfig.webServiceToken.trim()))
                }
                return assetResponse
            }

            val response = if (returnData.data is Bitmap) {
                val outputStream = ByteArrayOutputStream()
                (returnData.data as Bitmap).compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val byteArray = outputStream.toByteArray()
                outputStream.close()
                val inputStream = ByteArrayInputStream(byteArray)
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    inputStream,
                    byteArray.size.toLong()
                )
            } else {
                val data = returnData.data
                if (data is List<*> && data.size > 3000) {
                    val pipe = Pipe(16 * 1024)
                    Coroutine.async(Coroutine.defaultScope) {
                        pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                            GSON.toJson(returnData, it)
                        }
                    }
                    newChunkedResponse(
                        Response.Status.OK,
                        "application/json",
                        pipe.source.buffer().inputStream()
                    )
                } else {
                    newFixedLengthResponse(GSON.toJson(returnData))
                }
            }
            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
            response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${sanitizeQuery(session.queryParameterString)} - End($startAt)"
            }
            return response
        } catch (e: Exception) {
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${sanitizeQuery(session.queryParameterString)} - Error End($startAt)\n$e\n${e.stackTraceStr}"
            }
            return newFixedLengthResponse(e.message)
        }

    }

    companion object {
        private const val TAG = "HttpServer"

        /**
         * 未授权响应。
         *
         * 浏览器直接打开地址时给 HTML 说明页：前端对 401 与网络失败走同一个错误拦截器，
         * 统一提示"后端连接失败"，用户会以为服务没开（实际是没带口令），无从下手。
         * 服务端在能展示 HTML 的场景直接把原因和解法写清楚，前端零改动。
         */
        private fun unauthorizedResponse(session: IHTTPSession): Response {
            val accept = session.headers.entries
                .firstOrNull { it.key.equals("accept", ignoreCase = true) }
                ?.value
                .orEmpty()
            val response = if (accept.contains("text/html", ignoreCase = true)) {
                newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "text/html; charset=utf-8",
                    """<html><body style="font-family:sans-serif;padding:24px">
                        <h3>需要访问口令</h3>
                        <p>Web 服务已启用访问口令，请使用服务通知里附带的完整地址（含 ?token=）访问，
                        或在当前地址后加上 <code>?token=你的口令</code>。</p>
                        </body></html>"""
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"msg":"unauthorized"}"""
                )
            }
            response.addHeader("WWW-Authenticate", "Bearer")
            return response
        }

        /**
         * 日志里的 query 做 token 脱敏：启用口令后 `?token=xxx` 会随日志落盘，
         * 明文记下来等于把口令写进日志文件。
         */
        private fun sanitizeQuery(query: String?): String {
            if (query.isNullOrBlank()) return query ?: ""
            return query.replace(
                Regex("(token=)[^&]*", RegexOption.IGNORE_CASE),
                "$1***"
            )
        }
    }

}
