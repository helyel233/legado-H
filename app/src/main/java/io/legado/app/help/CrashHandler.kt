package io.legado.app.help

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Looper
import android.webkit.WebSettings
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.debug.DebugEventCenter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import io.legado.app.model.debug.DebugLogScope
import kotlinx.coroutines.launch
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.getFile
import io.legado.app.utils.longToastOnUiLegacy
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.writeText
import splitties.init.appCtx
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 异常管理类
 */
class CrashHandler(val context: Context) : Thread.UncaughtExceptionHandler {

    /**
     * 系统默认UncaughtExceptionHandler
     */
    private var mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        //设置该CrashHandler为系统默认的
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /**
     * uncaughtException 回调函数
     */
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (shouldAbsorb(ex)) {
            AppLog.put("发生未捕获的异常\n${ex.localizedMessage}", ex)
            // 只在主线程重入消息循环“吸住”崩溃；后台线程没有准备 Looper，
            // Looper.loop() 会再抛 "No Looper" 二次异常，反而把进程判死。
            // 后台线程直接放行（线程安静退出、进程存活），与吸收语义一致。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Looper.loop()
            }
        } else {
            runCatching { ReadAloud.stop(context) }
            handleException(ex)
            mDefaultHandler?.uncaughtException(thread, ex)
        }
    }

    private fun shouldAbsorb(e: Throwable): Boolean {
        return when {
            e::class.simpleName == "CannotDeliverBroadcastException" -> true
            e is SecurityException && e.message?.contains(
                "nor current process has android.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS",
                true
            ) == true -> true

            else -> false
        }
    }

    /**
     * 处理该异常
     */
    private fun handleException(ex: Throwable?) {
        if (ex == null) return
        recordCrashSummary(ex)
        //记录到调试事件中心（分类：崩溃），便于下次启动后在调试日志页回看
        DebugLogScope.launch {
            runCatching {
                DebugEventCenter.emit(
                    DebugEvent(
                        level = DebugLevel.ERROR,
                        category = DebugCategory.CRASH,
                        message = ex.localizedMessage ?: ex.toString(),
                        detail = ex.stackTraceToString(),
                        throwable = ex
                    )
                )
            }
        }
        //保存日志文件。这是崩溃收尾路径，绝不能因它抛异常而中断后面的收尾
        runCatching { saveCrashInfo2File(ex) }
            .onFailure { android.util.Log.e("CrashHandler", "save crash log failed", it) }
        if ((ex is OutOfMemoryError || ex.cause is OutOfMemoryError) && AppConfig.recordHeapDump) {
            doHeapDump()
        }
        // 给用户的简短提示：不再弹完整堆栈（对普通用户是噪音且暴露路径信息）。
        // 详细日志已同步落盘，下次启动有崩溃提示，关于页可复制诊断信息。
        runCatching {
            context.longToastOnUiLegacy(context.getString(R.string.crash_recorded_hint))
        }
        // 留出提示展示窗口后交给系统默认处理；崩溃收尾路径本身不允许再抛异常
        Thread.sleep(3000)
    }

    /**
     * 记一行可读的崩溃摘要（时间 + 页面 + 异常类型 + 消息）。
     *
     * 崩溃现场往往在后台线程或 WebView 回调中，只看堆栈很难判断是哪个页面触发的，
     * 因此在崩溃瞬间把当前前台页面一并留下，下次启动的提示就能直接说清"崩在哪、报什么"。
     */
    private fun recordCrashSummary(ex: Throwable) {
        kotlin.runCatching {
            val page = LifecycleHelp.currentPageName
            val errorType = ex::class.java.simpleName.takeIf { it.isNotBlank() }
                ?: ex::class.java.name
            // SimpleDateFormat 非线程安全：saveCrashInfo2File 也可能从 RegexExtensions
            // 的任意线程进来，共享实例会产出脏时间甚至抛数组越界，这里每次新建
            val summaryTime = SimpleDateFormat("MM-dd HH:mm:ss").format(Date())
            val summary = buildString {
                append(summaryTime)
                append(" · ")
                append(page)
                append('\n')
                append(errorType)
                ex.localizedMessage?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it.take(120))
                }
            }
            // 崩溃路径必须 commit 同步落盘：随后进程随时可能被杀，
            // apply 的异步落盘会丢，丢了下次启动就没有崩溃提示（证据链第一环失效）
            LocalConfig.edit(commit = true) {
                putBoolean("appCrash", true)
                putString("crashPage", page)
                putString("crashSummary", summary)
            }
        }
    }

    companion object {
        /**
         * 存储异常和参数信息
         */
        private val paramsMap by lazy {
            val map = LinkedHashMap<String, String>()
            kotlin.runCatching {
                //获取系统信息
                map["MANUFACTURER"] = Build.MANUFACTURER
                map["BRAND"] = Build.BRAND
                map["MODEL"] = Build.MODEL
                map["SDK_INT"] = Build.VERSION.SDK_INT.toString()
                map["RELEASE"] = Build.VERSION.RELEASE
                map["WebViewUserAgent"] = try {
                    WebSettings.getDefaultUserAgent(appCtx)
                } catch (e: Throwable) {
                    e.toString()
                }
                map["packageName"] = appCtx.packageName
                map["heapSize"] = Runtime.getRuntime().maxMemory().toString()
                //获取app版本信息
                AppConst.appInfo.let {
                    map["versionName"] = it.versionName
                    map["versionCode"] = it.versionCode.toString()
                }
            }
            map
        }

        /**
         * 保存错误信息到文件中
         */
        @SuppressLint("SimpleDateFormat")
        fun saveCrashInfo2File(ex: Throwable) {
            // SimpleDateFormat 非线程安全且本方法可能从任意线程进来，时间戳每次新建
            val timeFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")
            val sb = StringBuilder()
            // 崩溃现场常在后台线程，栈顶未必是页面，这里把时间与前台页面写在最前面方便定位
            sb.append("crashTime=").append(timeFormat.format(Date())).append("\n")
            sb.append("page=").append(LifecycleHelp.currentPageName).append("\n")
            for ((key, value) in paramsMap) {
                sb.append(key).append("=").append(value).append("\n")
            }

            val writer = StringWriter()
            val printWriter = PrintWriter(writer)
            ex.printStackTrace(printWriter)
            var cause: Throwable? = ex.cause
            while (cause != null) {
                cause.printStackTrace(printWriter)
                cause = cause.cause
            }
            printWriter.close()
            val result = writer.toString()
            sb.append(result)
            val crashLog = sb.toString()
            val timestamp = System.currentTimeMillis()
            val time = timeFormat.format(Date())
            val fileName = "crash-$time-$timestamp.log"
            try {
                val backupPath = AppConfig.backupPath
                    ?: throw NoStackTraceException("备份路径未配置")
                val uri = Uri.parse(backupPath)
                val fileDoc = FileDoc.fromUri(uri, true)
                fileDoc.createFileIfNotExist(fileName, "crash")
                    .writeText(crashLog)
            } catch (_: Exception) {
            }
            kotlin.runCatching {
                appCtx.externalCacheDir?.let { rootFile ->
                    val exceedTimeMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                    rootFile.getFile("crash").listFiles()?.forEach {
                        if (it.lastModified() < exceedTimeMillis) {
                            it.delete()
                        }
                    }
                    FileUtils.createFileIfNotExist(rootFile, "crash", fileName)
                        .writeText(crashLog)
                }
            }
        }

        /**
         * 进行堆转储
         */
        fun doHeapDump(manually: Boolean = false) {
            val heapDir = appCtx
                .externalCache
                .getFile("heapDump")
            heapDir.createFolderReplace()
            val fileName = if (manually) {
                "heap-dump-manually-${System.currentTimeMillis()}.hprof"
            } else {
                "heap-dump-${System.currentTimeMillis()}.hprof"
            }
            val heapFile = heapDir.getFile(fileName)
            val heapDumpName = heapFile.absolutePath
            Debug.dumpHprofData(heapDumpName)
        }

    }

}
