package io.legado.app.ui.glass

import android.content.Context
import android.content.SharedPreferences
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.utils.defaultSharedPreferences

/**
 * Compose 侧的玻璃面板背景。
 *
 * Compose 没有可挂 drawable 的背景宿主，这里用一个真实 View 承载 [GlassSurface] 的
 * drawable，并把它垫在 [content] 下方。材质、四档降级、设备判定、采样域与描边仍由
 * 底层管线单点决定——本类只声明采样域与圆角，**不携带任何观感参数**，避免出现
 * 「Compose 一套、View 一套」的第二份参数表。
 *
 * 可用性在组合期先判定（[glassSurfaceAvailable]，与入口早退分支同源）：可用才创建
 * 玻璃宿主；不可用（墨水屏 / 低内存等）直接铺 [fallback]，不产生额外的空 View。
 *
 * 使用约束：
 * - 只在壁纸域（[GlassSamplingDomain.WALLPAPER]）使用；面板域 / 内容域应传对应域，
 *   本组件会按域判定为不可用并走 [fallback]，不会采样被遮住的壁纸。
 * - [configSignature] 取 [rememberGlassConfigSignature] 的结果，一个页面算一次即可；
 *   签名变化会重建玻璃层，等价于发现页行的偏好重绑。
 */
@Composable
fun GlassPanel(
    domain: GlassSamplingDomain,
    cornerRadiusPx: Float,
    configSignature: String,
    modifier: Modifier = Modifier,
    fallback: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val available = remember(domain, configSignature) {
        GlassSurface.isAvailable(domain, context)
    }
    Box(modifier = modifier) {
        if (available) {
            AndroidView(
                factory = { GlassPanelHost(it) },
                update = { host -> host.bind(domain, cornerRadiusPx, configSignature) },
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(Modifier.matchParentSize().then(fallback))
        }
        content()
    }
}

/**
 * 玻璃配置签名（组合期可观察）。
 *
 * 玻璃档位 / 强度 / 倍率 / 描边色都不是 Compose 状态，设置页改完返回本页时页面可能
 * 一次都不重组；这里用偏好监听把签名提升为 State，变化即触发玻璃层重绑。
 * 每个页面调用一次，不要在每次 [GlassPanel] 里各注册一个监听。
 */
@Composable
fun rememberGlassConfigSignature(): String {
    val context = LocalContext.current
    var signature by remember(context) {
        mutableStateOf(GlassSurface.configSignature(context))
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val next = GlassSurface.configSignature(context)
            if (next != signature) {
                signature = next
            }
        }
        context.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            context.defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    return signature
}

/**
 * 玻璃背景宿主：只负责挂 drawable。
 *
 * 位置跟踪不在这里做：drawable 挂到宿主时 [io.legado.app.ui.main.explore.ExploreGlassBackdrop]
 * 已经把 `GlassPositionTracker` 绑到宿主的 attach / layout / OnPreDraw 回调上，
 * Compose 列表的平移（不触发子 View 布局回调）也由 OnPreDraw 覆盖，
 * 这里再写一遍检测只会多一份逐帧开销、且晚一帧生效。
 *
 * 不参与触摸与焦点，避免在 Compose 列表里抢走行的点击。
 */
private class GlassPanelHost(context: Context) : FrameLayout(context) {

    private var bindKey: String? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(domain: GlassSamplingDomain, cornerRadiusPx: Float, signature: String) {
        val key = "$signature|${domain.name}|$cornerRadiusPx"
        if (key == bindKey) return
        bindKey = key
        background = GlassSurface.panelBackground(this, domain, cornerRadiusPx)
    }
}
