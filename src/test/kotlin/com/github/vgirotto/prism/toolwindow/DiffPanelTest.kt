package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A file's path is agent-controlled (an AI coding agent can create/rename files with
 * arbitrary names), and [renderEntryHtml] embeds it inside a Swing `<html>` label that is
 * parsed as real markup. These tests guard against that name breaking out of its text-content
 * position, e.g. via `<img src=...>` remote fetches or arbitrary tag/style injection.
 */
class DiffPanelTest {

    @Test
    fun `plain file names render unescaped`() {
        val html = renderEntryHtml("src/Main.kt", isReverted = false)
        assertEquals("<html>Main.kt <font color='gray'> src</font></html>", html)
    }

    @Test
    fun `html special characters in the file name are escaped`() {
        val html = renderEntryHtml("src/<img src=x onerror=alert(1)>.txt", isReverted = false)
        assertFalse(html.contains("<img"), "raw <img> tag must not survive into the rendered HTML: $html")
        assertEquals(
            "<html>&lt;img src=x onerror=alert(1)&gt;.txt <font color='gray'> src</font></html>",
            html,
        )
    }

    @Test
    fun `html special characters in the parent directory are escaped`() {
        val html = renderEntryHtml("<b>evil</b>/file.txt", isReverted = false)
        assertFalse(html.contains("<b>"), "raw tag from the parent directory must not survive: $html")
        assertEquals("<html>file.txt <font color='gray'> &lt;b&gt;evil&lt;/b&gt;</font></html>", html)
    }

    @Test
    fun `ampersands and quotes are escaped`() {
        val html = renderEntryHtml("a & b \"quoted\".kt", isReverted = false)
        assertEquals(
            "<html>a &amp; b &quot;quoted&quot;.kt <font color='gray'></font></html>",
            html,
        )
    }

    @Test
    fun `reverted entries stay escaped and keep the strikethrough markup`() {
        // No "/" in the payload, so it lands entirely in the file name (no parent directory)
        // and the expected string below isn't split by File's own path parsing.
        val html = renderEntryHtml("<script>alert(1)<script>.kt", isReverted = true)
        assertFalse(html.contains("<script>"), "raw <script> tag must not survive: $html")
        assertEquals(
            "<html><s>&lt;script&gt;alert(1)&lt;script&gt;.kt</s> <font color='gray'> (reverted)</font></html>",
            html,
        )
    }

    @Test
    fun `a file name that itself looks like an html document is still just text content`() {
        val html = renderEntryHtml("<html><body>evil</body></html>", isReverted = false)
        assertEquals(1, html.split("<html>").size - 1, "only the wrapper markup should introduce a literal <html> tag: $html")
    }
}
