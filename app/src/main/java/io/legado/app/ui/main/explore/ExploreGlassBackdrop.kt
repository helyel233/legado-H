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
import android.graphics.Outline
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
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.MainThemeBackgroundState
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.utils.ColorUtils as AppColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.stackBlurSoftware
import java.io.File
import kotlin.math.max
import splitties.init.appCtx

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

/** 硬件管线模糊半径（屏幕像素）的上下限：与 `StableLiquidGlassView.setBlurRadius` 逐值一致。 */
private const val HARDWARE_BLUR_MIN_PX = 0.01f
private const val HARDWARE_BLUR_MAX_PX = 50f

/** 细项倍率滑杆范围：100 = 沿用该档预设原值，0 = 关闭该维度，200 = 加倍。 */
internal const val EXPLORE_GLASS_SCALE_MIN = 0
internal const val EXPLORE_GLASS_SCALE_DEFAULT = 100
internal const val EXPLORE_GLASS_SCALE_MAX = 200

/**
 * 展开行小控件「按下」反馈的叠色 alpha：玻璃 drawable 本身没有按下态，
 * 用一层低透明度反色保留可感知的按压反馈（原 `filletControlBackground` 的按下态
 * 会把底色换成接近不透明的颜色，直接叠加会盖住玻璃，因此这里用 0.10 的淡叠色）。
 */
private const val CONTROL_PRESS_SCRIM_ALPHA = 0.10f

/**
 * 三档材质的取值与底栏液态玻璃完全一致（solid/glass/frosted），
 * 参数由 [GlassMaterialPreset] 统一提供，不再用系数去缩放发现页既有观感。
 * 1.25.5 曾把「无玻璃」存成 `none`，这里保留为旧值别名以便平滑升级。
 */
internal const val EXPLORE_GLASS_MODE_SOLID = GlassMaterialPreset.SOLID
internal const val EXPLORE_GLASS_MODE_GLASS = GlassMaterialPreset.GLASS
internal const val EXPLORE_GLASS_MODE_FROSTED = GlassMaterialPreset.FROSTED
internal const val EXPLORE_GLASS_MODE_DEFAULT = EXPLORE_GLASS_MODE_FROSTED
private const val LEGACY_MODE_NONE = "none"

/**
 * 解析生效档位（纯函数，不依赖 Android 运行时，便于单测覆盖迁移）：
 * 1. 显式存的档位优先，旧值 `none` 归一到 `solid`；
 * 2. 从未存过档位时，1.25.4 的旧总开关 `exploreGlassEnabled=false` 迁移成「无玻璃」，
 *    否则回落到默认磨砂玻璃档。
 * 1.25.x 的「跟随底栏材质」开关已移除：升级用户由启动时的一次性迁移
 * [migrateLegacyExploreGlassFollow] 把底栏档位与强度固化为显式值，
 * 渲染路径不再有 follow 参数。
 */
internal fun resolveExploreGlassMode(
    rawMode: String?,
    legacyEnabled: Boolean
): String {
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
 * 「全局毛玻璃效果」里各设置项的一次性快照。
 *
 * 只保留**非材质**的开关：[mode] 决定档位，材质参数（档位强度、四个倍率）由
 * [GlassMaterialPreset.current] 在取用点统一读取，与底栏 / 顶栏完全同一来源。
 * 1.25.11 之前这里还存着 level 与四个 percent，行内再乘一遍（还叠了行级增益），
 * 于是同一档参数下行必然偏离底栏；材质收敛到单一入口后这些字段已无意义。
 */
internal data class ExploreGlassConfig(
    val mode: String,
    val strokeColorRaw: String?,
    val hardwareBlur: Boolean,
    val animatedSolid: Boolean
)

/**
 * 发现页毛玻璃全部偏好键名的**唯一声明处**。
 *
 * [readExploreGlassConfig] 只通过这些成员访问键，设置页恢复默认与即时重绑监听的
 * 键集合都由它派生；新增设置项必须在这里登记，否则读不到也写不进。
 * 纯 JVM 单测用记录型 [ExploreGlassPrefSource] 反向断言「实际读过的键 ⊆ 监听集合」。
 */
internal object ExploreGlassConfigKeys {
    val legacyEnabled = PreferKey.exploreGlassEnabled
    val effectMode = PreferKey.exploreGlassEffectMode
    val levelDay = PreferKey.themeExploreGlassBlur
    val levelNight = PreferKey.themeExploreGlassBlurN
    val blurScale = PreferKey.exploreGlassBlurScale
    val shellScale = PreferKey.exploreGlassShellScale
    val tintScale = PreferKey.exploreGlassTintScale
    val strokeScale = PreferKey.exploreGlassStrokeScale
    val strokeColor = PreferKey.glassStrokeColor
    val hardwareBlur = PreferKey.exploreGlassHardwareBlur
    val animatedSolid = PreferKey.exploreGlassAnimatedSolid
    val frostedLevel = PreferKey.frostedGlassLevel
    val liquidLevel = PreferKey.liquidGlassLevel

    /** 本设置页写入 /「恢复默认」清除的键（含旧总开关、日夜间强度两键与描边色）。 */
    val settingKeys: Set<String> = setOf(
        legacyEnabled, effectMode,
        levelDay, levelNight,
        blurScale, shellScale, tintScale, strokeScale,
        strokeColor,
        hardwareBlur, animatedSolid
    )

    /**
     * 底栏联动键：两个强度由 [GlassMaterialPreset.current] 读。
     * 旧 bottomBarEffectMode 已废弃删除（档位统一读 [effectMode]），不再监听。
     */
    val bottomBarKeys: Set<String> = setOf(frostedLevel, liquidLevel)
}

/**
 * 设置页写入 / 清除的键，是 [EXPLORE_GLASS_PREF_KEYS] 的来源之一。
 * 与渲染层读取键共用 [ExploreGlassConfigKeys] 的唯一声明，两处清单不再各自手写。
 */
internal val EXPLORE_GLASS_SETTING_KEYS: Set<String> = ExploreGlassConfigKeys.settingKeys

/** 底栏联动键，读取发生在 [readExploreGlassConfig] 之外，显式补进监听集合。 */
internal val EXPLORE_GLASS_BOTTOM_BAR_KEYS: Set<String> = ExploreGlassConfigKeys.bottomBarKeys

/**
 * 偏好读取的最小抽象：[readExploreGlassConfig] 的每个键访问都经过它，
 * 纯 JVM 单测可注入记录型实现，反查渲染层究竟读了哪些键。
 */
internal interface ExploreGlassPrefSource {
    fun boolean(key: String, default: Boolean): Boolean
    fun int(key: String, default: Int): Int
    fun string(key: String): String?
}

private class ContextExploreGlassPrefSource(
    private val context: Context
) : ExploreGlassPrefSource {
    override fun boolean(key: String, default: Boolean): Boolean =
        context.getPrefBoolean(key, default)

    override fun int(key: String, default: Int): Int =
        context.getPrefInt(key, default)

    override fun string(key: String): String? =
        context.getPrefString(key)
}
/** 集中读取一次偏好，避免每行 bind 时散落多次 SharedPreferences 访问。 */
internal fun readExploreGlassConfig(context: Context): ExploreGlassConfig =
    readExploreGlassConfig(ContextExploreGlassPrefSource(context))

/**
 * 纯 JVM 可测的读取逻辑。所有键名取自 [ExploreGlassConfigKeys]，单测据此反向校验
 * 键集合，新增键漏登记会立刻暴露。材质键（两个强度 + 四个倍率）不在这里读取：
 * 它们由 [GlassMaterialPreset.current] 统一读取，行 / 底栏 / 顶栏因此不可能读到不同值。
 */
internal fun readExploreGlassConfig(source: ExploreGlassPrefSource): ExploreGlassConfig {
    val keys = ExploreGlassConfigKeys
    val legacyEnabled = source.boolean(keys.legacyEnabled, true)
    return ExploreGlassConfig(
        mode = resolveExploreGlassMode(
            source.string(keys.effectMode),
            legacyEnabled
        ),
        strokeColorRaw = source.string(keys.strokeColor),
        hardwareBlur = source.boolean(keys.hardwareBlur, true),
        animatedSolid = source.boolean(keys.animatedSolid, true)
    )
}

/**
 * 迁移期间偏好读写的最小抽象：让一次性迁移逻辑可注入内存实现、纯 JVM 单测覆盖。
 */
internal interface ExploreGlassPrefStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String): String?
    fun putBoolean(key: String, value: Boolean)
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun remove(key: String)
}

