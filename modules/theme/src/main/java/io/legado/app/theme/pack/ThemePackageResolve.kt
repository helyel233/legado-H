package io.legado.app.theme.pack

import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.palette.SeedPalette

/** Factory fallback seed = builtin Sky theme (docs/ui-rewrite-plan-v4.md 2.1). */
internal const val FALLBACK_SKY = 0xFF5B6ABF.toInt()

/**
 * A1-4 layer ②: derive the full palette for this package.
 *
 * Layer order handled by the caller (uikit Applicator):
 * ① builtin defaults → ② this palette (seed derivation + package override) → ③ local tweaks.
 *
 * @param dark day/night switch
 * @param wallpaperSeed resolved Material You seed for the "dynamic" wallpaper theme;
 *        null falls back to the Sky seed (Android < 12 or unavailable).
 */
fun ThemePackageSpec.resolvePalette(
    dark: Boolean,
    wallpaperSeed: Int? = null,
): Map<PaletteRole, Int> {
    val seed = when {
        !isWallpaperSeed -> seedArgb() ?: FALLBACK_SKY
        wallpaperSeed != null -> wallpaperSeed
        else -> FALLBACK_SKY
    }
    val palette = SeedPalette.generate(seed, strategy, dark).toMutableMap()
    `override`?.forEach { (key, value) ->
        val role = paletteRoleFromKey(key) ?: return@forEach
        val argb = ThemePackageSpec.parseColor(value) ?: return@forEach
        palette[role] = argb
    }
    return palette
}

/**
 * Override keys look like `color.primary` / `color.on_background`
 * (case-insensitive). Unknown keys are skipped — never a crash.
 */
fun paletteRoleFromKey(key: String): PaletteRole? {
    val name = key.trim().removePrefix("color.").trim()
    if (name.isEmpty()) return null
    return PaletteRole.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
