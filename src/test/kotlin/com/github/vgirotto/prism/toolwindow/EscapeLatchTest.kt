package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class EscapeLatchTest {

    private var nanos = 0L
    private val latch = EscapeLatch { nanos }

    @Test
    fun `a press with no popup open reaches the agent`() {
        assertFalse(latch.onPress(popupActive = false))
    }

    @Test
    fun `the press a popup answers is passed through so the popup can close`() {
        assertFalse(latch.onPress(popupActive = true))
    }

    @Test
    fun `an auto-repeat press after the popup closed is dropped`() {
        latch.onPress(popupActive = true)

        // Same physical keypress, repeating; the popup is gone by now.
        assertTrue(latch.onPress(popupActive = false))
        assertTrue(latch.onPress(popupActive = false))
    }

    @Test
    fun `releasing the key hands Escape back to the agent`() {
        latch.onPress(popupActive = true)
        assertTrue(latch.onPress(popupActive = false))

        latch.onRelease()

        assertFalse(latch.onPress(popupActive = false))
    }

    @Test
    fun `key typed follows the same latch as the press`() {
        assertFalse(latch.isClosed())

        latch.onPress(popupActive = true)
        assertTrue(latch.isClosed())

        latch.onRelease()
        assertFalse(latch.isClosed())
    }

    @Test
    fun `a release lost to another window cannot silence Escape for the session`() {
        latch.onPress(popupActive = true)
        nanos += EscapeLatch.EXPIRY_NANOS + TimeUnit.MILLISECONDS.toNanos(1)

        // No release ever arrived, so only the expiry reopens the latch.
        assertFalse(latch.onPress(popupActive = false))
    }

    @Test
    fun `the latch holds right up to the expiry`() {
        latch.onPress(popupActive = true)
        nanos += EscapeLatch.EXPIRY_NANOS

        assertTrue(latch.onPress(popupActive = false))
    }
}
