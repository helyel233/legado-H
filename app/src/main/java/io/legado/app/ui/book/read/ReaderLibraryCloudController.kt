package io.legado.app.ui.book.read

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookCloudEntryMode
import io.legado.app.help.book.BookCloudEntryModeStore
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.library.LibraryChapterManifestV3
import io.legado.app.help.book.library.LibraryChapterPayloadV3
import io.legado.app.help.book.library.LibraryCloudBackend
import io.legado.app.help.book.library.LibraryCloudChapterVersion
import io.legado.app.help.book.library.LibraryCloudCrypto
import io.legado.app.help.book.library.LibraryCloudKeys
import io.legado.app.help.book.library.LibraryCloudPaths
import io.legado.app.help.book.library.LibraryCloudSession
import io.legado.app.help.book.library.LibraryCloudState
import io.legado.app.help.book.library.LibraryCloudSync
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.text.DateFormat
import java.util.Date

/**
 * Coordinates library cloud session state, chapter version selection, download/delete actions,
 * and debug inspection for [ReadBookActivity].
 */
internal class ReaderLibraryCloudController(
    private val activity: AppCompatActivity,
    private val onStateChanged: (LibraryCloudState) -> Unit,
    private val onOpenChapter: (Int) -> Unit
) {

    private var session: LibraryCloudSession? = null
    var state: LibraryCloudState = LibraryCloudState.DISABLED
        private set

    fun isEnabled(): Boolean {
        val book = ReadBook.book ?: return false
        if (book.isLocal) return false
        return when (BookCloudEntryModeStore.get(book.bookUrl)) {
            BookCloudEntryMode.CACHE_PACKAGE -> false
            BookCloudEntryMode.LIBRARY_CHAPTER ->
                LibraryContainerManager.readContainer() != null
        }
    }

    fun onMenuViewUpdated() {
        if (BookCloudEntryModeStore.get(ReadBook.book?.bookUrl.orEmpty()) == BookCloudEntryMode.LIBRARY_CHAPTER) {
            refreshSession(refresh = false, silent = true)
        } else {
            resetState()
        }
    }

    fun onLibraryContainerChanged() {
        val bookUrl = ReadBook.book?.bookUrl.orEmpty()
        if (BookCloudEntryModeStore.get(bookUrl) == BookCloudEntryMode.LIBRARY_CHAPTER) {
            session = null
            refreshSession(refresh = true, silent = true)
        } else {
            resetState()
        }
    }

    private fun resetState() {
        session = null
        state = LibraryCloudState.DISABLED
        onStateChanged(state)
    }

    fun showChapters(refresh: Boolean) {
        val book = ReadBook.book ?: return
        if (BookCloudEntryModeStore.get(book.bookUrl) == BookCloudEntryMode.CACHE_PACKAGE) {
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        activity.lifecycleScope.launch {
            val loadedSession = withContext(IO) {
                if (refresh) {
                    LibraryCloudSync.refreshSession(book)
                } else {
                    currentSession() ?: LibraryCloudSync.openSession(book)
                }
            }
            session = loadedSession
            state = loadedSession.state
            onStateChanged(loadedSession.state)
            if (loadedSession.state != LibraryCloudState.READY) {
                activity.toastOnUi(stateMessage(loadedSession))
                return@launch
            }
            val currentChapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            } ?: run {
                activity.toastOnUi(appCtx.getString(R.string.ui_book_current_chapter_not_found))
                return@launch
            }
            val versions = withContext(IO) {
                loadedSession.listChapterVersions(currentChapter)
            }
            if (versions.isEmpty()) {
                activity.toastOnUi(appCtx.getString(R.string.ui_book_no_cloud_cache_matches_current))
                return@launch
            }
            showChapterDialog(book, loadedSession, currentChapter, versions)
        }
    }

    fun showDebug() {
        if (!BuildConfig.DEBUG) return
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        activity.lifecycleScope.launch {
            val chapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            }
            val container = LibraryContainerManager.readContainer()
            val v3Debug = if (chapter != null && container != null) {
                withContext(IO) {
                    val v3SharedBookKey = LibraryCloudKeys.sharedBookKey(book)
                    val v3ExactBookKey = LibraryCloudKeys.bookKey(book)
                    val v3ChapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
                    val currentPaths = listOf(
                        LibraryCloudPaths.v3CurrentPath(v3SharedBookKey, v3ChapterKey),
                        LibraryCloudPaths.v3CurrentPath(v3ExactBookKey, v3ChapterKey)
                    ).distinct()
                    val manifestPaths = listOf(
                        LibraryCloudPaths.v3ManifestPath(v3ExactBookKey, v3ChapterKey),
                        LibraryCloudPaths.v3ManifestPath(v3SharedBookKey, v3ChapterKey)
                    ).distinct()
                    val backend = LibraryCloudBackend(container)
                    suspend fun currentState(path: String): String {
                        return runCatching {
                            val bytes = backend.downloadOrNull(path)
                                ?: return@runCatching "$path = missing"
                            val json = LibraryCloudCrypto.decodeString(bytes, container.password)
                            val payload = GSON.fromJsonObject<LibraryChapterPayloadV3>(json).getOrThrow()
                            "$path = exists source=${payload.sourceName.ifBlank { payload.sourceUrl }} hash=${payload.contentHash}"
                        }.getOrElse {
                            "$path = error ${it.localizedMessage}"
                        }
                    }
                    suspend fun manifestState(path: String): String {
                        return runCatching {
                            val bytes = backend.downloadOrNull(path)
                                ?: return@runCatching "$path = missing"
                            val json = LibraryCloudCrypto.decodeString(bytes, container.password)
                            val manifest = GSON.fromJsonObject<LibraryChapterManifestV3>(json).getOrThrow()
                            "$path = exists variants=${manifest.variants.size}"
                        }.getOrElse {
                            "$path = error ${it.localizedMessage}"
                        }
                    }
                    runCatching {
                        val currentLines = currentPaths.map { currentState(it) }
                        val manifestLines = manifestPaths.map { manifestState(it) }
                        buildString {
                            appendLine("v3CurrentState:")
                            currentLines.forEach { appendLine(it) }
                            appendLine("v3ManifestState:")
                            manifestLines.forEach { appendLine(it) }
                        }.trimEnd()
                    }.getOrElse {
                        "v3State=error ${it.localizedMessage}"
                    }
                }
            } else {
                "v3State=not_checked"
            }
            val message = buildString {
                appendLine("book.name=${book.name}")
                appendLine("book.author=${book.getRealAuthor()}")
                appendLine("book.bookUrl=${book.bookUrl}")
                appendLine("book.origin=${book.origin}")
                appendLine("book.originName=${book.originName}")
                appendLine()
                appendLine("exactBookKey=${LibraryCloudKeys.bookKey(book)}")
                appendLine("sharedBookKey=${LibraryCloudKeys.sharedBookKey(book)}")
                appendLine("allBookKeys=${LibraryCloudKeys.bookKeys(book).joinToString()}")
                appendLine("normalizedBookName=${LibraryCloudKeys.normalizeBookName(book.name)}")
                appendLine()
                appendLine("container=${LibraryContainerManager.displayLabel(container)}")
                appendLine("containerId=${container?.id.orEmpty()}")
                appendLine("containerPrefix=${container?.container?.prefix.orEmpty()}")
                appendLine()
                if (chapter == null) {
                    appendLine("chapter=null")
                } else {
                    appendLine("chapter.index=${chapter.index}")
                    appendLine("chapter.title=${chapter.title}")
                    appendLine("chapter.url=${chapter.url}")
                    appendLine("chapter.identity=${chapter.contentCacheIdentity()}")
                    appendLine("canonicalTitle=${LibraryCloudKeys.canonicalTextForKey(chapter.title)}")
                    appendLine("titleCodePoints=${LibraryCloudKeys.debugCodePoints(chapter.title)}")
                    appendLine("chapterKey=${LibraryCloudKeys.chapterKey(chapter)}")
                    appendLine("titleKey=${LibraryCloudKeys.titleKey(chapter)}")
                    appendLine("relaxedTitle=${LibraryCloudKeys.relaxedTitle(chapter.title)}")
                    appendLine("relaxedTitleKey=${LibraryCloudKeys.relaxedTitleKey(chapter)}")
                    appendLine("ordinalTitle=${LibraryCloudKeys.chapterOrdinal(chapter.title).orEmpty()}")
                    appendLine("ordinalTitleKey=${LibraryCloudKeys.ordinalTitleKey(chapter).orEmpty()}")
                    appendLine("matchKeys=${LibraryCloudKeys.matchKeys(chapter).joinToString { "${it.kind}:${it.key}" }}")
                    appendLine("sourceKey=${LibraryCloudKeys.sourceKey(book.origin)}")
                    appendLine()
                    val v3SharedBookKey = LibraryCloudKeys.sharedBookKey(book)
                    val v3ExactBookKey = LibraryCloudKeys.bookKey(book)
                    val v3ChapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
                    val sourceKey = LibraryCloudKeys.sourceKey(book.origin)
                    appendLine("v3SharedBookKey=$v3SharedBookKey")
                    appendLine("v3ExactBookKey=$v3ExactBookKey")
                    appendLine("v3ChapterKey=$v3ChapterKey")
                    appendLine("v3SharedCurrentPath=${LibraryCloudPaths.v3CurrentPath(v3SharedBookKey, v3ChapterKey)}")
                    appendLine("v3ExactCurrentPath=${LibraryCloudPaths.v3CurrentPath(v3ExactBookKey, v3ChapterKey)}")
                    appendLine("v3LegacyManifestPath=${LibraryCloudPaths.v3ManifestPath(v3ExactBookKey, v3ChapterKey)}")
                    appendLine("v3LegacyPayloadPath=${LibraryCloudPaths.v3PayloadPath(v3ExactBookKey, v3ChapterKey, sourceKey)}")
                    appendLine(v3Debug)
                    appendLine("v3RequestEstimate.uploadNew=1B+1A")
                    appendLine("v3RequestEstimate.uploadDuplicate=1B")
                    appendLine("v3RequestEstimate.readHit=1B")
                    appendLine("v3RequestEstimate.readLegacyFallback<=4B")
                    appendLine("v3RequestEstimate.listVersions<=4B")
                    appendLine()
                    appendLine("listPrefixes:")
                    LibraryCloudKeys.bookKeys(book).forEach { bookKey ->
                        LibraryCloudKeys.matchKeys(chapter).forEach { matchKey ->
                            appendLine(LibraryCloudPaths.variantsPrefix(bookKey, matchKey))
                        }
                    }
                    appendLine()
                    appendLine("currentPaths:")
                    LibraryCloudKeys.bookKeys(book).forEach { bookKey ->
                        LibraryCloudKeys.matchKeys(chapter).forEach { matchKey ->
                            appendLine(LibraryCloudPaths.currentChapterPath(bookKey, matchKey))
                        }
                    }
                }
            }
            val debugTextView = TextView(activity).apply {
                text = message
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
            }
            val debugScrollView = ScrollView(activity).apply {
                addView(
                    debugTextView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            AlertDialog.Builder(activity)
                .setTitle(appCtx.getString(R.string.ui_book_library_debug))
                .setView(debugScrollView)
                .setNegativeButton(appCtx.getString(R.string.ra_cfg_copy)) { _, _ ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(appCtx.getString(R.string.ui_book_library_debug), message))
                    activity.toastOnUi(appCtx.getString(R.string.review_error_copied))
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun refreshSession(refresh: Boolean, silent: Boolean) {
        val book = ReadBook.book
        if (book == null || !isEnabled()) {
            resetState()
            return
        }
        activity.lifecycleScope.launch {
            val loadedSession = withContext(IO) {
                if (refresh) {
                    LibraryCloudSync.refreshSession(book)
                } else {
                    currentSession() ?: LibraryCloudSync.openSession(book)
                }
            }
            if (ReadBook.book?.bookUrl != book.bookUrl) return@launch
            session = loadedSession
            state = loadedSession.state
            onStateChanged(loadedSession.state)
            if (!silent && loadedSession.state != LibraryCloudState.READY) {
                activity.toastOnUi(stateMessage(loadedSession))
            }
        }
    }

    private fun currentSession(): LibraryCloudSession? {
        val currentContainerId = LibraryContainerManager.readContainer()?.id
        return session?.takeIf { it.config?.id == currentContainerId }
    }

    private fun downloadChapter(
        book: Book,
        session: LibraryCloudSession,
        localChapter: BookChapter,
        item: LibraryCloudChapterVersion
    ) {
        activity.lifecycleScope.launch {
            ReadBook.upMsg(appCtx.getString(R.string.ui_book_read_cloud_chapter))
            val result = withContext(IO) {
                runCatching {
                    val content = session.downloadChapter(item)
                        ?: throw NoStackTraceException("云端章节内容不存在")
                    BookHelp.saveText(book, localChapter, content)
                    localChapter
                }
            }
            ReadBook.upMsg(null)
            result.onSuccess { chapter ->
                if (ReadBook.book?.bookUrl != book.bookUrl) return@onSuccess
                LibraryCloudSync.setCloudReadingActive(book, true)
                state = LibraryCloudState.READY
                onStateChanged(state)
                onOpenChapter(chapter.index)
            }.onFailure {
                activity.toastOnUi(appCtx.getString(R.string.ui_book_failed_read_cloud_chapter_n, it.localizedMessage))
            }
        }
    }

    private fun showChapterDialog(
        book: Book,
        session: LibraryCloudSession,
        currentChapter: BookChapter,
        items: List<LibraryCloudChapterVersion>
    ) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        }
        val summaryView = TextView(activity).apply {
            text = appCtx.getString(R.string.ui_book_current_chapter, currentChapter.title)
            textSize = 13f
            setTextColor(activity.secondaryTextColor)
            setPadding(0, 0, 0, 8.dpToPx())
            applyUiBodyTypefaceDeep(activity.uiTypeface())
        }
        val listLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        var dialog: AlertDialog? = null
        items.groupBy { sourceGroupKey(it) }.forEach { (_, sourceItems) ->
            val sourceLabel = sourceLabel(sourceItems.first())
            listLayout.addView(TextView(activity).apply {
                text = sourceLabel
                textSize = 14f
                setTextColor(activity.secondaryTextColor)
                setPadding(2.dpToPx(), 12.dpToPx(), 2.dpToPx(), 6.dpToPx())
                applyUiTitleTypeface(activity)
            })
            sourceItems.forEach { item ->
                listLayout.addView(createChapterRow(book, session, currentChapter, item) {
                    dialog?.dismiss()
                })
            }
        }
        val scrollView = NestedScrollView(activity).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                listLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        }
        content.addView(summaryView)
        content.addView(scrollView)
        dialog = AndroidAlertBuilder(activity).apply {
            setTitle(appCtx.getString(R.string.ui_book_select_library_chapter))
            setCustomView(content)
            negativeButton(android.R.string.cancel)
        }.show()
    }

    private fun createChapterRow(
        book: Book,
        session: LibraryCloudSession,
        currentChapter: BookChapter,
        item: LibraryCloudChapterVersion,
        dismissParent: () -> Unit
    ): View {
        val cardColor = activity.themeCardColorOrDefault()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            background = UiCorner.opaqueRounded(
                cardColor,
                UiCorner.panelRadius(activity)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dpToPx()
            }
            addView(TextView(activity).apply {
                text = chapterTitle(item)
                setTextColor(activity.primaryTextColor)
                textSize = 15f
                applyUiTitleTypeface(activity)
            })
            chapterTime(item)?.let { time ->
                addView(TextView(activity).apply {
                    text = time
                    textSize = 13f
                    setTextColor(activity.secondaryTextColor)
                    setPadding(0, 4.dpToPx(), 0, 8.dpToPx())
                    applyUiBodyTypefaceDeep(activity.uiTypeface())
                })
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(actionText(appCtx.getString(R.string.ui_book_read), activity.accentColor) {
                    dismissParent()
                    downloadChapter(book, session, currentChapter, item)
                })
                addView(actionText(appCtx.getString(R.string.delete), 0xFFD32F2F.toInt()) {
                    confirmDeleteChapter(book, session, currentChapter, item, dismissParent)
                })
            })
            setOnClickListener {
                dismissParent()
                downloadChapter(book, session, currentChapter, item)
            }
        }
    }

    private fun actionText(
        text: String,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 14f
            setPadding(0, 4.dpToPx(), 14.dpToPx(), 4.dpToPx())
            applyUiBodyTypefaceDeep(activity.uiTypeface())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun confirmDeleteChapter(
        book: Book,
        session: LibraryCloudSession,
        currentChapter: BookChapter,
        item: LibraryCloudChapterVersion,
        dismissParent: () -> Unit
    ) {
        val input = EditText(activity).apply {
            hint = appCtx.getString(R.string.lib_cloud_delete_hint, appCtx.getString(R.string.delete))
            setSingleLine(true)
            typeface = activity.uiTypeface()
            setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 8.dpToPx())
        }
        val confirmDialog = AndroidAlertBuilder(activity).apply {
            setTitle(appCtx.getString(R.string.ui_book_delete_cloud_chapter))
            setMessage(
                appCtx.getString(
                    R.string.lib_cloud_delete_message,
                    item.payload.title.ifBlank { currentChapter.title },
                    appCtx.getString(R.string.delete)
                )
            )
            setCustomView(input)
            negativeButton(android.R.string.cancel)
            positiveButton(appCtx.getString(R.string.delete))
        }.show()
        confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text?.toString()?.trim() != appCtx.getString(R.string.delete)) {
                activity.toastOnUi(appCtx.getString(R.string.lib_cloud_delete_retry, appCtx.getString(R.string.delete)))
                return@setOnClickListener
            }
            confirmDialog.dismiss()
            dismissParent()
            deleteChapter(book, session, item)
        }
    }

    private fun deleteChapter(
        book: Book,
        session: LibraryCloudSession,
        item: LibraryCloudChapterVersion
    ) {
        activity.lifecycleScope.launch {
            ReadBook.upMsg(appCtx.getString(R.string.ui_book_delete_cloud_chapter))
            val result = withContext(IO) {
                runCatching {
                    if (!session.deleteChapter(item)) {
                        throw NoStackTraceException("云端章节不存在或删除失败")
                    }
                    LibraryCloudSync.refreshSession(book)
                }
            }
            ReadBook.upMsg(null)
            val refreshedSession = result.getOrNull()
            if (refreshedSession != null) {
                if (ReadBook.book?.bookUrl != book.bookUrl) return@launch
                this@ReaderLibraryCloudController.session = refreshedSession
                state = refreshedSession.state
                onStateChanged(refreshedSession.state)
                activity.toastOnUi(appCtx.getString(R.string.ui_book_cloud_chapter_deleted))
                showChapters(refresh = false)
            } else {
                activity.toastOnUi(
                    appCtx.getString(
                        R.string.ui_book_failed_delete_cloud_chapter_n,
                        result.exceptionOrNull()?.localizedMessage.orEmpty()
                    )
                )
            }
        }
    }

    private fun sourceGroupKey(item: LibraryCloudChapterVersion): String {
        val payload = item.payload
        return listOf(payload.sourceUrl, payload.sourceBookUrl, payload.sourceName).joinToString("\u001F")
    }

    private fun sourceLabel(item: LibraryCloudChapterVersion): String {
        return item.payload.sourceName
            .ifBlank { item.payload.sourceUrl }
            .ifBlank { appCtx.getString(R.string.ui_book_old_cache_unknown_source) }
    }

    private fun chapterTitle(item: LibraryCloudChapterVersion): String {
        val payload = item.payload
        val title = payload.title.ifBlank { payload.normalizedTitle.ifBlank { payload.chapterKey } }
        val prefix = if (payload.chapterIndex >= 0) "${payload.chapterIndex + 1}. " else ""
        return "$prefix$title"
    }

    private fun chapterTime(item: LibraryCloudChapterVersion): String? {
        return item.payload.updatedAt.takeIf { it > 0L }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
    }

    private fun stateMessage(session: LibraryCloudSession): String {
        return when (session.state) {
            LibraryCloudState.DISABLED -> appCtx.getString(R.string.ui_book_no_library_container_configured)
            LibraryCloudState.READY -> appCtx.getString(R.string.ui_book_cloud_library_available)
            LibraryCloudState.ERROR -> session.errorMessage?.let { appCtx.getString(R.string.ui_book_failed_read_cloud_library_n, it) }
                ?: appCtx.getString(R.string.ui_book_failed_read_cloud_library)
        }
    }
}
