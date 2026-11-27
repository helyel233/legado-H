package io.legado.app.help.book

import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.rhinoContext
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.StrResponse
import io.legado.app.help.review.ReviewSnapshot
import io.legado.app.help.review.ReviewParagraphTemplate
import io.legado.app.help.review.ReviewWindowPrefetcher
import io.legado.app.help.review.ReviewSnapshotCapture
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.help.review.SyntheticParaContent
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext
import splitties.init.appCtx

/**
 * 图片/评论点击统一入口。
 *
 * “评论打开方式”二模式（[AppConfig.reviewOpenMode]，默认联网优先）：
 * - 联网优先（[AppConfig.ReviewOpenMode.NETWORK]）：点击评论直接执行原 click/js
 *   加载在线评论页；若该按钮存在本地快照，则在网络加载失败/超时时自动用缓存兜底，
 *   无快照则展示正常错误；
 * - 缓存优先（[AppConfig.ReviewOpenMode.SNAPSHOT_FIRST]）：命中缓存立即用快照秒开
 *   （0 秒可见），同时后台静默 SWR 刷新真实评论页；无缓存则按正常网络链路打开。
 *
 * 同一套 click/js 执行逻辑同时服务用户点击与后台抓取：抓取时传入拦截宿主
 * （click 分支替换 java 宿主；js 分支挂 AnalyzeRule 钩子），其余环境完全一致。
 */
object BookImgClick {

    internal enum class OpenAction {
        OPEN_SNAPSHOT_AND_REFRESH,
        OPEN_SNAPSHOT_OFFLINE,
        BLOCK_NO_SNAPSHOT,
        OPEN_NETWORK,
    }

    internal fun decideOpenAction(mode: String, hasSnapshot: Boolean): OpenAction = when (mode) {
        AppConfig.ReviewOpenMode.OFFLINE ->
            if (hasSnapshot) OpenAction.OPEN_SNAPSHOT_OFFLINE else OpenAction.BLOCK_NO_SNAPSHOT
        AppConfig.ReviewOpenMode.SNAPSHOT_FIRST ->
            if (hasSnapshot) OpenAction.OPEN_SNAPSHOT_AND_REFRESH else OpenAction.OPEN_NETWORK
        else -> OpenAction.OPEN_NETWORK
    }

    private fun openMode(): String = AppConfig.reviewOpenMode

    private fun isOfflineOnly(): Boolean = openMode() == AppConfig.ReviewOpenMode.OFFLINE

    /** 与图片点击入口共用同一判定，避免给没有 click/js 的零评论泡暴露段评菜单。 */
    fun hasAction(src: String, click: String?): Boolean {
        if (!click.isNullOrBlank()) return true
        val options = parseSrcOptions(src)?.second ?: return false
        return !options["click"].isNullOrBlank() || !options["js"].isNullOrBlank()
    }

    /**
     * 评论快照定位不依赖网络执行条件。书源缺失时仍可用 book/chapter 读取并打开
     * 已落盘的快照；只有真正执行 click/js 时才解析并要求书源存在。
     */
    private data class ReviewContext(
        val book: Book,
        val chapter: BookChapter,
        val useCurrentReadSource: Boolean,
    )

    /** 已在网络执行前读出的快照，失败回退时禁止重新查询或重新解析上下文。 */
    private data class CachedReviewSnapshot(
        val book: Book,
        val chapter: BookChapter,
        val snapshot: ReviewSnapshot,
    )

    private fun currentChapter(hostChapter: BookChapter?): BookChapter? {
        return hostChapter
            ?: ReadBook.book?.let {
                appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
            }
    }

    /**
     * 解析评论所属文字书上下文。这里不读取书源：本地快照的主键只需要
     * book/chapter，不能因为任何网络执行条件而错过离线快照。
     */
    private fun reviewContext(chapter: BookChapter, src: String): ReviewContext? {
        // legado-H 暂无音频书与文字书的融合挂载体系，评论一律归属当前阅读书。
        val book = ReadBook.book ?: return null
        return ReviewContext(book, chapter, useCurrentReadSource = true)
    }

