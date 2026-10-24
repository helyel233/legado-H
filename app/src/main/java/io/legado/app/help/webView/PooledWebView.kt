package io.legado.app.help.webView

import android.content.Context
import android.content.MutableContextWrapper
import io.legado.app.ui.rss.read.VisibleWebView

class PooledWebView(
    val realWebView: VisibleWebView, // 真正的WebView实例
    val id: String, // 唯一标识
    val scope: WebViewPool.Scope = WebViewPool.Scope.GLOBAL
) {
    // 池对象被多线程共享：主线程（池管理/预渲染）写，WebView 网络线程
    // （shouldInterceptRequest）读，全部字段需 volatile 保证可见性，
    // 否则实例快速复用时可能按旧书身份应答资源请求（跨书串页）。
    @Volatile
    var isInUse: Boolean = false // 是否正在被使用
    @Volatile
    var lastUseTime: Long = 0 // 最后一次被使用的时间戳
    @Volatile
    var resetToken: Int = 0
    @Volatile
    var isDestroyed: Boolean = false

    /**
     * 预渲染页面的内容指纹；null 表示这个实例上没有可复用的成品页。
     * 由 [io.legado.app.ui.widget.dialog.CommentWebViewSession] 写入与消费。
     */
    @Volatile
    var prerenderIdentity: String? = null

    /** 预渲染页面的加载是否已经结束（只有结束后才能直接接管）。 */
    @Volatile
    var prerenderReady: Boolean = false

    /** 预渲染页面的就绪时间，用于判定成品页是否已经过期。 */
    @Volatile
    var prerenderTime: Long = 0

    fun upContext(context: Context): PooledWebView {
        (realWebView.context as MutableContextWrapper).let {
            if (it.baseContext != context) {
                it.baseContext = context
            }
        }
        return this
    }
}
