package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchScopeDialog
import io.legado.app.ui.book.search.SearchViewModel
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSkeletonList
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

/**
 * P4：搜索页 V5 原生化（书架/主页搜索的二级页）。
 *
 * 取代旧 SearchActivity 的呈现层（RecyclerView + databinding）。
 * 数据层全复用：[SearchViewModel]（多源聚合搜索/书架判定/历史记录）+
 * searchKeywordDao 历史流 + SearchBookOpenHelper 打开书籍（→ V5 详情页）。
 */
class HostSearchActivity : AppCompatActivity(), SearchScopeDialog.Callback {

    // 搜索范围对话框只认 Activity 回调，经此桥回 Compose 层（scopeRevision 触发重组）
    internal var pendingScope: SearchScope? = null
    internal var scopeRevision by androidx.compose.runtime.mutableStateOf(0)

    override fun onSearchScopeOk(searchScope: SearchScope) {
        pendingScope = searchScope
        scopeRevision++
    }

    companion object {
        fun start(context: Context, key: String? = null) {
            context.startActivity(
                Intent(context, HostSearchActivity::class.java).apply {
                    // chrome 动作可能来自 Application 上下文（appCtx），必须带 NEW_TASK
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    key?.let { putExtra("key", it) }
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostSearchScreen() }
    }
}

@Composable
private fun HostSearchScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density

    val vm = remember { ViewModelProvider(activity)[SearchViewModel::class.java] }
    val initialKey = remember { activity.intent.getStringExtra("key") ?: "" }
    var input by remember { mutableStateOf(initialKey) }
    var menuOpen by remember { mutableStateOf(false) }
    var scopeOpen by remember { mutableStateOf(false) }

    val results by vm.searchBookLiveData.observeAsStateCompat(emptyList())
    val isSearching by vm.isSearchLiveData.observeAsStateCompat(false)
    val searchFinished by vm.searchFinishLiveData.observeAsStateCompat<Boolean?>(null)
    // 书架状态修订号：upAdapter 变化时让已读/书架徽标重组
    val shelfRevision by vm.upAdapterLiveData.observeAsStateCompat("")

    // 历史（key 空显示全部，非空显示匹配项）
    val history by remember(input) {
        when {
            input.isBlank() -> appDb.searchKeywordDao.flowByTime()
            else -> appDb.searchKeywordDao.flowSearch(input.trim())
        }.catch { emit(emptyList()) }.flowOn(Dispatchers.IO)
    }.collectAsState(initial = emptyList())

    fun doSearch(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        input = k
        vm.search(k)
        vm.saveSearchKey(k)
    }

    LaunchedEffect(initialKey) {
        if (initialKey.isNotBlank()) vm.search(initialKey)
    }
    DisposableEffect(Unit) {
        onDispose { vm.stop() }
    }
    // 搜索范围对话框确认后：更新范围并重搜（对齐 1.7.3 menu_search_scope）
    val hostActivity = context as HostSearchActivity
    LaunchedEffect(hostActivity.scopeRevision) {
        hostActivity.pendingScope?.let { scope ->
            hostActivity.pendingScope = null
            vm.searchScope.update(scope.toString())
            doSearch(input)
        }
    }

    HostV5Page(
        title = stringResource(R.string.v5_search),
        subtitle = null,
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 搜索框（组件化：AppSearchField + onSearch；自动聚焦）
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            AppSearchField(
                value = input,
                onValueChange = { input = it },
                onSearch = { doSearch(input) },
                focusRequester = focusRequester,
                placeholder = stringResource(R.string.v5_search),
                modifier = Modifier
                    .padding(horizontal = density.screenPadding, vertical = 10.dp),
                trailing = {
                    Text(
                        text = stringResource(R.string.v5_search),
                        style = visual.type.label,
                        color = colors.primary,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) { doSearch(input) },
                    )
                },
            )

        when {
            // 无搜索动作：历史
            results.isEmpty() && !isSearching && searchFinished != true -> {
                HistorySection(
                    history = history,
                    onPick = { doSearch(it.word) },
                    onClear = { vm.clearHistory() },
                    onDelete = { vm.deleteHistory(it) },
                )
            }

            // 搜索中无结果：骨架
            isSearching && results.isEmpty() -> AppSkeletonList(rows = 5)

            // 搜索完成且无结果：空态
            searchFinished == true && results.isEmpty() -> AppEmpty(
                title = stringResource(R.string.v5_search_empty_title, input.trim()),
                supporting = stringResource(R.string.v5_search_empty_supporting),
            )

            // 结果列表
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap / 2,
                    bottom = density.screenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap),
            ) {
                if (isSearching) {
                    item(key = "searching") {
                        Text(
                            text = stringResource(R.string.v5_search_searching, AppConfig.searchScope),
                            style = visual.type.caption,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                items(results, key = { it.bookUrl }) { book ->
                    key(shelfRevision) {
                        SearchBookCard(
                            book = book,
                            inShelf = vm.isInBookShelf(book),
                            onClick = { SearchBookOpenHelper.open(context, book, true) },
                        )
                    }
                }
            }
        }
        }
    }

