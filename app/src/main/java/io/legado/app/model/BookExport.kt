package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.help.config.AppConfig
import io.legado.app.service.ExportBookService
import io.legado.app.utils.startService

/**
 * 缓存/导出编排下沉（台账 #29-B2）。
 *
 * 此前 ExportBookService 的启动编排（导出类型解析、extra 组装）在旧 CacheActivity
 * 与新 HostCacheActivity 各写一份 —— 双份拷贝漂移风险。两壳统一调这里，
 * UI 只负责选书、选路径，不再关心 Intent 细节。
 */
object BookExport {

    /** 当前全局导出格式：txt / epub（AppConfig.exportType）。 */
    fun currentType(): String = if (AppConfig.exportType == 1) "epub" else "txt"

    /**
     * 导出一本书（按当前全局格式）。
     * @param exportPath 已确认的导出目录（SAF 持久化路径，由调用方选择）
     */
    fun export(
        context: Context,
        bookUrl: String,
        exportPath: String,
        exportType: String = currentType(),
    ) {
        context.startService<ExportBookService> {
            action = IntentAction.start
            putExtra("bookUrl", bookUrl)
            putExtra("exportType", exportType)
            putExtra("exportPath", exportPath)
        }
    }

    /**
     * EPUB 自定义章节导出。
     * @param epubScope 章节范围（如 "1-5,8,10-18"），调用方先做 verificationField 校验
     */
    fun exportEpubScope(
        context: Context,
        bookUrl: String,
        exportPath: String,
        epubScope: String,
        epubSize: Int = 1,
    ) {
        context.startService<ExportBookService> {
            action = IntentAction.start
            putExtra("bookUrl", bookUrl)
            putExtra("exportType", "epub")
            putExtra("exportPath", exportPath)
            putExtra("epubSize", epubSize)
            putExtra("epubScope", epubScope)
        }
    }
}
