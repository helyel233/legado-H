package io.legado.app.ui.book.read

import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.R
import io.legado.app.help.ai.AiReadAloudRolePreviewSegment
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ReadBook
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.composeActionShape
import io.legado.app.lib.theme.composePanelShape
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.book.read.config.SpeechVoiceRoutePickerDialog
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import splitties.init.appCtx

@Composable
internal fun ReadAloudPlayerContent(
    state: ReadAloudPlayerPanel.PlayerUiState,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    onPlayPause: () -> Unit,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onTimerChange: (Int) -> Unit,
    onEngineSelect: (String) -> Unit,
    onEngineSheetOpen: () -> Unit,
    onProgressSeek: (Float) -> Unit,
    onCueSelect: (Int) -> Unit,
    capsulePosition: CapsulePositionState,
    onCapsulePositionChange: (Float, Float) -> Unit,
    onCapsuleBounds: (RectF) -> Unit,
    onOpenCharacters: () -> Unit,
    onHideRoleDetail: () -> Unit,
    onOpenRoleDetail: () -> Unit,
    onDismissRoleStatus: () -> Unit,
    onRetryRoleAssignment: () -> Unit,
    onRoleSegmentAssign: (AiReadAloudRolePreviewSegment, String, Long, String) -> Unit
) {
    val palette = ReaderSheetStyle.resolve(LocalContext.current)
    val colors = rememberPlayerColors(palette)
    val density = LocalDensity.current
    var activeSheet by remember { mutableStateOf(PlayerSheet.None) }
    var sheetVisible by remember { mutableStateOf(false) }
    val animateTextChanges = !AppConfig.isEInkMode && state.foregroundActive
    val animatePanelChanges = !AppConfig.isEInkMode && state.foregroundActive
    var sceneFullscreen by remember { mutableStateOf(false) }
    var sceneFullscreenTopBarHeight by remember { mutableStateOf(0.dp) }
    var editingRoleSegment by remember { mutableStateOf<AiReadAloudRolePreviewSegment?>(null) }
    val sceneFullscreenActive = sceneFullscreen &&
            state.mode == ReadAloudPlayerPanel.DisplayMode.Scene &&
            state.panelPhase == ReadAloudPlayerPanel.PanelPhase.Expanded &&
            !state.roleBlockingCurrentContent
    val sceneFullscreenTopInset = if (sceneFullscreenActive) {
        (sceneFullscreenTopBarHeight + 8.dp).coerceAtLeast(58.dp)
    } else {
        0.dp
    }
    LaunchedEffect(state.mode, state.panelPhase, state.roleBlockingCurrentContent) {
        if (state.mode != ReadAloudPlayerPanel.DisplayMode.Scene ||
            state.panelPhase != ReadAloudPlayerPanel.PanelPhase.Expanded ||
            state.roleBlockingCurrentContent
        ) {
            sceneFullscreen = false
        }
    }
    LaunchedEffect(sceneFullscreenActive) {
        if (sceneFullscreenActive) {
            sheetVisible = false
        }
    }
    PlayerBackHandler(enabled = sceneFullscreenActive) {
        sceneFullscreen = false
    }
    val sheetEnter = if (animatePanelChanges) {
        fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(240, easing = FastOutSlowInEasing)) { height -> height / 6 } +
                expandVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top
                )
    } else {
        fadeIn(tween(1)) + expandVertically(tween(1), expandFrom = Alignment.Top)
    }
    val sheetExit = if (animatePanelChanges) {
        shrinkVertically(
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        ) +
                slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { height -> height / 8 } +
                fadeOut(tween(170, easing = FastOutSlowInEasing))
    } else {
        shrinkVertically(tween(1), shrinkTowards = Alignment.Top) + fadeOut(tween(1))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.panelPhase == ReadAloudPlayerPanel.PanelPhase.Expanded,
            enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(260, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it } +
                    fadeOut(tween(180, easing = FastOutSlowInEasing))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CoverAtmosphereBackdrop(
                    state = state,
                    colors = colors,
                    animateFluid = !sceneFullscreenActive
                )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val short = maxHeight < 660.dp
            val veryShort = maxHeight < 560.dp
            val sidePadding = when {
                landscape -> 34.dp
                maxWidth < 360.dp -> 18.dp
                else -> 24.dp
            }
            val topPadding = if (veryShort) 10.dp else 18.dp
            val bottomPadding = if (veryShort) 16.dp else 26.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = sidePadding, end = sidePadding, top = topPadding, bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = !sceneFullscreenActive,
                    enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(180, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) +
                            fadeOut(tween(140, easing = FastOutSlowInEasing))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MinimalHeader(
                            mode = state.mode,
                            multiRoleEnabled = state.multiRoleEnabled,
                            colors = colors,
                            onClose = onClose,
                            onOpenSettings = onOpenSettings,
                            onModeChange = {
                                sheetVisible = false
                                if (it == state.mode && it == ReadAloudPlayerPanel.DisplayMode.Scene) {
                                    sceneFullscreen = true
                                } else {
                                    sceneFullscreen = false
                                    onModeChange(it)
                                }
                            }
                        )
                        RoleAssignmentStatus(state = state, colors = colors, onOpen = onOpenRoleDetail)
                        Spacer(modifier = Modifier.height(if (veryShort) 4.dp else 10.dp))
                    }
                }
                if (landscape) {
                    LandscapePlayerBody(
                        state = state,
                        colors = colors,
                        short = short,
                        sceneFullscreen = sceneFullscreenActive,
                        sceneFullscreenTopInset = sceneFullscreenTopInset,
                        animateTextChanges = animateTextChanges,
                        onCueSelect = onCueSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    PortraitPlayerBody(
                        state = state,
                        colors = colors,
                        short = short,
                        veryShort = veryShort,
                        sceneFullscreen = sceneFullscreenActive,
                        sceneFullscreenTopInset = sceneFullscreenTopInset,
                        animateTextChanges = animateTextChanges,
                        onCueSelect = onCueSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
                AnimatedVisibility(
                    visible = !sceneFullscreenActive,
                    enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(180, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) +
                            fadeOut(tween(140, easing = FastOutSlowInEasing))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 14.dp))
                        MinimalProgress(state, colors, onProgressSeek)
                        Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 12.dp))
                        PlayerControlDock(
                            state = state,
                            colors = colors,
                            activeSheet = if (sheetVisible) activeSheet else PlayerSheet.None,
                            onSheetChange = { sheet ->
                                if (sheetVisible && activeSheet == sheet) {
                                    sheetVisible = false
                                } else {
                                    if (sheet == PlayerSheet.Engine) {
                                        onEngineSheetOpen()
                                    }
                                    activeSheet = sheet
                                    sheetVisible = true
                                }
                            },
                            onPlayPause = onPlayPause,
                            onPreviousChapter = onPreviousChapter,
                            onNextChapter = onNextChapter,
                            onOpenChapterList = onOpenChapterList
                        )
                    }
                }
            }
            if (sheetVisible && activeSheet != PlayerSheet.None) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(activeSheet) {
                            detectTapGestures {
                                sheetVisible = false
                            }
                        }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(
                        start = sidePadding,
                        end = sidePadding,
                        bottom = bottomPadding + 138.dp
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visible = sheetVisible && activeSheet != PlayerSheet.None,
                    enter = sheetEnter,
                    exit = sheetExit
                ) {
                    PlayerSheetPanel(
                        sheet = activeSheet,
                        state = state,
                        colors = colors,
                        onOpenChapterList = onOpenChapterList,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onChapterSelect = onChapterSelect,
                        onTimerChange = onTimerChange,
                        onEngineSelect = onEngineSelect,
                        onOpenCharacters = onOpenCharacters
                    )
                }
            }
            AnimatedVisibility(
                visible = sceneFullscreenActive,
                enter = fadeIn(tween(160, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(130, easing = FastOutSlowInEasing))
            ) {
                SceneFullscreenTopBar(
                    title = state.chapterTitle,
                    colors = colors,
                    onExit = { sceneFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = sidePadding, end = sidePadding, top = topPadding)
                        .onGloballyPositioned { coordinates ->
                            sceneFullscreenTopBarHeight = with(density) {
                                coordinates.size.height.toDp()
                            }
                        }
                )
            }
        }
    }
        }
        RoleAssignmentProgressDialog(
            state = state,
            colors = colors,
            onHide = onHideRoleDetail,
            onDismiss = onDismissRoleStatus,
            onRetry = onRetryRoleAssignment,
            onEditSegment = { editingRoleSegment = it }
        )
        editingRoleSegment?.let { segment ->
            RoleSegmentEditDialog(
                state = state,
                segment = segment,
                colors = colors,
                onDismiss = { editingRoleSegment = null },
                onSave = { roleType, characterId, characterName ->
                    editingRoleSegment = null
                    onRoleSegmentAssign(segment, roleType, characterId, characterName)
                }
            )
        }
    }
}

