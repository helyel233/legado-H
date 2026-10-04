package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
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
import io.legado.app.help.config.AppConfig
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskImport
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
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
import androidx.core.content.edit
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME

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
    internal const val fontsDirName = "fonts"
    internal const val bookCacheBackupDirName = "book_cache"

    private const val AT_FONT_PREFIX = "@font:"

    private const val TAG = "Backup"

    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_REPORT_INTERVAL_MS = 80L

    private val mutex = Mutex()

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
            "servers.json",
            "autoTask.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ReadBookConfig.epubConfigFileName,
            ReadBookConfig.epubShareConfigFileName,
            ThemeConfig.configFileName,
            BookCover.configFileName,
            "sourceRuntime.json",
            "config.xml",
            "videoConfig.xml"
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
        Coroutine.async {
            if (shouldBackup()) {
                LogUtils.d(TAG, "auto backup delayed for foreground first frame")
                delay(5000)
            }
            mutex.withLock {
                if (shouldBackup()) {
                    AppLog.put("自动备份触发")
                    AppLog.put("Auto backup trigger")
                    LogUtils.d(TAG, "auto backup trigger")
                    backup(appContext, AppConfig.backupPath)
                } else {
                    AppLog.put("自动备份跳过: 今日已备份")
                    AppLog.put("Auto backup skipped: already backed up today")
                    LogUtils.d(TAG, "auto backup skipped by interval")
                }
            }
        }.onError {
            AppLog.put("自动备份失败\n${it.localizedMessage}", it)
        }
    }

    suspend fun backupLocked(
        context: Context,
        path: String?,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        onProgress: ((BackupProgress) -> Unit)? = null
    ) {
        mutex.withLock {
            withContext(IO) {
                backup(context, path, uploadCloud, uploadWebDavFallback, onProgress)
            }
        }
    }

    private suspend fun backup(
        context: Context,
        path: String?,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        onProgress: ((BackupProgress) -> Unit)? = null
    ) {
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
        AutoTask.all().takeIf { it.isNotEmpty() }?.let { rules ->
            FileUtils.createFileIfNotExist(backupPath + File.separator + "autoTask.json")
                .writeText(AutoTaskImport.exportJson(rules), Charsets.UTF_8)
        }
        exportVisualResourcePackages()
        GSON.toJson(appDb.serverDao.all).let { json ->
            aes.runCatching {
                encryptBase64(json)
            }.getOrDefault(json).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                    .writeText(it)
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
        exportSourceRuntime()
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "config")?.let { sp ->
            val edit = sp.edit()
            appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword, PreferKey.s3SecretKey, PreferKey.s3SessionToken -> {
                            edit.putString(key, aes.runCatching {
                                encryptBase64(value.toString())
                            }.getOrDefault(value.toString()))
                        }

                        PreferKey.s3Containers -> {
                            edit.putString(key, S3ContainerManager.toEncryptedBackupJson(aes) ?: value.toString())
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                            is Set<*> -> edit.putStringSet(
                                key,
                                value.mapNotNull { it?.toString() }.toSet()
                            )
                        }
                    }
                }
            }
            edit.commit()
        }
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "videoConfig")?.let { sp ->
            sp.edit(commit = true) {
                appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val zipSources = arrayListOf<ZipUtils.ZipSource>()
        backupFileNames.forEach { name ->
            zipSources.add(ZipUtils.ZipSource(File(backupPath, name)))
        }
        File(backupPath, advancedTitlePackagesDirName).takeIf { it.exists() }
            ?.let { zipSources.add(ZipUtils.ZipSource(it)) }
        File(backupPath, bubblePackagesDirName).takeIf { it.exists() }
            ?.let { zipSources.add(ZipUtils.ZipSource(it)) }
        File(backupPath, io.legado.app.help.book.highlight.HighlightRules.BACKUP_DIR)
            .takeIf { it.exists() }?.let { zipSources.add(ZipUtils.ZipSource(it)) }
        File(backupPath, io.legado.app.help.reader.ReaderAssets.BACKUP_DIR)
            .takeIf { it.exists() }?.let { zipSources.add(ZipUtils.ZipSource(it)) }
        // 大体积资源直接从源目录压入 zip（entry 前缀与恢复端约定一致），跳过复制到 backupPath 的中间环节
        collectBookCharacterAvatarSource()?.let(zipSources::add)
        zipSources.addAll(collectReaderFontSources())
        zipSources.addAll(collectBackgroundAssetSources())
        zipSources.addAll(collectBookFileSources())
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        var backupSuccess = false
        reporter.report(BackupProgress(BackupStage.PACKING), force = true)
        if (ZipUtils.zipFiles(zipSources, zipFilePath) { processed, total ->
                reporter.report(BackupProgress(BackupStage.PACKING, processed, total))
            }) {
            reporter.report(BackupProgress(BackupStage.SAVING), force = true)
            when {
                path.isNullOrBlank() -> {
                    copyBackup(context.getExternalFilesDir(null)!!, backupFileName) { processed, total ->
                        reporter.report(BackupProgress(BackupStage.SAVING, processed, total))
                    }
                }

                path.isContentScheme() -> {
                    copyBackup(context, path.toUri(), backupFileName) { processed, total ->
                        reporter.report(BackupProgress(BackupStage.SAVING, processed, total))
                    }
                }

                else -> {
                    copyBackup(File(path), backupFileName) { processed, total ->
                        reporter.report(BackupProgress(BackupStage.SAVING, processed, total))
                    }
                }
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
                AppCloudStorage.upBookCovers()
            }
            backupSuccess = true
        } else {
            throw NoStackTraceException("创建备份压缩包失败")
        }
        if (backupSuccess) {
            LocalConfig.lastBackup = System.currentTimeMillis()
            LogUtils.d(TAG, "备份完成")
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        reporter.report(BackupProgress(BackupStage.UPLOADING), force = true)
        ReadBookConfig.getAllPicBgStr().map {
            if (it.contains(File.separator)) {
                File(it)
            } else {
                appCtx.externalFiles.getFile("bg", it)
            }
        }.let {
            AppCloudStorage.upBgs(it.toTypedArray())
        }
        reporter.report(BackupProgress(BackupStage.FINISHED), force = true)
    }

    private fun exportSourceRuntime() {
        if (BackupConfig.ignoreSourceRuntime) return
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
            FileUtils.createFileIfNotExist(backupPath + File.separator + "sourceRuntime.json")
                .writeText(GSON.toJson(SourceRuntimeBackup(items = items)))
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

    /** 角色头像：直接从源目录压入 zip，entry 前缀与恢复目标目录一致 */
    private fun collectBookCharacterAvatarSource(): ZipUtils.ZipSource? {
        val sourceDir = appCtx.externalFiles.getFile("bookCharacters", "avatars")
        if (!sourceDir.isDirectory) {
            return null
        }
        if (sourceDir.listFiles().isNullOrEmpty()) {
            return null
        }
        return ZipUtils.ZipSource(sourceDir, bookCharacterAvatarsDirName)
    }

    private fun exportVisualResourcePackages() {
        io.legado.app.help.reader.ReaderAssets.store.backupTo(
            File(backupPath, io.legado.app.help.reader.ReaderAssets.BACKUP_DIR)
        )
        io.legado.app.help.book.highlight.HighlightRules.store.backupTo(
            File(backupPath, io.legado.app.help.book.highlight.HighlightRules.BACKUP_DIR)
        )
        AdvancedTitlePackageManager.exportPackagesTo(
            File(backupPath, advancedTitlePackagesDirName)
        )
        copyPackageDirectories(
            BubblePackageManager.rootDir,
            File(backupPath, bubblePackagesDirName)
        ) { name -> name !in setOf("temp", "remote_cache", BubblePackageManager.BUILTIN_DIR_NAME) }
    }

    /**
     * 收集阅读界面选择的字体文件：readConfig 各样式的 textFont 引用的字体本体
     * （@font:名 与绝对路径均支持），直接以 fonts/ 前缀压入 zip，
     * 恢复端将文件还原到应用私有 font 目录后引用即可命中。
     */
    private fun collectReaderFontSources(): List<ZipUtils.ZipSource> {
        val refs = linkedSetOf<String>()
        ReadBookConfig.allLayoutConfigs().forEach { config ->
            config.textFont.takeIf { it.isNotBlank() }?.let(refs::add)
        }
        // 界面字体（编辑主题里的 UI/标题字体，日夜间分离）同样引用字体文件
        listOf(
            PreferKey.uiFontPath,
            PreferKey.uiFontPathN,
            PreferKey.titleFontPath,
            PreferKey.titleFontPathN
        ).forEach { key ->
            appCtx.getPrefString(key)?.takeIf { it.isNotBlank() }?.let(refs::add)
        }
        if (refs.isEmpty()) return emptyList()
        val files = refs.mapNotNull { resolveReaderFontFile(it) }
            .distinctBy { it.absolutePath }
        if (files.isNotEmpty()) {
            AppLog.put("备份阅读字体 ${files.size} 个")
        }
        return files.map { ZipUtils.ZipSource(it, fontsDirName) }
    }

    private fun resolveReaderFontFile(ref: String): File? {
        val raw = ref.trim()
        if (raw.isEmpty() || raw.startsWith("http", ignoreCase = true)) return null
        // @font:名 / 裸名：应用私有 font 目录
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
        // 旧数据可能是绝对路径
        if (raw.startsWith("/")) {
            File(raw).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /**
     * 收集主题背景图本体：主界面/书籍详情页/面板背景引用的本地图片文件，
     * 直接以 Restore.backgroundAssetDirNames 对应的目录前缀压入 zip；
     * 恢复端 restoreBackgroundAssets + normalizeBackgroundPrefs 已有完整管线。
     */
    private fun collectBackgroundAssetSources(): List<ZipUtils.ZipSource> {
        val sources = arrayListOf<ZipUtils.ZipSource>()
        Restore.backgroundAssetDirNames.forEach { key ->
            val path = appCtx.getPrefString(key)?.trim().orEmpty()
            if (path.isEmpty() || !path.startsWith("/")) return@forEach
            val file = File(path)
            if (!file.isFile) return@forEach
            sources.add(ZipUtils.ZipSource(file, key))
        }
        return sources
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

    private fun copyPackageDirectories(
        sourceRoot: File,
        targetRoot: File,
        include: (String) -> Boolean
    ) {
        sourceRoot.listFiles()
            ?.filter { it.isDirectory && include(it.name) }
            .orEmpty()
            .forEach { directory ->
                copyDir(directory, File(targetRoot, directory.name))
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
    @Suppress("SameParameterValue")
    private fun copyBackup(
        context: Context,
        uri: Uri,
        fileName: String,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(zipFilePath).use { inputS ->
                copyStreamWithProgress(inputS, outputS, onProgress)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(
        rootFile: File,
        fileName: String,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        FileInputStream(File(zipFilePath)).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                copyStreamWithProgress(inputS, outputS, onProgress)
            }
        }
    }

    private fun copyStreamWithProgress(
        inputS: InputStream,
        outputS: OutputStream,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        val totalBytes = File(zipFilePath).length()
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
