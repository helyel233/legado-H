package io.legado.app.ui.rss.article

/**
 * 订阅源文章页「栏目选择按钮」整排重铺表面的判定（纯 JVM，不引用任何 Android 类型）。
 *
 * 为什么需要这一步：栏目按钮由 [RssSortActivity.setupMultiLineTabs] 一次性创建，
 * 是常驻 View——「进设置切玻璃档 / 关背景图 → 返回本页」不会重建它们，页面的 `onResume`
 * 若不重铺，按钮就停在旧材质上（真实链路：开背景图 + 玻璃档 → 关背景图 → 返回，
 * 页面根背景已被 `BaseActivity` 换成实底，按钮却还是玻璃）。
 *
 * [lastSignature] 为 null 表示**这批 View 还没有铺过**（首次创建，或刚从
 * `setupMultiLineTabs` 重建出来），必须铺一次；非 null 时只比签名，未变化即零开销返回。
 *
 * 签名必须由调用方用 [io.legado.app.ui.glass.wallpaperGlassSignature] 取得，
 * **不能**用 `GlassSurface.configSignature`：后者只含档位 / 强度 / 倍率 / 描边色 / 夜间，
 * 不含背景图开关，只比它在「关/开背景图后返回」时签名不变，会漏掉这一次变化
 * （这条缺陷在本项目已复发过三次，口径见 `GlassViewBackground.kt` 的注释）。
 */
internal fun tabsGlassRelayoutRequired(lastSignature: String?, currentSignature: String): Boolean =
    lastSignature != currentSignature
