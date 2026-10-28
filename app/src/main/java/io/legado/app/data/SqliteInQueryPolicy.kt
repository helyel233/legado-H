package io.legado.app.data

/**
 * SQLite `IN (...)` 查询的参数分片策略（纯函数）。
 *
 * SQLite 的绑定参数上限是 999（Android 11 及以下更严格），一次 IN 传入上千个
 * bookUrl / id 会直接抛 SQLiteException。分片逻辑集中在这里，方便单测锁定边界。
 */
internal object SqliteInQueryPolicy {

    /** 单次绑定的安全上限：999 是硬上限，取 500 留余量 */
    const val DEFAULT_CHUNK_SIZE = 500

    /** 硬上限 */
    const val SQLITE_MAX_VARIABLES = 999

    /** 分片大小是否安全（必须为正且不超过硬上限） */
    fun isSafeChunkSize(chunkSize: Int): Boolean {
        return chunkSize in 1..SQLITE_MAX_VARIABLES
    }

    /**
     * 把列表按 [chunkSize] 分片。空列表返回空列表；分片大小非法时抛异常，
     * 避免"静默按默认大小分片"掩盖调用方的错误。
     */
    fun <T> chunk(items: List<T>, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<List<T>> {
        require(isSafeChunkSize(chunkSize)) { "illegal sqlite chunk size: $chunkSize" }
        if (items.isEmpty()) return emptyList()
        return items.chunked(chunkSize)
    }
}
