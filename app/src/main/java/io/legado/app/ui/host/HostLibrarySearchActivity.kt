package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.librarysearch.LibrarySearchViewModel
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppLoading
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSectionHeader
import io.legado.app.uikit.v5.components.appRowCard
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * P4-3：书库搜索 V5 化（2026-10-13）。
 * 数据层复用 [LibrarySearchViewModel]（零拷贝）；高亮解析整体搬运（⟦ ⟧ 纯函数）。
 */
class HostLibrarySearchActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HostLibrarySearchActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostLibrarySearchScreen() }
    }
}

@Composable
private fun HostLibrarySearchScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val viewModel = remember { ViewModelProvider(activity)[LibrarySearchViewModel::class.java] }

    val query by viewModel.query.collectAsState()
    val items by viewModel.items.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val indexedCount by viewModel.indexedCount.collectAsState()
    val rebuilding by viewModel.rebuilding.collectAsState()
    val rebuildProgress by viewModel.rebuildProgress.collectAsState()
    val listState = rememberLazyListState()

    HostV5Page(
        title = stringResource(R.string.library_search),
        onBack = { activity.finish() },
        trailing = {
            if (searching || rebuilding) {
                AppLoading(modifier = Modifier.size(20.dp))
            } else {
                AppGhostButton(
                    text = "↻",
                    onClick = { viewModel.rebuildIndex() },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppSearchField(
                    value = query,
                    onValueChange = { viewModel.updateQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.library_search_hint),
                    onSearch = { },
                )
            }
            if (rebuilding || rebuildProgress.isNotBlank()) {
                Text(
                    text = rebuildProgress,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 4,
                    ),
                )
            }

            when {
                query.isBlank() -> AppEmpty(
                    title = stringResource(R.string.library_search),
                    supporting = stringResource(R.string.library_search_index_count, indexedCount),
                )

                items.isEmpty() && !searching -> AppEmpty(
                    title = stringResource(R.string.library_search),
                    supporting = stringResource(R.string.library_search_empty),
                )

                else -> AppFastScroller(
                    listState = listState,
                    topContentPadding = density.blockGap / 2,
                    bottomContentPadding = density.screenPadding,
                    endContentPadding = density.screenPadding,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = density.screenPadding,
                            end = density.screenPadding,
                            top = density.blockGap / 2,
                            bottom = density.screenPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        items(items, key = { "${it.bookUrl}#${it.chapterIndex}" }) { item ->
                            Column {
                                if (item.showHeader) {
                                    AppSectionHeader(
                                        title = buildString {
                                            append(item.bookName)
                                            if (item.author.isNotBlank()) {
                                                append(" · ")
                                                append(item.author)
                                            }
                                        },
                                    )
                                }
                                LibraryResultRow(
                                    chapterTitle = item.chapterTitle,
                                    snippet = highlightText(item.snippetText, colors.primary),
                                    onClick = {
                                        viewModel.getBookUrlProgress(item) { bookUrl ->
                                            context.startActivity(
                                                Intent(context, ReadBookActivity::class.java)
                                                    .putExtra("bookUrl", bookUrl)
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 解析 snippet() 返回的 ⟦ ⟧ 标记，命中片段高亮（整体搬运旧实现）。 */
@Composable
private fun highlightText(snippetText: String, highlightColor: androidx.compose.ui.graphics.Color) =
    buildAnnotatedString {
        var highlight = false
        var buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        color = if (highlight) highlightColor else androidx.compose.ui.graphics.Color.Unspecified,
                        fontWeight = if (highlight) androidx.compose.ui.text.font.FontWeight.Bold else null
                    )
                ) { append(buffer.toString()) }
                buffer = StringBuilder()
            }
        }
        snippetText.forEach { ch ->
            when (ch) {
                '⟦' -> {
                    flush()
                    highlight = true
                }
                '⟧' -> {
                    flush()
                    highlight = false
                }
                else -> buffer.append(ch)
            }
        }
        flush()
    }

/** 结果行：章节标题 + 高亮命中片段（appRowCard 底色与全站行一致）。 */
@Composable
private fun LibraryResultRow(
    chapterTitle: String,
    snippet: androidx.compose.ui.text.AnnotatedString,
    onClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appRowCard()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = chapterTitle,
            style = visual.type.body,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = snippet,
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
        )
    }
}
