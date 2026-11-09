package io.legado.app.ui.glass

import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.widget.StableLiquidGlassView

/**
 * 液态玻璃一档的最终像素参数（与 `StableLiquidGlassView` 的注入口径一一对应）。
 * dp 值已按屏幕密度换算为像素；tintAlpha / dispersion 是无量纲值直传。
 *
 * 顶栏与阅读页菜单共用这一份换算：两处都不重定义任何公式，
 * 材质的唯一来源仍是 [GlassMaterialPreset.current]。
 */
internal data class LiquidGlassSpec(
    val blurPx: Float,
    val tintAlpha: Float,
    val dispersion: Float,
    val refractionHeightPx: Float,
    val refractionOffsetPx: Float
)

/** `StableLiquidGlassView` 的液态着色器门槛（`RuntimeShader` / `createRuntimeShaderEffect` = API 33）。 */
internal const val LIQUID_GLASS_MIN_SDK: Int = 33

/**
 * 预设 → 液态玻璃像素参数（纯 JVM，可直接单测）。
 *
 * solid 档返回 null（不激活玻璃层，由宿主既有的实底表面兜底）；
 * glass / frosted 档与 [GlassMaterialPreset] 预设 1:1。
 */
internal fun liquidGlassSpec(
    params: GlassMaterialPreset.Params,
    density: Float
): LiquidGlassSpec? = when (params.mode) {
    GlassMaterialPreset.SOLID -> null
    else -> LiquidGlassSpec(
        blurPx = params.blurDp * density,
        tintAlpha = params.tintAlpha,
        dispersion = params.dispersion,
        refractionHeightPx = params.refractionHeightDp * density,
        refractionOffsetPx = params.refractionOffsetDp * density
    )
}

/**
 * 把一份 [LiquidGlassSpec] 灌进玻璃宿主。
 *
 * 五个数值与 [LiquidGlassSpec] 字段一一对应，集中在 `LiquidGlassSpec` 旁边，
 * 顶栏 / 阅读页菜单 / 侧边面板 / 弹层四个接入点因此不会各抄一遍、也不会漏设某一项。
 */
internal fun StableLiquidGlassView.applyLiquidGlassSpec(spec: LiquidGlassSpec) {
    setBlurRadius(spec.blurPx)
    setTintAlpha(spec.tintAlpha)
    setDispersion(spec.dispersion)
    setRefractionHeight(spec.refractionHeightPx)
    setRefractionOffset(spec.refractionOffsetPx)
}

/**
 * 内容域（`GlassSamplingDomain.CONTENT`）液态玻璃的启用判定（纯 JVM）。
 *
 * 返回 null 表示宿主保持原有表面不变；非 null 才允许创建
 * `StableLiquidGlassView` 并绑定兄弟子树采样源。四个否决条件各自对应一个已确证的
 * 失败形态：
 *
 * - [sourceAvailable] = false：没有可采样的兄弟子树（或采样源不可见 / 是 WebView）。
 *   无源时玻璃会画出空内容，比不接更糟；
 * - [sdkInt] < [LIQUID_GLASS_MIN_SDK]：没有 `RuntimeShader`，`StableLiquidGlassView`
 *   整条路径不可用（这不是设备降级，是该入口本身不存在）；
 * - [isLowRam] / [isEInk]：每帧要把采样源整树录制进 `RenderNode`，阅读场景的
 *   性能红线要求这两类设备保持静态表面。
 */
internal fun contentLiquidGlassSpec(
    params: GlassMaterialPreset.Params,
    density: Float,
    sdkInt: Int,
    sourceAvailable: Boolean,
    isLowRam: Boolean,
    isEInk: Boolean
): LiquidGlassSpec? = when {
    !sourceAvailable -> null
    sdkInt < LIQUID_GLASS_MIN_SDK -> null
    isLowRam -> null
    isEInk -> null
    else -> liquidGlassSpec(params, density)
}

