package io.legado.app.help.config.pkg

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Silently disassembles legacy fragmented packages (ThemePackage + TopBarConfig +
 * NavigationBarIconConfig + CoverCollection + AppearanceKit) into orthogonal
 * [ArtPackagePayload] and [ParamPackagePayload] packages on upgrade or legacy import.
 */
object LegacyPackageMigrator {

    const val CURRENT_MIGRATION_VERSION = 1
    private val migrationMutex = Mutex()

    data class DisassembledLegacyBundle(
        val art: ArtPackagePayload,
        val param: ParamPackagePayload?
    )

    /**
     * Pure JVM helper splitting legacy [ThemeConfig.Config] (day/night) + optional [TopBarConfig.Config]
     * into pure sensory [ArtPackagePayload] and numeric [ParamPackagePayload].
     */
    fun disassembleLegacyBundle(
        dayTheme: ThemeConfig.Config? = null,
        nightTheme: ThemeConfig.Config? = null,
        dayTopBar: TopBarConfig.Config? = null,
        nightTopBar: TopBarConfig.Config? = null,
        dayIconSlotId: String? = null,
        nightIconSlotId: String? = null,
        dayCoverSlotId: String? = null,
        nightCoverSlotId: String? = null
    ): DisassembledLegacyBundle {
        val art = ArtPackagePayload(
            dayPalette = dayTheme?.toArtPalette(),
            nightPalette = nightTheme?.toArtPalette(),
            dayWallpapers = toArtWallpapers(dayTheme, dayTopBar),
            nightWallpapers = toArtWallpapers(nightTheme, nightTopBar),
            dayTypography = dayTheme?.toArtTypography(),
            nightTypography = nightTheme?.toArtTypography(),
            mounts = ArtAssetMounts(
                dayIconSlotId = dayIconSlotId?.takeIf { it.isNotBlank() && it != TopBarConfig.DEFAULT_DIR_NAME },
                nightIconSlotId = nightIconSlotId?.takeIf { it.isNotBlank() && it != TopBarConfig.DEFAULT_DIR_NAME },
                dayCoverSlotId = dayCoverSlotId?.takeIf { it.isNotBlank() },
                nightCoverSlotId = nightCoverSlotId?.takeIf { it.isNotBlank() }
            )
        )

        val primaryTheme = dayTheme ?: nightTheme
        val surfaceGlobal = SurfaceGlobalParams(
            cornerBaseScale = primaryTheme?.uiCornerScale,
            surfaceAlpha = primaryTheme?.uiLayoutAlpha,
            dialogAlpha = primaryTheme?.dialogAlpha,
            panelBorderAlpha = primaryTheme?.panelBorderAlpha,
            cardShadow = primaryTheme?.cardShadow,
            cardBackgroundBlur = primaryTheme?.cardBackgroundBlur,
            wallpaperBlurDay = dayTheme?.backgroundImgBlur,
            wallpaperBlurNight = nightTheme?.backgroundImgBlur,
            uiCornerSearchFollow = primaryTheme?.uiCornerSearchFollow,
            uiCornerReplyFollow = primaryTheme?.uiCornerReplyFollow
        ).takeIf { it.hasAnyValue() }

        val layout = primaryTheme?.fontScale?.let { scale ->
            LayoutParams(fontScale = scale)
        }

        val param = if (surfaceGlobal != null || layout != null) {
            ParamPackagePayload(surfaceGlobal = surfaceGlobal, layout = layout)
        } else {
            null
        }
        return DisassembledLegacyBundle(art = art, param = param)
    }

