package io.legado.app.ui.main.explore

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.MainThemeBackgroundState
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.utils.ColorUtils as AppColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.stackBlurSoftware
import java.io.File
import kotlin.math.max

/**
 * 同一张对齐底图的两种产物，供两条管线各取所需（两者尺寸完全一致，采样矩阵可共用）：
 * - [raw]：未经软件模糊，硬件管线的 GPU 高斯采样源；
 * - [blurred]：经 `stackBlurSoftware` 预模糊，软件管线（②③）的采样源，
 *   同时也是硬件管线运行期失败时的即时降级源。
 *
 * 两份共存的价值在于「降级零等待」：硬件失败当帧就能切到 blurred 重画，
 * 不必重新触发一次异步构建，也就不会闪一下纯实底或透出清晰壁纸。
 * 代价仅是构建期多一次 IO 线程的软件模糊（按 key 去重、仅壁纸/主题变化时执行一次）。
 */
private data class GlassBackdrop(
    val raw: Bitmap?,
    val blurred: Bitmap?
)

/**
 * 硬件模糊是否可用：`RenderEffect.createBlurEffect` / `RenderNode.setRenderEffect`
 * 均为 API 31（Android 12）。墨水屏、低内存设备与总开关关闭由
 * `ExploreGlassBackdrop.rowBackground()` 直接返回 null 挡在门外，此处无需重复判断。
 */
private fun supportsHardwareBlur(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 硬件管线模糊半径（屏幕像素）的上下限。 */
private const val HARDWARE_BLUR_MIN_PX = 1f
private const val HARDWARE_BLUR_MAX_PX = 80f

/** 细项倍率滑杆范围：100 = 沿用该档预设原值，0 = 关闭该维度，200 = 加倍。 */
internal const val EXPLORE_GLASS_SCALE_MIN = 0
internal const val EXPLORE_GLASS_SCALE_DEFAULT = 100
internal const val EXPLORE_GLASS_SCALE_MAX = 200

/**
 * 三档材质的取值与底栏 `bottomBarEffectMode` 完全一致（solid/glass/frosted），
 * 参数由 [GlassMaterialPreset] 统一提供，不再用系数去缩放发现页既有观感。
 * 1.25.5 曾把「无玻璃」存成 `none`，这里保留为旧值别名以便平滑升级。
 */
internal const val EXPLORE_GLASS_MODE_SOLID = GlassMaterialPreset.SOLID
internal const val EXPLORE_GLASS_MODE_GLASS = GlassMaterialPreset.GLASS
internal const val EXPLORE_GLASS_MODE_FROSTED = GlassMaterialPreset.FROSTED
internal const val EXPLORE_GLASS_MODE_DEFAULT = EXPLORE_GLASS_MODE_FROSTED
private const val LEGACY_MODE_NONE = "none"

/**
 * 解析生效档位（纯函数，不依赖 Android 运行时，便于单测覆盖迁移与联动）：
 * 1. 「跟随底栏材质」开启时直接沿用底栏的档位字符串（两边是同一套取值）；
 * 2. 未开启跟随时，显式存的档位优先，旧值 `none` 归一到 `solid`；
 * 3. 从未存过档位时，1.25.4 的旧总开关 `exploreGlassEnabled=false` 迁移成「无玻璃」，
 *    否则回落到默认磨砂玻璃档。
 */
internal fun resolveExploreGlassMode(
    rawMode: String?,
    legacyEnabled: Boolean,
    followBottomBar: Boolean,
    bottomBarMode: String
): String {
    if (followBottomBar) {
        return bottomBarMode.takeIf { it in GlassMaterialPreset.modes }
            ?: EXPLORE_GLASS_MODE_GLASS
    }
    val stored = rawMode
        ?.let { if (it == LEGACY_MODE_NONE) EXPLORE_GLASS_MODE_SOLID else it }
        ?.takeIf { it in GlassMaterialPreset.modes }
    return when {
        stored != null -> stored
        !legacyEnabled -> EXPLORE_GLASS_MODE_SOLID
        else -> EXPLORE_GLASS_MODE_DEFAULT
    }
}

/**
 * 把「该档预设给出的基础值」按用户滑杆倍率缩放并限幅的纯函数（可直接单测）。
 * percent=100 时原样返回预设值；越界值先钳到 [0, 200] 再计算，
 * 避免备份恢复写入异常值时 alpha 越界。
 */
internal fun scaledGlassParam(
    base: Float,
    percent: Int,
    upperBound: Float = Float.MAX_VALUE
): Float {
    val scale = percent.coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX) / 100f
    return (base * scale).coerceIn(0f, upperBound)
}

/**
 * 「我的 › 发现页毛玻璃」里各个设置项的一次性快照。
 * [mode] 是已解析的生效档位（含跟随底栏与旧开关迁移）；总强度 [level] 沿用
 * 日夜间分离的 themeExploreGlassBlur；其余都是全局倍率/开关，
 * 与 frostedGlassLevel 等全局 UI 键同体系，不参与主题包导入导出。
 */
internal data class ExploreGlassConfig(
    val mode: String,
    val followBottomBar: Boolean,
    val level: Int,
    val blurPercent: Int,
    val shellPercent: Int,
    val tintPercent: Int,
    val strokePercent: Int,
    val hardwareBlur: Boolean,
    val animatedSolid: Boolean
)

