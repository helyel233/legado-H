package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.help.config.pkg.UnifiedPackageRepository
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 美术/封面/媒体资源包独立异步协调器。
 *
 * 与主备份锁（[Backup.mutex]）彻底解耦，避免慢网打包/上传阻塞主包或恢复流程；
 * 支持请求合并防抖、高精度无变更指纹判重、TOCTOU 复核与显式失败反馈。
 */
object AssetsBackupManager {

    private const val ASSETS_BACKUP_PREFIX = "backup_assets"
    private const val ASSETS_TMP_FILE_NAME = "tmp_backup_assets.zip"
    private const val FONTS_COLLECT_DIR_NAME = "fonts_collect"
    private const val ASSETS_STAGING_DIR_NAME = "assets_staging"
    private const val AT_FONT_PREFIX = "@font:"
    private const val FINGERPRINT_CONTENT_HASH_MAX_BYTES = 256L * 1024L
    private const val MAX_MISMATCH_RETRIES = 2
    private const val AUTO_FAILURE_NOTIFY_THROTTLE_MS = 10L * 60L * 1000L
    private val fontFileRegex = Regex("(?i).*\\.[ot]tf")

    private val autoFailureNotifyLock = Any()
    private var lastAutoFailureMessage: String? = null
    private var lastAutoFailureNotifyTime: Long = 0L

    data class Request(
        val context: Context,
        val path: String?,
        val uploadCloud: Boolean = true,
        val uploadWebDavFallback: Boolean = false,
        val showUserFeedback: Boolean = false,
        val mismatchRetryCount: Int = 0
    )

    sealed class AssetsBackupState {
        data object Idle : AssetsBackupState()
        data object Checking : AssetsBackupState()
        data object Packing : AssetsBackupState()
        data object Uploading : AssetsBackupState()
        data object SkippedUnchanged : AssetsBackupState()
        data class Success(val localSaved: Boolean, val cloudUploaded: Boolean) : AssetsBackupState()
        data class Failed(val message: String) : AssetsBackupState()
    }

    /** 独立于 [Backup.mutex] 的资源包互斥锁 */
    private val assetsMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val pendingRequest = AtomicReference<Request?>(null)
    private val workerActive = AtomicBoolean(false)

    private val _state = MutableStateFlow<AssetsBackupState>(AssetsBackupState.Idle)
    val state: StateFlow<AssetsBackupState> = _state.asStateFlow()

    private val assetsTmpFile: File
        get() = File(appCtx.externalFiles, ASSETS_TMP_FILE_NAME)

    fun enqueueAsync(
        context: Context,
        path: String?,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        showUserFeedback: Boolean = false
    ) {
        val appContext = context.applicationContext ?: context
        val incoming = Request(
            context = appContext,
            path = path,
            uploadCloud = uploadCloud,
            uploadWebDavFallback = uploadWebDavFallback,
            showUserFeedback = showUserFeedback,
            mismatchRetryCount = 0
        )
        enqueueInternal(incoming)
    }

    private fun enqueueInternal(incoming: Request) {
        pendingRequest.getAndUpdate { prev ->
            if (prev == null) {
                incoming
            } else {
                incoming.copy(
                    showUserFeedback = prev.showUserFeedback || incoming.showUserFeedback,
                    mismatchRetryCount = if (incoming.mismatchRetryCount > 0) {
                        incoming.mismatchRetryCount
                    } else {
                        0
                    }
                )
            }
        }
        scheduleWorkerIfNeeded()
    }

