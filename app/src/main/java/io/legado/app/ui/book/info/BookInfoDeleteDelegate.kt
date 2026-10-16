package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.app.Activity
import android.widget.CheckBox
import android.widget.LinearLayout
import io.legado.app.R
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.dpToPx

/**
 * 书籍详情页删除确认弹窗的公共实现。
 * 旧 View 版(BookInfoActivity)与 Compose 版(BookInfoComposeActivity)共用，
 * 弹窗用同一套 Android Alert，差异只在删除完成后的回调。
 */
@SuppressLint("InflateParams")
fun Activity.confirmDeleteBook(
    viewModel: BookInfoViewModel,
    onDeleted: Activity.() -> Unit
) {
    val book = viewModel.getBook() ?: return
    if (LocalConfig.bookInfoDeleteAlert) {
        alert(
            titleResource = R.string.draw,
            messageResource = R.string.sure_del
        ) {
            var checkBox: CheckBox? = null
            if (book.isLocal) {
                checkBox = CheckBox(this@confirmDeleteBook).apply {
                    setText(R.string.delete_book_file)
                    isChecked = LocalConfig.deleteBookOriginal
                }
                val view = LinearLayout(this@confirmDeleteBook).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    addView(checkBox)
                }
                customView { view }
            }
            yesButton {
                checkBox?.let { LocalConfig.deleteBookOriginal = it.isChecked }
                SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book)
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    onDeleted()
                }
            }
            noButton()
        }
    } else {
        SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book)
        viewModel.delBook(LocalConfig.deleteBookOriginal) {
            onDeleted()
        }
    }
}
