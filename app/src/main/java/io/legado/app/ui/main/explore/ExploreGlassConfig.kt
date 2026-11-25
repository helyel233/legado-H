package io.legado.app.ui.main.explore

import android.content.Context
import android.os.Build
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.pkg.runtime.GlassMaterialModes
import io.legado.app.help.config.pkg.runtime.resolveExploreGlassMode as resolveGlassModeRuntime
import io.legado.app.help.config.pkg.runtime.resolvedExploreGlassMode as resolvedGlassModeRuntime
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref

/**
 * 硬件模糊是否可用：`RenderEffect.createBlurEffect` / `RenderNode.setRenderEffect`
 * 均为 API 31（Android 12）。墨水屏、低内存设备与总开关关闭由
 * `ExploreGlassBackdrop.rowBackground()` 直接返回 null 挡在门外，此处无需重复判断。
 */
internal fun supportsHardwareBlur(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 硬件管线模糊半径（屏幕像素）的上下限：与 `StableLiquidGlassView.setBlurRadius` 逐值一致。 */
internal const val HARDWARE_BLUR_MIN_PX = 0.01f
internal const val HARDWARE_BLUR_MAX_PX = 50f

/** 细项倍率滑杆范围：100 = 沿用该档预设原值，0 = 关闭该维度，200 = 加倍。 */
internal const val EXPLORE_GLASS_SCALE_MIN = 0
internal const val EXPLORE_GLASS_SCALE_DEFAULT = 100
internal const val EXPLORE_GLASS_SCALE_MAX = 200

/**
 * 展开行小控件「按下」反馈的叠色 alpha：玻璃 drawable 本身没有按下态，
 * 用一层低透明度反色保留可感知的按压反馈（原 `filletControlBackground` 的按下态
 * 会把底色换成接近不透明的颜色，直接叠加会盖住玻璃，因此这里用 0.10 的淡叠色）。
 */
internal const val CONTROL_PRESS_SCRIM_ALPHA = 0.10f

/**
 * 三档材质的取值与底栏液态玻璃完全一致（solid/glass/frosted），
 * 参数由 [GlassMaterialPreset] 统一提供，不再用系数去缩放发现页既有观感。
 * 1.25.5 曾把「无玻璃」存成 `none`，这里保留为旧值别名以便平滑升级。
 */
internal const val EXPLORE_GLASS_MODE_SOLID = GlassMaterialPreset.SOLID
internal const val EXPLORE_GLASS_MODE_GLASS = GlassMaterialPreset.GLASS
internal const val EXPLORE_GLASS_MODE_FROSTED = GlassMaterialPreset.FROSTED
internal const val EXPLORE_GLASS_MODE_CLEAR = GlassMaterialPreset.CLEAR
internal const val EXPLORE_GLASS_MODE_DEFAULT = EXPLORE_GLASS_MODE_FROSTED
private const val LEGACY_MODE_NONE = GlassMaterialModes.LEGACY_NONE

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
): String = resolveGlassModeRuntime(rawMode, legacyEnabled)

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
    val presetMatched = PreferKey.glassPresetMatched
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
        legacyEnabled, effectMode, presetMatched,
        PreferKey.glassPresetMatchedN,
        PreferKey.paramStylePresetId,
        PreferKey.paramStylePresetIdN,
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
fun resolvedExploreGlassMode(): String = resolvedGlassModeRuntime()

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
