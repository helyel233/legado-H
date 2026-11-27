package io.legado.app.utils

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.legado.app.help.config.AppConfig

/**
 * 配置 WebView 的离线资源加载隔离。
 *
 * 通过 [WebSettings.blockNetworkLoads] 控制是否拦截所有外部网络请求；
 * 离线快照按纯文降级展示，仅保留内联 `data:` 内容，不启用
 * [WebSettings.blockNetworkImage] 以保持内联图片与在线模式行为一致。
 */
fun WebSettings.configureOfflineResourceLoading(offline: Boolean) {
    blockNetworkLoads = offline
    blockNetworkImage = false
}

/**
 * 设置是否夜间模式
 */
@SuppressLint("RequiresFeature")
fun WebSettings.setDarkeningAllowed(allow: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        kotlin.runCatching {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, allow)
            return
        }.onFailure {
            it.printOnDebug()
        }
    }
    if (allow) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDarkStrategy(
                this,
                WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
            )
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                this,
                WebSettingsCompat.FORCE_DARK_ON
            )
        }
    } else {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                this,
                WebSettingsCompat.FORCE_DARK_OFF
            )
        }
    }
}