package io.legado.app.help.config

import android.content.Context
import androidx.annotation.Keep
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.compress.SafeZipExtractor
import io.legado.app.utils.compress.SafeZipLimits
import io.legado.app.utils.compress.readTextLimited
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isSameOrSubFileOf
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.help.config.appearance.AppearanceKitDiff
import io.legado.app.help.config.appearance.AppearanceKitSnapshot
import io.legado.app.help.config.appearance.AppearanceSlot
import io.legado.app.help.config.appearance.BuiltinAppearanceDefaults
import io.legado.app.help.config.appearance.GlassSlotSnapshot
import io.legado.app.help.config.appearance.ThemeSlotSnapshot
import io.legado.app.help.config.appearance.WelcomeSlotSnapshot
import io.legado.app.lib.theme.PrefBatchTransaction
import io.legado.app.lib.theme.recomputeThemeUiSignatureAfterBatch
import io.legado.app.ui.main.explore.resolveExploreGlassMode
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import io.legado.app.R

object AppearanceKitManager {

    const val KIT_FLOATING = "builtin_floating"
    const val KIT_FLOATING_NO_SEARCH = "builtin_floating_no_search"
    const val KIT_REGULAR = "builtin_regular"
    const val KIT_SIDEBAR = "builtin_sidebar"
    private const val kitManifestName = "appearance_kit.json"
    internal const val kitVersion = 2
    private const val maxKitManifestBytes = 1024L * 1024L
    private val kitZipLimits = SafeZipLimits(
        maxEntries = 2_048,
        maxEntryBytes = 128L * 1024L * 1024L,
        maxTotalBytes = 512L * 1024L * 1024L,
        maxCompressionRatio = 500L
    )
    private val currentModeThemeMutex = Mutex()
    private val currentModeThemeGeneration = AtomicLong()

    val rootDir: File
        get() = appCtx.externalFiles.getFile("appThemeKits").apply { mkdirs() }

    private val indexFile: File
        get() = rootDir.getFile("kits.json")

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("appearanceKitTemp").apply { mkdirs() }

    fun builtinKits(): List<AppearanceKit> {
        return listOf(
            AppearanceKit(
                id = BuiltinAppearanceDefaults.BUILTIN_DEFAULT_KIT_ID,
                name = appCtx.getString(R.string.builtin_default_kit_name),
                summary = appCtx.getString(R.string.builtin_default_kit_summary),
                type = AppearanceKitType.BUILTIN,
                binding = KitBinding()
            )
        )
    }

    suspend fun importedThemeKits(): List<AppearanceKit> {
        migrateLegacyThemeKits()
        val indexed = loadIndex().map { it.toAppearanceKit() }
        if (indexed.isNotEmpty()) return indexed.sortedBy { it.name }
        val day = ThemePackageManager.loadLocalOnly(false)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        val night = ThemePackageManager.loadLocalOnly(true)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        return (day + night)
            .groupBy { it.packageInfo.name }
            .map { (name, entries) ->
                val hasDay = entries.any { !it.packageInfo.isNightTheme }
                val hasNight = entries.any { it.packageInfo.isNightTheme }
                AppearanceKit(
                    id = "theme:${name}",
                    name = name,
                    summary = when {
                        hasDay && hasNight -> appCtx.getString(R.string.svc_day_night_themes_imported)
                        hasNight -> appCtx.getString(R.string.svc_night_theme_imported)
                        else -> appCtx.getString(R.string.svc_day_theme_imported)
                    },
                    type = AppearanceKitType.IMPORTED_THEME,
                    binding = KitBinding().apply {
                        entries.forEach {
                            setTheme(it.packageInfo.isNightTheme, ComponentRef(it.dirName, it.packageInfo.name))
                        }
                    }
                )
            }
            .sortedBy { it.name }
    }

    fun normalizeBuiltinKitId(rawId: String?): String {
        val trimmed = rawId?.trim().orEmpty()
        return if (trimmed.isBlank() || isBuiltinKitId(trimmed)) {
            BuiltinAppearanceDefaults.BUILTIN_DEFAULT_KIT_ID
        } else {
            trimmed
        }
    }

    fun alignBaselineAfterMigration(context: Context) {
        val raw = context.getPrefString(PreferKey.currentAppearanceKitId, "").orEmpty()
        val normalized = normalizeBuiltinKitId(raw)
        if (raw.isNotBlank() && raw != normalized) {
            context.putPrefString(PreferKey.currentAppearanceKitId, normalized)
        }
    }

    fun currentKitId(): String {
        return normalizeBuiltinKitId(appCtx.getPrefString(PreferKey.currentAppearanceKitId, ""))
    }

    suspend fun apply(context: Context, kit: AppearanceKit) {
        currentModeThemeMutex.lock()
        try {
            currentModeThemeGeneration.incrementAndGet()
            val binding = kit.binding ?: loadIndex().firstOrNull { it.id == kit.id }?.binding
            val normalizedId = normalizeBuiltinKitId(kit.id)
            PrefBatchTransaction.beginBatch()
            try {
                applyBinding(context, binding ?: KitBinding())
                context.putPrefString(PreferKey.currentAppearanceKitId, normalizedId)
            } finally {
                PrefBatchTransaction.endBatch {
                    context.applicationContext.recomputeThemeUiSignatureAfterBatch()
                }
            }
            postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
            postEvent(EventBus.RECREATE, "")
        } finally {
            currentModeThemeMutex.unlock()
        }
    }

