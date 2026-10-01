package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * The terminal-path half of [TerminalKeyBindings], fed the key events the terminal panel passes
 * to its pre-key handlers when "Override IDE shortcuts" is on.
 */
class TerminalKeyBindingsTest {

    private val ctrlV = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
    private val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)

    private fun key(id: Int, keyCode: Int, modifiers: Int, char: Char = KeyEvent.CHAR_UNDEFINED) =
        KeyEvent(JPanel(), id, 0L, modifiers, if (id == KeyEvent.KEY_TYPED) KeyEvent.VK_UNDEFINED else keyCode, char)

    private fun press(keyCode: Int, modifiers: Int) = key(KeyEvent.KEY_PRESSED, keyCode, modifiers)
    private fun typed(char: Char, modifiers: Int) = key(KeyEvent.KEY_TYPED, 0, modifiers, char)
    private fun release(keyCode: Int, modifiers: Int) = key(KeyEvent.KEY_RELEASED, keyCode, modifiers)

    @Test
    fun `a bound key runs its handler and is consumed`() {
        val ran = mutableListOf<String>()
        val claims = TerminalKeyBindings.KeyClaims().apply {
            bind(ctrlV) { ran += "paste" }
            bind(shiftEnter) { ran += "newline" }
        }
        val paste = press(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
        val newline = press(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)

        claims.handle(paste)
        claims.handle(newline)

        assertTrue(paste.isConsumed && newline.isConsumed)
        assertEquals(listOf("paste", "newline"), ran)
    }

    @Test
    fun `the typed character of a claimed key does not reach the PTY`() {
        val claims = TerminalKeyBindings.KeyClaims().apply { bind(ctrlV) {} }
        claims.handle(press(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))
        val ctrlVChar = typed('\u0016', InputEvent.CTRL_DOWN_MASK)

        claims.handle(ctrlVChar)

        assertTrue(ctrlVChar.isConsumed)
    }

    @Test
    fun `other keys, and the characters they type, pass through`() {
        var ran = false
        val claims = TerminalKeyBindings.KeyClaims().apply { bind(ctrlV) { ran = true } }
        claims.handle(press(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))
        claims.handle(release(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))

        val plainV = press(KeyEvent.VK_V, 0)
        val v = typed('v', 0)
        claims.handle(plainV)
        claims.handle(v)

        assertFalse(plainV.isConsumed || v.isConsumed)
        assertTrue(ran)
    }

    @Test
    fun `a key the IDE shortcut already handled is not run again`() {
        var runs = 0
        val claims = TerminalKeyBindings.KeyClaims().apply { bind(ctrlV) { runs++ } }
        val handled = press(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK).apply { consume() }

        claims.handle(handled)

        assertEquals(0, runs)
    }
}
