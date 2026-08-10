package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * [codexSubmitChunks] decides how a [AgentProcessManager.sendText] payload is
 * delivered to Codex: submitting inputs are staged as `[body, "\r"]` so the
 * Enter keystroke arrives separately (a text+newline burst is otherwise
 * swallowed as a paste), while non-submitting inputs are written verbatim.
 */
class CodexSubmitChunksTest {

    @Test
    fun `slash command with carriage return is staged`() {
        assertEquals(listOf("/resume", "\r"), codexSubmitChunks("/resume\r"))
    }

    @Test
    fun `prompt with newline is staged`() {
        assertEquals(listOf("ask something", "\r"), codexSubmitChunks("ask something\n"))
    }

    @Test
    fun `multiline prompt keeps its interior newlines and submits once`() {
        assertEquals(listOf("line one\nline two", "\r"), codexSubmitChunks("line one\nline two\n"))
    }

    @Test
    fun `insert-only text without a terminator is written verbatim`() {
        // e.g. a "@folder" mention or a selection reference ending in a space.
        assertNull(codexSubmitChunks("@src/main "))
        assertNull(codexSubmitChunks("Foo.kt:10-20 "))
    }

    @Test
    fun `bare control keys and escape sequences are written verbatim`() {
        assertNull(codexSubmitChunks("\u001B"))                       // ESC
        assertNull(codexSubmitChunks("\u0016"))                       // Ctrl-V
        assertNull(codexSubmitChunks("\u001B[13;2u"))                 // CSI key sequence
        assertNull(codexSubmitChunks("\u001B[200~paste\u001B[201~"))  // bracketed paste
    }

    @Test
    fun `empty and whitespace-only terminators are written verbatim`() {
        assertNull(codexSubmitChunks(""))
        assertNull(codexSubmitChunks("\n"))
        assertNull(codexSubmitChunks("   \n"))
    }
}
