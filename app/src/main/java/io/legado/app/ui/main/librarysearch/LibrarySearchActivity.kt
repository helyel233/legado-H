package io.legado.app.ui.main.librarysearch

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityLibrarySearchBinding
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 全库搜索：跨书检索已缓存章节正文（FTS4 索引）。
 */
class LibrarySearchActivity :
    VMBaseActivity<ActivityLibrarySearchBinding, LibrarySearchViewModel>() {

    override val binding by viewBinding(ActivityLibrarySearchBinding::inflate)
    override val viewModel by viewModels<LibrarySearchViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setNavigationOnClickListener {
            finish()
        }
        binding.composeView.setContent {
            LegadoComposeTheme {
                LibrarySearchScreen(viewModel)
            }
        }
    }

}

@Composable
private fun LibrarySearchScreen(viewModel: LibrarySearchViewModel) {
    val style = rememberAppDialogStyle()
    val query by viewModel.query.collectAsState()
    val items by viewModel.items.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val indexedCount by viewModel.indexedCount.collectAsState()
    val rebuilding by viewModel.rebuilding.collectAsState()
    val rebuildProgress by viewModel.rebuildProgress.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.surface)
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(style.actionRadius),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Text(
                            text = "✕",
                            color = style.secondaryText,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .clickable { viewModel.updateQuery("") }
                                .padding(8.dp)
                        )
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    cursorColor = style.accent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            if (searching || rebuilding) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                    color = style.accent
                )
            } else {
                Text(
                    text = "↻",
                    color = style.accent,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .clickable { viewModel.rebuildIndex() }
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                )
            }
        }
        if (rebuilding) {
            Text(
                text = rebuildProgress,
                color = style.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else if (rebuildProgress.isNotBlank()) {
            Text(
                text = rebuildProgress,
                color = style.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        when {
            query.isBlank() -> {
                EmptyHint(
                    text = stringResource(R.string.library_search_index_count, indexedCount),
                    style = style
                )
            }
            items.isEmpty() && !searching -> {
                EmptyHint(
                    text = stringResource(R.string.library_search_empty),
                    style = style
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items, key = { "${it.bookUrl}#${it.chapterIndex}" }) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.getBookUrlProgress(item) { bookUrl ->
                                        context.startActivity(
                                            Intent(context, ReadBookActivity::class.java)
                                                .putExtra("bookUrl", bookUrl)
                                        )
                                    }
                                }
                                .background(style.fieldSurface, RoundedCornerShape(style.actionRadius))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (item.showHeader) {
                                Text(
                                    text = buildString {
                                        append(item.bookName)
                                        if (item.author.isNotBlank()) {
                                            append(" · ")
                                            append(item.author)
                                        }
                                    },
                                    color = style.accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Text(
                                text = item.chapterTitle,
                                color = style.secondaryText,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            HighlightSnippet(item.snippetText, style)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, style: io.legado.app.ui.widget.compose.AppDialogStyle) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(text = text, color = style.secondaryText, fontSize = 14.sp)
    }
}

/**
 * 解析 snippet() 返回的 ⟦ ⟧ 标记，命中片段高亮。
 */
@Composable
private fun HighlightSnippet(snippetText: String, style: io.legado.app.ui.widget.compose.AppDialogStyle) {
    val annotated = buildAnnotatedString {
        var highlight = false
        var buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        color = if (highlight) style.accent else style.primaryText,
                        fontWeight = if (highlight) FontWeight.Bold else null
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
    Text(text = annotated, fontSize = 13.sp, lineHeight = 19.sp)
}
