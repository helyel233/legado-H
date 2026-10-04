package io.legado.app.service

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.IntentType
import io.legado.app.utils.openFileUri
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Request
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * 下载文件
 *
 * 使用应用内 OkHttp 网络栈下载，避免系统 DownloadManager 直连
 * 下载源失败时长时间无进度并静默转入暂停状态。
 * 支持断点续传、失败自动重试，并将真实进度与错误反馈到通知。
 */
class DownloadService : BaseService() {
    private val groupKey = "${appCtx.packageName}.download"
    private val downloads = ConcurrentHashMap<Long, DownloadInfo>()
    private val completedDownloads = ConcurrentHashMap<Long, CompletedInfo>()
    private val idGenerator = AtomicLong(0)

    /**
     * 共享 okHttpClient 带 60s callTimeout，无法完成大文件下载，
     * 派生一个无整段超时、读超时适中的客户端用于下载。
     */
    private val downloadClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val progressTempDir: File
        get() = File(cacheDir, "download").apply { mkdirs() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> startDownload(
                intent.getStringExtra("url"),
                intent.getStringExtra("fileName"),
                readHeaders(intent)
            )

            IntentAction.play -> {
                val id = intent.getLongExtra("downloadId", 0)
                val uri = completedDownloads[id]
                if (uri != null) {
                    openFileUri(uri.uri, IntentType.from(uri.fileName))
                } else {
                    toastOnUi("未完成,下载的文件夹Download")
                }
            }

            IntentAction.stop -> {
                val downloadId = intent.getLongExtra("downloadId", 0)
                removeDownload(downloadId)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun readHeaders(intent: Intent): Map<String, String> {
        val names = intent.getStringArrayExtra("headerNames").orEmpty()
        val values = intent.getStringArrayExtra("headerValues").orEmpty()
        return names.mapIndexedNotNull { index, name ->
            val value = values.getOrNull(index)?.takeIf { it.isNotBlank() }
            if (name.isBlank() || value == null) {
                null
            } else {
                name to value
            }
        }.toMap()
    }

    /**
     * 开始下载
     */
    @Synchronized
    private fun startDownload(url: String?, fileName: String?, headers: Map<String, String> = emptyMap()) {
        if (url == null || fileName == null) {
            if (downloads.isEmpty()) {
                stopSelf()
            }
            return
        }
        if (downloads.values.any { it.url == url }) {
            toastOnUi("已在下载列表")
            return
        }
        val id = idGenerator.incrementAndGet()
        val info = DownloadInfo(
            id = id,
            url = url,
            fileName = fileName,
            notificationId = NotificationId.Download + id.toInt(),
            headers = headers
        )
        downloads[id] = info
        upDownloadNotification(info, getString(R.string.wait_download), 0, 0)
        info.job = launchDownload(info)
    }

    private fun launchDownload(info: DownloadInfo): Job {
        return lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(progressTempDir, "${info.fileName}.part")
            try {
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        performDownload(info, tempFile)
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            throw e
                        }
                        AppLog.put("下载${info.fileName}出错", e)
                        if (attempt >= MAX_RETRY || !lifecycleScope.isActive) {
                            throw e
                        }
                        upDownloadNotification(
                            info,
                            "${getString(R.string.download_error)}，${RETRY_DELAY_SECONDS * attempt}s后重试($attempt/$MAX_RETRY)",
                            0,
                            0
                        )
                        delay(min(30_000L, RETRY_DELAY_SECONDS * attempt * 1000L))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDownloadError(info, e)
            }
        }
    }