/**
 * 内容域玻璃表面矩形（纯 JVM，不引用 Android `Rect`）。
 *
 * [panelLeft] / [panelTop] 是面板在宿主本地坐标系里的左上角，[panelWidth] /
 * [panelHeight] 是面板尺寸。底边额外向下延伸 [cornerRadiusPx]：玻璃层是单一半径的
 * 四角圆角矩形，而底部菜单面板贴着屏幕底边，下方两个圆角会露出缺口；
 * 把底边推出父容器裁剪区，就只能看到面板上方的两个圆角。
 *
 * 面板尺寸非法（未测量完成 / 已隐藏）时返回 null，调用方跳过定位。
 */
internal data class GlassPanelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal fun glassPanelRect(
    panelLeft: Float,
    panelTop: Float,
    panelWidth: Float,
    panelHeight: Float,
    cornerRadiusPx: Float
): GlassPanelRect? {
    if (panelWidth <= 0f || panelHeight <= 0f) return null
    val left = kotlin.math.floor(panelLeft).toInt()
    val top = kotlin.math.floor(panelTop).toInt()
    val right = kotlin.math.ceil(panelLeft + panelWidth).toInt()
    val bottom = kotlin.math.ceil(
        panelTop + panelHeight + cornerRadiusPx.coerceAtLeast(0f)
    ).toInt()
    if (right - left <= 0 || bottom - top <= 0) return null
    return GlassPanelRect(left, top, right, bottom)
}

/**
 * 面板是否让出底色（纯 JVM）。
 *
 * 只有「玻璃已激活且已拿到面板矩形」时才允许让出：玻璃层尚未定位时让出，
 * 得到的是一块透明空面板（真机表现为菜单背景整体消失），而玻璃层定位后
 * 尺寸或绘制失败时，实底面板至少还能兜住可读性。
 */
internal fun glassPanelFillYielded(active: Boolean, hasGeometry: Boolean): Boolean =
    active && hasGeometry

/**
 * 侧边导航面板是否让出底色（纯 JVM）。
 *
 * 与阅读页底部菜单 [glassPanelFillYielded] 同一纪律：玻璃层没拿到有效尺寸前，
 * 面板必须保持实底。差别只在几何来源——侧边面板走常规布局，尺寸由父容器给出，
 * 没有矩形要算；玻璃层由 GONE 转 VISIBLE 的那一帧还没有尺寸，此时让出底色
 * 会露出一帧透明空面板，所以判定加在「玻璃层已测量」上。
 */
internal fun sidebarGlassFillYielded(
    active: Boolean,
    glassWidth: Int,
    glassHeight: Int
): Boolean = active && glassWidth > 0 && glassHeight > 0

/**
 * 侧边导航面板是否有资格走玻璃（纯 JVM）。
 *
 * 面板配置了自定义侧边背景图时不接：那张图就是面板的最终外观，玻璃层会被它
 * 整块盖住（采样与录制全是白开销）。其余设备 / 采样源判定仍交给
 * [contentLiquidGlassSpec]，这里只锁「自定义背景图否决」这一条面板专属规则。
 */
internal fun sidebarGlassSourceAllowed(
    hasCustomBackground: Boolean,
    sourceAvailable: Boolean
): Boolean = !hasCustomBackground && sourceAvailable

/**
 * 覆盖式弹层是否让出底色（纯 JVM）。
 *
 * 弹层（如 `ModernActionPopup`）比面板多一个前置条件：玻璃层是**后挂**的，
 * 面板尺寸由首帧测量回填后才创建，因此「玻璃可用」与「面板已测量」必须同时成立。
 * 缺任一条件就让出底色，弹出的会是一块透明空面板——与阅读页菜单 1.25.11
 * 的「菜单背景整体消失」是同一形态，只是触发点从矩形未算变成尺寸未回填。
 */
internal fun overlayGlassFillYielded(
    glassAvailable: Boolean,
    panelWidthPx: Int,
    panelHeightPx: Int
): Boolean = glassAvailable && panelWidthPx > 0 && panelHeightPx > 0
