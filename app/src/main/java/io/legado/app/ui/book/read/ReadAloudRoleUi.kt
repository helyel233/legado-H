package io.legado.app.ui.book.read

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.ai.AiReadAloudRolePreviewSegment
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.model.ReadBook
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import splitties.init.appCtx

@Composable
internal fun RoleAssignmentStatus(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpen: () -> Unit
) {
    AnimatedVisibility(
        visible = state.roleStatusVisible,
        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                expandVertically(tween(180, easing = FastOutSlowInEasing)),
        exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) +
                fadeOut(tween(140, easing = FastOutSlowInEasing))
    ) {
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.roleStatusRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                    trackColor = Color.Transparent
                )
            }
            Text(
                text = state.roleStatusText,
                color = if (state.roleStatusError) Color(0xFFFF8A9A) else colors.primaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun RoleAssignmentProgressDialog(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onEditSegment: (AiReadAloudRolePreviewSegment) -> Unit
) {
    val roleState = state.roleState ?: return
    PlayerBackHandler(enabled = state.roleDetailVisible, onBack = onHide)
    AnimatedVisibility(
        visible = state.roleDetailVisible,
        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.96f),
        exit = scaleOut(tween(150, easing = FastOutSlowInEasing), targetScale = 0.98f) +
                fadeOut(tween(140, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (state.roleStatusRunning) 0.18f else 0.10f))
                .systemBarsPadding()
                .padding(22.dp),
            contentAlignment = Alignment.Center
        ) {
            val shape = LocalContext.current.composePanelShape()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp),
                shape = shape,
                color = colors.panelStrong.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, colors.panelBorder),
                shadowElevation = 14.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                text = appCtx.getString(R.string.ra_panel_multi_role_assignment_details),
                                color = colors.primaryText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = roleState.chapterTitle.ifBlank { state.chapterTitle },
                                color = colors.subtleText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RoleDialogAction(appCtx.getString(R.string.ra_panel_reassign), colors, onRetry)
                            RoleDialogAction(appCtx.getString(R.string.title_hide), colors, onHide)
                            if (!state.roleStatusRunning) RoleDialogAction(appCtx.getString(R.string.jf_convert_o), colors, onDismiss)
                        }
                    }
                    RoleAssignmentSummary(state, roleState, colors)
                    AudioAssignmentSummary(state.audioInfo, colors)
                    RolePreviewList(
                        state = state,
                        roleState = roleState,
                        colors = colors,
                        onEditSegment = onEditSegment,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleDialogAction(
    text: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = colors.panel.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Text(
            text = text,
            color = colors.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun AudioAssignmentSummary(
    audioInfo: ReadAloudPlayerPanel.AudioInfoUi,
    colors: PlayerColors
) {
    if (!audioInfo.enabled) return
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            text = appCtx.getString(R.string.ra_panel_smart_audio),
            color = colors.subtleText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoleSummaryChip(
                if (audioInfo.cacheKey.isBlank()) appCtx.getString(R.string.ra_panel_not_analyzed) else audioStatusLabel(audioInfo.status),
                colors
            )
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_score, audioInfo.bgmCount), colors)
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_sound_effects, audioInfo.soundEffectCount), colors)
            if (audioInfo.elapsedMillis > 0L) {
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_elapsed, formatRoleElapsed(audioInfo.elapsedMillis)), colors)
            }
            if (audioInfo.requestCount > 0) {
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_requests, audioInfo.requestCount), colors)
            }
            if (audioInfo.totalTokens > 0) {
                RoleSummaryChip("Token ${audioInfo.totalTokens}", colors)
            }
            if (audioInfo.inputTokens > 0) {
                val uncached = (audioInfo.inputTokens - audioInfo.cachedInputTokens).coerceAtLeast(0)
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_input, audioInfo.inputTokens), colors)
                if (uncached > 0) RoleSummaryChip(appCtx.getString(R.string.ra_panel_misses, uncached), colors)
            }
            if (audioInfo.cachedInputTokens > 0) {
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_cache_hits, audioInfo.cachedInputTokens), colors)
            }
            if (audioInfo.outputTokens > 0) {
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_output, audioInfo.outputTokens), colors)
            }
        }
        val names = buildList {
            if (audioInfo.bgmNames.isNotEmpty()) {
                add(appCtx.getString(R.string.ra_panel_score_2) + audioInfo.bgmNames.joinToString("、"))
            }
            if (audioInfo.soundEffectNames.isNotEmpty()) {
                add(appCtx.getString(R.string.ra_panel_sound_effects_2) + audioInfo.soundEffectNames.joinToString("、"))
            }
        }.joinToString("  ")
        if (names.isNotBlank()) {
            Text(
                text = names,
                color = colors.secondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (audioInfo.error.isNotBlank()) {
            Text(
                text = audioInfo.error,
                color = Color(0xFFFF8A9A),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun audioStatusLabel(status: String): String {
    return when (status) {
        "running" -> appCtx.getString(R.string.ra_panel_analyzing)
        "success" -> appCtx.getString(R.string.package_sync_task_status_completed)
        "failed" -> appCtx.getString(R.string.dns_result_failed)
        else -> appCtx.getString(R.string.cache_manage_cached)
    }
}

@Composable
private fun RoleAssignmentSummary(
    playerState: ReadAloudPlayerPanel.PlayerUiState,
    roleState: AiReadAloudRoleState,
    colors: PlayerColors
) {
    val preview = roleState.previewSegments
    val unmatchedCount = preview.count {
        roleSegmentNeedsVoice(it) && !it.matchedCharacter
    }
    val unboundVoiceCount = preview.count {
        roleSegmentNeedsVoice(it) && it.matchedCharacter && roleSegmentVoiceLabel(it).isBlank()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RoleSummaryChip(roleStatusLabel(roleState), colors)
        RoleSummaryChip(roleSourceLabel(roleState.previewSource), colors)
        RoleSummaryChip(appCtx.getString(R.string.ra_panel_segments_2, preview.size), colors)
        if (roleState.elapsedMillis > 0L) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_elapsed_2, formatRoleElapsed(roleState.elapsedMillis)), colors)
        }
        if (roleState.requestCount > 0) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_requests_2, roleState.requestCount), colors)
        }
        if (roleState.totalTokens > 0) {
            RoleSummaryChip("Token ${roleState.totalTokens}", colors)
        } else if (roleState.requestCount > 0 && !roleState.running) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_token_usage_not_returned), colors)
        }
        if (roleState.inputTokens > 0) {
            val uncached = (roleState.inputTokens - roleState.cachedInputTokens).coerceAtLeast(0)
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_input_2, roleState.inputTokens), colors)
            if (uncached > 0) {
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_misses, uncached), colors)
            }
        }
        if (roleState.outputTokens > 0) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_output_2, roleState.outputTokens), colors)
        }
        if (roleState.cachedInputTokens > 0) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_cache_hits_2, roleState.cachedInputTokens), colors)
        }
        if (roleState.createdCharacterCount > 0) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_characters_added, roleState.createdCharacterCount), colors)
        }
        if (playerState.speakerLoudnessEnabled) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_loudness, playerState.speakerLoudnessGainPercent), colors)
            if (playerState.speakerLoudnessLearnedCount > 0) {
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_speakers_learned, playerState.speakerLoudnessLearnedCount), colors)
            } else if (!playerState.speakerLoudnessLearned) {
                RoleSummaryChip(appCtx.getString(R.string.ra_panel_learning_loudness), colors)
            }
        }
        if (unmatchedCount > 0) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_unmatched, unmatchedCount), colors, danger = true)
        }
        if (unboundVoiceCount > 0) {
            RoleSummaryChip(appCtx.getString(R.string.ra_panel_speakers_unbound, unboundVoiceCount), colors, danger = true)
        }
    }
    Text(
        text = playerState.roleStatusText,
        color = colors.secondaryText,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 10.dp)
    )
    if (roleState.error.isNotBlank()) {
        Text(
            text = roleState.error,
            color = Color(0xFFFF8A9A),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun formatRoleElapsed(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return if (seconds < 60L) {
        "${seconds}s"
    } else {
        "${seconds / 60L}m${seconds % 60L}s"
    }
}

@Composable
private fun RoleSummaryChip(
    text: String,
    colors: PlayerColors,
    danger: Boolean = false
) {
    Text(
        text = text,
        color = if (danger) Color(0xFFFF8A9A) else colors.secondaryText,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 5.dp)
    )
}

@Composable
private fun RolePreviewList(
    state: ReadAloudPlayerPanel.PlayerUiState,
    roleState: AiReadAloudRoleState,
    colors: PlayerColors,
    onEditSegment: (AiReadAloudRolePreviewSegment) -> Unit,
    modifier: Modifier = Modifier
) {
    val preview = roleState.previewSegments
    if (preview.isEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
            color = colors.panel.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, colors.panelBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.roleStatusRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent
                    )
                }
                Text(
                    text = when {
                        state.roleStatusRunning -> appCtx.getString(R.string.ra_panel_waiting_ai_segment_assignments)
                        roleState.error.isNotBlank() -> appCtx.getString(R.string.ra_panel_no_assignable_segments_show)
                        else -> appCtx.getString(R.string.ra_panel_no_assignment_details)
                    },
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
        return
    }
    val groups = remember(preview) {
        preview.groupBy { it.paragraphIndex }
            .toSortedMap()
            .map { it.key to it.value.sortedWith(compareBy<AiReadAloudRolePreviewSegment> { segment -> segment.start }.thenBy { segment -> segment.end }) }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 430.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(groups, key = { it.first }) { (paragraphIndex, segments) ->
            RolePreviewParagraphGroup(
                paragraphIndex = paragraphIndex,
                segments = segments,
                current = paragraphIndex == state.currentCueIndex,
                colors = colors,
                onEditSegment = onEditSegment
            )
        }
    }
}

