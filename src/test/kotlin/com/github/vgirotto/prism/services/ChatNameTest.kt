package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    /** True when [display] is [text] up to a grapheme boundary, then the ellipsis. */
    private fun assertClippedWhole(text: String, display: String, maxChars: Int) {
        val head = display.removeSuffix("…")
        assertTrue(text.startsWith(head), display)
        val clusters = graphemes(text)
        val kept = graphemes(head)
        assertEquals(clusters.take(kept.size), kept, "the clip splits a character: $display")
        assertTrue(kept.size <= maxChars, display)
    }

    @Test
    fun `an emoji at the limit is kept whole or dropped whole`() {
        for (emoji in listOf("😀", "👍🏽", "👨‍👩‍👧‍👦", "🇧🇷", "🏳️‍🌈")) {
            val text = "x".repeat(9) + emoji + "y".repeat(10)
            for (limit in 8..12) {
                val shown = ChatName(text).display(maxChars = limit)
                assertClippedWhole(text, shown, limit)
                assertEquals(if (limit >= 10) "x".repeat(9) + emoji + "y".repeat(limit - 10) + "…" else "x".repeat(limit) + "…", shown)
            }
        }
    }

    @Test
    fun `a letter keeps its combining marks`() {
        val text = "Cafe\u0301 e\u0301te\u0301 au bord de la mer"
        for (limit in 3..12) assertClippedWhole(text, ChatName(text).display(maxChars = limit), limit)
        // Every mark on the e belongs to it, however many there are.
        assertEquals("Cafe\u0301\u0301\u0301…", ChatName("Cafe\u0301\u0301\u0301s".repeat(3)).display(maxChars = 4))
    }

    @Test
    fun `a name of emoji counts each one as a single character`() {
        val text = "🚀".repeat(28)
        assertEquals(text, ChatName(text).display(maxChars = 28))
        assertEquals("🚀".repeat(10) + "…", ChatName("🚀".repeat(11)).display(maxChars = 10))
    }
}
