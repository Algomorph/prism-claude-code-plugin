package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellHtmlBuilderTest {

    private val shell = ShellHtmlBuilder.build(mapOf("--prism-bg" to "#101010", "--prism-fg" to "#eee"))

    @Test
    fun `csp locks down default-src and pins each inline script by hash`() {
        assertTrue(shell.contains("Content-Security-Policy"))
        assertTrue(shell.contains("default-src 'none'"))
        assertTrue(shell.contains("object-src 'none'"))
        assertTrue(shell.contains("frame-src 'none'"))
        assertTrue(shell.contains("base-uri 'none'"))
        assertTrue(shell.contains("img-src data:"))
        assertTrue(shell.contains("font-src data:"))
        // Every bundled script must appear as a distinct sha256 in script-src — a blanket
        // allowance would be invalid (design §6.8).
        val hashes = ShellHtmlBuilder.scriptHashes()
        assertTrue(hashes.size >= 4, "expected dompurify+marked+katex+shell scripts")
        for (h in hashes) {
            assertTrue(shell.contains("'$h'"), "CSP must pin $h")
        }
        // No wildcard / unsafe-inline for scripts.
        assertFalse(shell.substringAfter("script-src").substringBefore(";").contains("unsafe-inline"))
        assertFalse(shell.contains("script-src *"))
    }

    @Test
    fun `all libraries and the shell runtime are inlined`() {
        assertTrue(shell.contains("DOMPurify")) // from purify.min.js banner
        assertTrue(shell.contains("__prismApplyDelta")) // from shell.js
        assertTrue(shell.contains("marked")) // marked.umd.js
        assertTrue(shell.contains("katex")) // katex.min.js/css
        assertTrue(shell.contains("id=\"prism-content\""))
    }

    @Test
    fun `katex fonts are inlined as data uris - no relative font url survives`() {
        assertTrue(shell.contains("data:font/woff2;base64,"))
        // The CSP has no font-src other than data:, so any leftover relative url() would
        // silently fail to load; assert none remain.
        assertFalse(Regex("""url\(\s*(?:fonts/)?[A-Za-z0-9_\-]+\.woff2""").containsMatchIn(shell))
    }

    @Test
    fun `theme variables are injected into root`() {
        assertTrue(shell.contains("--prism-bg:#101010;"))
        assertTrue(shell.contains("--prism-fg:#eee;"))
    }

    @Test
    fun `script hashes are stable for identical content`() {
        assertTrue(ShellHtmlBuilder.scriptHashes() == ShellHtmlBuilder.scriptHashes())
    }
}
