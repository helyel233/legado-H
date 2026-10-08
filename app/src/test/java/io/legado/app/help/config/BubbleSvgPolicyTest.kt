package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class BubbleSvgPolicyTest {

    @Test
    fun allowsLocalFragmentReferences() {
        BubbleSvgPolicy.validate(
            """<svg><defs><linearGradient id="g"/></defs><path fill="url(#g)"/><use href="#g"/></svg>"""
        )
    }

    @Test
    fun allowsPackageAliasesAndRelativeAssets() {
        val svg = """<svg><image href="asset://background"/><image href="assets/icon.webp"/></svg>"""

        BubbleSvgPolicy.validate(svg)

        assertEquals(
            setOf("asset://background", "assets/icon.webp"),
            BubbleSvgPolicy.packageReferences(svg)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNetworkReferences() {
        BubbleSvgPolicy.validate("""<svg><image href="https://example.com/a.png"/></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsScriptsAndEventHandlers() {
        BubbleSvgPolicy.validate("""<svg onload="run()"><script>run()</script></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEventHandlerWithSingleQuotes() {
        BubbleSvgPolicy.validate("""<svg><rect onload='run()'/></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEventHandlerWithoutQuotes() {
        BubbleSvgPolicy.validate("""<svg><rect onload=run() /></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEventHandlerWithMixedCaseAndSpaces() {
        BubbleSvgPolicy.validate("""<svg><rect OnLoad = "run()" /></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsXmlEntities() {
        BubbleSvgPolicy.validate("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg>&x;</svg>""")
    }

    @Test
    fun repairStripsXmlDeclarationAndDoctype() {
        val svg = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
<svg><text>${'$'}displayText</text></svg>"""
        val fixed = BubbleSvgPolicy.repair(svg)
        BubbleSvgPolicy.validate(fixed)
        assertEquals("""<svg><text>${'$'}{num}</text></svg>""", fixed)
    }

    @Test
    fun repairStripsDoctypeWithInternalSubset() {
        val svg = """<!DOCTYPE svg [<!ENTITY x "y">]><svg><text>${'$'}displayText</text></svg>"""
        val fixed = BubbleSvgPolicy.repair(svg)
        BubbleSvgPolicy.validate(fixed)
        assertEquals("""<svg><text>${'$'}{num}</text></svg>""", fixed)
    }

    @Test
    fun repairMapsCommonNumberPlaceholders() {
        val svg = """<svg><text>${'$'}displayText ${'$'}text ${'$'}number ${'$'}value ${'$'}count ${'$'}num ${'$'}NUM</text></svg>"""
        val fixed = BubbleSvgPolicy.repair(svg)
        assertEquals(
            """<svg><text>${'$'}{num} ${'$'}{num} ${'$'}{num} ${'$'}{num} ${'$'}{num} ${'$'}{num} ${'$'}{num}</text></svg>""",
            fixed
        )
    }

    @Test
    fun repairMapsColorPlaceholder() {
        val svg = """<svg><text fill="${'$'}color" stroke="${'$'}fontColor">${'$'}textColor</text></svg>"""
        assertEquals(
            """<svg><text fill="${'$'}{color}" stroke="${'$'}{color}">${'$'}{color}</text></svg>""",
            BubbleSvgPolicy.repair(svg)
        )
    }

    @Test
    fun repairHandlesBraceForms() {
        val svg = """<svg><text>${'$'}{displayText} {{num}} {{color}}</text></svg>"""
        val fixed = BubbleSvgPolicy.repair(svg)
        assertEquals(
            """<svg><text>${'$'}{num} ${'$'}{num} ${'$'}{color}</text></svg>""",
            fixed
        )
    }

    @Test
    fun repairLeavesUnknownPlaceholdersAndIsIdempotent() {
        val svg = """<svg><text>${'$'}unknown ${'$'}{num} ${'$'}{color}</text></svg>"""
        val fixed = BubbleSvgPolicy.repair(svg)
        assertEquals(svg, fixed)
    }

    @Test
    fun repairKeepsEmbeddedDataImageAndPassesValidate() {
        val svg = """<?xml version="1.0"?>
<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "dtd">
<svg><image href="data:image/webp;base64,UklGRg=="/><text>${'$'}displayText</text></svg>"""
        val fixed = BubbleSvgPolicy.repair(svg)
        BubbleSvgPolicy.validate(fixed)
        assertTrue(fixed.contains("data:image/webp;base64,UklGRg=="))
        assertTrue(fixed.contains("${'$'}{num}"))
        assertFalse(fixed.contains("<!DOCTYPE"))
        assertFalse(fixed.contains("<?xml"))
    }

    @Test
    fun escapedLabelsRoundTripAsTextAndAttributesWithXmlMetacharacters() {
        val labels = listOf(
            """5 < 6 & 7 > 2 "引号" '单引号'""",
            """</text><image href="asset://other"/><text>12""",
            "9+🌅"
        )
        for (label in labels) {
            val document = svgWithLabel(label)
            val text = document.getElementsByTagName("text").item(0)
            assertEquals(label, text.textContent)
            assertEquals(label, text.attributes.getNamedItem("aria-label").nodeValue)
            assertEquals(1, document.getElementsByTagName("text").length)
            assertEquals(0, document.getElementsByTagName("image").length)
        }
    }

    @Test
    fun entityLookingLabelsRemainLiteralAfterOneXmlParse() {
        for (label in listOf("&lt;3 &amp;", "&#65; &#x1F305; &unknown;", "&amp;lt;12", "")) {
            val text = svgWithLabel(label).getElementsByTagName("text").item(0)
            assertEquals(label, text.textContent)
            assertEquals(label, text.attributes.getNamedItem("aria-label").nodeValue)
        }
    }

    private fun svgWithLabel(label: String): org.w3c.dom.Document {
        val escaped = BubbleSvgPolicy.escapeText(label)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><text aria-label="$escaped">$escaped</text></svg>"""
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(svg)))
    }
}
