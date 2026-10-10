package io.legado.app.ui.book.explore

import android.content.Context
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.BookIntroUtils

internal fun SearchBook.exploreListIntro(context: Context): String {
    return BookIntroUtils.listIntro(intro)
        ?: context.getString(R.string.intro_show_null)
}
