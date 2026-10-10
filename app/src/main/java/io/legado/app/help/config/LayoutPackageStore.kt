package io.legado.app.help.config

import android.content.Context
import com.google.gson.Gson
import io.legado.app.uikit.layout.BuiltinLayouts
import io.legado.app.uikit.layout.LayoutEngine
import io.legado.app.uikit.layout.LayoutPackageSpec
import java.io.File

/**
 * A2-1 runtime store for the layout engine (edit-in-package model, V4.8).
 * See docs/ui-rewrite-plan-v4-impl.md A2-1. Legacy uiCornerScale folds into
 * a custom package once; density/glass legacy keys fold in at A2-4.
 */
object LayoutPackageStore {

    private const val FILE_NAME = "uiLayoutPackageRuntime.json"
    const val CUSTOM_ID = "custom"

    data class Runtime(
        val activeId: String = BuiltinLayouts.default.id,
        val customSpec: LayoutPackageSpec? = null,
        val migratedFromLegacy: Boolean = false,
    )

    private val gson = Gson()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): Runtime? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        gson.fromJson(f.readText(), Runtime::class.java)
    }.getOrNull()

    fun save(context: Context, runtime: Runtime) {
        val tmp = File(context.filesDir, ".$FILE_NAME.tmp")
        tmp.writeText(gson.toJson(runtime))
        val dst = file(context)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }

    fun resolveSpec(runtime: Runtime): LayoutPackageSpec? =
        when (runtime.activeId) {
            CUSTOM_ID -> runtime.customSpec?.takeIf { it.isValid() }
            else -> BuiltinLayouts.byId(runtime.activeId)
        }

    fun init(context: Context) {
        val app = context.applicationContext
        var runtime = load(app)
        if (runtime == null) {
            val legacyScale = AppConfig.uiCornerScale
            runtime = if (legacyScale != 1f) {
                val folded = BuiltinLayouts.default.copy(shapeScale = legacyScale.coerceIn(0f, 1.5f))
                Runtime(
                    activeId = CUSTOM_ID,
                    customSpec = folded.copy(id = CUSTOM_ID, name = "我的界面", author = "迁移自旧版"),
                    migratedFromLegacy = true,
                )
            } else {
                Runtime(migratedFromLegacy = true)
            }
            save(app, runtime)
        }
        LayoutEngine.applyLayout(resolveSpec(runtime) ?: BuiltinLayouts.default)
    }

    fun persistCurrent(context: Context) {
        val runtime = load(context) ?: Runtime()
        val custom = if (LayoutEngine.activeLayout.id == CUSTOM_ID) LayoutEngine.activeLayout else null
        save(
            context,
            runtime.copy(
                activeId = LayoutEngine.activeLayout.id,
                customSpec = custom,
            ),
        )
    }
}
