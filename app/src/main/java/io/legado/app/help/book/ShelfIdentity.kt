package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * 在线书身份键：用于「书架变动时自动备份」的增删判据。
 *
 * 口径：
 * - 换源（`bookUrl` 变、`name`/`author`/`type` 不变）**不算变动**；
 * - 阅读进度、封面等只改字段的动作**不算变动**；
 * - 离线书（本地/书仓文件）的增删**不算变动**；
 * - 只有书架上在线书的**增删**才算。
 */
object ShelfIdentity {

    data class Key(val type: Int, val name: String, val author: String)

    /** 单本书的身份键；离线书（本地/书仓文件）返回 null，表示永不参与增删判定。 */
    fun keyOf(book: Book): Key? = if (book.isLocal || book.isNotShelf) {
        null
    } else {
        Key(book.type, book.name, book.author)
    }

    fun keysOf(books: List<Book>): Set<Key> = books.mapNotNull(::keyOf).toSet()
}
