package io.legado.app.help.config.pkg

import android.content.Context
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.MainLayoutPresetConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.PrefBatchTransaction
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.lib.theme.defaultThemeTextColorHex
import io.legado.app.lib.theme.recomputeThemeUiSignatureAfterBatch
import io.legado.app.model.BookCover
import io.legado.app.ui.main.explore.resolveExploreGlassMode
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.isSameOrSubFileOf
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Orthogonal package repository managing immutable [PackageKind.ART] and [PackageKind.PARAM] presets.
 *
 * Runtime single source of truth remains `PreferKey` (via [PrefBatchTransaction]):
 * - Applying an Art package writes ONLY sensory assets (colors, wallpapers, fonts, mounts);
 * - Applying a Param package writes ONLY the user-selected sections (`@surface` and/or `@layout`);
 * - Tuning sliders in Appearance Studio writes runtime `PreferKey` and marks `paramPackageDirty = true`,
 *   never mutating package files on disk.
 */
object UnifiedPackageRepository {

    private const val MAX_MANIFEST_BYTES = 2L * 1024L * 1024L
    private val repoMutex = Mutex()

    data class StoredEntry(
        val manifest: UnifiedPackageManifest,
        val dir: File
    )

    data class UnifiedImportResult(
        val importedArt: StoredEntry? = null,
        val importedParam: StoredEntry? = null,
        val legacyTotal: Int = 0
    ) {
        val total: Int
            get() = (if (importedArt != null) 1 else 0) +
                (if (importedParam != null) 1 else 0) +
                legacyTotal
    }

    val rootDir: File
        get() = appCtx.externalFiles.getFile("unifiedPackages").apply { mkdirs() }

    private val artDir: File
        get() = rootDir.getFile("art").apply { mkdirs() }

    private val paramDir: File
        get() = rootDir.getFile("param").apply { mkdirs() }

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("unifiedPackageTemp").apply { mkdirs() }

    suspend fun listArtPackages(): List<StoredEntry> = withContext(IO) {
        repoMutex.withLock {
            loadEntries(artDir, PackageKind.ART)
        }
    }

    suspend fun listParamPackages(): List<StoredEntry> = withContext(IO) {
        repoMutex.withLock {
            loadEntries(paramDir, PackageKind.PARAM)
        }
    }

    fun isParamDirty(context: Context = appCtx): Boolean {
        return context.getPrefBoolean(PreferKey.paramPackageDirty, false)
    }

    fun markParamDirty(context: Context = appCtx, dirty: Boolean = true) {
        context.putPrefBoolean(PreferKey.paramPackageDirty, dirty)
    }

    fun currentArtPackageId(context: Context = appCtx): String {
        return context.getPrefString(PreferKey.currentArtPackageId, "").orEmpty()
    }

    fun currentParamPackageId(context: Context = appCtx): String {
        return context.getPrefString(PreferKey.currentParamPackageId, "").orEmpty()
    }

    suspend fun saveMigratedPair(
        idSlug: String,
        name: String,
        art: ArtPackagePayload?,
        param: ParamPackagePayload?
    ) = withContext(IO) {
        repoMutex.withLock {
            val now = System.currentTimeMillis()
            val cleanSlug = idSlug.normalizeFileName().ifBlank { "migrated_${UUID.randomUUID()}" }
            if (art != null) {
                val artId = "art_$cleanSlug"
                val targetDir = File(artDir, PackageIoKernel.uniqueDirName(artDir, cleanSlug) { dir ->
                    readManifest(dir)?.id == artId
                }).apply { mkdirs() }
                val packagedArt = copyArtAssetsIntoDir(art, targetDir)
                val manifest = UnifiedPackageManifest(
                    kind = PackageKind.ART,
                    id = artId,
                    name = name.ifBlank { cleanSlug },
                    updatedAt = now,
                    art = packagedArt
                )
                writeManifest(targetDir, manifest)
            }
            if (param != null) {
                val paramId = "param_$cleanSlug"
                val targetDir = File(paramDir, PackageIoKernel.uniqueDirName(paramDir, cleanSlug) { dir ->
                    readManifest(dir)?.id == paramId
                }).apply { mkdirs() }
                val manifest = UnifiedPackageManifest(
                    kind = PackageKind.PARAM,
                    id = paramId,
                    name = name.ifBlank { cleanSlug },
                    updatedAt = now,
                    param = param
                )
                writeManifest(targetDir, manifest)
            }
        }
    }