    suspend fun migrateLocalPackagesIfNeeded(
        context: Context,
        forceReconcile: Boolean = false,
        overwriteExisting: Boolean = false
    ) = withContext(IO) {
        if (!forceReconcile &&
            context.getPrefInt(PreferKey.legacyPackageMigrationVersion, 0) >= CURRENT_MIGRATION_VERSION
        ) {
            UnifiedPackageRepository.ensureDefaultPackagesExist(context)
            return@withContext
        }
        migrationMutex.withLock {
            if (!forceReconcile &&
                context.getPrefInt(PreferKey.legacyPackageMigrationVersion, 0) >= CURRENT_MIGRATION_VERSION
            ) {
                UnifiedPackageRepository.ensureDefaultPackagesExist(context)
                return@withContext
            }
            // 1. Preserve currently active TopBar wallpaper directly into global Prefs so removing
            // standalone TopBar management never loses the user's top bar image, without overwriting
            // active v2 topbar wallpapers during incremental reconciliation (unless explicit rollback).
            val onlyIfUnset = forceReconcile && !overwriteExisting
            preserveActiveTopBarWallpaper(context, isNight = false, onlyIfUnset = onlyIfUnset)
            preserveActiveTopBarWallpaper(context, isNight = true, onlyIfUnset = onlyIfUnset)

            // 2. Migrate existing AppearanceKits (paired day/night themes + topbars + icon/cover mounts + glass)
            val existingKits = runCatching { AppearanceKitManager.importedThemeKits() }.getOrDefault(emptyList())
            val dayThemes = runCatching { ThemePackageManager.loadLocalOnly(false) }.getOrDefault(emptyList())
                .filter { it.source != ThemePackageManager.Source.BUILTIN }
            val nightThemes = runCatching { ThemePackageManager.loadLocalOnly(true) }.getOrDefault(emptyList())
                .filter { it.source != ThemePackageManager.Source.BUILTIN }
            val dayTopBars = runCatching { TopBarConfig.loadLocalOnlyForKit(false) }.getOrDefault(emptyList())
            val nightTopBars = runCatching { TopBarConfig.loadLocalOnlyForKit(true) }.getOrDefault(emptyList())

            val migratedThemeDirs = hashSetOf<String>()

            existingKits.forEach { kit ->
                val binding = kit.binding ?: return@forEach
                val dayThemeEntry = binding.dayTheme?.let { ref ->
                    dayThemes.firstOrNull { it.dirName == ref.dirName || it.packageInfo.name == ref.name }
                }?.also { migratedThemeDirs += "d:${it.dirName}" }
                val nightThemeEntry = binding.nightTheme?.let { ref ->
                    nightThemes.firstOrNull { it.dirName == ref.dirName || it.packageInfo.name == ref.name }
                }?.also { migratedThemeDirs += "n:${it.dirName}" }

                val dayTopEntry = binding.dayTopBar?.let { ref ->
                    dayTopBars.firstOrNull { it.dirName == ref.dirName || it.config.name == ref.name }
                }
                val nightTopEntry = binding.nightTopBar?.let { ref ->
                    nightTopBars.firstOrNull { it.dirName == ref.dirName || it.config.name == ref.name }
                }

                val dayThemeCfg = dayThemeEntry?.let { ThemePackageManager.getConfig(it) }
                val nightThemeCfg = nightThemeEntry?.let { ThemePackageManager.getConfig(it) }
                val dayTopCfg = dayTopEntry?.let { resolveTopBarWallpaperConfig(it) }
                val nightTopCfg = nightTopEntry?.let { resolveTopBarWallpaperConfig(it) }

                val bundle = disassembleLegacyBundle(
                    dayTheme = dayThemeCfg,
                    nightTheme = nightThemeCfg,
                    dayTopBar = dayTopCfg,
                    nightTopBar = nightTopCfg,
                    dayIconSlotId = binding.dayNavigationBar?.dirName,
                    nightIconSlotId = binding.nightNavigationBar?.dirName,
                    dayCoverSlotId = binding.dayCoverCollection?.dirName,
                    nightCoverSlotId = binding.nightCoverCollection?.dirName
                )

                val enrichedParam = mergeGlassAndPresetIntoParam(
                    base = bundle.param,
                    kit = kit
                )
                UnifiedPackageRepository.saveMigratedPair(
                    idSlug = kit.id.ifBlank { kit.name },
                    name = kit.name,
                    art = bundle.art,
                    param = enrichedParam,
                    overwriteExisting = overwriteExisting
                )
            }

            // 3. Migrate remaining standalone local ThemePackages grouped by theme name
            val remainingThemes = dayThemes.filter { "d:${it.dirName}" !in migratedThemeDirs } +
                nightThemes.filter { "n:${it.dirName}" !in migratedThemeDirs }
            remainingThemes.groupBy { it.packageInfo.name }.forEach { (name, group) ->
                val dayCfg = group.firstOrNull { !it.packageInfo.isNightTheme }?.let { ThemePackageManager.getConfig(it) }
                val nightCfg = group.firstOrNull { it.packageInfo.isNightTheme }?.let { ThemePackageManager.getConfig(it) }
                val bundle = disassembleLegacyBundle(dayTheme = dayCfg, nightTheme = nightCfg)
                UnifiedPackageRepository.saveMigratedPair(
                    idSlug = "legacy_theme_$name",
                    name = name,
                    art = bundle.art,
                    param = bundle.param,
                    overwriteExisting = overwriteExisting
                )
            }

            // 4. Migrate standalone TopBar wallpapers that weren't attached to a kit
            (dayTopBars + nightTopBars)
                .filter { it.dirName != TopBarConfig.DEFAULT_DIR_NAME && !it.config.wallpaperPath.isNullOrBlank() }
                .groupBy { it.config.name }
                .forEach { (name, entries) ->
                    val dTop = entries.firstOrNull { !it.config.isNightMode }?.let(::resolveTopBarWallpaperConfig)
                    val nTop = entries.firstOrNull { it.config.isNightMode }?.let(::resolveTopBarWallpaperConfig)
                    val bundle = disassembleLegacyBundle(dayTopBar = dTop, nightTopBar = nTop)
                    UnifiedPackageRepository.saveMigratedPair(
                        idSlug = "legacy_topbar_$name",
                        name = "$name (顶栏壁纸)",
                        art = bundle.art,
                        param = null,
                        overwriteExisting = overwriteExisting
                    )
                }

            // 5. Ensure default Day/Night Art & Param packages exist and 4 pointers are valid
            UnifiedPackageRepository.ensureDefaultPackagesExist(context)

            // 6. Write non-destructive `.migrated` marker in legacy directories (never delete them
            // so v1 Backup / Restore / RestoreSnapshot remain intact for at least 1 major version)
            writeLegacyMigratedMarkers()

            context.putPrefInt(PreferKey.legacyPackageMigrationVersion, CURRENT_MIGRATION_VERSION)
        }
    }

