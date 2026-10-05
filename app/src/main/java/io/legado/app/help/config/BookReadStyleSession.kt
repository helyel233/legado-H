package io.legado.app.help.config

import io.legado.app.constant.AppLog
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
            book?.readConfig?.independentReadStyle?.let { serialized ->
                val cfg = next.readConfig ?: Book.ReadConfig().also { next.readConfig = it }
                cfg.independentReadStyle = serialized
            }
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

    /**
     * 把本书预设同步到 [owner] 实例并落库。
     *
     * [ReadBook.saveRead] 的 fullUpdate 路径会用整行 `bookDao.update()` 写回传入实例，
     * 若该实例的 readConfig 仍是绑定前的旧副本，会抹掉已落库的预设且后续去重无法自愈，
     * 所以保存点必须先把样式写进调用方传入的实例。
     */
    fun saveFor(owner: Book) {
        if (book?.bookUrl != owner.bookUrl) return
        val cfg = owner.readConfig ?: Book.ReadConfig().also { owner.readConfig = it }
        cfg.independentReadStyle = config?.let(GSON::toJson)
        save()
    }

    private fun write(serialized: String?) {
        val owner = book ?: return
        // 容器必须保证非空：readConfig 为 null 时若静默空操作，
        // 启用预设会落库成功而关闭预设被去重拦截，重启后已关闭的预设会复活。
        val cfg = owner.readConfig ?: Book.ReadConfig().also { owner.readConfig = it }
        if (cfg.independentReadStyle == serialized) return
        cfg.independentReadStyle = serialized
        persist(owner.bookUrl, serialized)
    }

    companion object {
        fun decode(serialized: String?): ReadBookConfig.Config? = serialized?.let {
            // 外部导入的 bookshelf.json 可能带非法 JSON，解析失败时跟随全局而非让阅读页崩溃。
            runCatching {
                requireNotNull(GSON.fromJson(it, ReadBookConfig.Config::class.java))
            }.onFailure { error ->
                AppLog.put("本书预设读取失败，恢复跟随全局\n${error.localizedMessage}", error)
            }.getOrNull()
        }
    }
}
