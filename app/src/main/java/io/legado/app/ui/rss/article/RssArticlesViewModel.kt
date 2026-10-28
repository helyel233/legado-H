package io.legado.app.ui.rss.article

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.constant.AppLog
import io.legado.app.data.SqliteInQueryPolicy
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.rss.Rss
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO


class RssArticlesViewModel(application: Application) : BaseViewModel(application) {
    val loadFinallyLiveData = MutableLiveData<Boolean>()
    val loadErrorLiveData = MutableLiveData<String>()
    var isLoading = true
    var order = System.currentTimeMillis()
    private var nextPageUrl: String? = null
    private var loadJob: Coroutine<Pair<MutableList<RssArticle>, String?>>? = null
    var sortName: String = ""
    var sortUrl: String = ""
    var searchKey: String? = null
    var page = 1

    fun init(bundle: Bundle?) {
        bundle?.let {
            sortName = it.getString("sortName") ?: ""
            sortUrl = it.getString("sortUrl") ?: ""
            searchKey = it.getString("searchKey")
        }
    }

    fun loadArticles(rssSource: RssSource) {
        isLoading = true
        page = 1
        order = System.currentTimeMillis()
        loadJob?.cancel()
        loadJob = Rss.getArticles(
            viewModelScope,
            sortName,
            sortUrl,
            rssSource,
            page,
            searchKey,
            webViewPoolScope = WebViewPool.Scope.RSS
        ).onSuccess(IO) {
            nextPageUrl = it.second
            val articles = it.first
            articles.forEach { rssArticle ->
                rssArticle.order = order--
            }
            appDb.rssArticleDao.insert(*articles.toTypedArray())
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                appDb.rssArticleDao.clearOld(rssSource.sourceUrl, sortName, order)
            }
            val hasMore = articles.isNotEmpty() && !rssSource.ruleNextPage.isNullOrEmpty()
            loadFinallyLiveData.postValue(hasMore)
            isLoading = false
        }.onError {
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    fun loadMore(rssSource: RssSource) {
        isLoading = true
        page++
        val pageUrl = nextPageUrl
        if (pageUrl.isNullOrEmpty()) {
            loadFinallyLiveData.postValue(false)
            return
        }
        loadJob?.cancel()
        loadJob = Rss.getArticles(
            viewModelScope,
            sortName,
            pageUrl,
            rssSource,
            page,
            searchKey,
            webViewPoolScope = WebViewPool.Scope.RSS
        ).onSuccess(IO) {
            nextPageUrl = it.second
            loadMoreSuccess(it.first)
            isLoading = false
        }.onError {
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    private fun loadMoreSuccess(articles: MutableList<RssArticle>) {
        if (articles.isEmpty()) {
            loadFinallyLiveData.postValue(false)
            return
        }
        //批量统计已在库中的数量：仅当整页均已存在时判定无更多，
        //避免首尾采样在部分重叠页上误判（重复 append 或提前终止）
        val links = articles.map { it.link }
        // SQLite 绑定参数上限 999（Android 11 及以下），必须分批查询。
        // 判定要用整页的 link：只查前 900 条会让「整页均已存在」永远不成立 → 无限翻页重复拉取。
        val existLinks = hashSetOf<String>()
        SqliteInQueryPolicy.chunk(links).forEach { chunk ->
            existLinks += appDb.rssArticleDao.existLinks(
                articles.first().origin, articles.first().sort, chunk
            )
        }
        val existCount = articles.count { it.link in existLinks }
        if (existCount == articles.size) {
            loadFinallyLiveData.postValue(false)
        } else {
            articles.forEach {
                it.order = order--
            }
            appDb.rssArticleDao.append(*articles.toTypedArray())
        }
    }

    fun cancelLoading() {
        loadJob?.cancel()
        loadJob = null
        isLoading = false
    }

    override fun onCleared() {
        cancelLoading()
        WebViewPool.destroyScope(WebViewPool.Scope.RSS)
        super.onCleared()
    }

}