    suspend fun applyCurrentModeTheme(
        context: Context,
        isNightTheme: Boolean = AppConfig.isNightTheme
    ): Boolean {
        val generation = currentModeThemeGeneration.incrementAndGet()
        val before = currentThemeModeSnapshot(isNightTheme)
        currentModeThemeMutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            if (generation != currentModeThemeGeneration.get()) return false
            val binding = loadIndex().firstOrNull { it.id == currentKitId() }?.binding
            currentCoroutineContext().ensureActive()
            if (generation != currentModeThemeGeneration.get()) return false
            PrefBatchTransaction.beginBatch()
            try {
                if (binding != null) {
                    applyCurrentThemeRef(context, binding, isNightTheme)
                }
                currentCoroutineContext().ensureActive()
                if (generation != currentModeThemeGeneration.get()) return false
                ThemeConfig.applyTheme(context, isNightTheme)
                BookCover.upDefaultCover()
            } finally {
                PrefBatchTransaction.endBatch {
                    context.applicationContext.recomputeThemeUiSignatureAfterBatch()
                }
            }
            // 只在投影真的改写了本模式主题（名字/目录/颜色/背景图）时返回 true，调用方
            // （AppConfig.themeMode 监听、App 系统日/夜链路）据此补发刷新事件。
            // 已一致时返回 false，避免与调用方同步的 applyDayNight 形成双重 recreate。
            return currentThemeModeSnapshot(isNightTheme) != before
        } finally {
            currentModeThemeMutex.unlock()
        }
    }

    /** 本模式主题的身份快照：主题名/目录名/主色/强调色/背景色/底栏色/背景图。 */
    private data class ThemeModeSnapshot(
        val themeName: String,
        val dirName: String,
        val primary: Int,
        val accent: Int,
        val background: Int,
        val bottomBackground: Int,
        val backgroundImage: String
    )

    private fun currentThemeModeSnapshot(isNightTheme: Boolean): ThemeModeSnapshot {
        return ThemeModeSnapshot(
            themeName = appCtx.getPrefString(
                if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName, ""
            ).orEmpty(),
            dirName = appCtx.getPrefString(
                if (isNightTheme) PreferKey.dNThemeDirName else PreferKey.dThemeDirName, ""
            ).orEmpty(),
            primary = appCtx.getPrefInt(
                if (isNightTheme) PreferKey.cNPrimary else PreferKey.cPrimary, Int.MIN_VALUE
            ),
            accent = appCtx.getPrefInt(
                if (isNightTheme) PreferKey.cNAccent else PreferKey.cAccent, Int.MIN_VALUE
            ),
            background = appCtx.getPrefInt(
                if (isNightTheme) PreferKey.cNBackground else PreferKey.cBackground, Int.MIN_VALUE
            ),
            bottomBackground = appCtx.getPrefInt(
                if (isNightTheme) PreferKey.cNBBackground else PreferKey.cBBackground, Int.MIN_VALUE
            ),
            backgroundImage = appCtx.getPrefString(
                if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage, ""
            ).orEmpty()
        )
    }

    suspend fun deleteImportedTheme(context: Context, kit: AppearanceKit): Boolean = withContext(IO) {
        if (kit.type != AppearanceKitType.IMPORTED_THEME) return@withContext false
        if (appCtx.getPrefString(PreferKey.currentAppearanceKitId, "") == kit.id) {
            throw IllegalArgumentException(context.getString(io.legado.app.R.string.theme_delete_applied_forbidden))
        }
        val kits = loadIndex()
        val target = kits.firstOrNull { it.id == kit.id } ?: return@withContext false
        deleteExclusiveComponents(target, kits.filterNot { it.id == target.id })
        saveIndex(kits.filterNot { it.id == target.id })
        true
    }

    suspend fun renameKit(kit: AppearanceKit, name: String): Boolean = withContext(IO) {
        if (kit.type != AppearanceKitType.IMPORTED_THEME) return@withContext false
        val nextName = name.trim().ifBlank { return@withContext false }
        val kits = loadIndex()
        val index = kits.indexOfFirst { it.id == kit.id }
        if (index < 0) return@withContext false
        if (kits.any { it.id != kit.id && it.name == nextName }) {
            throw IllegalArgumentException("应用主题名称已存在")
        }
        val next = kits.toMutableList()
        next[index] = next[index].copy(name = nextName, updatedAt = System.currentTimeMillis())
        saveIndex(next)
        if (appCtx.getPrefString(PreferKey.currentAppearanceKitId, "") == kit.id) {
            postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
            postEvent(EventBus.RECREATE, "")
        }
        true
    }

    suspend fun importedKit(id: String): StoredAppearanceKit? = withContext(IO) {
        loadIndex().firstOrNull { it.id == id }
    }

    suspend fun createFromCurrent(context: Context, name: String): StoredAppearanceKit = withContext(IO) {
        val nextName = name.trim().ifBlank { appCtx.getString(io.legado.app.R.string.appearance_kit_manage) }
        val kits = loadIndex()
        if (kits.any { it.name == nextName }) {
            throw IllegalArgumentException(appCtx.getString(io.legado.app.R.string.theme_name_exists))
        }
        ThemePackageManager.ensureLocalAppliedTheme(context, false)
        ThemePackageManager.ensureLocalAppliedTheme(context, true)
        val now = System.currentTimeMillis()
        val kit = StoredAppearanceKit(
            id = "kit_${UUID.randomUUID()}",
            name = nextName,
            binding = currentBinding(context),
            importedAt = now,
            updatedAt = now
        )
        saveIndex(kits + kit)
        context.putPrefString(PreferKey.currentAppearanceKitId, kit.id)
        kit
    }

    suspend fun overwriteKitFromCurrent(context: Context, kitId: String): Boolean = withContext(IO) {
        val kits = loadIndex()
        val index = kits.indexOfFirst { it.id == kitId }
        if (index < 0) return@withContext false
        ThemePackageManager.ensureLocalAppliedTheme(context, false)
        ThemePackageManager.ensureLocalAppliedTheme(context, true)
        val next = kits[index].copy(
            binding = currentBinding(context),
            updatedAt = System.currentTimeMillis()
        )
        val updated = kits.toMutableList()
        updated[index] = next
        saveIndex(updated)
        true
    }

    suspend fun computeDirtySlots(
        context: Context,
        activeKit: AppearanceKit?
    ): Set<AppearanceSlot> = withContext(IO) {
        if (activeKit == null || activeKit.type == AppearanceKitType.BUILTIN) {
            return@withContext emptySet()
        }
        val current = captureCurrentSnapshot(context)
        val baseline = snapshotFromBinding(context, activeKit.binding ?: KitBinding())
        AppearanceKitDiff.computeDirtySlots(current, baseline)
    }

    fun captureCurrentSnapshot(context: Context): AppearanceKitSnapshot {
        val dayTheme = currentThemeSlotSnapshot(context, false)
        val nightTheme = currentThemeSlotSnapshot(context, true)
        val glass = currentGlassBinding(context)
        val welcome = currentWelcomeBinding(context)
        return AppearanceKitSnapshot(
            dayTheme = dayTheme,
            nightTheme = nightTheme,
            dayTopBarDir = TopBarConfig.activeDirName(false),
            nightTopBarDir = TopBarConfig.activeDirName(true),
            dayNavBarDir = NavigationBarIconConfig.activeDirName(false),
            nightNavBarDir = NavigationBarIconConfig.activeDirName(true),
            dayCoverCollectionId = context.getPrefString(PreferKey.coverCollectionDay, "").orEmpty(),
            nightCoverCollectionId = context.getPrefString(PreferKey.coverCollectionNight, "").orEmpty(),
            dayCoverMode = context.getPrefString(
                PreferKey.coverCollectionModeDay,
                BuiltinAppearanceDefaults.DEFAULT_COVER_MODE
            ).orEmpty(),
            nightCoverMode = context.getPrefString(
                PreferKey.coverCollectionModeNight,
                BuiltinAppearanceDefaults.DEFAULT_COVER_MODE
            ).orEmpty(),
            glass = GlassSlotSnapshot(
                mode = glass.mode,
                frostedLevel = glass.frostedLevel,
                liquidLevel = glass.liquidLevel,
                blurScale = glass.blurScale,
                shellScale = glass.shellScale,
                tintScale = glass.tintScale,
                strokeScale = glass.strokeScale,
                strokeColor = glass.strokeColor,
                presetMatched = glass.presetMatched,
                hardwareBlur = glass.hardwareBlur,
                animatedSolid = glass.animatedSolid
            ),
            welcome = WelcomeSlotSnapshot(
                customWelcome = welcome.customWelcome,
                imageDay = context.getPrefString(PreferKey.welcomeImage, "").orEmpty(),
                imageNight = context.getPrefString(PreferKey.welcomeImageDark, "").orEmpty(),
                showTextDay = welcome.showTextDay,
                showTextNight = welcome.showTextNight,
                showIconDay = welcome.showIconDay,
                showIconNight = welcome.showIconNight,
                showTime = welcome.showTime
            )
        )
    }

    private fun currentThemeSlotSnapshot(context: Context, isNight: Boolean): ThemeSlotSnapshot {
        val snap = currentThemeModeSnapshot(isNight)
        return ThemeSlotSnapshot(
            themeName = snap.themeName,
            dirName = snap.dirName,
            primary = snap.primary,
            accent = snap.accent,
            background = snap.background,
            bottomBackground = snap.bottomBackground,
            backgroundImage = snap.backgroundImage
        )
    }

    private fun snapshotFromBinding(context: Context, binding: KitBinding): AppearanceKitSnapshot {
        val current = captureCurrentSnapshot(context)
        val dayThemeRef = binding.dayTheme
        val nightThemeRef = binding.nightTheme
        val glass = binding.glass
        val welcome = binding.welcome
        return current.copy(
            dayTheme = if (dayThemeRef != null) {
                current.dayTheme.copy(themeName = dayThemeRef.name, dirName = dayThemeRef.dirName)
            } else {
                current.dayTheme
            },
            nightTheme = if (nightThemeRef != null) {
                current.nightTheme.copy(themeName = nightThemeRef.name, dirName = nightThemeRef.dirName)
            } else {
                current.nightTheme
            },
            dayTopBarDir = binding.dayTopBar?.dirName ?: BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
            nightTopBarDir = binding.nightTopBar?.dirName ?: BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
            dayNavBarDir = binding.dayNavigationBar?.dirName ?: BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
            nightNavBarDir = binding.nightNavigationBar?.dirName ?: BuiltinAppearanceDefaults.DEFAULT_PACKAGE_DIR,
            dayCoverCollectionId = binding.dayCoverCollection?.dirName.orEmpty(),
            nightCoverCollectionId = binding.nightCoverCollection?.dirName.orEmpty(),
            glass = if (glass != null) {
                GlassSlotSnapshot(
                    mode = glass.mode,
                    frostedLevel = glass.frostedLevel,
                    liquidLevel = glass.liquidLevel,
                    blurScale = glass.blurScale,
                    shellScale = glass.shellScale,
                    tintScale = glass.tintScale,
                    strokeScale = glass.strokeScale,
                    strokeColor = glass.strokeColor,
                    presetMatched = glass.presetMatched,
                    hardwareBlur = glass.hardwareBlur,
                    animatedSolid = glass.animatedSolid
                )
            } else {
                current.glass
            },
            welcome = if (welcome != null) {
                current.welcome.copy(
                    customWelcome = welcome.customWelcome,
                    showTextDay = welcome.showTextDay,
                    showTextNight = welcome.showTextNight,
                    showIconDay = welcome.showIconDay,
                    showIconNight = welcome.showIconNight,
                    showTime = welcome.showTime
                )
            } else {
                current.welcome
            }
        )
    }

    suspend fun currentSlotSummaries(context: Context): AppearanceSlotSummaries = withContext(IO) {
        val isNight = AppConfig.isNightTheme
        val themeName = context.getPrefString(
            if (isNight) PreferKey.dNThemeName else PreferKey.dThemeName,
            ""
        ).orEmpty().ifBlank { context.getString(R.string.builtin_default_kit_name) }
        val topBarName = TopBarConfig.currentEntry(context, isNight).config.name
        val navBarName = NavigationBarIconConfig.currentEntry(isNight).config.name
        val glass = currentGlassBinding(context)
        val glassModeLabel = when (glass.mode) {
            io.legado.app.ui.main.GlassMaterialPreset.SOLID -> context.getString(R.string.appearance_glass_solid)
            io.legado.app.ui.main.GlassMaterialPreset.CLEAR -> context.getString(R.string.appearance_glass_clear)
            io.legado.app.ui.main.GlassMaterialPreset.FROSTED -> context.getString(R.string.appearance_glass_frosted)
            else -> context.getString(R.string.appearance_glass_liquid)
        }
        val coverName = CoverCollectionManager.selectedEntry(isNight)?.collection?.name
            ?: context.getString(R.string.cover_collection_none)
        val welcomeCustom = context.getPrefBoolean(PreferKey.customWelcome, false)
        val welcomeShowTime = context.getPrefInt(PreferKey.welcomeShowTime, 0)
        val welcomeSummary = if (welcomeCustom) {
            context.getString(R.string.appearance_welcome_custom_summary, welcomeShowTime)
        } else {
            context.getString(R.string.appearance_welcome_default_summary, welcomeShowTime)
        }
        AppearanceSlotSummaries(
            theme = themeName,
            topBar = topBarName,
            navigationBar = navBarName,
            glass = "$glassModeLabel · ${if (glass.mode == io.legado.app.ui.main.GlassMaterialPreset.FROSTED) glass.frostedLevel else glass.liquidLevel}%",
            cover = coverName,
            welcome = welcomeSummary
        )
    }

    suspend fun saveImportedKit(kit: StoredAppearanceKit, context: Context = appCtx): Boolean = withContext(IO) {
        val nextName = kit.name.trim().ifBlank { return@withContext false }
        val kits = loadIndex()
        if (kits.any { it.id != kit.id && it.name == nextName }) {
            throw IllegalArgumentException("应用主题名称已存在")
        }
        val target = kits.firstOrNull { it.id == kit.id } ?: return@withContext false
        val next = kit.copy(
            id = target.id,
            name = nextName,
            importedAt = target.importedAt,
            updatedAt = System.currentTimeMillis()
        )
        saveIndex(kits.map { if (it.id == target.id) next else it })
        if (appCtx.getPrefString(PreferKey.currentAppearanceKitId, "") == target.id) {
            applyBinding(context, next.binding)
            postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
            postEvent(EventBus.RECREATE, "")
        }
        true
    }

    suspend fun syncCurrentThemeRef(isNight: Boolean, entry: ThemePackageManager.Entry?): Boolean {
        val ref = entry
            ?.takeIf { it.source != ThemePackageManager.Source.BUILTIN }
            ?.let { ComponentRef(it.dirName, it.packageInfo.name) }
        return updateCurrentBinding { binding ->
            binding.setTheme(isNight, ref)
        }
    }

    suspend fun syncCurrentTopBarRef(isNight: Boolean, entry: TopBarConfig.Entry?): Boolean {
        val ref = entry
            ?.takeIf { it.dirName != TopBarConfig.DEFAULT_DIR_NAME }
            ?.let { ComponentRef(it.dirName, it.config.name) }
        return updateCurrentBinding { binding ->
            binding.setTopBar(isNight, ref)
        }
    }

    suspend fun syncCurrentNavigationBarRef(isNight: Boolean, entry: NavigationBarIconConfig.Entry?): Boolean {
        val ref = entry
            ?.takeIf { it.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME }
            ?.let { ComponentRef(it.dirName, it.config.name) }
        return updateCurrentBinding { binding ->
            binding.setNavigationBar(isNight, ref)
        }
    }

    suspend fun syncCurrentCoverCollectionRef(isNight: Boolean, collection: CoverCollectionManager.Collection?): Boolean {
        val ref = collection?.let { ComponentRef(it.id, it.name) }
        return updateCurrentBinding { binding ->
            binding.setCoverCollection(isNight, ref)
        }
    }

    private suspend fun updateCurrentBinding(update: (KitBinding) -> Unit): Boolean = withContext(IO) {
        val currentId = appCtx.getPrefString(PreferKey.currentAppearanceKitId, "")
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext false
        val kits = loadIndex()
        val index = kits.indexOfFirst { it.id == currentId }
        if (index < 0) return@withContext false
        val nextBinding = kits[index].binding.copy()
        update(nextBinding)
        val nextKits = kits.toMutableList()
        nextKits[index] = kits[index].copy(
            binding = nextBinding,
            updatedAt = System.currentTimeMillis()
        )
        saveIndex(nextKits)
        true
    }

    suspend fun editableOptions(isNight: Boolean): AppearanceKitEditOptions = withContext(IO) {
        AppearanceKitEditOptions(
            themes = ThemePackageManager.loadLocalOnly(isNight)
                .filter { it.source != ThemePackageManager.Source.BUILTIN }
                .map { ComponentRef(it.dirName, it.packageInfo.name) },
            topBars = listOf(ComponentRef(TopBarConfig.DEFAULT_DIR_NAME, appCtx.getString(R.string.top_bar_style_default))) +
                TopBarConfig.loadLocalOnlyForKit(isNight)
                    .filter { it.dirName != TopBarConfig.DEFAULT_DIR_NAME }
                    .map { ComponentRef(it.dirName, it.config.name) },
            navigationBars = NavigationBarIconConfig.loadLocalOnlyForKit(isNight)
                .filter { it.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME }
                .map { ComponentRef(it.dirName, it.config.name) },
            coverCollections = CoverCollectionManager.loadEntries(isNight)
                .filter { it.source != CoverCollectionManager.Source.REMOTE }
                .map { ComponentRef(it.collection.id, it.collection.name) }
        )
    }

    suspend fun importPackage(file: File): ImportResult = withContext(IO) {
        val kitZip = appearanceKitZipPayload(file)
        if (kitZip != null) {
            try {
                importAppearanceKit(kitZip)
            } finally {
                if (kitZip != file) kitZip.delete()
            }
        } else {
            val result = ThemePackageManager.importPackageDetailed(file)
            val kit = createKitFromImportResult(result)
            ImportResult(
                themeCount = result.themes.size,
                navigationBarCount = result.navigationBars.size,
                coverCollectionCount = result.coverCollections.size,
                kitCount = if (kit != null) 1 else 0
            )
        }
    }

    suspend fun exportCurrent(context: Context): File = withContext(IO) {
        val packageDir = tempDir.getFile("export_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val components = mutableListOf<KitComponent>()
        try {
            exportThemes(context, packageDir, components)
            exportTopBars(context, packageDir, components)
            exportNavigationBars(packageDir, components)
            exportCoverCollections(packageDir, components)
            val manifest = AppearanceKitPackage(
                id = UUID.randomUUID().toString(),
                name = appCtx.getString(R.string.appearance_kit_manage),
                version = kitVersion,
                exportedAt = System.currentTimeMillis(),
                binding = currentBinding(context),
                components = components
            )
            File(packageDir, kitManifestName).writeText(GSON.toJson(manifest))
            val zipFile = tempDir.getFile("appearance_kit_${System.currentTimeMillis()}.zip")
            if (zipFile.exists()) zipFile.delete()
            val packageFiles = packageDir.listFiles()?.toList().orEmpty()
            if (!ZipUtils.zipFiles(packageFiles, zipFile) || !zipFile.isFile || zipFile.length() <= 0L) {
                throw IllegalStateException("Export failed")
            }
            zipFile
        } finally {
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    fun isAppearanceKitPackage(file: File): Boolean {
        val kitZip = appearanceKitZipPayload(file) ?: return false
        if (kitZip != file) kitZip.delete()
        return true
    }

    private suspend fun importAppearanceKit(file: File): ImportResult {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        var result = ImportResult()
        try {
            unzipSecure(file, unzipDir)
            val manifestFile = findKitManifestFile(unzipDir)
                ?: throw IllegalArgumentException(appCtx.getString(io.legado.app.R.string.appearance_kit_manifest_missing))
            val packageRoot = manifestFile.parentFile ?: unzipDir
            val manifest = GSON.fromJsonObject<AppearanceKitPackage>(
                manifestFile.readTextLimited(maxKitManifestBytes)
            ).getOrThrow()
            require(validateManifestVersion(manifest.version)) {
                "Unsupported appearance kit version: ${manifest.version} (max supported: $kitVersion)"
            }
            val importedBinding = KitBinding()
            manifest.components.forEach { component ->
                val componentFile = resolveKitComponentFile(packageRoot, unzipDir, component.path) ?: return@forEach
                when (component.type) {
                    KitComponentType.THEME.name -> {
                        val entry = ThemePackageManager.importZip(componentFile)
                        importedBinding.setTheme(component.isNight, ComponentRef(entry.dirName, entry.packageInfo.name))
                        result = result.copy(themeCount = result.themeCount + 1)
                    }
                    KitComponentType.TOP_BAR.name -> {
                        val entry = TopBarConfig.importZip(componentFile)
                        importedBinding.setTopBar(component.isNight, ComponentRef(entry.dirName, entry.config.name))
                        result = result.copy(topBarCount = result.topBarCount + 1)
                    }
                    KitComponentType.NAVIGATION_BAR.name -> {
                        val entry = NavigationBarIconConfig.importZip(componentFile)
                        importedBinding.setNavigationBar(component.isNight, ComponentRef(entry.dirName, entry.config.name))
                        result = result.copy(navigationBarCount = result.navigationBarCount + 1)
                    }
                    KitComponentType.COVER_COLLECTION.name -> {
                        val collection = CoverCollectionManager.importZip(appCtx, componentFile, component.isNight)
                        importedBinding.setCoverCollection(component.isNight, ComponentRef(collection.id, collection.name))
                        result = result.copy(coverCollectionCount = result.coverCollectionCount + 1)
                    }
                }
            }
            val finalBinding = manifest.binding.mergeImported(importedBinding)
            saveOrReplaceKit(
                StoredAppearanceKit(
                    id = manifest.id.ifBlank { "kit_${manifest.name.normalizeFileName()}_${System.currentTimeMillis()}" },
                    name = manifest.name.ifBlank { appCtx.getString(R.string.appearance_kit_manage) },
                    previewPath = manifest.previewPath,
                    binding = finalBinding,
                    importedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            result = result.copy(kitCount = result.kitCount + 1)
            return result
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun appearanceKitZipPayload(file: File): File? {
        if (hasKitManifest(file)) return file
        val payload = RedAssetPackage.zipPayload(file, tempDir) ?: return null
        if (hasKitManifest(payload)) return payload
        payload.delete()
        return null
    }

    private fun hasKitManifest(file: File): Boolean {
        return runCatching {
            ZipFile(file).use { zip -> zip.findKitManifestEntry() != null }
        }.getOrDefault(false)
    }

    private fun ZipFile.findKitManifestEntry(): ZipEntry? {
        return entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && entry.name.isKitManifestPath()
        }
    }

    private fun String.isKitManifestPath(): Boolean {
        val normalized = replace('\\', '/').trim('/')
        return normalized == kitManifestName || normalized.endsWith("/$kitManifestName")
    }

    private fun findKitManifestFile(root: File): File? {
        return root.walkTopDown().firstOrNull { it.isFile && it.name == kitManifestName }
    }

    private fun resolveKitComponentFile(packageRoot: File, unzipRoot: File, path: String): File? {
        val normalized = path.replace('\\', '/').trim('/').takeIf { it.isNotBlank() } ?: return null
        if (File(normalized).isAbsolute) return null
        return listOf(
            File(packageRoot, normalized),
            File(unzipRoot, normalized)
        ).firstOrNull { it.isFile && it.isSameOrSubFileOf(unzipRoot) }
    }

    private suspend fun applyBinding(context: Context, binding: KitBinding) {
        val currentNight = AppConfig.isNightTheme
        // 切换皮肤方案不再覆写主导航布局骨架（M1b 解耦）
        applyCurrentThemeRef(context, binding, currentNight)
        applyTopBarRef(false, binding.dayTopBar)
        applyTopBarRef(true, binding.nightTopBar)
        applyNavigationRef(false, binding.dayNavigationBar)
        applyNavigationRef(true, binding.nightNavigationBar)
        CoverCollectionManager.setSelected(false, binding.dayCoverCollection?.dirName)
        CoverCollectionManager.setSelected(true, binding.nightCoverCollection?.dirName)
        if (AppConfig.isNightTheme != currentNight) {
            AppConfig.isNightTheme = currentNight
        }
        NavigationBarIconConfig.applyCurrentBottomConfig(currentNight, preserveLayoutMode = true)
        binding.glass?.let { applyGlassBinding(context, it) }
        binding.welcome?.let { applyWelcomeBinding(context, it) }
        ThemeConfig.applyTheme(context)
        BookCover.upDefaultCover()
    }

    private fun applyGlassBinding(context: Context, glass: KitGlassBinding) {
        if (glass.mode.isNotBlank()) {
            context.putPrefString(PreferKey.exploreGlassEffectMode, glass.mode)
        }
        context.putPrefInt(PreferKey.frostedGlassLevel, glass.frostedLevel)
        context.putPrefInt(PreferKey.liquidGlassLevel, glass.liquidLevel)
        context.putPrefInt(PreferKey.exploreGlassBlurScale, glass.blurScale)
        context.putPrefInt(PreferKey.exploreGlassShellScale, glass.shellScale)
        context.putPrefInt(PreferKey.exploreGlassTintScale, glass.tintScale)
        context.putPrefInt(PreferKey.exploreGlassStrokeScale, glass.strokeScale)
        context.putPrefString(PreferKey.glassStrokeColor, glass.strokeColor)
        context.putPrefBoolean(PreferKey.glassPresetMatched, glass.presetMatched)
        context.putPrefBoolean(PreferKey.exploreGlassHardwareBlur, glass.hardwareBlur)
        context.putPrefBoolean(PreferKey.exploreGlassAnimatedSolid, glass.animatedSolid)
    }

    private fun applyWelcomeBinding(context: Context, welcome: KitWelcomeBinding) {
        context.putPrefBoolean(PreferKey.customWelcome, welcome.customWelcome)
        context.putPrefBoolean(PreferKey.welcomeShowText, welcome.showTextDay)
        context.putPrefBoolean(PreferKey.welcomeShowTextDark, welcome.showTextNight)
        context.putPrefBoolean(PreferKey.welcomeShowIcon, welcome.showIconDay)
        context.putPrefBoolean(PreferKey.welcomeShowIconDark, welcome.showIconNight)
        context.putPrefInt(PreferKey.welcomeShowTime, welcome.showTime)
    }

    private suspend fun applyThemeRef(context: Context, isNight: Boolean, ref: ComponentRef?) {
        val entry = ref?.let { findThemeEntry(isNight, it) } ?: ThemePackageManager.builtinEntryForKit(isNight)
        ThemePackageManager.apply(context, entry, switchNightMode = false, notify = false)
    }

    private suspend fun applyCurrentThemeRef(context: Context, binding: KitBinding, isNight: Boolean) {
        applyThemeRef(context, isNight, if (isNight) binding.nightTheme else binding.dayTheme)
    }

    private suspend fun applyTopBarRef(isNight: Boolean, ref: ComponentRef?) {
        val entry = when (ref?.dirName) {
            TopBarConfig.DEFAULT_DIR_NAME -> TopBarConfig.defaultEntryForKit(appCtx, isNight)
            else -> ref?.let { findTopBarEntry(isNight, it) }
        }
        // 缺省顶栏样式由 MainLayoutPresetConfig.apply 写入的 defaultTopBarStyle 决定，
        // apply() 仅持久化 dirName(=DEFAULT)，故无需按 preset 区分 fallback entry。
        TopBarConfig.apply(entry ?: TopBarConfig.defaultEntryForKit(appCtx, isNight))
    }

    private suspend fun applyNavigationRef(isNight: Boolean, ref: ComponentRef?) {
        val entry = ref?.let { findNavigationEntry(isNight, it) }
        if (entry != null) {
            NavigationBarIconConfig.select(entry)
        } else {
            NavigationBarIconConfig.select(NavigationBarIconConfig.standardEntryForKit(isNight))
        }
    }

    private suspend fun findThemeEntry(isNight: Boolean, ref: ComponentRef): ThemePackageManager.Entry? {
        val entries = ThemePackageManager.loadLocalOnly(isNight)
        if (ref.dirName.isNotBlank()) {
            entries.firstOrNull { it.dirName == ref.dirName }?.let { return it }
        }
        return entries.firstOrNull { it.packageInfo.name == ref.name }
    }

    private suspend fun findTopBarEntry(isNight: Boolean, ref: ComponentRef): TopBarConfig.Entry? {
        return TopBarConfig.loadLocalOnlyForKit(isNight).firstOrNull {
            it.dirName == ref.dirName || it.config.name == ref.name
        }
    }

    private suspend fun findNavigationEntry(isNight: Boolean, ref: ComponentRef): NavigationBarIconConfig.Entry? {
        return NavigationBarIconConfig.loadLocalOnlyForKit(isNight).firstOrNull {
            it.dirName == ref.dirName || it.config.name == ref.name
        }
    }

    private fun createKitFromImportResult(result: ThemePackageManager.ThemeImportResult): StoredAppearanceKit? {
        if (result.themes.isEmpty()) return null
        val name = result.sourceName.ifBlank {
            result.themes.first().packageInfo.name.ifBlank { appCtx.getString(R.string.appearance_kit_manage) }
        }
        val binding = KitBinding()
        result.themes.forEach {
            binding.setTheme(it.packageInfo.isNightTheme, ComponentRef(it.dirName, it.packageInfo.name))
        }
        result.navigationBars.forEach {
            binding.setNavigationBar(it.config.isNightMode, ComponentRef(it.dirName, it.config.name))
        }
        if (binding.dayNavigationBar == null) {
            result.navigationBars.firstOrNull { it.config.isNightMode }?.let { source ->
                NavigationBarIconConfig.copyEntryForMode(source, false)?.let {
                    binding.setNavigationBar(false, ComponentRef(it.dirName, it.config.name))
                }
            }
        }
        if (binding.nightNavigationBar == null) {
            result.navigationBars.firstOrNull { !it.config.isNightMode }?.let { source ->
                NavigationBarIconConfig.copyEntryForMode(source, true)?.let {
                    binding.setNavigationBar(true, ComponentRef(it.dirName, it.config.name))
                }
            }
        }
        result.coverCollections.forEach {
            binding.setCoverCollection(it.isNight, ComponentRef(it.id, it.name))
        }
        return saveOrReplaceKit(
            StoredAppearanceKit(
                id = "theme_${name.normalizeFileName()}",
                name = name,
                binding = binding,
                importedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun migrateLegacyThemeKits() {
        if (loadIndex().isNotEmpty()) return
        val day = ThemePackageManager.loadLocalOnly(false)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        val night = ThemePackageManager.loadLocalOnly(true)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        val kits = (day + night).groupBy { it.packageInfo.name }.map { (name, entries) ->
            val binding = KitBinding()
            entries.forEach {
                binding.setTheme(it.packageInfo.isNightTheme, ComponentRef(it.dirName, it.packageInfo.name))
            }
            StoredAppearanceKit(
                id = "theme_${name.normalizeFileName()}",
                name = name,
                binding = binding,
                importedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }
        if (kits.isNotEmpty()) saveIndex(kits)
    }

    internal fun validateManifestVersion(version: Int): Boolean {
        return version in 1..kitVersion
    }

    private suspend fun currentBinding(context: Context): KitBinding {
        return KitBinding(
            preset = MainLayoutPresetConfig.currentPreset(),
            dayTheme = currentThemeRef(context, false),
            nightTheme = currentThemeRef(context, true),
            dayTopBar = currentTopBarRef(context, false),
            nightTopBar = currentTopBarRef(context, true),
            dayNavigationBar = currentNavigationRef(false),
            nightNavigationBar = currentNavigationRef(true),
            dayCoverCollection = currentCoverRef(false),
            nightCoverCollection = currentCoverRef(true),
            floatingBottomBarHideSearch = AppConfig.floatingBottomBarHideSearch,
            glass = currentGlassBinding(context),
            welcome = currentWelcomeBinding(context)
        )
    }

    private fun currentGlassBinding(context: Context): KitGlassBinding {
        val storedMode = context.getPrefString(PreferKey.exploreGlassEffectMode, "").orEmpty()
        val legacyEnabled = context.getPrefBoolean(PreferKey.exploreGlassEnabled, true)
        return KitGlassBinding(
            mode = resolveExploreGlassMode(storedMode, legacyEnabled),
            frostedLevel = context.getPrefInt(
                PreferKey.frostedGlassLevel,
                BuiltinAppearanceDefaults.DEFAULT_GLASS_LEVEL
            ),
            liquidLevel = context.getPrefInt(
                PreferKey.liquidGlassLevel,
                BuiltinAppearanceDefaults.DEFAULT_GLASS_LEVEL
            ),
            blurScale = context.getPrefInt(
                PreferKey.exploreGlassBlurScale,
                BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE
            ),
            shellScale = context.getPrefInt(
                PreferKey.exploreGlassShellScale,
                BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE
            ),
            tintScale = context.getPrefInt(
                PreferKey.exploreGlassTintScale,
                BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE
            ),
            strokeScale = context.getPrefInt(
                PreferKey.exploreGlassStrokeScale,
                BuiltinAppearanceDefaults.DEFAULT_GLASS_SCALE
            ),
            strokeColor = context.getPrefString(PreferKey.glassStrokeColor, "").orEmpty(),
            presetMatched = context.getPrefBoolean(PreferKey.glassPresetMatched, true),
            hardwareBlur = context.getPrefBoolean(PreferKey.exploreGlassHardwareBlur, true),
            animatedSolid = context.getPrefBoolean(PreferKey.exploreGlassAnimatedSolid, true)
        )
    }

    private fun currentWelcomeBinding(context: Context): KitWelcomeBinding {
        return KitWelcomeBinding(
            customWelcome = context.getPrefBoolean(PreferKey.customWelcome, false),
            showTextDay = context.getPrefBoolean(
                PreferKey.welcomeShowText,
                BuiltinAppearanceDefaults.DEFAULT_WELCOME_SHOW_TEXT
            ),
            showTextNight = context.getPrefBoolean(
                PreferKey.welcomeShowTextDark,
                BuiltinAppearanceDefaults.DEFAULT_WELCOME_SHOW_TEXT
            ),
            showIconDay = context.getPrefBoolean(
                PreferKey.welcomeShowIcon,
                BuiltinAppearanceDefaults.DEFAULT_WELCOME_SHOW_ICON
            ),
            showIconNight = context.getPrefBoolean(
                PreferKey.welcomeShowIconDark,
                BuiltinAppearanceDefaults.DEFAULT_WELCOME_SHOW_ICON
            ),
            showTime = context.getPrefInt(
                PreferKey.welcomeShowTime,
                BuiltinAppearanceDefaults.DEFAULT_WELCOME_SHOW_TIME
            )
        )
    }

    private fun currentThemeRef(context: Context, isNight: Boolean): ComponentRef? {
        val name = context.getPrefString(if (isNight) PreferKey.dNThemeName else PreferKey.dThemeName).orEmpty()
        if (name.isBlank() || ThemePackageManager.isBuiltinThemeForKit(isNight, name)) return null
        val dirName = context.getPrefString(
            if (isNight) PreferKey.dNThemeDirName else PreferKey.dThemeDirName
        ).orEmpty().ifBlank { name.normalizeFileName() }
        return ComponentRef(dirName, name)
    }

    private fun currentTopBarRef(context: Context, isNight: Boolean): ComponentRef? {
        val entry = TopBarConfig.currentEntry(context, isNight)
        if (entry.dirName == TopBarConfig.DEFAULT_DIR_NAME) return null
        return ComponentRef(entry.dirName, entry.config.name)
    }

    private fun currentNavigationRef(isNight: Boolean): ComponentRef? {
        val entry = NavigationBarIconConfig.currentEntry(isNight)
        if (entry.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) return null
        return ComponentRef(entry.dirName, entry.config.name)
    }

    private suspend fun currentCoverRef(isNight: Boolean): ComponentRef? {
        val entry = CoverCollectionManager.selectedEntry(isNight) ?: return null
        return ComponentRef(entry.collection.id, entry.collection.name)
    }

    private suspend fun deleteExclusiveComponents(target: StoredAppearanceKit, others: List<StoredAppearanceKit>) {
        val usedThemeRefs = others.flatMap { listOfNotNull(it.binding.dayTheme, it.binding.nightTheme) }.map { it.key() }.toSet()
        val usedTopRefs = others.flatMap { listOfNotNull(it.binding.dayTopBar, it.binding.nightTopBar) }.map { it.key() }.toSet()
        val usedNavRefs = others.flatMap { listOfNotNull(it.binding.dayNavigationBar, it.binding.nightNavigationBar) }.map { it.key() }.toSet()
        val usedCoverRefs = others.flatMap { listOfNotNull(it.binding.dayCoverCollection, it.binding.nightCoverCollection) }.map { it.key() }.toSet()

        listOf(false to target.binding.dayTheme, true to target.binding.nightTheme).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedThemeRefs) {
                findThemeEntry(isNight, ref)?.let { ThemePackageManager.deleteLocal(it) }
            }
        }
        listOf(false to target.binding.dayTopBar, true to target.binding.nightTopBar).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedTopRefs) {
                findTopBarEntry(isNight, ref)?.let { TopBarConfig.deleteLocal(it) }
            }
        }
        listOf(false to target.binding.dayNavigationBar, true to target.binding.nightNavigationBar).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedNavRefs) {
                findNavigationEntry(isNight, ref)?.let { NavigationBarIconConfig.deleteLocal(it) }
            }
        }
        listOf(false to target.binding.dayCoverCollection, true to target.binding.nightCoverCollection).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedCoverRefs) {
                CoverCollectionManager.localEntryForKit(isNight, ref.dirName, ref.name)
                    ?.let { CoverCollectionManager.deleteLocal(it) }
            }
        }
    }

    /**
     * 备份恢复后将 `kits.json` 索引与实盘资产目录对账，清理失效组件引用与全部失效的死条目。
     */
    suspend fun reconcileKitsAfterRestore() = withContext(IO) {
        val kits = loadIndex()
        if (kits.isEmpty()) return@withContext
        val dayThemeDirs = ThemePackageManager.loadLocalOnly(false).map { it.dirName }.toSet()
        val nightThemeDirs = ThemePackageManager.loadLocalOnly(true).map { it.dirName }.toSet()
        val dayTopBarDirs = TopBarConfig.loadEntries(appCtx, false, includeRemote = false).map { it.dirName }.toSet()
        val nightTopBarDirs = TopBarConfig.loadEntries(appCtx, true, includeRemote = false).map { it.dirName }.toSet()
        val dayNavBarDirs = NavigationBarIconConfig.loadEntries(false, includeRemote = false).map { it.dirName }.toSet()
        val nightNavBarDirs = NavigationBarIconConfig.loadEntries(true, includeRemote = false).map { it.dirName }.toSet()
        val dayCoverIds = CoverCollectionManager.load(false).map { it.id }.toSet()
        val nightCoverIds = CoverCollectionManager.load(true).map { it.id }.toSet()

        val reconciled = reconcileStoredKits(
            kits = kits,
            dayThemeDirs = dayThemeDirs,
            nightThemeDirs = nightThemeDirs,
            dayTopBarDirs = dayTopBarDirs,
            nightTopBarDirs = nightTopBarDirs,
            dayNavBarDirs = dayNavBarDirs,
            nightNavBarDirs = nightNavBarDirs,
            dayCoverIds = dayCoverIds,
            nightCoverIds = nightCoverIds
        )
        if (reconciled != kits) {
            saveIndex(reconciled)
        }
        val currentId = appCtx.getPrefString(PreferKey.currentAppearanceKitId, "").orEmpty()
        if (currentId.isNotBlank() &&
            !isBuiltinKitId(currentId) &&
            reconciled.none { it.id == currentId }
        ) {
            appCtx.removePref(PreferKey.currentAppearanceKitId)
        }
    }

    internal fun isBuiltinKitId(kitId: String): Boolean {
        return kitId == BuiltinAppearanceDefaults.BUILTIN_DEFAULT_KIT_ID ||
            kitId == KIT_FLOATING ||
            kitId == KIT_FLOATING_NO_SEARCH ||
            kitId == KIT_REGULAR ||
            kitId == KIT_SIDEBAR
    }

    internal fun reconcileStoredKits(
        kits: List<StoredAppearanceKit>,
        dayThemeDirs: Set<String>,
        nightThemeDirs: Set<String>,
        dayTopBarDirs: Set<String>,
        nightTopBarDirs: Set<String>,
        dayNavBarDirs: Set<String>,
        nightNavBarDirs: Set<String>,
        dayCoverIds: Set<String>,
        nightCoverIds: Set<String>
    ): List<StoredAppearanceKit> {
        return kits.mapNotNull { kit ->
            val b = kit.binding
            val nextBinding = b.copy(
                dayTheme = b.dayTheme?.takeIf { it.key() in dayThemeDirs },
                nightTheme = b.nightTheme?.takeIf { it.key() in nightThemeDirs },
                dayTopBar = b.dayTopBar?.takeIf { it.key() in dayTopBarDirs },
                nightTopBar = b.nightTopBar?.takeIf { it.key() in nightTopBarDirs },
                dayNavigationBar = b.dayNavigationBar?.takeIf { it.key() in dayNavBarDirs },
                nightNavigationBar = b.nightNavigationBar?.takeIf { it.key() in nightNavBarDirs },
                dayCoverCollection = b.dayCoverCollection?.takeIf { it.key() in dayCoverIds },
                nightCoverCollection = b.nightCoverCollection?.takeIf { it.key() in nightCoverIds }
            )
            val hasAnyAsset = nextBinding.dayTheme != null ||
                nextBinding.nightTheme != null ||
                nextBinding.dayTopBar != null ||
                nextBinding.nightTopBar != null ||
                nextBinding.dayNavigationBar != null ||
                nextBinding.nightNavigationBar != null ||
                nextBinding.dayCoverCollection != null ||
                nextBinding.nightCoverCollection != null ||
                nextBinding.glass != null ||
                nextBinding.welcome != null
            if (hasAnyAsset) kit.copy(binding = nextBinding) else null
        }
    }

    private fun loadIndex(): List<StoredAppearanceKit> {
        if (!indexFile.isFile) return emptyList()
        return GSON.fromJsonArray<StoredAppearanceKit>(indexFile.readText()).getOrDefault(emptyList())
    }

    private fun saveIndex(kits: List<StoredAppearanceKit>) {
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(GSON.toJson(kits.sortedBy { it.name }))
    }

    private fun saveOrReplaceKit(kit: StoredAppearanceKit): StoredAppearanceKit {
        val list = loadIndex().filterNot { it.id == kit.id || it.name == kit.name } + kit
        saveIndex(list)
        return kit
    }

    private fun unzipSecure(zipFile: File, targetDir: File) {
        SafeZipExtractor.extract(zipFile, targetDir, kitZipLimits)
    }

    private suspend fun exportThemes(
        context: Context,
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = ThemePackageManager.ensureLocalAppliedTheme(context, isNight)
            if (entry.source == ThemePackageManager.Source.BUILTIN) return@forEach
            val zip = ThemePackageManager.exportZip(entry)
            val targetName = "theme_${if (isNight) "night" else "day"}.zip"
            zip.copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.THEME.name, isNight, targetName)
        }
    }

    private suspend fun exportTopBars(
        context: Context,
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = TopBarConfig.currentEntry(context, isNight)
            if (entry.dirName == TopBarConfig.DEFAULT_DIR_NAME) return@forEach
            val targetName = "top_bar_${if (isNight) "night" else "day"}.zip"
            TopBarConfig.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.TOP_BAR.name, isNight, targetName)
        }
    }

    private suspend fun exportNavigationBars(
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = NavigationBarIconConfig.currentEntry(isNight)
            if (entry.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) return@forEach
            val targetName = "navigation_bar_${if (isNight) "night" else "day"}.zip"
            NavigationBarIconConfig.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.NAVIGATION_BAR.name, isNight, targetName)
        }
    }

    private suspend fun exportCoverCollections(
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = CoverCollectionManager.selectedEntry(isNight) ?: return@forEach
            val targetName = "cover_collection_${if (isNight) "night" else "day"}.zip"
            CoverCollectionManager.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.COVER_COLLECTION.name, isNight, targetName)
        }
    }
}

