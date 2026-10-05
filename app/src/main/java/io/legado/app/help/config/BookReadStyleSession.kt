package io.legado.app.help.config

import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON

/**
 * 当前书籍的独立预设副本（移植自 joestar817/legado_NG）。
 *
 * 绑定书籍后，[config] 非 null 表示该书使用独立样式（所有读写经 ReadBookConfig
 * 的 config/durConfig 转发到此对象）；null 表示跟随全局样式。
 * 序列化存于 `Book.ReadConfig.independentReadStyle`（readConfig JSON 列内）。
 */
internal class BookReadStyleSession(
    private val persist: (bookUrl: String, style: String?) -> Unit,
) {
    private var book: Book? = null
    var config: ReadBookConfig.Config? = null
        private set

    val isBound: Boolean get() = book != null

    /** 绑定书籍；返回 true 表示独立样式状态发生变化（需要刷新阅读界面）。 */
    fun bind(next: Book): Boolean {
        save()
        if (book?.bookUrl == next.bookUrl) {
            next.readConfig?.independentReadStyle = book?.readConfig?.independentReadStyle
            book = next
            return false
        }
        val previous = config
        val loaded = decode(next.readConfig?.independentReadStyle)
        book = next
        config = loaded
        return previous != null || loaded != null
    }

    /** 启用/替换本书独立样式。 */
    fun use(style: ReadBookConfig.Config) {
        check(isBound)
        config = style.copy()
        save()
    }

    /** 放弃本书独立样式，恢复跟随全局。 */
    fun followGlobal() {
        config = null
        write(null)
    }

    fun save() {
        config?.let { write(GSON.toJson(it)) }
    }

    fun saveFor(owner: Book) {
        if (book?.bookUrl == owner.bookUrl) save()
    }

    private fun write(serialized: String?) {
        val owner = book ?: return
        if (owner.readConfig?.independentReadStyle == serialized) return
        owner.readConfig?.independentReadStyle = serialized
        persist(owner.bookUrl, serialized)
    }

    companion object {
        fun decode(serialized: String?): ReadBookConfig.Config? = serialized?.let {
            requireNotNull(GSON.fromJson(it, ReadBookConfig.Config::class.java)) {
                "本书预设无法读取"
            }
        }
    }
}
