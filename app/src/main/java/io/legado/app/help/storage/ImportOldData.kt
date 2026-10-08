package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jayway.jsonpath.DocumentContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.*
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ReplaceAnalyzer
import io.legado.app.utils.*
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

object ImportOldData {

    @Suppress("RegExpRedundantEscape")
    private val headerPattern = Pattern.compile("@Header:\\{.+?\\}", Pattern.CASE_INSENSITIVE)
    @Suppress("RegExpRedundantEscape")
    private val jsPattern = Pattern.compile("\\{\\{.+?\\}\\}", Pattern.CASE_INSENSITIVE)

    private const val SHELF_FILE_NAME = "myBookShelf.json"
    private const val SOURCE_FILE_NAME = "myBookSource.json"
    private const val REPLACE_RULE_FILE_NAME = "myBookReplaceRule.json"

    private val oldFileNames = setOf(SHELF_FILE_NAME, SOURCE_FILE_NAME, REPLACE_RULE_FILE_NAME)

    /**
     * 导入旧版（阅读 2.x）数据，阻塞执行，调用方须在后台线程调用。
     * 支持三种入口：数据目录、旧版备份压缩包(zip)、单个旧版 json 文件。
     * 返回汇总消息（各项成功/失败/未找到），供调用方 toast 展示。
     */
    fun importUri(context: Context, uri: Uri): String {
        return try {
            if (uri.isContentScheme()) {
                importContentUri(context, uri)
            } else {
                val path = uri.path ?: return "导入旧版数据失败\n无效路径"
                val file = File(path)
                if (file.isFile) {
                    importZipFile(context, file)
                } else {
                    importLocalDir(file)
                }
            }
        } catch (e: Exception) {
            AppLog.put("导入旧版数据出错\n${e.localizedMessage}", e)
            "导入旧版数据出错\n${e.localizedMessage}"
        }
    }

    private fun importContentUri(context: Context, uri: Uri): String {
        // 目录选择（DIR 模式）返回 tree uri；fromTreeUri 对普通文件 uri 会抛异常
        val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (tree != null) {
            return importTreeDir(context, tree)
        }
        // 文件选择（FILE 模式）：zip 压缩包或单个旧版 json 文件
        val name = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
        return when {
            name == null || name.endsWith(".zip", true) -> importZipUri(context, uri)
            name in oldFileNames -> importSingleJson(context, uri, name)
            else -> "导入旧版数据失败\n不支持的文件：$name"
        }
    }

    private fun importTreeDir(context: Context, dir: DocumentFile): String {
        val messages = mutableListOf<String>()
        var shelfJson: String? = null
        var sourceJson: String? = null
        var ruleJson: String? = null
        for (doc in dir.listFiles()) {
            when (doc.name) {
                SHELF_FILE_NAME -> shelfJson = readDoc(context, doc, messages)
                SOURCE_FILE_NAME -> sourceJson = readDoc(context, doc, messages)
                REPLACE_RULE_FILE_NAME -> ruleJson = readDoc(context, doc, messages)
            }
        }
        shelfJson?.let { importShelf(it, messages) }
        sourceJson?.let { importSources(it, messages) }
        ruleJson?.let { importReplaceRules(it, messages) }
        return summary(messages)
    }

    private fun importLocalDir(dir: File): String {
        if (!dir.isDirectory) {
            return "导入旧版数据失败\n目录不存在：${dir.absolutePath}"
        }
        val messages = mutableListOf<String>()
        findFile(dir, SHELF_FILE_NAME)?.let { readLocalFile(it, messages) }
            ?.let { importShelf(it, messages) }
        findFile(dir, SOURCE_FILE_NAME)?.let { readLocalFile(it, messages) }
            ?.let { importSources(it, messages) }
        findFile(dir, REPLACE_RULE_FILE_NAME)?.let { readLocalFile(it, messages) }
            ?.let { importReplaceRules(it, messages) }
        return summary(messages)
    }

    private fun importSingleJson(context: Context, uri: Uri, name: String): String {
        val json = runCatching { uri.readText(context) }.getOrElse {
            AppLog.put("导入旧版数据读取 $name 失败\n${it.localizedMessage}", it)
            return "导入旧版数据失败\n${it.localizedMessage}"
        }
        val messages = mutableListOf<String>()
        when (name) {
            SHELF_FILE_NAME -> importShelf(json, messages)
            SOURCE_FILE_NAME -> importSources(json, messages)
            REPLACE_RULE_FILE_NAME -> importReplaceRules(json, messages)
        }
        return summary(messages)
    }

