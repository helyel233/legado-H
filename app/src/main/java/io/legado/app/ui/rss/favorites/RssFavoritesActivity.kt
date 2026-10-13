package io.legado.app.ui.rss.favorites

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.ui.host.AppAsyncImage
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/**
 * 收藏夹（RSS 收藏文章）。
 *
 * 自旧 View 体系（ViewPager + TabLayout + Fragment + Glide 列表）迁至 V5：
 * 分组切换用 AppFilterChipRow（横滑 chips），列表用 LazyColumn + AppAsyncImage，
 * 阅读跳旧 ReadRssActivity/VideoPlayer 体系，长按删单条、溢出菜单删当前组/删全部。
 */
class RssFavoritesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { RssFavoritesScreen() }
    }
}

@Composable
private fun RssFavoritesScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val density = visual.density

    val groups by remember { appDb.rssStarDao.flowGroups() }
        .collectAsState(initial = emptyList())

    var selectedGroup by remember { mutableStateOf<String?>(null) }
    // 选中分组被删后自动回退到第一个；无任何分组时 currentGroup 为 null
    val currentGroup = selectedGroup?.takeIf { it in groups } ?: groups.firstOrNull()

    val articles by remember(currentGroup) {
        if (currentGroup == null) {
            flowOf(emptyList())
        } else {
            appDb.rssStarDao.flowByGroup(currentGroup).catch {
                AppLog.put("订阅收藏获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(Dispatchers.IO)
        }
    }.collectAsState(initial = emptyList())

    var menuOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    HostV5Page(
        title = context.getString(R.string.favorites),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = context.getString(R.string.v5_more)) {
                menuOpen = true
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (groups.size > 1) {
                AppFilterChipRow(
                    chips = groups.map { g -> AppFilterChip(g, g == currentGroup) },
                    onChipClick = { index -> selectedGroup = groups[index] },
                )
                Spacer(Modifier.height(density.blockGap))
            }
            if (articles.isEmpty()) {
                AppEmpty(title = context.getString(R.string.favorites))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = density.screenPadding,
                        end = density.screenPadding,
                        top = density.blockGap / 2,
                        bottom = density.screenPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    items(articles, key = { "${it.origin}|${it.link}" }) { article ->
                        RssStarRow(
                            article = article,
                            onClick = { ReadRss.readRss(activity, article.toRssArticle()) },
                            onLongClick = { deleteTarget = DeleteTarget.Single(article) },
                        )
                    }
                }
            }
        }
    }

    if (menuOpen) {
        AppMenuSheet(
            title = context.getString(R.string.favorites),
            actions = buildList {
                currentGroup?.let { group ->
                    add(
                        context.getString(R.string.delete_select_group) to {
                            deleteTarget = DeleteTarget.Group(group)
                        }
                    )
                }
                add(context.getString(R.string.delete_all) to { deleteTarget = DeleteTarget.All })
            },
            onDismiss = { menuOpen = false },
        )
    }

    deleteTarget?.let { target ->
        val message = when (target) {
            is DeleteTarget.Single ->
                context.getString(R.string.sure_del) + "\n<" + target.item.title + ">"

            is DeleteTarget.Group ->
                context.getString(R.string.sure_del) + "\n<" + target.group + ">" +
                    context.getString(R.string.group)

            DeleteTarget.All ->
                context.getString(R.string.sure_del) + "\n<" + context.getString(R.string.all) +
                    ">" + context.getString(R.string.favorite)
        }
        AppConfirmDialog(
            title = context.getString(R.string.v5_delete),
            message = message,
            confirmLabel = context.getString(R.string.v5_delete),
            cancelLabel = context.getString(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                when (target) {
                    is DeleteTarget.Single ->
                        appDb.rssStarDao.delete(target.item.origin, target.item.link)

                    is DeleteTarget.Group -> appDb.rssStarDao.deleteByGroup(target.group)
                    DeleteTarget.All -> appDb.rssStarDao.deleteAll()
                }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun RssStarRow(
    article: RssStar,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.CARD))
            .background(colors.surface.copy(alpha = 0.72f), visual.shape(ShapeKind.CARD))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(density.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.blockGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = article.title,
                style = visual.type.body,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val pubDate = article.pubDate
            if (!pubDate.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pubDate,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!article.image.isNullOrBlank()) {
            AppAsyncImage(
                model = article.image,
                contentDescription = null,
                sourceOrigin = article.origin,
                cornerRadius = 8.dp,
                modifier = Modifier.size(width = 96.dp, height = 64.dp),
            )
        }
    }
}

private sealed class DeleteTarget {
    class Single(val item: RssStar) : DeleteTarget()
    class Group(val group: String) : DeleteTarget()
    object All : DeleteTarget()
}
