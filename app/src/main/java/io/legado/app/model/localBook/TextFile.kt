package io.legado.app.model.localBook

import androidx.annotation.Keep
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.exception.EmptyFileException
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.config.AppConfig
import io.legado.app.help.search.LibrarySearchIndexer
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.Utf8BomUtils
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.min

class TextFile(private var book: Book) {

    @Suppress("ConstPropertyName")
    companion object {
        private val padRegex = "^[\\n\\s]+".toRegex()
        const val spaceChars = "🫅🈳🏻"
        private const val txtBufferSize = 8 * 1024 * 1024
        private var textFile: TextFile? = null

        @Synchronized
        private fun getTextFile(book: Book): TextFile {
            if (textFile == null || textFile?.book?.bookUrl != book.bookUrl || book.isLocalModified()) {
                textFile = TextFile(book)
                return textFile!!
            }
            textFile?.book = book
            return textFile!!
        }

        @Throws(FileNotFoundException::class)
        fun getChapterList(
            book: Book,
            onProgress: ((Int) -> Unit)? = null,
            onCharsetSuspect: (() -> Unit)? = null
        ): ArrayList<BookChapter> {
            return getTextFile(book).getChapterList(onProgress, onCharsetSuspect)
        }

        @Synchronized
        @Throws(FileNotFoundException::class)
        fun getContent(book: Book, bookChapter: BookChapter): String {
            return getTextFile(book).getContent(bookChapter)
        }

        fun clear() {
            textFile = null
        }

    }

    private val blank: Byte = 0x0a

    //默认从文件中获取数据的长度
    private val bufferSize = 512000

    //没有标题的时候，每个章节的最大长度
    private val maxLengthWithNoToc = 10 * 1024

    //使用正则划分目录，每个章节的最大允许长度
    private val maxLengthWithToc = 102400

    //已读章节正文内存缓存：最多缓存的章节数与单章字数上限
    private val maxCachedChapters = 12
    private val maxCachedChapterLength = 300_000

    private var charset: Charset = book.fileCharset()

    /**
     * 已解码章节正文的 LRU 缓存。本地 txt 正文不落盘，翻回看过的章节
     * 不必再读文件+解码；仅缓存本章（TextFile 单例只持有一本书）。
     */
    private val contentCache = LinkedHashMap<String, String>()

    private var txtBuffer: ByteArray? = null
    private var bufferStart = -1L
    private var bufferEnd = -1L

    //选中更好的目录规则判断阈值
    private val overRuleCount = 2
    private val replaceBook = book.toReplaceBook()
    class MutableRef<String>(var value: String)
    private val lastVolumeTitle = MutableRef("")