    // 顶栏溢出菜单（对齐 1.7.3 book_search：精确搜索/搜索范围/书源管理/日志）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.v5_search),
            actions = listOf(
                (if (activity.getPrefBoolean(PreferKey.precisionSearch)) "✓ " else "") +
                    stringResource(R.string.precision_search) to {
                    activity.putPrefBoolean(
                        PreferKey.precisionSearch,
                        !activity.getPrefBoolean(PreferKey.precisionSearch),
                    )
                    doSearch(input)
                    menuOpen = false
                },
                stringResource(R.string.groups_or_source) to {
                    scopeOpen = true
                    menuOpen = false
                },
                stringResource(R.string.book_source_manage) to {
                    context.startActivity(
                        android.content.Intent(context, HostBookSourceManageActivity::class.java),
                    )
                    menuOpen = false
                },
                stringResource(R.string.log) to {
                    AppLogDialog().show(activity.supportFragmentManager, "appLog")
                    menuOpen = false
                },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    if (scopeOpen) {
        SearchScopeDialog().show(activity.supportFragmentManager, "searchScope")
    }
}

@Composable
private fun HistorySection(
    history: List<io.legado.app.data.entities.SearchKeyword>,
    onPick: (io.legado.app.data.entities.SearchKeyword) -> Unit,
    onClear: () -> Unit,
    onDelete: (io.legado.app.data.entities.SearchKeyword) -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    if (history.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = density.screenPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.v5_search_history),
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.v5_search_clear),
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { onClear() },
        )
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = density.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
    ) {
        items(history, key = { it.word }) { kw ->
            Box {
                AppChip(kw.word, selected = false, onClick = { onPick(kw) })
                // 单条删除：右上小叉
                Text(
                    text = "×",
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) { onDelete(kw) }
                        .padding(2.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchBookCard(
    book: SearchBook,
    inShelf: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.CARD))
            .background(colors.surface.copy(alpha = 0.72f), visual.shape(ShapeKind.CARD))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(density.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCoverImage(
            path = book.coverUrl,
            name = book.name,
            author = book.author,
            sourceOrigin = book.origin,
            modifier = Modifier
                .width(52.dp)
                .height(70.dp)
                .clip(visual.shape(ShapeKind.COVER)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = density.blockGap),
        ) {
            Text(
                text = book.name,
                style = visual.type.title,
                color = colors.onSurface,
                maxLines = 1,
            )
            if (book.author.isNotBlank()) {
                Text(
                    text = book.author,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = visual.type.caption,
                    color = colors.primary,
                    maxLines = 1,
                )
            }
            book.intro?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        if (inShelf) {
            Box(
                modifier = Modifier
                    .padding(start = density.blockGap)
                    .background(colors.primary.copy(alpha = 0.14f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.v5_search_in_shelf), style = visual.type.caption, color = colors.primary)
            }
        }
    }
}
