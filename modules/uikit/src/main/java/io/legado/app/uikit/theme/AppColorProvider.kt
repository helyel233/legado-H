package io.legado.app.uikit.theme

/**
 * Semantic color names for the View(XML) side.
 */
enum class AppColorName {
    PRIMARY, ON_PRIMARY, SECONDARY, ON_SECONDARY,
    SURFACE, ON_SURFACE, SURFACE_VARIANT,
    BACKGROUND, ON_BACKGROUND, OUTLINE, MUTED, ERROR,
    READER_TEXT, READER_BACKGROUND,
}

/**
 * View(XML) side color bridge. The app registers an implementation at startup;
 * after P0a the implementation delegates to the theme package repository.
 */
fun interface AppColorProvider {
    fun color(name: AppColorName): Int
}

/**
 * Single entry point for View-side semantic colors.
 * Falls back to the built-in light palette until a provider is registered.
 */
object AppUiColors {

    @Volatile
    var provider: AppColorProvider? = null

    @Volatile
    var radiusScale: Float = 1f

    val primary: Int get() = get(AppColorName.PRIMARY)
    val onPrimary: Int get() = get(AppColorName.ON_PRIMARY)
    val secondary: Int get() = get(AppColorName.SECONDARY)
    val surface: Int get() = get(AppColorName.SURFACE)
    val onSurface: Int get() = get(AppColorName.ON_SURFACE)
    val surfaceVariant: Int get() = get(AppColorName.SURFACE_VARIANT)
    val background: Int get() = get(AppColorName.BACKGROUND)
    val onBackground: Int get() = get(AppColorName.ON_BACKGROUND)
    val outline: Int get() = get(AppColorName.OUTLINE)
    val muted: Int get() = get(AppColorName.MUTED)
    val error: Int get() = get(AppColorName.ERROR)
    val readerText: Int get() = get(AppColorName.READER_TEXT)
    val readerBackground: Int get() = get(AppColorName.READER_BACKGROUND)

    private val fallback = mapOf(
        AppColorName.PRIMARY to 0xFF3F51B5.toInt(),
        AppColorName.ON_PRIMARY to 0xFFFFFFFF.toInt(),
        AppColorName.SECONDARY to 0xFF5C6BC0.toInt(),
        AppColorName.ON_SECONDARY to 0xFFFFFFFF.toInt(),
        AppColorName.SURFACE to 0xFFFFFFFF.toInt(),
        AppColorName.ON_SURFACE to 0xFF1C1B1F.toInt(),
        AppColorName.SURFACE_VARIANT to 0xFFF0F0F4.toInt(),
        AppColorName.BACKGROUND to 0xFFFAFAFA.toInt(),
        AppColorName.ON_BACKGROUND to 0xFF1C1B1F.toInt(),
        AppColorName.OUTLINE to 0xFFE0E0E0.toInt(),
        AppColorName.MUTED to 0xFF8A8A8E.toInt(),
        AppColorName.ERROR to 0xFFB3261E.toInt(),
        AppColorName.READER_TEXT to 0xFF1C1B1F.toInt(),
        AppColorName.READER_BACKGROUND to 0xFFF5F1E8.toInt(),
    )

    private fun get(name: AppColorName): Int =
        provider?.color(name) ?: fallback.getValue(name)
}
