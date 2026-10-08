package io.legado.app.help.config

import java.util.Locale

internal object BubbleSvgPolicy {
    fun escapeText(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private val eventHandlerPattern = Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE)
    private val hrefPattern = Regex(
        """(?:href|xlink:href)\s*=\s*(["'])(.*?)\1""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val urlPattern = Regex(
        """url\s*\((.*?)\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val xmlDeclPattern = Regex(
        """<\?xml\s+version[^>]*\?>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val doctypePattern = Regex(
        """<!DOCTYPE\b[^>\[]*(?:\[[^\]]*\])?[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val placeholderRepairPattern = Regex(
        """\$\{([A-Za-z_]\w*)\}|\{\{([A-Za-z_]\w*)\}\}|\$([A-Za-z_]\w*)\b""",
        RegexOption.IGNORE_CASE
    )
    private val numberPlaceholders = setOf("displaytext", "text", "number", "value", "count", "num")
    private val colorPlaceholders = setOf("color", "fontcolor", "textcolor")

    /**
     * Best-effort repair of an authoring-tool SVG so it passes [validate] and renders on AndroidSVG:
     * - strips the `<?xml ...?>` declaration and `<!DOCTYPE ...>` (AndroidSVG rejects both, and a
     *   DOCTYPE may carry external entities);
     * - maps common authoring placeholders to the two placeholders the renderer supports (`${num}`
     *   and `${color}`), in `$name`, `${name}` and `{{name}}` forms. Unknown placeholders are left
     *   untouched. This is a convenience heuristic for typical exports, not a strict normalization.
     * Security checks in [validate] still run afterwards on the repaired template.
     */
    fun repair(svg: String): String {
        var result = svg
        result = xmlDeclPattern.replace(result, "")
        result = doctypePattern.replace(result, "")
        result = placeholderRepairPattern.replace(result) { match ->
            val rawName = match.groupValues[1]
                .ifEmpty { match.groupValues[2] }
                .ifEmpty { match.groupValues[3] }
            when (rawName.lowercase(Locale.ROOT)) {
                in numberPlaceholders -> "\${num}"
                in colorPlaceholders -> "\${color}"
                else -> match.value
            }
        }
        return result.trimIndent().trim()
    }

    fun validate(svg: String) {
        val lower = svg.lowercase(Locale.ROOT)
        require("<svg" in lower) { "bubble template is not SVG" }
        require("<!doctype" !in lower && "<!entity" !in lower) { "bubble SVG contains XML entities" }
        require("<?xml-stylesheet" !in lower) { "bubble SVG contains an external stylesheet" }
        require("<script" !in lower && "<foreignobject" !in lower) { "bubble SVG contains active content" }
        require(!eventHandlerPattern.containsMatchIn(svg)) { "bubble SVG contains event handlers" }
        for (match in hrefPattern.findAll(svg)) {
            requireSafeReference(match.groupValues[2].trim(), "external reference")
        }
        for (match in urlPattern.findAll(svg)) {
            requireSafeReference(match.groupValues[1].trim().trim('"', '\''), "external URL")
        }
    }

    fun packageReferences(svg: String): Set<String> {
        return buildSet {
            hrefPattern.findAll(svg).forEach { match ->
                match.groupValues[2].trim().takeIf(::isPackageReference)?.let(::add)
            }
            urlPattern.findAll(svg).forEach { match ->
                match.groupValues[1].trim().trim('"', '\'')
                    .takeIf(::isPackageReference)
                    ?.let(::add)
            }
        }
    }

    private fun requireSafeReference(value: String, description: String) {
        require(
            value.startsWith("#") ||
                value.startsWith("data:image/", ignoreCase = true) ||
                PackageResourcePolicy.isSafeReference(value)
        ) {
            "bubble SVG contains an $description"
        }
    }

    private fun isPackageReference(value: String): Boolean {
        return !value.startsWith("#") &&
            !value.startsWith("data:image/", ignoreCase = true) &&
            PackageResourcePolicy.isSafeReference(value)
    }
}