data class ImportResult(
    val themeCount: Int = 0,
    val topBarCount: Int = 0,
    val navigationBarCount: Int = 0,
    val coverCollectionCount: Int = 0,
    val kitCount: Int = 0
) {
    val total: Int
        get() = themeCount + topBarCount + navigationBarCount + coverCollectionCount + kitCount
}

@Keep
private data class AppearanceKitPackage(
    val id: String = "",
    val name: String = "",
    val version: Int = 0,
    val exportedAt: Long = 0L,
    val previewPath: String? = null,
    val binding: KitBinding = KitBinding(),
    val components: List<KitComponent> = emptyList()
)

@Keep
private data class KitComponent(
    val type: String = "",
    val isNight: Boolean = false,
    val path: String = ""
)

private enum class KitComponentType {
    THEME,
    TOP_BAR,
    NAVIGATION_BAR,
    COVER_COLLECTION
}

data class AppearanceKit(
    val id: String,
    val name: String,
    val summary: String,
    val type: AppearanceKitType,
    val binding: KitBinding? = null,
    val previewPath: String? = null
)

enum class AppearanceKitType {
    BUILTIN,
    IMPORTED_THEME
}

@Keep
data class StoredAppearanceKit(
    val id: String = "",
    val name: String = "",
    val previewPath: String? = null,
    val binding: KitBinding = KitBinding(),
    val importedAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun toAppearanceKit(): AppearanceKit {
        val parts = listOfNotNull(
            binding.dayTheme?.let { appCtx.getString(R.string.theme_day) },
            binding.nightTheme?.let { appCtx.getString(R.string.theme_night) },
            binding.dayNavigationBar?.let { appCtx.getString(R.string.package_sync_task_type_navigation_bar) } ?: binding.nightNavigationBar?.let { appCtx.getString(R.string.package_sync_task_type_navigation_bar) },
            binding.dayTopBar?.let { appCtx.getString(R.string.package_sync_task_type_top_bar) } ?: binding.nightTopBar?.let { appCtx.getString(R.string.package_sync_task_type_top_bar) },
            binding.dayCoverCollection?.let { appCtx.getString(R.string.img_cover) } ?: binding.nightCoverCollection?.let { appCtx.getString(R.string.img_cover) }
        ).distinct()
        return AppearanceKit(
            id = id,
            name = name,
            summary = if (parts.isEmpty()) appCtx.getString(R.string.svc_missing_components_will_reset_default) else parts.joinToString(" / "),
            type = AppearanceKitType.IMPORTED_THEME,
            binding = binding,
            previewPath = previewPath
        )
    }
}

