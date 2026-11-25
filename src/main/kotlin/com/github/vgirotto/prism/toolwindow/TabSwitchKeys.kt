package com.github.vgirotto.prism.toolwindow

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.ui.content.ContentManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/** A step through a tool window's tabs. */
internal enum class TabStep(val actionId: String) {
    NEXT(IdeActions.ACTION_NEXT_TAB),
    PREVIOUS(IdeActions.ACTION_PREVIOUS_TAB);

    fun selectIn(manager: ContentManager) {
        if (manager.contentCount < 2) return
        if (this == NEXT) manager.selectNextContent() else manager.selectPreviousContent()
    }
}

/**
 * The keymap's Window > Active Tool Window > Select Next/Previous Tab keys, for an agent terminal.
 *
 * With "Override IDE shortcuts" on, the terminal sends these keys to the agent, so they are
 * claimed on the terminal path; with it off, the IDE runs the actions itself. The keymap is read
 * on every press, so a rebinding applies at once. Chords are not matched.
 */
internal class TabSwitchKeys(private val keymapStrokes: (String) -> List<KeyStroke> = ::activeKeymapStrokes) {

    fun strokes(step: TabStep): List<KeyStroke> = keymapStrokes(step.actionId)

    fun stepFor(stroke: KeyStroke): TabStep? = TabStep.entries.firstOrNull { stroke in strokes(it) }

    /**
     * The tab keys that Claude Code and Codex also use: arrow keys with at most one modifier move
     * their cursor, by character, by word (Codex's Alt or Ctrl+Left/Right) and through history.
     */
    fun agentKeys(): List<KeyStroke> = TabStep.entries.flatMap(::strokes).filter(::isAgentCursorKey)

    private fun isAgentCursorKey(stroke: KeyStroke): Boolean =
        stroke.keyCode in ARROWS && Integer.bitCount(stroke.modifiers and MODIFIERS) <= 1

    private companion object {
        val ARROWS = setOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN)
        const val MODIFIERS = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK or InputEvent.ALT_GRAPH_DOWN_MASK

        fun activeKeymapStrokes(actionId: String): List<KeyStroke> =
            KeymapManager.getInstance().activeKeymap.getShortcuts(actionId)
                .filterIsInstance<KeyboardShortcut>()
                .filter { it.secondKeyStroke == null }
                .map { it.firstKeyStroke }
    }
}
