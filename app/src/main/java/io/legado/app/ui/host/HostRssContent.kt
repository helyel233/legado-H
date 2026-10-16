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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppMenuSheet
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
                    io.legado.app.ui.host.HostRssSourceManageActivity.start(splitties.init.appCtx)
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
        // 长按源卡片弹出操作菜单（旧 RssAdapter 长按同款：编辑/置顶/登录/禁用源/删除）
        var rssMenuSource by remember { mutableStateOf<RssSource?>(null) }
        var rssDeleteTarget by remember { mutableStateOf<RssSource?>(null) }

        val sources by remember(searchKey, group) {
            when {
                group != null -> appDb.rssSourceDao.flowEnabledByGroup(group!!)
                searchKey.isNotBlank() -> appDb.rssSourceDao.flowEnabled(searchKey.trim())
                else -> appDb.rssSourceDao.flowEnabled()
            }
        }.collectAsStateWithLifecycle(initialValue = emptyList())

        val groups by remember { appDb.rssSourceDao.flowEnabledGroups() }
            .collectAsStateWithLifecycle(initialValue = emptyList())

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
                        io.legado.app.ui.host.HostRssSourceManageActivity.start(context)
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
                            onLongClick = { rssMenuSource = source },
                        )
                    }
                }
            }
        }

        // 长按菜单（旧版 rss_main_item 同款五项；登录仅源配了登录地址时出现）
        val menuRss = rssMenuSource
        if (menuRss != null) {
            AppMenuSheet(
                title = menuRss.sourceName,
                actions = buildList {
                    add(stringResource(R.string.edit) to {
                        context.startActivity<RssSourceEditActivity> {
                            putExtra("sourceUrl", menuRss.sourceUrl)
                        }
                    })
                    add(stringResource(R.string.to_top) to { viewModel.topSource(menuRss) })
                    if (!menuRss.loginUrl.isNullOrBlank()) {
                        add(stringResource(R.string.login) to {
                            context.startActivity<HostSourceLoginActivity> {
                                putExtra("type", "rssSource")
                                putExtra("key", menuRss.sourceUrl)
                            }
                        })
                    }
                    add(stringResource(R.string.disable_source) to {
                        viewModel.disable(menuRss)
                    })
                    add(stringResource(R.string.delete) to { rssDeleteTarget = menuRss })
                },
                onDismiss = { rssMenuSource = null },
            )
        }

        // 删除源确认（B 门：危险操作必须确认）
        val deleteRss = rssDeleteTarget
        if (deleteRss != null) {
            AppConfirmDialog(
                title = stringResource(R.string.delete),
                message = stringResource(R.string.sure_del) + "\n" + deleteRss.sourceName,
                confirmLabel = stringResource(R.string.delete),
                cancelLabel = stringResource(R.string.cancel),
                danger = true,
                onConfirm = {
                    rssDeleteTarget = null
                    viewModel.del(deleteRss)
                },
                onDismiss = { rssDeleteTarget = null },
            )
        }
    }
}

@Composable
private fun RssSourceCard(
    scope: ContentScope,
    source: RssSource,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
                onLongClick = onLongClick,
            )
            .padding(scope.density.screenPadding / 2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 源标：有 sourceIcon 显图标（对齐旧 RssAdapter ImageLoader.load(sourceIcon)），
        // 无图标回落首字圆形徽标
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(colors.primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (source.sourceIcon.isNotBlank()) {
                AppAsyncImage(
                    model = source.sourceIcon,
                    contentDescription = null,
                    sourceOrigin = source.sourceUrl,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape),
                )
            } else {
                Text(
                    text = source.sourceName.firstOrNull()?.toString() ?: "R",
                    style = scope.type.title,
                    color = colors.primary,
                )
            }
        }
        Spacer(Modifier.height(scope.density.blockGap / 2))
        Text(
            text = source.sourceName,
            style = if (compact) scope.type.caption else scope.type.body,
            color = colors.onSurface,
            maxLines = 1,
        )
        // 名称与下方分组标签之间补间距（验收：间隙太小）
        Spacer(Modifier.height(3.dp))
        // 分组标签行常驻占位（无分组留空串）：保证网格内卡片等高（验收：卡片大小不一致）
        if (!compact) {
            Text(
                text = source.sourceGroup.orEmpty(),
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
                HostReadRssActivity.start(
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
            HostReadRssActivity.start(
                context, true, source.sourceUrl, source.sourceName, startHtml = html,
            )
        }
    }
}
