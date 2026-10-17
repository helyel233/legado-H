package io.legado.app.help

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.utils.toastOnUi

/**
 * Android 17 (API 37) 起「本地网络保护」强制生效：系统在 eBPF 层静默丢弃未授权应用的局域网流量，
 * 入站（Web 服务被其它设备访问）与出站（连接本地 AI、局域网书源、WebDAV）同样受限。
 * 漏掉权限时用户只会看到连接超时，因此访问局域网地址前必须先走这里。
 */
object LocalNetworkAccess {

    /**
     * Android 17 (API 37)。SDK 尚未提供对应的 Build.VERSION_CODES 常量，这里用字面量。
     */
    const val PERMISSION_SDK_INT = 37

    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= PERMISSION_SDK_INT

    /** 低于 API 37 的设备隐式授予，调用方无需再自行判断系统版本。 */
    fun isGranted(context: Context): Boolean =
        !isRequired() || ContextCompat.checkSelfPermission(
            context,
            Permissions.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 访问局域网前确保已授权；已授权或低版本系统直接执行 [onGranted]，否则申请权限。
     * 权限申请必须在前台界面发起（后台启动 Activity 有系统限制），因此由调用方界面传入上下文。
     */
    fun request(context: Context, onGranted: () -> Unit, onDenied: (() -> Unit)? = null) {
        if (isGranted(context)) {
            onGranted()
            return
        }
        PermissionsCompat.Builder()
            .addPermissions(Permissions.ACCESS_LOCAL_NETWORK)
            .rationale(R.string.local_network_permission_rationale)
            .onGranted { onGranted() }
            .onDenied {
                context.toastOnUi(R.string.local_network_permission_denied)
                onDenied?.invoke()
            }
            .request()
    }
}

internal fun String.isLocalNetworkHost(): Boolean {
    val host = lowercase()
    return host == "localhost" ||
        host.endsWith(".localhost") ||
        host == "::1" ||
        host.isPrivateIpv4() ||
        host.isUniqueLocalIpv6() ||
        host.isLinkLocalIpv6()
}

/** 10/8、172.16/12、192.168/16、127/8、169.254/16 与 0.0.0.0。 */
private fun String.isPrivateIpv4(): Boolean {
    val octets = split('.')
    if (octets.size != 4) return false
    val nums = octets.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return false }
    return when {
        nums[0] == 10 -> true
        nums[0] == 127 -> true
        nums[0] == 0 -> true
        nums[0] == 192 && nums[1] == 168 -> true
        nums[0] == 172 && nums[1] in 16..31 -> true
        nums[0] == 169 && nums[1] == 254 -> true
        else -> false
    }
}

/** fc00::/7 唯一本地地址。 */
private fun String.isUniqueLocalIpv6(): Boolean {
    if (':' !in this) return false
    val first = substringBefore(':')
    return first.length >= 2 && (first.startsWith("fc") || first.startsWith("fd"))
}

/** fe80::/10 链路本地地址。 */
private fun String.isLinkLocalIpv6(): Boolean {
    if (':' !in this) return false
    val first = substringBefore(':')
    return first.length >= 3 && first.startsWith("fe") && first[2] in "89ab"
}
