package io.legado.app.ui.book.cache

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 书籍缓存导出界面：勾选有缓存的书籍，全选/全不选，导出为 ZIP 或从 ZIP 恢复。
 */
@Composable
fun BookCacheExportScreen(
    items: List<BookCacheExportItem>,
    state: BookCacheExportState,
    onToggle: (BookCacheExportItem) -> Unit,
    onToggleAll: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val exporting = state is BookCacheExportState.Exporting

    Column(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty() && !exporting) {
            Text(
                text = stringResource(R.string.book_cache_export_empty),
                color = style.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.book_cache_export_summary, items.size),
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        if (allSelected(items)) R.string.un_select_all else R.string.select_all
                    ),
                    color = style.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onToggleAll() }
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.book.bookUrl }) { item ->
                    BookCacheExportItemCard(
                        item = item,
                        style = style,
                        onClick = { onToggle(item) }
                    )
                }
            }
        }
        if (exporting) {
            Text(
                text = state.message,
                color = style.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LegadoMiuixActionButton(
                text = stringResource(R.string.book_cache_import),
                palette = palette,
                cornerRadius = style.actionRadius,
                onClick = onImport
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.book_cache_export),
                palette = palette,
                primary = true,
                cornerRadius = style.actionRadius,
                modifier = Modifier.weight(1f),
                onClick = onExport
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.close),
                palette = palette,
                cornerRadius = style.actionRadius,
                onClick = onDismiss
            )
        }
    }
}

private fun allSelected(items: List<BookCacheExportItem>): Boolean {
    return items.isNotEmpty() && items.all { it.isSelected }
}

/** 单本书缓存条目卡片：勾选框 + 书名 + 作者 + 缓存大小 */
@Composable
private fun BookCacheExportItemCard(
    item: BookCacheExportItem,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = if (item.isSelected) style.accent.copy(alpha = 0.10f) else style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (item.isSelected) "☑" else "☐",
                color = if (item.isSelected) style.accent else style.secondaryText,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.book.name,
                    color = style.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.book.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.formattedSize,
                color = style.secondaryText,
                fontSize = 12.sp
            )
        }
    }
}
