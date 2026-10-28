package io.legado.app.help.config

import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import splitties.init.appCtx

object SourceConfig {
    private val sp = appCtx.getSharedPreferences("SourceConfig", MODE_PRIVATE)
    fun setBookScore(origin: String, name: String, author: String, score: Int) {
        sp.edit {
            val preScore = getBookScore(origin, name, author)
            var newScore = score
            if (preScore != 0) {
                newScore = score - preScore
            }

            putInt(origin, getSourceScore(origin) + newScore)

            putInt("${origin}_${name}_${author}", score)
        }
    }

    fun getBookScore(origin: String, name: String, author: String): Int {
        return sp.getInt("${origin}_${name}_${author}", 0)
    }

    fun getSourceScore(origin: String): Int {
        return sp.getInt(origin, 0)
    }


    /**
     * 删除某书源的全部评分记录（源总分 + 每本书评分）。
     *
     * 不能用简单 startsWith(origin)：源 URL 之间存在前缀关系（如 a.com 与
     * a.com.cn）时，删短源会把长源的评分一并抹掉。这里按「最长源 URL 匹配」
     * 判归属；无法无歧义判定的 key 保守保留（宁剩孤儿 key，不误删他源评分）。
     */
    fun removeSource(origin: String) {
        val allKeys = sp.all.keys
        if (allKeys.isEmpty()) return
        // 「哪些字符串是某条 key 的下划线前缀」一次算清：等价于逐个 key 做
        // allKeys.any { it.startsWith("${key}_") }，但把 O(n²) 降成 O(n)。
        // 评分 key 形如 origin_name_author，下划线个数很少，内层循环代价极小。
        val prefixesWithChild = HashSet<String>()
        for (key in allKeys) {
            var idx = key.indexOf('_')
            while (idx > 0) {
                prefixesWithChild.add(key.substring(0, idx))
                idx = key.indexOf('_', idx + 1)
            }
        }
        // 以 origin 为前缀的更长的 key（可能属于另一个源），通常为空或极少
        val longerKeys = allKeys.filter { it.length > origin.length && it.startsWith(origin) }
        val removed = allKeys.filter { belongsToSource(it, origin, prefixesWithChild, longerKeys) }
        if (removed.isNotEmpty()) {
            sp.edit {
                removed.forEach { remove(it) }
            }
        }
    }

    /** key（源总分或源内某本书的评分）是否属于 origin 这本书源。 */
    private fun belongsToSource(
        key: String,
        origin: String,
        prefixesWithChild: Set<String>,
        longerKeys: List<String>
    ): Boolean {
        if (key == origin) return true
        if (!key.startsWith("${origin}_")) return false
        // key 自身是另一个（更长）源 URL 的总分 key：有其它评分记录挂在它名下，
        // 它不属于 origin，删掉会连带毁掉那个源的总分
        if (key in prefixesWithChild) return false
        // key 落在另一个以 origin 为前缀的更长源 URL 名下
        return longerKeys.none { other -> key.startsWith("${other}_") }
    }


}