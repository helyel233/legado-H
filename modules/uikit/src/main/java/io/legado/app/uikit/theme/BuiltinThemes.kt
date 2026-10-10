package io.legado.app.uikit.theme

import io.legado.app.theme.pack.ThemePackageSpec

/**
 * A1-3 builtin official themes (docs/ui-rewrite-plan-v4.md 2.1).
 * Same data ships as app assets `assets/themes/<id>/theme.json` for export
 * and community reference; consistency check lands in A1-7.
 */
object BuiltinThemes {

    val sky = ThemePackageSpec(
        id = "sky", name = "晴空白", author = "legado-H",
        seed = "#5B6ABF",
    )

    val night = ThemePackageSpec(
        id = "night", name = "墨夜黑", author = "legado-H",
        seed = "#3A4258", darkStrategy = "muted",
    )

    val amoled = ThemePackageSpec(
        id = "amoled", name = "AMOLED 纯黑", author = "legado-H",
        seed = "#5B6ABF", darkStrategy = "amoled",
    )

    val paper = ThemePackageSpec(
        id = "paper", name = "纸张米", author = "legado-H",
        seed = "#A08A5B",
    )

    /** Seed resolved at runtime from the wallpaper (Android 12+, falls back to Sky). */
    val dynamic = ThemePackageSpec(
        id = "dynamic", name = "动态取色", author = "legado-H",
        seed = "dynamic",
    )

    /** Grayscale in both modes (Monochrome palette). */
    val eink = ThemePackageSpec(
        id = "eink", name = "电子墨水", author = "legado-H",
        seed = "#808080", darkStrategy = "mono",
    )

    val all: List<ThemePackageSpec> = listOf(sky, night, amoled, paper, dynamic, eink)

    fun byId(id: String): ThemePackageSpec? = all.firstOrNull { it.id == id }

    /** Factory default. */
    val default: ThemePackageSpec = sky
}
