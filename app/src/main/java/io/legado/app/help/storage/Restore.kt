package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.bumptech.glide.Glide
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiImageGroup
import io.legado.app.data.entities.AiMemoryFragment
import io.legado.app.data.entities.AiMemoryItem
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.data.entities.BookCollection
import io.legado.app.data.entities.BookCollectionChild
import io.legado.app.data.entities.BookCollectionItem
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookIllustration
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cookie
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HomepageCustomSet
import io.legado.app.data.entities.HomepageModule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.data.entities.PendingReviewComment
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.data.entities.ReadAloudSpeakerGroup
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.data.entities.ReadRecentBook
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.cloud.S3ContainerManager
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.ai.AiMemoryStore
import io.legado.app.help.ai.AiSecretCipher
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ShelfIdentity
import io.legado.app.help.book.upType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AdvancedTitleDirectoryRestorer
import io.legado.app.help.config.AdvancedTitleDirectoryTree
import io.legado.app.help.config.BubbleDirectoryTransaction
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverHtmlTemplateConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.SecurePrefs
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.help.config.pkg.UnifiedPackageRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceGroupRepository
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.BookCover
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskImport
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.ui.book.read.ReadMenuButtonConfig
import io.legado.app.ui.config.FontProtocolResolver
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.restart
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 恢复
 */
object Restore {

    /**
     * 恢复流程进行中标记。供「书架变动自动备份」作 P0 守卫：
     * 恢复正把备份解压到 `Backup.backupPath`，中途备份会把该目录清空重建。
     */
    @Volatile
    var isRestoring = false
        private set

    /**
     * 「按备份覆盖」用的**本机在线书身份键快照**，在 DB 段合并之前取。
     *
     * 必须是实例字段：取值点在 `restoreBooks` 之前，消费点在 `restore()` 末尾，
     * 中间隔着整个恢复流程。关闭开关时为 null（不取快照、也不删除）。
     */
    private var localKeysBeforeMerge: Set<ShelfIdentity.Key>? = null

    private const val TAG = "Restore"
    private const val RESTORE_INSERT_BATCH_SIZE = 500

    internal val backgroundAssetDirNames = arrayOf(
        PreferKey.bgImage,
        PreferKey.bgImageN,
        PreferKey.bookInfoBgImage,
        PreferKey.bookInfoBgImageN,
        PreferKey.panelBgImage,
        PreferKey.panelBgImageN,
        PreferKey.topBarBgImage,
        PreferKey.topBarBgImageN,
        PreferKey.welcomeImage,
        PreferKey.welcomeImageDark
    )

    private val uiFontPrefKeys = arrayOf(
        PreferKey.uiFontPath,
        PreferKey.uiFontPathN,
        PreferKey.titleFontPath,
        PreferKey.titleFontPathN
    )

    private val artActivePackagePrefKeys = arrayOf(
        PreferKey.currentArtPackageId,
        PreferKey.activeDayArtPackageId,
        PreferKey.activeNightArtPackageId
    )

    private val coverSlotActivePackagePrefKeys = arrayOf(
        PreferKey.currentCoverSlotPackageId,
        PreferKey.activeDayCoverSlotPackageId,
        PreferKey.activeNightCoverSlotPackageId
    )

    private data class LocalUiStateSnapshot(
        val artPackageIds: Map<String, String>,
        val coverSlotPackageIds: Map<String, String>,
        val uiFontPaths: Map<String, String>,
        val backgroundPaths: Map<String, String>
    )

    /** 恢复成功后书架需要重建；由书架界面 onResume 时消费，避免事件在主界面后台时被丢弃。 */
    @Volatile
    private var bookshelfRebuildPending = false

    fun consumeBookshelfRebuildPending(): Boolean {
        return bookshelfRebuildPending.also { bookshelfRebuildPending = false }
    }

