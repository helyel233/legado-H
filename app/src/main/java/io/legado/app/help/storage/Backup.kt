package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.stream.JsonWriter
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.cloud.S3CapacityFullException
import io.legado.app.lib.cloud.S3ContainerManager
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.help.ai.AiSecretCipher
import io.legado.app.help.config.AppConfig
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ShelfIdentity
import io.legado.app.help.config.CoverHtmlTemplateConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.SecurePrefs
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.pkg.UnifiedPackageRepository
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.model.BookCover
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskImport
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.R

/**
 * 备份
 */
object Backup {

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"
    internal const val bookCharactersFileName = "bookCharacters.json"
    internal const val bookCharacterRelationsFileName = "bookCharacterRelations.json"
    internal const val bookCharacterAvatarsDirName = "bookCharacterAvatars"
    internal const val advancedTitlePackagesDirName = "advancedTitlePackages"
    internal const val bubblePackagesDirName = "bubblePackages"

    // UI 美术资产包目录名：与恢复端共用 UiPackageBackup 的单一真相源
    internal const val themePackagesDirName = UiPackageBackup.THEME_PACKAGES
    internal const val navigationBarPackagesDirName = UiPackageBackup.NAVIGATION_BAR_PACKAGES
    internal const val topBarPackagesDirName = UiPackageBackup.TOP_BAR_PACKAGES
    internal const val coverCollectionsDirName = UiPackageBackup.COVER_COLLECTIONS
    internal const val appThemeKitsDirName = UiPackageBackup.APP_THEME_KITS
    internal const val unifiedPackagesDirName = UiPackageBackup.UNIFIED_PACKAGES

    internal const val fontsDirName = "fonts"
    internal const val illustrationsDirName = IllustrationHelp.ILLUSTRATIONS_DIR_NAME
    internal const val readMenuIconsDirName = "readMenuIcons"
    internal const val readAloudAudioDirName = "readAloudAudio"
    internal const val aiImagesDirName = "ai_images"
    internal const val sourceConfigFileName = "sourceConfig.xml"
    internal const val sourceConfigPrefName = "SourceConfig"
    internal const val bookCacheBackupDirName = "book_cache"

    internal const val BACKUP_AI_SECRET_PREFIX = "benc:v1:"

    private const val TAG = "Backup"

    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_REPORT_INTERVAL_MS = 80L

    /** 与 [Restore] 共享的互斥锁：备份会删建备份目录，恢复会解压到同一目录，必须互斥。 */
    internal val mutex = Mutex()

    /** [pendingShelfChangeJob] 的 cancel-assign 原子化锁：书架变动回调可能来自多线程。 */
    private val pendingShelfChangeLock = Any()

    /**
     * 「书架变动自动备份」的去抖任务。
     * 连续触发时取消上一个重开计时，而不是叠加多个备份任务。
     */
    @Volatile
    private var pendingShelfChangeJob: Coroutine<Unit>? = null

