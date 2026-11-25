package io.legado.app.help.config.pkg.runtime

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import splitties.init.appCtx
import java.util.Locale

/**
 * Pure JVM material mode constants shared across data/config/package layers and UI renderers.
 */
object GlassMaterialModes {
    const val SOLID = "solid"
    const val GLASS = "glass"
    const val FROSTED = "frosted"
    const val CLEAR = "clear"
    const val DEFAULT = FROSTED
    const val LEGACY_NONE = "none"

    val modes: Set<String> = setOf(SOLID, GLASS, FROSTED, CLEAR)
}

/**
 * Resolves the effective global glass material mode (`solid / glass / frosted / clear`),
 * migrating legacy `"none"` and `exploreGlassEnabled = false` deterministically.
 */
fun resolveExploreGlassMode(
    rawMode: String?,
    legacyEnabled: Boolean
): String {
    val stored = rawMode
        ?.let { if (it == GlassMaterialModes.LEGACY_NONE) GlassMaterialModes.SOLID else it }
        ?.takeIf { it in GlassMaterialModes.modes }
    return when {
        stored != null -> stored
        !legacyEnabled -> GlassMaterialModes.SOLID
        else -> GlassMaterialModes.DEFAULT
    }
}

fun resolvedExploreGlassMode(): String = resolveExploreGlassMode(
    appCtx.getPrefString(PreferKey.exploreGlassEffectMode),
    appCtx.getPrefBoolean(PreferKey.exploreGlassEnabled, true)
)

/**
 * Pure JVM color string parsing/formatting shared by package presets and UI color pickers.
 */
object ColorParseRuntime {

    fun parseColorInput(
        raw: String?,
        allowDecimalArgb: Boolean = false,
        preserveAlpha: Boolean = false
    ): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.startsWith("#")) {
            val hex = text.substring(1)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val argbLong = when (hex.length) {
                3 -> {
                    val expanded = buildString(8) {
                        append("FF")
                        hex.forEach { ch ->
                            append(ch)
                            append(ch)
                        }
                    }
                    expanded.toLongOrNull(16)
                }
                4 -> {
                    val expanded = buildString(8) {
                        if (preserveAlpha) {
                            append(hex[0])
                            append(hex[0])
                        } else {
                            append("FF")
                        }
                        for (i in 1..3) {
                            append(hex[i])
                            append(hex[i])
                        }
                    }
                    expanded.toLongOrNull(16)
                }
                6 -> ("FF$hex").toLongOrNull(16)
                8 -> {
                    val resolved = if (preserveAlpha) hex else "FF${hex.substring(2)}"
                    resolved.toLongOrNull(16)
                }
                else -> null
            } ?: return null
            return argbLong.toInt()
        }
        if (allowDecimalArgb) {
            return text.toIntOrNull()
        }
        if (text.length == 6 || text.length == 8) {
            if (text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                val normalized = when {
                    text.length == 6 -> "FF$text"
                    preserveAlpha -> text
                    else -> "FF${text.substring(2)}"
                }
                return normalized.toLongOrNull(16)?.toInt()
            }
        }
        return null
    }

    fun formatHex6(argb: Int): String {
        val rgb = argb and 0x00FFFFFF
        return String.format(Locale.US, "#%06X", rgb)
    }
}
