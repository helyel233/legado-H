package io.legado.app.ui.host

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.cache.AudioCacheTaskState
import io.legado.app.ui.book.cache.CacheBookItem
import io.legado.app.ui.book.cache.CacheTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskState
import io.legado.app.ui.book.cache.audioTaskStateFor
import io.legado.app.ui.book.cache.cacheCountText
import io.legado.app.ui.book.cache.hasRemoteCache
import io.legado.app.ui.book.cache.cacheStateLabelRes
import io.legado.app.ui.book.cache.uploadActionLabelRes
import io.legado.app.ui.book.cache.webDavTaskStateFor
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * 缓存管理 V5 书籍卡片：封面 + 书名/来源 + 缓存计数与任务进度 + 操作按钮行。
 * 状态取值与按钮可用性规则复用旧 CacheBookCard 的 internal 辅助函数（零拷贝）。
 */
@Composable
internal fun HostCacheBookCard(
    item: CacheBookItem,
    audioTaskStates: Map<String, AudioCacheTaskState>,
    webDavTaskStates: Map<String, WebDavTaskState>,
    onOpenChapters: (CacheBookItem) -> Unit,
    onUpload: (CacheBookItem) -> Unit,
    onDownload: (CacheBookItem) -> Unit,
    onSelectSyncAction: (CacheBookItem) -> Unit,
    onRestore: (CacheBookItem) -> Unit,
    onDelete: (CacheBookItem) -> Unit,
    onStopAudio: (CacheBookItem) -> Unit,
    onOpenReviews: (CacheBookItem) -> Unit,
    onSelectSource: (CacheBookItem) -> Unit,
) {
    val context = LocalContext.current
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val audioState = remember(item, audioTaskStates) { audioTaskStateFor(item, audioTaskStates) }
    val webDavState = remember(item, webDavTaskStates) { webDavTaskStateFor(item, webDavTaskStates) }
    val isCaching = audioState?.active == true
    val isPaused = audioState?.status == CacheTaskStatus.PAUSED
    val webDavActive = webDavState?.active == true
    val taskLocked = isCaching || isPaused || webDavActive
    val hasLocalCache = item.localCachedCount > 0
    val hasRemoteCache = item.hasRemoteCache()

    io.legado.app.uikit.v5.components.AppCard(
        modifier = Modifier.fillMaxWidth(),
        level = MaterialLevel.EMBEDDED,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BookCoverImage(
                book = item.book,
                style = CoverImageView.CoverStyle.LIST,
                modifier = Modifier
                    .width(56.dp)
                    .height(76.dp),
            )
            Spacer(modifier = Modifier.width(density.blockGap / 2))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.book.name,
                    style = visual.type.title,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.cacheCountText(context),
                    style = visual.type.caption,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(cacheStateLabelRes(item)),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val taskMessage = when {
                    isCaching || isPaused -> audioState?.message
                    webDavActive -> webDavState?.message
                    else -> webDavState
                        ?.takeIf { it.status != io.legado.app.ui.book.cache.WebDavTaskStatus.COMPLETED }
                        ?.message
                        ?: audioState?.takeIf { it.status != CacheTaskStatus.COMPLETED }?.message
                }
                if (!taskMessage.isNullOrBlank()) {
                    Text(
                        text = taskMessage,
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sourceText = if (item.sourceAvailable) {
                    item.sourceName
                } else {
                    stringResource(R.string.cache_manage_source_deleted_chip, item.sourceName)
                }
                if (item.sourceVariants.size > 1) {
                    Text(
                        text = sourceText,
                        style = visual.type.caption,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(visual.shape(ShapeKind.TAG))
                            .background(colors.surfaceVariant)
                            .clickable { onSelectSource(item) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text(
                        text = sourceText,
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(density.blockGap / 2))
        Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
            CacheV5ActionChip(
                text = stringResource(R.string.cache_manage_chapters),
                accent = true,
                enabled = hasLocalCache,
                modifier = Modifier.weight(1f),
            ) { onOpenChapters(item) }
            CacheV5ActionChip(
                text = stringResource(uploadActionLabelRes(item)),
                enabled = (hasLocalCache || hasRemoteCache) && !taskLocked,
                modifier = Modifier.weight(1f),
            ) {
                when {
                    hasLocalCache && hasRemoteCache -> onSelectSyncAction(item)
                    hasRemoteCache && !hasLocalCache -> onDownload(item)
                    else -> onUpload(item)
                }
            }
            CacheV5ActionChip(
                text = stringResource(R.string.cache_manage_review_snapshots),
                modifier = Modifier.weight(1f),
            ) { onOpenReviews(item) }
        }
        Spacer(modifier = Modifier.height(density.blockGap / 4))
        Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
            if (item.manifest != null && hasLocalCache) {
                CacheV5ActionChip(
                    text = stringResource(
                        if (item.inBookshelf) R.string.cache_manage_use_cache
                        else R.string.cache_manage_add_bookshelf
                    ),
                    modifier = Modifier.weight(1f),
                ) { onRestore(item) }
            }
            if (isCaching || isPaused) {
                CacheV5ActionChip(
                    text = stringResource(if (isPaused) R.string.resume else R.string.pause),
                    modifier = Modifier.weight(1f),
                ) { onStopAudio(item) }
            }
            CacheV5ActionChip(
                text = stringResource(R.string.delete),
                danger = true,
                enabled = (hasLocalCache || hasRemoteCache) && !taskLocked,
                modifier = Modifier.weight(1f),
            ) { onDelete(item) }
        }
    }
}

/** 卡片底部的小动作块（token 化的 CacheActionButton 等价件）。 */
@Composable
private fun CacheV5ActionChip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val bg = when {
        !enabled -> colors.surfaceVariant.copy(alpha = 0.4f)
        danger -> colors.error.copy(alpha = 0.12f)
        accent -> colors.primary.copy(alpha = 0.12f)
        else -> colors.surfaceVariant
    }
    val fg = when {
        !enabled -> colors.onSurfaceVariant
        danger -> colors.error
        accent -> colors.primary
        else -> colors.onSurface
    }
    Box(
        modifier = modifier
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = visual.type.caption, color = fg, maxLines = 1)
    }
}

