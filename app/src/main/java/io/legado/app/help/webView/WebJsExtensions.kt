package io.legado.app.help.webView

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Token-gated WebView JavaScript bridge.
 *
 * Uses composition over [RssJsExtensions] instead of inheritance so no unauthenticated
 * `@JavascriptInterface` methods from superclasses are exposed on `window[nameJava]`.
 * Every `@JavascriptInterface` entry point requires the closure-private [bridgeToken].
 */
@Suppress("unused")
class WebJsExtensions(
    source: BaseSource,
    activity: AppCompatActivity?,
    webView: WebView,
    bookType: Int = 0,
    callback: Callback? = null,
    private val requestLifecycle: WebViewRequestLifecycle? = null,
    val bridgeToken: String = newBridgeToken()
) {
    private val delegate = RssJsExtensions(activity, source, bookType)
    private val callbackRef: WeakReference<Callback> = WeakReference(callback)
    private val webViewRef: WeakReference<WebView?> = WeakReference(webView)
    private val bridgeTokenBytes: ByteArray = bridgeToken.toByteArray(Charsets.UTF_8)

    interface Callback {
        fun upConfig(config: String)
    }

    private fun isValidToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), bridgeTokenBytes)
    }

    private fun requireValidToken(token: String?) {
        if (!isValidToken(token)) {
            throw SecurityException("Unauthorized JS bridge invocation")
        }
    }

    fun buildInjectionScript(): String = Companion.buildInjectionScript(bridgeToken)

    fun buildInjectionScript2(): String = Companion.buildInjectionScript2(bridgeToken)

    fun buildInjectionString(): String = Companion.buildInjectionString(bridgeToken)

    @JavascriptInterface
    fun upConfig(token: String?, config: String) {
        if (!isValidToken(token)) return
        val page = requestLifecycle?.currentPage()
        if (requestLifecycle != null && page == null) return
        if (page == null) {
            callbackRef.get()?.upConfig(config)
            return
        }
        page.scope.launch(Dispatchers.Main) {
            if (page.isCurrent) callbackRef.get()?.upConfig(config)
        }
    }

    /**
     * 由软件主动注入的js函数调用
     */
    @JavascriptInterface
    fun request(token: String?, funName: String, jsParam: Array<String?>, id: String) {
        if (!isValidToken(token)) return
        val page = requestLifecycle?.currentPage()
        if (requestLifecycle != null && page == null) return
        val requestScope = page?.scope ?: delegate.activityRef.get()?.lifecycleScope ?: return
        requestScope.launch(Dispatchers.Main) {
            if (page != null && !page.isCurrent) return@launch
            try {
                val data = withContext(Dispatchers.IO) {
                    val params = Array(6) { i -> jsParam.getOrNull(i) }
                    val p0 = params[0]
                    val p1 = params[1]
                    val p2 = params[2]
                    val p3 = params[3]
                    val p4 = params[4]
                    val p5 = params[5]
                    when (funName) {
                        "run" -> {
                            delegate.analyzeRule.setCoroutineContext(coroutineContext)
                                .evalJS(
                                    p0 ?: throw NoStackTraceException("error null")
                                ).toString()
                        }
                        "ajaxAwait" -> {
                            delegate.ajax(
                                p0 ?: throw NoStackTraceException("error url null"),
                                p1?.toLongOrNull()
                            ).toString()
                        }
                        "connectAwait" -> {
                            connectInternal(
                                p0 ?: throw NoStackTraceException("error url null"),
                                p1,
                                p2?.toIntOrNull()
                            )
                        }
                        "getAwait" -> {
                            getInternal(
                                p0 ?: throw NoStackTraceException("error url null"),
                                p1 ?: throw NoStackTraceException("error header null"),
                                p2?.toIntOrNull()
                            )
                        }
                        "headAwait" -> {
                            headInternal(
                                p0 ?: throw NoStackTraceException("error url null"),
                                p1 ?: throw NoStackTraceException("error header null"),
                                p2?.toIntOrNull()
                            )
                        }
                        "postAwait" -> {
                            postInternal(
                                p0 ?: throw NoStackTraceException("error url null"),
                                p1 ?: throw NoStackTraceException("error body null"),
                                p2 ?: throw NoStackTraceException("error header null"),
                                p3?.toIntOrNull()
                            )
                        }
                        "webViewAwait" -> {
                            delegate.webView(
                                p0,
                                p1,
                                p2,
                                p3.toBoolean()
                            ).toString()
                        }
                        "webViewGetSourceAwait" -> {
                            delegate.webViewGetSource(
                                p0,
                                p1,
                                p2,
                                p3 ?: throw NoStackTraceException("error sourceRegex null"),
                                p4.toBoolean(),
                                p5?.toLongOrNull() ?: 0
                            ).toString()
                        }
                        "decryptStrAwait" -> {
                            delegate.createSymmetricCrypto(
                                p0 ?: throw NoStackTraceException("error transformation null"),
                                p1 ?: throw NoStackTraceException("error key null"),
                                p2
                            ).decryptStr(p3 ?: throw NoStackTraceException("error data null"))
                        }
                        "encryptBase64Await" -> {
                            delegate.createSymmetricCrypto(
                                p0 ?: throw NoStackTraceException("error transformation null"),
                                p1 ?: throw NoStackTraceException("error key null"),
                                p2
                            ).encryptBase64(p3 ?: throw NoStackTraceException("error data null"))
                        }
                        "encryptHexAwait" -> {
                            delegate.createSymmetricCrypto(
                                p0 ?: throw NoStackTraceException("error transformation null"),
                                p1 ?: throw NoStackTraceException("error key null"),
                                p2
                            ).encryptHex(p3 ?: throw NoStackTraceException("error data null"))
                        }
                        "createSignHexAwait" -> {
                            delegate.createSign(p0 ?: throw NoStackTraceException("error algorithm null"))
                                .setPublicKey(p1 ?: throw NoStackTraceException("error publicKey null"))
                                .setPrivateKey(p2 ?: throw NoStackTraceException("error privateKey null"))
                                .signHex(p3 ?: throw NoStackTraceException("error data null"))
                        }
                        "downloadFileAwait" -> {
                            delegate.downloadFile(p0 ?: throw NoStackTraceException("error url null"))
                        }
                        "readTxtFileAwait" -> {
                            delegate.readTxtFile(p0 ?: throw NoStackTraceException("error path null"))
                        }
                        "importScriptAwait" -> {
                            delegate.importScript(p0 ?: throw NoStackTraceException("error path null"))
                        }
                        "getStringAwait" -> {
                            delegate.analyzeRule.setCoroutineContext(coroutineContext)
                                .getString(p0, p1)
                        }
                        else -> throw NoStackTraceException("error funName")
                    }
                }
                if (page != null && !page.isCurrent) return@launch
                CacheManager.putMemory(id, data)
                webViewRef.get()?.evaluateJavascript("window.$JSBridgeResult(${GSON.toJson(id)}, true);", null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (page != null && !page.isCurrent) return@launch
                CacheManager.putMemory(id, error.localizedMessage ?: "err")
                webViewRef.get()?.evaluateJavascript("window.$JSBridgeResult(${GSON.toJson(id)}, false);", null)
            }
        }
    }

    private fun connectInternal(urlStr: String, header: String? = null, callTimeout: Int? = 9000): String {
        return delegate.connect(urlStr, header, callTimeout?.toLong()).toString()
    }

    private fun getInternal(urlStr: String, header: String, timeout: Int? = 9000): String {
        val headers = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return delegate.get(urlStr, headers, timeout).body()
    }

    private fun postInternal(urlStr: String, body: String, header: String, timeout: Int? = 9000): String {
        val headers = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return delegate.post(urlStr, body, headers, timeout).body()
    }

    private fun headInternal(urlStr: String, header: String, timeout: Int? = 9000): String {
        val headers = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return GSON.toJson(delegate.head(urlStr, headers, timeout).headers())
    }

    @JavascriptInterface
    fun toast(token: String?, msg: String?) {
        if (!isValidToken(token)) return
        delegate.toast(msg)
    }

    @JavascriptInterface
    fun longToast(token: String?, msg: String?) {
        if (!isValidToken(token)) return
        delegate.longToast(msg)
    }

    @JavascriptInterface
    fun log(token: String?, msg: String?): String {
        requireValidToken(token)
        return delegate.log(msg).toString()
    }

    @JavascriptInterface
    @JvmOverloads
    fun ajax(token: String?, url: String, callTimeout: Int = 9000): String? {
        requireValidToken(token)
        return delegate.ajax(url, callTimeout.toLong())
    }

    @JavascriptInterface
    @JvmOverloads
    fun connect(token: String?, urlStr: String, header: String? = null, callTimeout: Int? = 9000): String {
        requireValidToken(token)
        return connectInternal(urlStr, header, callTimeout)
    }

    @JavascriptInterface
    fun get(token: String?, key: String): String {
        requireValidToken(token)
        return delegate.get(key)
    }

    @JavascriptInterface
    @JvmOverloads
    fun get(token: String?, urlStr: String, header: String, timeout: Int? = 9000): String {
        requireValidToken(token)
        return getInternal(urlStr, header, timeout)
    }

    @JavascriptInterface
    fun put(token: String?, key: String, value: String): String {
        requireValidToken(token)
        return delegate.put(key, value)
    }

    @JavascriptInterface
    @JvmOverloads
    fun post(token: String?, urlStr: String, body: String, header: String, timeout: Int? = 9000): String {
        requireValidToken(token)
        return postInternal(urlStr, body, header, timeout)
    }

    @JavascriptInterface
    @JvmOverloads
    fun head(token: String?, urlStr: String, header: String, timeout: Int? = 9000): String {
        requireValidToken(token)
        return headInternal(urlStr, header, timeout)
    }

    @JavascriptInterface
    fun getStringList(token: String?, rule: String?, mContent: String? = null, isUrl: Boolean = false): List<String>? {
        requireValidToken(token)
        return delegate.getStringList(rule, mContent, isUrl)
    }

    @JavascriptInterface
    fun getString(token: String?, ruleStr: String?): String {
        requireValidToken(token)
        return delegate.getString(ruleStr, null, false)
    }

    @JavascriptInterface
    fun getString(token: String?, ruleStr: String?, unescape: Boolean): String {
        requireValidToken(token)
        return delegate.getString(ruleStr, unescape)
    }

    @JavascriptInterface
    fun getString(token: String?, ruleStr: String?, mContent: String?, isUrl: Boolean): String {
        requireValidToken(token)
        return delegate.getString(ruleStr, mContent, isUrl)
    }

    @JavascriptInterface
    @JvmOverloads
    fun searchBook(token: String?, key: String, searchScope: String? = null) {
        if (!isValidToken(token)) return
        delegate.searchBook(key, searchScope)
    }

    @JavascriptInterface
    fun addBook(token: String?, bookUrl: String) {
        if (!isValidToken(token)) return
        delegate.addBook(bookUrl)
    }

    @JavascriptInterface
    fun showPhoto(token: String?, src: String) {
        if (!isValidToken(token)) return
        delegate.showPhoto(src)
    }

    @JavascriptInterface
    @JvmOverloads
    fun open(token: String?, name: String, url: String? = null, title: String? = null, origin: String? = null) {
        if (!isValidToken(token)) return
        delegate.open(name, url, title, origin)
    }

    @JavascriptInterface
    @JvmOverloads
    fun setContent(token: String?, content: Any?, baseUrl: String? = null): AnalyzeRule {
        requireValidToken(token)
        return delegate.setContent(content, baseUrl)
    }

    @JavascriptInterface
    fun setBaseUrl(token: String?, baseUrl: String?): AnalyzeRule {
        requireValidToken(token)
        return delegate.setBaseUrl(baseUrl)
    }

    @JavascriptInterface
    fun setRedirectUrl(token: String?, url: String): URL? {
        requireValidToken(token)
        return delegate.setRedirectUrl(url)
    }

    @JavascriptInterface
    fun httpCapture(token: String?, configJson: String): String {
        requireValidToken(token)
        return delegate.httpCapture(configJson)
    }

    @JavascriptInterface
    @JvmOverloads
    fun openVideoPlayer(token: String?, url: String, title: String, isFloat: Boolean = false) {
        if (!isValidToken(token)) return
        delegate.openVideoPlayer(url, title, isFloat)
    }

    @JavascriptInterface
    fun importScript(token: String?, path: String): String {
        requireValidToken(token)
        return delegate.importScript(path)
    }

    @JavascriptInterface
    @JvmOverloads
    fun cacheFile(token: String?, urlStr: String, saveTime: Int = 0): String {
        requireValidToken(token)
        return delegate.cacheFile(urlStr, saveTime)
    }

    @JavascriptInterface
    @JvmOverloads
    fun getCookie(token: String?, tag: String, key: String? = null): String {
        requireValidToken(token)
        return delegate.getCookie(tag, key)
    }

    @JavascriptInterface
    fun downloadFile(token: String?, url: String): String {
        requireValidToken(token)
        return delegate.downloadFile(url)
    }

    @JavascriptInterface
    fun downloadFile(token: String?, content: String, url: String): String {
        requireValidToken(token)
        return delegate.downloadFile(content, url)
    }

    @JavascriptInterface
    fun readTxtFile(token: String?, path: String): String {
        requireValidToken(token)
        return delegate.readTxtFile(path)
    }

    @JavascriptInterface
    fun readTxtFile(token: String?, path: String, charsetName: String): String {
        requireValidToken(token)
        return delegate.readTxtFile(path, charsetName)
    }

    @JavascriptInterface
    fun deleteFile(token: String?, path: String): Boolean {
        requireValidToken(token)
        return delegate.deleteFile(path)
    }

    @JavascriptInterface
    fun base64Decode(token: String?, str: String?): String {
        requireValidToken(token)
        return delegate.base64Decode(str)
    }

    @JavascriptInterface
    fun base64Decode(token: String?, str: String?, charset: String): String {
        requireValidToken(token)
        return delegate.base64Decode(str, charset)
    }

    @JavascriptInterface
    @JvmOverloads
    fun base64Encode(token: String?, str: String, flags: Int = 2): String? {
        requireValidToken(token)
        return delegate.base64Encode(str, flags)
    }

    @JavascriptInterface
    fun hexDecodeToString(token: String?, hex: String): String? {
        requireValidToken(token)
        return delegate.hexDecodeToString(hex)
    }

    @JavascriptInterface
    fun hexEncodeToString(token: String?, utf8: String): String? {
        requireValidToken(token)
        return delegate.hexEncodeToString(utf8)
    }

    @JavascriptInterface
    fun timeFormatUTC(token: String?, time: Long, format: String, sh: Int): String? {
        requireValidToken(token)
        return delegate.timeFormatUTC(time, format, sh)
    }

    @JavascriptInterface
    fun timeFormat(token: String?, time: Long): String {
        requireValidToken(token)
        return delegate.timeFormat(time)
    }

    @JavascriptInterface
    @JvmOverloads
    fun encodeURI(token: String?, str: String, enc: String = "utf-8"): String {
        requireValidToken(token)
        return delegate.encodeURI(str, enc)
    }

    @JavascriptInterface
    fun htmlFormat(token: String?, str: String): String {
        requireValidToken(token)
        return delegate.htmlFormat(str)
    }

    @JavascriptInterface
    fun t2s(token: String?, text: String): String {
        requireValidToken(token)
        return delegate.t2s(text)
    }

    @JavascriptInterface
    fun s2t(token: String?, text: String): String {
        requireValidToken(token)
        return delegate.s2t(text)
    }

    @JavascriptInterface
    fun getWebViewUA(token: String?): String {
        requireValidToken(token)
        return delegate.getWebViewUA()
    }

    @JavascriptInterface
    @JvmOverloads
    fun getFontList(token: String?, withAtFontPrefix: Boolean = false): String {
        requireValidToken(token)
        return delegate.getFontList(withAtFontPrefix)
    }

    @JavascriptInterface
    fun unzipFile(token: String?, zipPath: String): String {
        requireValidToken(token)
        return delegate.unzipFile(zipPath)
    }

    @JavascriptInterface
    fun un7zFile(token: String?, zipPath: String): String {
        requireValidToken(token)
        return delegate.un7zFile(zipPath)
    }

    @JavascriptInterface
    fun unrarFile(token: String?, zipPath: String): String {
        requireValidToken(token)
        return delegate.unrarFile(zipPath)
    }

    @JavascriptInterface
    fun unArchiveFile(token: String?, zipPath: String): String {
        requireValidToken(token)
        return delegate.unArchiveFile(zipPath)
    }

    @JavascriptInterface
    fun getTxtInFolder(token: String?, path: String): String {
        requireValidToken(token)
        return delegate.getTxtInFolder(path)
    }

    @JavascriptInterface
    @JvmOverloads
    fun getZipStringContent(token: String?, url: String, path: String, charsetName: String = "UTF-8"): String {
        requireValidToken(token)
        return delegate.getZipStringContent(url, path, charsetName)
    }

    @JavascriptInterface
    @JvmOverloads
    fun getRarStringContent(token: String?, url: String, path: String, charsetName: String = "UTF-8"): String {
        requireValidToken(token)
        return delegate.getRarStringContent(url, path, charsetName)
    }

    @JavascriptInterface
    @JvmOverloads
    fun get7zStringContent(token: String?, url: String, path: String, charsetName: String = "UTF-8"): String {
        requireValidToken(token)
        return delegate.get7zStringContent(url, path, charsetName)
    }

    @JavascriptInterface
    fun toNumChapter(token: String?, s: String?): String? {
        requireValidToken(token)
        return delegate.toNumChapter(s)
    }

    @JavascriptInterface
    fun randomUUID(token: String?): String {
        requireValidToken(token)
        return delegate.randomUUID()
    }

    @JavascriptInterface
    fun androidId(token: String?): String {
        requireValidToken(token)
        return delegate.androidId()
    }

    @JavascriptInterface
    fun openUrl(token: String?, url: String) {
        if (!isValidToken(token)) return
        delegate.openUrl(url)
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun newBridgeToken(): String {
            val bytes = ByteArray(24)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun getRandomLetter(): Char {
            val letters = "abcdefghijklmnopqrstuvwxyz"
            return letters.random()
        }

        val uuid by lazy {
            UUID.randomUUID().toString().replace('-', getRandomLetter()).chunked(6)
        }
        val uuid2 by lazy {
            UUID.randomUUID().toString().replace('-', getRandomLetter()).chunked(6)
        }
        val nameUrl by lazy { "https://" + uuid[0] + ".com/" + uuid2[0] + ".js" }
        val nameJava by lazy { getRandomLetter() + uuid[1] + uuid2[1] }
        val nameCache by lazy { getRandomLetter() + uuid[2] + uuid2[2] }
        val nameSource by lazy { getRandomLetter() + uuid[3] + uuid2[3] }
        val nameBasic by lazy { getRandomLetter() + uuid[4] + uuid2[4] }
        val JSBridgeResult by lazy { getRandomLetter() + uuid[5] + uuid2[5] }
        val JS_URL by lazy {
            "<script src=\"$nameUrl\"></script>"
        }

        fun buildInjectionString(token: String): String {
            val quotedToken = GSON.toJson(token)
            return "try{if(!window.__legadoBridgeInit){" +
                "var __t=$quotedToken;" +
                "var __rj=window.$nameJava;delete window.$nameJava;" +
                "var cache=window.$nameCache;delete window.$nameCache;" +
                "var source=window.$nameSource;delete window.$nameSource;" +
                "var java=__rj?new Proxy({},{get:function(_,p){" +
                "return typeof __rj[p]==='function'?function(){var a=[__t];for(var i=0;i<arguments.length;i++)a.push(arguments[i]);return __rj[p].apply(__rj,a);}:__rj[p];" +
                "}}):null;" +
                "window.__legadoBridgeInit=true;" +
                "}}catch(e){}"
        }

        fun buildInjectionScript(token: String): String {
            val quotedToken = GSON.toJson(token)
            return """
            const requestId = n => 'req_' + n + '_' + Date.now() + '_' + Math.random().toString(36).slice(-3);
            const params = a => a.map(p => p != null && typeof p.toString === 'function' ? p.toString() : null);
            const JSBridgeCallbacks = {};
            const __bridgeToken = $quotedToken;
            const __rawJava = window.$nameJava;
            delete window.$nameJava;
            const java = __rawJava ? new Proxy({}, {
                get(_, prop) {
                    if (typeof __rawJava[prop] === 'function') {
                        return (...args) => __rawJava[prop](__bridgeToken, ...args);
                    }
                    return __rawJava[prop];
                }
            }) : null;
            const source = window.$nameSource;
            delete window.$nameSource;
            const cache = window.$nameCache;
            delete window.$nameCache;
            function run(jsCode) {
                return new Promise((resolve, reject) => {
                    const id = requestId("run");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "run", [String(jsCode)], id);
                });
            };
            function ajaxAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("ajaxAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "ajaxAwait", params(args), id);
                });
            };
            function connectAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("connectAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "connectAwait", params(args), id);
                });
            };
            function getAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("getAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "getAwait", params(args), id);
                });
            };
            function headAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("headAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "headAwait", params(args), id);
                });
            };
            function postAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("postAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "postAwait", params(args), id);
                });
            };
            function webViewAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("webViewAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "webViewAwait", params(args), id);
                });
            };
            function webViewGetSourceAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("webViewGetSourceAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "webViewGetSourceAwait", params(args), id);
                });
            }
            function decryptStrAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("decryptStrAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "decryptStrAwait", params(args), id);
                });
            };
            function encryptBase64Await(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("encryptBase64Await");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "encryptBase64Await", params(args), id);
                });
            };
            function encryptHexAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("encryptHexAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "encryptHexAwait", params(args), id);
                });
            };
            function createSignHexAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("createSignHexAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "createSignHexAwait", params(args), id);
                });
            };
            function downloadFileAwait(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId("downloadFileAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "downloadFileAwait", [String(url)], id);
                });
            };
            function readTxtFileAwait(path) {
                return new Promise((resolve, reject) => {
                    const id = requestId("readTxtFileAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "readTxtFileAwait", [String(path)], id);
                });
            };
            function importScriptAwait(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId("importScriptAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "importScriptAwait", [String(url)], id);
                });
            };
            function getStringAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("getStringAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "getStringAwait", params(args), id);
                });
            };
            window.$JSBridgeResult = function(id, success) {
                const callBack = JSBridgeCallbacks[id];
                if (callBack) {
                    const result = cache.getFromMemory(id);
                    if (success) {
                        callBack.resolve(result);
                    } else {
                        callBack.reject(result);
                    }
                    delete JSBridgeCallbacks[id];
                }
            };""".trimIndent()
        }

        fun buildInjectionScript2(token: String): String {
            val quotedToken = GSON.toJson(token)
            return """
            (function() {
            const requestId = n => 'req_' + n + '_' + Date.now() + '_' + Math.random().toString(36).slice(-3);
            const JSBridgeCallbacks = {};
            const __bridgeToken = $quotedToken;
            const __rawJava = window.$nameJava;
            delete window.$nameJava;
            const cache = window.$nameCache;
            delete window.$nameCache;
            window.run = function(jsCode) {
                return new Promise((resolve, reject) => {
                    const id = requestId("run");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    __rawJava.request(__bridgeToken, "run", [String(jsCode)], id);
                });
            };
            window.$JSBridgeResult = function(id, success) {
                const callBack = JSBridgeCallbacks[id];
                if (callBack) {
                    const result = cache.getFromMemory(id);
                    if (success) {
                        callBack.resolve(result);
                    } else {
                        callBack.reject(result);
                    }
                    delete JSBridgeCallbacks[id];
                }
            };
            })();""".trimIndent()
        }

        val basicJs by lazy { """
            (function() {
            if (screen.orientation) {
                screen.orientation.lock = function(orientation) {
                    return new Promise((resolve, reject) => {
                        window.$nameBasic.lockOrientation(orientation);
                        resolve()
                    });
                };
                screen.orientation.unlock = function() {
                    return new Promise((resolve, reject) => {
                        window.$nameBasic.lockOrientation('unlock');
                        resolve()
                    });
                };
            };
            window.close = function() {
                window.$nameBasic.onCloseRequested();
            };
            })();""".trimIndent()
        }
    }
}
