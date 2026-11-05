package io.legado.app.model.localBook.epubcore.template

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx
import java.io.File
import io.legado.app.R

object EpubReaderTemplateStore {
    const val fileName = "readerTemplates.json"
    const val singleTemplateFileName = "readerTemplate.json"
    val filePath: String get() = File(appCtx.filesDir, fileName).absolutePath
    const val defaultId = "builtin.minecraft_live"
    const val doraemonScrollId = "builtin.doraemon_scroll"
    val builtinIds = listOf(defaultId, "builtin.asuka_sync", "builtin.lord_of_mysteries", doraemonScrollId, "builtin.vertical")
    private val retiredBuiltinIds = setOf("builtin.minecraft", "builtin.gilded", "builtin.flower", "builtin.night", "builtin.magazine", "builtin.clean", "builtin.garden", "builtin.cat")

    private fun loadBuiltIn(id: String): EpubReaderTemplate =
        appCtx.assets.open("epub/templates/" + id + ".json")
            .bufferedReader(Charsets.UTF_8).use { EpubReaderTemplate.fromJson(it.readText()) }
            .also { check(it.id == id) { "内置模板 id 不匹配：" + id } }

    val defaultTemplate: EpubReaderTemplate by lazy { loadBuiltIn(defaultId) }

    private val reviewedFrameTemplates by lazy {
        listOf(defaultTemplate, loadBuiltIn("builtin.asuka_sync"), loadBuiltIn("builtin.lord_of_mysteries"))
    }

    /** A renamed copy is reproducible only when all rendering source still matches. */
    fun reviewedFrameTemplate(template: EpubReaderTemplate): EpubReaderTemplate? =
        reviewedFrameTemplates.firstOrNull { reference ->
            !template.isScrolling && template.type == reference.type &&
                template.schemaVersion == reference.schemaVersion &&
                template.firstPageHtml == reference.firstPageHtml &&
                template.otherPageHtml == reference.otherPageHtml &&
                template.css == reference.css && template.javascript == reference.javascript
        }

    private val repository by lazy {
        EpubReaderTemplateRepository(File(filePath), retiredBuiltinIds) {
            builtinIds.map(::loadBuiltIn)
        }
    }

    private val pageAnimations by lazy {
        EpubTemplatePageAnimationPreferences(
            read = { key, fallback -> appCtx.getPrefInt(key, fallback) },
            write = { key, value -> appCtx.putPrefInt(key, value) }
        )
    }

    /** SharedPreferences reads are in memory; a gesture never parses the template library. */
    fun pageAnimation(id: String): Int? {
        val selectedId = id.ifEmpty { defaultId }
        return pageAnimations.selected(selectedId, isScrolling(selectedId))
    }

    private fun isScrolling(id: String): Boolean =
        appCtx.getPrefBoolean(PreferKey.readerTemplateScrollPrefix + id, id == doraemonScrollId)

    private fun rememberType(template: EpubReaderTemplate) {
        if (isScrolling(template.id) != template.isScrolling) {
            appCtx.putPrefBoolean(PreferKey.readerTemplateScrollPrefix + template.id, template.isScrolling)
        }
    }

    @Synchronized
    fun savePageAnimation(id: String, animation: Int?) {
        val template = requireNotNull(resolve(id)) { appCtx.getString(R.string.misc_template_not_found) }
        if (template.isScrolling) return
        pageAnimations.select(id, animation)
    }

    @Synchronized
    fun list(): List<EpubReaderTemplate> {
        clearRetiredReferences()
        return repository.list().onEach(::rememberType)
    }

    @Synchronized
    fun resolve(id: String): EpubReaderTemplate? {
        if (id.isEmpty()) return null
        if (id in retiredBuiltinIds) clearRetiredReferences()
        return repository.resolve(id)?.also(::rememberType)
    }
    fun isBuiltIn(id: String): Boolean = id in builtinIds
    fun isRetiredBuiltIn(id: String): Boolean = id in retiredBuiltinIds
    @Synchronized
    fun ensureReaderSelection(): EpubReaderTemplate {
        clearRetiredReferences()
        val previousId = ReadBookConfig.config.readerTemplateId
        return repository.resolveRequired(previousId, defaultId).also { selected ->
            rememberType(selected)
            if (selected.id != previousId) ReadBookConfig.saveReaderTemplateSelection(selected.id)
        }
    }
    @Synchronized
    fun save(template: EpubReaderTemplate): EpubReaderTemplate = repository.save(template).also(::rememberType)
    @Synchronized
    fun importJson(raw: String, asCopy: Boolean = true): EpubReaderTemplate = repository.importJson(raw, asCopy).also(::rememberType)
    fun exportJson(id: String): String = requireNotNull(resolve(id)) { appCtx.getString(R.string.misc_template_not_found_2) + id }.toJson()
    fun copyOf(template: EpubReaderTemplate): EpubReaderTemplate = copyReaderTemplate(template)

    /** Selection and deletion use the same lock, so a late selection cannot restore a deleted ID. */
    @Synchronized
    fun saveSelection(id: String) {
        clearRetiredReferences()
        require(id.isEmpty() || resolve(id) != null) { "模板不存在" }
        ReadBookConfig.saveReaderTemplateSelection(id)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (repository.resolve(id) == null) return false
        return ReadBookConfig.withoutReaderTemplateReferences(setOf(id)) { repository.delete(id) }
    }

    @Synchronized
    fun hasHiddenBuiltIns(): Boolean = repository.hasHiddenBuiltIns()

    @Synchronized
    fun restoreBuiltIns() = repository.restoreBuiltIns()

    @Synchronized
    internal fun importLibrary(library: EpubReaderTemplateLibrary): List<EpubReaderTemplate> {
        library.validate()
        return ReadBookConfig.withoutReaderTemplateReferences(library.hiddenBuiltInIds + retiredBuiltinIds) {
            repository.importLibrary(library).onEach(::rememberType)
        }
    }

    @Synchronized
    internal fun exportLibrary(): EpubReaderTemplateLibrary = repository.exportLibrary()

    /** Saves all user templates and exact source for built-ins referenced by layout profiles. */
    fun backupJson(referencedIds: Iterable<String>): String = repository.backupJson(referencedIds)
    @Synchronized
    fun restoreJson(raw: String) = repository.restoreJson(raw).also { repository.list().forEach(::rememberType) }

    /** A shared style must never overwrite another style's template with a colliding id. */
    @Synchronized
    fun importForLayout(raw: String, expectedId: String): EpubReaderTemplate = repository.importForLayout(raw, expectedId).also(::rememberType)

    private fun clearRetiredReferences() {
        ReadBookConfig.withoutReaderTemplateReferences(retiredBuiltinIds) { Unit }
    }
}
