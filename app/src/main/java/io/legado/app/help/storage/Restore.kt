package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
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
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.BookCover
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskImport
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
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
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
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
        PreferKey.panelBgImageN
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
                    runCatching { extractUriToBackupPath(context, assetsUri) }.onFailure {
                        AppLog.put("恢复资源包出错\n${it.localizedMessage}", it)
                    }
                }
            }
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    private fun extractUriToBackupPath(context: Context, uri: Uri): Result<Unit> = runCatching {
        if (uri.isContentScheme()) {
            val tempArchive = File(context.cacheDir, "restore_${UUID.randomUUID()}.zip")
            try {
                DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use { input ->
                    BackupArchiveExtractor.copyToTemporaryFile(input, tempArchive)
                }
                BackupArchiveExtractor.extract(tempArchive, File(Backup.backupPath))
            } finally {
                tempArchive.delete()
            }
        } else {
            BackupArchiveExtractor.extract(File(uri.path!!), File(Backup.backupPath))
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
                    restore(path)
                } catch (e: Throwable) {
                    snapshot.rollback("恢复过程异常: ${e.localizedMessage}")
                    throw e
                } finally {
                    snapshot.delete()
                }
            } finally {
                isRestoring = false
            }
        }
    }

    private suspend fun restore(path: String) {
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
            appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
            insertRestored(it) { items -> appDb.keyboardAssistsDao.insert(*items) }
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
        restoreGroupCovers(path)
        File(path, "servers.json").takeIf {
            it.exists()
        }?.runCatching {
            var json = readText()
            if (!json.isJsonArray()) {
                json = aes.decryptStr(json)
            }
            GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                insertRestored(it) { items -> appDb.serverDao.insert(*items) }
            }
        }?.onFailure {
            AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.isFile
        }?.let { ruleFile ->
            runCatching {
                require(ruleFile.length() <= DirectLinkUpload.MAX_RULE_JSON_BYTES.toLong()) {
                    "备份中的直链上传规则超过大小限制"
                }
                ruleFile.readText(Charsets.UTF_8)
            }
                .mapCatching { json -> DirectLinkUpload.importRule(json).getOrThrow() }
                .onFailure { error ->
                    AppLog.put(
                        "备份中的直链上传规则无效，已保留当前配置\n" +
                            (error.localizedMessage ?: "未知错误"),
                        error
                    )
                    appCtx.toastOnUi("备份中的直链上传规则无效，已保留当前配置")
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
        restoreVisualResourcePackages(path)
        restoreSourceRuntime(path)
        appCtx.getSharedPreferences(path, "config")?.all?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key) &&
                    key != PreferKey.advancedTitleLottieJson &&
                    key != PreferKey.advancedTitleLottiePath
                ) {
                    when (key) {
                        PreferKey.webDavPassword, PreferKey.s3SecretKey, PreferKey.s3SessionToken -> {
                            kotlin.runCatching {
                                aes.decryptStr(value.toString())
                            }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                if (appCtx.getPrefString(key).isNullOrBlank()) {
                                    edit.putString(key, value.toString())
                                }
                            }
                        }

                        PreferKey.s3Containers -> {
                            edit.putString(key, S3ContainerManager.restoreEncryptedBackupJson(value.toString(), aes))
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.commit()
        }
        normalizeBackgroundPrefs()
        normalizeUiFontPathPrefs()
        // The restored preferences can point at a different font folder, and any cached
        // lookup from before the restore would now be answering for the wrong one.
        io.legado.app.help.AppFont.invalidateFontList()
        ReaderDataRepair.repairAfterRestore()
        refreshWebDavAfterRestore()
        restoreBookCovers()
        restoreReadConfigBackgrounds()
        ReaderDataRepair.repairAfterRestore()
        restoreAppliedUiPackages()
        appCtx.getSharedPreferences(path, "videoConfig")?.all?.let { map ->
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                map.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                apply()
            }
        }
        AutoTask.refreshSchedule()
        // ⚠️ 「按备份覆盖」的删书放在所有可能失败的步骤之后：若在前面执行，
        // 后续步骤失败触发回滚时书已被永久删除——「看起来恢复失败，书其实已经没了」。
        overwriteShelfIfNeeded(path)
        bookshelfRebuildPending = true
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            ThemeConfig.applyDayNight(appCtx)
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
                        relationName = relation.relationName.ifBlank { "关系" },
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
            appCtx.toastOnUi("恢复定时任务失败，已保留当前任务")
        }
    }

    /**
     * 恢复书架分组封面图片：备份 covers/ 条目（来源 collectGroupCoverSources）
     * 复制到应用 covers 目录，并改写指向旧设备绝对路径的分组 cover 引用。
     */
    private fun restoreGroupCovers(path: String) {
        val sourceDir = File(path, "covers")
        if (!sourceDir.isDirectory) return
        val coversDir = appCtx.externalFiles.getFile("covers")
        runCatching {
            copyDir(sourceDir, coversDir)
        }.onFailure {
            AppLog.put("恢复分组封面出错\n${it.localizedMessage}", it)
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

    private fun restoreSourceRuntime(path: String) {
        if (BackupConfig.ignoreSourceRuntime) return
        File(path, "sourceRuntime.json").takeIf { it.exists() }?.runCatching {
            val backup = GSON.fromJsonObject<SourceRuntimeBackup>(readText()).getOrNull()
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
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
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
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
    }

    internal fun forEachBookBackup(
        file: File,
        onError: (Int, Throwable) -> Unit = { index, error ->
            AppLog.put(
                "bookshelf.json 第${index + 1}项读取失败\n${error.localizedMessage}",
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

    private suspend fun restoreReadConfigBackgrounds() {
        val names = linkedSetOf<String>()
        fun collect(config: ReadBookConfig.Config) {
            readConfigBgFileName(config.bgType, config.bgStr)?.let(names::add)
            readConfigBgFileName(config.bgTypeNight, config.bgStrNight)?.let(names::add)
            readConfigBgFileName(config.bgTypeEInk, config.bgStrEInk)?.let(names::add)
        }
        ReadBookConfig.allLayoutConfigs().forEach(::collect)
        if (names.isEmpty()) return
        val restored = AppCloudStorage.downBgs(names)
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
        restorePackageDirectory(File(path, "themePackages"), ThemePackageManager.rootDir)
    }

    private fun restoreNavigationIcons(path: String) {
        restorePackageDirectory(File(path, "navigationBarPackages"), NavigationBarIconConfig.rootDir)
    }

    private fun restoreTopBarPackages(path: String) {
        restorePackageDirectory(File(path, "topBarPackages"), TopBarConfig.rootDir)
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
        runCatching {
            AppCloudStorage.downBookCovers()
        }.onFailure {
            AppLog.put("恢复书架封面出错\n${it.localizedMessage}", it)
        }
    }

    private suspend fun restoreAppliedUiPackages() {
        kotlin.runCatching {
            ThemePackageManager.restoreAppliedThemes(appCtx)
        }.onFailure {
            AppLog.put("恢复主题包出错\n${it.localizedMessage}", it)
        }
        listOf(false, true).forEach { isNight ->
            kotlin.runCatching { TopBarConfig.restoreApplied(isNight) }.onFailure {
                AppLog.put("恢复顶栏包出错\n${it.localizedMessage}", it)
            }
            kotlin.runCatching { NavigationBarIconConfig.restoreApplied(isNight) }.onFailure {
                AppLog.put("恢复底栏包出错\n${it.localizedMessage}", it)
            }
        }
        postEvent(EventBus.RECREATE, "")
        postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
        postEvent(EventBus.NAVIGATION_BAR_CHANGED, AppConfig.isNightTheme)
    }
    private fun restoreCoverCollections(path: String) {
        restorePackageDirectory(File(path, "coverCollections"), CoverCollectionManager.rootDir)
    }

    private fun normalizeBackgroundPrefs() {
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        backgroundAssetDirNames.forEach { key ->
            val current = appCtx.getPrefString(key) ?: return@forEach
            if (current.isBlank() || current.startsWith("http")) return@forEach
            if (File(current).exists()) return@forEach
            val fileName = File(current).name.takeIf { it.isNotBlank() } ?: return@forEach
            val restoredFile = appCtx.externalFiles.getFile(key, fileName)
            if (restoredFile.exists()) {
                edit.putString(key, restoredFile.absolutePath)
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
     * 改写为新路径，使 UI/标题字体在换设备后仍然生效。
     */
    private fun normalizeUiFontPathPrefs() {
        val fontDir = File(FileUtils.getPath(appCtx.externalFiles, "font"))
        if (!fontDir.isDirectory) return
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        listOf(
            PreferKey.uiFontPath,
            PreferKey.uiFontPathN,
            PreferKey.titleFontPath,
            PreferKey.titleFontPathN
        ).forEach { key ->
            val current = appCtx.getPrefString(key) ?: return@forEach
            if (current.isBlank() || current.startsWith("http") || current.startsWith("content://")) return@forEach
            if (File(current).exists()) return@forEach
            val fileName = File(current).name.takeIf { it.isNotBlank() } ?: return@forEach
            val restoredFile = File(fontDir, fileName)
            if (restoredFile.exists()) {
                edit.putString(key, restoredFile.absolutePath)
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    private fun normalizeStringPrefs() {
        val stringKeys = setOf(
            PreferKey.language,
            PreferKey.themeMode,
            PreferKey.userAgent,
            PreferKey.customHosts,
            PreferKey.bookGroupStyle,
            PreferKey.bookshelfHiddenTags,
            PreferKey.bookshelfGroupTags,
            PreferKey.ttsEngine,
            PreferKey.prevKeys,
            PreferKey.nextKeys,
            PreferKey.mergedDiscoveryRssTarget,
            PreferKey.modernDiscoverySourceUrl,
            PreferKey.modernRssSourceUrl,
            PreferKey.aiProviderList,
            PreferKey.aiCurrentProviderId,
            PreferKey.aiModelConfigList,
            PreferKey.aiCurrentModelId,
            PreferKey.aiMcpServerList,
            PreferKey.aiChatSessionList,
            PreferKey.aiReadHistoryList,
            PreferKey.themePackageSyncTasks,
            PreferKey.aiCurrentChatSessionId,
            PreferKey.aiChatCompanionList,
            PreferKey.aiCurrentChatCompanionId,
            PreferKey.aiChatAutoSpeakEnabled,
            PreferKey.aiSystemPrompt,
            PreferKey.aiSkillPrompt,
            PreferKey.aiSkillList,
            PreferKey.aiWorldBookList,
            PreferKey.aiTavilyApiKey,
            PreferKey.aiTavilyBaseUrl,
            PreferKey.aiTavilySearchDepth,
            PreferKey.aiTavilyTopic,
            PreferKey.aiBaseUrl,
            PreferKey.aiApiKey,
            PreferKey.aiCurrentModel,
            PreferKey.aiModelList,
            PreferKey.bookshelfLayout,
            PreferKey.bookshelfSort,
            PreferKey.bookshelfReturnToTopAfterRead,
            PreferKey.bookExportFileName,
            PreferKey.bookImportFileName,
            PreferKey.episodeExportFileName,
            PreferKey.fontFolder,
            PreferKey.backupPath,
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir,
            PreferKey.cloudStorageType,
            PreferKey.s3Endpoint,
            PreferKey.s3Region,
            PreferKey.s3Bucket,
            PreferKey.s3Prefix,
            PreferKey.s3AccessKey,
            PreferKey.s3SecretKey,
            PreferKey.s3SessionToken,
            PreferKey.s3Containers,
            PreferKey.s3ContainerSelections,
            PreferKey.exportType,
            PreferKey.chineseConverterType,
            PreferKey.launcherIcon,
            PreferKey.systemTypefaces,
            PreferKey.uiFontPath,
            PreferKey.uiFontPathN,
            PreferKey.titleFontPath,
            PreferKey.titleFontPathN,
            PreferKey.uiFontColor,
            PreferKey.uiFontColorN,
            PreferKey.titleFontColor,
            PreferKey.titleFontColorN,
            PreferKey.bottomBarEffectMode,
            PreferKey.bottomBarLayoutMode,
            PreferKey.bottomBarSidebarGravity,
            PreferKey.uiCornerScale,
            PreferKey.uiCornerScaleN,
            PreferKey.themeCardColor,
            PreferKey.themeCardColorN,
            PreferKey.themeMutedColor,
            PreferKey.themeMutedColorN,
            PreferKey.themeSearchFieldBackgroundColor,
            PreferKey.themeSearchFieldBackgroundColorN,
            PreferKey.themeTabBackgroundColor,
            PreferKey.themeTabBackgroundColorN,
            PreferKey.themeShelfColor,
            PreferKey.themeShelfColorN,
            PreferKey.uiCornerEffectMode,
            PreferKey.bookCoverShadow,
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark,
            PreferKey.screenOrientation,
            PreferKey.exportCharset,
            PreferKey.mangaFooterConfig,
            PreferKey.mangaColorFilter,
            PreferKey.contentSelectMenuConfig,
            PreferKey.contentSelectDefaultOpen,
            PreferKey.contentSelectSearchEngines,
            PreferKey.contentSelectSearchEngineId,
            PreferKey.advancedTitleConfig,
            PreferKey.advancedTitleLottieJson,
            PreferKey.advancedTitleLottiePath,
            PreferKey.advancedTitlePackage,
            PreferKey.advancedTitleHeightFactor,
            PreferKey.doublePageHorizontal,
            PreferKey.defaultBookTreeUri,
            PreferKey.readRecordComponents,
            PreferKey.readRecordRecentSnapshots,
            PreferKey.readRecordGoalConfig,
            PreferKey.localBookImportSort,
            PreferKey.welcomeImage,
            PreferKey.welcomeImageDark,
            PreferKey.welcomeShowText,
            PreferKey.welcomeShowTextDark,
            PreferKey.progressBarBehavior,
            PreferKey.webDavDeviceName,
            PreferKey.defaultHomePage,
            PreferKey.clickImgWay,
            PreferKey.updateToVariant,
            PreferKey.dThemeName,
            PreferKey.dNThemeName,
            PreferKey.bgImage,
            PreferKey.bookInfoBgImage,
            PreferKey.bgImageN,
            PreferKey.bookInfoBgImageN,
            PreferKey.panelBgImage,
            PreferKey.panelBgImageN,
            PreferKey.navigationBarPackageDay,
            PreferKey.navigationBarPackageNight
        )
        val all = appCtx.defaultSharedPreferences.all
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        stringKeys.forEach { key ->
            val value = all[key] ?: return@forEach
            if (value !is String) {
                edit.putString(key, value.toString())
                changed = true
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

}
