package io.legado.app.help.config

import android.content.Context
import com.google.gson.Gson
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.uikit.theme.Applicator
import io.legado.app.uikit.theme.BuiltinThemes
import java.io.File

/**
 * A2-4b theme package library (V4.10 multi-package model).
 * Runtime file uiThemePackageRuntime.json: activeId + userSpecs list.
 * Back-compat: A1 single-custom format normalizes into a user package;
 * first launch translates the legacy theme into "我的主题包 1".
 */
object ThemePackageStore {

    private const val FILE_NAME = "uiThemePackageRuntime.json"

    // Gson bypasses Kotlin defaults: all fields nullable, normalized on load.
    data class Runtime(
        val activeId: String? = null,
        val userSpecs: List<ThemePackageSpec>? = null,
        val migratedFromLegacy: Boolean? = null,
        val customSpec: ThemePackageSpec? = null,
    )

    private val gson = Gson()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): Runtime? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        normalize(gson.fromJson(f.readText(), Runtime::class.java))
    }.getOrNull()

    private fun normalize(r: Runtime): Runtime {
        val users = r.userSpecs ?: emptyList()
        if (users.isNotEmpty()) {
            return Runtime(activeId = r.activeId ?: BuiltinThemes.default.id, userSpecs = users, migratedFromLegacy = r.migratedFromLegacy)
        }
        r.customSpec?.let { cs ->
            val spec = cs.copy(id = newUserId())
            return Runtime(activeId = spec.id, userSpecs = listOf(spec), migratedFromLegacy = true)
        }
        return Runtime(activeId = r.activeId ?: BuiltinThemes.default.id, migratedFromLegacy = true)
    }

    fun save(context: Context, runtime: Runtime) {
        val tmp = File(context.filesDir, ".$FILE_NAME.tmp")
        tmp.writeText(gson.toJson(runtime))
        val dst = file(context)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }

    fun resolveSpec(runtime: Runtime, id: String? = runtime.activeId): ThemePackageSpec? =
        BuiltinThemes.byId(id ?: "") ?: (runtime.userSpecs ?: emptyList()).firstOrNull { it.id == id }

    fun listUser(context: Context): List<ThemePackageSpec> =
        load(context)?.userSpecs ?: emptyList()

    fun upsertUserSpec(context: Context, spec: ThemePackageSpec) {
        val r = load(context) ?: Runtime()
        save(context, r.copy(userSpecs = (r.userSpecs ?: emptyList()).filterNot { it.id == spec.id } + spec))
    }

    fun deleteUserSpec(context: Context, id: String) {
        val r = load(context) ?: return
        save(context, r.copy(userSpecs = (r.userSpecs ?: emptyList()).filterNot { it.id == id }))
    }

    fun persistActive(context: Context) {
        val r = load(context) ?: Runtime()
        save(context, r.copy(activeId = Applicator.activeTheme.id))
    }

    fun newUserId(): String = "u_" + System.currentTimeMillis()

    fun init(context: Context) {
        val app = context.applicationContext
        var runtime = load(app)
        if (runtime == null) {
            val seed = runCatching { ThemeStore.primaryColor(app) }.getOrNull()
            runtime = if (seed != null) {
                val spec = ThemePackageSpec(
                    id = newUserId(),
                    name = "我的主题包 1",
                    author = "迁移自旧版",
                    seed = String.format("#%06X", seed and 0xFFFFFF),
                )
                Runtime(activeId = spec.id, userSpecs = listOf(spec), migratedFromLegacy = true)
            } else {
                Runtime(migratedFromLegacy = true)
            }
            save(app, runtime)
        }
        Applicator.applyTheme(resolveSpec(runtime) ?: BuiltinThemes.default)
    }
}