    suspend fun captureArtFromCurrent(
        context: Context,
        name: String
    ): StoredEntry = withContext(IO) {
        val cleanName = name.trim().ifBlank { context.getString(R.string.appearance_kit_manage) }
        repoMutex.withLock {
            val dirName = PackageIoKernel.uniqueDirName(artDir, cleanName)
            val targetDir = File(artDir, dirName).apply { mkdirs() }
            val rawArt = ArtPackagePayload(
                dayPalette = capturePalette(context, false),
                nightPalette = capturePalette(context, true),
                dayWallpapers = captureWallpapers(context, false),
                nightWallpapers = captureWallpapers(context, true),
                dayTypography = captureTypography(context, false),
                nightTypography = captureTypography(context, true),
                mounts = ArtAssetMounts(
                    dayIconSlotId = NavigationBarIconConfig.activeDirName(false)
                        .takeIf { it != NavigationBarIconConfig.DEFAULT_DIR_NAME },
                    nightIconSlotId = NavigationBarIconConfig.activeDirName(true)
                        .takeIf { it != NavigationBarIconConfig.DEFAULT_DIR_NAME },
                    dayCoverSlotId = context.getPrefString(PreferKey.coverCollectionDay, "")
                        ?.takeIf { it.isNotBlank() },
                    nightCoverSlotId = context.getPrefString(PreferKey.coverCollectionNight, "")
                        ?.takeIf { it.isNotBlank() }
                )
            )
            val packagedArt = copyArtAssetsIntoDir(rawArt, targetDir)
            val manifest = UnifiedPackageManifest(
                kind = PackageKind.ART,
                id = "art_${UUID.randomUUID()}",
                name = cleanName,
                updatedAt = System.currentTimeMillis(),
                art = packagedArt
            )
            writeManifest(targetDir, manifest)
            context.putPrefString(PreferKey.currentArtPackageId, manifest.id)
            StoredEntry(manifest, targetDir)
        }
    }

    suspend fun captureParamFromCurrent(
        context: Context,
        name: String,
        includeSurface: Boolean = true,
        includeLayout: Boolean = true
    ): StoredEntry = withContext(IO) {
        val cleanName = name.trim().ifBlank { "Param Preset" }
        repoMutex.withLock {
            val dirName = PackageIoKernel.uniqueDirName(paramDir, cleanName)
            val targetDir = File(paramDir, dirName).apply { mkdirs() }
            val payload = ParamPackagePayload(
                surfaceGlobal = if (includeSurface) captureSurfaceGlobal(context) else null,
                surfaceScopes = if (includeSurface) captureSurfaceScopes(context) else null,
                layout = if (includeLayout) captureLayoutParams(context) else null
            )
            val manifest = UnifiedPackageManifest(
                kind = PackageKind.PARAM,
                id = "param_${UUID.randomUUID()}",
                name = cleanName,
                updatedAt = System.currentTimeMillis(),
                param = payload
            )
            writeManifest(targetDir, manifest)
            context.putPrefString(PreferKey.currentParamPackageId, manifest.id)
            context.putPrefBoolean(PreferKey.paramPackageDirty, false)
            StoredEntry(manifest, targetDir)
        }
    }

    suspend fun applyArtPackage(
        context: Context,
        entry: StoredEntry,
        applyMounts: Boolean = true
    ) {
        val art = entry.manifest.art ?: return
        PrefBatchTransaction.beginBatch()
        try {
            art.dayPalette?.let { applyPalette(context, isNight = false, it) }
            art.nightPalette?.let { applyPalette(context, isNight = true, it) }
            art.dayWallpapers?.let { applyWallpapers(context, entry.dir, isNight = false, it) }
            art.nightWallpapers?.let { applyWallpapers(context, entry.dir, isNight = true, it) }
            art.dayTypography?.let { applyTypography(context, entry.dir, isNight = false, it) }
            art.nightTypography?.let { applyTypography(context, entry.dir, isNight = true, it) }
            if (applyMounts) {
                applyMounts(context, art.mounts)
            }
            context.putPrefString(PreferKey.currentArtPackageId, entry.manifest.id)
        } finally {
            PrefBatchTransaction.endBatch {
                context.applicationContext.recomputeThemeUiSignatureAfterBatch()
            }
        }
        ThemeConfig.applyTheme(context)
        BookCover.upDefaultCover()
        postEvent(EventBus.MAIN_THEME_BACKGROUND_CHANGED, AppConfig.isNightTheme)
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
    }

    suspend fun applyParamPackage(
        context: Context,
        entry: StoredEntry,
        applySurface: Boolean = true,
        applyLayout: Boolean = true
    ) {
        val param = entry.manifest.param ?: return
        PrefBatchTransaction.beginBatch()
        try {
            if (applySurface) {
                param.surfaceGlobal?.let { applySurfaceGlobal(context, it) }
                applySurfaceScopes(context, param.surfaceScopes)
            }
            if (applyLayout) {
                param.layout?.let { applyLayoutParams(context, it) }
            }
            context.putPrefString(PreferKey.currentParamPackageId, entry.manifest.id)
            context.putPrefBoolean(PreferKey.paramPackageDirty, !(applySurface && applyLayout))
        } finally {
            PrefBatchTransaction.endBatch {
                context.applicationContext.recomputeThemeUiSignatureAfterBatch()
            }
        }
        if (applyLayout) {
            postEvent(EventBus.NOTIFY_MAIN, true)
            postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
        }
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
    }

