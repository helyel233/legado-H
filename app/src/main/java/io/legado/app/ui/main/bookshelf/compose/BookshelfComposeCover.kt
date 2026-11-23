package io.legado.app.ui.main.bookshelf.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.constant.PreferKey
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverCollectionManager.isRealCoverPath
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.getPrefString
import splitties.init.appCtx

val LocalBookshelfAppearanceVersion = compositionLocalOf { 0 }

@Composable
fun BookshelfComposeCover(
    item: BookshelfItemUi,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    fillBounds: Boolean = false,
    style: CoverImageView.CoverStyle = CoverImageView.CoverStyle.GRID
) {
    val appearanceVersion = LocalBookshelfAppearanceVersion.current
    if (item is BookshelfCollectionItemUi) {
        BookshelfCollectionMosaicCover(
            books = item.previewBooks,
            fallbackName = item.collection.name,
            modifier = modifier,
            fragment = fragment,
            lifecycle = lifecycle,
            appearanceVersion = appearanceVersion
        )
        return
    }
    val coverRequest = remember(item.coverIdentityKey(), appearanceVersion) {
        item.toCoverRequest()
    }
    BookCoverImage(
        path = coverRequest.path,
        name = coverRequest.name,
        author = coverRequest.author,
        sourceOrigin = coverRequest.sourceOrigin,
        modifier = modifier,
        style = style,
        fragment = fragment,
        lifecycle = lifecycle,
        preferThumb = coverRequest.preferThumb,
        forcePath = coverRequest.forcePath,
        allowNameOverlay = coverRequest.allowNameOverlay,
        fillBounds = fillBounds,
        appearanceVersion = appearanceVersion
    )
}

/**
 * 封面请求参数。
 *
 * internal：封面预热（CoverWarmup）要算出**与首屏渲染完全一致**的封面 key，
 * 必须复用这里的 path / forcePath / allowNameOverlay 推导，自己另抄一份必然对不上。
 */
internal data class BookshelfCoverRequest(
    val path: String?,
    val name: String?,
    val author: String?,
    val sourceOrigin: String?,
    val preferThumb: Boolean,
    val forcePath: Boolean,
    val allowNameOverlay: Boolean?
)

private fun BookshelfItemUi.toCoverRequest(): BookshelfCoverRequest {
    return when (this) {
        is BookshelfBookItemUi -> display.toCoverRequest()
        is BookshelfFolderItemUi -> group.toCoverRequest()
        else -> throw IllegalStateException()
    }
}

@Composable
private fun BookshelfCollectionMosaicCover(
    books: List<Book>,
    fallbackName: String,
    modifier: Modifier,
    fragment: Fragment?,
    lifecycle: Lifecycle?,
    appearanceVersion: Int = 0
) {
    val previews = books.take(4)
    if (previews.isEmpty()) {
        BookCoverImage(
            book = Book(name = fallbackName),
            modifier = modifier,
            fragment = fragment,
            lifecycle = lifecycle,
            fillBounds = true,
            appearanceVersion = appearanceVersion
        )
        return
    }
    Column(modifier = modifier) {
        previews.chunked(2).forEach { rowBooks ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowBooks.forEach { preview ->
                    BookCoverImage(
                        book = preview,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.75f),
                        style = CoverImageView.CoverStyle.GRID,
                        fragment = fragment,
                        lifecycle = lifecycle,
                        fillBounds = true,
                        appearanceVersion = appearanceVersion
                    )
                }
                if (rowBooks.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** internal：供封面预热复用同一套封面来源推导（见 BookshelfCoverRequest 注释）。 */
internal fun BookShelfDisplay.toCoverRequest(): BookshelfCoverRequest {
    val originalCover = getDisplayCover()
    val collectionCover = CoverCollectionManager.selectedCollectionCover(
        bookKey = bookUrl.ifBlank { "$origin|$name|$author" },
        coverPath = originalCover
    )
    val usingCollectionCover = collectionCover != null
    val forceOriginalCover = collectionCover == null &&
        CoverCollectionManager.isMixedMode() &&
        originalCover.isRealCoverPath()
    val forcePath = usingCollectionCover || forceOriginalCover
    val allowNameOverlay = usingCollectionCover || !originalCover.isRealCoverPath()
    return buildCoverRequest(
        path = collectionCover ?: originalCover,
        name = name,
        author = author,
        sourceOrigin = origin,
        preferThumb = true,
        forcePath = forcePath,
        allowNameOverlay = allowNameOverlay
    )
}

internal fun resolveMountedGroupDefaultCover(): String? {
    return runCatching {
        val key = if (AppConfig.isNightTheme) {
            PreferKey.defaultGroupCoverDark
        } else {
            PreferKey.defaultGroupCover
        }
        appCtx.getPrefString(key)?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

internal fun BookGroup.toCoverRequest(
    fallbackGroupCover: String? = resolveMountedGroupDefaultCover()
): BookshelfCoverRequest {
    val ownCover = cover?.trim()?.takeIf { it.isNotBlank() }
    val fallback = fallbackGroupCover?.trim()?.takeIf { it.isNotBlank() }
    val resolvedPath = ownCover ?: fallback
    return buildCoverRequest(
        path = resolvedPath,
        name = groupName,
        author = null,
        sourceOrigin = null,
        preferThumb = true,
        forcePath = ownCover == null && fallback != null,
        allowNameOverlay = true,
    )
}

private fun buildCoverRequest(
    path: String?,
    name: String?,
    author: String?,
    sourceOrigin: String?,
    preferThumb: Boolean,
    forcePath: Boolean,
    allowNameOverlay: Boolean?
): BookshelfCoverRequest {
    return BookshelfCoverRequest(
        path = path,
        name = name,
        author = author,
        sourceOrigin = sourceOrigin,
        preferThumb = preferThumb,
        forcePath = forcePath,
        allowNameOverlay = allowNameOverlay
    )
}

private fun BookshelfItemUi.coverIdentityKey(): String {
    val configKey = listOf(
        AppConfig.useDefaultCover.toString(),
        AppConfig.loadCoverHighQuality.toString(),
        CoverCollectionManager.selectionKey(),
        resolveMountedGroupDefaultCover().orEmpty()
    ).joinToString("|")
    return when (this) {
        is BookshelfBookItemUi -> "$configKey|book|${display.bookUrl}|${display.getDisplayCover()}|${display.name}|${display.author}"
        is BookshelfFolderItemUi -> "$configKey|folder|${group.groupId}|${group.cover}|${group.groupName}"
        else -> "$configKey|collection"
    }
}
