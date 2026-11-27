package io.legado.app.help.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.ParagraphBubbleRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import splitties.init.appCtx
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** idea_comment 协议适配器。只识别已验证的段评协议，域名由实际评论地址提供。 */
internal object ReviewDataCapture {
    // 实际并发由各阶段 Gate 统一控制，避免 OkHttp 默认单主机队列再次限流；
    // 延迟初始化，避免纯 JVM 调用模板与时间戳格式化方法时触发 okHttpClient/appCtx 加载。
    private val client by lazy {
        okHttpClient.newBuilder().dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = Int.MAX_VALUE
            maxRequestsPerHost = Int.MAX_VALUE
        }).build()
    }
    const val PROTOCOL = "idea_comment"

    /**
     * 当前评论类型能否走数据接口。
     *
     * 书源声明的协议不是本适配器认识的类型时返回 false（让上层回退网页引擎），
     * 而不是用 require 把整次抓取炸掉——协议是书源侧的声明，不该成为崩溃点。
     */
    fun supports(url: String, html: String?, protocol: String): Boolean {
        val declared = protocol.trim()
        if (declared.isNotEmpty() && declared != PROTOCOL) return false
        val address = url.toHttpUrlOrNull() ?: return false
        return address.encodedPath == "/idea_comment" &&
            listOf("book_id", "item_id", "para").all { address.queryParameter(it) != null } &&
            (declared == PROTOCOL || (html != null && html.contains("loadParaFromApi") && html.contains("reply_api")))
    }

    /**
     * 最近成功结果窗口：同一评论页在短时间内被多个入口抓取（跨书同章节、预热 + 打开）时
     * 直接复用，避免重复请求同一批接口。结果只依赖入参和三个渲染开关，因此键里带上它们。
     */
    private const val REUSE_WINDOW_MS = 15_000L
    private const val REUSE_MAX_ENTRIES = 4

    /** 快照 HTML 可能很大，超过该长度的结果不进复用窗口，避免常驻内存放大堆峰值。 */
    private const val REUSE_MAX_CHARS = 512 * 1024

    /**
     * 单个分页循环的页数上限（判定规则见 [ReviewPaginationPolicy]）。
     *
     * 数据接口路径不走网页引擎的 PAGE_EXECUTION_TIMEOUT，只有单次请求超时；若接口持续返回
     * has_more=true 且 cursor 不重复，循环永不结束，而评论抓取只有 1 个消费者，会永久占住 worker。
     */
    private val recentCaptures = object : LinkedHashMap<String, Pair<Long, String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?): Boolean =
            size > REUSE_MAX_ENTRIES
    }

    suspend fun capture(url: String, headers: Map<String, String>, initialHtml: String?,
                        trace: CacheOperationDiagnostics.Operation?): String {
        val cacheKey = captureKey(url, headers, initialHtml)
        synchronized(recentCaptures) {
            val cached = recentCaptures[cacheKey]
            if (cached != null) {
                if (System.currentTimeMillis() - cached.first <= REUSE_WINDOW_MS) {
                    trace?.mark(
                        "DATA_REUSE",
                        CacheOperationDiagnostics.Metrics(inputChars = cached.second.length),
                        force = true
                    )
                    return cached.second
                }
                recentCaptures.remove(cacheKey)
            }
        }
        val html = captureUncached(url, headers, initialHtml, trace)
        if (html.length <= REUSE_MAX_CHARS) {
            synchronized(recentCaptures) { recentCaptures[cacheKey] = System.currentTimeMillis() to html }
        }
        return html
    }

    private fun captureKey(url: String, headers: Map<String, String>, initialHtml: String?): String {
        val text = initialHtml.orEmpty()
        return buildString {
            append(url).append('|').append(AppConfig.cacheReviewReplies)
            append('|').append(AppConfig.isNightTheme)
            append('|').append(text.length).append('|').append(text.hashCode())
            headers.forEach { (name, value) -> append('|').append(name).append('=').append(value) }
        }
    }

    private suspend fun captureUncached(url: String, headers: Map<String, String>, initialHtml: String?,
                                        trace: CacheOperationDiagnostics.Operation?): String = coroutineScope {
        val base = requireNotNull(url.toHttpUrlOrNull())
        val emoji = Regex("""var\s+EM\s*=\s*(\{[^;]*});""").find(initialHtml.orEmpty())?.let {
            JsonParser.parseString(it.groupValues[1]).asJsonObject.entrySet().associate { entry ->
                entry.key to requireNotNull(base.resolve(entry.value.asString)).toString()
            }
        }.orEmpty()
        suspend fun fetch(reply: Boolean, params: Map<String, String>): JsonObject {
            val builder = base.newBuilder()
            listOf("api", "reply_api", "cursor", "count", "sort", "comment_id").forEach {
                builder.removeAllQueryParameters(it)
            }
            params.forEach { (key, value) -> builder.setQueryParameter(key, value) }
            val request = Request.Builder().url(builder.build()).apply {
                headers.forEach { (key, value) -> header(key, value) }
                header("Referer", url)
            }.build()
            try {
                val json = executeRequest(request)
                trace?.mark(if (reply) "REPLY_PAGE_RECEIVED" else "DATA_PAGE_RECEIVED",
                    CacheOperationDiagnostics.Metrics(inputChars = json.length), force = true)
                return withContext(Dispatchers.Default) {
                    JsonParser.parseString(json).asJsonObject
                }
            } catch (error: IOException) {
                trace?.warn(if (reply) "REPLY_HTTP_FAILED" else "DATA_HTTP_FAILED", error)
                throw error
            }
        }
        val items = mutableListOf<JsonObject>()
        val cursors = hashSetOf<String>()
        val ids = hashSetOf<String>()
        var cursor = "0"
        var sourceText = ""
        var pageCount = 0
        while (true) {
            check(!ReviewPaginationPolicy.isOverLimit(++pageCount)) {
                appCtx.getString(R.string.misc_chapter_review_pagination_exceeds_limit, ReviewPaginationPolicy.MAX_PAGES)
            }
            check(cursors.add(cursor)) { "段评游标循环: $cursor" }
            val response = fetch(false, mapOf("api" to "1", "cursor" to cursor,
                "count" to ReviewDownloadConfig.Number.PAGE_SIZE.value.toString(), "sort" to "1"))
            if (response.has("code")) check(response.get("code").asInt == 0) { "段评接口失败: $response" }
            val data = response.getAsJsonObject("data") ?: error("段评缺少 data")
            val page = data.getAsJsonArray("data_list") ?: error("段评缺少 data_list")
            trace?.mark("DATA_PAGE_COUNT_${page.size()}", force = true)
            data.get("para_src_content")?.takeUnless { it.isJsonNull }?.let { sourceText = it.asString }
            page.forEach { element ->
                val item = element.asJsonObject
                val id = item.getAsJsonObject("comment").get("comment_id").asString
                check(ids.add(id)) { "段评跨页重复: $id" }
                items += item
            }
            val meta = response.getAsJsonObject("common_list_info") ?: error("段评缺少分页状态")
            check(meta.has("has_more")) { "段评缺少 has_more" }
            if (!meta.hasMore()) break
            check(page.size() > 0 && meta.has("cursor")) { "段评分页没有推进" }
            cursor = meta.get("cursor").asString
        }
        val replies = if (!AppConfig.cacheReviewReplies) emptyList() else items.map { item -> async {
            val comment = item.getAsJsonObject("comment")
            val expected = comment.getAsJsonObject("stat").get("reply_count").asInt
            val embedded = item.getAsJsonArray("replies") ?: JsonArray()
            check(expected >= 0) { "楼中楼总数无效: $expected" }
            if (expected == 0 && embedded.size() == 0) return@async emptyList<JsonObject>()
            if (embedded.size() >= expected) {
                return@async embedded.map { it.asJsonObject }
            }
            val result = mutableListOf<JsonObject>()
            val seenCursors = hashSetOf<String>()
            val seenIds = hashSetOf<String>()
            var offset = "0"
            var replyPageCount = 0
            while (true) {
                check(!ReviewPaginationPolicy.isOverLimit(++replyPageCount)) {
                    appCtx.getString(R.string.misc_reply_pagination_exceeds_limit, ReviewPaginationPolicy.MAX_PAGES)
                }
                check(seenCursors.add(offset)) { "楼中楼游标循环: $offset" }
                val response = fetch(true, mapOf("reply_api" to "1",
                    "comment_id" to comment.get("comment_id").asString, "cursor" to offset,
                    "count" to ReviewDownloadConfig.Number.PAGE_SIZE.value.toString()))
                check(response.get("code")?.asInt == 0) { "楼中楼接口失败: $response" }
                val data = response.getAsJsonObject("data") ?: error("楼中楼缺少 data")
                val page = data.getAsJsonArray("reply_list") ?: error("楼中楼缺少 reply_list")
                trace?.mark("REPLY_PAGE_COUNT_${page.size()}", force = true)
                page.forEach {
                    val record = it.asJsonObject
                    check(seenIds.add(record.get("reply_id").asString)) { "楼中楼跨页重复" }
                    result += record
                }
                check(data.has("has_more")) { "楼中楼缺少 has_more" }
                if (!data.hasMore()) break
                check(page.size() > 0 && data.has("next_offset")) { "楼中楼缺少 next_offset" }
                offset = data.get("next_offset").asString
            }
            check(result.size >= expected) { "楼中楼数量不足: ${result.size}/$expected" }
            result
        } }.awaitAll()
        withContext(Dispatchers.Default) {
            buildHtml(
                url = url,
                sourceText = sourceText,
                items = items,
                replies = replies,
                emoji = emoji,
            )
        }
    }

    internal fun buildHtml(
        url: String,
        sourceText: String,
        items: List<JsonObject>,
        replies: List<List<JsonObject>>,
        emoji: Map<String, String>,
        nowMillis: Long = System.currentTimeMillis(),
        isNightTheme: Boolean = safeIsNightTheme(),
        accentColorHex: String = safeAccentHex(),
        cacheAvatars: Boolean = safeCacheAvatars(),
        cacheImages: Boolean = safeCacheImages(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val doc = Jsoup.parse(
            "<!doctype html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<meta name='color-scheme' content='light dark'></head><body></body></html>",
            url
        )
        doc.selectFirst("html")?.attr("data-theme", if (isNightTheme) "dark" else "light")
        doc.head().appendElement("style")
            .attr("id", ReviewLiteTemplate.DATA_TEMPLATE_MARKER)
            .text(buildTemplateCss(accentColorHex))
        doc.body().attr("data-review-protocol", PROTOCOL)

        val header = doc.body().appendElement("header").addClass("review-header")
        header.appendElement("h2").addClass("review-title").text(safeString(R.string.review, "段评"))
        header.appendElement("span").addClass("review-count").text("${items.size} 条")

        val quote = doc.body().appendElement("blockquote")
            .attr("id", "quoteCard")
            .addClass("quote-card")
        if (sourceText.isBlank()) {
            quote.attr("style", "display:none")
        } else {
            quote.text(sourceText)
        }

        val contentArea = doc.body().appendElement("div")
            .attr("id", "contentArea")
            .addClass("comment-list")
        if (items.isEmpty()) {
            contentArea.appendElement("div")
                .addClass("empty-state")
                .text("暂无段评")
        } else {
            items.forEachIndexed { index, item ->
                val comment = item.getAsJsonObject("comment")
                val commentId = comment.get("comment_id").asString
                val card = contentArea.appendElement("article")
                    .addClass("comment-card")
                    .attr("data-comment-id", commentId)
                renderRecord(
                    parent = card,
                    record = comment,
                    emoji = emoji,
                    nowMillis = nowMillis,
                    cacheAvatars = cacheAvatars,
                    cacheImages = cacheImages,
                    timeZone = timeZone,
                )
                val commentReplies = replies.getOrNull(index).orEmpty()
                if (commentReplies.isNotEmpty()) {
                    val replySection = card.appendElement("div")
                        .addClass("replies")
                        .addClass("reply-section")
                    val toggle = replySection.appendElement("button")
                        .attr("type", "button")
                        .addClass("reply-toggle")
                        .attr("data-collapse-label", "收起回复")
                    toggle.appendText("展开 ${commentReplies.size} 条回复 ")
                    toggle.append(CHEVRON_DOWN_SVG)
                    val list = replySection.appendElement("div")
                        .addClass("reply-list")
                        .attr("style", "display:none")
                    commentReplies.forEach { reply ->
                        val replyEl = list.appendElement("article").addClass("reply-item")
                        renderRecord(
                            parent = replyEl,
                            record = reply,
                            emoji = emoji,
                            nowMillis = nowMillis,
                            cacheAvatars = cacheAvatars,
                            cacheImages = cacheImages,
                            timeZone = timeZone,
                        )
                    }
                }
            }
        }

        val bottomBar = doc.body().appendElement("div")
            .attr("id", "bottomBar")
            .addClass("bottom-bar")
        bottomBar.appendElement("button")
            .attr("type", "button")
            .attr("id", "commentTrigger")
            .addClass("comment-trigger")
            .text("写段评…")

        val modalOverlay = doc.body().appendElement("div")
            .attr("id", "commentModalOverlay")
            .addClass("comment-modal-overlay")
            .attr("style", "display:none")
        val modal = modalOverlay.appendElement("div").addClass("comment-modal")
        val modalHeader = modal.appendElement("div").addClass("comment-modal-header")
        modalHeader.appendElement("span").addClass("comment-modal-title").text("写段评")
        modalHeader.appendElement("button")
            .attr("type", "button")
            .addClass("modal-close")
            .attr("aria-label", "关闭")
            .text("✕")
        modal.appendElement("textarea")
            .attr("id", "commentTextarea")
            .attr("maxlength", "500")
            .attr("placeholder", "写下你的想法…")
        val modalFooter = modal.appendElement("div").addClass("comment-modal-footer")
        modalFooter.appendElement("span").attr("id", "charCount").text("0/500")
        modalFooter.appendElement("button")
            .attr("type", "button")
            .attr("id", "modalSubmitBtn")
            .addClass("modal-submit-btn")
            .text("发送")

        return doc.outerHtml()
    }

    internal fun formatReviewTimestamp(
        raw: String,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val numeric = trimmed.toLongOrNull() ?: return trimmed
        if (numeric <= 0L) return ""
        val millis = if (numeric < 10_000_000_000L) numeric * 1000L else numeric
        val diff = nowMillis - millis
        return when {
            diff in 0L until 60_000L -> "刚刚"
            diff in 60_000L until 3_600_000L -> "${diff / 60_000L}分钟前"
            diff in 3_600_000L until 86_400_000L -> "${diff / 3_600_000L}小时前"
            else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                this.timeZone = timeZone
            }.format(Date(millis))
        }
    }

    private fun buildTemplateCss(accentHex: String): String = """
        :root{
          color-scheme:light dark;
          --bg:#f5f6f8;
          --surface:#ffffff;
          --surface-alt:#eef1f5;
          --border:rgba(15,23,42,0.08);
          --text-primary:#18181b;
          --text-secondary:#64748b;
          --accent:$accentHex;
          --accent-soft:rgba(59,130,246,0.12);
          --quote-bg:rgba(15,23,42,0.04);
          --shadow:0 1px 3px rgba(0,0,0,0.05);
        }
        @media (prefers-color-scheme:dark){
          :root{
            --bg:#121214;
            --surface:#1c1c1f;
            --surface-alt:#26262b;
            --border:rgba(255,255,255,0.10);
            --text-primary:#f4f4f5;
            --text-secondary:#a1a1aa;
            --accent-soft:rgba(96,165,250,0.18);
            --quote-bg:rgba(255,255,255,0.05);
            --shadow:0 2px 6px rgba(0,0,0,0.34);
          }
        }
        html[data-theme="dark"]{
          --bg:#121214;
          --surface:#1c1c1f;
          --surface-alt:#26262b;
          --border:rgba(255,255,255,0.10);
          --text-primary:#f4f4f5;
          --text-secondary:#a1a1aa;
          --accent-soft:rgba(96,165,250,0.18);
          --quote-bg:rgba(255,255,255,0.05);
          --shadow:0 2px 6px rgba(0,0,0,0.34);
        }
        *{box-sizing:border-box}
        body{
          margin:0;
          padding:12px 12px 76px;
          background:var(--bg);
          color:var(--text-primary);
          font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
          line-height:1.55;
        }
        .review-header{
          display:flex;
          align-items:center;
          justify-content:space-between;
          margin:0 2px 10px;
        }
        .review-title{margin:0;font-size:17px;font-weight:600;color:var(--text-primary)}
        .review-count{font-size:12px;color:var(--text-secondary);background:var(--surface-alt);padding:2px 8px;border-radius:999px}
        .quote-card{
          margin:0 0 12px;
          padding:10px 12px;
          background:var(--quote-bg);
          border-left:3px solid var(--accent);
          border-radius:8px;
          color:var(--text-secondary);
          font-size:13px;
          white-space:pre-wrap;
        }
        .comment-list{display:flex;flex-direction:column;gap:10px}
        .empty-state{
          padding:28px 16px;
          text-align:center;
          color:var(--text-secondary);
          background:var(--surface);
          border:1px solid var(--border);
          border-radius:12px;
          font-size:14px;
        }
        .comment-card{
          background:var(--surface);
          border:1px solid var(--border);
          border-radius:12px;
          padding:12px;
          box-shadow:var(--shadow);
        }
        .comment-header{display:flex;align-items:center;gap:10px;margin-bottom:8px}
        .avatar{width:34px;height:34px;border-radius:50%;object-fit:cover;background:var(--surface-alt);flex-shrink:0}
        .comment-meta{flex:1;min-width:0;display:flex;flex-direction:column;gap:2px}
        .comment-author{font-size:14px;font-weight:600;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .comment-time{font-size:12px;color:var(--text-secondary)}
        .comment-likes{font-size:12px;color:var(--text-secondary);background:var(--surface-alt);padding:2px 8px;border-radius:999px;flex-shrink:0}
        .comment-body{margin:0;font-size:15px;color:var(--text-primary);white-space:pre-wrap;overflow-wrap:anywhere}
        .comment-images{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
        .comment-image{max-width:100%;max-height:260px;border-radius:8px;display:block}
        .reply-section{margin-top:10px;padding:8px 10px;background:var(--surface-alt);border-radius:10px}
        .reply-toggle{
          display:inline-flex;align-items:center;gap:4px;
          border:none;background:transparent;padding:2px 0;
          color:var(--accent);font-size:13px;font-weight:500;cursor:pointer;
        }
        .reply-list{margin-top:8px;padding-top:8px;border-top:1px solid var(--border)}
        .reply-item{padding:6px 0}
        .reply-item+.reply-item{border-top:1px solid var(--border)}
        .reply-target{font-size:12px;color:var(--accent);margin-bottom:2px}
        .bottom-bar{
          position:fixed;left:0;right:0;bottom:0;
          padding:10px 12px;
          background:var(--surface);
          border-top:1px solid var(--border);
          display:flex;align-items:center;
        }
        .comment-trigger{
          width:100%;height:38px;padding:0 14px;
          border:1px solid var(--border);border-radius:999px;
          background:var(--surface-alt);color:var(--text-secondary);
          text-align:left;font-size:14px;cursor:pointer;
        }
        .comment-modal-overlay{
          display:none;position:fixed;inset:0;z-index:99;
          background:rgba(0,0,0,0.48);
          align-items:flex-end;justify-content:center;
        }
        .comment-modal{
          width:100%;background:var(--surface);color:var(--text-primary);
          border-radius:16px 16px 0 0;padding:14px;border-top:1px solid var(--border);
        }
        .comment-modal-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;font-weight:600}
        .modal-close{border:none;background:transparent;color:var(--text-secondary);font-size:18px;padding:4px 8px;cursor:pointer}
        #commentTextarea{
          width:100%;min-height:96px;padding:10px;
          border:1px solid var(--border);border-radius:10px;
          background:var(--surface-alt);color:var(--text-primary);
          font:inherit;resize:vertical;
        }
        .comment-modal-footer{display:flex;align-items:center;justify-content:space-between;margin-top:10px}
        #charCount{font-size:12px;color:var(--text-secondary)}
        .modal-submit-btn{
          border:none;border-radius:999px;padding:8px 18px;
          background:var(--accent);color:#fff;font-size:14px;font-weight:600;cursor:pointer;
        }
    """.trimIndent()

    private const val CHEVRON_DOWN_SVG =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"currentColor\"><path d=\"M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z\"/></svg>"

    private fun safeIsNightTheme(): Boolean = runCatching { AppConfig.isNightTheme }.getOrDefault(false)
    private fun safeCacheAvatars(): Boolean = true
    private fun safeCacheImages(): Boolean = true
    private fun safeAccentHex(): String = runCatching {
        ParagraphBubbleRenderer.toRgbHex(ThemeStore.accentColor(appCtx))
    }.getOrDefault("#3B82F6")
    private fun safeString(resId: Int, fallback: String): String =
        runCatching { appCtx.getString(resId) }.getOrDefault(fallback)

    private fun JsonObject.hasMore(): Boolean = when (val value = get("has_more")?.asString) {
        "true", "1" -> true
        "false", "0" -> false
        else -> error("无效分页完成状态: $value")
    }

    private fun renderRecord(
        parent: Element,
        record: JsonObject,
        emoji: Map<String, String>,
        nowMillis: Long,
        cacheAvatars: Boolean,
        cacheImages: Boolean,
        timeZone: TimeZone,
    ) {
        val common = record.getAsJsonObject("common") ?: record.getAsJsonObject("Common") ?: record
        val info = common.getAsJsonObject("user_info") ?: error("评论缺少用户信息")
        val user = info.getAsJsonObject("base_info") ?: info
        fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val avatar = user.text("user_avatar")
        val userName = user.text("user_name").ifEmpty { user.text("nickname") }
        val formattedTime = formatReviewTimestamp(common.text("create_timestamp"), nowMillis, timeZone)
        val diggCount = record.getAsJsonObject("stat")?.text("digg_count")
            ?.ifEmpty { null }
            ?: record.text("digg_count").ifEmpty { "0" }

        val headerRow = parent.appendElement("div").addClass("comment-header")
        if (cacheAvatars && avatar.isNotEmpty()) {
            headerRow.appendElement("img").addClass("avatar").attr("src", avatar).attr("alt", userName)
        }
        val meta = headerRow.appendElement("div").addClass("comment-meta")
        meta.appendElement("span").addClass("comment-author").text(userName)
        if (formattedTime.isNotEmpty()) {
            meta.appendElement("span").addClass("comment-time").text(formattedTime)
        }
        headerRow.appendElement("span").addClass("comment-likes").text("👍 $diggCount")

        common.getAsJsonObject("reply_to_user_info")?.let { target ->
            val targetName = target.text("user_name").ifEmpty { target.text("nickname") }
            if (targetName.isNotEmpty()) {
                parent.appendElement("div").addClass("reply-target").text("回复 @$targetName")
            }
        }

        val content = common.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
        val paragraph = parent.appendElement("p").addClass("comment-body")
        val text = content?.text("text") ?: common.text("text")
        var offset = 0
        Regex("""\[[^\]]+]""").findAll(text).forEach { match ->
            paragraph.appendText(text.substring(offset, match.range.first))
            val address = emoji[match.value]
            if (address != null && cacheImages) {
                paragraph.appendElement("img").attr("src", address).attr("alt", match.value)
                    .attr("style", "width:22px;height:22px;vertical-align:middle")
            } else {
                paragraph.appendText(match.value)
            }
            offset = match.range.last + 1
        }
        paragraph.appendText(text.substring(offset))

        if (cacheImages) {
            val images = content?.getAsJsonObject("image_data_list")?.getAsJsonArray("image_data")
                ?: common.getAsJsonArray("image_data") ?: JsonArray()
            if (images.size() > 0) {
                val gallery = parent.appendElement("div").addClass("comment-images")
                images.forEach { image ->
                    val address = image.asJsonObject.text("web_uri")
                    check(address.isNotBlank()) { "评论图片缺少 web_uri" }
                    gallery.appendElement("img").addClass("comment-image").attr("src", address)
                }
            }
        }
    }

    private suspend fun executeRequest(request: Request): String = suspendCancellableCoroutine { continuation ->
        val timeout = ReviewDownloadConfig.Number.DATA_TIMEOUT.value.toLong()
        val call = client.newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("评论数据 HTTP ${it.code}")
                        it.body?.string() ?: throw IOException("评论数据响应为空")
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
