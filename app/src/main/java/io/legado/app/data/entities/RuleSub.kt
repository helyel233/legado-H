package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ruleSubs")
data class RuleSub(
    @PrimaryKey(autoGenerate = true)
    // 默认 0 才会真正触发 Room 的 autoGenerate；旧默认 currentTimeMillis 使自增长失效，
    // 同一毫秒内新增两条订阅会拿到相同 id，insert(REPLACE) 直接互相覆盖
    val id: Long = 0,
    var name: String = "",
    var url: String = "",
    /** 0 书源， 1 订阅源 ， 3 替换规则**/
    var type: Int = 0,
    var customOrder: Int = 0,
    var autoUpdate: Boolean = false,
    var update: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    var updateInterval: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var silentUpdate: Boolean = false,
    var js: String? = null, //在访问链接前执行的js规则
    var showRule: String? = null , //显示规则
    var sourceUrl: String? = null //绑定的源链接，能调用源的资源
) {
    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is RuleSub) {
            return id == other.id
        }
        return false
    }

}
