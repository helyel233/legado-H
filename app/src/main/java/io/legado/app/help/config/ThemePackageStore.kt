package io.legado.app.help.config

import android.content.Context
import com.google.gson.Gson
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.uikit.theme.Applicator
import io.legado.app.uikit.theme.BuiltinThemes
import java.io.File

/**
 * A1-5/A1-6 runtime store for the new theme engine (docs/ui-rewrite-plan-v4.md 2.2).
 *
 * Single runtime file `uiThemePackageRuntime.json` (filesDir):
 *  - activeId: active package id (builtin id or "custom")
 *  - customSpec: the user's custom package (seed/strategy), null unless used
 *  - tweaks: manual delta, keys `color.<palette_role>`
 *  - migratedFromLegacy: translator already ran (A1-6, one-shot)
 *
 * Legacy translation (one-shot, docs/ui-rewrite-plan-v4-impl.md A1-6):
 * active spec = custom package whose seed is the legacy primary color
 * (ThemeStore.KEY_PRIMARY_COLOR). The palette derives a coherent day/night
 * pair from that seed; users can fine-tune from the theme center.
 */
object ThemePackageStore {

    private const val FILE_NAME = "uiThemePackageRuntime.json"
    const val CUSTOM_ID = "custom"

    data class Runtime(
        val activeId: String = BuiltinThemes.default.id,
        val customSpec: ThemePackageSpec? = null,
        val tweaks: Map<String, String> = emptyMap(),
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

    /** Push the stored runtime into the engine; falls back to factory default. */
    fun applyToApplicator(context: Context) {
        val runtime = load(context)
        val spec = runtime?.let { resolveSpec(it) } ?: BuiltinThemes.default
        Applicator.applyTheme(spec)
        Applicator.loadTweaks(runtime?.tweaks ?: emptyMap())
        Applicator.applyDark(AppConfig.isNightTheme)
    }

    /** Snapshot the engine state back to disk (called after user mutations). */
    fun persistCurrent(context: Context) {
        val runtime = load(context) ?: Runtime()
        val custom = if (Applicator.activeTheme.id == CUSTOM_ID) Applicator.activeTheme else null
        save(
            context,
            runtime.copy(
                activeId = Applicator.activeTheme.id,
                customSpec = custom,
                tweaks = Applicator.tweaksSnapshot(),
            ),
        )
    }

    fun resolveSpec(runtime: Runtime): ThemePackageSpec? =
        when (runtime.activeId) {
            CUSTOM_ID -> runtime.customSpec?.takeIf { it.isValid() }
            else -> BuiltinThemes.byId(runtime.activeId)
        }

    // ---- A1-6 legacy translator (one-shot) ---------------------------------

    /**
     * Translate the legacy theme into a custom package seeded by the legacy
     * primary color. Runs only when no runtime file exists yet; then the
     * runtime is applied to the engine.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        if (load(app) == null) {
            save(app, translateLegacy(app))
        }
        applyToApplicator(app)
    }

    private fun translateLegacy(context: Context): Runtime {
        val seed = runCatching { ThemeStore.primaryColor(context) }.getOrNull()
        if (seed == null) return Runtime(migratedFromLegacy = true)
        return Runtime(
            activeId = CUSTOM_ID,
            customSpec = ThemePackageSpec(
                id = CUSTOM_ID,
                name = "我的主题",
                author = "迁移自旧版",
                seed = String.format("#%06X", seed and 0xFFFFFF),
            ),
            migratedFromLegacy = true,
        )
    }
}
