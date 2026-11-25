package io.legado.app.lib.theme

import io.legado.app.constant.PreferKey

/**
 * Single registry for all Day/Night preference key pairs in [PreferKey].
 *
 * [ThemeRuntimeKeys], [io.legado.app.help.storage.BackupConfig.themePrefKeys], and
 * [io.legado.app.help.config.pkg.UnifiedPackageRepository.PARAM_LIVE_SNAPSHOT_KEYS]
 * derive their paired keys from this registry so adding a new Day/Night key updates
 * all consumers from one place (verified by `tools/check_night_key_pairs.py`).
 */
object NightKeyPairs {

    /** 18 core runtime theme/typography/alpha/shape pairs migrated and queried by [ThemeRuntimeKeys]. */
    val themeRuntimePairs: List<Pair<String, String>> = listOf(
        PreferKey.fontScale to PreferKey.fontScaleN,
        PreferKey.uiFontPath to PreferKey.uiFontPathN,
        PreferKey.titleFontPath to PreferKey.titleFontPathN,
        PreferKey.uiFontColor to PreferKey.uiFontColorN,
        PreferKey.titleFontColor to PreferKey.titleFontColorN,
        PreferKey.uiCornerScale to PreferKey.uiCornerScaleN,
        PreferKey.uiLayoutAlpha to PreferKey.uiLayoutAlphaN,
        PreferKey.dialogAlpha to PreferKey.dialogAlphaN,
        PreferKey.uiCornerSearchFollow to PreferKey.uiCornerSearchFollowN,
        PreferKey.uiCornerReplyFollow to PreferKey.uiCornerReplyFollowN,
        PreferKey.themeCardColor to PreferKey.themeCardColorN,
        PreferKey.themeMutedColor to PreferKey.themeMutedColorN,
        PreferKey.themeSearchFieldBackgroundColor to PreferKey.themeSearchFieldBackgroundColorN,
        PreferKey.themeTabBackgroundColor to PreferKey.themeTabBackgroundColorN,
        PreferKey.themeShelfColor to PreferKey.themeShelfColorN,
        PreferKey.themeCardShadow to PreferKey.themeCardShadowN,
        PreferKey.themeCardBackgroundBlur to PreferKey.themeCardBackgroundBlurN,
        PreferKey.themeExploreGlassBlur to PreferKey.themeExploreGlassBlurN
    )

    /** Additional 14 palette/wallpaper/panel/preset pairs backed up in `BackupConfig.themePrefKeys`. */
    val themeBackupExtraPairs: List<Pair<String, String>> = listOf(
        PreferKey.cPrimary to PreferKey.cNPrimary,
        PreferKey.cAccent to PreferKey.cNAccent,
        PreferKey.cBackground to PreferKey.cNBackground,
        PreferKey.cBBackground to PreferKey.cNBBackground,
        PreferKey.bgImage to PreferKey.bgImageN,
        PreferKey.bgImageBlurring to PreferKey.bgImageNBlurring,
        PreferKey.bookInfoBgImage to PreferKey.bookInfoBgImageN,
        PreferKey.panelBgImage to PreferKey.panelBgImageN,
        PreferKey.panelBgScaleType to PreferKey.panelBgScaleTypeN,
        PreferKey.panelBorderColor to PreferKey.panelBorderColorN,
        PreferKey.panelBorderAlpha to PreferKey.panelBorderAlphaN,
        PreferKey.paramStylePresetId to PreferKey.paramStylePresetIdN,
        PreferKey.glassPresetMatched to PreferKey.glassPresetMatchedN,
        PreferKey.tNavBar to PreferKey.tNavBarN
    )

