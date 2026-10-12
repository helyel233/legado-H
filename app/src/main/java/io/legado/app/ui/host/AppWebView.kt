package io.legado.app.ui.host

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * V5 WebView 容器（台账 #30-P0④）。
 *
 * AndroidView 包 android.webkit.WebView；旧栈的 RSS 正文阅读、源登录、
 * 应用内浏览器后续迁移时统一走这里，不再各页手搓 WebViewClient。
 *
 * @param state 持有 WebView 实例引用与加载方法；页面离开组合时自动销毁
 * @param onProgress 加载进度 0-100
 * @param onTitleChange 页面标题变化
 * @param onUrlChange 当前 URL 变化（含重定向）
 * @param onError 主文档加载失败
 * @param factory 自定义 WebView（如需 JS 注入/UA 伪装的页面传入）
 */
class AppWebViewState(
    val initialUrl: String?,
    val headers: Map<String, String> = emptyMap(),
) {
    internal var webView: WebView? = null

    fun loadUrl(url: String) {
        webView?.loadUrl(url, headers)
    }

    fun reload() {
        webView?.reload()
    }

    fun goBack() {
        webView?.goBack()
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true
}

@Composable
fun rememberAppWebViewState(
    initialUrl: String? = null,
    headers: Map<String, String> = emptyMap(),
): AppWebViewState = remember(initialUrl, headers) { AppWebViewState(initialUrl, headers) }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppWebView(
    state: AppWebViewState,
    modifier: Modifier = Modifier,
    onProgress: ((Int) -> Unit)? = null,
    onTitleChange: ((String) -> Unit)? = null,
    onUrlChange: ((String) -> Unit)? = null,
    onError: (() -> Unit)? = null,
    factory: ((Context) -> WebView)? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val webView = factory?.invoke(context) ?: WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean,
                ) {
                    onUrlChange?.invoke(url.orEmpty())
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        onError?.invoke()
                    }
                }
            }
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onProgress?.invoke(newProgress)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    onTitleChange?.invoke(title.orEmpty())
                }
            }
            state.webView = webView
            state.initialUrl?.let { webView.loadUrl(it, state.headers) }
            webView
        },
        onRelease = { webView ->
            state.webView = null
            webView.stopLoading()
            webView.destroy()
        },
    )
}
