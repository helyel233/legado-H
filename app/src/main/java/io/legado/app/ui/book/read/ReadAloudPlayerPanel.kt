package io.legado.app.ui.book.read

import io.legado.app.ui.widget.compose.LegadoComposeTheme
import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudUsageRecord
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.ai.AiReadAloudBgmService
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.ai.AiReadAloudRolePreviewSegment
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.ReadAloudSpeakerLoudnessManager
import io.legado.app.help.readaloud.ReadAloudSpeechPlanItem
import io.legado.app.help.readaloud.ReadAloudSpeechPlanner
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.routeMatchesGroup
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.ReadAloudTextCleaner
import io.legado.app.ui.book.read.page.entities.TextParagraph
import io.legado.app.ui.book.read.page.entities.buildReadAloudCues
import io.legado.app.ui.book.read.page.entities.indexForChapterPosition
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlin.math.roundToInt
import splitties.init.appCtx

class ReadAloudPlayerPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun finish()
        fun openBookCharacters()
        fun onReadAloudPlayerVisibilityChanged(visible: Boolean)
    }

    enum class DisplayMode {
        Immersive,
        Scene,
        Text
    }

    enum class PanelPhase {
        Hidden,
        Collapsed,
        Opening,
        Expanded
    }

    data class ParagraphUi(
        val index: Int,
        val text: String,
        val current: Boolean,
        val key: String = index.toString(),
        val sequence: Int = index
    )

    data class TextCueUi(
        val index: Int,
        val text: String,
        val current: Boolean,
        val key: String,
        val sequence: Int,
        val chapterPosition: Int
    )

    data class SceneSegmentUi(
        val index: Int,
        val key: String,
        val text: String,
        val roleType: String,
        val characterId: Long,
        val characterName: String,
        val avatar: String,
        val emotionName: String,
        val leftSide: Boolean,
        val narrator: Boolean,
        val current: Boolean,
        val chapterPosition: Int
    )

    data class FocusTextUi(
        val key: String = "",
        val sequence: Int = 0,
        val text: String = ""
    )

    data class ChapterPreviewUi(
        val index: Int,
        val title: String,
        val indexText: String,
        val current: Boolean,
        val volume: Boolean,
        val key: String
    )

    data class TtsEngineUi(
        val title: String,
        val subtitle: String,
        val value: String,
        val selected: Boolean,
        val key: String,
        val group: SpeechVoiceEngineGroup
    )

    data class CharacterPreviewUi(
        val id: Long,
        val name: String,
        val role: String,
        val summary: String,
        val key: String
    )

    data class AudioInfoUi(
        val enabled: Boolean = false,
        val cacheKey: String = "",
        val status: String = "",
        val bgmCount: Int = 0,
        val soundEffectCount: Int = 0,
        val bgmNames: List<String> = emptyList(),
        val soundEffectNames: List<String> = emptyList(),
        val elapsedMillis: Long = 0L,
        val requestCount: Int = 0,
        val inputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
        val outputTokens: Int = 0,
        val totalTokens: Int = 0,
        val error: String = "",
        val fingerprint: String = ""
    )

    data class PlayerUiState(
        val bookName: String = "",
        val author: String = "",
        val coverUrl: String? = null,
        val sourceOrigin: String? = null,
        val coverForcePath: Boolean = false,
        val coverAllowNameOverlay: Boolean? = null,
        val chapterTitle: String = "",
        val chapterIndexText: String = "",
        val playing: Boolean = false,
        val playbackPhase: String = ReadAloudPlaybackState.PHASE_STOPPED,
        val playbackBusy: Boolean = false,
        val serviceRunning: Boolean = false,
        val timerMinute: Int = 0,
        val progress: Float = 0f,
        val progressText: String = "0%",
        val paragraphText: String = "",
        val paragraphIndex: Int = 0,
        val paragraphCount: Int = 0,
        val chapterIndex: Int = 0,
        val chapterCount: Int = 0,
        val chapterPreview: List<ChapterPreviewUi> = emptyList(),
        val ttsEngines: List<TtsEngineUi> = emptyList(),
        val speechRoute: SpeechRoute = SpeechRoute(),
        val characterPreview: List<CharacterPreviewUi> = emptyList(),
        val audioInfo: AudioInfoUi = AudioInfoUi(),
        val nearbyParagraphs: List<ParagraphUi> = emptyList(),
        val textCues: List<TextCueUi> = emptyList(),
        val sceneSegments: List<SceneSegmentUi> = emptyList(),
        val currentCueIndex: Int = 0,
        val chapterKey: String = "",
        val paragraphKey: String = "",
        val paragraphSequence: Int = 0,
        val focusText: FocusTextUi = FocusTextUi(),
        val speechRate: Int = AppConfig.ttsSpeechRate.coerceIn(0, 45),
        val followSystemSpeechRate: Boolean = AppConfig.ttsFlowSys,
        val mode: DisplayMode = DisplayMode.Immersive,
        val foregroundActive: Boolean = true,
        val expanded: Boolean = false,
        val opening: Boolean = false,
        val panelPhase: PanelPhase = PanelPhase.Hidden,
        val readMenuVisible: Boolean = false,
        val readMenuAvoidBounds: RectF? = null,
        val openToken: Int = 0,
        val roleStatusText: String = "",
        val roleStatusRunning: Boolean = false,
        val roleStatusError: Boolean = false,
        val roleStatusVisible: Boolean = false,
        val roleDetailVisible: Boolean = false,
        val roleBlockingCurrentContent: Boolean = false,
        val roleBlockingText: String = "",
        val roleState: AiReadAloudRoleState? = null,
        val multiRoleEnabled: Boolean = AppConfig.aiReadAloudRoleEnabled,
        val speakerLoudnessEnabled: Boolean = AppConfig.readAloudSpeakerLoudnessEnabled,
        val speakerLoudnessGainPercent: Int = 100,
        val speakerLoudnessLearned: Boolean = false,
        val speakerLoudnessLearnedCount: Int = 0
    )

    private val composeView = ComposeView(context)
    private var callBack: CallBack? = null
    private var dismissedForCurrentRun = false
    private var foregroundActive = true
    private var lastChapterStart = 0
    private var roleStatusText = ""
    private var roleStatusRunning = false
    private var roleStatusError = false
    private var roleStatusUntil = 0L
    private var roleState: AiReadAloudRoleState? = null
    private var roleDetailCollapsed = false
    private var roleDetailClosed = false
    private var roleDetailKey = ""
    private var playbackPhase = ReadAloudPlaybackState.PHASE_STOPPED
    private var playbackMessage = ""
    private var playbackActualPlaying: Boolean? = null
    private var playbackBuffering = false
    private var playbackCueIndex = -1
    private var playbackChapterIndex = -1
    private var playbackChapterUrl = ""
    private var playbackCueCount = 0
    private var playbackCueChapterPosition = -1
    private var playbackCueKey = ""
    private var playbackCueText = ""
    private var playbackPlanKey = ""
    private var expanded = false
    private var opening = false
    private var panelPhase = PanelPhase.Hidden
    private var readMenuVisible = false
    private var readMenuAvoidBounds: RectF? = null
    private var openToken = 0
    private val capsuleBounds = RectF()
    private var capsulePosition by mutableStateOf(CapsulePositionState())
    private var switchingTtsEngine = false
    private var pendingTtsEngineSwitch: PendingTtsEngineSwitch? = null
    private var chapterModelCache: PlayerChapterModel? = null
    private var ttsEngineOptionsCacheKey = ""
    private var ttsEngineOptionsCache: List<TtsEngineUi> = emptyList()

    private var uiState by mutableStateOf(PlayerUiState())

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView.setContent {
            LegadoComposeTheme {
            ReadAloudPlayerContent(
                state = uiState,
                onClose = ::closeByUser,
                onExpand = { openFromBottom(force = true) },
                onStop = ::stopReadAloud,
                onPlayPause = { callBack?.onClickReadAloud() },
                onModeChange = ::setMode,
                onOpenChapterList = {
                    callBack?.openChapterList()
                },
                onPreviousChapter = { moveChapterFromPanel(-1) },
                onNextChapter = { moveChapterFromPanel(1) },
                onChapterSelect = ::selectChapterFromPanel,
                onOpenSettings = ::openReadAloudSetting,
                onTimerChange = ::setTimer,
                onEngineSelect = ::selectTtsEngine,
                onEngineSheetOpen = ::refreshTtsEngineOptions,
                onProgressSeek = ::seekToParagraphProgress,
                onCueSelect = ::seekToChapterPosition,
                capsulePosition = capsulePosition,
                onCapsulePositionChange = ::updateCapsulePosition,
                onCapsuleBounds = ::updateCapsuleBounds,
                onOpenCharacters = { callBack?.openBookCharacters() },
                onHideRoleDetail = ::hideRoleDetail,
                onOpenRoleDetail = ::openRoleDetail,
                onDismissRoleStatus = ::dismissRoleStatus,
                onRetryRoleAssignment = ::retryRoleAssignment,
                onRoleSegmentAssign = ::updateRoleSegmentAssignment
            )
                    }
        }
    }

    fun attach(lifecycleOwner: LifecycleOwner, callBack: CallBack) {
        this.callBack = callBack
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun open(force: Boolean = true) {
        openFromBottom(force)
    }

    fun openFromBottom(force: Boolean = true) {
        if (force) {
            dismissedForCurrentRun = false
        }
        showPanel(expand = true, animateFromBottom = true)
    }

    fun onAloudState(status: Int, autoExpand: Boolean = true) {
        when (status) {
            io.legado.app.constant.Status.PLAY -> {
                switchingTtsEngine = false
                if (playbackPhase in setOf(
                        ReadAloudPlaybackState.PHASE_PAUSED,
                        ReadAloudPlaybackState.PHASE_STOPPED,
                        ReadAloudPlaybackState.PHASE_ERROR
                    )
                ) {
                    playbackPhase = ReadAloudPlaybackState.PHASE_PREPARING
                    playbackMessage = ""
                    playbackActualPlaying = null
                    playbackBuffering = true
                }
                syncPlaybackUiState()
                if (autoExpand && !dismissedForCurrentRun && (visibility != VISIBLE || !expanded)) {
                    showPanel(animateFromBottom = true)
                }
            }

            io.legado.app.constant.Status.PAUSE -> {
                switchingTtsEngine = false
                playbackPhase = ReadAloudPlaybackState.PHASE_PAUSED
                playbackMessage = ""
                playbackActualPlaying = false
                playbackBuffering = false
                syncPlaybackUiState()
            }
            io.legado.app.constant.Status.STOP -> {
                val keepVisibleForPendingStart = visibility == VISIBLE &&
                        playbackPhase in setOf(
                            ReadAloudPlaybackState.PHASE_PREPARING,
                            ReadAloudPlaybackState.PHASE_BUFFERING
                        )
                if (switchingTtsEngine) {
                    val pendingSwitch = pendingTtsEngineSwitch
                    if (pendingSwitch?.wasPlaying == true) {
                        pendingTtsEngineSwitch = null
                        ReadAloud.play(
                            context = context,
                            play = true,
                            pageIndex = pendingSwitch.pageIndex,
                            startPos = pendingSwitch.startPos
                        )
                    } else {
                        switchingTtsEngine = false
                        pendingTtsEngineSwitch = null
                    }
                    refresh()
                    return
                }
                playbackPhase = ReadAloudPlaybackState.PHASE_STOPPED
                playbackMessage = ""
                playbackActualPlaying = false
                playbackBuffering = false
                playbackCueIndex = -1
                playbackChapterIndex = -1
                dismissedForCurrentRun = false
                if (keepVisibleForPendingStart) {
                    syncPlaybackUiState()
                    refresh()
                } else {
                    hidePanel()
                }
            }
        }
    }

    fun onTtsProgress(chapterStart: Int) {
        val normalizedStart = chapterStart.coerceAtLeast(0)
        val playbackCueEnd = playbackCueChapterPosition + playbackCueText.length.coerceAtLeast(1)
        if (lastChapterStart == normalizedStart ||
            (playbackCueIndex >= 0 && playbackCueChapterPosition >= 0 &&
                    normalizedStart in playbackCueChapterPosition..playbackCueEnd)
        ) {
            lastChapterStart = normalizedStart
            return
        }
        lastChapterStart = normalizedStart
        refresh()
    }

    fun onChapterContentChanged() {
        chapterModelCache = null
        playbackCueIndex = -1
        playbackCueCount = 0
        playbackCueChapterPosition = -1
        playbackCueKey = ""
        playbackCueText = ""
        playbackPlanKey = ""
        if (visibility != VISIBLE && !BaseReadAloudService.isRun) return
        lastChapterStart = ReadBook.durChapterPos.coerceAtLeast(0)
        refresh()
    }

    fun onPlaybackState(state: ReadAloudPlaybackState) {
        val currentBookUrl = ReadBook.book?.bookUrl.orEmpty()
        if (state.bookUrl.isNotBlank() && currentBookUrl.isNotBlank() && state.bookUrl != currentBookUrl) return
        val currentChapterIndex = ReadBook.curTextChapter?.chapter?.index ?: ReadBook.durChapterIndex
        val currentChapterUrl = ReadBook.curTextChapter?.chapter?.url.orEmpty()
        if (state.chapterIndex >= 0 && state.chapterIndex != currentChapterIndex) return
        if (state.chapterUrl.isNotBlank() && currentChapterUrl.isNotBlank() && state.chapterUrl != currentChapterUrl) return
        val cueChanged = ReadAloudPanelLayout.playbackTargetChanged(
            currentCueIndex = playbackCueIndex,
            currentChapterIndex = playbackChapterIndex,
            currentPlanKey = playbackPlanKey,
            nextCueIndex = state.cueIndex,
            nextChapterIndex = state.chapterIndex,
            nextPlanKey = state.planKey
        )
        playbackPhase = state.phase
        playbackMessage = state.message
        playbackActualPlaying = state.playing
        playbackBuffering = state.busy
        playbackCueIndex = state.cueIndex
        playbackChapterIndex = state.chapterIndex
        playbackChapterUrl = state.chapterUrl
        playbackCueCount = state.cueCount
        playbackCueChapterPosition = state.cueChapterPosition
        playbackCueKey = state.cueKey
        playbackCueText = state.cueText
        playbackPlanKey = state.planKey
        if (cueChanged) {
            refresh()
        } else {
            syncPlaybackUiState()
        }
    }

    fun onAiRoleState(state: AiReadAloudRoleState) {
        val currentBookUrl = ReadBook.book?.bookUrl ?: return
        val currentChapterIndex = ReadBook.curTextChapter?.chapter?.index ?: return
        if (state.bookUrl != currentBookUrl) return
        if (state.stage != AiReadAloudRoleState.STAGE_CURRENT ||
            state.chapterIndex != currentChapterIndex
        ) {
            return
        }
        val nextKey = "${state.bookUrl}:${state.chapterIndex}:${state.stage}"
        if (nextKey != roleDetailKey) {
            roleDetailKey = nextKey
            roleDetailCollapsed = true
            roleDetailClosed = false
        }
        if (roleState != state) {
            chapterModelCache = null
        }
        roleState = state
        roleStatusText = buildRoleStatusText(state)
        roleStatusRunning = state.running
        roleStatusError = state.status == AiReadAloudRoleState.STATUS_FAILED
        roleStatusUntil = Long.MAX_VALUE
        refresh()
    }

    private fun hideRoleDetail() {
        roleDetailCollapsed = true
        refresh()
    }

    private fun openRoleDetail() {
        roleDetailCollapsed = false
        roleDetailClosed = false
        refresh()
    }

    private fun dismissRoleStatus() {
        if (roleStatusRunning) return
        roleStatusText = ""
        roleStatusError = false
        roleStatusUntil = 0L
        roleState = null
        roleDetailCollapsed = false
        roleDetailClosed = true
        refresh()
    }

    private fun retryRoleAssignment() {
        val book = ReadBook.book ?: return
        val bookUrl = BookCharacterIdentityMigrator.migrate(book).ifBlank { book.characterBookKey() }
        val chapter = ReadBook.curTextChapter ?: return
        AiReadAloudRoleService.clearChapterCache(bookUrl, chapter.chapter.index)
        chapterModelCache = null
        roleDetailCollapsed = true
        roleDetailClosed = false
        roleStatusText = appCtx.getString(R.string.ra_panel_reassigning_roles_current_chapter)
        roleStatusRunning = true
        roleStatusError = false
        roleStatusUntil = Long.MAX_VALUE
        val pageIndex = ReadBook.durPageIndex
        val startPos = (ReadBook.durChapterPos - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
        ReadAloud.play(context, play = true, pageIndex = pageIndex, startPos = startPos)
        refresh()
    }

    private fun updateRoleSegmentAssignment(
        segment: AiReadAloudRolePreviewSegment,
        roleType: String,
        characterId: Long,
        characterName: String
    ) {
        Coroutine.async(Coroutine.defaultScope) {
            val book = ReadBook.book
                ?: return@async AiReadAloudRoleService.ManualSegmentAssignmentResult(false, appCtx.getString(R.string.ra_panel_book_not_found))
            val chapter = ReadBook.curTextChapter
                ?: return@async AiReadAloudRoleService.ManualSegmentAssignmentResult(false, appCtx.getString(R.string.ra_panel_chapter_not_found))
            val baseCues = chapter.buildReadAloudCues(context.getPrefBoolean(PreferKey.readAloudByPage))
            AiReadAloudRoleService.updateCachedSegmentAssignment(
                book = book,
                textChapter = chapter,
                paragraphs = baseCues.map { it.text },
                assignment = AiReadAloudRoleService.ManualSegmentAssignment(
                    paragraphIndex = segment.paragraphIndex,
                    start = segment.start,
                    end = segment.end,
                    roleType = roleType,
                    characterId = characterId,
                    characterName = characterName,
                    emotionName = segment.emotionName,
                    emotionTag = segment.emotionTag
                )
            )
        }.onSuccess { result ->
            roleStatusText = result.message
            roleStatusRunning = false
            roleStatusError = !result.success
            roleStatusUntil = System.currentTimeMillis() + 4_000L
            roleDetailCollapsed = false
            roleDetailClosed = false
            if (result.success) {
                roleState = roleState?.copy(
                    status = AiReadAloudRoleState.STATUS_SUCCESS,
                    message = result.message,
                    segmentCount = result.previewSegments.size,
                    previewSource = AiReadAloudRoleState.SOURCE_RESOLVED,
                    previewSegments = result.previewSegments,
                    error = ""
                )
                chapterModelCache = null
                ReadAloudConfigChangeNotifier.notifySpeech()
            }
            refresh()
        }.onError {
            roleStatusText = it.localizedMessage ?: it.javaClass.simpleName
            roleStatusRunning = false
            roleStatusError = true
            roleStatusUntil = System.currentTimeMillis() + 4_000L
            roleDetailCollapsed = false
            roleDetailClosed = false
            refresh()
        }
    }

    private fun selectChapterFromPanel(index: Int) {
        val chapterCount = ReadBook.chapterSize
        if (chapterCount <= 0) return
        val targetIndex = index.coerceIn(0, chapterCount - 1)
        val shouldContinueReadAloud = BaseReadAloudService.isRun
        val shouldPlay = !BaseReadAloudService.pause
        prepareChapterNavigation(targetIndex)
        if (shouldContinueReadAloud) {
            ReadAloud.selectChapter(context, targetIndex, continuePlayback = shouldPlay)
            return
        }
        ReadBook.openChapter(targetIndex, upContent = true) {
            onChapterContentChanged()
        }
    }

    private fun moveChapterFromPanel(delta: Int) {
        val chapterCount = ReadBook.chapterSize
        if (chapterCount <= 0) return
        val targetIndex = (ReadBook.durChapterIndex + delta).coerceIn(0, chapterCount - 1)
        if (targetIndex == ReadBook.durChapterIndex) return
        prepareChapterNavigation(targetIndex)
        if (BaseReadAloudService.isRun) {
            val continuePlayback = BaseReadAloudService.isPlay()
            if (delta < 0) {
                ReadAloud.prevChapter(context, continuePlayback)
            } else {
                ReadAloud.nextChapter(context, continuePlayback)
            }
            return
        }
        val moved = if (delta < 0) {
            ReadBook.moveToPrevChapter(
                upContent = true,
                toLast = false,
                fromReadAloud = false
            )
        } else {
            ReadBook.moveToNextChapter(true, fromReadAloud = false)
        }
        if (!moved) {
            refresh()
            return
        }
        post { onChapterContentChanged() }
    }

    private fun prepareChapterNavigation(chapterIndex: Int) {
        chapterModelCache = null
        roleState = null
        roleStatusText = ""
        roleStatusRunning = false
        roleStatusError = false
        roleStatusUntil = 0L
        roleDetailCollapsed = false
        roleDetailClosed = false
        playbackCueIndex = -1
        playbackChapterIndex = chapterIndex
        playbackChapterUrl = ""
        playbackCueCount = 0
        playbackCueChapterPosition = -1
        playbackCueKey = ""
        playbackCueText = ""
        playbackPlanKey = ""
        lastChapterStart = 0
        val bookUrl = ReadBook.book?.bookUrl.orEmpty()
        val chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterIndex + 1)
        val title = runCatching {
            ReadBook.book?.let { appDb.bookChapterDao.getChapter(it.bookUrl, chapterIndex)?.title }
        }.getOrNull().orEmpty().ifBlank { appCtx.getString(R.string.ra_panel_chapter, chapterIndex + 1) }
        uiState = uiState.copy(
            chapterTitle = title,
            chapterIndexText = "${chapterIndex + 1}/$chapterCount",
            chapterIndex = chapterIndex,
            chapterCount = chapterCount,
            chapterPreview = buildChapterPreview(bookUrl, chapterIndex, chapterCount),
            progress = 0f,
            progressText = "0/0",
            paragraphText = appCtx.getString(R.string.ra_panel_loading_chapter),
            paragraphIndex = 0,
            paragraphCount = 0,
            nearbyParagraphs = emptyList(),
            textCues = emptyList(),
            sceneSegments = emptyList(),
            currentCueIndex = 0,
            chapterKey = "$bookUrl:$chapterIndex",
            paragraphKey = "$bookUrl:$chapterIndex:loading",
            paragraphSequence = chapterIndex * 100_000,
            focusText = FocusTextUi(
                key = "$bookUrl:$chapterIndex:loading",
                sequence = chapterIndex * 100_000,
                text = appCtx.getString(R.string.ra_panel_loading_chapter)
            ),
            roleBlockingCurrentContent = false,
            roleBlockingText = ""
        )
    }

    private fun buildRoleStatusText(state: AiReadAloudRoleState): String {
        val prefix = if (state.stage == AiReadAloudRoleState.STAGE_NEXT) appCtx.getString(R.string.ra_panel_next_chapter) else appCtx.getString(R.string.ra_panel_current_chapter)
        val detail = when {
            state.error.isNotBlank() && state.status == AiReadAloudRoleState.STATUS_FAILED -> state.error
            state.createdCharacterCount > 0 -> appCtx.getString(R.string.ra_panel_new_characters_added, state.createdCharacterCount)
            state.segmentCount > 0 -> appCtx.getString(R.string.ra_panel_segments, state.segmentCount)
            else -> ""
        }
        val message = state.message.ifBlank {
            when (state.status) {
                AiReadAloudRoleState.STATUS_RUNNING -> appCtx.getString(R.string.ra_panel_assigning_roles, prefix)
                AiReadAloudRoleState.STATUS_SUCCESS -> appCtx.getString(R.string.ra_panel_role_assignment_completed, prefix)
                AiReadAloudRoleState.STATUS_FALLBACK -> appCtx.getString(R.string.ra_panel_using_default_role_assignment)
                AiReadAloudRoleState.STATUS_FAILED -> appCtx.getString(R.string.ra_panel_role_assignment_failed, prefix)
                else -> appCtx.getString(R.string.ra_panel_roles_assigned, prefix)
            }
        }
        return listOf(message, detail).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun onTimerChanged(minute: Int) {
        uiState = uiState.copy(timerMinute = minute)
    }

    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        syncPlaybackUiState()
    }

    private fun syncPlaybackUiState() {
        val servicePlaying = playbackActualPlaying ?: BaseReadAloudService.isPlay()
        uiState = uiState.copy(
            playing = servicePlaying,
            playbackPhase = playbackPhase,
            playbackBusy = playbackBuffering ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_PREPARING ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_BUFFERING,
            serviceRunning = BaseReadAloudService.isRun,
            foregroundActive = foregroundActive && visibility == VISIBLE
        )
    }

    fun setReadMenuVisible(visible: Boolean) {
        readMenuVisible = visible
        if (!visible) {
            readMenuAvoidBounds = null
        }
        uiState = uiState.copy(
            readMenuVisible = visible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF)
        )
    }

    fun setReadMenuAvoidBounds(bounds: RectF?) {
        readMenuVisible = bounds != null
        readMenuAvoidBounds = bounds?.let(::RectF)
        uiState = uiState.copy(
            readMenuVisible = readMenuVisible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF)
        )
    }

    fun isExpanded(): Boolean = visibility == VISIBLE && expanded

    fun isFullPanelActive(): Boolean {
        return visibility == VISIBLE && (panelPhase == PanelPhase.Opening || panelPhase == PanelPhase.Expanded)
    }

    fun close() {
        closeByUser()
    }

    fun refresh() {
        uiState = buildState(uiState.mode)
    }

    fun onReadAloudConfigChanged(scope: String?) {
        when (scope) {
            EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE,
            EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH -> {
                invalidateTtsEngineOptions()
                chapterModelCache = null
            }
            EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO -> {
                chapterModelCache = null
            }
            else -> {
                invalidateTtsEngineOptions()
                chapterModelCache = null
            }
        }
        refresh()
    }

    private fun showPanel(expand: Boolean = true, animateFromBottom: Boolean = true) {
        post {
            val wasVisible = visibility == VISIBLE
            val wasExpanded = expanded
            if (expand && animateFromBottom && !AppConfig.isEInkMode) {
                expanded = false
                opening = true
                panelPhase = PanelPhase.Opening
                openToken++
                uiState = uiState.copy(
                    foregroundActive = foregroundActive,
                    expanded = false,
                    opening = true,
                    panelPhase = PanelPhase.Opening,
                    readMenuVisible = readMenuVisible,
                    readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                    openToken = openToken
                )
                visibility = VISIBLE
                bringToFront()
                ViewCompat.requestApplyInsets(this)
                ViewCompat.requestApplyInsets(composeView)
                postOnAnimation {
                    expanded = true
                    opening = false
                    panelPhase = PanelPhase.Expanded
                    uiState = buildState(uiState.mode).copy(
                        foregroundActive = foregroundActive,
                        expanded = true,
                        opening = false,
                        panelPhase = PanelPhase.Expanded,
                        readMenuVisible = readMenuVisible,
                        readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                        openToken = openToken
                    )
                    if (!wasVisible || !wasExpanded) {
                        callBack?.onReadAloudPlayerVisibilityChanged(true)
                    }
                }
                return@post
            }
            expanded = expand
            opening = false
            panelPhase = if (expand) PanelPhase.Expanded else PanelPhase.Collapsed
            if (expand) {
                openToken++
            }
            visibility = VISIBLE
            bringToFront()
            ViewCompat.requestApplyInsets(this)
            ViewCompat.requestApplyInsets(composeView)
            uiState = buildState(uiState.mode).copy(
                foregroundActive = foregroundActive,
                expanded = expanded,
                opening = opening,
                panelPhase = panelPhase,
                readMenuVisible = readMenuVisible,
                readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                openToken = openToken
            )
            if (!wasVisible || (!wasExpanded && expanded)) {
                callBack?.onReadAloudPlayerVisibilityChanged(true)
            }
        }
    }

    private fun hidePanel() {
        val wasVisible = visibility == VISIBLE
        expanded = false
        opening = false
        panelPhase = PanelPhase.Hidden
        visibility = GONE
        capsuleBounds.setEmpty()
        uiState = buildState(uiState.mode).copy(
            foregroundActive = false,
            expanded = false,
            opening = false,
            panelPhase = PanelPhase.Hidden
        )
        if (wasVisible) {
            callBack?.onReadAloudPlayerVisibilityChanged(false)
        }
    }

    private fun closeByUser() {
        if (BaseReadAloudService.isRun) {
            dismissedForCurrentRun = true
            hidePanel()
        } else {
            hidePanel()
        }
    }


    private fun stopReadAloud() {
        ReadAloud.stop(context)
        dismissedForCurrentRun = false
        hidePanel()
    }

    private fun updateCapsuleBounds(bounds: RectF) {
        capsuleBounds.set(bounds)
    }

    private fun updateCapsulePosition(x: Float, y: Float) {
        val current = capsulePosition
        if (current.x != x || current.y != y) {
            capsulePosition = CapsulePositionState(x, y)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility == VISIBLE && !expanded && !uiState.roleStatusVisible && !uiState.roleDetailVisible) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                !capsuleBounds.contains(ev.x, ev.y)
            ) {
                return false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openReadAloudSetting() {
        (context as? AppCompatActivity)?.showDialogFragment(ReadAloudConfigDialog())
    }

    private fun setTimer(minute: Int) {
        AppConfig.ttsTimer = minute
        ReadAloud.setTimer(context, minute)
        uiState = uiState.copy(timerMinute = minute)
    }

    private fun selectTtsEngine(value: String) {
        val route = SpeechRoute.fromTtsEngineValue(value)
        val wasRunning = BaseReadAloudService.isRun
        val wasPlaying = BaseReadAloudService.isPlay()
        val pageIndex = ReadBook.durPageIndex
        val startPos = ReadBook.curTextChapter
            ?.getReadLength(pageIndex)
            ?.let { (ReadBook.durChapterPos - it).coerceAtLeast(0) }
            ?: 0
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = value
        invalidateTtsEngineOptions()
        if (wasRunning) {
            switchingTtsEngine = true
            pendingTtsEngineSwitch = PendingTtsEngineSwitch(
                wasPlaying = wasPlaying,
                pageIndex = pageIndex,
                startPos = startPos
            )
        } else {
            switchingTtsEngine = false
            pendingTtsEngineSwitch = null
        }
        ReadAloud.upReadAloudClass()
        route.engineValue.toLongOrNull()
            ?.let { appDb.httpTTSDao.get(it) }
            ?.takeIf { !it.loginUrl.isNullOrBlank() && it.getLoginInfo().isNullOrBlank() }
            ?.let { httpTts ->
                context.startActivity<SourceLoginActivity> {
                    putExtra("type", "httpTts")
                    putExtra("key", httpTts.id.toString())
                }
            }
        uiState = buildState(uiState.mode)
    }


    private fun setMode(mode: DisplayMode) {
        val targetMode = if (!AppConfig.aiReadAloudRoleEnabled && mode == DisplayMode.Scene) {
            DisplayMode.Immersive
        } else {
            mode
        }
        if (uiState.mode != targetMode) {
            uiState = uiState.copy(mode = targetMode)
        }
    }

    private fun seekToParagraphProgress(progress: Float) {
        val cues = buildChapterModel()?.cues.orEmpty()
        if (cues.isEmpty()) return
        val targetIndex = if (cues.size == 1) {
            0
        } else {
            (progress.coerceIn(0f, 1f) * (cues.size - 1)).roundToInt()
        }.coerceIn(0, cues.lastIndex)
        seekToChapterPosition(cues[targetIndex].chapterPosition)
    }

    private fun seekToChapterPosition(chapterPosition: Int) {
        val chapter = ReadBook.curTextChapter ?: return
        val targetPos = chapterPosition.coerceAtLeast(0)
        val pageIndex = chapter.getPageIndexByCharIndex(targetPos)
        if (pageIndex < 0) return
        val startPos = (targetPos - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
        val model = buildChapterModel()
        val targetCueIndex = model?.indexForChapterPosition(targetPos) ?: -1
        ReadBook.durChapterPos = targetPos
        if (BaseReadAloudService.isRun && targetCueIndex >= 0) {
            ReadAloud.moveToCue(
                context = context,
                cueIndex = targetCueIndex,
                chapterPosition = targetPos,
                play = true
            )
        } else {
            ReadAloud.play(
                context = context,
                play = true,
                pageIndex = pageIndex,
                startPos = startPos
            )
        }
        lastChapterStart = targetPos
        uiState = buildState(uiState.mode)
    }

    private fun buildChapterModel(): PlayerChapterModel? {
        val book = ReadBook.book ?: return null
        val chapter = ReadBook.curTextChapter ?: return null
        val chapterSequence = chapter.chapter.index
        val readAloudByPage = context.getPrefBoolean(PreferKey.readAloudByPage)
        val paragraphs = chapter.getParagraphs(false)
        val baseCues = chapter.buildReadAloudCues(readAloudByPage)
        val cueTexts = baseCues.map { cue -> cue.text }
        val multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled
        val exactRoleCacheKey = if (multiRoleEnabled) {
            AiReadAloudRoleService.cacheKeyFor(book, chapter, cueTexts)
        } else {
            null
        }
        val exactRoleCache = exactRoleCacheKey?.let { appDb.aiReadAloudRoleCacheDao.get(it) }
        val roleCache = if (multiRoleEnabled) {
            AiReadAloudRoleService.cacheForPlayback(book, chapter, cueTexts) ?: exactRoleCache
        } else {
            null
        }
        val roleCacheKey = roleCache?.cacheKey ?: exactRoleCacheKey
        val roleCacheReady = roleCache?.status == AiReadAloudRoleCache.STATUS_SUCCESS &&
                roleCache.segmentsJson.isNotBlank()
        val roleCacheRunning = multiRoleEnabled &&
                !roleCacheReady &&
                exactRoleCache?.status == AiReadAloudRoleCache.STATUS_RUNNING &&
                AiReadAloudRoleService.isRunningCacheActive(exactRoleCacheKey, exactRoleCache.updatedAt)
        val audioInfo = buildAudioInfo(book.bookUrl, chapterSequence)
        val coverDisplay = CoverDisplayResolver.resolve(book)
        val coverUrl = coverDisplay.path
        val cueFingerprint = buildString {
            append(baseCues.size)
            append(':')
            append(baseCues.firstOrNull()?.key.orEmpty())
            append(':')
            append(baseCues.lastOrNull()?.key.orEmpty())
            append(':')
            append(baseCues.sumOf { it.text.length })
        }
        val modelKey = listOf(
            book.bookUrl,
            book.name,
            book.author,
            book.origin,
            coverDisplay.loadKey,
            chapterSequence.toString(),
            chapter.chapter.url.orEmpty(),
            chapter.chapter.title,
            chapter.chaptersSize.toString(),
            ReadBook.chapterSize.toString(),
            readAloudByPage.toString(),
            multiRoleEnabled.toString(),
            cueFingerprint,
            roleCacheKey.orEmpty(),
            roleCache?.status.orEmpty(),
            roleCache?.updatedAt?.toString().orEmpty(),
            roleCache?.characterHash.orEmpty(),
            roleCache?.voiceHash.orEmpty(),
            roleCache?.segmentsJson?.length?.toString().orEmpty(),
            roleCache?.segmentsJson?.hashCode()?.toString().orEmpty(),
            audioInfo.fingerprint
        ).joinToString("|")
        chapterModelCache?.takeIf { it.key == modelKey }?.let { return it }
        val chapterKey = "${book.bookUrl}:$chapterSequence"
        val characterBookKey = book.characterBookKey()
        val speechPlan = ReadAloudSpeechPlanner.build(
            bookUrl = characterBookKey,
            chapter = chapter,
            baseCues = baseCues,
            multiRoleEnabled = multiRoleEnabled,
            roleCacheKey = roleCacheKey
        )
        val planKey = ReadAloudSpeechPlanner.planKey(
            bookUrl = characterBookKey,
            chapter = chapter,
            cues = speechPlan.cues,
            roleCacheKey = roleCacheKey
        )
        val totalLength = chapter.lastPage
            ?.let { it.chapterPosition + it.charSize }
            ?.coerceAtLeast(1)
            ?: 1
        return PlayerChapterModel(
            key = modelKey,
            bookName = book.name.ifBlank { context.getString(R.string.book_name) },
            author = book.author,
            coverUrl = coverUrl,
            sourceOrigin = coverDisplay.sourceOrigin,
            coverForcePath = coverDisplay.forcePath,
            coverAllowNameOverlay = coverDisplay.allowNameOverlay,
            chapterTitle = chapter.chapter.title.ifBlank { appCtx.getString(R.string.ra_panel_current_chapter) },
            chapterIndexText = "${chapterSequence + 1}/${chapter.chaptersSize.coerceAtLeast(chapterSequence + 1)}",
            chapterSequence = chapterSequence,
            chapterKey = chapterKey,
            planKey = planKey,
            chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            totalLength = totalLength,
            paragraphs = paragraphs,
            baseCues = baseCues,
            cues = speechPlan.cues,
            cuePositions = speechPlan.cues.map { it.chapterPosition }.toIntArray(),
            speechItems = speechPlan.items,
            textCues = speechPlan.cues.toTextCueUi(chapterKey, chapterSequence),
            sceneSegments = if (multiRoleEnabled) speechPlan.items.toSceneSegmentUi(chapterKey) else emptyList(),
            roleCacheKey = roleCacheKey,
            roleCacheReady = roleCacheReady,
            roleCacheRunning = roleCacheRunning,
            chapterPreview = buildChapterPreview(book.bookUrl, chapterSequence, ReadBook.chapterSize),
            characterPreview = buildCharacterPreview(
                BookCharacterIdentityMigrator.migrate(book).ifBlank { book.characterBookKey() }
            ),
            audioInfo = audioInfo
        ).also {
            chapterModelCache = it
        }
    }

    private fun buildState(mode: DisplayMode): PlayerUiState {
        val multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled
        val effectiveMode = if (!multiRoleEnabled && mode == DisplayMode.Scene) {
            DisplayMode.Immersive
        } else {
            mode
        }
        val model = buildChapterModel()
        val paragraphs = model?.paragraphs.orEmpty()
        val cues = model?.cues.orEmpty()
        val chapterSequence = model?.chapterSequence ?: ReadBook.durChapterIndex
        val chapterKey = model?.chapterKey ?: "${ReadBook.book?.bookUrl.orEmpty()}:$chapterSequence"
        val totalLength = model?.totalLength ?: 1
        val playbackPlanCurrent = playbackPlanKey.isBlank() ||
                model?.planKey.isNullOrBlank() ||
                model?.planKey == playbackPlanKey
        val playbackCurrent = playbackCueIndex >= 0 &&
                playbackChapterIndex == chapterSequence &&
                playbackPlanCurrent &&
                playbackPhase != ReadAloudPlaybackState.PHASE_STOPPED &&
                playbackPhase != ReadAloudPlaybackState.PHASE_ERROR
        val playbackStart = playbackCueChapterPosition
            .takeIf { playbackCurrent && it >= 0 }
        val chapterStart = when {
            playbackStart != null -> playbackStart
            lastChapterStart > 0 -> lastChapterStart
            else -> ReadBook.durChapterPos
        }.coerceIn(0, totalLength)
        val paragraphIndex = paragraphs.indexForChapterPosition(chapterStart)
        val modelCueIndex = model?.indexForChapterPosition(chapterStart)
            ?: cues.indexForChapterPosition(chapterStart)
        val cueIndex = if (playbackCurrent) playbackCueIndex else modelCueIndex
        val cue = cues.getOrNull(cueIndex)
        val paragraph = paragraphs.getOrNull(paragraphIndex)
        val paragraphText = playbackCueText
            .takeIf { playbackCurrent && it.isNotBlank() }
            ?: cue?.text
            ?: paragraph?.text?.cleanReadAloudText().orEmpty()
        val paragraphCount = if (playbackCurrent && playbackCueCount > 0) playbackCueCount else cues.size
        val displayCueIndex = cueIndex.coerceIn(0, (paragraphCount - 1).coerceAtLeast(0))
        val paragraphProgress = when {
            paragraphCount <= 1 || cueIndex < 0 -> 0f
            else -> (displayCueIndex.toFloat() / (paragraphCount - 1).toFloat()).coerceIn(0f, 1f)
        }
        val paragraphProgressText = if (paragraphCount > 0 && cueIndex >= 0) {
            "${displayCueIndex + 1}/$paragraphCount"
        } else {
            "0/0"
        }
        val paragraphSequence = chapterSequence * 100_000 + displayCueIndex.coerceAtLeast(0)
        val paragraphKey = playbackCueKey
            .takeIf { playbackCurrent && it.isNotBlank() }
            ?: "$chapterKey:${cue?.chapterPosition ?: paragraph?.chapterPosition ?: cueIndex}"
        val sentenceSourceText = playbackCueText
            .takeIf { playbackCurrent && it.isNotBlank() }
            ?: cue?.text
            ?: paragraph?.text
        val sentenceStart = playbackStart
            ?: cue?.chapterPosition
            ?: paragraph?.chapterPosition
            ?: 0
        val sentence = sentenceSourceText
            ?.focusSentenceAt(chapterStart - sentenceStart)
            ?: (0 to paragraphText)
        val focusText = FocusTextUi(
            key = "$paragraphKey:${sentence.first}:${sentence.second.hashCode()}",
            sequence = paragraphSequence * 1_000 + sentence.first.coerceAtLeast(0),
            text = sentence.second.ifBlank { paragraphText.ifBlank { appCtx.getString(R.string.ra_panel_no_current_paragraph) } }
        )
        val nearby = emptyList<ParagraphUi>()
        val textCues = model?.textCues.orEmpty()
        val sceneSegments = if (multiRoleEnabled) model?.sceneSegments.orEmpty() else emptyList()
        if (multiRoleEnabled &&
            model?.roleCacheReady == true &&
            roleStatusRunning &&
            (roleState == null ||
                    roleState?.bookUrl == ReadBook.book?.bookUrl &&
                    roleState?.chapterIndex == chapterSequence &&
                    roleState?.stage == AiReadAloudRoleState.STAGE_CURRENT)
        ) {
            roleStatusText = ""
            roleStatusRunning = false
            roleStatusError = false
            roleStatusUntil = 0L
            if (roleState?.running == true) {
                roleState = null
            }
        }
        val currentRoleState = roleState?.takeIf {
            multiRoleEnabled &&
                    it.stage == AiReadAloudRoleState.STAGE_CURRENT &&
                    it.bookUrl == ReadBook.book?.bookUrl &&
                    it.chapterIndex == chapterSequence
        }
        val currentRoleRunning = currentRoleState?.running == true || model?.roleCacheRunning == true
        val roleBlockingCurrentContent = multiRoleEnabled &&
                model?.roleCacheKey != null &&
                currentRoleRunning &&
                !model.roleCacheReady
        val speechRoute = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
        val currentSpeechItem = model?.speechItems?.getOrNull(displayCueIndex)
        val loudnessInfo = ReadAloudSpeakerLoudnessManager.infoFor(
            currentSpeechItem,
            currentSpeechItem?.route ?: speechRoute
        )
        val timerMinute = BaseReadAloudService.timeMinute
        val servicePlaying = playbackActualPlaying ?: BaseReadAloudService.isPlay()
        val roleEventVisible = multiRoleEnabled &&
                roleStatusText.isNotBlank() &&
                !roleDetailClosed &&
                (roleStatusRunning || roleStatusUntil > System.currentTimeMillis())
        return PlayerUiState(
            bookName = model?.bookName.orEmpty(),
            author = model?.author.orEmpty(),
            coverUrl = model?.coverUrl,
            sourceOrigin = model?.sourceOrigin,
            coverForcePath = model?.coverForcePath ?: false,
            coverAllowNameOverlay = model?.coverAllowNameOverlay,
            chapterTitle = model?.chapterTitle.orEmpty(),
            chapterIndexText = model?.chapterIndexText.orEmpty(),
            playing = servicePlaying,
            playbackPhase = playbackPhase,
            playbackBusy = playbackBuffering ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_PREPARING ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_BUFFERING,
            serviceRunning = BaseReadAloudService.isRun,
            timerMinute = timerMinute,
            progress = paragraphProgress,
            progressText = paragraphProgressText,
            paragraphText = paragraphText,
            paragraphIndex = if (cueIndex >= 0) cueIndex + 1 else 0,
            paragraphCount = paragraphCount,
            chapterIndex = chapterSequence.coerceAtLeast(0),
            chapterCount = model?.chapterCount ?: ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            chapterPreview = model?.chapterPreview.orEmpty(),
            ttsEngines = buildTtsEngineOptions(),
            speechRoute = speechRoute,
            characterPreview = model?.characterPreview.orEmpty(),
            audioInfo = model?.audioInfo ?: AudioInfoUi(enabled = AppConfig.aiReadAloudBgmEnabled),
            nearbyParagraphs = nearby,
            textCues = textCues,
            sceneSegments = sceneSegments,
            currentCueIndex = displayCueIndex.coerceAtLeast(0),
            chapterKey = chapterKey,
            paragraphKey = paragraphKey,
            paragraphSequence = paragraphSequence,
            focusText = focusText,
            speechRate = AppConfig.ttsSpeechRate.coerceIn(0, 45),
            followSystemSpeechRate = AppConfig.ttsFlowSys,
            mode = effectiveMode,
            foregroundActive = foregroundActive && visibility == VISIBLE,
            expanded = expanded,
            opening = opening,
            panelPhase = panelPhase,
            readMenuVisible = readMenuVisible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
            openToken = openToken,
            roleStatusText = roleStatusText,
            roleStatusRunning = roleStatusRunning,
            roleStatusError = roleStatusError,
            roleStatusVisible = roleEventVisible && roleDetailCollapsed,
            roleDetailVisible = roleEventVisible && !roleDetailCollapsed,
            roleBlockingCurrentContent = roleBlockingCurrentContent,
            roleBlockingText = currentRoleState?.message
                ?.ifBlank { roleStatusText }
                ?.ifBlank { appCtx.getString(R.string.ra_panel_assigning_roles_current_chapter) }
                ?: appCtx.getString(R.string.ra_panel_assigning_roles_current_chapter),
            roleState = roleState.takeIf { multiRoleEnabled },
            multiRoleEnabled = multiRoleEnabled,
            speakerLoudnessEnabled = AppConfig.readAloudSpeakerLoudnessEnabled,
            speakerLoudnessGainPercent = (loudnessInfo.gain * 100f).roundToInt(),
            speakerLoudnessLearned = loudnessInfo.learned,
            speakerLoudnessLearnedCount = ReadAloudSpeakerLoudnessManager.learnedSpeakerCount()
        )
    }

    private fun PlayerChapterModel.indexForChapterPosition(chapterPosition: Int): Int {
        if (cuePositions.isEmpty()) return -1
        var low = 0
        var high = cuePositions.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = cuePositions[mid]
            when {
                value < chapterPosition -> low = mid + 1
                value > chapterPosition -> high = mid - 1
                else -> return mid
            }
        }
        return (low - 1).coerceIn(0, cuePositions.lastIndex)
    }

    private fun List<TextParagraph>.indexForChapterPosition(chapterPosition: Int): Int {
        if (isEmpty()) return -1
        var low = 0
        var high = lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val paragraph = this[mid]
            when {
                chapterPosition < paragraph.chapterPosition -> high = mid - 1
                chapterPosition > paragraph.chapterIndices.last -> low = mid + 1
                else -> return mid
            }
        }
        return (low - 1).coerceIn(0, lastIndex)
    }

    private fun List<TextParagraph>.nearbyParagraphs(
        currentIndex: Int,
        chapterKey: String,
        chapterSequence: Int
    ): List<ParagraphUi> {
        if (isEmpty() || currentIndex !in indices) return emptyList()
        val start = (currentIndex - 4).coerceAtLeast(0)
        val end = (currentIndex + 4).coerceAtMost(lastIndex)
        return (start..end).map { index ->
            val paragraph = this[index]
            ParagraphUi(
                index = index + 1,
                text = paragraph.text.cleanReadAloudText(),
                current = index == currentIndex,
                key = "$chapterKey:${paragraph.chapterPosition}:${paragraph.realNum}",
                sequence = chapterSequence * 100_000 + index
            )
        }
    }

    private fun List<ReadAloudCue>.nearbyCueParagraphs(
        currentIndex: Int,
        chapterKey: String,
        chapterSequence: Int
    ): List<ParagraphUi> {
        if (isEmpty() || currentIndex !in indices) return emptyList()
        val start = (currentIndex - 4).coerceAtLeast(0)
        val end = (currentIndex + 4).coerceAtMost(lastIndex)
        return (start..end).map { index ->
            val cue = this[index]
            ParagraphUi(
                index = index + 1,
                text = cue.text.cleanReadAloudText(),
                current = index == currentIndex,
                key = "$chapterKey:${cue.chapterPosition}:${cue.key}",
                sequence = chapterSequence * 100_000 + index
            )
        }
    }

    private fun List<ReadAloudCue>.toTextCueUi(
        chapterKey: String,
        chapterSequence: Int
    ): List<TextCueUi> {
        return mapIndexed { index, cue ->
            TextCueUi(
                index = index + 1,
                text = cue.text.cleanReadAloudText(),
                current = false,
                key = "$chapterKey:${cue.chapterPosition}:${cue.key}",
                sequence = chapterSequence * 100_000 + index,
                chapterPosition = cue.chapterPosition
            )
        }
    }

    private fun buildChapterPreview(
        bookUrl: String?,
        currentIndex: Int,
        chapterCount: Int
    ): List<ChapterPreviewUi> {
        if (bookUrl.isNullOrBlank() || chapterCount <= 0) return emptyList()
        val start = (currentIndex - 5).coerceAtLeast(0)
        val end = (currentIndex + 6).coerceAtMost(chapterCount - 1)
        return runCatching {
            appDb.bookChapterDao.getChapterList(bookUrl, start, end).map { chapter ->
                ChapterPreviewUi(
                    index = chapter.index,
                    title = chapter.title.ifBlank { appCtx.getString(R.string.ra_panel_untitled_chapter) },
                    indexText = if (chapter.isVolume) {
                        appCtx.getString(R.string.ra_panel_volume)
                    } else {
                        "${chapter.index + 1}/$chapterCount"
                    },
                    current = chapter.index == currentIndex,
                    volume = chapter.isVolume,
                    key = "$bookUrl:${chapter.index}:${chapter.title}"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun buildTtsEngineOptions(): List<TtsEngineUi> {
        val httpTtsList = runCatching { appDb.httpTTSDao.all }.getOrDefault(emptyList())
        val cacheKey = buildTtsEngineOptionsCacheKey(httpTtsList)
        if (ttsEngineOptionsCacheKey == cacheKey && ttsEngineOptionsCache.isNotEmpty()) {
            return ttsEngineOptionsCache
        }
        val currentRoute = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
        return SpeechVoiceCatalogRepository
            .allGroups(context, httpTtsList)
            .map { group ->
            TtsEngineUi(
                    title = group.title,
                    subtitle = group.subtitle,
                    value = group.engineValue,
                    selected = routeMatchesGroup(currentRoute, group),
                    key = group.key,
                    group = group
            )
        }.also {
            ttsEngineOptionsCacheKey = cacheKey
            ttsEngineOptionsCache = it
        }
    }

    private fun invalidateTtsEngineOptions() {
        ttsEngineOptionsCacheKey = ""
        ttsEngineOptionsCache = emptyList()
    }

    private fun buildTtsEngineOptionsCacheKey(httpTtsList: List<HttpTTS>): String {
        return buildString {
            append(ReadAloud.ttsEngine.orEmpty())
            httpTtsList.forEach { httpTts ->
                append('|')
                append(httpTts.id)
                append(':')
                append(httpTts.lastUpdateTime)
                append(':')
                append(httpTts.name.hashCode())
                append(':')
                append(httpTts.url.hashCode())
                append(':')
                append(MD5Utils.md5Encode(httpTts.speakersJson))
                append(':')
                append(MD5Utils.md5Encode(httpTts.emotionsJson))
            }
            append("|speakerGroups=")
            append(runCatching {
                val groups = appDb.readAloudSpeakerGroupDao.groups()
                    .joinToString(";") { "${it.id}:${it.name}:${it.enabled}:${it.updatedAt}" }
                val items = appDb.readAloudSpeakerGroupDao.items()
                    .joinToString(";") {
                        "${it.groupId}:${it.engineType}:${it.engineValue}:${it.speakerName}:${it.toneID}:${it.updatedAt}"
                    }
                MD5Utils.md5Encode("$groups\n$items")
            }.getOrDefault(""))
        }
    }

    private fun refreshTtsEngineOptions() {
        invalidateTtsEngineOptions()
        uiState = buildState(uiState.mode)
    }

    private fun buildCharacterPreview(bookUrl: String?): List<CharacterPreviewUi> {
        if (bookUrl.isNullOrBlank()) return emptyList()
        return runCatching {
            appDb.bookCharacterDao.characters(bookUrl).take(8).map { character ->
                CharacterPreviewUi(
                    id = character.id,
                    name = character.displayName(),
                    role = character.roleLabel(),
                    summary = character.previewSummary(),
                    key = "character:${character.id}"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun buildAudioInfo(bookUrl: String?, chapterIndex: Int): AudioInfoUi {
        if (bookUrl.isNullOrBlank() || chapterIndex < 0) {
            return AudioInfoUi(enabled = AppConfig.aiReadAloudBgmEnabled)
        }
        val cached = runCatching {
            AiReadAloudBgmService.cachedAudioInfoForChapter(bookUrl, chapterIndex)
        }.getOrNull()
        val usage = runCatching {
            appDb.aiReadAloudUsageRecordDao
                .list(AiReadAloudUsageRecord.TYPE_AUDIO, bookUrl, limit = 80)
                .firstOrNull { it.chapterIndex == chapterIndex }
        }.getOrNull()
        val fingerprint = buildString {
            append(AppConfig.aiReadAloudBgmEnabled)
            append('|')
            append(cached?.cacheKey.orEmpty())
            append('|')
            append(cached?.status.orEmpty())
            append('|')
            append(cached?.assignmentCount ?: 0)
            append('|')
            append(cached?.soundEffectCount ?: 0)
            append('|')
            append(cached?.updatedAt ?: 0L)
            append('|')
            append(usage?.id ?: 0L)
            append('|')
            append(usage?.totalTokens ?: 0)
        }
        return AudioInfoUi(
            enabled = AppConfig.aiReadAloudBgmEnabled,
            cacheKey = cached?.cacheKey.orEmpty(),
            status = cached?.status.orEmpty(),
            bgmCount = cached?.assignmentCount ?: 0,
            soundEffectCount = cached?.soundEffectCount ?: 0,
            bgmNames = cached?.bgmTrackNames.orEmpty(),
            soundEffectNames = cached?.soundEffectTrackNames.orEmpty(),
            elapsedMillis = usage?.elapsedMillis ?: 0L,
            requestCount = usage?.requestCount ?: 0,
            inputTokens = usage?.inputTokens ?: 0,
            cachedInputTokens = usage?.cachedInputTokens ?: 0,
            outputTokens = usage?.outputTokens ?: 0,
            totalTokens = usage?.totalTokens ?: 0,
            error = usage?.error.orEmpty(),
            fingerprint = fingerprint
        )
    }

    private fun List<ReadAloudSpeechPlanItem>.toSceneSegmentUi(
        chapterKey: String
    ): List<SceneSegmentUi> {
        return map { item ->
            SceneSegmentUi(
                index = item.index,
                key = "$chapterKey:scene:${item.index}:${item.sourceCueIndex}:${item.sourceStart}:${item.sourceEnd}",
                text = item.cue.text.cleanReadAloudText(),
                roleType = item.roleType,
                characterId = item.characterId,
                characterName = item.characterName,
                avatar = item.avatar,
                emotionName = item.emotionName,
                leftSide = item.leftSide,
                narrator = item.narrator,
                current = false,
                chapterPosition = item.cue.chapterPosition
            )
        }
    }


    private fun BookCharacter.previewSummary(): String {
        return listOf(identity, skills, attributes, biography)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(80)
            .ifBlank { appCtx.getString(R.string.ra_panel_no_character_summary) }
    }

    private fun String.focusSentenceAt(offset: Int): Pair<Int, String> {
        if (isBlank()) return 0 to ""
        val cursor = offset.coerceIn(0, lastIndex.coerceAtLeast(0))
        fun isBoundary(char: Char): Boolean {
            return char == '。' || char == '！' || char == '？' ||
                    char == '!' || char == '?' || char == ';' || char == '；' ||
                    char == '\n'
        }
        fun isClosingQuote(char: Char): Boolean {
            return char == '”' || char == '’' || char == '」' || char == '』' ||
                    char == ')' || char == '）' || char == ']' || char == '】'
        }
        var start = cursor
        while (start > 0 && !isBoundary(this[start - 1])) {
            start--
        }
        while (start < length && this[start].isWhitespace()) {
            start++
        }
        var end = cursor
        while (end < length && !isBoundary(this[end])) {
            end++
        }
        if (end < length) {
            end++
        }
        while (end < length && isClosingQuote(this[end])) {
            end++
        }
        val safeStart = start.coerceIn(0, end.coerceAtLeast(0))
        return safeStart to substring(safeStart, end.coerceIn(safeStart, length)).cleanReadAloudText()
    }

    private fun String.cleanReadAloudText(): String {
        return ReadAloudTextCleaner.cleanInlineText(this)
            .ifBlank { appCtx.getString(R.string.ra_panel_no_current_paragraph) }
    }
}

private data class PendingTtsEngineSwitch(
    val wasPlaying: Boolean,
    val pageIndex: Int,
    val startPos: Int
)

private data class PlayerChapterModel(
    val key: String,
    val bookName: String,
    val author: String,
    val coverUrl: String?,
    val sourceOrigin: String?,
    val coverForcePath: Boolean,
    val coverAllowNameOverlay: Boolean,
    val chapterTitle: String,
    val chapterIndexText: String,
    val chapterSequence: Int,
    val chapterKey: String,
    val planKey: String,
    val chapterCount: Int,
    val totalLength: Int,
    val paragraphs: List<TextParagraph>,
    val baseCues: List<ReadAloudCue>,
    val cues: List<ReadAloudCue>,
    val cuePositions: IntArray,
    val speechItems: List<ReadAloudSpeechPlanItem>,
    val textCues: List<ReadAloudPlayerPanel.TextCueUi>,
    val sceneSegments: List<ReadAloudPlayerPanel.SceneSegmentUi>,
    val roleCacheKey: String?,
    val roleCacheReady: Boolean,
    val roleCacheRunning: Boolean,
    val chapterPreview: List<ReadAloudPlayerPanel.ChapterPreviewUi>,
    val characterPreview: List<ReadAloudPlayerPanel.CharacterPreviewUi>,
    val audioInfo: ReadAloudPlayerPanel.AudioInfoUi
)
