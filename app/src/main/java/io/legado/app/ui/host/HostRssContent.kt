package io.legado.app.ui.host

import io.legado.app.R

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.rss.RssViewModel
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.openUrl
import io.legado.app.utils.startActivity

/**
 * P4：订阅 Tab V5 化。
 *
 * 策略与发现页一致：**源列表 V5 化，阅读呈现跳旧 WebView 体系**——
 * RSS 阅读页（ReadRssActivity/RssSortActivity）是 WebView 渲染 + 源脚本的
 * 长尾保留项，本轮不动。
 *
 * 数据层：appDb.rssSourceDao 的 reactive Flow 直接驱动，无中间 ViewModel 状态。
 * 源管理跳旧 RssSourceActivity（P4-3 批次范围）。
 */
class HostRssContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("订阅"),
        subtitle = DestTitle.Res(R.string.v5_rss_subtitle),
        showBack = false,
        contentOwnsScroll = true,
        actions = listOf(
            // 2026-10-12 功能盘点修复：旧 RssFragment ⋮ 菜单的收藏列表/源管理入口此前整组丢失
            ChromeAction(
                id = "favorites",
                icon = DestIcon.Vector(io.legado.app.ui.host.HostIcons.Star),
                contentDescription = DestTitle.Res(R.string.v5_rss_favorite),
                onClick = {
                    splitties.init.appCtx.startActivity<io.legado.app.ui.rss.favorites.RssFavoritesActivity>()
                },
            ),
            ChromeAction(
                id = "manage",
                icon = DestIcon.Vector(io.legado.app.ui.host.HostIcons.Menu),
                contentDescription = DestTitle.Res(R.string.v5_rss_manage),
                onClick = {
                    splitties.init.appCtx.startActivity<io.legado.app.ui.rss.source.manage.RssSourceActivity>()
                },
            ),
        ),
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current
        val colors = LocalSemanticColors.current
        val viewModel = remember {
            ViewModelProvider(context as androidx.lifecycle.ViewModelStoreOwner)[RssViewModel::class.java]
        }

        var searchOn by remember { mutableStateOf(false) }
        var searchKey by remember { mutableStateOf("") }
        var group by remember { mutableStateOf<String?>(null) }
        // 布局 1/2/3 列，沿用旧 AppConfig.rssSourceLayout 键（与旧壳互通）
        var columns by remember { mutableStateOf(AppConfig.rssSourceLayout.coerceIn(1, 3)) }

        val sources by remember(searchKey, group) {
            when {
                group != null -> appDb.rssSourceDao.flowEnabledByGroup(group!!)
                searchKey.isNotBlank() -> appDb.rssSourceDao.flowEnabled(searchKey.trim())
                else -> appDb.rssSourceDao.flowEnabled()
            }
        }.collectAsState(initial = emptyList())

        val groups by remember { appDb.rssSourceDao.flowEnabledGroups() }
            .collectAsState(initial = emptyList())

        Column(Modifier.fillMaxSize()) {
            // 分组筛选 + 搜索开关 + 布局切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scope.density.screenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(scope.density.blockGap / 2),
                ) {
                    AppChip("全部", group == null && searchKey.isBlank(), onClick = {
                        group = null
                        searchKey = ""
                    })
                    groups.forEach { g ->
                        AppChip(g, group == g, onClick = { group = if (group == g) null else g })
                    }
                }
                Text(
                    text = if (searchOn) "收起" else "搜索",
                    style = scope.type.label,
                    color = colors.primary,
                    modifier = Modifier
                        .padding(start = scope.density.blockGap)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            searchOn = !searchOn
                            if (!searchOn) searchKey = ""
                        },
                )
                Text(
                    text = stringResource(R.string.v5_rss_columns, columns),
                    style = scope.type.label,
                    color = colors.primary,
                    modifier = Modifier
                        .padding(start = scope.density.blockGap)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            columns = columns % 3 + 1
                            AppConfig.rssSourceLayout = columns
                        },
                )
            }

            if (searchOn) {
                AppSearchField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    modifier = Modifier.padding(
                        horizontal = scope.density.screenPadding,
                        vertical = scope.density.blockGap / 2,
                    ),
                    placeholder = "搜索源名称",
                )
            }

            Spacer(Modifier.height(scope.density.blockGap / 2))

            if (sources.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.v5_rss_no_sources_title),
                    supporting = stringResource(R.string.v5_rss_no_sources_supporting),
                    actionLabel = stringResource(R.string.v5_rss_manage),
                    onAction = {
                        context.startActivity<io.legado.app.ui.rss.source.manage.RssSourceActivity>()
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = scope.density.screenPadding,
                        end = scope.density.screenPadding,
                        bottom = scope.density.screenPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(scope.density.blockGap),
                    verticalArrangement = Arrangement.spacedBy(scope.density.blockGap),
                ) {
                    items(sources, key = { it.sourceUrl }) { source ->
                        RssSourceCard(
                            scope = scope,
                            source = source,
                            compact = columns >= 3,
                            onClick = { openRssSource(context, viewModel, source) },
                            onManage = {
                                context.startActivity<io.legado.app.ui.rss.source.manage.RssSourceActivity>()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RssSourceCard(
    scope: ContentScope,
    source: RssSource,
    compact: Boolean,
    onClick: () -> Unit,
    onManage: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .clip(scope.shape(ShapeKind.CARD))
            .background(colors.surface.copy(alpha = 0.72f))
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
                onLongClick = onManage,
            )
            .padding(scope.density.screenPadding / 2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 源标（首字圆形徽标）
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(colors.primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = source.sourceName.firstOrNull()?.toString() ?: "R",
                style = scope.type.title,
                color = colors.primary,
            )
        }
        Spacer(Modifier.height(scope.density.blockGap / 2))
        Text(
            text = source.sourceName,
            style = if (compact) scope.type.caption else scope.type.body,
            color = colors.onSurface,
            maxLines = 1,
        )
        if (!compact && !source.sourceGroup.isNullOrBlank()) {
            Text(
                text = source.sourceGroup ?: "",
                style = scope.type.caption,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 打开源：与旧壳 openRssLegacy 同逻辑（singleUrl 直达 / startHtml / 分类页）。 */
private fun openRssSource(
    context: android.content.Context,
    viewModel: RssViewModel,
    source: RssSource,
) {
    if (source.singleUrl) {
        viewModel.getSingleUrl(source) { url ->
            if (url.startsWith("http", true)) {
                io.legado.app.ui.rss.read.ReadRssActivity.start(
                    context, true, source.sourceUrl, source.sourceName, url,
                )
            } else {
                context.openUrl(url)
            }
        }
    } else {
        viewModel.launchRssWithHtml(source, {
            // V5 原生排序页（P4 第二阶段）；仅当源带 startHtml 时直接进旧阅读页
            HostRssSortActivity.start(context, source.sourceUrl)
        }) { html ->
            io.legado.app.ui.rss.read.ReadRssActivity.start(
                context, true, source.sourceUrl, source.sourceName, startHtml = html,
            )
        }
    }
}
