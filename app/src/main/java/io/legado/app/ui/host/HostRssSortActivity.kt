package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.legado.app.R
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LiveData
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import dev.chrisbanes.haze.HazeState
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.removeSortCache
import io.legado.app.help.source.sortUrls
import io.legado.app.ui.host.pack.V5PackageStore
import io.legado.app.ui.rss.article.RssArticlesViewModel
import io.legado.app.ui.host.HostSourceLoginActivity
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppSkeletonList
import io.legado.app.uikit.v5.resolve.LayoutResolver
import io.legado.app.uikit.v5.resolve.ThemeResolver
import io.legado.app.uikit.v5.runtime.LocalHazeState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.share
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4 订阅页 V5 化第二阶段：排序 + 文章列表 V5 原生页。
 *
 * 取代旧 RssSortActivity（View 体系 TabLayout + ViewPager + 4 套文章卡片 XML）。
 * 数据层全复用：
 *  - 排序列表：RssSource.sortUrls()（规则解析，与旧页同一实现）
 *  - 文章加载：Rss.getArticles（经 [RssArticlesViewModel]，与旧页同一实现）
 *  - 文章呈现：rssArticleDao.flowByOriginSort reactive Flow → V5 卡片
 * 文章阅读（ReadRssActivity WebView）与视频/图片分发仍走旧体系（ReadRss.readRss）。
 */
class HostRssSortActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context, sourceUrl: String, sortUrl: String? = null, key: String? = null) {
            context.startActivity(
                Intent(context, HostRssSortActivity::class.java).apply {
                    putExtra("sourceUrl", sourceUrl)
                    sortUrl?.let { putExtra("sortUrl", it) }
                    key?.let { putExtra("key", it) }
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostRssSortScreen() }
    }
}

/** V5 取值环境（与 AppHostActivity.HostRoot 同源）：主题/界面包 + 字体 + 玻璃状态。 */
@Composable
internal fun HostV5ThemeRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember { V5PackageStore.init(context) }

    val baseSpec = LayoutResolver.spec
    val visual = remember(baseSpec, ThemeResolver.revision) {
        baseSpec.copy(
            type = baseSpec.type.copy(
                uiFont = ThemeResolver.uiFontFamily(),
                titleFont = ThemeResolver.titleFontFamily(),
            )
        )
    }
    val colors = ThemeResolver.colors()
    val hazeState = remember { HazeState() }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalVisualSpec provides visual,
        LocalSemanticColors provides colors,
        LocalHazeState provides hazeState,
    ) {
        content()
    }
}