@Keep
data class KitBinding(
    var preset: String? = null,
    var dayTheme: ComponentRef? = null,
    var nightTheme: ComponentRef? = null,
    var dayTopBar: ComponentRef? = null,
    var nightTopBar: ComponentRef? = null,
    var dayNavigationBar: ComponentRef? = null,
    var nightNavigationBar: ComponentRef? = null,
    var dayCoverCollection: ComponentRef? = null,
    var nightCoverCollection: ComponentRef? = null,
    // 悬浮底栏是否隐藏搜索按钮(隐藏时顶栏自动显示搜索)。可空：旧主题包无此字段时不改变现状。
    var floatingBottomBarHideSearch: Boolean? = null,
    // v2 新增（可空，v1 旧包缺省为 null）：
    var glass: KitGlassBinding? = null,
    var welcome: KitWelcomeBinding? = null
) {
    fun mergeImported(imported: KitBinding): KitBinding {
        return copy(
            dayTheme = imported.dayTheme ?: dayTheme,
            nightTheme = imported.nightTheme ?: nightTheme,
            dayTopBar = imported.dayTopBar ?: dayTopBar,
            nightTopBar = imported.nightTopBar ?: nightTopBar,
            dayNavigationBar = imported.dayNavigationBar ?: dayNavigationBar,
            nightNavigationBar = imported.nightNavigationBar ?: nightNavigationBar,
            dayCoverCollection = imported.dayCoverCollection ?: dayCoverCollection,
            nightCoverCollection = imported.nightCoverCollection ?: nightCoverCollection,
            floatingBottomBarHideSearch = imported.floatingBottomBarHideSearch ?: floatingBottomBarHideSearch,
            glass = imported.glass ?: glass,
            welcome = imported.welcome ?: welcome
        )
    }

    fun setTheme(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightTheme = ref
        } else {
            dayTheme = ref
        }
    }

    fun setTopBar(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightTopBar = ref
        } else {
            dayTopBar = ref
        }
    }

    fun setNavigationBar(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightNavigationBar = ref
        } else {
            dayNavigationBar = ref
        }
    }

    fun setCoverCollection(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightCoverCollection = ref
        } else {
            dayCoverCollection = ref
        }
    }
}

@Keep
data class AppearanceKitEditOptions(
    val themes: List<ComponentRef> = emptyList(),
    val topBars: List<ComponentRef> = emptyList(),
    val navigationBars: List<ComponentRef> = emptyList(),
    val coverCollections: List<ComponentRef> = emptyList()
)

@Keep
data class KitGlassBinding(
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

@Keep
data class KitWelcomeBinding(
    val customWelcome: Boolean = false,
    val showTextDay: Boolean = true,
    val showTextNight: Boolean = true,
    val showIconDay: Boolean = true,
    val showIconNight: Boolean = true,
    val showTime: Int = 0
)

data class AppearanceSlotSummaries(
    val theme: String,
    val topBar: String,
    val navigationBar: String,
    val glass: String,
    val cover: String,
    val welcome: String
)

@Keep
data class ComponentRef(
    val dirName: String = "",
    val name: String = ""
) {
    fun key(): String = dirName.ifBlank { name }
}
