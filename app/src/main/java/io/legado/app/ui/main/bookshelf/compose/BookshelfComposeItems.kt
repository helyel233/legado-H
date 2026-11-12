package io.legado.app.ui.main.bookshelf.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCollection
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.widget.compose.imageBgAdaptiveScrim
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.toTimeAgo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import io.legado.app.R

sealed interface BookshelfItemUi {
    val key: String
    val contentType: String
}

// @Immutable：列表项为纯展示数据（字段构造后不再变化），
// 标注后 Compose 可跳过未变化 item 的重组，是书架滑动/刷新流畅度的关键
@Immutable
data class BookshelfFolderItemUi(
    val group: BookGroup
) : BookshelfItemUi {
    override val key: String = "folder:${group.groupId}"
    override val contentType: String = "folder"
}

@Immutable
data class BookshelfCollectionItemUi(
    val collection: BookCollection,
    val bookCount: Int,
    val previewBooks: List<Book>
) : BookshelfItemUi {
    override val key: String = "collection:${collection.collectionId}"
    override val contentType: String = "collection"
}

@Immutable
data class BookshelfBookItemUi(
    val display: BookShelfDisplay,
    val isUpdating: Boolean,
    val unreadCount: Int,
    val hasNewChapter: Boolean,
    val tags: List<String>,
    val lastUpdateText: String?,
    val localFileChanged: Boolean = false
) : BookshelfItemUi {
    override val key: String = "book:${display.bookUrl}"
    override val contentType: String = "book"
}

fun buildBookshelfItems(
    groups: List<BookGroup>,
    books: List<BookShelfDisplay>,
    isRootGroup: Boolean,
    groupId: Long,
    isUpdating: (String) -> Boolean,
    localChangedUrls: Set<String> = emptySet()
): List<BookshelfItemUi> {
    val configuredTags = AppConfig.bookshelfGroupTags[groupId].orEmpty()
    val hiddenTags = AppConfig.bookshelfHiddenTags[groupId].orEmpty()
    val bookItems = books.map { book ->
        BookshelfBookItemUi(
            display = book,
            isUpdating = !book.isLocal && isUpdating(book.bookUrl),
            unreadCount = book.getUnreadChapterNum(),
            hasNewChapter = book.lastCheckCount > 0,
            tags = book.displayUserTags(configuredTags, hiddenTags)
                .take(4),
            lastUpdateText = if (AppConfig.showLastUpdateTime && !book.isLocal) {
                book.latestChapterTime.toTimeAgo()
            } else {
                null
            },
            localFileChanged = book.isLocal && book.bookUrl in localChangedUrls
        )
    }
    if (!isRootGroup) {
        return bookItems
    }
    return groups.map(::BookshelfFolderItemUi) + bookItems
}

/**
 * 本地书籍文件在磁盘上被改动过（相对上次解析目录的时间）的书目集合。
 * 打开这些书时阅读器会自动重新分割目录，书架先给用户一个可见的提示。
 *
 * 只在轻量判断：content:// 走 DocumentFile 查询，文件路径直接取 mtime；
 * 取不到（如文件被移动）时返回 false，不影响书架展示。
 * 结果按（bookUrl, latestChapterTime）缓存，避免每次回前台/数据失效都重复查询 SAF。
 */
suspend fun computeLocalChangedBookUrls(books: List<BookShelfDisplay>): Set<String> {
    if (books.none { it.isLocal }) return emptySet()
    return withContext(Dispatchers.IO) {
        books.asSequence()
            .filter { it.isLocal }
            .filter { it.isLocalFileModified() }
            .mapTo(hashSetOf()) { it.bookUrl }
    }
}

/**
 * @return 文件 lastModified 是否新于上次解析目录的时间
 */
private fun BookShelfDisplay.isLocalFileModified(): Boolean {
    val cached = localModifiedCache[bookUrl]
    if (cached != null && cached.first == latestChapterTime) return cached.second
    val changed = runCatching {
        val lastModified = if (bookUrl.isUri()) {
            val uri = bookUrl.toUri()
            if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(appCtx, uri)?.lastModified() ?: return false
            } else {
                File(uri.path ?: return false).lastModified()
            }
        } else {
            File(bookUrl).lastModified()
        }
        lastModified > latestChapterTime
    }.getOrDefault(false)
    //本地书数量有限，缓存条目不会失控
    localModifiedCache[bookUrl] = latestChapterTime to changed
    return changed
}

/** 文件改动判断结果缓存：bookUrl -> (判断时的 latestChapterTime, 是否已改动) */
private val localModifiedCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

private fun BookShelfDisplay.displayUserTags(
    configuredTags: List<String>,
    hiddenTags: Set<String>
): List<String> {
    val userTags = BookTagHelper.parse(customTag)
    val visibleConfiguredTags = configuredTags
        .filterNot { configured -> hiddenTags.any { it.equals(configured, ignoreCase = true) } }
    val candidateTags = visibleConfiguredTags.ifEmpty { userTags }
    return candidateTags.filter { candidate ->
        userTags.any { it.equals(candidate, ignoreCase = true) }
    }.distinctBy { it.lowercase() }
}

