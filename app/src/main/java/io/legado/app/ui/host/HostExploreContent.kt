package io.legado.app.ui.host

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.exploreKinds
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSkeletonList
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.startActivity

/**
 * P4：发现 Tab V5 化。
 *
 * 策略与订阅页一致：**源与分类入口 V5 化，内容页跳旧**——
 * 发现结果页（ExploreShowActivity，封面网格 + 分页 + 缓存）是重逻辑页，
 * 已通过 P4-0 桥接吃 V5 主题，本轮不动其内部。
 *
 * 数据层：appDb.bookSourceDao.flowExplore()（启用发现的书源）+
 * BookSourcePart.exploreKinds()（分类，带磁盘缓存）。
 */
class HostExploreContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("发现"),
        subtitle = DestTitle.Text("书源分类聚合入口"),
        showBack = false,
        contentOwnsScroll = true,
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current
        val colors = LocalSemanticColors.current

        var searchKey by remember { mutableStateOf("") }

        val sources by remember(searchKey) {
            if (searchKey.isBlank()) appDb.bookSourceDao.flowExplore()
            else appDb.bookSourceDao.flowExplore(searchKey.trim())
        }.collectAsState(initial = emptyList())

        Column(Modifier.fillMaxSize()) {
            AppSearchField(
                value = searchKey,
                onValueChange = { searchKey = it },
                modifier = Modifier.padding(horizontal = scope.density.screenPadding),
                placeholder = "搜索书源",
            )
            Spacer(Modifier.height(scope.density.blockGap / 2))

            if (sources.isEmpty()) {
                AppEmpty(
                    title = "没有启用发现的源",
                    supporting = "给书源勾选「启用发现」，或先导入书源",
                    actionLabel = "书源管理",
                    onAction = {
                        context.startActivity<io.legado.app.ui.book.source.manage.BookSourceActivity>()
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = scope.density.screenPadding,
                        end = scope.density.screenPadding,
                        bottom = scope.density.screenPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(scope.density.blockGap),
                ) {
                    items(sources, key = { it.bookSourceUrl }) { source ->
                        ExploreSourceCard(
                            scope = scope,
                            source = source,
                            onKind = { kind ->
                                context.startActivity<io.legado.app.ui.book.explore.ExploreShowActivity> {
                                    putExtra("exploreName", kind.title)
                                    putExtra("sourceUrl", source.bookSourceUrl)
                                    putExtra("exploreUrl", kind.url ?: "")
                                }
                            },
                            onManage = {
                                context.startActivity<io.legado.app.ui.book.source.manage.BookSourceActivity>()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreSourceCard(
    scope: ContentScope,
    source: BookSourcePart,
    onKind: (ExploreKind) -> Unit,
    onManage: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    var kinds by remember { mutableStateOf<List<ExploreKind>?>(null) }
    val sourceGroup = source.bookSourceGroup

    LaunchedEffect(source.bookSourceUrl) {
        kinds = runCatching { source.exploreKinds() }.getOrNull()
    }

    scope.Surface(level = MaterialLevel.RAISED, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(scope.density.cardPadding)) {
            Text(
                text = source.bookSourceName,
                style = scope.type.title,
                color = colors.onSurface,
                maxLines = 1,
                modifier = Modifier.combinedClickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                    onLongClick = onManage,
                ),
            )
            if (!sourceGroup.isNullOrBlank()) {
                Text(
                    text = sourceGroup,
                    style = scope.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(scope.density.blockGap / 2))
            val list = kinds
            when {
                list == null -> AppSkeletonList(rows = 1)
                list.isEmpty() -> Text(
                    "无可用分类",
                    style = scope.type.caption,
                    color = colors.onSurfaceVariant,
                )
                else -> Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(scope.density.blockGap / 2),
                ) {
                    list.take(10).forEach { kind ->
                        AppChip(kind.title, selected = false, onClick = { onKind(kind) })
                    }
                    if (list.size > 10) {
                        Text(
                            "+${list.size - 10}",
                            style = scope.type.caption,
                            color = colors.primary,
                            modifier = Modifier
                                .clip(scope.shape(ShapeKind.TAG))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { onKind(list.first()) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