    suspend fun exportEntryZip(entry: StoredEntry): File = withContext(IO) {
        val outZip = File(tempDir, "${entry.manifest.kind.id}_${entry.dir.name}_${System.currentTimeMillis()}.zip")
        PackageIoKernel.zipDirectoryAtomic(entry.dir, outZip)
        outZip
    }

    /**
     * Inspects whether a file is a unified `param` or `kit` package so UI can prompt the user
     * for `@surface` / `@layout` checkboxes before applying.
     */
    suspend fun peekKind(file: File): PackageKind? = withContext(IO) {
        if (!file.isFile) return@withContext null
        val textPeek = runCatching {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(16)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8).trimStart() else ""
            }
            head.startsWith("{")
        }.getOrDefault(false)
        if (textPeek) {
            return@withContext runCatching {
                UnifiedPackageCodec.decode(file.readText()).kind
            }.getOrNull()
        }
        val unzipDir = File(tempDir, "peek_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            PackageIoKernel.extractZipSafe(file, unzipDir) {
                it == UnifiedPackageManifest.MANIFEST_FILE_NAME ||
                    it.endsWith("/${UnifiedPackageManifest.MANIFEST_FILE_NAME}")
            }
            val manifestFile = unzipDir.walkTopDown().firstOrNull {
                it.isFile && it.name == UnifiedPackageManifest.MANIFEST_FILE_NAME
            } ?: return@withContext null
            UnifiedPackageCodec.decode(manifestFile.readText()).kind
        } catch (_: Throwable) {
            null
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    suspend fun importFile(
        context: Context,
        file: File,
        importSurface: Boolean = true,
        importLayout: Boolean = false
    ): UnifiedImportResult = withContext(IO) {
        val kind = peekKind(file)
        if (kind == null) {
            val legacy = AppearanceKitManager.importPackage(file)
            LegacyPackageMigrator.migrateLocalPackagesIfNeeded(context)
            return@withContext UnifiedImportResult(legacyTotal = legacy.total)
        }
        val isRawJson = file.inputStream().use { it.read() == '{'.code }
        if (isRawJson) {
            val manifest = UnifiedPackageCodec.decode(file.readText())
            return@withContext importManifestOnly(context, manifest, importSurface, importLayout)
        }
        val unzipDir = File(tempDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            PackageIoKernel.extractZipSafe(file, unzipDir)
            val manifestFile = unzipDir.walkTopDown().firstOrNull {
                it.isFile && it.name == UnifiedPackageManifest.MANIFEST_FILE_NAME
            } ?: throw IllegalArgumentException("Missing ${UnifiedPackageManifest.MANIFEST_FILE_NAME}")
            val packageRoot = manifestFile.parentFile ?: unzipDir
            val manifest = UnifiedPackageCodec.decode(manifestFile.readText())
            repoMutex.withLock {
                var importedArt: StoredEntry? = null
                var importedParam: StoredEntry? = null
                if (manifest.art != null && (manifest.kind == PackageKind.ART || manifest.kind == PackageKind.KIT)) {
                    val slug = PackageIoKernel.uniqueDirName(artDir, manifest.name)
                    val dst = File(artDir, slug).apply { mkdirs() }
                    packageRoot.copyRecursively(dst, overwrite = true)
                    val artManifest = manifest.copy(
                        kind = PackageKind.ART,
                        id = manifest.id.ifBlank { "art_$slug" },
                        param = null
                    )
                    writeManifest(dst, artManifest)
                    importedArt = StoredEntry(artManifest, dst)
                }
                if (manifest.param != null && (manifest.kind == PackageKind.PARAM || manifest.kind == PackageKind.KIT)) {
                    val slug = PackageIoKernel.uniqueDirName(paramDir, manifest.name)
                    val dst = File(paramDir, slug).apply { mkdirs() }
                    val filteredParam = ParamPackagePayload(
                        surfaceGlobal = if (importSurface) manifest.param.surfaceGlobal else null,
                        surfaceScopes = if (importSurface) manifest.param.surfaceScopes else null,
                        layout = if (importLayout) manifest.param.layout else null,
                        unknownFields = manifest.param.unknownFields
                    )
                    val paramManifest = manifest.copy(
                        kind = PackageKind.PARAM,
                        id = manifest.id.ifBlank { "param_$slug" },
                        art = null,
                        param = filteredParam
                    )
                    writeManifest(dst, paramManifest)
                    importedParam = StoredEntry(paramManifest, dst)
                }
                UnifiedImportResult(importedArt = importedArt, importedParam = importedParam)
            }
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private suspend fun importManifestOnly(
        context: Context,
        manifest: UnifiedPackageManifest,
        importSurface: Boolean,
        importLayout: Boolean
    ): UnifiedImportResult = repoMutex.withLock {
        var importedArt: StoredEntry? = null
        var importedParam: StoredEntry? = null
        if (manifest.art != null && (manifest.kind == PackageKind.ART || manifest.kind == PackageKind.KIT)) {
            val slug = PackageIoKernel.uniqueDirName(artDir, manifest.name)
            val dst = File(artDir, slug).apply { mkdirs() }
            val m = manifest.copy(kind = PackageKind.ART, param = null)
            writeManifest(dst, m)
            importedArt = StoredEntry(m, dst)
        }
        if (manifest.param != null && (manifest.kind == PackageKind.PARAM || manifest.kind == PackageKind.KIT)) {
            val slug = PackageIoKernel.uniqueDirName(paramDir, manifest.name)
            val dst = File(paramDir, slug).apply { mkdirs() }
            val filtered = ParamPackagePayload(
                surfaceGlobal = if (importSurface) manifest.param.surfaceGlobal else null,
                surfaceScopes = if (importSurface) manifest.param.surfaceScopes else null,
                layout = if (importLayout) manifest.param.layout else null,
                unknownFields = manifest.param.unknownFields
            )
            val m = manifest.copy(kind = PackageKind.PARAM, art = null, param = filtered)
            writeManifest(dst, m)
            importedParam = StoredEntry(m, dst)
        }
        UnifiedImportResult(importedArt = importedArt, importedParam = importedParam)
    }

    private fun loadEntries(root: File, expectedKind: PackageKind): List<StoredEntry> {
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                readManifest(dir)
                    ?.takeIf { it.kind == expectedKind }
                    ?.let { StoredEntry(it, dir) }
            }
            ?.sortedByDescending { it.manifest.updatedAt }
            .orEmpty()
    }

    private fun readManifest(dir: File): UnifiedPackageManifest? {
        val file = File(dir, UnifiedPackageManifest.MANIFEST_FILE_NAME)
        val text = PackageIoKernel.readTextWithRecovery(file, MAX_MANIFEST_BYTES) ?: return null
        return runCatching { UnifiedPackageCodec.decode(text) }.getOrNull()
    }

    private fun writeManifest(dir: File, manifest: UnifiedPackageManifest) {
        val file = File(dir, UnifiedPackageManifest.MANIFEST_FILE_NAME)
        val json = UnifiedPackageCodec.encode(manifest)
        PackageIoKernel.writeTextAtomic(file, json)
    }

    private fun copyArtAssetsIntoDir(art: ArtPackagePayload, packageDir: File): ArtPackagePayload {
        val assetsDir = File(packageDir, "assets").apply { mkdirs() }
        fun copyFile(path: String?, prefix: String): String? {
            val trimmed = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
            val src = if (File(trimmed).isAbsolute) File(trimmed) else File(packageDir, trimmed)
            if (!src.isFile) return null
            if (src.isSameOrSubFileOf(packageDir)) {
                return src.relativeTo(packageDir).path.replace('\\', '/')
            }
            val ext = src.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            val dst = File(assetsDir, "$prefix$ext")
            src.copyTo(dst, overwrite = true)
            return "assets/${dst.name}"
        }
        return art.copy(
            dayWallpapers = art.dayWallpapers?.copy(
                main = copyFile(art.dayWallpapers.main, "day_main"),
                topBar = copyFile(art.dayWallpapers.topBar, "day_topbar"),
                bottomBar = copyFile(art.dayWallpapers.bottomBar, "day_bottombar"),
                sidebar = copyFile(art.dayWallpapers.sidebar, "day_sidebar"),
                bookInfo = copyFile(art.dayWallpapers.bookInfo, "day_book_info"),
                panel = copyFile(art.dayWallpapers.panel, "day_panel"),
                welcome = copyFile(art.dayWallpapers.welcome, "day_welcome")
            ),
            nightWallpapers = art.nightWallpapers?.copy(
                main = copyFile(art.nightWallpapers.main, "night_main"),
                topBar = copyFile(art.nightWallpapers.topBar, "night_topbar"),
                bottomBar = copyFile(art.nightWallpapers.bottomBar, "night_bottombar"),
                sidebar = copyFile(art.nightWallpapers.sidebar, "night_sidebar"),
                bookInfo = copyFile(art.nightWallpapers.bookInfo, "night_book_info"),
                panel = copyFile(art.nightWallpapers.panel, "night_panel"),
                welcome = copyFile(art.nightWallpapers.welcome, "night_welcome")
            ),
            dayTypography = art.dayTypography?.copy(
                uiFontPath = copyFile(art.dayTypography.uiFontPath, "day_ui_font"),
                titleFontPath = copyFile(art.dayTypography.titleFontPath, "day_title_font")
            ),
            nightTypography = art.nightTypography?.copy(
                uiFontPath = copyFile(art.nightTypography.uiFontPath, "night_ui_font"),
                titleFontPath = copyFile(art.nightTypography.titleFontPath, "night_title_font")
            )
        )
    }

    private fun resolvePackageAssetPath(packageDir: File, path: String?): String? {
        val trimmed = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        val file = File(trimmed)
        if (file.isAbsolute) return file.absolutePath.takeIf { file.isFile }
        val resolved = File(packageDir, trimmed)
        return resolved.absolutePath.takeIf { resolved.isSameOrSubFileOf(packageDir) && resolved.isFile }
    }

    private fun capturePalette(context: Context, isNight: Boolean): ArtPaletteSpec {
        val primary = context.getPrefInt(
            if (isNight) PreferKey.cNPrimary else PreferKey.cPrimary,
            context.getCompatColor(if (isNight) R.color.md_blue_grey_600 else R.color.md_brown_500)
        )
        val accent = context.getPrefInt(
            if (isNight) PreferKey.cNAccent else PreferKey.cAccent,
            context.getCompatColor(if (isNight) R.color.md_deep_orange_800 else R.color.md_red_600)
        )
        val background = context.getPrefInt(
            if (isNight) PreferKey.cNBackground else PreferKey.cBackground,
            context.getCompatColor(if (isNight) R.color.md_grey_900 else R.color.md_grey_100)
        )
        val bottom = context.getPrefInt(
            if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground,
            context.getCompatColor(if (isNight) R.color.md_grey_850 else R.color.md_grey_200)
        )
        return ArtPaletteSpec(
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bottom.hexString}",
            cardColor = context.getPrefString(ThemeRuntimeKeys.themeCardColor(isNight)),
            mutedColor = context.getPrefString(ThemeRuntimeKeys.themeMutedColor(isNight)),
            searchFieldBackgroundColor = context.getPrefString(ThemeRuntimeKeys.themeSearchFieldBackgroundColor(isNight)),
            tabBackgroundColor = context.getPrefString(ThemeRuntimeKeys.themeTabBackgroundColor(isNight)),
            shelfColor = context.getPrefString(ThemeRuntimeKeys.themeShelfColor(isNight)),
            panelBorderColor = context.getPrefString(if (isNight) PreferKey.panelBorderColorN else PreferKey.panelBorderColor),
            uiFontColor = context.getPrefString(ThemeRuntimeKeys.uiFontColor(isNight))
                ?: defaultThemeTextColorHex(isNight),
            titleFontColor = context.getPrefString(ThemeRuntimeKeys.titleFontColor(isNight))
                ?: defaultThemeTextColorHex(isNight)
        )
    }

    private fun captureWallpapers(context: Context, isNight: Boolean): ArtWallpapersSpec {
        return ArtWallpapersSpec(
            main = context.getPrefString(if (isNight) PreferKey.bgImageN else PreferKey.bgImage),
            mainCrop = context.getPrefString(if (isNight) PreferKey.bgImageNCrop else PreferKey.bgImageCrop),
            topBar = context.getPrefString(if (isNight) PreferKey.topBarBgImageN else PreferKey.topBarBgImage)
                ?: TopBarConfig.currentWallpaperFile(context, isNight)?.absolutePath,
            topBarAlpha = context.getPrefInt(
                if (isNight) PreferKey.topBarWallpaperAlphaN else PreferKey.topBarWallpaperAlpha,
                100
            ),
            bookInfo = context.getPrefString(if (isNight) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            panel = context.getPrefString(if (isNight) PreferKey.panelBgImageN else PreferKey.panelBgImage),
            panelScaleType = context.getPrefString(if (isNight) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType)
                ?: ThemeConfig.PANEL_BG_CROP,
            welcome = context.getPrefString(if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage)
        )
    }

    private fun captureTypography(context: Context, isNight: Boolean): ArtTypographySpec {
        return ArtTypographySpec(
            uiFontPath = context.getPrefString(ThemeRuntimeKeys.uiFontPath(isNight)),
            titleFontPath = context.getPrefString(ThemeRuntimeKeys.titleFontPath(isNight))
        )
    }

    private fun captureSurfaceGlobal(context: Context): SurfaceGlobalParams {
        val isNight = AppConfig.isNightTheme
        val storedMode = context.getPrefString(PreferKey.exploreGlassEffectMode, "").orEmpty()
        val legacyEnabled = context.getPrefBoolean(PreferKey.exploreGlassEnabled, true)
        return SurfaceGlobalParams(
            materialMode = resolveExploreGlassMode(storedMode, legacyEnabled),
            frostedLevel = context.getPrefInt(PreferKey.frostedGlassLevel, 68),
            liquidLevel = context.getPrefInt(PreferKey.liquidGlassLevel, 68),
            blurScale = context.getPrefInt(PreferKey.exploreGlassBlurScale, 100),
            shellScale = context.getPrefInt(PreferKey.exploreGlassShellScale, 100),
            tintScale = context.getPrefInt(PreferKey.exploreGlassTintScale, 100),
            strokeScale = context.getPrefInt(PreferKey.exploreGlassStrokeScale, 100),
            strokeColor = context.getPrefString(PreferKey.glassStrokeColor, ""),
            presetMatched = context.getPrefBoolean(PreferKey.glassPresetMatched, true),
            hardwareBlur = context.getPrefBoolean(PreferKey.exploreGlassHardwareBlur, true),
            animatedSolid = context.getPrefBoolean(PreferKey.exploreGlassAnimatedSolid, true),
            cornerBaseScale = context.getPrefString(ThemeRuntimeKeys.uiCornerScale(isNight), "1")
                ?.toFloatOrNull() ?: 1f,
            surfaceAlpha = context.getPrefInt(ThemeRuntimeKeys.uiLayoutAlpha(isNight), 100),
            dialogAlpha = context.getPrefInt(ThemeRuntimeKeys.dialogAlpha(isNight), 100),
            panelBorderAlpha = context.getPrefInt(
                if (isNight) PreferKey.panelBorderAlphaN else PreferKey.panelBorderAlpha,
                100
            ),
            cardShadow = context.getPrefInt(ThemeRuntimeKeys.themeCardShadow(isNight), 0),
            cardBackgroundBlur = context.getPrefInt(ThemeRuntimeKeys.themeCardBackgroundBlur(isNight), 0) / 10f,
            wallpaperBlurDay = context.getPrefInt(PreferKey.bgImageBlurring, 0),
            wallpaperBlurNight = context.getPrefInt(PreferKey.bgImageNBlurring, 0),
            uiCornerSearchFollow = context.getPrefBoolean(ThemeRuntimeKeys.uiCornerSearchFollow(isNight), false),
            uiCornerReplyFollow = context.getPrefBoolean(ThemeRuntimeKeys.uiCornerReplyFollow(isNight), false)
        )
    }

    private fun captureSurfaceScopes(context: Context): SurfaceScopesParams? {
        val topBar = UnifiedPackageCodec.decodeScopeOverride(context.getPrefString(PreferKey.scopeOverrideTopBar))
            ?.takeUnless { it.isEmpty() }
        val bottomBar = UnifiedPackageCodec.decodeScopeOverride(context.getPrefString(PreferKey.scopeOverrideBottomBar))
            ?.takeUnless { it.isEmpty() }
        val bookshelf = UnifiedPackageCodec.decodeScopeOverride(context.getPrefString(PreferKey.scopeOverrideBookshelfCard))
            ?.takeUnless { it.isEmpty() }
        val dialog = UnifiedPackageCodec.decodeScopeOverride(context.getPrefString(PreferKey.scopeOverrideDialog))
            ?.takeUnless { it.isEmpty() }
        if (topBar == null && bottomBar == null && bookshelf == null && dialog == null) return null
        return SurfaceScopesParams(
            topBar = topBar,
            bottomBar = bottomBar,
            bookshelfCard = bookshelf,
            dialog = dialog
        )
    }

    private fun captureLayoutParams(context: Context): LayoutParams {
        val isNight = AppConfig.isNightTheme
        val densityRaw = context.getPrefString(PreferKey.uiDensityScale, "1.0")?.toFloatOrNull() ?: 1.0f
        return LayoutParams(
            navigationPreset = MainLayoutPresetConfig.currentPreset(),
            sidebarGravity = AppConfig.bottomBarSidebarGravity,
            topBarFloatingChrome = AppConfig.topBarFloatingChrome,
            defaultTopBarShowSearch = AppConfig.defaultTopBarShowSearch,
            floatingBottomBarHideSearch = AppConfig.floatingBottomBarHideSearch,
            topBarExpandFilters = AppConfig.topBarExpandFilters,
            topBarHideFilterToggle = AppConfig.topBarHideFilterToggle,
            densityScale = densityRaw.coerceIn(0.8f, 1.3f),
            fontScale = context.getPrefInt(ThemeRuntimeKeys.fontScale(isNight), 0),
            discoveryPageLayout = AppConfig.discoveryPageLayout,
            rssSourceLayout = AppConfig.rssSourceLayout,
            homepageLayoutMode = AppConfig.homepageLayoutMode
        )
    }

    private fun applyPalette(context: Context, isNight: Boolean, p: ArtPaletteSpec) {
        p.primaryColor?.let { hex ->
            runCatching { hex.toColorInt() }.getOrNull()?.let {
                context.putPrefInt(if (isNight) PreferKey.cNPrimary else PreferKey.cPrimary, it)
            }
        }
        p.accentColor?.let { hex ->
            runCatching { hex.toColorInt() }.getOrNull()?.let {
                context.putPrefInt(if (isNight) PreferKey.cNAccent else PreferKey.cAccent, it)
            }
        }
        p.backgroundColor?.let { hex ->
            runCatching { hex.toColorInt() }.getOrNull()?.let {
                context.putPrefInt(if (isNight) PreferKey.cNBackground else PreferKey.cBackground, it)
            }
        }
        p.bottomBackground?.let { hex ->
            runCatching { hex.toColorInt() }.getOrNull()?.let {
                context.putPrefInt(if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground, it)
            }
        }
        putOrRemovePref(context, ThemeRuntimeKeys.themeCardColor(isNight), p.cardColor)
        putOrRemovePref(context, ThemeRuntimeKeys.themeMutedColor(isNight), p.mutedColor)
        putOrRemovePref(context, ThemeRuntimeKeys.themeSearchFieldBackgroundColor(isNight), p.searchFieldBackgroundColor)
        putOrRemovePref(context, ThemeRuntimeKeys.themeTabBackgroundColor(isNight), p.tabBackgroundColor)
        putOrRemovePref(context, ThemeRuntimeKeys.themeShelfColor(isNight), p.shelfColor)
        putOrRemovePref(
            context,
            if (isNight) PreferKey.panelBorderColorN else PreferKey.panelBorderColor,
            p.panelBorderColor
        )
        putOrRemovePref(context, ThemeRuntimeKeys.uiFontColor(isNight), p.uiFontColor)
        putOrRemovePref(context, ThemeRuntimeKeys.titleFontColor(isNight), p.titleFontColor)
    }

    private fun applyWallpapers(
        context: Context,
        packageDir: File,
        isNight: Boolean,
        w: ArtWallpapersSpec
    ) {
        putOrRemovePref(
            context,
            if (isNight) PreferKey.bgImageN else PreferKey.bgImage,
            resolvePackageAssetPath(packageDir, w.main)
        )
        putOrRemovePref(
            context,
            if (isNight) PreferKey.bgImageNCrop else PreferKey.bgImageCrop,
            w.mainCrop
        )
        putOrRemovePref(
            context,
            if (isNight) PreferKey.topBarBgImageN else PreferKey.topBarBgImage,
            resolvePackageAssetPath(packageDir, w.topBar)
        )
        w.topBarAlpha?.let {
            context.putPrefInt(
                if (isNight) PreferKey.topBarWallpaperAlphaN else PreferKey.topBarWallpaperAlpha,
                it.coerceIn(0, 100)
            )
        }
        putOrRemovePref(
            context,
            if (isNight) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage,
            resolvePackageAssetPath(packageDir, w.bookInfo)
        )
        putOrRemovePref(
            context,
            if (isNight) PreferKey.panelBgImageN else PreferKey.panelBgImage,
            resolvePackageAssetPath(packageDir, w.panel)
        )
        putOrRemovePref(
            context,
            if (isNight) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType,
            w.panelScaleType ?: ThemeConfig.PANEL_BG_CROP
        )
        w.welcome?.let {
            putOrRemovePref(
                context,
                if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage,
                resolvePackageAssetPath(packageDir, it)
            )
        }
    }

    private fun applyTypography(
        context: Context,
        packageDir: File,
        isNight: Boolean,
        t: ArtTypographySpec
    ) {
        putOrRemovePref(
            context,
            ThemeRuntimeKeys.uiFontPath(isNight),
            resolvePackageAssetPath(packageDir, t.uiFontPath)
        )
        putOrRemovePref(
            context,
            ThemeRuntimeKeys.titleFontPath(isNight),
            resolvePackageAssetPath(packageDir, t.titleFontPath)
        )
    }

    private suspend fun applyMounts(context: Context, mounts: ArtAssetMounts) {
        mounts.dayIconSlotId?.let { id ->
            NavigationBarIconConfig.loadLocalOnlyForKit(false)
                .firstOrNull { it.dirName == id || it.config.name == id }
                ?.let { NavigationBarIconConfig.select(it) }
        }
        mounts.nightIconSlotId?.let { id ->
            NavigationBarIconConfig.loadLocalOnlyForKit(true)
                .firstOrNull { it.dirName == id || it.config.name == id }
                ?.let { NavigationBarIconConfig.select(it) }
        }
        mounts.dayCoverSlotId?.let { id ->
            CoverCollectionManager.setSelected(false, id)
        }
        mounts.nightCoverSlotId?.let { id ->
            CoverCollectionManager.setSelected(true, id)
        }
    }

    private fun applySurfaceGlobal(context: Context, g: SurfaceGlobalParams) {
        g.materialMode?.takeIf { it.isNotBlank() }?.let {
            context.putPrefString(PreferKey.exploreGlassEffectMode, it)
        }
        g.frostedLevel?.let { context.putPrefInt(PreferKey.frostedGlassLevel, it.coerceIn(0, 100)) }
        g.liquidLevel?.let { context.putPrefInt(PreferKey.liquidGlassLevel, it.coerceIn(0, 100)) }
        g.blurScale?.let { context.putPrefInt(PreferKey.exploreGlassBlurScale, it) }
        g.shellScale?.let { context.putPrefInt(PreferKey.exploreGlassShellScale, it) }
        g.tintScale?.let { context.putPrefInt(PreferKey.exploreGlassTintScale, it) }
        g.strokeScale?.let { context.putPrefInt(PreferKey.exploreGlassStrokeScale, it) }
        g.strokeColor?.let { context.putPrefString(PreferKey.glassStrokeColor, it) }
        g.presetMatched?.let { context.putPrefBoolean(PreferKey.glassPresetMatched, it) }
        g.hardwareBlur?.let { context.putPrefBoolean(PreferKey.exploreGlassHardwareBlur, it) }
        g.animatedSolid?.let { context.putPrefBoolean(PreferKey.exploreGlassAnimatedSolid, it) }

        listOf(false, true).forEach { isNight ->
            g.cornerBaseScale?.let {
                val formatted = String.format(Locale.US, "%.2f", it.coerceIn(0f, 3f)).trimEnd('0').trimEnd('.')
                context.putPrefString(ThemeRuntimeKeys.uiCornerScale(isNight), formatted)
            }
            g.surfaceAlpha?.let {
                context.putPrefInt(ThemeRuntimeKeys.uiLayoutAlpha(isNight), it.coerceIn(0, 100))
            }
            g.dialogAlpha?.let {
                context.putPrefInt(ThemeRuntimeKeys.dialogAlpha(isNight), it.coerceIn(0, 100))
            }
            g.panelBorderAlpha?.let {
                context.putPrefInt(
                    if (isNight) PreferKey.panelBorderAlphaN else PreferKey.panelBorderAlpha,
                    it.coerceIn(0, 100)
                )
            }
            g.cardShadow?.let {
                context.putPrefInt(ThemeRuntimeKeys.themeCardShadow(isNight), it.coerceIn(0, 24))
            }
            g.cardBackgroundBlur?.let {
                context.putPrefInt(
                    ThemeRuntimeKeys.themeCardBackgroundBlur(isNight),
                    (it * 10f).toInt().coerceIn(0, 250)
                )
            }
            g.uiCornerSearchFollow?.let {
                context.putPrefBoolean(ThemeRuntimeKeys.uiCornerSearchFollow(isNight), it)
            }
            g.uiCornerReplyFollow?.let {
                context.putPrefBoolean(ThemeRuntimeKeys.uiCornerReplyFollow(isNight), it)
            }
        }
        g.wallpaperBlurDay?.let { context.putPrefInt(PreferKey.bgImageBlurring, it.coerceIn(0, 25)) }
        g.wallpaperBlurNight?.let { context.putPrefInt(PreferKey.bgImageNBlurring, it.coerceIn(0, 25)) }
    }

    private fun applySurfaceScopes(context: Context, scopes: SurfaceScopesParams?) {
        saveScopeOverride(context, PreferKey.scopeOverrideTopBar, scopes?.topBar)
        saveScopeOverride(context, PreferKey.scopeOverrideBottomBar, scopes?.bottomBar)
        saveScopeOverride(context, PreferKey.scopeOverrideBookshelfCard, scopes?.bookshelfCard)
        saveScopeOverride(context, PreferKey.scopeOverrideDialog, scopes?.dialog)
    }

    private fun saveScopeOverride(context: Context, key: String, override: SurfaceScopeOverride?) {
        if (override == null || override.isEmpty()) {
            context.removePref(key)
        } else {
            context.putPrefString(key, UnifiedPackageCodec.encodeScopeOverride(override))
        }
    }

    private fun applyLayoutParams(context: Context, l: LayoutParams) {
        l.navigationPreset?.let { preset ->
            MainLayoutPresetConfig.apply(context, preset, notify = false)
        }
        l.sidebarGravity?.let { AppConfig.bottomBarSidebarGravity = it }
        l.topBarFloatingChrome?.let { AppConfig.topBarFloatingChrome = it }
        l.defaultTopBarShowSearch?.let { AppConfig.defaultTopBarShowSearch = it }
        l.floatingBottomBarHideSearch?.let { AppConfig.floatingBottomBarHideSearch = it }
        l.topBarExpandFilters?.let { AppConfig.topBarExpandFilters = it }
        l.topBarHideFilterToggle?.let { AppConfig.topBarHideFilterToggle = it }
        l.densityScale?.let { scale ->
            context.putPrefString(
                PreferKey.uiDensityScale,
                String.format(Locale.US, "%.2f", scale.coerceIn(0.8f, 1.3f))
            )
        }
        l.fontScale?.let { scale ->
            val clamped = scale.coerceIn(0, 16)
            context.putPrefInt(ThemeRuntimeKeys.fontScale(false), clamped)
            context.putPrefInt(ThemeRuntimeKeys.fontScale(true), clamped)
        }
        l.discoveryPageLayout?.let { AppConfig.discoveryPageLayout = it }
        l.rssSourceLayout?.let { AppConfig.rssSourceLayout = it }
        l.homepageLayoutMode?.let { AppConfig.homepageLayoutMode = it }
    }

    private fun putOrRemovePref(context: Context, key: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotBlank() }
        if (clean == null) {
            context.removePref(key)
        } else {
            context.putPrefString(key, clean)
        }
    }
}
