package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeTabBackgroundColorOrDefault
import io.legado.app.lib.theme.themeUiSignature
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.File

object TopBarConfig {

    const val DEFAULT_DIR_NAME = "default"
    const val STYLE_DEFAULT = "default"
    const val STYLE_REGULAR = "regular"
    private const val packageFileName = "top_bar.json"
    private const val remoteListTimeoutMillis = 4_000L
    private const val activeDayKey = PreferKey.topBarPackageDay
    private const val activeNightKey = PreferKey.topBarPackageNight
    private const val legacyBehaviorMigratedKey = "legacyTopBarBehaviorMigratedV2"

    @Volatile
    private var legacyBehaviorMigratedMem = false

    val rootDir: File
        get() = appCtx.externalFiles.getFile("topBarPackages")

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("topBarTemp").apply { mkdirs() }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply { mkdirs() }

    @Keep
    data class Config(
        var name: String,
        var isNightMode: Boolean,
        var style: String = STYLE_DEFAULT,
        var tagBarColor: Int? = null,
        var tagBarAlpha: Int = 100,
        var tagSelectedColor: Int? = null,
        var tagSelectedAlpha: Int = 100,
        var wallpaperPath: String? = null,
        var wallpaperCropLeft: Float? = null,
        var wallpaperCropTop: Float? = null,
        var wallpaperCropRight: Float? = null,
        var wallpaperCropBottom: Float? = null,
        var wallpaperAlpha: Int = 100,
        var backgroundColor: Int? = null,
        var cornerScale: Float? = null,

        /**
         * 是否启用悬浮圆角顶栏。独立布尔状态，禁止由 cornerScale > 0 反推：
         * cornerScale=0 是用户主动选择的直角档，与「未启用悬浮」语义不同。
         * 存量顶栏包 JSON 缺此字段时走默认 false（视觉零变化，包作者 opt-in）。
         */
        var floatingChrome: Boolean = false,
        var expandFiltersByDefault: Boolean = false,
        var hideFilterToggleWhenExpanded: Boolean = false,
        var showSearchInDefaultStyle: Boolean = false,
        var updatedAt: Long = System.currentTimeMillis()
    )

    data class Entry(
        val config: Config,
        val source: Source,
        val dirName: String,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    )

    enum class Source { BUILTIN, LOCAL, REMOTE, BOTH }

    @Keep
    private data class RemoteCache(
        val name: String,
        val dirName: String,
        val isNightMode: Boolean,
        val updatedAt: Long
    )

    fun newPackageTemplate(name: String, isNight: Boolean): Config {
        return Config(
            name = name,
            isNightMode = isNight,
            style = STYLE_DEFAULT,
            tagBarColor = null,
            tagBarAlpha = 100,
            tagSelectedColor = null,
            tagSelectedAlpha = 100,
            wallpaperPath = null,
            wallpaperAlpha = 100,
            backgroundColor = null,
            cornerScale = null,
            floatingChrome = false,
            expandFiltersByDefault = false,
            hideFilterToggleWhenExpanded = false,
            showSearchInDefaultStyle = false
        )
    }

    fun migrateLegacyBehaviorToGlobalIfNeeded(context: Context = appCtx) {
        if (legacyBehaviorMigratedMem) return
        synchronized(this) {
            if (legacyBehaviorMigratedMem) return
            if (context.getPrefBoolean(legacyBehaviorMigratedKey, false)) {
                legacyBehaviorMigratedMem = true
                return
            }
            val isNight = AppConfig.isNightTheme
            val dirName = activeDirName(isNight)
            if (dirName != DEFAULT_DIR_NAME) {
                readEntry(localDir(isNight, dirName))?.config?.let { legacy ->
                    if (legacy.floatingChrome) AppConfig.topBarFloatingChrome = true
                    if (legacy.expandFiltersByDefault) AppConfig.topBarExpandFilters = true
                    if (legacy.hideFilterToggleWhenExpanded) AppConfig.topBarHideFilterToggle = true
                    if (legacy.showSearchInDefaultStyle) AppConfig.defaultTopBarShowSearch = true
                    if (legacy.style == STYLE_REGULAR) {
                        context.putPrefString(PreferKey.defaultTopBarStyle, STYLE_REGULAR)
                    }
                }
            }
            context.putPrefBoolean(legacyBehaviorMigratedKey, true)
            legacyBehaviorMigratedMem = true
        }
    }

