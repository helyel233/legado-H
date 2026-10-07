package io.legado.app.help

import android.net.Uri
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.CacheCloudIndex
import io.legado.app.help.book.CacheCloudIndexStore
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.cloud.CloudStorageBackend
import io.legado.app.lib.cloud.CloudStorageFile
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3BackupIndexItem
import io.legado.app.lib.cloud.S3CloudStorageBackend
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3ContainerManager
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.lib.cloud.WebDavCloudStorageBackend
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isContentScheme
import io.legado.app.help.storage.BackupArchiveExtractor
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx
import java.io.File
import java.util.UUID

object AppCloudStorage {

    private const val BOOK_PROGRESS_DIR = "bookProgress/"
    private const val EXPORTS_DIR = "books/"
    private const val BG_DIR = "background/"
    private const val THEMES_DIR = "themes/"
    private const val NAVIGATION_BARS_DIR = "navigationBars/"
    private const val TOP_BARS_DIR = "topBars/"
    private const val COVER_COLLECTIONS_DIR = "coverCollections/"
    private const val BUBBLES_DIR = "bubbles/"
    private const val COVERS_DIR = "covers/"

    private val webDavBackend = WebDavCloudStorageBackend()
    private val s3Backend = S3CloudStorageBackend()
    private var backend: CloudStorageBackend = webDavBackend
    val type: CloudStorageType
        get() = CloudStorageType.from(appCtx.getPrefString(PreferKey.cloudStorageType))

    val isOk: Boolean
        get() = selectBackend().isOk

    val isJianGuoYun: Boolean
        get() = selectBackend().isJianGuoYun

    val defaultBookWebDav: RemoteBookWebDav?
        get() = webDavBackend.defaultBookWebDav

    private fun selectBackend(): CloudStorageBackend {
        backend = when (type) {
            CloudStorageType.WEBDAV -> webDavBackend
            CloudStorageType.S3 -> s3Backend
        }
        return backend
    }

    suspend fun upConfig() {
        if (type == CloudStorageType.S3) S3ContainerManager.containers()
        selectBackend().upConfig()
    }

    suspend fun getBackupNames(): ArrayList<String> {
        ensureNetwork()
        val files = backupFiles()
            // 资源包不作为独立恢复项出现，云端恢复时单独询问
            .filter { !it.displayName.startsWith("backup_assets") }
            .sortedWith { o1, o2 -> AlphanumComparator.compare(o1.displayName, o2.displayName) }
            .reversed()
        return ArrayList(files.map { it.displayName })
    }

    suspend fun restore(name: String, assetsFileNames: List<String> = emptyList()) {
        // 主包与资源包的下载解压在 Backup.mutex 锁内执行（prepare），
        // 避免自动备份的删建把已解压内容清掉；主包下载/解压失败即中断恢复。
        Restore.restoreLocked(Backup.backupPath) {
            val location = findBackupLocation(name)
            if (location != null && type == CloudStorageType.S3) {
                s3Backend.downloadTo(location.containerId, name, File(Backup.zipFilePath), true)
            } else {
                storage(S3ContainerScope.MAIN_BACKUP).downloadTo(name, File(Backup.zipFilePath), true)
            }
            BackupArchiveExtractor.extract(File(Backup.zipFilePath), File(Backup.backupPath))
            // 资源包（字体/背景图/头像/分组封面）与主包解压到同一目录，恢复逻辑按条目前缀统一处理；
            // 单个资源包失败不阻断主包恢复。
            assetsFileNames.forEach { assetsName ->
                val assetsZip = File(appCtx.cacheDir, "restore_assets_${UUID.randomUUID()}.zip")
                try {
                    storage(S3ContainerScope.MAIN_BACKUP).downloadTo(assetsName, assetsZip, true)
                    // 追加解压：不清空目标目录，否则会把主包已恢复的书架/书源等全部删掉
                    BackupArchiveExtractor.extract(assetsZip, File(Backup.backupPath), resetDestination = false)
                } catch (e: Exception) {
                    io.legado.app.constant.AppLog.put("恢复资源包出错 $assetsName\n${e.localizedMessage}", e)
                } finally {
                    assetsZip.delete()
                }
            }
        }
    }

    /**
     * 云端资源包文件名列表（backup_assets 前缀），供云端恢复时询问用户是否一并恢复。
     * 列取失败返回空列表，不应因此中断恢复流程。
     */
    suspend fun listAssetsBackupNames(): List<String> {
        return runCatching {
            storage(S3ContainerScope.MAIN_BACKUP).listFiles("")
                .map { it.displayName }
                .filter { it.startsWith("backup_assets") }
        }.getOrDefault(emptyList())
    }