    private suspend fun performDownload(info: DownloadInfo, tempFile: File) {
        val downloaded = tempFile.length()
        val builder = Request.Builder().url(info.url)
        info.headers.forEach { (name, value) ->
            builder.header(name, value)
        }
        if (downloaded > 0) {
            builder.header("Range", "bytes=$downloaded-")
        }
        val response = downloadClient.newCall(builder.build()).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("服务器返回错误(${resp.code})")
            }
            val body = resp.body ?: throw IOException("服务器返回空数据")
            var offset = downloaded
            if (!(resp.code == 206 && downloaded > 0)) {
                // 服务器不支持续传，从头下载
                offset = 0
                tempFile.parentFile?.mkdirs()
                tempFile.delete()
                tempFile.createNewFile()
            }
            val total = body.contentLength().let { if (it >= 0) it + offset else -1L }
            body.byteStream().use { input ->
                FileOutputStream(tempFile, offset > 0).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var lastNotify = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        offset += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 800) {
                            lastNotify = now
                            upDownloadNotification(
                                info,
                                "${getString(R.string.downloading)} ${formatBytes(offset, total)}",
                                if (total > 0) (offset * 100 / total).toInt() else 0,
                                if (total > 0) 100 else 0
                            )
                        }
                    }
                }
            }
            val uri = publishFile(tempFile, info.fileName)
            successDownload(info, uri)
        }
    }

    /**
     * 下载完成，保存到公共下载目录
     */
    private fun publishFile(tempFile: File, fileName: String): Uri {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertViaMediaStore(tempFile, fileName)
            } else {
                copyToPublicDownloads(tempFile, fileName)
            }
            if (uri != null) {
                return uri
            }
        } catch (e: Exception) {
            AppLog.put("保存${fileName}到公共下载目录失败", e)
        }
        // 兜底：保存到应用专属下载目录
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(cacheDir, "downloads").apply { mkdirs() }
        val dest = uniqueFile(File(dir, fileName))
        tempFile.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    private fun insertViaMediaStore(tempFile: File, fileName: String): Uri? {
        val mimeType = IntentType.from(fileName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        contentResolver.openOutputStream(uri)?.use { output ->
            tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: run {
            contentResolver.delete(uri, null, null)
            return null
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun copyToPublicDownloads(tempFile: File, fileName: String): Uri? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.canWrite()) {
            return null
        }
        dir.mkdirs()
        val dest = uniqueFile(File(dir, fileName))
        tempFile.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) {
            return file
        }
        val name = file.nameWithoutExtension
        val ext = file.extension
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "$name($index).$ext")
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }

    /**
     * 下载成功
     */
    @Synchronized
    private fun successDownload(info: DownloadInfo, uri: Uri) {
        downloads.remove(info.id)
        completedDownloads[info.id] = CompletedInfo(uri, info.fileName)
        File(progressTempDir, "${info.fileName}.part").delete()
        upDownloadNotification(info, getString(R.string.download_success), 100, 100)
        openDownload(info, uri)
        checkStopSelf()
    }

    private fun onDownloadError(info: DownloadInfo, error: Throwable) {
        downloads.remove(info.id)
        File(progressTempDir, "${info.fileName}.part").delete()
        upDownloadNotification(
            info,
            "${getString(R.string.download_error)}: ${error.localizedMessage ?: error.message ?: "网络异常"}",
            0,
            0
        )
        checkStopSelf()
    }

    /**
     * 取消下载
     */
    @Synchronized
    private fun removeDownload(downloadId: Long) {
        val info = downloads.remove(downloadId)
        info?.job?.cancel()
        if (info != null) {
            File(progressTempDir, "${info.fileName}.part").delete()
            notificationManager.cancel(info.notificationId)
        } else {
            notificationManager.cancel(NotificationId.Download + downloadId.toInt())
        }
        completedDownloads.remove(downloadId)
        checkStopSelf()
    }

    private fun checkStopSelf() {
        if (downloads.isEmpty()) {
            stopSelf()
        }
    }

    /**
     * 打开下载文件
     */
    private fun openDownload(info: DownloadInfo, uri: Uri) {
        kotlin.runCatching {
            openFileUri(uri, IntentType.from(info.fileName))
        }.onFailure {
            AppLog.put("打开下载文件${info.fileName}出错", it)
        }
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.DownloadService, notification)
    }

    /**
     * 更新通知
     */
    private fun upDownloadNotification(
        info: DownloadInfo,
        content: String,
        progress: Int,
        progressMax: Int
    ) {
        val notificationBuilder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle("${info.fileName} $content")
            .setOnlyAlertOnce(true)
            .setContentIntent(
                servicePendingIntent<DownloadService>(IntentAction.play, info.notificationId) {
                    putExtra("downloadId", info.id)
                }
            )
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, info.notificationId) {
                    putExtra("downloadId", info.id)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setWhen(info.startTime)
        if (progressMax > 0) {
            notificationBuilder.setProgress(100, progress, false)
        } else {
            notificationBuilder.setProgress(0, 0, true)
        }
        notificationManager.notify(info.notificationId, notificationBuilder.build())
    }

    private fun formatBytes(downloaded: Long, total: Long): String {
        return if (total > 0) {
            String.format(
                Locale.ROOT,
                "%.1fMB/%.1fMB",
                downloaded / BYTES_PER_MB,
                total / BYTES_PER_MB
            )
        } else {
            String.format(Locale.ROOT, "%.1fMB", downloaded / BYTES_PER_MB)
        }
    }

    private data class CompletedInfo(
        val uri: Uri,
        val fileName: String
    )

    private data class DownloadInfo(
        val id: Long,
        val url: String,
        val fileName: String,
        val notificationId: Int,
        val headers: Map<String, String> = emptyMap(),
        val startTime: Long = System.currentTimeMillis(),
        var job: Job? = null
    )

    companion object {
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_SECONDS = 3L
        private const val BYTES_PER_MB = 1048576.0
    }

}