    fun defaultConfig(context: Context, isNight: Boolean): Config {
        val style = MainLayoutPresetConfig.defaultTopBarStyle()
        return applyGlobalLayoutAndThemeOverrides(
            context = context,
            config = Config(
                name = defaultName(isNight),
                isNightMode = isNight,
                tagBarAlpha = if (style == STYLE_REGULAR) 0 else 100,
                updatedAt = 0L
            )
        )
    }

    fun currentConfig(context: Context, isNight: Boolean = AppConfig.isNightTheme): Config {
        migrateLegacyBehaviorToGlobalIfNeeded(context)
        return applyGlobalLayoutAndThemeOverrides(context, currentEntry(context, isNight).config)
    }

    /**
     * 方案 B 单一真相源：
     * - 所有布局/交互开关由「界面布局与系统栏」(MainLayoutPresetConfig / AppConfig) 唯一接管；
     * - 所有圆角与颜色由「主题」(UiCorner / ThemeUiPalette) 唯一接管；
     * - 悬浮顶栏无壁纸实底取 `themeCardColorOrDefault()`，避免未配主色时出现刺眼的亮蓝色悬浮条；
     * - 顶栏包自身只保留壁纸与局部透明遮罩。
     */
    private fun applyGlobalLayoutAndThemeOverrides(context: Context, config: Config): Config {
        return config.copy(
            style = MainLayoutPresetConfig.defaultTopBarStyle(),
            floatingChrome = AppConfig.topBarFloatingChrome,
            expandFiltersByDefault = AppConfig.topBarExpandFilters,
            hideFilterToggleWhenExpanded = AppConfig.topBarHideFilterToggle,
            showSearchInDefaultStyle = MainLayoutPresetConfig.defaultTopBarShowSearch(),
            cornerScale = UiCorner.scale(),
            backgroundColor = context.themeCardColorOrDefault(),
            tagBarColor = context.themeTabBackgroundColorOrDefault(),
            tagSelectedColor = context.themeCardColorOrDefault()
        )
    }

    fun activeDirName(isNight: Boolean): String {
        return appCtx.getPrefString(if (isNight) activeNightKey else activeDayKey, DEFAULT_DIR_NAME)
            ?.ifBlank { DEFAULT_DIR_NAME }
            ?: DEFAULT_DIR_NAME
    }

    fun currentSignature(isNight: Boolean): String {
        val dirName = activeDirName(isNight)
        val globalPart = buildString {
            append(MainLayoutPresetConfig.defaultTopBarStyle()).append('|')
            append(MainLayoutPresetConfig.defaultTopBarShowSearch()).append('|')
            append(AppConfig.topBarFloatingChrome).append('|')
            append(AppConfig.topBarExpandFilters).append('|')
            append(AppConfig.topBarHideFilterToggle).append('|')
            append(appCtx.themeUiSignature())
        }
        if (dirName == DEFAULT_DIR_NAME) {
            return "$isNight|$DEFAULT_DIR_NAME|$globalPart"
        }
        val configFile = File(localDir(isNight, dirName), packageFileName)
        return "$isNight|$dirName|${configFile.lastModified()}|$globalPart"
    }