    private fun cachedSnapshot(context: ReviewContext, src: String, syntheticPara: SyntheticParaContent? = null): CachedReviewSnapshot? {
        // 快照文件损坏/身份不符（require 抛出）按无快照处理：
        // 调用方按各自模式回落网络或提示，不让一个坏文件卡死点击链路
        val snapshot = runCatching {
            ReviewSnapshotStore.get(context.book, context.chapter, src.trim())
        }.getOrElse { error ->
            AppLog.put("ReviewSnapshot 快照读取失败，按无快照处理\n${error.localizedMessage}", error)
            null
        } ?: return null
        val page = if (syntheticPara?.snapshotFallbackAllowed == false) {
            ReviewParagraphTemplate.create(snapshot, syntheticPara)
        } else snapshot
        return CachedReviewSnapshot(context.book, context.chapter, page)
    }

    private fun resolveNetworkSource(context: ReviewContext): BookSource? {
        return if (context.useCurrentReadSource) {
            ReadBook.bookSource
        } else {
            appDb.bookSourceDao.getBookSource(context.book.origin)
        }
    }

    private fun showSnapshot(
        context: AppCompatActivity,
        cached: CachedReviewSnapshot,
        source: BookSource? = null,
        networkRefresher: (suspend () -> Pair<String, String>?)? = null,
        offlineOnly: Boolean,
        sourceKeyOverride: String? = null,
        onDismiss: (() -> Unit)? = null,
        htmlDirect: String? = null,
        // 未走 htmlDirect 的调用方（网络失败回退快照）才传 html：对话框构造时
        // 会同步写临时文件（进程重建兜底）；热点路径一律走 htmlDirect 不写盘。
        html: String? = null,
    ): Boolean {
        if (context.isFinishing || context.isDestroyed) return false
        // 段落规则段评：弹窗按 paragraph_rule_<id> 源键加载规则宿主（页内 ajax /
        // 发表评论都走规则的登录态），与点击网络打开时的源键保持一致。
        val sourceKey = sourceKeyOverride ?: source?.getKey().orEmpty()
        context.runOnUiThread {
            if (context.isFinishing || context.isDestroyed) return@runOnUiThread
            context.showDialogFragment(
                BottomWebViewDialog(
                    sourceKey,
                    BookType.text,
                    cached.snapshot.url.ifBlank { "about:blank" },
                    html,
                    networkRefresher = networkRefresher,
                    offlineOnly = offlineOnly,
                    reviewResourceBook = cached.book,
                    // 内容来自 ReviewSnapshotStore 的快照：身份为离线，允许离线接管
                    isSnapshotHtml = true,
                    onDismiss = onDismiss,
                    // htmlDirect：快照 HTML 内存直传，主线程构造即渲染（不过 Bundle/不写文件）
                    htmlDirect = htmlDirect,
                    // 进程重建身份：dialog 按此主键重读本地快照
                    snapshotChapterUrl = cached.chapter.url,
                    snapshotButtonSrc = cached.snapshot.buttonSrc,
                    // 快照抓取时间：徽章显示「快照 · N 前」
                    snapshotSavedAt = cached.snapshot.savedAt,
                )
            )
        }
        return true
    }