    private fun writeLegacyMigratedMarkers() {
        val markerContent = "migratedVersion=$CURRENT_MIGRATION_VERSION\ntimestamp=${System.currentTimeMillis()}\n"
        listOf(
            ThemePackageManager.rootDir,
            TopBarConfig.rootDir,
            NavigationBarIconConfig.rootDir,
            CoverCollectionManager.rootDir,
            AppearanceKitManager.rootDir
        ).forEach { dir ->
            runCatching {
                if (dir.isDirectory) {
                    PackageIoKernel.writeTextAtomic(File(dir, ".migrated"), markerContent)
                }
            }
        }
    }

    private fun preserveActiveTopBarWallpaper(
        context: Context,
        isNight: Boolean,
        onlyIfUnset: Boolean = false
    ) {
        val wallpaperFile = TopBarConfig.currentWallpaperFile(context, isNight)
        val entry = TopBarConfig.currentEntry(context, isNight)
        val pathKey = if (isNight) PreferKey.topBarBgImageN else PreferKey.topBarBgImage
        val alphaKey = if (isNight) PreferKey.topBarWallpaperAlphaN else PreferKey.topBarWallpaperAlpha
        if (onlyIfUnset && !context.getPrefString(pathKey).isNullOrBlank()) {
            return
        }
        if (wallpaperFile != null) {
            context.putPrefString(pathKey, wallpaperFile.absolutePath)
            context.putPrefInt(alphaKey, entry.config.wallpaperAlpha.coerceIn(0, 100))
        }
    }

    private fun resolveTopBarWallpaperConfig(entry: TopBarConfig.Entry): TopBarConfig.Config {
        val rawPath = entry.config.wallpaperPath?.takeIf { it.isNotBlank() } ?: return entry.config
        val resolved = if (File(rawPath).isAbsolute || entry.localDir == null) {
            rawPath
        } else {
            File(entry.localDir, rawPath).absolutePath
        }
        return entry.config.copy(wallpaperPath = resolved)
    }

