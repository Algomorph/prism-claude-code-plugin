package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The name-shaping contract: what counts as a usable name, and how one is clipped for a tab.
 */
class ChatNameTest {

    @Test
    fun `whitespace is collapsed to one line`() {
        assertEquals(
            "Add quick action buttons",
            ChatName.clean("  Add quick\n\taction   buttons \n"),
        )
    }

    @Test
    fun `blank and absent text yield no name`() {
        assertNull(ChatName.clean(null))
        assertNull(ChatName.clean(""))
        assertNull(ChatName.clean("   \n\t "))
    }

    @Test
    fun `machine-composed envelopes are rejected rather than shown as titles`() {
        // Both CLIs log these as ordinary messages; naming a chat after one is worse than `Chat #N`.
        assertNull(ChatName.clean("<local-command-stdout>compacted</local-command-stdout>"))
        assertNull(ChatName.clean("<system-reminder>Do the thing</system-reminder>"))
        assertNull(ChatName.clean("  <user_instructions>...</user_instructions>"))
    }

    @Test
    fun `of pairs the cleaned text with its origin`() {
        val name = ChatName.of("  Review\nthe branch ", ChatName.Origin.USER_TITLE)
        assertEquals(ChatName("Review the branch", ChatName.Origin.USER_TITLE), name)
        assertNull(ChatName.of("   ", ChatName.Origin.USER_TITLE))
    }

    @Test
    fun `origins rank from weakest to strongest`() {
        assertTrue(ChatName.Origin.FIRST_MESSAGE < ChatName.Origin.AGENT_TITLE)
        assertTrue(ChatName.Origin.AGENT_TITLE < ChatName.Origin.USER_TITLE)
    }

    @Test
    fun `a short name is shown whole`() {
        val name = ChatName("Fix the parser", ChatName.Origin.AGENT_TITLE)
        assertEquals("Fix the parser", name.display())
    }

    @Test
    fun `a long name is clipped on a word boundary`() {
        val name = ChatName("Add quick action buttons to the Codex chat window", ChatName.Origin.AGENT_TITLE)
        val shown = name.display(maxChars = 28)
        assertEquals("Add quick action buttons to…", shown)
        // The full text stays available for the tooltip.
        assertEquals("Add quick action buttons to the Codex chat window", name.text)
    }

    @Test
    fun `an unbroken name is clipped mid-word rather than emptied`() {
        val name = ChatName("A".repeat(60), ChatName.Origin.FIRST_MESSAGE)
        assertEquals("A".repeat(28) + "…", name.display(maxChars = 28))
    }

    @Test
    fun `clipping does not leave dangling punctuation`() {
        val name = ChatName("Review this branch, then report back", ChatName.Origin.FIRST_MESSAGE)
        assertEquals("Review this branch…", name.display(maxChars = 22))
    }
}
