package io.legado.app.model.webBook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录完整性判定的回归网
 */
class TocIntegrityTest {

    @Test
    fun `keeps old toc when pagination failed and new toc is shorter`() {
        assertTrue(TocIntegrity.shouldKeepOldToc(failedPages = 1, newSize = 150, oldSize = 500))
    }

    @Test
    fun `does not keep old toc when no page failed`() {
        // 书源改版导致章节变少是正常更新，不能当成抓取不完整
        assertFalse(TocIntegrity.shouldKeepOldToc(failedPages = 0, newSize = 150, oldSize = 500))
    }

    @Test
    fun `does not keep old toc when new toc is not shorter`() {
        assertFalse(TocIntegrity.shouldKeepOldToc(failedPages = 2, newSize = 500, oldSize = 500))
        assertFalse(TocIntegrity.shouldKeepOldToc(failedPages = 2, newSize = 600, oldSize = 500))
    }

    @Test
    fun `does not keep old toc when there is no old toc`() {
        assertFalse(TocIntegrity.shouldKeepOldToc(failedPages = 3, newSize = 150, oldSize = 0))
    }

    @Test
    fun `keeps old toc when pagination failed and old chapters are missing despite growth`() {
        // 源新增了尾部章节但中间页抓取失败：newSize > oldSize，
        // 仅比长度会漏判；缺旧章必须沿用，否则完整目录被残缺目录覆盖
        assertTrue(
            TocIntegrity.shouldKeepOldToc(
                failedPages = 2, newSize = 600, oldSize = 500, missingOldUrls = 80
            )
        )
    }

    @Test
    fun `does not keep old toc when longer and fully covering old urls`() {
        // 变长且旧章全部保留（纯尾部新增）：正常更新，照常覆盖
        assertFalse(
            TocIntegrity.shouldKeepOldToc(
                failedPages = 2, newSize = 600, oldSize = 500, missingOldUrls = 0
            )
        )
    }
}