fun updateBookshelfItemUpdating(
    items: List<BookshelfItemUi>,
    bookUrl: String,
    isUpdating: (String) -> Boolean
): List<BookshelfItemUi> {
    var changed = false
    val updatedItems = items.map { item ->
        if (item is BookshelfBookItemUi && item.display.bookUrl == bookUrl) {
            val nextUpdating = !item.display.isLocal && isUpdating(bookUrl)
            if (nextUpdating != item.isUpdating) {
                changed = true
                item.copy(isUpdating = nextUpdating)
            } else {
                item
            }
        } else {
            item
        }
    }
    return if (changed) updatedItems else items
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfGridItem(
    item: BookshelfItemUi,
    /**
     * 玻璃观感签名，由页面（`BookshelfGridContent`）用 `rememberGlassConfigSignature()`
     * 算一次后下传。
     *
     * 不能在本函数里自己算：它会给每个可见项各注册一个偏好监听（网格通常 3 列 × 7 行
     * ≈ 20 个可见项），滚动时反复建销；而签名是整页同一份，页面算一次即可。
     */
    glassSignature: String,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    onClick: (BookshelfItemUi) -> Unit,
    onLongClick: (BookshelfItemUi) -> Unit
) {
    val context = LocalContext.current
    val themeSignature = rememberThemeUiPalette().signature
    val showBookName = AppConfig.showBookname
    val titleFontFamily = remember(context, themeSignature) {
        FontFamily(context.titleTypeface())
    }
    val titleColorArgb = context.titleTextColor
    val titleColor = remember(context, themeSignature, titleColorArgb) {
        Color(titleColorArgb)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) }
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.TopEnd
        ) {
            BookshelfCover(
                item = item,
                modifier = Modifier.fillMaxWidth(),
                fragment = fragment,
                lifecycle = lifecycle
            )
            if (item is BookshelfBookItemUi) {
                BookshelfStatusBadge(item)
            }
            if (item is BookshelfBookItemUi && item.localFileChanged && !item.isUpdating) {
                BookshelfLocalFileChangedBadge(
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
            if (showBookName == 2) {
                Text(
                    text = item.displayName,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = titleFontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // 封面自身排版复杂时白字易糊，加轻投影兜底
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.7f),
                            offset = Offset(1f, 1f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }
        if (showBookName == 0) {
            // 自定义背景图下主题文字色直接压图难分辨：背景图模式时给标签加自适应衬底条
            // （浅色文字配深衬底、深色文字配浅衬底）；无背景图时不加，普通主题观感不变
            val scrimColor = imageBgAdaptiveScrim(titleColor)
            // 标签是压在图上的浮层，采样域取壁纸域；圆角必须与原先 clip 的 6dp 逐值一致，
            // 否则玻璃层比回退表面更方，两档切换时肉眼可见
            val labelCorner = 6.dp
            GlassPanel(
                domain = GlassSamplingDomain.WALLPAPER,
                cornerRadiusPx = with(LocalDensity.current) { labelCorner.toPx() },
                configSignature = glassSignature,
                // 网格项背后到底有没有壁纸只有页面知道（无背景图 / solid 档 / 墨水屏 /
                // 低内存 / 书架页不露壁纸）。不要求时普通主题下也会建出采样隐藏壁纸的宿主，
                // 那条原本不存在的横条会凭空出现
                requireWallpaperHost = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                // 回退逐值等于改动前的衬底条：圆角 6dp + 自适应 scrim（无背景图时是
                // Color.Transparent，即今天根本没有横条）
                fallback = Modifier
                    .clip(RoundedCornerShape(labelCorner))
                    .background(scrimColor)
            ) {
                // GlassPanel 的 content 是 BoxScope：文字要自己撑满宽度并居中，
                // 否则会退化成左对齐的单行块
                Text(
                    text = item.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    color = titleColor,
                    fontSize = 12.sp,
                    fontFamily = titleFontFamily,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BookshelfCover(
    item: BookshelfItemUi,
    modifier: Modifier,
    fragment: Fragment?,
    lifecycle: Lifecycle?
) {
    BookshelfComposeCover(
        item = item,
        modifier = modifier,
        fragment = fragment,
        lifecycle = lifecycle
    )
}

/** 本地 txt 文件在磁盘上被改动过的角标（打开时会自动重新分割目录） */
@Composable
private fun BookshelfLocalFileChangedBadge(modifier: Modifier = Modifier) {
    Text(
        text = appCtx.getString(R.string.ui_main_updated),
        modifier = modifier
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color(LocalContext.current.accentColor))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun BookshelfStatusBadge(item: BookshelfBookItemUi) {    if (item.isUpdating) {
        CircularProgressIndicator(
            modifier = Modifier
                .padding(5.dp)
                .size(20.dp),
            strokeWidth = 2.dp,
            color = Color(LocalContext.current.accentColor)
        )
        return
    }
    if (!AppConfig.showUnread || item.unreadCount <= 0) return
    val badgeColor = if (item.hasNewChapter) {
        Color(LocalContext.current.accentColor)
    } else {
        Color.Black.copy(alpha = 0.55f)
    }
    Text(
        text = item.unreadCount.coerceAtMost(99999).toString(),
        modifier = Modifier
            .padding(5.dp)
            .clip(CircleShape)
            .background(badgeColor)
            .widthIn(min = 20.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

private val BookshelfItemUi.displayName: String
    get() = when (this) {
        is BookshelfBookItemUi -> display.name
        is BookshelfFolderItemUi -> group.groupName
        is BookshelfCollectionItemUi -> collection.name
    }