private enum class PlayerSheet {
    None,
    Chapter,
    Timer,
    Engine,
    Characters
}

private data class LyricsTarget(
    val key: String,
    val sequence: Int,
    val paragraphs: List<ReadAloudPlayerPanel.ParagraphUi>
)

internal data class PlayerColors(
    val night: Boolean,
    val background: Color,
    val panel: Color,
    val panelStrong: Color,
    val panelBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val subtleText: Color,
    val accent: Color,
    val accentText: Color,
    val fluidA: Color,
    val fluidB: Color,
    val fluidC: Color
)

internal data class CapsulePositionState(
    val x: Float? = null,
    val y: Float? = null
)

internal const val READ_ALOUD_CAPSULE_WIDTH_DP = 146
internal const val READ_ALOUD_CAPSULE_HEIGHT_DP = 54

@Composable
internal fun rememberPlayerColors(palette: ReaderSheetStyle.Palette): PlayerColors {
    val accent = Color(palette.accentColor)
    val night = AppConfig.isNightTheme || !ColorUtils.isColorLight(palette.surface)
    val panel = if (night) {
        Color(ColorUtils.blendColors(palette.panel, android.graphics.Color.BLACK, 0.50f))
    } else {
        Color(ColorUtils.blendColors(palette.panel, android.graphics.Color.WHITE, 0.82f))
    }
    val panelStrong = if (night) {
        Color(ColorUtils.blendColors(palette.panelStrong, android.graphics.Color.BLACK, 0.62f))
    } else {
        Color(ColorUtils.blendColors(palette.panelStrong, android.graphics.Color.WHITE, 0.88f))
    }
    return PlayerColors(
        night = night,
        background = if (night) {
            Color(ColorUtils.blendColors(palette.surface, android.graphics.Color.BLACK, 0.68f))
        } else {
            Color(ColorUtils.blendColors(palette.surface, android.graphics.Color.WHITE, 0.72f))
        },
        panel = panel,
        panelStrong = panelStrong,
        panelBorder = Color(UiCorner.borderLineColor(LocalContext.current)),
        primaryText = Color(palette.textColor).copy(alpha = 0.94f),
        secondaryText = Color(palette.secondaryTextColor).copy(alpha = 0.88f),
        subtleText = Color(palette.secondaryTextColor).copy(alpha = 0.66f),
        accent = accent,
        accentText = if (ColorUtils.isColorLight(palette.accentColor)) Color.Black else Color.White,
        fluidA = accent,
        fluidB = Color(ColorUtils.blendColors(palette.accentColor, 0xFF9E5A2A.toInt(), 0.38f)),
        fluidC = Color(ColorUtils.blendColors(palette.primaryColor, 0xFF24505A.toInt(), 0.42f))
    )
}

@Composable
private fun CoverAtmosphereBackdrop(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    animateFluid: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        CoverBackdropImage(
            state = state,
            alpha = 0.30f,
            modifier = Modifier.fillMaxSize()
        )
        if (animateFluid) {
            FluidBackdropLayer(state, colors)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (colors.night) {
                            listOf(
                                Color.Black.copy(alpha = 0.32f),
                                colors.background.copy(alpha = 0.54f),
                                Color.Black.copy(alpha = 0.66f)
                            )
                        } else {
                            listOf(
                                Color.White.copy(alpha = 0.40f),
                                colors.background.copy(alpha = 0.76f),
                                Color.White.copy(alpha = 0.82f)
                            )
                        }
                    )
                )
        )
    }
}

@Composable
private fun CoverBackdropImage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = {
            AppCompatImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
                this.alpha = alpha
            }
        },
        update = {
            it.alpha = alpha
            val loadKey = listOf(
                state.coverUrl.orEmpty(),
                state.sourceOrigin.orEmpty(),
                AppConfig.useDefaultCover.toString(),
                state.coverForcePath.toString()
            ).joinToString("|")
            if (it.tag != loadKey) {
                it.tag = loadKey
                BookCover.loadBlur(
                    context = it.context,
                    path = state.coverUrl,
                    loadOnlyWifi = false,
                    sourceOrigin = state.sourceOrigin,
                    forcePath = state.coverForcePath
                ).into(it)
            }
        },
        onRelease = { it.releaseComposeImage() }
    )
}

