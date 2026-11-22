package io.legado.app.help.config.pkg

import android.content.Context
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.lib.cloud.CloudStorageFile
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
    private val cacheLock = Any()

    @Volatile
    private var artIndexCache: Map<String, StoredEntry>? = null

    @Volatile
    private var paramIndexCache: Map<String, StoredEntry>? = null

    data class StoredEntry(
        val manifest: UnifiedPackageManifest,
        val dir: File
    )

    data class UnifiedImportResult(
        val importedArt: StoredEntry? = null,
        val importedParam: StoredEntry? = null,
        val duplicateArt: StoredEntry? = null,
        val duplicateParam: StoredEntry? = null,
        val nameConflictName: String? = null,
        val legacyTotal: Int = 0
    ) {
        val duplicateOfExisting: StoredEntry?
            get() = duplicateArt ?: duplicateParam

        val total: Int
            get() = (if (importedArt != null) 1 else 0) +
                (if (importedParam != null) 1 else 0) +
                (if (duplicateArt != null) 1 else 0) +
                (if (duplicateParam != null) 1 else 0) +
                legacyTotal
    }

    data class DeletePackageResult(
        val deletedId: String,
        val affectedSlots: List<String>,
        val fallbackEntry: StoredEntry?
    )

    /**
     * Pure JVM name-uniqueness & Copy-on-Write (CoW) helper functions.
     */
    fun normalizePackageName(raw: String): String {
        return raw.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }

    fun isPackageNameUnique(
        existingNames: Iterable<String>,
        candidateName: String
    ): Boolean {
        val norm = normalizePackageName(candidateName)
        if (norm.isBlank()) return false
        return existingNames.none { normalizePackageName(it) == norm }
    }

    fun suggestUniquePackageName(
        existingNames: Iterable<String>,
        baseName: String
    ): String {
        val cleanBase = baseName.trim().ifBlank { "未命名包" }
        val existingSet = existingNames.map(::normalizePackageName).toSet()
        if (normalizePackageName(cleanBase) !in existingSet) return cleanBase
        val copy1 = "$cleanBase 副本"
        if (normalizePackageName(copy1) !in existingSet) return copy1
        var index = 2
        while (true) {
            val candidate = "$cleanBase 副本 $index"
            if (normalizePackageName(candidate) !in existingSet) return candidate
            index++
        }
    }

    /**
     * Pure JVM Copy-on-Write asset resolver: checks [overlayDir] first, falling back to [baseDir]
     * when the file is inherited from a base package.
     *
     * Absolute paths are confined strictly inside [overlayDir], [baseDir], or explicitly granted
     * [allowedExternalRoots] to prevent reading arbitrary host paths from untrusted manifests.
     */
    fun resolveCoWAssetFile(
        overlayDir: File,
        baseDir: File?,
        relativePath: String?,
        allowedExternalRoots: List<File> = emptyList()
    ): File? {
        val trimmed = relativePath?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.startsWith("http", ignoreCase = true)) return null
        val direct = File(trimmed)
        if (direct.isAbsolute) {
            if (!direct.isFile) return null
            val allowedRoots = buildList {
                add(overlayDir)
                if (baseDir != null && baseDir.isDirectory) add(baseDir)
                addAll(allowedExternalRoots)
            }
            return direct.takeIf { candidate ->
                allowedRoots.any { root -> candidate.isSameOrSubFileOf(root) }
            }
        }
        val overlayCandidate = File(overlayDir, trimmed)
        if (overlayCandidate.isSameOrSubFileOf(overlayDir) && overlayCandidate.isFile) {
            return overlayCandidate
        }
        if (baseDir != null && baseDir.isDirectory) {
            val baseCandidate = File(baseDir, trimmed)
            if (baseCandidate.isSameOrSubFileOf(baseDir) && baseCandidate.isFile) {
                return baseCandidate
            }
        }
        return null
    }

    /**
     * Materializes a Copy-on-Write [overlayDir] (and its optional [baseDir]) into [targetDir]
     * so exported ZIPs or detached child packages become 100% self-contained.
     */
    fun materializeCoWDirectory(
        overlayDir: File,
        baseDir: File?,
        targetDir: File
    ) {
        targetDir.mkdirs()
        if (baseDir != null && baseDir.isDirectory && baseDir.absolutePath != targetDir.absolutePath) {
            baseDir.listFiles().orEmpty().forEach { child ->
                if (child.name == UnifiedPackageManifest.MANIFEST_FILE_NAME || child.name.startsWith(".")) {
                    return@forEach
                }
                val dst = File(targetDir, child.name)
                if (child.isDirectory) {
                    child.copyRecursively(dst, overwrite = false)
                } else if (child.isFile && !dst.exists()) {
                    child.copyTo(dst, overwrite = false)
                }
            }
        }
        if (overlayDir.isDirectory && overlayDir.absolutePath != targetDir.absolutePath) {
            overlayDir.listFiles().orEmpty().forEach { child ->
                if (child.name.startsWith(".")) return@forEach
                val dst = File(targetDir, child.name)
                if (child.isDirectory) {
                    child.copyRecursively(dst, overwrite = true)
                } else if (child.isFile) {
                    child.copyTo(dst, overwrite = true)
                }
            }
        }
    }

    val rootDir: File
        get() = appCtx.externalFiles.getFile("unifiedPackages").apply { mkdirs() }

    private val artDir: File
        get() = rootDir.getFile("art").apply { mkdirs() }

    private val paramDir: File
        get() = rootDir.getFile("param").apply { mkdirs() }

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("unifiedPackageTemp").apply { mkdirs() }

    fun invalidateCache() {
        synchronized(cacheLock) {
            artIndexCache = null
            paramIndexCache = null
        }
    }

    suspend fun prewarmCache() = withContext(IO) {
        repoMutex.withLock {
            loadEntries(artDir, PackageKind.ART)
            loadEntries(paramDir, PackageKind.PARAM)
        }
    }

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
        val key = if (AppConfig.isNightTheme) PreferKey.activeNightArtPackageId else PreferKey.activeDayArtPackageId
        return context.getPrefString(key, null)?.takeIf { it.isNotBlank() }
            ?: context.getPrefString(PreferKey.currentArtPackageId, "").orEmpty()
    }

    fun currentParamPackageId(context: Context = appCtx): String {
        val key = if (AppConfig.isNightTheme) PreferKey.activeNightParamPackageId else PreferKey.activeDayParamPackageId
        return context.getPrefString(key, null)?.takeIf { it.isNotBlank() }
            ?: context.getPrefString(PreferKey.currentParamPackageId, "").orEmpty()
    }

    fun activeArtPackageId(context: Context = appCtx, isNight: Boolean): String {
        val key = if (isNight) PreferKey.activeNightArtPackageId else PreferKey.activeDayArtPackageId
        return context.getPrefString(key, null)?.takeIf { it.isNotBlank() }
            ?: context.getPrefString(PreferKey.currentArtPackageId, "").orEmpty()
    }

    fun activeParamPackageId(context: Context = appCtx, isNight: Boolean): String {
        val key = if (isNight) PreferKey.activeNightParamPackageId else PreferKey.activeDayParamPackageId
        return context.getPrefString(key, null)?.takeIf { it.isNotBlank() }
            ?: context.getPrefString(PreferKey.currentParamPackageId, "").orEmpty()
    }

    fun getMountedSlotLabels(context: Context, entryId: String): List<String> {
        if (entryId.isBlank()) return emptyList()
        val labels = mutableListOf<String>()
        if (activeArtPackageId(context, false) == entryId) labels += "日间美术槽"
        if (activeArtPackageId(context, true) == entryId) labels += "夜间美术槽"
        if (activeParamPackageId(context, false) == entryId) labels += "日间参数槽"
        if (activeParamPackageId(context, true) == entryId) labels += "夜间参数槽"
        return labels
    }

    suspend fun ensureDefaultPackagesExist(context: Context = appCtx) {
        val arts = listArtPackages()
        if (arts.isEmpty()) {
            val dayArt = captureArtFromCurrent(context, "默认日间美术")
            val nightArt = captureArtFromCurrent(context, "默认夜间美术")
            context.putPrefString(PreferKey.activeDayArtPackageId, dayArt.manifest.id)
            context.putPrefString(PreferKey.activeNightArtPackageId, nightArt.manifest.id)
            context.putPrefString(
                PreferKey.currentArtPackageId,
                if (AppConfig.isNightTheme) nightArt.manifest.id else dayArt.manifest.id
            )
        } else {
            if (activeArtPackageId(context, false).isBlank() || arts.none { it.manifest.id == activeArtPackageId(context, false) }) {
                context.putPrefString(PreferKey.activeDayArtPackageId, arts.first().manifest.id)
            }
            if (activeArtPackageId(context, true).isBlank() || arts.none { it.manifest.id == activeArtPackageId(context, true) }) {
                val nightCandidate = arts.getOrNull(1) ?: arts.first()
                context.putPrefString(PreferKey.activeNightArtPackageId, nightCandidate.manifest.id)
            }
        }

        val params = listParamPackages()
        if (params.isEmpty()) {
            val dayParam = captureParamFromCurrent(context, "默认日间参数")
            val nightParam = captureParamFromCurrent(context, "默认夜间参数")
            context.putPrefString(PreferKey.activeDayParamPackageId, dayParam.manifest.id)
            context.putPrefString(PreferKey.activeNightParamPackageId, nightParam.manifest.id)
            context.putPrefString(
                PreferKey.currentParamPackageId,
                if (AppConfig.isNightTheme) nightParam.manifest.id else dayParam.manifest.id
            )
        } else {
            if (activeParamPackageId(context, false).isBlank() || params.none { it.manifest.id == activeParamPackageId(context, false) }) {
                context.putPrefString(PreferKey.activeDayParamPackageId, params.first().manifest.id)
            }
            if (activeParamPackageId(context, true).isBlank() || params.none { it.manifest.id == activeParamPackageId(context, true) }) {
                val nightCandidate = params.getOrNull(1) ?: params.first()
                context.putPrefString(PreferKey.activeNightParamPackageId, nightCandidate.manifest.id)
            }
        }
    }

    /**
     * Copy-on-Write (CoW) package clone with strict case-insensitive name uniqueness enforcement.
     *
     * Only writes `manifest.json` (with `basePackageId` pointing to the root base package) and
     * any existing overlay files, avoiding duplicating heavy base fonts/wallpapers/covers/icons.
     */
    suspend fun clonePackageCoW(
        kind: PackageKind,
        sourceId: String,
        newName: String
    ): StoredEntry = withContext(IO) {
        val cleanName = newName.trim()
        require(cleanName.isNotBlank()) { "包名称不能为空" }
        repoMutex.withLock {
            val root = if (kind == PackageKind.ART) artDir else paramDir
            val existing = loadEntries(root, kind)
            val source = existing.firstOrNull { it.manifest.id == sourceId }
                ?: throw IllegalArgumentException("未找到源包: $sourceId")
            require(isPackageNameUnique(existing.map { it.manifest.name }, cleanName)) {
                "包名称「$cleanName」已存在，严禁包名重复"
            }
            val dirName = PackageIoKernel.uniqueDirName(root, cleanName)
            val targetDir = File(root, dirName).apply { mkdirs() }
            if (source.manifest.basePackageId != null) {
                source.dir.listFiles().orEmpty().forEach { child ->
                    if (child.name == UnifiedPackageManifest.MANIFEST_FILE_NAME || child.name.startsWith(".")) {
                        return@forEach
                    }
                    val dst = File(targetDir, child.name)
                    if (child.isDirectory) child.copyRecursively(dst, overwrite = true)
                    else if (child.isFile) child.copyTo(dst, overwrite = true)
                }
            }
            val rootBaseId = if (kind == PackageKind.ART) {
                source.manifest.basePackageId ?: source.manifest.id
            } else {
                null
            }
            val newId = "${kind.id}_${UUID.randomUUID()}"
            val baseDir = rootBaseId?.let { bid -> existing.firstOrNull { it.manifest.id == bid }?.dir }
            val cowResolver: (String) -> File? = { rel -> resolveCoWAssetFile(targetDir, baseDir, rel) }
            val clonedManifest = source.manifest.copy(
                id = newId,
                name = cleanName,
                updatedAt = System.currentTimeMillis(),
                basePackageId = rootBaseId,
                contentHash = UnifiedPackageCodec.computeContentHash(source.manifest, targetDir, cowResolver)
            )
            writeManifest(targetDir, clonedManifest)
            StoredEntry(clonedManifest, targetDir).also {
                upsertCacheEntry(kind, it)
            }
        }
    }

    suspend fun saveEditedArtPackage(
        context: Context,
        entryId: String,
        newName: String,
        updatedArt: ArtPackagePayload
    ): StoredEntry = withContext(IO) {
        val cleanName = newName.trim()
        require(cleanName.isNotBlank()) { "包名称不能为空" }
        val saved = repoMutex.withLock {
            val existing = loadEntries(artDir, PackageKind.ART)
            val entriesById = existing.associateBy { it.manifest.id }
            val target = existing.firstOrNull { it.manifest.id == entryId }
                ?: throw IllegalArgumentException("未找到美术包: $entryId")
            val otherNames = existing.filter { it.manifest.id != entryId }.map { it.manifest.name }
            require(isPackageNameUnique(otherNames, cleanName)) {
                "包名称「$cleanName」已存在，严禁包名重复"
            }
            val packagedArt = copyArtAssetsIntoDir(updatedArt, target.dir)
            val baseDir = resolveBaseDir(target, entriesById)
            val cowResolver: (String) -> File? = { rel -> resolveCoWAssetFile(target.dir, baseDir, rel) }
            val draft = target.manifest.copy(
                name = cleanName,
                updatedAt = System.currentTimeMillis(),
                art = packagedArt
            )
            val finalManifest = draft.copy(
                contentHash = UnifiedPackageCodec.computeContentHash(draft, target.dir, cowResolver)
            )
            writeManifest(target.dir, finalManifest)
            StoredEntry(finalManifest, target.dir).also {
                upsertCacheEntry(PackageKind.ART, it)
            }
        }
        val activeDay = activeArtPackageId(context, false) == entryId
        val activeNight = activeArtPackageId(context, true) == entryId
        if (activeDay) mountArtPackage(context, saved, forNight = false)
        if (activeNight) mountArtPackage(context, saved, forNight = true)
        saved
    }

    suspend fun saveEditedParamPackage(
        context: Context,
        entryId: String,
        newName: String,
        updatedParam: ParamPackagePayload
    ): StoredEntry = withContext(IO) {
        val cleanName = newName.trim()
        require(cleanName.isNotBlank()) { "包名称不能为空" }
        val saved = repoMutex.withLock {
            val existing = loadEntries(paramDir, PackageKind.PARAM)
            val target = existing.firstOrNull { it.manifest.id == entryId }
                ?: throw IllegalArgumentException("未找到参数包: $entryId")
            val otherNames = existing.filter { it.manifest.id != entryId }.map { it.manifest.name }
            require(isPackageNameUnique(otherNames, cleanName)) {
                "包名称「$cleanName」已存在，严禁包名重复"
            }
            val draft = target.manifest.copy(
                name = cleanName,
                updatedAt = System.currentTimeMillis(),
                param = updatedParam
            )
            val finalManifest = draft.copy(
                contentHash = UnifiedPackageCodec.computeContentHash(draft, target.dir)
            )
            writeManifest(target.dir, finalManifest)
            StoredEntry(finalManifest, target.dir).also {
                upsertCacheEntry(PackageKind.PARAM, it)
            }
        }
        val activeDay = activeParamPackageId(context, false) == entryId
        val activeNight = activeParamPackageId(context, true) == entryId
        if (activeDay) mountParamPackage(context, saved, forNight = false, applyGeometryGlobal = true)
        if (activeNight) mountParamPackage(context, saved, forNight = true, applyGeometryGlobal = !activeDay)
        saved
    }

    suspend fun deletePackage(
        context: Context,
        kind: PackageKind,
        entryId: String
    ): DeletePackageResult = withContext(IO) {
        val affectedSlots = getMountedSlotLabels(context, entryId)
        val fallback = repoMutex.withLock {
            val root = if (kind == PackageKind.ART) artDir else paramDir
            val existing = loadEntries(root, kind)
            require(existing.size > 1) { "至少需要保留 1 个包，无法删除最后一个包" }
            val target = existing.firstOrNull { it.manifest.id == entryId }
                ?: throw IllegalArgumentException("未找到待删除的包: $entryId")

            // Materialize any CoW dependent packages before deleting the base directory
            existing.filter { it.manifest.basePackageId == entryId }.forEach { dependent ->
                materializeCoWDirectory(dependent.dir, target.dir, dependent.dir)
                val detachedManifest = dependent.manifest.copy(
                    basePackageId = null,
                    contentHash = UnifiedPackageCodec.computeContentHash(dependent.manifest, dependent.dir)
                )
                writeManifest(dependent.dir, detachedManifest)
                upsertCacheEntry(kind, StoredEntry(detachedManifest, dependent.dir))
            }
            PackageIoKernel.deletePackageDirSafely(target.dir, root)
            removeCacheEntry(kind, entryId)
            existing.firstOrNull { it.manifest.id != entryId }
        }
        if (fallback != null) {
            if (kind == PackageKind.ART) {
                if (activeArtPackageId(context, false) == entryId) {
                    mountArtPackage(context, fallback, forNight = false)
                }
                if (activeArtPackageId(context, true) == entryId) {
                    mountArtPackage(context, fallback, forNight = true)
                }
            } else if (kind == PackageKind.PARAM) {
                if (activeParamPackageId(context, false) == entryId) {
                    mountParamPackage(context, fallback, forNight = false, applyGeometryGlobal = true)
                }
                if (activeParamPackageId(context, true) == entryId) {
                    mountParamPackage(context, fallback, forNight = true, applyGeometryGlobal = true)
                }
            }
        }
        DeletePackageResult(deletedId = entryId, affectedSlots = affectedSlots, fallbackEntry = fallback)
    }

    suspend fun saveMigratedPair(
        idSlug: String,
        name: String,
        art: ArtPackagePayload?,
        param: ParamPackagePayload?,
        overwriteExisting: Boolean = false
    ) = withContext(IO) {
        repoMutex.withLock {
            val now = System.currentTimeMillis()
            val cleanSlug = idSlug.normalizeFileName().ifBlank { "migrated_${UUID.randomUUID()}" }
            if (art != null) {
                val existingArts = loadEntries(artDir, PackageKind.ART)
                val artId = "art_$cleanSlug"
                val existingById = existingArts.firstOrNull { it.manifest.id == artId }
                if (existingById == null || overwriteExisting) {
                    val uniqueName = existingById?.manifest?.name ?: suggestUniquePackageName(
                        existingArts.map { it.manifest.name },
                        name.ifBlank { cleanSlug }
                    )
                    val targetDir = existingById?.dir ?: File(
                        artDir,
                        PackageIoKernel.uniqueDirName(artDir, cleanSlug) { dir ->
                            readManifest(dir)?.id == artId
                        }
                    ).apply { mkdirs() }
                    val packagedArt = copyArtAssetsIntoDir(art, targetDir)
                    val draft = UnifiedPackageManifest(
                        kind = PackageKind.ART,
                        id = artId,
                        name = uniqueName,
                        updatedAt = now,
                        art = packagedArt
                    )
                    val manifest = draft.copy(
                        contentHash = UnifiedPackageCodec.computeContentHash(draft, targetDir)
                    )
                    writeManifest(targetDir, manifest)
                    upsertCacheEntry(PackageKind.ART, StoredEntry(manifest, targetDir))
                }
            }
            if (param != null) {
                val existingParams = loadEntries(paramDir, PackageKind.PARAM)
                val paramId = "param_$cleanSlug"
                val existingById = existingParams.firstOrNull { it.manifest.id == paramId }
                if (existingById == null || overwriteExisting) {
                    val uniqueName = existingById?.manifest?.name ?: suggestUniquePackageName(
                        existingParams.map { it.manifest.name },
                        name.ifBlank { cleanSlug }
                    )
                    val targetDir = existingById?.dir ?: File(
                        paramDir,
                        PackageIoKernel.uniqueDirName(paramDir, cleanSlug) { dir ->
                            readManifest(dir)?.id == paramId
                        }
                    ).apply { mkdirs() }
                    val draft = UnifiedPackageManifest(
                        kind = PackageKind.PARAM,
                        id = paramId,
                        name = uniqueName,
                        updatedAt = now,
                        param = param
                    )
                    val manifest = draft.copy(
                        contentHash = UnifiedPackageCodec.computeContentHash(draft, targetDir)
                    )
                    writeManifest(targetDir, manifest)
                    upsertCacheEntry(PackageKind.PARAM, StoredEntry(manifest, targetDir))
                }
            }
        }
    }

    suspend fun captureArtFromCurrent(
        context: Context,
        name: String,
        enforceUniqueName: Boolean = false
    ): StoredEntry = withContext(IO) {
        val rawName = name.trim().ifBlank { context.getString(R.string.appearance_kit_manage) }
        repoMutex.withLock {
            val existing = loadEntries(artDir, PackageKind.ART)
            val existingNames = existing.map { it.manifest.name }
            val cleanName = if (enforceUniqueName) {
                require(isPackageNameUnique(existingNames, rawName)) {
                    "包名称「$rawName」已存在，严禁包名重复"
                }
                rawName
            } else {
                suggestUniquePackageName(existingNames, rawName)
            }
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
            val draft = UnifiedPackageManifest(
                kind = PackageKind.ART,
                id = "art_${UUID.randomUUID()}",
                name = cleanName,
                updatedAt = System.currentTimeMillis(),
                art = packagedArt
            )
            val manifest = draft.copy(
                contentHash = UnifiedPackageCodec.computeContentHash(draft, targetDir)
            )
            writeManifest(targetDir, manifest)
            context.putPrefString(PreferKey.currentArtPackageId, manifest.id)
            StoredEntry(manifest, targetDir).also {
                upsertCacheEntry(PackageKind.ART, it)
            }
        }
    }

    suspend fun captureParamFromCurrent(
        context: Context,
        name: String,
        includeSurface: Boolean = true,
        includeLayout: Boolean = true,
        enforceUniqueName: Boolean = false
    ): StoredEntry = withContext(IO) {
        val rawName = name.trim().ifBlank { "Param Preset" }
        repoMutex.withLock {
            val existing = loadEntries(paramDir, PackageKind.PARAM)
            val existingNames = existing.map { it.manifest.name }
            val cleanName = if (enforceUniqueName) {
                require(isPackageNameUnique(existingNames, rawName)) {
                    "包名称「$rawName」已存在，严禁包名重复"
                }
                rawName
            } else {
                suggestUniquePackageName(existingNames, rawName)
            }
            val dirName = PackageIoKernel.uniqueDirName(paramDir, cleanName)
            val targetDir = File(paramDir, dirName).apply { mkdirs() }
            val payload = ParamPackagePayload(
                surfaceGlobal = if (includeSurface) captureSurfaceGlobal(context) else null,
                surfaceScopes = if (includeSurface) captureSurfaceScopes(context) else null,
                layout = if (includeLayout) captureLayoutParams(context) else null
            )
            val draft = UnifiedPackageManifest(
                kind = PackageKind.PARAM,
                id = "param_${UUID.randomUUID()}",
                name = cleanName,
                updatedAt = System.currentTimeMillis(),
                param = payload
            )
            val manifest = draft.copy(
                contentHash = UnifiedPackageCodec.computeContentHash(draft, targetDir)
            )
            writeManifest(targetDir, manifest)
            context.putPrefString(PreferKey.currentParamPackageId, manifest.id)
            context.putPrefBoolean(PreferKey.paramPackageDirty, false)
            StoredEntry(manifest, targetDir).also {
                upsertCacheEntry(PackageKind.PARAM, it)
            }
        }
    }

    /**
     * Mounts an [ArtPackagePayload] into either the Day slot (`forNight = false`) or Night slot (`forNight = true`).
     */
    suspend fun mountArtPackage(
        context: Context,
        entry: StoredEntry,
        forNight: Boolean = AppConfig.isNightTheme,
        applyMounts: Boolean = true
    ) {
        val art = entry.manifest.art ?: return
        val baseDir = resolveBaseDir(entry)
        val palette = if (forNight) (art.nightPalette ?: art.dayPalette) else (art.dayPalette ?: art.nightPalette)
        val wallpapers = if (forNight) (art.nightWallpapers ?: art.dayWallpapers) else (art.dayWallpapers ?: art.nightWallpapers)
        val typography = if (forNight) (art.nightTypography ?: art.dayTypography) else (art.dayTypography ?: art.nightTypography)

        PrefBatchTransaction.beginBatch()
        try {
            palette?.let { applyPalette(context, isNight = forNight, it) }
            wallpapers?.let { applyWallpapers(context, entry.dir, baseDir, isNight = forNight, it) }
            typography?.let { applyTypography(context, entry.dir, baseDir, isNight = forNight, it) }
            if (applyMounts) {
                applyMountsForSlot(context, art.mounts, forNight)
            }
            context.putPrefString(
                if (forNight) PreferKey.activeNightArtPackageId else PreferKey.activeDayArtPackageId,
                entry.manifest.id
            )
            if (AppConfig.isNightTheme == forNight) {
                context.putPrefString(PreferKey.currentArtPackageId, entry.manifest.id)
            }
        } finally {
            PrefBatchTransaction.endBatch {
                context.applicationContext.recomputeThemeUiSignatureAfterBatch()
            }
        }
        if (AppConfig.isNightTheme == forNight) {
            ThemeConfig.applyTheme(context)
            BookCover.upDefaultCover()
            postEvent(EventBus.MAIN_THEME_BACKGROUND_CHANGED, AppConfig.isNightTheme)
            postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
        }
    }

    suspend fun applyArtPackage(
        context: Context,
        entry: StoredEntry,
        applyMounts: Boolean = true
    ) {
        val art = entry.manifest.art ?: return
        val baseDir = resolveBaseDir(entry)
        PrefBatchTransaction.beginBatch()
        try {
            art.dayPalette?.let { applyPalette(context, isNight = false, it) }
            art.nightPalette?.let { applyPalette(context, isNight = true, it) }
            art.dayWallpapers?.let { applyWallpapers(context, entry.dir, baseDir, isNight = false, it) }
            art.nightWallpapers?.let { applyWallpapers(context, entry.dir, baseDir, isNight = true, it) }
            art.dayTypography?.let { applyTypography(context, entry.dir, baseDir, isNight = false, it) }
            art.nightTypography?.let { applyTypography(context, entry.dir, baseDir, isNight = true, it) }
            if (applyMounts) {
                applyMounts(context, art.mounts)
            }
            context.putPrefString(PreferKey.currentArtPackageId, entry.manifest.id)
            context.putPrefString(
                if (AppConfig.isNightTheme) PreferKey.activeNightArtPackageId else PreferKey.activeDayArtPackageId,
                entry.manifest.id
            )
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

    /**
     * Mounts a [ParamPackagePayload] into the Day or Night slot:
     * - `@atmosphere` (materialMode, levels/scales, surface/dialog/border alpha, wallpaper blur)
     *   is bound to [forNight];
     * - `@geometry` (cornerBaseScale, cardShadow, densityScale, 4 surfaceScopes) is global single-track
     *   and applied only when [applyGeometryGlobal] is true, preventing Day/Night geometry jumps.
     */
    suspend fun mountParamPackage(
        context: Context,
        entry: StoredEntry,
        forNight: Boolean = AppConfig.isNightTheme,
        applyGeometryGlobal: Boolean = true
    ) {
        val param = entry.manifest.param ?: return
        val isActiveMode = AppConfig.isNightTheme == forNight
        PrefBatchTransaction.beginBatch()
        try {
            param.surfaceGlobal?.let { g ->
                applyParamAtmosphere(context, g, isNight = forNight, activateGlassNow = isActiveMode)
                if (applyGeometryGlobal) {
                    applyParamGeometryGlobal(context, g, param.surfaceScopes, param.layout?.densityScale)
                }
            } ?: run {
                if (applyGeometryGlobal && param.surfaceScopes != null) {
                    applySurfaceScopes(context, param.surfaceScopes)
                }
            }
            context.putPrefString(
                if (forNight) PreferKey.activeNightParamPackageId else PreferKey.activeDayParamPackageId,
                entry.manifest.id
            )
            if (isActiveMode) {
                context.putPrefString(PreferKey.currentParamPackageId, entry.manifest.id)
            }
            context.putPrefBoolean(PreferKey.paramPackageDirty, false)
        } finally {
            PrefBatchTransaction.endBatch {
                context.applicationContext.recomputeThemeUiSignatureAfterBatch()
            }
        }
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
    }

    /**
     * Called when Day/Night mode switches (`ThemeConfig.applyDayNight`):
     * Re-projects the mounted `ArtPackage` and ONLY the `@atmosphere` layer of the mounted
     * `ParamPackage` for [isNight], keeping `@geometry` (`cornerBaseScale`, `densityScale`,
     * `cardShadow`, 4 `surfaceScopes`) completely stable across Day/Night transitions.
     */
    suspend fun onDayNightModeSwitched(context: Context, isNight: Boolean) = withContext(IO) {
        applyDayNightMountedSync(context, isNight)
    }

    /**
     * Synchronously projects the mounted Day or Night `ArtPackage` and ONLY the `@atmosphere`
     * layer of the mounted `ParamPackage` for [isNight] before `ThemeConfig.applyTheme` runs,
     * leaving `@geometry` (`cornerBaseScale`, `densityScale`, `cardShadow`, `surfaceScopes`) untouched.
     *
     * Uses the synchronized in-memory index cache ([artIndexCache] / [paramIndexCache]) so
     * repeated Day/Night toggles perform O(1) memory lookups with zero directory scans and
     * zero CoW O(N^2) overhead.
     */
    fun applyDayNightMountedSync(context: Context, isNight: Boolean) {
        if (context.getPrefBoolean(PreferKey.useLegacyPackageManager, false)) return
        val targetArtId = activeArtPackageId(context, isNight)
        val targetParamId = activeParamPackageId(context, isNight)
        val (artMap, paramMap) = synchronized(cacheLock) {
            val arts = if (targetArtId.isNotBlank()) {
                artIndexCache ?: loadEntriesIntoCacheLocked(artDir, PackageKind.ART)
            } else {
                emptyMap()
            }
            val params = if (targetParamId.isNotBlank()) {
                paramIndexCache ?: loadEntriesIntoCacheLocked(paramDir, PackageKind.PARAM)
            } else {
                emptyMap()
            }
            arts to params
        }
        val artEntry = if (targetArtId.isNotBlank()) artMap[targetArtId] else null
        val paramEntry = if (targetParamId.isNotBlank()) paramMap[targetParamId] else null
        if (artEntry == null && paramEntry == null) return

        PrefBatchTransaction.beginBatch()
        try {
            if (artEntry != null) {
                val art = artEntry.manifest.art
                val baseDir = resolveBaseDir(artEntry, artMap)
                val palette = if (isNight) (art?.nightPalette ?: art?.dayPalette) else (art?.dayPalette ?: art?.nightPalette)
                val wallpapers = if (isNight) (art?.nightWallpapers ?: art?.dayWallpapers) else (art?.dayWallpapers ?: art?.nightWallpapers)
                val typography = if (isNight) (art?.nightTypography ?: art?.dayTypography) else (art?.dayTypography ?: art?.nightTypography)
                palette?.let { applyPalette(context, isNight = isNight, it) }
                wallpapers?.let { applyWallpapers(context, artEntry.dir, baseDir, isNight = isNight, it) }
                typography?.let { applyTypography(context, artEntry.dir, baseDir, isNight = isNight, it) }
                context.putPrefString(PreferKey.currentArtPackageId, artEntry.manifest.id)
            }
            if (paramEntry != null) {
                paramEntry.manifest.param?.surfaceGlobal?.let { g ->
                    applyParamAtmosphere(context, g, isNight = isNight, activateGlassNow = true)
                }
                context.putPrefString(PreferKey.currentParamPackageId, paramEntry.manifest.id)
            }
        } finally {
            PrefBatchTransaction.endBatch {
                context.applicationContext.recomputeThemeUiSignatureAfterBatch()
            }
        }
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
            context.putPrefString(
                if (AppConfig.isNightTheme) PreferKey.activeNightParamPackageId else PreferKey.activeDayParamPackageId,
                entry.manifest.id
            )
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

    suspend fun listCloudPackages(
        kind: PackageKind,
        containerId: String? = null,
        scope: String? = null
    ): List<CloudStorageFile> = withContext(IO) {
        AppCloudStorage.listUnifiedPackages(kind, containerId, scope)
    }

    suspend fun uploadEntry(
        entry: StoredEntry,
        containerId: String? = null,
        scope: String? = null
    ) = withContext(IO) {
        val zipFile = exportEntryZip(entry)
        try {
            AppCloudStorage.uploadUnifiedPackage(
                kind = entry.manifest.kind,
                remoteDirName = entry.manifest.name.normalizeFileName().ifBlank { entry.dir.name },
                zipFile = zipFile,
                containerId = containerId,
                scope = scope
            )
        } finally {
            zipFile.delete()
        }
    }

    suspend fun downloadAndImportCloudPackage(
        context: Context,
        kind: PackageKind,
        remoteName: String,
        importSurface: Boolean = true,
        importLayout: Boolean = false,
        customNameOverride: String? = null,
        containerId: String? = null,
        scope: String? = null
    ): UnifiedImportResult = withContext(IO) {
        val tempZip = File(tempDir, "cloud_${kind.id}_${System.currentTimeMillis()}.zip")
        try {
            AppCloudStorage.downloadUnifiedPackage(
                kind = kind,
                remoteDirName = remoteName.removeSuffix(".zip"),
                zipFile = tempZip,
                containerId = containerId,
                scope = scope
            )
            importFile(
                context = context,
                file = tempZip,
                importSurface = importSurface,
                importLayout = importLayout,
                customNameOverride = customNameOverride
            )
        } finally {
            tempZip.delete()
        }
    }

    suspend fun deleteCloudPackage(
        kind: PackageKind,
        remoteName: String,
        containerId: String? = null,
        scope: String? = null
    ) = withContext(IO) {
        AppCloudStorage.deleteUnifiedPackage(
            kind = kind,
            remoteDirName = remoteName.removeSuffix(".zip"),
            containerId = containerId,
            scope = scope
        )
    }

    /**
     * Exports [entry] as a 100% self-contained ZIP: if [entry] uses Copy-on-Write (`basePackageId != null`),
     * materializes `base + overlay` into a temporary staging folder before zipping.
     */
    suspend fun exportEntryZip(entry: StoredEntry): File = withContext(IO) {
        val outZip = File(tempDir, "${entry.manifest.kind.id}_${entry.dir.name}_${System.currentTimeMillis()}.zip")
        val baseDir = resolveBaseDir(entry)
        if (baseDir == null) {
            PackageIoKernel.zipDirectoryAtomic(entry.dir, outZip)
        } else {
            val materializedStaging = File(tempDir, "mat_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                materializeCoWDirectory(entry.dir, baseDir, materializedStaging)
                val detached = entry.manifest.copy(basePackageId = null)
                val finalManifest = detached.copy(
                    contentHash = UnifiedPackageCodec.computeContentHash(detached, materializedStaging)
                )
                writeManifest(materializedStaging, finalManifest)
                PackageIoKernel.zipDirectoryAtomic(materializedStaging, outZip)
            } finally {
                FileUtils.delete(materializedStaging, deleteRootDir = true)
            }
        }
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
        importLayout: Boolean = false,
        customNameOverride: String? = null
    ): UnifiedImportResult = withContext(IO) {
        val kind = peekKind(file)
        if (kind == null) {
            val legacy = AppearanceKitManager.importPackage(file)
            LegacyPackageMigrator.migrateLocalPackagesIfNeeded(context, forceReconcile = true)
            return@withContext UnifiedImportResult(legacyTotal = legacy.total)
        }
        val isRawJson = file.inputStream().use { it.read() == '{'.code }
        if (isRawJson) {
            val manifest = UnifiedPackageCodec.decode(file.readText())
            return@withContext importManifestOnly(context, manifest, importSurface, importLayout, customNameOverride)
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
                var duplicateArt: StoredEntry? = null
                var duplicateParam: StoredEntry? = null

                if (manifest.art != null && (manifest.kind == PackageKind.ART || manifest.kind == PackageKind.KIT)) {
                    val existingArts = loadEntries(artDir, PackageKind.ART)
                    val artMap = existingArts.associateBy { it.manifest.id }
                    val artCandidateManifest = manifest.copy(kind = PackageKind.ART, param = null)
                    val incomingArtHash = UnifiedPackageCodec.computeContentHash(artCandidateManifest, packageRoot)
                    val sameContent = existingArts.firstOrNull {
                        ensureCurrentContentHashLocked(it, artMap) == incomingArtHash
                    }
                    if (sameContent != null) {
                        duplicateArt = sameContent
                    } else {
                        val candidateName = customNameOverride?.trim()?.takeIf { it.isNotBlank() }
                            ?: manifest.name.trim().ifBlank { "导入美术包" }
                        val existingNames = existingArts.map { it.manifest.name }
                        if (!isPackageNameUnique(existingNames, candidateName)) {
                            return@withLock UnifiedImportResult(nameConflictName = candidateName)
                        }
                        val slug = PackageIoKernel.uniqueDirName(artDir, candidateName)
                        val dst = File(artDir, slug).apply { mkdirs() }
                        packageRoot.copyRecursively(dst, overwrite = true)
                        val artManifest = artCandidateManifest.copy(
                            id = "art_$slug",
                            name = candidateName,
                            updatedAt = System.currentTimeMillis(),
                            basePackageId = null,
                            contentHash = incomingArtHash
                        )
                        writeManifest(dst, artManifest)
                        importedArt = StoredEntry(artManifest, dst).also {
                            upsertCacheEntry(PackageKind.ART, it)
                        }
                    }
                }
                if (manifest.param != null && (manifest.kind == PackageKind.PARAM || manifest.kind == PackageKind.KIT)) {
                    val existingParams = loadEntries(paramDir, PackageKind.PARAM)
                    val filteredParam = ParamPackagePayload(
                        surfaceGlobal = if (importSurface) manifest.param.surfaceGlobal else null,
                        surfaceScopes = if (importSurface) manifest.param.surfaceScopes else null,
                        layout = if (importLayout) manifest.param.layout else null,
                        unknownFields = manifest.param.unknownFields
                    )
                    val candidateManifest = manifest.copy(kind = PackageKind.PARAM, art = null, param = filteredParam)
                    val paramHash = UnifiedPackageCodec.computeContentHash(candidateManifest)
                    val sameContent = existingParams.firstOrNull {
                        ensureCurrentContentHashLocked(it) == paramHash
                    }
                    if (sameContent != null) {
                        duplicateParam = sameContent
                    } else {
                        val candidateName = customNameOverride?.trim()?.takeIf { it.isNotBlank() }
                            ?: manifest.name.trim().ifBlank { "导入参数包" }
                        val existingNames = existingParams.map { it.manifest.name }
                        if (!isPackageNameUnique(existingNames, candidateName)) {
                            return@withLock UnifiedImportResult(nameConflictName = candidateName)
                        }
                        val slug = PackageIoKernel.uniqueDirName(paramDir, candidateName)
                        val dst = File(paramDir, slug).apply { mkdirs() }
                        val paramManifest = candidateManifest.copy(
                            id = "param_$slug",
                            name = candidateName,
                            updatedAt = System.currentTimeMillis(),
                            basePackageId = null,
                            contentHash = paramHash
                        )
                        writeManifest(dst, paramManifest)
                        importedParam = StoredEntry(paramManifest, dst).also {
                            upsertCacheEntry(PackageKind.PARAM, it)
                        }
                    }
                }
                UnifiedImportResult(
                    importedArt = importedArt,
                    importedParam = importedParam,
                    duplicateArt = duplicateArt,
                    duplicateParam = duplicateParam
                )
            }
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private suspend fun importManifestOnly(
        context: Context,
        manifest: UnifiedPackageManifest,
        importSurface: Boolean,
        importLayout: Boolean,
        customNameOverride: String? = null
    ): UnifiedImportResult = repoMutex.withLock {
        var importedArt: StoredEntry? = null
        var importedParam: StoredEntry? = null
        var duplicateArt: StoredEntry? = null
        var duplicateParam: StoredEntry? = null

        if (manifest.art != null && (manifest.kind == PackageKind.ART || manifest.kind == PackageKind.KIT)) {
            val existingArts = loadEntries(artDir, PackageKind.ART)
            val artMap = existingArts.associateBy { it.manifest.id }
            val artCandidate = manifest.copy(kind = PackageKind.ART, param = null)
            val incomingArtHash = UnifiedPackageCodec.computeContentHash(artCandidate)
            val sameContent = existingArts.firstOrNull {
                ensureCurrentContentHashLocked(it, artMap) == incomingArtHash
            }
            if (sameContent != null) {
                duplicateArt = sameContent
            } else {
                val candidateName = customNameOverride?.trim()?.takeIf { it.isNotBlank() }
                    ?: manifest.name.trim().ifBlank { "导入美术包" }
                if (!isPackageNameUnique(existingArts.map { it.manifest.name }, candidateName)) {
                    return@withLock UnifiedImportResult(nameConflictName = candidateName)
                }
                val slug = PackageIoKernel.uniqueDirName(artDir, candidateName)
                val dst = File(artDir, slug).apply { mkdirs() }
                val m = artCandidate.copy(
                    id = "art_$slug",
                    name = candidateName,
                    updatedAt = System.currentTimeMillis(),
                    contentHash = incomingArtHash
                )
                writeManifest(dst, m)
                importedArt = StoredEntry(m, dst).also {
                    upsertCacheEntry(PackageKind.ART, it)
                }
            }
        }
        if (manifest.param != null && (manifest.kind == PackageKind.PARAM || manifest.kind == PackageKind.KIT)) {
            val existingParams = loadEntries(paramDir, PackageKind.PARAM)
            val filtered = ParamPackagePayload(
                surfaceGlobal = if (importSurface) manifest.param.surfaceGlobal else null,
                surfaceScopes = if (importSurface) manifest.param.surfaceScopes else null,
                layout = if (importLayout) manifest.param.layout else null,
                unknownFields = manifest.param.unknownFields
            )
            val candidateManifest = manifest.copy(kind = PackageKind.PARAM, art = null, param = filtered)
            val paramHash = UnifiedPackageCodec.computeContentHash(candidateManifest)
            val sameContent = existingParams.firstOrNull {
                ensureCurrentContentHashLocked(it) == paramHash
            }
            if (sameContent != null) {
                duplicateParam = sameContent
            } else {
                val candidateName = customNameOverride?.trim()?.takeIf { it.isNotBlank() }
                    ?: manifest.name.trim().ifBlank { "导入参数包" }
                if (!isPackageNameUnique(existingParams.map { it.manifest.name }, candidateName)) {
                    return@withLock UnifiedImportResult(nameConflictName = candidateName)
                }
                val slug = PackageIoKernel.uniqueDirName(paramDir, candidateName)
                val dst = File(paramDir, slug).apply { mkdirs() }
                val m = candidateManifest.copy(
                    id = "param_$slug",
                    name = candidateName,
                    updatedAt = System.currentTimeMillis(),
                    contentHash = paramHash
                )
                writeManifest(dst, m)
                importedParam = StoredEntry(m, dst).also {
                    upsertCacheEntry(PackageKind.PARAM, it)
                }
            }
        }
        UnifiedImportResult(
            importedArt = importedArt,
            importedParam = importedParam,
            duplicateArt = duplicateArt,
            duplicateParam = duplicateParam
        )
    }

    /**
     * Ensures [entry] carries a versioned binary-aware (`v2:`) contentHash:
     * - O(1) immediate return if already upgraded;
     * - One-time lazy recomputation & atomic persist for legacy hashes, preventing repeated
     *   binary scans on subsequent imports and fixing legacy v1 hash misses.
     */
    private fun ensureCurrentContentHashLocked(
        entry: StoredEntry,
        allById: Map<String, StoredEntry>? = null
    ): String {
        val current = entry.manifest.contentHash
        if (UnifiedPackageCodec.isCurrentContentHash(current)) {
            return current!!
        }
        val upgradedHash = if (entry.manifest.kind == PackageKind.ART) {
            val baseDir = resolveBaseDir(entry, allById)
            UnifiedPackageCodec.computeContentHash(
                entry.manifest,
                entry.dir
            ) { rel -> resolveCoWAssetFile(entry.dir, baseDir, rel) }
        } else {
            UnifiedPackageCodec.computeContentHash(entry.manifest)
        }
        val updatedManifest = entry.manifest.copy(contentHash = upgradedHash)
        runCatching {
            writeManifest(entry.dir, updatedManifest)
            upsertCacheEntry(entry.manifest.kind, StoredEntry(updatedManifest, entry.dir))
        }
        return upgradedHash
    }

    private fun upsertCacheEntry(kind: PackageKind, entry: StoredEntry) {
        synchronized(cacheLock) {
            if (kind == PackageKind.ART) {
                val cur = artIndexCache ?: return
                artIndexCache = cur + (entry.manifest.id to entry)
            } else if (kind == PackageKind.PARAM) {
                val cur = paramIndexCache ?: return
                paramIndexCache = cur + (entry.manifest.id to entry)
            }
        }
    }

    private fun removeCacheEntry(kind: PackageKind, id: String) {
        synchronized(cacheLock) {
            if (kind == PackageKind.ART) {
                artIndexCache = artIndexCache?.minus(id)
            } else if (kind == PackageKind.PARAM) {
                paramIndexCache = paramIndexCache?.minus(id)
            }
        }
    }

    private fun loadEntriesIntoCacheLocked(root: File, expectedKind: PackageKind): Map<String, StoredEntry> {
        val loaded = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                readManifest(dir)
                    ?.takeIf { it.kind == expectedKind }
                    ?.let { StoredEntry(it, dir) }
            }
            .orEmpty()
        val map = loaded.associateBy { it.manifest.id }
        if (expectedKind == PackageKind.ART) {
            artIndexCache = map
        } else if (expectedKind == PackageKind.PARAM) {
            paramIndexCache = map
        }
        return map
    }

    private fun loadEntries(root: File, expectedKind: PackageKind): List<StoredEntry> {
        val map = synchronized(cacheLock) {
            loadEntriesIntoCacheLocked(root, expectedKind)
        }
        return map.values.sortedByDescending { it.manifest.updatedAt }
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

    private fun resolveBaseDir(
        entry: StoredEntry,
        entriesById: Map<String, StoredEntry>? = null
    ): File? {
        val baseId = entry.manifest.basePackageId?.takeIf { it.isNotBlank() } ?: return null
        if (entriesById != null) {
            return entriesById[baseId]?.dir
        }
        val cached = synchronized(cacheLock) {
            if (entry.manifest.kind == PackageKind.ART) artIndexCache else paramIndexCache
        }
        if (cached != null) {
            return cached[baseId]?.dir
        }
        val root = if (entry.manifest.kind == PackageKind.ART) artDir else paramDir
        return loadEntries(root, entry.manifest.kind)
            .firstOrNull { it.manifest.id == baseId }
            ?.dir
    }

    private fun resolvePackageAssetPath(packageDir: File, baseDir: File?, path: String?): String? {
        val trimmed = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        val allowedRoots = listOfNotNull(runCatching { appCtx.externalFiles }.getOrNull())
        return resolveCoWAssetFile(packageDir, baseDir, trimmed, allowedRoots)?.absolutePath
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
        baseDir: File?,
        isNight: Boolean,
        w: ArtWallpapersSpec
    ) {
        putOrRemovePref(
            context,
            if (isNight) PreferKey.bgImageN else PreferKey.bgImage,
            resolvePackageAssetPath(packageDir, baseDir, w.main)
        )
        putOrRemovePref(
            context,
            if (isNight) PreferKey.bgImageNCrop else PreferKey.bgImageCrop,
            w.mainCrop
        )
        putOrRemovePref(
            context,
            if (isNight) PreferKey.topBarBgImageN else PreferKey.topBarBgImage,
            resolvePackageAssetPath(packageDir, baseDir, w.topBar)
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
            resolvePackageAssetPath(packageDir, baseDir, w.bookInfo)
        )
        putOrRemovePref(
            context,
            if (isNight) PreferKey.panelBgImageN else PreferKey.panelBgImage,
            resolvePackageAssetPath(packageDir, baseDir, w.panel)
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
                resolvePackageAssetPath(packageDir, baseDir, it)
            )
        }
    }

    private fun applyTypography(
        context: Context,
        packageDir: File,
        baseDir: File?,
        isNight: Boolean,
        t: ArtTypographySpec
    ) {
        putOrRemovePref(
            context,
            ThemeRuntimeKeys.uiFontPath(isNight),
            resolvePackageAssetPath(packageDir, baseDir, t.uiFontPath)
        )
        putOrRemovePref(
            context,
            ThemeRuntimeKeys.titleFontPath(isNight),
            resolvePackageAssetPath(packageDir, baseDir, t.titleFontPath)
        )
    }

    private suspend fun applyMountsForSlot(context: Context, mounts: ArtAssetMounts, isNight: Boolean) {
        val iconId = if (isNight) (mounts.nightIconSlotId ?: mounts.dayIconSlotId) else (mounts.dayIconSlotId ?: mounts.nightIconSlotId)
        iconId?.let { id ->
            NavigationBarIconConfig.loadLocalOnlyForKit(isNight)
                .firstOrNull { it.dirName == id || it.config.name == id }
                ?.let { NavigationBarIconConfig.select(it) }
        }
        val coverId = if (isNight) (mounts.nightCoverSlotId ?: mounts.dayCoverSlotId) else (mounts.dayCoverSlotId ?: mounts.nightCoverSlotId)
        coverId?.let { id ->
            CoverCollectionManager.setSelected(isNight, id)
        }
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

    /**
     * Applies ONLY the `@atmosphere` parameters (material mode, glass levels/scales,
     * surface/dialog/border alpha, wallpaper blur) for [isNight].
     */
    private fun applyParamAtmosphere(
        context: Context,
        g: SurfaceGlobalParams,
        isNight: Boolean,
        activateGlassNow: Boolean
    ) {
        if (activateGlassNow) {
            g.materialMode?.takeIf { it.isNotBlank() }?.let {
                context.putPrefString(PreferKey.exploreGlassEffectMode, it)
            }
            g.frostedLevel?.let { context.putPrefInt(PreferKey.frostedGlassLevel, it.coerceIn(0, 100)) }
            g.liquidLevel?.let { context.putPrefInt(PreferKey.liquidGlassLevel, it.coerceIn(0, 100)) }
            g.blurScale?.let { context.putPrefInt(PreferKey.exploreGlassBlurScale, it) }
            g.shellScale?.let { context.putPrefInt(PreferKey.exploreGlassShellScale, it) }
            g.tintScale?.let { context.putPrefInt(PreferKey.exploreGlassTintScale, it) }
            g.strokeScale?.let { context.putPrefInt(PreferKey.exploreGlassStrokeScale, it) }
            putOrRemovePref(context, PreferKey.glassStrokeColor, g.strokeColor)
            g.presetMatched?.let { context.putPrefBoolean(PreferKey.glassPresetMatched, it) }
            g.hardwareBlur?.let { context.putPrefBoolean(PreferKey.exploreGlassHardwareBlur, it) }
            g.animatedSolid?.let { context.putPrefBoolean(PreferKey.exploreGlassAnimatedSolid, it) }
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
        val blur = if (isNight) (g.wallpaperBlurNight ?: g.wallpaperBlurDay) else (g.wallpaperBlurDay ?: g.wallpaperBlurNight)
        blur?.let {
            context.putPrefInt(
                if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
                it.coerceIn(0, 25)
            )
        }
    }

    /**
     * Applies ONLY the global single-track `@geometry` parameters (`cornerBaseScale`,
     * `cardShadow`, `cardBackgroundBlur`, `uiCornerSearchFollow`, `uiCornerReplyFollow`,
     * `surfaceScopes`, `densityScale`) across both day/night keys so geometry never jumps on Day/Night switch.
     */
    private fun applyParamGeometryGlobal(
        context: Context,
        g: SurfaceGlobalParams,
        scopes: SurfaceScopesParams?,
        densityScale: Float?
    ) {
        listOf(false, true).forEach { isNight ->
            g.cornerBaseScale?.let {
                val formatted = String.format(Locale.US, "%.2f", it.coerceIn(0f, 3f)).trimEnd('0').trimEnd('.')
                context.putPrefString(ThemeRuntimeKeys.uiCornerScale(isNight), formatted)
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
        if (scopes != null) {
            applySurfaceScopes(context, scopes)
        }
        densityScale?.let { scale ->
            context.putPrefString(
                PreferKey.uiDensityScale,
                String.format(Locale.US, "%.2f", scale.coerceIn(0.8f, 1.3f))
            )
        }
    }

    private fun applySurfaceGlobal(context: Context, g: SurfaceGlobalParams) {
        applyParamAtmosphere(context, g, isNight = false, activateGlassNow = true)
        applyParamAtmosphere(context, g, isNight = true, activateGlassNow = false)
        applyParamGeometryGlobal(context, g, scopes = null, densityScale = null)
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
