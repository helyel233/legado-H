package io.legado.app.help

import android.os.Build
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.openOutputStream
import splitties.init.appCtx
import java.io.File

/**
 * 诊断信息导出
 *
 * 崩溃与运行日志其实早就落盘了（`externalCache/crash`、`externalCache/logs`），
 * 但以前只能在「我的 → 关于 → 保存日志」里手动打包，还要求已经设置备份目录，
 * 崩溃提示本身更是只能"打开崩溃日志"看一眼、拿不走。
 *
 * 这里把打包逻辑收成一处，供关于页与崩溃提示共用，并额外提供一份可直接粘走的纯文本报告，
 * 免去"装 zip、发文件、再解压"这一圈。
 */
object DiagnosticExporter {

    private const val LOGCAT_TAIL_LINES = 2000
    private const val ZIP_NAME = "logs.zip"
    private const val SUMMARY_NAME = "crash-summary.txt"
    private const val LOGCAT_NAME = "logcat.txt"
    private const val CRASH_DIR = "crash"
    private const val LOG_DIR = "logs"

    /**
     * 把崩溃日志、运行日志、logcat 与崩溃摘要打成一个 zip，写到给定文档目录（一般是备份目录）
     *
     * @return 是否成功写入
     */
    fun exportZip(doc: FileDoc): Boolean {
        val cacheDir = appCtx.externalCache
        val logcatFile = File(cacheDir, LOGCAT_NAME)
        val summaryFile = File(cacheDir, SUMMARY_NAME)
        dumpLogcat(logcatFile)
        runCatching { summaryFile.writeText(summaryText()) }
        val zipFile = File(cacheDir, ZIP_NAME)
        val sources = listOf(
            File(cacheDir, LOG_DIR),
            File(cacheDir, CRASH_DIR),
            logcatFile,
            summaryFile
        )
        val zipped = runCatching { ZipUtils.zipFiles(sources, zipFile) }.getOrDefault(false)
        if (!zipped) {
            zipFile.delete()
            return false
        }
        val saved = runCatching {
            doc.find(ZIP_NAME)?.delete()
            zipFile.inputStream().use { input ->
                doc.createFileIfNotExist(ZIP_NAME)
                    .openOutputStream()
                    .getOrNull()
                    ?.use { output -> input.copyTo(output) }
                    ?: return@runCatching false
            }
            true
        }.getOrDefault(false)
        zipFile.delete()
        summaryFile.delete()
        logcatFile.delete()
        return saved
    }

    /**
     * 生成一份纯文本诊断报告：环境信息 + 上次崩溃摘要 + 最近一次崩溃全文 + logcat 尾部。
     *
     * 面向"直接粘贴发出去"的场景，所以内容自包含、不依赖附件。
     */
    fun diagnosticReport(): String {
        val sb = StringBuilder()
        sb.append(summaryText())
        latestCrashFile()?.let { crashFile ->
            runCatching {
                sb.append("\n===== 最近一次崩溃全文 =====\n")
                sb.append(crashFile.readText().take(MAX_CRASH_TEXT))
            }
        }
        runCatching {
            sb.append("\n===== logcat 末尾（最近 $LOGCAT_TAIL_LINES 行，已截断到 ${MAX_LOGCAT_TEXT} 字符）=====\n")
            sb.append(runLogcat().takeLast(MAX_LOGCAT_TEXT))
        }
        return sb.toString()
    }

    /**
     * 环境信息 + 上次崩溃摘要 + 崩溃文件清单（zip 里的 summary 与报告开头都用它）
     */
    private fun summaryText(): String {
        val sb = StringBuilder()
        sb.append("===== 环境信息 =====\n")
        sb.append("versionName=").append(AppConst.appInfo.versionName).append('\n')
        sb.append("versionCode=").append(AppConst.appInfo.versionCode).append('\n')
        sb.append("packageName=").append(appCtx.packageName).append('\n')
        sb.append("android=").append(Build.VERSION.RELEASE)
            .append("(API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        val summary = LocalConfig.crashSummary
        if (summary.isNotBlank()) {
            sb.append("\n===== 上次崩溃 =====\n")
            sb.append(summary).append('\n')
        }
        val crashes = crashFiles()
        if (crashes.isNotEmpty()) {
            sb.append("\n===== 已记录崩溃文件（最近 ${crashes.size} 个）=====\n")
            crashes.forEach { sb.append(it.name).append('\n') }
        } else {
            sb.append("\n（没有已记录的崩溃文件）\n")
        }
        return sb.toString()
    }

    private fun crashFiles(): List<File> {
        val dir = File(appCtx.externalCache, CRASH_DIR)
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun latestCrashFile(): File? = crashFiles().firstOrNull()

    /**
     * 取 logcat 最近若干行。取不到就返回空，不因此影响导出。
     */
    private fun runLogcat(): String = runCatching {
        val process = Runtime.getRuntime().exec("logcat -d -t $LOGCAT_TAIL_LINES")
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun dumpLogcat(file: File) {
        runCatching {
            val process = Runtime.getRuntime().exec("logcat -d -t $LOGCAT_TAIL_LINES")
            file.outputStream().use { output ->
                process.inputStream.copyTo(output)
            }
        }.onFailure {
            AppLog.put("导出 logcat 失败\n${it.localizedMessage}", it)
        }
    }

    /** 崩溃全文截断长度，避免极端情况下文本报告过大 */
    private const val MAX_CRASH_TEXT = 20_000

    /**
     * logcat 截断长度。
     *
     * 剪贴板走 `sendToClip`，超过 64KB 会被直接砍掉尾巴，所以这里先自己收口，
     * 保证"环境 + 崩溃全文 + logcat"三段都能完整粘出去。
     */
    private const val MAX_LOGCAT_TEXT = 20_000
}