/** 集中读取一次偏好，避免每行 bind 时散落多次 SharedPreferences 访问。 */
internal fun readExploreGlassConfig(context: Context): ExploreGlassConfig {
    val legacyEnabled = context.getPrefBoolean(PreferKey.exploreGlassEnabled, true)
    val followBottomBar = context.getPrefBoolean(PreferKey.exploreGlassFollowBottomBar, false)
    return ExploreGlassConfig(
        mode = resolveExploreGlassMode(
            context.getPrefString(PreferKey.exploreGlassEffectMode),
            legacyEnabled,
            followBottomBar,
            AppConfig.bottomBarEffectMode
        ),
        followBottomBar = followBottomBar,
        level = context.getPrefInt(
            ThemeRuntimeKeys.themeExploreGlassBlur(), ExploreGlassBackdrop.LEVEL_DEFAULT
        ).coerceIn(0, 100),
        blurPercent = context.getPrefInt(
            PreferKey.exploreGlassBlurScale, EXPLORE_GLASS_SCALE_DEFAULT
        ).coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX),
        shellPercent = context.getPrefInt(
            PreferKey.exploreGlassShellScale, EXPLORE_GLASS_SCALE_DEFAULT
        ).coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX),
        tintPercent = context.getPrefInt(
            PreferKey.exploreGlassTintScale, EXPLORE_GLASS_SCALE_DEFAULT
        ).coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX),
        strokePercent = context.getPrefInt(
            PreferKey.exploreGlassStrokeScale, EXPLORE_GLASS_SCALE_DEFAULT
        ).coerceIn(EXPLORE_GLASS_SCALE_MIN, EXPLORE_GLASS_SCALE_MAX),
        hardwareBlur = context.getPrefBoolean(PreferKey.exploreGlassHardwareBlur, true),
        animatedSolid = context.getPrefBoolean(PreferKey.exploreGlassAnimatedSolid, true)
    )
}

/**
 * 解析当前生效档位的完整材质参数（与底栏共用 [GlassMaterialPreset]）。
 * 未开启跟随时：模糊、着色、壳层、描边、色散、折射全取该档预设，强度由本页
 * 日夜间分离的 themeExploreGlassBlur 提供；
 * 开启跟随时：连强度也取底栏的 frostedGlassLevel / liquidGlassLevel，
 * 此时发现页与底栏用的是同一份逐值一致的预设。
 */
internal fun exploreGlassMaterial(
    config: ExploreGlassConfig,
    fallbackBoost: Float = GlassMaterialPreset.fallbackBoost()
): GlassMaterialPreset.Params =
    if (config.followBottomBar) {
        GlassMaterialPreset.current(config.mode)
    } else {
        GlassMaterialPreset.params(
            config.mode,
            GlassMaterialPreset.opacityLevel(config.level),
            fallbackBoost
        )
    }

/**
 * 硬件高斯半径的纯函数换算：输入是该档预设给出的 blurDp，不是发现页自行推算的公式。
 * 乘屏幕密度得屏幕像素，再限幅到 [1, 80] px。**不乘**软件管线的 1.4 等效系数——
 * 那个系数是把 GPU 高斯折算成 stackBlur 半径用的，硬件管线用的就是 GPU 真高斯。
 */
internal fun exploreHardwareBlurPx(
    blurDp: Float,
    density: Float,
    blurPercent: Int = EXPLORE_GLASS_SCALE_DEFAULT
): Float {
    val scaledDp = scaledGlassParam(blurDp, blurPercent)
    return (scaledDp * density).coerceIn(HARDWARE_BLUR_MIN_PX, HARDWARE_BLUR_MAX_PX)
}

/**
 * 边缘高光强度：底栏的折射/色散由 LiquidGlass 着色器在边缘形成高光，
 * 发现页行渲染器没有折射 shader，用同一预设的 dispersion 线性映射出高光 alpha，
 * 使「透明玻璃」档的亮边明显强于「磨砂玻璃」档。纯函数便于确定性单测。
 */
internal fun exploreGlassRimAlpha(dispersion: Float): Float =
    (dispersion * 0.5f).coerceIn(0f, 0.24f)

