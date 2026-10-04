package io.legado.app.ui.book.cache

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * 缓存管理页（Compose 渲染）
 *
 * 数据与破坏性操作（上传/下载/删除/恢复书架）仍由 [CacheManageActivity] 处理，
 * 这里只负责三分类 Tab、摘要、书籍卡片列表与批量操作行的渲染与交互转发。
 */
@Composable
fun CacheManageScreen(
    mode: CacheManageMode,
    items: List<CacheBookItem>,
    summary: CacheSummary?,
    loading: Boolean,
    audioTaskStates: Map<String, AudioCacheTaskState>,
    webDavTaskStates: Map<String, WebDavTaskState>,
    onModeChange: (CacheManageMode) -> Unit,
    onOpenChapters: (CacheBookItem) -> Unit,
    onUpload: (CacheBookItem) -> Unit,
    onRestore: (CacheBookItem) -> Unit,
    onDelete: (CacheBookItem) -> Unit,
    onStopAudio: (CacheBookItem) -> Unit,
    onOpenReviews: (CacheBookItem) -> Unit,
    onSelectSource: (CacheBookItem) -> Unit,
    onDownload: (CacheBookItem) -> Unit,
    onSelectSyncAction: (CacheBookItem) -> Unit,
    onUploadAll: () -> Unit,
    onDeleteAll: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    val context = LocalContext.current
    val panelRadius = context.composePanelRadius()
    val actionRadius = context.composeActionRadius()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.settings.page)
    ) {
        CacheModeTabRow(
            mode = mode,
            palette = palette,
            panelRadius = panelRadius,
            actionRadius = actionRadius,
            onModeChange = onModeChange
        )
        Text(
            text = summary?.let {
                stringResource(
                    R.string.cache_manage_summary_state,
                    it.bookCount,
                    it.cachedChapterCount
                )
            }.orEmpty(),
            color = palette.settings.secondaryText,
            fontSize = 13.sp,
            fontFamily = palette.settings.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (items.isEmpty() && !loading) {
                Text(
                    text = stringResource(R.string.cache_manage_empty, stringResource(mode.titleRes)),
                    color = palette.settings.secondaryText,
                    fontSize = 14.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = items,
                        key = { it.groupKey },
                        contentType = { "cacheBook" }
                    ) { item ->
                        CacheBookCard(
                            item = item,
                            palette = palette,
                            audioTaskStates = audioTaskStates,
                            webDavTaskStates = webDavTaskStates,
                            onOpenChapters = onOpenChapters,
                            onUpload = onUpload,
                            onRestore = onRestore,
                            onDelete = onDelete,
                            onStopAudio = onStopAudio,
                            onOpenReviews = onOpenReviews,
                            onSelectSource = onSelectSource,
                            onDownload = onDownload,
                            onSelectSyncAction = onSelectSyncAction
                        )
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    color = palette.settings.accent,
                    strokeWidth = 2.dp
                )
            }
        }
        CacheBatchBar(
            palette = palette,
            actionRadius = actionRadius,
            onUploadAll = onUploadAll,
            onDeleteAll = onDeleteAll
        )
    }
}

@Composable
private fun CacheModeTabRow(
    mode: CacheManageMode,
    palette: AppManagementPalette,
    panelRadius: Dp,
    actionRadius: Dp,
    onModeChange: (CacheManageMode) -> Unit
) {
    val modes = remember { CacheManageMode.entries.toList() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .height(42.dp)
            .background(
                color = Color(palette.settings.row),
                shape = RoundedCornerShape(panelRadius)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        modes.forEach { entry ->
            val selected = entry == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(
                        color = if (selected) {
                            palette.miuix.surfaceVariant
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(actionRadius)
                    )
                    .clickable { onModeChange(entry) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(entry.titleRes),
                    color = if (selected) palette.settings.accent else palette.settings.primaryText,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CacheBatchBar(
    palette: AppManagementPalette,
    actionRadius: Dp,
    onUploadAll: () -> Unit,
    onDeleteAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CacheActionButton(
            text = stringResource(R.string.cache_manage_upload_all),
            palette = palette,
            actionRadius = actionRadius,
            modifier = Modifier.weight(1f),
            onClick = onUploadAll
        )
        CacheActionButton(
            text = stringResource(R.string.cache_manage_delete_all),
            palette = palette,
            actionRadius = actionRadius,
            modifier = Modifier.weight(1f),
            onClick = onDeleteAll
        )
    }
}

@Composable
internal fun CacheActionButton(
    text: String,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    actionRadius: Dp = LocalContext.current.composeActionRadius(),
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .background(
                color = if (accent) {
                    palette.settings.accent.copy(alpha = 0.12f)
                } else {
                    Color(palette.settings.row)
                },
                shape = RoundedCornerShape(actionRadius)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (accent) palette.settings.accent else palette.settings.primaryText,
            fontSize = 14.sp,
            fontFamily = palette.settings.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
