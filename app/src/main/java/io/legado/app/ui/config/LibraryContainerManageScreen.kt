package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.help.book.library.LibraryContainerConfig
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.ui.host.HostV5Page
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryContainerManageScreen(
    containers: List<LibraryContainerConfig>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onItemClick: (LibraryContainerConfig) -> Unit,
    pageMenuActions: () -> List<Pair<String, () -> Unit>>,
    onMoreActions: (LibraryContainerConfig) -> List<Pair<String, () -> Unit>>
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val colors = LocalSemanticColors.current
    var pageMenuOpen by remember { mutableStateOf(false) }

    HostV5Page(
        title = "书库容器",
        onBack = onBack,
        trailing = {
            AppOverflowMenuButton(
                contentDescription = "更多",
                onClick = { pageMenuOpen = true }
            )
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "书库容器只用于同步阅读章节缓存，不参与备份、主题、气泡或缓存包同步。阅读时会先读取目录索引，只有命中缓存章节才请求正文。",
                color = colors.onSurfaceVariant,
                style = visual.type.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2)
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = density.blockGap / 2),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                items(containers, key = { it.id }) { item ->
                    LibraryContainerCard(
                        item = item,
                        isDefault = LibraryContainerManager.selectedId() == item.id,
                        onClick = { onItemClick(item) },
                        moreActions = onMoreActions(item)
                    )
                }
            }
            AppFilledButton(
                text = "添加书库容器",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
                onClick = onAdd
            )
        }
    }

    if (pageMenuOpen) {
        AppMenuSheet(
            title = "更多",
            actions = pageMenuActions(),
            onDismiss = { pageMenuOpen = false }
        )
    }
}

@Composable
private fun LibraryContainerCard(
    item: LibraryContainerConfig,
    isDefault: Boolean,
    onClick: () -> Unit,
    moreActions: List<Pair<String, () -> Unit>>
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val colors = LocalSemanticColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val container = item.container
    val displayName = LibraryContainerManager.displayLabel(item)
    val capacityMb = container.capacityMb.coerceAtLeast(0)
    val usedBytes = container.usedBytes.coerceAtLeast(0)
    val capacityText = if (capacityMb > 0) {
        val capacityBytes = LibraryContainerManageActivity.mbToBytes(capacityMb)
        "容量：${LibraryContainerManageActivity.formatBytes(capacityBytes)} / 已用：${LibraryContainerManageActivity.formatBytes(usedBytes)} / 剩余：${LibraryContainerManageActivity.formatBytes((capacityBytes - usedBytes).coerceAtLeast(0))} / 已满：${if (container.isFull) "是" else "否"}"
    } else {
        "已用：${LibraryContainerManageActivity.formatBytes(usedBytes)}（不限容量）"
    }
    val minUpload = if (item.minUploadChars > 0) "最少${item.minUploadChars}字" else "不过滤短章"
    val dailyLimit = if (item.dailyUploadLimit > 0) "每日${item.dailyUploadLimit}章" else "每日不限"
    val lockState = if (item.lockedImported) " · 加密导入" else ""
    val stateText = "状态：${if (container.enabled) "启用" else "禁用"}$lockState · 书源优先 · ${item.sourceUrls.size} 个书源 · $minUpload · $dailyLimit"

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = density.screenPadding),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(density.screenPadding)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = colors.onSurface,
                    style = visual.type.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "${container.bucket}/${container.prefix.trim('/')}",
                    color = colors.onSurfaceVariant,
                    style = visual.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = capacityText,
                    color = colors.onSurfaceVariant,
                    style = visual.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stateText,
                    color = if (container.enabled) colors.primary else colors.onSurfaceVariant,
                    style = visual.type.caption,
                    fontWeight = if (container.enabled) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isDefault) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "✓",
                    color = colors.primary,
                    style = visual.type.body,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            AppOverflowMenuButton(
                contentDescription = "更多",
                onClick = { menuOpen = true }
            )
        }
    }

    if (menuOpen) {
        AppMenuSheet(
            title = displayName,
            actions = moreActions,
            onDismiss = { menuOpen = false }
        )
    }
}