/**
 * 发现页书源名行的毛玻璃背景。
 *
 * 取主界面背景图层（与主题背景同一文件/裁剪/模糊设置）生成一张按屏幕对齐的降采样底图，
 * 行背景按行在屏幕上的实时位置采样这张底图，滚动时随位置重新采样，
 * 得到「透出背后壁纸并磨砂」的玻璃观感，避免书源名与壁纸内容直接重合。
 *
 * 模糊有两条管线，按设备能力二选一（②③ 的软件管线始终保留，是硬件管线的降级基线）：
 * - **硬件管线（API 31+）**：底图不做软件模糊，改由 [HardwareGlassLayer] 把「玻璃层」
 *   录进一个独立 [RenderNode]，对其设置 [RenderEffect.createBlurEffect] 交给 GPU 做真高斯。
 *   RenderEffect 只作用于该 RenderNode 自身的绘制，不影响 tint/shell/描边，更不会影响
 *   同层的书源名文字，因此不需要把整行 View 一起模糊。
 * - **软件管线（API 21-30 / 降级）**：底图在 IO 线程经 [stackBlurSoftware] 预模糊一次，
 *   行绘制用 `BitmapShader` 采样，即 ②③ 已实测验证的既有实现。本轮只把采样源由单张
 *   Bitmap 换成 [GlassBackdrop.blurred]、并补一条硬件失败当帧回落分支；radius 公式、
 *   shaderMatrix、tint/shell/描边参数与绘制语义全部保留。
 *
 * 两条管线共用同一份底图构建（decode + crop 对齐 + fallbackColor）、同一套位置缓存
 * （[GlassPositionTracker]）与同一套 shell/tint/描边参数，保证降级时观感连续。
 *
 * 材质三档（自动预设，取值与底栏 bottomBarEffectMode 完全一致，见 [GlassMaterialPreset]）：
 * - **无玻璃 [EXPLORE_GLASS_MODE_SOLID]**：走 [GlassMaterialPreset.createSolidShellDrawable]，
 *   即底栏 solid 档同款实底壳层（bottomBackground + config.opacity + 描边），不再是透明背景；
 * - **透明玻璃 [EXPLORE_GLASS_MODE_GLASS]**：取 glass 档整组参数（`8 + l*10`dp 模糊、
 *   `0.02 + l*0.05` 着色、更强色散/折射，以及由此派生的边缘高光）；
 * - **磨砂玻璃 [EXPLORE_GLASS_MODE_FROSTED]**：取 frosted 档整组参数（`12 + l*18`dp 模糊、
 *   `0.05 + l*0.10` 着色、更弱色散/折射）。
 * 两档的壳层三色与描边 alpha 均由 [GlassMaterialPreset.shellAlphas] 提供，与底栏
 * createLiquidGlassShellDrawable 逐值一致；发现页不再自行推算基础值，也不再用
 * 「系数」在既有观感上缩放。四个倍率滑杆只在该档预设值之上微调。
 * 「跟随底栏材质」开启时档位与强度均取底栏（[GlassMaterialPreset.current]），
 * 本页存的档位保留但不生效。
 *
 * 降级矩阵（任一条命中都不留空白帧，也不退回未模糊的 raw）：
 * - 无玻璃档：优先返回底栏同款实底壳层（不受玻璃强度 0 影响）；
 * - 玻璃档 level<=0 / 墨水屏 / 低内存：返回 null，用原透明背景；
 * - 动图壁纸且「使用纯色底」开启：`backdrop()` 返回 null，玻璃层不采样，
 *   只留 tint/shell/描边纯实底；关闭该开关才允许采样静止首帧；
 * - API 21-30：不创建 [HardwareGlassLayer]，直接走软件管线；
 * - API 31+ 但 RenderEffect 构造失败：init 的 `runCatching` 得到 null，走软件管线；
 * - 非硬件加速 Canvas：当帧走软件管线，不置持久故障位（截图/软件 layer 是临时状态）；
 * - `drawRenderNode` 在硬件 Canvas 上抛异常：置 `hardwareFailed`，该行永久走软件管线；
 * - raw/blurred 任一缺失或已回收：raw 缺失直接让位软件管线（避免双重模糊），
 *   blurred 缺失则只画 shell，绝不退回未模糊的 raw；
 * - `stackBlurSoftware` 构建异常：blurred 留 null（不再兜底为 raw），软件管线只画 shell，
 *   硬件管线仍可用 raw 由 GPU 出图。
 * 由于 [GlassBackdrop] 同时缓存两份底图，以上降级都能「当帧」完成，无需重新异步构建。
 *
 * 参数来源见 [readExploreGlassConfig]：未跟随底栏时，总强度（0-100）沿用日夜间分离的
 * themeExploreGlassBlur 并按 [GlassMaterialPreset.opacityLevel] 映射；跟随底栏时，
 * 强度也取底栏的 frostedGlassLevel / liquidGlassLevel。其余细项为全局倍率/开关，
 * 倍率默认 100 即取该档预设原值，0 为关闭该维度，200 为加倍，越界值钳到 [0, 200]。
 */
object ExploreGlassBackdrop {

    /** 未设置时的默认毛玻璃强度。 */
    const val LEVEL_DEFAULT = 60

    /**
     * 软件模糊位图半径的换算系数：GPU 高斯半径 → stackBlurSoftware 等效半径的 1.4 倍，
     * 再除以 [DOWNSCALE]。blurDp 本身来自 [GlassMaterialPreset.Params.blurDp]，
     * 发现页不再自己定义 `12 + l*18` 这类公式。
     */
    private const val STACK_BLUR_DENSITY_FACTOR = 1.4f

    /**
     * 背景位图相对屏幕的降采样比例。
     * 值越小采样越细、越接近原图，能有效减少降采样导致的摩尔纹/色块，
     * 代价是构建位图变大、软件模糊耗时上升（构建已放 IO 线程且仅首次 miss 时执行一次）。
     * 屏幕等效模糊半径由 [backdrop] 的 radius 公式按 DOWNSCALE 反比例换算，故改此值不改变观感强度。
     */
    private const val DOWNSCALE = 3f

    private data class BackdropCache(
        val key: String,
        val backdrop: GlassBackdrop?
    )

    @Volatile
    private var cache: BackdropCache? = null

    //按 key 去重的在途构建任务与等待回填的 drawable，避免多行同时 miss 重复构建
    private val inFlightKeys = HashSet<String>()
    private val pendingCallbacks = HashMap<String, ArrayList<(GlassBackdrop?) -> Unit>>()

    /** 当前有效强度（含设备级强制降级与无玻璃档），供外部查询。 */
    fun level(context: Context): Int {
        if (AppConfig.isEInkMode || isLowRamDevice(context)) return 0
        val config = readExploreGlassConfig(context)
        return if (config.mode == EXPLORE_GLASS_MODE_SOLID) 0 else config.level
    }

    /**
     * 配置签名：任何影响观感的设置项变化都会改变它。
     * ExploreFragment 在 onResume 时用它判断是否刷新经典行背景，
     * 因此新增细项后不必再逐个加监听——只要参数进快照，返回发现页即生效。
     * [ExploreGlassConfig.mode] 已包含「跟随底栏」的解析结果；底栏的两个强度键在
     * 跟随时直接决定发现页的模糊与壳层，故无条件纳入，避免只改底栏强度时不重绘。
     */
    fun configSignature(context: Context): String {
        val config = readExploreGlassConfig(context)
        return listOf(
            config.mode, config.followBottomBar, config.level,
            config.blurPercent, config.shellPercent,
            config.tintPercent, config.strokePercent, config.hardwareBlur,
            config.animatedSolid, AppConfig.isNightTheme,
            AppConfig.bottomBarEffectMode, AppConfig.frostedGlassLevel,
            AppConfig.liquidGlassLevel
        ).joinToString("|")
    }