private class ContextExploreGlassPrefStore(
    private val context: Context
) : ExploreGlassPrefStore {
    override fun getBoolean(key: String, default: Boolean): Boolean =
        context.getPrefBoolean(key, default)

    override fun getString(key: String): String? =
        context.getPrefString(key)

    override fun putBoolean(key: String, value: Boolean) =
        context.putPrefBoolean(key, value)

    override fun putString(key: String, value: String) =
        context.putPrefString(key, value)

    override fun putInt(key: String, value: Int) =
        context.putPrefInt(key, value)

    override fun remove(key: String) = context.removePref(key)
}

/**
 * 1.25.x「跟随底栏材质」开关的一次性迁移核心（纯 JVM 可测，[migrateLegacyExploreGlassFollow]
 * 是其 Context 包装）。规则（幂等）：
 * 1. 迁移标记 [PreferKey.exploreGlassFollowMigrated] 已存在 → 直接返回；
 * 2. 旧键 [PreferKey.exploreGlassFollowBottomBar] 为 false / 缺失 → 不动任何值；
 * 3. 为 true → 把底栏当前状态**固化为显式档位 + 强度**（双固化）：
 *    - 档位：`exploreGlassEffectMode = [bottomBarMode]`（校验 ∈ 预设档位，非法回退默认 frosted）；
 *    - 强度：`themeExploreGlassBlur` 与 `themeExploreGlassBlurN` 同时写为底栏对应强度
 *      （frosted → [frostedLevel]，否则 [liquidLevel]）。跟随期间这两个页面强度键
 *      本来就不生效，覆写不会丢失任何用户可见状态；
 * 4. 移除旧键并写迁移标记。
 */
internal fun migrateExploreGlassFollowLocked(
    store: ExploreGlassPrefStore,
    bottomBarMode: String,
    frostedLevel: Int,
    liquidLevel: Int
) {
    if (store.getBoolean(PreferKey.exploreGlassFollowMigrated, false)) return
    val followEnabled = store.getBoolean(PreferKey.exploreGlassFollowBottomBar, false)
    if (followEnabled) {
        val resolvedMode = bottomBarMode.takeIf { it in GlassMaterialPreset.modes }
            ?: EXPLORE_GLASS_MODE_DEFAULT
        store.putString(PreferKey.exploreGlassEffectMode, resolvedMode)
        val level = if (resolvedMode == EXPLORE_GLASS_MODE_FROSTED) frostedLevel else liquidLevel
        store.putInt(PreferKey.themeExploreGlassBlur, level)
        store.putInt(PreferKey.themeExploreGlassBlurN, level)
    }
    store.remove(PreferKey.exploreGlassFollowBottomBar)
    store.putBoolean(PreferKey.exploreGlassFollowMigrated, true)
}

/**
 * 1.25.8 档位键统一的一次性迁移核心（纯 JVM 可测，幂等）：
 * 1. 迁移标记 [PreferKey.exploreGlassModeUnifiedMigrated] 已存在 → 直接返回；
 * 2. 全局档位键从未写过、而废弃键 `bottomBarEffectMode` 写过 →
 *    把旧档位固化为显式全局档位（非法值经 [resolveExploreGlassMode] 回退默认档），
 *    保证「改过底栏材质」的老用户升级后底栏观感不跳变；
 * 3. 写迁移标记。已写过全局档位（含上一轮跟随迁移固化）的用户不动任何值。
 */
internal fun migrateLegacyBottomBarEffectModeLocked(store: ExploreGlassPrefStore) {
    if (store.getBoolean(PreferKey.exploreGlassModeUnifiedMigrated, false)) return
    val explicitExploreMode = store.getString(PreferKey.exploreGlassEffectMode)
    val legacyBottomBarMode = store.getString(PreferKey.bottomBarEffectMode)
    if (explicitExploreMode.isNullOrBlank() && !legacyBottomBarMode.isNullOrBlank()) {
        store.putString(
            PreferKey.exploreGlassEffectMode,
            resolveExploreGlassMode(legacyBottomBarMode, true)
        )
    }
    store.putBoolean(PreferKey.exploreGlassModeUnifiedMigrated, true)
}

/**
 * 「跟随底栏材质」旧键的一次性迁移入口（由 MainActivity 启动时调用一次，早于任何 UI）。
 * 底栏当前档位与强度来自全局偏好；档位非法值由 [migrateExploreGlassFollowLocked] 回退。
 * 同时执行 1.25.8 的档位键统一迁移 [migrateLegacyBottomBarEffectModeLocked]。
 */
internal fun migrateLegacyExploreGlassFollow(context: Context) {
    migrateExploreGlassFollowLocked(
        ContextExploreGlassPrefStore(context),
        bottomBarMode = AppConfig.bottomBarEffectMode,
        frostedLevel = AppConfig.frostedGlassLevel,
        liquidLevel = AppConfig.liquidGlassLevel
    )
    migrateLegacyBottomBarEffectModeLocked(ContextExploreGlassPrefStore(context))
}

/**
 * 全局生效档位的**单一读取入口**：底栏 / 迁移等所有非 Compose 读取方都通过它取档位，
 * 与设置页、发现页、顶栏的 resolveExploreGlassMode 语义逐字一致
 * （显式档位 > 旧总开关 false → solid > 默认磨砂）。
 */
fun resolvedExploreGlassMode(): String = resolveExploreGlassMode(
    appCtx.getPrefString(PreferKey.exploreGlassEffectMode),
    appCtx.getPrefBoolean(PreferKey.exploreGlassEnabled, true)
)

/**
 * 全局描边色的解析（纯函数）：偏好存 ARGB 十进制字符串，空 / 未设置 / 脏值一律
 * 返回 null = 不描边（沿用底栏包废弃字段 borderColor 的可空语义）。
 */
internal fun resolveGlassStrokeColor(raw: String?): Int? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

/**
 * 硬件高斯半径的纯函数换算：输入是该档预设给出的 blurDp（已含强度与模糊倍率，
 * 由 [GlassMaterialPreset.current] 统一算好），乘屏幕密度得屏幕像素，
 * 再限幅到 `StableLiquidGlassView.setBlurRadius` 的同一段 [0.01, 50] px。
 * **不乘**软件管线的 1.4 等效系数——那个系数是把 GPU 高斯折算成 stackBlur 半径用的。
 */
