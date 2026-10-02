package com.github.vgirotto.prism.toolwindow

import java.awt.event.KeyEvent

/** Claims plain Escape before the terminal platform's focus-to-editor listener. */
internal fun handleTerminalEscape(event: KeyEvent, blocked: Boolean, sendEscape: () -> Unit) {
    if (event.isConsumed || event.modifiersEx != 0) return

    val escapePressed = event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE
    val escapeTyped = event.id == KeyEvent.KEY_TYPED && event.keyChar == '\u001B'
    if (!escapePressed && !escapeTyped) return

    event.consume()

    // KEY_TYPED belongs to the same press; forwarding it would send Escape twice.
    if (escapePressed && !blocked) sendEscape()
}