@Composable
private fun HostRssSortScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density

    val sourceUrl = remember { activity.intent.getStringExtra("sourceUrl") ?: "" }
    val sortUrlExtra = remember { activity.intent.getStringExtra("sortUrl") }
    // P4-4 补全：源内搜索（旧 RssSortActivity 的 menu_search）改为可交互状态
    var searchKey by remember { mutableStateOf(activity.intent.getStringExtra("key")) }
    var sortsReloadKey by remember { mutableIntStateOf(0) }
    var favorite by remember { mutableStateOf(false) }
    var compactLayout by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchInputOpen by remember { mutableStateOf(false) }

    val source by produceState<RssSource?>(null, sourceUrl) {
        value = withContext(Dispatchers.IO) { appDb.rssSourceDao.getByKey(sourceUrl) }
    }

    // 收藏状态（与旧 favoriteCurrentSource 同口径）
    LaunchedEffect(source) {
        val s = source ?: return@LaunchedEffect
        favorite = withContext(Dispatchers.IO) {
            appDb.rssStarDao.get(s.sourceUrl, s.sourceUrl) != null
        }
    }

    // 排序列表（与旧 RssSortActivity.upFragments 同逻辑）
    val sorts by produceState<List<Pair<String, String>>>(emptyList(), sourceUrl, sortsReloadKey, searchKey) {
        val s = source ?: appDb.rssSourceDao.getByKey(sourceUrl) ?: return@produceState
        value = runCatching {
            when {
                searchKey != null -> {
                    val url = s.searchUrl
                    if (url.isNullOrBlank()) emptyList() else listOf("搜索" to url)
                }
                !sortUrlExtra.isNullOrBlank() -> runCatching {
                    GSONStrict.fromJsonObject<Map<String, String>>(sortUrlExtra)
                        .getOrThrow().map { Pair(it.key, it.value) }
                }.getOrElse { listOf("" to sortUrlExtra) }
                else -> s.sortUrls()
            }
        }.getOrElse { emptyList() }
    }

    var selectedSort by remember(sorts) { mutableStateOf(0) }
    val currentSort = sorts.getOrNull(selectedSort)

    // 文章加载 VM（与旧页同一实现）
    val vm = remember { ViewModelProvider(activity)[RssArticlesViewModel::class.java] }
    val loadFinally by vm.loadFinallyLiveData.observeAsStateCompat(false)
    val loadError by vm.loadErrorLiveData.observeAsStateCompat<String?>(null)

    // 切分类 → 重新 init + 首次加载
    LaunchedEffect(currentSort, source) {
        val s = source ?: return@LaunchedEffect
        val sort = currentSort ?: return@LaunchedEffect
        vm.init(Bundle().apply {
            putString("sortName", sort.first)
            putString("sortUrl", sort.second)
            putString("searchKey", searchKey)
        })
        vm.loadArticles(s)
    }

    // 文章列表（DB Flow，加载结果经 VM 落库后回流）
    val sortName = currentSort?.first ?: ""
    val articles by remember(sourceUrl, sortName) {
        appDb.rssArticleDao.flowByOriginSort(sourceUrl, sortName)
            .catch { emit(emptyList()) }
            .flowOn(Dispatchers.IO)
    }.collectAsState(initial = emptyList())

    // 分页：滚动到倒数 6 条内触发 loadMore
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= articles.size - 6
        }
    }
    LaunchedEffect(nearEnd, loadFinally, articles.size) {
        if (nearEnd && loadFinally && !vm.isLoading) {
            source?.let { vm.loadMore(it) }
        }
    }

    HostV5Page(
        title = source?.sourceName
            ?: sorts.firstOrNull()?.first?.ifBlank { stringResource(R.string.v5_rss_fallback_title) }
            ?: stringResource(R.string.v5_rss_fallback_title),
        subtitle = if (sorts.size > 1) stringResource(R.string.v5_rss_sorts_count, sorts.size, articles.size) else null,
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // 分类 chips（单分类/搜索时不显示）
            if (sorts.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = density.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    sorts.forEachIndexed { i, (name, _) ->
                        AppChip(name.ifBlank { "全部" }, selected = i == selectedSort, onClick = {
                            selectedSort = i
                        })
                    }
                }
                Spacer(Modifier.height(density.blockGap / 2))
            }

            when {
                source == null -> AppSkeletonList(rows = 4)
                sorts.isEmpty() -> AppEmpty(
                    title = "没有可用分类",
                    supporting = "检查源的排序规则，或刷新排序缓存",
                )
                loadError != null && articles.isEmpty() -> AppEmpty(
                    title = "加载失败",
                    supporting = loadError?.lineSequence()?.firstOrNull() ?: "源连接失败",
                )
                articles.isEmpty() -> AppSkeletonList(rows = 5)
                else -> {
                AppFastScroller(
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
                    verticalArrangement = Arrangement.spacedBy(density.blockGap),
                ) {
                    items(articles, key = { it.link }) { article ->
                        RssArticleCard(article = article, compact = compactLayout) {
                            ReadRss.readRss(activity, article, source)
                        }
                    }
                    if (loadFinally) {
                        item(key = "footer") {
                            Text(
                                text = stringResource(R.string.v5_rss_loading_more),
                                style = visual.type.caption,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
                }
                }
            }
        }
    }

    // ---------------- P4-4 补全：旧 RssSortActivity 的 10 个菜单 ----------------
    if (menuOpen) {
        val s = source
        AppMenuSheet(
            title = s?.sourceName ?: "订阅",
            actions = buildList {
                if (s != null && !s.searchUrl.isNullOrBlank()) {
                    add(stringResource(R.string.v5_rss_search_in_source) to { searchInputOpen = true })
                }
                add((if (favorite) "✓ " else "") + stringResource(R.string.v5_rss_favorite) to {
                    val src = source ?: return@to
                    activity.lifecycleScope.launch {
                        val added = withContext(Dispatchers.IO) {
                            if (appDb.rssStarDao.get(src.sourceUrl, src.sourceUrl) != null) {
                                appDb.rssStarDao.delete(src.sourceUrl, src.sourceUrl)
                                false
                            } else {
                                appDb.rssStarDao.insert(
                                    io.legado.app.data.entities.RssStar(
                                        origin = src.sourceUrl,
                                        sort = "",
                                        title = src.sourceName,
                                        starTime = System.currentTimeMillis(),
                                        link = src.sourceUrl,
                                        type = 0,
                                    )
                                )
                                true
                            }
                        }
                        favorite = added
                        activity.toastOnUi(
                            if (added) R.string.in_favorites else R.string.out_favorites
                        )
                    }
                })
                add(stringResource(R.string.v5_rss_share_source) to {
                    source?.sourceUrl?.let { context.share(it) }
                })
                if (s != null && !s.loginUrl.isNullOrBlank()) {
                    add(stringResource(R.string.v5_rss_login) to {
                        context.startActivity<io.legado.app.ui.host.HostSourceLoginActivity> {
                            putExtra("type", "rssSource")
                            putExtra("key", s.sourceUrl)
                        }
                    })
                }
                add(stringResource(R.string.v5_rss_refresh_sort) to {
                    menuOpen = false
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { source?.removeSortCache(); Unit }
                        sortsReloadKey++
                    }
                })
                add(stringResource(R.string.v5_rss_edit_source) to {
                    menuOpen = false
                    context.startActivity<io.legado.app.ui.rss.source.edit.RssSourceEditActivity> {
                        putExtra("sourceUrl", sourceUrl)
                    }
                })
                add(stringResource(R.string.v5_rss_set_variable) to {
                    menuOpen = false
                    val src = source ?: return@to
                    activity.lifecycleScope.launch {
                        val variable = withContext(Dispatchers.IO) { src.getVariable() }
                        io.legado.app.ui.widget.dialog.VariableDialog(
                            activity.getString(R.string.set_source_variable),
                            src.getKey(),
                            variable,
                            src.getDisplayVariableComment(
                                activity.getString(R.string.source_variable_hint)
                            ),
                        ).show(activity.supportFragmentManager, "variable")
                    }
                })
                add((if (compactLayout) "✓ " else "") + stringResource(R.string.v5_rss_switch_layout) to {
                    compactLayout = !compactLayout
                    menuOpen = false
                })
                add(stringResource(R.string.v5_rss_read_record) to {
                    menuOpen = false
                    io.legado.app.ui.rss.article.ReadRecordDialog(sourceUrl)
                        .show(activity.supportFragmentManager, "readRecord")
                })
                add(stringResource(R.string.v5_rss_clear_articles) to {
                    menuOpen = false
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            appDb.rssArticleDao.delete(sourceUrl); Unit
                        }
                    }
                })
            },
            onDismiss = { menuOpen = false },
        )
    }

    // 源内搜索输入（AppInputDialog 统一件）
    if (searchInputOpen) {
        var query by remember { mutableStateOf(searchKey ?: "") }
        AppInputDialog(
            title = stringResource(R.string.v5_rss_search_in_source),
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.v5_rss_search_hint),
            confirmLabel = stringResource(R.string.v5_ok),
            cancelLabel = stringResource(R.string.v5_cancel),
            onConfirm = { text ->
                searchKey = text.trim().ifBlank { null }
                searchInputOpen = false
            },
            onDismiss = { searchInputOpen = false },
        )
    }
}

