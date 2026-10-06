package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File
import java.util.UUID

/**
 * 恢复前简单快照：把恢复将覆盖的配置文件/目录复制到临时快照目录，
 * 恢复过程异常时尽力复制回去。
 *
 * 不做状态机、不做原子发布：可出错面只有"复制本身"，失败只记日志，
 * 绝不阻断恢复。
 */
internal object RestoreSnapshot {

    private const val SNAPSHOT_DIR_PREFIX = ".restore_snapshot-"

    class Snapshot internal constructor(
        private val snapshotDir: File,
        private val entries: List<Entry>
    ) {
        internal class Entry(
            val target: File,
            val snapshot: File?,
            val existed: Boolean
        )

        /** 尽力回滚：失败项记日志后继续处理其余项。 */
        fun rollback(reason: String) {
            AppLog.put("恢复失败，回滚快照：$reason")
            entries.forEach { entry ->
                runCatching {
                    if (!entry.existed) {
                        // 恢复前不存在的目标：回滚即删除恢复新建的内容
                        entry.target.deleteRecursively()
                        return@runCatching
                    }
                    val snapshot = entry.snapshot ?: return@runCatching
                    if (entry.target.isDirectory) {
                        entry.target.deleteRecursively()
                    }
                    snapshot.copyRecursivelyTo(entry.target)
                }.onFailure {
                    AppLog.put("回滚失败：${entry.target.absolutePath}\n${it.localizedMessage}", it)
                }
            }
        }

        fun delete() {
            runCatching { snapshotDir.deleteRecursively() }
        }
    }

    fun create(backupPath: String): Snapshot {
        val backupDir = File(backupPath)
        val targets = buildTargets(backupDir)
        val snapshotDir = File(appCtx.cacheDir, "$SNAPSHOT_DIR_PREFIX${UUID.randomUUID()}")
        check(snapshotDir.mkdir()) { "无法创建恢复快照目录" }
        val entries = arrayListOf<Snapshot.Entry>()
        targets.forEachIndexed { index, target ->
            val snapshotFile = File(snapshotDir, index.toString())
            val existed = target.exists()
            runCatching {
                if (target.isFile) {
                    target.copyTo(snapshotFile, overwrite = true)
                    entries.add(Snapshot.Entry(target, snapshotFile, true))
                } else if (target.isDirectory) {
                    target.copyRecursivelyTo(snapshotFile)
                    entries.add(Snapshot.Entry(target, snapshotFile, true))
                } else {
                    // 恢复前不存在：回滚时删除恢复新建的内容
                    entries.add(Snapshot.Entry(target, null, false))
                }
            }.onFailure {
                // 快照失败不阻断恢复，只记日志
                AppLog.put("恢复快照失败：${target.absolutePath}\n${it.localizedMessage}", it)
            }
        }
        return Snapshot(snapshotDir, entries)
    }

    /** 应用启动时清理上次恢复中途退出残留的快照目录。 */
    fun cleanupOrphans() {
        appCtx.cacheDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith(SNAPSHOT_DIR_PREFIX)) {
                runCatching { file.deleteRecursively() }
            }
        }
    }

    /** 恢复将覆盖的目标：备份解压目录里存在对应条目时，才快照其应用侧落点。 */
    private fun buildTargets(backupDir: File): List<File> {
        val targets = arrayListOf<File>()
        fun addIfBackupExists(fileName: String, targetPath: String) {
            if (File(backupDir, fileName).exists()) {
                targets.add(File(targetPath))
            }
        }
        addIfBackupExists(ThemeConfig.configFileName, ThemeConfig.configFilePath)
        addIfBackupExists(ReadBookConfig.configFileName, ReadBookConfig.configFilePath)
        addIfBackupExists(ReadBookConfig.shareConfigFileName, ReadBookConfig.shareConfigFilePath)
        addIfBackupExists(ReadBookConfig.epubConfigFileName, ReadBookConfig.epubConfigFilePath)
        addIfBackupExists(ReadBookConfig.epubShareConfigFileName, ReadBookConfig.epubShareConfigFilePath)
        addIfBackupExists(EpubReaderTemplateStore.fileName, EpubReaderTemplateStore.filePath)
        if (File(backupDir, DirectLinkUpload.ruleFileName).isFile) {
            DirectLinkUpload.configStorageFile()?.let(targets::add)
        }
        addIfBackupExists("config.xml", sharedPrefsFile("${appCtx.packageName}_preferences").absolutePath)
        addIfBackupExists("videoConfig.xml", sharedPrefsFile(VIDEO_PREF_NAME).absolutePath)
        Restore.backgroundAssetDirNames.forEach { dirName ->
            if (File(backupDir, dirName).isDirectory) {
                targets.add(appCtx.externalFiles.getFile(dirName))
            }
        }
        if (File(backupDir, "themePackages").isDirectory) {
            targets.add(ThemePackageManager.rootDir)
        }
        if (File(backupDir, "navigationBarPackages").isDirectory) {
            targets.add(NavigationBarIconConfig.rootDir)
        }
        if (File(backupDir, "topBarPackages").isDirectory) {
            targets.add(TopBarConfig.rootDir)
        }
        if (File(backupDir, "coverCollections").isDirectory) {
            targets.add(CoverCollectionManager.rootDir)
        }
        if (File(backupDir, Backup.advancedTitlePackagesDirName).isDirectory) {
            targets.add(AdvancedTitlePackageManager.rootDir)
        }
        if (File(backupDir, Backup.bubblePackagesDirName).isDirectory) {
            targets.add(BubblePackageManager.rootDir)
        }
        if (File(backupDir, io.legado.app.help.book.highlight.HighlightRules.BACKUP_DIR).isDirectory) {
            targets.add(io.legado.app.help.book.highlight.HighlightRules.store.directory)
        }
        if (File(backupDir, io.legado.app.help.reader.ReaderAssets.BACKUP_DIR).isDirectory) {
            targets.add(io.legado.app.help.reader.ReaderAssets.store.directory)
        }
        return targets.distinctBy { it.absolutePath }
    }

    private fun File.copyRecursivelyTo(target: File) {
        if (isFile) {
            target.parentFile?.mkdirs()
            copyTo(target, overwrite = true)
        } else if (isDirectory) {
            if (!target.exists()) {
                target.mkdirs()
            }
            listFiles()?.forEach { child ->
                child.copyRecursivelyTo(File(target, child.name))
            }
        }
    }

    private fun sharedPrefsFile(name: String): File {
        return File(appCtx.applicationInfo.dataDir, "shared_prefs/$name.xml")
    }
}