    private fun isLowRamDevice(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /**
     * 无玻璃档：返回底栏 solid 档同款实底壳层，而不是默认透明背景。
     * 底色、不透明度、描边与底栏 createSolidBottomShellDrawable 共用同一实现。
     */
    private fun solidShellDrawable(context: Context): GradientDrawable {
        val navConfig = NavigationBarIconConfig.currentEntry(AppConfig.isNightTheme).config
        val borderColor = navConfig.borderColor?.let {
            AppColorUtils.withAlpha(it, navConfig.borderAlpha.coerceIn(0, 100) / 100f)
        }
        return GlassMaterialPreset.createSolidShellDrawable(
            baseColor = ThemeStore.bottomBackground(context),
            opacity = navConfig.opacity,
            borderColor = borderColor,
            cornerRadiusPx = context.resources.getDimension(R.dimen.ui_panel_radius),
            oval = false,
            strokeWidthPx = 1.dpToPx()
        )
    }

    /** 返回毛玻璃行背景；level<=0 / 设备降级时返回 null，调用方保留默认透明背景。 */
    fun rowBackground(host: View): Drawable? {
        val context = host.context
        // 墨水屏 / 低内存设备自动降级为关闭，牺牲少量视觉换取滚动流畅（架构 T03 验收项）
        if (AppConfig.isEInkMode || isLowRamDevice(context)) return null
        val config = readExploreGlassConfig(context)
        // 无玻璃档与底栏 solid 一样不依赖玻璃强度：先返回实底壳层，强度 0 也不改变它。
        if (config.mode == EXPLORE_GLASS_MODE_SOLID) return solidShellDrawable(context)
        if (config.level <= 0) return null
        val material = exploreGlassMaterial(config)
        val useHardware = supportsHardwareBlur() && config.hardwareBlur
        return FrostedGlassDrawable(
            host = host,
            radiusPx = context.resources.getDimension(R.dimen.ui_panel_radius),
            baseColor = ThemeStore.bottomBackground(context),
            config = config,
            material = material,
            useHardware = useHardware,
            hardwareBlurPx = if (useHardware) {
                exploreHardwareBlurPx(
                    material.blurDp, context.resources.displayMetrics.density,
                    config.blurPercent
                )
            } else 0f
        ).also { drawable ->
            //主线程只查缓存；miss 时异步构建（decode + 软件模糊较重，避免主线程卡顿/ANR），
            //完成后回填刷新，期间先用上一张缓存位图（若有）过渡
            drawable.updateBackdrop(
                backdrop(context, config, material, drawable::updateBackdrop)
            )
            GlassPositionTracker.attach(host, drawable)
        }
    }

    private fun backdrop(
        context: Context,
        config: ExploreGlassConfig,
        material: GlassMaterialPreset.Params,
        onReady: (GlassBackdrop?) -> Unit
    ): GlassBackdrop? {
        val state = MainThemeBackgroundState.from(context)
        // 动图壁纸（gif/webp）由背景层 ComposeThemeImage 逐帧播放，而 buildBitmap 用
        // BitmapFactory.decodeFile 只能取首帧：若照常采样会产生「壁纸在动、玻璃静止」的错乱。
        // 默认返回 null 走纯 shell 实底（玻璃层不再采样静止帧），与背景层动画脱钩；
        // 设置页关掉「动图壁纸使用纯色底」后，用户可接受静止首帧玻璃，此时照常构建。
        // 两条管线都基于这张静态底图，共用此降级判定。
        if (state.file != null && state.animated && config.animatedSolid) return null
        val metrics = context.resources.displayMetrics
        val targetW = max(64, (metrics.widthPixels / DOWNSCALE).toInt())
        val targetH = max(64, (metrics.heightPixels / DOWNSCALE).toInt())
        val file = state.file
        val key = listOf(
            file?.absolutePath.orEmpty(),
            file?.length() ?: 0L,
            file?.lastModified() ?: 0L,
            state.blur,
            state.crop?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
            AppConfig.isNightTheme,
            targetW,
            // blurDp 来自共享预设，已含档位公式与强度（跟随底栏时含底栏强度）。
            // 它决定软件模糊 radius，必须纳入 key 才会重建底图；换档、改强度、
            // 改模糊倍率都会改变这个值或下面几项。
            (material.blurDp * 1000).toInt(),
            config.blurPercent,
            config.mode,
            config.followBottomBar,
            AppConfig.frostedGlassLevel,
            AppConfig.liquidGlassLevel,
            config.animatedSolid
        ).joinToString("|")
        synchronized(this) {
            cache?.takeIf { it.key == key }?.let { return it.backdrop }
        }
        // 模糊强度直接取该档预设的 blurDp（玻璃档 8+l*10、磨砂档 12+l*18），
        // 再乘用户的模糊倍率。折算为位图像素：屏幕等效半径 ≈ dp*密度*
        // STACK_BLUR_DENSITY_FACTOR（GPU 高斯→stackBlur 等效系数），再除以降采样比例。
        // 固定用纯软件模糊：不依赖 renderscript toolkit 的 native 库。
        val density = metrics.density
        val blurDp = scaledGlassParam(material.blurDp, config.blurPercent)
        val radius = (
            state.blur / DOWNSCALE + blurDp * density * STACK_BLUR_DENSITY_FACTOR / DOWNSCALE
            ).toInt().coerceIn(3, 25)
        synchronized(this) {
            val stale = cache?.backdrop
            pendingCallbacks.getOrPut(key) { ArrayList() }.add(onReady)
            if (inFlightKeys.add(key)) {
                //构建放 IO；旧缓存位图不显式 recycle（仍可能被各行 drawable 引用，
                //显式回收会在重绘时抛 "trying to use a recycled bitmap"），交给 GC 回收
                Coroutine.async(Coroutine.defaultScope) {
                    buildBitmap(
                        file, state.blur, state.fallbackColor, state.crop,
                        targetW, targetH, radius
                    )
                }.onSuccess { backdrop ->
                    finishBackdropBuild(key, backdrop)
                }.onError {
                    finishBackdropBuild(key, null)
                }
            }
            return stale
        }
    }

    /** 构建完成：更新缓存并回填所有等待的 drawable（回调在主线程执行）。 */
    private fun finishBackdropBuild(key: String, backdrop: GlassBackdrop?) {
        val callbacks = synchronized(this) {
            inFlightKeys.remove(key)
            if (backdrop != null) {
                cache = BackdropCache(key, backdrop)
            }
            pendingCallbacks.remove(key)
        } ?: return
        callbacks.forEach { it(backdrop) }
    }

    private fun buildBitmap(
        file: File?,
        blurPref: Int,
        fallbackColor: Int,
        crop: ComposeThemeImageCrop?,
        targetW: Int,
        targetH: Int,
        radius: Int
    ): GlassBackdrop? {
        var source: Bitmap? = null
        if (file != null && file.isFile && file.canRead()) {
            source = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW * 2 &&
                    bounds.outHeight / (sample * 2) >= targetH * 2
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(fallbackColor)
        source?.let { src ->
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val cropRect = if (crop != null) {
                RectF(
                    src.width * crop.left.coerceIn(0f, 1f),
                    src.height * crop.top.coerceIn(0f, 1f),
                    src.width * crop.right.coerceIn(0f, 1f),
                    src.height * crop.bottom.coerceIn(0f, 1f)
                )
            } else {
                RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            }
            if (cropRect.width() >= 1f && cropRect.height() >= 1f) {
                // 与主题背景图层一致：居中裁剪铺满屏幕
                val scale = max(targetW / cropRect.width(), targetH / cropRect.height())
                val dstW = cropRect.width() * scale
                val dstH = cropRect.height() * scale
                val dx = (targetW - dstW) / 2f
                val dy = (targetH - dstH) / 2f
                canvas.drawBitmap(
                    src,
                    Rect(
                        cropRect.left.toInt(),
                        cropRect.top.toInt(),
                        cropRect.right.toInt(),
                        cropRect.bottom.toInt()
                    ),
                    RectF(dx, dy, dx + dstW, dy + dstH),
                    paint
                )
            }
            if (src !== result) {
                src.recycle()
            }
        }
        // 一次构建同时产出两份底图：
        // - raw 供硬件管线，模糊由 GPU RenderEffect 完成，绝不能预先软件模糊（否则双重叠加）；
        // - blurred 供 ②③ 软件管线与硬件失败时的即时降级，固定用纯软件模糊，
        //   不依赖 renderscript toolkit 的 native 库，避免 16KB 内核页设备上的不确定性。
        // stackBlurSoftware 返回全新 Bitmap、不改动入参，故 raw 与 blurred 可安全共存。
        // 模糊失败（多为 OOM）时不再像 1.25.2 那样把 blurred 兜底成 raw：那会让软件管线
        // 透出清晰壁纸。现在留 null，软件管线只画 shell 遮盖，硬件管线仍可用 raw 正常出图。
        val blurred = runCatching { result.stackBlurSoftware(radius) }.getOrNull()
        return GlassBackdrop(raw = result, blurred = blurred)
    }
}

/**
 * 单个书源名行的毛玻璃背景，与底栏（StableLiquidGlassView + shell overlay）同一套材质：
 * 1. 玻璃层：按行当前屏幕位置采样模糊壁纸，半径取 [GlassMaterialPreset.Params.blurDp]；
 * 2. tint 层：底栏 LiquidGlass 同款白色弱着色，alpha 取 [GlassMaterialPreset.Params.tintAlpha]；
 * 3. shell 层：底栏 createLiquidGlassShellDrawable 同款三色垂直渐变 + 描边，
 *    alpha 取 [GlassMaterialPreset.ShellAlphas]（夜间 rgb(22,24,28)、日间白）；
 * 4. rim 层：由该档色散/折射派生的上下边缘高光，补回底栏玻璃的边缘体积感。
 */
private class FrostedGlassDrawable(
    private val host: View,
    private val radiusPx: Float,
    baseColor: Int,
    private val config: ExploreGlassConfig,
    private val material: GlassMaterialPreset.Params,
    private val useHardware: Boolean,
    private val hardwareBlurPx: Float
) : Drawable() {

    companion object {
        /** 描边 alpha 上限沿用底栏 createLiquidGlassShellDrawable 的 0.42。 */
        private const val STROKE_MAX_ALPHA = 0.42f
        /** 壳层与着色的 alpha 上限，防止倍率拉到 200 时越界。 */
        private const val LAYER_MAX_ALPHA = 1f
    }

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = host.resources.displayMetrics.density
    }

    /** 底栏 shell 同款表面色。 */
    private val surfaceColor =
        if (AppColorUtils.isColorLight(baseColor)) Color.WHITE else Color.rgb(22, 24, 28)

    // 基础值全部来自共享预设（与底栏逐值一致），倍率滑杆只在其上做线性缩放。
    private val tintBaseAlpha =
        (scaledGlassParam(material.tintAlpha, config.tintPercent, LAYER_MAX_ALPHA) * 255).toInt()
    private val strokeBaseRatio =
        scaledGlassParam(material.shell.stroke, config.strokePercent, STROKE_MAX_ALPHA)
    private var shellShaderCache: LinearGradient? = null
    private var shellShaderHeight = -1
    private var rimShaderCache: LinearGradient? = null
    private var rimShaderHeight = -1
    private var alphaFactor = 1f
    private val shaderMatrix = Matrix()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val rect = RectF()
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    private var cachedLocation: IntArray? = null
    private var locationSampled = false

    /** 对齐底图（raw + blurred 两份）；缓存未命中时先为空，异步构建完成后由 [updateBackdrop] 回填。 */
    private var backdrop: GlassBackdrop? = null

    /**
     * API 31+ 的硬件模糊层，仅在 [useHardware] 且设备确实支持时创建。
     * 运行期若该层不可用，[hardwareFailed] 置位，本行改走 [backdrop] 的 blurred 位图，
     * 即 ②③ 已验证的软件管线——不是丢弃效果，也不是透出清晰壁纸。
     */
    private var hardwareLayer: HardwareGlassLayer? = null
    private var hardwareFailed = false

    init {
        if (useHardware && supportsHardwareBlur() && hardwareBlurPx > 0f) {
            hardwareLayer = runCatching { HardwareGlassLayer(hardwareBlurPx) }.getOrNull()
        }
    }

    fun updateBackdrop(next: GlassBackdrop?) {
        if (next === backdrop) return
        backdrop = next
        //位图更换后旧 shader 引用的已是过期位图，必须重建
        shader = null
        shaderBitmap = null
        glassPaint.shader = null
        hardwareLayer?.reset()
        invalidateSelf()
    }

    /**
     * 布局 / 滚动回调驱动的位置缓存：只有位置真正变化才重算 shader matrix，
     * 避免 draw() 里逐帧 getLocationOnScreen。
     */
    fun onPositionChanged(left: Int, top: Int, rootLeft: Int, rootTop: Int) {
        location[0] = left
        location[1] = top
        rootLocation[0] = rootLeft
        rootLocation[1] = rootTop
        val cached = cachedLocation
        if (cached != null && cached[0] == left && cached[1] == top) return
        cachedLocation = intArrayOf(left, top)
        locationSampled = true
        invalidateSelf()
    }

    /** 位置失效后，draw 内惰性采样一次当前屏幕位置。 */
    private fun ensureLocationSampled() {
        if (locationSampled && cachedLocation != null) return
        host.getLocationOnScreen(location)
        val root = host.rootView
        root.getLocationOnScreen(rootLocation)
        cachedLocation = intArrayOf(location[0], location[1])
        locationSampled = true
    }

    init {
        tintPaint.color = Color.WHITE
        tintPaint.alpha = tintBaseAlpha
        strokePaint.color = AppColorUtils.withAlpha(surfaceColor, strokeBaseRatio)
    }

    /** 底图可用性统一判定：已回收 / 空尺寸一律视为不可用，交给 shell 兜底。 */
    private fun usable(bitmap: Bitmap): Boolean =
        !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0

    private fun shellShader(height: Int): LinearGradient {
        val cached = shellShaderCache
        if (cached != null && shellShaderHeight == height) return cached
        // 三色 alpha 直接取共享预设（与底栏 createLiquidGlassShellDrawable 逐值一致），
        // 只乘「底色遮盖」滑杆倍率；上深下浅的垂直渐变同样来自底栏。
        val start = scaledGlassParam(material.shell.start, config.shellPercent, LAYER_MAX_ALPHA)
        val center = scaledGlassParam(material.shell.center, config.shellPercent, LAYER_MAX_ALPHA)
        val end = scaledGlassParam(material.shell.end, config.shellPercent, LAYER_MAX_ALPHA)
        return LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                AppColorUtils.withAlpha(surfaceColor, start),
                AppColorUtils.withAlpha(surfaceColor, center),
                AppColorUtils.withAlpha(surfaceColor, end)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        ).also {
            shellShaderCache = it
            shellShaderHeight = height
        }
    }