internal fun exploreHardwareBlurPx(
    blurDp: Float,
    density: Float
): Float = (blurDp * density).coerceIn(HARDWARE_BLUR_MIN_PX, HARDWARE_BLUR_MAX_PX)

/**
 * 发现页「无玻璃」档实底壳层是否强制完全不透明（纯 JVM 决策，可单测）。
 *
 * 底栏 solid 档不经过这个决策，仍按图标包 `config.opacity` 半透明；只有发现页
 * 书源名行要求一块不透壁纸的纯色块。把这个布尔值抽成具名决策点，是为了让
 * [exploreSolidShellColor] 与单测都能锁住它——将来若改成 false，实底口径单测立刻失败。
 */
internal fun exploreSolidShellForcesOpaque(): Boolean = true

/**
 * 发现页无玻璃档的最终填充色（纯 JVM，无 Context / Drawable / 资源依赖）。
 *
 * 把上述决策、图标包 opacity 与主题底色一次性换算成 ARGB。
 * [ExploreGlassBackdrop.solidShellDrawable] 只把这个结果交给
 * [GlassMaterialPreset.createSolidShellDrawable] 的填充色重载做薄映射，
 * 不再在 Android 侧自己决定不透明度；删掉或绕过这个决策都会让单测失败。
 */
internal fun exploreSolidShellColor(baseColor: Int, iconOpacity: Int): Int =
    GlassMaterialPreset.solidColor(
        baseColor, iconOpacity, exploreSolidShellForcesOpaque()
    )

/**
 * 发现页无玻璃档实底壳层的**全部构造参数**（纯 JVM 可测）。
 *
 * [ExploreGlassBackdrop.solidShellDrawable] 只收集 Android 侧参数（主题底色、图标包
 * 描边、圆角、描边宽度），经 [withExploreSolidShellSpec] 拿到这份规格后逐字段交给
 * [GlassMaterialPreset.createSolidShellDrawable]。颜色决策完全收敛到纯函数，Android
 * 薄映射里不再有任何可漏传或可改写的「是否强制不透明」实参。
 */
internal data class ExploreSolidShellSpec(
    val fillColor: Int,
    val borderColor: Int?,
    val cornerRadiusPx: Float,
    val oval: Boolean,
    val strokeWidthPx: Int
)

/** 纯 JVM：把主题底色与图标包 opacity 换算成强制不透明的完整构造规格。 */
internal fun resolveExploreSolidShellSpec(
    baseColor: Int,
    iconOpacity: Int,
    borderColor: Int?,
    cornerRadiusPx: Float,
    strokeWidthPx: Int
): ExploreSolidShellSpec = ExploreSolidShellSpec(
    fillColor = exploreSolidShellColor(baseColor, iconOpacity),
    borderColor = borderColor,
    cornerRadiusPx = cornerRadiusPx,
    oval = false,
    strokeWidthPx = strokeWidthPx
)

/**
 * 生产与单测共用的唯一出口：算出规格后交给 [factory]。
 * 生产侧 factory 构造 GradientDrawable；纯 JVM 单测注入 `{ it }` 直接拿回规格，
 * 从而锁住「发现页实底最终传给构造实现的是 alpha=255 的填充色」这条实参链路。
 */
internal fun <T> withExploreSolidShellSpec(
    baseColor: Int,
    iconOpacity: Int,
    borderColor: Int?,
    cornerRadiusPx: Float,
    strokeWidthPx: Int,
    factory: (ExploreSolidShellSpec) -> T
): T = factory(
    resolveExploreSolidShellSpec(
        baseColor, iconOpacity, borderColor, cornerRadiusPx, strokeWidthPx
    )
)

/**
 * 影响发现页行观感的偏好键全集：由 [EXPLORE_GLASS_SETTING_KEYS]（渲染层读取、
 * 设置页写入的键）加上 [EXPLORE_GLASS_BOTTOM_BAR_KEYS]（[GlassMaterialPreset] 另读的
 * 两个强度键）派生，不再手写第二份清单，新增键漏加监听的路径被单测反向断言堵住。
 *
 * `ExploreFragment` 用它注册即时重绑监听：只靠 `onResume` +
 * [ExploreGlassBackdrop.configSignature] 时，设置页在另一个 Activity、发现页又处于
 * STARTED（非 RESUMED）状态，改动可能无法在返回时重绑；偏好一变就直接刷新经典行背景。
 */
internal val EXPLORE_GLASS_PREF_KEYS: Set<String> =
    EXPLORE_GLASS_SETTING_KEYS + EXPLORE_GLASS_BOTTOM_BAR_KEYS

/**
 * 发现页书源名行的毛玻璃背景。
 *
 * 取主界面背景图层（与主题背景同一文件/裁剪/模糊设置）生成一张按屏幕对齐的降采样底图，
 * 行背景按行在屏幕上的实时位置采样这张底图，滚动时随位置重新采样，
 * 得到「透出背后壁纸并磨砂」的玻璃观感，避免书源名与壁纸内容直接重合。
 *
 * 1.25.10 起再增加**第三条、优先级最高**的管线（方案 A2，仅 API 33+）：
 * [RowGlassLiquidRenderer] 用底栏那颗同一份 AGSL（MIT，已复制到
 * `res/raw/glass_row_liquid.agsl`）在**未预模糊的 raw 位图**上做真折射 + 色散，
 * 并由 GPU 高斯完成模糊（`createChainEffect`，与底栏库同一顺序）。
 * 它**不复用底层库的 Java 宿主**、不 `bind` 任何视图、不对任何 `View` 调
 * `beginRecording` + `View.draw()`；录制内容永远只有我们自己画的一条 `drawRect`，
 * 因此 1.25.8 的 RenderNode 重入录制崩溃（native abort）在本路径上结构性不存在。
 * 材质、壳层、描边、色散与折射**全部**直接消费 [GlassMaterialPreset.current] 的同一个
 * [GlassMaterialPreset.Params]：底栏、顶栏、行三处读同一组键、走同一个纯函数，
 * 因此同档参数在构造上必然一致。行内不再有 tint / 壳层 / 高光增益，也没有只在行上
 * 叠加的拟液态层——那些层会让同一档参数下行比底栏更白、更厚，正是要修的偏差。
 * 该层失败或被降级时，下面两条既有管线**原样接管**，观感连续、不留空白帧。
 *
 * 模糊有两条管线，按设备能力二选一（②③ 的软件管线始终保留，是硬件管线的降级基线）：
 * - **硬件管线（API 31+）**：底图不做软件模糊，改由 [HardwareGlassLayer] 把「玻璃层」
 *   录进一个独立 [RenderNode]，对其设置 [RenderEffect.createBlurEffect] 交给 GPU 做真高斯。
 *   RenderEffect 只作用于该 RenderNode 自身的绘制，不影响 tint/shell/描边，更不会影响
 *   同层的书源名文字，因此不需要把整行 View 一起模糊。
 * - **软件管线（API 21-30 / 降级）**：底图在 IO 线程经 [stackBlurSoftware] 预模糊一次，
 *   行绘制用 `BitmapShader` 采样，即 ②③ 已实测验证的既有实现。软件模糊只由该档
 *   `blurDp × 模糊倍率` 决定，不再叠加主题背景的模糊偏好——生产背景层对壁纸根本
 *   不做模糊（该偏好只关闭动图），把 `state.blur` 计进半径等于凭空多糊一层。
 *
 * 两条管线共用同一份底图构建（decode + crop 对齐 + fallbackColor）、同一套位置缓存
 * （[GlassPositionTracker]）与同一套 shell/tint/描边参数，保证降级时观感连续。
 *
 * 材质三档（自动预设，与底栏液态玻璃完全一致，见 [GlassMaterialPreset]）：
 * - **无玻璃 [EXPLORE_GLASS_MODE_SOLID]**：由纯函数 [exploreSolidShellColor] 把
 *   [GlassMaterialPreset.createSolidShellDrawable] 填充色重载的入参钉成 alpha=255，
 *   得到贴主题底色、完全不透明的纯色块；不受图标包 opacity、玻璃强度、墨水屏/低内存
 *   降级影响，也不再透出壁纸。实底描边仍只读全局设置，行不额外兜边
 *   （兜边只用于浮在行之上的底栏，见 `GlassMaterialPreset.solidShellStroke`）；
 * - **透明玻璃 [EXPLORE_GLASS_MODE_GLASS]**：取 glass 档整组参数（`3 + l*6`dp 轻模糊、
 *   `0.004 + l*0.02` 近乎无色着色、更强色散、更深折射），行上表现为通透可见内容的玻璃；
 * - **磨砂玻璃 [EXPLORE_GLASS_MODE_FROSTED]**：取 frosted 档整组参数（`20 + l*24`dp 强模糊、
 *   `0.18 + l*0.20` 奶白着色、色散收敛、折射更浅），行上表现为糊成色块的奶白雾面。
 * 两档差异同时落在模糊 / 着色 / 色散 / 折射几何四条可见维度上，而不是同一观感的深浅版本；
 * 三档取值只有 [GlassMaterialPreset] 一份来源，行内不做任何再标定。
 * 1.25.x 的「跟随底栏材质」开关已移除（启动时一次性迁移固化档位与强度）。
 *
 * 降级矩阵（任一条命中都不留空白帧，也不退回未模糊的 raw）：
 * - 无玻璃档：完全不透明纯色块（不受玻璃强度 / 墨水屏 / 低内存影响）；
 * - 墨水屏 / 低内存：返回 null，用原透明背景；
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
 * 参数来源：档位、两个强度键与四个倍率全部由 [GlassMaterialPreset.current] 读取；
 * 本文件只读档位、描边色、硬件模糊与动图开关这四个非材质键。[EXPLORE_GLASS_PREF_KEYS]
 * 汇总所有影响观感的键，供 ExploreFragment 注册即时重绑监听，与 [configSignature]
 * 的 onResume 兜底一起保证「改完返回发现页即生效」。
 */
