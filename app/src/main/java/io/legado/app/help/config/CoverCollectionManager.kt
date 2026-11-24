package io.legado.app.help.config

import android.content.Context
import android.net.Uri
import io.legado.app.R
import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.pkg.CoverSlotApplyTarget
import io.legado.app.help.config.pkg.PackageIoKernel
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object CoverCollectionManager {

    const val MODE_RANDOM = "random"
    const val MODE_SEQUENCE = "sequence"
    const val MODE_MIXED = "mixed"
    const val MODE_FALLBACK_ONLY = "fallback_only"
    const val TARGET_GROUPS = CoverSlotApplyTarget.GROUPS
    const val TARGET_BOOKS = CoverSlotApplyTarget.BOOKS
    const val TARGET_BOTH = CoverSlotApplyTarget.BOTH
    private const val indexFileName = "collections.json"
    private const val packageFileName = "collection.json"
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp")
    @Volatile
    private var dayCache: List<Collection>? = null
    @Volatile
    private var nightCache: List<Collection>? = null
    private val revisionCounter = AtomicLong(0L)
    private val assignmentCache = hashMapOf<String, MutableList<Assignment>>()
    private val assignmentIoExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cover-assignment-io").apply { isDaemon = true }
        }
    private val assignmentGenerations = hashMapOf<String, Long>()
    private val pendingAssignmentWrites = hashMapOf<String, ScheduledFuture<*>>()
    private val pendingAssignmentSnapshots = hashMapOf<String, Pair<Long, List<Assignment>>>()
    private const val ASSIGNMENT_SAVE_DEBOUNCE_MS = 200L

    val rootDir: File
        get() = appCtx.externalFiles.getFile("coverCollections")

    private val tempDir: File
        get() = rootDir.getFile("temp").apply { mkdirs() }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply { mkdirs() }

    fun isMixedMode(): Boolean {
        return getMode(AppConfig.isNightTheme) == MODE_MIXED
    }

    fun getMode(isNight: Boolean = AppConfig.isNightTheme): String {
        val raw = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionModeNight else PreferKey.coverCollectionModeDay,
            MODE_RANDOM
        )
        return when (raw?.trim()?.lowercase()) {
            MODE_SEQUENCE -> MODE_SEQUENCE
            MODE_MIXED -> MODE_MIXED
            MODE_FALLBACK_ONLY, "fallback" -> MODE_FALLBACK_ONLY
            else -> MODE_RANDOM
        }
    }

    fun getTarget(isNight: Boolean = AppConfig.isNightTheme): String {
        val raw = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionTargetNight else PreferKey.coverCollectionTargetDay,
            TARGET_BOOKS
        )
        return CoverSlotApplyTarget.normalize(raw, defaultValue = TARGET_BOOKS)
    }

    fun bumpRevision() {
        revisionCounter.incrementAndGet()
    }

    @Synchronized
    fun invalidateCache() {
        dayCache = null
        nightCache = null
        assignmentCache.clear()
        pendingAssignmentWrites.values.forEach { it.cancel(false) }
        pendingAssignmentWrites.clear()
        pendingAssignmentSnapshots.clear()
        assignmentGenerations.keys.forEach { id ->
            assignmentGenerations[id] = (assignmentGenerations[id] ?: 0L) + 1L
        }
        revisionCounter.incrementAndGet()
    }

    fun selectionKey(): String {
        val isNight = AppConfig.isNightTheme
        val collectionId = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        ).orEmpty()
        val mode = getMode(isNight)
        val target = getTarget(isNight)
        val defaultCover = appCtx.getPrefString(
            if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
        ).orEmpty()
        val groupCover = appCtx.getPrefString(
            if (isNight) PreferKey.defaultGroupCoverDark else PreferKey.defaultGroupCover
        ).orEmpty()
        return "$isNight:$collectionId:$mode:$target:$defaultCover:$groupCover:${revisionCounter.get()}"
    }

    suspend fun load(isNight: Boolean): List<Collection> = withContext(IO) {
        loadIndex(isNight).sortedByDescending { it.updatedAt }
    }

    suspend fun loadEntries(isNight: Boolean, containerId: String? = null, scope: String? = null): List<Entry> = withContext(IO) {
        val local = loadIndex(isNight)
            .map { Entry(it, Source.LOCAL, collectionDir(it)) }
            .associateBy { it.dirName }
        val remote = loadRemoteOrCache(isNight, containerId, scope).associateBy { it.dirName }
        val keys = local.keys + remote.keys
        keys.mapNotNull { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )
                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> null
            }
        }.sortedWith(
            compareBy<Entry> { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.collection.updatedAt }
                .thenBy { it.collection.name }
                .thenBy { it.dirName }
        )
    }

    suspend fun get(isNight: Boolean, id: String?): Collection? = withContext(IO) {
        if (id.isNullOrBlank()) return@withContext null
        loadIndex(isNight).firstOrNull { it.id == id }
    }

    suspend fun selectedEntry(isNight: Boolean): Entry? = withContext(IO) {
        val key = if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        val id = appCtx.getPrefString(key)?.takeIf { it.isNotBlank() } ?: return@withContext null
        val collection = loadIndex(isNight).firstOrNull { it.id == id } ?: return@withContext null
        Entry(collection, Source.LOCAL, collectionDir(collection))
    }

    suspend fun localEntryForKit(isNight: Boolean, refId: String, name: String): Entry? = withContext(IO) {
        val collection = loadIndex(isNight).firstOrNull {
            it.id == refId || it.dirName == refId || it.name == name
        } ?: return@withContext null
        Entry(collection, Source.LOCAL, collectionDir(collection))
    }

    fun cachedName(isNight: Boolean, id: String?): String? {
        if (id.isNullOrBlank()) return null
        val cache = if (isNight) nightCache else dayCache
        return cache?.firstOrNull { it.id == id }?.name
    }

    suspend fun create(name: String, isNight: Boolean): Collection = withContext(IO) {
        val cleanName = name.trim().ifBlank { if (isNight) "Night collection" else "Day collection" }
        val collection = Collection(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            dirName = uniqueDirName(isNight, cleanName.normalizeFileName().ifBlank { System.currentTimeMillis().toString() }),
            isNight = isNight,
            updatedAt = System.currentTimeMillis()
        )
        collectionDir(collection).mkdirs()
        saveIndex(isNight, loadIndex(isNight) + collection)
        collection
    }

    suspend fun importZip(context: Context, zipFile: File, isNight: Boolean): Collection = withContext(IO) {
        importZipInternal(zipFile, isNight, overwrite = false, remoteUpdatedAt = 0L).collection
    }

    suspend fun importPackage(context: Context, file: File, isNight: Boolean): Collection = withContext(IO) {
        val zipFile = RedAssetPackage.zipPayload(file, tempDir) ?: return@withContext importZipInternal(
            file,
            isNight,
            overwrite = false,
            remoteUpdatedAt = 0L
        ).collection
        try {
            when (RedAssetPackage.classifyZip(zipFile)) {
                RedAssetPackage.Kind.CoverCollection -> importRedCoverCollection(zipFile, file.nameWithoutExtension, isNight)
                RedAssetPackage.Kind.NavigationBar,
                RedAssetPackage.Kind.ThemeZip -> throw IllegalArgumentException(appCtx.getString(R.string.wrong_format))
                RedAssetPackage.Kind.Unknown -> importZipInternal(zipFile, isNight, overwrite = false, remoteUpdatedAt = 0L).collection
            }
        } finally {
            zipFile.delete()
        }
    }

    suspend fun addImages(context: Context, collection: Collection, uris: List<Uri>): Collection = withContext(IO) {
        val dir = collectionDir(collection).apply { mkdirs() }
        val added = arrayListOf<String>()
        uris.forEachIndexed { index, uri ->
            val ext = resolveExtension(context, uri)
            val file = uniqueFile(dir, "${System.currentTimeMillis()}_${index}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            if (file.exists() && file.length() > 0L) {
                added.add(file.absolutePath)
            } else {
                file.delete()
            }
        }
        update(collection.copy(
            images = (collection.images + added).distinct(),
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun deleteImage(collection: Collection, imagePath: String): Collection = withContext(IO) {
        if (imagePath !in collection.images) return@withContext collection
        val collectionDir = collectionDir(collection)
        val nextImages = collection.images.filterNot { it == imagePath }
        val imageFile = File(imagePath)
        runCatching {
            val imageCanonical = imageFile.canonicalFile
            val collectionCanonical = collectionDir.canonicalFile
            if (
                imageCanonical.isFile &&
                imageCanonical.path.startsWith(collectionCanonical.path + File.separator)
            ) {
                imageCanonical.delete()
            }
        }
        clearAssignments(collection.id)
        update(collection.copy(
            images = nextImages,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun rename(collection: Collection, name: String): Collection = withContext(IO) {
        update(collection.copy(
            name = name.trim().ifBlank { collection.name },
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun update(collection: Collection): Collection = withContext(IO) {
        val list = loadIndex(collection.isNight).toMutableList()
        val index = list.indexOfFirst { it.id == collection.id }
        if (index >= 0) {
            list[index] = collection
        } else {
            list.add(collection)
        }
        saveIndex(collection.isNight, list)
        collection
    }

    suspend fun delete(collection: Collection) = withContext(IO) {
        deleteLocal(Entry(collection, Source.LOCAL, collectionDir(collection)))
    }

    suspend fun deleteLocal(entry: Entry) = withContext(IO) {
        val collection = entry.collection
        saveIndex(collection.isNight, loadIndex(collection.isNight).filterNot {
            it.id == collection.id || it.dirName == entry.dirName
        })
        FileUtils.delete(entry.localDir ?: collectionDir(collection), deleteRootDir = true)
        clearAssignments(collection.id)
        clearSelectedIfNeeded(collection)
    }

    suspend fun upload(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        AppCloudStorage.uploadCoverCollectionPackage(entry.collection.isNight, entry.dirName, exportZip(entry), containerId, scope)
    }

    suspend fun download(entry: Entry, containerId: String? = null, scope: String? = null): Entry = withContext(IO) {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppCloudStorage.downloadCoverCollectionPackage(entry.collection.isNight, entry.dirName, zipFile, containerId, scope)
        importZipInternal(zipFile, entry.collection.isNight, overwrite = true, remoteUpdatedAt = entry.remoteUpdatedAt)
            .copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    suspend fun deleteRemote(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        AppCloudStorage.deleteCoverCollectionPackage(entry.collection.isNight, entry.dirName, containerId, scope)
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val packageDir = tempDir.getFile("export_${localEntry.dirName}_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val imagesDir = packageDir.getFile("images").apply { mkdirs() }
        val packagedImages = localEntry.collection.images.mapNotNull { path ->
            val source = File(path)
            if (!source.isFile) return@mapNotNull null
            val target = uniqueFile(imagesDir, source.name.normalizeFileName().ifBlank { "image_${System.currentTimeMillis()}.${source.extension}" })
            source.copyTo(target, overwrite = true)
            "images/${target.name}"
        }
        if (packagedImages.isEmpty()) {
            FileUtils.delete(packageDir, deleteRootDir = true)
            throw IllegalArgumentException(appCtx.getString(R.string.cover_collection_no_images_to_export))
        }
        File(packageDir, packageFileName).writeText(GSON.toJson(localEntry.collection.copy(images = packagedImages)))
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        if (!ZipUtils.zipFile(packageDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
            FileUtils.delete(packageDir, deleteRootDir = true)
            throw IllegalStateException(appCtx.getString(R.string.cover_collection_export_failed_simple))
        }
        FileUtils.delete(packageDir, deleteRootDir = true)
        zipFile
    }

    fun selectedCollectionCover(book: Book): String? {
        val hasOriginalCover = book.getDisplayCover().isRealCoverPath()
        return selectedCollectionCover(
            bookKey = book.bookUrl.ifBlank { "${book.origin}|${book.name}|${book.author}" },
            hasOriginalCover = hasOriginalCover
        )
    }

    fun selectedCollectionCover(searchBook: SearchBook): String? {
        return selectedCollectionCover(
            bookKey = searchBook.bookUrl.ifBlank { "${searchBook.origin}|${searchBook.name}|${searchBook.author}" },
            hasOriginalCover = searchBook.coverUrl.isRealCoverPath()
        )
    }

    fun selectedCollectionCover(bookKey: String, coverPath: String? = null): String? {
        return selectedCollectionCover(
            bookKey = bookKey,
            hasOriginalCover = coverPath.isRealCoverPath()
        )
    }

    fun selectedGroupCollectionCover(
        groupId: Long,
        groupName: String,
        hasOriginalCover: String? = null
    ): String? {
        val isNight = AppConfig.isNightTheme
        val target = getTarget(isNight)
        if (target != TARGET_GROUPS && target != TARGET_BOTH) {
            return null
        }
        val collectionId = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        ) ?: return null
        val collection = loadIndex(isNight).firstOrNull { it.id == collectionId } ?: return null
        if (collection.images.isEmpty()) return null
        val mode = getMode(isNight)
        if (mode == MODE_FALLBACK_ONLY) {
            return null
        }
        if (mode == MODE_MIXED && hasOriginalCover.isRealCoverPath()) {
            return null
        }
        matchGroupCoverByName(collection.images, groupName)?.let { return it }
        val assignmentKey = "group:$groupId"
        val path = when (mode) {
            MODE_SEQUENCE -> assignSequential(collection, assignmentKey)
            else -> collection.images[stableImageIndex(assignmentKey, collection.images.size)]
        }
        return path.takeIf { File(it).exists() }
    }

    internal fun matchGroupCoverByName(images: List<String>, groupName: String): String? {
        val trimmedGroupName = groupName.trim()
        if (trimmedGroupName.isEmpty()) return null
        return images.firstOrNull { imagePath ->
            val stem = imagePath
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
                .trim()
            stem.equals(trimmedGroupName, ignoreCase = true)
        }
    }

    fun String?.isRealCoverPath(): Boolean {
        val value = this?.trim().orEmpty()
        if (value.isBlank() || value.equals("use_default_cover", ignoreCase = true)) {
            return false
        }
        val lowerValue = value.lowercase()
        return when {
            lowerValue.startsWith("http://") ||
                lowerValue.startsWith("https://") ||
                lowerValue.startsWith("content://") ||
                lowerValue.startsWith("android.resource://") ||
                lowerValue.startsWith("file:///android_asset/") -> true
            lowerValue.startsWith("file://") -> runCatching {
                File(Uri.parse(value).path.orEmpty()).isFile
            }.getOrDefault(false)
            File(value).isAbsolute -> File(value).isFile
            else -> true
        }
    }

    private fun selectedCollectionCover(bookKey: String, hasOriginalCover: Boolean): String? {
        val isNight = AppConfig.isNightTheme
        val target = getTarget(isNight)
        if (target != TARGET_BOOKS && target != TARGET_BOTH) {
            return null
        }
        val collectionId = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        ) ?: return null
        val collection = loadIndex(isNight).firstOrNull { it.id == collectionId } ?: return null
        if (collection.images.isEmpty()) return null
        val mode = getMode(isNight)
        if (mode == MODE_FALLBACK_ONLY) {
            return null
        }
        if (mode == MODE_MIXED && hasOriginalCover) {
            return null
        }
        val path = when (mode) {
            MODE_SEQUENCE -> assignSequential(collection, bookKey)
            else -> collection.images[stableImageIndex(bookKey, collection.images.size)]
        }
        return path.takeIf { File(it).exists() }
    }

    fun setSelected(isNight: Boolean, collectionId: String?) {
        val key = if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        appCtx.putPrefString(key, collectionId.orEmpty())
        bumpRevision()
    }

    @Synchronized
    fun upsertBridgeCollectionSync(
        collectionId: String,
        name: String,
        dirName: String,
        isNight: Boolean,
        mode: String,
        imagePaths: List<String>,
        updatedAt: Long
    ) {
        val cleanImages = imagePaths.filter { it.isNotBlank() && File(it).isFile }.distinct()
        val list = loadIndex(isNight).toMutableList()
        val index = list.indexOfFirst { it.id == collectionId }
        val existing = if (index >= 0) list[index] else null
        val updated = Collection(
            id = collectionId,
            name = name.ifBlank { collectionId },
            dirName = dirName.ifBlank { collectionId },
            isNight = isNight,
            mode = mode,
            images = cleanImages,
            updatedAt = updatedAt
        )
        if (index >= 0) {
            list[index] = updated
        } else {
            list.add(updated)
        }
        if (existing != null && existing.images != cleanImages) {
            clearAssignments(collectionId)
        }
        saveIndex(isNight, list)
        bumpRevision()
    }

    fun collectionDir(collection: Collection): File {
        return typeDir(collection.isNight).getFile(collection.dirName)
    }

    private fun importZipInternal(
        zipFile: File,
        fallbackIsNight: Boolean,
        overwrite: Boolean,
        remoteUpdatedAt: Long
    ): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
            val packageRoot = packageFile?.parentFile ?: unzipDir
            val rawCollection = packageFile
                ?.let { GSON.fromJsonObject<Collection>(it.readText()).getOrNull() }
                ?: Collection(
                    name = zipFile.nameWithoutExtension.ifBlank { "Imported collection" },
                    isNight = fallbackIsNight,
                    updatedAt = System.currentTimeMillis()
                )
            val isNight = rawCollection.isNight.takeIf { packageFile != null } ?: fallbackIsNight
            val baseName = rawCollection.name.ifBlank { zipFile.nameWithoutExtension.ifBlank { "Imported collection" } }
            val baseDirName = rawCollection.dirName.ifBlank { baseName.normalizeFileName() }
                .ifBlank { "collection_${System.currentTimeMillis()}" }
            val dirName = if (overwrite) baseDirName else uniqueDirName(isNight, baseDirName)
            val targetDir = typeDir(isNight).getFile(dirName)
            if (targetDir.exists()) {
                if (overwrite) FileUtils.delete(targetDir, deleteRootDir = true) else error("Collection exists")
            }
            targetDir.mkdirs()
            val imagesDir = targetDir.getFile("images").apply { mkdirs() }
            val candidateFiles = if (packageFile != null) {
                rawCollection.images.mapNotNull { value ->
                    val file = File(value)
                    if (file.isAbsolute) file else File(packageRoot, value)
                }
            } else {
                unzipDir.walkTopDown().filter { it.isFile && it.extension.lowercase() in imageExtensions }.toList()
            }
            val images = candidateFiles.mapNotNull { source ->
                if (!source.isFile || source.extension.lowercase() !in imageExtensions) return@mapNotNull null
                val target = uniqueFile(imagesDir, source.name.normalizeFileName().ifBlank { "image_${System.currentTimeMillis()}.${source.extension}" })
                source.copyTo(target, overwrite = true)
                target.absolutePath
            }
            if (images.isEmpty()) {
                FileUtils.delete(targetDir, deleteRootDir = true)
                throw IllegalArgumentException(appCtx.getString(R.string.cover_collection_zip_no_images))
            }
            val collection = rawCollection.copy(
                id = rawCollection.id.ifBlank { UUID.randomUUID().toString() },
                name = baseName,
                dirName = dirName,
                isNight = isNight,
                images = images,
                updatedAt = if (remoteUpdatedAt > 0L) remoteUpdatedAt else System.currentTimeMillis()
            )
            saveIndex(isNight, loadIndex(isNight).filterNot { it.dirName == dirName || it.id == collection.id } + collection)
            Entry(collection, Source.LOCAL, targetDir, remoteUpdatedAt)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun importRedCoverCollection(zipFile: File, fallbackName: String, isNight: Boolean): Collection {
        val unzipDir = tempDir.getFile("red_cover_import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val packageDir = tempDir.getFile("red_cover_package_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val imagesDir = packageDir.getFile("images").apply { mkdirs() }
        val packageZip = tempDir.getFile("red_cover_package_${UUID.randomUUID()}.zip")
        return try {
            RedAssetPackage.unzipSecure(zipFile, unzipDir)
            unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }?.let {
                return importZipInternal(zipFile, isNight, overwrite = false, remoteUpdatedAt = 0L).collection
            }
            val images = unzipDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in imageExtensions }
                .sortedBy { it.name }
                .mapIndexedNotNull { index, source ->
                    val ext = source.extension.lowercase().ifBlank { "jpg" }
                    val target = File(imagesDir, "cover_${index + 1}.$ext")
                    source.copyTo(target, overwrite = true)
                    "images/${target.name}"
                }
                .toList()
            if (images.isEmpty()) {
                throw IllegalArgumentException(appCtx.getString(R.string.cover_collection_zip_no_images))
            }
            val name = fallbackName.ifBlank { "RED Cover Gallery" }
            val collection = Collection(
                id = UUID.randomUUID().toString(),
                name = name,
                dirName = name.normalizeFileName(),
                isNight = isNight,
                images = images,
                updatedAt = System.currentTimeMillis()
            )
            File(packageDir, packageFileName).writeText(GSON.toJson(collection))
            if (!ZipUtils.zipFile(packageDir, packageZip) || !packageZip.isFile || packageZip.length() <= 0L) {
                throw IllegalArgumentException(appCtx.getString(R.string.wrong_format))
            }
            importZipInternal(packageZip, isNight, overwrite = false, remoteUpdatedAt = 0L).collection
        } finally {
            packageZip.delete()
            FileUtils.delete(unzipDir, deleteRootDir = true)
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    private suspend fun loadRemote(isNight: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        return AppCloudStorage.listCoverCollectionPackages(isNight, containerId, scope).map { file ->
            val dirName = file.displayName.trimEnd('/').removeSuffix(".zip")
            Entry(
                collection = Collection(
                    id = dirName,
                    name = dirName,
                    dirName = dirName,
                    isNight = isNight,
                    updatedAt = file.lastModify
                ),
                source = Source.REMOTE,
                remoteUpdatedAt = file.lastModify
            )
        }
    }

    private suspend fun loadRemoteOrCache(isNight: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        return runCatching {
            loadRemote(isNight, containerId, scope).also { writeRemoteCache(isNight, it, containerId) }
        }.getOrElse {
            readRemoteCache(isNight, containerId)
        }
    }

    private fun remoteCacheFile(isNight: Boolean, containerId: String? = null): File {
        val mode = if (isNight) "night" else "day"
        val suffix = containerId?.takeIf { it.isNotBlank() }?.normalizeFileName()?.let { "_$it" }.orEmpty()
        return remoteCacheDir.getFile("$mode$suffix.json")
    }

    private fun readRemoteCache(isNight: Boolean, containerId: String? = null): List<Entry> {
        val file = remoteCacheFile(isNight, containerId)
        return PackageIoKernel.readRemoteCache(file) { text ->
            GSON.fromJsonArray<RemoteCache>(text).getOrDefault(emptyList())
                .filter { it.isNight == isNight }
                .map { cache ->
                    Entry(
                        collection = Collection(
                            id = cache.dirName,
                            name = cache.name.ifBlank { cache.dirName },
                            dirName = cache.dirName,
                            isNight = cache.isNight,
                            updatedAt = cache.updatedAt
                        ),
                        source = Source.REMOTE,
                        remoteUpdatedAt = cache.updatedAt
                    )
                }
        }
    }

    private fun writeRemoteCache(isNight: Boolean, entries: List<Entry>, containerId: String? = null) {
        val json = GSON.toJson(entries.map {
            RemoteCache(
                name = it.collection.name,
                dirName = it.dirName,
                isNight = it.collection.isNight,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.collection.updatedAt
            )
        })
        PackageIoKernel.writeRemoteCache(remoteCacheFile(isNight, containerId), json)
    }

    private fun typeDir(isNight: Boolean): File {
        return rootDir.getFile(if (isNight) "night" else "day").apply { mkdirs() }
    }

    private fun indexFile(isNight: Boolean): File {
        return typeDir(isNight).getFile(indexFileName)
    }

    private fun loadIndex(isNight: Boolean): List<Collection> {
        (if (isNight) nightCache else dayCache)?.let { return it }
        val text = PackageIoKernel.readTextWithRecovery(indexFile(isNight), 2L * 1024L * 1024L)
            ?: return emptyList()
        val list = GSON.fromJsonArray<Collection>(text).getOrDefault(emptyList())
            .map { collection ->
                collection.copy(images = collection.images.filter { File(it).exists() })
            }
        if (isNight) {
            nightCache = list
        } else {
            dayCache = list
        }
        return list
    }

    private fun saveIndex(isNight: Boolean, list: List<Collection>) {
        val file = indexFile(isNight)
        PackageIoKernel.writeTextAtomic(file, GSON.toJson(list))
        if (isNight) {
            nightCache = list
        } else {
            dayCache = list
        }
    }

    private fun uniqueDirName(isNight: Boolean, preferred: String): String {
        val clean = preferred.normalizeFileName().ifBlank { "collection_${System.currentTimeMillis()}" }
        val existing = loadIndex(isNight).mapTo(hashSetOf()) { it.dirName }
        if (clean !in existing && !typeDir(isNight).getFile(clean).exists()) return clean
        var index = 1
        while (true) {
            val candidate = "${clean}_$index"
            if (candidate !in existing && !typeDir(isNight).getFile(candidate).exists()) return candidate
            index++
        }
    }

    private fun uniqueFile(dir: File, preferredName: String): File {
        val clean = preferredName.normalizeFileName().ifBlank { "image_${System.currentTimeMillis()}.jpg" }
        val base = clean.substringBeforeLast('.', clean)
        val suffix = clean.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var file = File(dir, clean)
        var index = 1
        while (file.exists()) {
            file = File(dir, "${base}_$index$suffix")
            index++
        }
        return file
    }

    private fun clearSelectedIfNeeded(collection: Collection) {
        val key = if (collection.isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        if (appCtx.getPrefString(key) == collection.id) {
            appCtx.putPrefString(key, "")
        }
    }

    private fun assignmentsFile(collectionId: String): File {
        return rootDir.getFile("assignments").apply { mkdirs() }.getFile("$collectionId.json")
    }

    private fun stableImageIndex(bookKey: String, imageCount: Int): Int {
        return (bookKey.hashCode() and Int.MAX_VALUE) % imageCount
    }

    @Synchronized
    private fun assignSequential(collection: Collection, bookUrl: String): String {
        val assignments = assignmentCache.getOrPut(collection.id) {
            readAssignments(collection.id).toMutableList()
        }
        assignments.firstOrNull { it.bookUrl == bookUrl }?.let {
            return collection.images.getOrNull(it.index % collection.images.size) ?: collection.images.first()
        }
        val index = assignments.size % collection.images.size
        assignments.add(Assignment(bookUrl, index))
        scheduleSaveAssignmentsLocked(collection.id, assignments.toList())
        return collection.images[index]
    }

    private fun readAssignments(collectionId: String): List<Assignment> {
        val file = assignmentsFile(collectionId)
        return GSON.fromJsonArray<Assignment>(
            file.takeIf { it.exists() }?.readText()
        ).getOrDefault(emptyList())
    }

    private fun scheduleSaveAssignmentsLocked(collectionId: String, snapshot: List<Assignment>) {
        pendingAssignmentWrites.remove(collectionId)?.cancel(false)
        val generation = assignmentGenerations[collectionId] ?: 0L
        pendingAssignmentSnapshots[collectionId] = generation to snapshot
        val future = assignmentIoExecutor.schedule({
            writeAssignmentsIfGenerationMatches(collectionId, generation, snapshot)
        }, ASSIGNMENT_SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        pendingAssignmentWrites[collectionId] = future
    }

    private fun writeAssignmentsIfGenerationMatches(
        collectionId: String,
        expectedGeneration: Long,
        snapshot: List<Assignment>
    ) {
        val shouldWrite = synchronized(this) {
            val currentGen = assignmentGenerations[collectionId] ?: 0L
            if (currentGen != expectedGeneration) {
                false
            } else {
                if (pendingAssignmentSnapshots[collectionId]?.first == expectedGeneration) {
                    pendingAssignmentWrites.remove(collectionId)
                    pendingAssignmentSnapshots.remove(collectionId)
                }
                true
            }
        }
        if (!shouldWrite) return
        val target = assignmentsFile(collectionId)
        val tmp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        runCatching {
            tmp.writeText(GSON.toJson(snapshot))
            val stillValid = synchronized(this) {
                (assignmentGenerations[collectionId] ?: 0L) == expectedGeneration
            }
            if (!stillValid) {
                tmp.delete()
                return
            }
            if (!tmp.renameTo(target)) {
                val validBeforeFallback = synchronized(this) {
                    (assignmentGenerations[collectionId] ?: 0L) == expectedGeneration
                }
                if (validBeforeFallback) {
                    if (target.exists()) {
                        target.delete()
                    }
                    if (!tmp.renameTo(target)) {
                        target.writeText(tmp.readText())
                    }
                }
                tmp.delete()
            }
        }.onFailure {
            tmp.delete()
        }
    }

    @Synchronized
    private fun clearAssignments(collectionId: String) {
        val nextGen = (assignmentGenerations[collectionId] ?: 0L) + 1L
        assignmentGenerations[collectionId] = nextGen
        pendingAssignmentWrites.remove(collectionId)?.cancel(false)
        pendingAssignmentSnapshots.remove(collectionId)
        assignmentCache.remove(collectionId)
        val file = assignmentsFile(collectionId)
        file.delete()
        assignmentIoExecutor.submit {
            val shouldDelete = synchronized(this) {
                (assignmentGenerations[collectionId] ?: 0L) == nextGen &&
                    !assignmentCache.containsKey(collectionId)
            }
            if (shouldDelete) {
                file.delete()
            }
        }
    }

    internal fun flushPendingAssignmentWritesForTest() {
        val toFlush = synchronized(this) {
            pendingAssignmentWrites.values.forEach { it.cancel(false) }
            pendingAssignmentWrites.clear()
            val items = pendingAssignmentSnapshots.entries.map { (id, pair) ->
                Triple(id, pair.first, pair.second)
            }
            pendingAssignmentSnapshots.clear()
            items
        }
        assignmentIoExecutor.submit {
            toFlush.forEach { (id, gen, snap) ->
                writeAssignmentsIfGenerationMatches(id, gen, snap)
            }
        }.get()
    }

    private fun resolveExtension(context: Context, uri: Uri): String {
        val last = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (last in imageExtensions) return last!!
        val type = context.contentResolver.getType(uri).orEmpty()
        return when {
            type.contains("png", true) -> "png"
            type.contains("webp", true) -> "webp"
            type.contains("bmp", true) -> "bmp"
            else -> "jpg"
        }
    }

    data class Entry(
        val collection: Collection,
        val source: Source,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    ) {
        val dirName: String get() = collection.dirName
    }

    enum class Source {
        LOCAL,
        REMOTE,
        BOTH
    }

    @Keep
    data class Collection(
        val id: String = "",
        val name: String = "",
        val dirName: String = "",
        val isNight: Boolean = false,
        val mode: String = MODE_RANDOM,
        val images: List<String> = emptyList(),
        val updatedAt: Long = 0L
    )

    @Keep
    private data class RemoteCache(
        val name: String = "",
        val dirName: String = "",
        val isNight: Boolean = false,
        val updatedAt: Long = 0L
    )

    @Keep
    private data class Assignment(
        val bookUrl: String = "",
        val index: Int = 0
    )
}