    /**
     * 底栏 LiquidGlass 在边缘产生折射/色散高光；行渲染器没有该 shader，
     * 用同一预设的 dispersion（亮度）与 refractionHeightDp（高光带宽度）生成
     * 上下边缘高光，使透明玻璃档的亮边明显强于磨砂玻璃档。
     * 参数来源是共享预设，不是在发现页既有观感上手工调系数。
     */
    private fun rimShader(height: Int): LinearGradient {
        val cached = rimShaderCache
        if (cached != null && rimShaderHeight == height) return cached
        val topAlpha = exploreGlassRimAlpha(material.dispersion)
        val bottomAlpha = topAlpha * 0.5f
        val bandPx = material.refractionHeightDp.dpToPx().coerceAtMost(height / 2f)
        val stop = (bandPx / height).coerceIn(0.01f, 0.45f)
        return LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                AppColorUtils.withAlpha(surfaceColor, topAlpha),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                AppColorUtils.withAlpha(surfaceColor, bottomAlpha)
            ),
            floatArrayOf(0f, stop, 1f - stop, 1f),
            Shader.TileMode.CLAMP
        ).also {
            rimShaderCache = it
            rimShaderHeight = height
        }
    }

    override fun draw(canvas: Canvas) {
        val bd = backdrop
        rect.set(bounds)
        // 先筛掉已回收 / 空位图：两条管线各自取源，任一缺失都不允许透出清晰壁纸。
        val software = bd?.blurred?.takeIf { usable(it) }
        val raw = bd?.raw?.takeIf { usable(it) }
        // 硬件管线需同时满足 API 31+、层创建成功、此前无持久故障、raw 底图可用。
        // raw 缺失时直接让位给软件管线：拿 blurred 去做 GPU 模糊会变成双重模糊。
        val activeLayer = hardwareLayer?.takeIf {
            !hardwareFailed && supportsHardwareBlur() && raw != null
        }
        // 硬件激活时 glassBitmap 就是 raw，否则就是 software；两条分支各自用它采样。
        val glassBitmap = if (activeLayer != null) raw else software
        if (glassBitmap != null) {
            val root = host.rootView
            if (root.width > 0 && root.height > 0) {
                ensureLocationSampled()
                val scaleX = glassBitmap.width / root.width.toFloat()
                val scaleY = glassBitmap.height / root.height.toFloat()
                shaderMatrix.setScale(scaleX, scaleY)
                shaderMatrix.postTranslate(
                    -(location[0] - rootLocation[0]) * scaleX,
                    -(location[1] - rootLocation[1]) * scaleY
                )
                var drewGlass = false
                if (activeLayer != null) {
                    // 硬件管线：把这一层录进独立 RenderNode，GPU 做真高斯。
                    drewGlass = activeLayer.draw(
                        canvas, glassBitmap, rect, radiusPx, shaderMatrix,
                        glassPaint.alpha, glassPaint.colorFilter
                    )
                    if (!drewGlass && canvas.isHardwareAccelerated) {
                        // 硬件 Canvas 上仍失败 = RenderEffect/drawRenderNode 的持久性故障，
                        // 置位后本行不再重试，避免逐帧抛异常。位图更换也不复位：设备级
                        // 故障换一张底图不会自己变好，而软件管线已验证可用、观感连续。
                        hardwareFailed = true
                    }
                    // 非硬件加速 Canvas（软件 layer / 截图等）只是当帧状态，不置位，
                    // 下一帧回到硬件 Canvas 会自动恢复真高斯。
                }
                if (!drewGlass && software != null) {
                    // 软件管线（②③ 已实测验证的既有实现）：底图在 IO 线程预模糊，
                    // 这里用 BitmapShader 按位置采样。硬件失败当帧也走这里，观感连续。
                    if (shader == null || shaderBitmap !== software) {
                        shader = BitmapShader(software, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        shaderBitmap = software
                        glassPaint.shader = shader
                    }
                    shader?.setLocalMatrix(shaderMatrix)
                    canvas.drawRoundRect(rect, radiusPx, radiusPx, glassPaint)
                }
            }
        }
        // tint（底栏 LiquidGlass 同款白色弱着色）
        canvas.drawRoundRect(rect, radiusPx, radiusPx, tintPaint)
        // shell 渐变实底（底栏 shell overlay 同款三色 alpha）
        if (rect.height() >= 1f) {
            val shaderHeight = rect.height().toInt().coerceAtLeast(1)
            shellPaint.shader = shellShader(shaderHeight)
            shellPaint.alpha = (255 * alphaFactor).toInt()
            canvas.drawRoundRect(rect, radiusPx, radiusPx, shellPaint)
            // 由同一预设的色散/折射派生的边缘高光
            rimPaint.shader = rimShader(shaderHeight)
            rimPaint.alpha = (255 * alphaFactor).toInt()
            canvas.drawRoundRect(rect, radiusPx, radiusPx, rimPaint)
        }
        // 描边（与底栏 strokeAlpha 同口径）
        canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        val factor = alpha.coerceIn(0, 255) / 255f
        if (factor != alphaFactor) {
            alphaFactor = factor
            glassPaint.alpha = (255 * factor).toInt()
            tintPaint.alpha = (tintBaseAlpha * factor).toInt()
            strokePaint.alpha = (255 * strokeBaseRatio * factor).toInt()
            invalidateSelf()
        }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glassPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
        shellPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        // 硬件层的 paint 在 draw() 里读 glassPaint.colorFilter 同步，需触发一次重绘才会生效。
        invalidateSelf()
    }
}

