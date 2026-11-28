package io.legado.app.ui.book.read

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.book.read.epub.EpubReadView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.glass.BackdropCropGravity
import io.legado.app.ui.glass.computeBackdropTranslateY
import io.legado.app.utils.BookIntroUtils
import io.legado.app.utils.share
import io.legado.app.utils.stackBlurSoftware
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Coordinates reader backdrop snapshot capture and share-note preview/payload assembly
 * for [ReadBookActivity].
 */
internal class ReaderSelectionShareCoordinator(
    private val activity: ReadBookActivity,
    private val readViewProvider: () -> ReadView,
    private val epubReadViewProvider: () -> EpubReadView,
    private val rootViewProvider: () -> ViewGroup,
    private val isEpubCoreActive: () -> Boolean,
    private val epubCoreChapterTitle: () -> String?
) {

    private var shareNotePreviewOverlay: ShareNotePreviewOverlay? = null

    fun dismissPreview() {
        shareNotePreviewOverlay?.dismiss()
        shareNotePreviewOverlay = null
    }

    fun captureContentBackdrop(
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Int,
        gravity: BackdropCropGravity = BackdropCropGravity.BOTTOM
    ): Bitmap? {
        if (isEpubCoreActive()) return null
        if (blurRadius <= 0) return null
        if (targetWidth <= 0 || targetHeight <= 0) return null
        val source: View = readViewProvider()
        if (source.width <= 0 || source.height <= 0) return null
        val raw = runCatching {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val scale = targetWidth.toFloat() / source.width
        val sampledHeight = targetHeight / scale
        var captureError: Throwable? = null
        val drawn = runCatching {
            val canvas = Canvas(raw)
            canvas.scale(scale, scale)
            canvas.translate(0f, computeBackdropTranslateY(gravity, sampledHeight, source.height))
            source.draw(canvas)
        }.onFailure { captureError = it }.isSuccess
        if (!drawn) {
            raw.recycle()
            AppLog.put(
                "readerPanelBackdrop: 捕获阅读正文失败，面板回退实底",
                captureError
            )
            return null
        }
        val blurred = runCatching { raw.stackBlurSoftware(blurRadius) }.getOrNull()
        if (blurred == null || blurred === raw) {
            raw.recycle()
            return null
        }
        raw.recycle()
        return blurred
    }

    fun captureWindowBackdrop(
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Int,
        gravity: BackdropCropGravity = BackdropCropGravity.BOTTOM
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (blurRadius <= 0 || targetWidth <= 0 || targetHeight <= 0) return null
        val window = activity.window
        val decor = window.decorView
        val sourceWidth = decor.width
        val sourceHeight = decor.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val raw = runCatching {
            Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val cropped = runCatching {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: run {
            raw.recycle()
            return null
        }

        val worker = HandlerThread("reader-window-backdrop").apply { start() }
        val latch = CountDownLatch(1)
        var copyStatus = PixelCopy.ERROR_UNKNOWN
        var result: Bitmap? = null
        try {
            PixelCopy.request(
                window,
                Rect(0, 0, sourceWidth, sourceHeight),
                raw,
                { status ->
                    copyStatus = status
                    latch.countDown()
                },
                Handler(worker.looper)
            )
            if (!latch.await(50, TimeUnit.MILLISECONDS) ||
                copyStatus != PixelCopy.SUCCESS
            ) {
                AppLog.put("readerPanelBackdrop: PixelCopy 失败(status=$copyStatus)，面板回退实底")
                return null
            }
            val scale = targetWidth.toFloat() / sourceWidth
            val sampledHeight = targetHeight / scale
            val canvas = Canvas(cropped)
            canvas.scale(scale, scale)
            canvas.translate(0f, computeBackdropTranslateY(gravity, sampledHeight, sourceHeight))
            canvas.drawBitmap(raw, 0f, 0f, null)
            val blurred = runCatching { cropped.stackBlurSoftware(blurRadius) }.getOrNull()
            if (blurred == null || blurred === cropped) return null
            result = blurred
            return blurred
        } catch (t: Throwable) {
            AppLog.put("readerPanelBackdrop: PixelCopy 兜底异常，面板回退实底", t)
            return null
        } finally {
            worker.quitSafely()
            raw.recycle()
            if (result !== cropped) cropped.recycle()
        }
    }

    fun showShareNotePreviewOverlay(selection: String) {
        val text = selection.trim()
        if (text.isBlank()) return
        val payload = buildShareNotePayload(text)
        activity.lifecycleScope.launch {
            val entries = runCatching {
                withContext(IO) {
                    ShareNoteTemplateManager.loadEntries()
                }
            }.getOrElse {
                AppLog.put("摘录分享模板加载失败\n${it.localizedMessage}", it, true)
                activity.toastOnUi(it.localizedMessage ?: activity.getString(R.string.error))
                return@launch
            }
            val entry = entries.firstOrNull { it.dirName == ShareNoteTemplateManager.lastDirName() }
                ?: entries.firstOrNull()
            if (entry == null) {
                activity.toastOnUi(R.string.share_note_no_template)
                return@launch
            }
            shareNotePreviewOverlay?.dismiss()
            shareNotePreviewOverlay = ShareNotePreviewOverlay.show(
                activity = activity,
                parent = rootViewProvider(),
                entry = entry,
                payload = payload
            )
        }
    }

    fun currentChapterSize(): Int {
        return ReadBook.simulatedChapterSize.takeIf { it > 0 }
            ?: ReadBook.chapterSize.takeIf { it > 0 }
            ?: ReadBook.book?.simulatedTotalChapterNum()?.takeIf { it > 0 }
            ?: ReadBook.book?.totalChapterNum?.takeIf { it > 0 }
            ?: 0
    }

    internal fun shareSelectionAsNoteImage(
        text: String,
        entry: ShareNoteTemplateManager.Entry
    ) {
        val payload = buildShareNotePayload(text)
        activity.lifecycleScope.launch {
            activity.toastOnUi(appCtx.getString(R.string.ui_book_generating_share_image))
            kotlin.runCatching {
                ShareNoteImageRenderer.renderShareImage(activity, entry, payload)
            }.onSuccess { file ->
                kotlin.runCatching {
                    activity.share(file, "image/png")
                }.onFailure {
                    activity.toastOnUi(it.localizedMessage ?: activity.getString(R.string.error))
                }
            }.onFailure {
                AppLog.put("摘录分享图片生成失败\n${it.localizedMessage}", it, true)
                activity.toastOnUi(it.localizedMessage ?: activity.getString(R.string.error))
            }
        }
    }

    private fun buildShareNotePayload(text: String): ShareNoteImageRenderer.Payload {
        val book = ReadBook.book
        val now = Date()
        val goalConfig = ReadRecordWidgetStore.loadGoalConfig()
        val readTimeMs = book?.let { currentShareNoteReadTime(it, now.time) } ?: 0L
        val readTimeText = readTimeMs.takeIf { it > 0L }?.let(::formatShareNoteDuration).orEmpty()
        val progressText = currentShareNoteProgressText()
        val tags = book?.shareNoteTags().orEmpty()
        return ShareNoteImageRenderer.Payload(
            generatedAt = DateFormat.getDateTimeInstance().format(now),
            profile = ShareNoteImageRenderer.Profile(
                name = goalConfig.userName.orEmpty().ifBlank { appCtx.getString(R.string.ui_book_reader) },
                avatar = goalConfig.avatar
            ),
            book = ShareNoteImageRenderer.Book(
                title = book?.name.orEmpty().ifBlank { activity.getString(R.string.book_name) },
                author = book?.getRealAuthor().orEmpty(),
                description = BookIntroUtils.listIntro(book?.getDisplayIntro()).orEmpty(),
                cover = book?.getDisplayCover()?.let(::normalizeShareNoteCoverUrl),
                type = tags,
                kind = tags,
                tags = tags,
                wordCountText = book?.wordCount.orEmpty(),
                readTimeText = readTimeText,
                readStatusText = readTimeText.takeIf { it.isNotBlank() }?.let { appCtx.getString(R.string.ui_book_reading, it) }.orEmpty(),
                readProgressText = progressText,
                readProgressPercent = parseShareNoteProgressPercent(progressText),
                lastReadTime = book?.durChapterTime?.takeIf { it > 0L }?.let {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                }.orEmpty()
            ),
            note = ShareNoteImageRenderer.Note(
                createAt = DateFormat.getDateInstance().format(now),
                sectionName = currentShareNoteChapterTitle(),
                description = text
            )
        )
    }

    private fun currentShareNoteReadTime(book: Book, now: Long): Long {
        val saved = appDb.readRecordDao.getReadTime(book.name) ?: 0L
        val live = if (AppConfig.enableReadRecord && ReadBook.book?.bookUrl == book.bookUrl) {
            (now - ReadBook.readStartTime).coerceAtLeast(0L)
        } else {
            0L
        }
        return saved + live
    }

    private fun formatShareNoteDuration(mss: Long): String {
        val days = mss / (1000 * 60 * 60 * 24)
        val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
        val seconds = mss % (1000 * 60) / 1000
        val d = if (days > 0) activity.getString(R.string.duration_day, days) else ""
        val h = if (hours > 0) activity.getString(R.string.duration_hour, hours) else ""
        val m = if (minutes > 0) activity.getString(R.string.duration_minute, minutes) else ""
        val s = if (seconds > 0 && days == 0L && hours == 0L) {
            activity.getString(R.string.duration_second, seconds)
        } else {
            ""
        }
        return "$d$h$m$s".ifBlank { activity.getString(R.string.duration_zero) }
    }

    private fun currentShareNoteProgressText(): String {
        return runCatching {
            if (isEpubCoreActive()) {
                val epubView = epubReadViewProvider()
                val page = epubView.currentPage() ?: return@runCatching ""
                val chapterSize = currentChapterSize()
                val chapterPageIndex = epubView.currentChapterPageIndex()
                val chapterPageCount = epubView.currentChapterPageCount()
                shareNoteProgressText(
                    chapterIndex = page.chapterIndex,
                    chapterSize = chapterSize,
                    pageIndex = chapterPageIndex,
                    pageSize = chapterPageCount
                )
            } else {
                currentTextShareNoteProgressText()
            }
        }.getOrDefault("")
    }

    private fun currentTextShareNoteProgressText(): String {
        val chapterSize = currentChapterSize()
        if (chapterSize <= 0) return ""
        val textChapter = ReadBook.curTextChapter
        val currentPage = runCatching { readViewProvider().curPage.textPage }.getOrNull()
        val chapterIndex = firstValidChapterIndex(
            chapterSize,
            textChapter?.chapter?.index,
            ReadBook.durChapterIndex,
            ReadBook.book?.durChapterIndex,
            currentPage?.takeIf { it.chapterSize > 0 }?.chapterIndex
        )
        val pageSize = textChapter?.pageSize?.takeIf { it > 0 }
            ?: currentPage?.pageSize?.takeIf { it > 0 }
            ?: 0
        val pageIndex = textChapter
            ?.getPageIndexByCharIndex(ReadBook.durChapterPos)
            ?.takeIf { it >= 0 }
            ?: currentPage?.index?.takeIf { it >= 0 }
            ?: 0
        return shareNoteProgressText(chapterIndex, chapterSize, pageIndex, pageSize)
    }

    private fun firstValidChapterIndex(chapterSize: Int, vararg candidates: Int?): Int {
        return candidates.firstOrNull { it != null && it in 0 until chapterSize } ?: 0
    }

    private fun parseShareNoteProgressPercent(progressText: String): Float? {
        val valueText = shareNoteProgressPercentRegex.find(progressText)
            ?.groupValues
            ?.getOrNull(1)
            ?: progressText.trim()
        return valueText.replace(',', '.')
            .toFloatOrNull()
            ?.div(100f)
            ?.coerceIn(0f, 1f)
    }

    private fun shareNoteProgressText(
        chapterIndex: Int,
        chapterSize: Int,
        pageIndex: Int,
        pageSize: Int
    ): String {
        if (chapterSize <= 0) return ""
        val safeChapterIndex = chapterIndex.coerceIn(0, chapterSize - 1)
        val safePageSize = pageSize.coerceAtLeast(0)
        val safePageIndex = if (safePageSize > 0) {
            pageIndex.coerceIn(0, safePageSize - 1)
        } else {
            0
        }
        val progress = if (safePageSize <= 0) {
            (safeChapterIndex + 1.0) / chapterSize.toDouble()
        } else {
            safeChapterIndex.toDouble() / chapterSize.toDouble() +
                (safePageIndex + 1.0) / safePageSize.toDouble() / chapterSize.toDouble()
        }.coerceIn(0.0, 1.0)
        return java.text.DecimalFormat("0.0%").format(progress)
    }

    private fun Book.shareNoteTags(): String {
        return customTag?.trim()?.takeIf { it.isNotBlank() }
            ?: kind?.trim()?.takeIf { it.isNotBlank() }
            ?: when {
                isAudio -> appCtx.getString(R.string.audio)
                isEpub -> "EPUB"
                isMobi -> "MOBI"
                isLocalTxt -> "TXT"
                isLocal -> activity.getString(R.string.local)
                else -> ""
            }
    }

    private fun currentShareNoteChapterTitle(): String {
        if (isEpubCoreActive()) {
            epubCoreChapterTitle()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ReadBook.curTextChapter?.chapter?.title
            ?: ReadBook.book?.durChapterTitle
            ?: ""
    }

    private fun normalizeShareNoteCoverUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("http", ignoreCase = true) ||
            value.startsWith("file:", ignoreCase = true) ||
            value.startsWith("content:", ignoreCase = true) ||
            value.startsWith("data:", ignoreCase = true)
        ) {
            return value
        }
        return runCatching {
            File(value).takeIf { it.exists() }?.toURI()?.toString()
        }.getOrNull() ?: value
    }

    companion object {
        private val shareNoteProgressPercentRegex = Regex("""(\d+(?:[,.]\d+)?)\s*%""")
    }
}
