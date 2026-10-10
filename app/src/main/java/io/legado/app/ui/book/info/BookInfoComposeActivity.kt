package io.legado.app.ui.book.info

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.help.export.BookmarkMarkdownExporter
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.longToastOnUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.book.BookCloudEntryMode
import io.legado.app.help.book.BookCloudEntryModeStore
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.SourceCallBack
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.compose.BookInfoActions
import io.legado.app.ui.book.info.compose.BookInfoComposeRoute
import io.legado.app.ui.book.info.compose.BookInfoUiState
import io.legado.app.ui.autoTask.showBookAutoTaskDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.ai.AiImageGalleryActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookInfoComposeActivity :
    VMBaseActivity<ViewBinding, BookInfoViewModel>(
        fullScreen = true,
        imageBg = false,
        showOpenMenuIcon = false
    ),
    GroupSelectDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeCoverDialog.CallBack,
    VariableDialog.Callback {

    private lateinit var composeView: ComposeView
    private lateinit var refreshLayout: SwipeRefreshLayout
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        refreshLayout = SwipeRefreshLayout(this).apply {
            addView(
                composeView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        object : ViewBinding {
            override fun getRoot(): View = refreshLayout
        }
    }
    override val viewModel by viewModels<BookInfoViewModel>()

    private val waitDialog by lazy { WaitDialog(this) }
    private var chapterChanged = false
    private var pendingTocJump = false
    private var uiState by mutableStateOf(BookInfoUiState(loading = true))
    private var readTimeText = ""
    private var groupText = ""
    private var aiImageCount = 0
    private var aiImagePaths: List<String> = emptyList()
    private var lastStableIntroBookUrl: String? = null
    private var lastStableIntro = ""
    private val chapterPreviewCache = BookInfoChapterPreviewCache()
    private val galleryLoader by lazy { BookInfoGalleryLoader(lifecycleScope) }
    private var readTimeJob: Job? = null

    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }

    private val exportNotesResult = registerForActivityResult(HandleFileContract()) {}

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let { result ->
            viewModel.getBook(false)?.let { book ->
                lifecycleScope.launch {
                    withContext(IO) {
                        book.durChapterIndex = result[0] as Int
                        book.durChapterPos = result[1] as Int
                        chapterChanged = result[2] as Boolean
                        book.durVolumeIndex = result[3] as Int
                        book.chapterInVolumeIndex = result[4] as Int
                        appDb.bookDao.update(book)
                    }
                    startReadActivity(book)
                }
            }
        } ?: run {
            if (!viewModel.inBookshelf) {
                viewModel.delBook()
            }
        }
    }

    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.upBook(intent)
        when (it.resultCode) {
            Activity.RESULT_OK -> {
                viewModel.inBookshelf = true
                updateUiState()
            }

            RESULT_DELETED -> {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            viewModel.upEditBook()
            updateUiState()
        }
    }

    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode == Activity.RESULT_CANCELED) return@registerForActivityResult
        viewModel.getBook(false)?.let { book ->
            viewModel.bookSource = appDb.bookSourceDao.getBookSource(book.origin)?.also { source ->
                viewModel.hasCustomBtn = source.customButton
            }
            viewModel.refreshBook(book)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        refreshLayout.setOnRefreshListener {
            refreshBook()
        }
        uiState = buildInitialUiStateFromIntent()
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            val actions = remember { composeActions() }
            BookInfoComposeRoute(
                state = uiState,
                actions = actions
            )
        }
        viewModel.bookData.observe(this) { book ->
            showBook(book)
        }
        viewModel.chapterListData.observe(this) {
            chapterPreviewCache.invalidate()
            updateUiState()
            maybeOpenPendingTocJump()
        }
        viewModel.tocLoadStateData.observe(this) {
            updateUiState()
            maybeOpenPendingTocJump()
        }
        viewModel.waitDialogData.observe(this) {
            upWaitDialogStatus(it)
        }
        viewModel.initData(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.getBook(false)?.let { updateAiImages(it, forceRefresh = true) }
    }

    override fun onStop() {
        // 离开页面后不再兑现「查看目录」的等待跳转，避免返回详情页时弹跳
        pendingTocJump = false
        super.onStop()
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            if (it == "selectBooksDir") {
                localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshBook()
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToc()
            }
        }
    }

    private fun composeActions(): BookInfoActions {
        return BookInfoActions(
            onBack = ::finish,
            onRefresh = ::refreshBook,
            onRefreshToc = ::refreshToc,
            onRead = {
                viewModel.getBook()?.let { book ->
                    if (book.isWebFile) {
                        showWebFileDownloadAlert { readBook(it) }
                    } else {
                        readBook(book)
                    }
                }
            },
            onShelf = {
                viewModel.getBook()?.let { book ->
                    if (viewModel.inBookshelf) {
                        deleteBook()
                    } else if (book.isWebFile) {
                        showWebFileDownloadAlert()
                    } else {
                        viewModel.addToBookshelf {
                            updateUiState()
                        }
                    }
                }
            },
            onChangeCover = {
                viewModel.getBook()?.let {
                    showDialogFragment(ChangeCoverDialog(it.name, it.author))
                }
            },
            onPreviewCover = {
                viewModel.getBook()?.getDisplayCover()?.let {
                    showDialogFragment(PhotoDialog(it, isBook = true))
                }
            },
            onAuthorClick = {
                viewModel.getBook(false)?.let { book ->
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.CLICK_AUTHOR,
                        viewModel.bookSource,
                        book,
                        null,
                        result = book.author
                    ) {
                        SearchActivity.start(this, book.author)
                    }
                }
            },
            onAuthorLongClick = {
                viewModel.getBook(false)?.let { book ->
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.LONG_CLICK_AUTHOR,
                        viewModel.bookSource,
                        book,
                        null,
                        result = book.author
                    )
                }
            },
            onNameClick = {
                viewModel.getBook(false)?.let { book ->
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.CLICK_BOOK_NAME,
                        viewModel.bookSource,
                        book,
                        null,
                        result = book.name
                    ) {
                        SearchActivity.start(this, book.name)
                    }
                }
            },
            onNameLongClick = {
                viewModel.getBook(false)?.let { book ->
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.LONG_CLICK_BOOK_NAME,
                        viewModel.bookSource,
                        book,
                        null,
                        result = book.name
                    )
                }
            },
            onEditBookInfo = {
                viewModel.getBook()?.let { book ->
                    infoEditResult.launch {
                        putExtra("bookUrl", book.bookUrl)
                    }
                }
            },
            onChangeSource = {
                viewModel.getBook()?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            },
            onEditSource = {
                viewModel.getBook()?.let { book ->
                    if (book.isLocal) return@let
                    if (!appDb.bookSourceDao.has(book.origin)) {
                        toastOnUi(R.string.error_no_source)
                        return@let
                    }
                    editSourceResult.launch {
                        putExtra("sourceUrl", book.origin)
                    }
                }
            },
            onChangeGroup = {
                viewModel.getBook()?.let {
                    showDialogFragment(GroupSelectDialog(it.group))
                }
            },
            onOpenToc = ::openChapterListSafely,
            onOpenChapter = { item ->
                viewModel.chapterListData.value
                    ?.firstOrNull { it.index == item.index }
                    ?.takeIf { !it.isVolume }
                    ?.let(::openChapterDirect)
            },
            onOpenAiGallery = ::openBookAiImageGallery,
            onCustomButton = ::callSourceCustomButton,
            onLogin = ::openSourceLogin,
            onCloudBackup = {
                viewModel.getBook(false)?.let { book ->
                    BookCloudEntryModeStore.set(book.bookUrl, BookCloudEntryMode.CACHE_PACKAGE)
                    updateUiState()
                    startActivity(
                        Intent(this, CacheManageActivity::class.java).apply {
                            putExtra(CacheManageActivity.EXTRA_INITIAL_SEARCH_KEY, book.name)
                        }
                    )
                }
            },
            onOpenLibraryContainer = {
                viewModel.getBook(false)?.let { book ->
                    BookCloudEntryModeStore.set(book.bookUrl, BookCloudEntryMode.LIBRARY_CHAPTER)
                    updateUiState()
                }
            },
            onBookAutoTask = {
                viewModel.getBook(false)?.let { book ->
                    showBookAutoTaskDialog(book) {
                        updateUiState()
                    }
                }
            },
            onAllowUpdateChanged = ::setBookCanUpdate,
            onSetSourceVariable = ::setSourceVariable,
            onSetBookVariable = ::setBookVariable,
            onCopyBookUrl = ::copyBookUrl,
            onCopyTocUrl = ::copyTocUrl,
            onClearCache = ::clearBookCache,
            onShareBook = ::shareBook,
            onExportNotes = ::exportNotes,
            onSplitLongChapter = ::toggleSplitLongChapter,
            onShowLog = { showDialogFragment<AppLogDialog>() },
            onTopBook = {
                viewModel.topBook()
                updateUiState()
            },
            onSetupWebIntro = ::setupWebIntro,
            onIntroButtonClick = { name, click ->
                viewModel.onButtonClick(this@BookInfoComposeActivity, "info button $name", click)
            },
            onIntroImageClick = { click ->
                viewModel.onButtonClick(this@BookInfoComposeActivity, "info image", click)
            },
            onIntroImageLongClick = { source ->
                showDialogFragment(PhotoDialog(source, viewModel.bookSource?.bookSourceUrl))
            },
            onRefreshEnabledChanged = { enabled ->
                if (::refreshLayout.isInitialized) {
                    refreshLayout.isEnabled = enabled
                }
            }
        )
    }

    private fun setBookCanUpdate(enabled: Boolean) {
        viewModel.getBook()?.let { book ->
            book.canUpdate = enabled
            if (!enabled) {
                book.removeType(BookType.updateError)
            }
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book) {
                    updateUiState()
                }
            } else {
                updateUiState()
            }
        }
    }

    private fun copyBookUrl() {
        viewModel.getBook()?.let { book ->
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_COPY_BOOK_URL,
                viewModel.bookSource,
                book,
                null,
                result = book.bookUrl
            ) {
                sendToClip(book.bookUrl)
            }
        }
    }

    private fun copyTocUrl() {
        viewModel.getBook()?.let { book ->
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_COPY_TOC_URL,
                viewModel.bookSource,
                book,
                null,
                result = book.tocUrl
            ) {
                sendToClip(book.tocUrl)
            }
        }
    }

    private fun clearBookCache() {
        viewModel.getBook()?.let { book ->
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_CLEAR_CACHE,
                viewModel.bookSource,
                book,
                null
            ) {
                viewModel.clearCache(book)
            }
        }
    }

    private fun shareBook() {
        viewModel.getBook()?.let { book ->
            val bookJson = GSON.toJson(book)
            val shareStr = "${book.bookUrl}#$bookJson"
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_SHARE_BOOK,
                viewModel.bookSource,
                book,
                null,
                result = shareStr
            ) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra(Intent.EXTRA_TEXT, shareStr)
                intent.type = "text/plain"
                startActivity(Intent.createChooser(intent, book.name))
            }
        }
    }

    private fun exportNotes() {
        val book = viewModel.getBook() ?: return
        lifecycleScope.launch {
            val data = withContext(IO) {
                runCatching { BookmarkMarkdownExporter.export(book) }.getOrNull()
            }
            if (data == null) {
                toastOnUi(R.string.note_export_empty)
                return@launch
            }
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            exportNotesResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "${book.name}_notes_$date.md",
                    data,
                    "text/markdown"
                )
            }
        }
    }

    private fun toggleSplitLongChapter() {
        viewModel.getBook()?.let { book ->
            val next = !book.getSplitLongChapter()
            book.setSplitLongChapter(next)
            viewModel.loadBookInfo(book, false)
            updateUiState()
            if (!next) {
                longToastOnUi(R.string.need_more_time_load_content)
            }
        }
    }

    private fun openSourceLogin() {
        viewModel.bookSource
            ?.takeIf { !it.loginUrl.isNullOrBlank() }
            ?.let { source ->
                startActivity(
                    Intent(this, SourceLoginActivity::class.java).apply {
                        putExtra("type", "bookSource")
                        putExtra("key", source.bookSourceUrl)
                        putExtra("bookUrl", viewModel.getBook(false)?.bookUrl)
                    }
                )
            }
    }

    private fun setupWebIntro(webView: WebView) {
        BookInfoUseWebHost.bindSource(webView, viewModel.bookSource)
    }

    private fun showBook(book: Book) {
        updateUiState()
        updateReadTime(book)
        updateGroup(book)
        updateAiImages(book)
    }

    private fun updateUiState() {
        val book = viewModel.getBook(false)
        if (book == null) {
            if (::refreshLayout.isInitialized) {
                refreshLayout.isRefreshing = false
            }
            uiState = if (uiState.name.isBlank() && uiState.bookUrl.isBlank()) {
                buildInitialUiStateFromIntent()
            } else {
                uiState.copy(loading = true)
            }
            return
        }
        val chapterList = viewModel.chapterListData.value.orEmpty()
        val tocPhase = viewModel.tocLoadPhase(book.bookUrl)
        val tocText = when {
            book.isWebFile -> getString(R.string.toc_s, getString(R.string.downloading))
            chapterList.isEmpty() -> getString(R.string.toc_s, getString(tocPhase.emptyMessageRes))
            else -> getString(R.string.toc_s, book.durChapterTitle)
        }
        val chapters = chapterPreviewCache.get(book.bookUrl, chapterList, book.durChapterIndex)
        val intro = resolveStableIntro(book)
        val coverPath = resolveStableCoverPath(CoverDisplayResolver.resolve(book).path)
        uiState = BookInfoUiState(
            bookUrl = book.bookUrl,
            sourceUrl = book.origin,
            name = book.name,
            author = book.getRealAuthor(),
            originName = getString(R.string.origin_show, book.originName),
            latestChapterTitle = getString(R.string.lasted_show, book.latestChapterTitle),
            readTimeText = readTimeText,
            coverPath = coverPath,
            intro = intro,
            kinds = book.getKindList(),
            customTags = BookTagHelper.parse(book.customTag),
            groupText = groupText,
            tocText = tocText,
            tocLoadPhase = tocPhase,
            chapterCount = chapters.count,
            chapterPreview = chapters.first,
            currentChapterIndex = book.durChapterIndex,
            currentChapterTitle = book.durChapterTitle.orEmpty(),
            currentChapterPreview = chapters.current,
            aiImageCount = aiImageCount,
            aiImagePaths = aiImagePaths,
            inBookshelf = viewModel.inBookshelf,
            hasCustomButton = viewModel.hasCustomBtn,
            hasSourceLogin = !viewModel.bookSource?.loginUrl.isNullOrBlank(),
            hasBookSource = viewModel.bookSource != null,
            canUpdate = book.canUpdate,
            splitLongChapter = book.getSplitLongChapter(),
            cloudEntryMode = BookCloudEntryModeStore.get(book.bookUrl),
            loading = tocPhase.isLoading
        )
        if (::refreshLayout.isInitialized && !tocPhase.isLoading) {
            refreshLayout.isRefreshing = false
        }
    }

    private fun buildInitialUiStateFromIntent(): BookInfoUiState {
        val name = intent.getStringExtra("name").orEmpty()
        val author = intent.getStringExtra("author").orEmpty()
        val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
        val origin = intent.getStringExtra("origin").orEmpty()
        val originName = intent.getStringExtra("originName").orEmpty()
        val coverUrl = intent.getStringExtra("coverUrl").orEmpty()
        if (name.isBlank() && author.isBlank() && bookUrl.isBlank() && coverUrl.isBlank()) {
            return BookInfoUiState(loading = true)
        }
        val coverPath = resolveInitialCoverPathFromIntent(
            name = name,
            author = author,
            bookUrl = bookUrl,
            origin = origin,
            originName = originName,
            coverUrl = coverUrl
        )
        return BookInfoUiState(
            bookUrl = bookUrl,
            sourceUrl = origin,
            name = name,
            author = author,
            originName = originName.takeIf { it.isNotBlank() }?.let {
                getString(R.string.origin_show, it)
            }.orEmpty(),
            coverPath = coverPath,
            hasBookSource = origin.isNotBlank(),
            loading = true
        )
    }

    private fun resolveInitialCoverPathFromIntent(
        name: String,
        author: String,
        bookUrl: String,
        origin: String,
        originName: String,
        coverUrl: String
    ): String? {
        val rawCover = coverUrl.takeIf { it.isNotBlank() }
        if (name.isBlank() && author.isBlank() && bookUrl.isBlank()) {
            return rawCover
        }
        return CoverDisplayResolver.resolve(
            Book(
                bookUrl = bookUrl,
                origin = origin,
                originName = originName,
                name = name,
                author = author,
                coverUrl = rawCover
            )
        ).path ?: rawCover
    }

    private fun resolveStableCoverPath(nextPath: String?): String? {
        val next = nextPath?.takeIf { it.isNotBlank() }
        val current = uiState.coverPath?.takeIf { it.isNotBlank() }
        return next ?: current
    }

    private fun resolveStableIntro(book: Book): String {
        val intro = book.getDisplayIntro().orEmpty()
        if (intro.isNotBlank()) {
            lastStableIntroBookUrl = book.bookUrl
            lastStableIntro = intro
            return intro
        }
        return if (lastStableIntroBookUrl == book.bookUrl && lastStableIntro.isNotBlank()) {
            lastStableIntro
        } else {
            intro
        }
    }

    private fun updateReadTime(targetBook: Book) {
        val bookUrl = targetBook.bookUrl
        val bookName = targetBook.name
        readTimeJob?.cancel()
        readTimeJob = lifecycleScope.launch {
            val readTime = withContext(IO) {
                appDb.readRecordDao.getReadTime(bookName) ?: 0L
            }
            if (viewModel.getBook(false)?.let { it.bookUrl == bookUrl && it.name == bookName } == true) {
                readTimeText = "${getString(R.string.reading_time_tag)} ${formatReadDuration(readTime)}"
                uiState = uiState.copy(readTimeText = readTimeText)
            }
        }
    }

    private fun updateGroup(targetBook: Book) {
        val bookUrl = targetBook.bookUrl
        val groupId = targetBook.group
        val isLocal = targetBook.isLocal
        viewModel.loadGroup(groupId) {
            val currentBook = viewModel.getBook(false) ?: return@loadGroup
            if (currentBook.bookUrl != bookUrl || currentBook.group != groupId) return@loadGroup
            groupText = if (it.isNullOrEmpty()) {
                if (isLocal) {
                    getString(R.string.group_s, getString(R.string.local_no_group))
                } else {
                    getString(R.string.group_s, getString(R.string.no_group))
                }
            } else {
                getString(R.string.group_s, it)
            }
            uiState = uiState.copy(groupText = groupText)
        }
    }

    private fun updateAiImages(targetBook: Book, forceRefresh: Boolean = false) {
        val bookUrl = targetBook.bookUrl
        val key = AiImageGalleryManager.buildBookKey(targetBook.name, targetBook.author)
        galleryLoader.load(key, forceRefresh) { preview ->
            val currentBook = viewModel.getBook(false) ?: return@load
            if (currentBook.bookUrl != bookUrl ||
                AiImageGalleryManager.buildBookKey(currentBook.name, currentBook.author) != key
            ) return@load
            aiImageCount = preview.count
            aiImagePaths = preview.images.map { it.localPath }
            uiState = uiState.copy(aiImageCount = aiImageCount, aiImagePaths = aiImagePaths)
        }
    }

    private fun formatReadDuration(millis: Long): String {
        val days = millis / (1000 * 60 * 60 * 24)
        val hours = millis % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = millis % (1000 * 60 * 60) / (1000 * 60)
        val seconds = millis % (1000 * 60) / 1000
        val d = if (days > 0) getString(R.string.duration_day, days) else ""
        val h = if (hours > 0) getString(R.string.duration_hour, hours) else ""
        val m = if (minutes > 0) getString(R.string.duration_minute, minutes) else ""
        val s = if (seconds > 0 && days == 0L && hours == 0L) {
            getString(R.string.duration_second, seconds)
        } else {
            ""
        }
        return "$d$h$m$s".ifBlank { getString(R.string.duration_zero) }
    }

    private fun refreshBook() {
        val book = viewModel.getBook(false) ?: return
        if (::refreshLayout.isInitialized) {
            refreshLayout.isRefreshing = true
        }
        uiState = uiState.copy(loading = true)
        viewModel.refreshBook(book)
    }

    private fun refreshToc() {
        val book = viewModel.getBook(false) ?: return
        uiState = uiState.copy(loading = true)
        viewModel.loadChapter(book, true, isFromBookInfo = true)
    }

    private fun openBookAiImageGallery() {
        val book = viewModel.getBook(false) ?: return
        val key = AiImageGalleryManager.buildBookKey(book.name, book.author)
        startActivity(Intent(this, AiImageGalleryActivity::class.java).apply {
            putExtra(AiImageGalleryActivity.EXTRA_BOOK_KEY, key)
            putExtra(AiImageGalleryActivity.EXTRA_TITLE, getString(R.string.book_info_component_ai_images))
        })
    }

    private fun openChapterListSafely() {
        val book = viewModel.getBook() ?: return
        val chapters = viewModel.chapterListData.value
        if (!chapters.isNullOrEmpty()) {
            launchTocList(book)
            return
        }
        when (viewModel.tocLoadPhase(book.bookUrl)) {
            BookInfoTocPhase.LOADING -> {
                // 首次进入详情页时目录可能仍在后台加载：等待结果并自动打开目录页
                pendingTocJump = true
                toastOnUi(R.string.loading)
            }
            BookInfoTocPhase.NOT_LOADED, BookInfoTocPhase.ERROR -> {
                // 尚未加载或上次加载失败：点击视为重新拉取，完成后自动打开目录页
                pendingTocJump = true
                toastOnUi(R.string.loading)
                refreshToc()
            }
            BookInfoTocPhase.READY -> {
                if (chapters == null) {
                    // 极端竞态：目录已就绪但章节观察值尚未派发，按加载中处理
                    pendingTocJump = true
                    toastOnUi(R.string.loading)
                } else {
                    toastOnUi(R.string.chapter_list_empty)
                }
            }
            BookInfoTocPhase.EMPTY -> toastOnUi(R.string.chapter_list_empty)
        }
    }

    /** 目录加载完成后兑现「查看目录」的等待跳转；加载失败或为空时提示。 */
    private fun maybeOpenPendingTocJump() {
        if (!pendingTocJump) return
        val book = viewModel.getBook() ?: return
        if (!viewModel.chapterListData.value.isNullOrEmpty()) {
            pendingTocJump = false
            launchTocList(book)
            return
        }
        when (viewModel.tocLoadPhase(book.bookUrl)) {
            BookInfoTocPhase.ERROR -> {
                pendingTocJump = false
                toastOnUi(R.string.error_load_toc)
            }
            BookInfoTocPhase.EMPTY -> {
                pendingTocJump = false
                toastOnUi(R.string.chapter_list_empty)
            }
            else -> Unit
        }
    }

    private fun launchTocList(book: Book) {
        viewModel.prepareBookForEntry(book) { targetBook ->
            tocActivityResult.launch(targetBook.bookUrl)
        }
    }

    private fun openChapterDirect(chapter: BookChapter) {
        viewModel.getBook()?.let { book ->
            chapterChanged = true
            viewModel.saveBookAtChapter(book, chapter) { targetBook ->
                startReadActivity(targetBook)
            }
        }
    }

    private fun readBook(book: Book) {
        viewModel.prepareBookForEntry(book) { targetBook ->
            startReadActivity(targetBook)
        }
    }

    private fun startReadActivity(book: Book) {
        when {
            book.isAudio -> readBookResult.launch(
                Intent(this, AudioPlayActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )

            book.isVideo -> readBookResult.launch(
                Intent(this, io.legado.app.ui.video.VideoPlayerActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )

            else -> readBookResult.launch(
                Intent(
                    this,
                    when {
                        !book.isLocal && book.isImage && AppConfig.showMangaUi -> ReadMangaActivity::class.java
                        else -> ReadBookActivity::class.java
                    }
                )
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
                    .putExtra("chapterChanged", chapterChanged)
            )
        }
    }

    private fun showWebFileDownloadAlert(onClick: ((Book) -> Unit)? = null) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) {
            toastOnUi("Unexpected webFileData")
            return
        }
        selector(
            R.string.download_and_import_file,
            webFiles
        ) { _, webFile, _ ->
            when {
                webFile.isSupported -> {
                    viewModel.importOrDownloadWebFile<Book>(webFile) {
                        onClick?.invoke(it)
                    }
                }

                webFile.isSupportDecompress -> {
                    viewModel.importOrDownloadWebFile<Uri>(webFile) { uri ->
                        viewModel.getArchiveFilesName(uri) { fileNames ->
                            if (fileNames.size == 1) {
                                viewModel.importArchiveBook(uri, fileNames[0]) {
                                    onClick?.invoke(it)
                                }
                            } else {
                                showDecompressFileImportAlert(uri, fileNames, onClick)
                            }
                        }
                    }
                }

                else -> {
                    alert(
                        title = getString(R.string.draw),
                        message = getString(R.string.file_not_supported, webFile.name)
                    ) {
                        neutralButton(R.string.open_fun) {
                            viewModel.importOrDownloadWebFile<Uri>(webFile) {
                                openFileUri(it, "*/*")
                            }
                        }
                        noButton()
                    }
                }
            }
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri,
        fileNames: List<String>,
        success: ((Book) -> Unit)? = null
    ) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        selector(
            R.string.import_select_book,
            fileNames
        ) { _, name, _ ->
            viewModel.importArchiveBook(archiveFileUri, name) {
                success?.invoke(it)
            }
        }
    }

    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw,
                    messageResource = R.string.sure_del
                ) {
                    var checkBox: CheckBox? = null
                    if (book.isLocal) {
                        checkBox = CheckBox(this@BookInfoComposeActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoComposeActivity).apply {
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(checkBox)
                        }
                        customView { view }
                    }
                    yesButton {
                        checkBox?.let { LocalConfig.deleteBookOriginal = it.isChecked }
                        SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book)
                        viewModel.delBook(LocalConfig.deleteBookOriginal) {
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                    }
                    noButton()
                }
            } else {
                SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book)
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun callSourceCustomButton() {
        viewModel.bookSource?.customButton?.let {
            viewModel.getBook()?.let { book ->
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    viewModel.bookSource,
                    book,
                    null
                )
            }
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi(R.string.book_source_not_found)
                return@launch
            }
            val comment = source.getDisplayVariableComment(getString(R.string.source_variable_hint))
            val variable = withContext(IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    private fun setBookVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi(R.string.book_source_not_found)
                return@launch
            }
            val book = viewModel.getBook() ?: return@launch
            val variable = withContext(IO) { book.getCustomVariable() }
            val comment = source.getDisplayVariableComment(getString(R.string.book_variable_hint))
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_book_variable),
                    book.bookUrl,
                    variable,
                    comment
                )
            )
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        if (isShow) {
            waitDialog.setText("Loading.....")
            waitDialog.show()
        } else {
            waitDialog.dismiss()
        }
    }

    private fun upLoadBook(
        book: Book,
        bookWebDav: RemoteBookWebDav? = AppWebDav.defaultBookWebDav
    ) {
        lifecycleScope.launch {
            waitDialog.setText(getString(R.string.book_info_uploading))
            waitDialog.show()
            try {
                bookWebDav
                    ?.upload(book)
                    ?: throw NoStackTraceException(getString(R.string.webdav_not_configured))
                book.lastCheckTime = System.currentTimeMillis()
                viewModel.saveBook(book)
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage)
            } finally {
                waitDialog.dismiss()
            }
        }
    }

    override val oldBook: Book?
        get() = viewModel.bookData.value

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        viewModel.changeTo(source, book, toc)
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            }
            updateUiState()
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf(null)
            }
            updateGroup(book)
            updateUiState()
        }
    }

    override fun setVariable(key: String, variable: String?) {
        when (key) {
            viewModel.bookSource?.getKey() -> viewModel.bookSource?.setVariable(variable)
            viewModel.bookData.value?.bookUrl -> viewModel.bookData.value?.let {
                it.putCustomVariable(variable)
                if (viewModel.inBookshelf) {
                    viewModel.saveBook(it)
                }
            }
        }
    }
}