/**
 * API 31+ 的硬件模糊层：把「玻璃层采样」录进一颗独立 [RenderNode]，
 * 再对该 RenderNode 设置 [RenderEffect.createBlurEffect] 交给 GPU 做真高斯。
 *
 * 为什么不直接 `View.setRenderEffect(blur)`：
 * 那会模糊整行 View 的 RenderNode，书源名文字、箭头、加载动画会一起糊掉。
 * 这里 RenderEffect 只作用于「玻璃层自己」这颗独立 RenderNode，文字与
 * tint/shell/描边都在 [FrostedGlassDrawable.draw] 的普通 Canvas 上绘制，不受影响。
 *
 * 为什么不需要「离屏预渲染整屏玻璃位图」：
 * RenderNode 的 display list 由调用方传入的 `shaderMatrix` 在**录制时**就完成位置采样，
 * 与 ②③ 的 `BitmapShader` 使用同一份矩阵，因此随滚动重录即可，无需任何位图回读、
 * Surface、HardwareRenderer 或跨线程同步；模糊发生在合成阶段，无额外全屏 Bitmap 内存。
 *
 * 生命周期：每个行背景 Drawable 持有一层，`reset()` 在位图更换时丢弃旧录制内容。
 * 类本身带 [RequiresApi]，只在 SDK_INT >= S 时由 [FrostedGlassDrawable] 创建。
 */
