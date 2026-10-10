package io.legado.app.help.source

import androidx.collection.LruCache
import io.legado.app.utils.InfoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 发现按钮信息（ExploreKind 参数）缓存
 */
object ExploreInfoCache {
    val infoMapList = LruCache<String, InfoMap>(99)

    fun savePending(scope: CoroutineScope) {
        val pending = infoMapList.snapshot().filter { (_, infoMap) -> infoMap.needSave }
        if (pending.isEmpty()) return
        scope.launch {
            pending.map { (_, infoMap) ->
                launch { infoMap.saveNow() }
            }.joinAll()
        }
    }
}
