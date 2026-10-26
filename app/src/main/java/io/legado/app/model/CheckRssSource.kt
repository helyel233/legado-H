package io.legado.app.model

import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.sortUrls
import io.legado.app.model.rss.Rss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 订阅源批量校验。
 *
 * 校验内容 = 拉取源默认分类页并解析出文章列表（与单源调试 [Debug.startDebug] 的
 * 第一段一致）：解析出列表即成功，空列表也算成功（抓到并解析成功，只是真的没有
 * 内容），网络/规则异常算失败。逐源超时沿用书源校验的超时配置（[CheckSource.timeout]）。
 *
 * 与书源校验（CheckSourceService，前台服务、可后台跑）刻意不同：这里是页面级
 * 轻校验，订阅源数量通常很少，挂在管理页生命周期上即可，省掉前台服务通知与
 * 权限成本；离开页面即停止，结果通过 [Debug.debugMessageMap] 供管理页行副标题展示。
 */
object CheckRssSource {

    /** 并发数取保守值：部分分类页规则会借 WebView 池渲染，并发太高互相排队反而慢 */
    private const val CONCURRENT_COUNT = 4

    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun start(scope: CoroutineScope, sources: List<RssSource>) {
        stop()
        if (sources.isEmpty()) return
        job = scope.launch {
            try {
                val semaphore = Semaphore(CONCURRENT_COUNT)
                coroutineScope {
                    sources.forEach { source ->
                        launch {
                            semaphore.withPermit {
                                check(source)
                            }
                        }
                    }
                }
            } finally {
                // 成功跑完 / 出错 / 被取消都要复位，否则管理页的加载圈永不消失。
                // 只复位 RSS 自己的标志：书源校验可能正在后台跑，动 isChecking 会
                // 让它的调试消息静默停更。
                Debug.finishRssChecking()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        Debug.finishRssChecking()
    }

    /** 校验单个订阅源，结果写入 [Debug] 消息缓存；返回是否成功 */
    suspend fun check(rssSource: RssSource): Boolean {
        Debug.startChecking(rssSource.sourceUrl)
        return try {
            withTimeout(CheckSource.timeout) {
                val sort = rssSource.sortUrls().first()
                Rss.getArticlesAwait(sort.first, sort.second, rssSource, 1)
            }
            Debug.updateFinalMessage(rssSource.sourceUrl, "校验成功")
            true
        } catch (e: Throwable) {
            // 整体被取消（离开页面/手动停止）时不能伪装成「校验失败」
            currentCoroutineContext().ensureActive()
            Debug.updateFinalMessage(rssSource.sourceUrl, "校验失败")
            false
        }
    }
}
