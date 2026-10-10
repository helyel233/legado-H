package io.legado.app.help.config

import android.content.Context
import com.google.gson.Gson
import io.legado.app.uikit.layout.BuiltinLayouts
import io.legado.app.uikit.layout.LayoutEngine
import io.legado.app.uikit.layout.LayoutPackageSpec
import java.io.File

/**
 * A2-4b layout package library (V4.10 multi-package model), mirroring
 * ThemePackageStore. Legacy uiCornerScale/bottomBarLayoutMode fold into
 * "我的界面包 1" on first launch.
 */
object LayoutPackageStore {

    private const val FILE_NAME = "uiLayoutPackageRuntime.json"

    data class Runtime(
        val activeId: String = BuiltinLayouts.default.id,
        val userSpecs: List<LayoutPackageSpec> = emptyList(),
        val migratedFromLegacy: Boolean = false,
        val customSpec: LayoutPackageSpec? = null,
    )

    private val gson = Gson()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): Runtime? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        normalize(gson.fromJson(f.readText(), Runtime::class.java))
    }.getOrNull()

    private fun normalize(r: Runtime): Runtime = when {
        r.userSpecs.isNotEmpty() -> r
        r.customSpec != null -> {
            val spec = r.customSpec.copy(id = newUserId())
            r.copy(userSpecs = listOf(spec), activeId = if (r.activeId == "custom") spec.id else r.activeId)
        }
        else -> r
    }

    fun save(context: Context, runtime: Runtime) {
        val tmp = File(context.filesDir, ".$FILE_NAME.tmp")
        tmp.writeText(gson.toJson(runtime))
        val dst = file(context)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }

    fun resolveSpec(runtime: Runtime, id: String = runtime.activeId): LayoutPackageSpec? =
        BuiltinLayouts.byId(id) ?: runtime.userSpecs.firstOrNull { it.id == id }

    fun listUser(context: Context): List<LayoutPackageSpec> =
        load(context)?.userSpecs ?: emptyList()

    fun upsertUserSpec(context: Context, spec: LayoutPackageSpec) {
        val r = load(context) ?: Runtime()
        save(context, r.copy(userSpecs = r.userSpecs.filterNot { it.id == spec.id } + spec))
    }

    fun deleteUserSpec(context: Context, id: String) {
        val r = load(context) ?: return
        save(context, r.copy(userSpecs = r.userSpecs.filterNot { it.id == id }))
    }

    fun persistActive(context: Context) {
        val r = load(context) ?: Runtime()
        save(context, r.copy(activeId = LayoutEngine.activeLayout.id))
    }

    fun newUserId(): String = "u_" + System.currentTimeMillis()

    fun init(context: Context) {
        val app = context.applicationContext
        var runtime = load(app)
        if (runtime == null) {
            val legacyScale = AppConfig.uiCornerScale
            val legacyNav = when (AppConfig.bottomBarLayoutMode) {
                "sidebar" -> LayoutPackageSpec.NAV_SIDE
                "standard" -> LayoutPackageSpec.NAV_BOTTOM
                else -> LayoutPackageSpec.NAV_FLOAT
            }
            val needFold = legacyScale != 1f || legacyNav != BuiltinLayouts.default.navPosition
            runtime = if (needFold) {
                val spec = BuiltinLayouts.default
                    .copy(
                        id = newUserId(),
                        name = "我的界面包 1",
                        author = "迁移自旧版",
                        shapeScale = legacyScale.coerceIn(0f, 1.5f),
                        navPosition = legacyNav,
                    )
                Runtime(activeId = spec.id, userSpecs = listOf(spec), migratedFromLegacy = true)
            } else {
                Runtime(migratedFromLegacy = true)
            }
            save(app, runtime)
        }
        LayoutEngine.applyLayout(resolveSpec(runtime) ?: BuiltinLayouts.default)
    }
}