    /**
     * 获取目录
     *
     * @param onProgress 分章进度回调，参数为百分比 0-100（仅 txt 解析时回调）
     * @param onCharsetSuspect 自动检测的编码疑似不正确时的回调
     */
    @Throws(FileNotFoundException::class, SecurityException::class, EmptyFileException::class)
    fun getChapterList(
        onProgress: ((Int) -> Unit)? = null,
        onCharsetSuspect: (() -> Unit)? = null
    ): ArrayList<BookChapter> {
        val modified = book.isLocalModified()
        //用户在阅读菜单/详情页改过编码时，TextFile 单例会被复用（同 bookUrl 且文件未变），
        //必须在此刷新 charset 字段，否则 getContent 仍按旧编码解码
        val charsetChanged = charset != book.fileCharset()
        if (charsetChanged) {
            synchronized(contentCache) { contentCache.clear() }
        }
        if (book.charset == null || book.tocUrl.isBlank() || modified || charsetChanged) {
            LocalBook.getBookInputStream(book).use { bis ->
                val buffer = ByteArray(bufferSize)
                val length = bis.read(buffer)
                if (length == -1) throw EmptyFileException("Unexpected Empty Txt File")
                if (book.charset.isNullOrBlank() || modified) {
                    book.charset = EncodingDetect.getEncode(buffer.copyOf(length))
                    charset = book.fileCharset()
                    /**
                     * 编码检测只采样前 bufferSize 字节且不可靠（GBK/UTF-8 混排尤其容易误判），
                     * 解码样本中替换符比例过高时说明大概率是乱码，提示用户可在阅读菜单中手动切换编码。
                     */
                    val sample = String(buffer, 0, length, charset)
                    if (sample.length > 1000 &&
                        sample.count { it == '\uFFFD' } > sample.length * 0.01
                    ) {
                        onCharsetSuspect?.invoke()
                    }
                }
                charset = book.fileCharset()
                if (book.tocUrl.isBlank() || modified) {
                    val blockContent = String(buffer, 0, length, charset)
                    val tocRule = getTocRule(blockContent)
                    book.tocUrl = tocRule?.let { it.rule + spaceChars + it.replacement } ?: ""
                }
            }
        }
        val fileSize = LocalBook.getBookSize(book)
        val (toc, wordCount) = analyze(
            book.tocUrl.split(spaceChars, limit = 2),
            fileSize,
            onProgress
        )
        book.wordCount = StringUtils.wordCountFormat(wordCount)
        toc.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            bookChapter.bookUrl = book.bookUrl
            bookChapter.url = MD5Utils.md5Encode16(book.originName + index + bookChapter.title)
        }
        getWordCount(toc, book)
        return toc
    }

    fun getContent(chapter: BookChapter): String {
        val start = chapter.start!!
        val end = chapter.end!!
        synchronized(contentCache) { contentCache[chapter.url] }?.let { return it }
        if (txtBuffer == null || start > bufferEnd || end < bufferStart) {
            LocalBook.getBookInputStream(book).use { bis ->
                bufferStart = txtBufferSize * (start / txtBufferSize)
                bis.skipFully(bufferStart)
                val buf = ByteArray(txtBufferSize)
                var read = 0
                while (read < buf.size) {
                    val r = bis.read(buf, read, buf.size - read)
                    if (r < 0) break
                    read += r
                }
                txtBuffer = if (read == buf.size) buf else buf.copyOf(read)
                bufferEnd = bufferStart + txtBuffer!!.size
            }
        }

        val count = (end - start).toInt()
        val buffer = ByteArray(count)

        @Suppress("ConvertTwoComparisonsToRangeCheck")
        if (start < bufferEnd && end > bufferEnd || start < bufferStart && end > bufferStart) {
            /** 章节内容在缓冲区交界处 */
            LocalBook.getBookInputStream(book).use { bis ->
                bis.skipFully(start)
                bis.readFully(buffer)
            }
        } else {
            /** 章节内容在缓冲区内 */
            txtBuffer!!.copyInto(
                buffer,
                0,
                (start - bufferStart).toInt(),
                (end - bufferStart).toInt()
            )
        }

        val content = String(buffer, charset)
            //.substringAfter(chapter.title) //之前裁切没有间隙，所以正文保留了标题，需要在这儿去掉
            .replace(padRegex, "　　")
        putContentCache(chapter.url, content)
        //本地 txt 正文不落盘，网络书靠 saveText 钩子建的全库索引对本地书不生效；
        //在读到的瞬间补建索引，让全库搜索能搜到本地 txt（只建一次，重复阅读不重复建；
        //索引内容上限由 LibrarySearchIndexer 内部截断，文件被外部改动后需手动重建索引）
        if (!LibrarySearchIndexer.hasChapterIndexed(book.bookUrl, chapter.index)) {
            LibrarySearchIndexer.indexChapterAsync(book, chapter, content)
        }
        return content
    }

    private fun putContentCache(url: String, content: String) {
        if (content.length > maxCachedChapterLength) return
        synchronized(contentCache) {
            contentCache.remove(url)
            contentCache[url] = content
            while (contentCache.size > maxCachedChapters) {
                val eldest = contentCache.keys.iterator().next()
                contentCache.remove(eldest)
            }
        }
    }

    /**
     * 按规则解析目录
     *
     * @param fileSize 文件总字节数，>0 时才上报进度
     * @param onProgress 每解析约 1% 回调一次当前进度（0-99）
     */
    private fun analyze(
        rr: List<String>,
        fileSize: Long = 0L,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<ArrayList<BookChapter>, Int> {
        val pattern = rr[0].toPattern(Pattern.MULTILINE)
        val jsStr = rr.getOrNull(1)
        if (rr[0].isEmpty()) {
            return analyze()
        }
        lastVolumeTitle.value = ""
        val toc = arrayListOf<BookChapter>()
        var bookWordCount = 0
        var lastPercent = -1
        LocalBook.getBookInputStream(book).use { bis ->
            var blockContent: String
            //加载章节
            var curOffset: Long = 0
            //读取的长度
            var length: Int
            var lastChapterWordCount = 0
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            bis.read(buffer, 0, 3)
            if (Utf8BomUtils.hasBom(buffer)) {
                bufferStart = 0
                curOffset = 3
            }
            //获取文件中的数据到buffer，直到没有数据为止
            while (bis.read(
                    buffer, bufferStart, bufferSize - bufferStart
                ).also { length = it } > 0
            ) {
                var end = bufferStart + length
                if (end == bufferSize) {
                    for (i in bufferStart + length - 1 downTo 0) {
                        if (buffer[i] == blank) {
                            end = i
                            break
                        }
                    }
                }
                //将数据转换成String, 不能超过length
                blockContent = String(buffer, 0, end, charset)
                buffer.copyInto(buffer, 0, end, bufferStart + length)
                bufferStart = bufferStart + length - end
                length = end
                //当前Block下使过的String的指针
                var seekPos = 0
                //进行正则匹配
                val matcher: Matcher = pattern.matcher(blockContent)
                //如果存在相应章节
                while (matcher.find()) { //获取匹配到的字符在字符串中的起始位置
                    val chapterStart = matcher.start()
                    //获取章节内容
                    val chapterContent = blockContent.substring(seekPos, chapterStart)
                    val chapterContentLength = chapterContent.length
                    val chapterLength = chapterContent.toByteArray(charset).size.toLong()
                    val titleLength = matcher.group().toByteArray(charset).size.toLong()
                    val lastStart = toc.lastOrNull()?.start ?: curOffset
                    if (book.getSplitLongChapter() && curOffset + chapterLength - lastStart > maxLengthWithToc) {
                        //章节字数太多进行拆分
                        val lastTitle = toc.lastOrNull()?.let {
                            it.end = it.start
                            it.isVolume = true
                            lastVolumeTitle.value = it.title
                            it.tag = null
                            it.title
                        }
                        val title = replacement(
                            matcher.group(),
                            jsStr,
                            toc,
                            lastTitle,
                            chapterContentLength
                        ).takeIf { it.isNotEmpty() } ?: continue
                        val (chapters, wordCount) = analyze(lastStart, curOffset + chapterLength)
                        lastTitle?.let {
                            chapters.forEachIndexed { index, bookChapter ->
                                bookChapter.title = "$lastTitle(${index + 1})"
                            }
                        }
                        toc.addAll(chapters)
                        bookWordCount += wordCount
                        //创建当前章节
                        val curChapter = BookChapter()
                        curChapter.title = title
                        curChapter.start = curOffset + chapterLength
                        curChapter.end = curChapter.start
                        toc.add(curChapter)
                        lastChapterWordCount = 0
                    } else if (seekPos == 0 && chapterStart != 0) {
                        /**
                         * 如果 seekPos == 0 && chapterStart != 0 表示当前block处前面有一段内容
                         * 第一种情况一定是序章 第二种情况是上一个章节的内容
                         */
                        if (toc.isEmpty()) { //如果当前没有章节，那么就是序章
                            //加入简介
                            if (chapterContent.isNotBlank()) {
                                val title = replacement("前言", jsStr, toc)
                                if (title.isNotEmpty()) {
                                    //如果js把"前言"处理成空了，那么就不要前言,并且前言内容会全部放到简介里面去
                                    val qyChapter = BookChapter()
                                    qyChapter.title = title
                                    qyChapter.start = curOffset
                                    qyChapter.end = curOffset + chapterLength
                                    qyChapter.wordCount =
                                        StringUtils.wordCountFormat(chapterContentLength)
                                    toc.add(qyChapter)
                                }
                                book.intro = if (chapterContentLength > 600 && title.isNotEmpty()) {
                                    chapterContent.take(600)
                                } else {
                                    chapterContent
                                }
                            }
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc
                            ).takeIf { it.isNotEmpty() } ?: continue
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = curOffset + chapterLength + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        } else { //否则就block分割之后，上一个章节的剩余内容
                            //获取上一章节
                            val lastChapter = toc.last()
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc,
                                lastChapter.title,
                                chapterContentLength
                            ).takeIf { it.isNotEmpty() } ?: continue
                            if (chapterContent.isBlank()) {
                                lastChapter.isVolume = true
                                lastVolumeTitle.value = lastChapter.title
                            } else {
                                lastChapter.isVolume = false
                            }
                            //将当前段落添加上一章去
                            lastChapter.end = lastChapter.end!! + chapterLength
                            lastChapterWordCount += chapterContentLength
                            lastChapter.wordCount =
                                StringUtils.wordCountFormat(lastChapterWordCount)
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = lastChapter.end!! + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        }
                        bookWordCount += chapterContentLength
                        lastChapterWordCount = 0
                    } else {
                        if (toc.isNotEmpty()) { //获取章节内容
                            //获取上一章节
                            val lastChapter = toc.last()
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc,
                                lastChapter.title,
                                chapterContentLength
                            ).takeIf { it.isNotEmpty() }
                                ?: continue
                            if (chapterContent.isBlank()) {
                                lastChapter.isVolume = true
                                lastVolumeTitle.value = lastChapter.title
                            } else {
                                lastChapter.isVolume = false
                            }
                            lastChapter.end =
                                lastChapter.start!! + chapterLength
                            lastChapter.wordCount =
                                StringUtils.wordCountFormat(chapterContentLength)
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = lastChapter.end!! + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        } else { //如果章节不存在则创建章节
                            val title = replacement(matcher.group(), jsStr, toc).takeIf { it.isNotEmpty() }
                                ?: continue
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = curOffset + titleLength
                            curChapter.end = curChapter.start
                            curChapter.wordCount =
                                StringUtils.wordCountFormat(chapterContentLength)
                            toc.add(curChapter)
                        }
                        bookWordCount += chapterContentLength
                        lastChapterWordCount = 0
                    }
                    //设置指针偏移
                    seekPos += chapterContentLength + matcher.group().length
                }
                val wordCount = blockContent.length - seekPos
                bookWordCount += wordCount
                lastChapterWordCount += wordCount
                //block的偏移点
                curOffset += length.toLong()
                if (fileSize > 0) {
                    val percent = (curOffset * 100 / fileSize).toInt().coerceIn(0, 99)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress?.invoke(percent)
                    }
                }
                //设置上一章的结尾
                toc.lastOrNull()?.let {
                    it.end = curOffset
                    it.wordCount = StringUtils.wordCountFormat(lastChapterWordCount)
                }
            }
            toc.lastOrNull()?.let { chapter ->
                //章节字数太多进行拆分
                if (book.getSplitLongChapter() && chapter.end!! - chapter.start!! > maxLengthWithToc) {
                    val end = chapter.end!!
                    chapter.end = chapter.start
                    chapter.isVolume = true
                    chapter.tag = null
                    val lastTitle = chapter.title
                    lastVolumeTitle.value = lastTitle
                    val (chapters, _) = analyze(
                        chapter.start!!, end
                    )
                    chapters.forEachIndexed { index, bookChapter ->
                        bookChapter.title = "$lastTitle(${index + 1})"
                    }
                    toc.addAll(chapters)
                }
            }
        }
        return toc to bookWordCount
    }

    /**
     * 无规则拆分目录
     */
    private fun analyze(
        fileStart: Long = 0L, fileEnd: Long = Long.MAX_VALUE
    ): Pair<ArrayList<BookChapter>, Int> {
        val toc = arrayListOf<BookChapter>()
        var bookWordCount = 0
        LocalBook.getBookInputStream(book).use { bis ->
            //block的个数
            var blockPos = 0
            //加载章节
            var curOffset: Long = 0
            var chapterPos = 0
            //读取的长度
            var length = 0
            var lastChapterWordCount = 0
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            if (fileStart == 0L) {
                bis.read(buffer, 0, 3)
                if (Utf8BomUtils.hasBom(buffer)) {
                    bufferStart = 0
                    curOffset = 3
                }
            } else {
                bis.skip(fileStart)
                curOffset = fileStart
                bufferStart = 0
            }
            //获取文件中的数据到buffer，直到没有数据为止
            while (
                fileEnd - curOffset - bufferStart > 0 &&
                bis.read(
                    buffer,
                    bufferStart,
                    min((bufferSize - bufferStart).toLong(), fileEnd - curOffset - bufferStart).toInt()
                ).also { length = it } > 0
            ) {
                blockPos++
                //章节在buffer的偏移量
                var chapterOffset = 0
                //当前剩余可分配的长度
                length += bufferStart
                var strLength = length
                //分章的位置
                chapterPos = 0
                while (strLength > 0) {
                    chapterPos++
                    //是否长度超过一章
                    if (strLength > maxLengthWithNoToc) { //在buffer中一章的终止点
                        var end = length
                        //寻找换行符作为终止点
                        for (i in chapterOffset + maxLengthWithNoToc until length) {
                            if (buffer[i] == blank) {
                                end = i
                                break
                            }
                        }
                        val content = String(buffer, chapterOffset, end - chapterOffset, charset)
                        bookWordCount += content.length
                        lastChapterWordCount = content.length
                        val chapter = BookChapter()
                        chapter.title = "第${blockPos}章($chapterPos)"
                        chapter.start = toc.lastOrNull()?.end ?: curOffset
                        chapter.end = chapter.start!! + end - chapterOffset
                        chapter.wordCount = StringUtils.wordCountFormat(content.length)
                        toc.add(chapter)
                        //减去已经被分配的长度
                        strLength -= (end - chapterOffset)
                        //设置偏移的位置
                        chapterOffset = end
                    } else {
                        buffer.copyInto(buffer, 0, length - strLength, length)
                        length -= strLength
                        bufferStart = strLength
                        strLength = 0
                    }
                }
                //block的偏移点
                curOffset += length.toLong()
            }
            //设置结尾章节
            val content = String(buffer, 0, bufferStart, charset)
            bookWordCount += content.length
            if (bufferStart > 100 || toc.isEmpty()) {
                val chapter = BookChapter()
                chapter.title = "第${blockPos}章(${chapterPos})"
                chapter.start = toc.lastOrNull()?.end ?: curOffset
                chapter.end = chapter.start!! + bufferStart
                chapter.wordCount = StringUtils.wordCountFormat(content.length)
                toc.add(chapter)
            } else {
                val wordCount = lastChapterWordCount + content.length
                toc.lastOrNull()?.let {
                    it.end = it.end!! + bufferStart
                    it.wordCount = StringUtils.wordCountFormat(wordCount)
                }
            }
        }
        return toc to bookWordCount
    }

    /**
     * 获取合适的目录规则
     */
    private fun getTocRule(content: String): TxtTocRule? {
        val rules = getTocRules() //.reversed() 改动num >= maxNum条件，不需要再反转
        var maxNum = -1
        var mTocRule: TxtTocRule? = null
        for (tocRule in rules) {
            val pattern = try {
                tocRule.rule.toPattern(Pattern.MULTILINE)
            } catch (e: PatternSyntaxException) {
                AppLog.put("TXT目录规则正则语法错误:${tocRule.name}\n$e", e)
                continue
            }
            val matcher = pattern.matcher(content)
            var start = 0
            var csNum = 0
            var numE = 0
            var lastTitle: String? = null
            while (matcher.find()) {
                val contentLength = matcher.start() - start
                if (start == 0 || contentLength > 1000) {
                    val title = replacement(matcher.group(), tocRule.replacement, csNum, lastTitle, contentLength)
                    if (title.isNotEmpty()) {
                        lastTitle = title
                        csNum++
                    }
                    start = matcher.end()
                } else if (contentLength < 100) {
                    numE++ //这种不足100字的一般被识别为卷，即错误识别，正常章节数小于卷3倍的不选
                }
            }
            if (csNum >= numE * 3 && (csNum > maxNum + overRuleCount)) { //后面的规则匹配数量没超过最大值2个，那么依旧用前面那个
                maxNum = csNum
                mTocRule = tocRule
                if (maxNum > 70) {
                    break
                } //能获取60个章节，说明这个规则能基本匹配，并且排在前面，所以不考虑后面的规则
            }
        }
        return mTocRule
    }

    @Keep
    @Suppress("unused")
    class JsExtensions(val lastVolumeTitle: MutableRef<String>, val toc: ArrayList<BookChapter>? = null) {
        fun putVolume(title: String) {
            lastVolumeTitle.value = title
            if (toc != null) {
                val start = toc.lastOrNull()?.end ?: 0
                toc.add(BookChapter(title = title, isVolume = true, start = start, end = start))
            }
        }
    }

    /**
     * 净化标题
     */
    private fun replacement(
        content: String,
        jsStr: String?,
        toc: ArrayList<BookChapter>,
        prevTitle: String? = null,
        prevLength: Int = -1
    ): String {
        if (jsStr.isNullOrBlank()) {
            return content
        }
        return evalJs(content, jsStr, toc.size + 1, prevTitle, prevLength, toc)
    }

    private fun replacement(
        content: String,
        jsStr: String?,
        index: Int,
        prevTitle: String? = null,
        prevLength: Int = -1
    ): String {
        if (jsStr.isNullOrBlank()) {
            return content
        }
        return evalJs(content, jsStr, index + 1, prevTitle, prevLength)
    }

    private fun evalJs(content: String, jsStr: String, index: Int, prevTitle: String?, prevLength: Int = -1, toc: ArrayList<BookChapter>? = null):String {
        return RhinoScriptEngine.run {
            val bindings = ScriptBindings()
            bindings["result"] = content
            bindings["book"] = replaceBook
            bindings["index"] = index
            bindings["prevTitle"] = prevTitle
            bindings["prevLength"] = prevLength
            bindings["lastVolumeTitle"] = lastVolumeTitle.value
            bindings["java"] = JsExtensions(lastVolumeTitle, toc)
            eval(jsStr, bindings)
        }.toString()
    }


            /**
     * 获取启用的目录规则
     */
    private fun getTocRules(): List<TxtTocRule> {
        var rules = appDb.txtTocRuleDao.enabled
        if (appDb.txtTocRuleDao.count == 0) {
            rules = DefaultData.txtTocRules.apply {
                appDb.txtTocRuleDao.insert(*this.toTypedArray())
            }.filter {
                it.enable
            }
        }
        return rules
    }

    private fun getWordCount(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfig.tocCountWords) {
            return
        }
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
            for (bookChapter in list) {
                val wordCount = map[bookChapter.getFileName()]
                if (wordCount != null) {
                    bookChapter.wordCount = wordCount
                }
            }
        }
    }

}

/**
 * 完整跳过 n 字节。InputStream.skip 允许跳过少于请求的字节数，
 * 直接信任其返回值会导致后续读取错位。
 */
private fun InputStream.skipFully(count: Long) {
    var skipped = 0L
    while (skipped < count) {
        val r = skip(count - skipped)
        if (r > 0) {
            skipped += r
        } else if (read() == -1) {
            break
        } else {
            skipped++
        }
    }
}

/**
 * 尽量读满 buffer。InputStream.read 同样允许部分读取，
 * 跨界章节只读一次会静默截断内容。
 */
private fun InputStream.readFully(buffer: ByteArray) {
    var read = 0
    while (read < buffer.size) {
        val r = read(buffer, read, buffer.size - read)
        if (r < 0) break
        read += r
    }
}