@Composable
internal fun ReadAloudCapsule(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    capsulePosition: CapsulePositionState,
    onPositionChange: (Float, Float) -> Unit,
    onBounds: (RectF) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val capsuleHeight = READ_ALOUD_CAPSULE_HEIGHT_DP.dp
        val capsuleWidth = READ_ALOUD_CAPSULE_WIDTH_DP.dp
        val capsuleWidthPx = with(density) { capsuleWidth.toPx() }
        val capsuleHeightPx = with(density) { capsuleHeight.toPx() }
        val sidePx = with(density) { 18.dp.toPx() }
        val bottomGapPx = with(density) { 28.dp.toPx() }
        val avoidGapPx = with(density) { 12.dp.toPx() }
        val safeInsets = WindowInsets.safeDrawing
        val safeLeft = safeInsets.getLeft(density, layoutDirection).toFloat()
        val safeRight = safeInsets.getRight(density, layoutDirection).toFloat()
        val safeTop = safeInsets.getTop(density).toFloat()
        val safeBottom = safeInsets.getBottom(density).toFloat()
        val minX = safeLeft + sidePx
        val maxX = (widthPx - safeRight - capsuleWidthPx - sidePx).coerceAtLeast(minX)
        val minY = safeTop + sidePx
        val maxY = (heightPx - safeBottom - capsuleHeightPx - bottomGapPx).coerceAtLeast(minY)
        var baseOffsetX by remember { mutableStateOf(capsulePosition.x ?: minX) }
        var baseOffsetY by remember { mutableStateOf(capsulePosition.y ?: maxY) }
        var dragging by remember { mutableStateOf(false) }
        val coverRotation = remember { Animatable(0f) }
        val rotating = state.playing && state.foregroundActive && !AppConfig.isEInkMode
        val clampedBaseX = baseOffsetX.coerceIn(minX, maxX)
        val clampedBaseY = baseOffsetY.coerceIn(minY, maxY)
        val menuAvoidBounds = state.readMenuAvoidBounds
        val displayOffsetY = if (dragging) {
            clampedBaseY
        } else {
            menuAvoidBounds?.let { bounds ->
                val capsuleLeft = clampedBaseX
                val capsuleRight = clampedBaseX + capsuleWidthPx
                val capsuleTop = clampedBaseY
                val capsuleBottom = clampedBaseY + capsuleHeightPx
                val horizontallyOverlapped = capsuleRight > bounds.left && capsuleLeft < bounds.right
                val verticallyOverlapped = capsuleBottom + avoidGapPx > bounds.top && capsuleTop < bounds.bottom
                if (horizontallyOverlapped && verticallyOverlapped) {
                    (bounds.top - capsuleHeightPx - avoidGapPx).coerceIn(minY, maxY)
                } else {
                    clampedBaseY
                }
            } ?: clampedBaseY
        }
        val animatedX by animateFloatAsState(
            targetValue = clampedBaseX,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleX"
        )
        val animatedY by animateFloatAsState(
            targetValue = displayOffsetY,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleY"
        )
        val renderedX = if (dragging) clampedBaseX else animatedX
        val renderedY = if (dragging) displayOffsetY else animatedY
        val latestClampedBaseX by rememberUpdatedState(clampedBaseX)
        val latestDisplayOffsetY by rememberUpdatedState(displayOffsetY)
        LaunchedEffect(widthPx, heightPx, minX, maxX, minY, maxY) {
            baseOffsetX = if (baseOffsetX + capsuleWidthPx / 2f < widthPx / 2f) minX else maxX
            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
            onPositionChange(baseOffsetX, baseOffsetY)
        }
        LaunchedEffect(capsulePosition.x, capsulePosition.y) {
            if (!dragging) {
                capsulePosition.x?.let { baseOffsetX = it }
                capsulePosition.y?.let { baseOffsetY = it }
            }
        }
        LaunchedEffect(rotating) {
            if (rotating) {
                while (true) {
                    val start = coverRotation.value % 360f
                    coverRotation.snapTo(start)
                    coverRotation.animateTo(
                        targetValue = start + 360f,
                        animationSpec = tween(durationMillis = 16000, easing = LinearEasing)
                    )
                }
            }
        }
        ReadAloudCapsuleSurface(
            state = state,
            colors = colors,
            onPlayPause = onPlayPause,
            onExpand = onExpand,
            onClose = onClose,
            modifier = Modifier
                .offset { IntOffset(renderedX.roundToInt(), renderedY.roundToInt()) }
                .width(capsuleWidth)
                .height(capsuleHeight)
                .onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    onBounds(RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
                }
                .pointerInput(widthPx, heightPx, minX, maxX, minY, maxY) {
                    detectDragGestures(
                        onDragStart = {
                            baseOffsetX = latestClampedBaseX
                            baseOffsetY = latestDisplayOffsetY
                            dragging = true
                            onPositionChange(baseOffsetX, baseOffsetY)
                        },
                        onDragEnd = {
                            dragging = false
                            baseOffsetX = if (baseOffsetX + capsuleWidthPx / 2f < widthPx / 2f) {
                                minX
                            } else {
                                maxX
                            }
                            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
                            onPositionChange(baseOffsetX, baseOffsetY)
                        },
                        onDragCancel = {
                            dragging = false
                            baseOffsetX = baseOffsetX.coerceIn(minX, maxX)
                            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
                            onPositionChange(baseOffsetX, baseOffsetY)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        baseOffsetX = (baseOffsetX + dragAmount.x).coerceIn(minX, maxX)
                        baseOffsetY = (baseOffsetY + dragAmount.y).coerceIn(minY, maxY)
                    }
                },
            coverRotation = coverRotation.value
        )
    }
}

@Composable
internal fun ReadAloudCapsuleSurface(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onCoverLongPress: (() -> Unit)? = null,
    onClose: () -> Unit,
    coverOnEnd: Boolean = false,
    modifier: Modifier = Modifier,
    coverRotation: Float = 0f,
    horizontalPadding: Dp = 6.dp,
    shadowElevation: Dp = 12.dp
) {
    val coverButtonSize = 42.dp
    val playButtonSize = 38.dp
    val closeButtonSize = 34.dp
    val coverButton: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(coverButtonSize)
                .graphicsLayer { rotationZ = coverRotation % 360f }
                .clip(CircleShape)
                .background(colors.panel)
                .pointerInput(onExpand, onCoverLongPress) {
                    detectTapGestures(
                        onTap = { onExpand() },
                        onLongPress = { onCoverLongPress?.invoke() }
                    )
                }
        ) {
            BookCoverImage(
                path = state.coverUrl,
                name = state.bookName,
                author = state.author,
                sourceOrigin = state.sourceOrigin,
                modifier = Modifier.fillMaxSize(),
                style = CoverImageView.CoverStyle.FLAT,
                loadOnlyWifi = false,
                preferThumb = true,
                forcePath = state.coverForcePath,
                allowNameOverlay = state.coverAllowNameOverlay,
                fillBounds = true
            )
        }
    }
    val playButton: @Composable () -> Unit = {
        Surface(
            onClick = onPlayPause,
            modifier = Modifier.size(playButtonSize),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.92f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.playbackBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black.copy(alpha = 0.72f),
                        trackColor = Color.Black.copy(alpha = 0.12f)
                    )
                } else {
                    Icon(
                        painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.86f),
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
    val closeButton: @Composable () -> Unit = {
        Surface(
            onClick = onClose,
            modifier = Modifier.size(closeButtonSize),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.12f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_x),
                    contentDescription = null,
                    tint = colors.primaryText,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.panelStrong,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = shadowElevation
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (coverOnEnd) {
                closeButton()
                playButton()
                coverButton()
            } else {
                coverButton()
                playButton()
                closeButton()
            }
        }
    }
}