@Composable
private fun RolePreviewParagraphGroup(
    paragraphIndex: Int,
    segments: List<AiReadAloudRolePreviewSegment>,
    current: Boolean,
    colors: PlayerColors,
    onEditSegment: (AiReadAloudRolePreviewSegment) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = if (current) colors.accent.copy(alpha = 0.12f) else colors.panel.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = appCtx.getString(R.string.ra_panel_paragraph, paragraphIndex + 1),
                    color = if (current) colors.accent else colors.subtleText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (current) {
                    Text(appCtx.getString(R.string.ra_panel_now_reading), color = colors.accent, fontSize = 11.sp)
                }
            }
            segments.forEach { segment ->
                RolePreviewSegmentRow(segment, colors, onClick = { onEditSegment(segment) })
            }
        }
    }
}

@Composable
private fun RolePreviewSegmentRow(
    segment: AiReadAloudRolePreviewSegment,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    val danger = roleSegmentNeedsVoice(segment) && !segment.matchedCharacter
    val unboundVoice = roleSegmentNeedsVoice(segment) &&
            segment.matchedCharacter &&
            roleSegmentVoiceLabel(segment).isBlank()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = colors.panelStrong.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoleTypeChip(segment.roleType, colors, danger)
                Text(
                    text = roleSpeakerLine(segment),
                    color = when {
                        danger -> Color(0xFFFFB3BB)
                        unboundVoice -> Color(0xFFFFC46B)
                        else -> colors.secondaryText
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(segment.confidence * 100).roundToInt()}%",
                    color = colors.subtleText,
                    fontSize = 11.sp
                )
            }
            Text(
                text = segment.text.ifBlank { appCtx.getString(R.string.ra_panel_empty_segment) },
                color = colors.primaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable
internal fun RoleSegmentEditDialog(
    state: ReadAloudPlayerPanel.PlayerUiState,
    segment: AiReadAloudRolePreviewSegment,
    colors: PlayerColors,
    onDismiss: () -> Unit,
    onSave: (String, Long, String) -> Unit
) {
    PlayerBackHandler(enabled = true, onBack = onDismiss)
    val characters by produceState<List<BookCharacter>>(initialValue = emptyList(), state.bookName, state.author) {
        value = withContext(Dispatchers.IO) {
            val bookKey = ReadBook.book?.characterBookKey().orEmpty()
            if (bookKey.isBlank()) emptyList() else appDb.bookCharacterDao.characters(bookKey)
        }
    }
    var roleType by remember(segment.key) {
        mutableStateOf(
            segment.roleType.takeIf { it in setOf("narrator", "character", "thought") }
                ?: "character"
        )
    }
    var query by remember(segment.key) { mutableStateOf("") }
    var selectedCharacterId by remember(segment.key) { mutableStateOf(segment.characterId) }
    var selectedCharacterName by remember(segment.key) { mutableStateOf(segment.characterName) }
    val filteredCharacters = remember(characters, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            characters
        } else {
            characters.filter { character ->
                character.displayName().contains(keyword, ignoreCase = true) ||
                        character.name.contains(keyword, ignoreCase = true) ||
                        character.identity.contains(keyword, ignoreCase = true)
            }
        }
    }
    val canSave = roleType == "narrator" || selectedCharacterId > 0L || selectedCharacterName.isNotBlank()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .systemBarsPadding()
            .padding(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            shape = LocalContext.current.composePanelShape(),
            color = colors.panelStrong.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, colors.panelBorder),
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appCtx.getString(R.string.ra_panel_edit_segment_assignment),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = appCtx.getString(R.string.ra_panel_paragraph_2, segment.paragraphIndex + 1),
                            color = colors.subtleText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    RoleDialogAction(appCtx.getString(R.string.dialog_cancel), colors, onDismiss)
                }
                Text(
                    text = segment.text,
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("narrator", "character", "thought").forEach { type ->
                        RoleEditChoice(
                            text = roleTypeLabel(type),
                            selected = roleType == type,
                            colors = colors,
                            onClick = {
                                roleType = type
                                if (type == "narrator") {
                                    selectedCharacterId = 0L
                                    selectedCharacterName = ""
                                }
                            }
                        )
                    }
                }
                AnimatedVisibility(visible = roleType != "narrator") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text(appCtx.getString(R.string.ra_panel_search_characters)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (filteredCharacters.isEmpty()) {
                                item {
                                    Text(
                                        text = appCtx.getString(R.string.ra_panel_no_characters_available),
                                        color = colors.secondaryText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                }
                            } else {
                                items(filteredCharacters, key = { it.id }) { character ->
                                    RoleCharacterChoiceRow(
                                        character = character,
                                        selected = selectedCharacterId == character.id,
                                        colors = colors,
                                        onClick = {
                                            selectedCharacterId = character.id
                                            selectedCharacterName = character.name
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        onClick = {
                            if (canSave) {
                                onSave(roleType, selectedCharacterId, selectedCharacterName)
                            }
                        },
                        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
                        color = if (canSave) colors.accent.copy(alpha = 0.20f) else colors.panel.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, if (canSave) colors.accent.copy(alpha = 0.35f) else colors.panelBorder)
                    ) {
                        Text(
                            text = appCtx.getString(R.string.action_save),
                            color = if (canSave) colors.accent else colors.subtleText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleEditChoice(
    text: String,
    selected: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = if (selected) colors.accent.copy(alpha = 0.18f) else colors.panel.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = 0.35f) else colors.panelBorder)
    ) {
        Text(
            text = text,
            color = if (selected) colors.accent else colors.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun RoleCharacterChoiceRow(
    character: BookCharacter,
    selected: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    val route = remember(character.speechRouteJson) { SpeechRoute.fromJson(character.speechRouteJson) }
    val voice = route.displaySpeakerName()
        .ifBlank { route.toneID }
        .ifBlank { route.groupName }
        .ifBlank { if (route.isConfigured) appCtx.getString(R.string.ra_panel_speaker_bound) else appCtx.getString(R.string.ra_panel_speaker_not_bound) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = if (selected) colors.accent.copy(alpha = 0.16f) else colors.panel.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = 0.36f) else colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.displayName(),
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = listOf(character.genderLabel(), character.identity, voice)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (selected) {
                Text(appCtx.getString(R.string.ra_panel_selected), color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RoleTypeChip(
    roleType: String,
    colors: PlayerColors,
    danger: Boolean
) {
    val color = when {
        danger -> Color(0xFFFF8A9A)
        roleType == "narrator" -> colors.subtleText
        roleType == "thought" -> Color(0xFFBFA7FF)
        roleType == "character" -> colors.accent
        else -> colors.secondaryText
    }
    Surface(
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, Color(UiCorner.borderLineColor(LocalContext.current)))
    ) {
        Text(
            text = roleTypeLabel(roleType),
            color = color,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun roleTypeLabel(roleType: String): String {
    return when (roleType) {
        "narrator" -> appCtx.getString(R.string.ra_panel_narrator)
        "character" -> appCtx.getString(R.string.ra_panel_character)
        "thought" -> appCtx.getString(R.string.ra_panel_thought)
        else -> appCtx.getString(R.string.discover_group_other)
    }
}

private fun roleStatusLabel(state: AiReadAloudRoleState): String {
    return when (state.status) {
        AiReadAloudRoleState.STATUS_RUNNING -> appCtx.getString(R.string.ra_panel_assigning)
        AiReadAloudRoleState.STATUS_SUCCESS -> appCtx.getString(R.string.package_sync_task_status_completed)
        AiReadAloudRoleState.STATUS_FALLBACK -> "Fallback"
        AiReadAloudRoleState.STATUS_FAILED -> appCtx.getString(R.string.dns_result_failed)
        AiReadAloudRoleState.STATUS_SKIPPED -> appCtx.getString(R.string.cache_manage_cached)
        else -> appCtx.getString(R.string.ra_panel_pending)
    }
}

private fun roleSourceLabel(source: String): String {
    return when (source) {
        AiReadAloudRoleState.SOURCE_AI -> appCtx.getString(R.string.ra_panel_ai_raw_result)
        AiReadAloudRoleState.SOURCE_RULE -> appCtx.getString(R.string.ra_panel_local_preprocessing)
        AiReadAloudRoleState.SOURCE_AI_CONFIRM -> appCtx.getString(R.string.ra_panel_ai_confirmed)
        AiReadAloudRoleState.SOURCE_RESOLVED -> appCtx.getString(R.string.ra_panel_matched_character)
        AiReadAloudRoleState.SOURCE_CACHE -> appCtx.getString(R.string.cache_manage_cache_selected)
        AiReadAloudRoleState.SOURCE_FALLBACK -> appCtx.getString(R.string.ra_panel_default_split)
        else -> appCtx.getString(R.string.ra_panel_waiting_result)
    }
}

private fun roleSpeakerLine(segment: AiReadAloudRolePreviewSegment): String {
    val roleName = when {
        segment.roleType == "narrator" -> appCtx.getString(R.string.ra_panel_narrator)
        segment.characterName.isBlank() -> appCtx.getString(R.string.ra_panel_unrecognized_character)
        segment.matchedCharacter -> segment.characterName
        else -> appCtx.getString(R.string.ra_panel_unmatched_2, segment.characterName)
    }
    val voice = roleSegmentVoiceLabel(segment).takeIf { it.isNotBlank() }
        ?: if (roleSegmentNeedsVoice(segment) && segment.matchedCharacter) appCtx.getString(R.string.ra_panel_speaker_not_bound) else null
    val emotion = segment.emotionName.takeIf { it.isNotBlank() }
    return buildList {
        add(roleName)
        voice?.let(::add)
        emotion?.let(::add)
    }.joinToString(" · ")
}

private fun roleSegmentNeedsVoice(segment: AiReadAloudRolePreviewSegment): Boolean {
    return segment.roleType == "character" || segment.roleType == "thought"
}

private fun roleSegmentVoiceLabel(segment: AiReadAloudRolePreviewSegment): String {
    return segment.speakerName
        .ifBlank { segment.toneID }
        .ifBlank { segment.groupName }
        .ifBlank {
            when (segment.engineType) {
                SpeechRoute.ENGINE_SYSTEM -> appCtx.getString(R.string.ra_panel_system_voice)
                SpeechRoute.ENGINE_HTTP -> "HTTP TTS"
                else -> ""
            }
        }
}
