package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.SourceRecycleBin
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceRecycleBinDao {

    @Query("select * from source_recycle_bin order by deletedAt desc, id desc")
    fun flowAll(): Flow<List<SourceRecycleBin>>

    @Query("select * from source_recycle_bin where type = :type order by deletedAt desc, id desc")
    fun flowByType(type: String): Flow<List<SourceRecycleBin>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg items: SourceRecycleBin)

    @Delete
    fun delete(vararg items: SourceRecycleBin)

    @Query("delete from source_recycle_bin where expireAt <= :now")
    fun deleteExpired(now: Long)

    /**
     * 取已过期的条目。
     *
     * 清理前要先拿到条目本身：过期即"真删除"，需要顺带清掉该源删除时
     * 故意保留的源变量与源评分（见 SourceRecycleBinHelp.purgeAttachedData）。
     */
    @Query("select * from source_recycle_bin where expireAt <= :now")
    fun getExpired(now: Long): List<SourceRecycleBin>

    /** 取全部条目，用于清空回收站前清理附带数据。 */
    @Query("select * from source_recycle_bin")
    fun getAll(): List<SourceRecycleBin>

    @Query("delete from source_recycle_bin where type = :type and key in (:keys)")
    fun deleteByKeys(type: String, keys: List<String>)

    @Query("delete from source_recycle_bin")
    fun deleteAll()
}
