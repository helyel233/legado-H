package io.legado.app.ui.host

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.main.explore.DiscoverSuiteSession
import io.legado.app.ui.main.explore.DiscoverySuite
import io.legado.app.ui.main.explore.DiscoverySuiteWidget
import io.legado.app.ui.main.explore.DiscoverySuiteWidgetSection
import io.legado.app.ui.main.explore.DiscoverySuiteWidgetTarget
import io.legado.app.ui.main.explore.withSuiteOpacityMultiplier
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.contract.ContentScope

/**
 * 主页「发现套件」接回（台账 #29-B13 裁决：套件聚合归主页域）。
 *
 * 数据全走 [DiscoverSuiteSession]（bind/stop 由 HostHomeContent 随主页进出挂/解），
 * 渲染复用 DiscoverySuiteHomeScreen 的控件级渲染件 [DiscoverySuiteWidgetSection]
 * （已提升 internal，零拷贝）。以 LazyListScope 扩展挂进主页 LazyColumn，
 * 每个控件一个 item，保持懒加载。
 */

internal fun LazyListScope.hostHomeSuiteItems(
    scope: ContentScope,
    suites: List<DiscoverySuite>,
    selectedSuite: DiscoverySuite?,
    widgetBooks: Map<String, List<SearchBook>>,
    rankedWidgetBooks: Map<String, Map<String, List<SearchBook>>>,
    loadingWidgetIds: Set<String>,
    onSuiteSelect: (DiscoverySuite) -> Unit,
    onManage: () -> Unit,
    onOpenBook: (SearchBook) -> Unit,
) {
    item(key = "suite_header") {
        SuiteHeader(
            scope = scope,
            suites = suites,
            selectedSuite = selectedSuite,
            onSuiteSelect = onSuiteSelect,
            onManage = onManage,
        )
    }
    if (selectedSuite == null || selectedSuite.widgets.isEmpty()) {
        item(key = "suite_empty") {
            AppCard {
                Column(Modifier.padding(scope.density.cardPadding)) {
                    androidx.compose.material3.Text(
                        text = "还没有可用的发现套件",
                        style = scope.type.body,
                        color = scope.color.onSurface,
                    )
                    androidx.compose.material3.Text(
                        text = "套件可聚合多来源推荐（随机牌堆/横排/瀑布/排行）",
                        style = scope.type.caption,
                        color = scope.color.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(scope.density.blockGap / 2))
                    AppGhostButton(text = "去创建", onClick = onManage)
                }
            }
        }
        return
    }
    val opacity = selectedSuite.opacityMultiplier
    selectedSuite.widgets.forEach { widget ->
        item(key = "suite_${widget.id}") {
            SuiteWidgetItem(
                opacityMultiplier = opacity,
                widget = widget,
                books = widgetBooks[widget.id].orEmpty(),
                rankedBooks = rankedWidgetBooks[widget.id].orEmpty(),
                isLoading = widget.id in loadingWidgetIds,
                onOpenBook = onOpenBook,
            )
        }
    }
}

/** 套件头：区块标题 + 管理入口；多套件时给切换 chips。 */
@Composable
private fun SuiteHeader(
    scope: ContentScope,
    suites: List<DiscoverySuite>,
    selectedSuite: DiscoverySuite?,
    onSuiteSelect: (DiscoverySuite) -> Unit,
    onManage: () -> Unit,
) {
    Column(Modifier.padding(top = scope.density.blockGap / 2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text(
                text = "发现套件",
                style = scope.type.title,
                color = scope.color.onSurface,
                modifier = Modifier.weight(1f),
            )
            AppGhostButton(text = "管理", onClick = onManage)
        }
        if (suites.size > 1) {
            Spacer(Modifier.height(scope.density.blockGap / 2))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suites, key = { it.id }) { suite ->
                    AppChip(
                        text = suite.displayName,
                        selected = suite.id == selectedSuite?.id,
                        onClick = { onSuiteSelect(suite) },
                    )
                }
            }
        }
    }
}

/** 单控件渲染：套件透明度换算 renderConfig 后委托给旧套件渲染件。 */
@Composable
private fun SuiteWidgetItem(
    opacityMultiplier: Float,
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    rankedBooks: Map<String, List<SearchBook>>,
    isLoading: Boolean,
    onOpenBook: (SearchBook) -> Unit,
) {
    val context = LocalContext.current
    val base = rememberBookshelfListRenderConfig()
    val renderConfig = remember(context, base, opacityMultiplier) {
        base.withSuiteOpacityMultiplier(context, opacityMultiplier)
    }
    DiscoverySuiteWidgetSection(
        widget = widget,
        books = books,
        rankedBooks = rankedBooks,
        isLoading = isLoading,
        renderConfig = renderConfig,
        onBookClick = onOpenBook,
        // 长按预览浮层是旧套件整页屏的能力，主页内直接落书详情
        onBookPreview = { book, _ -> onOpenBook(book) },
        onTagClick = { target -> openSuiteTarget(context, target) },
        onRefreshWidget = { DiscoverSuiteSession.refreshWidget(it) },
        onHorizontalLoadMore = { DiscoverSuiteSession.loadMoreHorizontal(it) },
        onRankedLoadMore = { w, t -> DiscoverSuiteSession.loadMoreRanked(w, t) },
    )
}

/** 与 ExploreFragment.openSuiteTarget 同跳转契约。 */
private fun openSuiteTarget(context: android.content.Context, target: DiscoverySuiteWidgetTarget) {
    if (target.sourceUrl.isBlank() || target.tagUrl.isBlank()) return
    HostExploreShowActivity.start(
        context,
        target.title.substringAfter(" - ", target.title),
        target.sourceUrl,
        target.tagUrl,
    )
}