/** V5 文章卡：缩略图 + 标题 + 元信息，已读弱化。 */
@Composable
private fun RssArticleCard(
    article: RssArticle,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val hasImage = !compact && !article.image.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.CARD))
            .background(colors.surface.copy(alpha = 0.72f), visual.shape(ShapeKind.CARD))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(density.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = article.title,
                style = visual.type.title,
                color = if (article.read) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 2,
            )
            article.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            article.pubDate?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = visual.type.caption, color = colors.onSurfaceVariant)
            }
        }
        if (hasImage) {
            io.legado.app.ui.widget.compose.BookCoverImage(
                path = article.image,
                name = article.title,
                author = null,
                sourceOrigin = article.origin,
                modifier = Modifier
                    .padding(start = density.blockGap)
                    .size(width = 86.dp, height = 60.dp)
                    .clip(visual.shape(ShapeKind.TAG)),
                fillBounds = true,
            )
        }
    }
}

/** LiveData → Compose State（app 未引入 runtime-livedata，手写轻量观察）。 */
@Composable
internal fun <T> LiveData<T>.observeAsStateCompat(initial: T): androidx.compose.runtime.State<T> {
    val state = androidx.compose.runtime.remember(this) { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.runtime.DisposableEffect(this) {
        val observer = androidx.lifecycle.Observer<T> { state.value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}