@Composable
private fun FluidBackdropLayer(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors
) {
    val visualActive = state.foregroundActive && state.playing
    val shift = if (visualActive) {
        val transition = rememberInfiniteTransition(label = "readAloudFluid")
        val value by transition.animateFloat(
            initialValue = -0.10f,
            targetValue = 0.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 9200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "fluidShift"
        )
        value
    } else {
        0f
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    colors.fluidC.copy(alpha = 0.28f),
                    Color.Transparent,
                    colors.fluidB.copy(alpha = 0.22f)
                ),
                start = Offset(size.width * (0.05f + shift), 0f),
                end = Offset(size.width, size.height)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidA.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(size.width * (0.20f + shift), size.height * 0.06f),
                radius = size.maxDimension * 0.70f
            ),
            radius = size.maxDimension * 0.70f,
            center = Offset(size.width * (0.20f + shift), size.height * 0.06f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidB.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(size.width * (0.78f - shift), size.height * 0.50f),
                radius = size.maxDimension * 0.58f
            ),
            radius = size.maxDimension * 0.58f,
            center = Offset(size.width * (0.78f - shift), size.height * 0.50f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidC.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * (0.34f + shift * 0.5f), size.height * 0.98f),
                radius = size.maxDimension * 0.62f
            ),
            radius = size.maxDimension * 0.62f,
            center = Offset(size.width * (0.34f + shift * 0.5f), size.height * 0.98f)
        )
    }
}

