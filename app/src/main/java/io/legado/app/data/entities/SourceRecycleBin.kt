package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 规则回收站（移植自 Legado_Max）
 *
 * 删除书源/订阅源/各类规则前先序列化入站，默认保留 7 天后过期清理，
 * 支持搜索、按类型过滤与恢复（含同名冲突覆盖确认）。
 */
@Entity(
    tableName = "source_recycle_bin",
    indices = [
        Index(value = ["type"]),
        Index(value = ["key"]),
        Index(value = ["expireAt"])
    ]
)
data class SourceRecycleBin(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var type: String = "",
    var key: String = "",
    var name: String = "",
    var groupName: String? = null,
    var payload: String = "",
    var deletedAt: Long = 0,
    var expireAt: Long = 0
)
