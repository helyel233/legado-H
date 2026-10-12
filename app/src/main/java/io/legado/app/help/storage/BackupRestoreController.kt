package io.legado.app.help.storage

import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3CapacityFullException
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.flow.StateFlow
import splitties.init.appCtx
import java.io.File

/**
 * 备份 / 恢复编排下沉（台账 #29-B7）。
 *
 * 此前这段流程写在 BackupConfigFragment 里（触发备份、进度写入、S3 满回退判定、
 * 云存储类型回退、资源包发现、本地/云端恢复）。V5 备份页迁移前抽出共用，
 * 旧页与新壳都只负责：选路径、选文件、弹确认、渲染进度。
 *
 * 互斥契约：备份走 [Backup.backupLocked]、恢复走 [Restore] 的 locked 入口，
 * 本控制器不自行加锁（Backup.mutex 与 Restore.isRestoring 是跨流程契约）。
 */
object BackupRestoreController {

    // ---------------------------------------------------------------- 配置完备性

    fun hasCloudStorageAccount(): Boolean {
        return when (CloudStorageType.from(appCtx.getPrefString(PreferKey.cloudStorageType))) {
            CloudStorageType.WEBDAV -> hasWebDavAccount()
            CloudStorageType.S3 -> hasS3Account()
        }
    }

    fun hasWebDavAccount(): Boolean {
        return !appCtx.getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !appCtx.getPrefString(PreferKey.webDavPassword).isNullOrBlank()
    }

    fun hasS3Account(): Boolean {
        return AppCloudStorage.listContainers().any {
            it.enabled && it.endpoint.isNotBlank() && it.bucket.isNotBlank()
                    && it.accessKey.isNotBlank() && it.secretKey.isNotBlank()
        }
    }

    // ---------------------------------------------------------------- 进度

    /** 备份进度流（UI 直接 collect；null = 当前无任务）。 */
    fun backupProgress(): StateFlow<BackupProgress?> = BackupProgressHolder.progress

    fun isBackupRunning(): Boolean = BackupProgressHolder.isRunning

    // ---------------------------------------------------------------- 备份编排

    /**
     * 启动备份：全局作用域执行（点「后台继续」或离开页面不中断），
     * 进度写入 [BackupProgressHolder]，结束时清空进度并回调 [onError]（成功为 null）。
     */
    fun startBackup(
        backupPath: String,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        onError: (Throwable?) -> Unit,
    ) {
        BackupProgressHolder.update(BackupProgress(BackupStage.PREPARING))
        Coroutine.async(Coroutine.defaultScope) {
            Backup.backupLocked(appCtx, backupPath, uploadCloud, uploadWebDavFallback) { progress ->
                BackupProgressHolder.update(progress)
            }
        }.onSuccess {
            BackupProgressHolder.update(null)
            onError(null)
        }.onError { e ->
            BackupProgressHolder.update(null)
            onError(e)
        }
    }

    // ---------------------------------------------------------------- S3 满容量回退

    /** S3 全部容器已满且未设「不再提醒」时，备份前应先询问是否改用 WebDav。 */
    fun shouldShowS3FullWebDavFallback(): Boolean {
        if (CloudStorageType.from(appCtx.getPrefString(PreferKey.cloudStorageType)) != CloudStorageType.S3) {
            return false
        }
        if (s3FullWebDavFallbackNeverRemind()) return false
        val items = AppCloudStorage.listContainers().filter { it.enabled }
        return items.isNotEmpty() && items.all { it.isFull }
    }

    /** 备份失败后是否可走「改用 WebDav / 仅本地」回退询问。 */
    fun shouldShowS3FullFallbackAfterFailure(): Boolean = !s3FullWebDavFallbackNeverRemind()

    fun isS3CapacityFull(throwable: Throwable): Boolean = throwable is S3CapacityFullException

    fun s3FullWebDavFallbackNeverRemind(): Boolean =
        appCtx.defaultSharedPreferences.getBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, false)

    fun setS3FullWebDavFallbackNeverRemind() {
        appCtx.defaultSharedPreferences.edit {
            putBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, true)
        }
    }

    // ---------------------------------------------------------------- 云端恢复

    /**
     * 确保云存储可用：当前类型已配置则直接 upConfig；否则按 S3 → WEBDAV 回退，
     * 并**改写偏好**。返回最终类型 —— UI 侧据此刷新设置树（不在后端直接刷 UI）。
     */
    suspend fun ensureCloudStorageForRestore(): CloudStorageType {
        val currentType = CloudStorageType.from(appCtx.getPrefString(PreferKey.cloudStorageType))
        if (currentType == CloudStorageType.S3 && hasS3Account()) {
            AppCloudStorage.upConfig()
            return currentType
        }
        if (currentType == CloudStorageType.WEBDAV && hasWebDavAccount()) {
            AppCloudStorage.upConfig()
            return currentType
        }
        val fallbackType = when {
            hasS3Account() -> CloudStorageType.S3
            hasWebDavAccount() -> CloudStorageType.WEBDAV
            else -> throw io.legado.app.exception.NoStackTraceException("Cloud storage is not configured")
        }
        appCtx.defaultSharedPreferences.edit {
            putString(PreferKey.cloudStorageType, fallbackType.name)
        }
        AppCloudStorage.upConfig()
        return fallbackType
    }

    /** 云存储类型是否因回退而改变（供 UI 决定是否重建设置树）。 */
    fun currentCloudStorageType(): CloudStorageType =
        CloudStorageType.from(appCtx.getPrefString(PreferKey.cloudStorageType))

    /** 列出云端备份名（内部先确保云存储可用）。 */
    suspend fun listCloudBackups(): List<String> {
        ensureCloudStorageForRestore()
        return AppCloudStorage.getBackupNames()
    }

    /** 列出云端资源包名（字体/背景等备份）。 */
    suspend fun listCloudAssetNames(): List<String> = AppCloudStorage.listAssetsBackupNames()

    suspend fun restoreFromCloud(name: String, assetsFileNames: List<String> = emptyList()) {
        AppCloudStorage.restore(name, assetsFileNames)
    }

    // ---------------------------------------------------------------- 本地恢复

    /**
     * 本地恢复的资源包发现：备份路径目录下枚举 backup_assets 前缀的 zip，
     * 返回 uri → 显示名（供弹窗确认）；目录不可枚举时返回空。
     */
    suspend fun findLocalAssetsUris(): List<Pair<Uri, String>> {
        val path = AppConfig.backupPath
        if (path.isNullOrBlank()) return emptyList()
        return runCatching {
            if (path.isContentScheme()) {
                DocumentFile.fromTreeUri(appCtx, Uri.parse(path))?.listFiles()
                    ?.filter { it.isFile && it.name?.startsWith("backup_assets") == true }
                    ?.mapNotNull { doc -> doc.name?.let { doc.uri to it } }
                    .orEmpty()
            } else {
                File(path).listFiles()
                    ?.filter { it.isFile && it.name.startsWith("backup_assets") }
                    ?.map { Uri.fromFile(it) to it.name }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun restoreFromLocal(uri: Uri, assetsUris: List<Uri> = emptyList()) {
        Restore.restore(appCtx, uri, assetsUris)
    }
}
