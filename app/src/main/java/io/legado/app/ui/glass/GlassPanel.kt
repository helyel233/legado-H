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
 * 「壁纸此刻是否可用」是一个**可观察**的 State（见 [rememberGlassAvailability]），
 * 并进 [glassPanelBindKey]：背景图开关变化不改玻璃观感签名，只靠签名去重会让卡片停在
 * 旧材质上。
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
    /**
     * 采样是否按屏幕对齐。独立窗口（对话框）必须置 true：窗口根视图只有面板大小，
     * 按根视图换算会把整张壁纸缩进面板（见 [GlassSurface.panelBackground]）。
     */
    screenAligned: Boolean = false,
    /**
     * 是否要求宿主页面真的露出壁纸（[GlassSurface.pageGlassAllowed]）。
     *
     * 页面内卡片应置 true：可用性只有设备/档位口径，无法判断「这张卡背后到底有没有
     * 壁纸」；不要求时，自铺不透明背景的页面（阅读页等）也会建出采样隐藏壁纸的宿主。
     * 底栏 / 顶栏等由页面显式声明壁纸域的位置保持 false 即可（它们本来就只在这些页面出现）。
     */
    requireWallpaperHost: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    // 壁纸可用性**必须每次组合重新确认**，不能包在 remember 里只认 (domain, signature,
    // requireWallpaperHost) 三个 key：它读的是「背景图开关 + 宿主页面是否露壁纸」，既不进
    // [configSignature]（只含档位 / 强度 / 描边 / 夜间），也不改变上面两个 key。少这一次
    // 重算就会出现「开背景图 + 玻璃 → 进设置关背景图 → 返回，页根背景已变实底、卡片却
    // 停在玻璃」，反向开背景图则玻璃不出现。
    //
    // 这里不用「等父 Composable 重组」来触发：`rememberHasUsableBgImage()` 的 key 是
    // 主题色板签名，背景图开关不在其中，返回本页时页面通常一次都不重组。改为本组件
    // 自己订阅偏好变化，把值提升为 State——结论不变时不触发重组，变化时走新的
    // available key / bind key 建宿主或回退到 [fallback]。
    val wallpaperGlassAllowed = if (requireWallpaperHost) {
        rememberGlassAvailability { GlassSurface.pageGlassAllowed(context) }
    } else {
        // 底栏 / 顶栏 / 对话框：只看采样域与设备，不叠壁纸门槛（否则无背景图时整块消失）
        true
    }
    // 可用性不再需要 remember：壁纸那一半已由 rememberGlassAvailability 提升为 State，
    // 采样域 / 设备那一半（isAvailable）是廉价的内存判定，每次组合重算即可
    val available = glassPanelAvailable(
        requireWallpaperHost = requireWallpaperHost,
        pageWallpaperAllowed = wallpaperGlassAllowed,
        domainAvailable = GlassSurface.isAvailable(domain, context)
    )
    Box(modifier = modifier) {
        if (available) {
            AndroidView(
                factory = { GlassPanelHost(it) },
                update = { host ->
                    host.bind(
                        domain = domain,
                        cornerRadiusPx = cornerRadiusPx,
                        signature = configSignature,
                        screenAligned = screenAligned,
                        wallpaperGlassAllowed = wallpaperGlassAllowed
                    )
                },
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(Modifier.matchParentSize().then(fallback))
        }
        content()
    }
}

