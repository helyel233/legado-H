package io.legado.app.ui.glass

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import io.legado.app.help.config.ThemeConfig

/**
 * View 背景的壁纸域玻璃化（列表行 / 页面卡片共用的唯一实现）。
 *
 * 抽成一个入口的原因：这套「先判壁纸可用、再取玻璃 drawable、不可用回退实底」的逻辑
 * 在订阅文章行、水瀑布文章卡、首页模块卡等十几处都要用，各写一遍必然分叉——而分叉
 * 的错法在真机上很难一眼看出（solid 档拿到不透明实底壳、开背景图后复用行停在透明底、
 * 切档后已挂载的行留在旧材质上）。这里把四条纪律钉死：
 *
 * 1. **壁纸不可用时不碰玻璃入口**：无可用背景图（普通主题）时直接铺 [fallback]，
 *    与改动前逐值一致；`GlassSurface.panelBackground` 在无玻璃档返回的是底栏色实底壳，
 *    直接拿来铺页面卡片会变成「主题卡片 → 底栏色」的观感跳变。
 * 2. **先判 [GlassSurface.pageGlassAllowed] 再回退**：solid 档 / 墨水屏 / 低内存 /
 *    页面不露壁纸（`showsWallpaperBehindContent = false`）逐值保持调用方原表面。
 * 3. **签名去重**：壁纸可用性与玻璃观感签名一起挂进 keyed tag，重复挂载（滚动复用、
 *    `onViewAttachedToWindow`）是零开销的；签名变化才重铺，避免切档后行停在旧材质。
 * 4. **回退即解绑**：任何「本次不铺玻璃」的分支（关背景图 / solid / 墨水屏 / 低内存 /
 *    页面不露壁纸）都要调 [GlassSurface.releaseBackgroundTracking]。宿主 View 是常驻的，
 *    不处理的话上一份 `TrackerBinding` 会继续挂在布局与滚动回调上给旧 drawable 重采样。
 *
 * 采样方式与发现页书源名行、订阅源卡片**同一份**：玻璃 drawable 按 View 在屏幕上的
 * 实时位置采样进程级缓存的壁纸位图，**不录制自身或祖先子树**（壁纸域不涉及
 * `StableLiquidGlassView`），`GlassPositionTracker` 负责位移后重采样。
 *
 * @param cornerRadiusPx 玻璃层圆角（像素）。通栏连续行取 0；自带卡面的卡片取原卡面圆角。
 * @param tagKey 签名 tag 的 keyed tag key。调用方各自持有独立 key，互不覆盖
 *   （同一 View 上可能同时有业务 tag）；默认 [GLASS_SIGNATURE_TAG]。
 * @param fallback 玻璃不可用时的原表面。返回 null 表示「原本就是透明」——此时玻璃
 *   仍可在壁纸可见时接管，但绝不会铺不透明实底壳。
 */