    private fun scheduleWorkerIfNeeded() {
        if (!workerActive.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val req = pendingRequest.getAndSet(null) ?: break
                    if (Restore.isRestoring) {
                        AppLog.put("美术/封面资源包备份已跳过：恢复流程进行中")
                        _state.value = AssetsBackupState.Idle
                        continue
                    }
                    assetsMutex.withLock {
                        if (Restore.isRestoring) {
                            AppLog.put("美术/封面资源包备份已跳过：恢复流程进行中")
                            _state.value = AssetsBackupState.Idle
                            return@withLock
                        }
                        runBackupOnce(req)
                    }
                }
            } finally {
                workerActive.set(false)
                if (pendingRequest.get() != null) {
                    scheduleWorkerIfNeeded()
                }
            }
        }
    }

    private suspend fun runBackupOnce(req: Request) {
        val tmpZipFile = assetsTmpFile
        try {
            currentCoroutineContext().ensureActive()
            if (Restore.isRestoring) {
                _state.value = AssetsBackupState.Idle
                return
            }
            _state.value = AssetsBackupState.Checking
            val sources = withContext(IO) { collectArtAndMediaSources(req.context) }
            if (sources.isEmpty()) {
                AppLog.put("美术/封面资源为空，跳过资源包备份")
                _state.value = AssetsBackupState.SkippedUnchanged
                return
            }
            val assetsZipName = getAssetsZipFileName()
            val targetKey = "${AppCloudStorage.assetsStorageKey(req.uploadWebDavFallback)}:$assetsZipName"
            val fpBefore = withContext(IO) { computeAssetsFingerprint(sources) }
            val fingerprintMatched = fpBefore.isNotEmpty() && fpBefore == LocalConfig.lastAssetsFingerprint
            val hasLocalTarget = !req.path.isNullOrBlank() || req.context.getExternalFilesDir(null) != null
            val needLocalSave = hasLocalTarget &&
                (!fingerprintMatched || !localAssetsZipExists(req.context, req.path, assetsZipName))
            val needCloudUpload = req.uploadCloud && (
                !fingerprintMatched ||
                    LocalConfig.lastAssetsTarget != targetKey ||
                    !AppCloudStorage.assetsBackupExists(assetsZipName, req.uploadWebDavFallback)
                )
            if (!needLocalSave && !needCloudUpload) {
                AppLog.put("美术/封面资源包未变化，跳过打包与上传：$assetsZipName")
                _state.value = AssetsBackupState.SkippedUnchanged
                return
            }
            if (Restore.isRestoring) {
                _state.value = AssetsBackupState.Idle
                return
            }

            FileUtils.delete(tmpZipFile, deleteRootDir = true)
            _state.value = AssetsBackupState.Packing
            if (!ZipUtils.zipFiles(sources, tmpZipFile.absolutePath, null)) {
                throw NoStackTraceException("创建美术/封面资源备份压缩包失败")
            }

            val fpAfterPack = withContext(IO) { computeAssetsFingerprint(sources) }
            if (fpAfterPack != fpBefore) {
                handleConcurrentChange(req, "打包期间")
                return
            }

            var localSaved = false
            if (needLocalSave) {
                if (Restore.isRestoring) {
                    _state.value = AssetsBackupState.Idle
                    return
                }
                withContext(IO) {
                    Backup.copyBackupFile(req.context, req.path, assetsZipName, tmpZipFile)
                }
                localSaved = true
            }

            var cloudUploaded = false
            if (needCloudUpload) {
                if (Restore.isRestoring) {
                    _state.value = AssetsBackupState.Idle
                    return
                }
                _state.value = AssetsBackupState.Uploading
                AppLog.put("Upload cloud assets backup: $assetsZipName")
                if (req.uploadWebDavFallback) {
                    AppCloudStorage.backupAssetsToWebDav(assetsZipName, tmpZipFile)
                } else {
                    AppCloudStorage.backupAssets(assetsZipName, tmpZipFile)
                }
                AppLog.put("Cloud assets backup finished: $assetsZipName")
                cloudUploaded = true
            }

            val fpFinal = withContext(IO) { computeAssetsFingerprint(sources) }
            if (fpFinal != fpBefore) {
                handleConcurrentChange(req, "保存/上传期间")
                return
            }

            LocalConfig.lastAssetsFingerprint = fpBefore
            if (cloudUploaded) {
                LocalConfig.lastAssetsTarget = targetKey
            } else if (!req.uploadCloud && !fingerprintMatched) {
                // 仅本地保存了新版资源时清空云端已同步目标，防止下次开启云同步误判远端已是新版
                LocalConfig.lastAssetsTarget = ""
            }
            _state.value = AssetsBackupState.Success(
                localSaved = localSaved,
                cloudUploaded = cloudUploaded
            )
            if (cloudUploaded && req.showUserFeedback) {
                appCtx.toastOnUi(R.string.assets_backup_synced)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val detail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            _state.value = AssetsBackupState.Failed(detail)
            AppLog.put("美术/封面资源包备份失败：$detail", e)
            notifyBackupFailure(req, detail)
        } finally {
            FileUtils.delete(tmpZipFile, deleteRootDir = true)
        }
    }

    private fun handleConcurrentChange(req: Request, phase: String) {
        if (req.mismatchRetryCount < MAX_MISMATCH_RETRIES) {
            AppLog.put("美术/封面资源在${phase}发生并发变更，跳过旧指纹并合并重跑")
            enqueueInternal(req.copy(mismatchRetryCount = req.mismatchRetryCount + 1))
        } else {
            val msg = "资源在${phase}持续变动，已取消旧指纹提交"
            _state.value = AssetsBackupState.Failed(msg)
            AppLog.put("美术/封面资源包备份失败：$msg")
            notifyBackupFailure(req, msg)
        }
    }

    private fun notifyBackupFailure(req: Request, detail: String) {
        if (req.showUserFeedback) {
            appCtx.toastOnUi(appCtx.getString(R.string.assets_backup_failed, detail))
            return
        }
        if (!shouldNotifyAutoFailure(detail)) {
            return
        }
        val message = appCtx.getString(R.string.assets_backup_auto_failed, detail)
        val notificationManager = NotificationManagerCompat.from(appCtx)
        val notified = if (notificationManager.areNotificationsEnabled()) {
            runCatching {
                val notification = NotificationCompat.Builder(appCtx, AppConst.channelIdDownload)
                    .setSmallIcon(R.drawable.ic_backup)
                    .setContentTitle(appCtx.getString(R.string.backup))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                notificationManager.notify(NotificationId.BackupComplete, notification)
            }.isSuccess
        } else {
            false
        }
        if (!notified) {
            appCtx.toastOnUi(message)
        }
    }

    private fun shouldNotifyAutoFailure(detail: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(autoFailureNotifyLock) {
            val elapsed = now - lastAutoFailureNotifyTime
            if (lastAutoFailureMessage == detail && elapsed in 0L until AUTO_FAILURE_NOTIFY_THROTTLE_MS) {
                return false
            }
            lastAutoFailureMessage = detail
            lastAutoFailureNotifyTime = now
            return true
        }
    }

    internal fun getAssetsZipFileName(): String {
        val deviceName = AppConfig.webDavDeviceName
        return if (deviceName?.isNotBlank() == true) {
            "${ASSETS_BACKUP_PREFIX}-${deviceName}.zip"
        } else {
            "${ASSETS_BACKUP_PREFIX}.zip"
        }.normalizeFileName()
    }

    internal fun localAssetsZipExists(
        context: Context,
        path: String?,
        assetsZipName: String
    ): Boolean {
        return when {
            path.isNullOrBlank() -> context.getExternalFilesDir(null)
                ?.let { File(it, assetsZipName) }
                ?.let { it.isFile && it.length() > 0L } ?: false

            path.isContentScheme() -> runCatching {
                val doc = DocumentFile.fromTreeUri(context, path.toUri())?.findFile(assetsZipName)
                doc != null && doc.isFile && doc.length() > 0L
            }.getOrDefault(false)

            else -> File(path, assetsZipName).let { it.isFile && it.length() > 0L }
        }
    }

    /**
     * 收集全部美术、封面与媒体资源，供独立资源包与单包回退模式共用：
     * 1. 10 类 UI 美术/封面/视觉目录；
     * 2. 原有 4 类媒体（角色头像、字体库、10 项背景图、分组+书籍封面）；
     * 3. 阅读正文自定义背景图（`bg/`）；
     * 4. 扩展钩子 [collectAdditionalMediaSources]。
     */
    internal fun collectArtAndMediaSources(context: Context): List<ZipUtils.ZipSource> {
        val sources = arrayListOf<ZipUtils.ZipSource>()

        // 1. UI 美术/封面/视觉包目录（unifiedPackages/param 与 active_prefs.json 留在主参数包）
        collectVisibleSubdirSources(
            File(UnifiedPackageRepository.rootDir, UiPackageBackup.UNIFIED_ART),
            "${Backup.unifiedPackagesDirName}/${UiPackageBackup.UNIFIED_ART}",
            sources
        )
        collectVisibleSubdirSources(
            File(UnifiedPackageRepository.rootDir, UiPackageBackup.UNIFIED_COVER_SLOT),
            "${Backup.unifiedPackagesDirName}/${UiPackageBackup.UNIFIED_COVER_SLOT}",
            sources
        )
        collectVisibleSubdirSources(CoverCollectionManager.rootDir, Backup.coverCollectionsDirName, sources)
        collectVisibleSubdirSources(ThemePackageManager.rootDir, Backup.themePackagesDirName, sources)
        collectVisibleSubdirSources(NavigationBarIconConfig.rootDir, Backup.navigationBarPackagesDirName, sources)
        collectVisibleSubdirSources(TopBarConfig.rootDir, Backup.topBarPackagesDirName, sources)
        collectAppearanceKitSources(sources)
        collectAdvancedTitleSources(sources)
        collectBubblePackageSources(sources)
        collectReaderAssetSources(sources)

        // 2. 原有 4 类媒体
        collectBookCharacterAvatarSource()?.let(sources::add)
        sources.addAll(collectReaderFontSources(context))
        sources.addAll(collectBackgroundAssetSources(context))
        sources.addAll(collectCoverSources())

        // 3. 阅读正文自定义背景图 bg/
        sources.addAll(collectReadingBackgroundSources())

        // 4. 扩展点（预留给后续批次接入插图/自定义菜单图标/音频/AI图库等）
        sources.addAll(collectAdditionalMediaSources())

        return sources.sortedWith(compareBy<ZipUtils.ZipSource>({ it.entryRoot }, { it.file.name }))
    }

    /** 扩展点：返回附加媒体资源条目（仅非空目录纳入）。 */
    internal fun collectAdditionalMediaSources(): List<ZipUtils.ZipSource> {
        val candidates = listOf(
            appCtx.externalFiles.getFile(Backup.illustrationsDirName) to Backup.illustrationsDirName,
            appCtx.externalFiles.getFile(Backup.readMenuIconsDirName) to Backup.readMenuIconsDirName,
            File(appCtx.filesDir, Backup.readAloudAudioDirName) to Backup.readAloudAudioDirName,
            File(appCtx.filesDir, Backup.aiImagesDirName) to Backup.aiImagesDirName
        )
        return candidates.mapNotNull { (dir, entryName) ->
            if (hasAnyRegularFile(dir)) ZipUtils.ZipSource(dir, entryName) else null
        }
    }

    private fun hasAnyRegularFile(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) return true
                if (child.isDirectory) stack.addLast(child)
            }
        }
        return false
    }

    /**
     * 高精度资源指纹：
     * - 所有条目按 `entryPath` 递归排序；
     * - `<= 256 KB` 小文件/JSON 直接计算内容 SHA-256（感知同长度同 mtime 的内容变更）；
     * - `> 256 KB` 大文件写入 `entryPath:length:lastModified`（高效无 OOM）。
     */
    internal fun computeAssetsFingerprint(sources: List<ZipUtils.ZipSource>): String {
        if (sources.isEmpty()) return ""
        val entries = sortedMapOf<String, File>()
        fun walk(file: File, entryPath: String) {
            if (!file.exists()) return
            if (file.isDirectory) {
                val children = file.listFiles()?.sortedBy { it.name }.orEmpty()
                if (children.isEmpty()) {
                    entries[entryPath] = file
                } else {
                    children.forEach { child ->
                        walk(child, "$entryPath/${child.name}")
                    }
                }
            } else if (file.isFile) {
                entries[entryPath] = file
            }
        }
        sources.forEach { source ->
            val rootPath = when {
                source.entryRoot.isBlank() -> source.file.name
                source.file.isFile -> "${source.entryRoot}/${source.file.name}"
                else -> source.entryRoot
            }
            walk(source.file, rootPath)
        }
        if (entries.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        entries.forEach { (entryPath, file) ->
            val line = if (file.isDirectory) {
                "$entryPath/:dir\n"
            } else {
                val len = file.length()
                if (len <= FINGERPRINT_CONTENT_HASH_MAX_BYTES) {
                    val contentHash = sha256File(file, buffer)
                    "$entryPath:$len:sha256:$contentHash\n"
                } else {
                    "$entryPath:$len:${file.lastModified()}\n"
                }
            }
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun clearCache() {
        FileUtils.delete(assetsTmpFile, deleteRootDir = true)
        FileUtils.delete(File(appCtx.cacheDir, FONTS_COLLECT_DIR_NAME), deleteRootDir = true)
        FileUtils.delete(File(appCtx.cacheDir, ASSETS_STAGING_DIR_NAME), deleteRootDir = true)
    }

    private fun collectVisibleSubdirSources(
        rootDir: File,
        entryPrefix: String,
        out: MutableList<ZipUtils.ZipSource>
    ) {
        if (!rootDir.isDirectory) return
        rootDir.listFiles()
            ?.filter { it.isDirectory && UiPackageBackup.dirVisible(it.name) && hasAnyRegularFile(it) }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                out.add(ZipUtils.ZipSource(dir, "$entryPrefix/${dir.name}"))
            }
    }

    private fun collectAppearanceKitSources(out: MutableList<ZipUtils.ZipSource>) {
        val rootDir = AppearanceKitManager.rootDir
        if (!rootDir.isDirectory) return
        rootDir.listFiles()
            ?.filter { UiPackageBackup.dirVisible(it.name) }
            ?.sortedBy { it.name }
            ?.forEach { entry ->
                if (entry.isDirectory) {
                    if (hasAnyRegularFile(entry)) {
                        out.add(ZipUtils.ZipSource(entry, "${Backup.appThemeKitsDirName}/${entry.name}"))
                    }
                } else if (entry.isFile) {
                    out.add(ZipUtils.ZipSource(entry, Backup.appThemeKitsDirName))
                }
            }
    }

    private fun collectAdvancedTitleSources(out: MutableList<ZipUtils.ZipSource>) {
        val rootDir = AdvancedTitlePackageManager.rootDir
        if (!rootDir.isDirectory) return
        val rootCanonical = runCatching { rootDir.canonicalFile }.getOrNull() ?: return
        rootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') && hasAnyRegularFile(it) }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return@forEach
                if (canonical.parentFile == rootCanonical) {
                    out.add(ZipUtils.ZipSource(dir, "${Backup.advancedTitlePackagesDirName}/${dir.name}"))
                }
            }
    }

    private fun collectBubblePackageSources(out: MutableList<ZipUtils.ZipSource>) {
        val rootDir = BubblePackageManager.rootDir
        if (!rootDir.isDirectory) return
        val excluded = setOf("temp", "remote_cache", BubblePackageManager.BUILTIN_DIR_NAME)
        rootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') && it.name !in excluded && hasAnyRegularFile(it) }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                out.add(ZipUtils.ZipSource(dir, "${Backup.bubblePackagesDirName}/${dir.name}"))
            }
    }

    private fun collectReaderAssetSources(out: MutableList<ZipUtils.ZipSource>) {
        val stagingDir = File(appCtx.cacheDir, "$ASSETS_STAGING_DIR_NAME/${ReaderAssets.BACKUP_DIR}")
        val stagedLibraryFile = File(stagingDir, "library.json")
        val libraryJson = ReaderAssets.store.exportBackupLibraryJson()
        val assets = ReaderAssets.store.library().assets
        if (libraryJson == null || assets.isEmpty()) {
            FileUtils.delete(stagingDir, deleteRootDir = true)
            return
        }
        stagingDir.mkdirs()
        if (!stagedLibraryFile.isFile ||
            runCatching { stagedLibraryFile.readText(Charsets.UTF_8) }.getOrNull() != libraryJson
        ) {
            stagedLibraryFile.writeText(libraryJson, Charsets.UTF_8)
        }
        out.add(ZipUtils.ZipSource(stagedLibraryFile, ReaderAssets.BACKUP_DIR))
        val usedIds = hashSetOf<String>()
        assets.forEach { asset ->
            if (!usedIds.add(asset.id)) return@forEach
            ReaderAssets.store.file(asset.id)?.takeIf { it.isFile }?.let { file ->
                out.add(ZipUtils.ZipSource(file, "${ReaderAssets.BACKUP_DIR}/files"))
            }
        }
    }

    private fun collectBookCharacterAvatarSource(): ZipUtils.ZipSource? {
        val sourceDir = appCtx.externalFiles.getFile("bookCharacters", "avatars")
        if (!hasAnyRegularFile(sourceDir)) {
            return null
        }
        return ZipUtils.ZipSource(sourceDir, Backup.bookCharacterAvatarsDirName)
    }

    private fun collectReaderFontSources(context: Context): List<ZipUtils.ZipSource> {
        val sources = arrayListOf<ZipUtils.ZipSource>()
        val fontDir = File(FileUtils.getPath(appCtx.externalFiles, "font"))
        val usedEntryNames = hashSetOf<String>()
        if (hasAnyRegularFile(fontDir)) {
            sources.add(ZipUtils.ZipSource(fontDir, Backup.fontsDirName))
            fontDir.listFiles()?.filter { it.isFile }?.forEach { usedEntryNames.add(it.name) }
        }
        val refs = linkedSetOf<String>()
        ReadBookConfig.allLayoutConfigs().forEach { config ->
            config.textFont.takeIf { it.isNotBlank() }?.let(refs::add)
        }
        listOf(
            PreferKey.uiFontPath,
            PreferKey.uiFontPathN,
            PreferKey.titleFontPath,
            PreferKey.titleFontPathN
        ).forEach { key ->
            appCtx.getPrefString(key)?.takeIf { it.isNotBlank() }?.let(refs::add)
        }
        val files = refs.mapNotNull { resolveReaderFontFile(it) }
            .filter { !it.absolutePath.startsWith(fontDir.absolutePath + File.separator) }
            .distinctBy { it.absolutePath }
        files.forEach { file ->
            if (usedEntryNames.add(file.name)) {
                sources.add(ZipUtils.ZipSource(file, Backup.fontsDirName))
            }
        }
        refs.filter { resolveReaderFontFile(it) == null }
            .forEach { ref ->
                collectFontFolderFile(context, ref)?.let { file ->
                    if (usedEntryNames.add(file.name)) {
                        sources.add(ZipUtils.ZipSource(file, Backup.fontsDirName))
                    }
                }
            }
        return sources
    }

    private fun resolveReaderFontFile(ref: String): File? {
        val raw = ref.trim()
        if (raw.isEmpty() || raw.startsWith("http", ignoreCase = true)) return null
        val normalized = raw.removePrefix(AT_FONT_PREFIX)
        val name = if (normalized.contains(File.separator) || normalized.contains("://")) {
            null
        } else {
            normalized.trim().takeIf { it.isNotBlank() }
        }
        name?.let {
            File(FileUtils.getPath(appCtx.externalFiles, "font"), it)
                .takeIf { file -> file.isFile }?.let { return it }
        }
        if (raw.startsWith("/")) {
            File(raw).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private fun collectFontFolderFile(context: Context, ref: String): File? {
        val name = ref.trim().removePrefix(AT_FONT_PREFIX).trim()
        if (name.isBlank() || name.contains(File.separator) || name.contains("://")) {
            return null
        }
        if (!fontFileRegex.matches(name)) {
            return null
        }
        val fontPath = appCtx.getPrefString(PreferKey.fontFolder)?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            if (fontPath.isContentScheme()) {
                val tree = DocumentFile.fromTreeUri(context, fontPath.toUri())
                    ?: return@runCatching null
                val doc = tree.findFile(name) ?: return@runCatching null
                val stagingDir = File(appCtx.cacheDir, FONTS_COLLECT_DIR_NAME)
                stageDocumentFile(doc, name, stagingDir)
            } else {
                File(fontPath, name).takeIf { it.isFile }
            }
        }.onFailure {
            AppLog.put("从字体库目录收集字体失败：$name\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun collectBackgroundAssetSources(context: Context): List<ZipUtils.ZipSource> {
        val sources = arrayListOf<ZipUtils.ZipSource>()
        val stagingRoot = File(appCtx.cacheDir, "$ASSETS_STAGING_DIR_NAME/bg_assets")
        val activeKeys = Restore.backgroundAssetDirNames.toSet()
        stagingRoot.listFiles()?.forEach { child ->
            if (child.name !in activeKeys) {
                FileUtils.delete(child, deleteRootDir = true)
            }
        }
        Restore.backgroundAssetDirNames.forEach { key ->
            val raw = appCtx.getPrefString(key)?.trim().orEmpty()
            val keyStagingDir = File(stagingRoot, key)
            if (raw.isEmpty() || raw.startsWith("http", ignoreCase = true)) {
                FileUtils.delete(keyStagingDir, deleteRootDir = true)
                return@forEach
            }
            if (raw.isContentScheme()) {
                val staged = runCatching {
                    val doc = DocumentFile.fromSingleUri(context, raw.toUri()) ?: return@runCatching null
                    val fileName = doc.name?.normalizeFileName()?.takeIf { it.isNotBlank() }
                        ?: "${key}_${MD5Utils.md5Encode16(raw)}"
                    keyStagingDir.listFiles()?.forEach { existing ->
                        if (existing.name != fileName && existing.name != "$fileName.tmp") {
                            FileUtils.delete(existing, deleteRootDir = true)
                        }
                    }
                    stageDocumentFile(doc, fileName, keyStagingDir)
                }.onFailure {
                    AppLog.put("收集 SAF 背景图片失败($key)\n${it.localizedMessage}", it)
                }.getOrNull()
                if (staged != null) {
                    sources.add(ZipUtils.ZipSource(staged, key))
                } else {
                    FileUtils.delete(keyStagingDir, deleteRootDir = true)
                }
                return@forEach
            }
            FileUtils.delete(keyStagingDir, deleteRootDir = true)
            if (raw.startsWith("/")) {
                val file = File(raw)
                if (file.isFile) {
                    sources.add(ZipUtils.ZipSource(file, key))
                }
            }
        }
        return sources
    }

    private fun collectCoverSources(): List<ZipUtils.ZipSource> {
        val sources = arrayListOf<ZipUtils.ZipSource>()
        val usedNames = hashSetOf<String>()
        val coversDir = appCtx.externalFiles.getFile("covers")
        appDb.bookGroupDao.all.forEach { group ->
            val cover = group.cover ?: return@forEach
            if (cover.isBlank() || cover.startsWith("http", true) || cover.isContentScheme()) {
                return@forEach
            }
            val file = File(cover)
            if (file.isFile &&
                file.parentFile?.absolutePath == coversDir.absolutePath &&
                usedNames.add(file.name)
            ) {
                sources.add(ZipUtils.ZipSource(file, "covers"))
            }
        }
        if (AppConfig.webDavBackupCover) {
            AppCloudStorage.collectBookCoverFiles().forEach { file ->
                if (file.isFile && usedNames.add(file.name)) {
                    sources.add(ZipUtils.ZipSource(file, "covers"))
                }
            }
        }
        return sources
    }

    private fun collectReadingBackgroundSources(): List<ZipUtils.ZipSource> {
        val sources = arrayListOf<ZipUtils.ZipSource>()
        val usedNames = hashSetOf<String>()
        val bgDir = appCtx.externalFiles.getFile("bg")
        ReadBookConfig.getAllPicBgStr().forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("http", ignoreCase = true) ||
                trimmed.isContentScheme()
            ) {
                return@forEach
            }
            val direct = if (trimmed.contains(File.separator)) {
                File(trimmed)
            } else {
                File(bgDir, trimmed)
            }
            val file = when {
                direct.isFile -> direct
                else -> File(bgDir, File(trimmed).name).takeIf { it.isFile }
            } ?: return@forEach
            if (usedNames.add(file.name)) {
                sources.add(ZipUtils.ZipSource(file, "bg"))
            }
        }
        return sources
    }

    /**
     * 将 SAF 文档原子缓存到本地 staging 目录：
     * - 同时比对 `doc.length()` 与 `doc.lastModified()`（`lastModified <= 0` 时退化对比流 SHA-256）；
     * - 拷贝后同步 `target.setLastModified(doc.lastModified())`，消除同名同长度替换漏传。
     */
    private fun stageDocumentFile(doc: DocumentFile, targetName: String, stagingDir: File): File? {
        val docLength = doc.length()
        val docModified = doc.lastModified()
        val target = File(stagingDir, targetName)
        if (target.isFile && target.length() == docLength && target.length() > 0L) {
            if (docModified > 0L && target.lastModified() == docModified) {
                return target
            }
            if (docModified <= 0L) {
                val existingHash = sha256File(target)
                val incomingHash = doc.openInputStream()?.use { sha256Stream(it) }
                if (incomingHash != null && existingHash == incomingHash) {
                    return target
                }
            }
        }
        stagingDir.mkdirs()
        val temp = File(stagingDir, "$targetName.tmp")
        temp.delete()
        doc.openInputStream()?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: run {
            temp.delete()
            return null
        }
        if (!temp.isFile || temp.length() == 0L) {
            temp.delete()
            return null
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        if (docModified > 0L) {
            target.setLastModified(docModified)
        }
        return target.takeIf { it.isFile && it.length() > 0L }
    }

    private fun sha256File(file: File, buffer: ByteArray = ByteArray(16 * 1024)): String {
        FileInputStream(file).use { input ->
            return sha256Stream(input, buffer)
        }
    }

    private fun sha256Stream(input: InputStream, buffer: ByteArray = ByteArray(16 * 1024)): String {
        val digest = MessageDigest.getInstance("SHA-256")
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