    suspend fun hasBackup(name: String): Boolean {
        return if (type == CloudStorageType.S3) {
            findBackupLocation(name) != null
        } else {
            selectBackend().exists(name)
        }
    }

    suspend fun lastBackup(): Result<CloudStorageFile?> {
        return kotlin.runCatching {
            backupFiles().maxByOrNull { it.lastModify }
        }
    }

    suspend fun backup(fileName: String) {
        ensureNetwork()
        storage(S3ContainerScope.MAIN_BACKUP).upload(fileName, Backup.zipFilePath)
        // 仅当上传目标为 WebDAV 时才清理，避免 S3 备份误删 WebDAV 上的旧备份
        if (type == CloudStorageType.WEBDAV) {
            deleteOldWebDavBackups(fileName)
        }
    }

    suspend fun backupToWebDav(fileName: String) {
        ensureNetwork()
        webDavBackend.upConfig()
        webDavBackend.upload(fileName, Backup.zipFilePath)
        deleteOldWebDavBackups(fileName)
    }

    /**
     * 上传「资源文件单独备份」包（字体/背景图/头像）。
     * 不做旧备份清理：资源包用稳定文件名覆盖上传，
     * 且主备份的清理逻辑会跳过 backup_assets 包。
     */
    suspend fun backupAssets(fileName: String, file: File) {
        ensureNetwork()
        storage(S3ContainerScope.MAIN_BACKUP).upload(fileName, file)
    }

    /**
     * 资源包是否仍存在于云端。哈希未变化准备跳过上传前调用；
     * 若用户手动删除了云端资源包可自愈补传。
     * 仅 WebDAV 支持廉价的存在性检查，其余类型保守返回 true。
     * 查询失败时也保守返回 true，避免网络抖动导致每次备份都重传大包。
     */
    suspend fun assetsBackupExists(fileName: String): Boolean {
        if (type != CloudStorageType.WEBDAV) return true
        return runCatching {
            webDavBackend.listFiles("")
                .any { !it.isDir && it.displayName == fileName }
        }.getOrDefault(true)
    }

    /**
     * 自动删除 WebDAV 目录中之前的备份，只保留最新备份
     */
    private suspend fun deleteOldWebDavBackups(fileName: String) {
        if (!AppConfig.webDavDeleteOldBackup) return
        if (!webDavBackend.isOk) return
        runCatching {
            webDavBackend.listFiles("")
                .filter { !it.isDir && it.displayName.startsWith("backup") }
                .filter { !it.displayName.startsWith("backup_assets", ignoreCase = true) }
                .filter { it.displayName != fileName && it.displayName.endsWith(".zip", ignoreCase = true) }
                .forEach { file ->
                    runCatching {
                        webDavBackend.delete(file.displayName)
                    }.onFailure {
                        io.legado.app.constant.AppLog.put(
                            "删除云端旧备份失败: ${file.displayName}\n${it.localizedMessage}", it
                        )
                    }
                }
        }.onFailure {
            io.legado.app.constant.AppLog.put("清理云端旧备份出错\n${it.localizedMessage}", it)
        }
    }

    fun listContainers(): List<S3Container> = S3ContainerManager.listContainers()

    fun saveContainers(containers: List<S3Container>) = S3ContainerManager.saveContainers(containers)

    fun addContainer(container: S3Container): S3Container = S3ContainerManager.addContainer(container)

    fun updateContainer(container: S3Container) = S3ContainerManager.updateContainer(container)

    fun deleteContainer(id: String) = S3ContainerManager.deleteContainer(id)

    suspend fun refreshUsage(containerId: String): S3Container = s3Backend.refreshUsage(containerId)

    fun selectContainer(scope: S3ContainerScope, id: String?) = S3ContainerManager.selectContainer(scope, id)

    fun selectContainer(scope: String, id: String?) = selectContainer(S3ContainerScope.from(scope), id)

    fun selectedContainer(scope: S3ContainerScope = S3ContainerScope.DEFAULT): S3Container? =
        S3ContainerManager.selectedContainer(scope)

    fun selectedContainer(scope: String): S3Container? = selectedContainer(S3ContainerScope.from(scope))

    fun containerDisplayLabel(container: S3Container?): String = S3ContainerManager.displayLabel(container)

