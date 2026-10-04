package io.legado.app.help.export

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记导出 Markdown：按书聚合书签/笔记，输出可读的 md 文档。
 */
object BookmarkMarkdownExporter {

    fun export(book: Book): ByteArray? {
        val bookmarks = appDb.bookmarkDao
            .getByBook(book.name, book.author)
            .sortedWith(
                compareBy({ it.chapterIndex }, { it.chapterPos }, { it.time })
            )
        if (bookmarks.isEmpty()) return null
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            append("# ").append(book.name)
            if (book.author.isNotBlank()) {
                append("\n\n").append(book.author)
            }
            bookmarks.forEach { bookmark ->
                append("\n\n## ")
                    .append(bookmark.chapterName.ifBlank { "..." })
                val quote = bookmark.bookText.trim()
                if (quote.isNotBlank()) {
                    quote.lines().forEach { line ->
                        append("\n> ").append(line.trim())
                    }
                }
                val content = bookmark.content.trim()
                if (content.isNotBlank()) {
                    append("\n\n").append(content)
                }
                append("\n\n").append(dateFmt.format(Date(bookmark.time)))
            }
            append("\n")
        }.toByteArray(Charsets.UTF_8)
    }

}
