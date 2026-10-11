package io.legado.app.ui.host.pack

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import io.legado.app.uikit.theme.BuiltinThemes
import io.legado.app.uikit.v5.pack.BuiltinLayoutPackages
import io.legado.app.uikit.v5.pack.LayoutPackageSpecV2
import io.legado.app.uikit.v5.resolve.LayoutResolver
import io.legado.app.uikit.v5.resolve.ThemeResolver
import io.legado.app.theme.pack.ThemePackageSpec

/**
 * P3-2 V5 双包库（用户多包模型，docs/ui-v5-skeleton-first.md 4.3）。
 *
 * - 主题包 / 界面包两个库，官方包 + 用户包（数量不限），点包即应用；
 * - **另存模型**：编辑官方包时自动克隆为"我的包 N"再改，官方包永远受保护；
 * - 状态是 Compose State：库变化（增删改/切换）自动刷新 UI；
 * - 持久化一个 json（filesDir/v5_packages.json），轻量同步写（包数据本身很小）；
 * - **不做旧存档迁移**（前提 5）：旧 ThemePackageManager 栈与本库无关，
 *   P4-5 随旧壳一起物理删除，P5 提供一次性导入器。
 */
object V5PackageStore {

    private const val FILE = "v5_packages.json"
    private val gson = Gson()

    data class Library(
        val userThemes: List<ThemePackageSpec> = emptyList(),
        val userLayouts: List<LayoutPackageSpecV2> = emptyList(),
        val activeThemeId: String = BuiltinThemes.default.id,
        val activeLayoutId: String = BuiltinLayoutPackages.default.id,
        val nextThemeSeq: Int = 1,
        val nextLayoutSeq: Int = 1,
    )

    private var lib: Library by mutableStateOf(Library())
    private lateinit var appContext: Context

    val initialized: Boolean get() = this::appContext.isInitialized

    // ---------------------------------------------------------------- 生命周期

    /** App 宿主启动时调用一次：读盘 + 应用 active 包。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        lib = try {
            val f = java.io.File(appContext.filesDir, FILE)
            if (f.exists()) gson.fromJson(f.readText(), Library::class.java) ?: Library() else Library()
        } catch (_: Exception) {
            Library()
        }
        restoreActive()
    }

    private fun restoreActive() {
        val theme = allThemes().firstOrNull { it.id == lib.activeThemeId } ?: BuiltinThemes.default
        val layout = allLayouts().firstOrNull { it.id == lib.activeLayoutId } ?: BuiltinLayoutPackages.default
        runCatching { ThemeResolver.applyTheme(theme) }
        runCatching { LayoutResolver.apply(layout.toVisualSpec()) }
    }

    private fun persist() {
        try {
            java.io.File(appContext.filesDir, FILE).writeText(gson.toJson(lib))
        } catch (_: Exception) {
            // 持久化失败不影响本次会话使用
        }
    }

    // ---------------------------------------------------------------- 读取

    fun userThemes(): List<ThemePackageSpec> = lib.userThemes
    fun userLayouts(): List<LayoutPackageSpecV2> = lib.userLayouts
    fun activeThemeId(): String = lib.activeThemeId
    fun activeLayoutId(): String = lib.activeLayoutId

    fun allThemes(): List<ThemePackageSpec> = BuiltinThemes.all + lib.userThemes
    fun allLayouts(): List<LayoutPackageSpecV2> = BuiltinLayoutPackages.all + lib.userLayouts

    fun activeTheme(): ThemePackageSpec =
        allThemes().firstOrNull { it.id == lib.activeThemeId } ?: BuiltinThemes.default

    fun activeLayout(): LayoutPackageSpecV2 =
        allLayouts().firstOrNull { it.id == lib.activeLayoutId } ?: BuiltinLayoutPackages.default

    // ---------------------------------------------------------------- 应用

    fun applyTheme(id: String) {
        val spec = allThemes().firstOrNull { it.id == id } ?: return
        lib = lib.copy(activeThemeId = id)
        runCatching { ThemeResolver.applyTheme(spec) }
        // P4-1：新 → 旧 同步（旧 View 页面与窗口色跟随 V5 包库）
        if (initialized) runCatching { io.legado.app.help.config.V5ThemeSync.pushToLegacy(appContext, spec) }
        persist()
    }

    fun applyLayout(id: String) {
        val spec = allLayouts().firstOrNull { it.id == id } ?: return
        lib = lib.copy(activeLayoutId = id)
        runCatching { LayoutResolver.apply(spec.toVisualSpec()) }
        persist()
    }

    // ---------------------------------------------------------------- 另存 / 编辑

    /** 官方包 → 克隆为"我的包 N"；用户包 → 原样返回（可直接编辑）。 */
    fun editableTheme(source: ThemePackageSpec): ThemePackageSpec =
        if (BuiltinThemes.byId(source.id) != null) {
            val name = "我的主题包 ${lib.nextThemeSeq}"
            val copy = source.copy(
                id = "user_theme_${System.currentTimeMillis()}",
                name = name,
                author = "zego",
                basedOn = source.id,
            )
            lib = lib.copy(userThemes = lib.userThemes + copy, nextThemeSeq = lib.nextThemeSeq + 1)
            persist()
            copy
        } else {
            source
        }