    private fun mergeGlassAndPresetIntoParam(
        base: ParamPackagePayload?,
        kit: io.legado.app.help.config.AppearanceKit
    ): ParamPackagePayload? {
        val glass = kit.binding?.glass
        val preset = kit.binding?.preset
        val hideSearch = kit.binding?.floatingBottomBarHideSearch
        if (glass == null && preset == null && base == null) return null

        val mergedSurface = (base?.surfaceGlobal ?: SurfaceGlobalParams()).copy(
            materialMode = glass?.mode ?: base?.surfaceGlobal?.materialMode,
            frostedLevel = glass?.frostedLevel ?: base?.surfaceGlobal?.frostedLevel,
            liquidLevel = glass?.liquidLevel ?: base?.surfaceGlobal?.liquidLevel,
            blurScale = glass?.blurScale ?: base?.surfaceGlobal?.blurScale,
            shellScale = glass?.shellScale ?: base?.surfaceGlobal?.shellScale,
            tintScale = glass?.tintScale ?: base?.surfaceGlobal?.tintScale,
            strokeScale = glass?.strokeScale ?: base?.surfaceGlobal?.strokeScale,
            strokeColor = glass?.strokeColor?.takeIf { it.isNotBlank() } ?: base?.surfaceGlobal?.strokeColor,
            presetMatched = glass?.presetMatched ?: base?.surfaceGlobal?.presetMatched,
            hardwareBlur = glass?.hardwareBlur ?: base?.surfaceGlobal?.hardwareBlur,
            animatedSolid = glass?.animatedSolid ?: base?.surfaceGlobal?.animatedSolid
        )
        val mergedLayout = if (preset != null || hideSearch != null || base?.layout != null) {
            (base?.layout ?: LayoutParams()).copy(
                navigationPreset = preset ?: base?.layout?.navigationPreset,
                floatingBottomBarHideSearch = hideSearch ?: base?.layout?.floatingBottomBarHideSearch
            )
        } else {
            null
        }
        return ParamPackagePayload(
            surfaceGlobal = mergedSurface.takeIf { it.hasAnyValue() },
            surfaceScopes = base?.surfaceScopes,
            layout = mergedLayout
        )
    }

    private fun ThemeConfig.Config.toArtPalette(): ArtPaletteSpec = ArtPaletteSpec(
        primaryColor = primaryColor,
        accentColor = accentColor,
        backgroundColor = backgroundColor,
        bottomBackground = bottomBackground,
        cardColor = cardColor,
        mutedColor = mutedColor,
        searchFieldBackgroundColor = searchFieldBackgroundColor,
        tabBackgroundColor = tabBackgroundColor,
        shelfColor = shelfColor,
        panelBorderColor = panelBorderColor,
        uiFontColor = uiFontColor,
        titleFontColor = titleFontColor
    )

    private fun toArtWallpapers(
        theme: ThemeConfig.Config?,
        topBar: TopBarConfig.Config?
    ): ArtWallpapersSpec? {
        val spec = ArtWallpapersSpec(
            main = theme?.backgroundImgPath?.takeIf { it.isNotBlank() },
            mainCrop = theme?.backgroundImgCrop?.takeIf { it.isNotBlank() },
            topBar = topBar?.wallpaperPath?.takeIf { it.isNotBlank() },
            topBarCropLeft = topBar?.wallpaperCropLeft,
            topBarCropTop = topBar?.wallpaperCropTop,
            topBarCropRight = topBar?.wallpaperCropRight,
            topBarCropBottom = topBar?.wallpaperCropBottom,
            topBarAlpha = topBar?.wallpaperAlpha,
            bookInfo = theme?.bookInfoBackgroundImgPath?.takeIf { it.isNotBlank() },
            panel = theme?.panelBackgroundImgPath?.takeIf { it.isNotBlank() },
            panelScaleType = theme?.panelBackgroundScaleType?.takeIf { it.isNotBlank() }
        )
        return spec.takeIf {
            it.main != null || it.topBar != null || it.bookInfo != null || it.panel != null
        }
    }

    private fun ThemeConfig.Config.toArtTypography(): ArtTypographySpec? {
        val ui = uiFontPath?.takeIf { it.isNotBlank() }
        val title = titleFontPath?.takeIf { it.isNotBlank() }
        if (ui == null && title == null) return null
        return ArtTypographySpec(uiFontPath = ui, titleFontPath = title)
    }

    private fun SurfaceGlobalParams.hasAnyValue(): Boolean {
        return materialMode != null ||
            frostedLevel != null ||
            liquidLevel != null ||
            blurScale != null ||
            shellScale != null ||
            tintScale != null ||
            strokeScale != null ||
            strokeColor != null ||
            presetMatched != null ||
            hardwareBlur != null ||
            animatedSolid != null ||
            cornerBaseScale != null ||
            surfaceAlpha != null ||
            dialogAlpha != null ||
            panelBorderAlpha != null ||
            cardShadow != null ||
            cardBackgroundBlur != null ||
            wallpaperBlurDay != null ||
            wallpaperBlurNight != null ||
            uiCornerSearchFollow != null ||
            uiCornerReplyFollow != null
    }
}
