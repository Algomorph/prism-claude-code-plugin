package com.github.vgirotto.prism.changelog

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale

class ChangelogResourcesTest {
    @Test
    fun `all packaged changelogs render in their own language without fallback`() {
        val content = ChangelogContent()

        for (language in listOf("en", "pt", "es")) {
            val result = content.load(Locale.forLanguageTag(language)) as ChangelogResult.Rendered

            assertEquals(language, result.language)
            assertFalse(result.usedFallback, language)
            assertTrue(result.markdown.startsWith("# "), language)
            assertTrue(result.html.contains("<h2>[1.4.0] — 2026-10-03</h2>"), language)
            assertTrue(result.html.contains("<h2>[1.0.0] — 2026-03-26</h2>"), language)
            assertFalse(result.markdown.contains('\uFFFD'), "Invalid UTF-8 in $language")
        }

        val portuguese = content.load(Locale.forLanguageTag("pt")) as ChangelogResult.Rendered
        val spanish = content.load(Locale.forLanguageTag("es")) as ChangelogResult.Rendered
        assertTrue(portuguese.markdown.contains('ç'))
        assertTrue(spanish.markdown.contains('ñ'))
    }

    @Test
    fun `translations preserve release order dates categories and every list entry`() {
        val english = structure(read("en"))

        for (language in listOf("pt", "es")) {
            val translated = structure(read(language))

            assertEquals(english.sections, translated.sections, "Release/category/list structure differs in $language")
            assertEquals(english.codeLiterals, translated.codeLiterals, "Code identifiers differ in $language")
            assertEquals(english.linkDestinations, translated.linkDestinations, "Link destinations differ in $language")
        }
    }

    private fun read(language: String): String =
        (ChangelogContent().load(Locale.forLanguageTag(language)) as ChangelogResult.Rendered).markdown

    private fun structure(markdown: String): Structure {
        val document = Parser.builder().build().parse(markdown)
        val sections = mutableListOf<String>()
        val codeLiterals = mutableListOf<String>()
        val linkDestinations = mutableListOf<String>()

        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val label = headingText(heading)
                when (heading.level) {
                    2 -> sections.add("release:$label")
                    3 -> sections.add("category:${canonicalCategory(label)}")
                }
                visitChildren(heading)
            }

            override fun visit(listItem: ListItem) {
                sections.add("entry")
                visitChildren(listItem)
            }

            override fun visit(code: Code) {
                codeLiterals.add(code.literal)
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                codeLiterals.add("${fencedCodeBlock.info}\n${fencedCodeBlock.literal}")
            }

            override fun visit(link: Link) {
                linkDestinations.add(link.destination)
                visitChildren(link)
            }
        })

        return Structure(sections, codeLiterals, linkDestinations)
    }

    private fun headingText(heading: Node): String {
        val text = StringBuilder()
        heading.accept(object : AbstractVisitor() {
            override fun visit(node: Text) {
                text.append(node.literal)
            }
        })
        return text.toString()
    }

    private fun canonicalCategory(label: String): String = when (label) {
        "Added", "Adicionado", "Añadido" -> "Added"
        "Changed", "Alterado", "Cambiado" -> "Changed"
        "Fixed", "Corrigido", "Corregido" -> "Fixed"
        "Technical", "Técnico" -> "Technical"
        else -> label
    }

    private data class Structure(
        val sections: List<String>,
        val codeLiterals: List<String>,
        val linkDestinations: List<String>,
    )
}
