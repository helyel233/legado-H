package io.legado.app.help.review

import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import java.io.File
import splitties.init.appCtx

/**
 * 段评预热失败退避表的持久化。
 *
 * 为什么需要落盘：退避表此前是纯内存 Map，进程一重启就清零——对「源规则坏了」
 * 这种持续性失败，用户每开一次 App 就要把最近几章重新白抓一遍（每章一轮
 * click/js + WebView 抓取，几十秒）。落盘后跨启动生效，配合指数退避才真正
 * 把无效抓取压下去。
 *
 * 与 [ReviewSnapshotManager] 的待刷新标记同约定：应用私有 filesDir 下的单个
 * JSON，不受 book_cache 清理影响；读失败按空表处理，坏文件不阻塞预热。
 */
internal object ReviewBackoffStore {

    /** 一条落盘记录：键 + 失败次数 + 解禁时间，与策略层的 [ReviewBackoffPolicy.Failure] 同构 */
    private data class Payload(
        val entries: Map<String, ReviewBackoffPolicy.Failure> = emptyMap(),
    )

    private val file by lazy { File(appCtx.filesDir, "review_preheat_backoff.json") }

    /**
     * 读取退避表，并按当前时间裁剪：过期的丢掉，超上限的保留最顽固的。
     * 在 IO 线程调用（会读文件）。
     */
    @Synchronized
    fun load(now: Long, maxEntries: Int): Map<String, ReviewBackoffPolicy.Failure> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val payload = GSON.fromJson(file.readText(), Payload::class.java)
                ?: return@runCatching emptyMap()
            ReviewBackoffPolicy.retained(payload.entries, now, maxEntries)
        }.getOrElse { error ->
            AppLog.put("读取段评预热退避表失败，按空表处理\n${error.localizedMessage}", error)
            emptyMap()
        }
    }

    /** 写入退避表；失败只记日志，预热本身不该因为写不了盘而中断 */
    @Synchronized
    fun save(entries: Map<String, ReviewBackoffPolicy.Failure>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(GSON.toJson(Payload(entries)))
        }.onFailure { error ->
            AppLog.put("保存段评预热退避表失败\n${error.localizedMessage}", error)
        }
    }
}