    private fun importZipUri(context: Context, uri: Uri): String {
        val tempZip = File(context.cacheDir, "import_old_${UUID.randomUUID()}.zip")
        return try {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    BackupArchiveExtractor.copyToTemporaryFile(input, tempZip)
                }
            }.getOrElse {
                return "导入旧版数据失败\n无法读取所选文件：${it.localizedMessage}"
            }
            importZipFile(context, tempZip)
        } finally {
            tempZip.delete()
        }
    }

    private fun importZipFile(context: Context, zipFile: File): String {
        val tempDir = File(context.cacheDir, "import_old_${UUID.randomUUID()}")
        return try {
            tempDir.mkdirs()
            BackupArchiveExtractor.extract(zipFile, tempDir)
            importLocalDir(tempDir)
        } catch (e: Exception) {
            AppLog.put("导入旧版数据解压失败\n${e.localizedMessage}", e)
            "导入旧版数据失败\n解压失败：${e.localizedMessage}"
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** 顶层找不到时向有限深度内查找（旧版备份压缩包可能多包一层目录） */
    private fun findFile(dir: File, name: String): File? {
        val topFile = File(dir, name)
        if (topFile.isFile) return topFile
        return runCatching {
            dir.walkTopDown().maxDepth(4)
                .firstOrNull { it.isFile && it.name == name }
        }.getOrNull()
    }

    private fun readDoc(
        context: Context,
        doc: DocumentFile,
        messages: MutableList<String>
    ): String? {
        return runCatching { doc.uri.readText(context) }.getOrElse {
            AppLog.put("导入旧版数据读取 ${doc.name} 失败\n${it.localizedMessage}", it)
            messages.add("读取 ${doc.name} 失败\n${it.localizedMessage}")
            null
        }
    }

    private fun readLocalFile(file: File, messages: MutableList<String>): String? {
        return runCatching { file.readText() }.getOrElse {
            AppLog.put("导入旧版数据读取 ${file.name} 失败\n${it.localizedMessage}", it)
            messages.add("读取 ${file.name} 失败\n${it.localizedMessage}")
            null
        }
    }

    private fun importShelf(json: String, messages: MutableList<String>) {
        if (json.isBlank()) {
            messages.add("书架数据为空")
            return
        }
        runCatching {
            val count = importOldBookshelf(json)
            messages.add("成功导入书架${count}本")
        }.onFailure {
            messages.add("导入书架失败\n${it.localizedMessage}")
            AppLog.put("导入旧版书架失败\n${it.localizedMessage}", it)
        }
    }

    private fun importSources(json: String, messages: MutableList<String>) {
        if (json.isBlank()) {
            messages.add("书源数据为空")
            return
        }
        runCatching {
            val (count, failed) = importOldSources(json)
            messages.add(
                if (failed > 0) "成功导入书源${count}条，${failed}条格式错误已跳过"
                else "成功导入书源${count}条"
            )
        }.onFailure {
            messages.add("导入书源失败\n${it.localizedMessage}")
            AppLog.put("导入旧版书源失败\n${it.localizedMessage}", it)
        }
    }

    private fun importReplaceRules(json: String, messages: MutableList<String>) {
        if (json.isBlank()) {
            messages.add("替换规则数据为空")
            return
        }
        runCatching {
            val count = importOldReplaceRule(json)
            messages.add("成功导入替换规则${count}条")
        }.onFailure {
            messages.add("导入替换规则失败\n${it.localizedMessage}")
            AppLog.put("导入旧版替换规则失败\n${it.localizedMessage}", it)
        }
    }

    private fun summary(messages: List<String>): String {
        if (messages.isEmpty()) {
            return "未找到旧版数据文件\n（myBookShelf.json、myBookSource.json、myBookReplaceRule.json）"
        }
        return messages.joinToString("\n")
    }

    private fun importOldBookshelf(json: String): Int {
        val books = fromOldBooks(json)
        if (books.isNotEmpty()) {
            appDb.bookDao.insert(*books.toTypedArray())
        }
        return books.size
    }

    fun importOldSource(json: String): Int {
        val (count, failed) = importOldSources(json)
        if (failed > 0) {
            AppLog.put("导入旧版书源：${failed}条格式错误已跳过")
        }
        return count
    }

    /** 逐条转换并容错：单条格式错误只跳过该条，不影响整批导入 */
    private fun importOldSources(json: String): Pair<Int, Int> {
        val sources = mutableListOf<BookSource>()
        var failed = 0
        for (item in parseOldItems(json)) {
            runCatching { fromOldBookSource(jsonPath.parse(item)) }
                .onSuccess { sources.add(it) }
                .onFailure { e ->
                    failed++
                    AppLog.put("旧版书源转换失败\n${e.localizedMessage}", e)
                }
        }
        if (sources.isNotEmpty()) {
            appDb.bookSourceDao.insert(*sources.toTypedArray())
        }
        return sources.size to failed
    }

    private fun importOldReplaceRule(json: String): Int {
        val rules = ReplaceAnalyzer.jsonToReplaceRules(json).getOrNull().orEmpty()
        if (rules.isNotEmpty()) {
            appDb.replaceRuleDao.insert(*rules.toTypedArray())
        }
        return rules.size
    }

    /** 兼容根节点为数组或单个对象两种旧版数据格式 */
    @Suppress("UNCHECKED_CAST")
    private fun parseOldItems(json: String): List<Map<String, Any>> {
        val root: Any = jsonPath.parse(json).read("$")
        return when (root) {
            is List<*> -> root.mapNotNull { it as? Map<String, Any> }
            is Map<*, *> -> listOf(root as Map<String, Any>)
            else -> throw NoStackTraceException(appCtx.getString(R.string.wrong_format))
        }
    }

    private fun fromOldBooks(json: String): List<Book> {
        val books = mutableListOf<Book>()
        val existingBooks = appDb.bookDao.allBookUrls.toSet()
        for (item in parseOldItems(json)) {
            runCatching { parseOldBook(jsonPath.parse(item), existingBooks) }
                .onSuccess { book -> book?.let(books::add) }
                .onFailure { e ->
                    AppLog.put("旧版书架条目转换失败\n${e.localizedMessage}", e)
                }
        }
        return books
    }

    private fun parseOldBook(jsonItem: DocumentContext, existingBooks: Set<String>): Book? {
        val book = Book()
        book.bookUrl = jsonItem.readString("$.noteUrl") ?: ""
        if (book.bookUrl.isBlank()) return null
        book.name = jsonItem.readString("$.bookInfoBean.name") ?: ""
        if (book.bookUrl in existingBooks) {
            DebugLog.d(javaClass.name, "Found existing book: " + book.name)
            return null
        }
        book.origin = jsonItem.readString("$.tag") ?: ""
        book.originName = jsonItem.readString("$.bookInfoBean.origin") ?: ""
        book.author = jsonItem.readString("$.bookInfoBean.author") ?: ""
        val local = if (book.origin == "loc_book") BookType.local else 0
        val isAudio = jsonItem.readString("$.bookInfoBean.bookSourceType") == "AUDIO"
        book.type = local or if (isAudio) BookType.audio else BookType.text
        book.tocUrl = jsonItem.readString("$.bookInfoBean.chapterUrl") ?: book.bookUrl
        book.coverUrl = jsonItem.readString("$.bookInfoBean.coverUrl")
        book.customCoverUrl = jsonItem.readString("$.customCoverPath")
        book.lastCheckTime = jsonItem.readLong("$.bookInfoBean.finalRefreshData") ?: 0
        book.canUpdate = jsonItem.readBool("$.allowUpdate") == true
        book.totalChapterNum = jsonItem.readInt("$.chapterListSize") ?: 0
        book.durChapterIndex = jsonItem.readInt("$.durChapter") ?: 0
        book.durChapterTitle = jsonItem.readString("$.durChapterName")
        book.durChapterPos = jsonItem.readInt("$.durChapterPage") ?: 0
        book.durChapterTime = jsonItem.readLong("$.finalDate") ?: 0
        book.intro = jsonItem.readString("$.bookInfoBean.introduce")
        book.latestChapterTitle = jsonItem.readString("$.lastChapterName")
        book.lastCheckCount = jsonItem.readInt("$.newChapters") ?: 0
        book.order = jsonItem.readInt("$.serialNumber") ?: 0
        book.variable = jsonItem.readString("$.variable")
        book.setUseReplaceRule(jsonItem.readBool("$.useReplaceRule") == true)
        return book
    }

    fun fromOldBookSource(jsonItem: DocumentContext): BookSource {
        val source = BookSource()
        return source.apply {
            bookSourceUrl = jsonItem.readString("bookSourceUrl")
                ?: throw NoStackTraceException(appCtx.getString(R.string.wrong_format))
            bookSourceName = jsonItem.readString("bookSourceName") ?: ""
            bookSourceGroup = jsonItem.readString("bookSourceGroup")
            loginUrl = jsonItem.readString("loginUrl")
            loginUi = jsonItem.readString("loginUi")
            loginCheckJs = jsonItem.readString("loginCheckJs")
            coverDecodeJs = jsonItem.readString("coverDecodeJs")
            bookSourceComment = jsonItem.readString("bookSourceComment") ?: ""
            bookUrlPattern = jsonItem.readString("ruleBookUrlPattern")
            customOrder = jsonItem.readInt("serialNumber") ?: 0
            header = uaToHeader(jsonItem.readString("httpUserAgent"))
            searchUrl = toNewUrl(jsonItem.readString("ruleSearchUrl"))
            exploreUrl = toNewUrls(jsonItem.readString("ruleFindUrl"))
            bookSourceType =
                if (jsonItem.readString("bookSourceType") == "AUDIO") BookSourceType.audio else BookSourceType.default
            enabled = jsonItem.readBool("enable") ?: true
            if (exploreUrl.isNullOrBlank()) {
                enabledExplore = false
            }
            ruleSearch = SearchRule(
                bookList = toNewRule(jsonItem.readString("ruleSearchList")),
                name = toNewRule(jsonItem.readString("ruleSearchName")),
                author = toNewRule(jsonItem.readString("ruleSearchAuthor")),
                intro = toNewRule(jsonItem.readString("ruleSearchIntroduce")),
                kind = toNewRule(jsonItem.readString("ruleSearchKind")),
                bookUrl = toNewRule(jsonItem.readString("ruleSearchNoteUrl")),
                coverUrl = toNewRule(jsonItem.readString("ruleSearchCoverUrl")),
                lastChapter = toNewRule(jsonItem.readString("ruleSearchLastChapter"))
            )
            ruleExplore = ExploreRule(
                bookList = toNewRule(jsonItem.readString("ruleFindList")),
                name = toNewRule(jsonItem.readString("ruleFindName")),
                author = toNewRule(jsonItem.readString("ruleFindAuthor")),
                intro = toNewRule(jsonItem.readString("ruleFindIntroduce")),
                kind = toNewRule(jsonItem.readString("ruleFindKind")),
                bookUrl = toNewRule(jsonItem.readString("ruleFindNoteUrl")),
                coverUrl = toNewRule(jsonItem.readString("ruleFindCoverUrl")),
                lastChapter = toNewRule(jsonItem.readString("ruleFindLastChapter"))
            )
            ruleBookInfo = BookInfoRule(
                init = toNewRule(jsonItem.readString("ruleBookInfoInit")),
                name = toNewRule(jsonItem.readString("ruleBookName")),
                author = toNewRule(jsonItem.readString("ruleBookAuthor")),
                intro = toNewRule(jsonItem.readString("ruleIntroduce")),
                kind = toNewRule(jsonItem.readString("ruleBookKind")),
                coverUrl = toNewRule(jsonItem.readString("ruleCoverUrl")),
                lastChapter = toNewRule(jsonItem.readString("ruleBookLastChapter")),
                tocUrl = toNewRule(jsonItem.readString("ruleChapterUrl"))
            )
            ruleToc = TocRule(
                chapterList = toNewRule(jsonItem.readString("ruleChapterList")),
                chapterName = toNewRule(jsonItem.readString("ruleChapterName")),
                chapterUrl = toNewRule(jsonItem.readString("ruleContentUrl")),
                nextTocUrl = toNewRule(jsonItem.readString("ruleChapterUrlNext"))
            )
            var content = toNewRule(jsonItem.readString("ruleBookContent")) ?: ""
            if (content.startsWith("$") && !content.startsWith("$.")) {
                content = content.substring(1)
            }
            ruleContent = ContentRule(
                content = content,
                replaceRegex = toNewRule(jsonItem.readString("ruleBookContentReplace")),
                nextContentUrl = toNewRule(jsonItem.readString("ruleContentUrlNext"))
            )
        }
    }


    // default规则适配
    // #正则#替换内容 替换成 ##正则##替换内容
    // | 替换成 ||
    // & 替换成 &&
    private fun toNewRule(oldRule: String?): String? {
        if (oldRule.isNullOrBlank()) return null
        var newRule = oldRule
        var reverse = false
        var allinone = false
        if (oldRule.startsWith("-")) {
            reverse = true
            newRule = oldRule.substring(1)
        }
        if (newRule.startsWith("+")) {
            allinone = true
            newRule = newRule.substring(1)
        }
        if (!newRule.startsWith("@CSS:", true) &&
            !newRule.startsWith("@XPath:", true) &&
            !newRule.startsWith("//") &&
            !newRule.startsWith("##") &&
            !newRule.startsWith(":") &&
            !newRule.contains("@js:", true) &&
            !newRule.contains("<js>", true)
        ) {
            if (newRule.contains("#") && !newRule.contains("##")) {
                newRule = oldRule.replace("#", "##")
            }
            if (newRule.contains("|") && !newRule.contains("||")) {
                if (newRule.contains("##")) {
                    val list = newRule.split("##")
                    if (list[0].contains("|")) {
                        newRule = list[0].replace("|", "||")
                        for (i in 1 until list.size) {
                            newRule += "##" + list[i]
                        }
                    }
                } else {
                    newRule = newRule.replace("|", "||")
                }
            }
            if (newRule.contains("&")
                && !newRule.contains("&&")
                && !newRule.contains("http")
                && !newRule.startsWith("/")
            ) {
                newRule = newRule.replace("&", "&&")
            }
        }
        if (allinone) {
            newRule = "+$newRule"
        }
        if (reverse) {
            newRule = "-$newRule"
        }
        return newRule
    }

    private fun toNewUrls(oldUrls: String?): String? {
        if (oldUrls.isNullOrBlank()) return null
        if (oldUrls.startsWith("@js:") || oldUrls.startsWith("<js>")) {
            return oldUrls
        }
        if (!oldUrls.contains("\n") && !oldUrls.contains("&&")) {
            return toNewUrl(oldUrls)
        }
        val urls = oldUrls.split("(&&|\r?\n)+".toRegex())
        return urls.map {
            toNewUrl(it)?.replace("\n\\s*".toRegex(), "")
        }.joinToString("\n")
    }

    private fun toNewUrl(oldUrl: String?): String? {
        if (oldUrl.isNullOrBlank()) return null
        var url: String = oldUrl
        if (oldUrl.startsWith("<js>", true)) {
            url = url.replace("=searchKey", "={{key}}")
                .replace("=searchPage", "={{page}}")
            return url
        }
        val map = HashMap<String, String>()
        var mather = headerPattern.matcher(url)
        if (mather.find()) {
            val header = mather.group()
            url = url.replace(header, "")
            map["headers"] = header.substring(8)
        }
        var urlList = url.split("|")
        url = urlList[0]
        if (urlList.size > 1) {
            map["charset"] = urlList[1].split("=").getOrNull(1) ?: "utf-8"
        }
        mather = jsPattern.matcher(url)
        val jsList = arrayListOf<String>()
        while (mather.find()) {
            jsList.add(mather.group())
            url = url.replace(jsList.last(), "$${jsList.size - 1}")
        }
        url = url.replace("{", "<").replace("}", ">")
        url = url.replace("searchKey", "{{key}}")
        url = url.replace("<searchPage([-+]1)>".toRegex(), "{{page$1}}")
            .replace("searchPage([-+]1)".toRegex(), "{{page$1}}")
            .replace("searchPage", "{{page}}")
        for ((index, item) in jsList.withIndex()) {
            url = url.replace(
                "$$index",
                item.replace("searchKey", "key").replace("searchPage", "page")
            )
        }
        urlList = url.split("@")
        url = urlList[0]
        if (urlList.size > 1) {
            map["method"] = "POST"
            map["body"] = urlList[1]
        }
        if (map.size > 0) {
            url += "," + GSON.toJson(map)
        }
        return url
    }

    private fun uaToHeader(ua: String?): String? {
        if (ua.isNullOrEmpty()) return null
        val map = mapOf(Pair(AppConst.UA_NAME, ua))
        return GSON.toJson(map)
    }

}