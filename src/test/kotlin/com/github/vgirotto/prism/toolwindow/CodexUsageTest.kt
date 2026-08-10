package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Codex has no `/cost`; its `/usage` command takes an optional view argument
 * (`daily`/`weekly`/`cumulative`) that jumps straight to that token-activity
 * view. The Cost button exposes these as a dropdown. See [CodexUsage].
 */
class CodexUsageTest {

    @Test
    fun `command builds a submitting usage view command`() {
        assertEquals("/usage daily\r", CodexUsage.command("daily"))
        assertEquals("/usage weekly\r", CodexUsage.command("weekly"))
        assertEquals("/usage cumulative\r", CodexUsage.command("cumulative"))
    }

    @Test
    fun `every view ends in a carriage return so sendText stages the submit`() {
        // A trailing CR is what codexSubmitChunks keys on to stage the Enter
        // keystroke separately; without it Codex would swallow the paste.
        for ((view, _) in CodexUsage.VIEWS) {
            assertTrue(CodexUsage.command(view).endsWith("\r"))
        }
    }

    @Test
    fun `exposes exactly the three token-activity views with label keys`() {
        assertEquals(
            listOf("daily", "weekly", "cumulative"),
            CodexUsage.VIEWS.map { it.first },
        )
        // Each view carries a bundle key for its menu-item description.
        for ((view, labelKey) in CodexUsage.VIEWS) {
            assertEquals("toolbar.cost.codex.$view", labelKey)
        }
    }
}
