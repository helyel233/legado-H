package io.legado.app.ui.book.read.page.provider

/**
 * Pure parsing of the CSS values the EPUB block-decoration code pulls out of styles.
 *
 * These used to live as private `String` extensions inside [TextChapterLayout]; moving them
 * here keeps the 3000-line layout engine smaller and, more importantly, lets the color and
 * length rules be pinned down with unit tests — a wrong color parse makes every styled EPUB
 * render with the wrong background or border, and there was no test catching it.
 *
 * The original code leaned on `android.graphics.Color.parseColor`, which is unavailable in a
 * plain JVM unit test. The packing is reimplemented by hand (hex / rgb / named all end up as
 * `0xAARRGGBB`), so the whole object is Android-free and testable without Robolectric. The
 * behavior is byte-for-byte what `Color.parseColor` produced.
 */
internal object EpubCssValues {

    // --- color ---------------------------------------------------------------

    /** Parses a CSS color (hex, rgb()/rgba(), or a named color) into `0xAARRGGBB`. */
    fun parseColor(input: String): Int? {
        val clean = input.trim().trimMatchingQuote()
        return when {
            clean.startsWith("rgba", true) || clean.startsWith("rgb", true) -> parseRgb(clean)
            clean.startsWith("#") -> runCatching { parseHex(clean.normalizeHexColor()) }.getOrNull()
            else -> toNamedColor(clean)?.let { runCatching { parseHex(it.normalizeHexColor()) }.getOrNull() }
        }
    }

    /** A 6- or 8-digit hex string (no leading `#`) as `0xAARRGGBB`; anything else is null. */
    fun parseTagColor(input: String): Int? {
        val clean = input.trim()
        if (clean.length == 6 || clean.length == 8) {
            return runCatching { parseHex("#$clean") }.getOrNull()
        }
        return null
    }

    /** Pulls the first color token out of a `background`/`border` shorthand value. */
    fun extractColor(input: String): String? {
        val clean = input.trim()
        if (clean.startsWith("#") || clean.startsWith("rgb", true)) return clean
        val parts = clean.split(' ', ',', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return parts.firstOrNull { part ->
            part.startsWith("#") || part.startsWith("rgb", true) || toNamedColor(part) != null
        }
    }

    // --- length --------------------------------------------------------------

    /** Pulls the first length token (px / em / rem / number) out of a shorthand value. */
    fun extractLength(input: String): String? {
        return input.trim().split(' ', '\t', '\n')
            .map { it.trim() }
            .firstOrNull { value ->
                value.endsWith("px", true) || value.endsWith("em", true) ||
                    value.endsWith("rem", true) || value.toFloatOrNull() != null
            }
    }

    /** Expands a CSS box shorthand (1–4 values) into exactly four [top, right, bottom, left]. */
    fun toBoxLengths(input: String): List<String> {
        val parts = input.trim().split(' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return when (parts.size) {
            0 -> emptyList()
            1 -> listOf(parts[0], parts[0], parts[0], parts[0])
            2 -> listOf(parts[0], parts[1], parts[0], parts[1])
            3 -> listOf(parts[0], parts[1], parts[2], parts[1])
            else -> parts.take(4)
        }
    }

    // --- spacing and break decisions -----------------------------------------

    /** Whether a `page-break-before`/`break-before` value forces a page break. */
    fun isAlwaysBreak(input: String): Boolean {
        val value = input.trim().lowercase()
        return value == "always" || value == "page" || value == "left" || value == "right"
    }

    /** Whether a margin/padding value is big enough to count as a visible block gap. */
    fun isLargeSpacing(input: String): Boolean {
        val value = input.trim().lowercase()
        if (value.isBlank() || value == "0") return false
        return when {
            value.endsWith("em") -> (value.dropLast(2).toFloatOrNull() ?: 0f) >= 1f
            value.endsWith("rem") -> (value.dropLast(3).toFloatOrNull() ?: 0f) >= 1f
            value.endsWith("%") -> (value.dropLast(1).toFloatOrNull() ?: 0f) >= 8f
            value.endsWith("px") -> (value.dropLast(2).toFloatOrNull() ?: 0f) >= 16f
            else -> (value.toFloatOrNull() ?: 0f) >= 16f
        }
    }

    /** Vertical spacing in px. Unlike [toHorizontalPx], `auto` yields null, not 0. */
    fun toSpacingPx(input: String, visibleWidth: Float, emBase: Float): Float? =
        toPx(input, visibleWidth, emBase, treatAutoAsZero = false)

    /** Horizontal length in px; `auto` and `0` both collapse to 0. */
    fun toHorizontalPx(input: String, visibleWidth: Float, emBase: Float): Float? =
        toPx(input, visibleWidth, emBase, treatAutoAsZero = true)

    private fun toPx(
        input: String,
        visibleWidth: Float,
        emBase: Float,
        treatAutoAsZero: Boolean
    ): Float? {
        val value = input.trim().lowercase()
        if (value.isBlank() || value == "0") return 0f
        if (treatAutoAsZero && value == "auto") return 0f
        return when {
            value.endsWith("%") -> {
                val percentage = value.dropLast(1).toFloatOrNull() ?: return null
                visibleWidth * percentage / 100f
            }
            value.endsWith("em") -> {
                val em = value.dropLast(2).toFloatOrNull() ?: return null
                emBase * em
            }
            value.endsWith("rem") -> {
                val rem = value.dropLast(3).toFloatOrNull() ?: return null
                emBase * rem
            }
            value.endsWith("px") -> value.dropLast(2).toFloatOrNull()
            else -> value.toFloatOrNull()
        }
    }

    // --- internals -----------------------------------------------------------

    private fun String.trimMatchingQuote(): String {
        val clean = trim()
        if (clean.length >= 2) {
            val first = clean.first()
            val last = clean.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return clean.substring(1, clean.lastIndex)
            }
        }
        return clean
    }

