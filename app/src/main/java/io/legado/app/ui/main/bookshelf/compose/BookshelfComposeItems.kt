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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
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
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.toTimeAgo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

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
 * 只做轻量判断：content:// 走 DocumentFile 查询，文件路径直接取 mtime；
 * 取不到（如文件被移动）时返回 false，不影响书架展示。
 */
suspend fun computeLocalChangedBookUrls(books: List<BookShelfDisplay>): Set<String> {
    if (books.none { it.isLocal }) return emptySet()
    return withContext(Dispatchers.IO) {
        books.asSequence()
            .filter { it.isLocal }
            .filter { it.latestChapterTime > 0L }
            .filter { it.isLocalFileModified() }
            .mapTo(hashSetOf()) { it.bookUrl }
    }
}

private fun BookShelfDisplay.isLocalFileModified(): Boolean {
    return runCatching {
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
}

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
            val hasBgImage = remember(context, themeSignature) {
                ThemeConfig.hasUsableBgImage(context)
            }
            val scrimColor = if (hasBgImage) {
                if (titleColor.luminance() > 0.5f) {
                    Color.Black.copy(alpha = 0.45f)
                } else {
                    Color.White.copy(alpha = 0.55f)
                }
            } else {
                Color.Transparent
            }
            Text(
                text = item.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scrimColor)
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
        text = "已更新",
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