@RequiresApi(Build.VERSION_CODES.S)
private class HardwareGlassLayer(private val blurRadiusPx: Float) {

    private val renderNode = RenderNode("ExploreGlassBackdrop")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null

    init {
        // CLAMP：模糊采样越出内容边界时钳制到边缘像素，避免行边缘出现暗边/透明边。
        // 半径由外部按「底栏 frosted 同款 dp 口径」换算成屏幕像素，已在调用方限幅。
        renderNode.setRenderEffect(
            RenderEffect.createBlurEffect(
                blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP
            )
        )
    }

    /** 底图更换后丢弃录制内容与 shader 引用，避免复用已回收/过期的位图。 */
    fun reset() {
        shader = null
        shaderBitmap = null
        paint.shader = null
        renderNode.discardDisplayList()
    }

    /**
     * 画玻璃层。返回 false 表示当前环境不可用（canvas 非硬件加速、RenderEffect 异常等），
     * 调用方据此立即回落到 ②③ 软件管线。
     */
    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        rect: RectF,
        cornerRadius: Float,
        matrix: Matrix,
        alpha: Int,
        colorFilter: ColorFilter?
    ): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val width = rect.width().toInt()
        val height = rect.height().toInt()
        if (width <= 0 || height <= 0) return false
        return runCatching {
            val current = shader
            val next = if (current != null && shaderBitmap === bitmap) {
                current
            } else {
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    shader = it
                    shaderBitmap = bitmap
                }
            }
            // 与软件管线共用同一个 matrix：Drawable 作为 View background 时 bounds 恒为
            // (0,0,w,h)，RenderNode 录制坐标与之同为局部原点，采样位置因此完全一致；
            // 外层 translate(rect.left, rect.top) 只负责把结果摆到 bounds 起点。
            next.setLocalMatrix(matrix)
            paint.shader = next
            paint.alpha = alpha
            paint.colorFilter = colorFilter

            // beginRecording(width, height) 会隐式把 RenderNode position 设为
            // (0,0,width,height)，无需再 setPosition；滚动导致 matrix 变化才逐帧重录，
            // 静止时 GlassPositionTracker 不触发 invalidate，没有额外开销。
            val recording = renderNode.beginRecording(width, height)
            try {
                // 录整块矩形（不画圆角），圆角由外层 clipPath 裁剪：
                // 模糊核在圆角内侧不会因「内容提前结束」而出现半透明羽化边。
                recording.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            } finally {
                // 即便 drawRect 抛异常也要闭合录制，避免 RenderNode 停留在未结束状态。
                renderNode.endRecording()
            }

            clipPath.reset()
            clipPath.addRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                cornerRadius, cornerRadius, Path.Direction.CW
            )
            val saveCount = canvas.save()
            try {
                // rect 可能不在原点（Drawable bounds 有偏移），平移到对应位置后裁剪、绘制
                canvas.translate(rect.left, rect.top)
                canvas.clipPath(clipPath)
                canvas.drawRenderNode(renderNode)
            } finally {
                // 异常也必须恢复 canvas 状态，否则同帧后续的 tint/shell/描边会错位绘制。
                canvas.restoreToCount(saveCount)
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * 把 [FrostedGlassDrawable] 挂到 host 的布局 / 滚动 / attach 回调上，
 * 用「位置缓存 + 变化才重算」替代 draw() 里的逐帧 getLocationOnScreen。
 * 视图脱离窗口时自动解绑，避免泄漏。
 */
private object GlassPositionTracker {

    private const val TAG_KEY = 0x5F_4753_50
    private const val TAG_KEY_SCROLLER = 0x5F_4753_51

    fun attach(host: View, drawable: FrostedGlassDrawable) {
        // 行复用会反复设置新背景，先把上一次挂接的 tracker 解绑，避免回调累积/泄漏
        (host.getTag(TAG_KEY) as? TrackerBinding)?.detach(host)
        val binding = TrackerBinding(host, drawable)
        host.setTag(TAG_KEY, binding)
        binding.attach()
        if (host.isAttachedToWindow) {
            binding.onViewAttachedToWindow(host)
        }
    }

    private class TrackerBinding(
        private val host: View,
        private val drawable: FrostedGlassDrawable
    ) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private var scrollTarget: View? = null

        fun attach() {
            host.addOnAttachStateChangeListener(this)
            host.addOnLayoutChangeListener(this)
        }

        fun detach(v: View) {
            v.removeOnAttachStateChangeListener(this)
            v.removeOnLayoutChangeListener(this)
            onViewDetachedFromWindow(v)
        }

        override fun onViewAttachedToWindow(v: View) {
            // attach 可能连续发生（复用/重挂）而中间没有走 detach，
            // 先卸载旧滚动目标，避免 RecyclerView 滚动监听重复注册
            scrollTarget?.let { uninstallScrollTarget(it) }
            scrollTarget = nearestScrollableAncestor(v)
            when (val target = scrollTarget) {
                is RecyclerView -> target.addOnScrollListener(scrollListener)
                is NestedScrollView -> {
                    target.setOnScrollChangeListener(scrollChangeListener)
                    // 打标记录「监听是我们装的」，detach 时只清自己的，
                    // 不动别人（如 host 自身业务）设置的监听
                    target.setTag(TAG_KEY_SCROLLER, scrollChangeListener)
                }
            }
            updatePosition(v)
        }

        override fun onViewDetachedFromWindow(v: View) {
            scrollTarget?.let { uninstallScrollTarget(it) }
            scrollTarget = null
        }

        private fun uninstallScrollTarget(target: View) {
            when (target) {
                is RecyclerView -> target.removeOnScrollListener(scrollListener)
                is NestedScrollView -> {
                    if (target.getTag(TAG_KEY_SCROLLER) === scrollChangeListener) {
                        target.setOnScrollChangeListener(null as NestedScrollView.OnScrollChangeListener?)
                        target.setTag(TAG_KEY_SCROLLER, null)
                    }
                }
            }
        }

        override fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        ) {
            updatePosition(v)
        }

        private val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePosition(host)
            }
        }

        private val scrollChangeListener =
            NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                updatePosition(host)
            }

        private fun updatePosition(v: View) {
            val root = v.rootView
            if (v.width <= 0 || v.height <= 0 || root.width <= 0 || root.height <= 0) return
            val location = IntArray(2)
            val rootLocation = IntArray(2)
            v.getLocationOnScreen(location)
            root.getLocationOnScreen(rootLocation)
            drawable.onPositionChanged(location[0], location[1], rootLocation[0], rootLocation[1])
        }
    }

    private fun nearestScrollableAncestor(view: View): View? {
        var parent = view.parent
        while (parent is ViewGroup) {
            when (parent) {
                is RecyclerView, is NestedScrollView -> return parent
            }
            parent = parent.parent
        }
        return null
    }
}