@Composable
private fun MinimalHeader(
    mode: ReadAloudPlayerPanel.DisplayMode,
    multiRoleEnabled: Boolean,
    colors: PlayerColors,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            HeaderIconButton(
                icon = R.drawable.ic_expand_more,
                contentDescription = appCtx.getString(R.string.collapse),
                colors = colors,
                onClick = onClose
            )
        }
        Box(contentAlignment = Alignment.Center) {
            ModeSwitch(mode, multiRoleEnabled, colors, onModeChange)
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderIconButton(
                icon = R.drawable.ic_settings,
                contentDescription = appCtx.getString(R.string.setting),
                colors = colors,
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun SceneFullscreenTopBar(
    title: String,
    colors: PlayerColors,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIconButton(
            icon = R.drawable.ic_expand_more,
            contentDescription = appCtx.getString(R.string.ra_panel_exit_full_screen),
            colors = colors,
            onClick = onExit
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.ifBlank { appCtx.getString(R.string.ra_panel_current_chapter) },
                color = colors.primaryText.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.size(42.dp))
    }
}

@Composable
internal fun PlayerBackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    val activity = LocalContext.current as? AppCompatActivity ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        val callback = object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() {
                currentOnBack()
            }
        }
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose {
            callback.remove()
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: Int,
    contentDescription: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = colors.primaryText,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun ModeSwitch(
    mode: ReadAloudPlayerPanel.DisplayMode,
    multiRoleEnabled: Boolean,
    colors: PlayerColors,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    val modes = remember(multiRoleEnabled) {
        buildList {
            add(ReadAloudPlayerPanel.DisplayMode.Immersive to appCtx.getString(R.string.book_info_style_immersive))
            if (multiRoleEnabled) add(ReadAloudPlayerPanel.DisplayMode.Scene to appCtx.getString(R.string.ra_panel_scene))
            add(ReadAloudPlayerPanel.DisplayMode.Text to appCtx.getString(R.string.ra_panel_original_text))
        }
    }
    Row(
        modifier = Modifier
            .width(if (multiRoleEnabled) 188.dp else 132.dp)
            .height(32.dp)
            .clip(actionShape)
            .background(Color.White.copy(alpha = 0.13f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        modes.forEach { (displayMode, text) ->
            ModeChip(
                text = text,
                selected = mode == displayMode,
                fullscreenHint = mode == ReadAloudPlayerPanel.DisplayMode.Scene &&
                        displayMode == ReadAloudPlayerPanel.DisplayMode.Scene,
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f)
            ) {
                onModeChange(displayMode)
            }
        }
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    fullscreenHint: Boolean,
    colors: PlayerColors,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = if (selected) colors.accentText else colors.secondaryText,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
            if (fullscreenHint) {
                Text(
                    text = appCtx.getString(R.string.full_screen),
                    color = colors.accentText.copy(alpha = 0.78f),
                    fontSize = 8.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun PortraitPlayerBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    veryShort: Boolean,
    sceneFullscreen: Boolean,
    sceneFullscreenTopInset: Dp,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.roleBlockingCurrentContent) {
        RoleAssigningBody(state = state, colors = colors, modifier = modifier)
        return
    }
    when (state.mode) {
        ReadAloudPlayerPanel.DisplayMode.Immersive -> ImmersivePlayerStage(
            state = state,
            colors = colors,
            short = short,
            veryShort = veryShort,
            animateTextChanges = animateTextChanges,
            modifier = modifier
        )

        ReadAloudPlayerPanel.DisplayMode.Scene -> ScenePlayerStage(
            state = state,
            colors = colors,
            compact = short,
            fullscreen = sceneFullscreen,
            fullscreenTopInset = sceneFullscreenTopInset,
            onCueSelect = onCueSelect,
            modifier = modifier
        )

        ReadAloudPlayerPanel.DisplayMode.Text -> LyricsPlayerStage(
            state = state,
            colors = colors,
            compact = short,
            maxParagraphs = if (veryShort) 5 else 7,
            currentMaxLines = if (veryShort) 4 else 6,
            animateTextChanges = animateTextChanges,
            onCueSelect = onCueSelect,
            modifier = modifier
        )
    }
}

@Composable
private fun LandscapePlayerBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    sceneFullscreen: Boolean,
    sceneFullscreenTopInset: Dp,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.roleBlockingCurrentContent) {
        RoleAssigningBody(state = state, colors = colors, modifier = modifier)
        return
    }
    val immersive = state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (immersive) {
            CoverArt(
                state = state,
                colors = colors,
                width = if (short) 136.dp else 176.dp
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            BookIdentity(
                state = state,
                colors = colors,
                centered = false,
                compact = short
            )
            Spacer(modifier = Modifier.height(if (short) 12.dp else 18.dp))
            if (state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive) {
                FocusSentenceBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    animateTextChanges = animateTextChanges,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (state.mode == ReadAloudPlayerPanel.DisplayMode.Scene) {
                ScenePlayerStage(
                    state = state,
                    colors = colors,
                    compact = short,
                    fullscreen = sceneFullscreen,
                    fullscreenTopInset = sceneFullscreenTopInset,
                    onCueSelect = onCueSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                )
            } else {
                LyricCueBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    maxParagraphs = if (short) 5 else 7,
                    currentMaxLines = if (short) 4 else 6,
                    textAlign = TextAlign.Start,
                    animateTextChanges = animateTextChanges,
                    onCueSelect = onCueSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun RoleAssigningBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = colors.accent,
                trackColor = Color.Transparent
            )
            Text(
                text = state.roleBlockingText.ifBlank { appCtx.getString(R.string.ra_panel_assigning_roles_current_chapter) },
                color = colors.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = appCtx.getString(R.string.ra_panel_show_chapter_content_after_assignment),
                color = colors.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ImmersivePlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    veryShort: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CoverArt(
            state = state,
            colors = colors,
            width = when {
                veryShort -> 128.dp
                short -> 164.dp
                else -> 210.dp
            }
        )
        Spacer(modifier = Modifier.height(if (veryShort) 14.dp else 22.dp))
        BookIdentity(
            state = state,
            colors = colors,
            centered = true,
            compact = veryShort
        )
        Spacer(modifier = Modifier.height(if (veryShort) 12.dp else 22.dp))
        FocusSentenceBody(
            state = state,
            colors = colors,
            compact = short,
            animateTextChanges = animateTextChanges,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (veryShort) 84.dp else 118.dp)
        )
    }
}

@Composable
private fun LyricsPlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BookIdentity(
            state = state,
            colors = colors,
            centered = true,
            compact = compact
        )
        Spacer(modifier = Modifier.height(if (compact) 12.dp else 18.dp))
        LyricCueBody(
            state = state,
            colors = colors,
            compact = compact,
            maxParagraphs = maxParagraphs,
            currentMaxLines = currentMaxLines,
            animateTextChanges = animateTextChanges,
            onCueSelect = onCueSelect,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun ScenePlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    fullscreen: Boolean = false,
    fullscreenTopInset: Dp = 0.dp,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = !fullscreen,
            enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                    expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = shrinkVertically(tween(150, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(120, easing = FastOutSlowInEasing))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BookIdentity(
                    state = state,
                    colors = colors,
                    centered = true,
                    compact = compact
                )
                Spacer(modifier = Modifier.height(if (compact) 10.dp else 16.dp))
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            val listState = rememberLazyListState()
            val density = LocalDensity.current
            val currentIndex = state.currentCueIndex.coerceIn(
                0,
                state.sceneSegments.lastIndex.coerceAtLeast(0)
            )
            val viewportTopInset = if (fullscreen) fullscreenTopInset.coerceAtLeast(58.dp) else 0.dp
            val visibleHeight = (maxHeight - viewportTopInset).coerceAtLeast(1.dp)
            val listTopPadding = 12.dp
            val listBottomPadding = if (fullscreen) 96.dp else 12.dp
            val currentScrollOffset = if (fullscreen) {
                with(density) {
                    -((visibleHeight * 0.5f) - if (compact) 30.dp else 36.dp)
                        .coerceAtLeast(0.dp)
                        .roundToPx()
                }
            } else {
                0
            }
            var programmaticScroll by remember(state.chapterKey) { mutableStateOf(false) }
            var lastSceneTarget by remember(state.chapterKey) { mutableStateOf<Int?>(null) }
            LaunchedEffect(state.chapterKey, fullscreen) {
                lastSceneTarget = null
            }
            LaunchedEffect(state.chapterKey, currentIndex, fullscreen) {
                val userScrolling = listState.isScrollInProgress && !programmaticScroll
                if (state.sceneSegments.isNotEmpty() && !userScrolling) {
                    val firstTarget = lastSceneTarget == null
                    val targetDistance = lastSceneTarget
                        ?.let { kotlin.math.abs(it - currentIndex) }
                        ?: 0
                    try {
                        programmaticScroll = true
                        if (firstTarget) {
                            listState.scrollToItem(currentIndex, scrollOffset = currentScrollOffset)
                        } else if (lastSceneTarget != currentIndex) {
                            if (AppConfig.isEInkMode || !state.foregroundActive || targetDistance > 8) {
                                listState.scrollToItem(currentIndex, scrollOffset = currentScrollOffset)
                            } else {
                                listState.animateScrollToItem(currentIndex, scrollOffset = currentScrollOffset)
                            }
                        }
                    } finally {
                        programmaticScroll = false
                    }
                    lastSceneTarget = currentIndex
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = viewportTopInset)
                    .clipToBounds(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = if (fullscreen) 780.dp else 760.dp),
                    contentPadding = PaddingValues(
                        top = listTopPadding,
                        bottom = listBottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        when {
                            fullscreen -> 14.dp
                            compact -> 9.dp
                            else -> 12.dp
                        }
                    )
                ) {
                    items(state.sceneSegments, key = { it.key }) { segment ->
                        SceneSegmentRow(
                            segment = segment,
                            current = segment.index == currentIndex,
                            colors = colors,
                            compact = compact,
                            fullscreen = fullscreen,
                            onClick = {
                                onCueSelect(segment.chapterPosition)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneSegmentRow(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    fullscreen: Boolean,
    onClick: () -> Unit
) {
    if (segment.narrator) {
        val interactionSource = remember { MutableInteractionSource() }
        val maxWidth = if (fullscreen) 560.dp else 430.dp
        val textColor = when {
            current && fullscreen -> colors.primaryText.copy(alpha = 0.78f)
            current -> colors.secondaryText.copy(alpha = 0.86f)
            fullscreen -> colors.subtleText.copy(alpha = 0.74f)
            else -> colors.subtleText
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = segment.text,
                color = textColor,
                fontSize = when {
                    compact -> 12.sp
                    fullscreen -> 14.sp
                    else -> 13.sp
                },
                lineHeight = when {
                    compact -> 18.sp
                    fullscreen -> 23.sp
                    else -> 20.sp
                },
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                    .padding(
                        horizontal = if (fullscreen) 20.dp else 14.dp,
                        vertical = if (fullscreen) 9.dp else 6.dp
                    )
            )
        }
        return
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth(if (fullscreen) 0.88f else 0.86f)
                .align(if (segment.leftSide) Alignment.CenterStart else Alignment.CenterEnd),
            horizontalArrangement = if (segment.leftSide) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            if (segment.leftSide) {
                SceneAvatar(segment, colors, compact)
                Spacer(modifier = Modifier.width(6.dp))
                SceneBubbleArrow(leftSide = true, current = current, colors = colors)
                SceneBubble(
                    segment = segment,
                    current = current,
                    colors = colors,
                    compact = compact,
                    fullscreen = fullscreen,
                    onClick = onClick,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                SceneBubble(
                    segment = segment,
                    current = current,
                    colors = colors,
                    compact = compact,
                    fullscreen = fullscreen,
                    onClick = onClick,
                    modifier = Modifier.weight(1f, fill = false)
                )
                SceneBubbleArrow(leftSide = false, current = current, colors = colors)
                Spacer(modifier = Modifier.width(6.dp))
                SceneAvatar(segment, colors, compact)
            }
        }
    }
}

@Composable
private fun SceneBubbleArrow(
    leftSide: Boolean,
    current: Boolean,
    colors: PlayerColors
) {
    val arrowColor = sceneBubbleFillColor(
        leftSide = leftSide,
        current = current,
        colors = colors
    )
    Canvas(
        modifier = Modifier
            .padding(top = 14.dp)
            .size(width = 8.dp, height = 14.dp)
    ) {
        val path = androidx.compose.ui.graphics.Path().apply {
            if (leftSide) {
                moveTo(size.width, 0f)
                lineTo(0f, size.height / 2f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
            }
            close()
        }
        drawPath(path, arrowColor)
    }
}

@Composable
private fun SceneBubble(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    fullscreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = sceneBubbleFillColor(segment.leftSide, current, colors)
    val textColor = if (segment.leftSide) colors.primaryText else colors.accentText
    val actionShape = LocalContext.current.composeActionShape()
    val maxWidth = when {
        fullscreen -> 540.dp
        compact -> 460.dp
        else -> 520.dp
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .shadow(
                elevation = if (current) 8.dp else 4.dp,
                shape = actionShape,
                clip = false
            )
    ) {
        Column(
            modifier = Modifier
                .clip(actionShape)
                .background(bubbleColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(
                    horizontal = if (fullscreen) 15.dp else 13.dp,
                    vertical = if (fullscreen) 11.dp else 10.dp
                ),
            horizontalAlignment = if (segment.leftSide) Alignment.Start else Alignment.End
        ) {
            val emotion = segment.emotionName.takeIf { it.isNotBlank() }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!segment.leftSide) {
                    emotion?.let {
                        Text(
                            text = it,
                            color = textColor.copy(alpha = 0.70f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                Text(
                    text = segment.characterName,
                    color = textColor.copy(alpha = 0.86f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (segment.leftSide) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.widthIn(max = if (compact) 230.dp else 280.dp)
                )
                if (segment.leftSide) {
                    emotion?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = it,
                            color = textColor.copy(alpha = 0.70f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                    }
                }
            }
            Text(
                text = segment.text,
                color = textColor,
                fontSize = when {
                    compact -> 14.sp
                    fullscreen -> 16.sp
                    else -> 15.sp
                },
                lineHeight = when {
                    compact -> 21.sp
                    fullscreen -> 24.sp
                    else -> 23.sp
                },
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

private fun sceneBubbleFillColor(
    leftSide: Boolean,
    current: Boolean,
    colors: PlayerColors
): Color {
    val base = if (leftSide) {
        colors.panelStrong
    } else {
        val target = if (colors.night) colors.panelStrong else colors.panel
        Color(ColorUtils.blendColors(colors.accent.toArgb(), target.toArgb(), 0.10f))
    }.copy(alpha = 1f)
    if (current) return base
    val mutedTarget = if (colors.night) colors.panel else Color.White
    return Color(ColorUtils.blendColors(base.toArgb(), mutedTarget.toArgb(), 0.18f))
}

@Composable
private fun SceneAvatar(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    colors: PlayerColors,
    compact: Boolean
) {
    val context = LocalContext.current
    val size = if (compact) 34.dp else 38.dp
    val avatarPath = segment.avatar.ifBlank { null }
    val bitmap by produceState<Bitmap?>(initialValue = null, avatarPath, size) {
        value = null
        if (avatarPath.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val px = (size.value * context.resources.displayMetrics.density)
                    .roundToInt()
                    .coerceAtLeast(1)
                ImageLoader.loadBitmap(context.applicationContext, avatarPath)
                    .centerCrop()
                    .submit(px, px)
                    .get()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.panel),
        contentAlignment = Alignment.Center
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = segment.characterName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val initial = segment.characterName
                .trim()
                .firstOrNull()
                ?.toString()
                .orEmpty()
            if (initial.isNotBlank()) {
                Text(
                    text = initial,
                    color = colors.primaryText.copy(alpha = 0.82f),
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            } else {
                Icon(
                    // ic_bottom_person 是双态 selector：painterResource 只支持 vector/位图
                    painter = painterResource(R.drawable.ic_bottom_person_e),
                    contentDescription = segment.characterName,
                    tint = colors.subtleText,
                    modifier = Modifier.size(if (compact) 19.dp else 21.dp)
                )
            }
        }
    }
}

@Composable
private fun CoverArt(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    width: Dp,
    modifier: Modifier = Modifier
) {
    BookCoverImage(
        path = state.coverUrl,
        name = state.bookName,
        author = state.author,
        sourceOrigin = state.sourceOrigin,
        modifier = modifier
            .width(width)
            .aspectRatio(0.75f),
        style = CoverImageView.CoverStyle.DETAIL,
        loadOnlyWifi = false,
        preferThumb = false,
        forcePath = state.coverForcePath,
        allowNameOverlay = state.coverAllowNameOverlay,
        fillBounds = true
    )
}

@Composable
private fun BookIdentity(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    centered: Boolean,
    compact: Boolean
) {
    Column(
        modifier = Modifier.widthIn(max = 560.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            text = state.bookName.ifBlank { appCtx.getString(R.string.paragraph_rule_current_book) },
            color = colors.primaryText,
            fontSize = if (compact) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = state.chapterTitle.ifBlank { appCtx.getString(R.string.ra_panel_current_chapter) },
            color = colors.secondaryText,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun FocusSentenceBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val focus = state.focusText.takeIf { it.text.isNotBlank() }
        ?: ReadAloudPlayerPanel.FocusTextUi(
            key = state.paragraphKey,
            sequence = state.paragraphSequence,
            text = state.paragraphText.ifBlank { appCtx.getString(R.string.ra_panel_no_current_paragraph) }
        )
    AnimatedContent(
        targetState = focus,
        transitionSpec = {
            val direction = if (targetState.sequence >= initialState.sequence) 1 else -1
            if (animateTextChanges) {
                ((slideInVertically(tween(320)) { height -> height * direction / 3 } +
                        fadeIn(tween(220)) +
                        scaleIn(tween(320), initialScale = 0.98f)) togetherWith
                        (slideOutVertically(tween(240)) { height -> -height * direction / 4 } +
                                fadeOut(tween(160)) +
                                scaleOut(tween(240), targetScale = 1.02f)))
                    .using(SizeTransform(clip = false))
            } else {
                (fadeIn(tween(1)) togetherWith fadeOut(tween(1)))
                    .using(SizeTransform(clip = false))
            }
        },
        modifier = modifier,
        label = "readAloudFocusText"
    ) { target ->
        Text(
            text = target.text,
            color = colors.primaryText,
            fontSize = if (compact) 24.sp else 28.sp,
            lineHeight = if (compact) 33.sp else 38.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (compact) 3 else 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LyricCueBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val cues = state.textCues.ifEmpty {
        listOf(
            ReadAloudPlayerPanel.TextCueUi(
                index = state.paragraphIndex.coerceAtLeast(1),
                text = state.paragraphText.ifBlank { appCtx.getString(R.string.ra_panel_no_current_paragraph) },
                current = true,
                key = state.paragraphKey.ifBlank { state.paragraphIndex.toString() },
                sequence = state.paragraphSequence,
                chapterPosition = ReadBook.durChapterPos
            )
        )
    }
    val listState = rememberLazyListState()
    var userTouching by remember(state.chapterKey) { mutableStateOf(false) }
    var programmaticScroll by remember(state.chapterKey) { mutableStateOf(false) }
    var lastCenteredTarget by remember(state.chapterKey) { mutableStateOf<Int?>(null) }
    val currentIndex = state.currentCueIndex.coerceIn(0, cues.lastIndex)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 720.dp)
    ) {
        val centerPadding = (maxHeight / 2)
            .coerceAtLeast(if (compact) 42.dp else 58.dp)

        suspend fun centerCue(index: Int, animated: Boolean) {
            if (cues.isEmpty()) return
            val targetIndex = index.coerceIn(0, cues.lastIndex)
            var targetItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }
            if (targetItem == null) {
                listState.scrollToItem(targetIndex, scrollOffset = 0)
                targetItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == targetIndex }
            }
            targetItem?.let { item ->
                val scrollDelta = ReadAloudPanelLayout.centeredScrollDelta(
                    viewportStartOffset = listState.layoutInfo.viewportStartOffset,
                    viewportEndOffset = listState.layoutInfo.viewportEndOffset,
                    itemOffset = item.offset,
                    itemSize = item.size
                )
                if (kotlin.math.abs(scrollDelta) >= 1f) {
                    if (animated) {
                        listState.animateScrollBy(scrollDelta)
                    } else {
                        listState.scrollBy(scrollDelta)
                    }
                }
            }
        }

        LaunchedEffect(state.chapterKey) {
            userTouching = false
            lastCenteredTarget = null
        }
        LaunchedEffect(state.chapterKey, currentIndex) {
            val userScrolling = listState.isScrollInProgress && !programmaticScroll
            if (!userTouching && !userScrolling && cues.isNotEmpty()) {
                val firstTarget = lastCenteredTarget == null
                try {
                    programmaticScroll = true
                    centerCue(
                        index = currentIndex,
                        animated = !firstTarget && !AppConfig.isEInkMode && state.foregroundActive
                    )
                } finally {
                    programmaticScroll = false
                }
                lastCenteredTarget = currentIndex
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.chapterKey) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            userTouching = event.changes.any { it.pressed }
                        }
                    }
                },
            contentPadding = PaddingValues(vertical = centerPadding),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            horizontalAlignment = when (textAlign) {
                TextAlign.Start -> Alignment.Start
                TextAlign.End -> Alignment.End
                else -> Alignment.CenterHorizontally
            }
        ) {
            itemsIndexed(cues, key = { _, cue -> cue.key }) { _, cue ->
                LyricCueLine(
                    cue = cue,
                    current = cue.index - 1 == currentIndex,
                    colors = colors,
                    compact = compact,
                    currentMaxLines = currentMaxLines,
                    textAlign = textAlign,
                    animate = animateTextChanges,
                    onClick = {
                        userTouching = false
                        onCueSelect(cue.chapterPosition)
                    }
                )
            }
        }
    }
}

@Composable
private fun LyricCueLine(
    cue: ReadAloudPlayerPanel.TextCueUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    currentMaxLines: Int,
    textAlign: TextAlign,
    animate: Boolean,
    onClick: () -> Unit
) {
    val emphasis by animateFloatAsState(
        targetValue = if (current) 1f else 0f,
        animationSpec = tween(if (animate) 220 else 1),
        label = "readAloudCueEmphasis"
    )
    val fontSize = if (compact) 20.sp else 23.sp
    val lineHeight = if (compact) 28.sp else 32.sp
    Text(
        text = cue.text,
        color = colors.primaryText.copy(alpha = 0.36f + emphasis * 0.58f),
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = if (current) currentMaxLines else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

@Composable
private fun LyricParagraphLine(
    paragraph: ReadAloudPlayerPanel.ParagraphUi,
    colors: PlayerColors,
    compact: Boolean,
    currentMaxLines: Int,
    textAlign: TextAlign,
    animate: Boolean
) {
    val emphasis by animateFloatAsState(
        targetValue = if (paragraph.current) 1f else 0f,
        animationSpec = tween(if (animate) 220 else 1),
        label = "readAloudLyricEmphasis"
    )
    val fontSize = when {
        compact -> 14f + emphasis * 6f
        else -> 15f + emphasis * 8f
    }
    val lineHeight = when {
        compact -> 21f + emphasis * 7f
        else -> 23f + emphasis * 9f
    }
    Text(
        text = paragraph.text,
        color = colors.primaryText.copy(alpha = 0.36f + emphasis * 0.58f),
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        fontWeight = if (paragraph.current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = if (paragraph.current) currentMaxLines else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MinimalProgress(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onProgressSeek: (Float) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    var draggingProgress by remember(state.chapterKey) { mutableStateOf<Float?>(null) }
    fun snapProgress(value: Float): Float {
        val maxIndex = state.paragraphCount - 1
        if (maxIndex <= 0) return 0f
        return ((value.coerceIn(0f, 1f) * maxIndex).roundToInt() / maxIndex.toFloat())
            .coerceIn(0f, 1f)
    }
    val progress = draggingProgress ?: state.progress
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
    ) {
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { draggingProgress = snapProgress(it) },
            onValueChangeFinished = {
                draggingProgress?.let(onProgressSeek)
                draggingProgress = null
            },
            enabled = state.paragraphCount > 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(actionShape),
            colors = SliderDefaults.colors(
                thumbColor = colors.primaryText,
                activeTrackColor = colors.primaryText,
                inactiveTrackColor = colors.panel,
                disabledThumbColor = colors.subtleText,
                disabledActiveTrackColor = colors.panel,
                disabledInactiveTrackColor = colors.panel
            )
        )
        if (false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.chapterIndexText.ifBlank { appCtx.getString(R.string.manga_check_chapter) },
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = state.progressText,
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        }
    }
}

@Composable
private fun PlayerControlDock(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    activeSheet: PlayerSheet,
    onSheetChange: (PlayerSheet) -> Unit,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapterList: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 300.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundTransportButton(
                icon = R.drawable.ic_skip_previous,
                contentDescription = appCtx.getString(R.string.previous_chapter),
                colors = colors,
                size = 50.dp,
                iconSize = 25.dp,
                onClick = onPreviousChapter
            )
            Surface(
                onClick = onPlayPause,
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 16.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.playbackBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = Color.Black.copy(alpha = 0.72f),
                            trackColor = Color.Black.copy(alpha = 0.12f)
                        )
                    } else {
                        Icon(
                            painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                            contentDescription = if (state.playing) context.getString(R.string.pause) else context.getString(R.string.audio_play),
                            tint = Color.Black.copy(alpha = 0.88f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            RoundTransportButton(
                icon = R.drawable.ic_skip_next,
                contentDescription = appCtx.getString(R.string.next_chapter),
                colors = colors,
                size = 50.dp,
                iconSize = 25.dp,
                onClick = onNextChapter
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FeaturePill(
                icon = R.drawable.ic_toc,
                text = context.getString(R.string.chapter_list),
                selected = activeSheet == PlayerSheet.Chapter,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Chapter)
            }
            FeaturePill(
                icon = R.drawable.ic_time_add_24dp,
                text = if (state.timerMinute > 0) context.getString(R.string.timer_m, state.timerMinute)
                else context.getString(R.string.set_timer),
                selected = activeSheet == PlayerSheet.Timer,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Timer)
            }
            FeaturePill(
                icon = R.drawable.ic_settings,
                text = "\u5f15\u64ce",
                selected = activeSheet == PlayerSheet.Engine,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Engine)
            }
            FeaturePill(
                icon = R.drawable.ic_bottom_person_e,
                selectedIcon = R.drawable.ic_bottom_person_s,
                text = "\u89d2\u8272",
                selected = activeSheet == PlayerSheet.Characters,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Characters)
            }
        }
    }
}

@Composable
private fun RoundTransportButton(
    icon: Int,
    contentDescription: String,
    colors: PlayerColors,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = colors.primaryText,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun FeaturePill(
    icon: Int,
    selectedIcon: Int = icon,
    text: String,
    selected: Boolean,
    colors: PlayerColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        shape = actionShape,
        color = if (selected) colors.accent else colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = if (selected) 8.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(if (selected) selectedIcon else icon),
                contentDescription = text,
                tint = if (selected) colors.accentText else colors.primaryText,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                color = if (selected) colors.accentText else colors.primaryText,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlayerSheetPanel(
    sheet: PlayerSheet,
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onTimerChange: (Int) -> Unit,
    onEngineSelect: (String) -> Unit,
    onOpenCharacters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val panelShape = LocalContext.current.composePanelShape()
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = panelShape,
        color = colors.panelStrong,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = 12.dp
    ) {
        Box(
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
        ) {
            AnimatedContent(
                targetState = sheet,
                transitionSpec = {
                    (fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                            slideInVertically(tween(180, easing = FastOutSlowInEasing)) { it / 10 })
                        .togetherWith(
                            fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                                    slideOutVertically(tween(140, easing = FastOutSlowInEasing)) { -it / 12 }
                        )
                        .using(SizeTransform(clip = false))
                },
                label = "readAloudSheetContent"
            ) { targetSheet ->
                when (targetSheet) {
                    PlayerSheet.Chapter -> ChapterSheet(
                        state = state,
                        colors = colors,
                        onOpenChapterList = onOpenChapterList,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onChapterSelect = onChapterSelect
                    )
                    PlayerSheet.Timer -> TimerSheet(state, colors, onTimerChange)
                    PlayerSheet.Engine -> EngineSheet(state, colors, onEngineSelect)
                    PlayerSheet.Characters -> CharactersSheet(state, colors, onOpenCharacters)
                    PlayerSheet.None -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChapterSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    val chapterCount = state.chapterCount.coerceAtLeast(1)
    val chapters = state.chapterPreview
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = context.getString(R.string.chapter_list),
                    color = colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.chapterTitle.ifBlank { state.chapterIndexText },
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = "${state.chapterIndex.coerceIn(0, chapterCount - 1) + 1}/$chapterCount",
                color = colors.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 188.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = chapters,
                key = { it.key }
            ) { chapter ->
                ChapterPreviewRow(
                    chapter = chapter,
                    colors = colors,
                    shape = actionShape,
                    onClick = { onChapterSelect(chapter.index) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetActionButton(
                text = context.getString(R.string.previous_chapter),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onPreviousChapter
            )
            SheetActionButton(
                text = context.getString(R.string.chapter_list),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onOpenChapterList
            )
            SheetActionButton(
                text = context.getString(R.string.next_chapter),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onNextChapter
            )
        }
    }
}

@Composable
private fun ChapterPreviewRow(
    chapter: ReadAloudPlayerPanel.ChapterPreviewUi,
    colors: PlayerColors,
    shape: Shape,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(enabled = !chapter.volume, onClick = onClick),
        shape = shape,
        color = when {
            chapter.current -> colors.accent
            chapter.volume -> colors.panel
            else -> colors.panel
        },
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chapter.indexText,
                color = if (chapter.current) colors.accentText else colors.subtleText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(56.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = chapter.title,
                color = if (chapter.current) colors.accentText else colors.primaryText,
                fontSize = if (chapter.volume) 12.sp else 13.sp,
                fontWeight = if (chapter.current || chapter.volume) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SheetActionButton(
    text: String,
    colors: PlayerColors,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        shape = shape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = colors.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TimerSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onTimerChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    val times = listOf(0, 5, 10, 15, 30, 60, 90, 180)
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = context.getString(R.string.set_timer),
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        times.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { minute ->
                    val selected = state.timerMinute == minute || (state.timerMinute <= 0 && minute == 0)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clickable { onTimerChange(minute) },
                        shape = actionShape,
                        color = if (selected) colors.accent else colors.panel
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = context.getString(R.string.timer_m, minute),
                                color = if (selected) colors.accentText else colors.primaryText,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onEngineSelect: (String) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    var pickerGroupKey by remember(state.ttsEngines) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "\u6717\u8bfb\u5f15\u64ce",
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp, max = 202.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.ttsEngines, key = { it.key }) { engine ->
                EngineRow(
                    engine = engine,
                    colors = colors,
                    shape = actionShape,
                    onClick = { pickerGroupKey = engine.key }
                )
            }
        }
    }
    pickerGroupKey?.let { key ->
        SpeechVoiceRoutePickerDialog(
            title = appCtx.getString(R.string.ra_panel_select_speaker),
            groups = state.ttsEngines.map { it.group },
            currentRoute = state.speechRoute,
            initialGroupKey = key,
            onDismiss = { pickerGroupKey = null },
            onRouteSelected = { route ->
                pickerGroupKey = null
                onEngineSelect(route.toJson())
            }
        )
    }
}

@Composable
private fun EngineRow(
    engine: ReadAloudPlayerPanel.TtsEngineUi,
    colors: PlayerColors,
    shape: Shape,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (engine.selected) colors.accent else colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.title,
                    color = if (engine.selected) colors.accentText else colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = if (engine.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = engine.subtitle,
                    color = if (engine.selected) colors.accentText.copy(alpha = 0.72f) else colors.subtleText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (engine.selected) {
                Text(
                    text = "\u5f53\u524d",
                    color = colors.accentText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CharactersSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenCharacters: () -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u89d2\u8272",
                color = colors.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.characterPreview.size}",
                color = colors.subtleText,
                fontSize = 12.sp
            )
        }
        if (state.characterPreview.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\u6682\u65e0\u89d2\u8272",
                    color = colors.secondaryText,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 188.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.characterPreview, key = { it.key }) { character ->
                    CharacterPreviewRow(character, colors, actionShape)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            SheetActionButton(
                text = "\u5b8c\u6574\u89d2\u8272\u9875",
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onOpenCharacters
            )
        }
    }
}

@Composable
private fun CharacterPreviewRow(
    character: ReadAloudPlayerPanel.CharacterPreviewUi,
    colors: PlayerColors,
    shape: Shape
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = shape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = character.role,
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(66.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    color = colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = character.summary,
                    color = colors.subtleText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
