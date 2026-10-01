package com.github.vgirotto.prism.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.JBTerminalWidget
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Prism's own keys in an agent terminal (Ctrl+V paste, Shift+Enter newline, the CLI shortcuts).
 *
 * Which path a key takes depends on the IDE terminal setting "Override IDE shortcuts", which the
 * IDE terminal's settings provider applies to this terminal too (on by default):
 *  - **Off:** the IDE's action system sees the key first, and a shortcut registered on the
 *    terminal component runs it.
 *  - **On:** the terminal panel takes every key before the action system does. Its own actions
 *    use the keys of the IDE keymap: in recent versions (2026.2), Terminal > Paste includes
 *    Ctrl+V, and is enabled only when the clipboard holds text. With an image on the clipboard,
 *    it lets the key go to the PTY as a bare ^V, which Claude Code on Linux cannot turn into an
 *    image without `xclip` or `wl-paste`.
 *
 * So each key is bound on both paths. On the terminal path it is claimed by a pre-key handler,
 * which runs before the panel's own actions, whatever the keymap binds. The first path to handle
 * a key consumes it, and neither runs a consumed key, so a key never runs twice.
 *
 * Escape is claimed by its own pre-key handler, which also keeps the panel's Escape listener from
 * moving focus to the editor.
 */
internal class TerminalKeyBindings(private val widget: JBTerminalWidget, private val parent: Disposable) {

    private val claims = KeyClaims().also { widget.terminalPanel.addPreKeyEventHandler(it::handle) }

    fun bind(keyStroke: KeyStroke, handler: () -> Unit) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = handler()
        }.registerCustomShortcutSet(CustomShortcutSet(keyStroke), widget.component, parent)
        claims.bind(keyStroke, handler)
    }

    /** The terminal-path half: claims bound key presses before the terminal panel handles them. */
    internal class KeyClaims {
        private val handlers = mutableMapOf<KeyStroke, () -> Unit>()

        /** Set while a claimed key is down, so its typed character (^V, Enter) stays out of the PTY. */
        private var swallowTyped = false

        fun bind(keyStroke: KeyStroke, handler: () -> Unit) {
            handlers[keyStroke] = handler
        }

        fun handle(event: KeyEvent) {
            when (event.id) {
                KeyEvent.KEY_PRESSED -> {
                    swallowTyped = false
                    if (event.isConsumed) return
                    val handler = handlers[KeyStroke.getKeyStrokeForEvent(event)] ?: return
                    event.consume()
                    swallowTyped = true
                    handler()
                }
                KeyEvent.KEY_TYPED -> if (swallowTyped) event.consume()
                KeyEvent.KEY_RELEASED -> swallowTyped = false
            }
        }
    }
}
