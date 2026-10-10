package io.legado.app.uikit.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.paletteRoleFromKey
import io.legado.app.theme.pack.resolvePalette

/**
 * A1-4 Applicator: the ONLY value-resolution entry point for looks
 * (docs/ui-rewrite-plan-v4.md 2.2 / 4).
 *
 * Sandwich order (higher layer wins):
 * ① builtin factory defaults (Sky derivation, cached)
 * ② active theme package (seed derivation + package `override`)
 * ③ local manual tweaks (delta; keys `color.<palette_role>`)
 *
 * Reader page roles stay frozen (plan chapter 1 seam rule): READER_* keeps the
 * legacy values and is never affected by packages or tweaks.
 *
 * Reactivity: [isDark] / [revision] are Compose states — read them via
 * [rememberAppColorScheme] so any change (theme switch / tweak / night mode)
 * recomposes the whole App without restart.
 */
object Applicator {

    // ---- reactive state (Compose) ------------------------------------------

    var isDark by mutableStateOf(false)
        private set

    /** Bumped on every theme switch / tweak / wallpaper-seed change. */
    var revision by mutableStateOf(0)
        private set

    // ---- layer ② active package ---------------------------------------------

    @Volatile
    var activeTheme: ThemePackageSpec = BuiltinThemes.default
        private set

    /** Resolved Material You seed for the "dynamic" wallpaper theme. */
    @Volatile
    var wallpaperSeed: Int? = null
        private set

    /**
     * A1-5b: package fonts (absolute paths). Blank = not set by the package,
     * consumers fall back to the legacy settings. Reader font is frozen.
     */
    var uiFontPath by mutableStateOf("")
        private set

    var titleFontPath by mutableStateOf("")
        private set

    // ---- layer ③ manual tweaks (delta, only touched keys) -------------------

    private val tweaks = LinkedHashMap<String, String>()

    // ---- inputs from the app layer ------------------------------------------

    fun applyTheme(spec: ThemePackageSpec) {
        require(spec.isValid()) { "invalid theme spec: ${spec.id}" }
        activeTheme = spec
        uiFontPath = spec.fonts?.ui.orEmpty()
        titleFontPath = spec.fonts?.title.orEmpty()
        bump()
    }

    fun applyWallpaperSeed(seed: Int?) {
        if (wallpaperSeed == seed) return
        wallpaperSeed = seed
        bump()
    }

    fun applyDark(dark: Boolean) {
        if (isDark == dark) return
        isDark = dark
        bump()
    }

    fun setTweak(role: PaletteRole, argb: Int) {
        tweaks[tweakKey(role)] = String.format("#%08X", argb)
        bump()
    }

    fun clearTweak(role: PaletteRole) {
        if (tweaks.remove(tweakKey(role)) != null) bump()
    }

    fun clearAllTweaks() {
        if (tweaks.isNotEmpty()) {
            tweaks.clear()
            bump()
        }
    }

    fun isTweaked(role: PaletteRole): Boolean = tweaks.containsKey(tweakKey(role))

    /** For persistence: only touched keys. */
    fun tweaksSnapshot(): Map<String, String> = tweaks.toMap()

    fun loadTweaks(saved: Map<String, String>) {
        tweaks.clear()
        saved.forEach { (key, value) ->
            if (paletteRoleFromKey(key) != null && ThemePackageSpec.parseColor(value) != null) {
                tweaks[key.trim()] = value.trim()
            }
        }
        bump()
    }

    // ---- resolution ----------------------------------------------------------

    private fun tweakKey(role: PaletteRole) = "color.${role.name.lowercase()}"

    /** Layer ①: factory defaults = builtin Sky derivation, cached per mode. */
    private val factoryDefaults = mutableMapOf<Boolean, Map<PaletteRole, Int>>()

    private fun factory(dark: Boolean): Map<PaletteRole, Int> =
        factoryDefaults.getOrPut(dark) { BuiltinThemes.sky.resolvePalette(dark) }

    /** Full sandwich resolve for one role. */
    fun resolveColor(role: PaletteRole): Int {
        tweaks[tweakKey(role)]?.let { return ThemePackageSpec.parseColor(it)!! }
        return activeTheme.resolvePalette(isDark, wallpaperSeed)[role]
            ?: factory(isDark)[role]
            ?: 0xFF000000.toInt()
    }

    /** Full role set after all three layers (for previews and the XML bridge). */
    fun resolvePalette(): Map<PaletteRole, Int> {
        val base = activeTheme.resolvePalette(isDark, wallpaperSeed).toMutableMap()
        val defaults = factory(isDark)
        PaletteRole.entries.forEach { role ->
            if (!base.containsKey(role)) base[role] = defaults[role] ?: 0xFF000000.toInt()
            tweaks[tweakKey(role)]?.let { value ->
                ThemePackageSpec.parseColor(value)?.let { base[role] = it }
            }
        }
        return base
    }

    /** Map onto the stable [AppColorScheme] consumed by every Compose screen. */
    fun resolveScheme(): AppColorScheme {
        val p = resolvePalette()
        // Reader page is frozen (plan chapter 1): legacy values, never themed.
        val readerText = if (isDark) 0xFFCCC8C0.toInt() else 0xFF1C1B1F.toInt()
        val readerBg = if (isDark) 0xFF121212.toInt() else 0xFFF5F1E8.toInt()
        return AppColorScheme(
            primary = Color(p.getValue(PaletteRole.PRIMARY)),
            onPrimary = Color(p.getValue(PaletteRole.ON_PRIMARY)),
            secondary = Color(p.getValue(PaletteRole.SECONDARY)),
            onSecondary = Color(p.getValue(PaletteRole.ON_SECONDARY)),
            surface = Color(p.getValue(PaletteRole.SURFACE)),
            onSurface = Color(p.getValue(PaletteRole.ON_SURFACE)),
            surfaceVariant = Color(p.getValue(PaletteRole.SURFACE_VARIANT)),
            background = Color(p.getValue(PaletteRole.BACKGROUND)),
            onBackground = Color(p.getValue(PaletteRole.ON_BACKGROUND)),
            outline = Color(p.getValue(PaletteRole.OUTLINE)),
            muted = Color(p.getValue(PaletteRole.ON_SURFACE_VARIANT)),
            error = Color(p.getValue(PaletteRole.ERROR)),
            readerText = Color(readerText),
            readerBackground = Color(readerBg),
            isDark = isDark,
        )
    }

    /** Compose entry: read reactive state so changes recompose the App. */
    @Composable
    fun rememberAppColorScheme(): AppColorScheme {
        val dark = isDark
        val rev = revision
        return remember(dark, rev) { resolveScheme() }
    }

    private fun bump() {
        revision++
    }
}
