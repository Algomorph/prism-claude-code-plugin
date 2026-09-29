package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How a chat's name is clipped for a tab. */
class ChatNameTest {

    @Test
    fun `a short name is shown whole`() {
        assertEquals("Fix the parser", ChatName("Fix the parser").display())
    }

    @Test
    fun `a long name is clipped on a word boundary`() {
        val name = ChatName("Add quick action buttons to the Codex chat window")
        assertEquals("Add quick action buttons to…", name.display(maxChars = 28))
        // The full text stays available for the tooltip.
        assertEquals("Add quick action buttons to the Codex chat window", name.text)
    }

    @Test
    fun `an unbroken name is clipped mid-word rather than emptied`() {
        assertEquals("A".repeat(28) + "…", ChatName("A".repeat(60)).display(maxChars = 28))
    }

    @Test
    fun `clipping does not leave dangling punctuation`() {
        assertEquals("Review this branch…", ChatName("Review this branch, then report back").display(maxChars = 22))
    }
}
