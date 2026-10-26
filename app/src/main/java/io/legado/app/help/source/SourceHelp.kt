package io.legado.app.help.source

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.RssSource
import io.legado.app.help.AppCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object SourceHelp {

    private val list18Plus by lazy {
        try {
            return@lazy String(appCtx.assets.open("18PlusList.txt").readBytes())
                .splitNotBlank("\n").map {
                    EncoderUtils.base64Decode(it)
                }.toHashSet()
        } catch (_: Exception) {
            return@lazy emptySet()
        }
    }

    fun getSource(key: String?): BaseSource? {
        key ?: return null
        if (ReadBook.bookSource?.bookSourceUrl == key) {
            return ReadBook.bookSource
        } else if (AudioPlay.bookSource?.bookSourceUrl == key) {
            return AudioPlay.bookSource
        } else if (ReadManga.bookSource?.bookSourceUrl == key) {
            return ReadManga.bookSource
        } else if (VideoPlay.source?.getKey() == key) {
            return VideoPlay.source
        }
        return appDb.bookSourceDao.getBookSource(key)
            ?: appDb.rssSourceDao.getByKey(key)
    }

    fun getSource(key: String?, @SourceType.Type type: Int): BaseSource? {
        key ?: return null
        return when (type) {
            SourceType.book -> appDb.bookSourceDao.getBookSource(key)
            SourceType.rss -> appDb.rssSourceDao.getByKey(key)
            else -> null
        }
    }

    fun deleteSource(key: String, @SourceType.Type type: Int) {
        when (type) {
            SourceType.book -> deleteBookSource(key)
            SourceType.rss -> deleteRssSource(key)
        }
    }

    fun deleteBookSourceParts(sources: List<BookSourcePart>) {
        appDb.runInTransaction {
            val fullSources = sources.mapNotNull {
                appDb.bookSourceDao.getBookSource(it.bookSourceUrl)
            }
            SourceRecycleBinHelp.recycleBookSources(fullSources)
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    fun deleteBookSources(sources: List<BookSource>) {
        appDb.runInTransaction {
            SourceRecycleBinHelp.recycleBookSources(sources)
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    /**
     * 删除书源并在事务后回调被删的完整书源列表（用于 Snackbar 撤销恢复）。
     * 删除仍会先入回收站，撤销是直接重新插回书源表。
     */
    fun deleteBookSourcePartsWithUndo(
        sources: List<BookSourcePart>,
        onDeleted: (List<BookSource>) -> Unit
    ) {
        // Room 的 runInTransaction 只收 Runnable，事务内用外部变量带回结果
        var fullSources: List<BookSource> = emptyList()
        appDb.runInTransaction {
            fullSources = sources.mapNotNull {
                appDb.bookSourceDao.getBookSource(it.bookSourceUrl)
            }
            SourceRecycleBinHelp.recycleBookSources(fullSources)
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
        onDeleted(fullSources)
    }

    private fun deleteBookSourceInternal(key: String) {
        appDb.bookSourceDao.delete(key)
        if (!AppConfig.sourceRecycleBinEnabled) {
            // 回收站关闭时没有撤销窗口，这就是真删除，附带数据必须清掉，
            // 否则登录态/JS 变量/评分永久残留在库里
            appDb.cacheDao.deleteSourceVariables(key)
            SourceConfig.removeSource(key)
            return
        }
        // 回收站开启时**不在这里清**：删除会先入回收站（撤销窗口内），
        // 而撤销只是把书源插回表里；这里清掉的话，撤销回来的书源会
        // 丢登录态/JS 变量、源评分归零。
        // 改由真删除（彻底删除、清空回收站、过期清理）时清理，
        // 见 SourceRecycleBinHelp.purgeAttachedData。
    }

    fun deleteBookSource(key: String) {
        appDb.runInTransaction {
            appDb.bookSourceDao.getBookSource(key)?.let {
                SourceRecycleBinHelp.recycleBookSources(listOf(it))
            }
            deleteBookSourceInternal(key)
        }
        AppCacheManager.clearSourceVariables()
    }

    fun deleteRssSources(sources: List<RssSource>) {
        appDb.runInTransaction {
            SourceRecycleBinHelp.recycleRssSources(sources)
            sources.forEach {
                deleteRssSourceInternal(it.sourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    /**
     * 带撤销的订阅源删除：删除完成后把完整实体回调给调用方，
     * 由调用方在撤销窗口内提供 undo 入口（见 RssSourceActivity 的 undoSnackbar）。
     *
     * 与 [deleteRssSources] 的差别只是回调：删除链路本身（入回收站、删源、
     * 删文章、按回收站开关决定是否清源变量）完全一致，两条路径不会漂移。
     * 管理页列表持有的是完整 RssSource 实体，无需像书源那样回读。
     */
    fun deleteRssSourcesWithUndo(
        sources: List<RssSource>,
        onDeleted: (List<RssSource>) -> Unit
    ) {
        appDb.runInTransaction {
            SourceRecycleBinHelp.recycleRssSources(sources)
            sources.forEach {
                deleteRssSourceInternal(it.sourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
        onDeleted(sources)
    }

    private fun deleteRssSourceInternal(key: String) {
        appDb.rssSourceDao.delete(key)
        appDb.rssArticleDao.delete(key)
        if (!AppConfig.sourceRecycleBinEnabled) {
            // 回收站关闭 = 真删除，理由同 deleteBookSourceInternal
            appDb.cacheDao.deleteSourceVariables(key)
            return
        }
        // 回收站开启时不在删除时清源变量，理由同 deleteBookSourceInternal：
        // 撤销只插回 RSS 源本身，删除即清会让撤销回来的源丢掉登录态与 JS 变量。
        // 真删除时由 SourceRecycleBinHelp.purgeAttachedData 清理。
    }

    fun deleteRssSource(key: String) {
        appDb.runInTransaction {
            appDb.rssSourceDao.getByKey(key)?.let {
                SourceRecycleBinHelp.recycleRssSources(listOf(it))
            }
            deleteRssSourceInternal(key)
        }
        AppCacheManager.clearSourceVariables()
    }

    fun enableSource(key: String, @SourceType.Type type: Int, enable: Boolean) {
        when (type) {
            SourceType.book -> appDb.bookSourceDao.enable(key, enable)
            SourceType.rss -> appDb.rssSourceDao.enable(key, enable)
        }
    }

    fun insertRssSource(vararg rssSources: RssSource) {
        val rssSourcesGroup = rssSources.groupBy {
            is18Plus(it.sourceUrl)
        }
        rssSourcesGroup[true]?.forEach {
            appCtx.toastOnUi("${it.sourceName}是18+网址,禁止导入.")
        }
        rssSourcesGroup[false]?.let {
            appDb.rssSourceDao.insert(*it.toTypedArray())
        }
    }

    fun insertBookSource(vararg bookSources: BookSource) {
        val bookSourcesGroup = bookSources.groupBy {
            is18Plus(it.bookSourceUrl)
        }
        bookSourcesGroup[true]?.forEach {
            appCtx.toastOnUi("${it.bookSourceName}是18+网址,禁止导入.")
        }
        bookSourcesGroup[false]?.let {
            appDb.bookSourceDao.insert(*it.toTypedArray())
        }
        Coroutine.async(Coroutine.defaultScope) {
            adjustSortNumber()
        }
    }

    private fun is18Plus(url: String?): Boolean {
        if (list18Plus.isEmpty()) {
            return false
        }
        url ?: return false
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return false
        kotlin.runCatching {
            val host = baseUrl.split("//", ".").let {
                if (it.size > 2) "${it[it.lastIndex - 1]}.${it.last()}" else return false
            }
            return list18Plus.contains(host)
        }
        return false
    }

    /**
     * 调整排序序号
     */
    fun adjustSortNumber() {
        if (
            appDb.bookSourceDao.maxOrder > 99999
            || appDb.bookSourceDao.minOrder < -99999
            || appDb.bookSourceDao.hasDuplicateOrder
        ) {
            val sources = appDb.bookSourceDao.allPart
            sources.forEachIndexed { index, bookSource ->
                bookSource.customOrder = index
            }
            appDb.bookSourceDao.upOrder(sources)
        }
    }

    fun openVideoPlayer(source: BaseSource?, url: String, title: String, isFloat: Boolean) {
        if (isFloat) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) {
                PermissionsCompat.Builder()
                    .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
                    .rationale(R.string.float_permission_rationale)
                    .onGranted {
                        openVideoPlayer(source, url, title, isFloat = true)
                    }
                    .onDenied {
                        appCtx.toastOnUi("授权后重新打开悬浮播放")
                    }
                    .request()
                return
            }
            val intent = Intent(appCtx, VideoPlayService::class.java).apply {
                putExtra("videoUrl", url)
                putExtra("videoTitle", title)
                putExtra("sourceKey", source?.getKey())
                putExtra("sourceType", source?.getSourceType())
            }
            ContextCompat.startForegroundService(appCtx, intent)
        } else {
            appCtx.startActivity<VideoPlayerActivity> {
                putExtra("videoUrl", url)
                putExtra("videoTitle", title)
                putExtra("sourceKey", source?.getKey())
                putExtra("sourceType", source?.getSourceType())
            }
        }
    }

}
