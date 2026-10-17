package io.legado.app.help.http

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.LocalNetworkAccess
import io.legado.app.help.isLocalNetworkHost
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Android 17+ 未授予本地网络权限时，系统会在 eBPF 层静默丢弃发往局域网地址的包，
 * 书源、图源、本地 AI、WebDAV 这类请求只会莫名其妙地超时或连接被拒。
 * 这里在网络层兜底：识别这种情况，提示一次，并通知前台界面补申请权限。
 */
object LocalNetworkBlockedNotifier {

    /** 同一原因连续失败时不刷屏，60 秒内只提示一次。 */
    private const val REPEAT_INTERVAL_MS = 60_000L

    private val lastNotifyAt = AtomicLong(0L)

    /** 请求因缺少本地网络权限失败时的兜底处理，由网络层在抛出 IOException 前调用。 */
    fun onRequestFailed(host: String, error: IOException) {
        // 先做无开销的 host 判断，避免每个失败请求都去查一次权限（IPC）
        if (!host.isLocalNetworkHost()) return
        if (!shouldReport(error, LocalNetworkAccess.isGranted(appCtx))) return
        // 事件每次都发：宿主在前台就补授权（已授权时申请会被直接放行，无副作用）
        postEvent(EventBus.LOCAL_NETWORK_PERMISSION_REQUIRED, true)
        val now = System.currentTimeMillis()
        val last = lastNotifyAt.get()
        if (now - last < REPEAT_INTERVAL_MS || !lastNotifyAt.compareAndSet(last, now)) return
        appCtx.toastOnUi(R.string.local_network_permission_required)
    }

    /**
     * 是否像是被本地网络保护拦下的请求：目标在局域网内、没有权限，且失败是连接类错误。
     * 系统丢包与"服务本身没开"在网络层无法区分，所以提示文案不把话说死。
     */
    internal fun shouldReport(error: IOException, permissionGranted: Boolean): Boolean =
        !permissionGranted &&
            (error is SocketTimeoutException || error is ConnectException)
}
