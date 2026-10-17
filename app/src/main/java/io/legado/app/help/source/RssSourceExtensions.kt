package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.ACache
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import com.script.rhino.runScriptWithContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val aCache by lazy { ACache.get("rssSortUrl") }

/**
 * 提取订阅规则里的 JS 代码，支持 `<js>...</js>` 与 `@js:` 两种前缀。
 *
 * 旧写法 `substring(4, lastIndexOf("<"))` 在 `<js>` 规则缺少结尾 `<` 时
 * lastIndexOf 返回 -1，直接 StringIndexOutOfBounds：RssSourceExtensions 里被
 * runCatching 吞掉导致分类静默丢失，RssViewModel/RssJsExtensions 里则直接崩。
 * 这里结尾 `<` 缺失时兜底截到末尾，保证规则畸形时降级而不是崩/静默失败。
 */
fun String.extractJsFromRule(): String? {
    return when {
        startsWith("@js:", ignoreCase = true) -> substring(4)
        startsWith("<js>", ignoreCase = true) -> {
            val end = lastIndexOf('<').takeIf { it >= 4 } ?: length
            substring(4, end)
        }

        else -> null
    }
}

private fun RssSource.getSortUrlsKey(): String {
    return MD5Utils.md5Encode(sourceUrl + sortUrl)
}

suspend fun RssSource.sortUrls(): List<Pair<String, String>> {
    return arrayListOf<Pair<String, String>>().apply {
        val sortUrlsKey = getSortUrlsKey()
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                var str = sortUrl
                if (sortUrl?.startsWith("<js>", false) == true
                    || sortUrl?.startsWith("@js:", false) == true
                ) {
                    str = aCache.getAsString(sortUrlsKey)
                    if (str.isNullOrBlank()) {
                        val jsStr = sortUrl?.extractJsFromRule().orEmpty()
                        str = runScriptWithContext {
                            evalJS(jsStr).toString()
                        }
                        aCache.put(sortUrlsKey, str)
                    }
                }
                str?.split("(&&|\n)+".toRegex())?.forEach { sort ->
                    val name = sort.substringBefore("::").trim()
                    val rawUrl = sort.substringAfter("::", "").trim()
                    val url = rawUrl.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
                    if (name.isNotBlank() || url.isNotBlank()) {
                        add(Pair(name, url))
                    }
                }
                if (isEmpty()) {
                    add(Pair("", sourceUrl))
                }
            }.onFailure {
                // 旧写法完全静默：JS 执行失败/规则畸形时用户看到「分类全丢但当成默认分类」
                AppLog.put("获取订阅分类失败\n${it.localizedMessage}", it)
            }
        }
    }
}

suspend fun RssSource.removeSortCache() {
    withContext(Dispatchers.IO) {
        aCache.remove(getSortUrlsKey())
    }
}
