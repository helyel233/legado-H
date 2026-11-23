package io.legado.app.help.book

import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/** One bounded anchor per ordinary book; does not mutate the directory or source variables. */
object ReaderTextPositionStore {
    @androidx.annotation.Keep
    data class Position(val chapterUrl: String, val renderer: String, val offset: Int, val anchor: ReaderTextAnchor)

    private data class ChangeSourceAnchorKey(val bookUrl: String, val chapterIndex: Int)

    private val pendingChangeSourceAnchors =
        ConcurrentHashMap<ChangeSourceAnchorKey, ChangeSourceTextAnchor>()

    fun saveChangeSourceAnchor(bookUrl: String, chapterIndex: Int, anchor: ChangeSourceTextAnchor) {
        if (bookUrl.isBlank() || chapterIndex < 0) return
        pendingChangeSourceAnchors[ChangeSourceAnchorKey(bookUrl, chapterIndex)] = anchor
    }

    fun hasChangeSourceAnchor(bookUrl: String, chapterIndex: Int): Boolean {
        if (bookUrl.isBlank() || chapterIndex < 0) return false
        return pendingChangeSourceAnchors.containsKey(ChangeSourceAnchorKey(bookUrl, chapterIndex))
    }

    fun consumeChangeSourceAnchor(bookUrl: String, chapterIndex: Int): ChangeSourceTextAnchor? {
        if (bookUrl.isBlank() || chapterIndex < 0) return null
        return pendingChangeSourceAnchors.remove(ChangeSourceAnchorKey(bookUrl, chapterIndex))
    }

    fun clearChangeSourceAnchors(bookUrl: String? = null) {
        if (bookUrl.isNullOrBlank()) {
            pendingChangeSourceAnchors.clear()
        } else {
            pendingChangeSourceAnchors.keys.removeIf { it.bookUrl == bookUrl }
        }
    }

    private fun key(bookUrl: String) = "readerTextAnchor." + MD5Utils.md5Encode16(bookUrl)

    private fun read(bookUrl: String): Position? = runCatching {
        val json = appCtx.getPrefString(key(bookUrl))?.takeIf { it.length <= 4096 } ?: return@runCatching null
        GSON.fromJson(json, Position::class.java)?.takeIf {
            it.chapterUrl.isNotBlank() && it.offset >= 0 && it.renderer in setOf("native", "epub") &&
                it.anchor.cue.length in 1..160 && it.anchor.cueOffset in 0..it.anchor.cue.length
        }
    }.getOrNull()

    fun needsNativeRestore(bookUrl: String, chapterUrl: String, offset: Int): Boolean = read(bookUrl)?.let {
        it.renderer == "epub" && it.chapterUrl == chapterUrl && it.offset == offset
    } == true

    fun resolve(bookUrl: String, chapterUrl: String, renderer: String, text: String, offset: Int): Int {
        val old = read(bookUrl)?.takeIf { it.chapterUrl == chapterUrl && it.offset == offset }
            ?: return offset.coerceIn(0, text.length)
        return old.anchor.locate(text, offset) ?: if (old.renderer != renderer) 0 else offset.coerceIn(0, text.length)
    }

    fun remember(bookUrl: String, chapterUrl: String, renderer: String, text: String, offset: Int, textStart: Int = 0) {
        if (text.isBlank()) return
        val position = Position(chapterUrl, renderer, offset,
            ReaderTextAnchor.capture(text, (offset - textStart).coerceIn(0, text.length)))
        val json = GSON.toJson(position)
        if (appCtx.getPrefString(key(bookUrl)) != json) appCtx.putPrefString(key(bookUrl), json)
    }
}