    fun editableLayout(source: LayoutPackageSpecV2): LayoutPackageSpecV2 =
        if (BuiltinLayoutPackages.byId(source.id) != null) {
            val name = "我的界面包 ${lib.nextLayoutSeq}"
            val copy = source.copy(
                id = "user_layout_${System.currentTimeMillis()}",
                name = name,
                author = "zego",
                basedOn = source.id,
            )
            lib = lib.copy(userLayouts = lib.userLayouts + copy, nextLayoutSeq = lib.nextLayoutSeq + 1)
            persist()
            copy
        } else {
            source
        }

    /** 保存对用户包的修改并立即应用（包内编辑的落点）。官方包被拒绝 —— 先 editable* 克隆。 */
    fun updateTheme(spec: ThemePackageSpec) {
        if (BuiltinThemes.byId(spec.id) != null) return
        if (lib.userThemes.none { it.id == spec.id }) return
        lib = lib.copy(userThemes = lib.userThemes.map { if (it.id == spec.id) spec else it })
        if (lib.activeThemeId == spec.id) runCatching { ThemeResolver.applyTheme(spec) }
        persist()
    }

    fun updateLayout(spec: LayoutPackageSpecV2) {
        if (BuiltinLayoutPackages.byId(spec.id) != null) return
        if (lib.userLayouts.none { it.id == spec.id }) return
        lib = lib.copy(userLayouts = lib.userLayouts.map { if (it.id == spec.id) spec else it })
        if (lib.activeLayoutId == spec.id) runCatching { LayoutResolver.apply(spec.toVisualSpec()) }
        persist()
    }

    /** 新建：基于当前 active 复制一份可编辑包（不自动应用）。 */
    fun newThemeFromActive(): ThemePackageSpec = editableTheme(activeTheme())
    fun newLayoutFromActive(): LayoutPackageSpecV2 = editableLayout(activeLayout())

    // ---------------------------------------------------------------- 资产导入

    /**
     * P3-1 把用户选的字体/图片拷进 `filesDir/v5_assets/<packageId>/`，
     * 返回绝对路径（写进 ThemePackageSpec 的 fonts/images/texture）。
     * @param kind font / image / texture（用于命名与将来清理）
     */
    fun importAsset(uri: android.net.Uri, packageId: String, kind: String): String? {
        if (!this::appContext.isInitialized) return null
        return try {
            val dir = java.io.File(appContext.filesDir, "v5_assets/$packageId").apply { mkdirs() }
            val displayName = appContext.contentResolver.queryName(uri) ?: "asset"
            val ext = displayName.substringAfterLast('.', "bin").lowercase().take(8)
            val target = java.io.File(dir, "${kind}_${System.currentTimeMillis()}.$ext")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun android.content.ContentResolver.queryName(uri: android.net.Uri): String? =
        try {
            query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
            }
        } catch (_: Exception) {
            null
        }

    // ---------------------------------------------------------------- 重命名 / 删除

    fun renameTheme(id: String, name: String) {
        if (BuiltinThemes.byId(id) != null) return
        lib = lib.copy(userThemes = lib.userThemes.map {
            if (it.id == id) it.copy(name = name) else it
        })
        persist()
    }

    fun renameLayout(id: String, name: String) {
        if (BuiltinLayoutPackages.byId(id) != null) return
        lib = lib.copy(userLayouts = lib.userLayouts.map {
            if (it.id == id) it.copy(name = name) else it
        })
        persist()
    }

    /** 删除用户包；删的是当前使用中的包则回退官方默认。 */
    fun deleteTheme(id: String) {
        if (BuiltinThemes.byId(id) != null) return
        lib = lib.copy(userThemes = lib.userThemes.filter { it.id != id })
        if (lib.activeThemeId == id) applyTheme(BuiltinThemes.default.id) else persist()
    }

    fun deleteLayout(id: String) {
        if (BuiltinLayoutPackages.byId(id) != null) return
        lib = lib.copy(userLayouts = lib.userLayouts.filter { it.id != id })
        if (lib.activeLayoutId == id) applyLayout(BuiltinLayoutPackages.default.id) else persist()
    }
}
