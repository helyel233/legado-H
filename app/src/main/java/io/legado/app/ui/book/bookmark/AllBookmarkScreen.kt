package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage

/**
 * 全部书签列表。
 *
 * 玻璃化口径（与阅读记录页、设置类列表同源）：
 * - 页面根背景走 [glassPageBackgroundColor]：壁纸可见时透明，让壁纸真的透到列表后面；
 *   无背景图 / 墨水屏保持主题页色，观感与改动前逐值一致；
 * - **行与 stickyHeader 都用 [GlassPanel] 垫底**，而不是把 `palette.row` 直接丢掉换成
 *   透明：书签内容（章节名 / 正文 / 备注）是「贴在壁纸上的三段文字」，直接透明压壁纸
 *   在深色壁纸上会读不出来。stickyHeader 更是必须有不透明衬底——它停留期间下方行会
 *   从它下面滚过，透明头会把两段文字叠在一起；
 * - 圆角取 0：这是通栏连续行（上下无缝相连，靠行底分色分界），只有圆角 0 才保持
 *   原有行几何，与目录页章节行「无缝相连取圆角 0」同一口径；
 * - solid 档 / 墨水屏 / 低内存 / 无壁纸由 `GlassPanel(requireWallpaperHost = true)` 与
 *   它的 fallback 逐值回退到原来的实底行（`palette.row` / 主题页色）。
 *
 * 纯 Compose LazyColumn，没有 RecyclerView 复用与 Adapter 签名问题：`GlassPanel` 的
 * 每个宿主都是独立 View，采样走壁纸域位图（不录制自身或祖先子树），列表滚动时由
 * `GlassPositionTracker` 的 OnPreDraw 跟随位置重采样。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllBookmarkScreen(
    bookmarks: List<Bookmark>,
    onItemClick: (Bookmark, Int) -> Unit,
    onItemLongClick: (Bookmark, Int) -> Unit
) {
    val context = LocalContext.current
    val palette = rememberAppSettingPalette()
    val headerBg = Color(context.backgroundColor)
    val headerTextColor = Color(context.accentColor)
    // 壁纸可见性：与页面根背景透明、行玻璃三处同一判定（BaseActivity 的根背景策略）
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    // 每个页面算一次签名即可，不要在每一行里各注册一个偏好监听
    val glassSignature = rememberGlassConfigSignature()

    val grouped = remember(bookmarks) {
        bookmarks.groupBy { "${it.bookName}\u0000${it.bookAuthor}" }
    }
    val indexMap = remember(bookmarks) {
        bookmarks.withIndex().associate { (index, item) -> item.time to index }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(glassPageBackgroundColor(palette.page, wallpaperVisible))
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        grouped.forEach { (_, items) ->
            val headerText = items.firstOrNull()
                ?.let { "${it.bookName}(${it.bookAuthor})" }
                .orEmpty()
            val groupKey = items.firstOrNull()
                ?.let { "${it.bookName}\u0000${it.bookAuthor}" }
                .orEmpty()

            stickyHeader(key = "header_$groupKey") {
                GlassPanel(
                    domain = GlassSamplingDomain.WALLPAPER,
                    cornerRadiusPx = 0f,
                    configSignature = glassSignature,
                    // 页面内表面：必须先问「这张卡背后真的有壁纸吗」
                    requireWallpaperHost = true,
                    modifier = Modifier.fillMaxWidth(),
                    fallback = Modifier.background(headerBg)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = headerText,
                            color = headerTextColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            itemsIndexed(items, key = { _, item ->
                "${item.time}_${item.bookName}_${item.chapterIndex}_${item.chapterPos}"
            }) { _, bookmark ->
                val globalIndex = indexMap[bookmark.time] ?: 0
                BookmarkItemRow(
                    bookmark = bookmark,
                    glassSignature = glassSignature,
                    onClick = { onItemClick(bookmark, globalIndex) },
                    onLongClick = { onItemLongClick(bookmark, globalIndex) }
                )
            }
        }
    }
}

@Composable
private fun BookmarkItemRow(
    bookmark: Bookmark,
    glassSignature: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    GlassPanel(
        domain = GlassSamplingDomain.WALLPAPER,
        cornerRadiusPx = 0f,
        configSignature = glassSignature,
        requireWallpaperHost = true,
        modifier = Modifier.fillMaxWidth(),
        fallback = Modifier.background(Color(palette.row))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = bookmark.chapterName,
                color = palette.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (bookmark.bookText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bookmark.bookText,
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (bookmark.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bookmark.content,
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
