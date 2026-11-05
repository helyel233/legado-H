package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.legado.app.R
import splitties.init.appCtx

/**
 * 文本成书：将分享的长文本写入本地书籍目录并入库。
 */
object TextBookImporter {

    /**
     * @return 导入成功的书籍，失败返回 null
     */
    suspend fun import(text: String, bookName: String): Book? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val safeName = bookName.trim()
                    .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "")
                    .take(50)
                    .ifBlank { appCtx.getString(R.string.misc_shared_text) }
                val timeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "${safeName}_$timeTag.txt"
                val uri = LocalBook.saveBookFile(text.byteInputStream(), fileName)
                LocalBook.importFile(uri)
            }.getOrNull()
        }
    }

}