    /** 书架变动后的去抖时长：等连续增删（批量导入/删除）停下来再备份。 */
    private const val SHELF_CHANGE_DEBOUNCE_MS = 8_000L

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            "readRecord.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "keyboardAssists.json",
            "dictRule.json",
            bookCharactersFileName,
            bookCharacterRelationsFileName,
            "bookCollections.json",
            "bookCollectionItems.json",
            "bookCollectionChildren.json",
            "bookIllustrations.json",
            "paragraphRules.json",
            "bookParagraphRules.json",
            "paragraphRuleVars.json",
            "readMenuCustomButtons.json",
            "homepageModules.json",
            "homepageCustomSets.json",
            "readAloudBgmGroups.json",
            "readAloudBgmTracks.json",
            "readAloudSpeakerGroups.json",
            "readAloudSpeakerGroupItems.json",
            "readRecordDaily.json",
            "readRecentBooks.json",
            "rssReadRecords.json",
            "pendingReviewComments.json",
            "aiMemoryItems.json",
            "aiMemoryFragments.json",
            "aiImageGroups.json",
            "aiGeneratedImages.json",
            "servers.json",
            "cookies.json",
            "autoTask.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ReadBookConfig.epubConfigFileName,
            ReadBookConfig.epubShareConfigFileName,
            EpubReaderTemplateStore.fileName,
            ThemeConfig.configFileName,
            BookCover.configFileName,
            CoverHtmlTemplateConfig.configFileName,
            "sourceRuntime.json",
            "config.xml",
            "videoConfig.xml",
            sourceConfigFileName
        )
    }

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = AppConfig.webDavDeviceName
        return if (deviceName?.isNotBlank() == true) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    fun autoBack(context: Context) {
        val appContext = context.applicationContext ?: context
        Coroutine.async(Coroutine.defaultScope) {
            if (shouldBackup()) {
                LogUtils.d(TAG, "auto backup delayed for foreground first frame")
                delay(5000)
            }
            var mainSucceeded = false
            val targetPath = AppConfig.backupPath
            mutex.withLock {
                if (shouldBackup()) {
                    AppLog.put("自动备份触发")
                    AppLog.put("Auto backup trigger")
                    LogUtils.d(TAG, "auto backup trigger")
                    mainSucceeded = backup(appContext, targetPath)
                } else {
                    AppLog.put("自动备份跳过: 今日已备份")
                    AppLog.put("Auto backup skipped: already backed up today")
                    LogUtils.d(TAG, "auto backup skipped by interval")
                }
            }
            if (mainSucceeded && AppConfig.backupAssetsSeparately) {
                AssetsBackupManager.enqueueAsync(
                    context = appContext,
                    path = targetPath,
                    uploadCloud = true,
                    uploadWebDavFallback = false,
                    showUserFeedback = false
                )
            }
        }.onError {
            AppLog.put("自动备份失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 「书架变动时自动备份」。
     *
     * 与 [autoBack] 的区别（**两者不可互相替代**）：
     * - [autoBack] 是「**一天一次**」的定时兑底（`shouldBackup()` 闸门）；
     * - 本方法由书架**增删**触发，且**刻意绕开** `shouldBackup()` 闸门——
     *   否则「变动即备份」会被一天一次的闸门吞掉。
     *
     * ## 判据
     *
     * 比对**在线书身份键集合**（[ShelfIdentity.keysOf]）与本机上次记账的集合：
     * - 换源（`bookUrl` 变、身份键不变）**不算变动**；
     * - 阅读进度/封面等只改字段的动作**不算变动**；
     * - 离线书的增删**不算变动**；
     * - 只有在线书的**增删**才算。
     */
    fun autoBackupOnShelfChangeIfNeeded(context: Context) {
        if (!AppConfig.autoBackupOnShelfChange) {
            return
        }
        // P0 守卫：恢复流程进行中一律不备份。
        // 恢复正把备份解压到 `backupPath`，而 `backup()` 开头会 `FileUtils.delete(backupPath)`
        // —— 中途备份会把正在被读取的备份目录删掉，使恢复读到空/半截内容。
        if (Restore.isRestoring) {
            AppLog.put("书架变动自动备份已跳过：恢复流程进行中")
            return
        }
        synchronized(pendingShelfChangeLock) {
            pendingShelfChangeJob?.cancel()
            pendingShelfChangeJob = Coroutine.async(Coroutine.defaultScope) {
                // 去抖：连续的增删（如批量导入、批量删除）只触发一次。
                delay(SHELF_CHANGE_DEBOUNCE_MS)
                // 去抖期间开关可能被关闭或进了恢复，这里复查一次。
                if (!AppConfig.autoBackupOnShelfChange || Restore.isRestoring) {
                    return@async
                }
                var mainSucceeded = false
                var targetPath: String? = null
                // 持锁后再复查：恢复流程（restoreLocked）持同一把锁，锁内复查可保证
                // 备份删建 backupPath 与恢复解压到 backupPath 互斥。
                mutex.withLock {
                    if (Restore.isRestoring) {
                        AppLog.put("书架变动自动备份已跳过：恢复流程进行中")
                        return@withLock
                    }
                    val backupPath = AppConfig.backupPath
                    if (backupPath.isNullOrBlank()) {
                        // 未配置备份路径：跳过并记日志，不弹 UI（自动行为不该打断用户）。
                        AppLog.put("书架变动自动备份已跳过：未配置备份路径")
                        return@withLock
                    }
                    val currentKeys = ShelfIdentity.keysOf(appDb.bookDao.all)
                    if (encodeKeys(currentKeys) == LocalConfig.lastShelfKeys) {
                        // 补跳过日志：消除「开关开着但从不备份」时的静默出口，便于排查。
                        AppLog.put("书架变动自动备份已跳过：身份键集合未变化")
                        return@withLock
                    }
                    targetPath = backupPath
                    mainSucceeded = backup(context, backupPath)
                    if (mainSucceeded) {
                        // ⚠️ 记账必须放在**备份成功之后**：放在开头的话，备份中途被取消/进程被杀
                        // 会留下「标记已更新但备份没做成」，该次变动此后永不补备份。
                        LocalConfig.lastShelfKeys = encodeKeys(currentKeys)
                        AppLog.put("书架变动自动备份完成：${currentKeys.size} 本在线书")
                    }
                }
                if (mainSucceeded && AppConfig.backupAssetsSeparately) {
                    AssetsBackupManager.enqueueAsync(
                        context = context,
                        path = targetPath,
                        uploadCloud = true,
                        uploadWebDavFallback = false,
                        showUserFeedback = false
                    )
                }
            }.onError {
                AppLog.put("书架变动自动备份失败\n${it.localizedMessage}", it)
            }
        }
    }

    /** 身份键集合 → 持久化字符串。 */
    internal fun encodeKeys(keys: Set<ShelfIdentity.Key>): String =
        keys.map { "${it.type}\u0001${it.name}\u0001${it.author}" }
            .sorted()
            .joinToString("\n")

    suspend fun backupLocked(
        context: Context,
        path: String?,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        isManual: Boolean = false,
        onProgress: ((BackupProgress) -> Unit)? = null
    ) {
        val appContext = context.applicationContext ?: context
        var mainSucceeded = false
        mutex.withLock {
            withContext(IO) {
                mainSucceeded = backup(appContext, path, uploadCloud, uploadWebDavFallback, onProgress)
            }
        }
        if (mainSucceeded && AppConfig.backupAssetsSeparately) {
            AssetsBackupManager.enqueueAsync(
                context = appContext,
                path = path,
                uploadCloud = uploadCloud,
                uploadWebDavFallback = uploadWebDavFallback,
                showUserFeedback = isManual
            )
        }
    }

    private suspend fun backup(
        context: Context,
        path: String?,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        onProgress: ((BackupProgress) -> Unit)? = null
    ): Boolean {
        LogUtils.d(TAG, "开始备份 path:$path")
        val reporter = BackupProgressReporter(onProgress)
        reporter.report(BackupProgress(BackupStage.PREPARING), force = true)
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        writeBookshelfToJson(backupPath)
        writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
        writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
        writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", backupPath)
        writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        writeListToJson(appDb.bookCharacterDao.allCharacters(), bookCharactersFileName, backupPath)
        writeListToJson(appDb.bookCharacterDao.allRelations(), bookCharacterRelationsFileName, backupPath)
        writeListToJson(appDb.bookCollectionDao.allCollections(), "bookCollections.json", backupPath)
        writeListToJson(appDb.bookCollectionDao.allItems(), "bookCollectionItems.json", backupPath)
        writeListToJson(appDb.bookCollectionDao.allChildren(), "bookCollectionChildren.json", backupPath)
        writeListToJson(appDb.bookIllustrationDao.all, "bookIllustrations.json", backupPath)
        writeListToJson(appDb.paragraphRuleDao.all(), "paragraphRules.json", backupPath)
        writeListToJson(appDb.paragraphRuleDao.allBookRules(), "bookParagraphRules.json", backupPath)
        writeListToJson(appDb.paragraphRuleDao.allVars(), "paragraphRuleVars.json", backupPath)
        writeListToJson(appDb.readMenuCustomButtonDao.all(), "readMenuCustomButtons.json", backupPath)
        writeListToJson(appDb.homepageModuleDao.all, "homepageModules.json", backupPath)
        writeListToJson(appDb.homepageCustomSetDao.all, "homepageCustomSets.json", backupPath)
        writeListToJson(appDb.readAloudBgmDao.groups(), "readAloudBgmGroups.json", backupPath)
        writeListToJson(appDb.readAloudBgmDao.allTracks(), "readAloudBgmTracks.json", backupPath)
        writeListToJson(appDb.readAloudSpeakerGroupDao.groups(), "readAloudSpeakerGroups.json", backupPath)
        writeListToJson(appDb.readAloudSpeakerGroupDao.items(), "readAloudSpeakerGroupItems.json", backupPath)
        writeListToJson(appDb.readRecordDailyDao.allDesc, "readRecordDaily.json", backupPath)
        writeListToJson(appDb.readRecentBookDao.all, "readRecentBooks.json", backupPath)
        writeListToJson(appDb.rssReadRecordDao.getRecords(), "rssReadRecords.json", backupPath)
        writeListToJson(appDb.pendingReviewCommentDao.all(), "pendingReviewComments.json", backupPath)
        writeListToJson(appDb.aiMemoryDao.allItems(), "aiMemoryItems.json", backupPath)
        writeListToJson(appDb.aiMemoryDao.allFragments(), "aiMemoryFragments.json", backupPath)
        writeListToJson(appDb.aiImageGroupDao.all(), "aiImageGroups.json", backupPath)
        writeListToJson(appDb.aiGeneratedImageDao.all(), "aiGeneratedImages.json", backupPath)
        AutoTask.all().takeIf { it.isNotEmpty() }?.let { rules ->
            FileUtils.createFileIfNotExist(backupPath + File.separator + "autoTask.json")
                .writeText(AutoTaskImport.exportJson(rules), Charsets.UTF_8)
        }
        exportVisualResourcePackages()
        // 未设备份密码时跳过含凭据的服务器配置与 Cookie，避免以空密码或明文导出
        if (aes.hasPassword) {
            appDb.serverDao.all.takeIf { it.isNotEmpty() }?.let { servers ->
                val json = GSON.toJson(servers)
                aes.runCatching {
                    encryptBase64(json)
                }.onSuccess { encrypted ->
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                        .writeText(encrypted)
                }.onFailure {
                    AppLog.put("备份加密 servers.json 失败，已跳过", it)
                }
            }
            appDb.cookieDao.all.takeIf { it.isNotEmpty() }?.let { cookies ->
                val json = GSON.toJson(cookies)
                aes.runCatching {
                    encryptBase64(json)
                }.onSuccess { encrypted ->
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "cookies.json")
                        .writeText(encrypted)
                }.onFailure {
                    AppLog.put("备份加密 cookies.json 失败，已跳过", it)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        ReadBookConfig.backupLayoutFiles().forEach { (name, json) ->
            FileUtils.createFileIfNotExist(backupPath + File.separator + name).writeText(json)
        }
        GSON.toJson(ThemeConfig.configList).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfig.configFileName)
                .writeText(it)
        }
        File(EpubReaderTemplateStore.filePath).takeIf { it.isFile && it.length() > 0L }?.let { file ->
            file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }?.let { text ->
                FileUtils.createFileIfNotExist(backupPath + File.separator + EpubReaderTemplateStore.fileName)
                    .writeText(text, Charsets.UTF_8)
            }
        }
        File(CoverHtmlTemplateConfig.configFilePath).takeIf { it.isFile && it.length() > 0L }?.let { file ->
            file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }?.let { text ->
                FileUtils.createFileIfNotExist(backupPath + File.separator + CoverHtmlTemplateConfig.configFileName)
                    .writeText(text, Charsets.UTF_8)
            }
        }
        DirectLinkUpload.getConfig()?.let { rule ->
            DirectLinkUpload.encodeRule(rule)
                .onSuccess { json ->
                    FileUtils.createFileIfNotExist(
                        backupPath + File.separator + DirectLinkUpload.ruleFileName
                    ).writeText(json)
                }
                .onFailure { AppLog.put("备份直链上传规则失败", it) }
        }
        BookCover.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                .writeText(GSON.toJson(it))
        }
        exportSourceRuntime(aes)
        currentCoroutineContext().ensureActive()
        // 直接以文件形式写入 config.xml：SharedPreferences 进程级缓存只在首次
        // 创建时读盘，且会残留上次备份的键（如后来开启「忽略阅读配置」前的键），
        // 直接读写绕开缓存，每次都依据当前偏好生成完整文件
        val configValues = mutableMapOf<String, Any?>()
        appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
            if (BackupConfig.keyIsNotIgnore(key)) {
                when (key) {
                    PreferKey.webDavPassword, PreferKey.s3SecretKey, PreferKey.s3SessionToken,
                    PreferKey.webServiceToken, PreferKey.aiTavilyApiKey -> {
                        // 未设备份密码时省略敏感凭据键，禁止用空密码派生密钥加密导出
                        if (!aes.hasPassword) return@forEach
                        // SecurePrefs 本地存储值可能是密文 blob：备份包里必须放
                        // 「明文的二次加密」，否则跨设备恢复时本机 KeyStore 密钥解不开。
                        // read 兼容明文（未迁移数据），解密失败按空处理即跳过该键。
                        // 加密失败跳过该键：绝不能把明文密码落进备份包
                        val plain = SecurePrefs.read(key).orEmpty()
                        if (plain.isNotEmpty()) {
                            runCatching { aes.encryptBase64(plain) }.getOrNull()?.let {
                                configValues[key] = it
                            } ?: AppLog.put("备份加密 $key 失败，已跳过该键")
                        }
                    }

                    PreferKey.s3Containers -> {
                        // 未设备份密码时省略含 S3 密钥的容器配置，避免明文或空密码导出
                        if (aes.hasPassword) {
                            S3ContainerManager.toEncryptedBackupJson(aes)?.let {
                                configValues[key] = it
                            }
                        }
                    }

                    PreferKey.aiProviderList, PreferKey.aiImageProviderList, PreferKey.aiMcpServerList -> {
                        // 未设备份密码时省略含 AI API 密钥的配置键，避免空密码加密导出
                        if (aes.hasPassword) {
                            val rawJson = value?.toString().orEmpty()
                            if (rawJson.isNotEmpty()) {
                                encodeAiSecretsForBackup(key, rawJson, aes).takeIf { it.isNotEmpty() }?.let {
                                    configValues[key] = it
                                }
                            }
                        }
                    }

                    else -> configValues[key] = value
                }
            }
        }
        BackupPrefsFile.write(File(backupPath, "config.xml"), configValues)
        currentCoroutineContext().ensureActive()
        // 直接以文件形式写入 videoConfig.xml / sourceConfig.xml（同 config.xml，绕开 SP 进程缓存）
        BackupPrefsFile.write(
            File(backupPath, "videoConfig.xml"),
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).all
        )
        BackupPrefsFile.write(
            File(backupPath, sourceConfigFileName),
            appCtx.getSharedPreferences(sourceConfigPrefName, Context.MODE_PRIVATE).all
        )
        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val zipSources = arrayListOf<ZipUtils.ZipSource>()
        backupFileNames.forEach { name ->
            zipSources.add(ZipUtils.ZipSource(File(backupPath, name)))
        }
        // 主参数包仅保留 unifiedPackages/param 与 active_prefs.json 以及 highlightRules；
        // 美术/封面/媒体资产由 AssetsBackupManager 异步分包同步（或单包模式统一并入）。
        File(backupPath, unifiedPackagesDirName).takeIf { it.exists() }
            ?.let { zipSources.add(ZipUtils.ZipSource(it)) }
        File(backupPath, io.legado.app.help.book.highlight.HighlightRules.BACKUP_DIR)
            .takeIf { it.exists() }?.let { zipSources.add(ZipUtils.ZipSource(it)) }
        if (!AppConfig.backupAssetsSeparately) {
            zipSources.addAll(AssetsBackupManager.collectArtAndMediaSources(context))
        }
        zipSources.addAll(collectBookFileSources())
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        var backupSuccess = false
        try {
            reporter.report(BackupProgress(BackupStage.PACKING), force = true)
            if (ZipUtils.zipFiles(zipSources, zipFilePath) { processed, total ->
                    reporter.report(BackupProgress(BackupStage.PACKING, processed, total))
                }) {
                reporter.report(BackupProgress(BackupStage.SAVING), force = true)
                copyBackupFile(context, path, backupFileName, File(zipFilePath)) { processed, total ->
                    reporter.report(BackupProgress(BackupStage.SAVING, processed, total))
                }
                if (uploadCloud) {
                    val cloudType = if (uploadWebDavFallback) CloudStorageType.WEBDAV else AppCloudStorage.type
                    AppLog.put("Upload cloud backup: ${cloudType.name} $zipFileName")
                    reporter.report(BackupProgress(BackupStage.UPLOADING), force = true)
                    if (uploadWebDavFallback) {
                        AppCloudStorage.backupToWebDav(zipFileName)
                    } else {
                        AppCloudStorage.backup(zipFileName)
                    }
                    AppLog.put("Cloud backup finished: ${cloudType.name} $zipFileName")
                }
                backupSuccess = true
            } else {
                throw NoStackTraceException("创建备份压缩包失败")
            }
            if (backupSuccess) {
                LocalConfig.lastBackup = System.currentTimeMillis()
                LogUtils.d(TAG, appCtx.getString(R.string.backup_stage_finished))
            }
        } finally {
            // 无论成功失败都清理：上传失败时若跳过清理，含明文数据的临时 zip
            // 与备份目录会一直残留在 externalFiles 里
            FileUtils.delete(backupPath)
            FileUtils.delete(zipFilePath)
        }
        currentCoroutineContext().ensureActive()
        reporter.report(BackupProgress(BackupStage.FINISHED), force = true)
        return backupSuccess
    }

    private fun exportSourceRuntime(aes: BackupAES) {
        if (BackupConfig.ignoreSourceRuntime) return
        // 未设备份密码时跳过含源登录态的 sourceRuntime.json，避免明文导出凭据
        if (!aes.hasPassword) return
        val sourceCaches = appDb.cacheDao.getSourceRuntimeCaches()
        val items = arrayListOf<SourceRuntimeBackupItem>()
        appDb.bookSourceDao.all.forEach { source ->
            buildSourceRuntimeItem("bookSource", source, sourceCaches)?.let(items::add)
        }
        appDb.rssSourceDao.all.forEach { source ->
            buildSourceRuntimeItem("rssSource", source, sourceCaches)?.let(items::add)
        }
        appDb.httpTTSDao.all.forEach { source ->
            buildSourceRuntimeItem("httpTts", source, sourceCaches)?.let(items::add)
        }
        if (items.isNotEmpty()) {
            val json = GSON.toJson(SourceRuntimeBackup(items = items))
            aes.runCatching {
                encryptBase64(json)
            }.onSuccess { encrypted ->
                FileUtils.createFileIfNotExist(backupPath + File.separator + "sourceRuntime.json")
                    .writeText(encrypted)
            }.onFailure {
                AppLog.put("备份加密 sourceRuntime.json 失败，已跳过", it)
            }
        }
    }

    private fun buildSourceRuntimeItem(
        sourceType: String,
        source: BaseSource,
        sourceCaches: List<io.legado.app.data.entities.Cache>
    ): SourceRuntimeBackupItem? {
        val loginInfo = source.getLoginInfo()?.let { raw ->
            GSON.fromJsonObject<HashMap<String, String>>(raw).getOrNull()?.toMap()
        }
        val sourceVariable = source.getVariable().takeIf { it.isNotBlank() }
        val sourceValues = sourceCaches
            .asSequence()
            .filter { it.key.startsWith("v_${source.getKey()}_") }
            .mapNotNull { cache ->
                cache.value?.let { value ->
                    cache.key.removePrefix("v_${source.getKey()}_") to value
                }
            }
            .toMap(linkedMapOf())
        if (loginInfo == null && sourceVariable == null && sourceValues.isEmpty()) {
            return null
        }
        return SourceRuntimeBackupItem(
            sourceType = sourceType,
            sourceKey = source.getKey(),
            loginInfo = loginInfo,
            sourceVariable = sourceVariable,
            sourceValues = sourceValues
        )
    }

    private fun exportVisualResourcePackages() {
        io.legado.app.help.book.highlight.HighlightRules.store.backupTo(
            File(backupPath, io.legado.app.help.book.highlight.HighlightRules.BACKUP_DIR)
        )
        UiPackageBackup.copyUnifiedPackages(
            UnifiedPackageRepository.rootDir,
            File(backupPath, unifiedPackagesDirName),
            kindDirNames = listOf(UiPackageBackup.UNIFIED_PARAM)
        )
        val prefs = appCtx.defaultSharedPreferences
        UiPackageBackup.exportUnifiedActivePrefs(
            targetRoot = File(backupPath, unifiedPackagesDirName),
            readString = { key -> prefs.getString(key, null) },
            readBoolean = { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null }
        )
    }

    /**
     * 收集书籍文件（已下载书籍本体与正文缓存）的来源，体积较大，
     * 由「备份书籍文件」开关控制（默认关闭），直接从源目录压入 zip。
     */
    private fun collectBookFileSources(): List<ZipUtils.ZipSource> {
        if (!AppConfig.backupBookFiles) return emptyList()
        val sourceDir = File(BookHelp.cachePath)
        if (!sourceDir.isDirectory) return emptyList()
        AppLog.put("备份书籍文件：直接从缓存目录打包")
        return listOf(ZipUtils.ZipSource(sourceDir, bookCacheBackupDirName))
    }

    internal fun encodeAiSecretsForBackup(key: String, rawJson: String, aes: BackupAES): String {
        if (rawJson.isBlank() || !aes.hasPassword) return ""
        return runCatching {
            when (key) {
                PreferKey.aiProviderList -> {
                    val list = GSON.fromJsonArray<AiProviderConfig>(rawJson).getOrThrow()
                    GSON.toJson(list.map { provider ->
                        provider.copy(
                            apiKey = encryptAiSecretField(provider.apiKey, aes),
                            headers = provider.headers?.let { encryptAiSecretField(it, aes) }
                        )
                    })
                }

                PreferKey.aiImageProviderList -> {
                    val list = GSON.fromJsonArray<AiImageProviderConfig>(rawJson).getOrThrow()
                    GSON.toJson(list.map { provider ->
                        provider.copy(
                            apiKey = encryptAiSecretField(provider.apiKey, aes),
                            headers = encryptAiSecretField(provider.headers, aes)
                        )
                    })
                }

                PreferKey.aiMcpServerList -> {
                    val list = GSON.fromJsonArray<AiMcpServerConfig>(rawJson).getOrThrow()
                    GSON.toJson(list.map { server ->
                        server.copy(apiKey = encryptAiSecretField(server.apiKey, aes))
                    })
                }

                else -> rawJson
            }
        }.getOrElse {
            AppLog.put("备份加密 AI 凭据失败($key)，已跳过\n${it.localizedMessage}", it)
            ""
        }
    }

    private fun encryptAiSecretField(raw: String, aes: BackupAES): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith(BACKUP_AI_SECRET_PREFIX)) return raw
        val plain = AiSecretCipher.decrypt(raw)
        if (plain.isEmpty()) return ""
        return runCatching {
            BACKUP_AI_SECRET_PREFIX + aes.encryptBase64(plain)
        }.getOrElse {
            AppLog.put("备份加密 AI 字段失败，已清空该字段\n${it.localizedMessage}", it)
            ""
        }
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty()) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    private suspend fun writeBookshelfToJson(path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            val bookUrls = appDb.bookDao.allBookUrls
            if (bookUrls.isEmpty()) {
                LogUtils.d(TAG, "Backup bookshelf.json list is empty")
                return@withContext
            }
            LogUtils.d(TAG, "Backup bookshelf.json list size ${bookUrls.size}")
            val file = FileUtils.createFileIfNotExist(path + File.separator + "bookshelf.json")
            file.outputStream().buffered().use { output ->
                JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                    writer.beginArray()
                    bookUrls.chunked(50).forEach { chunk ->
                        currentCoroutineContext().ensureActive()
                        appDb.bookDao.getBooksSafe(chunk, chunkSize = 50).forEach { book ->
                            GSON.toJson(book, book.javaClass, writer)
                        }
                    }
                    writer.endArray()
                }
            }
            LogUtils.d(TAG, "Backup bookshelf.json written size ${file.length()}")
        }
    }

    @Throws(Exception::class)
    internal fun copyBackupFile(
        context: Context,
        path: String?,
        fileName: String,
        sourceFile: File = File(zipFilePath),
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        when {
            path.isNullOrBlank() -> {
                val fallbackDir = context.getExternalFilesDir(null)
                    ?: throw NoStackTraceException("外部存储不可用")
                copyBackup(fallbackDir, fileName, sourceFile, onProgress)
            }

            path.isContentScheme() -> {
                copyBackup(context, path.toUri(), fileName, sourceFile, onProgress)
            }

            else -> {
                copyBackup(File(path), fileName, sourceFile, onProgress)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(
        context: Context,
        uri: Uri,
        fileName: String,
        sourceFile: File = File(zipFilePath),
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)
            ?: throw NoStackTraceException("无法访问备份目录")
        // 写临时文件成功后再替换目标：直接删旧建新再覆盖写，过程中断
        // （进程被杀/断电/存储瞬满）会把上一份完好的备份一并毁掉
        val tmpName = "$fileName.tmp"
        treeDoc.findFile(tmpName)?.delete()
        val tmpDoc = treeDoc.createFile("", tmpName)
            ?: throw NoStackTraceException("创建临时文件失败")
        try {
            val outputS = tmpDoc.openOutputStream()
                ?: throw NoStackTraceException("打开OutputStream失败")
            outputS.use {
                FileInputStream(sourceFile).use { inputS ->
                    copyStreamWithProgress(inputS, outputS, sourceFile, onProgress)
                }
            }
            val oldDoc = treeDoc.findFile(fileName)
            oldDoc?.delete()
            val renamed = runCatching {
                DocumentsContract.renameDocument(context.contentResolver, tmpDoc.uri, fileName)
            }.getOrNull()
            if (renamed == null) {
                // provider 不支持重命名：退回复制临时文件内容到目标，失败必抛异常并清理残余空文件
                val fileDoc = treeDoc.createFile("", fileName)
                    ?: throw NoStackTraceException("创建文件失败")
                try {
                    val input = context.contentResolver.openInputStream(tmpDoc.uri)
                        ?: throw NoStackTraceException("读取临时文件失败")
                    val output = fileDoc.openOutputStream()
                        ?: throw NoStackTraceException("打开目标文件失败")
                    input.use { inStream ->
                        output.use { outStream ->
                            inStream.copyTo(outStream, COPY_BUFFER_SIZE)
                        }
                    }
                    if (sourceFile.length() > 0L && fileDoc.length() == 0L) {
                        throw NoStackTraceException("写入目标文件后大小为 0")
                    }
                } catch (t: Throwable) {
                    runCatching { fileDoc.delete() }
                    throw t
                }
            }
        } finally {
            // 成功（tmp 已改名）或失败都清理临时文件
            runCatching { treeDoc.findFile(tmpName)?.delete() }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(
        rootFile: File,
        fileName: String,
        sourceFile: File = File(zipFilePath),
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        if (!rootFile.exists()) {
            rootFile.mkdirs()
        }
        val file = File(rootFile, fileName)
        // 先写临时文件再替换：FileOutputStream 直接截断覆盖旧备份，中断即毁唯一备份
        val tmp = File(rootFile, "$fileName.tmp")
        try {
            FileInputStream(sourceFile).use { inputS ->
                FileOutputStream(tmp).use { outputS ->
                    copyStreamWithProgress(inputS, outputS, sourceFile, onProgress)
                }
            }
            if (!tmp.renameTo(file)) {
                // rename 失败（跨文件系统等）：退回复制
                FileInputStream(tmp).use { inputS ->
                    FileOutputStream(file).use { outputS -> inputS.copyTo(outputS) }
                }
                tmp.delete()
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun copyStreamWithProgress(
        inputS: InputStream,
        outputS: OutputStream,
        sourceFile: File,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        val totalBytes = sourceFile.length()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = inputS.read(buffer)
            if (read < 0) break
            outputS.write(buffer, 0, read)
            copied += read
            onProgress?.invoke(copied, totalBytes)
        }
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        AssetsBackupManager.clearCache()
    }

    /** 备份进度上报：按时间节流，避免高频刷新界面 */
    private class BackupProgressReporter(
        private val onProgress: ((BackupProgress) -> Unit)?
    ) {
        private var lastReportTime = 0L

        fun report(progress: BackupProgress, force: Boolean = false) {
            val callback = onProgress ?: return
            val now = System.currentTimeMillis()
            if (!force && now - lastReportTime < PROGRESS_REPORT_INTERVAL_MS) return
            lastReportTime = now
            callback(progress)
        }
    }
}