fun View.applyWallpaperGlassBackground(
    cornerRadiusPx: Float,
    tagKey: Int = GLASS_SIGNATURE_TAG,
    fallback: () -> Drawable?
) {
    val hasBgImage = ThemeConfig.hasUsableBgImage(context)
    // 签名除玻璃观感外还要带「壁纸是否可用」：它决定走玻璃还是原表面，
    // 少这一位则「开启背景图」后已复用的 View 会停在原表面上。
    // 口径收敛在 [wallpaperGlassSignature]，本文件与常驻 Fragment 的 onResume 重绑
    // 读同一份实现——各自就地把 configSignature 拼一次，正是这条缺陷反复复发的来源。
    val signature = wallpaperGlassSignature(context)
    if (getTag(tagKey) == signature) return
    if (!hasBgImage) {
        // 无壁纸：不铺玻璃，上一次挂上的位置跟踪必须同时解绑（关背景图后复用中的
        // 卡片 / 行会走到这里），否则它会继续逐帧给旧玻璃 drawable 重采样
        GlassSurface.releaseBackgroundTracking(this)
        background = fallback()
        setTag(tagKey, signature)
        return
    }
    val solid = fallback()
    background = when {
        // 有实底原表面：玻璃优先，不可用时逐值回退到这张实底（回退分支在
        // pagePanelBackground 内部解绑旧跟踪）
        solid != null -> GlassSurface.pagePanelBackground(this, cornerRadiusPx) { solid }
        // 原本透明：只有壁纸域玻璃真的可用时才铺，否则保持透明
        GlassSurface.pageGlassAllowed(context) ->
            GlassSurface.panelBackground(this, GlassSamplingDomain.WALLPAPER, cornerRadiusPx)
        else -> {
            // 原表面透明且当前不允许玻璃：不铺，旧跟踪同样要下线
            GlassSurface.releaseBackgroundTracking(this)
            null
        }
    }
    // 签名在背景真正铺好后才记录：中途中抛时 View 不会被误标「已铺」，
    // 重绑仍会重走一遍（与 RssSortActivity.applyTabGlass 的「记在最后」同一纪律）
    setTag(tagKey, signature)
}

/**
 * 壁纸域玻璃的**完整**重绑签名 = 玻璃观感签名 + 壁纸此刻是否可用。
 *
 * 为什么必须合并成一个字符串：所有「按签名决定要不要重铺」的调用点（本文件的
 * [applyWallpaperGlassBackground]、`ReadRecordFragment.onResume`）比的是**同一件事**
 * ——「这次要铺的表面和上次铺的是不是同一档」。玻璃观感签名（[GlassSurface.configSignature]）
 * 只含档位 / 强度 / 倍率 / 描边色 / 夜间，**不含背景图开关**：单用它比较时，
 * 「进设置关背景图 → 返回」签名不变 → 不重铺 → 页面根背景已被 `BaseActivity` 换成实底、
 * 卡片却停在玻璃上（反向开背景图则玻璃不出现）。
 *
 * 无壁纸时统一折叠成 [NO_WALLPAPER_SIGNATURE]：此时不论观感参数如何都不会有玻璃，
 * 折叠是刻意的——避免「无壁纸 + 改档位」触发一轮无意义的重铺。
 *
 * 纯函数，供 JVM 单测锁真值表。
 */
internal fun glassSignatureWithWallpaper(configSignature: String, hasBgImage: Boolean): String =
    if (hasBgImage) configSignature else NO_WALLPAPER_SIGNATURE

/**
 * [glassSignatureWithWallpaper] 的 Context 版本：View 侧与 Fragment 侧共用的唯一入口。
 *
 * 新增「按签名判断要不要重铺」的调用点时**只准调这个**，不要就地用
 * `GlassSurface.configSignature(context)` 拼——那正是本缺陷第三次复发的形态。
 *
 * `GlassSurface.configSignature` 只读偏好、无副作用，因此「先取签名再判壁纸」与
 * 「先判壁纸再取签名」在观感与开销上都等价（无壁纸时多一次纯偏好读取）。
 */
fun wallpaperGlassSignature(context: Context): String =
    glassSignatureWithWallpaper(
        configSignature = GlassSurface.configSignature(context),
        hasBgImage = ThemeConfig.hasUsableBgImage(context)
    )

/**
 * 签名 tag 的默认 keyed tag key。
 *
 * 高位 `0x5F` 保证 `View.setTag(int, Object)` 的「应用位 >= 2」契约成立
 * （否则真机抛 `IllegalArgumentException`），与 `GlassPositionTracker`、
 * 文章行行底用的是同一套约定。
 */
const val GLASS_SIGNATURE_TAG: Int = 0x5F_4756_42

/**
 * 无可用背景图（普通主题）时的表面签名。
 *
 * 取值是字面量而非对象：它只用于「同一个 View 是否已经铺过这一档」的比较，
 * 不参与任何观感计算。
 */
const val NO_WALLPAPER_SIGNATURE: String = "no-bg-image"
