package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringReader
import javax.swing.JLabel
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.ElementIterator
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

/** A chat name is the agent's text: the tab must show it, never render it. */
class ChatNameTextTest {

    private val markup = listOf(
        "<html><b>bold</b>",
        "<HTML><img src='https://example.com/x.png'>",
        "<html><img src=file:///etc/passwd>",
        "<Html><a href='https://example.com'>link</a>",
    )

    @Test
    fun `a label that starts with html is not rendered as HTML`() {
        for (name in markup) {
            val label = ChatNameText.label(name)
            assertFalse(BasicHTML.isHTMLString(label), name)
            assertNull(JLabel(label).getClientProperty(BasicHTML.propertyKey), name)
            assertTrue(label.endsWith(name), "the whole name stays visible: $name")
        }
    }

    @Test
    fun `a label without leading html is left as it is`() {
        for (name in listOf("Fix the <b> tag", "a < b > c", "Chat #3", "")) {
            assertEquals(name, ChatNameText.label(name))
        }
    }

    @Test
    fun `a tooltip shows markup and image tags as text`() {
        for (name in markup + listOf("<img src='https://example.com/x.png'>", "Tom & Jerry <script>x</script>")) {
            val document = render(ChatNameText.tooltip("Codex — $name"))
            assertEquals("Codex — $name", document.getText(0, document.length).trim(), name)
            assertFalse(hasElement(document, HTML.Tag.IMG), name)
            assertFalse(hasElement(document, HTML.Tag.A), name)
            assertFalse(hasElement(document, HTML.Tag.B), name)
        }
    }

    @Test
    fun `a tooltip is HTML for every renderer, so the escapes never show`() {
        assertTrue(BasicHTML.isHTMLString(ChatNameText.tooltip("Claude Code")))
    }

    @Test
    fun `a notice that a chat ended shows its name as text`() {
        for (name in markup + listOf("<img src='https://example.com/x.png'>", "Tom & Jerry")) {
            val document = render(ChatNameText.sessionEndedNotice(name))
            assertTrue(document.getText(0, document.length).contains("Session '$name' ended"), name)
            assertFalse(hasElement(document, HTML.Tag.IMG), name)
            assertFalse(hasElement(document, HTML.Tag.A), name)
            assertFalse(hasElement(document, HTML.Tag.B), name)
        }
    }

    private fun render(html: String): HTMLDocument {
        val kit = HTMLEditorKit()
        val document = kit.createDefaultDocument() as HTMLDocument
        kit.read(StringReader(html), document, 0)
        return document
    }

    private fun hasElement(document: HTMLDocument, tag: HTML.Tag): Boolean {
        val elements = ElementIterator(document)
        while (true) {
            val element = elements.next() ?: return false
            if (element.attributes.getAttribute(javax.swing.text.StyleConstants.NameAttribute) == tag) return true
        }
    }
}