/**
 * 把「此刻能不能用壁纸域玻璃」提升为组合期 State。
 *
 * 为什么必须自订阅而不是等父级重组：`io.legado.app.ui.widget.compose.rememberHasUsableBgImage()`
 * 的 remember key 是主题色板签名（`ThemeUiPalette.signature`），背景图开关
 * （`PreferKey.bgImage`）不在其中；页面把 `rememberGlassConfigSignature()` 传给
 * [GlassPanel] 时那份签名也只含档位 / 强度 / 描边 / 夜间。于是「进设置关背景图 → 返回」
 * 时页面通常一次都不重组，玻璃层就停在旧结论上。
 *
 * 这里订阅默认偏好（背景图路径 / 墨水屏 / 夜间模式都在其中），只有结论真的变了才写
 * State，同值时零重组。监听器随组合创建 / 销毁（LazyColumn 的行滚出即解绑），回调只做
 * 几次内存偏好读取。
 *
 * **读什么就必须进 key**：[compute] 闭包里除 `context` 外读到的 call site 参数一律要由
 * 调用方按位置传进 [keys]——`remember` 与 `DisposableEffect` 都以 `context + keys` 为
 * key，漏掉一位则 effect 不重建、闭包继续捕获旧值（`GlassSurfaceCard` 的 `screenAligned`
 * 就是这种情况：它换的是整套可用性判定入口，不能只靠「调用方保证传字面量」）。
 * 当前调用点：`GlassSurfaceCard` 传 `screenAligned`，[GlassPanel] 不传。
 *
 * [compute] 只应在偏好变化或 [keys] 变化时被调用，因此除 [keys] 声明的参数外，**不要**
 * 读取本实例不稳定的局部状态（缓存了一次的 `colorScheme`、`remember` 出来的对象等）。
 */
@Composable
internal fun rememberGlassAvailability(
    vararg keys: Any?,
    compute: () -> Boolean
): Boolean {
    val context = LocalContext.current
    var allowed by remember(context, *keys) { mutableStateOf(compute()) }
    DisposableEffect(context, *keys) {
        val prefs = context.defaultSharedPreferences
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val next = compute()
            if (next != allowed) {
                allowed = next
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    return allowed
}

/**
 * [GlassPanel] 是否建玻璃宿主（纯 JVM，便于单测）。
 *
 * 两条口径互斥，不能混用：
 * - [requireWallpaperHost] = true（页面内卡片 / 吸顶头 / 行）：以「壁纸此刻是否可用」为准
 *   （[pageWallpaperAllowed]，含背景图开关、solid 档、墨水屏、低内存、页面是否露壁纸）；
 * - [requireWallpaperHost] = false（底栏 / 顶栏 / 对话框）：只看采样域与设备
 *   （[domainAvailable]）。这些位置由调用方保证合法，无背景图的普通主题下同样需要玻璃 /
 *   实底壳，叠一层壁纸门槛会让它们整块消失。
 */
internal fun glassPanelAvailable(
    requireWallpaperHost: Boolean,
    pageWallpaperAllowed: Boolean,
    domainAvailable: Boolean
): Boolean = if (requireWallpaperHost) pageWallpaperAllowed else domainAvailable

/**
 * [GlassPanelHost] 的重绑 key（纯 JVM，便于单测）。
 *
 * 必须含 [wallpaperGlassAllowed]：宿主是同一个 View，背景图开关变化不会改
 * `signature`（那是玻璃观感签名），少这一位则「关背景图后玻璃 drawable 仍挂在宿主上」。
 */
internal fun glassPanelBindKey(
    signature: String,
    domain: GlassSamplingDomain,
    cornerRadiusPx: Float,
    screenAligned: Boolean,
    wallpaperGlassAllowed: Boolean
): String = "$signature|${domain.name}|$cornerRadiusPx|$screenAligned|$wallpaperGlassAllowed"

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

    fun bind(
        domain: GlassSamplingDomain,
        cornerRadiusPx: Float,
        signature: String,
        screenAligned: Boolean,
        wallpaperGlassAllowed: Boolean
    ) {
        val key = glassPanelBindKey(
            signature = signature,
            domain = domain,
            cornerRadiusPx = cornerRadiusPx,
            screenAligned = screenAligned,
            wallpaperGlassAllowed = wallpaperGlassAllowed
        )
        if (key == bindKey) return
        bindKey = key
        // 壁纸不可用时显式清空：宿主复用（AndroidView 不重建）时不能把旧玻璃留在身上
        background = if (wallpaperGlassAllowed) {
            GlassSurface.panelBackground(this, domain, cornerRadiusPx, screenAligned)
        } else {
            null
        }
    }
}
