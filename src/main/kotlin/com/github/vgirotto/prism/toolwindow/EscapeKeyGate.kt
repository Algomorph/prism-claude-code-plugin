package com.github.vgirotto.prism.toolwindow

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.AWTEvent
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Keeps a *held* Escape from reaching the agent after a popup has already answered it:
 * auto-repeat delivers a run of presses, the first closes the popup, and the rest arrive
 * with no popup left to claim them. A grace period cannot separate the two, since the
 * repeat delay is a per-user OS setting.
 *
 * Events are dropped here rather than by disabling the forwarding action because an
 * unconsumed keystroke still reaches the PTY through the terminal widget's own key
 * handling. Only events headed for [terminal] are dropped — silencing Escape for the rest
 * of the IDE is none of this gate's business.
 */
internal class EscapeKeyGate(
    private val terminal: JComponent,
    parent: Disposable,
    private val latch: EscapeLatch = EscapeLatch(),
) {

    init {
        IdeEventQueue.getInstance().addDispatcher(
            object : IdeEventQueue.EventDispatcher {
                override fun dispatch(event: AWTEvent): Boolean = dispatchEscape(event)
            },
            parent,
        )
    }

    /** @return true to swallow [event] so neither the forwarding action nor the PTY sees it. */
    private fun dispatchEscape(event: AWTEvent): Boolean {
        if (event !is KeyEvent || !event.isEscape()) return false
        return when (event.id) {
            // A popup anywhere closes the latch, so onPress runs first for its side effect.
            KeyEvent.KEY_PRESSED ->
                latch.onPress(JBPopupFactory.getInstance().isPopupActive) && isBoundForTerminal(event)

            KeyEvent.KEY_TYPED -> latch.isClosed() && isBoundForTerminal(event)

            KeyEvent.KEY_RELEASED -> {
                latch.onRelease()
                false
            }

            else -> false
        }
    }

    private fun isBoundForTerminal(event: KeyEvent): Boolean {
        val target = event.component ?: return false
        return SwingUtilities.isDescendingFrom(target, terminal)
    }

    // KEY_TYPED carries no key code, only the character.
    private fun KeyEvent.isEscape(): Boolean =
        keyCode == KeyEvent.VK_ESCAPE || (id == KeyEvent.KEY_TYPED && keyChar == ESCAPE_CHAR)

    private companion object {
        const val ESCAPE_CHAR = '\u001B'
    }
}

/** Whether Escape is currently spoken for by a popup rather than by the agent. */
internal class EscapeLatch(private val nowNanos: () -> Long = System::nanoTime) {

    private var closedAtNanos: Long? = null

    /**
     * @param popupActive whether a popup was open when the press arrived.
     * @return true if the press should be dropped instead of reaching the agent.
     */
    fun onPress(popupActive: Boolean): Boolean {
        if (popupActive) {
            // The popup gets this press; the auto-repeats behind it are the problem.
            closedAtNanos = nowNanos()
            return false
        }
        return isClosed()
    }

    fun onRelease() {
        closedAtNanos = null
    }

    /** Expires on its own, since a release delivered to another window never reaches us. */
    fun isClosed(): Boolean {
        val closedAt = closedAtNanos ?: return false
        if (nowNanos() - closedAt > EXPIRY_NANOS) {
            closedAtNanos = null
            return false
        }
        return true
    }

    companion object {
        val EXPIRY_NANOS: Long = TimeUnit.SECONDS.toNanos(5)
    }
}
