package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.RuleSub
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleSubDao {

    @get:Query("select * from ruleSubs order by customOrder")
    val all: List<RuleSub>

    @Query("select * from ruleSubs order by customOrder")
    fun flowAll(): Flow<List<RuleSub>>

    // 聚合必返回一行：空表时 max() 为 NULL，游标取 Int 得 0；
    // 旧写法 `order by customOrder limit 0,1` 取的是最小值且空表零行，新增订阅会拿到
    // 与既有行冲突的 customOrder，插到列表中段
    @get:Query("select max(customOrder) from ruleSubs")
    val maxOrder: Int

    @Query("select * from ruleSubs where url = :url")
    fun findByUrl(url: String): RuleSub?

    /**
     * 同一事务内查重+插入：旧写法「查重」与「插入」是两次独立 IO 跳，
     * 连点保存/与自动更新并发时两个协程都能通过查重并插入，产生重复 url 行。
     * @param excludeId 编辑已有订阅时排除自身主键
     */
    @Transaction
    fun insertIfNotDuplicate(ruleSub: RuleSub, excludeId: Long): Boolean {
        val duplicate = findByUrl(ruleSub.url)
        if (duplicate != null && duplicate.id != excludeId) return false
        insert(ruleSub)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg ruleSub: RuleSub)

    @Delete
    fun delete(vararg ruleSub: RuleSub)

    @Update
    fun update(vararg ruleSub: RuleSub)
}