    /** Paired keys included in `ParamRuntimeSnapshot` (14 pairs = 28 keys). */
    val paramLiveSnapshotPairs: List<Pair<String, String>> = listOf(
        PreferKey.uiLayoutAlpha to PreferKey.uiLayoutAlphaN,
        PreferKey.dialogAlpha to PreferKey.dialogAlphaN,
        PreferKey.panelBorderAlpha to PreferKey.panelBorderAlphaN,
        PreferKey.bgImageBlurring to PreferKey.bgImageNBlurring,
        PreferKey.paramStylePresetId to PreferKey.paramStylePresetIdN,
        PreferKey.glassPresetMatched to PreferKey.glassPresetMatchedN,
        PreferKey.uiCornerScale to PreferKey.uiCornerScaleN,
        PreferKey.themeCardShadow to PreferKey.themeCardShadowN,
        PreferKey.themeCardBackgroundBlur to PreferKey.themeCardBackgroundBlurN,
        PreferKey.uiCornerSearchFollow to PreferKey.uiCornerSearchFollowN,
        PreferKey.uiCornerReplyFollow to PreferKey.uiCornerReplyFollowN,
        PreferKey.paramPackageDirty to PreferKey.paramPackageDirtyN,
        PreferKey.paramDirtyDayPackageId to PreferKey.paramDirtyNightPackageId,
        PreferKey.activeDayParamPackageId to PreferKey.activeNightParamPackageId
    )

    /** Cover collection & slot Day/Night pairs backed up in `BackupConfig.coverPrefKeys`. */
    val coverBackupPairs: List<Pair<String, String>> = listOf(
        PreferKey.coverShowName to PreferKey.coverShowNameN,
        PreferKey.coverShowAuthor to PreferKey.coverShowAuthorN,
        PreferKey.activeDayCoverSlotPackageId to PreferKey.activeNightCoverSlotPackageId,
        PreferKey.coverCollectionDay to PreferKey.coverCollectionNight,
        PreferKey.coverCollectionModeDay to PreferKey.coverCollectionModeNight,
        PreferKey.coverCollectionTargetDay to PreferKey.coverCollectionTargetNight
    )

    /** Active Art & Param package slot pairs backed up in `BackupConfig.artParamActivePrefKeys`. */
    val artParamActivePairs: List<Pair<String, String>> = listOf(
        PreferKey.activeDayArtPackageId to PreferKey.activeNightArtPackageId,
        PreferKey.activeDayParamPackageId to PreferKey.activeNightParamPackageId
    )

    /** Remaining top-bar, navigation-bar, welcome, and misc Day/Night pairs audited by `check_night_key_pairs.py`. */
    val miscDayNightPairs: List<Pair<String, String>> = listOf(
        PreferKey.bgImageCrop to PreferKey.bgImageNCrop,
        PreferKey.dThemeName to PreferKey.dNThemeName,
        PreferKey.dThemeDirName to PreferKey.dNThemeDirName,
        PreferKey.topBarBgImage to PreferKey.topBarBgImageN,
        PreferKey.topBarWallpaperAlpha to PreferKey.topBarWallpaperAlphaN,
        PreferKey.defaultCover to PreferKey.defaultCoverDark,
        PreferKey.defaultGroupCover to PreferKey.defaultGroupCoverDark,
        PreferKey.navigationBarPackageDay to PreferKey.navigationBarPackageNight,
        PreferKey.topBarPackageDay to PreferKey.topBarPackageNight,
        PreferKey.welcomeImage to PreferKey.welcomeImageDark,
        PreferKey.welcomeShowText to PreferKey.welcomeShowTextDark,
        PreferKey.welcomeShowIcon to PreferKey.welcomeShowIconDark,
        PreferKey.editTheme to PreferKey.editThemeDark
    )

    val themeRuntimeKeys: Set<String> =
        themeRuntimePairs.flatMapTo(LinkedHashSet(themeRuntimePairs.size * 2)) { listOf(it.first, it.second) }

    val themeBackupKeys: Array<String> =
        (themeRuntimePairs + themeBackupExtraPairs)
            .flatMap { listOf(it.first, it.second) }
            .distinct()
            .toTypedArray()

    val coverBackupPairedKeys: Array<String> =
        coverBackupPairs
            .flatMap { listOf(it.first, it.second) }
            .distinct()
            .toTypedArray()

    val artParamActivePairedKeys: Array<String> =
        artParamActivePairs
            .flatMap { listOf(it.first, it.second) }
            .distinct()
            .toTypedArray()

    val paramLiveSnapshotPairedKeys: Set<String> =
        paramLiveSnapshotPairs.flatMapTo(LinkedHashSet(paramLiveSnapshotPairs.size * 2)) { listOf(it.first, it.second) }
}
