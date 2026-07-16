package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Row-classification + spinner-debounce for the auto-follow divider (design §6.6, R11).
 * Fixtures are hand-authored terminal frames until the Group 0/HITL grid-diff recordings
 * tune the constants.
 */
class LiveRegionTrackerTest {

    private val tracker = ClaudeLiveRegionTracker(minRows = 3)

    // committed scrollback, a blank gap, then the live input box.
    private val idleFrame = listOf(
        "Claude: done.", "", "╭─────────────╮", "│ > ▏         │", "╰─────────────╯", "", ""
    )

    private val menuFrame = listOf(
        "● Bash(npm test)", "",
        "Do you want to proceed?", "❯ 1. Yes", "  2. Yes, and don't ask again", "  3. No",
    )

    private val spinnerFrameA = listOf("committed", "", "⠋ Working…", "> ▏")
    private val spinnerFrameB = listOf("committed", "", "⠙ Working…", "> ▏")

    @Test
    fun `idle frame classifies the input box as the live region`() {
        // trailing block = the 3 box rows (blank rows dropped, gap above stops the walk).
        assertEquals(3, tracker.liveRegionRows(idleFrame))
    }

    @Test
    fun `menu frame classifies the whole prompt plus options`() {
        // "Do you want to proceed?" + 3 option lines = 4 contiguous rows above no gap.
        assertEquals(4, tracker.liveRegionRows(menuFrame))
    }

    @Test
    fun `empty frame falls back to the minimum`() {
        assertEquals(3, tracker.liveRegionRows(emptyList()))
        assertEquals(3, tracker.liveRegionRows(listOf("", "")))
    }

    @Test
    fun `settle filter ignores single-row spinner churn but follows shape changes`() {
        val filter = LiveRegionSettleFilter(threshold = 1)
        assertEquals(4, filter.offer(4))       // initial
        assertEquals(4, filter.offer(5))       // +1 spinner flap -> ignored
        assertEquals(4, filter.offer(4))       // back -> still held
        assertEquals(12, filter.offer(12))     // shape change -> followed
        assertEquals(12, filter.offer(11))     // -1 flap -> ignored
    }

    @Test
    fun `spinner animation does not move the divider`() {
        val filter = LiveRegionSettleFilter(threshold = 1)
        val a = filter.offer(tracker.liveRegionRows(spinnerFrameA))
        val b = filter.offer(tracker.liveRegionRows(spinnerFrameB))
        assertEquals(a, b, "spinner frames of equal height keep the divider steady")
    }
}
