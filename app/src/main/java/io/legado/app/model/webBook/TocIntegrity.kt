package io.legado.app.model.webBook

/**
 * 目录完整性判定（纯函数）。
 *
 * 目录分页抓取失败时会拿到「残缺目录」，此时若库里有更完整的目录，
 * 应该沿用旧目录，而不是把完整目录覆盖成残缺目录。
 */
internal object TocIntegrity {

    /**
     * 是否应该沿用库里的旧目录而不用这次抓到的目录。
     *
     * 条件：抓取有分页失败（目录不完整）+ 库里已有目录，且二者之一成立：
     * 1. 新目录比旧目录短（经典缺口）
     * 2. 新目录缺失了旧目录的章节（[missingOldUrls] > 0）——源同时新增了
     *    尾部章节时分页失败，长度比对会失效（newSize >= oldSize），
     *    必须按旧章 url 覆盖率判定，否则残缺目录会覆盖完整目录
     *
     * failedPages 条件不能省：书源改版后章节数变少是正常更新，
     * 不该被当成抓取不完整拦下。
     */
    fun shouldKeepOldToc(
        failedPages: Int,
        newSize: Int,
        oldSize: Int,
        missingOldUrls: Int = 0
    ): Boolean {
        return failedPages > 0 && oldSize > 0 &&
            (newSize < oldSize || missingOldUrls > 0)
    }
}
