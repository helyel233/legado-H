package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.help.LocalNetworkAccess
import io.legado.app.help.config.AppConfig
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.HttpServer
import io.legado.app.web.WebServiceGuard
import io.legado.app.web.WebSocketServer
import splitties.init.appCtx
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager
import java.io.IOException

class WebService : BaseService() {

    companion object {
        // 服务线程写、UI/网络回调线程读，需要保证可见性
        @Volatile
        var isRun = false
        @Volatile
        var hostAddress = ""

        fun start(context: Context) {
            context.startService<WebService>()
        }

        fun startForeground(context: Context) {
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            context.stopService<WebService>()
        }

        fun serve() {
            appCtx.startService<WebService> {
                action = "serve"
            }
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.webServiceWakeLock, false)
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:WebService")
            .apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:WebService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null

    /**
     * 未设访问口令时只绑回环地址（127.0.0.1），Web 服务仅本机可访问，
     * 防止同局域网设备在无鉴权状态下增删书源、删书、读取登录态。
     * 设置口令后才绑所有网卡放开局域网访问。改口令会走 stop+start，此值随服务重建而刷新。
     */
    private val loopbackOnly: Boolean = AppConfig.webServiceToken.isBlank()
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        upTile(true)
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            notificationList.clear()
            notificationList.addAll(addressEntries())
            hostAddress = decorateAddress(notificationList.first())
            startForegroundNotification()
            postEvent(EventBus.WEB_SERVICE, hostAddress)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> stopSelf()
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> if (useWakeLock) {
                wakeLock.acquire()
                wifiLock?.acquire()
            }

            else -> upWebServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        networkChangedListener.unRegister()
        isRun = false
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        postEvent(EventBus.WEB_SERVICE, "")
        upTile(false)
    }

    private fun upWebServer() {
        // 绑所有网卡（已设口令）时需要本地网络权限：Android 17 起没有该权限时，
        // 其它设备一律连不上。这里兜底提示并停止，避免开关显示已开启却连不上。
        // 未设口令走回环模式（仅本机访问），不依赖该权限。
        if (!loopbackOnly && !LocalNetworkAccess.isGranted(this)) {
            toastOnUi(R.string.local_network_permission_required)
            postEvent(EventBus.LOCAL_NETWORK_PERMISSION_REQUIRED, true)
            stopSelf()
            return
        }
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        val addressList = NetworkUtils.getLocalIPAddress()
        if (loopbackOnly || addressList.any()) {
            val port = getPort()
            httpServer = if (loopbackOnly) HttpServer(port, "127.0.0.1") else HttpServer(port)
            webSocketServer = if (loopbackOnly) {
                WebSocketServer(port + 1, "127.0.0.1")
            } else {
                WebSocketServer(port + 1)
            }
            try {
                httpServer?.start()
                webSocketServer?.start(1000 * 30) // 通信超时设置
                notificationList.clear()
                notificationList.addAll(addressEntries())
                hostAddress = decorateAddress(notificationList.first())
                isRun = true
                postEvent(EventBus.WEB_SERVICE, hostAddress)
                startForegroundNotification()
            } catch (e: IOException) {
                toastOnUi(e.localizedMessage ?: "")
                e.printOnDebug()
                stopSelf()
            }
        } else {
            toastOnUi("web service cant start, no ip address")
            stopSelf()
        }
    }

    /** 通知与复制地址用的条目：回环模式只展示 127.0.0.1，并附未设口令的提示。 */
    private fun addressEntries(): List<String> {
        return if (loopbackOnly) {
            listOf(
                getString(R.string.http_ip, "127.0.0.1", getPort()),
                getString(R.string.web_service_no_token_hint)
            )
        } else {
            NetworkUtils.getLocalIPAddress().map { address ->
                getString(
                    R.string.http_ip,
                    address.hostAddress,
                    getPort()
                )
            }
        }
    }

    private fun getPort(): Int {
        var port = getPrefInt(PreferKey.webPort, 1122)
        if (port !in 1024..65530) {
            port = 1122
        }
        return port
    }

    /**
     * 给对外展示/复制的地址附上访问口令。
     *
     * 启用了口令时，用户复制到的地址必须带 `?token=` 才能打开，否则会 401；
     * 未启用时原样返回。非地址文本（如"网络不可用"）也原样返回。
     */
    private fun decorateAddress(address: String): String {
        if (!address.startsWith("http")) return address
        return WebServiceGuard.authorizedUrl(address, AppConfig.webServiceToken)
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.web_service))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<WebService>("copyHostAddress")
            )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<WebService>(IntentAction.stop)
        )
        val notification = builder.build()
        startForeground(NotificationId.WebService, notification)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) {
                        IntentAction.start
                    } else {
                        IntentAction.stop
                    }
                }
            }

        }
    }
}
