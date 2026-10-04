package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * 全库全文搜索索引（standalone FTS4，由 LibrarySearchIndexer 手动维护）。
 * content 列存储经 CJK 分词预处理后的正文（CJK 字符间插空格），
 * 配合 unicode61 tokenizer 实现中文子串的短语匹配。
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "libraryContentFts")
data class LibraryContentFts(
    val bookUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val content: String = ""
)

/**
 * 全库搜索结果行（snippet() 摘要使用 ⟦ ⟧ 包裹命中词，由 UI 层解析高亮）。
 */
data class LibraryContentSearchRow(
    val docId: Int = 0,
    val bookUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val snippetText: String = ""
)