    fun currentEntry(context: Context, isNight: Boolean): Entry {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) return defaultEntry(context, isNight)
        return readEntry(localDir(isNight, dirName)) ?: defaultEntry(context, isNight)
    }

    fun currentWallpaperFile(context: Context, isNight: Boolean): File? {
        val entry = currentEntry(context, isNight)
        val path = entry.config.wallpaperPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        val resolved = if (file.isAbsolute) {
            file
        } else {
            File(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), path)
        }
        return resolved.takeIf { it.exists() && it.isFile }
    }

    suspend fun loadEntries(context: Context, isNight: Boolean, includeRemote: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        val local = loadLocal(isNight).associateBy { it.dirName }
        val remote = if (includeRemote) {
            loadRemoteOrCache(isNight, containerId, scope).associateBy { it.dirName }
        } else {
            emptyMap()
        }
        val entries = linkedMapOf<String, Entry>()
        entries[DEFAULT_DIR_NAME] = defaultEntry(context, isNight)
        (local.keys + remote.keys).forEach { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            entries[key] = when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )
                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> return@forEach
            }
        }
        return entries.values.sortedWith(
            compareBy<Entry> { it.dirName != DEFAULT_DIR_NAME }
                .thenBy { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.config.updatedAt }
                .thenBy { it.config.name }
                .thenBy { it.dirName }
        )
    }

    fun addOrUpdate(config: Config, oldEntry: Entry? = null): Entry {
        val normalized = stripOutOfScopeFields(normalizeConfig(config))
        val name = normalized.name.trim().ifBlank { defaultName(normalized.isNightMode) }
        val keepOldDir = oldEntry != null &&
            oldEntry.dirName.isNotBlank() &&
            oldEntry.dirName != DEFAULT_DIR_NAME &&
            oldEntry.source != Source.REMOTE
        val preferredDirName = name.normalizeFileName().ifBlank { "top_bar_${System.currentTimeMillis()}" }
        val dirName = if (keepOldDir) {
            oldEntry.dirName
        } else {
            uniqueDirName(normalized.isNightMode, preferredDirName)
        }
        if (localNameExists(normalized.isNightMode, name, oldEntry?.dirName)) {
            throw IllegalArgumentException(appCtx.getString(R.string.top_bar_name_exists))
        }
        val dir = localDir(normalized.isNightMode, dirName).apply { mkdirs() }
        val next = normalized.copy(
            name = name,
            wallpaperPath = normalizeWallpaperPath(normalized.wallpaperPath, dir),
            updatedAt = System.currentTimeMillis()
        )
        File(dir, packageFileName).writeText(GSON.toJson(next))
        return Entry(next, Source.LOCAL, dirName, localDir = dir)
    }

    private fun localNameExists(isNightMode: Boolean, name: String, excludeDirName: String? = null): Boolean {
        return loadLocal(isNightMode).any {
            it.dirName != excludeDirName && it.config.name == name
        }
    }

    private fun uniqueDirName(isNightMode: Boolean, preferred: String): String {
        val clean = preferred.normalizeFileName().ifBlank { "top_bar_${System.currentTimeMillis()}" }
        if (readEntry(localDir(isNightMode, clean)) == null) return clean
        var index = 1
        while (true) {
            val candidate = "${clean}_$index"
            if (readEntry(localDir(isNightMode, candidate)) == null) return candidate
            index++
        }
    }

    fun apply(entry: Entry) {
        val key = if (entry.config.isNightMode) activeNightKey else activeDayKey
        appCtx.putPrefString(key, entry.dirName)
    }

    suspend fun loadLocalOnlyForKit(isNight: Boolean): List<Entry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        loadLocal(isNight)
    }

    fun defaultEntryForKit(context: Context, isNight: Boolean): Entry {
        return defaultEntry(context, isNight)
    }

    suspend fun restoreApplied(isNight: Boolean): Entry? {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) return defaultEntry(appCtx, isNight)
        readEntry(localDir(isNight, dirName))?.let { return it }
        val remoteEntries = loadRemoteOrCache(isNight)
        val remote = remoteEntries.firstOrNull { it.dirName == dirName }
            ?: remoteEntries.firstOrNull { it.config.name.normalizeFileName() == dirName }
            ?: run {
                io.legado.app.constant.AppLog.put("恢复顶栏包未找到: $dirName")
                return null
            }
        return runCatching { download(remote) }.onSuccess {
            if (it.dirName != dirName) apply(it)
        }.getOrElse {
            io.legado.app.constant.AppLog.put("恢复顶栏包下载失败: $dirName\n${it.localizedMessage}", it)
            null
        }
    }

    fun deleteLocal(entry: Entry) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        FileUtils.delete(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), deleteRootDir = true)
        resetActiveIfNeeded(entry)
    }

    suspend fun deleteRemote(entry: Entry, containerId: String? = null, scope: String? = null) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        AppCloudStorage.deleteTopBarPackage(entry.config.isNightMode, entry.dirName, containerId, scope)
    }

    suspend fun delete(entry: Entry, containerId: String? = null, scope: String? = null) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        when (entry.source) {
            Source.REMOTE -> deleteRemote(entry, containerId, scope)
            Source.BOTH -> {
                val remoteResult = runCatching { deleteRemote(entry, containerId, scope) }
                deleteLocal(entry)
                remoteResult.getOrThrow()
            }
            Source.LOCAL -> deleteLocal(entry)
            Source.BUILTIN -> return
        }
        resetActiveIfNeeded(entry)
    }

    suspend fun exportZip(entry: Entry): File {
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val dir = localEntry.localDir ?: localDir(localEntry.config.isNightMode, localEntry.dirName)
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        return zipFile
    }

    fun importZip(zipFile: File): Entry = importZipInternal(zipFile)

    suspend fun upload(entry: Entry, containerId: String? = null, scope: String? = null) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        AppCloudStorage.uploadTopBarPackage(entry.config.isNightMode, entry.dirName, exportZip(entry), containerId, scope)
    }

    suspend fun download(entry: Entry, containerId: String? = null, scope: String? = null): Entry {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppCloudStorage.downloadTopBarPackage(entry.config.isNightMode, entry.dirName, zipFile, containerId, scope)
        return importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    fun withOpacity(color: Int, opacity: Int): Int {
        val alpha = (opacity.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun defaultBackgroundColor(isNight: Boolean): Int {
        return if (isNight) Color.BLACK else Color.WHITE
    }

    fun resolveBackgroundColor(config: Config): Int {
        return config.backgroundColor ?: defaultBackgroundColor(config.isNightMode)
    }

    fun resolveCornerScale(config: Config): Float {
        return config.cornerScale ?: 1f
    }

    /** 纯函数：只做 scale 归一与钳制，供几何 spec 与顶层 adapter 共用，避免两处重复公式。 */
    fun resolveCornerRadiusPx(baseRadiusPx: Float, config: Config): Float {
        return baseRadiusPx * resolveCornerScale(config).coerceIn(0f, 3f)
    }

    fun cornerRadius(context: Context, config: Config): Float {
        return resolveCornerRadiusPx(context.resources.getDimension(R.dimen.ui_panel_radius), config)
    }

    private fun defaultEntry(context: Context, isNight: Boolean): Entry {
        return Entry(defaultConfig(context, isNight), Source.BUILTIN, DEFAULT_DIR_NAME)
    }

    private fun loadLocal(isNight: Boolean): List<Entry> {
        return typeDir(isNight).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readEntry(it) }
            .orEmpty()
    }

    private suspend fun loadRemote(isNight: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        return AppCloudStorage.listTopBarPackages(isNight, containerId, scope).mapNotNull { file ->
            val name = remoteZipBaseName(file.displayName)
            val dirName = name.normalizeFileName().ifBlank {
                AppLog.put("跳过异常远端顶栏包: ${file.displayName}")
                return@mapNotNull null
            }
            Entry(
                Config(name = name.ifBlank { dirName }, isNightMode = isNight, updatedAt = file.lastModify),
                Source.REMOTE,
                dirName,
                remoteUpdatedAt = file.lastModify
            )
        }.dedupeRemoteEntries()
    }

    private fun remoteZipBaseName(displayName: String): String {
        val name = displayName.trim().trimEnd('/').trim()
        return if (name.endsWith(".zip", ignoreCase = true)) {
            name.dropLast(4).trim()
        } else {
            name
        }
    }

    private fun List<Entry>.dedupeRemoteEntries(): List<Entry> {
        return groupBy { it.dirName }.values.mapNotNull { entries ->
            entries.maxByOrNull { it.remoteUpdatedAt }
        }
    }

    private suspend fun loadRemoteOrCache(isNight: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        val cached = readRemoteCache(isNight, containerId)
        return try {
            val remote = withTimeout(remoteListTimeoutMillis) {
                loadRemote(isNight, containerId, scope)
            }
            writeRemoteCache(isNight, remote, containerId)
            remote
        } catch (e: TimeoutCancellationException) {
            AppLog.put(
                "加载远端顶栏包列表超时: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}, timeout=${remoteListTimeoutMillis}ms"
            )
            cached
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put(
                "加载远端顶栏包列表失败: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}\n${e.localizedMessage}",
                e
            )
            cached
        }
    }

    private fun readRemoteCache(isNight: Boolean, containerId: String? = null): List<Entry> {
        val file = remoteCacheFile(isNight, containerId)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<RemoteCache>(file.readText()).getOrDefault(emptyList())
            .filter { it.isNightMode == isNight }
            .mapNotNull { cache ->
                val dirName = cache.dirName.ifBlank { cache.name.normalizeFileName() }
                    .ifBlank { return@mapNotNull null }
                Entry(
                    Config(name = cache.name.ifBlank { dirName }, isNightMode = isNight, updatedAt = cache.updatedAt),
                    Source.REMOTE,
                    dirName,
                    remoteUpdatedAt = cache.updatedAt
                )
            }
    }

    private fun writeRemoteCache(isNight: Boolean, entries: List<Entry>, containerId: String? = null) {
        val cache = entries.map {
            RemoteCache(
                name = it.config.name,
                dirName = it.dirName,
                isNightMode = it.config.isNightMode,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.config.updatedAt
            )
        }
        remoteCacheFile(isNight, containerId).writeTextIfChanged(GSON.toJson(cache))
    }

    private fun remoteCacheFile(isNight: Boolean, containerId: String? = null): File {
        val mode = if (isNight) "night" else "day"
        val suffix = containerId?.takeIf { it.isNotBlank() }?.normalizeFileName()?.let { "_$it" }.orEmpty()
        return remoteCacheDir.getFile("$mode$suffix.json")
    }

    private fun readEntry(dir: File): Entry? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        val config = GSON.fromJsonObject<Config>(file.readText()).getOrNull()?.let(::normalizeConfig)
            ?: return null
        return Entry(config, Source.LOCAL, dir.name, localDir = dir)
    }

    private fun importZipInternal(zipFile: File, remoteUpdatedAt: Long = 0L): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException(appCtx.getString(R.string.top_bar_config_missing))
            val config = stripOutOfScopeFields(
                normalizeConfig(GSON.fromJsonObject<Config>(packageFile.readText()).getOrThrow())
            )
            if (remoteUpdatedAt == 0L) {
                config.updatedAt = System.currentTimeMillis()
            }
            val dirName = config.name.normalizeFileName().ifBlank { "top_bar_${System.currentTimeMillis()}" }
            val targetDir = localDir(config.isNightMode, dirName)
            if (targetDir.exists()) FileUtils.delete(targetDir, deleteRootDir = true)
            targetDir.mkdirs()
            packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
            val finalConfig = config.copy(
                wallpaperPath = normalizeWallpaperPath(config.wallpaperPath, targetDir)
            )
            File(targetDir, packageFileName).writeText(GSON.toJson(finalConfig))
            Entry(finalConfig, Source.LOCAL, dirName, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun normalizeConfig(config: Config): Config {
        config.style = when (config.style) {
            STYLE_DEFAULT, STYLE_REGULAR -> config.style
            "immersive", "flow" -> STYLE_REGULAR
            else -> STYLE_DEFAULT
        }
        config.tagBarAlpha = config.tagBarAlpha.coerceIn(0, 100)
        config.tagSelectedAlpha = config.tagSelectedAlpha.coerceIn(0, 100)
        config.wallpaperAlpha = config.wallpaperAlpha.coerceIn(0, 100)
        config.wallpaperPath = config.wallpaperPath?.takeIf { it.isNotBlank() }
        normalizeWallpaperCrop(config)
        config.cornerScale = config.cornerScale?.coerceIn(0f, 3f)
        return config
    }

    /**
     * 落盘净化（仅用于新保存与导入 ZIP 落盘，不污染迁移读取）：
     * 剥离已收归「界面布局」与「主题」的越界/重复字段，确保导出包纯净。
     */
    private fun stripOutOfScopeFields(config: Config): Config {
        return config.copy(
            style = STYLE_DEFAULT,
            tagBarColor = null,
            tagSelectedColor = null,
            backgroundColor = null,
            cornerScale = null,
            floatingChrome = false,
            expandFiltersByDefault = false,
            hideFilterToggleWhenExpanded = false,
            showSearchInDefaultStyle = false
        )
    }

    private fun normalizeWallpaperCrop(config: Config) {
        val left = config.wallpaperCropLeft?.coerceIn(0f, 1f)
        val top = config.wallpaperCropTop?.coerceIn(0f, 1f)
        val right = config.wallpaperCropRight?.coerceIn(0f, 1f)
        val bottom = config.wallpaperCropBottom?.coerceIn(0f, 1f)
        if (
            config.wallpaperPath.isNullOrBlank() ||
            left == null || top == null || right == null || bottom == null ||
            right <= left || bottom <= top
        ) {
            config.wallpaperCropLeft = null
            config.wallpaperCropTop = null
            config.wallpaperCropRight = null
            config.wallpaperCropBottom = null
            return
        }
        config.wallpaperCropLeft = left
        config.wallpaperCropTop = top
        config.wallpaperCropRight = right
        config.wallpaperCropBottom = bottom
    }

    private fun normalizeWallpaperPath(path: String?, dir: File): String? {
        val value = path?.takeIf { it.isNotBlank() } ?: return null
        val source = File(value)
        if (!source.isAbsolute) {
            return value
        }
        if (!source.exists() || !source.isFile) {
            return null
        }
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("top_bar_wallpaper.") }
            ?.forEach { it.delete() }
        val suffix = when {
            ImageTypeUtils.isAnimatedImage(source) -> ImageTypeUtils.preferredRasterExtension(source)
            source.extension.isNotBlank() -> source.extension
            else -> "jpg"
        }
        val target = File(dir, "top_bar_wallpaper.$suffix")
        if (source.absolutePath != target.absolutePath) {
            source.copyTo(target, overwrite = true)
        }
        return target.name
    }

    private fun resetActiveIfNeeded(entry: Entry) {
        if (activeDirName(entry.config.isNightMode) == entry.dirName) {
            appCtx.putPrefString(if (entry.config.isNightMode) activeNightKey else activeDayKey, DEFAULT_DIR_NAME)
        }
    }

    private fun localDir(isNight: Boolean, dirName: String): File = typeDir(isNight).getFile(dirName)

    private fun typeDir(isNight: Boolean): File {
        return rootDir.getFile(if (isNight) "night" else "day").apply { mkdirs() }
    }

    private fun defaultName(isNight: Boolean): String {
        return appCtx.getString(if (isNight) R.string.top_bar_night_default_name else R.string.top_bar_day_default_name)
    }
}
