package io.legado.app.ui.book.read.page

/**
 * Stable identity for a page's position within a book.
 *
 * Chapter and page numbers repeat across books, so the book has to take part or a stale
 * entry from a previously open book could answer for this one.
 */
internal object AdvancedTitlePageKeys {

    fun of(bookUrl: String, chapterIndex: Int, pageIndex: Int): String {
        return "$bookUrl#$chapterIndex#$pageIndex"
    }
}

/**
 * Access-ordered map bounded to a fixed number of entries.
 *
 * Reading an entry counts as a use, so the entry the reader is most likely to come back to
 * is the last one to be dropped. Writes to an existing key also count as a use.
 */
internal class AccessOrderedLruCache<K, V>(private val maxSize: Int) {

    private val entries = LinkedHashMap<K, V>(8, 0.75f, true)

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    operator fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries[key] = value
        while (entries.size > maxSize) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
        }
    }

    fun containsKey(key: K): Boolean = entries.containsKey(key)

    fun size(): Int = entries.size

    fun clear() {
        entries.clear()
    }
}

/**
 * Composition key last bound for a page, keyed by that page's position.
 *
 * Two things have to be true before a composition may go back up, and they need
 * separate mechanisms:
 *
 * The *lookup* is keyed by position — chapter index and page number — because that is
 * what stays stable when the reader turns away and back. Title text cannot be used:
 * chapter names repeat ("序", "第一章", "番外"), so two unrelated positions would
 * collide and one chapter could show another's composition.
 *
 * The *decision* stays with the recorded key, which encodes the styled JSON hash and
 * the view size. Changing the font, rotating, or swapping the title package therefore
 * produces a key that no longer resolves, and the page rebuilds instead of reusing a
 * composition built for different conditions — something position alone cannot detect.
 */
internal class AdvancedTitlePageKeyCache(maxSize: Int) {

    private val cache = AccessOrderedLruCache<String, String>(maxSize)

    operator fun get(pageKey: String): String? = cache[pageKey]

    fun put(pageKey: String, compositionKey: String) {
        cache.put(pageKey, compositionKey)
    }

    fun size(): Int = cache.size()

    fun clear() {
        cache.clear()
    }
}
