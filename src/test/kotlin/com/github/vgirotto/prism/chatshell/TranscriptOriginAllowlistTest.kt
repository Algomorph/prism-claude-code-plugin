package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the request-handler allowlist (§6.8). This is a plain unit test — no JCEF, no X
 * display — so it runs in the default `./gradlew test` suite, unlike the browser tests.
 *
 * Regression: `JBCefBrowser.loadHTML(html, url)` serves the shell under an internal
 * `file:///jbcefbrowser/<id>#url=<shellUrl>` pseudo-URL, not [ShellHtmlBuilder.shellUrl].
 * An allowlist that only matched the literal shellUrl cancelled the shell's own document
 * load, so `onLoadEnd` never fired and the transcript pane stayed permanently blank.
 */
class TranscriptOriginAllowlistTest {

    @Test
    fun `shell own document url is allowed to load`() {
        // The exact form observed from JBCefBrowser.loadHTML in the IDE.
        assertTrue(TranscriptView.isSameOriginRequest("file:///jbcefbrowser/2133044007#url=https://prism.local/transcript"))
        // Resource requests arrive with the fragment stripped.
        assertTrue(TranscriptView.isSameOriginRequest("file:///jbcefbrowser/2133044007"))
    }

    @Test
    fun `inlined and declared origins are allowed`() {
        assertTrue(TranscriptView.isSameOriginRequest(ShellHtmlBuilder.shellUrl))
        assertTrue(TranscriptView.isSameOriginRequest("data:font/woff2;base64,AAAA"))
        assertTrue(TranscriptView.isSameOriginRequest("about:blank"))
    }

    @Test
    fun `genuinely external requests are blocked`() {
        assertFalse(TranscriptView.isSameOriginRequest("https://evil.example.com/pixel.gif"))
        assertFalse(TranscriptView.isSameOriginRequest("http://prism.local/transcript"))
        assertFalse(TranscriptView.isSameOriginRequest("https://prism.local/other"))
        // A real filesystem read must NOT be mistaken for the in-memory shell document.
        assertFalse(TranscriptView.isSameOriginRequest("file:///etc/passwd"))
    }
}
