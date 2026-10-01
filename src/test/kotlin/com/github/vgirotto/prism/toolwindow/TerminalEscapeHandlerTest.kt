package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel

class TerminalEscapeHandlerTest {

    private val terminal = JPanel()
    private var sends = 0

    private fun press(modifiers: Int = 0, keyCode: Int = KeyEvent.VK_ESCAPE) =
        KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)

    private fun handle(event: KeyEvent, blocked: Boolean = false) {
        handleTerminalEscape(event, blocked) { sends++ }
    }

    @Test
    fun `plain Escape is consumed before the native focus listener and sent once`() {
        val event = press()

        handle(event)
        handle(event)

        assertTrue(event.isConsumed)
        assertEquals(1, sends)
    }

    @Test
    fun `typed Escape after a press does not produce a second send`() {
        handle(press())
        val typed = KeyEvent(terminal, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, '\u001B')

        handle(typed)

        assertTrue(typed.isConsumed)
        assertEquals(1, sends)
    }

    @Test
    fun `popup or held-key latch blocks forwarding and native focus switching`() {
        val event = press()

        handle(event, blocked = true)

        assertTrue(event.isConsumed)
        assertEquals(0, sends)
    }

    @Test
    fun `already consumed Escape is not forwarded`() {
        val event = press()
        event.consume()

        handle(event)

        assertEquals(0, sends)
    }

    @Test
    fun `modified Escape and other keys retain their normal handling`() {
        val modified = press(InputEvent.ALT_DOWN_MASK)
        val other = press(keyCode = KeyEvent.VK_ENTER)

        handle(modified)
        handle(other)

        assertFalse(modified.isConsumed)
        assertFalse(other.isConsumed)
        assertEquals(0, sends)
    }

    @Test
    fun `release passes through so the held-key latch can reset`() {
        val release = KeyEvent(terminal, KeyEvent.KEY_RELEASED, 0, 0, KeyEvent.VK_ESCAPE, '\u001B')

        handle(release)

        assertFalse(release.isConsumed)
        assertEquals(0, sends)
    }
}
