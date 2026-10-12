package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.bookmark.AllBookmarkViewModel
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-3：全部书签 V5 化（2026-10-12）。
 * 点击：书存在 → 跳阅读页对应位置；否则弹 BookmarkDialog。长按 → BookmarkDialog。
 * ⋮ 菜单：导出 txt / 导出 md（复用 AllBookmarkViewModel，requestCode 分流）。
 */
class HostBookmarkActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostBookmarkActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostBookmarkScreen() }
    }
}

@Composable
private fun HostBookmarkScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember { ViewModelProvider(activity)[AllBookmarkViewModel::class.java] }

    var menuOpen by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            when (result.requestCode) {
                1 -> viewModel.exportBookmark(uri)
                2 -> viewModel.exportBookmarkMd(uri)
            }
        }
    }

    val bookmarks by remember {
        appDb.bookmarkDao.flowAll()
            .catch { AppLog.put("书签管理-V5数据获取失败", it) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsState(initial = emptyList())

    fun onItemClick(bookmark: Bookmark) {
        activity.lifecycleScope.launch {
            val book = withContext(Dispatchers.IO) {
                appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
            }
            if (book == null) {
                BookmarkDialog(bookmark, -1)
                    .show(activity.supportFragmentManager, "bookmark")
            } else {
                context.startActivityForBook(book) {
                    putExtra("index", bookmark.chapterIndex)
                    putExtra("chapterPos", bookmark.chapterPos)
                }
            }
        }
    }

    HostV5Page(
        title = stringResource(R.string.bookmark),
        subtitle = stringResource(R.string.all_bookmark),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
    ) {
        if (bookmarks.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                AppEmpty(title = stringResource(R.string.empty))
            }
        } else {
            val fsState125 = rememberLazyListState()
            AppFastScroller(
                listState = fsState125,
                topContentPadding = Dp.Hairline,
                bottomContentPadding = Dp.Hairline,
                endContentPadding = Dp.Hairline,
            ) {
            LazyColumn(
                state = fsState125,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap / 2,
                    bottom = density.screenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                items(bookmarks, key = { it.time }) { bookmark ->
                    AppSelectionRow(
                        title = bookmark.bookName,
                        supporting = bookmark.chapterName + " · " + bookmark.bookText.take(40),
                        selected = false,
                        selectionMode = false,
                        onClick = { onItemClick(bookmark) },
                        onLongClick = {
                            BookmarkDialog(bookmark, -1)
                                .show(activity.supportFragmentManager, "bookmark")
                        },
                    )
                }
            }
            }
        }
    }

    // ⋮ 菜单：导出（对齐旧 bookmark 菜单，requestCode 1=txt 2=md）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.bookmark),
            actions = listOf(
                stringResource(R.string.export) to {
                    exportLauncher.launch {
                        mode = HandleFileContract.DIR
                        requestCode = 1
                    }
                    menuOpen = false
                },
                stringResource(R.string.export_md) to {
                    exportLauncher.launch {
                        mode = HandleFileContract.DIR
                        requestCode = 2
                    }
                    menuOpen = false
                },
            ),
            onDismiss = { menuOpen = false },
        )
    }
}
