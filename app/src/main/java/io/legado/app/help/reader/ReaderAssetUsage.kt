package io.legado.app.help.reader

import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.R
import splitties.init.appCtx

object ReaderAssetUsage {
    fun references(id: String, rules: List<HighlightRule>, templates: List<EpubReaderTemplate>): List<String> = buildList {
        require(ReaderAssetReferences.validId(id))
        rules.filter { it.asset == id || id in ReaderAssetReferences.ids(it.styleCssText) }
            .forEach { add(appCtx.getString(R.string.misc_highlight_rule, it.displayName())) }
        templates.filter {
            id in ReaderAssetReferences.ids(it.resourceSource())
        }.forEach { add(appCtx.getString(R.string.misc_epub_page_2, it.name)) }
    }
}
