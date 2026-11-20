package io.legado.app.ui.config

import com.google.gson.Gson
import io.legado.app.help.config.NavigationBarIconConfig

/**
 * Pure JVM state snapshot for [NavigationBarEditDialog] across configuration changes.
 */
data class NavigationBarEditSnapshot(
    val entryDirName: String,
    val entrySourceName: String,
    val entryRemoteUpdatedAt: Long = 0L,
    val editingName: String,
    val config: NavigationBarIconConfig.Config
) {
    fun toJson(): String = snapshotGson.toJson(this)

    companion object {
        private val snapshotGson by lazy { Gson() }

        fun fromJson(json: String?): NavigationBarEditSnapshot? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                snapshotGson.fromJson(json, NavigationBarEditSnapshot::class.java)
            }.getOrNull()?.let { snapshot ->
                snapshot.copy(
                    config = snapshot.config.copy(
                        icons = LinkedHashMap(snapshot.config.icons)
                    )
                )
            }
        }
    }
}