    /**
     * 构造「快照优先」模式的后台刷新器：解析真实评论页并取回在线 HTML。
     * 纯本地构造（不产生 IO），可安全在主线程构造后交给对话框异步执行。
     */
    private fun buildNetworkRefresher(
        src: String,
        cached: CachedReviewSnapshot,
        snapshotSource: BookSource?,
        syntheticPara: SyntheticParaContent? = null,
    ): (suspend () -> Pair<String, String>?)? {
        if (snapshotSource == null) return null
        val button = reviewButtonOf(src) ?: return null
        return refresh@{
            if (syntheticPara?.snapshotFallbackAllowed == false) {
                return@refresh fetchOnlineHtml(cached.snapshot.url, snapshotSource)
            }
            val page = ReviewSnapshotManager.resolveReviewPageUrl(
                cached.book, snapshotSource, cached.chapter, button
            )
            val onlineUrl = page.url ?: return@refresh null
            // showBrowser 已带回渲染 HTML 且有效：直接用，不再重复请求
            val onlineHtml = when {
                !page.html.isNullOrBlank() &&
                    ReviewSnapshotCapture.isValidCommentHtml(page.html) -> page.html
                else -> fetchOnlineHtml(onlineUrl, snapshotSource)?.second
            } ?: return@refresh null
            onlineUrl to onlineHtml
        }
    }

    /**
     * 打开评论快照。
     * @param refreshToNetwork 快照优先：后台刷新为最新网络评论页（成功后覆盖）
     * @param offlineOnly 仅使用快照：WebView 禁止一切 http/https 网络请求
     * @param pclick 段落规则点击值；非空时后台刷新走规则点击脚本解析真实评论页
     * @return true 表示已用快照打开
     */
    private fun openSnapshotIfCached(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter?,
        refreshToNetwork: Boolean,
        offlineOnly: Boolean,
        syntheticPara: SyntheticParaContent? = null,
        pclick: String? = null,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        val chapter = currentChapter(hostChapter) ?: return false
        // 先持有本地快照；网络刷新条件不得影响离线快照可用性。
        val resolvedContext = reviewContext(chapter, src) ?: return false
        val cached = cachedSnapshot(resolvedContext, src, syntheticPara) ?: return false
        // 缓存优先：后台解析真实评论页并加载在线内容，有变化时提示更新
        val snapshotSource = resolveNetworkSource(resolvedContext)
        val refresher = if (refreshToNetwork) {
            buildNetworkRefresher(src, cached, snapshotSource, syntheticPara)
        } else {
            null
        }
        val ruleSourceKey = pclick
            ?.let { ParagraphRuleProcessor.ruleIdOfClick(it) }
            ?.let { "paragraph_rule_$it" }
        if (offlineOnly) {
            // 纯离线兜底无打开时刷新器：过期快照由后台刷新覆盖，弹窗先用旧快照保证 0 秒可见
            val ttlDays = AppConfig.reviewSnapshotTtlDays
            if (ReviewWindowPrefetcher.isStale(
                    cached.snapshot.savedAt, System.currentTimeMillis(), ttlDays
                )
            ) {
                ReviewWindowPrefetcher.refreshButton(cached.book, cached.chapter, src)
            }
        }
        // 快照 HTML 内存直传对话框：不过 Bundle、不写临时文件，
        // 主线程构造对话框即可渲染；进程重建时对话框按身份重读本地快照。
        return showSnapshot(
            context, cached, snapshotSource, refresher, offlineOnly,
            sourceKeyOverride = ruleSourceKey, onDismiss = onDismiss,
            htmlDirect = cached.snapshot.html
        )
    }

