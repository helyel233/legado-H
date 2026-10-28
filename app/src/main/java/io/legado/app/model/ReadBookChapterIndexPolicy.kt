package io.legado.app.model

/**
 * Keeps speculative adjacent-chapter loads from querying impossible DAO indexes.
 * A zero count means the chapter list is not known yet, so non-negative indexes
 * are still allowed to reach the normal missing-chapter error path.
 */
internal object ReadBookChapterIndexPolicy {

    fun canLoad(index: Int, knownChapterCount: Int): Boolean {
        return index >= 0 && (knownChapterCount <= 0 || index < knownChapterCount)
    }

    fun canSelect(index: Int, chapterCount: Int): Boolean {
        return index in 0 until chapterCount
    }

    /**
     * 把持久化的章节号夹回有效范围。
     *
     * 目录可能变短（换源后章数变少、源删章、模拟章节数变化），而 Book.durChapterIndex 仍是旧值。
     * 直接用旧值会让加载器判定「索引超出范围」并让「下一章」永远翻不动，因此在进入阅读态前统一夹取。
     *
     * chapterSize <= 0 表示目录还没拉到：此时不能把进度重置成 0（会丢阅读位置），
     * 只把负索引归零，等目录到位后由 onChapterListUpdated 再夹。
     */
    fun clamp(chapterIndex: Int, chapterSize: Int): Int {
        return if (chapterSize <= 0) chapterIndex.coerceAtLeast(0)
        else chapterIndex.coerceIn(0, chapterSize - 1)
    }
}
