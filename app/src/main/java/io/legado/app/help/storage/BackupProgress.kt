package io.legado.app.help.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 备份阶段 */
enum class BackupStage {
    /** 整理 JSON 数据与复制资源包 */
    PREPARING,

    /** 压缩文件到 zip */
    PACKING,

    /** 保存备份文件到目标位置 */
    SAVING,

    /** 上传云端与封面/背景图 */
    UPLOADING,

    /** 全部完成 */
    FINISHED
}

/** 备份进度快照 */
data class BackupProgress(
    val stage: BackupStage,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    /** 确定进度（0~1），无法确定时返回 null */
    val fraction: Float?
        get() = if (totalBytes > 0L) {
            (processedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }
}

/**
 * 备份进度总线：备份任务在 IO 线程写入，进度对话框订阅展示。
 * progress 为 null 表示当前没有备份任务在运行。
 */
object BackupProgressHolder {

    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress = _progress.asStateFlow()

    val isRunning: Boolean
        get() = _progress.value != null

    fun update(value: BackupProgress?) {
        _progress.value = value
    }
}