    private fun String.normalizeHexColor(): String {
        val hex = trim().removePrefix("#")
        return when (hex.length) {
            3 -> "#" + hex.map { "$it$it" }.joinToString("")
            4 -> "#" + hex.map { "$it$it" }.joinToString("")
            else -> "#$hex"
        }
    }

    private fun parseHex(hex: String): Int? {
        val h = hex.removePrefix("#")
        return when (h.length) {
            3 -> pack(0xFF, hex2(h[0], h[0]), hex2(h[1], h[1]), hex2(h[2], h[2]))
            4 -> pack(hex2(h[0], h[0]), hex2(h[1], h[1]), hex2(h[2], h[2]), hex2(h[3], h[3]))
            6 -> pack(0xFF, hex2(h[0], h[1]), hex2(h[2], h[3]), hex2(h[4], h[5]))
            8 -> pack(hex2(h[0], h[1]), hex2(h[2], h[3]), hex2(h[4], h[5]), hex2(h[6], h[7]))
            else -> null
        }
    }

    private fun parseRgb(input: String): Int? {
        val start = input.indexOf('(')
        val end = input.lastIndexOf(')')
        if (start < 0 || end <= start) return null
        val parts = input.substring(start + 1, end)
            .split(',', ' ', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 3) return null

        fun component(value: String): Int {
            return if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f).toInt()
            } else {
                value.toFloatOrNull()?.toInt() ?: 0
            }.coerceIn(0, 255)
        }

        val alpha = parts.getOrNull(3)?.let { value ->
            if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 100f) * 2.55f).toInt()
            } else {
                ((value.toFloatOrNull() ?: 1f) * 255f).toInt()
            }
        } ?: 255

        return pack(
            alpha.coerceIn(0, 255),
            component(parts[0]),
            component(parts[1]),
            component(parts[2])
        )
    }

    private fun toNamedColor(input: String): String? {
        return when (input.lowercase()) {
            "black" -> "#000000"
            "white" -> "#FFFFFF"
            "red" -> "#FF0000"
            "green" -> "#008000"
            "blue" -> "#0000FF"
            "cyan", "aqua" -> "#00FFFF"
            "magenta", "fuchsia" -> "#FF00FF"
            "yellow" -> "#FFFF00"
            "gray", "grey" -> "#808080"
            "silver" -> "#C0C0C0"
            "maroon" -> "#800000"
            "purple" -> "#800080"
            "teal" -> "#008080"
            "navy" -> "#000080"
            "orange" -> "#FFA500"
            "transparent" -> "#00000000"
            else -> null
        }
    }

    private fun hex2(c1: Char, c2: Char): Int =
        (c1.digitToInt(16) shl 4) or c2.digitToInt(16)

    private fun pack(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