    fun containerDisplayLabel(containerId: String?): String = containerDisplayLabel(S3ContainerManager.container(containerId))

    fun cacheStorageKey(): String {
        return when (type) {
            CloudStorageType.WEBDAV -> listOf(
                type.name,
                AppConfig.webDavDir.orEmpty(),
                appCtx.getPrefString(PreferKey.webDavUrl).orEmpty(),
                appCtx.getPrefString(PreferKey.webDavAccount).orEmpty()
            ).joinToString("|")
            CloudStorageType.S3 -> listOf(
                type.name,
                S3ContainerScope.CACHE.key,
                S3ContainerManager.selectedContainerId(S3ContainerScope.CACHE).orEmpty()
            ).joinToString("|")
        }
    }

    suspend fun listThemePackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getThemeTypePath(isNightTheme), scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun uploadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getThemeTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun downloadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getThemeTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun deleteThemePackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getThemeTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun listNavigationBarPackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getNavigationBarTypePath(isNightTheme), scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun uploadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getNavigationBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun downloadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getNavigationBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun deleteNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getNavigationBarTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun listTopBarPackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getTopBarTypePath(isNightTheme), scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun uploadTopBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getTopBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun downloadTopBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getTopBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun deleteTopBarPackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getTopBarTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun listCoverCollectionPackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getCoverCollectionTypePath(isNightTheme), scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope), emptyOnError = true)
    }

    suspend fun uploadCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getCoverCollectionTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope))
    }

    suspend fun downloadCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getCoverCollectionTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope))
    }

    suspend fun deleteCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getCoverCollectionTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope))
    }

    suspend fun listBubblePackages(containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(BUBBLES_DIR, scopedBackend(S3ContainerScope.BUBBLE, containerId, scope), emptyOnError = true)
    }

    suspend fun uploadBubblePackage(remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(BUBBLES_DIR, remoteDirName, zipFile, scopedBackend(S3ContainerScope.BUBBLE, containerId, scope))
    }

    suspend fun downloadBubblePackage(remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(BUBBLES_DIR, remoteDirName, zipFile, scopedBackend(S3ContainerScope.BUBBLE, containerId, scope))
    }

    suspend fun deleteBubblePackage(remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(BUBBLES_DIR, remoteDirName, scopedBackend(S3ContainerScope.BUBBLE, containerId, scope))
    }
    suspend fun uploadCachePackage(fileName: String, zipFile: File) {
        ensureNetwork()
        val target = storage(S3ContainerScope.CACHE)
        target.makeDir(EXPORTS_DIR)
        target.upload(EXPORTS_DIR + normalizeCachePackageFileName(fileName), zipFile, "application/zip")
    }

    suspend fun downloadCachePackage(fileName: String, zipFile: File) {
        ensureNetwork()
        var lastError: Throwable? = null
        cachePackageFileNameCandidates(fileName).forEach { candidate ->
            runCatching {
                storage(S3ContainerScope.CACHE).downloadTo(EXPORTS_DIR + candidate, zipFile, true)
            }.onSuccess {
                return
            }.onFailure {
                lastError = it
            }
        }
        throw lastError ?: NoStackTraceException("Cache package not found")
    }

    suspend fun deleteCachePackage(fileName: String) {
        ensureNetwork()
        storage(S3ContainerScope.CACHE).delete(EXPORTS_DIR + normalizeCachePackageFileName(fileName))
    }

    suspend fun downloadCacheIndex(): CacheCloudIndex {
        if (!NetworkUtils.isAvailable() || !storage(S3ContainerScope.CACHE).isOk) return CacheCloudIndex()
        return kotlin.runCatching {
            val bytes = storage(S3ContainerScope.CACHE).download(EXPORTS_DIR + CacheCloudIndexStore.remoteFileName())
            GSON.fromJsonObject<CacheCloudIndex>(String(bytes)).getOrNull() ?: CacheCloudIndex()
        }.getOrElse { CacheCloudIndex() }
    }

    suspend fun uploadCacheIndex(index: CacheCloudIndex) {
        ensureNetwork()
        val target = storage(S3ContainerScope.CACHE)
        target.makeDir(EXPORTS_DIR)
        target.upload(EXPORTS_DIR + CacheCloudIndexStore.remoteFileName(), GSON.toJson(index).toByteArray(), "application/json")
    }

    suspend fun upBgs(files: Array<File>) {
        val target = storage(S3ContainerScope.DEFAULT)
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        val remoteNames = runCatching { target.listFiles(BG_DIR).map { it.displayName }.toSet() }.getOrDefault(emptySet())
        files.forEach {
            if (!remoteNames.contains(it.name) && it.exists()) {
                target.upload(BG_DIR + it.name, it)
            }
        }
    }

    suspend fun downBgs(names: Collection<String>): Map<String, File> {
        val fileNames = names
            .mapNotNull { File(it).name.takeIf { name -> name.isNotBlank() } }
            .distinct()
        if (fileNames.isEmpty()) return emptyMap()
        val target = storage(S3ContainerScope.DEFAULT)
        if (!NetworkUtils.isAvailable() || !target.isOk) return emptyMap()
        val bgDir = appCtx.externalFiles.getFile("bg").apply { mkdirs() }
        val restored = linkedMapOf<String, File>()
        fileNames.forEach { fileName ->
            val localFile = bgDir.getFile(fileName)
            if (localFile.exists()) {
                restored[fileName] = localFile
                return@forEach
            }
            runCatching {
                target.downloadTo(BG_DIR + fileName, localFile, true)
                if (localFile.exists()) {
                    restored[fileName] = localFile
                }
            }.onFailure {
                localFile.delete()
                io.legado.app.constant.AppLog.put("恢复背景图片失败 $fileName\n${it.localizedMessage}", it)
            }
        }
        return restored
    }

    /**
     * 上传书架中本地存储的封面文件到 WebDAV covers 目录
     */
    suspend fun upBookCovers() {
        if (!AppConfig.webDavBackupCover) return
        val target = webDavBackend
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        val files = collectBookCoverFiles()
        if (files.isEmpty()) return
        runCatching {
            target.makeDir(COVERS_DIR)
            val remoteFiles = target.listFiles(COVERS_DIR).associate { it.displayName to it.size }
            files.forEach { file ->
                if (remoteFiles[file.name] == file.length()) return@forEach
                runCatching {
                    target.upload(COVERS_DIR + file.name, file)
                }.onFailure {
                    io.legado.app.constant.AppLog.put(
                        "上传书架封面失败: ${file.name}\n${it.localizedMessage}", it
                    )
                }
            }
        }.onFailure {
            io.legado.app.constant.AppLog.put("备份书架封面出错\n${it.localizedMessage}", it)
        }
    }

    /**
     * 从 WebDAV covers 目录下载书架封面到本地
     */
    suspend fun downBookCovers() {
        if (!AppConfig.webDavBackupCover) return
        val target = webDavBackend
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        val wanted = collectBookCoverRestoreTargets()
        if (wanted.exactNames.isEmpty() && wanted.prefixes.isEmpty()) return
        val coversDir = appCtx.externalFiles.getFile("covers").apply { mkdirs() }
        runCatching {
            target.listFiles(COVERS_DIR).filter { !it.isDir }.forEach { remote ->
                val name = remote.displayName
                val matched = wanted.exactNames.contains(name)
                        || wanted.prefixes.any { name.startsWith(it) }
                if (!matched) return@forEach
                val localFile = coversDir.getFile(name)
                if (localFile.exists()) return@forEach
                runCatching {
                    target.downloadTo(COVERS_DIR + name, localFile, true)
                }.onFailure {
                    localFile.delete()
                    io.legado.app.constant.AppLog.put(
                        "下载书架封面失败: $name\n${it.localizedMessage}", it
                    )
                }
            }
        }.onFailure {
            io.legado.app.constant.AppLog.put("恢复书架封面出错\n${it.localizedMessage}", it)
        }
        // 下载完成后改写失效的本地封面路径：书库里的 coverUrl 指向旧设备绝对路径，
        // 文件已还原到 covers/ 目录，不改写界面永远空白。
        normalizeBookCoverPaths()
    }

    private fun normalizeBookCoverPaths() {
        val coversDir = appCtx.externalFiles.getFile("covers")
        if (!coversDir.isDirectory) return
        appDb.bookDao.all.forEach { book ->
            val display = book.getDisplayCover()
            if (display.isNullOrBlank()
                || display.startsWith("http", true)
                || display.isContentScheme()
            ) return@forEach
            if (File(display).exists()) return@forEach
            val restoredFile = if (book.isLocal) {
                // 本地书封面按 md516(bookUrl) 命名，扩展名可能不同
                LocalBook.findCoverPath(book)
            } else {
                File(coversDir, File(display).name).takeIf { it.isFile }?.absolutePath
            }
            val newCover = restoredFile ?: return@forEach
            if (book.customCoverUrl == display) {
                book.customCoverUrl = newCover
            } else {
                book.coverUrl = newCover
            }
            appDb.bookDao.update(book)
        }
    }

    private fun collectBookCoverFiles(): List<File> {
        val files = linkedSetOf<File>()
        val localBookCoverPrefixes = hashSetOf<String>()
        val coversDir = appCtx.externalFiles.getFile("covers")
        appDb.bookDao.all.forEach { book ->
            val cover = book.getDisplayCover()
            if (!cover.isNullOrBlank()
                && !cover.startsWith("http", true)
                && !cover.isContentScheme()
            ) {
                File(cover).takeIf { it.isFile }?.let { files.add(it) }
            }
            if (book.isLocal) {
                localBookCoverPrefixes.add(MD5Utils.md5Encode16(book.bookUrl))
            }
        }
        if (localBookCoverPrefixes.isNotEmpty()) {
            coversDir.listFiles()?.forEach { file ->
                if (file.isFile && localBookCoverPrefixes.any { file.name.startsWith(it) }) {
                    files.add(file)
                }
            }
        }
        return files.toList()
    }

    private fun collectBookCoverRestoreTargets(): CoverRestoreTargets {
        val exactNames = hashSetOf<String>()
        val prefixes = hashSetOf<String>()
        appDb.bookDao.all.forEach { book ->
            val cover = book.getDisplayCover()
            if (!cover.isNullOrBlank()
                && !cover.startsWith("http", true)
                && !cover.isContentScheme()
            ) {
                File(cover).name.takeIf { it.isNotBlank() }?.let { exactNames.add(it) }
            }
            if (book.isLocal) {
                prefixes.add(MD5Utils.md5Encode16(book.bookUrl))
            }
        }
        return CoverRestoreTargets(exactNames, prefixes)
    }

    private data class CoverRestoreTargets(
        val exactNames: Set<String>,
        val prefixes: Set<String>
    )

    suspend fun export(byteArray: ByteArray, fileName: String) {
        val target = storage(S3ContainerScope.DEFAULT)
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        target.upload(EXPORTS_DIR + fileName, byteArray, "text/plain")
    }

    suspend fun export(uri: Uri, fileName: String) {
        val target = storage(S3ContainerScope.DEFAULT)
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        target.upload(EXPORTS_DIR + fileName, uri, "text/plain")
    }

    suspend fun uploadBookProgress(book: Book, toast: Boolean = false, onSuccess: (() -> Unit)? = null) {
        if (!AppConfig.syncBookProgress || !NetworkUtils.isAvailable() || !selectBackend().isOk) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            storage(S3ContainerScope.DEFAULT).upload(getProgressPath(book.name, book.author), json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            io.legado.app.constant.AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        if (!AppConfig.syncBookProgress || !NetworkUtils.isAvailable() || !selectBackend().isOk) return
        val json = GSON.toJson(bookProgress)
        storage(S3ContainerScope.DEFAULT).upload(getProgressPath(bookProgress.name, bookProgress.author), json.toByteArray(), "application/json")
        onSuccess?.invoke()
    }

    suspend fun getBookProgress(book: Book): BookProgress? {
        val path = getProgressPath(book.name, book.author)
        kotlin.runCatching {
            val byteArray = storage(S3ContainerScope.DEFAULT).download(path)
            val json = String(byteArray)
            if (json.isJson()) {
                return GSON.fromJsonObject<BookProgress>(json).getOrNull()
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            io.legado.app.constant.AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        if (!NetworkUtils.isAvailable() || !selectBackend().isOk) return
        val files = storage(S3ContainerScope.DEFAULT).listFiles(BOOK_PROGRESS_DIR)
        val map = files.associateBy { it.displayName }
        appDb.bookDao.all.forEach { book ->
            val file = map[getProgressFileName(book.name, book.author)] ?: return@forEach
            if (file.lastModify <= book.syncTime) return@forEach
            getBookProgress(book)?.let { progress ->
                if (progress.durChapterIndex > book.durChapterIndex ||
                    (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = progress.durChapterIndex
                    book.durChapterPos = progress.durChapterPos
                    book.durChapterTitle = progress.durChapterTitle
                    book.durChapterTime = progress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

    private suspend fun listZipFiles(
        path: String,
        backend: CloudStorageBackend,
        emptyOnError: Boolean = false
    ): List<CloudStorageFile> {
        if (!NetworkUtils.isAvailable()) {
            if (emptyOnError) return emptyList()
            throw NoStackTraceException("Network unavailable")
        }
        val files = runCatching {
            backend.listFiles(path)
        }.getOrElse {
            if (it is ObjectNotFoundException) {
                emptyList()
            } else {
                throw it
            }
        }
        return files.filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    private suspend fun uploadZip(path: String, remoteDirName: String, zipFile: File, backend: CloudStorageBackend) {
        ensureNetwork()
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        backend.makeDir(path)
        backend.upload(path + fileName, zipFile)
    }

    private suspend fun downloadZip(path: String, remoteDirName: String, zipFile: File, backend: CloudStorageBackend) {
        ensureNetwork()
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        backend.downloadTo(path + fileName, zipFile, true)
    }

    private suspend fun deleteZip(path: String, remoteDirName: String, backend: CloudStorageBackend) {
        ensureNetwork()
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        backend.delete(path + fileName)
    }

    private fun storage(scope: S3ContainerScope): CloudStorageBackend {
        return if (type == CloudStorageType.S3) {
            S3CloudStorageBackend(scope)
        } else {
            selectBackend()
        }
    }

    private fun scopedBackend(defaultScope: S3ContainerScope, containerId: String?, scope: String?): CloudStorageBackend {
        if (type != CloudStorageType.S3) return selectBackend()
        val targetScope = scope?.let { S3ContainerScope.from(it) } ?: defaultScope
        return S3CloudStorageBackend(targetScope, containerId)
    }

    private suspend fun backupFiles(): List<CloudStorageFile> {
        return if (type == CloudStorageType.S3) {
            scanBackupFiles()
        } else {
            storage(S3ContainerScope.MAIN_BACKUP).listFiles("")
                .filter { it.displayName.startsWith("backup") }
        }
    }

    private suspend fun findBackupLocation(fileName: String): S3BackupIndexItem? {
        return scanBackupIndexItems().firstOrNull { it.fileName == fileName }
    }

    private suspend fun scanBackupFiles(): List<CloudStorageFile> {
        return scanBackupIndexItems().map {
            CloudStorageFile(it.fileName, it.fileName, it.size, it.time, false)
        }
    }

    private suspend fun scanBackupIndexItems(): List<S3BackupIndexItem> {
        if (type != CloudStorageType.S3) return emptyList()
        return S3ContainerManager.listContainers().filter { it.enabled }.flatMap { container ->
            runCatching {
                s3Backend.listFiles(container.id, "")
                    .filter { it.displayName.startsWith("backup") }
                    .map { file ->
                        S3BackupIndexItem(file.displayName, file.lastModify, file.size, container.id)
                    }
            }.getOrDefault(emptyList())
        }
    }
    private fun getThemeTypePath(isNightTheme: Boolean): String = THEMES_DIR + if (isNightTheme) "night/" else "day/"
    private fun getNavigationBarTypePath(isNightTheme: Boolean): String = NAVIGATION_BARS_DIR + if (isNightTheme) "night/" else "day/"
    private fun getTopBarTypePath(isNightTheme: Boolean): String = TOP_BARS_DIR + if (isNightTheme) "night/" else "day/"
    private fun getCoverCollectionTypePath(isNightTheme: Boolean): String = COVER_COLLECTIONS_DIR + if (isNightTheme) "night/" else "day/"

    private fun getProgressPath(name: String, author: String): String = BOOK_PROGRESS_DIR + getProgressFileName(name, author)

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    private fun cachePackageFileNameCandidates(fileName: String): List<String> {
        val raw = fileName.trimEnd('/')
        val withZip = if (raw.endsWith(".zip", ignoreCase = true)) raw else "$raw.zip"
        val normalized = normalizeCachePackageFileName(fileName)
        val normalizedWithZip = normalizeCachePackageFileName(withZip)
        return linkedSetOf(withZip, raw, normalized, normalizedWithZip)
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun normalizeCachePackageFileName(fileName: String): String {
        val safeFileName = UrlUtil.replaceReservedChar(
            fileName.trimEnd('/').removeSuffix(".zip").normalizeFileName()
        ).ifBlank { "cache_${System.currentTimeMillis()}" }
        return "$safeFileName.zip"
    }

    private fun ensureNetwork() {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
    }
}

