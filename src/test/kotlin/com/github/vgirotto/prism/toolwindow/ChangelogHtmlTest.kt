package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.StringReader
import javax.swing.text.AttributeSet
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.InlineView

class ChangelogHtmlTest {

    @Test
    fun `the themed HTML kit preserves bold italic and code fonts`() {
        val kit = createChangelogEditorKit(Color.BLACK, Color.BLUE)
        val document = kit.createDefaultDocument() as HTMLDocument
        kit.read(StringReader("<html><body><p><strong>Bold</strong> <em>Italic</em> <code>Code</code></p></body></html>"), document, 0)

        assertTrue(document.styleSheet.getFont(attributesFor(document, "Bold")).isBold)
        assertTrue(document.styleSheet.getFont(attributesFor(document, "Italic")).isItalic)
        assertEquals("Monospaced", document.styleSheet.getFont(attributesFor(document, "Code")).family)
    }

    @Test
    fun `theme colors remain local to each changelog document`() {
        val light = createChangelogEditorKit(Color.BLACK, Color.BLUE)
        val dark = createChangelogEditorKit(Color.WHITE, Color.CYAN)

        assertNotSame(light.styleSheet, dark.styleSheet)
        assertEquals(Color.BLUE, light.styleSheet.getForeground(light.styleSheet.getRule("a")))
        assertEquals(Color.CYAN, dark.styleSheet.getForeground(dark.styleSheet.getRule("a")))
        assertEquals(Color.BLACK, light.styleSheet.getForeground(light.styleSheet.getRule("body")))
        assertEquals(Color.WHITE, dark.styleSheet.getForeground(dark.styleSheet.getRule("body")))
    }

    private fun attributesFor(document: HTMLDocument, text: String): AttributeSet {
        val offset = document.getText(0, document.length).indexOf(text)
        assertTrue(offset >= 0, "Missing text: $text")
        return document.styleSheet.getViewAttributes(InlineView(document.getCharacterElement(offset)))
    }
}