    suspend fun restore(context: Context, uri: Uri, assetsUris: List<Uri> = emptyList()) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            restoreLocked(Backup.backupPath) {
                extractUriToBackupPath(context, uri).getOrThrow()
                // 资源包（字体/背景图/头像）与主包解压到同一目录，恢复逻辑按条目前缀统一处理；
                // 单个资源包失败不阻断主包恢复。
                assetsUris.forEach { assetsUri ->
                    // 资源包追加解压：不清空目标目录，否则会把主包已恢复的书架/书源等全部删掉
                    runCatching {
                        extractUriToBackupPath(context, assetsUri, resetDestination = false)
                    }.onFailure {
                        AppLog.put("恢复资源包出错\n${it.localizedMessage}", it)
                    }
                }
            }
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onFailure {
            // 用户主动取消不是恢复出错：重新抛出让协程正常结束，避免误报「恢复备份出错\nnull」
            if (it is CancellationException) {
                throw it
            }
            appCtx.toastOnUi(appCtx.getString(R.string.svc_failed_restore_backup_n, it.localizedMessage))
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    private fun extractUriToBackupPath(
        context: Context,
        uri: Uri,
        resetDestination: Boolean = true
    ): Result<Unit> = runCatching {
        if (uri.isContentScheme()) {
            val tempArchive = File(context.cacheDir, "restore_${UUID.randomUUID()}.zip")
            try {
                DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use { input ->
                    BackupArchiveExtractor.copyToTemporaryFile(input, tempArchive)
                }
                BackupArchiveExtractor.extract(tempArchive, File(Backup.backupPath), resetDestination)
            } finally {
                tempArchive.delete()
            }
        } else {
            val filePath = uri.path
                ?: throw NoStackTraceException("无效的文件路径")
            BackupArchiveExtractor.extract(File(filePath), File(Backup.backupPath), resetDestination)
        }
    }

    suspend fun restoreLocked(path: String, prepare: (suspend () -> Unit)? = null) {
        // 与 Backup 共用同一把锁：备份的删建与恢复的解压操作同一目录，必须互斥。
        // 主包与资源包的下载解压也在锁内执行（prepare），避免自动备份的删建
        // 把已解压内容清掉。
        Backup.mutex.withLock {
            isRestoring = true
            try {
                prepare?.invoke()
                // 简单快照兑底：恢复前把将被覆盖的目标复制到临时快照，
                // 失败时尽力复制回去；无状态机、无原子发布。
                val snapshot = RestoreSnapshot.create(path)
                try {
                    restore(path) {
                        io.legado.app.help.config.pkg.UnifiedPackageRepository.invalidateCache()
                        snapshot.delete()
                        isRestoring = false
                    }
                    io.legado.app.help.config.pkg.UnifiedPackageRepository.invalidateCache()
                } catch (e: Throwable) {
                    snapshot.rollback(appCtx.getString(R.string.misc_restore_failed_2, e.localizedMessage))
                    io.legado.app.help.config.pkg.UnifiedPackageRepository.invalidateCache()
                    throw e
                } finally {
                    snapshot.delete()
                }
            } finally {
                isRestoring = false
            }
        }
    }

    private suspend fun restore(
        path: String,
        onBeforeRestart: (() -> Unit)? = null
    ) {
        val aes = BackupAES()
        // 「按备份覆盖」的本机快照必须在合并之前取：合并会把备份书并入本机记录、
        // 并可能插入新行，之后取会把新行算进来，导致本该删掉的本机旧记录被新行
        // 的身份键"顶替"而漏判。每次恢复都重置，避免上一次的残留被本次误用。
        localKeysBeforeMerge = if (BackupConfig.overwriteShelfOnRestore) {
            ShelfIdentity.keysOf(appDb.bookDao.all)
        } else {
            null
        }
        restoreBooks(path)
        fileToListT<Bookmark>(path, "bookmark.json")?.let {
            insertRestored(it) { items -> appDb.bookmarkDao.insert(*items) }
        }
        fileToListT<BookGroup>(path, "bookGroup.json")?.let {
            insertRestored(it) { items -> appDb.bookGroupDao.insert(*items) }
        }
        fileToListT<BookSource>(path, "bookSource.json")?.let {
            insertRestored(it) { items -> appDb.bookSourceDao.insert(*items) }
        } ?: run {
            val bookSourceFile = File(path, "bookSource.json")
            if (bookSourceFile.exists()) {
                val json = bookSourceFile.readText()
                ImportOldData.importOldSource(json)
            }
        }
        fileToListT<RssSource>(path, "rssSources.json")?.let {
            insertRestored(it) { items -> appDb.rssSourceDao.insert(*items) }
        }
        fileToListT<RssStar>(path, "rssStar.json")?.let {
            insertRestored(it) { items -> appDb.rssStarDao.insert(*items) }
        }
        fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
            insertRestored(it) { items -> appDb.replaceRuleDao.insert(*items) }
        }
        fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
            insertRestored(it) { items -> appDb.searchKeywordDao.insert(*items) }
        }
        fileToListT<RuleSub>(path, "sourceSub.json")?.let {
            insertRestored(it) { items -> appDb.ruleSubDao.insert(*items) }
        }
        fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
            insertRestored(it) { items -> appDb.txtTocRuleDao.insert(*items) }
        }
        fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
            insertRestored(it) { items -> appDb.httpTTSDao.insert(*items) }
        }
        fileToListT<DictRule>(path, "dictRule.json")?.let {
            insertRestored(it) { items -> appDb.dictRuleDao.insert(*items) }
        }
        fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
            // 先删后插必须在同一事务：快照回滚只覆盖文件不覆盖数据库，
            // 中途失败会让本机全部键盘辅助配置永久丢失
            appDb.withTransaction {
                appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
                insertRestored(it) { items -> appDb.keyboardAssistsDao.insert(*items) }
            }
        }
        restoreAutoTasks(path)
        fileToListT<ReadRecord>(path, "readRecord.json")?.let {
            it.forEach { readRecord ->
                //判断是不是本机记录
                if (readRecord.deviceId != androidId) {
                    appDb.readRecordDao.insert(readRecord)
                } else {
                    val time = appDb.readRecordDao
                        .getReadTime(readRecord.deviceId, readRecord.bookName)
                    if (time == null || time < readRecord.readTime) {
                        appDb.readRecordDao.insert(readRecord)
                    }
                }
            }
        }
        restoreBookCharacters(path)
        restoreBookCollections(path)
        restoreBookIllustrations(path)
        restoreParagraphRules(path)
        val readMenuButtonIdMap = restoreReadMenuCustomButtons(path)
        restoreHomepageModules(path)
        restoreReadAloudBgm(path)
        restoreReadAloudSpeakerGroups(path)
        restoreReadRecordDaily(path)
        fileToListT<ReadRecentBook>(path, "readRecentBooks.json")?.let {
            insertRestored(it) { items -> appDb.readRecentBookDao.insert(*items) }
        }
        fileToListT<RssReadRecord>(path, "rssReadRecords.json")?.let {
            insertRestored(it) { items -> appDb.rssReadRecordDao.insertRecord(*items) }
        }
        restorePendingReviewComments(path)
        restoreAiMemory(path)
        restoreAiImageGallery(path)
        restoreCoversFromBackup(path)
        restoreEncryptedJsonList<Server>(path, "servers.json", aes, "恢复服务器配置出错") { items ->
            appDb.serverDao.insert(*items)
        }
        restoreEncryptedJsonList<Cookie>(path, "cookies.json", aes, "恢复 Cookie 出错") { items ->
            appDb.cookieDao.insert(*items)
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.isFile
        }?.let { ruleFile ->
            runCatching {
                require(ruleFile.length() <= DirectLinkUpload.MAX_RULE_JSON_BYTES.toLong()) {
                    appCtx.getString(R.string.svc_direct_link_upload_rule_backup)
                }
                ruleFile.readText(Charsets.UTF_8)
            }
                .mapCatching { json -> DirectLinkUpload.importRule(json).getOrThrow() }
                .onFailure { error ->
                    AppLog.put(
                        appCtx.getString(R.string.svc_direct_link_upload_rule_backup_2) +
                            (error.localizedMessage ?: "未知错误"),
                        error
                    )
                    appCtx.toastOnUi(appCtx.getString(R.string.svc_direct_link_upload_rule_backup_3))
                }
        }
        //恢复主题配置
        restoreFile(File(path, ThemeConfig.configFileName), File(ThemeConfig.configFilePath)) {
            ThemeConfig.upConfig()
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        restoreFile(
            File(path, CoverHtmlTemplateConfig.configFileName),
            File(CoverHtmlTemplateConfig.configFilePath)
        ) {
            CoverHtmlTemplateConfig.reload()
        }
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, EpubReaderTemplateStore.fileName).takeIf { it.isFile }?.let {
                EpubReaderTemplateStore.restoreJson(it.readText(Charsets.UTF_8))
            }
            restoreFile(File(path, ReadBookConfig.configFileName), File(ReadBookConfig.configFilePath)) {
                ReadBookConfig.initConfigs()
            }
            restoreFile(File(path, ReadBookConfig.shareConfigFileName), File(ReadBookConfig.shareConfigFilePath)) {
                ReadBookConfig.initShareConfig()
            }
        }
        if (!BackupConfig.ignoreReadConfig) {
            listOf(ReadBookConfig.epubConfigFileName, ReadBookConfig.epubShareConfigFileName).forEach { name ->
                restoreFile(File(path, name), File(appCtx.filesDir, name))
            }
            ReadBookConfig.initConfigs()
            ReadBookConfig.initShareConfig()
        }
        val unifiedBackupDir = File(path, Backup.unifiedPackagesDirName)
        val backupHasArtDir = File(unifiedBackupDir, UiPackageBackup.UNIFIED_ART).isDirectory
        val backupHasCoverSlotDir = File(unifiedBackupDir, UiPackageBackup.UNIFIED_COVER_SLOT).isDirectory
        val backupHasFontsDir = File(path, Backup.fontsDirName).isDirectory
        val localUiSnapshot = captureLocalUiStateSnapshot()
        restoreBackgroundAssets(path)
        restoreFonts(path)
        restoreBookFiles(path)
        runCatching {
            // 兼容带书籍缓存索引的备份包（Legado_Max 语义）：恢复书架与章节目录
            BookCacheZip.restoreIndexedBookCache(File(path))
        }.onFailure {
            AppLog.put("恢复书籍缓存索引出错\n${it.localizedMessage}", it)
        }
        restoreThemePackages(path)
        restoreNavigationIcons(path)
        restoreTopBarPackages(path)
        restoreCoverCollections(path)
        restoreAppearanceKits(path)
        restoreUnifiedPackages(path)
        restoreVisualResourcePackages(path)
        restoreSourceRuntime(path, aes)
        // 直接以文件形式读取 config.xml：SharedPreferences 进程级缓存只在首次
        // 创建时读盘，本机此前若做过备份，缓存里还是本机上次备份的旧值，
        // 直接读文件才能拿到刚解压出来的备份内容
        BackupPrefsFile.read(File(path, "config.xml"))?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key) &&
                    key != PreferKey.advancedTitleLottieJson &&
                    key != PreferKey.advancedTitleLottiePath
                ) {
                    when (key) {
                        PreferKey.webDavPassword, PreferKey.s3SecretKey, PreferKey.s3SessionToken,
                        PreferKey.webServiceToken, PreferKey.aiTavilyApiKey -> {
                            // 解密失败说明本机加密密码与备份端不同：跳过该键，
                            // 绝不能把密文当明文写入造成永久认证失败。
                            // 经 SecurePrefs.write 落盘会用本机 KeyStore 重新加密
                            val decrypted = value?.toString()?.let { plain ->
                                kotlin.runCatching { aes.decryptStr(plain) }.getOrNull()
                            }
                            if (decrypted != null) {
                                SecurePrefs.write(key, decrypted)
                            }
                        }

                        PreferKey.s3Containers -> {
                            edit.putString(key, S3ContainerManager.restoreEncryptedBackupJson(value.toString(), aes))
                        }

                        PreferKey.aiProviderList, PreferKey.aiImageProviderList, PreferKey.aiMcpServerList -> {
                            val rawJson = value?.toString().orEmpty()
                            if (rawJson.isNotEmpty()) {
                                edit.putString(key, decodeAiSecretsFromBackup(key, rawJson, aes))
                            }
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }
            }
            edit.commit()
        }
        normalizeBackgroundPrefs(path, localUiSnapshot.backgroundPaths)
        normalizeUiFontPathPrefs(backupHasFontsDir, localUiSnapshot.uiFontPaths)
        normalizeReadMenuButtonLayoutPrefs(readMenuButtonIdMap)
        reconcileRestoredUiPackageIds(
            backupHasArtDir = backupHasArtDir,
            backupHasCoverSlotDir = backupHasCoverSlotDir,
            localSnapshot = localUiSnapshot
        )
        // The restored preferences can point at a different font folder, and any cached
        // lookup from before the restore would now be answering for the wrong one.
        io.legado.app.help.AppFont.invalidateFontList()
        ReaderDataRepair.repairAfterRestore()
        refreshWebDavAfterRestore()
        restoreBookCovers()
        restoreReadingBackgrounds(path)
        // restoreBookCovers/restoreReadingBackgrounds 恢复的资源可能再次引入
        // 待修复状态（封面引用/背景文件），故在资源恢复完成后再次执行修复
        ReaderDataRepair.repairAfterRestore()
        restoreAppliedUiPackages()
        // 直接读文件（同 config.xml，绕开 SharedPreferences 进程缓存）；
        // commit 同步落盘：随后 1.5 秒即整进程重启，apply 异步写可能丢失
        restoreSharedPrefsFile(File(path, "videoConfig.xml"), VIDEO_PREF_NAME)
        restoreSharedPrefsFile(File(path, Backup.sourceConfigFileName), Backup.sourceConfigPrefName)
        AutoTask.refreshSchedule()
        // ⚠️ 「按备份覆盖」的删书放在所有可能失败的步骤之后：若在前面执行，
        // 后续步骤失败触发回滚时书已被永久删除——「看起来恢复失败，书其实已经没了」。
        overwriteShelfIfNeeded(path)
        bookshelfRebuildPending = true
        appCtx.toastOnUi(R.string.restore_success)
        withContext(IO) {
            runCatching {
                Glide.get(appCtx).clearDiskCache()
            }.onFailure {
                AppLog.put("清理 Glide 磁盘缓存出错\n${it.localizedMessage}", it)
            }
        }
        LocalConfig.lastAssetsFingerprint = ""
        LocalConfig.lastAssetsTarget = ""
        LocalConfig.lastShelfKeys = ""
        LocalConfig.lastBackup = System.currentTimeMillis()
        LocalConfig.edit().commit()
        withContext(Main) {
            delay(1500)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            onBeforeRestart?.invoke()
            // 恢复覆盖了偏好/主题/书架等全局状态。此处不能走原 applyDayNight 的
            // RECREATE 风暴：多 Activity 同时 recreate 会让 Compose retained store
            // 条目跨 Activity 复用，并与 setDefaultNightMode 触发的 recreate 二次
            // 交错，在部分设备（vivo / Android 16 实测）击穿 "ManagedValuesStore
            // tried to enter composition twice" 断言。
            // 改为整应用重启，恢复后的状态在新进程全新加载。
            appCtx.restart()
        }
    }

    /**
     * 「恢复时按备份覆盖书架」：把本机比备份多出来的**在线书**删掉。
     *
     * 默认关闭（[BackupConfig.overwriteShelfOnRestore]）；关闭时本方法直接返回，
     * 恢复维持既有的**增量合并**语义（只加不删）。
     *
     * ## 三道守卫（缺一即可能删光书架）
     *
     * ⚠️ **① `bookshelf.json` 不存在 ⇒ 拒绝删除。**
     * 用户未勾选「书架」恢复（或备份本身不含书架）时，把「备份在线书集合」
     * 当成空集会导致 `全部 − 空 = 全部` ⇒ **删光在线书架**。
     *
     * ⚠️ **② 解析结果为空 ⇒ 拒绝删除。**
     * 读不出来不等于「备份里没有」。
     *
     * ⚠️ **③ 两侧集合都用 [ShelfIdentity] 过滤离线书**，离线书恒不在删除集合内。
     * 尤其是本地书——`Book.delete()` 对本地书会连带处理本地文件。
     */
    private suspend fun overwriteShelfIfNeeded(path: String) {
        if (!BackupConfig.overwriteShelfOnRestore) {
            return
        }
        val localKeys = localKeysBeforeMerge ?: return
        // 守卫①：文件不存在（用户未勾选「书架」恢复，或备份本身不含书架）
        val shelfFile = File(path, "bookshelf.json")
        if (!shelfFile.exists()) {
            AppLog.put("按备份覆盖已跳过：备份中没有 bookshelf.json，未删除任何书籍")
            return
        }
        // 守卫②：解析不出任何书（读不出来不等于「备份里没有」）
        val backupBooks = buildList {
            Restore.forEachBookBackup(shelfFile) { _, book -> add(book) }
        }
        if (backupBooks.isEmpty()) {
            AppLog.put("按备份覆盖已跳过：bookshelf.json 未解析出任何书籍，未删除任何书籍")
            return
        }
        val backupKeys = ShelfIdentity.keysOf(backupBooks)
        val toDelete = appDb.bookDao.all.filter { book ->
            // 守卫③：离线书一律排除（keyOf 为 null），绝不进入删除集合
            val key = ShelfIdentity.keyOf(book) ?: return@filter false
            key in localKeys && key !in backupKeys
        }
        if (toDelete.isEmpty()) {
            return
        }
        // 删除不可撤销，先把将被删项落盘：把「完全不可逆」降级为「可手工找回」。
        writeOverwriteManifest(toDelete)
        toDelete.forEach { book ->
            appDb.bookDao.getBook(book.bookUrl)?.delete()
        }
        AppLog.put("按备份覆盖：已删除 ${toDelete.size} 本备份中不存在的在线书籍")
        appCtx.toastOnUi(appCtx.getString(R.string.restore_overwrite_done, toDelete.size))
    }

    /**
     * 落一份将被删书籍的清单（完整 `Book` JSON，含 bookUrl/origin/进度等可重建字段），
     * 返回路径；失败不阻断删除但会记日志。
     *
     * ⚠️ 落点 `filesDir/` **本身**、不在 `backupPath` 内，
     * 故 `Backup` 的 `FileUtils.delete(backupPath)` 删不到它。
     */
    private fun writeOverwriteManifest(books: List<Book>): String? {
        return runCatching {
            val file = File(appCtx.filesDir, "deleted-books-${System.currentTimeMillis()}.json")
            file.writeText(GSON.toJson(books))
            file.absolutePath
        }.onFailure {
            AppLog.put("按备份覆盖清单落盘失败\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun restoreBookCharacters(path: String) {
        val characters = fileToListT<BookCharacter>(path, Backup.bookCharactersFileName) ?: return
        kotlin.runCatching {
            restoreBookCharacterAvatars(path)
            val idMap = hashMapOf<Long, Long>()
            characters.forEach { character ->
                val oldId = character.id
                val normalized = character.copy(
                    avatar = restoreBookCharacterAvatarPath(character.avatar),
                    updatedAt = character.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                )
                val exists = appDb.bookCharacterDao.getCharacter(normalized.bookUrl, normalized.name)
                val newId = if (exists != null) {
                    appDb.bookCharacterDao.updateCharacter(normalized.copy(id = exists.id))
                    exists.id
                } else {
                    appDb.bookCharacterDao.insertCharacter(normalized.copy(id = 0L))
                }
                if (oldId > 0L) {
                    idMap[oldId] = newId
                }
            }
            fileToListT<BookCharacterRelation>(path, Backup.bookCharacterRelationsFileName)
                ?.forEach { relation ->
                    val fromId = idMap[relation.fromCharacterId] ?: return@forEach
                    val toId = idMap[relation.toCharacterId] ?: return@forEach
                    val saving = relation.copy(
                        id = 0L,
                        fromCharacterId = fromId,
                        toCharacterId = toId,
                        relationName = relation.relationName.ifBlank { appCtx.getString(R.string.bchar_relation) },
                        updatedAt = relation.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                    val exists = appDb.bookCharacterDao.getRelation(
                        saving.bookUrl,
                        saving.fromCharacterId,
                        saving.toCharacterId,
                        saving.relationName
                    )
                    if (exists != null) {
                        appDb.bookCharacterDao.updateRelation(saving.copy(id = exists.id))
                    } else {
                        appDb.bookCharacterDao.insertRelation(saving)
                    }
                }
        }.onFailure {
            AppLog.put("恢复角色资料出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreAutoTasks(path: String) {
        val file = File(path, "autoTask.json")
        if (!file.isFile) return
        runCatching {
            val rules = AutoTaskImport.parse(file.readText(Charsets.UTF_8)).getOrThrow()
            if (rules.isNotEmpty()) {
                AutoTask.upsert(rules)
            }
        }.onFailure {
            AppLog.put("恢复定时任务出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi(appCtx.getString(R.string.svc_failed_restore_scheduled_tasks_current))
        }
    }

    /**
     * 恢复备份包内 covers/ 目录：复制到应用 externalFiles/covers/，
     * 并修正指向旧设备绝对路径的分组封面与书籍本地/自定义封面路径。
     */
    private fun restoreCoversFromBackup(path: String) {
        val sourceDir = File(path, "covers")
        if (!sourceDir.isDirectory) return
        val coversDir = appCtx.externalFiles.getFile("covers")
        runCatching {
            copyDir(sourceDir, coversDir)
        }.onFailure {
            AppLog.put("恢复封面目录出错\n${it.localizedMessage}", it)
            return
        }
        runCatching {
            appDb.bookGroupDao.all.forEach { group ->
                val cover = group.cover ?: return@forEach
                if (cover.isBlank()
                    || cover.startsWith("http", true)
                    || cover.isContentScheme()
                ) return@forEach
                if (File(cover).exists()) return@forEach
                val name = File(cover).name.takeIf { it.isNotBlank() } ?: return@forEach
                val restored = File(coversDir, name)
                if (restored.isFile) {
                    appDb.bookGroupDao.update(group.copy(cover = restored.absolutePath))
                }
            }
        }.onFailure {
            AppLog.put("修正分组封面路径出错\n${it.localizedMessage}", it)
        }
        runCatching {
            AppCloudStorage.normalizeBookCoverPaths()
        }.onFailure {
            AppLog.put("修正书籍封面路径出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreBookCharacterAvatars(path: String) {
        val sourceDir = File(path, Backup.bookCharacterAvatarsDirName)
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = appCtx.externalFiles.getFile("bookCharacters", "avatars")
        copyDir(sourceDir, targetDir)
    }

    private fun restoreBookCharacterAvatarPath(avatar: String): String {
        if (avatar.isBlank() || avatar.startsWith("http", ignoreCase = true)) {
            return avatar
        }
        val fileName = File(avatar).name.takeIf { it.isNotBlank() } ?: return avatar
        val restoredFile = appCtx.externalFiles.getFile("bookCharacters", "avatars", fileName)
        return restoredFile.takeIf { it.exists() }?.absolutePath ?: avatar
    }

    private fun restoreSourceRuntime(path: String, aes: BackupAES) {
        if (BackupConfig.ignoreSourceRuntime) return
        File(path, "sourceRuntime.json").takeIf { it.exists() }?.runCatching {
            val raw = readText()
            val json = if (raw.trimStart().startsWith("{")) {
                raw
            } else {
                aes.decryptStr(raw)
            }
            val backup = GSON.fromJsonObject<SourceRuntimeBackup>(json).getOrNull()
                ?: return@runCatching
            backup.items.forEach { item ->
                val source = when (item.sourceType) {
                    "bookSource" -> appDb.bookSourceDao.getBookSource(item.sourceKey)
                    "rssSource" -> appDb.rssSourceDao.getByKey(item.sourceKey)
                    "httpTts" -> item.sourceKey.substringAfter("httpTts:").toLongOrNull()
                        ?.let { appDb.httpTTSDao.get(it) }
                    else -> null
                } ?: return@forEach
                restoreSourceRuntimeItem(source, item)
            }
        }?.onFailure {
            AppLog.put("恢复源运行期数据出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreSourceRuntimeItem(source: BaseSource, item: SourceRuntimeBackupItem) {
        item.loginInfo?.takeIf { it.isNotEmpty() }?.let {
            source.putLoginInfo(GSON.toJson(it))
        }
        source.putVariable(item.sourceVariable?.takeIf { it.isNotBlank() })
        item.sourceValues.forEach { (key, value) ->
            source.put(key, value)
        }
    }

    private inline fun <reified T> insertRestored(
        items: List<T>,
        insert: (Array<T>) -> Unit
    ) {
        items.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { chunk ->
            insert(chunk.toTypedArray())
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi(appCtx.getString(R.string.misc_nfailed_read_file_n, fileName, e.localizedMessage))
        }
        return null
    }

    private fun restoreBooks(path: String) {
        val fileName = "bookshelf.json"
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                val pendingNewBooks = ArrayList<Book>(RESTORE_INSERT_BATCH_SIZE)
                val ignoreLocalBook = BackupConfig.ignoreLocalBook
                var restoredCount = 0
                fun flushNewBooks() {
                    if (pendingNewBooks.isEmpty()) return
                    appDb.bookDao.insert(*pendingNewBooks.toTypedArray())
                    pendingNewBooks.clear()
                }
                forEachBookBackup(file) { index, book ->
                    book.upType()
                    if (book.isLocal) {
                        book.coverUrl = LocalBook.getCoverPath(book)
                        if (ignoreLocalBook) return@forEachBookBackup
                    }
                    if (appDb.bookDao.has(book.bookUrl)) {
                        try {
                            appDb.bookDao.update(book)
                        } catch (_: SQLiteConstraintException) {
                            appDb.bookDao.insert(book)
                        }
                    } else {
                        pendingNewBooks.add(book)
                        if (pendingNewBooks.size >= RESTORE_INSERT_BATCH_SIZE) {
                            flushNewBooks()
                        }
                    }
                    restoredCount++
                }
                flushNewBooks()
                LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 $restoredCount")
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi(appCtx.getString(R.string.misc_nfailed_read_file_n, fileName, e.localizedMessage))
        }
    }

    internal fun forEachBookBackup(
        file: File,
        onError: (Int, Throwable) -> Unit = { index, error ->
            AppLog.put(
                appCtx.getString(R.string.misc_bookshelf_json_item_could_not, index + 1, error.localizedMessage),
                error
            )
        },
        onBook: (Int, Book) -> Unit
    ) {
        file.reader(Charsets.UTF_8).buffered().use { input ->
            JsonReader(input).use { reader ->
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    val currentIndex = index++
                    val element = JsonParser.parseReader(reader)
                    val bookJson = element.deepCopy()
                    sanitizeBookJson(bookJson)
                    runCatching {
                        GSON.fromJson(bookJson, Book::class.java)
                    }.onSuccess { book ->
                        if (book != null) onBook(currentIndex, book)
                    }.onFailure { error ->
                        onError(currentIndex, error)
                    }
                }
                reader.endArray()
            }
        }
    }

    private fun sanitizeBookJson(element: JsonElement) {
        if (!element.isJsonObject) {
            return
        }
        val bookJson = element.asJsonObject
        val readConfig = bookJson.get("readConfig") ?: return
        if (!readConfig.isJsonObject) {
            bookJson.remove("readConfig")
            return
        }
        sanitizeReadConfigJson(readConfig.asJsonObject)
    }

    private fun sanitizeReadConfigJson(readConfig: JsonObject) {
        val startDate = readConfig.get("startDate") ?: return
        if (startDate.isJsonPrimitive && startDate.asJsonPrimitive.isString) {
            val legacyStartDate = runCatching {
                JsonParser.parseString(startDate.asString)
            }.getOrNull()
            if (legacyStartDate?.isJsonObject == true && legacyStartDate.asJsonObject.isValidLocalDateJson()) {
                readConfig.add("startDate", legacyStartDate)
            } else {
                readConfig.remove("startDate")
            }
        } else if (startDate.isJsonObject && !startDate.asJsonObject.isValidLocalDateJson()) {
            readConfig.remove("startDate")
        }
    }

    private fun JsonObject.isValidLocalDateJson(): Boolean {
        val year = getIntOrNull("year") ?: return true
        val month = getIntOrNull("month") ?: return true
        val day = getIntOrNull("day") ?: return true
        return year > 0 && month in 1..12 && day in 1..31
    }

    private fun JsonObject.getIntOrNull(name: String): Int? {
        val value = get(name)?.takeIf { it.isJsonPrimitive } ?: return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun restoreBackgroundAssets(path: String) {
        backgroundAssetDirNames.forEach { dirName ->
            restorePackageDirectory(File(path, dirName), appCtx.externalFiles.getFile(dirName))
        }
    }

    private suspend fun restoreReadingBackgrounds(path: String) {
        val bgDir = appCtx.externalFiles.getFile("bg").apply { mkdirs() }
        val backupBgDir = File(path, "bg")
        if (backupBgDir.isDirectory) {
            runCatching {
                copyDir(backupBgDir, bgDir)
            }.onFailure {
                AppLog.put("恢复正文背景图片出错\n${it.localizedMessage}", it)
            }
        }
        val names = linkedSetOf<String>()
        fun collect(config: ReadBookConfig.Config) {
            readConfigBgFileName(config.bgType, config.bgStr)?.let(names::add)
            readConfigBgFileName(config.bgTypeNight, config.bgStrNight)?.let(names::add)
            readConfigBgFileName(config.bgTypeEInk, config.bgStrEInk)?.let(names::add)
        }
        ReadBookConfig.allLayoutConfigs().forEach(::collect)
        if (names.isEmpty()) return
        val restored = linkedMapOf<String, File>()
        val missingNames = arrayListOf<String>()
        names.forEach { fileName ->
            val localFile = bgDir.getFile(fileName)
            if (localFile.isFile) {
                restored[fileName] = localFile
            } else {
                missingNames.add(fileName)
            }
        }
        if (missingNames.isNotEmpty()) {
            restored.putAll(AppCloudStorage.downBgs(missingNames))
        }
        if (restored.isEmpty()) return
        var changed = false
        fun normalize(config: ReadBookConfig.Config) {
            if (normalizeReadConfigBg(config, restored)) {
                changed = true
            }
        }
        ReadBookConfig.allLayoutConfigs().forEach(::normalize)
        if (!changed) return
        runCatching {
            ReadBookConfig.saveLayoutFiles()
        }.onFailure {
            AppLog.put("恢复正文背景图片配置出错\n${it.localizedMessage}", it)
        }
    }

    private fun normalizeReadConfigBg(
        config: ReadBookConfig.Config,
        restored: Map<String, File>
    ): Boolean {
        var changed = false
        fun restorePath(bgType: Int, bgStr: String): String? {
            val fileName = readConfigBgFileName(bgType, bgStr) ?: return null
            return restored[fileName]?.absolutePath
        }
        restorePath(config.bgType, config.bgStr)?.let {
            if (config.bgStr != it) {
                config.bgStr = it
                changed = true
            }
        }
        restorePath(config.bgTypeNight, config.bgStrNight)?.let {
            if (config.bgStrNight != it) {
                config.bgStrNight = it
                changed = true
            }
        }
        restorePath(config.bgTypeEInk, config.bgStrEInk)?.let {
            if (config.bgStrEInk != it) {
                config.bgStrEInk = it
                changed = true
            }
        }
        return changed
    }

    private fun readConfigBgFileName(bgType: Int, bgStr: String?): String? {
        if (bgType != 2) return null
        val value = bgStr?.trim().orEmpty()
        if (value.isBlank() || value.startsWith("http", ignoreCase = true)) return null
        return File(value).name.takeIf { it.isNotBlank() }
    }

    /**
     * 恢复阅读字体：备份 fonts/ 目录内的字体文件复制到应用私有 font 目录，
     * 使 readConfig 中 @font:名 引用重新命中；
     * 旧数据的绝对路径引用若已失效且同名文件可用，改写为 @font:引用。
     */
    private fun restoreFonts(path: String) {
        val sourceDir = File(path, Backup.fontsDirName)
        if (!sourceDir.isDirectory) return
        val fontFiles = sourceDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (fontFiles.isEmpty()) return
        runCatching {
            val targetDir = File(FileUtils.getPath(appCtx.externalFiles, "font"))
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            fontFiles.forEach { file ->
                file.copyTo(File(targetDir, file.name), overwrite = true)
            }
            io.legado.app.help.AppFont.invalidateFontList()
        }.onFailure {
            AppLog.put("恢复阅读字体出错\n${it.localizedMessage}", it)
            return
        }
        normalizeReaderFontRefs()
    }

    private fun normalizeReaderFontRefs() {
        val fontDir = File(FileUtils.getPath(appCtx.externalFiles, "font"))
        var changed = false
        ReadBookConfig.allLayoutConfigs().forEach { config ->
            val ref = config.textFont.trim()
            if (!ref.startsWith("/") || File(ref).isFile) return@forEach
            val name = File(ref).name.takeIf { it.isNotBlank() } ?: return@forEach
            if (File(fontDir, name).isFile) {
                config.textFont = "@font:$name"
                changed = true
            }
        }
        if (!changed) return
        runCatching {
            ReadBookConfig.saveLayoutFiles()
        }.onFailure {
            AppLog.put("修正阅读字体引用出错\n${it.localizedMessage}", it)
        }
    }

    /**
     * 恢复书籍文件（已下载书籍本体与正文缓存）：备份里有就还原，
     * 覆盖本地同名文件。
     */
    private fun restoreBookFiles(path: String) {
        val sourceDir = File(path, Backup.bookCacheBackupDirName)
        if (!sourceDir.isDirectory) return
        runCatching {
            copyDir(sourceDir, File(BookHelp.cachePath))
            AppLog.put("恢复书籍文件完成")
        }.onFailure {
            AppLog.put("恢复书籍文件出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreThemePackages(path: String) {
        restorePackageDirectory(File(path, Backup.themePackagesDirName), ThemePackageManager.rootDir)
    }

    private fun restoreNavigationIcons(path: String) {
        restorePackageDirectory(File(path, Backup.navigationBarPackagesDirName), NavigationBarIconConfig.rootDir)
    }

    private fun restoreTopBarPackages(path: String) {
        restorePackageDirectory(File(path, Backup.topBarPackagesDirName), TopBarConfig.rootDir)
    }

    private fun restoreVisualResourcePackages(path: String) {
        io.legado.app.help.reader.ReaderAssets.store.restoreFrom(
            File(path, io.legado.app.help.reader.ReaderAssets.BACKUP_DIR)
        )
        io.legado.app.help.book.highlight.HighlightRules.store.restoreFrom(
            File(path, io.legado.app.help.book.highlight.HighlightRules.BACKUP_DIR)
        )
        File(path, Backup.advancedTitlePackagesDirName)
            .takeIf { it.isDirectory }
            ?.let { sourceDir ->
                AdvancedTitlePackageManager.restorePackagesFrom(sourceDir)
            }
        restorePackageDirectory(
            sourceDir = File(path, Backup.bubblePackagesDirName),
            targetDir = BubblePackageManager.rootDir
        ) {
            BubblePackageManager.invalidateCurrentEntry()
        }
    }

    internal fun restorePackageDirectory(
        sourceDir: File,
        targetDir: File,
        afterRestore: () -> Unit = {}
    ) {
        if (!sourceDir.exists()) return
        // Complete and verify the copy before touching the live directory. Propagate any
        // install failure so restoreLocked can roll back the other journaled resources too.
        AdvancedTitleDirectoryRestorer(
            mutationLock = this,
            expectedTargetParent = requireNotNull(targetDir.parentFile),
            verifyInstalledRoot = { AdvancedTitleDirectoryTree.verifyEquivalent(sourceDir, it) },
            invalidateCache = afterRestore
        ).restore(sourceDir, targetDir)
    }

    /** The same non-destructive replacement is used when restoring journal snapshots. */
    internal fun restoreFile(source: File, requestedTarget: File, afterRestore: () -> Unit = {}) {
        if (!source.exists()) return
        require(source.isFile) { "备份配置文件无效：${source.name}" }
        val parent = requireNotNull(requestedTarget.parentFile).canonicalFile
        check(parent.isDirectory || parent.mkdirs()) { "无法创建恢复目录" }
        val target = AdvancedTitleDirectoryTree.resolveDirectChild(requestedTarget, parent)
        require(!target.exists() || target.isFile) { "恢复目标不是文件：${target.name}" }
        AdvancedTitleDirectoryTree.requireDisjoint(source, target)
        val staging = File(parent, ".${target.name}.staging-${UUID.randomUUID()}")
        val backup = File(parent, ".${target.name}.backup-${UUID.randomUUID()}")
        check(staging.createNewFile()) { "无法创建恢复临时文件" }
        try {
            val copied = FileOutputStream(staging).use { output ->
                val count = source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
                count
            }
            check(copied == source.length() && staging.length() == copied) { "备份配置文件复制不完整" }
            BubbleDirectoryTransaction().install(target, staging, backup) { afterRestore() }
            if (!AdvancedTitleDirectoryRestorer.cleanupRestoreArtifacts(target)) {
                runCatching { AppLog.put("无法清理已恢复配置的临时文件：${target.name}") }
            }
        } finally {
            staging.delete()
        }
    }

    private suspend fun refreshWebDavAfterRestore() {
        kotlin.runCatching {
            AppCloudStorage.upConfig()
        }.onFailure {
            AppLog.put("refresh WebDAV after restore failed\n${it.localizedMessage}", it)
        }
    }

    private suspend fun restoreBookCovers() {
        if (!AppConfig.webDavBackupCover) return
        if (!AppCloudStorage.hasMissingBookCovers()) return
        runCatching {
            AppCloudStorage.downBookCovers()
        }.onFailure {
            AppLog.put("恢复书架封面出错\n${it.localizedMessage}", it)
        }
    }

    internal fun decodeAiSecretsFromBackup(key: String, rawJson: String, aes: BackupAES): String {
        if (rawJson.isBlank()) return rawJson
        return runCatching {
            when (key) {
                PreferKey.aiProviderList -> {
                    val list = GSON.fromJsonArray<AiProviderConfig>(rawJson).getOrThrow()
                    GSON.toJson(list.map { provider ->
                        provider.copy(
                            apiKey = decodeAiSecretField(provider.apiKey, aes),
                            headers = provider.headers?.let { decodeAiSecretField(it, aes) }
                        )
                    })
                }

                PreferKey.aiImageProviderList -> {
                    val list = GSON.fromJsonArray<AiImageProviderConfig>(rawJson).getOrThrow()
                    GSON.toJson(list.map { provider ->
                        provider.copy(
                            apiKey = decodeAiSecretField(provider.apiKey, aes),
                            headers = decodeAiSecretField(provider.headers, aes)
                        )
                    })
                }

                PreferKey.aiMcpServerList -> {
                    val list = GSON.fromJsonArray<AiMcpServerConfig>(rawJson).getOrThrow()
                    GSON.toJson(list.map { server ->
                        server.copy(apiKey = decodeAiSecretField(server.apiKey, aes))
                    })
                }

                else -> rawJson
            }
        }.getOrElse {
            AppLog.put("恢复解密 AI 凭据失败($key)，保留原值\n${it.localizedMessage}", it)
            rawJson
        }
    }

    private fun decodeAiSecretField(raw: String, aes: BackupAES): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith(Backup.BACKUP_AI_SECRET_PREFIX)) {
            val payload = raw.removePrefix(Backup.BACKUP_AI_SECRET_PREFIX)
            val plain = runCatching { aes.decryptStr(payload) }.getOrNull()
            return if (plain.isNullOrBlank()) "" else AiSecretCipher.encrypt(plain)
        }
        if (raw.startsWith("enc:v1:")) {
            val plain = AiSecretCipher.decrypt(raw)
            return if (plain.isBlank()) "" else AiSecretCipher.encrypt(plain)
        }
        return AiSecretCipher.encrypt(raw)
    }

    private suspend fun restoreAppliedUiPackages() {
        val useLegacy = appCtx.getPrefBoolean(PreferKey.useLegacyPackageManager, false)
        if (useLegacy) {
            kotlin.runCatching {
                ThemePackageManager.restoreAppliedThemes(appCtx)
            }.onFailure {
                AppLog.put("恢复主题包出错\n${it.localizedMessage}", it)
            }
        }
        listOf(false, true).forEach { isNight ->
            kotlin.runCatching { TopBarConfig.restoreApplied(isNight) }.onFailure {
                AppLog.put("恢复顶栏包出错\n${it.localizedMessage}", it)
            }
            kotlin.runCatching { NavigationBarIconConfig.restoreApplied(isNight) }.onFailure {
                AppLog.put("恢复底栏包出错\n${it.localizedMessage}", it)
            }
        }
        if (!useLegacy) {
            kotlin.runCatching {
                io.legado.app.help.config.pkg.UnifiedPackageRepository.reapplyActiveUnifiedPackagesAfterRestore(appCtx)
            }.onFailure {
                AppLog.put("恢复统一外观包出错\n${it.localizedMessage}", it)
            }
        }
        // 不再发 RECREATE 事件：恢复成功后统一重启应用（见 restore 尾部），
        // 顶栏/底栏包在新进程加载；RECREATE 会让多 Activity 同时重建，
        // 触发 Compose retained store 条目跨 Activity 复用竞态（见尾部注释）。
        postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
        postEvent(EventBus.NAVIGATION_BAR_CHANGED, AppConfig.isNightTheme)
    }

    private fun restoreCoverCollections(path: String) {
        val sourceDir = File(path, Backup.coverCollectionsDirName)
        restorePackageDirectory(sourceDir, CoverCollectionManager.rootDir)
        if (sourceDir.exists()) {
            runCatching {
                CoverCollectionManager.migrateAndReloadAfterRestore()
            }.onFailure {
                AppLog.put("迁移并重载封面集合出错\n${it.localizedMessage}", it)
            }
        }
    }

    private suspend fun restoreAppearanceKits(path: String) {
        restorePackageDirectory(File(path, Backup.appThemeKitsDirName), AppearanceKitManager.rootDir)
        runCatching {
            AppearanceKitManager.reconcileKitsAfterRestore()
        }.onFailure {
            AppLog.put("对账外观方案索引出错\n${it.localizedMessage}", it)
        }
    }

    /**
     * 恢复 V4 统一双包目录（`unifiedPackages`），并在恢复了任何 V1 旧包目录时
     * 自动触发幂等合成迁移，防止老用户恢复 V1 备份后落入静默黑洞。
     */
    private suspend fun restoreUnifiedPackages(path: String) {
        val backupUnifiedDir = File(path, Backup.unifiedPackagesDirName)
        if (backupUnifiedDir.isDirectory) {
            UiPackageBackup.unifiedKindDirNames.forEach { kindDirName ->
                val kindSourceDir = File(backupUnifiedDir, kindDirName)
                if (kindSourceDir.isDirectory) {
                    restorePackageDirectory(
                        kindSourceDir,
                        File(io.legado.app.help.config.pkg.UnifiedPackageRepository.rootDir, kindDirName)
                    )
                }
            }
        }
        io.legado.app.help.config.pkg.UnifiedPackageRepository.invalidateCache()
        if (backupUnifiedDir.isDirectory) {
            val edit = appCtx.defaultSharedPreferences.edit()
            val restored = UiPackageBackup.importUnifiedActivePrefs(
                sourceRoot = backupUnifiedDir,
                writeString = { key, value -> edit.putString(key, value) },
                writeBoolean = { key, value -> edit.putBoolean(key, value) },
                keyFilter = { key ->
                    !(BackupConfig.ignoreCoverConfig && key in BackupConfig.coverPrefKeys) &&
                        !(BackupConfig.ignoreThemeConfig && key in BackupConfig.artParamActivePrefKeys)
                }
            )
            if (restored) {
                edit.commit()
            }
        }
        val hasRestoredUnifiedDir = File(path, Backup.unifiedPackagesDirName).isDirectory
        val hasRestoredLegacyDirs = UiPackageBackup.legacyPackageDirNames.any { dirName ->
            File(path, dirName).isDirectory
        }
        if (hasRestoredLegacyDirs) {
            runCatching {
                io.legado.app.help.config.pkg.LegacyPackageMigrator.migrateLocalPackagesIfNeeded(
                    context = appCtx,
                    forceReconcile = true,
                    overwriteExisting = !hasRestoredUnifiedDir
                )
            }.onFailure {
                AppLog.put("恢复旧版外观包后合成迁移出错\n${it.localizedMessage}", it)
            }
        }
    }

    private suspend fun captureLocalUiStateSnapshot(): LocalUiStateSnapshot {
        val localArtIds = runCatching {
            UnifiedPackageRepository.listArtPackages().mapTo(hashSetOf()) { it.manifest.id }
        }.getOrDefault(emptySet())
        val localCoverSlotIds = runCatching {
            UnifiedPackageRepository.listCoverSlotPackages().mapTo(hashSetOf()) { it.manifest.id }
        }.getOrDefault(emptySet())

        val artPackageIds = buildMap {
            artActivePackagePrefKeys.forEach { key ->
                val value = appCtx.getPrefString(key)?.trim().orEmpty()
                if (value.isNotBlank() &&
                    hasLocalUnifiedPackage(UiPackageBackup.UNIFIED_ART, value, localArtIds)
                ) {
                    put(key, value)
                }
            }
        }
        val coverSlotPackageIds = buildMap {
            coverSlotActivePackagePrefKeys.forEach { key ->
                val value = appCtx.getPrefString(key)?.trim().orEmpty()
                if (value.isNotBlank() &&
                    hasLocalUnifiedPackage(UiPackageBackup.UNIFIED_COVER_SLOT, value, localCoverSlotIds)
                ) {
                    put(key, value)
                }
            }
        }
        val uiFontPaths = buildMap {
            uiFontPrefKeys.forEach { key ->
                val value = appCtx.getPrefString(key)?.trim().orEmpty()
                if (value.isNotBlank() && isValidLocalFontPref(value)) {
                    put(key, value)
                }
            }
        }
        val backgroundPaths = buildMap {
            backgroundAssetDirNames.forEach { key ->
                val value = appCtx.getPrefString(key)?.trim().orEmpty()
                if (value.isNotBlank() && isValidLocalBackgroundPref(value)) {
                    put(key, value)
                }
            }
        }
        return LocalUiStateSnapshot(
            artPackageIds = artPackageIds,
            coverSlotPackageIds = coverSlotPackageIds,
            uiFontPaths = uiFontPaths,
            backgroundPaths = backgroundPaths
        )
    }

    private suspend fun reconcileRestoredUiPackageIds(
        backupHasArtDir: Boolean,
        backupHasCoverSlotDir: Boolean,
        localSnapshot: LocalUiStateSnapshot
    ) {
        if (backupHasArtDir && backupHasCoverSlotDir) return
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false

        if (!backupHasArtDir) {
            val availableArtIds = runCatching {
                UnifiedPackageRepository.listArtPackages().mapTo(hashSetOf()) { it.manifest.id }
            }.getOrDefault(emptySet())
            artActivePackagePrefKeys.forEach { key ->
                val currentId = appCtx.getPrefString(key)?.trim().orEmpty()
                if (currentId.isNotBlank() &&
                    !hasLocalUnifiedPackage(UiPackageBackup.UNIFIED_ART, currentId, availableArtIds)
                ) {
                    val fallbackId = localSnapshot.artPackageIds[key]?.takeIf {
                        hasLocalUnifiedPackage(UiPackageBackup.UNIFIED_ART, it, availableArtIds)
                    }
                    if (fallbackId != null) {
                        edit.putString(key, fallbackId)
                    } else {
                        edit.remove(key)
                    }
                    changed = true
                }
            }
        }

        if (!backupHasCoverSlotDir) {
            val availableCoverSlotIds = runCatching {
                UnifiedPackageRepository.listCoverSlotPackages().mapTo(hashSetOf()) { it.manifest.id }
            }.getOrDefault(emptySet())
            coverSlotActivePackagePrefKeys.forEach { key ->
                val currentId = appCtx.getPrefString(key)?.trim().orEmpty()
                if (currentId.isNotBlank() &&
                    !hasLocalUnifiedPackage(UiPackageBackup.UNIFIED_COVER_SLOT, currentId, availableCoverSlotIds)
                ) {
                    val fallbackId = localSnapshot.coverSlotPackageIds[key]?.takeIf {
                        hasLocalUnifiedPackage(UiPackageBackup.UNIFIED_COVER_SLOT, it, availableCoverSlotIds)
                    }
                    if (fallbackId != null) {
                        edit.putString(key, fallbackId)
                    } else {
                        edit.remove(key)
                    }
                    changed = true
                }
            }
        }

        if (changed) {
            edit.commit()
        }
    }

    private fun hasLocalUnifiedPackage(
        kindDirName: String,
        packageId: String,
        loadedIds: Set<String>
    ): Boolean {
        if (packageId in loadedIds) return true
        if (packageId.isBlank() ||
            packageId == "." ||
            packageId == ".." ||
            packageId.contains('/') ||
            packageId.contains('\\')
        ) {
            return false
        }
        val kindRoot = File(UnifiedPackageRepository.rootDir, kindDirName)
        return File(kindRoot, packageId).isDirectory
    }

    private fun isValidLocalFontPref(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("http", ignoreCase = true)) return true
        if (FontProtocolResolver.normalizeSystemProtocol(value) != null) return true
        FontProtocolResolver.extractAtFontName(value)?.let { name ->
            return FontProtocolResolver.resolveAtFontTarget(appCtx, name) != null
        }
        if (value.isContentScheme()) return isReadableContentUri(value)
        val filePath = if (value.startsWith("file://", ignoreCase = true)) {
            runCatching { Uri.parse(value).path }.getOrNull().orEmpty()
        } else {
            value
        }
        return filePath.isNotBlank() && File(filePath).isFile
    }

    private fun isValidLocalBackgroundPref(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("http", ignoreCase = true)) return true
        if (value.isContentScheme()) return isReadableContentUri(value)
        return File(value).isFile
    }

    private fun isReadableContentUri(raw: String): Boolean {
        return runCatching {
            val doc = DocumentFile.fromSingleUri(appCtx, Uri.parse(raw))
            doc != null && doc.exists() && doc.canRead()
        }.getOrDefault(false)
    }

    private fun normalizeBackgroundPrefs(
        path: String,
        localBgSnapshot: Map<String, String>
    ) {
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        backgroundAssetDirNames.forEach { key ->
            val current = appCtx.getPrefString(key)?.trim() ?: return@forEach
            if (current.isBlank() || current.startsWith("http", ignoreCase = true)) return@forEach
            if (!current.isContentScheme() && File(current).isFile) return@forEach

            val backupKeyDir = File(path, key)
            val backupHasKeyDir = backupKeyDir.isDirectory
            val targetDir = appCtx.externalFiles.getFile(key)

            val restoredFile = if (current.isContentScheme()) {
                if (isReadableContentUri(current)) return@forEach
                if (backupHasKeyDir) {
                    val backupFileName = backupKeyDir.listFiles()?.firstOrNull { it.isFile }?.name
                    backupFileName?.let { File(targetDir, it) }?.takeIf { it.isFile }
                } else {
                    null
                }
            } else {
                val fileName = File(current).name.takeIf { it.isNotBlank() }
                fileName?.let { File(targetDir, it) }?.takeIf { it.isFile }
            }

            if (restoredFile != null) {
                edit.putString(key, restoredFile.absolutePath)
                changed = true
            } else {
                val fallback = if (!backupHasKeyDir) localBgSnapshot[key] else null
                if (!fallback.isNullOrBlank()) {
                    edit.putString(key, fallback)
                } else {
                    edit.remove(key)
                }
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    /**
     * 修正界面/标题字体路径：恢复后的 pref 指向旧设备的绝对路径且已失效时，
     * 若备份 fonts/ 内有同名文件（restoreFonts 已还原到应用私有 font 目录），
     * 改写为新路径，使 UI/标题字体在换设备后仍然生效；
     * 若找不到文件且未恢复字体包，则还原本地有效快照或移除悬空引用。
     */
    private fun normalizeUiFontPathPrefs(
        backupHasFontsDir: Boolean,
        localFontSnapshot: Map<String, String>
    ) {
        val fontDir = File(FileUtils.getPath(appCtx.externalFiles, "font"))
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false

        fun fallbackOrRemove(key: String) {
            val fallback = if (!backupHasFontsDir) localFontSnapshot[key] else null
            if (!fallback.isNullOrBlank()) {
                edit.putString(key, fallback)
            } else {
                edit.remove(key)
            }
            changed = true
        }

        uiFontPrefKeys.forEach { key ->
            val current = appCtx.getPrefString(key)?.trim() ?: return@forEach
            if (current.isBlank() || current.startsWith("http", ignoreCase = true)) return@forEach
            if (FontProtocolResolver.normalizeSystemProtocol(current) != null) return@forEach

            val atFontName = FontProtocolResolver.extractAtFontName(current)
            if (atFontName != null) {
                if (FontProtocolResolver.resolveAtFontTarget(appCtx, atFontName) == null) {
                    fallbackOrRemove(key)
                }
                return@forEach
            }

            if (current.isContentScheme()) {
                if (!isReadableContentUri(current)) {
                    fallbackOrRemove(key)
                }
                return@forEach
            }

            val rawPath = if (current.startsWith("file://", ignoreCase = true)) {
                runCatching { Uri.parse(current).path }.getOrNull().orEmpty()
            } else {
                current
            }
            if (rawPath.isNotBlank() && File(rawPath).isFile) return@forEach

            val fileName = rawPath.takeIf { it.isNotBlank() }?.let { File(it).name }
                ?.takeIf { it.isNotBlank() }
            val restoredFile = if (fontDir.isDirectory && fileName != null) {
                File(fontDir, fileName).takeIf { it.isFile }
            } else {
                null
            }
            if (restoredFile != null) {
                edit.putString(key, restoredFile.absolutePath)
                changed = true
            } else {
                fallbackOrRemove(key)
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    private fun copyDir(source: File, target: File) {
        if (!target.exists()) {
            target.mkdirs()
        }
        source.listFiles()?.forEach { file ->
            val targetFile = File(target, file.name)
            if (file.isDirectory) {
                copyDir(file, targetFile)
            } else {
                targetFile.parentFile?.mkdirs()
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }

    private inline fun <T, K> mergeWithIdRemap(
        incoming: List<T>,
        existing: List<T>,
        idOf: (T) -> Long,
        naturalKeyOf: (T) -> K,
        withId: (T, Long) -> T,
        mergeUpdate: (existingItem: T, incomingItem: T) -> T?,
        updateItem: (T) -> Unit,
        insertItem: (T) -> Long
    ): Map<Long, Long> {
        val occupiedIds = existing.mapTo(hashSetOf(), idOf)
        val byKey = existing.associateByTo(hashMapOf(), naturalKeyOf)
        val idMap = hashMapOf<Long, Long>()
        incoming.forEach { item ->
            val oldId = idOf(item)
            val key = naturalKeyOf(item)
            val hit = byKey[key]
            if (hit != null) {
                val hitId = idOf(hit)
                idMap[oldId] = hitId
                mergeUpdate(hit, item)?.let { updated ->
                    updateItem(updated)
                    byKey[key] = updated
                }
            } else {
                val candidate = if (oldId > 0L && oldId !in occupiedIds) item else withId(item, 0L)
                val insertedId = insertItem(candidate)
                val finalId = if (insertedId > 0L) insertedId else idOf(candidate)
                occupiedIds.add(finalId)
                byKey[key] = withId(candidate, finalId)
                idMap[oldId] = finalId
            }
        }
        return idMap
    }

    private inline fun <T, K> mergeBatchByNaturalKey(
        incoming: List<T>,
        existing: List<T>,
        idOf: (T) -> Long,
        naturalKeyOf: (T) -> K,
        clearId: (T) -> T,
        mergeUpdate: (current: T, incoming: T) -> T?,
        updateExisting: (T) -> Unit,
        insertBatch: (List<T>) -> Unit
    ) {
        val occupiedIds = existing.mapTo(hashSetOf(), idOf)
        val existingByKey = existing.associateByTo(hashMapOf(), naturalKeyOf)
        val toInsert = arrayListOf<T>()
        val pendingIdxByKey = hashMapOf<K, Int>()

        incoming.forEach { item ->
            val key = naturalKeyOf(item)
            val hit = existingByKey[key]
            if (hit != null) {
                mergeUpdate(hit, item)?.let { updated ->
                    updateExisting(updated)
                    existingByKey[key] = updated
                }
                return@forEach
            }
            val pendingIdx = pendingIdxByKey[key]
            if (pendingIdx != null) {
                mergeUpdate(toInsert[pendingIdx], item)?.let { mergedPending ->
                    toInsert[pendingIdx] = mergedPending
                }
                return@forEach
            }
            val rawId = idOf(item)
            val prepared = if (rawId > 0L && occupiedIds.add(rawId)) item else clearId(item)
            pendingIdxByKey[key] = toInsert.size
            toInsert.add(prepared)
        }
        toInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { chunk ->
            insertBatch(chunk)
        }
    }

    private fun bookCollectionNaturalKey(collection: BookCollection): String {
        val trimmed = collection.name.trim()
        return if (trimmed.isNotEmpty()) {
            "name:$trimmed"
        } else {
            "blank:${collection.createdTime}"
        }
    }

    private suspend fun restoreBookCollections(path: String) {
        val collections = fileToListT<BookCollection>(path, "bookCollections.json")
        val items = fileToListT<BookCollectionItem>(path, "bookCollectionItems.json")
        val children = fileToListT<BookCollectionChild>(path, "bookCollectionChildren.json")
        if (collections.isNullOrEmpty() && items.isNullOrEmpty() && children.isNullOrEmpty()) return
        runCatching {
            appDb.withTransaction {
                val existingCollections = appDb.bookCollectionDao.allCollections()
                val existingCollectionIds = existingCollections.mapTo(hashSetOf()) { it.collectionId }
                val idMap = if (!collections.isNullOrEmpty()) {
                    mergeWithIdRemap(
                        incoming = collections,
                        existing = existingCollections,
                        idOf = { it.collectionId },
                        naturalKeyOf = ::bookCollectionNaturalKey,
                        withId = { col, id -> col.copy(collectionId = id) },
                        mergeUpdate = { hit, inc ->
                            if (inc.updatedTime > hit.updatedTime) {
                                inc.copy(
                                    collectionId = hit.collectionId,
                                    createdTime = minOf(hit.createdTime, inc.createdTime)
                                )
                            } else {
                                null
                            }
                        },
                        updateItem = { appDb.bookCollectionDao.update(it) },
                        insertItem = { appDb.bookCollectionDao.insert(it) }
                    )
                } else {
                    emptyMap()
                }
                fun resolveCollectionId(oldId: Long): Long? {
                    return if (!collections.isNullOrEmpty()) {
                        idMap[oldId]
                    } else {
                        oldId.takeIf { it in existingCollectionIds }
                    }
                }
                if (!items.isNullOrEmpty()) {
                    val bookExistsCache = hashMapOf<String, Boolean>()
                    val validItems = items.mapNotNull { item ->
                        val mappedId = resolveCollectionId(item.collectionId) ?: return@mapNotNull null
                        val hasBook = bookExistsCache.getOrPut(item.bookUrl) { appDb.bookDao.has(item.bookUrl) }
                        if (!hasBook) return@mapNotNull null
                        if (mappedId != item.collectionId) item.copy(collectionId = mappedId) else item
                    }
                    validItems.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { chunk ->
                        appDb.bookCollectionDao.insertItems(chunk)
                    }
                }
                if (!children.isNullOrEmpty()) {
                    val validChildren = children.mapNotNull { child ->
                        val parentId = resolveCollectionId(child.parentCollectionId) ?: return@mapNotNull null
                        val childId = resolveCollectionId(child.childCollectionId) ?: return@mapNotNull null
                        if (parentId == childId) return@mapNotNull null
                        if (parentId != child.parentCollectionId || childId != child.childCollectionId) {
                            child.copy(parentCollectionId = parentId, childCollectionId = childId)
                        } else {
                            child
                        }
                    }
                    validChildren.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { chunk ->
                        appDb.bookCollectionDao.insertChildren(chunk)
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("恢复自建书单出错\n${it.localizedMessage}", it)
        }
    }

    private fun bookIllustrationNaturalKey(item: BookIllustration): String {
        return listOf(
            item.bookUrl,
            item.chapterIndex.toString(),
            item.anchorType,
            item.anchorPos.toString(),
            item.imageSrcs,
            item.pdfPage.toString(),
            item.pdfRect
        ).joinToString("\u0000")
    }

    private fun restoreBookIllustrations(path: String) {
        val sourceDir = File(path, Backup.illustrationsDirName)
        if (sourceDir.isDirectory) {
            runCatching {
                copyDir(sourceDir, appCtx.externalFiles.getFile(Backup.illustrationsDirName))
            }.onFailure {
                AppLog.put("恢复书籍插图目录出错\n${it.localizedMessage}", it)
            }
        }
        val illustrations = fileToListT<BookIllustration>(path, "bookIllustrations.json") ?: return
        if (illustrations.isEmpty()) return
        runCatching {
            appDb.runInTransaction {
                val bookExistsCache = hashMapOf<String, Boolean>()
                val validIllustrations = illustrations.filter { item ->
                    bookExistsCache.getOrPut(item.bookUrl) { appDb.bookDao.has(item.bookUrl) }
                }
                if (validIllustrations.isEmpty()) return@runInTransaction
                mergeBatchByNaturalKey(
                    incoming = validIllustrations,
                    existing = appDb.bookIllustrationDao.all,
                    idOf = { it.id },
                    naturalKeyOf = ::bookIllustrationNaturalKey,
                    clearId = { it.copy(id = 0L) },
                    mergeUpdate = { current, incoming ->
                        incoming.copy(id = current.id).takeIf { it != current }
                    },
                    updateExisting = { appDb.bookIllustrationDao.update(it) },
                    insertBatch = { chunk ->
                        appDb.bookIllustrationDao.insert(*chunk.toTypedArray())
                    }
                )
            }
        }.onFailure {
            AppLog.put("恢复书籍插图记录出错\n${it.localizedMessage}", it)
        }
    }

    private fun paragraphRuleNaturalKey(rule: ParagraphRule): String {
        val name = rule.name.trim()
        val script = rule.script.trim()
        val jsLib = rule.jsLib.trim()
        return if (name.isEmpty() && script.isEmpty() && jsLib.isEmpty()) {
            "blank\u0000${rule.updateTime}"
        } else {
            "$name\u0000$script\u0000$jsLib"
        }
    }

    private suspend fun restoreParagraphRules(path: String) {
        val rules = fileToListT<ParagraphRule>(path, "paragraphRules.json")
        val bookRules = fileToListT<BookParagraphRule>(path, "bookParagraphRules.json")
        val vars = fileToListT<ParagraphRuleVar>(path, "paragraphRuleVars.json")
        if (rules.isNullOrEmpty() && bookRules.isNullOrEmpty() && vars.isNullOrEmpty()) return
        runCatching {
            appDb.withTransaction {
                val existingRules = appDb.paragraphRuleDao.all()
                val existingRuleIds = existingRules.mapTo(hashSetOf()) { it.id }
                val ruleIdMap = if (!rules.isNullOrEmpty()) {
                    mergeWithIdRemap(
                        incoming = rules,
                        existing = existingRules,
                        idOf = { it.id },
                        naturalKeyOf = ::paragraphRuleNaturalKey,
                        withId = { rule, id -> rule.copy(id = id) },
                        mergeUpdate = { hit, inc ->
                            if (inc.updateTime > hit.updateTime) inc.copy(id = hit.id) else null
                        },
                        updateItem = { appDb.paragraphRuleDao.update(it) },
                        insertItem = { appDb.paragraphRuleDao.insert(it) }
                    )
                } else {
                    emptyMap()
                }
                fun resolveRuleId(oldRuleId: Long): Long? {
                    return if (!rules.isNullOrEmpty()) {
                        ruleIdMap[oldRuleId]
                    } else {
                        oldRuleId.takeIf { it in existingRuleIds }
                    }
                }
                if (!bookRules.isNullOrEmpty()) {
                    bookRules.mapNotNull { bookRule ->
                        val mappedId = resolveRuleId(bookRule.ruleId) ?: return@mapNotNull null
                        if (mappedId != bookRule.ruleId) bookRule.copy(ruleId = mappedId) else bookRule
                    }.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { chunk ->
                        appDb.paragraphRuleDao.insertBookRules(chunk)
                    }
                }
                if (!vars.isNullOrEmpty()) {
                    vars.mapNotNull { ruleVar ->
                        val mappedId = resolveRuleId(ruleVar.ruleId) ?: return@mapNotNull null
                        if (mappedId != ruleVar.ruleId) ruleVar.copy(ruleId = mappedId) else ruleVar
                    }.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { chunk ->
                        appDb.paragraphRuleDao.putVars(chunk)
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("恢复段落规则出错\n${it.localizedMessage}", it)
        }
    }

    private fun readMenuCustomButtonNaturalKey(button: ReadMenuCustomButton): String {
        val name = button.name.trim()
        val script = button.script.trim()
        val jsLib = button.jsLib.trim()
        return if (name.isEmpty() && script.isEmpty() && jsLib.isEmpty()) {
            "blank\u0000${button.updateTime}"
        } else {
            "$name\u0000$script\u0000$jsLib"
        }
    }

    private fun restoreReadMenuCustomButtons(path: String): Map<Long, Long> {
        val iconsDir = appCtx.externalFiles.getFile(Backup.readMenuIconsDirName)
        val sourceDir = File(path, Backup.readMenuIconsDirName)
        if (sourceDir.isDirectory) {
            runCatching {
                copyDir(sourceDir, iconsDir)
            }.onFailure {
                AppLog.put("恢复阅读菜单图标出错\n${it.localizedMessage}", it)
            }
        }
        val buttons = fileToListT<ReadMenuCustomButton>(path, "readMenuCustomButtons.json")
            ?: return emptyMap()
        if (buttons.isEmpty()) return emptyMap()
        return runCatching {
            val remapped = buttons.map { button ->
                val mappedIcon = remapFilePathByName(button.iconPath, iconsDir)
                if (mappedIcon != button.iconPath) button.copy(iconPath = mappedIcon) else button
            }
            appDb.runInTransaction<Map<Long, Long>> {
                mergeWithIdRemap(
                    incoming = remapped,
                    existing = appDb.readMenuCustomButtonDao.all(),
                    idOf = { it.id },
                    naturalKeyOf = ::readMenuCustomButtonNaturalKey,
                    withId = { btn, id -> btn.copy(id = id) },
                    mergeUpdate = { hit, inc ->
                        if (inc.updateTime > hit.updateTime) {
                            val resolvedIcon = if (inc.iconPath.isBlank() ||
                                File(inc.iconPath).isFile ||
                                !File(hit.iconPath).isFile
                            ) {
                                inc.iconPath
                            } else {
                                hit.iconPath
                            }
                            inc.copy(id = hit.id, iconPath = resolvedIcon)
                        } else {
                            null
                        }
                    },
                    updateItem = { appDb.readMenuCustomButtonDao.update(it) },
                    insertItem = { appDb.readMenuCustomButtonDao.insert(it) }
                )
            }
        }.onFailure {
            AppLog.put("恢复阅读菜单自定义按钮出错\n${it.localizedMessage}", it)
        }.getOrDefault(emptyMap())
    }

    private fun normalizeReadMenuButtonLayoutPrefs(buttonIdMap: Map<Long, Long>) {
        val raw = appCtx.getPrefString(PreferKey.readMenuButtonLayout)?.takeIf { it.isNotBlank() } ?: return
        val iconsDir = appCtx.externalFiles.getFile(Backup.readMenuIconsDirName)
        val layout = runCatching {
            GSON.fromJson(raw, ReadMenuButtonConfig.ButtonLayout::class.java)
        }.getOrNull() ?: return
        var changed = false
        fun remapRow(row: List<ReadMenuButtonConfig.ButtonRef>?): List<ReadMenuButtonConfig.ButtonRef> {
            return row.orEmpty().map { ref ->
                val newIcon = remapFilePathByName(ref.iconPath, iconsDir)
                val newNightIcon = remapFilePathByName(ref.nightIconPath, iconsDir)
                val newId = if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM && buttonIdMap.isNotEmpty()) {
                    ref.id.toLongOrNull()?.let { buttonIdMap[it]?.toString() } ?: ref.id
                } else {
                    ref.id
                }
                if (newIcon != ref.iconPath || newNightIcon != ref.nightIconPath || newId != ref.id) {
                    changed = true
                    ref.copy(id = newId, iconPath = newIcon, nightIconPath = newNightIcon)
                } else {
                    ref
                }
            }
        }
        val updated = ReadMenuButtonConfig.ButtonLayout(
            firstRow = remapRow(layout.firstRow),
            secondRow = remapRow(layout.secondRow)
        )
        if (changed) {
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.readMenuButtonLayout, GSON.toJson(updated))
                .commit()
        }
    }

    private suspend fun restoreHomepageModules(path: String) {
        val customSets = fileToListT<HomepageCustomSet>(path, "homepageCustomSets.json")
        val modules = fileToListT<HomepageModule>(path, "homepageModules.json")
        if (customSets.isNullOrEmpty() && modules.isNullOrEmpty()) return
        runCatching {
            appDb.withTransaction {
                customSets?.chunked(RESTORE_INSERT_BATCH_SIZE)?.forEach { chunk ->
                    appDb.homepageCustomSetDao.upsertAll(chunk)
                }
                modules?.chunked(RESTORE_INSERT_BATCH_SIZE)?.forEach { chunk ->
                    appDb.homepageModuleDao.upsertAll(chunk)
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("恢复聚合主页配置出错\n${it.localizedMessage}", it)
        }
    }

    private fun bgmGroupNaturalKey(group: ReadAloudBgmGroup): String {
        val type = ReadAloudBgmTrack.normalizeAssetType(group.assetType)
        val name = group.name.trim()
        return if (name.isNotEmpty()) {
            "$type\u0000name:$name"
        } else {
            "$type\u0000blank:${group.createdAt}"
        }
    }

    private fun bgmTrackNaturalKey(track: ReadAloudBgmTrack): String {
        val filePart = File(track.filePath).name.takeIf { it.isNotBlank() }
            ?: track.fileName.trim().takeIf { it.isNotBlank() }
            ?: track.checksum.trim().takeIf { it.isNotBlank() }
            ?: "${track.name.trim()}#${track.createdAt}"
        return "${track.groupId}\u0000${track.normalizedAssetType()}\u0000$filePart"
    }

    private fun restoreReadAloudBgm(path: String) {
        val audioDir = File(appCtx.filesDir, Backup.readAloudAudioDirName)
        val sourceDir = File(path, Backup.readAloudAudioDirName)
        if (sourceDir.isDirectory) {
            runCatching {
                copyDir(sourceDir, audioDir)
            }.onFailure {
                AppLog.put("恢复听书音效目录出错\n${it.localizedMessage}", it)
            }
        }
        val groups = fileToListT<ReadAloudBgmGroup>(path, "readAloudBgmGroups.json")
        val tracks = fileToListT<ReadAloudBgmTrack>(path, "readAloudBgmTracks.json")
        if (groups.isNullOrEmpty() && tracks.isNullOrEmpty()) return
        runCatching {
            appDb.runInTransaction {
                val existingGroups = appDb.readAloudBgmDao.groups()
                val existingGroupIds = existingGroups.mapTo(hashSetOf()) { it.id }
                val groupIdMap = if (!groups.isNullOrEmpty()) {
                    mergeWithIdRemap(
                        incoming = groups,
                        existing = existingGroups,
                        idOf = { it.id },
                        naturalKeyOf = ::bgmGroupNaturalKey,
                        withId = { group, id -> group.copy(id = id) },
                        mergeUpdate = { hit, inc ->
                            if (inc.updatedAt > hit.updatedAt) {
                                inc.copy(
                                    id = hit.id,
                                    createdAt = minOf(hit.createdAt, inc.createdAt)
                                )
                            } else {
                                null
                            }
                        },
                        updateItem = { appDb.readAloudBgmDao.updateGroup(it) },
                        insertItem = { appDb.readAloudBgmDao.insertGroup(it) }
                    )
                } else {
                    emptyMap()
                }
                if (!tracks.isNullOrEmpty()) {
                    val remappedTracks = tracks.map { track ->
                        val mappedGroupId = when {
                            track.groupId <= 0L -> 0L
                            !groups.isNullOrEmpty() -> groupIdMap[track.groupId] ?: 0L
                            track.groupId in existingGroupIds -> track.groupId
                            else -> 0L
                        }
                        val mappedPath = remapReadAloudTrackPath(track, audioDir)
                        if (mappedGroupId != track.groupId || mappedPath != track.filePath) {
                            track.copy(groupId = mappedGroupId, filePath = mappedPath)
                        } else {
                            track
                        }
                    }
                    mergeWithIdRemap(
                        incoming = remappedTracks,
                        existing = appDb.readAloudBgmDao.allTracks(),
                        idOf = { it.id },
                        naturalKeyOf = ::bgmTrackNaturalKey,
                        withId = { track, id -> track.copy(id = id) },
                        mergeUpdate = { hit, inc ->
                            if (inc.updatedAt > hit.updatedAt) {
                                val resolvedPath = if (inc.filePath.isBlank() ||
                                    File(inc.filePath).isFile ||
                                    !File(hit.filePath).isFile
                                ) {
                                    inc.filePath
                                } else {
                                    hit.filePath
                                }
                                inc.copy(
                                    id = hit.id,
                                    filePath = resolvedPath,
                                    createdAt = minOf(hit.createdAt, inc.createdAt)
                                )
                            } else {
                                null
                            }
                        },
                        updateItem = { appDb.readAloudBgmDao.updateTrack(it) },
                        insertItem = { appDb.readAloudBgmDao.insertTrack(it) }
                    )
                }
            }
        }.onFailure {
            AppLog.put("恢复听书音效配置出错\n${it.localizedMessage}", it)
        }
    }

    private fun remapReadAloudTrackPath(track: ReadAloudBgmTrack, audioDir: File): String {
        val raw = track.filePath
        if (raw.isBlank() || File(raw).isFile) return raw
        val relative = raw.substringAfter("/${Backup.readAloudAudioDirName}/", "")
            .trimStart('/', '\\')
            .takeIf { it.isNotBlank() }
        val relativeCandidate = relative?.let { File(audioDir, it) }?.takeIf { it.isFile }
        if (relativeCandidate != null) return relativeCandidate.absolutePath
        val fileName = File(raw).name.takeIf { it.isNotBlank() } ?: return raw
        val typedCandidate = File(audioDir, "${track.normalizedAssetType()}/$fileName")
        return if (typedCandidate.isFile) typedCandidate.absolutePath else raw
    }

    private fun speakerGroupNaturalKey(group: ReadAloudSpeakerGroup): String {
        val name = group.name.trim()
        return if (name.isNotEmpty()) {
            "name:$name"
        } else {
            "blank:${group.createdAt}"
        }
    }

    private fun speakerItemNaturalKey(item: ReadAloudSpeakerGroupItem): String {
        return "${item.groupId}\u0000${SpeechVoiceGroupRepository.itemKey(item)}"
    }

    private fun restoreReadAloudSpeakerGroups(path: String) {
        val groups = fileToListT<ReadAloudSpeakerGroup>(path, "readAloudSpeakerGroups.json")
        val items = fileToListT<ReadAloudSpeakerGroupItem>(path, "readAloudSpeakerGroupItems.json")
        if (groups.isNullOrEmpty() && items.isNullOrEmpty()) return
        runCatching {
            appDb.runInTransaction {
                val existingGroups = appDb.readAloudSpeakerGroupDao.groups()
                val existingGroupIds = existingGroups.mapTo(hashSetOf()) { it.id }
                val groupIdMap = if (!groups.isNullOrEmpty()) {
                    mergeWithIdRemap(
                        incoming = groups,
                        existing = existingGroups,
                        idOf = { it.id },
                        naturalKeyOf = ::speakerGroupNaturalKey,
                        withId = { group, id -> group.copy(id = id) },
                        mergeUpdate = { hit, inc ->
                            if (inc.updatedAt > hit.updatedAt) {
                                inc.copy(
                                    id = hit.id,
                                    createdAt = minOf(hit.createdAt, inc.createdAt)
                                )
                            } else {
                                null
                            }
                        },
                        updateItem = { appDb.readAloudSpeakerGroupDao.updateGroup(it) },
                        insertItem = { appDb.readAloudSpeakerGroupDao.insertGroup(it) }
                    )
                } else {
                    emptyMap()
                }
                if (!items.isNullOrEmpty()) {
                    val remappedItems = items.mapNotNull { item ->
                        val mappedGroupId = if (!groups.isNullOrEmpty()) {
                            groupIdMap[item.groupId]
                        } else {
                            item.groupId.takeIf { it in existingGroupIds }
                        } ?: return@mapNotNull null
                        if (mappedGroupId != item.groupId) item.copy(groupId = mappedGroupId) else item
                    }
                    if (remappedItems.isEmpty()) return@runInTransaction
                    mergeBatchByNaturalKey(
                        incoming = remappedItems,
                        existing = appDb.readAloudSpeakerGroupDao.items(),
                        idOf = { it.id },
                        naturalKeyOf = ::speakerItemNaturalKey,
                        clearId = { it.copy(id = 0L) },
                        mergeUpdate = { cur, inc ->
                            if (inc.updatedAt > cur.updatedAt) {
                                inc.copy(
                                    id = cur.id,
                                    createdAt = minOf(cur.createdAt, inc.createdAt)
                                )
                            } else {
                                null
                            }
                        },
                        updateExisting = { appDb.readAloudSpeakerGroupDao.updateItem(it) },
                        insertBatch = { chunk -> appDb.readAloudSpeakerGroupDao.insertItems(chunk) }
                    )
                }
            }
        }.onFailure {
            AppLog.put("恢复听书发音人分组出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreReadRecordDaily(path: String) {
        val records = fileToListT<ReadRecordDaily>(path, "readRecordDaily.json") ?: return
        if (records.isEmpty()) return
        runCatching {
            val merged = records.mapNotNull { restored ->
                if (restored.date.isBlank()) return@mapNotNull null
                val local = appDb.readRecordDailyDao.get(restored.date)
                if (local == null) {
                    restored
                } else {
                    ReadRecordDaily(
                        date = restored.date,
                        readTime = maxOf(local.readTime, restored.readTime),
                        updatedAt = maxOf(local.updatedAt, restored.updatedAt)
                    )
                }
            }
            insertRestored(merged) { items -> appDb.readRecordDailyDao.insert(*items) }
        }.onFailure {
            AppLog.put("恢复每日阅读统计出错\n${it.localizedMessage}", it)
        }
    }

    private fun pendingReviewCommentNaturalKey(item: PendingReviewComment): String {
        return listOf(
            item.bookUrl,
            item.chapterIndex.toString(),
            item.chapterUrl,
            item.kind.toString(),
            item.para.orEmpty(),
            item.content,
            item.createdAt.toString()
        ).joinToString("\u0000")
    }

    private suspend fun restorePendingReviewComments(path: String) {
        val comments = fileToListT<PendingReviewComment>(path, "pendingReviewComments.json") ?: return
        if (comments.isEmpty()) return
        runCatching {
            appDb.withTransaction {
                mergeBatchByNaturalKey(
                    incoming = comments,
                    existing = appDb.pendingReviewCommentDao.all(),
                    idOf = { it.id },
                    naturalKeyOf = ::pendingReviewCommentNaturalKey,
                    clearId = { it.copy(id = 0L) },
                    mergeUpdate = { cur, inc ->
                        val shouldUpdate = when {
                            cur.status == PendingReviewComment.STATUS_SENT &&
                                inc.status != PendingReviewComment.STATUS_SENT -> false
                            inc.status == PendingReviewComment.STATUS_SENT &&
                                cur.status != PendingReviewComment.STATUS_SENT -> true
                            (inc.lastAttemptAt ?: 0L) > (cur.lastAttemptAt ?: 0L) -> true
                            inc.attempts > cur.attempts -> true
                            else -> false
                        }
                        if (shouldUpdate) inc.copy(id = cur.id) else null
                    },
                    updateExisting = { appDb.pendingReviewCommentDao.update(it) },
                    insertBatch = { chunk -> appDb.pendingReviewCommentDao.insertAll(chunk) }
                )
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("恢复离线评论账本出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreAiMemory(path: String) {
        val items = fileToListT<AiMemoryItem>(path, "aiMemoryItems.json")
        val fragments = fileToListT<AiMemoryFragment>(path, "aiMemoryFragments.json")
        if (items.isNullOrEmpty() && fragments.isNullOrEmpty()) return
        runCatching {
            appDb.runInTransaction {
                items?.forEach { AiMemoryStore.upsertItem(it) }
                fragments?.forEach { AiMemoryStore.upsertFragment(it) }
            }
        }.onFailure {
            AppLog.put("恢复 AI 长期记忆出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreAiImageGallery(path: String) {
        val aiImagesDir = File(appCtx.filesDir, Backup.aiImagesDirName)
        val sourceDir = File(path, Backup.aiImagesDirName)
        if (sourceDir.isDirectory) {
            runCatching {
                copyDir(sourceDir, aiImagesDir)
            }.onFailure {
                AppLog.put("恢复 AI 图库图片出错\n${it.localizedMessage}", it)
            }
        }
        val groups = fileToListT<AiImageGroup>(path, "aiImageGroups.json")
        val images = fileToListT<AiGeneratedImage>(path, "aiGeneratedImages.json")
        if (groups.isNullOrEmpty() && images.isNullOrEmpty()) return
        runCatching {
            appDb.runInTransaction {
                if (!groups.isNullOrEmpty()) {
                    insertRestored(groups) { items -> appDb.aiImageGroupDao.insert(*items) }
                }
                if (!images.isNullOrEmpty()) {
                    val remapped = images.map { image ->
                        val mappedPath = remapFilePathByName(image.localPath, aiImagesDir)
                        if (mappedPath != image.localPath) image.copy(localPath = mappedPath) else image
                    }
                    insertRestored(remapped) { items -> appDb.aiGeneratedImageDao.insert(*items) }
                }
            }
        }.onFailure {
            AppLog.put("恢复 AI 图库记录出错\n${it.localizedMessage}", it)
        }
    }

    private inline fun <reified T> restoreEncryptedJsonList(
        path: String,
        fileName: String,
        aes: BackupAES,
        errorLabel: String,
        insert: (Array<T>) -> Unit
    ) {
        File(path, fileName).takeIf { it.exists() }?.runCatching {
            var json = readText()
            if (!json.isJsonArray()) {
                json = aes.decryptStr(json)
            }
            GSON.fromJsonArray<T>(json).getOrNull()?.let {
                insertRestored(it, insert)
            }
        }?.onFailure {
            AppLog.put("${errorLabel}\n${it.localizedMessage}", it)
        }
    }

    private fun restoreSharedPrefsFile(file: File, prefName: String) {
        BackupPrefsFile.read(file)?.let { map ->
            val edit = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
            map.forEach { (key, value) ->
                when (value) {
                    is Int -> edit.putInt(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            edit.commit()
        }
    }

    private fun remapFilePathByName(rawPath: String?, targetDir: File): String {
        val value = rawPath.orEmpty()
        if (value.isBlank() || value.startsWith("http", ignoreCase = true) || value.isContentScheme()) {
            return value
        }
        if (File(value).isFile) return value
        val fileName = File(value).name.takeIf { it.isNotBlank() } ?: return value
        val restoredFile = File(targetDir, fileName)
        return if (restoredFile.isFile) restoredFile.absolutePath else value
    }

}
