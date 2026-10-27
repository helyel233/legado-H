package io.legado.app.model.webBook

import android.text.TextUtils
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.StrResponse
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import org.mozilla.javascript.Context
import splitties.init.appCtx
import io.legado.app.constant.AppPattern
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 目录抓取状态：记录分页失败情况，供调用方判断这次拿到的目录是否完整。
 */
class TocLoadState {

    /**
     * 抓取失败的目录页数，0 表示目录完整
     */
    @Volatile
    var failedPages: Int = 0
        internal set

    internal fun onPageFailed() {
        failedPages++
    }
}

/**
 * 获取目录
 */
object BookChapterList {

    /**
     * 目录单页抓取失败后的重试次数（不含首次请求）
     */
    private const val TOC_PAGE_MAX_RETRY = 2

    /**
     * 目录单页重试的基础间隔，按重试次数递增
     */
    private const val TOC_PAGE_RETRY_DELAY_MS = 500L

    suspend fun analyzeChapterList(
        bookSource: BookSource,
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        isFromBookInfo: Boolean = false,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
        loadState: TocLoadState? = null
    ): List<BookChapter> {
        body ?: throw NoStackTraceException(
            appCtx.getString(R.string.error_get_web_content, baseUrl)
        )
        val chapterList = ArrayList<BookChapter>()
        Debug.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        Debug.log(bookSource.bookSourceUrl, body, state = 30)
        val tocRule = bookSource.getTocRule()
        val nextUrlList = arrayListOf(redirectUrl)
        var reverse = false
        var listRule = tocRule.chapterList ?: ""
        if (listRule.startsWith("-")) {
            reverse = true
            listRule = listRule.substring(1)
        }
        if (listRule.startsWith("+")) {
            listRule = listRule.substring(1)
        }
        var chapterData =
            analyzeChapterList(
                book, baseUrl, redirectUrl, body,
                tocRule, listRule, bookSource, log = true,
                isFromBookInfo = isFromBookInfo
            )
        chapterList.addAll(chapterData.first)
        when (chapterData.second.size) {
            0 -> Unit
            1 -> {
                var nextUrl = chapterData.second[0]
                while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                    nextUrlList.add(nextUrl)
                    val res = fetchTocPage(bookSource, book, nextUrl)
                    val nextBody = res?.body
                    if (nextBody.isNullOrEmpty()) {
                        // 本页取不到内容：已解析出的章节照常使用（部分目录可用），
                        // 不再因为个别分页失败把整本目录作废
                        loadState?.onPageFailed()
                        Debug.log(
                            bookSource.bookSourceUrl,
                            "◇目录第${nextUrlList.size}页获取失败，沿用已解析的${chapterList.size}章"
                        )
                        break
                    }
                    chapterData = analyzeChapterList(
                        book, nextUrl, nextUrl,
                        nextBody, tocRule, listRule, bookSource,
                        isFromBookInfo = isFromBookInfo
                    )
                    nextUrl = chapterData.second.firstOrNull() ?: ""
                    chapterList.addAll(chapterData.first)
                    onProgress?.invoke(chapterList.size, -1)
                }
                Debug.log(bookSource.bookSourceUrl, "◇目录总页数:${nextUrlList.size}")
            }

            else -> {
                val totalPages = chapterData.second.size
                Debug.log(
                    bookSource.bookSourceUrl,
                    "◇并发解析目录,总页数:${totalPages}"
                )
                val failedPages = AtomicInteger(0)
                flow {
                    for (urlStr in chapterData.second) {
                        emit(urlStr)
                    }
                }.mapAsync(AppConfig.threadCount) { urlStr ->
                    try {
                        val res = fetchTocPage(bookSource, book, urlStr)
                        val resBody = res?.body
                        if (resBody.isNullOrEmpty()) {
                            failedPages.incrementAndGet()
                            loadState?.onPageFailed()
                            emptyList()
                        } else {
                            analyzeChapterList(
                                book, urlStr, res?.url ?: urlStr,
                                resBody, tocRule, listRule, bookSource, false,
                                isFromBookInfo = isFromBookInfo
                            ).first
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 单页失败只跳过这一页，其余页照常解析
                        failedPages.incrementAndGet()
                        loadState?.onPageFailed()
                        Debug.log(
                            bookSource.bookSourceUrl,
                            "◇目录页解析失败:$urlStr ${e.localizedMessage}"
                        )
                        emptyList()
                    }
                }.collect {
                    chapterList.addAll(it)
                    onProgress?.invoke(chapterList.size, totalPages)
                }
                val failed = failedPages.get()
                if (failed > 0) {
                    Debug.log(
                        bookSource.bookSourceUrl,
                        "◇目录有$failed 页获取失败，已跳过，剩余${chapterList.size}章"
                    )
                }
            }
        }
        if (chapterList.isEmpty()) {
            throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
        }
        if (!reverse) {
            chapterList.reverse()
        }
        currentCoroutineContext().ensureActive()
        //去重
        val lh = LinkedHashSet(chapterList)
        val list = ArrayList(lh)
        if (!book.getReverseToc()) {
            list.reverse()
        }
        Debug.log(book.origin, "◇目录总数:${list.size}")
        currentCoroutineContext().ensureActive()
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
        }
        val formatJs = tocRule.formatJs
        if (!formatJs.isNullOrBlank()) {
            Context.enter().use {
                val bindings = ScriptBindings()
                bindings["gInt"] = 0
                list.forEachIndexed { index, bookChapter ->
                    bindings["index"] = index + 1
                    bindings["chapter"] = bookChapter
                    bindings["title"] = bookChapter.title
                    RhinoScriptEngine.runCatching {
                        eval(formatJs, bindings)?.toString()?.let {
                            bookChapter.title = it
                        }
                    }.onFailure {
                        Debug.log(book.origin, "格式化标题出错, ${it.localizedMessage}")
                    }
                }
            }
        }
        val replaceRules = ContentProcessor.get(book).getTitleReplaceRules()
        val replaceBook = book.toReplaceBook()
        book.durChapterTitle = list.getOrElse(book.durChapterIndex) { list.last() }
            .getDisplayTitle(
                replaceRules,
                book.getUseReplaceRule(),
                replaceBook = replaceBook
            )
        if (book.totalChapterNum < list.size) {
            book.lastCheckCount = list.size - book.totalChapterNum
            book.latestChapterTime = System.currentTimeMillis()
        }
        book.lastCheckTime = System.currentTimeMillis()
        book.totalChapterNum = list.size
        book.latestChapterTitle =
            list.getOrElse(book.simulatedTotalChapterNum() - 1) { list.last() }
                .getDisplayTitle(
                    replaceRules,
                    book.getUseReplaceRule(),
                    replaceBook = replaceBook
                )
        currentCoroutineContext().ensureActive()
        upChapterInfo(list, book)
        return list
    }

    /**
     * 抓取目录分页，失败自动重试。
     *
     * 网络抖动是目录拉取失败最常见的原因，重试比整本重头再来划算得多。
     *
     * @return 成功返回响应；重试后仍失败返回 null，由调用方按「部分目录可用」处理。
     */
    private suspend fun fetchTocPage(
        bookSource: BookSource,
        book: Book,
        url: String
    ): StrResponse? {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val analyzeUrl = AnalyzeUrl(
                    mUrl = url,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = currentCoroutineContext()
                )
                return analyzeUrl.getStrResponseAwait()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt > TOC_PAGE_MAX_RETRY) {
                    Debug.log(
                        bookSource.bookSourceUrl,
                        "◇目录页获取失败(已重试${attempt - 1}次):$url ${e.localizedMessage}"
                    )
                    return null
                }
                delay(TOC_PAGE_RETRY_DELAY_MS * attempt)
            }
        }
    }

    private suspend fun analyzeChapterList(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        tocRule: TocRule,
        listRule: String,
        bookSource: BookSource,
        getNextUrl: Boolean = true,
        log: Boolean = false,
        isFromBookInfo:Boolean
    ): Pair<List<BookChapter>, List<String>> {
        val analyzeRule = AnalyzeRule(book, bookSource, false, isFromBookInfo)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        //获取目录列表
        val chapterList = arrayListOf<BookChapter>()
        Debug.log(bookSource.bookSourceUrl, "┌获取目录列表", log)
        val elements = analyzeRule.getElements(listRule)
        Debug.log(bookSource.bookSourceUrl, "└列表大小:${elements.size}", log)
        //获取下一页链接
        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (getNextUrl && !nextTocRule.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "┌获取目录下一页列表", log)
            analyzeRule.getStringList(nextTocRule, isUrl = true)?.let {
                for (item in it) {
                    if (item != redirectUrl) {
                        nextUrlList.add(item)
                    }
                }
            }
            Debug.log(
                bookSource.bookSourceUrl,
                "└" + TextUtils.join("，\n", nextUrlList),
                log
            )
        }
        currentCoroutineContext().ensureActive()
        if (elements.isNotEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "┌解析目录列表", log)
            val nameRule = analyzeRule.splitSourceRule(tocRule.chapterName)
            val urlRule = analyzeRule.splitSourceRule(tocRule.chapterUrl)
            val vipRule = analyzeRule.splitSourceRule(tocRule.isVip)
            val payRule = analyzeRule.splitSourceRule(tocRule.isPay)
            val upTimeRule = analyzeRule.splitSourceRule(tocRule.updateTime)
            val isVolumeRule = analyzeRule.splitSourceRule(tocRule.isVolume)
            val tocCountWords = AppConfig.tocCountWords
            elements.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                analyzeRule.setContent(item)
                val bookChapter = BookChapter(bookUrl = book.bookUrl, baseUrl = redirectUrl)
                analyzeRule.setChapter(bookChapter)
                bookChapter.title = analyzeRule.getString(nameRule)
                bookChapter.url = analyzeRule.getString(urlRule)
                val info = analyzeRule.getString(upTimeRule)
                val isVolume = analyzeRule.getString(isVolumeRule)
                bookChapter.isVolume = false
                if (isVolume.isTrue()) {
                    bookChapter.isVolume = true
                    bookChapter.tag = info
                } else {
                    if (tocCountWords) {
                        AppPattern.wordCountRegex.find(info)?.let { match ->
                            bookChapter.apply {
                                wordCount = match.groupValues[1].trim()
                                tag = info.replaceFirst(match.value, "")
                            }
                        } ?: run { bookChapter.tag = info }
                    } else {
                        bookChapter.tag = info
                    }
                }
                if (bookChapter.url.isEmpty()) {
                    if (bookChapter.isVolume) {
                        bookChapter.url = bookChapter.title + index
                        Debug.log(
                            bookSource.bookSourceUrl,
                            "⇒一级目录${index}未获取到url,使用标题替代"
                        )
                    } else {
                        bookChapter.url = baseUrl
                        Debug.log(
                            bookSource.bookSourceUrl,
                            "⇒目录${index}未获取到url,使用baseUrl替代"
                        )
                    }
                }
                if (bookChapter.title.isNotEmpty()) {
                    val isVip = analyzeRule.getString(vipRule)
                    val isPay = analyzeRule.getString(payRule)
                    if (isVip.isTrue()) {
                        bookChapter.isVip = true
                    }
                    if (isPay.isTrue()) {
                        bookChapter.isPay = true
                    }
                    chapterList.add(bookChapter)
                }
            }
            Debug.log(bookSource.bookSourceUrl, "└目录列表解析完成", log)
            if (chapterList.isEmpty()) {
                Debug.log(bookSource.bookSourceUrl, "◇章节列表为空", log)
            } else {
                Debug.log(bookSource.bookSourceUrl, "≡首章信息", log)
                Debug.log(bookSource.bookSourceUrl, "◇章节名称:${chapterList[0].title}", log)
                Debug.log(bookSource.bookSourceUrl, "◇章节链接:${chapterList[0].url}", log)
                chapterList[0].wordCount?.run{
                    Debug.log(bookSource.bookSourceUrl, "◇章节信息:${chapterList[0].tag} $this", log)
                    Debug.log(bookSource.bookSourceUrl, "⇒已识别到章节信息中的字数",log)
                } ?: run {
                    Debug.log(bookSource.bookSourceUrl, "◇章节信息:${chapterList[0].tag}", log)
                }
                Debug.log(bookSource.bookSourceUrl, "◇是否VIP:${chapterList[0].isVip}", log)
                Debug.log(bookSource.bookSourceUrl, "◇是否购买:${chapterList[0].isPay}", log)
            }
        }
        return Pair(chapterList, nextUrlList)
    }

    private fun upChapterInfo(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfig.tocCountWords) {
            return
        }
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val mapByIdentity = HashMap<String, Triple<String?, String?, String?>>(chapterList.size)
            val mapByTitle = HashMap<String, Triple<String?, String?, String?>>(chapterList.size)
            for (chapter in chapterList) {
                val triple = Triple(chapter.wordCount, chapter.variable, chapter.imgUrl)
                mapByIdentity[chapter.contentCacheIdentity()] = triple
                mapByTitle.putIfAbsent(chapter.title.trim(), triple)
            }
            for (chapter in list) {
                (mapByIdentity[chapter.contentCacheIdentity()] ?: mapByTitle[chapter.title.trim()])?.let { (w, v, i) ->
                    chapter.run {
                        w?.let { wordCount = it }
                        v?.let { variable = it }
                        i?.let { imgUrl = it }
                    }
                }
            }
        }
    }

}
