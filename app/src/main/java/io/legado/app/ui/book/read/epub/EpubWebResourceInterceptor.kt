package io.legado.app.ui.book.read.epub

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import io.legado.app.constant.AppLog
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResource
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.LinkedHashSet

/**
 * Intercepts WebView resource requests for EPUB chapters, bundled reader assets,
 * template scripts, and custom reader fonts, with bounded failure logging.
 */
internal class EpubWebResourceInterceptor(
    private val context: Context
) {
    private val reportedResourceFailures = LinkedHashSet<String>()

    fun clearFailures() {
        synchronized(reportedResourceFailures) {
            reportedResourceFailures.clear()
        }
    }

    fun intercept(
        request: WebResourceRequest,
        boundSession: EpubDirectSession?,
        preparedConfig: EpubCoreLayoutConfig?
    ): WebResourceResponse? {
        return intercept(
            uri = request.url,
            method = request.method,
            requestHeaders = request.requestHeaders,
            boundSession = boundSession,
            preparedConfig = preparedConfig
        )
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    fun intercept(
        url: String?,
        boundSession: EpubDirectSession?,
        preparedConfig: EpubCoreLayoutConfig?
    ): WebResourceResponse? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return intercept(
            uri = uri,
            method = "GET",
            requestHeaders = emptyMap(),
            boundSession = boundSession,
            preparedConfig = preparedConfig
        )
    }

    private fun intercept(
        uri: Uri,
        method: String,
        requestHeaders: Map<String, String>,
        boundSession: EpubDirectSession?,
        preparedConfig: EpubCoreLayoutConfig?
    ): WebResourceResponse? {
        if (uri.host.equals(ReaderAssetReferences.HOST, true)) {
            if (method != "GET" && method != "HEAD") return methodNotAllowedResponse()
            return runCatching {
                ReaderAssets.resource(uri.toString(), method == "HEAD")
                    ?.toWebResponse(withBody = method != "HEAD", crossOrigin = true)
            }.getOrNull() ?: errorResponse(404, "Not Found")
        }
        if (uri.scheme.equals(EpubDirectSession.SCHEME, true) &&
            uri.host.equals(boundSession?.resourceHost, true)
        ) {
            val normalizedMethod = method.uppercase()
            if (normalizedMethod != "GET" && normalizedMethod != "HEAD") {
                return methodNotAllowedResponse()
            }
            if (preparedConfig?.readerTemplate != null && uri.path?.startsWith("/__reader_template__/") == true) {
                val asset = when (uri.path) {
                    "/__reader_template__/paged.js" -> "epub/vendor/paged.js"
                    "/__reader_template__/source-map.js" -> "epub/template-source-map.js"
                    "/__reader_template__/page-alignment.js" -> "epub/page-alignment.js"
                    "/__reader_template__/browser-flow.js" -> "epub/template-browser-flow.js"
                    "/__reader_template__/runtime.js" -> "epub/template-runtime.js"
                    else -> return errorResponse(404, "Not Found")
                }
                return WebResourceResponse(
                    "application/javascript",
                    "UTF-8",
                    200,
                    "OK",
                    mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                    if (normalizedMethod == "HEAD") ByteArrayInputStream(ByteArray(0)) else context.assets.open(asset)
                )
            }
            if (Uri.decode(uri.encodedPath.orEmpty()).endsWith("/__legado_reader_font__")) {
                if (normalizedMethod == "HEAD") {
                    return if (preparedConfig?.readerFontPath.isNullOrBlank() ||
                        preparedConfig?.readerFontLength == null
                    ) {
                        errorResponse(404, "Not Found")
                    } else {
                        emptyResponse(readerFontHeaders(preparedConfig))
                    }
                }
                return readerFontResponse(preparedConfig)
            }
            val rangeHeader = requestHeaders.entries
                .firstOrNull { it.key.equals("Range", true) }
                ?.value
            val result = runCatching {
                if (normalizedMethod == "HEAD") {
                    boundSession?.openHeadResource(uri.toString(), rangeHeader)
                } else {
                    boundSession?.openResource(uri.toString(), rangeHeader)
                }
            }
            result.exceptionOrNull()?.let { throwable ->
                reportFailure(uri, 500, throwable.localizedMessage ?: "resource read failed", throwable)
                return errorResponse(500, "EPUB Resource Error")
            }
            val resource = result.getOrNull() ?: run {
                reportFailure(uri, 404, "entry not found")
                return errorResponse(404, "Not Found")
            }
            return resource.toWebResponse(
                withBody = normalizedMethod != "HEAD",
                crossOrigin = preparedConfig?.readerTemplate != null
            )
        }
        return when (uri.scheme?.lowercase()) {
            "data", "blob", "about" -> null
            "http", "https" -> if (preparedConfig?.readerTemplate != null) null else errorResponse(403, "Forbidden")
            else -> errorResponse(403, "Forbidden")
        }
    }

    fun reportFailure(
        uri: Uri,
        statusCode: Int,
        reason: String,
        throwable: Throwable? = null
    ) {
        val path = Uri.decode(uri.encodedPath.orEmpty()).ifBlank { "/" }
        val key = "$statusCode|${uri.host}|$path"
        val shouldReport = synchronized(reportedResourceFailures) {
            if (key in reportedResourceFailures || reportedResourceFailures.size >= MAX_RESOURCE_FAILURE_REPORTS) {
                false
            } else {
                reportedResourceFailures.add(key)
            }
        }
        if (!shouldReport) return
        val message = "EPUB direct resource failed: status=$statusCode, host=${uri.host}, path=$path, reason=$reason"
        if (throwable == null) AppLog.putDebug(message) else AppLog.putDebug(message, throwable)
    }

    private fun readerFontResponse(preparedConfig: EpubCoreLayoutConfig?): WebResourceResponse {
        val path = preparedConfig?.readerFontPath ?: return errorResponse(404, "Not Found")
        val expectedLength = preparedConfig.readerFontLength ?: return errorResponse(404, "Not Found")
        val file = File(path)
        if (!file.isFile || file.length() != expectedLength) return errorResponse(404, "Not Found")
        val stream = runCatching { FileInputStream(file) }.getOrNull()
            ?: return errorResponse(404, "Not Found")
        return WebResourceResponse(
            preparedConfig.readerFontMimeType ?: "font/ttf",
            null,
            200,
            "OK",
            readerFontHeaders(preparedConfig),
            stream
        )
    }

    private fun readerFontHeaders(config: EpubCoreLayoutConfig): Map<String, String> {
        return mapOf(
            "Cache-Control" to "public, max-age=31536000, immutable",
            "Content-Length" to (config.readerFontLength ?: 0L).toString(),
            "Access-Control-Allow-Origin" to "*",
            "X-Content-Type-Options" to "nosniff"
        )
    }

    private fun EpubDirectResource.toWebResponse(
        withBody: Boolean,
        crossOrigin: Boolean = false
    ): WebResourceResponse {
        val responseStream = if (withBody) stream else {
            stream.close()
            ByteArrayInputStream(ByteArray(0))
        }
        return WebResourceResponse(
            mimeType,
            encoding,
            statusCode,
            reasonPhrase,
            if (crossOrigin) headers + ("Access-Control-Allow-Origin" to "*") else headers,
            responseStream
        )
    }

    private fun methodNotAllowedResponse() = WebResourceResponse(
        "text/plain",
        Charsets.UTF_8.name(),
        405,
        "Method Not Allowed",
        nonCacheableResourceHeaders() + mapOf("Allow" to "GET, HEAD"),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun emptyResponse(headers: Map<String, String> = emptyMap()) = WebResourceResponse(
        "text/plain",
        Charsets.UTF_8.name(),
        200,
        "OK",
        headers,
        ByteArrayInputStream(ByteArray(0))
    )

    private fun errorResponse(statusCode: Int, reasonPhrase: String) = WebResourceResponse(
        "text/plain",
        Charsets.UTF_8.name(),
        statusCode,
        reasonPhrase,
        nonCacheableResourceHeaders(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun nonCacheableResourceHeaders(): Map<String, String> {
        return mapOf(
            "Cache-Control" to "no-store, max-age=0",
            "Pragma" to "no-cache",
            "Access-Control-Allow-Origin" to "*"
        )
    }

    private companion object {
        const val MAX_RESOURCE_FAILURE_REPORTS = 32
    }
}