    /**
     * 段评图标直点路径的**同步热命中**快速通道（点击即弹）。
     *
     * 仅在缓存优先模式（[AppConfig.ReviewOpenMode.SNAPSHOT_FIRST]）生效：只做一次
     * stat + 进程内快照内存缓存查询（无解析、无协程调度），命中即在主线程直接构造并
     * 显示对话框，并挂上后台 SWR 刷新器；联网优先模式直接返回 false 走原链路。
     *
     * 未命中返回 false：调用方按原链路走 IO 协程读文件（首次/被淘汰），
     * 那次读入后会热缓存，下次同一按钮即走本通道。
     *
     * @param pclick 段落规则点击值；图标直点段落规则段评时传入，用于弹窗源键
     * @param onDismiss 快照弹窗关闭回调（调用方据此复位防重入标记）
     * @param hostChapter 调用方须传**内存中已排版好的当前章**（ReadBook.curTextChapter），
     *   本通道在主线程执行、绝不查库（Room 禁止主线程查询）；为 null 直接放弃，
     *   由调用方回落 IO 链路取章节。
     * @return true = 已用内存快照打开弹窗
     */
    fun tryOpenCachedSnapshotImmediately(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter? = null,
        pclick: String? = null,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        if (context.isFinishing || context.isDestroyed) return false
        if (openMode() != AppConfig.ReviewOpenMode.SNAPSHOT_FIRST) return false
        // 主线程快速通道：只认调用方给的内存章节，不做任何数据库回退
        val chapter = hostChapter ?: return false
        val resolvedContext = reviewContext(chapter, src) ?: return false
        val cached = ReviewSnapshotStore.getCached(resolvedContext.book, chapter, src.trim())
            ?.let { snapshot ->
                CachedReviewSnapshot(
                    book = resolvedContext.book,
                    chapter = resolvedContext.chapter,
                    snapshot = snapshot
                )
            }
            ?: return false
        val snapshotSource = resolveNetworkSource(resolvedContext)
        val refresher = buildNetworkRefresher(src, cached, snapshotSource)
        return showSnapshot(
            context, cached,
            source = snapshotSource,
            networkRefresher = refresher,
            offlineOnly = false,
            sourceKeyOverride = pclick
                ?.let { ParagraphRuleProcessor.ruleIdOfClick(it) }
                ?.let { "paragraph_rule_$it" },
            onDismiss = onDismiss,
            htmlDirect = cached.snapshot.html
        )
    }

