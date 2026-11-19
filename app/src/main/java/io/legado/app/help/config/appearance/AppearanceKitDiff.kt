package io.legado.app.help.config.appearance

/**
 * 6 大外观槽位的完整不可变快照，用于纯函数比较式判脏（[AppearanceKitDiff]）。
 *
 * 每槽位严格对应 v3 设计稿显式键集：
 * - THEME: 日/夜七元组（themeName, dirName, primary, accent, background, bottomBackground, backgroundImage）
 * - TOP_BAR: topBarPackageDay / Night
 * - NAVIGATION_BAR: navigationBarPackageDay / Night
 * - COVER: coverCollectionDay/Night + coverCollectionModeDay/Night
 * - GLASS: exploreGlassEffectMode + frosted/liquid + 4 scales + glassStrokeColor + glassPresetMatched
 * - WELCOME: customWelcome + welcomeImage/Dark + welcomeShowText/Dark + welcomeShowIcon/Dark + welcomeShowTime
 */
data class ThemeSlotSnapshot(
    val themeName: String = "",
    val dirName: String = "",
    val primary: Int = Int.MIN_VALUE,
    val accent: Int = Int.MIN_VALUE,
    val background: Int = Int.MIN_VALUE,
    val bottomBackground: Int = Int.MIN_VALUE,
    val backgroundImage: String = ""
)

data class GlassSlotSnapshot(
    val mode: String = BuiltinAppearanceDefaults.DEFAULT_GLASS_MODE,
    val frostedLevel: Int = BuiltinAppearanceDefaults.DEFAULT_GLASS_LEVEL,
    val liquidLevel: Int = BuiltinAppearanceDefaults.DEFAULT_GLASS_LEVEL,
    val blurScale: Int = BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE,
    val shellScale: Int = BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE,
    val tintScale: Int = BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE,
    val strokeScale: Int = BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE,
    val strokeColor: String = "",
    val presetMatched: Boolean = true,
    val hardwareBlur: Boolean = true,
    val animatedSolid: Boolean = true
)

data class WelcomeSlotSnapshot(
    val customWelcome: Boolean = false,
    val imageDay: String = "",
    val imageNight: String = "",
    val showTextDay: Boolean = true,
    val showTextNight: Boolean = true,
    val showIconDay: Boolean = true,
    val showIconNight: Boolean = true,
    val showTime: Int = BuiltinAppearanceDefaults.DEFAULT_WELCOME_SHOW_TIME
)

data class AppearanceKitSnapshot(
    val dayTheme: ThemeSlotSnapshot = ThemeSlotSnapshot(),
    val nightTheme: ThemeSlotSnapshot = ThemeSlotSnapshot(),
    val dayTopBarDir: String = BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
    val nightTopBarDir: String = BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
    val dayNavBarDir: String = BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
    val nightNavBarDir: String = BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
    val dayCoverCollectionId: String = "",
    val nightCoverCollectionId: String = "",
    val dayCoverMode: String = BuiltinAppearanceDefaults.DEFAULT_COVER_MODE,
    val nightCoverMode: String = BuiltinAppearanceDefaults.DEFAULT_COVER_MODE,
    val glass: GlassSlotSnapshot = GlassSlotSnapshot(),
    val welcome: WelcomeSlotSnapshot = WelcomeSlotSnapshot()
)

object AppearanceKitDiff {

    /**
     * 比较当前生效快照 [current] 与当前激活方案基线 [baseline]，返回发生偏离的槽位集合。
     */
    fun computeDirtySlots(
        current: AppearanceKitSnapshot,
        baseline: AppearanceKitSnapshot
    ): Set<AppearanceSlot> {
        val dirty = linkedSetOf<AppearanceSlot>()
        if (current.dayTheme != baseline.dayTheme || current.nightTheme != baseline.nightTheme) {
            dirty += AppearanceSlot.THEME
        }
        if (current.dayTopBarDir != baseline.dayTopBarDir ||
            current.nightTopBarDir != baseline.nightTopBarDir
        ) {
            dirty += AppearanceSlot.TOP_BAR
        }
        if (current.dayNavBarDir != baseline.dayNavBarDir ||
            current.nightNavBarDir != baseline.nightNavBarDir
        ) {
            dirty += AppearanceSlot.NAVIGATION_BAR
        }
        if (current.dayCoverCollectionId != baseline.dayCoverCollectionId ||
            current.nightCoverCollectionId != baseline.nightCoverCollectionId ||
            current.dayCoverMode != baseline.dayCoverMode ||
            current.nightCoverMode != baseline.nightCoverMode
        ) {
            dirty += AppearanceSlot.COVER
        }
        if (current.glass != baseline.glass) {
            dirty += AppearanceSlot.GLASS
        }
        if (current.welcome != baseline.welcome) {
            dirty += AppearanceSlot.WELCOME
        }
        return dirty
    }
}