object ExploreGlassBackdrop {

    /**
     * 软件管线 `stackBlurSoftware` 的位图像素半径（纯函数，便于单测）。
     * 输入是 [GlassMaterialPreset.Params.blurDp]（已含档位与模糊倍率），与硬件 / 液态
     * 管线读同一个值。主题背景的模糊偏好**不参与**：生产背景层对壁纸不做模糊（该偏好
     * 只关闭动图播放），把它计进半径会让软件管线凭空多糊一层，与底栏观感不一致。
     * blurDp = 0（模糊倍率 0）时返回 0 = 不模糊，与硬件/液态管线传 0 的语义一致。
     */
    fun exploreSoftwareBlurRadius(
        blurDp: Float,
        density: Float
    ): Int {
        if (blurDp <= 0f) return 0
        return (blurDp * density * STACK_BLUR_DENSITY_FACTOR / DOWNSCALE)
            .toInt().coerceIn(1, 25)
    }

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

    /**
     * 未模糊底图缓存（键只含壁纸/裁剪/夜间/目标尺寸/fallbackColor）。
     * 与 [cache] 分开：调整模糊倍率时只需复用 raw 重新做一次软件模糊，
     * 不必重新 decode + 裁剪；设置页预览拖动模糊滑杆时差别最明显。
     */
    private data class RawCache(
        val key: String,
        val raw: Bitmap
    )

    @Volatile
    private var cache: BackdropCache? = null

    @Volatile
    private var rawCache: RawCache? = null

    //按 key 去重的在途构建任务与等待回填的 drawable，避免多行同时 miss 重复构建
    private val inFlightKeys = HashSet<String>()
    private val pendingCallbacks = HashMap<String, ArrayList<(GlassBackdrop?) -> Unit>>()

    /**
     * 配置签名：任何影响观感的设置项变化都会改变它。
     * ExploreFragment 在 onResume 时用它做兜底重绑（另有 [EXPLORE_GLASS_PREF_KEYS]
     * 的即时偏好监听），因此新增细项后不必再逐个加监听——只要参数进快照，
     * 返回发现页即生效。材质签名覆盖档位、两个强度与四个倍率（[GlassMaterialPreset]
     * 的唯一入口），非材质键单独拼上；描边色 raw 值纳入签名（颜色变化需重绑行背景）。
     */
    fun configSignature(context: Context): String {
        val config = readExploreGlassConfig(context)
        return listOf(
            config.mode,
            GlassMaterialPreset.signature(GlassMaterialPreset.current(config.mode)),
            config.strokeColorRaw.orEmpty(),
            config.hardwareBlur,
            config.animatedSolid,
            AppConfig.isNightTheme
        ).joinToString("|")
    }

    /** 屏幕对齐的降采样壁纸（未模糊）；设置页预览用它铺底，与行玻璃采样同一份底图。 */
    fun previewWallpaper(context: Context, onReady: (Bitmap?) -> Unit): Bitmap? {
        val config = readExploreGlassConfig(context)
        val material = GlassMaterialPreset.current(config.mode)
        return backdrop(context, config, material) { onReady(it?.raw) }?.raw
    }