    /**
     * 段评图标直点路径的快照消费（与选中文字后的段评菜单同一套二模式语义）：
     * - 缓存优先（[AppConfig.ReviewOpenMode.SNAPSHOT_FIRST]）：有缓存 → 立即打开并后台
     *   SWR 刷新，返回 [ReviewSnapshotConsumption.Opened]；无缓存 → 返回 NotConsumed 回落网络；
     * - 联网优先（[AppConfig.ReviewOpenMode.NETWORK]，默认）：直接返回 NotConsumed，
     *   由调用方按原 click/js 链路联网加载，并在失败/超时时用缓存兜底。
     *
     * 在 IO 线程调用（内部会读快照文件），弹窗经 runOnUiThread 展示。
     * 热路径请先试 [tryOpenCachedSnapshotImmediately]（同步内存命中直开）。
     *
     * @param pclick 段落规则点击值；图标直点段落规则段评时传入，用于后台刷新与弹窗源键
     * @param onDismiss 快照弹窗关闭回调（调用方据此复位防重入标记）
     */
    fun clickImgSnapshotFirst(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter? = null,
        pclick: String? = null,
        onDismiss: (() -> Unit)? = null,
    ): ReviewSnapshotConsumption {
        return when {
            isOfflineOnly() -> {
                val opened = openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = false, offlineOnly = true,
                    pclick = pclick, onDismiss = onDismiss
                )
                if (opened) {
                    ReviewSnapshotConsumption.Opened
                } else {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                    ReviewSnapshotConsumption.BlockedOffline
                }
            }
            openMode() == AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                val opened = openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = true, offlineOnly = false,
                    pclick = pclick, onDismiss = onDismiss
                )
                if (opened) {
                    ReviewSnapshotConsumption.Opened
                } else {
                    ReviewSnapshotConsumption.NotConsumed
                }
            }
            else -> ReviewSnapshotConsumption.NotConsumed
        }
    }

    /** [clickImgSnapshotFirst] 的消费结果 */
    sealed interface ReviewSnapshotConsumption {
        /** 已用快照打开弹窗：调用方不得再执行 click/js */
        object Opened : ReviewSnapshotConsumption

        /** 纯快照模式下无本地快照，已提示用户：调用方不得再执行 click/js */
        object BlockedOffline : ReviewSnapshotConsumption

        /** 未消费：调用方继续执行 click/js 网络链路 */
        object NotConsumed : ReviewSnapshotConsumption
    }

    /**
     * 执行 src 附带的 click JS（用户点击路径）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书
     * 上下文执行；为 null 时按当前阅读上下文（ReadBook）执行。
     */
    fun clickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String,
        src: String,
        hostChapter: BookChapter? = null,
        syntheticPara: SyntheticParaContent? = null,
    ) {
        // 合成入口只复用缓存地址创建本段本地模板，不展示锚点段的评论。
        when {
            isOfflineOnly() -> {
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = false, offlineOnly = true, syntheticPara = syntheticPara
                    )
                ) {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                }
            }
            openMode() == AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = true, offlineOnly = false, syntheticPara = syntheticPara
                    )
                ) {
                    return
                }
                // 无快照 → 直接按正常网络评论打开
                openNetwork(context, scope, click, null, null, src, hostChapter, syntheticPara)
            }
            else -> openNetwork(context, scope, click, null, null, src, hostChapter, syntheticPara)
        }
    }

    /**
     * 兼容旧源：click/js 写在 src 的 url 选项里（无独立 click 字段时走此入口）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书上下文。
     * @return true 表示已处理
     */
    fun oldClickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        src: String,
        hostChapter: BookChapter? = null,
    ): Boolean {
        val parsed = parseSrcOptions(src)
        if (parsed == null) {
            // 旧源选项 JSON 损坏时，网络链路没有可执行的 click/js。若已缓存，仍应
            // 直接展示持有的离线评论；无快照则交回原图片链路暴露其原始失败。
            return if (paramPattern.matcher(src).find()) {
                openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = false, offlineOnly = true
                )
            } else {
                false
            }
        }
        val (urlNoOption, options) = parsed
        val click = options["click"]
        val js = options["js"]
        if (click.isNullOrBlank() && js.isNullOrBlank()) {
            return openSnapshotIfCached(
                context, src, hostChapter,
                refreshToNetwork = false, offlineOnly = true
            )
        }
        when {
            isOfflineOnly() -> {
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = false, offlineOnly = true
                    )
                ) {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                }
            }
            openMode() == AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = true, offlineOnly = false
                    )
                ) {
                    openNetwork(context, scope, click, js, urlNoOption, src, hostChapter)
                }
            }
            else -> openNetwork(context, scope, click, js, urlNoOption, src, hostChapter)
        }
        return true
    }

    /**
     * 网络打开（网络优先默认路径 / 快照优先无快照回退）。
     * click 与旧源 js 走与用户点击完全一致的执行环境；
     * 网络优先模式下载入时若该按钮存在快照，将其作为“加载失败/超时兜底”
     * 传给浏览器（绝不因存在快照截断原链路）。
     */
    private fun openNetwork(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String?,
        js: String?,
        urlNoOption: String?,
        src: String,
        hostChapter: BookChapter?,
        syntheticPara: SyntheticParaContent? = null,
    ) {
        Coroutine.async(scope, Dispatchers.IO) {
            val chapter = currentChapter(hostChapter)
                ?: error("无法定位当前章节，无法执行评论网络打开")
            // 快照必须在任何网络执行条件、click/js 解析之前读出并持有。
            // 合成入口从锚点缓存地址创建独立段评模板。
            val resolvedContext = reviewContext(chapter, src)
            val fallback = resolvedContext?.let { cachedSnapshot(it, src, syntheticPara) }
            try {
                val execution = resolvedContext
                    ?: error("无法解析评论所属书籍或章节，无法执行评论网络打开")
                val execSource = resolveNetworkSource(execution)
                    ?: error("评论书源不存在，无法执行评论网络打开")
                when {
                    !click.isNullOrBlank() -> {
                        // 评论 click 统一宿主：有无快照兜底都走本宿主，保证
                        // showBrowser 弹窗路径统一携带离线评论上下文
                        val host = SnapshotFallbackJsExtensions(
                            context,
                            execSource,
                            BookType.text,
                            fallback?.snapshot?.html,
                            fallback?.snapshot?.savedAt ?: 0L,
                            execution.book,
                            execution.chapter,
                            src.takeUnless { syntheticPara?.snapshotFallbackAllowed == false },
                            syntheticPara,
                        )
                        executeClick(
                            execution.book, execSource, execution.chapter, click, src
                        ) {
                            host
                        }
                        if (!host.browserRequested) {
                            error("评论 click 未发起浏览器打开")
                        }
                    }
                    else -> {
                        val fallbackHtml = fallback?.snapshot?.html
                        var browserRequested = false
                        executeJs(
                            execution.book, execSource, execution.chapter,
                            js.orEmpty(), urlNoOption.orEmpty()
                        ) {
                            fallbackBrowserHtml = fallbackHtml
                            fallbackReviewResourceBook = fallback?.book
                            if (fallbackHtml != null) {
                                onBrowserOpenRequestedHook = { _, _, _ ->
                                    browserRequested = true
                                    false
                                }
                                onBrowserAwaitRequestedHook = { _, _, _ ->
                                    browserRequested = true
                                    null
                                }
                            }
                        }
                        if (fallbackHtml != null && !browserRequested) {
                            error("评论 js 未发起浏览器打开")
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (fallback != null && showSnapshot(
                        context, fallback,
                        networkRefresher = null,
                        offlineOnly = true,
                        // 该路径无 htmlDirect，显式传 html 走临时文件兜底
                        html = fallback.snapshot.html
                    )
                ) {
                    AppLog.put(
                        appCtx.getString(R.string.misc_failed_open_reviews_online_fell, error.localizedMessage),
                        error
                    )
                } else {
                    throw error
                }
            }
        }.onError {
            AppLog.put("执行图片链接click/js键值出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 从 src 构造评论按钮模型（后台解析/刷新用） */
    private fun reviewButtonOf(src: String): ReviewSnapshotManager.ReviewButton? {
        val (urlNoOption, options) = parseSrcOptions(src) ?: return null
        return ReviewSnapshotManager.ReviewButton(
            src = src.trim(),
            click = options["click"]?.takeIf { it.isNotBlank() },
            js = options["js"]?.takeIf { it.isNotBlank() },
            urlNoOption = urlNoOption,
            pclick = options["pclick"]?.takeIf { it.isNotBlank() }
        )
    }

    /** 后台加载真实网络评论页（带书源 cookie/UA），失败或超时返回 null */
    private suspend fun fetchOnlineHtml(
        url: String,
        source: BookSource
    ): Pair<String, String>? {
        return runCatching {
            val analyzeUrl = AnalyzeUrl(url, source = source)
            val body = BackstageWebView(
                url = analyzeUrl.url,
                headerMap = analyzeUrl.headerMap,
                tag = source.getKey(),
                timeout = FETCH_TIMEOUT_MS
            ).getStrResponse().body
            body?.takeIf { it.isNotBlank() }?.let { analyzeUrl.url to it } ?: return null
        }.getOrNull()
    }

    /**
     * 解析 src 的选项 JSON：返回 (去选项地址, 选项Map)。无选项时返回 null。
     */
    fun parseSrcOptions(src: String): Pair<String, Map<String, String>>? {
        val urlMatcher = paramPattern.matcher(src)
        if (!urlMatcher.find()) return null
        val urlNoOption = src.take(urlMatcher.start())
        val urlOptionStr = src.substring(urlMatcher.end())
        val options = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull() ?: return null
        return urlNoOption to options
    }

    /**
     * click 分支统一执行：与用户点击完全一致的环境，java 宿主可替换为拦截宿主。
     */
    suspend fun executeClick(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        click: String,
        src: String,
        javaBuilder: () -> io.legado.app.help.JsExtensions
    ) {
        runScriptWithContext {
            source.evalJS(click) {
                val java = javaBuilder()
                put("java", java)
                put("book", book)
                put("chapter", chapter)
                put("result", src)
            }
        }
    }

    /**
     * 旧源 js 分支统一执行：AnalyzeRule 规则引擎环境，与用户点击完全一致。
     * 评论快照抓取可通过 [AnalyzeRule.onBrowserOpenRequestedHook] 拦截浏览器请求；
     * 网络优先的失败兜底可通过 [AnalyzeRule.fallbackBrowserHtml] 指定快照。
     */
    suspend fun executeJs(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        js: String,
        urlNoOption: String,
        ruleHook: (AnalyzeRule.() -> Unit)? = null
    ) {
        AnalyzeRule(book, source).apply {
            setCoroutineContext(coroutineContext)
            setBaseUrl(chapter.url)
            setChapter(chapter)
            ruleHook?.invoke(this)
            evalJS(js, urlNoOption)
        }
    }

    /**
     * 网络优先模式的浏览器宿主：打开真实评论页时附带快照兜底，
     * 网络加载失败/超时由 WebViewActivity 自动切换到快照。
     */
    private class SnapshotFallbackJsExtensions(
        context: AppCompatActivity?,
        source: BookSource?,
        bookType: Int,
        private val fallbackHtml: String?,
        private val fallbackSavedAt: Long = 0L,
        private val reviewResourceBook: Book,
        private val chapter: BookChapter?,
        private val buttonSrc: String?,
        private val syntheticPara: SyntheticParaContent? = null,
    ) : SourceLoginJsExtensions(context, source, bookType) {

        var browserRequested = false
            private set

        override fun startBrowser(url: String, title: String) {
            startBrowser(url, title, null)
        }

        override fun startBrowser(url: String, title: String, html: String?) {
            browserRequested = true
            rhinoContext.ensureActive()
            SourceVerificationHelp.startBrowser(
                getSource(),
                url,
                title,
                html = html,
                fallbackHtml = fallbackHtml,
                fallbackReviewResourceBook = reviewResourceBook,
            )
        }

        override fun startBrowserAwait(url: String, title: String): StrResponse {
            return startBrowserAwait(url, title, true, null)
        }

        override fun startBrowserAwait(
            url: String,
            title: String,
            refetchAfterSuccess: Boolean
        ): StrResponse {
            return startBrowserAwait(url, title, refetchAfterSuccess, null)
        }

        override fun startBrowserAwait(
            url: String,
            title: String,
            refetchAfterSuccess: Boolean,
            html: String?
        ): StrResponse {
            browserRequested = true
            rhinoContext.ensureActive()
            val pair = SourceVerificationHelp.getVerificationResult(
                getSource(), url, title, true, refetchAfterSuccess, html,
                fallbackHtml = fallbackHtml,
                fallbackReviewResourceBook = reviewResourceBook,
            )
            val (url2, body) = pair
            return StrResponse(url2.ifEmpty { url }, body)
        }

        /** 评论底部弹窗路径同样支持“网络优先”：失败/超时切快照 */
        override fun showBrowser(
            url: String,
            html: String?,
            preloadJs: String?,
            config: String?
        ) {
            browserRequested = true
            val activity = activityRef.get() ?: return
            val source = getSource() ?: return
            if (callbackRef.get()?.showBrowser(url, html, preloadJs, config) == true) {
                return
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                activity.showDialogFragment(
                    BottomWebViewDialog(
                        source.getKey(),
                        bookType,
                        url,
                        html,
                        preloadJs,
                        config,
                        networkRefresher = null,
                        fallbackHtml = fallbackHtml,
                        fallbackSavedAt = fallbackSavedAt,
                        reviewResourceBook = reviewResourceBook,
                        syntheticParaContent = syntheticPara,
                    )
                )
            }
            if (chapter != null && !buttonSrc.isNullOrBlank()) {
                ReviewWindowPrefetcher.captureFromClick(
                    reviewResourceBook, chapter, buttonSrc, url, html, preloadJs
                )
            }
        }
    }

    private const val FETCH_TIMEOUT_MS = 60_000L
}
