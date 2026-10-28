package io.legado.app.help.review

/**
 * 评论抓取分页预算（纯函数）。
 *
 * 数据接口路径不走网页引擎的执行超时，只有单次请求超时；
 * 若接口持续返回 has_more=true 且 cursor 不重复，循环永不结束，
 * 而评论抓取只有一个消费者，会把 worker 永久占住。
 */
internal object ReviewPaginationPolicy {

    /** 单个分页循环的页数上限（每页默认 20 条，200 页已远超正常段评规模） */
    const val MAX_PAGES = 200

    /** 是否已超过页数上限 */
    fun isOverLimit(pageCount: Int, maxPages: Int = MAX_PAGES): Boolean {
        return pageCount > maxPages
    }
}