    /**
     * 低内存设备判定（设备级事实，非偏好）。
     *
     * 公开给 [io.legado.app.ui.glass.GlassSurface.isAvailable]：Compose 桥需要在组合期
     * 提前决定「画玻璃层还是回退原背景」，必须与 [glassDrawable] 的降级判定同源，
     * 不能各写一份阈值。
     */
    fun isLowRamDevice(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /**
     * 无玻璃档：完全不透明的纯色块（贴主题底色），不再是半透明壳层。
     *
     * 1.25.5 把图标包 `config.opacity`（内置默认 76、Config 默认 72）直接传给
     * [GlassMaterialPreset.createSolidShellDrawable]，经 `solidOpacity` 得到
     * 0.72~0.76 的 alpha，行背景仍然透出壁纸——这正是真机反馈的「无玻璃没变成
     * 不透明纯色块」。这里只收集 Android 侧参数，颜色与全部构造规格统一由
     * [withExploreSolidShellSpec] 产出（填充色 alpha 钉死为 255），函数体只做
     * 字段展开交给填充色重载；底栏仍走原 `solidOpacity`，观感逐值不变。
     * 底色、圆角与描边依旧复用同一实现，不另写一份 drawable。
     * 描边色自 1.25.8 起统一读全局 [PreferKey.glassStrokeColor]（未设置 = 不描边）。
     * 行不参与底栏的兜边：行直接贴在壁纸上，同色底不会让轮廓消失；
     * 只有浮在行之上的底栏才需要 `GlassMaterialPreset.solidShellStroke` 兜边。
     */
    private fun solidShellDrawable(
        context: Context,
        cornerRadiusPx: Float = context.resources.getDimension(R.dimen.ui_panel_radius)
    ): GradientDrawable {
        val navConfig = NavigationBarIconConfig.currentEntry(AppConfig.isNightTheme).config
        return withExploreSolidShellSpec(
            baseColor = ThemeStore.bottomBackground(context),
            iconOpacity = navConfig.opacity,
            borderColor = glassStrokeColor(context),
            cornerRadiusPx = cornerRadiusPx,
            strokeWidthPx = 1.dpToPx()
        ) { spec ->
            GlassMaterialPreset.createSolidShellDrawable(
                fillColor = spec.fillColor,
                borderColor = spec.borderColor,
                cornerRadiusPx = spec.cornerRadiusPx,
                oval = spec.oval,
                strokeWidthPx = spec.strokeWidthPx
            )
        }
    }

    /** 全局描边色（ARGB，未设置 / 脏值为 null = 不描边）。 */
    fun glassStrokeColor(context: Context): Int? =
        resolveGlassStrokeColor(context.getPrefString(PreferKey.glassStrokeColor))

    /**
     * 底栏 / 面板玻璃背景（选择模式底栏等与主底栏同语义的表面）。
     *
     * 与书源名行是**同一份** drawable 与材质：圆角同为 `ui_panel_radius`（主底栏
     * `bg_liquid_glass_shell_overlay` 的圆角逐值一致），四档降级与设备判定全部由
     * [glassDrawable] 决定，这里只负责把用途命名清楚。
     * 返回 null 表示设备降级（墨水屏 / 低内存 / 动图壁纸纯色底），调用方回退实色。
     *
     * 跨包（非发现页）宿主请改走 [io.legado.app.ui.glass.GlassSurface.panelBackground]：
     * 那里要求显式声明采样域，面板域 / 内容域直接返回 null，从结构上阻止
     * 「在不透明面板里采样壁纸」的错误复用。
     */
    fun panelBackground(host: View): Drawable? =
        panelBackground(host, host.resources.getDimension(R.dimen.ui_panel_radius))

    /**
     * 与 [panelBackground] 同一份产物，只允许自定义圆角。
     *
     * Compose 桥（[io.legado.app.ui.glass.GlassPanel]）的宿主是一个独立 View，圆角由调用方
     * 传入（如设置页卡片的 `UiCorner.panelRadius` 缩放值），不能读死 `ui_panel_radius`。
     * 除圆角外没有任何参数分支：材质、降级、采样域仍由 [glassDrawable] 单点决定。
     *
     * [screenAligned] 供独立窗口（对话框）使用：本管线按「壁纸位图对齐到宿主根视图」
     * 建立采样矩阵，而对话框窗口的根视图只有面板那么大，照常换算会把整张壁纸
     * 缩进面板里。置 true 时改用屏幕尺寸与屏幕坐标，与壁纸位图的构建口径一致。
     */
    fun panelBackground(
        host: View,
        cornerRadiusPx: Float,
        screenAligned: Boolean = false
    ): Drawable? = glassDrawable(host, cornerRadiusPx, screenAligned)

    /** 返回毛玻璃行背景；设备降级时返回 null，调用方保留默认透明背景。 */
    fun rowBackground(host: View): Drawable? = panelBackground(host)

    /**
     * 设置页预览专用：同一份行玻璃 drawable，只允许自定义圆角。
     * 底栏药丸预览传 `height / 2`，与书源名行并排即可用肉眼核对「同参数是否同观感」。
     */
    fun previewBackground(host: View, cornerRadiusPx: Float): Drawable? =
        glassDrawable(host, cornerRadiusPx)

    /**
     * 设置页预览专用：Compose 的 LazyColumn 滚动不会触发子 View 的布局回调，
     * 预览容器在自身屏幕坐标变化后主动调这里，让玻璃层按新位置重采样。
     * 未挂接玻璃（无玻璃档 / 设备降级）时是 no-op。
     */
    fun refreshPreviewPosition(host: View) {
        GlassPositionTracker.refresh(host)
    }

    /**
     * 展开行内部小控件（线路 / 类型 / 平台 / 搜索按钮等）的玻璃背景。
     *
     * 与行标题、底栏走**同一条**链路：档位、强度、四个倍率、tint / shell / 描边全部来自
     * [GlassMaterialPreset.current]，四级降级（液态 AGSL / 硬件高斯 / 软件预模糊 / 实底）
     * 也由 [glassDrawable] 按同一套判定决定。上一版这里刻意只走静态管线以换取
     * 展开性能，真机对比下控件与行标题的观感差异是肉眼可见的（暗磨砂 vs 折射亮边），
     * 因此按「观感一致优先」改回同一条管线：每个控件尺寸远小于整行，单帧高斯面积很小，
     * 且液态层惰性创建（首次绘制才编译 AGSL），不会在绑定阶段集中付出开销。
     * 圆角取主题 `actionRadius`，与 [filletControlBackground] 的控件圆角一致。
     *
     * 玻璃 drawable 本身没有按下态，这里用一层低透明度反色叠在玻璃上方保留按压反馈；
     * 设备降级（墨水屏 / 低内存）返回 null，调用方回退 [filletControlBackground]。
     */
    fun controlBackground(host: View): Drawable? {
        val context = host.context
        val radius = UiCorner.actionRadius(context)
        val glass = glassDrawable(host, radius) ?: return null
        val pressScrim = AppColorUtils.withAlpha(
            if (AppColorUtils.isColorLight(ThemeStore.bottomBackground(context))) {
                Color.BLACK
            } else {
                Color.WHITE
            },
            CONTROL_PRESS_SCRIM_ALPHA
        )
        val pressed = LayerDrawable(
            arrayOf(
                glass,
                GradientDrawable().apply {
                    setColor(pressScrim)
                    cornerRadius = radius
                }
            )
        )
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), glass)
        }
    }

    private fun glassDrawable(
        host: View,
        cornerRadiusPx: Float,
        screenAligned: Boolean = false
    ): Drawable? {
        val context = host.context
        val config = readExploreGlassConfig(context)
        // 无玻璃档是纯实底，没有模糊/位图采样开销，墨水屏与低内存设备也必须
        // 保持「不透明纯色块」（放在设备降级判定之前）。
        // 所有非玻璃分支都主动解绑上一份 tracker，避免切档后监听与旧 drawable 滞留。
        if (config.mode == EXPLORE_GLASS_MODE_SOLID) {
            GlassPositionTracker.detach(host)
            return solidShellDrawable(context, cornerRadiusPx)
        }
        // 墨水屏 / 低内存设备自动降级为关闭，牺牲少量视觉换取滚动流畅（架构 T03 验收项）
        if (AppConfig.isEInkMode || isLowRamDevice(context)) {
            GlassPositionTracker.detach(host)
            return null
        }
        // 材质的唯一入口：档位、两个强度键、四个倍率、API<33 壳层补偿都在这里读，
        // 底栏 / 顶栏 / 行拿到的是同一个函数产物，行内不再做任何二次缩放。
        val material = GlassMaterialPreset.current(config.mode)
        val useHardware = supportsHardwareBlur() && config.hardwareBlur
        val density = context.resources.displayMetrics.density
        val blurPx = exploreHardwareBlurPx(material.blurDp, density)
        return FrostedGlassDrawable(
            host = host,
            radiusPx = cornerRadiusPx,
            baseColor = ThemeStore.bottomBackground(context),
            material = material,
            strokeColor = glassStrokeColor(context),
            useHardware = useHardware,
            blurPx = if (useHardware) blurPx else 0f,
            screenAligned = screenAligned,
            // 设备与偏好层面判定一次；「当帧」的三个事实（硬件加速 Canvas / 底图可用 /
            // 持久故障位）交给 rowLiquidDrawableNow 在 draw() 里逐帧判定，
            // 故障位传**进程级**那一位：AGSL 一旦失败过，之后重建的行不再建液态层。
            useLiquid = rowLiquidCapability(
                sdkInt = Build.VERSION.SDK_INT,
                rowLiquidEnabled = ROW_LIQUID_ENABLED,
                hardwareBlurPref = config.hardwareBlur,
                isEInk = false,
                isLowRam = false,
                canvasHardwareAccelerated = true,
                backdropAvailable = true,
                liquidFailed = RowLiquidRuntimeState.shaderFailed
            ) == RowLiquidCapability.LIQUID
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
        // 动图壁纸（gif/webp）由背景层 ComposeThemeImage 逐帧播放，而解码只能取首帧：
        // 若照常采样会产生「壁纸在动、玻璃静止」的错乱。默认返回 null 走纯 shell 实底；
        // 设置页关掉「动图壁纸使用纯色底」后，用户可接受静止首帧玻璃，此时照常构建。
        if (state.file != null && state.animated && config.animatedSolid) return null
        val metrics = context.resources.displayMetrics
        val targetW = max(64, (metrics.widthPixels / DOWNSCALE).toInt())
        val targetH = max(64, (metrics.heightPixels / DOWNSCALE).toInt())
        val file = state.file
        // rawKey 只含「底图内容」：壁纸文件 / 裁剪 / 夜间 / 目标尺寸 / 兜底色。
        // 模糊半径单独拼进 blurKey——调模糊倍率时复用 raw 只重做一次软件模糊，
        // 不重新 decode + 裁剪（设置页预览拖动模糊滑杆的流畅度取决于此）。
        val rawKey = listOf(
            file?.absolutePath.orEmpty(),
            file?.length() ?: 0L,
            file?.lastModified() ?: 0L,
            state.crop?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
            AppConfig.isNightTheme,
            targetW,
            targetH,
            state.fallbackColor
        ).joinToString("|")
        val radius = exploreSoftwareBlurRadius(material.blurDp, metrics.density)
        val blurKey = "$rawKey|$radius"
        synchronized(this) {
            cache?.takeIf { it.key == blurKey }?.let { return it.backdrop }
        }
        synchronized(this) {
            val stale = cache?.backdrop
            pendingCallbacks.getOrPut(blurKey) { ArrayList() }.add(onReady)
            if (inFlightKeys.add(blurKey)) {
                // 构建放 IO；旧缓存位图不显式 recycle（仍可能被各行 drawable 引用，
                // 显式回收会在重绘时抛 "trying to use a recycled bitmap"），交给 GC 回收
                val reusableRaw = rawCache?.takeIf { it.key == rawKey }?.raw
                Coroutine.async(Coroutine.defaultScope) {
                    buildBackdrop(
                        file, state.fallbackColor, state.crop, targetW, targetH,
                        rawKey, radius, reusableRaw
                    )
                }.onSuccess { backdrop ->
                    finishBackdropBuild(blurKey, backdrop)
                }.onError {
                    finishBackdropBuild(blurKey, null)
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

    private fun buildBackdrop(
        file: File?,
        fallbackColor: Int,
        crop: ComposeThemeImageCrop?,
        targetW: Int,
        targetH: Int,
        rawKey: String,
        radius: Int,
        reusableRaw: Bitmap?
    ): GlassBackdrop? {
        val raw = reusableRaw?.takeIf { !it.isRecycled } ?: decodeRaw(
            file, fallbackColor, crop, targetW, targetH
        )
        if (raw !== reusableRaw) {
            rawCache = RawCache(rawKey, raw)
        }
        // raw 供硬件管线与液态管线，模糊由 GPU 完成，绝不能预先软件模糊（否则双重叠加）；
        // blurred 供软件管线与硬件失败时的即时降级。radius = 0 表示模糊倍率为 0：
        // 直接复用 raw（同一实例），此时「不模糊」是用户明确的选择，不是降级兜底。
        val blurred = if (radius <= 0) raw else runCatching {
            raw.stackBlurSoftware(radius)
        }.getOrNull()
        return GlassBackdrop(raw = raw, blurred = blurred)
    }

    /** 解码并按屏幕对齐构建未模糊底图（纯 IO，无 Android 主线程状态）。 */
    private fun decodeRaw(
        file: File?,
        fallbackColor: Int,
        crop: ComposeThemeImageCrop?,
        targetW: Int,
        targetH: Int
    ): Bitmap {
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
        return result
    }
}

/**
 * 单个书源名行的毛玻璃背景，与底栏（StableLiquidGlassView + shell overlay）同一套材质：
 * 0. 液态层（API 33+）：[RowGlassLiquidRenderer] 的 AGSL 真折射 + 色散 + GPU 高斯，
 *    采样源是未预模糊的 raw 壁纸位图，tint 由着色器负责——与底栏的
 *    「着色器 → 高斯 → shell → 描边」逐层一致；不可用时下面 1 的三条既有分支原样接管；
 * 1. 玻璃层：按行当前屏幕位置采样模糊壁纸，半径取 [GlassMaterialPreset.Params.blurDp]
 *    （硬件管线由 GPU 出真高斯，降级时用 IO 线程预模糊的位图）；
 * 2. tint 层（仅静态降级）：液态路径的 tint 由着色器负责，静态路径没有着色器，
 *    因此单画一层白色弱着色，alpha 取同一份 [GlassMaterialPreset.Params.tintAlpha]；
 * 3. shell 层（两条路径都画）：底栏 createLiquidGlassShellDrawable 同款三色垂直渐变；
 * 4. 描边层：全局 glassStrokeColor 或 surfaceColor × `Params.shell.stroke`。
 *
 * 所有可变量都只有一份来源——[GlassMaterialPreset.current] 的 [GlassMaterialPreset.Params]。
 * 行内没有增益、没有由色散派生的 rim 高光、也没有拟液态叠加层：底栏没有这些层，
 * 行多画一层就必然比底栏更白/更厚。绘制层只负责把同一份材质画出来。
 */
private class FrostedGlassDrawable(
    private val host: View,
    private val radiusPx: Float,
    baseColor: Int,
    private val material: GlassMaterialPreset.Params,
    /** 全局描边色（非空时覆盖默认的 surfaceColor × strokeAlpha 描边）。 */
    private val strokeColor: Int?,
    private val useHardware: Boolean,
    /** 硬件 / 液态管线的高斯 sigma（像素）；0 = 模糊倍率 0，不额外模糊。 */
    private val blurPx: Float,
    /** 是否尝试行内液态玻璃（A2）；为 false 时只走静态管线。 */
    useLiquid: Boolean,
    /**
     * 采样根是否取屏幕而非宿主根视图。对话框是独立窗口，根视图只有面板大小，
     * 按根视图换算会把整张壁纸缩进面板；置 true 后与壁纸位图的屏幕口径一致。
     */
    private val screenAligned: Boolean = false
) : Drawable() {

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = host.resources.displayMetrics.density
    }

    /** 底栏 shell 同款表面色。 */
    private val surfaceColor =
        if (AppColorUtils.isColorLight(baseColor)) Color.WHITE else Color.rgb(22, 24, 28)

    // 全部直接来自唯一材质入口；行内不做任何二次缩放。
    private val tintBaseAlpha = (material.tintAlpha * 255).toInt()
    private val strokeBaseRatio = material.shell.stroke
    private var shellShaderCache: LinearGradient? = null
    private var alphaFactor = 1f
    private val shaderMatrix = Matrix()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val rect = RectF()
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    /** 最近一次「已同步到绘制」的屏幕坐标；用于判断位置变化是否需要重录内容。 */
    private var cachedLeft = Int.MIN_VALUE
    private var cachedTop = Int.MIN_VALUE

    /** 对齐底图（raw + blurred 两份）；缓存未命中时先为空，异步构建完成后由 [updateBackdrop] 回填。 */
    private var backdrop: GlassBackdrop? = null

    /**
     * API 31+ 的硬件模糊层，仅在 [useHardware] 且设备确实支持时创建。
     * 运行期若该层不可用，[hardwareFailed] 置位，本行改走 [backdrop] 的 blurred 位图，
     * 即 ②③ 已验证的软件管线——不是丢弃效果，也不是透出清晰壁纸。
     */
    private var hardwareLayer: HardwareGlassLayer? = null
    private var hardwareFailed = false

    /**
     * 1.25.10 的行内液态玻璃层（AGSL 真折射+色散）。仅在 [useLiquid] 且 API 33+ 时创建，
     * 创建本身失败（极少数 ROM 上 `RenderNode`/`RuntimeShader` 构造抛异常）只是让本行走
     * 静态管线，不影响任何既有行为。`liquidFailed` 由渲染器自己持有的持久位管理。
     */
    private val liquidLayer: RowGlassLiquidRenderer? =
        if (useLiquid && Build.VERSION.SDK_INT >= ROW_LIQUID_MIN_SDK) {
            runCatching {
                RowGlassLiquidRenderer(
                    host.context, material, host.resources.displayMetrics.density
                )
            }.getOrNull()
        } else {
            null
        }

    init {
        if (useHardware && supportsHardwareBlur() && blurPx > 0f) {
            hardwareLayer = runCatching { HardwareGlassLayer(blurPx) }.getOrNull()
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
        liquidLayer?.reset()
        invalidateSelf()
    }

    /**
     * 布局 / 滚动回调驱动的位置缓存：只有位置真正变化才重算 shader matrix。
     * draw() 内仍会按当帧坐标重采样一次（见 [ensureLocationSampled]），
     * 这里只需保证「位置变了会重录内容」，即 invalidateSelf。
     */
    fun onPositionChanged(left: Int, top: Int, rootLeft: Int, rootTop: Int) {
        location[0] = left
        location[1] = top
        rootLocation[0] = rootLeft
        rootLocation[1] = rootTop
        if (left == cachedLeft && top == cachedTop) return
        cachedLeft = left
        cachedTop = top
        invalidateSelf()
    }

    /**
     * 按**当帧**的屏幕坐标重采样。
     *
     * 早期实现只在位置回调里采样并缓存，前提是「行位移一定会走布局/滚动回调」。
     * 真机验证不成立：RecyclerView 折叠上方行时用 change 动画的 translation 移动子项，
     * 子项自身的 frame 不变、也不产生滚动事件，缓存坐标与实际绘制位置出现偏差，
     * 表现为折叠后下方行玻璃错位、滑动一下（滚动回调）才恢复。
     * `getLocationOnScreen` 只是沿父链累加偏移，相对本帧的 AGSL + 高斯开销可忽略，
     * 因此改为每次绘制都取当前真实坐标，动画帧内也始终与绘制位置对齐。
     */
    private fun ensureLocationSampled() {
        host.getLocationOnScreen(location)
        host.rootView.getLocationOnScreen(rootLocation)
        cachedLeft = location[0]
        cachedTop = location[1]
    }

    init {
        tintPaint.color = Color.WHITE
        tintPaint.alpha = tintBaseAlpha
        applyStrokeColor(1f, strokeBaseRatio)
    }

    private var shellShaderKey: String? = null

    /**
     * 描边色：全局描边色非空时用它（保留其自身 alpha），否则回退
     * surfaceColor × [strokeAlpha] 的底栏同口径着色。[factor] 为 Drawable alpha 系数。
     */
    private fun applyStrokeColor(factor: Float, strokeAlpha: Float) {
        val override = strokeColor
        strokePaint.color = if (override != null) {
            AppColorUtils.withAlpha(override and 0x00ff_ffff, (override ushr 24) / 255f * factor)
        } else {
            AppColorUtils.withAlpha(surfaceColor, strokeAlpha * factor)
        }
    }

    /** 底图可用性统一判定：已回收 / 空尺寸一律视为不可用，交给 shell 兜底。 */
    private fun usable(bitmap: Bitmap): Boolean =
        !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0

    private fun shellShader(
        height: Int,
        start: Float,
        center: Float,
        end: Float
    ): LinearGradient {
        val key = "$height|$start|$center|$end"
        val cached = shellShaderCache
        if (cached != null && shellShaderKey == key) return cached
        // 三色 alpha 取当前绘制路径的壳层口径（液态 = 底栏 1:1，静态 = 行标定）；
        // 上深下浅的垂直渐变同样来自底栏。
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
            shellShaderKey = key
        }
    }

    override fun draw(canvas: Canvas) {
        val bd = backdrop
        rect.set(bounds)
        // A2 本帧是否真的画出了玻璃层：决定后面要不要让位/叠加既有层。
        var drewLiquidGlass = false
        // 先筛掉已回收 / 空位图：两条管线各自取源，任一缺失都不允许透出清晰壁纸。
        val software = bd?.blurred?.takeIf { usable(it) }
        val raw = bd?.raw?.takeIf { usable(it) }
        // 1.25.11 行内液态（A2）优先用 raw：高斯交给 GPU，与底栏「先着色器、后模糊」
        // 完全同一条链路，也不再受主题背景模糊偏好的二次叠加影响。
        // raw 不可用时退回已预模糊的 blurred，并关闭额外模糊（否则双重模糊）。
        val liquidSource = raw ?: software
        val liquid = liquidLayer?.takeIf {
            rowLiquidDrawableNow(
                canvas.isHardwareAccelerated, liquidSource != null, it.liquidFailed
            )
        }
        // 硬件管线需同时满足 API 31+、层创建成功、此前无持久故障、raw 底图可用。
        // raw 缺失时直接让位给软件管线：拿 blurred 去做 GPU 模糊会变成双重模糊。
        // 液态层激活时同一笔玻璃由 shader 完成，硬件高斯层让位（否则重复出图）。
        val activeLayer = hardwareLayer?.takeIf {
            liquid == null && !hardwareFailed && supportsHardwareBlur() && raw != null
        }
        // 硬件激活时 glassBitmap 就是 raw，液态用 raw 或降级后的 blurred，软件用它自己的 blurred。
        val glassBitmap = when {
            liquid != null -> liquidSource
            activeLayer != null -> raw
            else -> software
        }
        if (glassBitmap != null) {
            val root = host.rootView
            // 对话框窗口的根视图只有面板大小，不能当壁纸的对齐基准；此时改用屏幕尺寸与
            // 屏幕原点，和 decodeRaw 里「按 displayMetrics 构建位图」的口径保持一致。
            val rootWidth = if (screenAligned) host.resources.displayMetrics.widthPixels else root.width
            val rootHeight = if (screenAligned) host.resources.displayMetrics.heightPixels else root.height
            if (rootWidth > 0 && rootHeight > 0) {
                ensureLocationSampled()
                if (screenAligned) {
                    rootLocation[0] = 0
                    rootLocation[1] = 0
                }
                // 采样矩阵：与软件 / 硬件 / 液态三条管线共用同一份口径（纯函数在
                // RowGlassLiquidSpec.rowSampleTransform）。方向是「位图 → 局部」，
                // 与 BitmapShader.setLocalMatrix 的约定一致：降采样位图放大回根视图尺寸。
                val transform = rowSampleTransform(
                    bitmapWidth = glassBitmap.width,
                    bitmapHeight = glassBitmap.height,
                    rootWidth = rootWidth,
                    rootHeight = rootHeight,
                    locationX = location[0],
                    locationY = location[1],
                    rootX = rootLocation[0],
                    rootY = rootLocation[1]
                )
                shaderMatrix.setScale(transform.scaleX, transform.scaleY)
                shaderMatrix.postTranslate(transform.translateX, transform.translateY)
                var drewGlass = false
                if (liquid != null) {
                    // 液态管线：AGSL 真折射 + 色散 + GPU 高斯，tint 由着色器负责。
                    // 失败时渲染器自己置**进程级**故障位并返回 false，本帧立即走静态分支。
                    drewLiquidGlass = liquid.draw(
                        canvas, glassBitmap,
                        // raw 走 GPU 高斯；只有退回 blurred 时才不额外模糊。
                        if (raw != null) blurPx else 0f,
                        rect, radiusPx, shaderMatrix,
                        glassPaint.alpha, glassPaint.colorFilter
                    )
                    drewGlass = drewLiquidGlass
                }
                if (!drewGlass && activeLayer != null) {
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
        // 静态降级路径没有着色器，tint 由这一层补上；液态成功时着色器已经完成
        // tint，再叠一层会比底栏更白，因此只在此让位。
        if (!drewLiquidGlass) {
            canvas.drawRoundRect(rect, radiusPx, radiusPx, tintPaint)
        }
        if (rect.height() >= 1f) {
            val shaderHeight = rect.height().toInt().coerceAtLeast(1)
            shellPaint.shader = shellShader(
                shaderHeight,
                material.shell.start,
                material.shell.center,
                material.shell.end
            )
            shellPaint.alpha = (255 * alphaFactor).toInt()
            canvas.drawRoundRect(rect, radiusPx, radiusPx, shellPaint)
        }
        // 描边：与底栏同一份 shell.stroke，液态与静态路径不再各有一套系数。
        applyStrokeColor(alphaFactor, strokeBaseRatio)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        val factor = alpha.coerceIn(0, 255) / 255f
        if (factor != alphaFactor) {
            alphaFactor = factor
            glassPaint.alpha = (255 * factor).toInt()
            tintPaint.alpha = (tintBaseAlpha * factor).toInt()
            applyStrokeColor(factor, strokeBaseRatio)
            invalidateSelf()
        }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * 圆角轮廓：对话框把结论 View 设为 `clipToOutline`，若这里只给矩形轮廓，
     * 子内容会按直角裁剪、在圆角处露出方角。与绘制用的圆角保持同一半径。
     */
    override fun getOutline(outline: Outline) {
        super.getOutline(outline)
        if (bounds.width() > 0 && bounds.height() > 0) {
            outline.setRoundRect(bounds, radiusPx)
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glassPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
        shellPaint.colorFilter = colorFilter
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
        detach(host)
        val binding = TrackerBinding(host, drawable)
        host.setTag(TAG_KEY, binding)
        binding.attach()
        if (host.isAttachedToWindow) {
            binding.onViewAttachedToWindow(host)
        }
    }

    /**
     * 显式解绑：切到无玻璃档 / 降级分支时不会再走 [attach]，若不处理，上一份
     * [TrackerBinding]（含 host 的 attach/layout 监听、RecyclerView / NestedScrollView
     * 滚动监听与旧 drawable 引用）会滞留在 tag 里直到 View detach 或切回玻璃档。
     *
     * 复用 [TrackerBinding.detach] 这条既有的 View-detach 路径，不注册任何新监听、
     * 不新增对象引用，还顺手清掉 tag 释放旧 drawable；重复调用是 no-op
     * （[TrackerBinding.uninstallScrollTarget] 本身幂等）。玻璃档 attach 仍会先
     * detach 再挂新 binding，行为逐值不变。
     */
    fun detach(host: View) {
        (host.getTag(TAG_KEY) as? TrackerBinding)?.detach(host)
        host.setTag(TAG_KEY, null)
    }

    /** 设置页预览：Host 被 Compose 平移到新位置后，主动重算一次采样坐标。 */
    fun refresh(host: View) {
        (host.getTag(TAG_KEY) as? TrackerBinding)?.updatePosition(host)
    }

    private class TrackerBinding(
        private val host: View,
        private val drawable: FrostedGlassDrawable
    ) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener,
        ViewTreeObserver.OnPreDrawListener {
        private var scrollTarget: View? = null

        /**
         * 宿主所在窗口的「绘制前」回调。
         *
         * 行滚动由 [scrollListener] 覆盖，但「展开 / 折叠上方行导致下方行整体位移」不会
         * 产生滚动事件：RecyclerView 用 change 动画的 translation 移动子项时子项 frame
         * 不变，`onLayoutChange` 不触发；子项被移动本身也不会让系统重录它的内容，
         * 于是玻璃里仍是原地采样的壁纸，表现为折叠后下方行错位、滑动一下才恢复。
         * `OnPreDrawListener` 在每次遍历的布局之后、录制绘制之前派发，能覆盖 translation
         * 位移，位置变了就 invalidateSelf 让本帧重录，保证采样坐标与绘制位置一致。
         *
         * 注册时保存 observer 实例：View 脱离窗口后 `viewTreeObserver` 会返回新的
         * 游离 observer，用保存的实例才能移除原来那一个。
         */
        private var preDrawObserver: ViewTreeObserver? = null

        fun attach() {
            host.addOnAttachStateChangeListener(this)
            host.addOnLayoutChangeListener(this)
        }

        fun detach(v: View) {
            v.removeOnAttachStateChangeListener(this)
            v.removeOnLayoutChangeListener(this)
            uninstallPreDrawListener()
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
            installPreDrawListener(v)
            updatePosition(v)
        }

        override fun onViewDetachedFromWindow(v: View) {
            scrollTarget?.let { uninstallScrollTarget(it) }
            scrollTarget = null
            uninstallPreDrawListener()
        }

        private fun installPreDrawListener(v: View) {
            uninstallPreDrawListener()
            val observer = v.viewTreeObserver
            if (observer.isAlive) {
                observer.addOnPreDrawListener(this)
                preDrawObserver = observer
            }
        }

        private fun uninstallPreDrawListener() {
            val observer = preDrawObserver ?: return
            if (observer.isAlive) {
                observer.removeOnPreDrawListener(this)
            }
            preDrawObserver = null
        }

        /** 绘制前：按当帧位置刷新采样坐标；位置变化时重录内容。始终返回 true 放行绘制。 */
        override fun onPreDraw(): Boolean {
            updatePosition(host)
            return true
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

        /** 供 [GlassPositionTracker.refresh] 调用：预览容器滚动后主动重采样。 */
        fun updatePosition(v: View) {
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
