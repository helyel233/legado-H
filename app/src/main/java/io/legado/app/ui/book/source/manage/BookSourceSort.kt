package io.legado.app.ui.book.source.manage

enum class BookSourceSort {
    Default, Name, Url, Weight, Update, Enable, Respond
}

/**
 * 把持久化的排序名解析回枚举：存 name 而非 ordinal，枚举成员增删不会错位；
 * 空值/历史遗留的非法值静默回退手动排序，绝不让坏偏好炸掉书源页。
 */
internal fun parseBookSourceSort(raw: String?): BookSourceSort =
    raw?.let { runCatching { BookSourceSort.valueOf(it) }.getOrNull() }
        ?: BookSourceSort.Default