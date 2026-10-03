package io.legado.app.ui.book.cache

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.image.CoverImageView

/**
 * 缓存管理的单本书卡片：封面 + 书名/来源 + 缓存计数与任务进度 + 操作按钮行
 *
 * 任务状态取值与按钮可用性规则与原 View 版列表项保持一致
 */
@Composable
internal fun CacheBookCard(
    item: CacheBookItem,
    palette: AppManagementPalette,
    audioTaskStates: Map<String, AudioCacheTaskState>,
    webDavTaskStates: Map<String, WebDavTaskState>,
    onOpenChapters: (CacheBookItem) -> Unit,
    onUpload: (CacheBookItem) -> Unit,
    onRestore: (CacheBookItem) -> Unit,
    onDelete: (CacheBookItem) -> Unit,
    onStopAudio: (CacheBookItem) -> Unit,
    onSelectSource: (CacheBookItem) -> Unit,
    onDownload: (CacheBookItem) -> Unit,
    onSelectSyncAction: (CacheBookItem) -> Unit
) {
    val context = LocalContext.current
    val panelRadius = context.composePanelRadius()
    val actionRadius = context.composeActionRadius()
    val audioState = remember(item, audioTaskStates) { audioTaskStateFor(item, audioTaskStates) }
    val webDavState = remember(item, webDavTaskStates) { webDavTaskStateFor(item, webDavTaskStates) }
    val isCaching = audioState?.active == true
    val isPaused = audioState?.status == CacheTaskStatus.PAUSED
    val webDavActive = webDavState?.active == true
    val taskLocked = isCaching || isPaused || webDavActive
    val hasLocalCache = item.localCachedCount > 0
    val hasRemoteCache = item.hasRemoteCache()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(palette.settings.row),
                shape = RoundedCornerShape(panelRadius)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BookCoverImage(
                book = item.book,
                style = CoverImageView.CoverStyle.LIST,
                modifier = Modifier
                    .width(66.dp)
                    .height(90.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.book.name,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = palette.settings.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.cacheCountText(context),
                    color = palette.settings.primaryText,
                    fontSize = 13.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(cacheStateLabelRes(item)),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val taskMessage = when {
                    isCaching || isPaused -> audioState?.message
                    webDavActive -> webDavState?.message
                    else -> webDavState?.takeIf { it.status != WebDavTaskStatus.COMPLETED }?.message
                        ?: audioState?.takeIf { it.status != CacheTaskStatus.COMPLETED }?.message
                }
                if (!taskMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = taskMessage,
                        color = palette.settings.secondaryText,
                        fontSize = 12.sp,
                        fontFamily = palette.settings.bodyFontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.sourceVariants.size > 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(palette.settings.rowPressed),
                                shape = RoundedCornerShape(actionRadius)
                            )
                            .clickable { onSelectSource(item) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (item.sourceAvailable) {
                                item.sourceName
                            } else {
                                stringResource(R.string.cache_manage_source_deleted_chip, item.sourceName)
                            },
                            color = palette.settings.secondaryText,
                            fontSize = 12.sp,
                            fontFamily = palette.settings.bodyFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (item.sourceAvailable) {
                            item.sourceName
                        } else {
                            stringResource(R.string.cache_manage_source_deleted_chip, item.sourceName)
                        },
                        color = palette.settings.secondaryText,
                        fontSize = 12.sp,
                        fontFamily = palette.settings.bodyFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CacheActionButton(
                text = stringResource(R.string.cache_manage_chapters),
                palette = palette,
                actionRadius = actionRadius,
                modifier = Modifier.weight(1f),
                enabled = hasLocalCache,
                accent = true,
                onClick = { onOpenChapters(item) }
            )
            CacheActionButton(
                text = stringResource(uploadActionLabelRes(item)),
                palette = palette,
                actionRadius = actionRadius,
                modifier = Modifier.weight(1f),
                enabled = (hasLocalCache || hasRemoteCache) && !taskLocked,
                onClick = {
                    when {
                        hasLocalCache && hasRemoteCache -> onSelectSyncAction(item)
                        hasRemoteCache && !hasLocalCache -> onDownload(item)
                        else -> onUpload(item)
                    }
                }
            )
            if (item.manifest != null && hasLocalCache) {
                CacheActionButton(
                    text = stringResource(
                        if (item.inBookshelf) R.string.cache_manage_use_cache
                        else R.string.cache_manage_add_bookshelf
                    ),
                    palette = palette,
                    actionRadius = actionRadius,
                    modifier = Modifier.weight(1f),
                    onClick = { onRestore(item) }
                )
            }
            if (isCaching || isPaused) {
                CacheActionButton(
                    text = stringResource(if (isPaused) R.string.resume else R.string.pause),
                    palette = palette,
                    actionRadius = actionRadius,
                    modifier = Modifier.weight(1f),
                    onClick = { onStopAudio(item) }
                )
            }
            CacheActionButton(
                text = stringResource(R.string.delete),
                palette = palette,
                actionRadius = actionRadius,
                modifier = Modifier.weight(1f),
                enabled = (hasLocalCache || hasRemoteCache) && !taskLocked,
                onClick = { onDelete(item) }
            )
        }
    }
}

private fun uploadActionLabelRes(item: CacheBookItem): Int = when {
    item.localCachedCount > 0 && item.hasRemoteCache() -> R.string.cache_manage_sync_action
    item.hasRemoteCache() && item.localCachedCount <= 0 -> R.string.action_download
    else -> R.string.cache_manage_upload
}

private fun cacheStateLabelRes(item: CacheBookItem): Int {
    val local = item.localCachedCount > 0
    val remote = item.hasRemoteCache()
    return when {
        local && remote -> R.string.cache_manage_state_both
        local -> R.string.cache_manage_state_local
        remote -> R.string.cache_manage_state_remote
        else -> R.string.cache_manage_state_none
    }
}

private fun CacheBookItem.cacheCountText(context: Context): String {
    return buildCacheCountText(
        context, localCachedCount, remoteCachedCount, totalChapterCount, remoteAvailable
    )
}

fun CacheBookSourceVariant.cacheCountText(context: Context): String {
    return buildCacheCountText(
        context, localCachedCount, remoteCachedCount, totalChapterCount, remoteAvailable
    )
}

private fun buildCacheCountText(
    context: Context,
    localCount: Int,
    remoteCount: Int,
    totalCount: Int,
    remoteAvailable: Boolean
): String {
    val parts = arrayListOf<String>()
    if (localCount > 0) {
        parts += context.getString(R.string.cache_manage_local_cached_count, localCount)
    }
    if (remoteAvailable && remoteCount > 0) {
        parts += context.getString(R.string.cache_manage_remote_cached_count, remoteCount)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: context.getString(R.string.cache_manage_cached_count, localCount)
}

private fun audioTaskStateFor(
    item: CacheBookItem,
    taskStates: Map<String, AudioCacheTaskState>
): AudioCacheTaskState? {
    taskStates[item.book.bookUrl]?.let { return it }
    item.sourceVariants.forEach { variant ->
        taskStates[variant.book.bookUrl]?.let { return it }
        variant.taskState?.let { return it }
    }
    return item.taskState
}

private fun webDavTaskStateFor(
    item: CacheBookItem,
    taskStates: Map<String, WebDavTaskState>
): WebDavTaskState? {
    taskStates[item.cacheKey]?.let { return it }
    item.sourceVariants.forEach { variant ->
        taskStates[variant.cacheKey]?.let { return it }
    }
    return null
}
