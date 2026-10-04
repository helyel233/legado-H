package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.LibraryContentFts
import io.legado.app.data.entities.LibraryContentSearchRow

@Dao
interface LibraryContentFtsDao {

    @Query(
        """
        SELECT rowid AS docId, bookUrl, chapterIndex, chapterTitle,
            snippet(libraryContentFts, '⟦', '⟧', '…', -1, 12) AS snippetText
        FROM libraryContentFts
        WHERE libraryContentFts MATCH :query
        LIMIT :limit OFFSET :offset
        """
    )
    fun search(query: String, limit: Int, offset: Int): List<LibraryContentSearchRow>

    @Query("SELECT count(*) FROM libraryContentFts WHERE libraryContentFts MATCH :query")
    fun countMatch(query: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg items: LibraryContentFts)

    @Query("DELETE FROM libraryContentFts WHERE bookUrl = :bookUrl AND chapterIndex = :chapterIndex")
    fun deleteChapter(bookUrl: String, chapterIndex: Int)

    @Query("DELETE FROM libraryContentFts WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @Query("DELETE FROM libraryContentFts")
    fun deleteAll()

    @Query("SELECT count(*) FROM libraryContentFts")
    fun countAll(): Int

    @Query("SELECT chapterIndex FROM libraryContentFts WHERE bookUrl = :bookUrl")
    fun indexedChapterIndexes(bookUrl: String): List<Int>

    @Query("SELECT DISTINCT bookUrl FROM libraryContentFts")
    fun indexedBookUrls(): List<String>
}
