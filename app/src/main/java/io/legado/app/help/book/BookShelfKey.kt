package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * 书架条目唯一键（自 legadoC 移植时剥离快捷方式体系，恒为普通书籍键）
 */
val Book.shelfKey: String
    get() = "book:$bookUrl"

/** legado-H 未移植书架快捷方式体系，恒为 false */
val Book.isShortcut: Boolean
    get() = false
